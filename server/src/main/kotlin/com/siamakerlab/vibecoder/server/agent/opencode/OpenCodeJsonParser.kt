package com.siamakerlab.vibecoder.server.agent.opencode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v1.150.0 — opencode CLI `run --format json` NDJSON 파서. [OpenCodeEvent] sealed 로 변환.
 * [docs/opencode-cli-reference.md] §2 이벤트 형식 참고.
 *
 * 형식: 한 줄당 `{"type": "...", "sessionID": "ses_...", "part": {"type": "...", ...}}`.
 * 인식 못한 type 은 [OpenCodeEvent.Unknown] (콘솔에 원시 JSON 표시 — reasoning 등 변동 형식 디버깅용).
 */
class OpenCodeJsonParser(
    private val json: Json = DEFAULT_JSON,
) {
    fun parseLine(line: String): OpenCodeEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return null
        val sessionId = root.string("sessionID") ?: ""
        val type = root.string("type") ?: return OpenCodeEvent.Unknown(root, sessionId)
        val part = root["part"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return when (type) {
            "step_start" -> OpenCodeEvent.StepStart(sessionId)
            "text" -> OpenCodeEvent.Text(
                sessionId,
                part?.string("text") ?: "",
            )
            "tool_use" -> parseToolUse(part, sessionId)
            "step_finish" -> OpenCodeEvent.StepFinish(
                sessionId,
                part?.string("reason"),
                part?.let { parseTokens(it) },
            )
            else -> OpenCodeEvent.Unknown(root, sessionId)
        }
    }

    private fun parseToolUse(part: JsonObject?, sessionId: String): OpenCodeEvent {
        val tool = part?.string("tool") ?: "tool"
        val callId = part?.string("callID") ?: "opencode_tool_${System.nanoTime()}"
        val state = part?.get("state")?.let { runCatching { it.jsonObject }.getOrNull() }
        val input = state?.get("input")
        val output = state?.get("output")
        val status = state?.string("status") ?: "completed"
        return if (status == "completed") {
            OpenCodeEvent.ToolCompleted(sessionId, callId, tool, input, output)
        } else {
            OpenCodeEvent.ToolStarted(sessionId, callId, tool, input)
        }
    }

    private fun parseTokens(part: JsonObject): OpenCodeTokens? {
        val tokens = part["tokens"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val cache = tokens["cache"]?.let { runCatching { it.jsonObject }.getOrNull() }
        return OpenCodeTokens(
            total = tokens.long("total"),
            input = tokens.long("input"),
            output = tokens.long("output"),
            reasoning = tokens.long("reasoning"),
            cacheWrite = cache?.long("write") ?: 0L,
            cacheRead = cache?.long("read") ?: 0L,
        )
    }

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.let { it as? JsonPrimitive }?.contentOrNull

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
