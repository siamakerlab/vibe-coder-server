package com.siamakerlab.vibecoder.server.projects

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists

private val log = KotlinLogging.logger {}

/**
 * v0.12.2 — Claude 관련 프로젝트 파일 (CLAUDE.md, .claude/settings.json) 의
 * 멱등 backfill 헬퍼.
 *
 * 신규 프로젝트는 [ProjectService.register] 가 생성 시점에 만들지만, v0.7.0
 * 이전에 만들어졌거나 사용자가 수동으로 만든 프로젝트는 이 파일들이 없을 수
 * 있다. 그러면 Claude Code 가 `permissions.defaultMode: bypassPermissions`
 * 를 적용 못 받아 `ask` 모드로 동작 → vibe-coder 비인터랙티브 환경에서
 * 권한 prompt 가 응답 안 됨 → 모든 write/edit 거부.
 *
 * [ClaudeSessionManager.spawnSession] 이 매 spawn 직전에 호출. 기존 파일은
 * 절대 덮어쓰지 않음 — 사용자 수정 보존.
 */
object ProjectScaffolder {

    /**
     * 프로젝트 루트에 CLAUDE.md / .claude/settings.json 이 없으면 default 생성.
     * 이미 있으면 noop — 사용자가 customize 한 내용 보존.
     *
     * @return 새로 생성된 파일 수 (디버그용).
     */
    fun ensureClaudeFiles(projectRoot: Path): Int {
        var created = 0
        try {
            if (projectRoot.notExists()) return 0   // race — register 가 아직 못 만든 케이스

            val claudeMd = projectRoot.resolve("CLAUDE.md")
            if (claudeMd.notExists()) {
                Files.writeString(claudeMd, ClaudeMdTemplate.CONTENT)
                created++
                log.info { "backfilled CLAUDE.md → $claudeMd" }
            }

            if (ensureAgentsLink(projectRoot)) {
                created++
            }

            val claudeDir = projectRoot.resolve(".claude")
            if (claudeDir.notExists()) {
                claudeDir.createDirectories()
            }
            val settingsJson = claudeDir.resolve("settings.json")
            if (settingsJson.notExists()) {
                Files.writeString(settingsJson, ClaudeSettingsTemplate.CONTENT)
                created++
                log.info { "backfilled .claude/settings.json → $settingsJson" }
            }
        } catch (e: Throwable) {
            // backfill 실패가 prompt 차단 사유는 아니어야 함 — log 만.
            log.warn(e) { "Claude file backfill failed for $projectRoot: ${e.message}" }
        }
        return created
    }

    /**
     * 프로젝트 루트의 `AGENTS.md` 를 같은 폴더의 `CLAUDE.md` 를 가리키는
     * 심볼릭 링크로 유지한다. 사용자가 직접 만든 일반 파일은 덮어쓰지 않는다.
     */
    fun ensureAgentsLink(projectRoot: Path): Boolean {
        return try {
            val claudeMd = projectRoot.resolve("CLAUDE.md")
            if (claudeMd.notExists()) return false

            val agentsMd = projectRoot.resolve("AGENTS.md")
            if (Files.exists(agentsMd)) return false
            if (Files.isSymbolicLink(agentsMd)) {
                Files.deleteIfExists(agentsMd)
            }
            Files.createSymbolicLink(agentsMd, Path.of("CLAUDE.md"))
            log.info { "created AGENTS.md symlink → $agentsMd -> CLAUDE.md" }
            true
        } catch (e: FileAlreadyExistsException) {
            false
        } catch (e: Throwable) {
            log.warn(e) { "AGENTS.md symlink backfill failed for $projectRoot: ${e.message}" }
            false
        }
    }
}
