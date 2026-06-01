package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.env.GlobalClaudeMdService
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v1.35.0 — 전역 CLAUDE.md 관리 (settings 탭). admin 전용.
 *
 *   GET  /settings/claude-md   — 편집기
 *   POST /settings/claude-md   — 저장
 */
fun Routing.globalClaudeMdRoutes(authDeps: AdminRoutesDeps, service: GlobalClaudeMdService) {
    get("/settings/claude-md") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val t = { key: String -> Messages.t(sess.language, key) }
        call.respondText(
            ClaudeMdTemplates.page(
                username = sess.username,
                currentPath = "/settings/claude-md",
                heading = t("claudeMd.global.title"),
                intro = t("claudeMd.global.intro"),
                pathDisplay = service.path.toString(),
                content = service.read(),
                exists = service.exists(),
                sizeBytes = service.sizeBytes(),
                saveAction = "/settings/claude-md",
                csrf = sess.csrf,
                lang = sess.language,
                flashOk = flash(call.request.queryParameters["ok"], sess.language),
                flashErr = call.request.queryParameters["err"],
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/settings/claude-md") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val content = form["content"].orEmpty()
        val t = { key: String -> Messages.t(sess.language, key) }
        if (content.toByteArray(StandardCharsets.UTF_8).size > GlobalClaudeMdService.MAX_BYTES) {
            call.respondRedirect("/settings/claude-md?err=${enc(t("claudeMd.tooLarge"))}")
            return@post
        }
        runCatching { service.write(content) }
            .onFailure { e ->
                log.warn(e) { "global CLAUDE.md save failed" }
                call.respondRedirect("/settings/claude-md?err=${enc(e.message ?: "save failed")}")
                return@post
            }
        log.info { "global CLAUDE.md updated by ${sess.username}" }
        call.respondRedirect("/settings/claude-md?ok=saved")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

private fun flash(code: String?, lang: String): String? =
    if (code == "saved") Messages.t(lang, "claudeMd.flash.saved") else null
