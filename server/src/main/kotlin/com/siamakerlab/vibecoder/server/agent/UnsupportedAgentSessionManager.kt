package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.PromptImageDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import com.siamakerlab.vibecoder.shared.ws.WsFrame

open class UnsupportedAgentSessionManager(
    override val provider: AgentProvider,
    private val hub: LogHub,
) : AgentSessionManager {
    // v1.146.0 — hook 유지 (no-op). installTurnListeners 가 모든 manager 에 주입해도
    // 미구현 provider 에서는 fire 경로가 없어 안전하게 무시된다.
    @Volatile
    override var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    @Volatile
    override var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) {
        emit(projectId, "${provider.displayName} provider is registered but not implemented yet.")
        throw UnsupportedOperationException("${provider.id} provider is not implemented yet")
    }

    override suspend fun startNew(projectId: String) {
        emit(projectId, "${provider.displayName} has no active session to reset.")
    }

    override suspend fun cancelTurn(projectId: String) {
        emit(projectId, "${provider.displayName} has no active turn to cancel.")
    }

    override fun isAlive(projectId: String): Boolean = false
    override fun isBusy(projectId: String): Boolean = false
    override fun currentSessionId(projectId: String): String? = null

    private suspend fun emit(projectId: String, message: String) {
        hub.emitConsole(LogHub.consoleTopic(projectId, provider.id)) { seq ->
            WsFrame.ConsoleSystem(code = "${provider.id}_unavailable", message = message, seq = seq)
        }
        hub.emitConsole(LogHub.consoleTopic(projectId, provider.id)) { seq ->
            WsFrame.ConsoleBusyState(busy = false, seq = seq, state = ProjectState.STOPPED.wire)
        }
    }
}
