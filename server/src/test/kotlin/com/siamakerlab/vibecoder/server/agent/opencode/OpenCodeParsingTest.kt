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
}
