package com.siamakerlab.vibecoder.server.env

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists

private val log = KotlinLogging.logger {}

/**
 * v1.35.0 — Claude Code 스킬 디렉토리 관리.
 *
 * 스킬은 `<skillsDir>/<name>/SKILL.md` 형태(디렉토리당 하나). Claude Code 는 전역
 * `~/.claude/skills` + 프로젝트 `<root>/.claude/skills` 양쪽을 읽는다. 본 레지스트리는
 * [rootProvider] 가 가리키는 skills 디렉토리를 스캔하므로 터미널/Claude 가 직접 만든
 * 스킬도 자동 감지된다.
 *
 * 편집 범위는 각 스킬의 SKILL.md (스킬 본문). 부속 스크립트/리소스는 파일 브라우저로.
 * 삭제는 해당 스킬 디렉토리 전체를 제거(스킬 = 디렉토리).
 */
class SkillRegistry(
    private val rootProvider: () -> Path,
) {
    data class Skill(
        val name: String,
        val sizeBytes: Long,
        val preview: String,
        val updatedAtMs: Long,
        /** SKILL.md 없이 디렉토리만 있는 경우 false (불완전 스킬). */
        val hasSkillMd: Boolean,
    )

    fun list(): List<Skill> {
        val dir = rootProvider()
        if (dir.notExists() || !dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.isDirectory() }
                .map { d ->
                    val name = d.fileName.toString()
                    val md = d.resolve("SKILL.md")
                    val has = md.isRegularFile()
                    val size = if (has) runCatching { Files.size(md) }.getOrDefault(0L) else 0L
                    val preview = if (has) runCatching {
                        val raw = Files.readString(md)
                        if (raw.length > 600) raw.take(600) + " …(+${raw.length - 600})" else raw
                    }.getOrDefault("(read failed)") else "(SKILL.md 없음)"
                    val mtime = if (has) runCatching { Files.getLastModifiedTime(md).toMillis() }.getOrDefault(0L) else 0L
                    Skill(name, size, preview, mtime, has)
                }
                .toList()
                .sortedBy { it.name.lowercase() }
        }
    }

    fun read(name: String): String? {
        val safe = sanitize(name) ?: return null
        val md = rootProvider().resolve(safe).resolve("SKILL.md")
        if (!md.isRegularFile()) return null
        return runCatching { Files.readString(md) }.getOrNull()
    }

    /** Create or overwrite `<name>/SKILL.md`. */
    fun write(name: String, body: String) {
        val safe = sanitize(name) ?: throw IllegalArgumentException("invalid skill name (use [A-Za-z0-9._-]{1,64})")
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw IllegalArgumentException("body too large (max $MAX_BODY_BYTES bytes)")
        }
        val skillDir = rootProvider().resolve(safe)
        Files.createDirectories(skillDir)
        val target = skillDir.resolve("SKILL.md")
        val tmp = skillDir.resolve("SKILL.md.tmp")
        Files.writeString(tmp, body)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        log.info { "skill saved: $safe (${body.length}B)" }
    }

    /** 스킬 디렉토리 전체 삭제. sanitize + skillsDir 하위 확인으로 traversal 방어. */
    fun delete(name: String): Boolean {
        val safe = sanitize(name) ?: return false
        val root = rootProvider().toAbsolutePath().normalize()
        val skillDir = root.resolve(safe).toAbsolutePath().normalize()
        if (!skillDir.startsWith(root) || skillDir == root) return false
        if (skillDir.notExists()) return false
        return runCatching {
            Files.walk(skillDir).use { s ->
                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            true
        }.getOrElse { e -> log.warn(e) { "skill delete failed: $safe" }; false }
            .also { log.info { "skill delete: $safe ok=$it" } }
    }

    private fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        if (!trimmed.all { it.isLetterOrDigit() || it in setOf('.', '-', '_') }) return null
        if (trimmed.startsWith('.')) return null
        return trimmed
    }

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024

        fun globalRoot(): Path {
            val claudeConfig = System.getenv("CLAUDE_CONFIG_DIR")?.ifBlank { null }
                ?: (System.getProperty("user.home") + "/.claude")
            return Path.of(claudeConfig, "skills")
        }
    }
}
