package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Android emulator pool. Up to five headless AVDs can be managed concurrently.
 *
 * Legacy callers still see [serial], [avdName], [systemImage], [start], [stop] and [status] for
 * the default slot so existing instrumented-test and sidebar code keeps working.
 */
class EmulatorService(
    private val adb: AdbService,
    val avdName: String = "vibe_pixel_api35",
    // v1.162.0 — google_apis(하드웨어 렌더링 O)로 교체. 이전 google_atd 는 스크린샷/렌더링을
    // 비활성화한 Automated Test Device 이미지라 adb screencap 이 검은 화면만 반환했다.
    val systemImage: String = "system-images;android-35;google_apis;x86_64",
    private val deviceProfile: String = "pixel_6",
    private val workspace: WorkspacePath? = null,
) {
    val serial: String = "emulator-5554"
    val maxEmulators: Int = MAX_EMULATORS

    data class EmulatorProfile(
        val id: String,
        val label: String,
        val kind: String,
        val avdName: String,
        val systemImage: String,
        val deviceProfile: String,
    )

    data class EmulatorSlot(
        val id: String,
        val index: Int,
        val consolePort: Int,
        val serial: String,
        val profile: EmulatorProfile,
    )

    data class StartResult(val ok: Boolean, val message: String)

    data class Lease(
        val emulatorId: String,
        val serial: String,
        val projectId: String,
        val acquiredAtIso: String,
        val lastSeenAtIso: String,
        val expiresAtIso: String,
        val mode: String,
    )

    data class Status(
        val available: Boolean,
        val running: Boolean,
        val booted: Boolean,
        val serial: String,
        val startedAtIso: String?,
        val external: Boolean = false,
        val id: String = "phone-1",
        val label: String = "Phone",
        val kind: String = "phone",
        val avdName: String = "vibe_pixel_api35",
        val systemImage: String = "system-images;android-35;google_apis;x86_64",
        val consolePort: Int = 5554,
        val leasedByProjectId: String? = null,
        val leaseExpiresAtIso: String? = null,
    )

    data class PoolStatus(
        val available: Boolean,
        val max: Int,
        val running: Int,
        val booted: Int,
        val slots: List<Status>,
    )

    private data class ManagedProcess(val process: Process, val slot: EmulatorSlot)

    private data class MutableLease(
        val emulatorId: String,
        val serial: String,
        val projectId: String,
        val acquiredAt: Instant,
        var lastSeenAt: Instant,
        var expiresAt: Instant,
        val mode: String,
    ) {
        fun snapshot(): Lease = Lease(
            emulatorId = emulatorId,
            serial = serial,
            projectId = projectId,
            acquiredAtIso = acquiredAt.toString(),
            lastSeenAtIso = lastSeenAt.toString(),
            expiresAtIso = expiresAt.toString(),
            mode = mode,
        )
    }

    private data class BoolCache(var value: Boolean = false, var checkedAt: Long = 0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val startedAt = ConcurrentHashMap<String, Instant>()
    private val logTails = ConcurrentHashMap<String, java.util.ArrayDeque<String>>()
    private val bootedCache = ConcurrentHashMap<String, BoolCache>()
    private val serialPresentCache = ConcurrentHashMap<String, BoolCache>()
    private val leases = ConcurrentHashMap<String, MutableLease>()

    val slots: List<EmulatorSlot> = buildSlots(avdName, systemImage, deviceProfile)

    init {
        loadLeases()
    }

    private fun defaultSlot(): EmulatorSlot = slots.first()

    // SDK paths
    private fun androidHome(): Path? =
        (System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null })?.let { Path.of(it) }

    private fun emulatorBin(): Path? =
        androidHome()?.resolve("emulator/emulator")?.takeIf { Files.isExecutable(it) }

    private fun avdmanagerBin(): Path? =
        androidHome()?.resolve("cmdline-tools/latest/bin/avdmanager")?.takeIf { Files.exists(it) }

    private fun systemImageDir(systemImage: String = this.systemImage): Path? =
        androidHome()?.resolve(systemImage.split(';').joinToString("/"))

    // google_apis(렌더링 O) 우선. 구 설치에 google_atd 만 남아 있으면 최소 동작용으로 폴백
    // (스크린샷은 안 되지만 logcat/설치는 가능) — 새 이미지를 받으면 자동으로 google_apis 로 승격된다.
    private fun effectiveSystemImage(slot: EmulatorSlot): String? =
        slot.profile.systemImage.takeIf { systemImageDir(it)?.let(Files::isDirectory) == true }
            ?: FALLBACK_ATD_IMAGE.takeIf { systemImageDir(it)?.let(Files::isDirectory) == true }

    private fun avdHome(): Path {
        System.getenv("ANDROID_AVD_HOME")?.ifBlank { null }?.let { return Path.of(it) }
        val home = System.getenv("HOME")?.ifBlank { null } ?: "/home/vibe"
        return Path.of(home, ".android", "avd")
    }

    fun available(): Boolean = available(defaultSlot())

    fun available(slot: EmulatorSlot): Boolean {
        val emu = emulatorBin() ?: return false
        val img = effectiveSystemImage(slot)?.let { systemImageDir(it) } ?: return false
        return Files.isExecutable(emu) && Files.isDirectory(img)
    }

    fun isManaged(): Boolean = processes[defaultSlot().id]?.process?.isAlive == true

    fun isRunning(): Boolean = isRunning(defaultSlot())

    private fun isRunning(slot: EmulatorSlot): Boolean =
        processes[slot.id]?.process?.isAlive == true || serialPresentCached(slot)

    fun booted(): Boolean = booted(defaultSlot())

    private fun booted(slot: EmulatorSlot): Boolean {
        if (!isRunning(slot)) return false
        val out = adbCmd(listOf("-s", slot.serial, "shell", "getprop", "sys.boot_completed"), 6) ?: return false
        return out.trim() == "1"
    }

    fun accelCheckUsable(): Boolean {
        val kvm = Path.of("/dev/kvm")
        if (!Files.exists(kvm)) return false
        if (Files.isReadable(kvm) && Files.isWritable(kvm)) return true
        return runCatching { java.io.RandomAccessFile(kvm.toFile(), "rw").close(); true }.getOrDefault(false)
    }

    suspend fun status(): Status = status(defaultSlot())

    suspend fun status(id: String): Status? = slots.firstOrNull { it.id == id }?.let { status(it) }

    suspend fun poolStatus(): PoolStatus = withContext(Dispatchers.IO) {
        reapExpiredLeases()
        val statuses = slots.map { status(it) }
        PoolStatus(
            available = statuses.any { it.available },
            max = maxEmulators,
            running = statuses.count { it.running },
            booted = statuses.count { it.booted },
            slots = statuses,
        )
    }

    private suspend fun status(slot: EmulatorSlot): Status = withContext(Dispatchers.IO) {
        val managed = processes[slot.id]?.process?.isAlive == true
        val present = runCatching { serialPresentCached(slot) }.getOrDefault(false)
        val running = managed || present
        val lease = leases[slot.id]?.takeIf { it.expiresAt.isAfter(Instant.now()) }
        Status(
            available = available(slot),
            running = running,
            booted = if (running) runCatching { bootedCached(slot) }.getOrDefault(false) else false,
            serial = slot.serial,
            startedAtIso = startedAt[slot.id]?.toString(),
            external = present && !managed,
            id = slot.id,
            label = slot.profile.label,
            kind = slot.profile.kind,
            avdName = slot.profile.avdName,
            systemImage = effectiveSystemImage(slot) ?: slot.profile.systemImage,
            consolePort = slot.consolePort,
            leasedByProjectId = lease?.projectId,
            leaseExpiresAtIso = lease?.expiresAt?.toString(),
        )
    }

    fun recentLog(id: String = defaultSlot().id): List<String> =
        synchronized(tailFor(id)) { tailFor(id).toList() }

    suspend fun start(): StartResult = start(defaultSlot().id)

    suspend fun start(id: String): StartResult = startMutex.withLock {
        val slot = slots.firstOrNull { it.id == id } ?: return StartResult(false, "unknown emulator: $id")
        if (!available(slot)) return StartResult(false, "emulator/system-image 미설치 — 빌드환경에서 먼저 설치하세요")
        if (processes[slot.id]?.process?.isAlive == true) return StartResult(true, "이미 실행 중 (${slot.serial})")
        if (serialPresentCached(slot)) {
            return StartResult(false, "이미 ${slot.serial} 에뮬레이터가 외부에서 실행 중입니다. 중지 후 다시 시작하세요.")
        }
        if (!accelCheckUsable()) {
            return StartResult(false, "KVM 하드웨어 가속을 쓸 수 없어 시작을 막았습니다 — /dev/kvm 매핑과 권한을 확인하세요.")
        }
        if (!ensureAvd(slot)) return StartResult(false, "AVD 생성 실패 (${slot.profile.avdName})")
        val emu = emulatorBin() ?: return StartResult(false, "emulator 바이너리 없음")
        val args = listOf(
            emu.toString(), "-avd", slot.profile.avdName,
            "-port", slot.consolePort.toString(),
            "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
            "-gpu", "swiftshader_indirect", "-accel", "on", "-no-metrics",
        )
        runCatching {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            processes[slot.id] = ManagedProcess(p, slot)
            startedAt[slot.id] = Instant.now()
            serialPresentCache[slot.id] = BoolCache()
            synchronized(tailFor(slot.id)) { tailFor(slot.id).clear() }
            scope.launch {
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) appendLog(slot.id, line)
                    }
                }
            }
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { p.waitFor() } }
                processes.remove(slot.id, processes[slot.id])
                startedAt.remove(slot.id)
                log.info { "에뮬레이터 프로세스 종료: ${slot.id}/${slot.serial} (exit=${runCatching { p.exitValue() }.getOrNull()})" }
            }
            log.info { "에뮬레이터 시작: ${slot.id}/${slot.profile.avdName} (${slot.serial}, headless, accel on)" }
            StartResult(true, "시작됨 — ${slot.serial} 부팅까지 1~2분 소요")
        }.getOrElse {
            log.warn(it) { "에뮬레이터 spawn 실패: ${slot.id}" }
            processes.remove(slot.id)
            startedAt.remove(slot.id)
            StartResult(false, "에뮬레이터 실행 실패: ${it.message}")
        }
    }

    suspend fun stop(): StartResult = stop(defaultSlot().id)

    suspend fun stop(id: String): StartResult = startMutex.withLock {
        val slot = slots.firstOrNull { it.id == id } ?: return StartResult(false, "unknown emulator: $id")
        leases.remove(slot.id)
        saveLeases()
        val managed = processes[slot.id]
        if (managed == null) {
            if (serialPresentCached(slot)) {
                runCatching { adbCmd(listOf("-s", slot.serial, "emu", "kill"), 8) }
                serialPresentCache[slot.id] = BoolCache()
                return StartResult(true, "외부에서 실행된 에뮬레이터에 종료 신호를 보냈습니다 (${slot.serial})")
            }
            return StartResult(true, "이미 중지됨")
        }
        runCatching { adbCmd(listOf("-s", slot.serial, "emu", "kill"), 8) }
        withContext(Dispatchers.IO) {
            val p = managed.process
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        processes.remove(slot.id)
        startedAt.remove(slot.id)
        serialPresentCache[slot.id] = BoolCache()
        StartResult(true, "중지됨 (${slot.serial})")
    }

    suspend fun acquireLease(projectId: String, preferredKind: String? = null, mode: String = "manual"): StartResult {
        reapExpiredLeases()
        leases.values.firstOrNull { it.projectId == projectId && it.expiresAt.isAfter(Instant.now()) }?.let {
            touchLease(it)
            return StartResult(true, "이미 할당됨: ${it.serial}")
        }
        val occupied = leases.values.filter { it.expiresAt.isAfter(Instant.now()) }.map { it.emulatorId }.toSet()
        val candidates = slots
            .filter { it.id !in occupied }
            .filter { preferredKind.isNullOrBlank() || it.profile.kind == preferredKind }
            .ifEmpty { slots.filter { it.id !in occupied } }
        val slot = candidates.sortedWith(compareBy<EmulatorSlot>({ !bootedCached(it) }, { !isRunning(it) }, { it.index })).firstOrNull()
            ?: return StartResult(false, "사용 가능한 에뮬레이터가 없습니다 (상한 ${MAX_EMULATORS}개)")
        if (!isRunning(slot)) {
            val started = start(slot.id)
            if (!started.ok) return started
        }
        val now = Instant.now()
        leases[slot.id] = MutableLease(
            emulatorId = slot.id,
            serial = slot.serial,
            projectId = projectId,
            acquiredAt = now,
            lastSeenAt = now,
            expiresAt = now.plusSeconds(LEASE_TTL_SECONDS),
            mode = mode,
        )
        saveLeases()
        return StartResult(true, "할당됨: ${slot.serial}")
    }

    fun releaseLease(projectId: String): StartResult {
        val removed = leases.entries.filter { it.value.projectId == projectId }.map { it.key }
        removed.forEach { leases.remove(it) }
        saveLeases()
        return StartResult(true, if (removed.isEmpty()) "할당된 에뮬레이터 없음" else "에뮬레이터 할당 해제됨")
    }

    fun leaseForProject(projectId: String): Lease? {
        reapExpiredLeases()
        return leases.values.firstOrNull { it.projectId == projectId }?.also { touchLease(it) }?.snapshot()
    }

    suspend fun shutdown() {
        slots.forEach { runCatching { stop(it.id) } }
        scope.cancel()
    }

    private fun avdExists(avdName: String): Boolean {
        val home = avdHome()
        return Files.isDirectory(home.resolve("$avdName.avd")) || Files.exists(home.resolve("$avdName.ini"))
    }

    /**
     * v1.162.0 — 기존 AVD 의 config.ini `image.sysdir.1` 이 현재 목표 시스템 이미지와 일치하는지.
     * 불일치(예: 구 google_atd 로 만들어진 AVD)면 stale 로 보고 재생성해야 한다 — 안 그러면
     * 이미지를 google_apis 로 바꿔도 옛 AVD 가 재사용돼 screencap 이 계속 검은 화면이 된다.
     */
    private fun avdImageMatches(slot: EmulatorSlot): Boolean {
        val target = (effectiveSystemImage(slot) ?: slot.profile.systemImage).split(';').joinToString("/")
        val config = avdHome().resolve("${slot.profile.avdName}.avd").resolve("config.ini")
        if (!Files.isRegularFile(config)) return false
        return runCatching {
            Files.readAllLines(config).any { it.startsWith("image.sysdir.1=") && it.contains(target) }
        }.getOrDefault(false)
    }

    private fun deleteAvd(avdName: String) {
        runCatching {
            val home = avdHome()
            home.resolve("$avdName.avd").toFile().deleteRecursively()
            Files.deleteIfExists(home.resolve("$avdName.ini"))
        }.onFailure { log.warn(it) { "AVD 삭제 실패: $avdName" } }
    }

    suspend fun ensureAvd(): Boolean = ensureAvd(defaultSlot())

    private suspend fun ensureAvd(slot: EmulatorSlot): Boolean = withContext(Dispatchers.IO) {
        if (avdExists(slot.profile.avdName)) {
            if (avdImageMatches(slot)) return@withContext true
            log.info { "AVD '${slot.profile.avdName}' 시스템 이미지 불일치 → 삭제 후 재생성 (렌더링 가능한 이미지로 승격)" }
            deleteAvd(slot.profile.avdName)
        }
        val avdm = avdmanagerBin() ?: return@withContext false
        val image = effectiveSystemImage(slot) ?: slot.profile.systemImage
        val args = listOf(
            avdm.toString(), "create", "avd",
            "-n", slot.profile.avdName, "-k", image, "-d", slot.profile.deviceProfile, "--force",
        )
        createAvd(args, slot.profile.avdName).also { ok ->
            if (!ok && slot.profile.deviceProfile != deviceProfile) {
                log.warn { "AVD profile '${slot.profile.deviceProfile}' 실패 — 기본 profile '$deviceProfile' 로 재시도" }
                createAvd(
                    listOf(avdm.toString(), "create", "avd", "-n", slot.profile.avdName, "-k", image, "-d", deviceProfile, "--force"),
                    slot.profile.avdName,
                )
            }
        } || avdExists(slot.profile.avdName)
    }

    private fun createAvd(args: List<String>, avdName: String): Boolean = runCatching {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val outText = StringBuilder()
        val drain = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(outText) { outText.appendLine(it) } } }
        }.apply { isDaemon = true; name = "avd-create-drain-$avdName"; start() }
        runCatching { p.outputStream.bufferedWriter().use { it.write("no\n"); it.flush() } }
        val finished = p.waitFor(120, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        drain.join(2000)
        val tail = synchronized(outText) { outText.toString() }.takeLast(300).replace('\n', ' ')
        log.info { "avdmanager create avd '$avdName': finished=$finished exit=${runCatching { p.exitValue() }.getOrNull()} $tail" }
        finished && p.exitValue() == 0 && avdExists(avdName)
    }.getOrElse { log.warn(it) { "avd create 실패: $avdName" }; false }

    private fun serialPresentCached(slot: EmulatorSlot): Boolean {
        val now = System.currentTimeMillis()
        val cache = serialPresentCache.computeIfAbsent(slot.id) { BoolCache() }
        if (now - cache.checkedAt < SERIAL_TTL_MS) return cache.value
        cache.value = runCatching {
            adb.devices().any { it.serial == slot.serial && it.state != "offline" }
        }.getOrDefault(false)
        cache.checkedAt = now
        return cache.value
    }

    private fun bootedCached(slot: EmulatorSlot): Boolean {
        if (!isRunning(slot)) {
            bootedCache[slot.id] = BoolCache(false, System.currentTimeMillis())
            return false
        }
        val now = System.currentTimeMillis()
        val cache = bootedCache.computeIfAbsent(slot.id) { BoolCache() }
        if (now - cache.checkedAt < BOOTED_TTL_MS) return cache.value
        val out = adbCmd(listOf("-s", slot.serial, "shell", "getprop", "sys.boot_completed"), 6)
        cache.value = out?.trim() == "1"
        cache.checkedAt = now
        return cache.value
    }

    private fun appendLog(id: String, line: String) {
        synchronized(tailFor(id)) {
            val tail = tailFor(id)
            tail.addLast(line)
            while (tail.size > 200) tail.removeFirst()
        }
    }

    private fun tailFor(id: String): java.util.ArrayDeque<String> =
        logTails.computeIfAbsent(id) { java.util.ArrayDeque() }

    private fun adbCmd(args: List<String>, timeoutSec: Long): String? {
        val adbBin = adb.adbPath() ?: return null
        return runCatching {
            val p = ProcessBuilder(listOf(adbBin) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            out
        }.getOrElse { log.warn(it) { "adb ${args.firstOrNull()} 실패" }; null }
    }

    private fun leaseFile(): Path? = workspace?.root?.resolve(".vibecoder")?.resolve("emulator-leases.tsv")

    private fun loadLeases() {
        val file = leaseFile() ?: return
        if (!Files.isRegularFile(file)) return
        runCatching {
            Files.readAllLines(file).forEach { line ->
                val p = line.split('\t')
                if (p.size < 7) return@forEach
                val lease = MutableLease(
                    emulatorId = p[0],
                    serial = p[1],
                    projectId = p[2],
                    acquiredAt = Instant.parse(p[3]),
                    lastSeenAt = Instant.parse(p[4]),
                    expiresAt = Instant.parse(p[5]),
                    mode = p[6],
                )
                if (lease.expiresAt.isAfter(Instant.now())) leases[lease.emulatorId] = lease
            }
        }.onFailure { log.warn(it) { "emulator lease 로드 실패" } }
    }

    private fun saveLeases() {
        val file = leaseFile() ?: return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                leases.values.joinToString("\n") {
                    listOf(it.emulatorId, it.serial, it.projectId, it.acquiredAt, it.lastSeenAt, it.expiresAt, it.mode).joinToString("\t")
                },
            )
        }.onFailure { log.warn(it) { "emulator lease 저장 실패" } }
    }

    private fun touchLease(lease: MutableLease) {
        lease.lastSeenAt = Instant.now()
        lease.expiresAt = lease.lastSeenAt.plusSeconds(LEASE_TTL_SECONDS)
        saveLeases()
    }

    private fun reapExpiredLeases() {
        val now = Instant.now()
        val expired = leases.entries.filter { it.value.expiresAt.isBefore(now) }.map { it.key }
        if (expired.isNotEmpty()) {
            expired.forEach { leases.remove(it) }
            saveLeases()
        }
    }

    private fun buildSlots(defaultAvdName: String, defaultSystemImage: String, defaultDeviceProfile: String): List<EmulatorSlot> {
        val profiles = listOf(
            EmulatorProfile("phone", "Phone", "phone", defaultAvdName, defaultSystemImage, defaultDeviceProfile),
            EmulatorProfile("phone-large", "Large phone", "phone", "vibe_phone_large_api35", defaultSystemImage, "pixel_7_pro"),
            EmulatorProfile("tablet", "Tablet", "tablet", "vibe_tablet_api35", defaultSystemImage, "pixel_tablet"),
            EmulatorProfile("foldable", "Foldable", "foldable", "vibe_foldable_api35", defaultSystemImage, "pixel_fold"),
            EmulatorProfile("fold7", "Foldable wide", "foldable", "vibe_fold7_api35", defaultSystemImage, "pixel_fold"),
        )
        return profiles.mapIndexed { idx, profile ->
            val port = 5554 + (idx * 2)
            EmulatorSlot(
                id = when (idx) {
                    0 -> "phone-1"
                    1 -> "phone-2"
                    2 -> "tablet-1"
                    3 -> "foldable-1"
                    else -> "foldable-2"
                },
                index = idx,
                consolePort = port,
                serial = "emulator-$port",
                profile = profile,
            )
        }
    }

    private companion object {
        const val MAX_EMULATORS = 5
        const val LEASE_TTL_SECONDS = 4 * 60 * 60L
        const val BOOTED_TTL_MS = 4000L
        const val SERIAL_TTL_MS = 4000L
        // 구 설치 잔존 폴백(스크린샷 미지원 이미지). 기본은 google_apis(위 systemImage).
        const val FALLBACK_ATD_IMAGE = "system-images;android-35;google_atd;x86_64"
    }
}
