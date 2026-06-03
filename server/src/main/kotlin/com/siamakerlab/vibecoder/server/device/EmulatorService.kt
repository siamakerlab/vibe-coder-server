package com.siamakerlab.vibecoder.server.device

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
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.73.0 — 안드로이드 에뮬레이터(헤드리스) lifecycle. **Claude Code 로그분석용** — 화면
 * 미러링(noVNC) 없이, 에뮬레이터를 띄워 `adb` 로 잡히게 하는 것이 목적. APK 설치/logcat 은
 * Claude 가 프로젝트 콘솔에서 `adb -s emulator-5554 install|logcat` 으로 직접 수행한다.
 *
 * - 설치: 빌드환경(/env-setup)의 "Android Emulator" 컴포넌트(vibe-doctor android) 가
 *   `emulator` + `system-images;android-35;google_apis;x86_64` 를 SDK 볼륨에 설치.
 * - AVD 생성: 첫 [start] 시 [ensureAvd] 가 `avdmanager` 로 1개(`vibe_pixel_api35`) 멱등 생성.
 * - 실행: 수동(/emulator·사이드바 pill 의 시작/중지). KVM 가속(`-accel on`, compose `/dev/kvm`).
 *   자동 시작/idle reaper 없음(운영자 결정).
 *
 * 종료는 [ClaudeSessionManager] 와 동일 패턴: `adb emu kill`(graceful) → SIGTERM → 5s → SIGKILL.
 */
class EmulatorService(
    private val adb: AdbService,
    val avdName: String = "vibe_pixel_api35",
    val systemImage: String = "system-images;android-35;google_apis;x86_64",
    private val deviceProfile: String = "pixel_6",
) {
    /** 첫 에뮬레이터 인스턴스의 콘솔 포트(5554) 기준 serial. 단일 에뮬레이터만 운영. */
    val serial: String = "emulator-5554"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var startedAt: Instant? = null
    /** 진단용 최근 stdout/stderr tail (최대 200줄). */
    private val logTail = java.util.ArrayDeque<String>()

    data class StartResult(val ok: Boolean, val message: String)
    data class Status(
        val available: Boolean,
        val running: Boolean,
        val booted: Boolean,
        val serial: String,
        val startedAtIso: String?,
        /**
         * v1.96.0 — adb 에 [serial] 이 잡혀 있으나 **서버가 spawn한 프로세스가 아님**.
         * 콘솔/수동으로(특히 `-accel off`) 띄운 인스턴스 → 서버가 KVM 가속을 보장 못 함.
         * UI 가 경고 + [중지] 회수를 노출하는 근거.
         */
        val external: Boolean = false,
    )

    // ── SDK 경로 ────────────────────────────────────────────────────────────
    private fun androidHome(): Path? =
        (System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null })?.let { Path.of(it) }

    private fun emulatorBin(): Path? =
        androidHome()?.resolve("emulator/emulator")?.takeIf { Files.isExecutable(it) }

    private fun avdmanagerBin(): Path? =
        androidHome()?.resolve("cmdline-tools/latest/bin/avdmanager")?.takeIf { Files.exists(it) }

    private fun systemImageDir(): Path? {
        // "system-images;android-35;google_apis;x86_64" → system-images/android-35/google_apis/x86_64
        val rel = systemImage.split(';').joinToString("/")
        return androidHome()?.resolve(rel)
    }

    private fun avdHome(): Path {
        System.getenv("ANDROID_AVD_HOME")?.ifBlank { null }?.let { return Path.of(it) }
        val home = System.getenv("HOME")?.ifBlank { null } ?: "/home/vibe"
        return Path.of(home, ".android", "avd")
    }

    /** emulator 바이너리 + 대상 system-image 가 모두 설치되어 있는지. */
    fun available(): Boolean {
        val emu = emulatorBin() ?: return false
        val img = systemImageDir() ?: return false
        return Files.isExecutable(emu) && Files.isDirectory(img)
    }

    /** 서버가 직접 spawn 해 추적 중인 프로세스가 살아있는가. */
    fun isManaged(): Boolean = process?.isAlive == true

    /**
     * v1.96.0 — 서버 프로세스 **또는** 외부/콘솔에서 띄운 같은 [serial] 에뮬레이터가 살아있는가.
     * 외부 인식은 [serialPresentCached] (adb devices, TTL 캐시) 로 — 콘솔에서 수동 실행한
     * 인스턴스도 status/stop 대상이 되도록.
     */
    fun isRunning(): Boolean = isManaged() || serialPresentCached()

    // v1.96.0 — adb devices 에 우리 serial 존재 여부. pill 30s 폴링이 매번 adb 를 fork 하지
    // 않도록 짧은 TTL 캐시(booted 캐시와 동일 전략). devices 는 로컬 데몬 즉답이라 booted 보다
    // 가볍지만, 무인증 status 폴링×탭수 fork 누적을 흡수.
    @Volatile private var serialPresentCache: Boolean = false
    @Volatile private var serialPresentCheckedAt: Long = 0L

    private fun serialPresentCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - serialPresentCheckedAt < SERIAL_TTL_MS) return serialPresentCache
        // v1.98.2 — offline(죽어가는/좀비) 인스턴스는 제외. 포함 시 좀비가 status.external/
        //  isRunning 을 true 로 묶어 start 를 영구 거부하고 stop(adb emu kill)은 offline 에
        //  무효라 "종료 신호 보냄" 거짓 성공이 된다. device/booting 등 live 상태만 present.
        serialPresentCache = runCatching {
            adb.devices().any { it.serial == serial && it.state != "offline" }
        }.getOrDefault(false)
        serialPresentCheckedAt = now
        return serialPresentCache
    }

    /** 부팅 완료: `getprop sys.boot_completed == 1`. 미부팅/오프라인이면 false. (직접 adb — 캐시 X) */
    fun booted(): Boolean {
        if (!isRunning()) return false
        val out = adbCmd(listOf("-s", serial, "shell", "getprop", "sys.boot_completed"), 6) ?: return false
        return out.trim() == "1"
    }

    /**
     * KVM 하드웨어 가속 가용 여부. 미가용이면 [start] 가 TCG 소프트 에뮬레이션(부팅 수분 +
     * ANR 폭주)을 막기 위해 시작을 거부한다.
     *
     * v1.96.1 — 판정 방식 교체. v1.96.0 은 `emulator -accel-check` 바이너리를 ProcessBuilder
     * 로 호출했는데, **셸에선 어떤 조건(cwd/stdin/비-TTY 포함)에서도 exit 0 + "usable"** 인
     * 명령이 JVM 자식프로세스 컨텍스트에선 오탐(false)을 내, 정상 KVM 환경에서도 시작을
     * 막아 버렸다(운영 회귀). emulator 런처가 JVM 하위에서 종료코드를 제대로 전파하지 못한
     * 것으로 추정. KVM 가속의 실제 전제는 **`/dev/kvm` 을 R/W 로 열 수 있는지**(`-accel on`
     * 이 여는 것)이므로, fork 없이 파일 접근성으로 직접·견고하게 판정한다.
     */
    fun accelCheckUsable(): Boolean {
        val kvm = Path.of("/dev/kvm")
        if (!Files.exists(kvm)) return false
        if (Files.isReadable(kvm) && Files.isWritable(kvm)) return true
        // access(2) 가 supplementary group/ACL 을 놓치는 드문 환경 대비 — 실제 R/W open 시도.
        return runCatching { java.io.RandomAccessFile(kvm.toFile(), "rw").close(); true }.getOrDefault(false)
    }

    // v1.73.0 점검 — booted adb 호출 TTL 캐시. 무인증 /api/emulator/status pill 폴링이
    // 매 호출 adb getprop 를 fork 하지 않도록(코루틴 워커 블로킹 + adb spawn 남용 방지).
    @Volatile private var bootedCache: Boolean = false
    @Volatile private var bootedCheckedAt: Long = 0L

    private fun bootedCached(): Boolean {
        if (!isRunning()) { bootedCache = false; return false }
        val now = System.currentTimeMillis()
        if (now - bootedCheckedAt < BOOTED_TTL_MS) return bootedCache
        val out = adbCmd(listOf("-s", serial, "shell", "getprop", "sys.boot_completed"), 6)
        bootedCache = out?.trim() == "1"
        bootedCheckedAt = now
        return bootedCache
    }

    /**
     * 상태 스냅샷. booted 의 adb 호출은 [Dispatchers.IO] 격리 + [BOOTED_TTL_MS] TTL 캐시 —
     * 라우트(코루틴) 워커를 블로킹하지 않고, pill 폴링이 매번 adb 를 fork 하지 않는다.
     */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val managed = isManaged()
        val present = runCatching { serialPresentCached() }.getOrDefault(false)
        val running = managed || present
        Status(
            available = available(),
            running = running,
            booted = if (running) runCatching { bootedCached() }.getOrDefault(false) else false,
            serial = serial,
            startedAtIso = startedAt?.toString(),
            external = present && !managed,
        )
    }

    fun recentLog(): List<String> = synchronized(logTail) { logTail.toList() }

    // ── lifecycle ────────────────────────────────────────────────────────────
    private fun avdExists(): Boolean {
        val home = avdHome()
        return Files.isDirectory(home.resolve("$avdName.avd")) || Files.exists(home.resolve("$avdName.ini"))
    }

    /** AVD 가 없으면 avdmanager 로 1개 생성(멱등). google_apis x86_64 + pixel_6 프로파일. */
    suspend fun ensureAvd(): Boolean = withContext(Dispatchers.IO) {
        if (avdExists()) return@withContext true
        val avdm = avdmanagerBin() ?: run {
            log.warn { "avdmanager 미설치 (cmdline-tools)" }; return@withContext false
        }
        val args = listOf(
            avdm.toString(), "create", "avd",
            "-n", avdName, "-k", systemImage, "-d", deviceProfile, "--force",
        )
        runCatching {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            // v1.73.0 점검(M2) — stdout 을 별도 데몬 스레드로 drain 하면서 stdin 에 "no" 주입.
            // ("Do you wish to create a custom hardware profile? [no]" 프롬프트 대비.)
            // avdmanager 출력이 파이프 버퍼를 채워도(stdin 미독 상태) 데드락 나지 않도록 분리.
            val outText = StringBuilder()
            val drain = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(outText) { outText.appendLine(it) } } }
            }.apply { isDaemon = true; name = "avd-create-drain"; start() }
            runCatching { p.outputStream.bufferedWriter().use { it.write("no\n"); it.flush() } }
            val finished = p.waitFor(120, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly() }
            drain.join(2000)
            val tail = synchronized(outText) { outText.toString() }.takeLast(200).replace('\n', ' ')
            log.info { "avdmanager create avd '$avdName': finished=$finished $tail" }
            finished && avdExists()
        }.getOrElse { log.warn(it) { "avd create 실패" }; false }
    }

    /**
     * 헤드리스 에뮬레이터 시작(비동기 — 부팅은 백그라운드, status.booted 로 폴링). 멱등.
     * KVM 가속 필요(`-accel on` + compose `/dev/kvm`). 미가속이면 매우 느려 부팅 실패 가능.
     */
    suspend fun start(): StartResult = startMutex.withLock {
        if (!available()) return StartResult(false, "emulator/system-image 미설치 — 빌드환경에서 먼저 설치하세요")
        if (isManaged()) return StartResult(true, "이미 실행 중")
        // v1.96.0 — 콘솔/외부에서 같은 serial 을 점유 중이면 중복 spawn 금지(포트 충돌·좀비 방지).
        //  특히 `-accel off` 로 수동 실행해 방치된 인스턴스를 서버가 위에 또 띄우지 않도록.
        if (serialPresentCached()) {
            return StartResult(false,
                "이미 $serial 에뮬레이터가 외부(콘솔/수동)에서 실행 중입니다. 서버가 KVM 가속을 보장하지 못하므로, [중지]로 회수한 뒤 다시 시작하세요.")
        }
        // v1.96.0 — KVM 가드. 가속 불가 상태로 시작하면 TCG 소프트 에뮬레이션이 되어 부팅 수분 +
        //  ANR 폭주(불안정)로 이어진다 → 차라리 시작을 막고 원인(/dev/kvm)을 안내.
        if (!accelCheckUsable()) {
            return StartResult(false,
                "KVM 하드웨어 가속을 쓸 수 없어 시작을 막았습니다 — 가속 없이는 매우 느리고 불안정합니다. compose 에 `/dev/kvm` 디바이스 매핑 + vibe 의 kvm 그룹 권한을 확인하세요.")
        }
        if (!ensureAvd()) return StartResult(false, "AVD 생성 실패 (avdmanager/system-image 확인)")
        val emu = emulatorBin() ?: return StartResult(false, "emulator 바이너리 없음")
        val args = listOf(
            emu.toString(), "-avd", avdName,
            "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot",
            "-gpu", "swiftshader_indirect", "-accel", "on", "-no-metrics",
        )
        return runCatching {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            // emulator 는 $ANDROID_SDK_ROOT / $HOME(~/.android) 환경을 본다 — ProcessBuilder 가 상속.
            val p = pb.start()
            process = p
            startedAt = Instant.now()
            serialPresentCheckedAt = 0L
            synchronized(logTail) { logTail.clear() }
            // stdout drain(진단 tail) — 안 읽으면 파이프 버퍼가 차서 멈출 수 있음.
            scope.launch {
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) appendLog(line)
                    }
                }
            }
            // 종료 감지 → 상태 정리.
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { p.waitFor() } }
                if (process === p) { process = null; startedAt = null }
                log.info { "에뮬레이터 프로세스 종료 (exit=${runCatching { p.exitValue() }.getOrNull()})" }
            }
            log.info { "에뮬레이터 시작: $avdName (headless, accel on)" }
            StartResult(true, "시작됨 — 부팅까지 1~2분 소요")
        }.getOrElse {
            log.warn(it) { "에뮬레이터 spawn 실패" }
            process = null; startedAt = null
            StartResult(false, "에뮬레이터 실행 실패: ${it.message}")
        }
    }

    /** graceful(adb emu kill) → SIGTERM → 5s → SIGKILL. 외부/콘솔 에뮬레이터는 adb 로만 회수. */
    suspend fun stop(): StartResult = startMutex.withLock {
        val p = process
        if (p == null) {
            // v1.96.0 — 서버가 띄운 프로세스는 없지만 외부(콘솔/수동, 예: -accel off 좀비)가
            //  같은 serial 로 살아있으면 adb 로 종료 신호를 보내 회수한다.
            if (serialPresentCached()) {
                runCatching { adbCmd(listOf("-s", serial, "emu", "kill"), 8) }
                serialPresentCheckedAt = 0L
                return StartResult(true, "외부에서 실행된 에뮬레이터에 종료 신호를 보냈습니다 ($serial)")
            }
            return StartResult(true, "이미 중지됨")
        }
        runCatching { adbCmd(listOf("-s", serial, "emu", "kill"), 8) }
        withContext(Dispatchers.IO) {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn { "에뮬레이터 SIGTERM grace 만료 → SIGKILL" }
                    p.destroyForcibly()
                }
            }
        }
        process = null
        startedAt = null
        serialPresentCheckedAt = 0L
        StartResult(true, "중지됨")
    }

    suspend fun shutdown() {
        runCatching { stop() }
        scope.cancel()
    }

    // ── 내부 ────────────────────────────────────────────────────────────────
    private fun appendLog(line: String) {
        synchronized(logTail) {
            logTail.addLast(line)
            while (logTail.size > 200) logTail.removeFirst()
        }
    }

    /** adb 동기 실행(adb 경로는 AdbService 재사용). 실패/타임아웃 시 null. */
    private fun adbCmd(args: List<String>, timeoutSec: Long): String? {
        val adbBin = adb.adbPath() ?: return null
        return runCatching {
            val p = ProcessBuilder(listOf(adbBin) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            out
        }.getOrElse { log.warn(it) { "adb ${args.firstOrNull()} 실패" }; null }
    }

    private companion object {
        /** booted(adb getprop) 결과 캐시 TTL. pill 폴링(30s)·동시 호출의 adb fork 억제. */
        const val BOOTED_TTL_MS = 4000L

        /** v1.96.0 — serial 존재(adb devices) 캐시 TTL. 외부 인식 폴링의 fork 억제. */
        const val SERIAL_TTL_MS = 4000L
    }
}
