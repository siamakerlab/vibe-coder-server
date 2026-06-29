package com.siamakerlab.vibecoder.server.agent.codex

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

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

    @Test fun `builds Codex exec args with global approval option before exec`() {
        buildCodexExecArgs(
            cmd = "codex",
            text = "hello",
            threadId = null,
            model = null,
        ) shouldBe listOf(
            "codex",
            "--ask-for-approval", "never",
            "exec",
            "--json",
            "--sandbox", "danger-full-access",
            "--skip-git-repo-check",
            "hello",
        )
    }

    @Test fun `builds Codex resume args after exec options`() {
        buildCodexExecArgs(
            cmd = "codex",
            text = "continue",
            threadId = "019f0b7e-3a17-7520-8447-dbd7e1cc958d",
            model = "gpt-5.5",
        ) shouldBe listOf(
            "codex",
            "--ask-for-approval", "never",
            "exec",
            "--json",
            "--sandbox", "danger-full-access",
            "--skip-git-repo-check",
            "--model", "gpt-5.5",
            "resume", "019f0b7e-3a17-7520-8447-dbd7e1cc958d",
            "continue",
        )
    }
}
