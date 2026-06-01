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
 * v0.17.0 — `/settings/email` SSR — SMTP 설정 read-only view + 테스트 메일 전송.
 *
 * 설정 값 자체의 영구 저장은 ConfigPersistence 를 통해 server.yml 에 기록.
 * `/settings/email/test` 는 현재 설정으로 즉시 테스트 메일 발송 (1회).
 */
fun Routing.emailSettingsRoutes(authDeps: AdminRoutesDeps, notifier: EmailNotifier) {
    get("/settings/email") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val cfg = authDeps.config.email
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            EmailSettingsTemplates.page(sess.username, cfg, sess.csrf, ok, err, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/settings/email/test") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val sent = notifier.sendNow(
            subject = "Test email from vibe-coder",
            body = "Sent by ${sess.username} at ${java.time.Instant.now()}",
        )
        call.respondRedirect("/settings/email?${if (sent) "ok=test" else "err=disabled_or_failed"}")
    }
}

object EmailSettingsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        cfg: com.siamakerlab.vibecoder.server.config.EmailSection,
        csrf: String?,
        ok: String?,
        err: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val statusBadge = if (cfg.enabled)
            """<span class="ok">✓ 활성</span>"""
        else
            """<span class="warn">✗ 비활성</span>"""

        val okHtml = ok?.let { """<div class="ok-banner">✓ 테스트 메일 발송 성공.</div>""" } ?: ""
        val errHtml = err?.let {
            val msg = when (it) {
                "disabled_or_failed" -> "SMTP 가 비활성이거나 발송 실패. server.yml / env 설정과 server log 를 확인하세요."
                else -> "오류: $it"
            }
            """<div class="error">$msg</div>"""
        } ?: ""

        return AdminTemplates.shell(
            title = "이메일 알림",
            username = username,
            currentPath = "/settings/email",
            csrf = csrf,
            body = """
<header>
  <h1>이메일 알림 $statusBadge</h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    빌드 결과 / Claude 사용량 / 디스크 임계치 알림. **읽기 전용** — 값은
    <code>server.yml</code> 의 <code>email:</code> 섹션 또는
    <code>VIBECODER_SMTP_*</code> env 로 변경합니다 (CORS settings 와 같은 정책).
  </p>
</header>
${com.siamakerlab.vibecoder.server.admin.SettingsNav.categoryNav("/settings/email", lang)}

$okHtml
$errHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">현재 설정</h2>
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 14px;margin:0">
    <dt class="dim">Enabled</dt><dd>${cfg.enabled}</dd>
    <dt class="dim">SMTP host</dt><dd><code>${esc(cfg.host)}</code>:${cfg.port}</dd>
    <dt class="dim">STARTTLS</dt><dd>${cfg.tls}</dd>
    <dt class="dim">User</dt><dd><code>${esc(cfg.user.ifBlank { "(none)" })}</code></dd>
    <dt class="dim">Password</dt><dd>${if (cfg.password.isNotBlank() || cfg.passwordFile.isNotBlank()) "•••••• (set)" else "(empty)"}</dd>
    <dt class="dim">From</dt><dd><code>${esc(cfg.from.ifBlank { "(empty — required)" })}</code></dd>
    <dt class="dim">To</dt><dd><code>${esc(cfg.to.ifBlank { "(empty — required)" })}</code></dd>
    <dt class="dim">Claude 사용량 임계치</dt><dd>${cfg.claudeUsageWarnPercent}% 이하 시 알림</dd>
    <dt class="dim">디스크 사용량 임계치</dt><dd>${cfg.diskUsageWarnPercent}% 이상 시 알림</dd>
  </dl>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">테스트 메일 전송</h2>
  <p class="dim" style="font-size:13px">현재 설정으로 즉시 한 통 발송 → SMTP / from / to 가 모두 동작하는지 확인.</p>
  <form method="post" action="/settings/email/test">
    ${CsrfTokens.hiddenInput(csrf)}
    <button type="submit" class="primary" ${if (!cfg.enabled) "disabled" else ""} style="padding:8px 16px">
      ${if (cfg.enabled) "✉ 테스트 메일 전송" else "✗ 비활성 — 활성 후 시도"}
    </button>
  </form>
</div>

<div class="card" style="background:rgba(80,150,255,0.06)">
  <h2 style="margin-top:0">설정 방법</h2>
  <p class="dim" style="font-size:13px">두 가지 — env (권장, 비밀번호 노출 줄임) / server.yml</p>

  <h3 style="margin-top:14px">옵션 A — docker compose env (.env)</h3>
  <pre class="diff-block">VIBECODER_SMTP_ENABLED=true
VIBECODER_SMTP_HOST=smtp.gmail.com
VIBECODER_SMTP_PORT=587
VIBECODER_SMTP_TLS=true
VIBECODER_SMTP_USER=alerts@example.com
VIBECODER_SMTP_PASSWORD=app-specific-password
VIBECODER_SMTP_FROM=vibe-coder &lt;alerts@example.com&gt;
VIBECODER_SMTP_TO=ops@example.com,me@personal.com</pre>

  <h3 style="margin-top:14px">옵션 B — server.yml</h3>
  <pre class="diff-block">email:
  enabled: true
  host: smtp.gmail.com
  port: 587
  tls: true
  user: alerts@example.com
  passwordFile: /run/secrets/smtp_password   # Docker secret 권장
  from: "vibe-coder &lt;alerts@example.com&gt;"
  to: ops@example.com,me@personal.com
  claudeUsageWarnPercent: 20
  diskUsageWarnPercent: 85</pre>

  <h3 style="margin-top:14px">Gmail / Outlook 등 주요 provider 빠른 가이드</h3>
  <ul style="font-size:13px;line-height:1.7">
    <li><strong>Gmail</strong>: <code>smtp.gmail.com:587</code> STARTTLS.
      Google 계정에서 "앱 비밀번호" 생성 후 사용 (일반 비번 불가).</li>
    <li><strong>Outlook / Office365</strong>: <code>smtp.office365.com:587</code>
      STARTTLS. 일반 비번 가능 (2FA 시 앱 비번).</li>
    <li><strong>Mailgun / SendGrid / SES</strong>: provider 별 SMTP host
      + API key 를 user/password 자리에. <code>tls: true</code>.</li>
    <li><strong>self-host (Postfix 등)</strong>: 25 (no TLS) 또는 587 (STARTTLS).</li>
  </ul>
</div>
""",
            lang = lang,
            embed = embed,
        )
    }
}
