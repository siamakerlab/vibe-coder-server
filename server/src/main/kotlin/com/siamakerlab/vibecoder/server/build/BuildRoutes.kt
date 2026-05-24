package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.buildRoutes(service: BuildService, hub: LogHub) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/build/debug") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException(400, "bad_request", "projectId")
            val row = service.enqueueDebug(projectId, hub)
            call.respond(HttpStatusCode.Accepted, BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant,
                status = row.status, startedAt = row.startedAt ?: row.createdAt,
                finishedAt = row.finishedAt, artifactId = row.artifactId, errorMessage = row.errorMessage,
            ))
        }
        get("/api/projects/{projectId}/builds") {
            val projectId = call.parameters["projectId"] ?: throw ApiException(400, "bad_request", "projectId")
            call.respond(service.list(projectId))
        }
        get("/api/projects/{projectId}/builds/{buildId}") {
            val projectId = call.parameters["projectId"]!!
            val buildId = call.parameters["buildId"]!!
            call.respond(service.get(projectId, buildId))
        }
        post("/api/projects/{projectId}/builds/{buildId}/cancel") {
            call.requireApiWrite()
            val buildId = call.parameters["buildId"]!!
            service.cancel(buildId)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
