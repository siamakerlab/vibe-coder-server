package com.siamakerlab.vibecoder.server.agent.opencode

import kotlinx.serialization.json.JsonElement

/**
 * v1.150.0 — opencode CLI `run --format json` 이벤트 (NDJSON 한 줄).
 * [docs/opencode-cli-reference.md] §2 참고. opencode 1.17.13 기준.
 *
 * 하나의 turn 은 여러 step 으로 구성될 수 있다 (도구 호출 후 다음 step). 전체 turn 완료는
 * [StepFinish] 의 reason 이 `"stop"` 인 경우. `"tool-calls"` 면 아직 진행 중.
 */
sealed interface OpenCodeEvent {
    /** 이벤트가 속한 opencode 세션 id (`ses_...`). 첫 이벤트에서 thread id 로 저장. */
    val sessionId: String

    /** step(추론 한 단계) 시작. Codex 의 TurnStarted 와 유사하나 turn 내 다중 step 가능. */
    data class StepStart(override val sessionId: String) : OpenCodeEvent

    /** assistant 메시지 텍스트. */
    data class Text(override val sessionId: String, val text: String) : OpenCodeEvent

    /** 도구 호출 시작 (state.status == "pending"). */
    data class ToolStarted(
        override val sessionId: String,
        val callId: String,
        val tool: String,
        val input: JsonElement?,
    ) : OpenCodeEvent

    /** 도구 호출 완료 (state.status == "completed"). */
    data class ToolCompleted(
        override val sessionId: String,
        val callId: String,
        val tool: String,
        val input: JsonElement?,
        val output: JsonElement?,
    ) : OpenCodeEvent

    /** step 종료 + usage. reason == "stop" 이면 turn 전체 완료. */
    data class StepFinish(
        override val sessionId: String,
        val reason: String?,
        val tokens: OpenCodeTokens?,
    ) : OpenCodeEvent

    /** 그 외 인식 못한 이벤트. 콘솔에 원시 JSON 표시(디버깅). */
    data class Unknown(val raw: JsonElement, override val sessionId: String) : OpenCodeEvent
}

/** opencode step_finish 의 part.tokens 구조. */
data class OpenCodeTokens(
    val total: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheWrite: Long = 0,
    val cacheRead: Long = 0,
)

/**
 * v1.154.0 — OpenCode rate-limit / usage-limit 분류 순수 함수. [OpenCodeSessionManager] 가
 * 프로세스 crash(stderr) 또는 Unknown 이벤트(raw) 에서 rate-limit 신호를 감지해 자동 재개 /
 * 사용량 한도 처리를 분기하는 데 사용. opencode CLI 의 정확한 rate-limit 이벤트 형식이
 * 변동 가능해 broad 한 메시지 매칭(Codex [isCodexRateLimitMessage] 패턴 차용).
 */

/** 일시 rate-limit (429 / too many requests / overloaded) — 백오프 후 자동 재개 의미 있음. */
internal fun isOpencodeRateLimitMessage(message: String): Boolean {
    val m = message.lowercase()
    return m.contains("temporarily limiting") ||
        m.contains("rate limit") ||
        m.contains("rate-limit") ||
        m.contains("rate_limit") ||
        m.contains("429") ||
        m.contains("too many requests") ||
        m.contains("overloaded")
}

/**
 * 사용량/요금 한도 (5h / weekly / plan quota) — 재시도해도 소용없음. STOPPED + interrupt.
 * 일시 rate-limit 신호가 섞이면 usage-limit 이 아닌 것으로 판정.
 */
internal fun isOpencodeUsageLimitMessage(message: String): Boolean {
    val m = message.lowercase()
    if (isOpencodeRateLimitMessage(message)) return false
    return m.contains("usage limit") ||
        m.contains("spend limit") ||
        m.contains("quota") ||
        m.contains("plan limit") ||
        m.contains("reached your limit") ||
        m.contains("billing")
}
