package com.siamakerlab.vibecoder.server.env

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists

private val log = KotlinLogging.logger {}

/**
 * v0.31.0 — Claude Code `.agents/` 디렉토리 관리.
 *
 * Claude Code CLI 는 `~/.claude/agents/<name>.md` 를 읽어 custom agent
 * (sub-agent) 로 사용할 수 있다. 본 서비스는 그 디렉토리를 UI 로 노출:
 *   - 목록 + 본문 미리보기 + 신규 생성 + 삭제
 *   - 이름 sanitize ([A-Za-z0-9._-]{1,64}) — shell / path traversal 방어
 *   - 본문은 최대 64 KB 까지 허용 (보통 1~5 KB 안 넘음)
 *
 * 위치는 `CLAUDE_CONFIG_DIR` env (= `/home/vibe/.claude` 안의 `agents`)
 * 를 따른다. env 미설정 시 시스템 `user.home/.claude/agents`.
 */
class AgentRegistry(
    private val rootProvider: () -> Path = ::defaultRoot,
) {

    data class Agent(
        val name: String,                 // 파일명 stem (e.g. "code-reviewer")
        val sizeBytes: Long,
        val preview: String,              // 첫 600 자
        val updatedAtMs: Long,
    )

    fun list(): List<Agent> {
        val dir = rootProvider()
        if (dir.notExists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".md", ignoreCase = true) }
                .map { p ->
                    val name = p.fileName.toString().removeSuffix(".md").removeSuffix(".MD")
                    val size = runCatching { Files.size(p) }.getOrDefault(0L)
                    val preview = runCatching {
                        val raw = Files.readString(p)
                        if (raw.length > 600) raw.take(600) + " …(+${raw.length - 600})"
                        else raw
                    }.getOrDefault("(read failed)")
                    val mtime = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                    Agent(name, size, preview, mtime)
                }
                .toList()
                .sortedBy { it.name.lowercase() }
        }
    }

    fun read(name: String): String? {
        val safe = sanitize(name) ?: return null
        val p = rootProvider().resolve("$safe.md")
        if (!p.isRegularFile()) return null
        return runCatching { Files.readString(p) }.getOrNull()
    }

    /**
     * Create or overwrite. Returns Path on success, throws IllegalArgumentException on
     * invalid name / too-large body. Sanitizes name strictly.
     */
    fun write(name: String, body: String) {
        val safe = sanitize(name) ?: throw IllegalArgumentException("invalid agent name (use [A-Za-z0-9._-]{1,64})")
        // v1.43.0 — 22차 정밀점검 회수: char/byte 혼동 + 무효 분기. 이전엔 take(MAX_BODY_BYTES)
        // 로 char 기준 자른 뒤 length 검사라 검증이 항상 false(무truncation) + 멀티바이트
        // 본문은 실제 UTF-8 byte 가 한도 초과 가능. UTF-8 byte 기준 검증, 자르지 않고 거부.
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BODY_BYTES) {
            throw IllegalArgumentException("body too large (max $MAX_BODY_BYTES bytes)")
        }
        val dir = rootProvider()
        if (dir.notExists()) Files.createDirectories(dir)
        val target = dir.resolve("$safe.md")
        val tmp = dir.resolve("$safe.md.tmp")
        Files.writeString(tmp, body)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        log.info { "agent saved: $safe (${bytes.size}B)" }
    }

    fun delete(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        val target = rootProvider().resolve("$safe.md")
        return runCatching { Files.deleteIfExists(target) }.getOrDefault(false)
            .also { log.info { "agent delete: $safe ok=$it" } }
    }

    private fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        if (!trimmed.all { it.isLetterOrDigit() || it in setOf('.', '-', '_') }) return null
        if (trimmed.startsWith('.')) return null  // hidden 파일 방지
        return trimmed
    }

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024  // 64 KB

        fun defaultRoot(): Path {
            val claudeConfig = System.getenv("CLAUDE_CONFIG_DIR")?.ifBlank { null }
                ?: (System.getProperty("user.home") + "/.claude")
            return Path.of(claudeConfig, "agents")
        }
    }
}
