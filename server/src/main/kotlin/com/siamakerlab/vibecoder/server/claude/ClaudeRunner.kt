package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

class ClaudeRunner(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
) {
    /**
     * Run `claude -p "<wrapped>"` inside [sourcePath] and stream stdout/stderr
     * via [logger]. The pre-task `git status` snapshot and post-task `git diff`
     * are written to `.vibecoder/patches/{taskId}.patch`.
     */
    suspend fun execute(
        projectId: String,
        taskId: String,
        sourcePath: Path,
        userPrompt: String,
        cancellation: Flow<Unit>,
        logger: TaskLogger,
    ): Int {
        val wrapped = ClaudePromptBuilder.wrap(userPrompt)
        val cmd = resolveClaudeCmd()
        logger.info("Running Claude Code: $cmd -p <wrapped prompt>")
        logger.info("Working directory: $sourcePath")

        captureGitStatus(projectId, taskId, sourcePath, logger)

        val runner = ProcessRunner(workdir = sourcePath)
        val result = runner.run(
            command = listOf(cmd, "-p", wrapped),
            timeout = config.claude.timeoutMinutes.minutes,
            cancellation = cancellation,
        ) { level, line -> logger.line(level, line) }

        captureGitDiff(projectId, taskId, sourcePath, logger)

        logger.info("Claude exited with code=${result.exitCode} duration=${result.durationMs}ms timedOut=${result.timedOut}")
        return result.exitCode
    }

    private fun resolveClaudeCmd(): String {
        val envOverride = System.getenv("CLAUDE_CMD")
        if (!envOverride.isNullOrBlank()) return envOverride
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private suspend fun captureGitStatus(projectId: String, taskId: String, source: Path, logger: TaskLogger) {
        runCatching {
            val pb = ProcessBuilder("git", "status", "--short")
                .directory(source.toFile()).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val target = workspace.patchesDir(projectId).resolve("${taskId}.pre-status.txt")
            target.writeText(out)
            logger.info("Saved pre-task git status to ${target.fileName}")
        }.onFailure {
            logger.warn("Failed to capture git status: ${it.message}")
        }
    }

    private suspend fun captureGitDiff(projectId: String, taskId: String, source: Path, logger: TaskLogger) {
        runCatching {
            val pb = ProcessBuilder("git", "diff")
                .directory(source.toFile()).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            val patch = workspace.patchesDir(projectId).resolve("${taskId}.patch")
            patch.writeText(out)
            logger.info("Saved post-task git diff to ${patch.fileName} (${out.length} chars)")
        }.onFailure {
            logger.warn("Failed to capture git diff: ${it.message}")
        }
    }
}
