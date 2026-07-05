package com.siamakerlab.vibecoder.server.agent.opencode

import io.kotest.matchers.shouldBe
import org.junit.Test

class OpenCodeParsingTest {
    // ── parseOpenCodeProvidersList ─────────────────────────────────────────────

    @Test
    fun `parses registered credential line from providers list`() {
        val raw = """
            ┌  Credentials ~/.local/share/opencode/auth.json
            │
            ●  Z.AI Coding Plan api
            │
            └  1 credentials
        """.trimIndent()

        val creds = parseOpenCodeProvidersList(raw)
        creds.size shouldBe 1
        creds[0].provider shouldBe "Z.AI Coding Plan"
        creds[0].type shouldBe "api"
    }

    @Test
    fun `handles multiple credentials`() {
        val raw = """
            ┌  Credentials
            ●  Z.AI Coding Plan api
            ●  OpenAI api
            └  2 credentials
        """.trimIndent()

        val creds = parseOpenCodeProvidersList(raw)
        creds.size shouldBe 2
        creds[0].provider shouldBe "Z.AI Coding Plan"
        creds[1].provider shouldBe "OpenAI"
    }

    @Test
    fun `returns empty list when no credentials`() {
        val raw = """
            ┌  Credentials ~/.local/share/opencode/auth.json
            │
            └  0 credentials
        """.trimIndent()
        parseOpenCodeProvidersList(raw) shouldBe emptyList()
    }

    @Test
    fun `strips ANSI escapes before parsing`() {
        val raw = "\u001B[32m●\u001B[0m  Z.AI Coding Plan api"
        val creds = parseOpenCodeProvidersList(raw)
        creds.size shouldBe 1
        creds[0].provider shouldBe "Z.AI Coding Plan"
    }

    // ── parseTokenCount ────────────────────────────────────────────────────────

    @Test
    fun `parses K and M suffixes to long`() {
        parseTokenCount("2.2M") shouldBe 2_200_000L
        parseTokenCount("19.1K") shouldBe 19_100L
        parseTokenCount("64.6M") shouldBe 64_600_000L
        parseTokenCount("1G") shouldBe 1_000_000_000L
    }

    @Test
    fun `parses plain integer token count`() {
        parseTokenCount("12345") shouldBe 12345L
    }

    @Test
    fun `returns null for unparseable token text`() {
        parseTokenCount("n/a") shouldBe null
        parseTokenCount("") shouldBe null
    }

    // ── parseOpenCodeStats ─────────────────────────────────────────────────────

    @Test
    fun `parses cost and tokens from stats box output`() {
        val raw = """
            ┌────────────────────────────────────────────────────────┐
            │                    COST & TOKENS                       │
            ├────────────────────────────────────────────────────────┤
            │Total Cost                                        ${'$'}0.00 │
            │Avg Cost/Day                                      ${'$'}0.00 │
            │Avg Tokens/Session                                 2.8M │
            │Median Tokens/Session                             19.1K │
            │Input                                              2.2M │
            │Output                                           189.1K │
            │Cache Read                                        64.6M │
            │Cache Write                                           0 │
            └────────────────────────────────────────────────────────┘
        """.trimIndent()

        val parsed = parseOpenCodeStats(raw)
        parsed.totalCost shouldBe "\$0.00"
        parsed.inputTokens shouldBe 2_200_000L
        parsed.outputTokens shouldBe 189_100L
        parsed.cacheReadTokens shouldBe 64_600_000L
        parsed.totalTokens shouldBe (2_200_000L + 189_100L + 64_600_000L)
        parsed.usageSummary!!.contains("tokens") shouldBe true
        parsed.usageSummary!!.contains("\$0.00") shouldBe true
    }

    @Test
    fun `returns empty parse for blank stats output`() {
        val parsed = parseOpenCodeStats("")
        parsed.totalCost shouldBe null
        parsed.inputTokens shouldBe null
        parsed.usageSummary shouldBe null
    }

    // ── withCredential ─────────────────────────────────────────────────────────

    @Test
    fun `credential updates dto loggedIn provider and available`() {
        val dto = com.siamakerlab.vibecoder.shared.dto.OpenCodeUsageDto(updatedAt = "now")
        val updated = dto.withCredential(OpenCodeAuthService.Credential("Z.AI Coding Plan", "api"))
        updated.loggedIn shouldBe true
        updated.available shouldBe true
        updated.provider shouldBe "Z.AI Coding Plan"
        updated.credentialType shouldBe "api"
    }

    @Test
    fun `null credential clears loggedIn and available`() {
        val dto = com.siamakerlab.vibecoder.shared.dto.OpenCodeUsageDto(
            updatedAt = "now", loggedIn = true, provider = "old", available = true,
        )
        val updated = dto.withCredential(null)
        updated.loggedIn shouldBe false
        updated.available shouldBe false
        updated.provider shouldBe null
    }

    // ── isZaiCodingPlanModel (v1.153.0 z.ai 강제 모드) ──────────────────────────

    @Test
    fun `zai coding plan model prefix is recognized`() {
        isZaiCodingPlanModel("zai-coding-plan/glm-5.2") shouldBe true
        isZaiCodingPlanModel("zai-coding-plan/glm-4.5-air") shouldBe true
    }

    @Test
    fun `zai coding plan check is case-insensitive`() {
        isZaiCodingPlanModel("ZAI-CODING-PLAN/glm-5.2") shouldBe true
    }

    @Test
    fun `non-zai models are rejected`() {
        isZaiCodingPlanModel("vllm-gemma4/cyankiwi/gemma-4-26B") shouldBe false
        isZaiCodingPlanModel("gpt-5") shouldBe false
        isZaiCodingPlanModel("default") shouldBe false
        isZaiCodingPlanModel("") shouldBe false
    }

    // ── rate-limit 분류 (v1.154.0) ─────────────────────────────────────────────

    @Test
    fun `classifies transient rate-limit phrases`() {
        isOpencodeRateLimitMessage("Server is temporarily limiting requests (rate limit).") shouldBe true
        isOpencodeRateLimitMessage("429 Too Many Requests") shouldBe true
        isOpencodeRateLimitMessage("overloaded — retry later") shouldBe true
    }

    @Test
    fun `classifies usage and quota limit phrases`() {
        isOpencodeUsageLimitMessage("You've reached your usage limit for this window.") shouldBe true
        isOpencodeUsageLimitMessage("spend limit reached") shouldBe true
        isOpencodeUsageLimitMessage("quota exceeded") shouldBe true
    }

    @Test
    fun `transient rate-limit is not misclassified as usage limit`() {
        isOpencodeUsageLimitMessage("rate limit exceeded — retry in 30s") shouldBe false
        isOpencodeUsageLimitMessage("429 Too Many Requests") shouldBe false
    }

    @Test
    fun `generic errors are neither rate nor usage limit`() {
        isOpencodeRateLimitMessage("network error") shouldBe false
        isOpencodeUsageLimitMessage("network error") shouldBe false
    }
}
