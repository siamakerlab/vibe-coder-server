package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.shared.dto.GlmUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

private val ZAI_PROVIDERS = listOf("zai-coding-plan", "zai", "z-ai", "z.ai", "zhipu", "zhipuai")

/**
 * v1.158.0 — z.ai coding plan 사용량 모니터링.
 *
 * Z.AI monitor API (`/api/monitor/usage/quota/limit`) 를 직접 호출해서
 * 사용량 백분율·리셋 시각·총 토큰 사용량을 가져온다.
 * API key 는 opencode auth.json (`zai-coding-plan.key`) 에서 읽는다.
 *
 * 사이드바 GLM pill 이 [cachedSnapshot] 을 60s 폴링하며,
 * 백그라운드 코루틴이 120s 주기로 [refresh] 로 캐시를 갱신한다.
 *
 * 인증: `Authorization: {API_KEY}` (Bearer 없이 — Z.AI monitor API 규약).
 * JDK 11+ 표준 [HttpClient] 만 사용 (외부 의존성 없음, [WebhookNotifier] 와 동일 패턴).
 */
class GlmQuotaService(
    private val authJsonPath: Path = Path.of(System.getenv("HOME")?.plus("/.local/share/opencode/auth.json")
        ?: "/home/vibe/.local/share/opencode/auth.json"),
    private val baseUrl: String = "https://api.z.ai",
    private val pollIntervalSeconds: Long = 120,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = AtomicReference<GlmUsageDto?>(null)

    /** 비차단 캐시 읽기 — route handler / 사이드바 pill 폴링용. */
    fun cachedSnapshot(): GlmUsageDto =
        cache.get() ?: GlmUsageDto(updatedAt = Instant.now().toString(), available = false)

    /** 백그라운드 폴링 시작. ServerMain 에서 서버 부팅 시 1회 호출. */
    fun start() {
        scope.launch {
            while (true) {
                refresh()
                delay(pollIntervalSeconds * 1000)
            }
        }
    }

    /** z.ai API 를 호출해서 캐시를 갱신. auth.json 에 key 가 없으면 available=false. */
    suspend fun refresh() {
        val apiKey = readApiKey()
        if (apiKey == null) {
            cache.set(GlmUsageDto(updatedAt = Instant.now().toString(), available = false, loggedIn = false))
            return
        }
        val dto = runCatching { fetchQuota(apiKey) }
            .onFailure { log.warn(it) { "GLM quota fetch failed: ${it.message}" } }
            .getOrElse {
                GlmUsageDto(updatedAt = Instant.now().toString(), available = false, loggedIn = true)
            }
        cache.set(dto)
    }

    fun stop() = scope.cancel()

    // ── auth.json → API key ──────────────────────────────────────────────

    private fun readApiKey(): String? {
        if (!Files.exists(authJsonPath)) return null
        return runCatching {
            val root = Json.parseToJsonElement(Files.readString(authJsonPath)).jsonObject
            for (p in ZAI_PROVIDERS) {
                val cred = root[p] as? JsonObject ?: continue
                val key = cred["key"]?.jsonPrimitive?.content
                if (!key.isNullOrBlank()) return key
            }
            null
        }.getOrNull()
    }

    // ── Z.AI monitor API ─────────────────────────────────────────────────

    private fun fetchQuota(apiKey: String): GlmUsageDto {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/monitor/usage/quota/limit"))
            .header("Authorization", apiKey)
            .header("Accept-Language", "en-US,en")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            log.warn { "Z.AI quota API returned HTTP ${resp.statusCode()}" }
            return GlmUsageDto(
                updatedAt = Instant.now().toString(),
                available = false,
                loggedIn = true,
                raw = resp.body().take(2048),
            )
        }
        return parseQuota(resp.body())
    }

    /**
     * Z.AI `/api/monitor/usage/quota/limit` 응답 파싱.
     *
     * 실제 응답 구조 (2026-07-06 확인):
     * ```json
     * {"code":200,"data":{"limits":[
     *   {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":53,"nextResetTime":1783327414608},
     *   {"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":88,"nextResetTime":1783777748997}
     * ],"level":"max"},"success":true}
     * ```
     *
     * `TOKENS_LIMIT` 항목 중 number 가 가장 작은(단기=세션) 것과 가장 큰(장기=주간) 것을
     * 추출하여 session/weekly 바로 매핑. `TIME_LIMIT` 은 무시.
     * `nextResetTime` 은 Unix epoch 밀리초 → ISO 8601 로 변환.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseQuota(body: String): GlmUsageDto {
        val now = Instant.now().toString()
        if (body.isBlank()) return GlmUsageDto(updatedAt = now, available = true, loggedIn = true)
        val root = Json.parseToJsonElement(body).let { it as? JsonObject }
            ?: return GlmUsageDto(updatedAt = now, available = true, loggedIn = true, raw = body.take(4096))

        val data = (root["data"] as? JsonObject) ?: root
        val limits = (data["limits"] as? JsonArray)

        if (limits == null) {
            // fallback: flat 구조 (과거 플러그인 호환)
            val pct = data["percentage"]?.jsonPrimitive?.let { parsePercent(it.content) }
            val resetAt = data["nextResetTime"]?.jsonPrimitive?.content
            return GlmUsageDto(
                updatedAt = now, available = true, loggedIn = true,
                sessionUsagePercent = pct,
                sessionResetAt = resetAt?.let(::epochMsToIso),
                usageSummary = pct?.let { "$it%" },
                raw = body.take(4096),
            )
        }

        // TOKENS_LIMIT 항목만 추출 → nextResetTime 기준 정렬 (가까운=세션 5h, 먼=주간 7d).
        // number 기준 정렬은 세션/주간이 뒤바뀌는 회귀가 있어 nextResetTime 으로 교체.
        val tokenLimits = limits.mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "TOKENS_LIMIT" }
            .sortedBy { it["nextResetTime"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE }

        val sessionLimit = tokenLimits.firstOrNull()
        val weeklyLimit = tokenLimits.drop(1).firstOrNull() ?: tokenLimits.firstOrNull()

        val sessionPct = sessionLimit?.get("percentage")?.jsonPrimitive?.let { parsePercent(it.content) }
        val weeklyPct = weeklyLimit?.get("percentage")?.jsonPrimitive?.let { parsePercent(it.content) }
        val sessionReset = sessionLimit?.get("nextResetTime")?.jsonPrimitive?.longOrNull?.let(::epochMsToIso)
        val weeklyReset = weeklyLimit?.get("nextResetTime")?.jsonPrimitive?.longOrNull?.let(::epochMsToIso)

        val summary = buildString {
            sessionPct?.let { append("session $it%") }
            weeklyPct?.let {
                if (isNotEmpty()) append(" · ")
                append("weekly $it%")
            }
        }.ifBlank { null }

        return GlmUsageDto(
            updatedAt = now,
            available = true,
            loggedIn = true,
            sessionUsagePercent = sessionPct,
            weeklyUsagePercent = if (weeklyLimit != sessionLimit) weeklyPct else null,
            sessionResetAt = sessionReset,
            weeklyResetAt = if (weeklyLimit != sessionLimit) weeklyReset else null,
            usageSummary = summary,
            raw = body.take(4096),
        )
    }

    /** Unix epoch 밀리초 정수/문자열 → ISO 8601. 파싱 실패 시 원본 반환. */
    private fun epochMsToIso(raw: Any): String? = when (raw) {
        is String -> raw.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() } ?: raw
        else -> raw.toString().toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }
    }

    /** "75" / "75.0" / "75%" → 75. 소수점 버림. */
    private fun parsePercent(raw: String): Int? =
        raw.trim().trimEnd('%').toDoubleOrNull()?.toInt()

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000L -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000L -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}
