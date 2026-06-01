package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.env.AgentRegistry
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

/**
 * v1.35.0 — 프로젝트별 에이전트 정의 관리 (`<projectRoot>/.claude/agents/NAME.md`).
 *
 * 전역(`~/.claude/agents`)과 프로젝트 둘 다 표시하되 전역은 읽기 전용(편집은 /agents 탭).
 * 두 목록 모두 [AgentRegistry] 가 디스크를 스캔하므로 터미널 / Claude 가 직접 만든 .md
 * 도 자동 감지된다. 프로젝트 항목만 편집/삭제/추가 가능 — write 권한 + Project ACL.
 *
 *   GET  /projects/{id}/agent-defs                — 전역(RO) + 프로젝트(편집) 목록
 *   GET  /projects/{id}/agent-defs/{name}/edit    — 프로젝트 agent 편집
 *   POST /projects/{id}/agent-defs/save           — 프로젝트 agent 저장
 *   POST /projects/{id}/agent-defs/{name}/delete  — 프로젝트 agent 삭제
 */
fun Routing.projectAgentRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    workspace: WorkspacePath,
    globalRegistry: AgentRegistry,
) {
    fun projectRegistry(id: String) =
        AgentRegistry { workspace.projectRoot(id).resolve(".claude").resolve("agents") }

    get("/projects/{id}/agent-defs") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val t = { key: String -> Messages.t(sess.language, key) }
        val globalItems = runCatching { globalRegistry.list() }.getOrElse { emptyList() }
            .map { ScopedManagerTemplates.Item(it.name, "${(it.sizeBytes + 512L) / 1024L}KB", it.preview.take(160)) }
        val projectItems = runCatching { projectRegistry(id).list() }.getOrElse { emptyList() }
            .map { ScopedManagerTemplates.Item(it.name, "${(it.sizeBytes + 512L) / 1024L}KB", it.preview.take(160)) }
        call.respondText(
            ScopedManagerTemplates.listPage(
                username = sess.username, currentPath = "/projects",
                heading = t("agentDefs.title"), intro = t("agentDefs.intro"),
                globalLabel = t("scope.global"), globalManageHref = "/agents",
                projectLabel = t("scope.project"),
                globalPathNote = "~/.claude/agents/", projectPathNote = "$id/.claude/agents/",
                globalItems = globalItems, projectItems = projectItems,
                editBase = "/projects/$id/agent-defs", deleteBase = "/projects/$id/agent-defs",
                newFormAction = "/projects/$id/agent-defs/save", newNamePattern = "[A-Za-z0-9._\\-]{1,64}",
                bodyPlaceholder = "---\nname: my-agent\ndescription: ...\n---\n\nYou are ...",
                csrf = sess.csrf, lang = sess.language,
                flashOk = if (call.request.queryParameters["ok"] != null) call.request.queryParameters["ok"] else null,
                flashErr = call.request.queryParameters["err"],
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    get("/projects/{id}/agent-defs/{name}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = call.parameters["name"]!!
        val t = { key: String -> Messages.t(sess.language, key) }
        val body = projectRegistry(id).read(name)
            ?: run { call.respondRedirect("/projects/$id/agent-defs?err=${enc("'$name' not found")}"); return@get }
        call.respondText(
            ScopedManagerTemplates.editPage(
                username = sess.username, currentPath = "/projects",
                heading = "${t("agentDefs.title")} · ${name}", name = name, body = body,
                saveAction = "/projects/$id/agent-defs/save", backHref = "/projects/$id/agent-defs",
                csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/agent-defs/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val params = call.receiveParameters()
        val name = params["name"]?.trim().orEmpty()
        val body = params["body"].orEmpty()
        runCatching { projectRegistry(id).write(name, body) }
            .onSuccess { call.respondRedirect("/projects/$id/agent-defs?ok=${enc("'$name' 저장됨")}") }
            .onFailure { e ->
                log.warn(e) { "project agent save failed: $id/$name" }
                call.respondRedirect("/projects/$id/agent-defs?err=${enc(e.message ?: "save failed")}")
            }
    }

    post("/projects/{id}/agent-defs/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = call.parameters["name"]!!
        val ok = projectRegistry(id).delete(name)
        val q = if (ok) "ok=${enc("'$name' 삭제됨")}" else "err=${enc("'$name' 삭제 실패")}"
        call.respondRedirect("/projects/$id/agent-defs?$q")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
