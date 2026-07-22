package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists

class EnvDiagnostics(private val config: ServerConfig) {

    /**
     * v1.25.2 — Q4 회수: service-to-service / JSON API entry 등 사용자 lang 모르는
     * 호출자는 `run()` overload 사용 → 서버 default language 자동. 이전엔 `"en"`
     * hardcode 6 site 였음.
     */
    fun run(): EnvironmentCheckDto = run(config.i18n.defaultLanguage)

    /**
     * v1.7.15 — lang 받아 message / detail 을 i18n 키 기반으로 emit. 호출자가
     * sess.language 전달. API endpoint 등 lang 없는 호출자는 [run] no-arg overload 사용.
     */
    fun run(lang: String): EnvironmentCheckDto {
        val cli = checkClaude(lang)
        return EnvironmentCheckDto(
            java = checkJava(lang),
            androidSdk = checkAndroidSdk(lang),
            git = checkGit(lang),
            claude = cli,
            workspace = checkWorkspace(lang),
            claudeAuth = checkClaudeAuth(cli, lang),
        )
    }

    private fun t(lang: String, key: String, vararg args: Any?): String =
        com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, *args)

    private fun checkJava(lang: String): CheckItemDto {
        val version = runtimeCommand(listOf("java", "-version"))
        return if (version.exitCode == 0) {
            CheckItemDto(CheckStatus.OK, "JDK", t(lang, "diag.jdk.ok"), detail = version.combined.take(200))
        } else {
            CheckItemDto(CheckStatus.ERROR, "JDK", t(lang, "diag.jdk.notFound"), detail = version.combined.take(200))
        }
    }

    private fun checkAndroidSdk(lang: String): CheckItemDto {
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return when {
            sdkRoot.isNullOrBlank() ->
                CheckItemDto(
                    CheckStatus.WARNING, "Android SDK",
                    t(lang, "diag.sdk.warnMissing"),
                    detail = "Gradle builds will fail unless local.properties sdk.dir is set per project."
                )
            !Path.of(sdkRoot).exists() ->
                CheckItemDto(
                    CheckStatus.ERROR, "Android SDK",
                    t(lang, "diag.sdk.errInvalid") + ": $sdkRoot", detail = null
                )
            else ->
                CheckItemDto(CheckStatus.OK, "Android SDK", t(lang, "diag.sdk.ok", sdkRoot), detail = null)
        }
    }

    private fun checkGit(lang: String): CheckItemDto {
        val v = runtimeCommand(listOf("git", "--version"))
        return if (v.exitCode == 0)
            CheckItemDto(CheckStatus.OK, "Git", v.combined.trim().ifBlank { t(lang, "diag.git.ok") }, detail = null)
        else
            CheckItemDto(CheckStatus.ERROR, "Git", t(lang, "diag.git.notFound"), detail = v.combined.take(200))
    }

    private fun checkClaude(lang: String): CheckItemDto {
        if (!config.claude.enabled)
            return CheckItemDto(CheckStatus.WARNING, "Claude Code", t(lang, "diag.claudeCli.disabled"))
        val cmd = resolveClaudeCmd()
        val v = runtimeCommand(listOf(cmd, "--version"))
        return when {
            v.exitCode == 0 ->
                CheckItemDto(CheckStatus.OK, "Claude Code", v.combined.trim().ifBlank { t(lang, "diag.claudeCli.ok") }, detail = "cmd=$cmd")
            v.looksLikeMissingExecutable() ->
                CheckItemDto(CheckStatus.ERROR, "Claude Code", t(lang, "diag.claudeCli.notFound"), detail = t(lang, "diag.claudeCli.tried", cmd))
            else ->
                CheckItemDto(
                    CheckStatus.WARNING,
                    "Claude Code",
                    t(lang, "diag.claudeCli.versionCheckFailed"),
                    detail = "cmd=$cmd --version, exit=${v.exitCode}, output=${v.combined.take(200)}",
                )
        }
    }

    /**
     * Claude CLI 로그인 상태 진단.
     *
     * 판정:
     * - CLI 미설치 → ERROR.
     * - `~/.claude/.credentials.json` (또는 `CLAUDE_CONFIG_DIR/.credentials.json`)
     *   없음 → ERROR + `claude login` 가이드.
     * - 파일은 있지만 안의 `claudeAiOauth.expiresAt` (epoch ms) 가 현재보다
     *   과거 → ERROR ("토큰 만료"). 사용자가 콘솔에서 "Not logged in" 을 받는
     *   가장 흔한 원인.
     * - 만료 시각 6시간 이내 → WARNING (곧 만료 예정 안내).
     * - 그 외 정상 → OK.
     *
     * v0.6.2 변경: 단순 파일 존재 → expiresAt 까지 파싱. v0.5.4 ~ v0.6.1 에서
     * 만료된 자격증명을 들고 콘솔에서 "Not logged in" 을 받는데도 빌드환경
     * 페이지에서 "로그인됨" 으로 표시되던 false positive 해결.
     */
    private fun checkClaudeAuth(cli: CheckItemDto, lang: String): CheckItemDto {
        if (!config.claude.enabled) {
            return CheckItemDto(CheckStatus.WARNING, "Claude Auth", t(lang, "diag.claudeAuth.disabled"))
        }
        if (cli.status == CheckStatus.ERROR) {
            return CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                t(lang, "diag.claudeAuth.cliMissing"),
                detail = t(lang, "diag.claudeAuth.cliMissingDetail"),
            )
        }

        val cfg = claudeConfigDir()
        // v0.7.0 — API 키 모드 (.env.api-key 등록) 가 OAuth 자격증명 검사보다 우선.
        val apiKey = ClaudeProcessEnv.readApiKey(cfg.resolve(".env.api-key"))
        if (apiKey != null) {
            return CheckItemDto(
                CheckStatus.OK, "Claude Auth",
                t(lang, "diag.claudeAuth.apiKey"),
                detail = t(lang, "diag.claudeAuth.apiKeyDetail", cfg.resolve(".env.api-key").toString(), apiKey.length),
            )
        }
        val credentials = cfg.resolve(".credentials.json")
        if (!credentials.exists()) {
            val stray = findStrayCredentials(cfg)
            return if (stray != null) {
                CheckItemDto(
                    CheckStatus.ERROR, "Claude Auth",
                    t(lang, "diag.claudeAuth.strayRoot"),
                    detail = t(lang, "diag.claudeAuth.strayRootDetail", stray.toString(), credentials.toString()),
                )
            } else {
                CheckItemDto(
                    CheckStatus.ERROR, "Claude Auth",
                    t(lang, "diag.claudeAuth.loginRequired"),
                    detail = buildClaudeAuthHelp(cfg, lang),
                )
            }
        }

        val expiresAt = readOauthExpiresAt(credentials)
        val hasRefresh = readOauthRefreshToken(credentials) != null
        if (expiresAt == null) {
            return if (hasRefresh) {
                CheckItemDto(
                    CheckStatus.OK, "Claude Auth",
                    t(lang, "diag.claudeAuth.refreshOnlyOk"),
                    detail = credentials.toString(),
                )
            } else {
                CheckItemDto(
                    CheckStatus.WARNING, "Claude Auth",
                    t(lang, "diag.claudeAuth.parseWarn"),
                    detail = t(lang, "diag.claudeAuth.parseWarnDetail", credentials.toString()),
                )
            }
        }

        val nowMs = System.currentTimeMillis()
        val expiryStr = formatInstant(expiresAt)
        val IMMINENT_MS = 30 * 60 * 1000L
        return when {
            expiresAt <= nowMs && !hasRefresh -> CheckItemDto(
                CheckStatus.ERROR, "Claude Auth",
                t(lang, "diag.claudeAuth.expiredHard", expiryStr),
                detail = t(lang, "diag.claudeAuth.expiredHardDetail", buildClaudeAuthHelp(cfg, lang), credentials.toString()),
            )
            expiresAt <= nowMs -> CheckItemDto(
                CheckStatus.OK, "Claude Auth",
                t(lang, "diag.claudeAuth.expiredRefresh"),
                detail = credentials.toString(),
            )
            expiresAt - nowMs < IMMINENT_MS && !hasRefresh -> CheckItemDto(
                CheckStatus.WARNING, "Claude Auth",
                t(lang, "diag.claudeAuth.imminent", expiryStr),
                detail = t(lang, "diag.claudeAuth.imminentDetail"),
            )
            else -> CheckItemDto(
                CheckStatus.OK, "Claude Auth",
                t(lang, "diag.claudeAuth.okExpiry", expiryStr),
                detail = credentials.toString(),
            )
        }
    }

    private fun readOauthRefreshToken(file: Path): String? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject ?: return null
        val oauth = root["claudeAiOauth"] as? kotlinx.serialization.json.JsonObject ?: return null
        (oauth["refreshToken"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /**
     * `.credentials.json` 안의 `claudeAiOauth.expiresAt` (epoch ms) 추출.
     * 파일 없음 / 형식 변경 등 어떤 실패에도 null 반환 — 호출자가 처리.
     */
    private fun readOauthExpiresAt(file: Path): Long? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val oauth = root["claudeAiOauth"]?.jsonObject ?: return null
        oauth["expiresAt"]?.jsonPrimitive?.longOrNull
    } catch (_: Throwable) {
        null
    }

    private fun formatInstant(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))

    /**
     * `CLAUDE_CONFIG_DIR` env 우선, 없으면 OS 별 기본 (`~/.claude`).
     * 도커 컨테이너에선 entrypoint 가 `CLAUDE_CONFIG_DIR=/home/vibe/.claude` 를 export.
     */
    private fun claudeConfigDir(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home).resolve(".claude")
    }

    /**
     * `docker exec` 의 기본 사용자가 root 라서 vibe 가 아닌 root home 에 토큰이
     * 저장되는 흔한 실수를 잡는다. 우리가 보는 [cfg] 가 vibe 의 홈이 아닌 다른
     * 후보 경로(`/root/.claude`) 에 `.credentials.json` 이 있으면 반환.
     */
    private fun findStrayCredentials(currentCfg: Path): Path? {
        val candidates = listOf("/root/.claude/.credentials.json")
        return candidates
            .map { Path.of(it) }
            .firstOrNull { it != currentCfg.resolve(".credentials.json") && it.exists() }
    }

    /** v1.7.17 — buildClaudeAuthHelp 도 lang 받음. 호출자가 같은 lang 전달. */
    private fun buildClaudeAuthHelp(cfg: Path, lang: String): String =
        com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "diag.claudeAuth.help", cfg.toString())

    private fun checkWorkspace(lang: String): CheckItemDto {
        val root = Path.of(config.workspace.root)
        return try {
            if (!root.exists()) Files.createDirectories(root)
            val probe = Files.createTempFile(root, "probe-", ".tmp")
            Files.deleteIfExists(probe)
            CheckItemDto(CheckStatus.OK, "Workspace", t(lang, "diag.workspace.ok"), detail = root.toAbsolutePath().toString())
        } catch (e: Throwable) {
            CheckItemDto(CheckStatus.ERROR, "Workspace", t(lang, "diag.workspace.fail", root.toAbsolutePath().toString()), detail = e.message)
        }
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private data class Captured(val exitCode: Int, val combined: String)

    private fun Captured.looksLikeMissingExecutable(): Boolean =
        exitCode == -1 && combined.contains(
            Regex("Cannot run program|No such file|error=2|CreateProcess error=2", RegexOption.IGNORE_CASE)
        )

    private fun runtimeCommand(cmd: List<String>): Captured =
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // give destroyForcibly a moment to land so exitValue() doesn't
                // throw IllegalThreadStateException on a still-alive process.
                p.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            val exit = runCatching { p.exitValue() }.getOrDefault(-1)
            Captured(exit, out)
        } catch (e: Throwable) {
            Captured(-1, e.message ?: e.javaClass.simpleName)
        }
}
