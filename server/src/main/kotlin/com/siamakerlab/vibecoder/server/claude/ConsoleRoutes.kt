package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.BroadcastRejectDto
import com.siamakerlab.vibecoder.shared.dto.BroadcastSendRequestDto
import com.siamakerlab.vibecoder.shared.dto.BroadcastSendResponseDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.PromptAcceptedDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/** v1.136.0 — 일괄 전송 1회에 허용하는 최대 프로젝트 수 (운영 단일 사용자 기준 여유값). */
private const val MAX_BROADCAST_PROJECTS = 100

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
            val images = validatedImages(body)

            try {
                sessionManager.sendPrompt(projectId, text, images = images)
            } catch (e: Exception) {
                log.warn(e) { "[$projectId] prompt failed" }
                throw ApiException.localized(500, "claude_send_failed",
                    messageKey = "api.console.sendFailed", args = listOf(e.message ?: "unknown error"))
            }

            val seq = hub.consoleCurrentSeq(LogHub.consoleTopic(projectId))
            call.respond(HttpStatusCode.Accepted, PromptAcceptedDto(seq = seq))
        }

        // v1.136.0 — 프롬프트 일괄 전송: 선택한 여러 프로젝트의 메인 콘솔에 같은 프롬프트.
        // 즉시 202 (accepted/rejected) — 실제 전송은 비동기, 동시 turn 게이트가 순차 처리(큐).
        post(ApiPath.CLAUDE_BROADCAST) {
            call.requireApiWrite()
            val env = envDiagnostics.run()
            if (env.claude.status != CheckStatus.OK) {
                throw ApiException.localized(503, "claude_cli_missing", messageKey = "api.console.claudeCliMissing")
            }
            if (env.claudeAuth?.status == CheckStatus.ERROR) {
                throw ApiException.localized(503, "claude_auth_required", messageKey = "api.console.claudeAuthRequired")
            }
            val body = call.receive<BroadcastSendRequestDto>()
            val text = body.prompt.trim()
            if (text.isEmpty()) throw ApiException.localized(400, "bad_request", messageKey = "api.console.textRequired")
            val byteSize = text.toByteArray(Charsets.UTF_8).size
            if (byteSize > ClaudeSessionManager.MAX_PROMPT_BYTES) {
                throw ApiException.localized(400, "prompt_too_large",
                    messageKey = "api.console.promptTooLarge",
                    args = listOf(ClaudeSessionManager.MAX_PROMPT_BYTES, byteSize))
            }
            val ids = body.projectIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty() || ids.size > MAX_BROADCAST_PROJECTS) {
                throw ApiException.localized(400, "bad_request", messageKey = "api.broadcast.projectsRequired")
            }
            val accepted = mutableListOf<String>()
            val rejected = mutableListOf<BroadcastRejectDto>()
            for (id in ids) {
                val exists = !WorkspacePath.isGhostId(id) && runCatching { projects.rowOrThrow(id) }.isSuccess
                if (exists) {
                    accepted += id
                    sessionManager.sendPromptAsync(id, text)
                } else {
                    rejected += BroadcastRejectDto(id, "not_found")
                }
            }
            log.info { "broadcast prompt → ${accepted.size} project(s) (rejected=${rejected.size}, bytes=$byteSize)" }
            call.respond(HttpStatusCode.Accepted, BroadcastSendResponseDto(accepted, rejected))
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
        // v1.112.0 — 내부 구현이 control_request interrupt(같은 세션 유지) 로 변경(엔드포인트 동일).
        post("/api/projects/{projectId}/claude/console/cancel") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            sessionManager.cancelTurn(projectId)
            val device = call.requireDevice().device
            audit.consoleCancel(device.userId, projectId, call.request.origin.remoteHost)
            call.respond(HttpStatusCode.Accepted)
        }

        // v1.112.0 — "끼어들기": 진행 중 turn 을 interrupt 로 중단하고 곧바로 새 prompt 전송.
        // 요청/응답 형태는 console/prompt 와 동일(PromptRequestDto → PromptAcceptedDto).
        post("/api/projects/{projectId}/claude/console/interrupt") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)

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
            val byteSize = text.toByteArray(Charsets.UTF_8).size
            if (byteSize > ClaudeSessionManager.MAX_PROMPT_BYTES) {
                throw ApiException.localized(400, "prompt_too_large",
                    messageKey = "api.console.promptTooLarge",
                    args = listOf(ClaudeSessionManager.MAX_PROMPT_BYTES, byteSize))
            }
            val images = validatedImages(body)

            try {
                sessionManager.interruptAndSend(projectId, text, images = images)
            } catch (e: Exception) {
                log.warn(e) { "[$projectId] interrupt-send failed" }
                throw ApiException.localized(500, "claude_send_failed",
                    messageKey = "api.console.sendFailed", args = listOf(e.message ?: "unknown error"))
            }

            val device = call.requireDevice().device
            audit.consoleCancel(device.userId, projectId, call.request.origin.remoteHost)
            val seq = hub.consoleCurrentSeq(LogHub.consoleTopic(projectId))
            call.respond(HttpStatusCode.Accepted, PromptAcceptedDto(seq = seq))
        }

        get("/api/projects/{projectId}/claude/status") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            // v1.46.0 — 비차단 캐시-온리(동기 TUI 캡처 hang 회수). usage 는 백그라운드 폴러가 갱신.
            call.respond(statusService.cachedSnapshot(projectId))
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

        // ── v1.120.0 콘솔 토큰/모델 설정 (SSR /console/{model|mcp-strict|auto-compact} 의 JSON) ──
        get("/api/projects/{projectId}/claude/console/settings") {
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            call.respond(buildConsoleSettings(sessionManager, projectId))
        }
        post("/api/projects/{projectId}/claude/console/model") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val model = call.receive<com.siamakerlab.vibecoder.shared.dto.ConsoleModelRequestDto>().model
                ?.trim()?.ifBlank { null }
            sessionManager.setProjectModelAndRestart(projectId, model)
            call.respond(buildConsoleSettings(sessionManager, projectId))
        }
        post("/api/projects/{projectId}/claude/console/mcp-strict") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val enabled = call.receive<com.siamakerlab.vibecoder.shared.dto.ConsoleToggleRequestDto>().enabled
            sessionManager.setMcpStrictAndRestart(projectId, enabled)
            call.respond(buildConsoleSettings(sessionManager, projectId))
        }
        post("/api/projects/{projectId}/claude/console/auto-compact") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val enabled = call.receive<com.siamakerlab.vibecoder.shared.dto.ConsoleToggleRequestDto>().enabled
            sessionManager.setAutoCompact(projectId, enabled)
            call.respond(buildConsoleSettings(sessionManager, projectId))
        }
    }
}

/**
 * v1.133.0 — 프롬프트 첨부 이미지 검증. 형식/한도 위반은 400 (image_invalid) 으로 변환.
 */
private fun validatedImages(body: PromptRequestDto): List<com.siamakerlab.vibecoder.shared.dto.PromptImageDto> {
    val images = body.images.orEmpty()
    try {
        ClaudeSessionManager.validateImages(images)
    } catch (e: IllegalArgumentException) {
        throw ApiException.localized(400, "image_invalid",
            messageKey = "api.console.imageInvalid", args = listOf(e.message ?: "invalid image"))
    }
    return images
}

/** v1.120.0 — 콘솔 설정 현재 상태 → DTO. 모델 목록은 알려진 4종(기본 포함). */
private fun buildConsoleSettings(
    sessionManager: ClaudeSessionManager,
    projectId: String,
): com.siamakerlab.vibecoder.shared.dto.ConsoleSettingsDto =
    com.siamakerlab.vibecoder.shared.dto.ConsoleSettingsDto(
        model = sessionManager.readProjectModel(projectId),
        effectiveModel = sessionManager.effectiveModel(projectId),
        autoCompact = sessionManager.isAutoCompact(projectId),
        mcpStrict = sessionManager.isMcpStrict(projectId),
        availableModels = listOf(
            com.siamakerlab.vibecoder.shared.dto.ConsoleModelOptionDto("", "CLI default"),
            com.siamakerlab.vibecoder.shared.dto.ConsoleModelOptionDto("sonnet", "Sonnet"),
            com.siamakerlab.vibecoder.shared.dto.ConsoleModelOptionDto("opus", "Opus"),
            com.siamakerlab.vibecoder.shared.dto.ConsoleModelOptionDto("haiku", "Haiku"),
        ),
    )
