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

    /**
     * v1.84.0 — 백그라운드 작업(Bash run_in_background 등) lifecycle. CLI 가 system
     * 메시지 subtype `task_started` / `task_updated` / `task_notification` 로 노출한다
     * (Claude Code TUI 하단 Shell 카드의 데이터 소스). 콘솔에 "실행 중 → 완료" 카드로
     * 시각화해, claude 가 백그라운드 작업을 띄우고 turn 을 끝내도 진행 상황을 알 수 있게 한다.
     *
     * - kind="started": [taskId] 새 작업 시작. description/taskType 동반.
     * - kind="progress": 진행 갱신(주로 Task 서브에이전트). description 실시간 변경 +
     *   lastTool/toolUses 진행 메타(예: "Reading Theme.kt · Read · 14 tools").
     * - kind="updated": [status] 갱신(running/completed/failed 등). patch.status 추출.
     * - kind="notification": 완료/이벤트 통지(보조).
     */
    data class BackgroundTask(
        val kind: String,
        val taskId: String,
        val description: String? = null,
        val taskType: String? = null,
        val status: String? = null,
        val lastTool: String? = null,
        val toolUses: Int? = null,
    ) : ClaudeEvent()

    /** CLI emitted a known top-level type we don't model — passed through. */
    data class Unknown(val raw: JsonElement) : ClaudeEvent()
}
