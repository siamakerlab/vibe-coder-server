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

    private fun manager(projectId: String): AgentSessionManager =
        byProvider[providerFor(projectId)] ?: byProvider.getValue(AgentProvider.CLAUDE)
}
