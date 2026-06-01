package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.env.SkillRegistry
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.scope.ScopedManagerTemplates
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

private const val SKILL_PLACEHOLDER =
    "---\nname: my-skill\ndescription: When to use this skill ...\n---\n\n# Instructions\n\n..."

/**
 * v1.35.0 — 전역 스킬 관리 (settings 탭, admin 전용). `~/.claude/skills/<name>/SKILL.md`.
 * 디스크 스캔 기반이라 터미널/Claude 가 직접 만든 스킬도 표시.
 */
fun Routing.skillRoutes(authDeps: AdminRoutesDeps, registry: SkillRegistry) {
    fun item(s: SkillRegistry.Skill) = ScopedManagerTemplates.Item(
        name = if (s.hasSkillMd) s.name else "${s.name} ⚠",
        sizeLabel = "${(s.sizeBytes + 512L) / 1024L}KB",
        preview = s.preview.take(160),
    )

    get("/settings/skills") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val t = { key: String -> Messages.t(sess.language, key) }
        val items = runCatching { registry.list() }.getOrElse { emptyList() }.map(::item)
        call.respondText(
            ScopedManagerTemplates.managePage(
                username = sess.username, currentPath = "/settings/skills",
                heading = t("skills.global.title"), intro = t("skills.intro.global"),
                pathNote = "~/.claude/skills/", items = items,
                editBase = "/settings/skills", deleteBase = "/settings/skills",
                newFormAction = "/settings/skills/save", newNamePattern = "[A-Za-z0-9._\\-]{1,64}",
                bodyPlaceholder = SKILL_PLACEHOLDER, csrf = sess.csrf, lang = sess.language,
                flashOk = call.request.queryParameters["ok"], flashErr = call.request.queryParameters["err"],
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    get("/settings/skills/{name}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val name = call.parameters["name"]!!
        val t = { key: String -> Messages.t(sess.language, key) }
        val body = registry.read(name)
            ?: run { call.respondRedirect("/settings/skills?err=${enc("'$name' not found")}"); return@get }
        call.respondText(
            ScopedManagerTemplates.editPage(
                username = sess.username, currentPath = "/settings/skills",
                heading = "${t("skills.global.title")} · $name", name = name, body = body,
                saveAction = "/settings/skills/save", backHref = "/settings/skills",
                csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/settings/skills/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val params = call.receiveParameters()
        val name = params["name"]?.trim().orEmpty()
        val body = params["body"].orEmpty()
        runCatching { registry.write(name, body) }
            .onSuccess { call.respondRedirect("/settings/skills?ok=${enc("'$name' 저장됨")}") }
            .onFailure { e ->
                log.warn(e) { "global skill save failed: $name" }
                call.respondRedirect("/settings/skills?err=${enc(e.message ?: "save failed")}")
            }
    }

    post("/settings/skills/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val name = call.parameters["name"]!!
        val ok = registry.delete(name)
        val q = if (ok) "ok=${enc("'$name' 삭제됨")}" else "err=${enc("'$name' 삭제 실패")}"
        call.respondRedirect("/settings/skills?$q")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
