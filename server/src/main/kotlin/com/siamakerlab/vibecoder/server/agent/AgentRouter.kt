package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.shared.dto.PromptImageDto

class AgentRouter(
    private val store: ProjectAgentPreferenceStore,
    managers: List<AgentSessionManager>,
) {
    private val byProvider = managers.associateBy { it.provider }

    fun providerFor(projectId: String): AgentProvider = store.get(projectId)

    fun setProvider(projectId: String, provider: AgentProvider) {
        require(byProvider.containsKey(provider)) { "agent provider not registered: ${provider.id}" }
        store.set(projectId, provider)
    }

    fun availableProviders(): List<AgentProvider> = AgentProvider.entries.filter { byProvider.containsKey(it) }

    suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto> = emptyList()) =
        manager(projectId).sendPrompt(projectId, text, images)

    suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto> = emptyList()) =
        manager(projectId).interruptAndSend(projectId, text, images)

    suspend fun startNew(projectId: String) = manager(projectId).startNew(projectId)

    /** v1.175.0 — 수동 "세션 종료": 현재 provider 세션을 session-id 보존 상태로 종료. */
    suspend fun closeSession(projectId: String) = manager(projectId).closeSession(projectId)

    suspend fun cancelTurn(projectId: String) = manager(projectId).cancelTurn(projectId)

    fun isAlive(projectId: String): Boolean = manager(projectId).isAlive(projectId)

    fun isBusy(projectId: String): Boolean = manager(projectId).isBusy(projectId)

    fun currentSessionId(projectId: String): String? = manager(projectId).currentSessionId(projectId)

    fun contextSnapshot(projectId: String): AgentContextSnapshot = manager(projectId).contextSnapshot(projectId)

    fun readProjectModel(projectId: String): String? = manager(projectId).readProjectModel(projectId)

    fun effectiveModel(projectId: String): String? = manager(projectId).effectiveModel(projectId)

    suspend fun setProjectModelAndRestart(projectId: String, model: String?) =
        manager(projectId).setProjectModelAndRestart(projectId, model)

    fun managerFor(provider: AgentProvider): AgentSessionManager? = byProvider[provider]

    /**
     * v1.146.0 — turn 관찰 listener 를 등록된 **모든** manager 에 일괄 주입한다.
     * [com.siamakerlab.vibecoder.server.ServerMain] 이 자동화(PromptAutomationManager) +
     * 알림(NotificationService) 합성 리스너를 provider 무관하게 깔기 위해 사용.
     * 이전에는 ClaudeSessionManager 에만 setter 주입했으므로 Codex/OpenCode turn 완료가
     * 자동화/알림에 닿지 않았다.
     */
    fun installTurnListeners(
        done: suspend (projectId: String, reason: String) -> Unit,
        interrupt: suspend (projectId: String, reason: String) -> Unit,
    ) {
        byProvider.values.forEach { mgr ->
            mgr.turnDoneListener = done
            mgr.turnInterruptListener = interrupt
        }
    }

    /** v1.146.0 — 등록된 모든 manager (테스트/점검용). */
    fun allManagers(): Collection<AgentSessionManager> = byProvider.values

    private fun manager(projectId: String): AgentSessionManager =
        byProvider[providerFor(projectId)] ?: byProvider.getValue(AgentProvider.CLAUDE)
}
