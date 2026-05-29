package com.siamakerlab.vibecoder.server.env

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * v1.35.0 — 전역(글로벌) CLAUDE.md 관리.
 *
 * Claude Code 는 매 세션에서 user-level memory 인 `~/.claude/CLAUDE.md` 를 자동 로드한다.
 * 컨테이너 안에서 자식 Claude 프로세스는 vibe 유저로 spawn 되므로 이 파일은 **모든
 * 프로젝트에 공통 적용**되는 전역 규칙이 된다(프로젝트별 `<root>/CLAUDE.md` 와 병합).
 *
 * 경로 정책: 워크스페이스 밖의 고정 경로(`/home/vibe/keystores`, `/home/vibe/.ssh`,
 * `/home/vibe/.config/git/config` 와 동일 계열의 운영자-관리 config). 사용자 입력 경로가
 * 아니라 고정값이므로 path traversal 위험 없음. 기본값은 `user.home/.claude/CLAUDE.md`
 * (컨테이너 = /home/vibe, 로컬 dev = 개발자 home) — Claude Code 의 user-memory 해석과 일치.
 * `VIBECODER_GLOBAL_CLAUDEMD_PATH` env 로 override 가능.
 *
 * 저장은 atomic move(`.tmp` → `move REPLACE_EXISTING`) + 직전 내용 `.bak` 백업.
 */
class GlobalClaudeMdService(
    val path: Path = defaultPath(),
) {
    fun exists(): Boolean = path.isRegularFile()

    fun sizeBytes(): Long =
        if (exists()) runCatching { Files.size(path) }.getOrDefault(0L) else 0L

    /** 파일이 없으면 빈 문자열. */
    fun read(): String =
        if (exists()) runCatching { Files.readString(path) }.getOrElse { "" } else ""

    /** 원자적 저장 + 직전 내용 1단계 백업. 호출 전 byte 한도 검증은 라우트가 수행. */
    fun write(content: String) {
        Files.createDirectories(path.parent)
        if (exists()) {
            runCatching {
                Files.copy(path, path.resolveSibling("CLAUDE.md.bak"), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { log.warn(it) { "global CLAUDE.md backup failed (계속 진행)" } }
        }
        val tmp = path.resolveSibling("CLAUDE.md.tmp")
        Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info { "global CLAUDE.md saved → $path (${content.toByteArray(StandardCharsets.UTF_8).size}B)" }
    }

    companion object {
        /** 256KB — CLAUDE.md 는 보통 수~수십 KB. 비정상적 대용량 차단. */
        const val MAX_BYTES = 256 * 1024

        private fun defaultPath(): Path {
            System.getenv("VIBECODER_GLOBAL_CLAUDEMD_PATH")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return Path.of(it)
            }
            val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: "/home/vibe"
            return Path.of(home).resolve(".claude").resolve("CLAUDE.md")
        }
    }
}
