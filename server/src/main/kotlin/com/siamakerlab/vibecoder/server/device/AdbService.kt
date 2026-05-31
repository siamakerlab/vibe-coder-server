package com.siamakerlab.vibecoder.server.device

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.40.0 — 무선 ADB 기기 logcat. 같은 LAN(보통 HOST 네트워크)에서 안드로이드 11+ 폰의
 * "무선 디버깅"에 연결해 logcat 을 브라우저로 스트림.
 *
 * - 자동 탐지(`adb mdns services`): mDNS 멀티캐스트라 **HOST 네트워크**에서만 동작.
 *   bridge 컨테이너면 빈 목록 → UI 가 수동 IP:포트 입력으로 fallback.
 * - 수동 연결(`adb pair` / `adb connect <ip:port>`): outbound TCP 라 bridge 에서도 동작
 *   (호스트가 폰과 같은 LAN + 공유기 AP isolation off 일 때).
 *
 * 보안: 모든 인자 strict 검증(IP:포트/페어코드/serial/패키지) + ProcessBuilder list-args
 * (셸 미경유). 라우트는 admin 전용. adb 는 platform-tools 설치 시 존재 — 없으면 [adbPath] null.
 */
class AdbService(
    private val adbBinOverride: String? = null,
) {
    data class Device(val serial: String, val model: String?, val state: String) {
        val connected: Boolean get() = state == "device"
    }

    data class Discovered(val name: String, val hostPort: String, val pairing: Boolean)

    data class CmdResult(val ok: Boolean, val output: String)

    private val ipPortRe = Regex("""^[A-Za-z0-9.\-]{1,253}:\d{1,5}$""")
    private val pairCodeRe = Regex("""^\d{6}$""")
    private val serialRe = Regex("""^[A-Za-z0-9.:_\-]{1,128}$""")
    private val pkgRe = Regex("""^[A-Za-z0-9._]{1,128}$""")

    /** adb 바이너리 경로(있으면). ANDROID_HOME/platform-tools/adb → PATH 순. */
    fun adbPath(): String? {
        adbBinOverride?.let { return it }
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (home != null) {
            val p = Path.of(home, "platform-tools", "adb")
            if (Files.isExecutable(p)) return p.toString()
        }
        // PATH 탐색.
        (System.getenv("PATH") ?: "").split(':').forEach { dir ->
            if (dir.isBlank()) return@forEach
            val p = Path.of(dir, "adb")
            if (Files.isExecutable(p)) return p.toString()
        }
        return null
    }

    fun available(): Boolean = adbPath() != null

    /** `adb devices -l` → 연결된(또는 인식된) 기기 목록. */
    fun devices(): List<Device> {
        val out = run(listOf("devices", "-l"), 10) ?: return emptyList()
        return out.lineSequence()
            .drop(1) // "List of devices attached"
            .mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty()) return@mapNotNull null
                val parts = t.split(Regex("\\s+"))
                val serial = parts.getOrNull(0) ?: return@mapNotNull null
                val state = parts.getOrNull(1) ?: return@mapNotNull null
                // 21차 후속(M6 방어심화) — serial 화이트리스트 미통과(비정상/주입 의심) 제외.
                if (!serialRe.matches(serial)) return@mapNotNull null
                val model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                Device(serial, model, state)
            }
            .toList()
    }

    fun connectedCount(): Int = devices().count { it.connected }

    /** mDNS 자동 탐지 (HOST 네트워크에서만 의미 있음). connect/pair 서비스 분류. */
    fun discover(): List<Discovered> {
        val out = run(listOf("mdns", "services"), 8) ?: return emptyList()
        return out.lineSequence().mapNotNull { line ->
            val t = line.trim()
            val pairing = t.contains("_adb-tls-pairing._tcp")
            val connect = t.contains("_adb-tls-connect._tcp")
            if (!pairing && !connect) return@mapNotNull null
            // 형식: <name>\t<service>\t<ip:port>
            val cols = t.split(Regex("\\s+"))
            val hp = cols.lastOrNull { ipPortRe.matches(it) } ?: return@mapNotNull null
            Discovered(cols.firstOrNull() ?: hp, hp, pairing)
        }.toList()
    }

    fun pair(hostPort: String, code: String): CmdResult {
        if (!ipPortRe.matches(hostPort)) return CmdResult(false, "잘못된 주소 형식 (IP:포트)")
        if (!pairCodeRe.matches(code)) return CmdResult(false, "페어링 코드는 6자리 숫자")
        val out = run(listOf("pair", hostPort, code), 30) ?: return CmdResult(false, "adb pair 실행 실패")
        return CmdResult(out.contains("Successfully paired", ignoreCase = true), out)
    }

    fun connect(hostPort: String): CmdResult {
        if (!ipPortRe.matches(hostPort)) return CmdResult(false, "잘못된 주소 형식 (IP:포트)")
        val out = run(listOf("connect", hostPort), 20) ?: return CmdResult(false, "adb connect 실행 실패")
        return CmdResult(out.contains("connected", ignoreCase = true) && !out.contains("failed", ignoreCase = true), out)
    }

    /**
     * v1.59.1 — connect 실패가 "미페어링" 때문인지 추정.
     *
     * adb 는 포트 미도달이면 `Connection refused` / `timed out` 등 suffix 를 붙이지만,
     * Android 11+ 무선 디버깅의 connect 포트는 **열려 있으나 미페어링 키를 TLS 단계에서
     * 거부**한다 → suffix 없는 `failed to connect to 'ip:port'`. 이 모양이면 페어링 안내.
     */
    fun isLikelyUnpaired(output: String): Boolean {
        val o = output.lowercase()
        if (!o.contains("failed to connect")) return false
        return !o.contains("refused") && !o.contains("timed out") &&
            !o.contains("timeout") && !o.contains("no route") && !o.contains("unreachable")
    }

    /**
     * v1.59.1 — 주어진 IP 와 같은 호스트의 mDNS connect 서비스(`_adb-tls-connect`) 포트.
     * pair 성공 직후 자동 connect 대상 결정용. 못 찾으면 null (HOST 네트워크 아님 등).
     */
    fun connectPortFor(ip: String): String? {
        if (ip.isBlank()) return null
        return discover().firstOrNull { !it.pairing && it.hostPort.substringBefore(':') == ip }?.hostPort
    }

    fun disconnect(hostPort: String): CmdResult {
        if (!ipPortRe.matches(hostPort)) return CmdResult(false, "잘못된 주소 형식")
        val out = run(listOf("disconnect", hostPort), 10) ?: return CmdResult(false, "adb disconnect 실행 실패")
        return CmdResult(true, out)
    }

    /**
     * logcat 프로세스 spawn. 호출자(WS 핸들러)가 stdout 을 읽어 스트림하고, 연결 종료 시
     * destroy 한다. pkg 지정 시 해당 앱 PID 로 필터(`--pid`), 못 찾으면 전체.
     */
    fun startLogcat(serial: String, pkg: String?): Process? {
        val adb = adbPath() ?: return null
        if (!serialRe.matches(serial)) return null
        val args = mutableListOf(adb, "-s", serial, "logcat", "-v", "time", "-T", "200")
        if (pkg != null && pkgRe.matches(pkg)) {
            val pid = run(listOf("-s", serial, "shell", "pidof", pkg), 8)?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            if (pid != null) args += listOf("--pid", pid)
        }
        return runCatching {
            ProcessBuilder(args).redirectErrorStream(true).start()
        }.getOrElse { log.warn(it) { "logcat spawn 실패: $serial" }; null }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────
    /** adb 동기 실행 → stdout(+stderr) 텍스트. 실패/타임아웃 시 null. */
    private fun run(args: List<String>, timeoutSec: Long): String? {
        val adb = adbPath() ?: return null
        return runCatching {
            val proc = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { proc.destroyForcibly(); return null }
            out
        }.getOrElse { log.warn(it) { "adb ${args.firstOrNull()} 실패" }; null }
    }
}
