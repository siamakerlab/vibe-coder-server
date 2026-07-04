package com.siamakerlab.vibecoder.server.agent.codex

import io.kotest.matchers.shouldBe
import org.junit.Test

class CodexRateLimitClassificationTest {
    // ── isCodexRateLimitMessage ────────────────────────────────────────────────

    @Test
    fun `classifies transient rate-limit phrases`() {
        isCodexRateLimitMessage("Server is temporarily limiting requests (rate limit).") shouldBe true
        isCodexRateLimitMessage("rate limit exceeded") shouldBe true
        isCodexRateLimitMessage("rate-limit: please retry") shouldBe true
        isCodexRateLimitMessage("rate_limit reached") shouldBe true
        isCodexRateLimitMessage("429 Too Many Requests") shouldBe true
        isCodexRateLimitMessage("Too many requests, slow down") shouldBe true
        isCodexRateLimitMessage("The server is overloaded") shouldBe true
    }

    @Test
    fun `rate limit is case-insensitive`() {
        isCodexRateLimitMessage("RATE LIMIT") shouldBe true
        isCodexRateLimitMessage("Overloaded") shouldBe true
    }

    @Test
    fun `not your usage limit phrase counts as transient rate limit`() {
        // Claude v1.144.2 회귀: "(not your usage limit)" 부정 문구는 일시 rate-limit 신호.
        isCodexRateLimitMessage("Rate limit (not your usage limit). Retry in 30s.") shouldBe true
    }

    @Test
    fun `non-rate-limit error messages are not classified as rate limit`() {
        isCodexRateLimitMessage("network error: connection reset") shouldBe false
        isCodexRateLimitMessage("internal server error") shouldBe false
        isCodexRateLimitMessage("") shouldBe false
    }

    // ── isCodexUsageLimitMessage ───────────────────────────────────────────────

    @Test
    fun `classifies usage and quota limit phrases`() {
        isCodexUsageLimitMessage("You've reached your usage limit for this window.") shouldBe true
        isCodexUsageLimitMessage("Spend limit reached") shouldBe true
        isCodexUsageLimitMessage("quota exceeded — upgrade your plan") shouldBe true
        isCodexUsageLimitMessage("plan limit reached") shouldBe true
        isCodexUsageLimitMessage("reached your limit of requests") shouldBe true
        isCodexUsageLimitMessage("billing issue: payment required") shouldBe true
    }

    @Test
    fun `usage limit is case-insensitive`() {
        isCodexUsageLimitMessage("USAGE LIMIT REACHED") shouldBe true
        isCodexUsageLimitMessage("QUOTA Exceeded") shouldBe true
    }

    @Test
    fun `transient rate-limit signal is not misclassified as usage limit`() {
        // 가장 중요한 분기 — 재시도 의미가 정반대이므로 오탐이 치명적.
        isCodexUsageLimitMessage("Server is temporarily limiting requests (rate limit).") shouldBe false
        isCodexUsageLimitMessage("429 Too Many Requests") shouldBe false
        isCodexUsageLimitMessage("Rate limit (not your usage limit). Retry in 30s.") shouldBe false
    }

    @Test
    fun `generic error messages are neither rate nor usage limit`() {
        isCodexUsageLimitMessage("network error: connection reset") shouldBe false
        isCodexUsageLimitMessage("internal server error") shouldBe false
        isCodexUsageLimitMessage("") shouldBe false
    }
}
