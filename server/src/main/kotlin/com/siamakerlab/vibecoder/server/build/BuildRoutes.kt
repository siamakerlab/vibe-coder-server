package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.publish.PlayPublishService
import com.siamakerlab.vibecoder.server.publish.TestFlightPublishService
import com.siamakerlab.vibecoder.server.repo.BuildRow
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadJobRepository
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadStatus
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.PlayUploadRequestDto
import com.siamakerlab.vibecoder.shared.dto.StoreUploadResponseDto
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadRequestDto
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadResponseDto
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadStatusUpdateRequestDto
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
    testFlightPublishService: TestFlightPublishService? = null,
    testFlightUploadJobRepo: TestFlightUploadJobRepository? = null,
) {
    authenticate(AUTH_BEARER) {
        post(ApiPath.buildDebug("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueDebug(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        // v1.118.0 — Release(APK) / AAB 번들 JSON endpoint. SSR 폼(/projects/{id}/builds/{release|bundle})
        // 과 동일 동작을 ApiPath SSOT 로 노출(§8.A). 키스토어 가드는 service.enqueue* 내부
        // requireKeystoreOrThrow 가 처리(미존재 시 409 keystore_required).
        post(ApiPath.buildRelease("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueRelease(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post(ApiPath.buildBundle("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueBundle(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post(ApiPath.iosBuildDebug("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueIosDebug(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post(ApiPath.iosBuildTest("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueIosTest(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post(ApiPath.iosBuildArchive("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueIosArchive(projectId, hub)
            call.respond(HttpStatusCode.Accepted, row.toBuildDto())
        }
        post(ApiPath.iosBuildExportIpa("{projectId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"] ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val row = service.enqueueIosExportIpa(projectId, hub)
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
        get(ApiPath.testFlightUploads("{projectId}")) {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val repo = testFlightUploadJobRepo ?: error("testflight upload job repository unavailable")
            call.respond(repo.listForProject(projectId, limit = 20))
        }
        post(ApiPath.testFlightUploadStatus("{projectId}", "{jobId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val jobId = call.parameters["jobId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.testflightUpload.jobIdRequired")
            val repo = testFlightUploadJobRepo ?: error("testflight upload job repository unavailable")
            val req = call.receive<TestFlightUploadStatusUpdateRequestDto>()
            val status = TestFlightUploadStatus.fromWire(req.status)
                ?: throw ApiException.localized(400, "invalid_testflight_status", messageKey = "api.testflightUpload.invalidStatus", args = listOf(req.status))
            val existing = repo.get(jobId)
                ?: throw ApiException.localized(404, "testflight_upload_not_found", messageKey = "api.testflightUpload.notFound", args = listOf(jobId))
            if (existing.projectId != projectId) {
                throw ApiException.localized(404, "testflight_upload_not_found", messageKey = "api.testflightUpload.notFound", args = listOf(jobId))
            }
            val updated = repo.mark(jobId, status, message = req.message, errorCode = req.errorCode)
                ?: throw ApiException.localized(404, "testflight_upload_not_found", messageKey = "api.testflightUpload.notFound", args = listOf(jobId))
            call.respond(updated)
        }
        post(ApiPath.testFlightUpload("{projectId}", "{buildId}")) {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val buildId = call.parameters["buildId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.buildIdRequired")
            val project = projects.get(projectId)
            val build = service.get(projectId, buildId)
            val req = call.receive<TestFlightUploadRequestDto>()
            val ipa = req.ipaPath.trim().ifBlank { "out/app-release.ipa" }
            val publisher = testFlightPublishService ?: error("testflight publish service unavailable")
            val asc = publisher.diagnoseApp(project.packageName)
            val nextBuildNumber = publisher.nextBuildNumber(project.sourcePath)
            val job = publisher.trigger(
                TestFlightPublishService.UploadRequest(
                    projectId = projectId,
                    buildId = buildId,
                    artifactId = build.artifactId,
                    ipaRelativePath = ipa,
                    bundleId = project.packageName,
                    appId = asc?.matchingAppId,
                    appName = asc?.matchingAppName,
                    buildNumber = nextBuildNumber,
                    distributionGroups = req.distributionGroups,
                    releaseNotes = req.releaseNotes,
                )
            ) ?: error("testflight upload job repository unavailable")
            call.respond(HttpStatusCode.Accepted, TestFlightUploadResponseDto(ok = true, job = job))
        }
    }
}

/** [BuildRow] → wire [BuildDto] 매핑. debug/release/bundle JSON 응답 공통. */
private fun BuildRow.toBuildDto(): BuildDto = BuildDto(
    id = id, projectId = projectId, variant = variant,
    status = status, startedAt = startedAt ?: createdAt,
    finishedAt = finishedAt, artifactId = artifactId, errorMessage = errorMessage, failureKind = failureKind,
    gitBranch = gitBranch, gitSha = gitSha,
)
