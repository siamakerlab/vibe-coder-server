package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

fun Routing.gitRoutes(projects: ProjectService, reader: GitReader) {
    authenticate(AUTH_BEARER) {
        get("/api/projects/{projectId}/git/status") {
            val projectId = call.parameters["projectId"]!!
            call.respond(reader.status(projects.sourcePathOrThrow(projectId)))
        }
        get("/api/projects/{projectId}/git/diff") {
            val projectId = call.parameters["projectId"]!!
            call.respond(reader.diff(projects.sourcePathOrThrow(projectId)))
        }
        get("/api/projects/{projectId}/git/log") {
            val projectId = call.parameters["projectId"]!!
            call.respond(reader.log(projects.sourcePathOrThrow(projectId)))
        }
    }
}
