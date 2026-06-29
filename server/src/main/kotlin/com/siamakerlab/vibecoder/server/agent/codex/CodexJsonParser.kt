package com.siamakerlab.vibecoder.server.agent.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CodexJsonParser(
    private val json: Json = DEFAULT_JSON,
) {
    fun parseLine(line: String): CodexEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return CodexEvent.Error(trimmed.take(500))
        return when (val type = root.string("type")) {
            "thread.started" -> root.string("thread_id")?.let { CodexEvent.ThreadStarted(it) } ?: CodexEvent.Unknown(root)
            "turn.started" -> CodexEvent.TurnStarted
            "turn.completed" -> CodexEvent.TurnCompleted(usage = root["usage"] as? JsonObject)
            "turn.failed" -> CodexEvent.TurnFailed(root.string("message") ?: "Codex turn failed")
            "error" -> CodexEvent.Error(root.string("message") ?: root.toString())
            "item.started" -> parseItem(root["item"], started = true) ?: CodexEvent.Unknown(root)
            "item.completed" -> parseItem(root["item"], started = false) ?: CodexEvent.Unknown(root)
            else -> if (type?.startsWith("item.") == true) parseItem(root["item"], started = false) ?: CodexEvent.Unknown(root)
            else CodexEvent.Unknown(root)
        }
    }

    private fun parseItem(itemEl: JsonElement?, started: Boolean): CodexEvent? {
        val item = runCatching { itemEl?.jsonObject }.getOrNull() ?: return null
        val itemType = item.string("type")
        val id = item.string("id")
        return when (itemType) {
            "agent_message" -> {
                val text = item.string("text") ?: item.string("message") ?: return null
                CodexEvent.AgentMessage(text)
            }
            "command_execution" -> {
                val cmd = item.string("command") ?: item.string("cmd") ?: item["command"]?.toString() ?: "command"
                if (started) CodexEvent.CommandStarted(id, cmd)
                else CodexEvent.CommandCompleted(id, cmd, item["aggregated_output"] ?: item["output"] ?: item["result"])
            }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
