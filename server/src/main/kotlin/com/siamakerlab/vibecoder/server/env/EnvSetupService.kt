package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * 빌드환경 페이지의 컴포넌트 — 사용자가 "원터치 설치" 할 수 있는 단위.
 *
 * 도커 이미지는 의도적으로 슬림화되어 있어, 컨테이너 첫 부팅 후 사용자가
 * 이 컴포넌트들을 볼륨으로 다운로드해야 안드로이드 앱을 빌드할 수 있다.
 *
 * 각 컴포넌트는 [EnvSetupService.detect] 로 현재 상태를 확인할 수 있고,
 * [EnvSetupService.spawnInstall] 로 vibe-doctor 자식 프로세스를 통해 설치한다.
 */
enum class SetupComponent(
    val id: String,
    /** 화면에 보이는 사람 친화 이름. */
    val displayName: String,
    /** vibe-doctor 서브커맨드. null 이면 자동 설치 불가 (정보 전용). */
    val doctorCmd: String?,
    /** 컴포넌트가 무엇이고 왜 필요한지. */
    val description: String,
    /** 설치 예상 시간 / 크기 메모. */
    val sizeHint: String,
) {
    JAVA(
        id = "java",
        displayName = "JDK 17",
        doctorCmd = null,
        description = "안드로이드 Gradle 빌드에 필요한 자바 런타임. 도커 이미지에 이미 포함되어 있으므로 별도 설치 불필요.",
        sizeHint = "이미지 내장",
    ),
    GIT(
        id = "git",
        displayName = "Git CLI",
        doctorCmd = null,
        description = "프로젝트 소스 변경 추적용. 도커 이미지에 포함.",
        sizeHint = "이미지 내장",
    ),
    NODE(
        id = "node",
        displayName = "Node.js 20",
        doctorCmd = null,
        description = "Claude Code CLI 실행에 필요. 도커 이미지에 포함.",
        sizeHint = "이미지 내장",
    ),
    CLAUDE_CLI(
        id = "claude-cli",
        displayName = "Claude Code CLI",
        doctorCmd = null,
        description = "프롬프트로 코드를 만드는 에이전트 CLI. 도커 이미지에 포함.",
        sizeHint = "이미지 내장",
    ),
    CLAUDE_AUTH(
        id = "claude-auth",
        displayName = "Claude 로그인",
        doctorCmd = "claude",
        description = "Anthropic 계정 인증. `claude login` 으로 한 번만 진행하면 호스트의 ~/.claude 볼륨에 자격증명이 저장됨. 인터랙티브 OAuth 가 필요하므로 컨테이너 터미널에서 직접 실행을 권장.",
        sizeHint = "~1 분",
    ),
    ANDROID_SDK(
        id = "android-sdk",
        displayName = "Android SDK",
        doctorCmd = "android",
        description = "cmdline-tools + platform-tools(ADB 포함) + platforms;android-35 + build-tools;35.0.0. sdkmanager 라이선스 자동 수락.",
        sizeHint = "약 3~4 GB · 5~15 분",
    ),
    PLATFORM_TOOLS(
        id = "platform-tools",
        displayName = "Platform Tools (ADB)",
        doctorCmd = "android",
        description = "ADB · fastboot 등 디바이스 통신 도구. Android SDK 설치에 포함되지만 단독 재설치도 가능.",
        sizeHint = "약 12 MB",
    ),
    MCP_DEFAULTS(
        id = "mcp",
        displayName = "기본 MCP 서버 묶음",
        doctorCmd = "mcp",
        description = "filesystem / sqlite / fetch / playwright 등 자주 쓰는 MCP 서버. 각 서버는 사용자 동의 후 개별 설치.",
        sizeHint = "선택적",
    ),
    GRADLE(
        id = "gradle",
        displayName = "Gradle",
        doctorCmd = "gradle",
        description = "Android 빌드 wrapper bootstrap 용. 신규 프로젝트에 gradle wrapper 가 없을 때 BuildService 가 자동 사용. 설치 후 사용자 build.gradle.kts 의 wrapper 버전이 실제 빌드를 좌우 (이건 부트스트랩 도구).",
        sizeHint = "약 130 MB",
    ),
    ;

    companion object {
        fun byId(id: String): SetupComponent? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 각 컴포넌트의 현재 상태.
 *
 * - [INSTALLED] — 사용 가능. 추가 작업 불필요.
 * - [MISSING]   — 미설치. 자동 설치 가능하면 버튼이 활성화됨.
 * - [PARTIAL]   — 일부만 설치됨 (예: cmdline-tools 만 있고 platforms 누락).
 * - [UNKNOWN]   — 진단 자체가 실패했거나 비활성화됨.
 */
enum class ComponentStatus { INSTALLED, MISSING, PARTIAL, UNKNOWN }

data class ComponentState(
    val component: SetupComponent,
    val status: ComponentStatus,
    val message: String,
)

/**
 * 컴포넌트별 현재 상태 진단 + 설치 트리거.
 *
 * 진단 로직은 docker/doctor/lib/check.sh 와 같은 기준 — Kotlin 으로 재현.
 * 설치는 vibe-doctor (`/opt/vibe-doctor/vibe-doctor` 또는 PATH 의 vibe-doctor)
 * 자식 프로세스를 spawn 해서 진행한다.
 */
class EnvSetupService(
    private val config: ServerConfig,
    private val queue: TaskQueue,
    private val hub: LogHub,
    private val clock: Clock,
) {

    /**
     * 컴포넌트별 마지막으로 실행한 task 의 id 를 캐시.
     * 진행 중인지 여부는 task 상태가 아니라 단순히 "최근에 실행했는가" 표시용.
     * (TaskQueue 에 외부에서 들여다볼 status API 가 없어 단순화)
     */
    private val lastTask = ConcurrentHashMap<SetupComponent, String>()

    fun lastTaskId(c: SetupComponent): String? = lastTask[c]

    /** 모든 컴포넌트의 현재 상태를 한 번에 반환. */
    fun detectAll(): List<ComponentState> = SetupComponent.entries.map { detect(it) }

    fun detect(c: SetupComponent): ComponentState = when (c) {
        SetupComponent.JAVA -> probeCmd(c, listOf("java", "-version"))
        SetupComponent.GIT -> probeCmd(c, listOf("git", "--version"))
        SetupComponent.NODE -> probeCmd(c, listOf("node", "--version"))
        SetupComponent.CLAUDE_CLI -> probeCmd(c, listOf(resolveClaudeCmd(), "--version"))
        SetupComponent.CLAUDE_AUTH -> probeClaudeAuth(c)
        SetupComponent.ANDROID_SDK -> probeAndroidSdk(c)
        SetupComponent.PLATFORM_TOOLS -> probePlatformTools(c)
        SetupComponent.MCP_DEFAULTS -> probeMcpDefaults(c)
        SetupComponent.GRADLE -> probeGradle(c)
    }

    // ── 개별 probe ────────────────────────────────────────────────────

    private fun probeCmd(c: SetupComponent, cmd: List<String>): ComponentState {
        val r = runtimeCommand(cmd, timeoutSec = 5)
        return if (r.exitCode == 0) {
            ComponentState(c, ComponentStatus.INSTALLED, r.combined.lineSequence().firstOrNull().orEmpty().trim().ifBlank { "OK" })
        } else {
            ComponentState(c, ComponentStatus.MISSING, "미설치: ${cmd.first()} 실행 실패")
        }
    }

    private fun probeClaudeAuth(c: SetupComponent): ComponentState {
        // v0.7.0 — API 키 모드 (.env.api-key) 가 먼저. 등록되어 있으면 OAuth 자격증명 없이도 OK.
        val cfg = claudeConfigDir()
        val apiKeyPath = cfg.resolve(".env.api-key")
        if (ClaudeProcessEnv.readApiKey(apiKeyPath) != null) {
            return ComponentState(c, ComponentStatus.INSTALLED, "API 키 모드 (ANTHROPIC_API_KEY)")
        }
        // `.credentials.json` 존재 + 그 안의 claudeAiOauth.expiresAt 까지 검증.
        // 파일만 보면 v0.5.4~v0.6.1 처럼 토큰 만료 시 false positive 가 난다.
        val credentials = cfg.resolve(".credentials.json")
        if (!cfg.exists()) {
            return ComponentState(c, ComponentStatus.MISSING, "디렉토리 없음: $cfg — 로그인 필요")
        }
        if (!credentials.exists()) {
            // root 쪽에 잘못 저장됐는지 함께 안내.
            val stray = listOf("/root/.claude/.credentials.json")
                .map { Path.of(it) }
                .firstOrNull { it != credentials && it.exists() }
            return if (stray != null) {
                ComponentState(c, ComponentStatus.MISSING,
                    "토큰이 root 사용자 홈에 저장됨 ($stray) — `--user vibe` 로 재로그인 필요")
            } else {
                ComponentState(c, ComponentStatus.MISSING, "로그인 필요: $credentials 없음")
            }
        }
        val expiresAt = readOauthExpiresAt(credentials)
        if (expiresAt == null) {
            return ComponentState(c, ComponentStatus.PARTIAL, "credentials 파일 존재 — 만료 시각 확인 실패")
        }
        val nowMs = System.currentTimeMillis()
        return when {
            expiresAt <= nowMs -> ComponentState(c, ComponentStatus.MISSING, "토큰 만료 — 재로그인 필요")
            expiresAt - nowMs < 6 * 3600 * 1000L -> ComponentState(c, ComponentStatus.PARTIAL, "곧 만료 — 재로그인 권장")
            else -> ComponentState(c, ComponentStatus.INSTALLED, "로그인됨 (${credentials.fileName})")
        }
    }

    private fun readOauthExpiresAt(file: Path): Long? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val oauth = root["claudeAiOauth"] as? kotlinx.serialization.json.JsonObject ?: return null
        (oauth["expiresAt"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    } catch (_: Throwable) {
        null
    }

    private fun probeAndroidSdk(c: SetupComponent): ComponentState {
        val sdk = androidSdkRoot() ?: return ComponentState(c, ComponentStatus.MISSING, "ANDROID_HOME 미설정")
        if (!sdk.exists()) return ComponentState(c, ComponentStatus.MISSING, "$sdk 가 존재하지 않음")

        val cmdline = sdk.resolve("cmdline-tools/latest")
        val platformTools = sdk.resolve("platform-tools")
        val platforms35 = sdk.resolve("platforms/android-35")
        val buildTools = sdk.resolve("build-tools")
        val hasBuildTools = buildTools.exists() && (Files.list(buildTools).use { it.findAny().isPresent })

        val ok = cmdline.exists() && platformTools.exists() && platforms35.exists() && hasBuildTools
        val partial = cmdline.exists()
        return when {
            ok -> ComponentState(c, ComponentStatus.INSTALLED, "$sdk · platform-tools + android-35 + build-tools")
            partial -> ComponentState(c, ComponentStatus.PARTIAL, "$sdk · 일부 누락 (platforms/build-tools)")
            else -> ComponentState(c, ComponentStatus.MISSING, "$sdk · cmdline-tools 부터 설치 필요")
        }
    }

    private fun probePlatformTools(c: SetupComponent): ComponentState {
        val sdk = androidSdkRoot() ?: return ComponentState(c, ComponentStatus.MISSING, "ANDROID_HOME 미설정")
        val pt = sdk.resolve("platform-tools")
        return if (pt.exists() && pt.resolve("adb").exists().let { it } || pt.resolve("adb.exe").exists()) {
            ComponentState(c, ComponentStatus.INSTALLED, "adb @ $pt")
        } else if (pt.exists()) {
            ComponentState(c, ComponentStatus.PARTIAL, "디렉토리는 있으나 adb 누락: $pt")
        } else {
            ComponentState(c, ComponentStatus.MISSING, "platform-tools 미설치: $pt")
        }
    }

    private fun probeMcpDefaults(c: SetupComponent): ComponentState {
        // MCP 는 사용자별 설정 파일 (예: ~/.claude/mcp.json) 에 의존. PoC 에선 단순히 UNKNOWN.
        return ComponentState(c, ComponentStatus.UNKNOWN, "선택 설치 — 진행 페이지에서 개별 토글")
    }

    /**
     * Gradle 진단 — 설치 버전 + services.gradle.org 의 latest stable 비교.
     * UI 는 status + message 로 "최신" / "업데이트 가능 (현재 X.Y → A.B)" / "미설치" 표시.
     *
     * latest 조회 실패는 fatal 아님 — 현재 설치 버전 정보만 반환.
     */
    private fun probeGradle(c: SetupComponent): ComponentState {
        val installed = runtimeCommand(listOf("gradle", "--version"), timeoutSec = 5)
        val currentVer = if (installed.exitCode == 0) {
            // 출력 line 중 `^Gradle X.Y(.Z)?` 매칭
            Regex("^Gradle\\s+([0-9.]+)\\b", RegexOption.MULTILINE)
                .find(installed.combined)?.groupValues?.getOrNull(1)
        } else null

        if (currentVer == null) {
            // 미설치 — latest 만 조회해 UI 에 표시
            val latest = fetchGradleLatest()
            val msg = if (latest != null) "미설치 — 최신 stable: $latest 설치 가능"
                      else "미설치"
            return ComponentState(c, ComponentStatus.MISSING, msg)
        }

        val latest = fetchGradleLatest()
        if (latest == null) {
            return ComponentState(c, ComponentStatus.INSTALLED, "$currentVer (최신 조회 실패)")
        }
        return if (compareGradleVersion(currentVer, latest) < 0) {
            ComponentState(c, ComponentStatus.PARTIAL, "현재 $currentVer → 최신 $latest 사용가능")
        } else {
            ComponentState(c, ComponentStatus.INSTALLED, "$currentVer (최신)")
        }
    }

    /** services.gradle.org/versions/current 의 .version 추출. 실패 시 null. */
    private fun fetchGradleLatest(): String? = try {
        val conn = java.net.URI("https://services.gradle.org/versions/current").toURL().openConnection()
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        val body = conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
    } catch (_: Throwable) {
        null
    }

    /** SemVer-ish 비교 — `8.7` vs `8.10.2` 등. 같으면 0, current < latest 면 음수. */
    private fun compareGradleVersion(a: String, b: String): Int {
        val pa = a.split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val da = pa.getOrNull(i) ?: 0
            val db = pb.getOrNull(i) ?: 0
            if (da != db) return da - db
        }
        return 0
    }

    // ── 보조 ──────────────────────────────────────────────────────────

    private fun androidSdkRoot(): Path? {
        val candidate = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
            ?: return null
        return Path.of(candidate)
    }

    private fun claudeConfigDir(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home).resolve(".claude")
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return "claude"
    }

    private data class Captured(val exitCode: Int, val combined: String)

    private fun runtimeCommand(cmd: List<String>, timeoutSec: Long = 5): Captured =
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val ok = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) p.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            val exit = runCatching { p.exitValue() }.getOrDefault(-1)
            Captured(exit, out)
        } catch (e: Throwable) {
            Captured(-1, e.message ?: e.javaClass.simpleName)
        }

    // ── 설치 액션 ─────────────────────────────────────────────────────

    /**
     * 단일 컴포넌트 설치. vibe-doctor <subcmd> 자식 프로세스를 spawn 하고
     * 라인 단위로 LogHub 의 taskId topic 에 [WsFrame.Log] 를 emit.
     * 종료 시 [WsFrame.Done] 으로 마무리. 진행 페이지는 그 topic 을 구독한다.
     *
     * @return 새 task id. 호출 측은 이걸로 `/env-setup/tasks/{taskId}` 로 redirect.
     * @throws ApiException 자동 설치가 불가능한 컴포넌트 (예: CLAUDE_AUTH OAuth).
     */
    fun spawnInstall(c: SetupComponent): String {
        val subcmd = c.doctorCmd
            ?: throw ApiException(400, "manual_install_only",
                "${c.displayName} 는 자동 설치를 지원하지 않습니다 (OAuth 인터랙티브 등).")
        val taskId = Ids.taskId()
        lastTask[c] = taskId
        submitDoctor(taskId, label = c.displayName, steps = listOf(subcmd))
        return taskId
    }

    /**
     * 자동 설치 가능한 모든 컴포넌트를 순차 실행. 하나의 task id 로 묶여
     * 한 화면에서 전체 진행을 본다. OAuth 등 인터랙티브 컴포넌트는 skip.
     */
    fun spawnInstallAll(): String {
        val taskId = Ids.taskId()
        val steps = SetupComponent.entries
            .mapNotNull { c -> c.doctorCmd?.takeIf { c != SetupComponent.CLAUDE_AUTH }?.let { c to it } }
        // CLAUDE_AUTH 는 OAuth 라 자동 불가. 나머지 (android / mcp 등) 만.
        SetupComponent.entries.forEach { c -> if (c.doctorCmd != null) lastTask[c] = taskId }
        submitDoctor(taskId, label = "모두 설치/업데이트", steps = steps.map { it.second }, displaySteps = steps.map { it.first.displayName })
        return taskId
    }

    private fun submitDoctor(
        taskId: String,
        label: String,
        steps: List<String>,
        displaySteps: List<String>? = null,
    ) {
        queue.submit(
            projectId = "env-setup",
            taskId = taskId,
            onStart = { hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "▶ $label 시작", clock.nowIso())) },
            executor = { _ ->
                withContext(Dispatchers.IO) {
                    runDoctorSteps(taskId, steps, displaySteps)
                }
            },
            onSuccess = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "✓ $label 완료", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "SUCCESS"))
            },
            onFailure = { e ->
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR", "✗ $label 실패: ${e.message}", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "FAILED", e.message))
            },
            onCancel = {
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "CANCELED"))
            },
        )
    }

    private suspend fun runDoctorSteps(taskId: String, steps: List<String>, displaySteps: List<String>?) {
        for ((i, sub) in steps.withIndex()) {
            val name = displaySteps?.getOrNull(i) ?: sub
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "── [${i + 1}/${steps.size}] $name (vibe-doctor $sub)", clock.nowIso()))
            val exit = runDoctor(taskId, sub)
            if (exit != 0) {
                throw RuntimeException("vibe-doctor $sub failed with exit $exit")
            }
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "✓ $name 단계 완료", clock.nowIso()))
        }
    }

    /**
     * `vibe-doctor <sub>` 또는 호환 명령을 spawn 하고 stdout/stderr 라인을 emit.
     * 컨테이너에서는 `/usr/local/bin/vibe-doctor` (symlink). 로컬 dev 에서는 PATH 의
     * `vibe-doctor` 가 없을 수 있으며, 그 경우엔 ProcessBuilder 가 IOException 을
     * 던지므로 호출자에게 그대로 전파해 UI 에 표시.
     */
    private suspend fun runDoctor(taskId: String, sub: String): Int = withContext(Dispatchers.IO) {
        val cmd = listOf(resolveDoctorCmd(), sub)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "STDOUT", line, clock.nowIso()))
            }
        }
        process.waitFor()
    }

    private fun resolveDoctorCmd(): String {
        // 도커 이미지 안에서는 entrypoint 가 /usr/local/bin/vibe-doctor symlink 를 만든다.
        // 호스트에서 직접 띄우는 dev 환경은 PATH 에 vibe-doctor 가 없을 수 있다.
        return System.getenv("VIBE_DOCTOR_CMD")?.ifBlank { null } ?: "vibe-doctor"
    }
}
