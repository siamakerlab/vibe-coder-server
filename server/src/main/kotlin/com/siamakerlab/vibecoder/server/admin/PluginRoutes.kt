package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.env.PluginService
import com.siamakerlab.vibecoder.server.i18n.Messages
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
 * v1.38.0 — 전역(user-scope) 플러그인/마켓플레이스 관리. admin 전용.
 * 변경 작업은 `claude plugin …` task → /env-setup/tasks/{id} 진행 페이지로 redirect.
 */
fun Routing.pluginRoutes(authDeps: AdminRoutesDeps, plugins: PluginService) {
    get("/settings/plugins") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val t = { key: String -> Messages.t(sess.language, key) }
        val all = runCatching { plugins.plugins() }.getOrElse { emptyList() }
        call.respondText(
            PluginTemplates.page(
                username = sess.username, currentPath = "/settings/plugins", scope = "user",
                heading = t("plugins.global.title"), intro = t("plugins.intro.global"),
                actionBase = "/settings/plugins",
                marketplaces = runCatching { plugins.marketplaces() }.getOrElse { emptyList() },
                marketplacesReadonly = false,
                editablePlugins = all.filter { it.scope == "user" },
                readonlyPlugins = emptyList(), readonlyLabel = "", readonlyManageHref = null,
                csrf = sess.csrf, lang = sess.language,
                flashOk = call.request.queryParameters["ok"], flashErr = call.request.queryParameters["err"],
            ),
            ContentType.Text.Html,
        )
    }

    // 모든 변경 = admin + CSRF + task spawn → 진행 페이지.
    suspend fun io.ktor.server.routing.RoutingContext.guard(): WebSession? {
        val sess = requireSessionOrRedirect(authDeps) ?: return null
        if (!requireAdminOrRedirect(sess)) return null
        return sess
    }

    post("/settings/plugins/marketplace/add") {
        val sess = guard() ?: return@post
        val form = requireCsrf()
        val source = form["source"]?.trim().orEmpty()
        if (source.isEmpty()) { call.respondRedirect("/settings/plugins?err=${enc("source required")}"); return@post }
        toTask(call, runCatching { plugins.addMarketplace(source) })
    }
    post("/settings/plugins/marketplace/remove") {
        val sess = guard() ?: return@post
        val name = requireCsrf()["name"]?.trim().orEmpty()
        toTask(call, runCatching { plugins.removeMarketplace(name) })
    }
    post("/settings/plugins/install") {
        val sess = guard() ?: return@post
        val p = requireCsrf()["plugin"]?.trim().orEmpty()
        toTask(call, runCatching { plugins.install(p, scope = "user") })
    }
    post("/settings/plugins/enable") {
        val sess = guard() ?: return@post
        toTask(call, runCatching { plugins.enable(requireCsrf()["plugin"]?.trim().orEmpty(), scope = "user") })
    }
    post("/settings/plugins/disable") {
        val sess = guard() ?: return@post
        toTask(call, runCatching { plugins.disable(requireCsrf()["plugin"]?.trim().orEmpty(), scope = "user") })
    }
    post("/settings/plugins/uninstall") {
        val sess = guard() ?: return@post
        toTask(call, runCatching { plugins.uninstall(requireCsrf()["plugin"]?.trim().orEmpty(), scope = "user") })
    }
    post("/settings/plugins/update") {
        val sess = guard() ?: return@post
        toTask(call, runCatching { plugins.update(requireCsrf()["plugin"]?.trim().orEmpty()) })
    }
}

private suspend fun toTask(call: io.ktor.server.application.ApplicationCall, r: Result<String>) {
    r.onSuccess { call.respondRedirect("/env-setup/tasks/$it") }
        .onFailure { e ->
            log.warn(e) { "plugin command failed" }
            call.respondRedirect("/settings/plugins?err=${enc(e.message ?: "failed")}")
        }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
