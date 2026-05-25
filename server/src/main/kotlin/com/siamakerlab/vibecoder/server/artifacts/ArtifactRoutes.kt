package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.nio.file.Path

fun Routing.artifactRoutes(
    repo: ArtifactRepository,
    workspace: WorkspacePath,
    service: ArtifactService,
    /** v0.70.0 — Phase 49 #14 APK 시그너처 verify. */
    apkVerifier: ApkVerifier,
) {
    authenticate(AUTH_BEARER) {
        get("/api/projects/{projectId}/artifacts") {
            val projectId = call.parameters["projectId"]!!
            call.respond(repo.listForProject(projectId).map { service.toDto(it) })
        }
        get("/api/projects/{projectId}/artifacts/{artifactId}") {
            val projectId = call.parameters["projectId"]!!
            val artifactId = call.parameters["artifactId"]!!
            val row = repo.get(projectId, artifactId)
                ?: throw ApiException(404, "artifact_not_found", artifactId)
            call.respond(service.toDto(row))
        }
        // v0.70.0 — Phase 49 #14: APK 시그너처 on-demand verify.
        // 결과는 DB 영속 안함 — 사용자 클릭 시점에만 apksigner 실행 (1-5초).
        get("/api/projects/{projectId}/artifacts/{artifactId}/verify") {
            val projectId = call.parameters["projectId"]!!
            val artifactId = call.parameters["artifactId"]!!
            call.respond(apkVerifier.verify(projectId, artifactId))
        }
        get("/api/projects/{projectId}/artifacts/{artifactId}/download") {
            val projectId = call.parameters["projectId"]!!
            val artifactId = call.parameters["artifactId"]!!
            val row = repo.get(projectId, artifactId)
                ?: throw ApiException(404, "artifact_not_found", artifactId)
            val file = Path.of(row.filePath)
            workspace.ensureUnderWorkspace(file)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, row.fileName,
                ).toString(),
            )
            call.response.header("X-Sha256", row.sha256)
            call.respondFile(file.toFile())
        }
    }
}
