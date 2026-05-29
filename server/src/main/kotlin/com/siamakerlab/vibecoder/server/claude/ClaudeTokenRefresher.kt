package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.env.ClaudeAuthService
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * v1.7.9 — 컨테이너 구동 중 Claude OAuth 토큰 자동 갱신.
 *
 * 사용자 요구 (v1.7.9): "컨테이너가 구동되는 동안 토큰을 계속해서 자동갱신하도록
 * 구현하고 (...) 사용자가 1회 로그인 후 다시 로그인 하지 않고, 따로 신경 안써도
 * 되게끔 구현".
 *
 * 동작:
 *  1. 10분 주기 폴링.
 *  2. `~/.claude/.credentials.json` 의 `claudeAiOauth.expiresAt` 가 현재 시각 +
 *     `REFRESH_AHEAD` (60분) 이내면 refresh 시도.
 *  3. Refresh trigger 는 `claude --print --output-format json "ok"` (1 turn).
 *     비용 minimal (작은 prompt 1회). Claude CLI 내부 OAuth flow 가 호출 직전
 *     access_token 만료/임박 시 refresh_token 으로 자동 재발급.
 *  4. 호출 후 `.credentials.json` 의 expiresAt 가 갱신됐는지 비교 + log.
 *  5. 갱신 실패해도 silent (notifier 가 별도 임계치 알림).
 *
 * scratchOnly 와 무관 — 사용자 계정 1개라 어디서 호출하든 같은 토큰 갱신.
 * scratch project 의 workspace 를 cwd 로 사용 (claude.enabled + projectRoot 존재
 * 가정).
 */
class ClaudeTokenRefresher(
    private val claudeAuthService: ClaudeAuthService,
    private val cwd: Path,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            log.info { "Claude token refresher started (poll=${POLL_MINUTES}m, ahead=${REFRESH_AHEAD_MINUTES}m)" }
            // 부팅 직후 1회 즉시 확인 (만료 임박 상태에서 시작된 케이스 대응).
            runCatching { tick() }
                .onFailure { log.debug(it) { "initial token refresher tick failed: ${it.message}" } }
            while (isActive) {
                delay(Duration.ofMinutes(POLL_MINUTES).toMillis())
                runCatching { tick() }
                    .onFailure { log.debug(it) { "token refresher tick failed: ${it.message}" } }
            }
        }
    }

    fun shutdown() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    private suspend fun tick() {
        val cred = claudeAuthService.credentialsPath()
        if (!cred.exists()) {
            log.debug { "credentials file not found — skipping refresh tick" }
            return
        }
        val expiresAt = readOauthExpiresAt(cred)
        if (expiresAt == null) {
            log.debug { "credentials file has no expiresAt — skipping refresh" }
            return
        }
        val nowMs = System.currentTimeMillis()
        val remainingMs = expiresAt - nowMs
        // 만료까지 REFRESH_AHEAD_MS 이상 남으면 skip — 적절한 시점에 다시 폴링.
        if (remainingMs > REFRESH_AHEAD_MS) {
            log.debug { "token has ${remainingMs / 60_000}m remaining — refresh deferred" }
            return
        }

        log.info {
            val sign = if (remainingMs < 0) "expired ${(-remainingMs) / 60_000}m ago" else "${remainingMs / 60_000}m remaining"
            "Claude token refresh triggered ($sign, expiresAt=${formatInstant(expiresAt)})"
        }
        val refreshed = withContext(Dispatchers.IO) { triggerRefresh() }
        if (refreshed) {
            val newExpiresAt = readOauthExpiresAt(cred)
            if (newExpiresAt != null && newExpiresAt > expiresAt) {
                log.info {
                    "Claude token refreshed: ${formatInstant(expiresAt)} → ${formatInstant(newExpiresAt)} " +
                            "(extended by ${(newExpiresAt - expiresAt) / 3_600_000}h)"
                }
            } else {
                log.warn { "Claude --print returned but expiresAt unchanged. CLI may not have triggered refresh; manual `claude login` may be required." }
            }
        } else {
            log.warn { "Claude --print refresh trigger failed (process error or timeout). Will retry next cycle." }
        }
    }

    /**
     * `claude --print --output-format json "ok"` 호출. Claude CLI 가 호출 직전
     * access_token 만료/임박 시 OAuth refresh 자동 수행. 비용 minimal.
     */
    private fun triggerRefresh(): Boolean {
        val cmd = listOf(resolveClaudeCmd(), "--print", "--output-format", "json", "ok")
        return try {
            // v1.43.0 — 출력 불필요(refresh 성공 여부는 종료코드). 이전엔 merge 출력을
            // 읽지 않아 응답 JSON 이 파이프 버퍼 초과 시 자식 block → 매번 timeout 실패 가능.
            // stdout/stderr DISCARD 로 파이프 포화 제거.
            val pb = ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            val process = pb.start()
            val finished = process.waitFor(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                log.debug { "claude --print refresh trigger timed out after ${REFRESH_TIMEOUT_SECONDS}s" }
                return false
            }
            process.exitValue() == 0
        } catch (e: Exception) {
            log.debug(e) { "claude --print refresh trigger threw" }
            false
        }
    }

    private fun resolveClaudeCmd(): String =
        System.getenv("CLAUDE_CMD")?.takeIf { it.isNotBlank() } ?: "claude"

    private fun readOauthExpiresAt(file: Path): Long? = try {
        val text = Files.readString(file, Charsets.UTF_8)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val oauth = root["claudeAiOauth"] as? JsonObject ?: return null
        (oauth["expiresAt"] as? JsonPrimitive)?.longOrNull
    } catch (_: Throwable) {
        null
    }

    private fun formatInstant(epochMs: Long): String =
        ISO_FMT.format(Instant.ofEpochMilli(epochMs))

    companion object {
        /** 폴링 주기 (분). 토큰 만료가 보통 8h 단위라 10분이면 충분히 잦음. */
        private const val POLL_MINUTES = 10L

        /** 만료까지 X 분 이내면 refresh 시도. CLI 가 access_token 만료 임박 시 자동
         *  refresh 한다고 가정 — 1h 이내 호출이면 안전 마진 큼. */
        private const val REFRESH_AHEAD_MINUTES = 60L
        private const val REFRESH_AHEAD_MS = REFRESH_AHEAD_MINUTES * 60 * 1000L

        /** claude --print 1회 timeout. 보통 3~5초 — 30s 면 여유 충분. */
        private const val REFRESH_TIMEOUT_SECONDS = 30L

        private val ISO_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            .withZone(ZoneId.of("Asia/Seoul"))
    }
}
