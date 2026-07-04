package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.shared.dto.PromptImageDto

data class AgentContextSnapshot(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreation: Long = 0,
    val limit: Long = 0,
) {
    val used: Long get() = input + cacheRead + cacheCreation
}

interface AgentSessionManager {
    val provider: AgentProvider

    /**
     * v1.146.0 — turn 정상 완료 리스너 (provider 무관). [com.siamakerlab.vibecoder.server.automation.PromptAutomationManager]
     * 가 등록해 "turn 완료마다 다음 프롬프트"를 구현하고, [com.siamakerlab.vibecoder.server.notify.NotificationService]
     * 가 알림을 합성해 넣는다. reason 은 provider 고유의 turn 종료 사유.
     *
     * 구현체는 [fireTurnDone] helper 로 안전하게(예외 흡수 + 비동기 launch) 호출해야 한다.
     */
    var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)?

    /**
     * v1.146.0 — turn 비정상 중단(cancel / new session / crash) 리스너.
     * 진행 중인 자동화를 멈추기 위함. reason = "cancelled" | "new_session" | "crashed" | "error".
     */
    var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)?

    suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto> = emptyList())

    suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto> = emptyList()) {
        cancelTurn(projectId)
        sendPrompt(projectId, text, images)
    }

    suspend fun startNew(projectId: String)

    suspend fun cancelTurn(projectId: String)

    fun isAlive(projectId: String): Boolean

    fun isBusy(projectId: String): Boolean

    fun currentSessionId(projectId: String): String?

    fun contextSnapshot(projectId: String): AgentContextSnapshot = AgentContextSnapshot()

    fun readProjectModel(projectId: String): String? = null

    fun effectiveModel(projectId: String): String? = readProjectModel(projectId)

    suspend fun setProjectModelAndRestart(projectId: String, model: String?) {}
}
