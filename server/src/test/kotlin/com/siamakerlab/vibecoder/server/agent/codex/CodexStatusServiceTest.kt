package com.siamakerlab.vibecoder.server.agent.codex

import io.kotest.matchers.shouldBe
import org.junit.Test

class CodexStatusServiceTest {
    @Test
    fun `normalizes Codex TUI character stream with spaces`() {
        val raw = "Y\no\nu\n \nh\na\nv\ne\n \n2\n \nu\ns\na\ng\ne\n \nl\ni\nm\ni\nt\n \nr\ne\ns\ne\nt\ns\n \na\nv\na\ni\nl\na\nb\nl\ne\n.\n \nR\nu\nn\n \n/\nu\ns\na\ng\ne\n \nt\no\n \nu\ns\ne\n \no\nn\ne\n.\n"

        normalizeCodexCapture(raw) shouldBe
            "You have 2 usage limit resets available. Run /usage to use one."
    }

    @Test
    fun `restores already collapsed Codex usage reset sentence`() {
        val raw = "Youhave2usagelimitresetsavailable.Run/usagetouseone.\n/status\n"

        normalizeCodexCapture(raw) shouldBe
            "You have 2 usage limit resets available. Run /usage to use one."
    }

    @Test
    fun `removes appended status command from Codex usage summary`() {
        cleanCodexSummaryLine(
            "\u2022You have 2 usage limit resets available. Run /usage to use one./status",
        ) shouldBe
            "You have 2 usage limit resets available. Run /usage to use one."
    }

    @Test
    fun `keeps Codex reset availability as summary when status percentages are absent`() {
        val dto = parseCodexUsageCapture(
            "\u2022Youhave2usagelimitresetsavailable.Run/usagetouseone.\n/status\n",
        )

        dto.usagePercent shouldBe null
        dto.rateLimitResetAt shouldBe
            "You have 2 usage limit resets available. Run /usage to use one."
        dto.usageSummary shouldBe
            "You have 2 usage limit resets available. Run /usage to use one."
    }

    @Test
    fun `parses Codex status left percentages as used gauges`() {
        val raw = """
            >_ OpenAI Codex (v0.142.2)

            Context window:       18% left (214K used / 258K)
            5h limit:             [████████████░░░░░░░░] 58% left (resets 14:51)
            Weekly limit:         [███████████░░░░░░░░░] 56% left (resets 11:03 on 30 Jun)
            Warning:              limits may be stale - run /status again shortly.
        """.trimIndent()

        val dto = parseCodexUsageCapture(raw)

        dto.contextUsagePercent shouldBe 82
        dto.sessionUsagePercent shouldBe 42
        dto.weeklyUsagePercent shouldBe 44
        dto.usagePercent shouldBe 44
        dto.sessionResetAt shouldBe "14:51"
        dto.weeklyResetAt shouldBe "11:03 on 30 Jun"
        dto.rateLimitResetAt shouldBe "14:51"
        dto.usageSummary shouldBe "limits may be stale - run /status again shortly."
    }

    @Test
    fun `does not surface reset availability as summary when status gauges exist`() {
        val raw = """
            • You have 2 usage limit resets available. Run /usage to use one.
            /status

            5h limit:             [██████░░░░░░░░░░░░░░] 32% left (resets 14:51)
            Weekly limit:         [██████████░░░░░░░░░░] 52% left (resets 11:03 on 30 Jun)
        """.trimIndent()

        val dto = parseCodexUsageCapture(raw)

        dto.sessionUsagePercent shouldBe 68
        dto.weeklyUsagePercent shouldBe 48
        dto.usageSummary shouldBe null
    }
}
