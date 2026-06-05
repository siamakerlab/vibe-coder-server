package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.Totp
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v0.26.0 — `/2fa` SSR 페이지 + POST 라우트.
 *
 * 흐름:
 *   - GET /2fa
 *     - 비활성: pending secret 생성 (in-memory transient, 세션 단위) → otpauth URI
 *       + Base32 secret 표시. 사용자가 Authenticator 앱에 등록 후 코드 입력 폼.
 *     - 활성: "현재 활성" 안내 + 비활성화 폼 (현재 코드 1회 확인).
 *   - POST /2fa/enable: 6자리 코드 검증 → users.enableTotp(secret) 영구화.
 *   - POST /2fa/disable: 6자리 코드 검증 → users.disableTotp.
 *
 * Pending secret 은 in-memory `pendingSecrets[userId]` ConcurrentHashMap 에 저장 —
 * 서버 재시작 시 초기화 (재생성). UI 가 secret 을 폼에 hidden 으로 다시 보내지
 * 않는 이유: 브라우저 history / 캡처 위험 회피.
 *
 * v0.87.0 Phase 64.11 — 모든 사용자 가시 한국어 i18n 키화 (twofa.*).
 */
fun Routing.twoFactorRoutes(deps: AdminRoutesDeps, users: AdminUserRepository) {
    val pendingSecrets = java.util.concurrent.ConcurrentHashMap<String, String>()

    get("/2fa") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        // v0.40.0 — 2FA 는 개인 보안 설정 — admin/member/viewer 모두 자기 자신 관리 허용.
        // 단 viewer 는 일반적으로 enable 후 forgot 만 위험하므로 별도 가드 안 함.
        val u = users.findById(sess.userId) ?: run {
            call.respondRedirect("/login"); return@get
        }
        if (u.totpEnabled) {
            call.respondText(
                TwoFactorTemplates.enabledPage(sess.username, sess.csrf, u.totpEnabledAt, sess.language, embed = call.isEmbeddedRequest()),
                ContentType.Text.Html,
            )
            return@get
        }
        // 비활성 → pending secret 보장. 이미 있으면 재사용 (페이지 새로고침 안전).
        val secret = pendingSecrets.computeIfAbsent(sess.userId) { Totp.generateSecret() }
        val issuer = deps.config.server.name
        val uri = Totp.otpauthUri(issuer, u.username, secret)
        call.respondText(
            TwoFactorTemplates.disabledPage(sess.username, sess.csrf, secret, uri, sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/2fa/enable") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        val params = requireCsrf()
        val code = params["code"]?.trim().orEmpty()
        val secret = pendingSecrets[sess.userId]
        if (secret.isNullOrBlank()) {
            call.respondRedirect("/2fa?err=${enc(Messages.t(sess.language, "twofa.flash.sessionExpired"))}")
            return@post
        }
        if (!Totp.verify(secret, code)) {
            call.respondRedirect("/2fa?err=${enc(Messages.t(sess.language, "twofa.flash.codeMismatch"))}")
            return@post
        }
        users.enableTotp(sess.userId, secret)
        pendingSecrets.remove(sess.userId)
        deps.audit.twoFactorEnabled(sess.userId, call.request.origin.remoteHost)
        log.info { "2FA enabled for user ${sess.username}" }
        call.respondRedirect("/2fa?ok=${enc(Messages.t(sess.language, "twofa.flash.enabled"))}")
    }

    post("/2fa/disable") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        val params = requireCsrf()
        val u = users.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@post }
        if (!u.totpEnabled) {
            call.respondRedirect("/2fa")
            return@post
        }
        val code = params["code"]?.trim().orEmpty()
        if (!Totp.verify(u.totpSecret!!, code)) {
            call.respondRedirect("/2fa?err=${enc(Messages.t(sess.language, "twofa.flash.currentCodeMismatch"))}")
            return@post
        }
        users.disableTotp(sess.userId)
        deps.audit.twoFactorDisabled(sess.userId, call.request.origin.remoteHost)
        log.info { "2FA disabled for user ${sess.username}" }
        call.respondRedirect("/2fa?ok=${enc(Messages.t(sess.language, "twofa.flash.disabled"))}")
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

private object TwoFactorTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun disabledPage(username: String, csrf: String?, secret: String, otpauthUri: String, lang: String, embed: Boolean = false): String {
        val t = { key: String -> Messages.t(lang, key) }
        return AdminTemplates.shell(
            title = t("twofa.title"),
            username = username,
            currentPath = "/2fa",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
${SettingsNav.categoryNav("/2fa", lang)}
<header><h1>${esc(t("twofa.heading"))}</h1></header>

<div class="card">
  <h2>${esc(t("twofa.disabled.statusTitle"))}</h2>
  <p>${esc(t("twofa.disabled.statusDesc"))}</p>
</div>

<div class="card" style="margin-top:14px">
  <h2>${esc(t("twofa.disabled.stepRegister"))}</h2>
  <p>${esc(t("twofa.disabled.registerDesc"))}</p>

  <p><strong>${esc(t("twofa.disabled.uriLabel"))}</strong></p>
  <pre class="diff-block" style="font-size:11px;word-break:break-all;white-space:pre-wrap">${esc(otpauthUri)}</pre>

  <p style="margin-top:12px"><strong>${esc(t("twofa.disabled.secretLabel"))}</strong></p>
  <pre class="diff-block" style="font-size:13px;letter-spacing:2px">${esc(secret.chunked(4).joinToString(" "))}</pre>

  <p class="hint">${t("twofa.disabled.qrHint")}</p>
</div>

<div class="card" style="margin-top:14px">
  <h2>${esc(t("twofa.disabled.stepEnable"))}</h2>
  <form method="post" action="/2fa/enable" style="display:grid;gap:10px;max-width:400px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("twofa.disabled.codeLabel"))}
      <input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autofocus>
    </label>
    <button type="submit" class="primary">${esc(t("twofa.disabled.enableBtn"))}</button>
  </form>
</div>
"""
        )
    }

    fun enabledPage(username: String, csrf: String?, enabledAt: String?, lang: String, embed: Boolean = false): String {
        val t = { key: String -> Messages.t(lang, key) }
        return AdminTemplates.shell(
            title = t("twofa.title"),
            username = username,
            currentPath = "/2fa",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
${SettingsNav.categoryNav("/2fa", lang)}
<header><h1>${esc(t("twofa.heading"))}</h1></header>

<div class="card">
  <h2>${esc(t("twofa.enabled.statusTitle"))}</h2>
  <p>${esc(t("twofa.enabled.enabledAt"))} <code>${esc(enabledAt ?: "-")}</code></p>
  <p>${esc(t("twofa.enabled.desc"))}</p>
</div>

<div class="card" style="margin-top:14px;border-color:var(--warn);background:rgba(255,150,80,0.06)">
  <h2 style="margin-top:0">${esc(t("twofa.enabled.disableTitle"))}</h2>
  <p class="hint">${esc(t("twofa.enabled.disableHint"))}</p>
  <form method="post" action="/2fa/disable" style="display:grid;gap:10px;max-width:400px;margin-top:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("twofa.enabled.codeLabel"))}
      <input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required>
    </label>
    <button type="submit" class="chip chip-danger">${esc(t("twofa.enabled.disableBtn"))}</button>
  </form>
</div>
"""
        )
    }
}
