package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.PluginTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.env.PluginService
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
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
 * v1.38.0 — 프로젝트별(project-scope) 플러그인 관리. admin 전용(플러그인은 코드 실행).
 * 전역(user-scope) 플러그인은 읽기 전용으로 함께 표시. project-scope 작업은 cwd=projectRoot.
 */
fun Routing.projectPluginRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    workspace: WorkspacePath,
    plugins: PluginService,
) {
    get("/projects/{id}/plugins") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val id = call.parameters["id"]!!
        runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 없음")}"); return@get
        }
        val t = { key: String -> Messages.t(sess.language, key) }
        val root = workspace.projectRoot(id)
        val all = runCatching { plugins.plugins(cwd = root) }.getOrElse { emptyList() }
        call.respondText(
            PluginTemplates.page(
                username = sess.username, currentPath = "/projects", scope = "project",
                heading = t("plugins.project.title"), intro = t("plugins.intro.project"),
                actionBase = "/projects/$id/plugins",
                marketplaces = runCatching { plugins.marketplaces() }.getOrElse { emptyList() },
                marketplacesReadonly = true,
                editablePlugins = all.filter { it.scope == "project" },
                readonlyPlugins = all.filter { it.scope == "user" },
                readonlyLabel = t("scope.global"), readonlyManageHref = "/settings/plugins",
                csrf = sess.csrf, lang = sess.language,
                flashOk = call.request.queryParameters["ok"], flashErr = call.request.queryParameters["err"],
            ),
            ContentType.Text.Html,
        )
    }

    fun route(action: String, op: (String, java.nio.file.Path) -> String) {
        post("/projects/{id}/plugins/$action") {
            val sess = requireSessionOrRedirect(authDeps) ?: return@post
            if (!requireAdminOrRedirect(sess)) return@post
            val id = call.parameters["id"]!!
            val plugin = requireCsrf()["plugin"]?.trim().orEmpty()
            val root = workspace.projectRoot(id)
            runCatching { op(plugin, root) }
                .onSuccess { call.respondRedirect("/env-setup/tasks/$it") }
                .onFailure { e ->
                    log.warn(e) { "project plugin $action failed: $id/$plugin" }
                    call.respondRedirect("/projects/$id/plugins?err=${enc(e.message ?: "failed")}")
                }
        }
    }
    route("install") { p, root -> plugins.install(p, scope = "project", cwd = root) }
    route("enable") { p, root -> plugins.enable(p, scope = "project", cwd = root) }
    route("disable") { p, root -> plugins.disable(p, scope = "project", cwd = root) }
    route("uninstall") { p, root -> plugins.uninstall(p, scope = "project", cwd = root) }
    route("update") { p, root -> plugins.update(p, cwd = root) }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
