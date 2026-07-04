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
            // v1.149.0 — item.* 이벤트는 [parseItem] 이 아는 itemType(agent_message/command_execution)
            // 만 이벤트로 변환. reasoning/thinking 등 모르는 itemType 은 null → 조용히 스킵
            // (이전엔 Unknown 폴백이 원시 JSON 을 콘솔에 뿌려 reasoning 노이즈가 됐다).
            // reasoning 형식이 확정되면 별도 CodexEvent.Reasoning + 전용 렌더링 추가.
            "item.started" -> parseItem(root["item"], started = true)
            "item.completed" -> parseItem(root["item"], started = false)
            else -> if (type?.startsWith("item.") == true) parseItem(root["item"], started = false)
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
            // reasoning/thinking 및 그 외 모르는 itemType → null (스킵). 형식 확정 시 별도 처리.
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
