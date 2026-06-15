package com.siamakerlab.vibecoder.server.claude

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * v1.108.2 — `result` 프레임 파싱 계약 고정. 특히 사용량/요금 한도 종료 시 CLI 가 보내는
 * 모순 프레임(subtype="success" + is_error=true + result 에 직전 assistant 본문 복사)을
 * 어떻게 정규화하는지 회귀 방지한다:
 *   - 한도 메시지 → code="usage_limit" + 명료한 안내문(원문 echo 안 함 → 청록 assistant
 *     버블과의 중복 제거).
 *   - 한도 아님 + subtype="success" 모순 → code="error" (빨간 "success:" 오인 제거).
 *   - 정상 종료(is_error=false) → Done(reason=subtype).
 */
class ClaudeStreamParserResultTest {

    private val parser = ClaudeStreamParser()

    /** result 프레임은 usage 가 없으면 단일 이벤트. usage 동반 시 [UsageReport]가 앞에 붙어 2개. */
    private fun lastOf(line: String): ClaudeEvent = parser.parseLine(line).last()

    @Test fun `success subtype with is_error and spend-limit text normalizes to usage_limit`() {
        val line = """{"type":"result","subtype":"success","is_error":true,""" +
            """"result":"You've hit your monthly spend limit."}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.ErrorEvent>()
        ev.code shouldBe "usage_limit"
        // 원문을 그대로 echo 하지 않고(중복 제거) 명료한 한국어 안내로 대체.
        ev.message shouldContain "사용량 한도"
    }

    @Test fun `usage limit reached text is detected regardless of subtype`() {
        val line = """{"type":"result","subtype":"error_during_execution","is_error":true,""" +
            """"error":"5-hour limit reached. Resets at 3pm."}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.ErrorEvent>()
        ev.code shouldBe "usage_limit"
    }

    @Test fun `contradictory success plus is_error without limit text normalizes code to error`() {
        val line = """{"type":"result","subtype":"success","is_error":true,""" +
            """"result":"some unexpected failure happened"}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.ErrorEvent>()
        ev.code shouldBe "error"
        ev.message shouldContain "unexpected failure"
    }

    @Test fun `transient rate limit is not misclassified as usage limit`() {
        val line = """{"type":"result","subtype":"error","is_error":true,""" +
            """"error":"Server is temporarily limiting requests (rate limit)."}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.ErrorEvent>()
        // 일시 rate limit 은 usage_limit 으로 잡히면 안 된다(자동 재시도 경로가 따로 처리).
        ev.code shouldBe "error"
    }

    /**
     * v1.144.2 회귀 — Anthropic rate-limit 원문은 "(not your usage limit)" 라는 부정 문구를
     * 포함해 "usage"+"limit" 단순 매칭에 오탐됐다(사용자 보고: rate limit 인데 usage_limit 으로
     * 표시 + 자동 재시도 안 됨). 원문(code/message)이 보존되어야 [ClaudeSessionManager.isRateLimitError]
     * 가 "temporarily limiting" 을 감지해 자동 재시도 경로로 보낸다.
     */
    @Test fun `rate limit text containing not-your-usage-limit phrase is not usage limit`() {
        val line = """{"type":"result","subtype":"success","is_error":true,""" +
            """"error":"API Error: Server is temporarily limiting requests (not your usage limit) · Rate limited"}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.ErrorEvent>()
        ev.code shouldBe "error"
        // 원문이 보존되어야 isRateLimitError 가 "temporarily limiting" 을 감지할 수 있다.
        ev.message shouldContain "temporarily limiting"
    }

    @Test fun `normal success result yields Done`() {
        val line = """{"type":"result","subtype":"success","is_error":false,""" +
            """"result":"done"}"""
        val ev = lastOf(line)
        ev.shouldBeInstanceOf<ClaudeEvent.Done>()
        ev.reason shouldBe "success"
    }
}
