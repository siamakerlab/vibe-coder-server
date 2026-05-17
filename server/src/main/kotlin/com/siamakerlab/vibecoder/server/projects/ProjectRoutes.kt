package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.projectRoutes(service: ProjectService) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.PROJECTS) { call.respond(service.list()) }

        post(ApiPath.PROJECTS_REGISTER) {
            val body = call.receive<RegisterProjectRequestDto>()
            val dto = service.register(body)
            call.respond(HttpStatusCode.Created, dto)
        }

        get("/api/projects/{projectId}") {
            val id = call.parameters["projectId"]
                ?: throw com.siamakerlab.vibecoder.server.error.ApiException(400, "bad_request", "projectId")
            call.respond(service.get(id))
        }

        delete("/api/projects/{projectId}") {
            val id = call.parameters["projectId"]
                ?: throw com.siamakerlab.vibecoder.server.error.ApiException(400, "bad_request", "projectId")
            val removed = service.delete(id)
            call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }
}
