package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
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
        get(ApiPath.PROJECTS) {
            // v0.49.0 — ACL filter via DevicePrincipal.userRole.
            val p = call.requireDevice()
            val userId = p.device.userId
            if (userId == null) {
                call.respond(service.list())
            } else {
                call.respond(service.listForUser(userId, p.isAdmin))
            }
        }

        post(ApiPath.PROJECTS_REGISTER) {
            call.requireApiWrite()
            val body = call.receive<RegisterProjectRequestDto>()
            val dto = service.register(body)
            call.respond(HttpStatusCode.Created, dto)
        }

        get("/api/projects/{projectId}") {
            val p = call.requireDevice()
            val id = call.parameters["projectId"]
                ?: throw com.siamakerlab.vibecoder.server.error.ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            val uid = p.device.userId
            if (uid != null && !service.canUserAccess(uid, p.isAdmin, id)) {
                throw com.siamakerlab.vibecoder.server.error.ApiException.localized(
                    403, "project_forbidden", messageKey = "api.auth.projectForbidden",
                )
            }
            call.respond(service.get(id))
        }

        delete("/api/projects/{projectId}") {
            call.requireApiWrite()
            val id = call.parameters["projectId"]
                ?: throw com.siamakerlab.vibecoder.server.error.ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            // v1.31.0 (A-B1) — Project ACL. GET 은 canUserAccess 검증하는데 delete 만
            // 누락돼 있었음(비대칭). ACL 제한 member 가 임의 프로젝트 삭제 가능했음.
            call.requireProjectAcl(service, id)
            val removed = service.delete(id)
            call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }
}
