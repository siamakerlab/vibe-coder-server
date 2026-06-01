package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.JsonElement

/**
 * In-memory representation of one stream-json line emitted by `claude --output-format stream-json`.
 *
 * This is the server-internal model — translation to [com.siamakerlab.vibecoder.shared.ws.WsFrame]
 * sub-types happens in [ConsoleHub] when frames are published to subscribers.
 *
 * Keep this sealed hierarchy independent of the wire format so the CLI format can drift
 * without affecting the wire — [ClaudeStreamParser] absorbs the drift via [Unknown].
 */
sealed class ClaudeEvent {

    data class SessionStarted(
        val sessionId: String,
        val model: String?,
        val cwd: String?,
    ) : ClaudeEvent()

    data class AssistantMessage(
        val text: String,
        val isPartial: Boolean,
    ) : ClaudeEvent()

    data class ToolUse(
        val toolName: String,
        val input: JsonElement,
        val toolUseId: String,
    ) : ClaudeEvent()

    data class ToolResult(
        val toolUseId: String,
        val output: JsonElement,
        val isError: Boolean,
    ) : ClaudeEvent()

    data class ErrorEvent(
        val code: String,
        val message: String,
    ) : ClaudeEvent()

    data class Done(
        val reason: String,
    ) : ClaudeEvent()

    /**
     * v0.63.0 — Phase 42 token usage report. Emitted whenever the CLI publishes a
     * `usage` block (typically inside the final `result` frame). All fields nullable
     * because Anthropic ships them inconsistently across model versions / channels.
     *
     * `cacheReadInputTokens` + `cacheCreationInputTokens` together convey prompt-cache
     * efficiency: high `cacheRead` vs total `input` means the model is hitting cached
     * prompt prefixes (much cheaper).
     */
    data class UsageReport(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cacheReadInputTokens: Long?,
        val cacheCreationInputTokens: Long?,
    ) : ClaudeEvent() {
        /** Sum of all input categories the server saw. */
        val totalInputTokens: Long?
            get() = listOfNotNull(inputTokens, cacheReadInputTokens, cacheCreationInputTokens)
                .takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * v1.83.0 — CLI 가 보낸 정보성 상태 알림(현재는 `rate_limit_event`). turn 을 끝내지
     * 않으며 busy 상태도 바꾸지 않는다. 콘솔에 시스템 메시지로만 노출 — 사용자가 "멈춤/
     * thinking 후 중단" 으로 오해하던 rate limit 대기·재시도를 가시화한다.
     */
    data class SystemNote(
        val code: String,
        val message: String,
    ) : ClaudeEvent()

    /** CLI emitted a known top-level type we don't model — passed through. */
    data class Unknown(val raw: JsonElement) : ClaudeEvent()
}
