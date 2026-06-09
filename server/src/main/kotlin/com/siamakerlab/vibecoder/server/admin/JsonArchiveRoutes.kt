package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.automation.PromptAutomationManager
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectArchiveService
import com.siamakerlab.vibecoder.server.repo.ArchivedProjectRow
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ArchivedProjectDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * v1.119.0 — 프로젝트 아카이브 JSON API (Bearer 토큰 인증). SSR `archiveRoutes`(`/archive`,
 * `/projects/{id}/archive`, `/archive/{aid}/restore|delete|download`)와 동일 동작을
 * `/api/...` 경로로 노출(§8.A SSOT). android `/archives` 화면이 소비.
 *
 * 인증: 단일 admin 모델 — 모든 write 는 [requireApiWrite]. 아카이브는 idle 가드
 * ([isProjectIdle], 폴더 rename 과 동일) 통과 시에만 — 동작 중(응답/빌드/자동화) 프로젝트는
 * 409 project_busy.
 */
fun Routing.jsonArchiveRoutes(
    archive: ProjectArchiveService,
    sessionManager: ClaudeSessionManager,
    buildRepo: BuildRepository,
    promptAutomationManager: PromptAutomationManager,
) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.ARCHIVES) {
            call.requireApiWrite()
            call.respond(archive.list().map { it.toDto() })
        }

        // 현재 프로젝트 아카이브(압축 보관 + DB 정리). idle 가드.
        post("/api/projects/{projectId}/archive") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            if (!isProjectIdle(sessionManager, buildRepo, promptAutomationManager, projectId)) {
                throw ApiException.localized(409, "project_busy", messageKey = "flash.project.rename.notIdle")
            }
            val archiveId = archive.archive(projectId)
            val row = archive.get(archiveId)
                ?: throw ApiException.localized(500, "archive_failed", messageKey = "api.common.archiveFailed")
            call.respond(HttpStatusCode.Accepted, row.toDto())
        }

        // 아카이브 복원(원래 projectId 로 되살림).
        post("/api/archives/{aid}/restore") {
            call.requireApiWrite()
            val aid = call.parameters["aid"]!!
            archive.unarchive(aid)
            call.respond(HttpStatusCode.Accepted)
        }

        // 아카이브 영구 삭제(파일 + 레지스트리 행).
        delete("/api/archives/{aid}") {
            call.requireApiWrite()
            val aid = call.parameters["aid"]!!
            val ok = archive.deleteArchive(aid)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound)
        }

        // 아카이브 .tar.gz 다운로드(attachment).
        get("/api/archives/{aid}/download") {
            call.requireApiWrite()
            val aid = call.parameters["aid"]!!
            val f = archive.archiveFile(aid)
                ?: throw ApiException.localized(404, "archive_not_found", messageKey = "api.common.archiveNotFound")
            call.response.headers.append("Content-Disposition", "attachment; filename=\"$aid.tar.gz\"")
            call.respondFile(f.toFile())
        }
    }
}

/** [ArchivedProjectRow] → wire [ArchivedProjectDto] (내부 경로/manifest 제외). */
private fun ArchivedProjectRow.toDto(): ArchivedProjectDto = ArchivedProjectDto(
    id = id,
    originalId = originalId,
    name = name,
    packageName = packageName,
    archivedAt = archivedAt,
    sizeBytes = sizeBytes,
)
