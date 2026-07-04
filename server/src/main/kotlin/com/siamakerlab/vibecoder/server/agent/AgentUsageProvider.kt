package com.siamakerlab.vibecoder.server.agent

/**
 * v1.146.0 — provider 무관 usage 조회 추상화 (Phase 0-C).
 *
 * [com.siamakerlab.vibecoder.server.automation.ScheduledPromptManager] 가
 * SESSION_RESET / WEEKLY_RESET 트리거를 판정할 때 provider 의 usage % 를 읽기 위해 사용.
 * 이전에는 [com.siamakerlab.vibecoder.server.claude.ClaudeStatusService] 구체 타입에
 * 하드 바인딩돼 있어 Codex/OpenCode provider 에서 한도-해제 예약이 동작하지 않았다.
 *
 * provider 가 usage % 를 관측할 수 없으면 null 을 반환해 보수적으로 보류(트리거 미발사)한다.
 * Codex/OpenCode 구현체는 Phase 1/2 에서 추가될 수 있다 — 이 인터페이스만 보면 된다.
 */
interface AgentUsageProvider {
    /**
     * projectId 의 usage 스냅샷. session/weekly usage % (0~100).
     * 둘 다 null 이면 SESSION_RESET/WEEKLY_RESET 트리거는 발사하지 않는다.
     */
    fun usageSnapshot(projectId: String): AgentUsageSnapshot?
}

data class AgentUsageSnapshot(
    val sessionUsagePercent: Int? = null,
    val weeklyUsagePercent: Int? = null,
)
