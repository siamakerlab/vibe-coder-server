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
        // 21차 후속(M1) — sidecar 이름을 실제 target 파일명에서 파생(env override 로 다른
        // 파일명을 써도 <name>.bak / <name>.tmp 로 일치).
        val name = path.fileName.toString()
        if (exists()) {
            runCatching {
                Files.copy(path, path.resolveSibling("$name.bak"), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { log.warn(it) { "global CLAUDE.md backup failed (계속 진행)" } }
        }
        val tmp = path.resolveSibling("$name.tmp")
        Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info { "global CLAUDE.md saved → $path (${content.toByteArray(StandardCharsets.UTF_8).size}B)" }
    }

    /**
     * v1.41.0 — 도커 first-run 시 전역 CLAUDE.md 가 없으면 번들 시드 템플릿으로 생성.
     * v1.42.0 — 언어별(en/ko) 템플릿. **파일이 이미 있으면 절대 덮어쓰지 않는다**
     * (운영자/사용자 편집본 보존). 리소스가 없거나 쓰기 실패해도 startup 비차단
     * (무시 — /settings/claude-md 에서 직접 작성 가능).
     */
    fun seedDefaultIfAbsent(lang: String) {
        if (exists()) return
        val seed = seedFor(lang)
        if (seed.isNullOrBlank()) {
            log.warn { "global CLAUDE.md 시드 리소스를 찾을 수 없음 (lang=$lang) — skip" }
            return
        }
        atomicWrite(seed, ".seed.tmp")
        log.info { "global CLAUDE.md first-run 시드 생성 → $path (lang=$lang, ${seed.toByteArray(StandardCharsets.UTF_8).size}B)" }
    }

    /**
     * v1.42.0 — 설정 언어 변경 시 해당 언어 템플릿 적용.
     *
     * 단, **현재 파일이 미편집 시드 상태(en/ko 어느 쪽 시드와도 내용이 정확히 일치)일 때만**
     * 교체한다. 사용자가 직접 편집한 내용은 [SKIPPED_EDITED] 로 보존(절대 덮어쓰지 않음).
     * 파일이 없으면 그냥 시드한다([SEEDED]). [write] 가 직전 내용을 `.bak` 로 남기므로 교체는
     * 되돌릴 수 있다.
     */
    fun applyLanguage(lang: String): LanguageApply {
        val seed = seedFor(lang) ?: return LanguageApply.FAILED
        if (!exists()) {
            return runCatching { atomicWrite(seed, ".seed.tmp"); LanguageApply.SEEDED }
                .getOrElse { log.warn(it) { "global CLAUDE.md 언어 시드 실패" }; LanguageApply.FAILED }
        }
        val current = read().trim()
        if (current == seed.trim()) return LanguageApply.NOOP // 이미 해당 언어 시드
        val isUnmodifiedSeed = knownSeeds().any { it.trim() == current }
        if (!isUnmodifiedSeed) return LanguageApply.SKIPPED_EDITED // 사용자 편집본 보존
        return runCatching { write(seed); LanguageApply.SWAPPED }
            .getOrElse { log.warn(it) { "global CLAUDE.md 언어 교체 실패" }; LanguageApply.FAILED }
    }

    /** 언어별 번들 시드 템플릿 로드. 미지원 언어/누락 시 en fallback. */
    private fun seedFor(lang: String): String? {
        val normalized = lang.trim().lowercase().ifEmpty { "en" }
        return loadResource("/templates/container-global-claude.$normalized.md")
            ?: loadResource("/templates/container-global-claude.en.md")
    }

    /** 미편집 판정용 — 모든 언어 시드 내용. */
    private fun knownSeeds(): List<String> =
        listOf("en", "ko").mapNotNull { loadResource("/templates/container-global-claude.$it.md") }

    private fun loadResource(name: String): String? = runCatching {
        javaClass.getResourceAsStream(name)
            ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    private fun atomicWrite(content: String, tmpSuffix: String) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}$tmpSuffix")
        Files.write(tmp, content.toByteArray(StandardCharsets.UTF_8))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** v1.42.0 — [applyLanguage] 결과. */
    enum class LanguageApply { SEEDED, SWAPPED, NOOP, SKIPPED_EDITED, FAILED }

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
