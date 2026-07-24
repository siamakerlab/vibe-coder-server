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

    /**
     * v1.146.0 — 래퍼 경유 listener 주입 시 delegate([ClaudeSessionManager])에 전달.
     * forward 하지 않으면 AgentRouter.installTurnListeners 가 Claude manager 의 fire
     * 경로에 닿지 않아 자동화/알림이 무력화된다.
     */
    override var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)?
        get() = delegate.turnDoneListener
        set(value) { delegate.turnDoneListener = value }

    override var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)?
        get() = delegate.turnInterruptListener
        set(value) { delegate.turnInterruptListener = value }

    override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) =
        delegate.sendPrompt(projectId, text, images = images)

    override suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto>) =
        delegate.interruptAndSend(projectId, text, images = images)

    override suspend fun startNew(projectId: String) = delegate.startNew(projectId)

    override suspend fun closeSession(projectId: String) = delegate.closeSession(projectId)

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
