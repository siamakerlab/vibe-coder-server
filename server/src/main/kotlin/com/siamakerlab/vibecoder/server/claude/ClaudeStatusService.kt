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
import java.util.concurrent.CountDownLatch
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
     * v1.46.0 — 단일 비행(single-flight) 가드. 같은 projectId 에 대해 동시/중첩 캡처를
     * 막아 expect+claude TUI 프로세스가 누적 spawn 되는 것을 방지.
     */
    private val capturing = ConcurrentHashMap<String, Unit>()

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

    /**
     * v1.46.0 — **요청 경로용 비차단 스냅샷**. 자식 프로세스를 절대 spawn 하지 않고
     * 마지막 캐시(만료됐어도)를 즉시 반환한다. 캐시가 없으면 account-global 인 usage 는
     * scratch 캐시로 폴백하고, busy/alive/sessionId 는 요청 projectId 의 실시간 상태로 덮어쓴다.
     *
     * 실제 캡처(usage 갱신)는 백그라운드 [ClaudeUsageMonitor] 가 주기(기본 5분)로만 [snapshot] 을
     * 호출해 수행한다. 이 분리로 quota/console-status 같은 HTTP 요청이 느린 TUI 캡처에
     * **절대 블록되지 않는다**(이전엔 캐시 미스 시 동기 캡처 → 25~80s hang).
     */
    fun cachedSnapshot(projectId: String): ClaudeStatusDto {
        val base = cache[projectId]?.dto ?: cache[SCRATCH]?.dto
        val dto = base ?: ClaudeStatusDto(updatedAt = Instant.now().toString())
        return dto.copy(
            busy = sessionManager.isBusy(projectId),
            processAlive = sessionManager.isAlive(projectId),
            sessionId = sessionManager.currentSessionId(projectId),
        )
    }

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

        // v1.46.0 — single-flight: 이미 같은 project 캡처가 진행 중이면 중복 spawn 하지 않고
        // 마지막 캐시(stale) 를 즉시 반환. (백그라운드 폴러 호출이 겹쳐도 프로세스 누적 0.)
        if (capturing.putIfAbsent(projectId, Unit) != null) {
            return cachedSnapshot(projectId)
        }
        try {
            return captureAndCache(projectId)
        } finally {
            capturing.remove(projectId)
        }
    }

    private suspend fun captureAndCache(projectId: String): ClaudeStatusDto {
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
        val initParsed = parseInitFrame(initRaw)
        // v1.5.1 — 사용자 요구: TUI capture 는 Claude 로그인 상태에서만 실행.
        // init frame 의 apiKeySource / model 이 null 이면 미인증 (또는 claude
        // CLI 실패) — usage capture spawn 안 함 (1분+ 소요 + 의미없는 결과).
        val authed = initParsed.plan != null || initParsed.model != null
        val usageRaw = if (authed) runUsageCapture(projectId) else ""
        val combined = buildString {
            append("--- /status init frame ---\n")
            append(initRaw)
            if (authed) {
                append("\n\n--- /usage TUI capture ---\n")
                append(usageRaw)
            } else {
                append("\n\n--- usage capture skipped (Claude not logged in) ---\n")
            }
        }
        rawSnapshots[projectId] = RawSnapshot(text = combined.take(64 * 1024), capturedAt = Instant.now())
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
        // system/init 줄을 받는 즉시 트리 종료(assistant 응답 frame 미대기 → cost/지연 최소).
        // 10s 내 미수신 시 타임아웃 + 트리 kill. (parseInitFrame 이 반환 텍스트에서 init 줄 추출.)
        return runWithHardTimeout(pb, timeoutSeconds = 10) { it.contains("\"type\":\"system\"") }
    }

    /**
     * v1.46.0 — 자식 프로세스를 **하드 타임아웃**으로 실행하고 stdout 을 수집한다.
     *
     * - stdout 읽기는 별도 daemon thread(pump)에서 수행 → 자식(특히 expect→claude TUI)이
     *   stdout 을 안 닫아도 본 함수가 블록되지 않는다(이전 `readText()`/`readLine()` 직접
     *   호출의 *read-before-timeout* 데드락 회수 — 23차 점검 패턴).
     * - 타임아웃 또는 [stopWhen] 충족 시 **프로세스 트리 전체**(자식·손자 = expect → claude
     *   TUI)를 `descendants().destroyForcibly()` 로 강제 종료 → orphan TUI 누적 방지.
     */
    private fun runWithHardTimeout(
        pb: ProcessBuilder,
        timeoutSeconds: Long,
        stopWhen: ((String) -> Boolean)? = null,
    ): String {
        return runCatching {
            val proc = pb.start()
            val sb = StringBuilder()
            val matched = CountDownLatch(1)
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            synchronized(sb) { sb.append(line).append('\n') }
                            if (stopWhen != null && stopWhen(line)) { matched.countDown(); break }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            if (stopWhen != null) matched.await(timeoutSeconds, TimeUnit.SECONDS)
            else proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (proc.isAlive) {
                runCatching { proc.descendants().forEach { it.destroyForcibly() } }
                proc.destroyForcibly()
            }
            pump.join(1500)
            synchronized(sb) { sb.toString() }
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
        // v1.43.0 — stdout(usage capture 결과)은 읽고 stderr 는 DISCARD. 이전엔 stderr 미배수로
        // expect 진단 출력이 파이프를 채우면 readText 데드락 가능(25초 상한이 있으나 매번 timeout).
        val pb = ProcessBuilder(expectBin, "-f", scriptPath, workDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
        // v1.46.0 — pump thread + 하드 타임아웃 + 트리 kill. 이전엔 readText() 가 waitFor 보다
        // 먼저라, TUI 가 stdout 을 안 닫으면 readText 가 무한 블록(quota 25~80s hang) + expect 를
        // 죽여도 자식 claude TUI 가 orphan 으로 누적되던 문제를 회수.
        return runWithHardTimeout(pb, timeoutSeconds = 25)
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
        // v1.5.1 — ANSI escape 를 공백으로 치환 (이전엔 "" → "Current session"
        // 이 "Currentsession" 으로 합쳐져 parsing 실패). 연속 공백 통합으로 단어
        // 사이 1칸 유지.
        // ANSI: ESC [ ... m / ESC ] ... BEL 등 일반 패턴.
        val ansi = Regex("\\[[0-?]*[ -/]*[@-~]|\\][^]*?")
        val boxChars = Regex("[─━│┃┄┅┆┇┈┉┊┋┌┍┎┏┐┑┒┓└┕┖┗┘┙┚┛├┝┞┟┠┡┢┣┤┥┦┧┨┩┪┫┬┭┮┯┰┱┲┳┴┵┶┷┸┹┺┻┼┽┾┿╀╁╂╃╄╅╆╇╈╉╊╋╌╍╎╏═║╒╓╔╕╖╗╘╙╚╛╜╝╞╟╠╡╢╣╤╥╦╧╨╩╪╫╬█▌▐░▒▓●▁▂▃▄▅▆▇]")
        return s.replace(ansi, " ").replace(boxChars, " ")
            .replace(Regex("[ \\t]+"), " ")
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
        // v1.5.2 — 라인 단위 매칭 폐기. ANSI cursor positioning 이 strip 후 단어
        // 일부를 소실시키는 케이스 (예: "Current session" → "Curret session", n
        // 글자 사라짐) 가 흔해서 line.contains("current session") 매칭 fail.
        //
        // 대신 % 매치 좌측 context window (60 chars) 에서 키워드 검사:
        //   - "sess" 가 있고 "week" 가 없으면 → session
        //   - "week" 가 있고 "sess" 가 없으면 → weekly
        //   - "sonnet" 가 있으면 skip (변종)
        // % 매치 직후 같은 줄 또는 다음 100 chars 안의 "Resets <text>" 와 짝지음.
        val percentRegex = Regex("(\\d{1,3})\\s*%\\s*used", RegexOption.IGNORE_CASE)
        val resetRegex = Regex("Resets\\s+([^\\n\\r]+?)(?=\\s{2,}|[\\n\\r]|$)", RegexOption.IGNORE_CASE)

        var sessionUsage: Int? = null
        var weeklyUsage: Int? = null
        var sessionReset: String? = null
        var weeklyReset: String? = null

        for (m in percentRegex.findAll(raw)) {
            val pct = m.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: continue
            // 좌측 60 chars context window
            val ctxStart = (m.range.first - 60).coerceAtLeast(0)
            val ctxLower = raw.substring(ctxStart, m.range.first).lowercase()
            // 한 줄 안에 헤더 다음 % 가 같이 나오는 경우 흔하므로, 가장 가까운
            // 키워드 우선 — context 의 마지막 (= % 직전) 에서 검색.
            val sessIdx = ctxLower.lastIndexOf("sess")
            val weekIdx = ctxLower.lastIndexOf("week")
            val sonnet = ctxLower.contains("sonnet")
            if (sonnet) continue
            val isSession = sessIdx >= 0 && sessIdx > weekIdx
            val isWeek = weekIdx >= 0 && weekIdx > sessIdx
            if (!isSession && !isWeek) continue

            // % 매치 직후 100 chars 안에서 첫 Resets 찾기.
            val resetCtxEnd = (m.range.last + 100).coerceAtMost(raw.length - 1)
            val resetCtx = raw.substring(m.range.last + 1, resetCtxEnd + 1)
            val resetMatch = resetRegex.find(resetCtx)?.groupValues?.get(1)?.trim()
            val resetText = resetMatch?.let { "Resets $it" }

            if (isSession) {
                if (sessionUsage == null) sessionUsage = pct
                if (sessionReset == null && resetText != null) sessionReset = resetText
            } else if (isWeek) {
                if (weeklyUsage == null) weeklyUsage = pct
                if (weeklyReset == null && resetText != null) weeklyReset = resetText
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

    private companion object {
        /** General Chat ghost 프로젝트 id. usage(account-global) 폴백 캐시 키. */
        const val SCRATCH = "__scratch__"
    }
}
