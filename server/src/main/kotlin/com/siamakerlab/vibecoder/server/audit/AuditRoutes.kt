package com.siamakerlab.vibecoder.server.audit

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.repo.AuditLogRepository
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

fun Routing.auditRoutes(authDeps: AdminRoutesDeps, repo: AuditLogRepository) {
    get("/audit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val params = call.request.queryParameters
        val filter = AuditLogRepository.Filter(
            action = params["action"]?.ifBlank { null },
            result = params["result"]?.ifBlank { null },
            userId = params["user"]?.ifBlank { null },
            fromTs = params["from"]?.ifBlank { null },
            toTs = params["to"]?.ifBlank { null },
        )
        val page = (params["p"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val pageSize = 100
        val rows = repo.list(filter, limit = pageSize, offset = page * pageSize.toLong())
        val total = repo.count(filter)
        val actions = repo.distinctActions()
        call.respondText(
            AuditTemplates.page(
                username = sess.username,
                rows = rows,
                filter = filter,
                actions = actions,
                page = page,
                pageSize = pageSize,
                total = total,
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }
}
