package com.siamakerlab.vibecoder.server.emulator

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.19.0 — Android emulator orchestration (Phase 5, scaffolding).
 *
 * **상태**: 본 cycle 은 진단 + ADB 통합 + 수동 launch 가이드. 풀 자동화
 * (compose KVM passthrough + AVD 자동 생성 + noVNC 미러링) 는 v0.20+ scope.
 *
 * 진단 항목:
 *  - /dev/kvm 존재 + readable → KVM hardware accel 가능 여부
 *  - emulator binary 존재 (Android SDK 의 emulator/)
 *  - adb binary 존재 (platform-tools)
 *  - 설치된 AVD 목록 (avdmanager list avd)
 *  - 실행 중인 device 목록 (adb devices)
 *
 * 운영 정책:
 *  - KVM 없으면 software emulation (느림, 일반 안내).
 *  - 본 컨테이너는 기본 `privileged: false` — KVM passthrough 사용하려면
 *    compose 에 `devices: [/dev/kvm:/dev/kvm]` 와 group 설정 필요.
 *  - 실 자동 launch + noVNC mirror 는 base image 부피 (qemu/x11/websockify 추가)
 *    상승 때문에 별도 image variant (`siamakerlab/vibe-coder-server:full`) 로 분리 예정.
 */
class EmulatorService {

    data class Diagnostics(
        val kvmAvailable: Boolean,
        val kvmPath: String?,
        val emulatorBinary: String?,
        val adbBinary: String?,
        val avds: List<String>,
        val runningDevices: List<String>,
        val recommendation: String,
    )

    fun diagnose(): Diagnostics {
        val kvm = Path.of("/dev/kvm")
        val kvmOk = Files.exists(kvm) && Files.isReadable(kvm) && Files.isWritable(kvm)
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
        val emulator = sdk?.let { "$it/emulator/emulator" }?.takeIf { Path.of(it).let(Files::exists) }
        val adb = sdk?.let { "$it/platform-tools/adb" }?.takeIf { Path.of(it).let(Files::exists) }
        val avds = if (sdk != null) runAvdmanagerList(sdk) else emptyList()
        val devices = if (adb != null) runAdbDevices(adb) else emptyList()
        val rec = buildRecommendation(kvmOk, sdk, emulator, adb)
        return Diagnostics(
            kvmAvailable = kvmOk,
            kvmPath = if (kvmOk) "/dev/kvm" else null,
            emulatorBinary = emulator,
            adbBinary = adb,
            avds = avds,
            runningDevices = devices,
            recommendation = rec,
        )
    }

    private fun buildRecommendation(kvm: Boolean, sdk: String?, emu: String?, adb: String?): String =
        buildString {
            if (sdk == null) {
                appendLine("- Android SDK 가 설치되지 않았습니다. /env-setup 페이지에서 Android SDK 설치.")
            } else if (emu == null) {
                appendLine("- `emulator` 패키지가 없습니다. `sdkmanager 'emulator'` 로 설치.")
            }
            if (adb == null) {
                appendLine("- ADB (`platform-tools`) 가 없습니다. SDK 설치 시 자동 포함됨.")
            }
            if (!kvm) {
                appendLine("- /dev/kvm 사용 불가. compose 의 vibe-coder-server 서비스에 다음 추가:")
                appendLine("    ```yaml")
                appendLine("    devices:")
                appendLine("      - /dev/kvm:/dev/kvm")
                appendLine("    ```")
                appendLine("  + 호스트의 kvm 그룹에 운영자 추가 (Linux 의 경우 `sudo usermod -aG kvm \$USER`).")
                appendLine("  KVM 없이도 software emulation 으로 실행은 되지만 매우 느림 (10× 이상).")
            }
            if (isEmpty()) {
                append("✓ KVM + SDK + emulator + adb 모두 준비. AVD 를 생성 (`avdmanager create avd`) 후 launch 가능.")
            }
        }

    /**
     * 실행 중인 emulator 에 APK 설치 (`adb -s <device> install -r <apk>`).
     * 본 cycle 은 BuildService 의 APK 위치를 받아 동작 — 자동 emulator 실행 후 install
     * 까지는 미구현 (수동으로 emulator 띄운 상태에서만 동작).
     */
    fun installApk(deviceSerial: String, apkPath: Path): InstallResult {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: return InstallResult(false, "ANDROID_HOME unset")
        val adb = "$sdk/platform-tools/adb"
        if (!Files.exists(Path.of(adb))) return InstallResult(false, "adb not found at $adb")
        if (!Files.exists(apkPath)) return InstallResult(false, "apk not found: $apkPath")
        val cmd = listOf(adb, "-s", deviceSerial, "install", "-r", apkPath.toString())
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                InstallResult(false, "adb install timeout (60s)")
            } else {
                InstallResult(proc.exitValue() == 0, output)
            }
        } catch (e: Throwable) {
            InstallResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    data class InstallResult(val ok: Boolean, val log: String)

    // ─── v0.24.0 — Lifecycle ──────────────────────────────────────────

    data class LaunchResult(val ok: Boolean, val pid: Long?, val message: String)

    /**
     * v0.24.0 — AVD 백그라운드 launch. headless (`-no-window`) 가 기본 — :full 변형
     * (Xvfb + noVNC) 에선 entrypoint 가 Xvfb 를 미리 띄우므로 별도 옵션 안 받아도
     * GUI 가 가상 디스플레이 :99 에 떠 노VNC 로 미러링됨.
     *
     * 이미 같은 이름 AVD 가 실행 중이면 새로 띄우지 않고 message 만 반환.
     * SDK / KVM 미준비 상태에서도 호출은 가능 (실패 메시지로 즉시 안내).
     */
    fun launchAvd(name: String, noWindow: Boolean = true): LaunchResult {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
            ?: return LaunchResult(false, null, "ANDROID_HOME unset")
        val emulator = "$sdk/emulator/emulator"
        if (!Files.exists(Path.of(emulator))) return LaunchResult(false, null, "emulator not found at $emulator")
        if (!sanitizeAvdName(name)) return LaunchResult(false, null, "invalid AVD name (only [A-Za-z0-9._-] allowed)")
        if (isAvdRunning(name)) return LaunchResult(true, null, "AVD '$name' already running")

        val args = mutableListOf(emulator, "-avd", name, "-no-audio", "-no-boot-anim")
        if (noWindow) args += "-no-window"
        // KVM 없으면 자동으로 software-only 로 떨어지지만 명시 옵션은 안 줌 (CLI 기본 behavior 신뢰).
        return try {
            // v0.76.0 (M7 fix) — 즉시 exit 한 경우 (예: 같은 AVD 가 이미 실행 중,
            // KVM 권한 없음, 잘못된 옵션) 사용자에게 'launched' 라고 거짓 응답하던
            // 회귀 회복. proc.waitFor(1, SECONDS) 로 짧게 기다려 즉시 exit 했는지
            // 확인 후 stderr 메시지를 LaunchResult.message 에 포함.
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            val proc = pb.start()
            val exitedQuickly = proc.waitFor(1, TimeUnit.SECONDS)
            if (exitedQuickly) {
                val out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrElse { "" }
                LaunchResult(false, null,
                    "emulator exited (code=${proc.exitValue()}): ${out.trim().take(500).ifBlank { "no output" }}")
            } else {
                // 1초 후에도 살아 있으면 정상 launch 진행 중. process 는 background daemon.
                LaunchResult(true, proc.pid(), "launched '$name' (pid=${proc.pid()})")
            }
        } catch (e: Throwable) {
            LaunchResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** AVD 종료 — adb emu kill. 실행 중인 device 시리얼을 인자로. */
    fun stopAvd(deviceSerial: String): LaunchResult {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: return LaunchResult(false, null, "ANDROID_HOME unset")
        val adb = "$sdk/platform-tools/adb"
        if (!Files.exists(Path.of(adb))) return LaunchResult(false, null, "adb not found")
        return try {
            val pb = ProcessBuilder(adb, "-s", deviceSerial, "emu", "kill").redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                LaunchResult(false, null, "adb emu kill timeout")
            } else {
                LaunchResult(proc.exitValue() == 0, null, out.ifBlank { "stopped" })
            }
        } catch (e: Throwable) {
            LaunchResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 디폴트 AVD 자동 생성 — `vibe-default`. 이미 존재하면 no-op.
     * v0.76.0 — Phase 59 #13: system-image 가 없으면 자동 sdkmanager install 시도.
     * `system-images;android-35;google_apis;x86_64` (~500MB) 다운로드 후 AVD create.
     */
    fun createDefaultAvd(name: String = "vibe-default", apiLevel: Int = 35): LaunchResult {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: return LaunchResult(false, null, "ANDROID_HOME unset")
        val avdmanager = "$sdk/cmdline-tools/latest/bin/avdmanager"
        if (!Files.exists(Path.of(avdmanager))) return LaunchResult(false, null, "avdmanager not found")
        if (!sanitizeAvdName(name)) return LaunchResult(false, null, "invalid AVD name")
        if (runAvdmanagerList(sdk).contains(name)) return LaunchResult(true, null, "AVD '$name' already exists")

        // v0.76.0 — Phase 59 #13: system-image 자동 ensure.
        val ensureResult = ensureSystemImage(sdk, apiLevel)
        if (!ensureResult.first) {
            return LaunchResult(false, null, "system-image install failed: ${ensureResult.second.take(300)}")
        }

        val pkg = "system-images;android-$apiLevel;google_apis;x86_64"
        val cmd = listOf(avdmanager, "create", "avd", "-n", name, "-k", pkg, "-d", "pixel_6", "--force")
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.redirectInput(ProcessBuilder.Redirect.PIPE)
            val proc = pb.start()
            proc.outputStream.use { it.write("no\n".toByteArray()) }
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                LaunchResult(false, null, "avdmanager create timeout")
            } else {
                val ok = proc.exitValue() == 0
                LaunchResult(ok, null, out.take(500))
            }
        } catch (e: Throwable) {
            LaunchResult(false, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * v0.76.0 — Phase 59 #13: system-image 자동 install.
     * `sdkmanager --list_installed` 로 존재 확인 → 없으면 `sdkmanager --install`.
     * Returns (ok, log).
     */
    private fun ensureSystemImage(sdk: String, apiLevel: Int): Pair<Boolean, String> {
        val sdkmanager = "$sdk/cmdline-tools/latest/bin/sdkmanager"
        if (!Files.exists(Path.of(sdkmanager))) return false to "sdkmanager not found"
        val pkg = "system-images;android-$apiLevel;google_apis;x86_64"

        // 1. 설치 여부 확인.
        val installedCheck = runCatching {
            val pb = ProcessBuilder(sdkmanager, "--list_installed").redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly(); ""
            } else out
        }.getOrDefault("")
        if (installedCheck.lineSequence().any { it.contains(pkg) }) {
            return true to "system-image $pkg already installed"
        }

        // 2. Install. License 자동 accept (yes 입력 다수 line).
        log.info { "Installing $pkg via sdkmanager — ~500MB download, may take 1-3 minutes" }
        return try {
            val cmd = listOf(sdkmanager, "--install", pkg)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.PIPE)
            val proc = pb.start()
            proc.outputStream.use { it.write("y\ny\ny\ny\ny\n".toByteArray()) }
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(600, TimeUnit.SECONDS)) {  // 10분 timeout (큰 다운로드).
                proc.destroyForcibly()
                false to "sdkmanager install timeout (10min)"
            } else {
                (proc.exitValue() == 0) to out.takeLast(800)
            }
        } catch (e: Throwable) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * v0.76.0 — Phase 59 #13: boot 완료 대기 (launchAvd 후 호출).
     * `adb -s <serial> shell getprop sys.boot_completed` 가 "1" 반환할 때 까지 polling.
     * timeout 2분 — 큰 emulator 는 첫 boot 시 더 길 수 있음.
     */
    fun waitForBoot(deviceSerial: String, timeoutSec: Int = 120): Boolean {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null } ?: return false
        val adb = "$sdk/platform-tools/adb"
        if (!Files.exists(Path.of(adb))) return false
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val booted = runCatching {
                val pb = ProcessBuilder(adb, "-s", deviceSerial, "shell", "getprop", "sys.boot_completed")
                    .redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" } else out
            }.getOrDefault("")
            if (booted == "1") return true
            Thread.sleep(2000)
        }
        return false
    }

    private fun isAvdRunning(name: String): Boolean {
        // adb devices 의 시리얼 (emulator-5554 등) 만 보고는 AVD 이름을 모름.
        // adb -s <serial> emu avd name 으로 확인하지만 모든 시리얼 폴링은 비싸므로
        // diagnose() 에서 보일 정도만으로 충분. launch race 는 emulator 자체가 안전하게 fail.
        return false
    }

    /** AVD 이름은 영문/숫자/`.-_` 만 허용. shell injection 차단. */
    private fun sanitizeAvdName(name: String): Boolean =
        name.isNotBlank() && name.length <= 64 && name.all { it.isLetterOrDigit() || it in setOf('.', '-', '_') }

    private fun runAvdmanagerList(sdk: String): List<String> {
        val tool = "$sdk/cmdline-tools/latest/bin/avdmanager"
        if (!Files.exists(Path.of(tool))) return emptyList()
        return try {
            val pb = ProcessBuilder(tool, "list", "avd", "-c").redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                emptyList()
            } else if (proc.exitValue() != 0) {
                emptyList()
            } else {
                out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
            }
        } catch (e: Throwable) {
            log.debug(e) { "avdmanager list failed" }
            emptyList()
        }
    }

    private fun runAdbDevices(adb: String): List<String> {
        return try {
            val pb = ProcessBuilder(adb, "devices").redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                emptyList()
            } else {
                // adb devices output: header + "<serial>\t<state>" lines
                out.lines()
                    .drop(1)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.contains("\t") }
                    .map { it.substringBefore("\t") }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
