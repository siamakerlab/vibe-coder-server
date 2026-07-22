package com.siamakerlab.vibecoder.server.terminal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ConsoleTuiHistoryIngestServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses Claude assistant transcript text and usage`() {
        val raw = """
            {
              "type": "assistant",
              "uuid": "turn-1",
              "sessionId": "claude-session-1",
              "message": {
                "role": "assistant",
                "content": [
                  { "type": "text", "text": "작업 요약:" },
                  { "type": "tool_use", "id": "tool-1" },
                  { "type": "text", "text": "- 완료" }
                ],
                "usage": {
                  "input_tokens": 12,
                  "output_tokens": 34
                }
              }
            }
        """.trimIndent()

        val turn = parseClaudeAssistantTranscriptLine(
            obj = json.parseToJsonElement(raw).jsonObject,
            raw = raw,
            fallbackSessionId = "fallback-session",
        )

        turn!!.sessionId shouldBe "fallback-session"
        turn.importKey shouldBe "claude:claude-session-1:turn-1"
        turn.text shouldBe "작업 요약:\n\n- 완료"
        turn.tokensIn shouldBe 12
        turn.tokensOut shouldBe 34
    }

    @Test
    fun `ignores non assistant transcript lines`() {
        val raw = """
            {
              "type": "user",
              "message": {
                "role": "user",
                "content": [
                  { "type": "text", "text": "hello" }
                ]
              }
            }
        """.trimIndent()

        parseClaudeAssistantTranscriptLine(
            obj = json.parseToJsonElement(raw).jsonObject,
            raw = raw,
            fallbackSessionId = null,
        ).shouldBeNull()
    }

    @Test
    fun `uses fallback session id when Claude line has no session id`() {
        val raw = """
            {
              "type": "assistant",
              "message": {
                "id": "msg-1",
                "role": "assistant",
                "content": [
                  { "type": "text", "text": "done" }
                ]
              }
            }
        """.trimIndent()

        val turn = parseClaudeAssistantTranscriptLine(
            obj = json.parseToJsonElement(raw).jsonObject,
            raw = raw,
            fallbackSessionId = "tui-session",
        )

        turn!!.sessionId shouldBe "tui-session"
        turn.importKey shouldBe "claude:tui-session:msg-1"
        turn.text shouldBe "done"
    }

    @Test
    fun `parses OpenCode assistant text row`() {
        val raw = """
            {
              "partId": "part-1",
              "sessionId": "ses-1",
              "messageId": "msg-1",
              "text": "작업 요약:\n- 완료",
              "tokensIn": 123,
              "tokensOut": 45,
              "raw": "{\"type\":\"text\",\"text\":\"작업 요약\"}"
            }
        """.trimIndent()

        val turn = parseOpenCodeAssistantRow(raw)

        turn!!.sessionId shouldBe "ses-1"
        turn.importKey shouldBe "opencode:ses-1:part-1"
        turn.text shouldBe "작업 요약:\n- 완료"
        turn.tokensIn shouldBe 123
        turn.tokensOut shouldBe 45
    }

    @Test
    fun `ignores OpenCode blank text row`() {
        parseOpenCodeAssistantRow(
            """{"partId":"part-1","sessionId":"ses-1","text":"   "}""",
        ).shouldBeNull()
    }

    @Test
    fun `parses OpenCode stream error log line for matching session`() {
        val line = """
            timestamp=2026-07-20T09:34:05.812Z level=ERROR run=658d0fc8 message="stream error" providerID=zai-coding-plan modelID=glm-5.2 session.id=ses-1 small=false agent=build mode=primary error.error="AI_APICallError: Weekly/Monthly Limit Exhausted. Your limit will reset at 2026-07-25 21:49:08"
        """.trimIndent()

        val turn = parseOpenCodeLogErrorLine(line, allowedSessionIds = setOf("ses-1"))

        turn!!.sessionId shouldBe "ses-1"
        turn.importKey.startsWith("opencode-log:ses-1:") shouldBe true
        turn.text shouldBe "OpenCode provider error (zai-coding-plan/glm-5.2): AI_APICallError: Weekly/Monthly Limit Exhausted. Your limit will reset at 2026-07-25 21:49:08"
        turn.tokensIn.shouldBeNull()
        turn.tokensOut.shouldBeNull()
    }

    @Test
    fun `ignores OpenCode stream error log line for unrelated session`() {
        parseOpenCodeLogErrorLine(
            """timestamp=2026-07-20T09:34:05.812Z level=ERROR message="stream error" session.id=ses-other error.error="quota"""",
            allowedSessionIds = setOf("ses-1"),
        ).shouldBeNull()
    }

    @Test
    fun `parses Codex assistant rollout message`() {
        val raw = """
            {
              "timestamp": "2026-07-19T08:06:33.764Z",
              "type": "response_item",
              "payload": {
                "type": "message",
                "id": "msg_1",
                "role": "assistant",
                "content": [
                  { "type": "output_text", "text": "작업 요약:" },
                  { "type": "output_text", "text": "- 완료" }
                ],
                "phase": "final_answer"
              }
            }
        """.trimIndent()

        val turn = parseCodexAssistantRolloutLine(raw, fallbackSessionId = "codex-thread")

        turn!!.sessionId shouldBe "codex-thread"
        turn.importKey shouldBe "codex:codex-thread:msg_1"
        turn.text shouldBe "작업 요약:\n\n- 완료"
    }

    @Test
    fun `uses Codex native thread for import key while storing TUI session id`() {
        val raw = """
            {
              "type": "response_item",
              "payload": {
                "type": "message",
                "id": "msg_1",
                "role": "assistant",
                "content": [
                  { "type": "output_text", "text": "done" }
                ]
              }
            }
        """.trimIndent()

        val turn = parseCodexAssistantRolloutLine(
            line = raw,
            fallbackSessionId = "tui-session",
            nativeSessionKey = "codex-thread",
        )

        turn!!.sessionId shouldBe "tui-session"
        turn.importKey shouldBe "codex:codex-thread:msg_1"
        turn.text shouldBe "done"
    }

    @Test
    fun `ignores Codex non assistant rollout events`() {
        parseCodexAssistantRolloutLine(
            """{"type":"event_msg","payload":{"type":"agent_message","message":"commentary"}}""",
            fallbackSessionId = "codex-thread",
        ).shouldBeNull()
    }

    @Test
    fun `ignores Codex commentary assistant rollout message`() {
        parseCodexAssistantRolloutLine(
            """
            {
              "type": "response_item",
              "payload": {
                "type": "message",
                "id": "msg_1",
                "role": "assistant",
                "phase": "commentary",
                "content": [{ "type": "output_text", "text": "진행 중입니다." }]
              }
            }
            """.trimIndent(),
            fallbackSessionId = "codex-thread",
        ).shouldBeNull()
    }
}
