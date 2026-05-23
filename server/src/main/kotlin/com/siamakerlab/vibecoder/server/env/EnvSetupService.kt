package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

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
) {

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
        val cfg = claudeConfigDir()
        val credentials = cfg.resolve(".credentials.json")
        val cfgJson = cfg.resolve("config.json")
        return when {
            credentials.exists() -> ComponentState(c, ComponentStatus.INSTALLED, "자격증명 발견: ${credentials.fileName}")
            cfgJson.exists() -> ComponentState(c, ComponentStatus.INSTALLED, "자격증명 발견: ${cfgJson.fileName}")
            !cfg.exists() -> ComponentState(c, ComponentStatus.MISSING, "디렉토리 없음: $cfg")
            else -> ComponentState(c, ComponentStatus.MISSING, "로그인 필요: $cfg")
        }
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
}
