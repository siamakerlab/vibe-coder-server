package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.70.2 — `conversation_turns` 의 raw content 를 SSR 히스토리 페이지(/history,
 * /chat/history, 전역 검색)에서 사람이 읽는 한 줄로 변환.
 *
 * 콘솔의 클라이언트 렌더러(`static/admin/console-render.js` 의 `window.VibeConsole`)
 * 와 동일한 규칙의 **서버측 짝**이다. 콘솔(라이브/replay)은 JS 가, SSR 히스토리는
 * 이 객체가 담당한다 — 두 곳 모두 raw JSON 이 노출되지 않게 한다.
 *
 * 파싱 실패 시 항상 원본 content 로 안전 폴백.
 */
object HistoryContentFormatter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** (role, toolName, rawContent) → 친화 문자열. */
    fun friendly(role: String, toolName: String?, content: String): String = try {
        when (role) {
            "tool_use" -> summarizeObject(content)
            "tool_result", "tool_result_error" -> extractResult(content)
            "unknown" -> friendlyUnknown(content)
            "system" -> friendlySystem(content)
            "error" -> {
                val o = json.parseToJsonElement(content) as? JsonObject ?: return content
                val code = o["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val msg = o["message"]?.jsonPrimitive?.contentOrNull ?: content
                if (code.isBlank()) msg else "$code: $msg"
            }
            else -> content // user / assistant / usage 등은 그대로
        }
    } catch (e: Exception) {
        content
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.contentOrNull

    /** tool_use input object → "key=value …" 요약 (긴 값은 80자 클립). */
    private fun summarizeObject(content: String): String {
        val o = json.parseToJsonElement(content) as? JsonObject ?: return content
        if (o.isEmpty()) return ""
        return o.entries.take(6).joinToString("  ") { (k, v) ->
            val vs = when (v) {
                is JsonPrimitive -> v.contentOrNull?.let { if (it.length > 80) it.take(80) + "…" else it } ?: v.toString()
                is JsonArray -> "[${v.size}]"
                is JsonObject -> "{…}"
                else -> v.toString()
            }
            "$k=$vs"
        }
    }

    /** tool_result content(string | array[{type,text}] | object) → 평문. */
    private fun extractResult(content: String): String {
        val el = json.parseToJsonElement(content)
        return when (el) {
            is JsonPrimitive -> el.contentOrNull ?: content
            is JsonArray -> el.joinToString("\n") { b ->
                when (b) {
                    is JsonPrimitive -> b.contentOrNull ?: b.toString()
                    is JsonObject -> when (str(b, "type")) {
                        "text" -> str(b, "text") ?: ""
                        "image" -> "[image]"
                        else -> str(b, "text") ?: summarizeObject(b.toString())
                    }
                    else -> b.toString()
                }
            }
            is JsonObject -> str(el, "text") ?: str(el, "content") ?: content
            else -> content
        }
    }

    private fun friendlyUnknown(content: String): String {
        val o = json.parseToJsonElement(content) as? JsonObject ?: return content
        return when (str(o, "type")) {
            "thinking" -> {
                val th = str(o, "thinking")?.trim().orEmpty()
                if (th.isEmpty()) "💭 (사고 과정)" else "💭 $th"
            }
            "system" -> when (val st = str(o, "subtype")) {
                "task_started" -> "🟢 task: " + str(o, "description").orEmpty()
                "task_notification" -> "✓ task: " + (str(o, "summary") ?: str(o, "status").orEmpty())
                "task_updated" -> "… task: " + (str(o, "summary") ?: ("status=" + str(o, "status").orEmpty()))
                else -> "system·${st ?: "?"}"
            }
            "rate_limit_event" -> {
                val info = o["rate_limit_info"] as? JsonObject
                "⏳ rate limit: " + (info?.let { str(it, "status") } ?: "")
            }
            else -> summarizeObject(content)
        }
    }

    private fun friendlySystem(content: String): String {
        val o = json.parseToJsonElement(content) as? JsonObject ?: return content
        return when (str(o, "kind")) {
            "session_started" -> "session started" + (str(o, "model")?.let { " · $it" } ?: "")
            "done" -> "done: " + str(o, "reason").orEmpty()
            "system" -> (str(o, "code") ?: "system") + ": " + str(o, "message").orEmpty()
            else -> content
        }
    }
}
