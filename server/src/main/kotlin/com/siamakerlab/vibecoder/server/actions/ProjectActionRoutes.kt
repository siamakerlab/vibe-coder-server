package com.siamakerlab.vibecoder.server.actions

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.shared.dto.ActionInvokeRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/** Design §7 — hard cap on `params` JSON payload. */
private const val MAX_ACTION_PARAMS_BYTES = 4 * 1024

/**
 * Routes:
 *   GET  /api/projects/{id}/actions            → ActionTreeDto
 *   POST /api/projects/{id}/actions/invoke     → 202 Accepted (work happens async via ConsoleHub)
 */
fun Routing.projectActionRoutes(
    projects: ProjectService,
    registry: ProjectActionRegistry,
    handler: ServerActionHandler,
    capabilities: CapabilityService,
) {
    authenticate(AUTH_BEARER) {
        get("/api/projects/{projectId}/actions") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            projects.rowOrThrow(projectId)
            call.respond(registry.listForProject(projectId, capabilities.forProject(projectId)))
        }

        post("/api/projects/{projectId}/actions/invoke") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            projects.rowOrThrow(projectId)
            val body = call.receive<ActionInvokeRequestDto>()
            val paramsSize = body.params?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
            if (paramsSize > MAX_ACTION_PARAMS_BYTES) {
                throw ApiException(
                    statusCode = 413,
                    code = "params_too_large",
                    message = "action params exceed $MAX_ACTION_PARAMS_BYTES bytes (got $paramsSize)",
                )
            }
            val action = registry.findAction(projectId, body.actionId)
                ?: throw ApiException(404, "action_not_found", body.actionId)
            try {
                handler.dispatch(projectId, action, body.params)
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[$projectId] action dispatch failed: ${body.actionId}" }
                throw ApiException(500, "action_dispatch_failed", e.message ?: "unknown error")
            }
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
