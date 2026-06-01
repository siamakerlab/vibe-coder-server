package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * v0.27.0 — `/settings/webhook` SSR — Slack / Discord / Telegram 설정 read-only
 * view + 테스트 메시지 전송. EmailSettingsRoutes 의 자매 라우트.
 *
 * 값 자체는 `server.yml` 의 `webhook:` 섹션 또는 `VIBECODER_WEBHOOK_*` env 로 설정.
 */
fun Routing.webhookSettingsRoutes(authDeps: AdminRoutesDeps, notifier: WebhookNotifier) {
    get("/settings/webhook") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val cfg = authDeps.config.webhook
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            WebhookSettingsTemplates.page(sess.username, cfg, sess.csrf, ok, err, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/settings/webhook/test") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val results = notifier.sendNow(
            title = "Test webhook from vibe-coder",
            body = "Sent by ${sess.username} at ${java.time.Instant.now()}",
        )
        val summary = if (results.isEmpty()) {
            "disabled_or_none"
        } else {
            results.entries.joinToString(",") { (k, v) -> "$k=${if (v) "ok" else "fail"}" }
        }
        val q = if (results.isNotEmpty() && results.values.any { it }) "ok=$summary" else "err=$summary"
        call.respondRedirect("/settings/webhook?$q")
    }
}

object WebhookSettingsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        cfg: com.siamakerlab.vibecoder.server.config.WebhookSection,
        csrf: String?,
        ok: String?,
        err: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val statusBadge = if (cfg.enabled) """<span class="ok">✓ 활성</span>"""
        else """<span class="warn">✗ 비활성</span>"""

        val okHtml = ok?.let { """<div class="ok-banner">✓ 테스트 webhook 결과: <code>${esc(it)}</code></div>""" } ?: ""
        val errHtml = err?.let {
            val msg = if (it == "disabled_or_none") {
                "비활성이거나 활성화된 provider 가 없습니다. server.yml 의 webhook.enabled + provider URL 확인."
            } else "오류: ${esc(it)}"
            """<div class="error">$msg</div>"""
        } ?: ""

        fun providerRow(label: String, value: String, hint: String): String =
            """
            <dt class="dim">$label</dt>
            <dd>${if (value.isNotBlank()) "•••••• (set)" else """<span class="dim">(empty)</span>"""}<br>
              <small class="dim">$hint</small>
            </dd>
            """.trimIndent()

        return AdminTemplates.shell(
            title = "Webhook 알림",
            username = username,
            currentPath = "/settings/webhook",
            csrf = csrf,
            embed = embed,
            body = """
<header>
  <h1>Webhook 알림 (Slack / Discord / Telegram) $statusBadge</h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    이메일과 같은 트리거 (빌드 결과 / Claude 사용량 / 디스크 임계치) 가 활성
    provider 모두에 동시 발송. 값은 <code>server.yml</code> 의 <code>webhook:</code>
    섹션 또는 env 로 변경.
  </p>
</header>

$okHtml
$errHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">현재 설정</h2>
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:8px 14px;margin:0">
    <dt class="dim">Enabled</dt><dd>${cfg.enabled}</dd>
    ${providerRow("Slack webhook URL", cfg.slackUrl, "https://hooks.slack.com/services/T../B../...")}
    ${providerRow("Discord webhook URL", cfg.discordUrl, "https://discord.com/api/webhooks/&lt;id&gt;/&lt;token&gt;")}
    ${providerRow("Telegram bot token", cfg.telegramBotToken, "BotFather → /newbot → 토큰 형식 123456:ABC-...")}
    ${providerRow("Telegram chat id", cfg.telegramChatId, "본인 user id 또는 그룹 id (음수 가능)")}
  </dl>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">테스트 메시지 전송</h2>
  <p class="dim" style="font-size:13px">활성된 모든 provider 에 1회 메시지 전송. 결과는 위 배너에 provider 별 ok/fail 로 표시.</p>
  <form method="post" action="/settings/webhook/test">
    ${CsrfTokens.hiddenInput(csrf)}
    <button type="submit" class="primary" ${if (!cfg.enabled) "disabled" else ""} style="padding:8px 16px">
      ${if (cfg.enabled) "🛎 테스트 메시지 전송" else "✗ 비활성 — 활성 후 시도"}
    </button>
  </form>
</div>

<div class="card" style="background:rgba(80,150,255,0.06)">
  <h2 style="margin-top:0">설정 방법</h2>

  <h3 style="margin-top:8px">옵션 A — docker compose env (.env)</h3>
  <pre class="diff-block">VIBECODER_WEBHOOK_ENABLED=true
VIBECODER_WEBHOOK_SLACK_URL=https://hooks.slack.com/services/T.../B.../...
VIBECODER_WEBHOOK_DISCORD_URL=https://discord.com/api/webhooks/.../...
VIBECODER_WEBHOOK_TELEGRAM_BOT_TOKEN=123456:ABC-...
VIBECODER_WEBHOOK_TELEGRAM_CHAT_ID=987654321</pre>

  <h3 style="margin-top:14px">옵션 B — server.yml</h3>
  <pre class="diff-block">webhook:
  enabled: true
  slackUrl: https://hooks.slack.com/services/T.../B.../...
  discordUrl: https://discord.com/api/webhooks/.../...
  telegramBotToken: 123456:ABC-...
  telegramChatId: "987654321"</pre>

  <h3 style="margin-top:14px">Provider 가이드</h3>
  <ul style="font-size:13px;line-height:1.7">
    <li><strong>Slack</strong>: 채널 설정 → "Add apps" → "Incoming Webhooks" → 채널 선택
      → URL 복사. 화이트리스트로 <code>hooks.slack.com</code> 만 허용.</li>
    <li><strong>Discord</strong>: 서버 설정 → Integrations → Webhooks → "New Webhook"
      → 채널 + 이름 지정 → URL 복사.</li>
    <li><strong>Telegram</strong>: @BotFather 에 <code>/newbot</code> → 토큰.
      대화 시작 후 <code>https://api.telegram.org/bot&lt;token&gt;/getUpdates</code>
      에서 본인 chat id 확인 (또는 봇을 그룹에 추가 후 그룹 id 사용).</li>
  </ul>

  <p class="hint" style="margin-top:10px">SSRF 방어: 위 화이트리스트 host 가 아니면
    서버가 요청을 거절합니다 (Slack: <code>hooks.slack.com</code>, Discord:
    <code>discord.com</code>/<code>discordapp.com</code>, Telegram: 토큰 형식 검증).</p>
</div>
""",
            lang = lang,
        )
    }
}
