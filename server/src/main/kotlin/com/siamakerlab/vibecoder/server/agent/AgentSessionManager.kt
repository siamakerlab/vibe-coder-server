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
