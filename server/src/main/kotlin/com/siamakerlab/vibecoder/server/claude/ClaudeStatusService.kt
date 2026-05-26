package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * Side-channel snapshot of Claude's `/status`-equivalent output.
 *
 * Strategy: run `claude /status` as a one-shot subprocess (NOT in the persistent
 * stream session) with a small timeout, parse a few well-known fields out of the
 * plaintext output, and cache the result per project for 60s.
 *
 * Output formats change between Claude releases; we extract best-effort and leave
 * unknowns as null so the UI gracefully degrades.
 */
class ClaudeStatusService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val sessionManager: ClaudeSessionManager,
    private val ttl: Duration = Duration.ofSeconds(60),
) {

    private data class Cached(val dto: ClaudeStatusDto, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()

    /**
     * v0.47.0 — last raw `/status` output per project. The structured fields above only keep
     * a handful of best-effort extractions; the raw text contains additional info Anthropic
     * occasionally ships (prompt cache hit/miss counts, billing context) that we don't want
     * to lose. `/usage` page renders this verbatim.
     */
    private val rawSnapshots = ConcurrentHashMap<String, RawSnapshot>()

    data class RawSnapshot(val text: String, val capturedAt: Instant)

    fun lastRawSnapshot(projectId: String): RawSnapshot? = rawSnapshots[projectId]

    fun allRawSnapshots(): Map<String, RawSnapshot> = rawSnapshots.toMap()

    suspend fun snapshot(projectId: String): ClaudeStatusDto {
        val cached = cache[projectId]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            // v0.98.0 — busy/processAlive/sessionId 는 자주 바뀌므로 cache hit 시에도 fresh.
            // 60s cached 항목은 /status CLI 호출 결과 (model/plan/quota/resetAt) 만.
            return cached.dto.copy(
                busy = sessionManager.isBusy(projectId),
                processAlive = sessionManager.isAlive(projectId),
                sessionId = sessionManager.currentSessionId(projectId),
            )
        }

        val sessionId = sessionManager.currentSessionId(projectId)
        val alive = sessionManager.isAlive(projectId)
        val parsed = runCatching { runStatusCommand(projectId) }
            .getOrElse {
                log.debug(it) { "[$projectId] /status invocation failed; falling back to session-manager state" }
                ParsedStatus(model = null, plan = null, quotaRemaining = null, usagePercent = null, resetAt = null)
            }
        val dto = ClaudeStatusDto(
            sessionId = sessionId,
            processAlive = alive,
            model = parsed.model,
            plan = parsed.plan,
            quotaRemaining = parsed.quotaRemaining,
            usagePercent = parsed.usagePercent,
            resetAt = parsed.resetAt,
            updatedAt = Instant.now().toString(),
            // v0.98.0 — Android / REST 폴링 클라이언트가 응답중/대기중 즉시 확인.
            busy = sessionManager.isBusy(projectId),
            // v1.0.1 — Pro/Max plan 의 세션 (5h) vs 주간 (7d) quota 분리 노출.
            sessionUsagePercent = parsed.sessionUsagePercent,
            weeklyUsagePercent = parsed.weeklyUsagePercent,
            sessionResetAt = parsed.sessionResetAt,
            weeklyResetAt = parsed.weeklyResetAt,
        )
        // v0.98.0 — busy 는 자주 바뀌므로 cache hit 시 busy 만 fresh 로 덮어쓰기 (sessionId/processAlive 도).
        cache[projectId] = Cached(dto, Instant.now().plus(ttl))
        return dto
    }

    private suspend fun runStatusCommand(projectId: String): ParsedStatus = withContext(Dispatchers.IO) {
        // v1.4.0 — Claude Code 2.1.x 부터 모든 slash command 가 --print 모드에서
        // 차단됨 ("/status isn't available in this environment."). quota 정보는
        // interactive TUI 의 Usage 탭에서만 보임.
        //
        // 두 가지 path 병행:
        //   1) `--print --output-format=stream-json --verbose ""` (빈 prompt) →
        //      init frame 에서 model / apiKeySource / mcp_servers 등 메타데이터.
        //      cheap (0.1s), 항상 안정적.
        //   2) `claude-usage-capture.exp` PTY script → TUI Usage 탭 화면 capture.
        //      ANSI escape + box-drawing 섞여 들어옴. stripAnsi + parseUsageOutput
        //      이 정리. fragile (Claude UI 변경 시 깨짐), ~5s 소요.
        //
        // 두 결과를 합쳐 ClaudeStatusDto 채움.
        val initRaw = runInitFrame(projectId)
        val usageRaw = runUsageCapture(projectId)
        val combined = buildString {
            append("--- /status init frame ---\n")
            append(initRaw)
            append("\n\n--- /usage TUI capture ---\n")
            append(usageRaw)
        }
        rawSnapshots[projectId] = RawSnapshot(text = combined.take(64 * 1024), capturedAt = Instant.now())
        val initParsed = parseInitFrame(initRaw)
        val usageParsed = parseUsageOutput(stripAnsiAndBoxChars(usageRaw))
        initParsed.merge(usageParsed)
    }

    /**
     * v1.4.1 — `claude --print --output-format=stream-json --verbose "hi"` 호출.
     * `--print ""` (빈 prompt) 는 "Input must be provided" 에러로 거절됨.
     * dummy "hi" prompt 사용 + stdout 의 **첫 system/init JSON 줄만** 추출 후
     * 즉시 process kill — Claude 가 "hi" 응답하기 전에 종료 → cost 최소화.
     */
    private fun runInitFrame(projectId: String): String {
        val cmd = resolveClaudeCmd()
        val projectRoot = workspace.projectRoot(projectId).toFile()
        val workDir = if (projectRoot.isDirectory) projectRoot else workspace.root.toFile()
        val pb = ProcessBuilder(
            cmd, "--print", "--output-format=stream-json", "--verbose",
            "--dangerously-skip-permissions", "hi",
        ).directory(workDir).redirectErrorStream(true)
        com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
        return runCatching {
            val proc = pb.start()
            // 첫 줄 (system/init frame) 만 capture 후 process kill — assistant
            // 응답 frame 까지 기다리지 않아 cost / 지연 둘 다 최소.
            val firstLine = proc.inputStream.bufferedReader().use { reader ->
                val deadline = System.currentTimeMillis() + 8_000L
                var line: String? = null
                while (System.currentTimeMillis() < deadline) {
                    line = reader.readLine() ?: break
                    if (line.contains("\"type\":\"system\"")) break
                    line = null
                }
                line.orEmpty()
            }
            if (proc.isAlive) proc.destroyForcibly()
            firstLine
        }.getOrDefault("")
    }

    /**
     * v1.4.0 — expect script 로 Claude TUI 의 Usage 탭 화면 capture.
     * v1.4.1 — `env expect -f` shebang 이 env 의 옵션 처리 한계로 fail → `expect`
     * 바이너리 직접 호출. 스크립트 미설치 (dev 환경) 시 빈 문자열. timeout 25s.
     */
    private fun runUsageCapture(projectId: String): String {
        val scriptPath = "/usr/local/bin/claude-usage-capture.exp"
        val expectBin = "/usr/bin/expect"
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(scriptPath))) return ""
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(expectBin))) return ""
        val projectRoot = workspace.projectRoot(projectId).toFile()
        val workDir = if (projectRoot.isDirectory) projectRoot.toString() else workspace.root.toString()
        val pb = ProcessBuilder(expectBin, "-f", scriptPath, workDir).redirectErrorStream(false)
        com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
        return runCatching {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(25, TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly()
            output
        }.onFailure { log.debug(it) { "[$projectId] usage capture script failed" } }
            .getOrDefault("")
    }

    /**
     * v1.4.0 — `--print --output-format=stream-json --verbose ""` 결과의
     * 첫 줄 (system/init frame) 에서 model / apiKeySource 추출.
     *
     * frame 예:
     *   {"type":"system","subtype":"init","model":"claude-opus-4-7[1m]",
     *    "apiKeySource":"none","slash_commands":[...], "mcp_servers":[...]}
     */
    private fun parseInitFrame(raw: String): ParsedStatus {
        if (raw.isBlank()) return ParsedStatus(null, null, null, null, null)
        val initLine = raw.lineSequence().firstOrNull { it.contains("\"type\":\"system\"") && it.contains("\"subtype\":\"init\"") }
            ?: return ParsedStatus(null, null, null, null, null)
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(initLine) }.getOrNull()
            ?: return ParsedStatus(null, null, null, null, null)
        val obj = (json as? kotlinx.serialization.json.JsonObject) ?: return ParsedStatus(null, null, null, null, null)
        val model = (obj["model"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val keySrc = (obj["apiKeySource"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        // apiKeySource: "none" → subscription, "ANTHROPIC_API_KEY" / "user" → API key, etc.
        val plan = when (keySrc) {
            null, "" -> null
            "none" -> "Subscription (Pro/Max)"
            else -> "API key ($keySrc)"
        }
        return ParsedStatus(
            model = model,
            plan = plan,
            quotaRemaining = null,
            usagePercent = null,
            resetAt = null,
        )
    }

    /** v1.3.2 — single `claude --print /<slash-command>` 호출 helper. 실패 시 빈 문자열. */
    private fun runOneSlashCommand(projectId: String, slashCmd: String): String {
        val cmd = resolveClaudeCmd()
        val projectRoot = workspace.projectRoot(projectId)
        val pb = ProcessBuilder(cmd, "--print", "--dangerously-skip-permissions", slashCmd)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
        com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
        return runCatching {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, TimeUnit.SECONDS)
            if (proc.isAlive) proc.destroyForcibly()
            output
        }.getOrDefault("")
    }

    /**
     * v1.3.2 — ANSI escape sequences (`[...m`) + TUI box-drawing 문자 정리.
     * Claude Code 2.1.x 의 `--print /<slash>` 가 interactive TUI screen 을 그대로
     * stdout 으로 보내는 경우가 있어 raw 가 시각 노이즈로 가득.
     */
    private fun stripAnsiAndBoxChars(s: String): String {
        if (s.isEmpty()) return s
        // ANSI: ESC [ ... m / ESC ] ... BEL 등 일반 패턴.
        val ansi = Regex("\\[[0-?]*[ -/]*[@-~]|\\][^]*?")
        val boxChars = Regex("[─━│┃┄┅┆┇┈┉┊┋┌┍┎┏┐┑┒┓└┕┖┗┘┙┚┛├┝┞┟┠┡┢┣┤┥┦┧┨┩┪┫┬┭┮┯┰┱┲┳┴┵┶┷┸┹┺┻┼┽┾┿╀╁╂╃╄╅╆╇╈╉╊╋╌╍╎╏═║╒╓╔╕╖╗╘╙╚╛╜╝╞╟╠╡╢╣╤╥╦╧╨╩╪╫╬█▌▐░▒▓●▁▂▃▄▅▆▇]")
        return s.replace(ansi, "").replace(boxChars, "")
    }

    private data class ParsedStatus(
        val model: String?,
        val plan: String?,
        val quotaRemaining: String?,
        /** v0.21.0 — quota/usage 줄에서 추출한 사용량 percent. */
        val usagePercent: Int?,
        /** v0.21.0 — quota line 의 reset 시각 (free-form). */
        val resetAt: String?,
        /** v1.0.1 — 세션 (5시간) quota 사용량 %. */
        val sessionUsagePercent: Int? = null,
        /** v1.0.1 — weekly (7일) quota 사용량 %. */
        val weeklyUsagePercent: Int? = null,
        /** v1.0.1 — 세션 reset 시각 (free-form). */
        val sessionResetAt: String? = null,
        /** v1.0.1 — weekly reset 시각 (free-form). */
        val weeklyResetAt: String? = null,
    )

    /**
     * Best-effort `claude /status` parser.
     *
     * `claude /status` 출력 포맷은 릴리즈마다 미세하게 바뀌므로 정규식만으로 100%
     * 잡지 않는다. v0.21.0 에선 다음 휴리스틱:
     *   - "Model:" / "Plan:" 의 colon-separated value
     *   - "quota|remaining|usage" 포함 줄 전체를 quotaRemaining 으로 저장 (UI 표시용)
     *   - 같은 줄에 N% 패턴이 있으면 usagePercent 로 추출 (임계치 트리거용)
     *   - "reset" + "at|in" 포함 줄을 resetAt 으로 저장
     *
     * v1.0.1 — Pro/Max plan 의 세션 (5h) vs weekly (7d) quota 분리. 같은 줄에 또는
     * 직후 줄에 "weekly|week" 가 있으면 weeklyUsagePercent / weeklyResetAt 으로 분류.
     * 명시적으로 "session|5-hour" 가 있으면 sessionUsagePercent. 키워드 없으면 legacy
     * usagePercent 만 채움 (기존 동작 유지).
     */
    private fun parseOutput(raw: String): ParsedStatus {
        val lines = raw.lines()
        var model: String? = null
        var plan: String? = null
        var quota: String? = null
        var usagePercent: Int? = null
        var resetAt: String? = null
        var sessionUsage: Int? = null
        var weeklyUsage: Int? = null
        var sessionReset: String? = null
        var weeklyReset: String? = null
        val percentRegex = Regex("(\\d{1,3})%")
        for (line in lines) {
            val lower = line.lowercase()
            if (model == null && lower.contains("model")) model = line.substringAfter(":", "").trim().ifBlank { null }
            // v1.3.2 — Claude Code 2.1.x `/status` 는 "Plan:" 대신 "Login method:"
            // 같은 단어 사용 ("Claude Max account" / "Anthropic API key" 등).
            if (plan == null && (lower.contains("plan") || lower.contains("login method") || lower.contains("subscription"))) {
                plan = line.substringAfter(":", "").trim().ifBlank { null }
            }

            val hasQuotaKw = lower.contains("quota") || lower.contains("remaining") || lower.contains("usage")
            val isWeekly = lower.contains("weekly") || lower.contains("week")
            val isSession = lower.contains("session") || lower.contains("5-hour") || lower.contains("5 hour")

            if (hasQuotaKw) {
                if (quota == null) quota = line.trim().ifBlank { null }
                percentRegex.find(line)?.let { match ->
                    val n = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
                    if (n != null) {
                        val used = if (lower.contains("remaining")) 100 - n else n
                        when {
                            isWeekly -> { if (weeklyUsage == null) weeklyUsage = used }
                            isSession -> { if (sessionUsage == null) sessionUsage = used }
                            else -> { if (usagePercent == null) usagePercent = used }
                        }
                    }
                }
            }
            if (lower.contains("reset") && (lower.contains(" at") || lower.contains(" in"))) {
                val trimmed = line.trim().ifBlank { null }
                when {
                    isWeekly && weeklyReset == null -> weeklyReset = trimmed
                    isSession && sessionReset == null -> sessionReset = trimmed
                    resetAt == null -> resetAt = trimmed
                }
            }
        }
        // legacy usagePercent / resetAt 도 최대값 / fallback 으로 채워 backward compatible 유지.
        val legacyUsage = usagePercent ?: maxOf(sessionUsage ?: -1, weeklyUsage ?: -1).takeIf { it >= 0 }
        val legacyReset = resetAt ?: sessionReset ?: weeklyReset
        return ParsedStatus(
            model = model, plan = plan, quotaRemaining = quota,
            usagePercent = legacyUsage, resetAt = legacyReset,
            sessionUsagePercent = sessionUsage,
            weeklyUsagePercent = weeklyUsage,
            sessionResetAt = sessionReset,
            weeklyResetAt = weeklyReset,
        )
    }

    /**
     * v1.3.2 — `claude --print /usage` 출력 전용 파서.
     *
     * Claude Code 2.1.x 의 `/usage` 화면 구조:
     * ```
     *   Current session
     *   ███████▌                                  15% used
     *   Resets 10:20pm (Asia/Seoul)
     *
     *   Current week (all models)
     *   ▌                                          1% used
     *   Resets Jun 2, 6pm (Asia/Seoul)
     *
     *   Current week (Sonnet only)
     *                                              0% used
     * ```
     *
     * 섹션 헤더 ("Current session" / "Current week (all models)") 이후 3 줄 안에서
     * 첫 `\d+% used` 패턴과 첫 `Resets <text>` 라인을 추출. 정확히 같은 섹션 안의 것만
     * 짝지어 둠 (Sonnet only week 는 별도 fields 없으므로 skip).
     */
    private fun parseUsageOutput(raw: String): ParsedStatus {
        if (raw.isBlank()) return ParsedStatus(null, null, null, null, null)
        val lines = raw.lines().map { it.trim() }
        val percentRegex = Regex("(\\d{1,3})\\s*%\\s*used", RegexOption.IGNORE_CASE)
        val resetRegex = Regex("^Resets\\s+(.+)$", RegexOption.IGNORE_CASE)

        var sessionUsage: Int? = null
        var weeklyUsage: Int? = null
        var sessionReset: String? = null
        var weeklyReset: String? = null

        // 섹션 추적: "Current session" / "Current week (all models)" 헤더 발견 시 lookahead.
        for ((i, line) in lines.withIndex()) {
            val lower = line.lowercase()
            val isSessionHeader = lower == "current session" ||
                lower.startsWith("current session ") || lower.startsWith("current session:")
            // "Current week (all models)" 우선. "(sonnet only)" 등 변종은 무시.
            val isWeeklyHeader = lower.startsWith("current week (all models)") ||
                lower == "current week" || lower == "current week (all)"
            if (!isSessionHeader && !isWeeklyHeader) continue

            // lookahead 3 lines for percent + reset
            for (j in (i + 1)..minOf(i + 4, lines.lastIndex)) {
                val l = lines[j]
                val pct = percentRegex.find(l)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                if (pct != null) {
                    if (isSessionHeader && sessionUsage == null) sessionUsage = pct
                    if (isWeeklyHeader && weeklyUsage == null) weeklyUsage = pct
                }
                val reset = resetRegex.find(l)?.groupValues?.get(1)?.trim()
                if (reset != null) {
                    if (isSessionHeader && sessionReset == null) sessionReset = "Resets $reset"
                    if (isWeeklyHeader && weeklyReset == null) weeklyReset = "Resets $reset"
                }
            }
        }
        val legacyUsage = maxOf(sessionUsage ?: -1, weeklyUsage ?: -1).takeIf { it >= 0 }
        val legacyReset = sessionReset ?: weeklyReset
        return ParsedStatus(
            model = null, plan = null, quotaRemaining = null,
            usagePercent = legacyUsage, resetAt = legacyReset,
            sessionUsagePercent = sessionUsage,
            weeklyUsagePercent = weeklyUsage,
            sessionResetAt = sessionReset,
            weeklyResetAt = weeklyReset,
        )
    }

    /** v1.3.2 — 두 ParsedStatus (예: /status + /usage) 의 non-null 필드 우선 결합. */
    private fun ParsedStatus.merge(other: ParsedStatus): ParsedStatus = ParsedStatus(
        model = model ?: other.model,
        plan = plan ?: other.plan,
        quotaRemaining = quotaRemaining ?: other.quotaRemaining,
        usagePercent = usagePercent ?: other.usagePercent,
        resetAt = resetAt ?: other.resetAt,
        sessionUsagePercent = sessionUsagePercent ?: other.sessionUsagePercent,
        weeklyUsagePercent = weeklyUsagePercent ?: other.weeklyUsagePercent,
        sessionResetAt = sessionResetAt ?: other.sessionResetAt,
        weeklyResetAt = weeklyResetAt ?: other.weeklyResetAt,
    )

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }
}
