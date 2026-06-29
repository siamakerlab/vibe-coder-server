package com.siamakerlab.vibecoder.server.agent.codex

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.CodexUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val codexStatusLog = KotlinLogging.logger {}
private val CODEX_PERCENT_REGEX = Regex("\\b(\\d{1,3})\\s*%")

class CodexStatusService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val usageRecorder: CodexUsageRecorder,
) {
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
        val pb = ProcessBuilder(resolveCodexCmd(), "login", "status").redirectErrorStream(true)
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

    private fun applyCodexProcessEnv(pb: ProcessBuilder) {
        pb.environment().putIfAbsent("HOME", "/home/vibe")
        pb.environment().putIfAbsent("XDG_CONFIG_HOME", "/home/vibe/.config")
        pb.environment().putIfAbsent("CODEX_HOME", "/home/vibe/.config/codex")
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
    val session = parseCodexLimitLine(cleaned, Regex("(?i)5h\\s+limit:\\s*(?:\\[[^\\]]+\\]\\s*)?(\\d{1,3})\\s*%\\s*left\\s*\\(resets\\s*([^)]*)\\)"))
    val weekly = parseCodexLimitLine(cleaned, Regex("(?i)Weekly\\s+limit:\\s*(?:\\[[^\\]]+\\]\\s*)?(\\d{1,3})\\s*%\\s*left\\s*\\(resets\\s*([^)]*)\\)"))
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
                it.contains("usage", ignoreCase = true) ||
                    it.contains("limit", ignoreCase = true) ||
                    it.contains("token", ignoreCase = true)
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

private data class CodexLimitParse(
    val usagePercent: Int,
    val resetAt: String,
)

private fun parseCodexLimitLine(text: String, regex: Regex): CodexLimitParse? {
    val match = regex.find(text) ?: return null
    val left = match.groupValues.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: return null
    val resetAt = match.groupValues.getOrNull(2)?.trim().orEmpty()
    return CodexLimitParse(
        usagePercent = 100 - left,
        resetAt = resetAt,
    )
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

class CodexUsageMonitor(
    private val statusService: CodexStatusService,
    private val intervalProvider: () -> Duration = { Duration.ofMinutes(5) },
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                runCatching { statusService.snapshot() }
                    .onFailure { codexStatusLog.debug(it) { "Codex usage monitor tick failed: ${it.message}" } }
                kotlinx.coroutines.delay(intervalProvider().toMillis().coerceAtLeast(60_000L))
            }
        }
    }

    fun snapshot(): CodexUsageDto = statusService.cachedSnapshot()

    fun shutdown() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
