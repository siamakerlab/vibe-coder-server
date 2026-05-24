package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val log = KotlinLogging.logger {}

/**
 * v0.16.0 — Claude conversation turn 영구 적재 facade.
 *
 * ClaudeSessionManager 가 user prompt 를 보낼 때, ClaudeStreamParser 가 stdout
 * line 을 ClaudeEvent 로 파싱할 때 본 service 를 호출. 실패는 fire-and-forget
 * — 영구 적재 실패가 콘솔 streaming 을 막아선 안 됨.
 *
 * 적재 row 종류:
 *  - role="user" — sendPrompt 의 text
 *  - role="assistant" — AssistantMessage (전체 turn 의 final text. partial 은 적재 안 함)
 *  - role="tool_use" — ToolUse (input JSON)
 *  - role="tool_result" — ToolResult (output JSON)
 *  - role="system" — SessionStarted, Done, system notice
 *  - role="error" — ErrorEvent
 *  - role="unknown" — Unknown (raw 만 보존)
 */
class ConversationHistoryService(
    private val repo: ConversationTurnRepository,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log.warn(e) { "conversation persist failed: ${e.message}" }
        }
    }

    fun userPrompt(projectId: String, sessionId: String?, text: String, agentName: String? = null) = safe {
        repo.insert(
            projectId = projectId,
            sessionId = sessionId,
            role = "user",
            content = text,
            agentName = agentName,
        )
    }

    /**
     * ClaudeEvent (system/init / assistant / tool_use / tool_result / done / error / unknown)
     * 적재. partial assistant chunks 는 skip — 전체 turn 의 final assistant message 만
     * 한 row (스트리밍 중간 token 누적은 LogHub 으로만 흘림).
     */
    fun event(projectId: String, sessionId: String?, event: ClaudeEvent, agentName: String? = null) = safe {
        when (event) {
            is ClaudeEvent.SessionStarted -> repo.insert(
                projectId = projectId,
                sessionId = event.sessionId,
                role = "system",
                content = """{"kind":"session_started","model":${jsonStr(event.model)},"cwd":${jsonStr(event.cwd)}}""",
                agentName = agentName,
            )
            is ClaudeEvent.AssistantMessage -> {
                // partial chunks 는 적재 안 함 — token-by-token 누적이 row 수 폭발 유발.
                if (event.isPartial) return@safe
                repo.insert(
                    projectId = projectId,
                    sessionId = sessionId,
                    role = "assistant",
                    content = event.text,
                    agentName = agentName,
                )
            }
            is ClaudeEvent.ToolUse -> repo.insert(
                projectId = projectId,
                sessionId = sessionId,
                role = "tool_use",
                content = jsonString(event.input),
                toolName = event.toolName,
                toolUseId = event.toolUseId,
                agentName = agentName,
            )
            is ClaudeEvent.ToolResult -> repo.insert(
                projectId = projectId,
                sessionId = sessionId,
                role = if (event.isError) "tool_result_error" else "tool_result",
                content = jsonString(event.output),
                toolUseId = event.toolUseId,
                agentName = agentName,
            )
            is ClaudeEvent.Done -> repo.insert(
                projectId = projectId,
                sessionId = sessionId,
                role = "system",
                content = """{"kind":"done","reason":${jsonStr(event.reason)}}""",
                agentName = agentName,
            )
            is ClaudeEvent.ErrorEvent -> repo.insert(
                projectId = projectId,
                sessionId = sessionId,
                role = "error",
                content = """{"code":${jsonStr(event.code)},"message":${jsonStr(event.message)}}""",
                agentName = agentName,
            )
            is ClaudeEvent.Unknown -> repo.insert(
                projectId = projectId,
                sessionId = sessionId,
                role = "unknown",
                content = jsonString(event.raw),
                raw = jsonString(event.raw),
                agentName = agentName,
            )
        }
    }

    /** Server-emitted system notice (cancel_noop, turn_cancelled, idle_terminated, etc.). */
    fun systemNotice(projectId: String, sessionId: String?, code: String, message: String, agentName: String? = null) = safe {
        repo.insert(
            projectId = projectId,
            sessionId = sessionId,
            role = "system",
            content = """{"kind":"system","code":${jsonStr(code)},"message":${jsonStr(message)}}""",
            agentName = agentName,
        )
    }

    private fun jsonStr(s: String?): String =
        if (s == null) "null" else Json.encodeToString(String.serializer(), s)

    private fun jsonString(el: JsonElement): String = el.toString()
}
