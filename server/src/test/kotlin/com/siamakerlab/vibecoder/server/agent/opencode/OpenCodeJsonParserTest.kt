package com.siamakerlab.vibecoder.server.agent.opencode

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class OpenCodeJsonParserTest {
    private val parser = OpenCodeJsonParser()

    @Test
    fun `parses step_start event`() {
        val ev = parser.parseLine(
            """{"type":"step_start","timestamp":1783212376153,"sessionID":"ses_abc","part":{"id":"prt_1","type":"step-start"}}""",
        )
        ev.shouldBeInstanceOf<OpenCodeEvent.StepStart>()
        ev.sessionId shouldBe "ses_abc"
    }

    @Test
    fun `parses text event`() {
        val ev = parser.parseLine(
            """{"type":"text","timestamp":1,"sessionID":"ses_abc","part":{"type":"text","text":"hello","time":{"start":1,"end":2}}}""",
        )
        ev.shouldBeInstanceOf<OpenCodeEvent.Text>()
        ev.text shouldBe "hello"
        ev.sessionId shouldBe "ses_abc"
    }

    @Test
    fun `parses completed tool_use event as ToolCompleted`() {
        val ev = parser.parseLine(
            """{"type":"tool_use","sessionID":"ses_abc","part":{"type":"tool","tool":"read","callID":"call_1","state":{"status":"completed","input":{"filePath":"/tmp/x"},"output":"<content>x</content>"}}}""",
        )
        val t = ev.shouldBeInstanceOf<OpenCodeEvent.ToolCompleted>()
        t.tool shouldBe "read"
        t.callId shouldBe "call_1"
        t.input.toString() shouldBe """{"filePath":"/tmp/x"}"""
        t.output.toString() shouldBe "\"<content>x</content>\""
    }

    @Test
    fun `parses pending tool_use event as ToolStarted`() {
        val ev = parser.parseLine(
            """{"type":"tool_use","sessionID":"ses_abc","part":{"type":"tool","tool":"bash","callID":"call_2","state":{"status":"pending","input":{"command":"ls"}}}}""",
        )
        val t = ev.shouldBeInstanceOf<OpenCodeEvent.ToolStarted>()
        t.tool shouldBe "bash"
        t.callId shouldBe "call_2"
    }

    @Test
    fun `parses step_finish with stop reason and tokens`() {
        val ev = parser.parseLine(
            """{"type":"step_finish","sessionID":"ses_abc","part":{"type":"step-finish","reason":"stop","tokens":{"total":9686,"input":135,"output":15,"reasoning":0,"cache":{"write":0,"read":9536}},"cost":0}}""",
        )
        val f = ev.shouldBeInstanceOf<OpenCodeEvent.StepFinish>()
        f.reason shouldBe "stop"
        f.tokens!!.total shouldBe 9686
        f.tokens.input shouldBe 135
        f.tokens.output shouldBe 15
        f.tokens.cacheRead shouldBe 9536
    }

    @Test
    fun `parses intermediate step_finish with tool-calls reason`() {
        val ev = parser.parseLine(
            """{"type":"step_finish","sessionID":"ses_abc","part":{"type":"step-finish","reason":"tool-calls","tokens":{"total":1,"input":1,"output":0,"reasoning":0,"cache":{"write":0,"read":0}}}}""",
        )
        val f = ev.shouldBeInstanceOf<OpenCodeEvent.StepFinish>()
        // tool-calls 는 turn 중간 step — [OpenCodeSessionManager] 가 turn 완료로 취급 X.
        f.reason shouldBe "tool-calls"
    }

    @Test
    fun `skips blank and non-JSON lines`() {
        parser.parseLine("") shouldBe null
        parser.parseLine("not json") shouldBe null
        parser.parseLine("   ") shouldBe null
    }

    @Test
    fun `unknown event type becomes Unknown`() {
        val ev = parser.parseLine(
            """{"type":"reasoning","sessionID":"ses_abc","part":{"type":"reasoning","text":"..."}}""",
        )
        ev.shouldBeInstanceOf<OpenCodeEvent.Unknown>()
        ev.sessionId shouldBe "ses_abc"
    }

    @Test
    fun `builds OpenCode exec args with model and resume session`() {
        buildOpenCodeExecArgs(
            cmd = "opencode",
            text = "hello",
            sessionId = "ses_abc",
            model = "zai-coding-plan/glm-5.2",
            dir = "/workspace/postgres-client",
        ) shouldBe listOf(
            "opencode",
            "run",
            "--format", "json",
            "--auto",
            "-m", "zai-coding-plan/glm-5.2",
            "--dir", "/workspace/postgres-client",
            "-s", "ses_abc",
            "hello",
        )
    }

    @Test
    fun `builds OpenCode exec args without model and session for first prompt`() {
        buildOpenCodeExecArgs(
            cmd = "opencode",
            text = "hello",
            sessionId = null,
            model = null,
            dir = "/workspace/postgres-client",
        ) shouldBe listOf(
            "opencode",
            "run",
            "--format", "json",
            "--auto",
            "--dir", "/workspace/postgres-client",
            "hello",
        )
    }
}
