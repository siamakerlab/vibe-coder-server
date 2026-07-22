package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ConsoleTuiCommandBuilderTest {
    @Test
    fun `builds allowlisted provider commands without shell wrapping`() {
        val builder = ConsoleTuiCommandBuilder { projectId ->
            when (projectId) {
                "claude-app" -> "opus"
                "codex-app" -> "gpt-5-codex"
                "opencode-app" -> "zai-coding-plan/glm-5.2"
                else -> null
            }
        }

        builder.build("claude-app", AgentProvider.CLAUDE).argv shouldContainExactly listOf("claude", "--settings", """{"tui":"default"}""", "--model", "opus")
        builder.build("codex-app", AgentProvider.CODEX).argv shouldContainExactly listOf("codex", "--dangerously-bypass-approvals-and-sandbox", "--no-alt-screen", "-m", "gpt-5-codex")
        builder.build("opencode-app", AgentProvider.OPENCODE).argv shouldContainExactly listOf("opencode", "--auto", "--mini", "-m", "zai-coding-plan/glm-5.2")
    }

    @Test
    fun `omits default or blank model arguments`() {
        val defaultBuilder = ConsoleTuiCommandBuilder { "default" }
        val blankBuilder = ConsoleTuiCommandBuilder { "   " }

        defaultBuilder.build("app", AgentProvider.CLAUDE).argv shouldContainExactly listOf("claude", "--settings", """{"tui":"default"}""")
        blankBuilder.build("app", AgentProvider.OPENCODE).argv shouldContainExactly listOf("opencode", "--auto", "--mini")
    }

    @Test
    fun `display name reflects argv for operator visibility`() {
        val builder = ConsoleTuiCommandBuilder { "zai-coding-plan/glm-5.2" }

        builder.build("app", AgentProvider.OPENCODE).displayName shouldBe "opencode --auto --mini -m zai-coding-plan/glm-5.2"
    }

    @Test
    fun `claude display name masks settings payload`() {
        val builder = ConsoleTuiCommandBuilder(
            effectiveModel = { "opus" },
            claudeHookUrl = { _, sessionId -> "http://127.0.0.1:17880/internal/agent-events/claude?projectId=app&token=secret&sessionId=$sessionId" },
        )

        builder.build("app", AgentProvider.CLAUDE, runtimeSessionId = "sess1").displayName shouldBe "claude --settings {settings} --model opus"
    }

    @Test
    fun `claude resumes latest persisted session for project workdir`() {
        val claudeHome = Files.createTempDirectory("claude-home")
        val projectDir = claudeHome.resolve("projects").resolve("-workspace-app")
        Files.createDirectories(projectDir)
        val oldSession = projectDir.resolve("old-session.jsonl")
        val newSession = projectDir.resolve("new-session.jsonl")
        Files.writeString(oldSession, "{}\n")
        Files.writeString(newSession, "{}\n")
        Files.setLastModifiedTime(oldSession, FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newSession, FileTime.fromMillis(2_000))
        val builder = ConsoleTuiCommandBuilder(
            claudeHome = { claudeHome },
        )

        builder.build("app", AgentProvider.CLAUDE, workdir = Path.of("/workspace/app")).argv shouldContainExactly listOf(
            "claude",
            "--settings",
            """{"tui":"default"}""",
            "--resume",
            "new-session",
        )
    }

    @Test
    fun `claude fresh start does not resume persisted session`() {
        val claudeHome = Files.createTempDirectory("claude-home")
        val projectDir = claudeHome.resolve("projects").resolve("-workspace-app")
        Files.createDirectories(projectDir)
        Files.writeString(projectDir.resolve("existing-session.jsonl"), "{}\n")
        val builder = ConsoleTuiCommandBuilder(
            claudeHome = { claudeHome },
        )

        builder.build(
            "app",
            AgentProvider.CLAUDE,
            workdir = Path.of("/workspace/app"),
            resumePrevious = false,
        ).argv shouldContainExactly listOf("claude", "--settings", """{"tui":"default"}""")
    }

    @Test
    fun `claude omits resume when persisted session directory is absent`() {
        val claudeHome = Files.createTempDirectory("claude-home")
        val builder = ConsoleTuiCommandBuilder(
            claudeHome = { claudeHome },
        )

        builder.build("app", AgentProvider.CLAUDE, workdir = Path.of("/workspace/app")).argv shouldContainExactly listOf(
            "claude",
            "--settings",
            """{"tui":"default"}""",
        )
    }

    @Test
    fun `claude command can include local status hooks while keeping default tui mode`() {
        val builder = ConsoleTuiCommandBuilder(
            effectiveModel = { "default" },
            claudeHookUrl = { projectId, sessionId -> "http://127.0.0.1:17880/internal/agent-events/claude?projectId=$projectId&token=t1&sessionId=$sessionId" },
        )

        val argv = builder.build("app", AgentProvider.CLAUDE, runtimeSessionId = "sess1").argv

        argv shouldContain "--settings"
        val settings = argv[argv.indexOf("--settings") + 1]
        settings shouldContain """"tui":"default""""
        settings shouldContain """"UserPromptSubmit""""
        settings shouldContain """"PreToolUse""""
        settings shouldContain """"PostToolUseFailure""""
        settings shouldContain """"PermissionDenied""""
        settings shouldContain """"StopFailure""""
        settings shouldContain """"url":"http://127.0.0.1:17880/internal/agent-events/claude?projectId=app&token=t1&sessionId=sess1""""
    }
}
