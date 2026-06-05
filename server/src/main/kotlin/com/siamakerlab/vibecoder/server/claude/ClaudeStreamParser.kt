package com.siamakerlab.vibecoder.server.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
                val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull
                when (subtype) {
                    // v1.70.0 — thinking_tokens 는 추정 토큰 카운터로 UI/이력 가치가 없는 순수
                    // 노이즈(운영 DB 5792행). emit/적재 모두 생략.
                    "thinking_tokens" -> emptyList()
                    // v1.84.0 — 백그라운드 작업(Bash run_in_background / Task 서브에이전트) lifecycle.
                    "task_started", "task_progress", "task_updated", "task_notification" ->
                        parseBackgroundTask(obj, subtype).let { listOf(it) }
                    // v1.85.0 — 컨텍스트 auto-compaction 경계. claude 가 한도 근처에서 이전
                    // 대화를 요약 압축할 때 emit. 세션(session-id)은 그대로 유지되므로 정보성
                    // 알림으로만 노출(사용자가 "왜 앞 내용을 잊은 듯하지?" 를 이해하게).
                    "compact_boundary" -> listOf(
                        ClaudeEvent.SystemNote(
                            code = "compact",
                            message = "🗜 컨텍스트가 자동으로 압축되었습니다 — 이전 대화가 요약되었고 " +
                                "세션은 그대로 이어집니다.",
                        ),
                    )
                    else -> parseSystem(obj)?.let { listOf(it) } ?: listOf(ClaudeEvent.Unknown(obj))
                }
            }
            "assistant" -> parseAssistant(obj)
            "user" -> parseUserToolResult(obj)
            "result" -> parseResult(obj)
            // v1.83.0 — claude 2.x 가 서버측 rate limit 시 내보내는 정보성 이벤트.
            // 이전엔 Unknown(JSON 노이즈) 으로만 흘러 사용자가 "thinking 후 멈춤" 으로
            // 오해했다. turn 종료 아님 — 시스템 메시지로 노출해 대기/재시도임을 알린다.
            "rate_limit_event" -> listOf(
                ClaudeEvent.SystemNote(
                    code = "rate_limit",
                    message = "Anthropic 서버가 요청을 일시적으로 제한하고 있습니다 (rate limit). " +
                        "Claude CLI 가 자동으로 재시도 중입니다 — 잠시만 기다려 주세요.",
                ),
            )
            else -> listOf(ClaudeEvent.Unknown(obj))
        }
    }

    /**
     * v1.84.0 — system subtype task_started/task_updated/task_notification →
     * [ClaudeEvent.BackgroundTask]. task_started 는 description/task_type, task_updated 는
     * patch.status 를 동반한다(예: {"patch":{"status":"completed"}}). task_id 가 없으면
     * 의미 없는 프레임이라 Unknown 으로 흘린다.
     */
    private fun parseBackgroundTask(obj: JsonObject, subtype: String): ClaudeEvent {
        val taskId = obj["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return ClaudeEvent.Unknown(obj)
        val kind = when (subtype) {
            "task_started" -> "started"
            "task_progress" -> "progress"
            "task_updated" -> "updated"
            else -> "notification"
        }
        val patch = runCatching { obj["patch"]?.jsonObject }.getOrNull()
        val status = patch?.get("status")?.jsonPrimitive?.contentOrNull
            ?: obj["status"]?.jsonPrimitive?.contentOrNull
        val usage = runCatching { obj["usage"]?.jsonObject }.getOrNull()
        return ClaudeEvent.BackgroundTask(
            kind = kind,
            taskId = taskId,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            // task_started 는 task_type(local_bash), task_progress 는 subagent_type(general-purpose).
            taskType = obj["task_type"]?.jsonPrimitive?.contentOrNull
                ?: obj["subagent_type"]?.jsonPrimitive?.contentOrNull,
            status = status,
            lastTool = obj["last_tool_name"]?.jsonPrimitive?.contentOrNull,
            toolUses = usage?.get("tool_uses")?.jsonPrimitive?.intOrNull,
        )
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
        message["usage"]?.let { parseUsage(it, cumulative = false) }?.let { out += it }
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
                    // v1.86.0 — 빈 thinking(signature-only)도 통과. 클라(renderUnknown)가
                    // "💭 Thinking…" 뱃지(이름만)로 렌더한다(사용자 요청 — 숨기지 말고 흔적 표시).
                    // 내용 있는 thinking 은 💭 + 내용. 절단으로 JSON 깨져도 renderUnknown 이
                    // 타입 추출 뱃지로 안전 처리.
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
        // v1.107.1 — cumulative=true 로 표시 → 컨텍스트 미터는 무시(누적치라 윈도우 초과).
        obj["usage"]?.let { parseUsage(it, cumulative = true) }?.let { out += it }
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
    private fun parseUsage(el: JsonElement, cumulative: Boolean): ClaudeEvent.UsageReport? {
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
            cumulative = cumulative,
        )
    }

    companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
