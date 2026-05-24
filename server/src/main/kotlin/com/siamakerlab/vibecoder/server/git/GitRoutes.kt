package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * v0.18.0 — Read-only git API + 새 write API (commit & push).
 */
fun Routing.gitRoutes(
    projects: ProjectService,
    reader: GitReader,
    writer: GitWriter,
    audit: AuditLogger,
) {
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

        // v0.18.0 — commit (+ optional push)
        post("/api/projects/{projectId}/git/commit") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]!!
            val body = call.receive<GitCommitRequestDto>()
            val source = projects.sourcePathOrThrow(projectId)
            val result = try {
                writer.commitAndPush(
                    source = source,
                    message = body.message,
                    push = body.push,
                    onlyTracked = body.onlyTracked,
                )
            } catch (e: ApiException) {
                audit.gitCommit(call.requireDevice().device.userId, projectId, false, body.push,
                    call.request.local.remoteHost)
                throw e
            }
            audit.gitCommit(call.requireDevice().device.userId, projectId,
                ok = result.committed, push = result.pushed,
                ip = call.request.local.remoteHost)
            call.respond(GitCommitResponseDto(
                committed = result.committed, pushed = result.pushed,
                branch = result.branch, sha = result.sha, log = result.log,
            ))
        }
    }
}

@Serializable
data class GitCommitRequestDto(
    val message: String,
    val push: Boolean = true,
    val onlyTracked: Boolean = false,
)

@Serializable
data class GitCommitResponseDto(
    val committed: Boolean,
    val pushed: Boolean,
    val branch: String,
    val sha: String?,
    val log: String,
)
