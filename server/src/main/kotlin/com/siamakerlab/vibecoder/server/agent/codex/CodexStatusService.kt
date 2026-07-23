package com.siamakerlab.vibecoder.server.agent.codex

import com.siamakerlab.vibecoder.server.agent.AgentUsageProvider
import com.siamakerlab.vibecoder.server.agent.AgentUsageSnapshot
import com.siamakerlab.vibecoder.server.config.CodexUsageSection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.notify.Notifiers
import com.siamakerlab.vibecoder.shared.dto.CodexUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val codexStatusLog = KotlinLogging.logger {}
private val CODEX_PERCENT_REGEX = Regex("\\b(\\d{1,3})\\s*%")

class CodexStatusService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val usageRecorder: CodexUsageRecorder,
) : AgentUsageProvider {

    /**
     * v1.147.0 — [AgentUsageProvider] 구현. Codex 는 계정 전역 quota 를 공유(단일 사용자 가정)
     * 하므로 [cachedSnapshot] 의 session/weekly % 를 projectId 와 무관하게 반환한다. provider 가
     * usage % 를 아직 관측하지 않은(캡처 전) 상태면 양쪽 다 null → [ScheduledPromptManager] 가
     * SESSION_RESET/WEEKLY_RESET 트리거를 보수적으로 보류한다.
     */
    override fun usageSnapshot(projectId: String): AgentUsageSnapshot? =
        codexUsageSnapshotFromDto(cachedSnapshot())

    private val latest = AtomicReference<CodexUsageDto?>(null)

    fun cachedSnapshot(): CodexUsageDto =
        latest.get() ?: CodexUsageDto(
            updatedAt = Instant.now().toString(),
            available = false,
            loginStatus = runLoginStatusOrNull(),
            usageSummary = "No Codex usage snapshot captured yet.",
        )

    suspend fun snapshot(): CodexUsageDto = withContext(Dispatchers.IO) {
        val raw = runCatching { runTuiCapture() }
            .onFailure { codexStatusLog.debug(it) { "Codex TUI usage capture failed: ${it.message}" } }
            .getOrDefault("")
        val parsed = parseCapture(raw)
        val fallback = usageRecorder.snapshot()
        val summary = parsed.usageSummary ?: fallback?.let {
            val parts = listOfNotNull(
                it.inputTokens?.let { n -> "input $n" },
                it.cachedInputTokens?.let { n -> "cached $n" },
                it.outputTokens?.let { n -> "output $n" },
                it.reasoningOutputTokens?.let { n -> "reasoning $n" },
            )
            if (parts.isEmpty()) null else "Last turn tokens: ${parts.joinToString(", ")}"
        }
        val loginStatus = parsed.loginStatus ?: runLoginStatusOrNull()
        val loggedIn = loginStatus?.contains("logged in", ignoreCase = true) == true
        val dto = parsed.copy(
            loginStatus = loginStatus,
            usageSummary = summary,
            updatedAt = Instant.now().toString(),
            available = loggedIn && (raw.isNotBlank() || fallback != null),
            raw = raw.take(32 * 1024).ifBlank { null },
        )
        latest.set(dto)
        dto
    }

    private fun runTuiCapture(): String {
        if (!isCommandAvailable(resolveCodexCmd())) return ""
        val scriptPath = System.getenv("CODEX_USAGE_CAPTURE_SCRIPT")?.ifBlank { null }
            ?: "/usr/local/bin/codex-usage-capture.exp"
        val script = java.nio.file.Path.of(scriptPath)
        val expect = java.nio.file.Path.of("/usr/bin/expect")
        if (!java.nio.file.Files.exists(script) || !java.nio.file.Files.exists(expect)) return ""
        val pb = ProcessBuilder(expect.toString(), "-f", script.toString(), workspace.root.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        applyCodexProcessEnv(pb)
        return runWithHardTimeout(pb, timeoutSeconds = 60)
    }

    private fun runLoginStatusOrNull(): String? = runCatching {
        val cmd = resolveCodexCmd()
        if (!isCommandAvailable(cmd)) return@runCatching null
        val pb = ProcessBuilder(cmd, "login", "status").redirectErrorStream(true)
        applyCodexProcessEnv(pb)
        runWithHardTimeout(pb, timeoutSeconds = 8)
            .lineSequence()
            .map { stripAnsi(it).trim() }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()

    private fun parseCapture(raw: String): CodexUsageDto {
        return parseCodexUsageCapture(raw)
    }

    private fun runWithHardTimeout(pb: ProcessBuilder, timeoutSeconds: Long): String {
        return runCatching {
            val proc = pb.start()
            val sb = StringBuilder()
            val done = CountDownLatch(1)
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            synchronized(sb) { sb.append(line).append('\n') }
                        }
                    }
                }
                done.countDown()
            }.apply { isDaemon = true; start() }
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (proc.isAlive) {
                runCatching { proc.descendants().forEach { it.destroyForcibly() } }
                proc.destroyForcibly()
            }
            done.await(1500, TimeUnit.MILLISECONDS)
            synchronized(sb) { sb.toString() }
        }.getOrDefault("")
    }

    private fun stripAnsi(line: String): String =
        ANSI_REGEX.replace(line, "")

    private fun resolveCodexCmd(): String =
        System.getenv("CODEX_CMD")?.takeIf { it.isNotBlank() }
            ?: if (OsType.detect() == OsType.WINDOWS) "codex.cmd" else "codex"

    private fun isCommandAvailable(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        if (cmd.contains('/') || cmd.contains('\\')) {
            return java.nio.file.Files.isExecutable(java.nio.file.Path.of(cmd))
        }
        val path = System.getenv("PATH").orEmpty()
        if (path.isBlank()) return false
        val extensions = if (OsType.detect() == OsType.WINDOWS) {
            System.getenv("PATHEXT").orEmpty()
                .split(';')
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(".exe", ".cmd", ".bat") }
        } else {
            listOf("")
        }
        return path.split(File.pathSeparatorChar).any { dir ->
            if (dir.isBlank()) return@any false
            extensions.any { ext ->
                java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, cmd + ext))
            }
        }
    }

    private fun applyCodexProcessEnv(pb: ProcessBuilder) {
        // v1.156.1 — putIfAbsent → put 강제 설정 (컨테이너 HOME=/root 회피).
        pb.environment()["HOME"] = "/home/vibe"
        pb.environment()["XDG_CONFIG_HOME"] = "/home/vibe/.config"
        pb.environment()["CODEX_HOME"] = "/home/vibe/.codex"
    }

    companion object {
        private val ANSI_REGEX = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
    }
}

internal fun parseCodexUsageCapture(raw: String): CodexUsageDto {
    val cleaned = normalizeCodexCapture(raw)
    val percents = CODEX_PERCENT_REGEX.findAll(cleaned).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
    val limitResetNotice = codexUsageLimitResetNotice(cleaned)
    val context = parseCodexLeftPercent(
        cleaned,
        Regex("(?i)Context\\s+window:\\s*(\\d{1,3})\\s*%\\s*left"),
    ) ?: Regex("(?i)(?:context|token)[^\\n]{0,80}?(\\d{1,3})%")
        .find(cleaned)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100)
    val limitLines = parseCodexLimitLines(cleaned)
    val session = limitLines.firstOrNull { it.kind == CodexLimitKind.SESSION }
        ?: limitLines.firstOrNull { it.kind == CodexLimitKind.UNKNOWN && limitLines.any { known -> known.kind == CodexLimitKind.WEEKLY } }
    val weekly = limitLines.firstOrNull { it.kind == CodexLimitKind.WEEKLY }
    val hasLimitGauges = session != null || weekly != null
    val reset = session?.resetAt ?: weekly?.resetAt ?: limitResetNotice ?: Regex("(?i)(?:reset|resets|limit reset)[^\\n.]{0,120}")
        .find(cleaned)?.value?.trim()
    val summary = codexWarningSummary(cleaned) ?: if (hasLimitGauges) {
        null
    } else {
        limitResetNotice ?: cleaned
            .lineSequence()
            .map { cleanCodexSummaryLine(it) }
            .firstOrNull {
                // v1.158.6 — 키워드 + 숫자/% 가 함께 있는 줄만 허용 (TUI 노이즈 필터링).
                // "Tip: Use /fast ... increased plan usage" 같은 팁 메시지가 summary 로 들어가는 회귀 방지.
                (it.contains("usage", ignoreCase = true) ||
                    it.contains("limit", ignoreCase = true) ||
                    it.contains("token", ignoreCase = true)) &&
                    Regex("\\d").containsMatchIn(it) &&
                    it.length <= 200
            }
    }
    val legacyUsage = listOfNotNull(session?.usagePercent, weekly?.usagePercent)
        .maxOrNull()
        ?: percents.firstOrNull()?.coerceIn(0, 100)
    return CodexUsageDto(
        usagePercent = legacyUsage,
        contextUsagePercent = context?.coerceIn(0, 100),
        rateLimitResetAt = reset,
        usageSummary = summary,
        loginStatus = null,
        updatedAt = Instant.now().toString(),
        available = raw.isNotBlank(),
        raw = cleaned.take(32 * 1024).ifBlank { null },
        sessionUsagePercent = session?.usagePercent,
        weeklyUsagePercent = weekly?.usagePercent,
        sessionResetAt = session?.resetAt,
        weeklyResetAt = weekly?.resetAt,
    )
}

private enum class CodexLimitKind {
    SESSION,
    WEEKLY,
    UNKNOWN,
}

private data class CodexLimitParse(
    val kind: CodexLimitKind,
    val label: String,
    val usagePercent: Int,
    val resetAt: String,
)

private fun parseCodexLimitLines(text: String): List<CodexLimitParse> =
    Regex(
        """(?im)^\s*([A-Za-z0-9][A-Za-z0-9\s._/-]{0,48}?\blimit)\s*:\s*(?:\[[^\]]+\]\s*)?(\d{1,3})\s*%\s*left\s*\(resets\s*([^)]+)\)""",
    ).findAll(text).mapNotNull { match ->
        val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val left = match.groupValues.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100) ?: return@mapNotNull null
        val resetAt = match.groupValues.getOrNull(3)?.trim().orEmpty()
        CodexLimitParse(
            kind = codexLimitKind(label),
            label = label,
            usagePercent = 100 - left,
            resetAt = resetAt,
        )
    }.toList()

private fun codexLimitKind(label: String): CodexLimitKind {
    val normalized = label.lowercase().replace(Regex("[_\\s-]+"), " ")
    return when {
        normalized.contains("weekly") || normalized.contains("week") || normalized.contains("7d") ||
            normalized.contains("7 day") -> CodexLimitKind.WEEKLY
        normalized.contains("session") || normalized.contains("5h") || normalized.contains("5 h") ||
            normalized.contains("5 hour") || normalized.contains("five hour") ||
            normalized.contains("hourly") -> CodexLimitKind.SESSION
        else -> CodexLimitKind.UNKNOWN
    }
}

private fun parseCodexLeftPercent(text: String, regex: Regex): Int? {
    val left = regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: return null
    return 100 - left
}

internal fun normalizeCodexCapture(raw: String): String {
    val stripped = stripCodexAnsi(raw)
        .replace("\r", "\n")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
    val joinedChars = buildString {
        val run = StringBuilder()
        for (line in stripped.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (line.isNotEmpty() && run.isNotEmpty() && !run.last().isWhitespace()) {
                    run.append(' ')
                }
                continue
            }
            if (trimmed.length <= 2) {
                run.append(trimmed)
            } else {
                if (run.isNotEmpty()) {
                    append(run.toString().trim()).append('\n')
                    run.clear()
                }
                append(trimmed).append('\n')
            }
        }
        if (run.isNotEmpty()) append(run.toString().trim()).append('\n')
    }
    return restoreCodexUsagePhrases(joinedChars)
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

internal fun cleanCodexSummaryLine(line: String): String =
    restoreCodexUsagePhrases(line)
        .replace(Regex("(?i)(?<=\\.)\\s*/status\\b.*$"), "")
        .replace(Regex("(?i)^\\s*/status\\b.*$"), "")
        .replace(Regex("^[\\s\\u2022\\u00B7*-]+(?=\\S)"), "")
        .replace(Regex("[ \\t]{2,}"), " ")
        .trim()

private fun codexUsageLimitResetNotice(text: String): String? =
    Regex(
        "(?i)You\\s+have\\s+(\\d+)\\s+usage\\s+limit\\s+resets\\s+available\\.\\s+Run\\s+/usage\\s+to\\s+use\\s+one\\.",
    ).find(text)?.let { m ->
        "You have ${m.groupValues[1]} usage limit resets available. Run /usage to use one."
    }

private fun codexWarningSummary(text: String): String? =
    Regex("(?im)^\\s*Warning:\\s*(.+)$").find(text)?.groupValues?.getOrNull(1)?.trim()

private fun stripCodexAnsi(line: String): String =
    Regex("\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)").replace(line, "")

private fun restoreCodexUsagePhrases(text: String): String {
    var restored = text
    restored = Regex(
        "(?i)You\\s*have\\s*(\\d+)\\s*usage\\s*limit\\s*resets\\s*available\\.\\s*Run\\s*/usage\\s*to\\s*use\\s*one\\.(?:\\s*/status\\b)?",
    ).replace(restored) { m ->
        "You have ${m.groupValues[1]} usage limit resets available. Run /usage to use one."
    }
    restored = Regex(
        "(?i)Youhave(\\d+)usagelimitresetsavailable\\.Run/usagetouseone\\.(?:/status\\b)?",
    ).replace(restored) { m ->
        "You have ${m.groupValues[1]} usage limit resets available. Run /usage to use one."
    }
    return restored
}

/**
 * v1.123.0 — Codex usage 백그라운드 폴링. v1.147.0 — 임계치 transition 기반 알림 추가
 * ([ClaudeUsageMonitor] 패턴 차용). Codex usage 는 session(5h)/weekly(7d) 두 게이지로 오며,
 * 임계치 판정은 **둘 중 큰 값**(둘 다 없으면 legacy usagePercent)을 기준으로 한다.
 *
 * 알림 정책 (transition 기반):
 *   - usage 가 `warnThresholdPercent` 이상으로 처음 올라간 순간 1회 발송.
 *   - 더 올라가서 `criticalThresholdPercent` 이상에 처음 도달했을 때 1회 발송.
 *   - 다시 아래로 내려가면(= reset 이후) 마지막 발송 상태 초기화 — 다음 cycle 재발송 가능.
 *   - 같은 transition 직후 재발송은 10분 cooldown 으로 차단.
 *
 * 단일 admin 가정 (CLAUDE.md §1). Codex 는 계정 전역 quota 공유이므로 projectId 무관.
 */
class CodexUsageMonitor(
    private val statusService: CodexStatusService,
    private val notifiers: Notifiers,
    private val configProvider: () -> CodexUsageSection,
    private val intervalProvider: () -> Duration = { Duration.ofMinutes(5) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** "warn" / "critical" / null (아직 임계치 미도달 또는 reset 이후). */
    private val lastAlertLevel = AtomicReference<String?>(null)
    /** 마지막 알림 발송 시점. 같은 임계치 transition 직후 재발송을 안전하게 차단. */
    private val lastAlertAt = AtomicReference<Instant?>(null)

    /** v1.147.0 — 마지막 폴링 결과 (UI 대시보드가 즉시 보여줄 수 있도록 캐시). */
    @Volatile
    private var lastSnapshot: CodexUsageDto? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            codexStatusLog.info { "Codex usage monitor started" }
            while (isActive) {
                runCatching { tick() }
                    .onFailure { codexStatusLog.debug(it) { "Codex usage monitor tick failed: ${it.message}" } }
                kotlinx.coroutines.delay(intervalProvider().toMillis().coerceAtLeast(60_000L))
            }
        }
    }

    private suspend fun tick() {
        val cfg = configProvider()
        if (!cfg.enabled) return

        // 캡처는 enabled 일 때만 수행한다. Codex TUI capture 는 PTY + expect 기반이라
        // Apple Silicon Docker Desktop 같은 환경에서 체감 지연을 만들 수 있다.
        runCatching { statusService.snapshot() }
            .onFailure { codexStatusLog.debug(it) { "Codex usage snapshot tick failed: ${it.message}" } }

        val dto = statusService.cachedSnapshot()
        lastSnapshot = dto

        // session/weekly 중 큰 값; 둘 다 null 이면 legacy usagePercent; 그것도 null 이면 skip.
        val pct = codexUsageEffectivePercent(dto) ?: return
        val session = dto.sessionUsagePercent
        val weekly = dto.weeklyUsagePercent

        // 임계치 transition 판정 (ClaudeUsageMonitor 와 동일 정책) — 순수 함수로 위임.
        when (val t = codexUsageAlertTransition(pct, lastAlertLevel.get(), cfg)) {
            is CodexUsageAlertDecision.BelowThreshold -> {
                if (lastAlertLevel.get() != null) {
                    codexStatusLog.info { "Codex usage dropped below thresholds ($pct%). Reset alert state." }
                    lastAlertLevel.set(null)
                    lastAlertAt.set(null)
                }
            }
            is CodexUsageAlertDecision.NoFire -> Unit
            is CodexUsageAlertDecision.Fire -> {
                // 너무 잦은 재발송 방지 — 최소 10분 간격.
                val now = Instant.now()
                val last = lastAlertAt.get()
                if (last != null && Duration.between(last, now).toMinutes() < 10) return

                codexStatusLog.info { "Codex usage threshold transition → ${t.level} (usage=$pct%). Firing notifications." }
                val reset = dto.sessionResetAt ?: dto.weeklyResetAt ?: dto.rateLimitResetAt
                notifiers.codexUsageWarn(pct, session, weekly, reset)
                lastAlertLevel.set(t.level)
                lastAlertAt.set(now)
            }
        }
    }

    fun snapshot(): CodexUsageDto = lastSnapshot ?: statusService.cachedSnapshot()

    fun shutdown() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}

/**
 * v1.147.0 — [CodexUsageDto] 에서 임계치 판정에 쓸 대표 usage % 추출.
 * session(5h)/weekly(7d) 중 큰 값; 둘 다 null 이면 legacy usagePercent; 그것도 null 이면 null.
 */
internal fun codexUsageEffectivePercent(dto: CodexUsageDto): Int? =
    listOfNotNull(dto.sessionUsagePercent, dto.weeklyUsagePercent, dto.usagePercent).maxOrNull()

/**
 * v1.147.0 — [CodexStatusService.usageSnapshot] 이 [CodexUsageDto] → [AgentUsageSnapshot] 변환에 사용.
 * session/weekly 가 둘 다 null 이면 null 반환 (관측 불가 → [ScheduledPromptManager] 보류).
 */
internal fun codexUsageSnapshotFromDto(dto: CodexUsageDto): AgentUsageSnapshot? {
    val session = dto.sessionUsagePercent ?: dto.usagePercent
    val weekly = dto.weeklyUsagePercent ?: dto.usagePercent
    if (session == null && weekly == null) return null
    return AgentUsageSnapshot(sessionUsagePercent = session, weeklyUsagePercent = weekly)
}

/**
 * v1.147.0 — 임계치 transition 판정 (순수 함수, 단위 테스트 가능).
 * [ClaudeUsageMonitor] 와 동일 정책: 처음 임계치 진입 시 발사, warn→critical 승격 시 발사,
 * 같은 레벨 유지 시 미발사, 임계치 이탈 시 reset 신호.
 */
internal sealed interface CodexUsageAlertDecision {
    /** usage 가 임계치 아래 (이전 알림 상태가 있으면 reset). */
    data object BelowThreshold : CodexUsageAlertDecision
    /** 같은 레벨 유지 중 — 발사 안 함. [level] 은 현재 도달한 임계치 ("warn"/"critical"). */
    data class NoFire(val level: String) : CodexUsageAlertDecision
    /** transition 발생 — 발사. [level] 은 새로 도달한 임계치. */
    data class Fire(val level: String) : CodexUsageAlertDecision
}

internal fun codexUsageAlertTransition(
    usedPercent: Int,
    prior: String?,
    cfg: CodexUsageSection,
): CodexUsageAlertDecision {
    val current = when {
        usedPercent >= cfg.criticalThresholdPercent -> "critical"
        usedPercent >= cfg.warnThresholdPercent -> "warn"
        else -> null
    } ?: return CodexUsageAlertDecision.BelowThreshold
    val shouldFire = when {
        prior == null -> true
        prior == "warn" && current == "critical" -> true
        else -> false
    }
    return if (shouldFire) CodexUsageAlertDecision.Fire(current)
    else CodexUsageAlertDecision.NoFire(current)
}
