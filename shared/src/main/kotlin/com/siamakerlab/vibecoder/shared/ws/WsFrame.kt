package com.siamakerlab.vibecoder.shared.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * WebSocket frame format used on log streams.
 *
 * Two channel families currently use this hierarchy:
 *  - Task / Build log streams: original frames (`auth`, `log`, `done`, `error`, `ping`).
 *  - Console stream (`/ws/projects/{id}/console/logs`): `console_*` sub-frames carry
 *    Claude stream-json events, server-originated notices, and replay markers.
 *
 * The very first frame from the client MUST be [Auth]; otherwise the server
 * closes the connection within 5 seconds.
 */
@Serializable
sealed class WsFrame {

    @Serializable
    @SerialName("auth")
    data class Auth(val token: String) : WsFrame()

    @Serializable
    @SerialName("log")
    data class Log(
        val taskId: String,
        val level: String, // "INFO" | "WARN" | "ERROR" | "STDOUT" | "STDERR"
        val message: String,
        val ts: String,
    ) : WsFrame()

    @Serializable
    @SerialName("done")
    data class Done(
        val taskId: String,
        val status: String, // TaskStatus name
        val errorMessage: String? = null,
    ) : WsFrame()

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
    ) : WsFrame()

    @Serializable
    @SerialName("ping")
    data object Ping : WsFrame()

    // region Console (project-claude-console feature)

    /**
     * Sent right after the `claude` child process emits its `system/init` line.
     * `seq` is the monotonically increasing sequence number from ConsoleHub —
     * the client persists this and reconnects with ?since=<lastSeenSeq>.
     */
    @Serializable
    @SerialName("console_session_started")
    data class ConsoleSessionStarted(
        val sessionId: String,
        val model: String? = null,
        val cwd: String? = null,
        val seq: Long,
    ) : WsFrame()

    /** A chunk (or full piece) of assistant text. `isPartial` differentiates streaming chunks. */
    @Serializable
    @SerialName("console_assistant")
    data class ConsoleAssistant(
        val text: String,
        val isPartial: Boolean = false,
        val seq: Long,
    ) : WsFrame()

    /** A tool call requested by the model — raw `input` preserved as JsonElement. */
    @Serializable
    @SerialName("console_tool_use")
    data class ConsoleToolUse(
        val toolName: String,
        val input: JsonElement,
        val toolUseId: String,
        val seq: Long,
    ) : WsFrame()

    /** Output of a tool call. `isError` may be set when the tool reports failure. */
    @Serializable
    @SerialName("console_tool_result")
    data class ConsoleToolResult(
        val toolUseId: String,
        val output: JsonElement,
        val isError: Boolean = false,
        val seq: Long,
    ) : WsFrame()

    /** Claude-emitted error (within stream-json, not a process crash). */
    @Serializable
    @SerialName("console_error")
    data class ConsoleError(
        val code: String,
        val message: String,
        val seq: Long,
    ) : WsFrame()

    /** Turn complete. Client may show "ready for next prompt" affordance. */
    @Serializable
    @SerialName("console_done")
    data class ConsoleDone(
        val reason: String = "end_turn",
        val seq: Long,
    ) : WsFrame()

    /** Stream-json event whose type the server doesn't know — passed through verbatim. */
    @Serializable
    @SerialName("console_unknown")
    data class ConsoleUnknown(
        val raw: JsonElement,
        val seq: Long,
    ) : WsFrame()

    /**
     * Server-originated notice (process_crashed, resume_failed_starting_new,
     * idle_terminated, replay_partial, claude_unavailable, etc.).
     */
    @Serializable
    @SerialName("console_system")
    data class ConsoleSystem(
        val code: String,
        val message: String,
        val seq: Long,
    ) : WsFrame()

    /**
     * v0.98.0 — Busy/idle 전환 알림. busy=true 면 사용자 prompt 처리 중
     * (Claude 가 응답을 stream 중), busy=false 면 다음 prompt 대기.
     *
     * 서버가 [ClaudeSessionManager] 에서 sendPrompt 직후 / Done/cancel/crash/idle
     * 시점에 emit. Web 클라이언트는 기존 inFlight 로직과 병행 동작 (둘 다 같은 결과).
     * Android 클라이언트는 본 frame 으로 응답중/대기중 배지 동기.
     */
    @Serializable
    @SerialName("console_busy_state")
    data class ConsoleBusyState(
        val busy: Boolean,
        val seq: Long,
    ) : WsFrame()

    /**
     * v1.3.0 — 프로젝트별 busy 상태 변화를 cross-project 토픽 (`/ws/projects`)
     * 으로 broadcast. workspaces 목록 / 대시보드 등이 실시간 busy 뱃지 동기.
     *
     * 서버 [ClaudeSessionManager.setBusy] 가 ConsoleBusyState (per-project topic)
     * 와 동시에 본 frame 도 `__projects__` topic 으로 emit. Android 의 workspaces
     * list 가 `/ws/projects` 에 구독해서 list state 의 해당 projectId.busy 만 patch.
     */
    @Serializable
    @SerialName("project_busy_changed")
    data class ProjectBusyChanged(
        val projectId: String,
        val busy: Boolean,
        val seq: Long,
    ) : WsFrame()

    /** Sent right before replay frames so the client can show a "loading history" affordance. */
    @Serializable
    @SerialName("console_replay_begin")
    data class ConsoleReplayBegin(
        val fromSeq: Long,
        val toSeq: Long,
    ) : WsFrame()

    /** Sent after the last replay frame. Subsequent frames are live. */
    @Serializable
    @SerialName("console_replay_end")
    data object ConsoleReplayEnd : WsFrame()

    /** Client → server: send a user prompt over the same WS connection. */
    @Serializable
    @SerialName("user_prompt")
    data class UserPrompt(val text: String) : WsFrame()

    /** Client → server: invoke a chip / action by id. */
    @Serializable
    @SerialName("action_invoke")
    data class ActionInvoke(
        val actionId: String,
        val params: JsonElement? = null,
    ) : WsFrame()

    // endregion
}

object WsLevel {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
    const val STDOUT = "STDOUT"
    const val STDERR = "STDERR"
}
