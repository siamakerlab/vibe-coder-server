package com.siamakerlab.vibecoder.server.agent.claude

import com.siamakerlab.vibecoder.server.agent.AgentContextSnapshot
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentSessionManager
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.shared.dto.PromptImageDto

class ClaudeAgentSessionManager(
    private val delegate: ClaudeSessionManager,
) : AgentSessionManager {
    override val provider: AgentProvider = AgentProvider.CLAUDE

    override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) =
        delegate.sendPrompt(projectId, text, images = images)

    override suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto>) =
        delegate.interruptAndSend(projectId, text, images = images)

    override suspend fun startNew(projectId: String) = delegate.startNew(projectId)

    override suspend fun cancelTurn(projectId: String) = delegate.cancelTurn(projectId)

    override fun isAlive(projectId: String): Boolean = delegate.isAlive(projectId)

    override fun isBusy(projectId: String): Boolean = delegate.isBusy(projectId)

    override fun currentSessionId(projectId: String): String? = delegate.currentSessionId(projectId)

    override fun contextSnapshot(projectId: String): AgentContextSnapshot =
        delegate.contextSnapshot(projectId).let {
            AgentContextSnapshot(
                input = it.input,
                cacheRead = it.cacheRead,
                cacheCreation = it.cacheCreation,
                limit = it.limit,
            )
        }

    override fun readProjectModel(projectId: String): String? = delegate.readProjectModel(projectId)

    override fun effectiveModel(projectId: String): String? = delegate.effectiveModel(projectId)

    override suspend fun setProjectModelAndRestart(projectId: String, model: String?) =
        delegate.setProjectModelAndRestart(projectId, model)
}
