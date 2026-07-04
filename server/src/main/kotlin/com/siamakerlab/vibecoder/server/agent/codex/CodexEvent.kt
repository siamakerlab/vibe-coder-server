package com.siamakerlab.vibecoder.server.agent.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface CodexEvent {
    data class ThreadStarted(val threadId: String) : CodexEvent
    data object TurnStarted : CodexEvent
    data class AgentMessage(val text: String) : CodexEvent
    data class CommandStarted(val id: String?, val command: String) : CodexEvent
    data class CommandCompleted(val id: String?, val command: String?, val output: JsonElement?) : CodexEvent
    data class TurnCompleted(val reason: String = "completed", val usage: JsonObject? = null) : CodexEvent
    data class TurnFailed(val message: String) : CodexEvent
    data class Error(val message: String) : CodexEvent
    data class Unknown(val raw: JsonElement) : CodexEvent
}

/**
 * v1.148.0 — Codex turn 실패 메시지 분류 순수 함수 (단위 테스트 가능).
 * [CodexSessionManager] 가 [CodexEvent.TurnFailed] 를 3가지 종료 경로로 분기하는 데 사용.
 *
 * Codex CLI(openai/codex) exec --json 모드는 turn 실패를 `turn.failed` 이벤트의 message
 * 텍스트로 전달한다 (Claude 처럼 구조화된 code 필드가 없음). 그래서 message 패턴 매칭이 필요.
 * 패턴은 Claude 쪽 [com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager.isRateLimitError]
 * 와 [com.siamakerlab.vibecoder.server.claude.ClaudeStreamParser] usage_limit 정규화 로직을
 * 차용해 Codex CLI / OpenAI API 429 응답에 맞게 다듬었다.
 */

/**
 * 일시적 rate-limit (429 / too many requests / overloaded) — 백오프 후 자동 재개 의미 있음.
 * usage-limit(요금/할당) 와 구분: "(not your usage limit)" 부정 문구는 일시 rate-limit 신호.
 */
internal fun isCodexRateLimitMessage(message: String): Boolean {
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
 * 일시 rate-limit ("temporarily limiting" / "429" / "overloaded" / "(not your usage limit)")
 * 신호가 섞이면 usage-limit 이 아닌 것으로 판정해 rate-limit 자동 재개 경로로 보낸다.
 */
internal fun isCodexUsageLimitMessage(message: String): Boolean {
    val m = message.lowercase()
    if (isCodexRateLimitMessage(message)) return false
    return m.contains("usage limit") ||
        m.contains("spend limit") ||
        m.contains("quota") ||
        m.contains("plan limit") ||
        m.contains("reached your limit") ||
        m.contains("billing")
}
