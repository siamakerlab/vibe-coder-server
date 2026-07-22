package com.siamakerlab.vibecoder.server.actions

import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptSender
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * Dispatches whitelisted [ProjectAction.RunServerAction] keys to the appropriate service.
 * Also handles slash-command and prompt actions by routing back through the selected console provider.
 *
 * Every dispatch result is announced on the project's console topic as a [WsFrame.ConsoleSystem]
 * so the user sees what happened without a separate channel.
 */
class ServerActionHandler(
    private val projects: ProjectService,
    private val builds: BuildService,
    private val git: GitReader,
    private val hub: LogHub,
    private val promptSender: ConsolePromptSender,
) {

    suspend fun dispatch(projectId: String, action: ProjectAction, params: JsonElement?) {
        when (action) {
            is ProjectAction.RunServerAction -> runServer(projectId, action.serverAction)
            is ProjectAction.InvokeClaudeSlashCommand -> invokeSlash(projectId, action.command)
            is ProjectAction.SendPrompt -> sendPrompt(projectId, action.promptTemplate)
            is ProjectAction.SnippetInsert -> {
                // Snippet insertion happens client-side; server just echoes a notice.
                emitSystem(projectId, "snippet_inserted", "Snippet inserted: ${action.label}")
            }
            is ProjectAction.OpenPalette -> emitSystem(projectId, "open_palette", "Open palette: ${action.paletteId}")
            is ProjectAction.InvokeMcpTool -> emitSystem(
                projectId, "mcp_invoke_pending",
                "MCP tool invocation pending (Phase E will wire dispatch): ${action.mcpServer}/${action.toolName}",
            )
        }
    }

    private suspend fun runServer(projectId: String, key: String) {
        if (key !in WHITELIST) {
            throw ApiException.localized(403, "action_not_allowed",
                messageKey = "api.serverAction.notAllowed", args = listOf(key))
        }
        val row = projects.rowOrThrow(projectId)
        when (key) {
            "build.debug" -> {
                val build = builds.enqueueDebug(projectId, hub)
                emitSystem(projectId, "build_started", "Build queued: ${build.id}")
            }
            "git.status" -> {
                val status = git.status(Path.of(row.sourcePath))
                emitSystem(projectId, "git_status",
                    "branch=${status.branch}, files=${status.entries.size}, ahead=${status.ahead}, behind=${status.behind}")
            }
            "git.diff" -> {
                val diff = git.diff(Path.of(row.sourcePath))
                val preview = diff.diff.lineSequence().take(20).joinToString("\n")
                emitSystem(projectId, "git_diff", preview.ifBlank { "no diff" })
            }
            "git.log" -> {
                val gitLog = git.log(Path.of(row.sourcePath))
                emitSystem(projectId, "git_log",
                    gitLog.entries.take(5).joinToString("\n") { "${it.sha.take(7)} ${it.message}" }.ifBlank { "no log" })
            }
            else -> throw ApiException.localized(500, "action_not_implemented",
                messageKey = "api.serverAction.notImplemented", args = listOf(key))
        }
    }

    private suspend fun invokeSlash(projectId: String, command: String) {
        // v0.75.0 — Claude Code 의 interactive slash commands (/status /cost /model /clear /memory
        // /plan /compact) 는 `claude --print --output-format stream-json` non-interactive 모드에서
        // 동작 안 함. 그냥 prompt 로 들어가면 Claude 가 "그게 뭔지 모르겠다" 응답. 모두 차단.
        //
        // 대안:
        //   - 사용량/모델: `/api/projects/{id}/claude/status` (ClaudeStatusService) 가 별도 snapshot.
        //   - 세션 reset: `/api/projects/{id}/claude/console/new` (서버 측 process 재시작).
        //
        // 사용자 정의 prompt-기반 actions (`actions.yml` 의 kind=prompt) 는 영향 없음 — 본 핸들러는 kind=slash 만.
        throw ApiException.localized(410, "slash_not_supported", messageKey = "api.serverAction.slashNotSupported")
    }

    private suspend fun sendPrompt(projectId: String, prompt: String) {
        promptSender.send(projectId, prompt, source = "server_action_prompt")
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(LogHub.consoleTopic(projectId)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
    }

    companion object {
        val WHITELIST = setOf("build.debug", "git.status", "git.diff", "git.log")
        // v0.76.0 (L5 fix) — SLASH_WHITELIST 는 v0.75.0 이후 사용처 없음 (invokeSlash
        // 가 항상 410). dead constant 제거. Android wire (`ProjectActionDto.
        // InvokeClaudeSlashCommand`) 는 그대로 직렬화 가능하나 서버는 거부함.
    }
}
