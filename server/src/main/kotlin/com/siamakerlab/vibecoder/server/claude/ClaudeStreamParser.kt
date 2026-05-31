package com.siamakerlab.vibecoder.server.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger {}

/**
 * Maps one stream-json line → one [ClaudeEvent].
 *
 * Claude Code CLI's stream-json envelope (observed) wraps a top-level discriminator
 * `type` of:
 *   - `system`     subtype=`init`  → contains sessionId / model / cwd
 *   - `assistant`  with `message.content[]` of `text` / `tool_use` blocks
 *   - `user`       with `message.content[]` of `tool_result` blocks
 *   - `result`     subtype=`success`|`error` etc. → turn done
 *
 * Anything outside this set is wrapped in [ClaudeEvent.Unknown] so the client still
 * receives it and the CLI format can evolve without us redeploying.
 */
class ClaudeStreamParser(
    private val json: Json = DEFAULT_JSON,
) {

    /** Parse a single non-empty line. Returns a list because one `assistant` line can yield
     *  multiple events (text chunk + tool_use block on the same line). */
    fun parseLine(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }
            .getOrElse {
                log.debug { "stream-json parse failed: ${it.message}; line=${trimmed.take(200)}" }
                return listOf(ClaudeEvent.Unknown(JsonPrimitive(trimmed)))
            }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "system" -> {
                // v1.70.0 — thinking_tokens 는 추정 토큰 카운터로 UI/이력 가치가 없는 순수
                // 노이즈(운영 DB 5792행). emit/적재 모두 생략.
                if (obj["subtype"]?.jsonPrimitive?.contentOrNull == "thinking_tokens") emptyList()
                else parseSystem(obj)?.let { listOf(it) } ?: listOf(ClaudeEvent.Unknown(obj))
            }
            "assistant" -> parseAssistant(obj)
            "user" -> parseUserToolResult(obj)
            "result" -> parseResult(obj)
            else -> listOf(ClaudeEvent.Unknown(obj))
        }
    }

    private fun parseSystem(obj: JsonObject): ClaudeEvent? {
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull
        if (subtype != "init") return null
        val sessionId = obj["session_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val model = obj["model"]?.jsonPrimitive?.contentOrNull
        val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull
        return ClaudeEvent.SessionStarted(sessionId, model, cwd)
    }

    private fun parseAssistant(obj: JsonObject): List<ClaudeEvent> {
        val message = obj["message"]?.jsonObject ?: return listOf(ClaudeEvent.Unknown(obj))
        val content = message["content"] ?: return listOf(ClaudeEvent.Unknown(obj))
        val blocks = runCatching { content.jsonArray }.getOrNull() ?: return listOf(ClaudeEvent.Unknown(obj))

        val out = mutableListOf<ClaudeEvent>()
        // v0.63.0 — Phase 42 prompt cache usage 추적. assistant message 의 usage 객체에서
        // input/output/cache_read/cache_creation 토큰 추출. 누락된 model 버전도 있어
        // nullable. 비어 있으면 emit skip.
        message["usage"]?.let { parseUsage(it) }?.let { out += it }
        for (block in blocks) {
            val b = runCatching { block.jsonObject }.getOrNull() ?: continue
            when (b["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = b["text"]?.jsonPrimitive?.contentOrNull ?: continue
                    out += ClaudeEvent.AssistantMessage(text = text, isPartial = false)
                }
                "tool_use" -> {
                    val toolName = b["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val toolUseId = b["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val input: JsonElement = b["input"] ?: JsonObject(emptyMap())
                    out += ClaudeEvent.ToolUse(toolName, input, toolUseId)
                }
                "thinking" -> {
                    // Internal reasoning — wrap as Unknown for now (UI can render or hide).
                    out += ClaudeEvent.Unknown(b)
                }
                else -> out += ClaudeEvent.Unknown(b)
            }
        }
        return out.ifEmpty { listOf(ClaudeEvent.Unknown(obj)) }
    }

    private fun parseUserToolResult(obj: JsonObject): List<ClaudeEvent> {
        val message = obj["message"]?.jsonObject ?: return listOf(ClaudeEvent.Unknown(obj))
        val content = message["content"] ?: return listOf(ClaudeEvent.Unknown(obj))
        val blocks = runCatching { content.jsonArray }.getOrNull() ?: return listOf(ClaudeEvent.Unknown(obj))

        val out = mutableListOf<ClaudeEvent>()
        for (block in blocks) {
            val b = runCatching { block.jsonObject }.getOrNull() ?: continue
            if (b["type"]?.jsonPrimitive?.contentOrNull != "tool_result") continue
            val toolUseId = b["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: continue
            val isError = b["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
            val output: JsonElement = b["content"] ?: JsonObject(emptyMap())
            out += ClaudeEvent.ToolResult(toolUseId, output, isError)
        }
        return out.ifEmpty { listOf(ClaudeEvent.Unknown(obj)) }
    }

    private fun parseResult(obj: JsonObject): List<ClaudeEvent> {
        val out = mutableListOf<ClaudeEvent>()
        // v0.63.0 — result frame 에도 usage 가 종종 포함됨 (turn 종료 시 누적치).
        obj["usage"]?.let { parseUsage(it) }?.let { out += it }
        val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val isError = obj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
        out += if (isError) {
            val msg = obj["error"]?.jsonPrimitive?.contentOrNull
                ?: obj["result"]?.jsonPrimitive?.contentOrNull
                ?: "claude returned an error"
            ClaudeEvent.ErrorEvent(code = subtype, message = msg)
        } else {
            ClaudeEvent.Done(reason = subtype)
        }
        return out
    }

    /**
     * v0.63.0 — usage JSON 파싱.
     * ```
     * { "input_tokens": 4, "output_tokens": 50,
     *   "cache_read_input_tokens": 12345, "cache_creation_input_tokens": 0 }
     * ```
     * 4 필드 모두 nullable. 모두 null 이면 emit skip (return null).
     */
    private fun parseUsage(el: JsonElement): ClaudeEvent.UsageReport? {
        val obj = runCatching { el.jsonObject }.getOrNull() ?: return null
        fun long(key: String): Long? =
            obj[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        val input = long("input_tokens")
        val output = long("output_tokens")
        val cacheRead = long("cache_read_input_tokens")
        val cacheCreate = long("cache_creation_input_tokens")
        if (input == null && output == null && cacheRead == null && cacheCreate == null) return null
        return ClaudeEvent.UsageReport(
            inputTokens = input,
            outputTokens = output,
            cacheReadInputTokens = cacheRead,
            cacheCreationInputTokens = cacheCreate,
        )
    }

    companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
