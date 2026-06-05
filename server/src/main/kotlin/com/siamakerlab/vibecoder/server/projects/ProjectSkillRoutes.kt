package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
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
 * v1.35.0 — 프로젝트별 스킬 관리 (`<projectRoot>/.claude/skills/<name>/SKILL.md`).
 * 전역(~/.claude/skills) RO + 프로젝트(편집). 둘 다 디스크 스캔 → 외부 생성 감지.
 */
fun Routing.projectSkillRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    workspace: WorkspacePath,
    globalRegistry: SkillRegistry,
) {
    fun projectRegistry(id: String) =
        SkillRegistry { workspace.projectRoot(id).resolve(".claude").resolve("skills") }

    fun item(s: SkillRegistry.Skill) = ScopedManagerTemplates.Item(
        name = if (s.hasSkillMd) s.name else "${s.name} ⚠",
        sizeLabel = "${(s.sizeBytes + 512L) / 1024L}KB",
        preview = s.preview.take(160),
    )

    get("/projects/{id}/skills") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val t = { key: String -> Messages.t(sess.language, key) }
        val globalItems = runCatching { globalRegistry.list() }.getOrElse { emptyList() }.map(::item)
        val projectItems = runCatching { projectRegistry(id).list() }.getOrElse { emptyList() }.map(::item)
        call.respondText(
            ScopedManagerTemplates.listPage(
                username = sess.username, currentPath = "/projects",
                heading = t("skills.project.title"), intro = t("skills.intro"),
                globalLabel = t("scope.global"), globalManageHref = "/settings/skills",
                projectLabel = t("scope.project"),
                globalPathNote = "~/.claude/skills/", projectPathNote = "$id/.claude/skills/",
                globalItems = globalItems, projectItems = projectItems,
                editBase = "/projects/$id/skills", deleteBase = "/projects/$id/skills",
                newFormAction = "/projects/$id/skills/save", newNamePattern = "[A-Za-z0-9._\\-]{1,64}",
                bodyPlaceholder = SKILL_PLACEHOLDER, csrf = sess.csrf, lang = sess.language,
                flashOk = call.request.queryParameters["ok"], flashErr = call.request.queryParameters["err"],
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    get("/projects/{id}/skills/{name}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = call.parameters["name"]!!
        val t = { key: String -> Messages.t(sess.language, key) }
        val body = projectRegistry(id).read(name)
            ?: run { call.respondRedirect("/projects/$id/skills?err=${enc("'$name' not found")}"); return@get }
        call.respondText(
            ScopedManagerTemplates.editPage(
                username = sess.username, currentPath = "/projects",
                heading = "${t("skills.project.title")} · $name", name = name, body = body,
                saveAction = "/projects/$id/skills/save", backHref = "/projects/$id/skills",
                csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/skills/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = params["name"]?.trim().orEmpty()
        val body = params["body"].orEmpty()
        runCatching { projectRegistry(id).write(name, body) }
            .onSuccess { call.respondRedirect("/projects/$id/skills?ok=${enc("'$name' 저장됨")}") }
            .onFailure { e ->
                log.warn(e) { "project skill save failed: $id/$name" }
                call.respondRedirect("/projects/$id/skills?err=${enc(e.message ?: "save failed")}")
            }
    }

    post("/projects/{id}/skills/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = call.parameters["name"]!!
        val ok = projectRegistry(id).delete(name)
        val q = if (ok) "ok=${enc("'$name' 삭제됨")}" else "err=${enc("'$name' 삭제 실패")}"
        call.respondRedirect("/projects/$id/skills?$q")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
