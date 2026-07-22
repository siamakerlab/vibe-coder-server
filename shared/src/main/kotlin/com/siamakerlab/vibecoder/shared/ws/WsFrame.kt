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
     * v1.84.0 — 백그라운드 작업(Bash run_in_background 등) lifecycle 카드용. CLI 의
     * system 메시지(task_started/task_updated/task_notification)를 모델링. 콘솔이
     * "실행 중 → 완료" 카드를 그려, claude 가 백그라운드 작업을 띄우고 turn 을 끝내도
     * 진행 상황을 시각화한다(Claude Code TUI 하단 Shell 카드 동형).
     */
    @Serializable
    @SerialName("console_background_task")
    data class ConsoleBackgroundTask(
        /** "started" | "progress" | "updated" | "notification" */
        val kind: String,
        val taskId: String,
        val description: String? = null,
        val taskType: String? = null,
        /** task_updated 의 status (running/completed/failed 등). */
        val status: String? = null,
        /** v1.84.0 — task_progress 진행 메타(Task 서브에이전트). */
        val lastTool: String? = null,
        val toolUses: Int? = null,
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
        /**
         * v1.83.0 — 콘솔 페이지 상태 뱃지 정확성용 [com.siamakerlab.vibecoder.shared.dto.ProjectState]
         * 의 wire 값: "responding" | "ready" | "waiting" | "stopped" | "error".
         * null 이면 구버전 호환 — 클라가 busy 로 responding/ready 도출. rate-limit
         * 재시도 소진 등 비정상 종료 시 "stopped"(중단됨) 로 emit 해, busy=false 만으론
         * 구분 못 하던 "중단됨" 을 콘솔에서도 live 반영([ProjectBusyChanged.state] 와 동형).
         */
        val state: String? = null,
    ) : WsFrame()

    /**
     * v1.106.1 — 컨텍스트 윈도우 점유율 미터(상시 표시)용. turn 종료 usage 에서 추출한
     * 토큰 분해(input/cache_read/cache_creation)와 모델별 윈도우 한도. 클라이언트는
     * used = input+cacheRead+cacheCreation, free = limit-used 로 그래픽 바를 그린다.
     * Claude CLI 의 `/context` 와 유사한 점유/사용/남음 시각화(카테고리 분해는 stream-json
     * 미노출이라 input/cached 세그먼트로 표현).
     */
    @Serializable
    @SerialName("console_context_usage")
    data class ConsoleContextUsage(
        val inputTokens: Long,
        val cacheReadTokens: Long,
        val cacheCreationTokens: Long,
        val contextLimit: Long,
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
        /**
         * v1.60.0 — 상태칩(목록/switcher) 실시간 정확성용 명시 상태
         * ([com.siamakerlab.vibecoder.shared.dto.ProjectState] wire):
         * "responding" | "ready" | "waiting" | "stopped" | "error". null 이면 구버전 호환 —
         * 클라가 busy 로 responding/ready 도출. cancel/crash 시 "stopped" 로 emit 해
         * busy=false 만으론 구분 못 하던 "중지됨" 을 live 반영.
         */
        val state: String? = null,
    ) : WsFrame()

    /**
     * Normalized provider/session status. This is separate from terminal output and richer than
     * ProjectBusyChanged so every project/provider tab can render consistent state without parsing
     * ANSI/TUI text. Additive frame; older clients ignore it.
     */
    @Serializable
    @SerialName("agent_status_changed")
    data class AgentStatusChanged(
        val projectId: String,
        val provider: String,
        val sessionId: String? = null,
        val state: String,
        val activity: String? = null,
        val currentTool: String? = null,
        val message: String? = null,
        val error: String? = null,
        val pid: Long? = null,
        val lastEventAt: Long,
        val lastOutputAt: Long,
        val turnStartedAt: Long? = null,
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

    // endregion

    // region Terminal (v1.6.0 — workspace bash PTY)

    /**
     * v1.6.0 — Server → client: PTY 의 raw output (ANSI escape 포함).
     * xterm.js 가 그대로 처리. seq 없음 — terminal 은 history replay 불필요
     * (재진입 시 server-side history 미보관).
     */
    @Serializable
    @SerialName("terminal_output")
    data class TerminalOutput(val data: String) : WsFrame()

    /** v1.6.0 — Client → server: 사용자 키 입력 (raw bytes 그대로 PTY stdin). */
    @Serializable
    @SerialName("terminal_input")
    data class TerminalInput(
        val data: String,
        /**
         * Optional user-visible prompt text to persist in console history. Raw terminal bytes are
         * not a reliable prompt source because they also contain mouse, scroll, focus, paste, and
         * terminal-control escape sequences.
         */
        val recordPrompt: String? = null,
    ) : WsFrame()

    /** v1.6.0 — Client → server: terminal resize (rows × cols). xterm.js 의 resize event. */
    @Serializable
    @SerialName("terminal_resize")
    data class TerminalResize(val cols: Int, val rows: Int) : WsFrame()

    /** v1.6.0 — Server → client: PTY 종료. exitCode 와 함께. */
    @Serializable
    @SerialName("terminal_exit")
    data class TerminalExit(val exitCode: Int) : WsFrame()

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

    /**
     * v1.59.0 — 프롬프트 자동화(서버 백그라운드 autopilot) 진행 상태.
     *
     * 자동화 run 이 시작/매 turn 완료마다 다음 프롬프트 발사/종료될 때 console
     * topic 으로 emit. 웹/Android 콘솔은 본 프레임으로 "자동화 N/총 · status"
     * 뱃지를 갱신한다. `active=false` 면 run 종료(status = done|stopped|failed).
     */
    @Serializable
    @SerialName("automation_progress")
    data class AutomationProgress(
        val projectId: String,
        val runId: String,
        val status: String,          // running | done | stopped | failed
        val mode: String,            // repeat | sequence
        val sent: Int,
        val total: Int,
        val active: Boolean,
        val lastPrompt: String? = null,
        val seq: Long,
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
