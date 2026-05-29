package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiAdmin
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.SshKeyDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * v1.2.0 — SSH key 관리 라우트.
 *
 * SSR:
 *   GET /settings/ssh-key                  — 공개키 + fingerprint 열람
 *   POST /settings/ssh-key/regenerate      — 키 재생성 (confirm 후) — 기존 키 .bak 으로 백업
 *
 * REST (Android catch-up 용):
 *   GET  /api/server/ssh-key               — SshKeyDto
 *   POST /api/server/ssh-key/regenerate    — SshKeyDto (재생성 결과)
 */
fun Routing.sshKeyRoutes(authDeps: AdminRoutesDeps, sshKey: SshKeyService) {

    // ── SSR ─────────────────────────────────────────────────────────────
    get("/settings/ssh-key") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            SshKeyTemplates.page(sess.username, sshKey.snapshot(), csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    post("/settings/ssh-key/regenerate") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        // v1.28.1 — 폼은 csrf 를 hidden input(body)으로 보낸다. body `_csrf` 검증 +
        // 실패 시 SSR redirect (이전 verifyCsrfFromQueryOrHeader 는 query/header 만
        // 봐서 항상 실패 + JSON 응답으로 페이지 붕괴 — keystore 와 동일 버그).
        val form = call.receiveParameters()
        if (!com.siamakerlab.vibecoder.server.auth.CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("/settings/ssh-key?err=csrf")
            return@post
        }
        runCatching { sshKey.regenerate() }
            .onFailure { e -> log.warn(e) { "SSH key regenerate failed" } }
        call.respondRedirect("/settings/ssh-key")
    }

    // ── JSON API ────────────────────────────────────────────────────────
    // v1.43.0 — 22차 정밀점검 회수: 두 endpoint 가 무인증이었음(외부에서 POST 한 번으로
    // deploy key 재생성 → git remote 연동 무력화 DoS 가능). Bearer 인증 + admin 강제.
    // SSR 짝(/settings/ssh-key/*)은 cookie session + admin + CSRF 적용 — 비대칭 해소.
    authenticate(AUTH_BEARER) {
        get(ApiPath.SERVER_SSH_KEY) {
            call.requireApiAdmin()
            val snap = sshKey.snapshot()
            if (snap == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "ssh_key_not_found"))
                return@get
            }
            call.respond(snap.toDto())
        }

        post(ApiPath.SERVER_SSH_KEY_REGENERATE) {
            call.requireApiAdmin()
            try {
                val snap = sshKey.regenerate()
                call.respond(snap.toDto())
            } catch (e: Exception) {
                log.warn(e) { "SSH key regenerate failed via API" }
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "regenerate_failed")))
            }
        }
    }
}

private fun SshKeySnapshot.toDto(): SshKeyDto = SshKeyDto(
    publicKey = publicKey,
    algorithm = algorithm,
    comment = comment,
    fingerprint = fingerprint,
    createdAt = createdAt,
)

internal object SshKeyTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(username: String, snap: SshKeySnapshot?, csrf: String? = null, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        val csrfHidden = csrf?.let {
            """<input type="hidden" name="_csrf" value="${esc(it)}">"""
        } ?: ""
        val body = if (snap == null) {
            """
<div class="card" style="background:#451a03;border-color:#92400e">
  <h2 style="margin-top:0">${esc(t("ssh.notFound.title"))}</h2>
  <p>${esc(t("ssh.notFound.body"))}</p>
  <p class="dim" style="font-size:12px">${esc(t("ssh.notFound.hint"))}</p>
</div>
"""
        } else {
            val createdLabel = snap.createdAt?.let {
                Messages.t(lang, "ssh.createdAt", it)
            } ?: ""
            """
<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("ssh.publicKey"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 12px">${esc(t("ssh.publicKey.hint"))}</p>
  <textarea id="ssh-pub" rows="3" readonly
    style="width:100%;font-family:ui-monospace,Menlo,monospace;font-size:12px;
           background:#0c0c0c;color:#eee;padding:10px;border:1px solid #333;
           border-radius:6px;resize:vertical">${esc(snap.publicKey)}</textarea>
  <button type="button" class="chip chip-action" style="margin-top:10px"
    onclick="(async function(){var el=document.getElementById('ssh-pub');
      try{await navigator.clipboard.writeText(el.value);
        this.textContent='${esc(t("ssh.copied"))}';
        setTimeout(function(){this.textContent='${esc(t("ssh.copy"))}';}.bind(this),1500);
      }catch(e){el.select();document.execCommand('copy');}
    }).call(this)">${esc(t("ssh.copy"))}</button>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("ssh.meta"))}</h2>
  <table style="width:100%;border-collapse:collapse">
    <tr><td class="dim" style="padding:6px 12px 6px 0;width:140px">${esc(t("ssh.algorithm"))}</td>
        <td><code>${esc(snap.algorithm)}</code></td></tr>
    <tr><td class="dim" style="padding:6px 12px 6px 0">${esc(t("ssh.fingerprint"))}</td>
        <td><code style="font-size:12px">${esc(snap.fingerprint ?: "—")}</code></td></tr>
    <tr><td class="dim" style="padding:6px 12px 6px 0">${esc(t("ssh.comment"))}</td>
        <td><code style="font-size:12px">${esc(snap.comment ?: "—")}</code></td></tr>
    ${if (createdLabel.isNotBlank()) "<tr><td class=\"dim\" style=\"padding:6px 12px 6px 0\">${esc(t("ssh.created"))}</td><td>${esc(createdLabel)}</td></tr>" else ""}
  </table>
</div>

<div class="card" style="background:#1f0f0f;border-color:#7f1d1d;margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("ssh.regen.title"))}</h2>
  <p>${esc(t("ssh.regen.body"))}</p>
  <p class="dim" style="font-size:12px">${esc(t("ssh.regen.hint"))}</p>
  <form method="post" action="/settings/ssh-key/regenerate"
    onsubmit="return confirm('${esc(t("ssh.regen.confirm"))}')">
    $csrfHidden
    <button type="submit" class="chip chip-action" style="background:#7f1d1d;color:#fff">
      ${esc(t("ssh.regen.button"))}
    </button>
  </form>
</div>

<div class="card" style="background:#0a1a2a;border-color:#1e40af">
  <h2 style="margin-top:0">${esc(t("ssh.usage.title"))}</h2>
  <p>${esc(t("ssh.usage.body"))}</p>
  <ol style="margin:8px 0 0 18px;padding:0">
    <li>${esc(t("ssh.usage.step1"))}</li>
    <li>${esc(t("ssh.usage.step2"))}</li>
    <li>${esc(t("ssh.usage.step3"))}</li>
  </ol>
</div>
"""
        }
        return AdminTemplates.shell(
            title = t("ssh.title"),
            username = username,
            currentPath = "/settings/ssh-key",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("ssh.title"))}</h1>
    <a href="/settings" class="chip chip-link">${esc(t("ssh.backToSettings"))}</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("ssh.intro"))}</p>
</header>

$body
""",
        )
    }
}
