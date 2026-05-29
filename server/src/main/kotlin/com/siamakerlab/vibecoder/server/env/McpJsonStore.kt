package com.siamakerlab.vibecoder.server.env

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * v1.35.0 — 임의 경로의 `.mcp.json` 읽기/검증 저장 유틸. 프로젝트 `<root>/.mcp.json`
 * 관리에 사용(전역은 [McpService] 가 담당). 목록은 디스크 파싱이라 터미널/Claude 가
 * 직접 추가한 server 도 감지된다.
 */
object McpJsonStore {
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    const val MAX_BYTES = 128 * 1024

    /** `mcpServers` 의 server 이름들 (파싱 실패 시 빈 목록). */
    fun serverNames(path: Path): List<String> {
        if (!path.isRegularFile()) return emptyList()
        return runCatching {
            val root = lenient.parseToJsonElement(Files.readString(path)) as? JsonObject
            (root?.get("mcpServers") as? JsonObject)?.keys?.sorted().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun readRaw(path: Path): String =
        if (path.isRegularFile()) runCatching { Files.readString(path) }.getOrDefault("") else ""

    /**
     * JSON 유효성(객체 루트) 검증 후 atomic 저장. 성공 시 null, 실패 시 사용자용 에러 메시지.
     */
    fun writeRawValidated(path: Path, content: String): String? {
        val parsed = runCatching { lenient.parseToJsonElement(content) }
            .getOrElse { return "invalid JSON: ${it.message?.take(160)}" }
        if (parsed !is JsonObject) return "root must be a JSON object (예: { \"mcpServers\": { ... } })"
        runCatching { Files.createDirectories(path.parent) }
        val tmp = path.resolveSibling(".mcp.json.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return null
    }
}
