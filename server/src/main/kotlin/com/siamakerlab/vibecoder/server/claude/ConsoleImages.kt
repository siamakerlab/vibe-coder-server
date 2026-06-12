package com.siamakerlab.vibecoder.server.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.133.0 — 콘솔 대화 row 안의 이미지 블록 추출/분리 유틸.
 *
 * 두 곳에서 사용:
 *  1. 콘솔 이력 복원([com.siamakerlab.vibecoder.server.admin.WebProjectTemplates.renderInitialHistoryJson]):
 *     tool_result content 의 base64 이미지를 inline JSON 에 싣지 않고(페이지 수 MB 방지 +
 *     기존 4000자 클립으로 JSON 이 깨지던 문제 회피) 메타(`images:[{mediaType}]`)만 emit.
 *  2. 이미지 서빙(`GET /api/projects/{id}/claude/console/image?turn=N&idx=M`):
 *     DB row 에서 idx 번째 이미지의 base64 를 디코드해 실제 이미지 bytes 로 응답.
 *
 * 대상 row:
 *  - role=user — 첨부 이미지가 `raw` 컬럼에 `[{"mediaType","data"}]` JSON 으로 보존됨(v1.133.0+).
 *  - role=tool_result(_error) — content 가 stream-json tool_result 의 content JSON.
 *    이미지 블록 형태: `{"type":"image","source":{"type":"base64","media_type":...,"data":...}}`
 *    (Read 가 이미지 파일을 읽었을 때 / MCP 스크린샷 도구 등).
 */
object ConsoleImages {

    data class Img(val mediaType: String, val data: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 빠른 사전 판정 — 파싱 비용 없이 이미지 블록 존재 가능성 체크. */
    fun toolResultMayContainImage(content: String): Boolean =
        content.contains("\"type\":\"image\"")

    /** role=user row 의 raw 컬럼(JSON 배열) → 이미지 목록. 형식이 어긋나면 빈 리스트. */
    fun fromUserRaw(raw: String?): List<Img> {
        if (raw.isNullOrBlank() || !raw.startsWith("[")) return emptyList()
        return runCatching {
            val arr = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val mt = o["mediaType"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val data = o["data"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Img(mt, data)
            }
        }.getOrDefault(emptyList())
    }

    /** tool_result content JSON → base64 이미지 블록 목록(등장 순서 = idx). */
    fun fromToolResultContent(content: String): List<Img> {
        if (!toolResultMayContainImage(content)) return emptyList()
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Img>()
        collectImages(root, out)
        return out
    }

    /**
     * tool_result content JSON 의 이미지 블록에서 base64 data 만 제거(블록은
     * `{"type":"image","omitted":true,"media_type":...}` 로 치환)한 JSON 문자열과
     * 제거된 이미지의 mediaType 목록(등장 순서 = idx)을 반환.
     * 파싱 실패 시 원문 그대로 + 빈 목록.
     */
    fun stripToolResultImages(content: String): Pair<String, List<String>> {
        if (!toolResultMayContainImage(content)) return content to emptyList()
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull()
            ?: return content to emptyList()
        val types = mutableListOf<String>()
        val cleaned = stripImages(root, types)
        if (types.isEmpty()) return content to emptyList()
        return cleaned.toString() to types
    }

    private fun imageBlockOrNull(el: JsonElement): Img? {
        val o = el as? JsonObject ?: return null
        if (o["type"]?.jsonPrimitive?.contentOrNull != "image") return null
        val src = o["source"] as? JsonObject ?: return null
        if (src["type"]?.jsonPrimitive?.contentOrNull != "base64") return null
        val mt = src["media_type"]?.jsonPrimitive?.contentOrNull ?: return null
        val data = src["data"]?.jsonPrimitive?.contentOrNull ?: return null
        if (data.isBlank()) return null
        return Img(mt, data)
    }

    private fun collectImages(el: JsonElement, out: MutableList<Img>) {
        when (el) {
            is JsonArray -> el.forEach { item ->
                imageBlockOrNull(item)?.let { out += it } ?: collectImages(item, out)
            }
            is JsonObject -> {
                imageBlockOrNull(el)?.let { out += it; return }
                // tool_result 가 {content:[...]} 형태로 한 겹 더 감싼 경우.
                (el["content"])?.let { collectImages(it, out) }
            }
            else -> {}
        }
    }

    private fun stripImages(el: JsonElement, types: MutableList<String>): JsonElement {
        imageBlockOrNull(el)?.let { img ->
            types += img.mediaType
            return JsonObject(mapOf(
                "type" to JsonPrimitive("image"),
                "omitted" to JsonPrimitive(true),
                "media_type" to JsonPrimitive(img.mediaType),
            ))
        }
        return when (el) {
            is JsonArray -> JsonArray(el.map { stripImages(it, types) })
            is JsonObject -> {
                val content = el["content"]
                if (content != null) {
                    val newContent = stripImages(content, types)
                    if (newContent !== content) {
                        JsonObject(el.toMutableMap().apply { put("content", newContent) })
                    } else el
                } else el
            }
            else -> el
        }
    }
}
