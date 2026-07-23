package com.siamakerlab.vibecoder.server.agent.opencode

import com.siamakerlab.vibecoder.shared.dto.OpenCodeUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

private val log = KotlinLogging.logger {}

private val COST_REGEX = Regex("""(?im)^\s*Total\s+Cost\s+\$([\d.]+)""")
private val TOKEN_REGEX = Regex("""(?im)^\s*Input\s+([\d.]+\w?)\s*$""")
private val OUTPUT_REGEX = Regex("""(?im)^\s*Output\s+([\d.]+\w?)\s*$""")
private val CACHE_READ_REGEX = Regex("""(?im)^\s*Cache\s+Read\s+([\d.]+\w?)\s*$""")

/**
 * v1.151.0 — OpenCode usage/credential 스냅샷 캡처. [CodexStatusService] 와 대칭이나,
 * opencode CLI 가 usage % 게이지(세션/주간 한도)를 직접 노출하지 않아 `opencode stats` 의
 * 토큰 사용량 + `opencode providers list` 의 credential 상태로 구성한다.
 *
 * z.ai coding plan 잔여량(%)은 opencode CLI 로 조회 불가 → [AgentUsageProvider] 미구현.
 * SESSION_RESET/WEEKLY_RESET 예약 트리거는 OpenCode provider 에서 보수적으로 보류된다
 * ([com.siamakerlab.vibecoder.server.automation.ScheduledPromptManager] 가 provider별
 * usageProviders 맵에서 누락된 provider 는 null 처리). 추후 z.ai API 직접 연동(Phase 3.1) 시 확장.
 */
class OpenCodeStatusService(
    private val authService: OpenCodeAuthService,
) {
    private val latest = AtomicReference<OpenCodeUsageDto?>(null)

    fun cachedSnapshot(): OpenCodeUsageDto =
        latest.get() ?: OpenCodeUsageDto(updatedAt = Instant.now().toString(), available = false)

    suspend fun snapshot(): OpenCodeUsageDto = withContext(Dispatchers.IO) {
        val creds = authService.listCredentials()
        val cred = creds.firstOrNull()
        val statsRaw = runCatching { runStatsCapture() }
            .onFailure { log.debug(it) { "opencode stats 캡처 실패: ${it.message}" } }
            .getOrDefault("")
        val parsed = parseOpenCodeStats(statsRaw)
        val dto = cachedSnapshot()
            .withCredential(cred)
            .copy(
                updatedAt = Instant.now().toString(),
                usageSummary = parsed.usageSummary,
                totalCost = parsed.totalCost,
                totalTokens = parsed.totalTokens,
                inputTokens = parsed.inputTokens,
                outputTokens = parsed.outputTokens,
                cacheReadTokens = parsed.cacheReadTokens,
                raw = statsRaw.take(32 * 1024).ifBlank { null },
            )
        latest.set(dto)
        dto
    }

    private suspend fun runStatsCapture(): String {
        val cmd = defaultOpenCodeCmdForStats()
        if (!isOpenCodeCommandAvailable(cmd)) return ""
        val pb = ProcessBuilder(cmd, "stats").redirectError(ProcessBuilder.Redirect.DISCARD)
        applyOpenCodeProcessEnv(pb)
        return runWithHardTimeout(pb, timeoutSeconds = 15)
    }

    private fun runWithHardTimeout(pb: ProcessBuilder, timeoutSeconds: Long): String =
        runCatching {
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

private fun defaultOpenCodeCmdForStats(): String =
    System.getenv("OPENCODE_CMD")?.takeIf { it.isNotBlank() } ?: "opencode"

internal data class OpenCodeStatsParse(
    val totalCost: String? = null,
    val totalTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val usageSummary: String? = null,
)

/**
 * v1.151.0 — `opencode stats` ANSI 박스 출력에서 토큰/cost 추출 (순수 함수).
 * opencode stats 는 `--format json` 미지원이라 TUI 박스를 파싱한다. K/M/G 접미사를 long 으로 환산.
 */
internal fun parseOpenCodeStats(raw: String): OpenCodeStatsParse {
    if (raw.isBlank()) return OpenCodeStatsParse()
    // opencode stats 는 TUI 박스(`─│┌└├`) 로 감싸져 있어 줄 시작/끝의 박스 문자를 공백으로
    // 치환한 뒤 정규식 매칭.
    val cleaned = stripOpenCodeAnsi(raw).replace(Regex("[─│┌└├┤┬┴┼]"), " ")
    val totalCost = COST_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let { "\$$it" }
    val input = TOKEN_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let(::parseTokenCount)
    val output = OUTPUT_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let(::parseTokenCount)
    val cacheRead = CACHE_READ_REGEX.find(cleaned)?.groupValues?.getOrNull(1)?.let(::parseTokenCount)
    val total = listOfNotNull(input, output, cacheRead).takeIf { it.isNotEmpty() }?.sum()
    val summary = buildSummary(totalCost, total)
    return OpenCodeStatsParse(
        totalCost = totalCost,
        totalTokens = total,
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = cacheRead,
        usageSummary = summary,
    )
}

/** `2.2M` / `19.1K` / `64.6M` → long. 인식 불가 seed(소수점 없는 숫자) 도 처리. */
internal fun parseTokenCount(token: String): Long? {
    val cleaned = token.trim()
    val regex = Regex("""^([\d.]+)\s*([KMG]?)$""", RegexOption.IGNORE_CASE)
    val match = regex.matchEntire(cleaned) ?: cleaned.toLongOrNull()?.let { return it }
    val num = match?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
    val suffix = match.groupValues.getOrNull(2)?.uppercase()
    val multiplier = when (suffix) {
        "K" -> 1_000L
        "M" -> 1_000_000L
        "G" -> 1_000_000_000L
        else -> 1L
    }
    // roundToLong — 부동소수점 표현 오류(예: 64.6 * 1_000_000 = 64599999.99) 방지.
    return (num * multiplier).roundToLong()
}

private fun buildSummary(totalCost: String?, totalTokens: Long?): String? {
    val parts = mutableListOf<String>()
    totalTokens?.let { parts += "tokens ${formatOpenCodeTokens(it)}" }
    totalCost?.let { parts += "cost $it" }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** v1.152.0 — 토큰 수를 1.2K/3.4M 형태로 포맷 (AdminTemplates 카드 공유). */
internal fun formatOpenCodeTokens(n: Long): String = when {
    n >= 1_000_000L -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000L -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
