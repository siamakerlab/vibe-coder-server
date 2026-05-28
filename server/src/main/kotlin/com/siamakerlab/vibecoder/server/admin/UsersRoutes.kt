package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.PasswordHasher
import com.siamakerlab.vibecoder.server.auth.PasswordPolicy
import com.siamakerlab.vibecoder.server.auth.UsernamePolicy
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
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
 * v0.37.0 — `/users` SSR (admin 만).
 *
 *   GET  /users                       — 전체 사용자 + 권한 변경 / 삭제
 *   POST /users                       — 신규 사용자 (default role=member)
 *   POST /users/{id}/role             — admin <-> member
 *   POST /users/{id}/delete           — 사용자 삭제 (device cascade)
 *
 * 가드:
 *   - 모든 endpoint 가 admin role 만. member 가 접근하면 403 redirect /login.
 *   - 마지막 admin 강등/삭제 차단 (lockout 방지).
 */
fun Routing.usersRoutes(
    deps: AdminRoutesDeps,
    userRepo: AdminUserRepository,
    deviceRepo: DeviceRepository,
    hasher: PasswordHasher,
) {
    get("/users") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        val me = userRepo.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@get }
        if (!me.isAdmin) {
            call.respondRedirect("/?err=${enc(Messages.t(sess.language, "users.flash.adminOnlyPage"))}")
            return@get
        }
        val users = userRepo.listAll()
        val adminCount = userRepo.adminCount()
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            UsersTemplates.page(sess.username, sess.userId, users, adminCount, ok, err, sess.csrf, sess.language),
            ContentType.Text.Html,
        )
    }

    post("/users") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
        val me = userRepo.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@post }
        if (!me.isAdmin) {
            call.respondRedirect("/?err=${enc(Messages.t(sess.language, "users.flash.adminOnly"))}")
            return@post
        }
        val form = call.receiveParameters()
        val username = form["username"]?.trim().orEmpty()
        val password = form["password"].orEmpty()
        val role = form["role"]?.trim()?.takeIf { it in setOf("admin", "member", "viewer") } ?: "member"

        UsernamePolicy.violation(username)?.let {
            call.respondRedirect("/users?err=${enc(it)}")
            return@post
        }
        PasswordPolicy.violation(password)?.let {
            call.respondRedirect("/users?err=${enc(it)}")
            return@post
        }
        if (userRepo.findByUsername(username) != null) {
            call.respondRedirect("/users?err=${enc(Messages.t(sess.language, "users.flash.exists", username))}")
            return@post
        }
        val hash = hasher.hash(password)
        userRepo.insert(Ids.deviceId(), username, hash, role)
        log.info { "user created: $username role=$role by ${sess.username}" }
        deps.audit.userCreate(sess.userId, call.request.origin.remoteHost, username, role)
        call.respondRedirect("/users?ok=${enc(Messages.t(sess.language, "users.flash.created", username, role))}")
    }

    post("/users/{id}/role") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
        val me = userRepo.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@post }
        if (!me.isAdmin) {
            call.respondRedirect("/?err=${enc(Messages.t(sess.language, "users.flash.adminOnly"))}")
            return@post
        }
        val targetId = call.parameters["id"]!!
        val form = call.receiveParameters()
        val newRole = form["role"]?.trim()?.takeIf { it in setOf("admin", "member", "viewer") } ?: run {
            call.respondRedirect("/users?err=${enc("invalid role")}")
            return@post
        }
        val target = userRepo.findById(targetId) ?: run {
            call.respondRedirect("/users?err=${enc("user not found")}")
            return@post
        }
        // 마지막 admin 강등 차단
        if (target.isAdmin && newRole != "admin" && userRepo.adminCount() <= 1) {
            call.respondRedirect("/users?err=${enc(Messages.t(sess.language, "users.flash.cantDemoteLastAdmin"))}")
            return@post
        }
        userRepo.setRole(targetId, newRole)
        log.info { "role change: ${target.username} → $newRole by ${sess.username}" }
        deps.audit.userRoleChange(sess.userId, call.request.origin.remoteHost, target.username, newRole)
        call.respondRedirect("/users?ok=${enc("${target.username} → $newRole")}")
    }

    post("/users/{id}/delete") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
        val me = userRepo.findById(sess.userId) ?: run { call.respondRedirect("/login"); return@post }
        if (!me.isAdmin) {
            call.respondRedirect("/?err=${enc(Messages.t(sess.language, "users.flash.adminOnly"))}")
            return@post
        }
        val targetId = call.parameters["id"]!!
        val target = userRepo.findById(targetId) ?: run {
            call.respondRedirect("/users?err=${enc("user not found")}")
            return@post
        }
        if (target.id == sess.userId) {
            call.respondRedirect("/users?err=${enc(Messages.t(sess.language, "users.flash.cantDeleteSelf"))}")
            return@post
        }
        if (target.isAdmin && userRepo.adminCount() <= 1) {
            call.respondRedirect("/users?err=${enc(Messages.t(sess.language, "users.flash.cantDeleteLastAdmin"))}")
            return@post
        }
        // device row 도 cascade — 토큰 즉시 무효화.
        val devices = deviceRepo.listAll().filter { it.userId == targetId }
        for (d in devices) deviceRepo.deleteById(d.id)
        userRepo.delete(targetId)
        log.info { "user delete: ${target.username} (devices=${devices.size}) by ${sess.username}" }
        deps.audit.userDelete(sess.userId, call.request.origin.remoteHost, target.username)
        call.respondRedirect("/users?ok=${enc(Messages.t(sess.language, "users.flash.deleted", target.username, devices.size))}")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

private object UsersTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    fun page(
        currentUsername: String,
        currentUserId: String,
        users: List<com.siamakerlab.vibecoder.server.repo.AdminUserRow>,
        adminCount: Long,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        val rows = if (users.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">no users</td></tr>"""
        } else users.joinToString("") { u ->
            val isMe = u.id == currentUserId
            val roleBadge = when (u.role) {
                "admin" -> """<span class="ok">admin</span>"""
                "viewer" -> """<span class="dim" style="opacity:0.6">viewer</span>"""
                else -> """<span class="dim">member</span>"""
            }
            val totpBadge = if (u.totpEnabled) """ <span class="dim" style="font-size:11px">🔐 2FA</span>""" else ""
            val canDemote = !isMe && (!u.isAdmin || adminCount > 1)
            val canDelete = !isMe && (!u.isAdmin || adminCount > 1)
            // Cycle: admin → member → viewer → admin
            val nextRole = when (u.role) {
                "admin" -> "member"
                "member" -> "viewer"
                else -> "admin"
            }
            val roleBtnLabel = "→ $nextRole"
            val deleteConfirm = Messages.t(lang, "users.row.deleteConfirm", u.username)

            """<tr>
              <td><strong>${esc(u.username)}</strong>${if (isMe) " <small class=\"dim\">${esc(t("users.row.me"))}</small>" else ""}$totpBadge</td>
              <td>$roleBadge</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(u.createdAt)}</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(u.lastLoginAt ?: "-")}</td>
              <td>
                ${if (canDemote) """
                <form method="post" action="/users/${esc(u.id)}/role" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <input type="hidden" name="role" value="$nextRole">
                  <button type="submit" class="chip chip-link" onclick="return confirm('${escJs(u.username)} → $nextRole?')">$roleBtnLabel</button>
                </form>""" else ""}
                <a href="/users/${esc(u.id)}/projects" class="chip chip-link" title="${esc(t("users.row.aclTitle"))}">${esc(t("users.row.acl"))}</a>
                ${if (canDelete) """
                <form method="post" action="/users/${esc(u.id)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('${escJs(deleteConfirm)}')">${esc(t("users.row.deleteBtn"))}</button>
                </form>""" else ""}
              </td>
            </tr>"""
        }

        return AdminTemplates.shell(
            title = t("users.title"),
            username = currentUsername,
            currentPath = "/users",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("users.title"))} <small class="dim" style="font-size:14px;font-weight:400">${esc(Messages.t(lang, "users.subtitle.adminCount", adminCount))}</small></h1>
</header>

$okHtml
$errHtml

<table class="devices" style="margin-bottom:14px">
  <thead><tr><th>username</th><th>role</th><th>${esc(t("users.col.signup"))}</th><th>${esc(t("users.col.lastLogin"))}</th><th>${esc(t("users.col.action"))}</th></tr></thead>
  <tbody>$rows</tbody>
</table>

<div class="card">
  <h2 style="margin-top:0">${esc(t("users.newCard.title"))}</h2>
  <form method="post" action="/users" style="display:grid;grid-template-columns:1fr 1fr 140px auto;gap:8px;align-items:end">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">username
      <input name="username" required pattern="[a-zA-Z0-9._\\-]{3,32}" placeholder="alice">
    </label>
    <label style="margin:0">${esc(t("users.newCard.passwordLabel"))}
      <input name="password" type="password" required minlength="8">
    </label>
    <label style="margin:0">role
      <select name="role">
        <option value="member" selected>member</option>
        <option value="viewer">viewer (read-only)</option>
        <option value="admin">admin</option>
      </select>
    </label>
    <div>
      <button type="submit" class="primary" style="padding:8px 14px">${esc(t("users.newCard.submit"))}</button>
    </div>
  </form>
  <p class="hint" style="margin-top:10px;font-size:12px">
    ${t("users.newCard.hint")}
  </p>
</div>
"""
        )
    }
}
