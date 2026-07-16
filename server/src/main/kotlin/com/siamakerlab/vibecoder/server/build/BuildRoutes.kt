package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.publish.PlayPublishService
import com.siamakerlab.vibecoder.server.repo.BuildRow
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.PlayUploadRequestDto
import com.siamakerlab.vibecoder.shared.dto.StoreUploadResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.buildRoutes(
    service: BuildService,
    hub: LogHub,
    projects: ProjectService,
    playPublishService: PlayPublishService,
) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/build/debug") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueDebug(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        // v1.118.0 — Release(APK) / AAB 번들 JSON endpoint. SSR 폼(/projects/{id}/builds/{release|bundle})
        // 과 동일 동작을 ApiPath SSOT 로 노출(§8.A). 키스토어 가드는 service.enqueue* 내부
        // requireKeystoreOrThrow 가 처리(미존재 시 409 keystore_required).
        post("/api/projects/{projectId}/build/release") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueRelease(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post("/api/projects/{projectId}/build/bundle") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueBundle(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        get("/api/projects/{projectId}/builds") {
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            call.respond(service.list(projectId))
        }
        get("/api/projects/{projectId}/builds/{buildId}") {
            val projectId = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, projectId)
            val buildId = call.parameters["buildId"]!!
            call.respond(service.get(projectId, buildId))
        }
        post("/api/projects/{projectId}/builds/{buildId}/cancel") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, projectId)
            val buildId = call.parameters["buildId"]!!
            service.cancel(buildId)
            call.respond(HttpStatusCode.Accepted)
        }
        // v1.121.0 — Google Play 업로드 트리거(빌드→배포 완결). 현재 콘솔 provider 로 업로드 프롬프트 전송.
        post("/api/projects/{projectId}/play-upload") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.get(projectId)  // 존재 검증
            val req = call.receive<PlayUploadRequestDto>()
            val aab = req.aabPath.trim().ifBlank { "app/build/outputs/bundle/release/app-release.aab" }
            playPublishService.trigger(
                projectId = projectId,
                aabRelativePath = aab,
                track = req.track,
                releaseNotes = req.releaseNotes,
            )
            call.respond(HttpStatusCode.Accepted, StoreUploadResponseDto(ok = true))
        }
    }
}

/** [BuildRow] → wire [BuildDto] 매핑. debug/release/bundle JSON 응답 공통. */
private fun BuildRow.toBuildDto(): BuildDto = BuildDto(
    id = id, projectId = projectId, variant = variant,
    status = status, startedAt = startedAt ?: createdAt,
    finishedAt = finishedAt, artifactId = artifactId, errorMessage = errorMessage,
    gitBranch = gitBranch, gitSha = gitSha,
)
