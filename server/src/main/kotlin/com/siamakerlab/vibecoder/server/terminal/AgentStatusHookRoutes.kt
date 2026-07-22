package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

private val agentStatusHookLog = KotlinLogging.logger {}
private val hookJson = Json { ignoreUnknownKeys = true }

fun Routing.agentStatusHookRoutes(
    broadcaster: AgentStatusBroadcaster,
    expectedToken: String,
) {
    post(ApiPath.INTERNAL_CLAUDE_AGENT_EVENTS) {
        val remote = call.request.origin.remoteHost
        if (remote !in setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost")) {
            call.respondText("forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return@post
        }
        if (call.request.queryParameters["token"] != expectedToken) {
            call.respondText("forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            return@post
        }
        val projectId = call.request.queryParameters["projectId"]?.trim().orEmpty()
        if (projectId.isBlank()) {
            throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")
        }
        val raw = call.receiveText()
        val event = runCatching { hookJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (event == null) {
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        val sessionId = call.request.queryParameters["sessionId"]?.trim()?.ifBlank { null }
            ?: event.string("session_id")
        val snapshot = mapClaudeHookEvent(projectId, sessionId, event)
        if (snapshot != null) {
            runCatching { broadcaster.publish(snapshot) }
                .onFailure { agentStatusHookLog.warn(it) { "Claude hook status publish failed for $projectId" } }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

internal fun mapClaudeHookEvent(
    projectId: String,
    sessionId: String?,
    event: JsonObject,
    nowMs: Long = Instant.now().toEpochMilli(),
): AgentStatusSnapshot? {
    val name = event.string("hook_event_name") ?: event.string("event") ?: event.string("type") ?: return null
    return when (name) {
        "SessionStart" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.IDLE,
            message = "Claude session started",
            lastEventAt = nowMs,
        )
        "UserPromptSubmit" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.RUNNING,
            activity = AgentActivity.THINKING,
            lastEventAt = nowMs,
            turnStartedAt = nowMs,
        )
        "PreToolUse" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.RUNNING,
            activity = AgentActivity.TOOL_EXECUTION,
            currentTool = event.string("tool_name") ?: event.string("toolName"),
            lastEventAt = nowMs,
        )
        "PostToolUse" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.RUNNING,
            activity = AgentActivity.THINKING,
            lastEventAt = nowMs,
        )
        "PostToolUseFailure" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = if (event.bool("is_interrupt") == true) AgentState.INTERRUPTED else AgentState.RUNNING,
            activity = if (event.bool("is_interrupt") == true) null else AgentActivity.THINKING,
            currentTool = event.string("tool_name") ?: event.string("toolName"),
            error = event.string("error"),
            lastEventAt = nowMs,
        )
        "PermissionRequest" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.WAITING_APPROVAL,
            currentTool = event.string("tool_name") ?: event.string("toolName"),
            message = event.string("message"),
            lastEventAt = nowMs,
        )
        "PermissionDenied" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.RUNNING,
            activity = AgentActivity.THINKING,
            currentTool = event.string("tool_name") ?: event.string("toolName"),
            message = event.string("reason"),
            lastEventAt = nowMs,
        )
        "Notification" -> when (event.string("notification_type")) {
            "permission_prompt" -> AgentStatusSnapshot(
                projectId = projectId,
                provider = AgentProvider.CLAUDE,
                sessionId = sessionId,
                state = AgentState.WAITING_APPROVAL,
                message = event.string("message") ?: event.string("notification"),
                lastEventAt = nowMs,
            )
            "idle_prompt", "agent_completed" -> AgentStatusSnapshot(
                projectId = projectId,
                provider = AgentProvider.CLAUDE,
                sessionId = sessionId,
                state = AgentState.IDLE,
                message = event.string("message") ?: event.string("notification"),
                lastEventAt = nowMs,
            )
            "agent_needs_input" -> AgentStatusSnapshot(
                projectId = projectId,
                provider = AgentProvider.CLAUDE,
                sessionId = sessionId,
                state = AgentState.RUNNING,
                activity = AgentActivity.THINKING,
                message = event.string("message") ?: event.string("notification"),
                lastEventAt = nowMs,
            )
            else -> null
        }
        "Stop" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.IDLE,
            lastEventAt = nowMs,
        )
        "StopFailure" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.ERROR,
            error = event.string("error") ?: event.string("message") ?: "Claude execution failed",
            lastEventAt = nowMs,
        )
        "SessionEnd" -> AgentStatusSnapshot(
            projectId = projectId,
            provider = AgentProvider.CLAUDE,
            sessionId = sessionId,
            state = AgentState.IDLE,
            message = "Claude session ended",
            lastEventAt = nowMs,
        )
        else -> null
    }
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.content?.trim()?.ifBlank { null }

private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.content?.trim()?.toBooleanStrictOrNull()
