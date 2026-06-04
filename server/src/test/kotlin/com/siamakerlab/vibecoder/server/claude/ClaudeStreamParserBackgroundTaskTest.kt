package com.siamakerlab.vibecoder.server.claude

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * v1.99.2 — 백그라운드 작업 lifecycle 파싱 계약 고정. ClaudeSessionManager 가
 * outstandingBgTasks 추적(started→추가, terminal→제거)에 의존하므로, parser 가
 * 운영에서 실제로 흘러온 JSON 을 정확한 [ClaudeEvent.BackgroundTask] 로 변환하는지
 * 회귀 방지한다. 라인은 운영 DB(conversation_turns.raw)에서 채취한 실제 프레임.
 */
class ClaudeStreamParserBackgroundTaskTest {

    private val parser = ClaudeStreamParser()

    private fun one(line: String): ClaudeEvent {
        val events = parser.parseLine(line)
        events.size shouldBe 1
        return events.first()
    }

    @Test fun `task_started yields BackgroundTask started with taskId and taskType`() {
        val line = """{"type":"system","subtype":"task_started","task_id":"buhilnkdu",""" +
            """"tool_use_id":"toolu_015","description":"Build + test","task_type":"local_bash",""" +
            """"session_id":"67728f58"}"""
        val ev = one(line)
        ev.shouldBeInstanceOf<ClaudeEvent.BackgroundTask>()
        ev.kind shouldBe "started"
        ev.taskId shouldBe "buhilnkdu"
        ev.taskType shouldBe "local_bash"
        ev.status shouldBe null
    }

    @Test fun `task_updated carries patch_status completed`() {
        val line = """{"type":"system","subtype":"task_updated","task_id":"bogrc2lcl",""" +
            """"patch":{"status":"completed","end_time":1780312534881},"session_id":"67728f58"}"""
        val ev = one(line)
        ev.shouldBeInstanceOf<ClaudeEvent.BackgroundTask>()
        ev.kind shouldBe "updated"
        ev.taskId shouldBe "bogrc2lcl"
        ev.status shouldBe "completed"
    }

    @Test fun `task_notification carries status completed`() {
        val line = """{"type":"system","subtype":"task_notification","task_id":"buomd4v9r",""" +
            """"tool_use_id":"toolu_01V","status":"completed","output_file":"","summary":"Re-verify"}"""
        val ev = one(line)
        ev.shouldBeInstanceOf<ClaudeEvent.BackgroundTask>()
        ev.kind shouldBe "notification"
        ev.taskId shouldBe "buomd4v9r"
        ev.status shouldBe "completed"
    }

    @Test fun `task event without task_id degrades to Unknown`() {
        val line = """{"type":"system","subtype":"task_started","description":"no id"}"""
        one(line).shouldBeInstanceOf<ClaudeEvent.Unknown>()
    }

    @Test fun `result success is a Done turn end (not a BackgroundTask)`() {
        val line = """{"type":"result","subtype":"success","result":"ok"}"""
        val ev = one(line)
        ev.shouldBeInstanceOf<ClaudeEvent.Done>()
        ev.reason shouldBe "success"
    }
}
