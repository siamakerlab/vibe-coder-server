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
