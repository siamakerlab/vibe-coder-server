package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.PromptAcceptedDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
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

/**
 * Routes for the persistent Claude console session attached to a project.
 *
 * `POST .../console/prompt` — append a user turn (spawns the session on first call).
 *   Response is 202 Accepted with the post-emit seq baseline so the client can
 *   correlate when the answer arrives over WS.
 *
 * `POST .../console/new`    — terminate current session + delete saved session-id.
 *
 * `GET  .../claude/status`  — best-effort session snapshot (no /status invocation
 *   in Phase A; Phase E will plug in [ClaudeStatusService]).
 */
fun Routing.consoleRoutes(
    projects: ProjectService,
    sessionManager: ClaudeSessionManager,
    hub: LogHub,
    statusService: ClaudeStatusService,
    envDiagnostics: EnvDiagnostics,
    audit: AuditLogger,
    /** v0.31.0 — prompt 자동완성. */
    promptSuggestionService: PromptSuggestionService,
) {
    authenticate(AUTH_BEARER) {
        post("/api/projects/{projectId}/claude/console/prompt") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            // ensure project is registered (404 path matches the rest of the codebase)
            projects.rowOrThrow(projectId)

            // 인증 안 된 상태에서 자식 프로세스를 띄우면 사용자는 의미 없는 stderr 만 보게 된다.
            // 미리 차단하고 명확한 가이드를 응답으로 돌려준다.
            val env = envDiagnostics.run()
            if (env.claude.status != CheckStatus.OK) {
                throw ApiException.localized(503, "claude_cli_missing", messageKey = "api.console.claudeCliMissing")
            }
            if (env.claudeAuth?.status == CheckStatus.ERROR) {
                throw ApiException.localized(503, "claude_auth_required", messageKey = "api.console.claudeAuthRequired")
            }

            val body = call.receive<PromptRequestDto>()
            val text = body.text.trim()
            if (text.isEmpty()) throw ApiException.localized(400, "bad_request", messageKey = "api.console.textRequired")
            // UTF-8 byte 기준으로 통일 (sendPrompt 내부 검증과 일치). char count 검증은
            // 한국어 등에서 한 글자가 3 byte 가 되어 의도가 어긋났다.
            val byteSize = text.toByteArray(Charsets.UTF_8).size
            if (byteSize > ClaudeSessionManager.MAX_PROMPT_BYTES) {
                throw ApiException.localized(400, "prompt_too_large",
                    messageKey = "api.console.promptTooLarge",
                    args = listOf(ClaudeSessionManager.MAX_PROMPT_BYTES, byteSize))
            }

            try {
                sessionManager.sendPrompt(projectId, text)
            } catch (e: Exception) {
                log.warn(e) { "[$projectId] prompt failed" }
                throw ApiException.localized(500, "claude_send_failed",
                    messageKey = "api.console.sendFailed", args = listOf(e.message ?: "unknown error"))
            }

            val seq = hub.consoleCurrentSeq(LogHub.consoleTopic(projectId))
            call.respond(HttpStatusCode.Accepted, PromptAcceptedDto(seq = seq))
        }

        post("/api/projects/{projectId}/claude/console/new") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            sessionManager.startNew(projectId)
            call.respond(HttpStatusCode.Accepted)
        }

        // v0.13.0 — 진행 중인 turn 중단. session-id 는 보존.
        post("/api/projects/{projectId}/claude/console/cancel") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            sessionManager.cancelTurn(projectId)
            val device = call.requireDevice().device
            audit.consoleCancel(device.userId, projectId, call.request.local.remoteHost)
            call.respond(HttpStatusCode.Accepted)
        }

        get("/api/projects/{projectId}/claude/status") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            call.respond(statusService.snapshot(projectId))
        }

        // v0.31.0 — prompt 자동완성 (history 기반 prefix 매치).
        get("/api/projects/{projectId}/claude/prompt-suggestions") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val prefix = call.request.queryParameters["prefix"]?.trim().orEmpty()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 8
            val suggestions = promptSuggestionService.suggest(projectId, prefix, limit)
            call.respond(mapOf("suggestions" to suggestions))
        }
    }
}
