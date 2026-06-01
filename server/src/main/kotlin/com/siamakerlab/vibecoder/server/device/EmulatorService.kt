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

    fun isRunning(): Boolean = process?.isAlive == true

    /** 부팅 완료: `getprop sys.boot_completed == 1`. 미부팅/오프라인이면 false. */
    fun booted(): Boolean {
        if (!isRunning()) return false
        val out = adbCmd(listOf("-s", serial, "shell", "getprop", "sys.boot_completed"), 6) ?: return false
        return out.trim() == "1"
    }

    fun status(): Status = Status(
        available = available(),
        running = isRunning(),
        booted = runCatching { booted() }.getOrDefault(false),
        serial = serial,
        startedAtIso = startedAt?.toString(),
    )

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
            // "Do you wish to create a custom hardware profile? [no]" 프롬프트 → no.
            runCatching { p.outputStream.bufferedWriter().use { it.write("no\n"); it.flush() } }
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching false }
            log.info { "avdmanager create avd '$avdName': exit=${p.exitValue()} ${out.takeLast(200).replace('\n', ' ')}" }
            avdExists()
        }.getOrElse { log.warn(it) { "avd create 실패" }; false }
    }

    /**
     * 헤드리스 에뮬레이터 시작(비동기 — 부팅은 백그라운드, status.booted 로 폴링). 멱등.
     * KVM 가속 필요(`-accel on` + compose `/dev/kvm`). 미가속이면 매우 느려 부팅 실패 가능.
     */
    suspend fun start(): StartResult = startMutex.withLock {
        if (!available()) return StartResult(false, "emulator/system-image 미설치 — 빌드환경에서 먼저 설치하세요")
        if (isRunning()) return StartResult(true, "이미 실행 중")
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

    /** graceful(adb emu kill) → SIGTERM → 5s → SIGKILL. */
    suspend fun stop(): StartResult = startMutex.withLock {
        val p = process ?: return StartResult(true, "이미 중지됨")
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
}
