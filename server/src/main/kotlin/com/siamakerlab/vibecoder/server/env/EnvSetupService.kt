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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
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
    // v1.7.16 — displayName / description / sizeHint 값을 i18n 키 String 으로
    // 저장 (실제 표시는 SSR 시점에 Messages.t(lang, key) lookup). 영어/한글 양쪽 지원.
    JAVA(
        id = "java",
        displayName = "env.comp.java.name",
        doctorCmd = null,
        description = "env.comp.java.desc",
        sizeHint = "env.size.builtin",
    ),
    GIT(
        id = "git",
        displayName = "env.comp.git.name",
        doctorCmd = null,
        description = "env.comp.git.desc",
        sizeHint = "env.size.builtin",
    ),
    NODE(
        id = "node",
        displayName = "env.comp.node.name",
        doctorCmd = null,
        description = "env.comp.node.desc",
        sizeHint = "env.size.builtin",
    ),
    CLAUDE_CLI(
        id = "claude-cli",
        displayName = "env.comp.claudeCli.name",
        doctorCmd = null,
        description = "env.comp.claudeCli.desc",
        sizeHint = "env.size.builtin",
    ),
    CLAUDE_AUTH(
        id = "claude-auth",
        displayName = "env.comp.claudeAuth.name",
        doctorCmd = "claude",
        description = "env.comp.claudeAuth.desc",
        sizeHint = "env.size.aboutMin",
    ),
    ANDROID_SDK(
        id = "android-sdk",
        displayName = "env.comp.androidSdk.name",
        doctorCmd = "android",
        description = "env.comp.androidSdk.desc",
        sizeHint = "env.size.sdkLarge",
    ),
    PLATFORM_TOOLS(
        id = "platform-tools",
        displayName = "env.comp.platformTools.name",
        doctorCmd = "android",
        description = "env.comp.platformTools.desc",
        sizeHint = "env.size.platformTools",
    ),
    // v1.73.0 — 안드로이드 에뮬레이터(헤드리스). doctorCmd="android" 재사용:
    // manifest.yml 에 emulator + system-images;android-35;google_apis;x86_64 가 들어가 있어
    // `vibe-doctor android` 가 SDK 와 함께 설치한다. 실행/AVD 생성은 EmulatorService(/emulator).
    // v1.162.0 — google_apis(렌더링 O)로 교체: adb screencap / mobile-mcp 스크린샷이 실제로 찍힌다.
    ANDROID_EMULATOR(
        id = "android-emulator",
        displayName = "env.comp.androidEmulator.name",
        doctorCmd = "android",
        description = "env.comp.androidEmulator.desc",
        sizeHint = "env.size.emulator",
    ),
    MCP_DEFAULTS(
        id = "mcp",
        displayName = "env.comp.mcp.name",
        doctorCmd = "mcp",
        description = "env.comp.mcp.desc",
        sizeHint = "env.size.optional",
    ),
    GRADLE(
        id = "gradle",
        displayName = "env.comp.gradle.name",
        doctorCmd = "gradle",
        description = "env.comp.gradle.desc",
        sizeHint = "env.size.gradle",
    ),
    // v1.124.0 — Flutter SDK (Android 앱 빌드 전용). doctorCmd="flutter" → vibe-doctor flutter
    // 가 git stable channel 을 /home/vibe/.local/flutter 에 clone + Android-only precache.
    // 선택적 컴포넌트 — "모두 설치"(install-all) 에서는 제외(Kotlin 사용자에겐 불필요한 2.5GB).
    FLUTTER(
        id = "flutter",
        displayName = "env.comp.flutter.name",
        doctorCmd = "flutter",
        description = "env.comp.flutter.desc",
        sizeHint = "env.size.flutter",
    ),
    // v1.145.0 — Codex CLI (OpenAI). doctorCmd="codex" → vibe-doctor codex 가 npm `@openai/codex`
    // 를 /home/vibe/.local 에 글로벌 설치(이미지 업데이트 후에도 영속). 로그인/설정은 CODEX_HOME
    // (/home/vibe/.codex, codex bind mount)에 영속. 선택적 컴포넌트 — "모두 설치"에서
    // 제외하고 개별 카드 버튼으로만 설치(Android 빌드 필수 도구가 아님).
    CODEX(
        id = "codex",
        displayName = "env.comp.codex.name",
        doctorCmd = "codex",
        description = "env.comp.codex.desc",
        sizeHint = "env.size.codex",
    ),
    // v1.156.0 — opencode CLI (z.ai coding plan provider). doctorCmd="opencode" → vibe-doctor opencode
    // 가 공식 install 스크립트로 /home/vibe/.opencode/bin 에 설치(영속). 로그인은 auth.json
    // (~/.local/share/opencode/)에 저장. 선택적 컴포넌트 — "모두 설치"에서 제외.
    OPENCODE(
        id = "opencode",
        displayName = "env.comp.opencode.name",
        doctorCmd = "opencode",
        description = "env.comp.opencode.desc",
        sizeHint = "env.size.opencode",
    ),
    SSH_SERVER(
        id = "ssh-server",
        displayName = "env.comp.sshServer.name",
        doctorCmd = "ssh-server",
        description = "env.comp.sshServer.desc",
        sizeHint = "env.size.sshServer",
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

    /** 모든 컴포넌트의 현재 상태를 한 번에 반환.
     *  v1.7.16 — lang 받음. 호출자 (EnvSetupRoutes) 가 sess.language 전달.
     *  default "en" — API / internal 호출자 호환. */
    fun detectAll(lang: String): List<ComponentState> = SetupComponent.entries.map { detect(it, lang) }

    /** v1.25.2 — Q4 회수: service-to-service / API entry 가 lang 모를 때 사용. */
    fun detectAll(): List<ComponentState> = detectAll(config.i18n.defaultLanguage)

    fun detect(c: SetupComponent, lang: String): ComponentState = when (c) {
        SetupComponent.JAVA -> probeCmd(c, listOf("java", "-version"), lang)
        SetupComponent.GIT -> probeCmd(c, listOf("git", "--version"), lang)
        SetupComponent.NODE -> probeCmd(c, listOf("node", "--version"), lang)
        SetupComponent.CLAUDE_CLI -> probeCmd(c, listOf(resolveClaudeCmd(), "--version"), lang)
        SetupComponent.CLAUDE_AUTH -> probeClaudeAuth(c, lang)
        SetupComponent.ANDROID_SDK -> probeAndroidSdk(c, lang)
        SetupComponent.PLATFORM_TOOLS -> probePlatformTools(c, lang)
        SetupComponent.ANDROID_EMULATOR -> probeAndroidEmulator(c, lang)
        SetupComponent.MCP_DEFAULTS -> probeMcpDefaults(c, lang)
        SetupComponent.GRADLE -> probeGradle(c, lang)
        SetupComponent.FLUTTER -> probeFlutter(c, lang)
        SetupComponent.CODEX -> probeCmd(c, listOf("codex", "--version"), lang)
        SetupComponent.OPENCODE -> probeCmd(c, listOf(resolveOpenCodeCmd(), "--version"), lang)
        SetupComponent.SSH_SERVER -> probeSshServer(c, lang)
    }

    private fun t(lang: String, key: String, vararg args: Any?): String =
        com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, *args)

    // ── 개별 probe ────────────────────────────────────────────────────

    private fun probeCmd(c: SetupComponent, cmd: List<String>, lang: String): ComponentState {
        val r = runtimeCommand(cmd, timeoutSec = 5)
        return if (r.exitCode == 0) {
            ComponentState(c, ComponentStatus.INSTALLED, r.combined.lineSequence().firstOrNull().orEmpty().trim().ifBlank { "OK" })
        } else {
            ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.cmd.missing", cmd.first()))
        }
    }

    private fun probeClaudeAuth(c: SetupComponent, lang: String): ComponentState {
        val cfg = claudeConfigDir()
        val apiKeyPath = cfg.resolve(".env.api-key")
        if (ClaudeProcessEnv.readApiKey(apiKeyPath) != null) {
            return ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.claudeAuth.apiKey"))
        }
        val credentials = cfg.resolve(".credentials.json")
        if (!cfg.exists()) {
            return ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.claudeAuth.dirMissing", cfg.toString()))
        }
        if (!credentials.exists()) {
            val stray = listOf("/root/.claude/.credentials.json")
                .map { Path.of(it) }
                .firstOrNull { it != credentials && it.exists() }
            return if (stray != null) {
                ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.claudeAuth.strayRoot", stray.toString()))
            } else {
                ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.claudeAuth.credMissing", credentials.toString()))
            }
        }
        val expiresAt = readOauthExpiresAt(credentials)
        val hasRefresh = readOauthRefreshToken(credentials) != null
        if (expiresAt == null) {
            return if (hasRefresh) {
                ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.claudeAuth.refreshOnlyOk"))
            } else {
                ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.claudeAuth.parseFail"))
            }
        }
        val nowMs = System.currentTimeMillis()
        val IMMINENT_MS = 30 * 60 * 1000L
        return when {
            expiresAt <= nowMs && !hasRefresh -> ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.claudeAuth.expiredHard"))
            expiresAt <= nowMs -> ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.claudeAuth.expiredRefresh"))
            expiresAt - nowMs < IMMINENT_MS && !hasRefresh -> ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.claudeAuth.imminent"))
            else -> ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.claudeAuth.ok", credentials.fileName.toString()))
        }
    }

    private fun readOauthRefreshToken(file: Path): String? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val oauth = root["claudeAiOauth"] as? kotlinx.serialization.json.JsonObject ?: return null
        (oauth["refreshToken"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    private fun readOauthExpiresAt(file: Path): Long? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val oauth = root["claudeAiOauth"] as? kotlinx.serialization.json.JsonObject ?: return null
        (oauth["expiresAt"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
    } catch (_: Throwable) {
        null
    }

    private fun probeAndroidSdk(c: SetupComponent, lang: String): ComponentState {
        val sdk = androidSdkRoot() ?: return ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidSdk.notSet"))
        if (!sdk.exists()) return ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidSdk.notExist", sdk.toString()))

        val cmdline = sdk.resolve("cmdline-tools/latest")
        val platformTools = sdk.resolve("platform-tools")
        val platforms35 = sdk.resolve("platforms/android-35")
        val buildTools = sdk.resolve("build-tools")
        val hasBuildTools = buildTools.exists() && (Files.list(buildTools).use { it.findAny().isPresent })

        val ok = cmdline.exists() && platformTools.exists() && platforms35.exists() && hasBuildTools
        val partial = cmdline.exists()
        return when {
            ok -> ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.androidSdk.ok", sdk.toString()))
            partial -> ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.androidSdk.partial", sdk.toString()))
            else -> ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidSdk.cmdlineNeeded", sdk.toString()))
        }
    }

    private fun probePlatformTools(c: SetupComponent, lang: String): ComponentState {
        val sdk = androidSdkRoot() ?: return ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidSdk.notSet"))
        val pt = sdk.resolve("platform-tools")
        val adbPresent = pt.resolve("adb").exists() || pt.resolve("adb.exe").exists()
        return when {
            pt.exists() && adbPresent -> ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.platformTools.ok", pt.toString()))
            pt.exists() -> ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.platformTools.partial", pt.toString()))
            else -> ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.platformTools.missing", pt.toString()))
        }
    }

    // v1.73.0 — 에뮬레이터: emulator 바이너리 + 대상 system-image 존재 여부.
    private fun probeAndroidEmulator(c: SetupComponent, lang: String): ComponentState {
        val sdk = androidSdkRoot() ?: return ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidSdk.notSet"))
        val emulatorBin = sdk.resolve("emulator/emulator")
        // v1.162.0 — google_apis(렌더링 O) 우선. 구 설치의 google_atd 도 최소 동작용으로 인정.
        val imageDir = sdk.resolve("system-images/android-35/google_apis/x86_64")
        val legacyImageDir = sdk.resolve("system-images/android-35/google_atd/x86_64")
        val hasEmu = emulatorBin.exists()
        val hasImg = (imageDir.exists() && Files.isDirectory(imageDir)) ||
            (legacyImageDir.exists() && Files.isDirectory(legacyImageDir))
        return when {
            hasEmu && hasImg -> ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.androidEmulator.ok", sdk.toString()))
            hasEmu -> ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.androidEmulator.noImage"))
            else -> ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.androidEmulator.missing"))
        }
    }

    private fun probeMcpDefaults(c: SetupComponent, lang: String): ComponentState {
        return ComponentState(c, ComponentStatus.UNKNOWN, t(lang, "probe.mcp.unknown"))
    }

    /**
     * Gradle 진단 — 설치 버전 + services.gradle.org 의 latest stable 비교.
     * UI 는 status + message 로 "최신" / "업데이트 가능 (현재 X.Y → A.B)" / "미설치" 표시.
     *
     * latest 조회 실패는 fatal 아님 — 현재 설치 버전 정보만 반환.
     */
    private fun probeGradle(c: SetupComponent, lang: String): ComponentState {
        val installed = runtimeCommand(listOf("gradle", "--version"), timeoutSec = 5)
        val currentVer = if (installed.exitCode == 0) {
            Regex("^Gradle\\s+([0-9.]+)\\b", RegexOption.MULTILINE)
                .find(installed.combined)?.groupValues?.getOrNull(1)
        } else null

        if (currentVer == null) {
            val latest = fetchGradleLatest()
            val msg = if (latest != null) t(lang, "probe.cmd.missing", "gradle") + " — latest: $latest"
                      else t(lang, "probe.cmd.missing", "gradle")
            return ComponentState(c, ComponentStatus.MISSING, msg)
        }

        val latest = fetchGradleLatest()
        if (latest == null) {
            return ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.gradle.fetchFail", currentVer))
        }
        return if (compareGradleVersion(currentVer, latest) < 0) {
            ComponentState(c, ComponentStatus.PARTIAL, t(lang, "probe.gradle.update", currentVer, latest))
        } else {
            ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.gradle.latest", currentVer))
        }
    }

    /**
     * Flutter 진단 — 표준 설치 경로(`.local/flutter/bin/flutter`) 존재를 우선 검사하고,
     * 없으면 PATH 의 `flutter --version` 을 시도. Gradle 과 달리 latest 비교는 하지 않는다
     * (Flutter 는 git channel 기반 — `flutter upgrade` 로 갱신, 버전 비교 의미가 약함).
     *
     * v1.124.0 — Android 앱 빌드 전용 컴포넌트. 실제 설치/precache 는 vibe-doctor flutter.
     */
    private fun probeFlutter(c: SetupComponent, lang: String): ComponentState {
        val bin = flutterBinPath()
        if (bin != null && bin.exists()) {
            return ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.flutter.ok", bin.toString()))
        }
        val r = runtimeCommand(listOf("flutter", "--version"), timeoutSec = 8)
        return if (r.exitCode == 0) {
            val first = r.combined.lineSequence().firstOrNull().orEmpty().trim().ifBlank { "OK" }
            ComponentState(c, ComponentStatus.INSTALLED, first)
        } else {
            ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.flutter.missing"))
        }
    }

    private fun probeSshServer(c: SetupComponent, lang: String): ComponentState {
        val sshd = Path.of("/usr/sbin/sshd")
        return if (Files.exists(sshd)) {
            ComponentState(c, ComponentStatus.INSTALLED, t(lang, "probe.sshServer.ok", sshServerPort()))
        } else {
            ComponentState(c, ComponentStatus.MISSING, t(lang, "probe.sshServer.missing", sshServerPort()))
        }
    }

    /** 표준 Flutter 설치 위치 — `<user.home>/.local/flutter/bin/flutter` (lib/flutter.sh 와 동기). */
    private fun flutterBinPath(): Path? {
        val home = System.getProperty("user.home")?.ifBlank { null }
            ?: System.getenv("HOME")?.ifBlank { null }
            ?: return null
        return Path.of(home).resolve(".local/flutter/bin/flutter")
    }

    fun sshServerPort(): Int {
        val env = System.getenv("VIBECODER_SSH_PORT")?.trim()?.toIntOrNull()
        if (env != null && env in SSH_PORT_MIN..SSH_PORT_MAX) return env
        val file = sshServerPortFile()
        val saved = runCatching { Files.readString(file).trim().toIntOrNull() }.getOrNull()
        return saved?.takeIf { it in SSH_PORT_MIN..SSH_PORT_MAX } ?: DEFAULT_SSH_PORT
    }

    fun saveSshServerPort(port: String): Int {
        val parsed = port.trim().toIntOrNull()
            ?: throw ApiException.localized(400, "invalid_ssh_port", messageKey = "api.envSetup.invalidSshPort")
        if (parsed !in SSH_PORT_MIN..SSH_PORT_MAX) {
            throw ApiException.localized(400, "invalid_ssh_port", messageKey = "api.envSetup.invalidSshPort")
        }
        val file = sshServerPortFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, "$parsed\n", Charsets.UTF_8)
        return parsed
    }

    private fun sshServerPortFile(): Path {
        val dataDir = System.getenv("VIBECODER_DATA_DIR")?.ifBlank { null } ?: "/data"
        return Path.of(dataDir).resolve("ssh-server/port")
    }

    /**
     * services.gradle.org/versions/current 의 .version 추출. 실패 시 null.
     *
     * v0.12.4 — 30분 TTL 캐시. 이전엔 매 detect 마다 (대시보드 로드 시점마다)
     * 외부 HTTP 호출이 최대 10 초 블로킹돼 UX 가 늘어졌다. 캐시 miss / 실패 시만
     * 네트워크 사용 — 결과가 stale 해도 사용자 영향은 "최신 버전 알림 30분 지연" 정도.
     */
    private fun fetchGradleLatest(): String? {
        val now = System.currentTimeMillis()
        gradleLatestCache.get()?.let { (ts, ver) ->
            if (now - ts < GRADLE_LATEST_TTL_MS) return ver
        }
        val fetched = try {
            val conn = java.net.URI("https://services.gradle.org/versions/current").toURL().openConnection()
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
        } catch (_: Throwable) {
            null
        }
        if (fetched != null) gradleLatestCache.set(now to fetched)
        return fetched
    }

    /** (fetchedAtMs, version) — 성공 결과만 캐싱. 실패는 재시도. */
    private val gradleLatestCache = AtomicReference<Pair<Long, String>?>(null)

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
            ?: throw ApiException.localized(400, "manual_install_only",
                messageKey = "api.envSetup.manualInstallOnly", args = listOf(c.displayName))
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
            .mapNotNull { c -> c.doctorCmd?.takeIf { c != SetupComponent.CLAUDE_AUTH && c != SetupComponent.FLUTTER && c != SetupComponent.CODEX && c != SetupComponent.OPENCODE && c != SetupComponent.SSH_SERVER }?.let { c to it } }
        // CLAUDE_AUTH 는 OAuth 라 자동 불가. FLUTTER(~2.5GB)·CODEX·OPENCODE 는 선택적 도구라
        // 개별 카드 버튼으로만 설치(Android 빌드 필수 아님). SSH_SERVER 는 외부 포트 노출/접속
        // 경계가 열리는 선택 기능이라 개별 카드로만 설치. 나머지 (android / gradle / mcp) 만.
        SetupComponent.entries.forEach { c -> if (c.doctorCmd != null && c != SetupComponent.FLUTTER && c != SetupComponent.CODEX && c != SetupComponent.OPENCODE && c != SetupComponent.SSH_SERVER) lastTask[c] = taskId }
        submitDoctor(taskId, label = "모두 설치/업데이트", steps = steps.map { it.second }, displaySteps = steps.map { it.first.displayName })
        return taskId
    }

    fun spawnCodexLogin(): String {
        val taskId = Ids.taskId()
        var loginUrlAnnounced = false
        var loginCodeAnnounced = false
        var deviceExchangeRejected = false
        queue.submit(
            projectId = "env-setup",
            taskId = taskId,
            onStart = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "▶ Codex 로그인 시작", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "브라우저에서 $CODEX_DEVICE_LOGIN_URL 를 열고, 아래에 표시되는 8~9자리 device code를 입력하세요.", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "승인 후 이 작업은 Codex CLI가 로그인 완료를 확인할 때까지 대기합니다.", clock.nowIso()))
            },
            executor = { _ ->
                withContext(Dispatchers.IO) {
                    val exit = runStreamingCommand(
                        taskId,
                        codexDeviceAuthCommand(),
                    ) { line ->
                        if (!loginUrlAnnounced && line.contains(CODEX_DEVICE_LOGIN_URL, ignoreCase = true)) {
                            loginUrlAnnounced = true
                            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "로그인 페이지: $CODEX_DEVICE_LOGIN_URL", clock.nowIso()))
                        }
                        if (!loginCodeAnnounced) {
                            CODEX_DEVICE_CODE_REGEX.find(line)?.value?.let { code ->
                                loginCodeAnnounced = true
                                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "Codex device code: $code", clock.nowIso()))
                                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "$CODEX_DEVICE_LOGIN_URL 에서 위 코드를 승인하세요. 승인 후 자동으로 다음 단계로 진행됩니다.", clock.nowIso()))
                            }
                        }
                        if (line.contains("device code exchange failed", ignoreCase = true) ||
                            line.contains("token_exchange_user_error", ignoreCase = true)
                        ) {
                            deviceExchangeRejected = true
                        }
                    }
                    if (exit != 0) {
                        if (deviceExchangeRejected) {
                            hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR", "Codex device-code 토큰 교환이 OpenAI 인증 서버에서 거절되었습니다.", clock.nowIso()))
                            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "장치 코드 승인까지는 완료됐지만, 승인한 ChatGPT 계정/워크스페이스가 Codex CLI 토큰 발급 조건을 통과하지 못했습니다.", clock.nowIso()))
                            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "브라우저가 올바른 계정/워크스페이스로 로그인되어 있는지, Codex Local 권한과 MFA 요건이 충족되어 있는지 확인한 뒤 다시 시도하세요.", clock.nowIso()))
                        }
                        throw RuntimeException("codex login --device-auth failed with exit $exit")
                    }
                }
            },
            onSuccess = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "✓ Codex 로그인 완료", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "SUCCESS"))
            },
            onFailure = { e ->
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR", "✗ Codex 로그인 실패: ${e.message}", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "FAILED", e.message))
            },
            onCancel = {
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "CANCELED"))
            },
        )
        return taskId
    }

    // v1.160.3 — spawnOpenCodeLogin(`opencode providers login`) 제거. 대화형 CLI 라 헤드리스
    // 컨테이너에서 stdin 대기로 hang 하고, runStreamingCommand 가 타임아웃 없이 waitFor 하여
    // env-setup 태스크 큐(projectId="env-setup")를 영구 점유 → 이후 API key 등록/설치 태스크가
    // 실행되지 못했다. z.ai 자격증명은 spawnOpenCodeApiKeyLogin(auth.json 직접 작성)로, 다른
    // provider 대화형 로그인은 카드 안내대로 `docker exec ... opencode providers login` 로.

    /**
     * v1.156.0 — z.ai coding plan API key 를 auth.json 에 직접 작성. opencode 의
     * `providers login` 이 대화형/브라우저 기반이라 서버 무인 환경에서 API key 직접 입력이
     * 더 확실. 기존 credential(다른 provider)은 보존 — zai-coding-plan 항목만 병합/갱신.
     * 파일은 0600 권한으로 저장(민감정보).
     */
    fun spawnOpenCodeApiKeyLogin(apiKey: String): String {
        val taskId = Ids.taskId()
        queue.submit(
            projectId = "env-setup",
            taskId = taskId,
            onStart = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "▶ OpenCode(z.ai) API key 등록 시작", clock.nowIso()))
            },
            executor = { _ ->
                withContext(Dispatchers.IO) {
                    writeOpenCodeAuth(apiKey)
                }
            },
            onSuccess = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "✓ z.ai coding plan API key 가 auth.json 에 저장됨", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "SUCCESS"))
            },
            onFailure = { e ->
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR", "✗ API key 등록 실패: ${e.message}", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "FAILED", e.message))
            },
            onCancel = {
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "CANCELED"))
            },
        )
        return taskId
    }

    /** auth.json(~/.local/share/opencode/auth.json) 에 zai-coding-plan credential 작성. */
    private fun writeOpenCodeAuth(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key 가 비어있습니다" }
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val authFile = java.nio.file.Path.of("/home/vibe/.local/share/opencode/auth.json")
        java.nio.file.Files.createDirectories(authFile.parent)
        val existing = runCatching {
            json.parseToJsonElement(java.nio.file.Files.readString(authFile)).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        val merged = existing.toMutableMap()
        merged["zai-coding-plan"] = buildJsonObject {
            put("type", "api")
            put("key", apiKey)
        }
        java.nio.file.Files.writeString(authFile, JsonObject(merged).toString())
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                authFile,
                java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        // API key 는 로그에 남기지 않는다 (민감정보).
    }

    private fun resolveOpenCodeCmd(): String {
        System.getenv("OPENCODE_CMD")?.takeIf { it.isNotBlank() }?.let { return it }
        // v1.160.2 — opencode 는 공식 install 스크립트가 /home/vibe/.opencode/bin 에 설치하는데
        // 이 경로가 서버 JVM 프로세스의 PATH 에 없어(=Dockerfile 이 .local/bin 만 추가) bare
        // "opencode" 로는 detect/spawn 이 실패한다. 설치 위치가 실행 가능하면 절대경로를 우선 쓴다.
        val installed = java.io.File("/home/vibe/.opencode/bin/opencode")
        if (installed.canExecute()) return installed.absolutePath
        return if (com.siamakerlab.vibecoder.server.core.OsType.detect() == com.siamakerlab.vibecoder.server.core.OsType.WINDOWS) "opencode.cmd" else "opencode"
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
        runStreamingCommand(taskId, cmd)
    }

    private suspend fun runStreamingCommand(
        taskId: String,
        cmd: List<String>,
        onLine: (suspend (String) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyCodexProcessEnv(pb)
        val process = pb.start()
        runCatching { process.outputStream.close() }
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val cleanLine = stripAnsi(line)
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "STDOUT", cleanLine, clock.nowIso()))
                onLine?.invoke(cleanLine)
            }
        }
        process.waitFor()
    }

    private fun stripAnsi(line: String): String =
        ANSI_REGEX.replace(line, "")

    private fun resolveDoctorCmd(): String {
        // 도커 이미지 안에서는 entrypoint 가 /usr/local/bin/vibe-doctor symlink 를 만든다.
        // 호스트에서 직접 띄우는 dev 환경은 PATH 에 vibe-doctor 가 없을 수 있다.
        return System.getenv("VIBE_DOCTOR_CMD")?.ifBlank { null } ?: "vibe-doctor"
    }

    private fun resolveCodexCmd(): String =
        System.getenv("CODEX_CMD")?.ifBlank { null } ?: "codex"

    private fun codexDeviceAuthCommand(): List<String> {
        if (System.getenv("CODEX_DEVICE_AUTH_USE_PTY")?.equals("0") == true) {
            return listOf(resolveCodexCmd(), "login", "--device-auth")
        }
        return listOf(
            "script",
            "-q",
            "-e",
            "-c",
            "${shellQuote(resolveCodexCmd())} login --device-auth",
            "/dev/null",
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun applyCodexProcessEnv(pb: ProcessBuilder) {
        pb.environment().putIfAbsent("HOME", "/home/vibe")
        pb.environment().putIfAbsent("XDG_CONFIG_HOME", "/home/vibe/.config")
        pb.environment().putIfAbsent("CODEX_HOME", "/home/vibe/.codex")
    }

    companion object {
        /** Gradle latest version 캐시 TTL — 30 분. */
        private const val GRADLE_LATEST_TTL_MS = 30L * 60 * 1000
        private const val DEFAULT_SSH_PORT = 2222
        private const val SSH_PORT_MIN = 1024
        private const val SSH_PORT_MAX = 65535
        private const val CODEX_DEVICE_LOGIN_URL = "https://auth.openai.com/codex/device"
        private val ANSI_REGEX = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val CODEX_DEVICE_CODE_REGEX = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4,5}\\b|\\b[A-Z0-9]{8,9}\\b")
    }
}
