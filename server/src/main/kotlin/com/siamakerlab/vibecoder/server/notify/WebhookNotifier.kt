package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.config.WebhookSection
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * v0.27.0 — Slack / Discord / Telegram 알림.
 *
 * EmailNotifier 와 같은 트리거에 병렬 발송 (둘 다 enable 가능). JDK 11+ 표준
 * `java.net.http.HttpClient` 만 사용 (외부 의존성 없음).
 *
 * 각 provider 는 비어 있으면 silent skip — 부분 활성 가능 (예: Slack 만).
 * 발송 실패는 swallow + log warn — 알림이 본 작업을 차단하면 안 됨.
 *
 * URL 검증:
 *   - Slack: https://hooks.slack.com/services/...
 *   - Discord: https://discord.com/api/webhooks/... 또는 https://discordapp.com/...
 *   - Telegram: botToken + chatId 분리. URL 은 서버가 합성.
 *   - 다른 host 는 reject (SSRF 방어 — provider 화이트리스트).
 */
class WebhookNotifier(
    private val configProvider: () -> WebhookSection,
    private val client: HttpClient = defaultClient(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { encodeDefaults = false }

    /** 동기 발송 (테스트/진단). enabled=false 이면 false. 호출자 별 결과 맵 반환. */
    suspend fun sendNow(title: String, body: String): Map<String, Boolean> {
        val cfg = configProvider()
        if (!cfg.enabled) return emptyMap()
        val results = mutableMapOf<String, Boolean>()
        if (cfg.slackUrl.isNotBlank()) results["slack"] = trySendSlack(cfg.slackUrl, title, body)
        if (cfg.discordUrl.isNotBlank()) results["discord"] = trySendDiscord(cfg.discordUrl, title, body)
        if (cfg.telegramBotToken.isNotBlank() && cfg.telegramChatId.isNotBlank()) {
            results["telegram"] = trySendTelegram(cfg.telegramBotToken, cfg.telegramChatId, title, body)
        }
        return results
    }

    /** Fire-and-forget. 모든 활성 provider 에 동시 발송. */
    fun send(title: String, body: String) {
        val cfg = configProvider()
        if (!cfg.enabled) return
        scope.launch {
            runCatching { sendNow(title, body) }
                .onFailure { log.warn(it) { "webhook send failed: ${it.message}" } }
        }
    }

    // ── Provider-specific payload 빌더 ─────────────────────────────────

    private fun trySendSlack(url: String, title: String, body: String): Boolean {
        if (!url.startsWith("https://hooks.slack.com/")) {
            log.warn { "Slack webhook URL 거절 (화이트리스트 외): ${url.take(50)}..." }
            return false
        }
        // Slack incoming webhook 의 단순 text 필드. 코드블록으로 multi-line 가독성 ↑.
        val payload = buildJsonObject {
            put("text", "*[$title]*\n```\n${body.take(3500)}\n```")
        }
        return postJson(url, payload, providerName = "slack")
    }

    private fun trySendDiscord(url: String, title: String, body: String): Boolean {
        if (!(url.startsWith("https://discord.com/api/webhooks/") ||
                  url.startsWith("https://discordapp.com/api/webhooks/"))) {
            log.warn { "Discord webhook URL 거절 (화이트리스트 외): ${url.take(50)}..." }
            return false
        }
        val payload = buildJsonObject {
            // Discord 는 content 2000자 cap. 그 이상은 별도 embed 가 필요한데 단순화.
            put("content", "**[$title]**\n```\n${body.take(1800)}\n```")
        }
        return postJson(url, payload, providerName = "discord")
    }

    private fun trySendTelegram(botToken: String, chatId: String, title: String, body: String): Boolean {
        // Bot token 화이트리스트는 형식 검증으로 (서버 호스트 자체는 api.telegram.org 고정).
        if (!botToken.matches(Regex("^\\d+:[A-Za-z0-9_-]+$"))) {
            log.warn { "Telegram botToken 형식 오류" }
            return false
        }
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = buildJsonObject {
            put("chat_id", chatId)
            put("text", "*[$title]*\n```\n${body.take(3500)}\n```")
            put("parse_mode", "Markdown")
            put("disable_web_page_preview", true)
        }
        return postJson(url, payload, providerName = "telegram")
    }

    private fun postJson(url: String, payload: JsonObject, providerName: String): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "vibe-coder-server")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), payload), StandardCharsets.UTF_8))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val ok = resp.statusCode() in 200..299
            if (!ok) {
                log.warn { "$providerName webhook non-2xx: status=${resp.statusCode()} body=${resp.body().take(200)}" }
            } else {
                log.info { "$providerName webhook sent: '${payload["text"] ?: payload["content"]}'.. ok" }
            }
            ok
        } catch (e: Throwable) {
            log.warn(e) { "$providerName webhook IO 실패: ${e.message}" }
            false
        }
    }

    // ── 도메인 별 헬퍼 (EmailNotifier 와 동일 시그니처) ────────────────

    fun buildResult(projectId: String, buildId: String, status: String, errorMessage: String?) {
        val title = "Build $status"
        val body = buildString {
            appendLine("Project: $projectId")
            appendLine("Build:   $buildId")
            appendLine("Status:  $status")
            if (errorMessage != null) {
                appendLine()
                appendLine(errorMessage.take(1500))
            }
        }
        send(title, body)
    }

    fun claudeUsageWarn(remainingPercent: Int, resetAt: String?) {
        val title = "Claude usage warning"
        val body = "Usage at ${100 - remainingPercent}% (remaining ${remainingPercent}%)." +
            if (resetAt != null) "\nReset: $resetAt" else ""
        send(title, body)
    }

    /** v1.147.0 — Codex 사용량 임계치 (session/weekly breakdown 포함). */
    fun codexUsageWarn(usedPercent: Int, sessionPercent: Int?, weeklyPercent: Int?, resetAt: String?) {
        val title = "Codex usage warning"
        val body = buildString {
            append("Usage at ").append(usedPercent).append("%.")
            sessionPercent?.let { append(" Session(5h) ").append(it).append("%.") }
            weeklyPercent?.let { append(" Weekly(7d) ").append(it).append("%.") }
            if (resetAt != null) append("\nReset: ").append(resetAt)
        }
        send(title, body)
    }

    fun diskUsageWarn(usedPercent: Int, freeGb: Double) {
        send("Disk usage warning", "Disk at ${usedPercent}% used. Free=${"%.1f".format(freeGb)} GB.")
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)  // SSRF 회피
            .build()
    }
}

/** Reserved for future test-helpers. */
internal fun encodeFormParam(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
