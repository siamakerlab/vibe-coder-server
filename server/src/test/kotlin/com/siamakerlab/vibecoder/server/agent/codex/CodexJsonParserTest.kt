package com.siamakerlab.vibecoder.server.agent.codex

import com.siamakerlab.vibecoder.server.config.BuildSection
import com.siamakerlab.vibecoder.server.config.ClaudeSection
import com.siamakerlab.vibecoder.server.config.GitSection
import com.siamakerlab.vibecoder.server.config.SecuritySection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.config.ServerSection
import com.siamakerlab.vibecoder.server.config.WorkspaceSection
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.ws.LogHub
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class CodexJsonParserTest {
    private val parser = CodexJsonParser()

    @Test fun `parses thread started`() {
        val ev = parser.parseLine("""{"type":"thread.started","thread_id":"thr_123"}""")

        ev.shouldBeInstanceOf<CodexEvent.ThreadStarted>()
        ev.threadId shouldBe "thr_123"
    }

    @Test fun `parses completed agent message item`() {
        val ev = parser.parseLine(
            """{"type":"item.completed","item":{"id":"i1","type":"agent_message","text":"done"}}""",
        )

        ev.shouldBeInstanceOf<CodexEvent.AgentMessage>()
        ev.text shouldBe "done"
    }

    @Test fun `parses command execution start`() {
        val ev = parser.parseLine(
            """{"type":"item.started","item":{"id":"cmd1","type":"command_execution","command":"bash -lc ls"}}""",
        )

        ev.shouldBeInstanceOf<CodexEvent.CommandStarted>()
        ev.id shouldBe "cmd1"
        ev.command shouldBe "bash -lc ls"
    }

    @Test fun `parses command execution completed output`() {
        val ev = parser.parseLine(
            """{"type":"item.completed","item":{"id":"cmd1","type":"command_execution","command":"bash -lc 'printf ok'","aggregated_output":"ok","exit_code":0,"status":"completed"}}""",
        )

        ev.shouldBeInstanceOf<CodexEvent.CommandCompleted>()
        ev.id shouldBe "cmd1"
        ev.command shouldBe "bash -lc 'printf ok'"
        ev.output.toString() shouldBe "\"ok\""
    }

    @Test fun `parses turn completed`() {
        parser.parseLine("""{"type":"turn.completed"}""") shouldBe CodexEvent.TurnCompleted()
    }

    @Test fun `builds Codex exec args with yolo option before exec`() {
        buildCodexExecArgs(
            cmd = "codex",
            text = "hello",
            threadId = null,
            model = null,
        ) shouldBe listOf(
            "codex",
            "--dangerously-bypass-approvals-and-sandbox",
            "exec",
            "--json",
            "--skip-git-repo-check",
            "--",
            "hello",
        )
    }

    @Test fun `builds Codex exec args with prompt delimiter before dash-leading prompt`() {
        buildCodexExecArgs(
            cmd = "codex",
            text = "- fix the failing test",
            threadId = null,
            model = null,
        ).takeLast(2) shouldBe listOf("--", "- fix the failing test")
    }

    @Test fun `builds Codex resume args after exec options`() {
        buildCodexExecArgs(
            cmd = "codex",
            text = "continue",
            threadId = "019f0b7e-3a17-7520-8447-dbd7e1cc958d",
            model = "gpt-5.5",
        ) shouldBe listOf(
            "codex",
            "--dangerously-bypass-approvals-and-sandbox",
            "exec",
            "--json",
            "--skip-git-repo-check",
            "--model", "gpt-5.5",
            "resume", "019f0b7e-3a17-7520-8447-dbd7e1cc958d",
            "--",
            "continue",
        )
    }

    @Test fun `start new clears Codex thread id and context snapshot`() {
        runBlocking {
            val root = Files.createTempDirectory("codex-session-manager-test")
            val workspace = WorkspacePath(root)
            val projectId = "app"
            val meta = workspace.vibecoderDir(projectId)
            val threadFile = meta.resolve("codex-thread.id")
            val contextFile = meta.resolve("codex-context-tokens")
            threadFile.writeText("thread-1")
            contextFile.writeText("100,200,0,128000")

            val manager = CodexSessionManager(testServerConfig(), workspace, LogHub())

            manager.startNew(projectId)

            threadFile.exists().shouldBeFalse()
            contextFile.exists().shouldBeFalse()
            manager.currentSessionId(projectId) shouldBe null
            manager.contextSnapshot(projectId) shouldBe com.siamakerlab.vibecoder.server.agent.AgentContextSnapshot()
        }
    }

    @Test fun `busy prompt is queued and runs after current Codex turn`() {
        runBlocking {
            val root = Files.createTempDirectory("codex-queue-test")
            val workspace = WorkspacePath(root)
            val projectId = "app"
            Files.createDirectories(workspace.projectRoot(projectId))
            val promptLog = root.resolve("prompts.log")
            val fakeCodex = root.resolve("fake-codex.sh")
            fakeCodex.writeText(
                """
                #!/usr/bin/env bash
                last="${'$'}{!#}"
                printf '%s\n' "${'$'}last" >> "$promptLog"
                printf '%s\n' '{"type":"thread.started","thread_id":"thread-1"}'
                printf '%s\n' '{"type":"turn.started"}'
                if [ "${'$'}last" = "first" ]; then sleep 0.3; fi
                printf '%s\n' '{"type":"turn.completed"}'
                """.trimIndent(),
            )
            fakeCodex.toFile().setExecutable(true)

            val manager = CodexSessionManager(
                testServerConfig(),
                workspace,
                LogHub(),
                codexCmdProvider = { fakeCodex.toString() },
            )

            manager.sendPrompt(projectId, "first")
            manager.sendPrompt(projectId, "second")

            repeat(30) {
                if (promptLog.exists() && promptLog.readLines().size >= 2) return@repeat
                delay(100)
            }

            promptLog.readLines() shouldBe listOf("first", "second")
        }
    }

    private fun testServerConfig(): ServerConfig =
        ServerConfig(
            server = ServerSection(),
            workspace = WorkspaceSection(),
            security = SecuritySection(),
            claude = ClaudeSection(),
            build = BuildSection(),
            git = GitSection(),
        )
}
