package com.siamakerlab.vibecoder.server.agent.codex

import com.siamakerlab.vibecoder.server.agent.AgentUsageSnapshot
import com.siamakerlab.vibecoder.server.config.CodexUsageSection
import com.siamakerlab.vibecoder.shared.dto.CodexUsageDto
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

    @Test
    fun `parses newer Codex session label variants`() {
        val raw = """
            Context window:       91% left
            5-hour limit:         [████████░░░░░░░░░░░░] 40% left (resets 17:20)
            Weekly limit:         [██████████░░░░░░░░░░] 52% left (resets 11:03 on 30 Jun)
        """.trimIndent()

        val dto = parseCodexUsageCapture(raw)

        dto.sessionUsagePercent shouldBe 60
        dto.weeklyUsagePercent shouldBe 48
        dto.sessionResetAt shouldBe "17:20"
    }

    @Test
    fun `uses unknown non-weekly limit as session fallback when weekly is present`() {
        val raw = """
            Session quota:         91% left
            Rolling limit:         [████████░░░░░░░░░░░░] 40% left (resets 17:20)
            Weekly limit:         [██████████░░░░░░░░░░] 52% left (resets 11:03 on 30 Jun)
        """.trimIndent()

        val dto = parseCodexUsageCapture(raw)

        dto.sessionUsagePercent shouldBe 60
        dto.weeklyUsagePercent shouldBe 48
        dto.usagePercent shouldBe 60
    }

    @Test
    fun `parses session limit wording`() {
        val raw = """
            Session limit:         [████████████░░░░░░░░] 58% left (resets 14:51)
            Weekly limit:         [███████████░░░░░░░░░] 56% left (resets 11:03 on 30 Jun)
        """.trimIndent()

        val dto = parseCodexUsageCapture(raw)

        dto.sessionUsagePercent shouldBe 42
        dto.weeklyUsagePercent shouldBe 44
    }

    // ── v1.147.0 — AgentUsageProvider / 임계치 transition 순수 함수 ────────────────

    private fun dto(
        session: Int? = null,
        weekly: Int? = null,
        legacy: Int? = null,
    ) = CodexUsageDto(
        updatedAt = "2026-07-05T00:00:00Z",
        usagePercent = legacy,
        sessionUsagePercent = session,
        weeklyUsagePercent = weekly,
    )

    @Test
    fun `effective percent prefers the larger of session and weekly`() {
        codexUsageEffectivePercent(dto(session = 42, weekly = 60)) shouldBe 60
        codexUsageEffectivePercent(dto(session = 70, weekly = 44)) shouldBe 70
    }

    @Test
    fun `effective percent falls back to legacy usagePercent when session and weekly are null`() {
        codexUsageEffectivePercent(dto(legacy = 33)) shouldBe 33
    }

    @Test
    fun `effective percent returns null when no gauge is observed`() {
        codexUsageEffectivePercent(dto()) shouldBe null
    }

    @Test
    fun `usage snapshot forwards observed session and weekly percents`() {
        val snap = codexUsageSnapshotFromDto(dto(session = 42, weekly = 60))
        snap shouldBe AgentUsageSnapshot(sessionUsagePercent = 42, weeklyUsagePercent = 60)
    }

    @Test
    fun `usage snapshot falls back to legacy gauge when session and weekly are absent`() {
        val snap = codexUsageSnapshotFromDto(dto(legacy = 33))
        snap shouldBe AgentUsageSnapshot(sessionUsagePercent = 33, weeklyUsagePercent = 33)
    }

    @Test
    fun `usage snapshot returns null when nothing is observed yet`() {
        codexUsageSnapshotFromDto(dto()) shouldBe null
    }

    private val cfg = CodexUsageSection(warnThresholdPercent = 80, criticalThresholdPercent = 95)

    @Test
    fun `alert transition is BelowThreshold when usage is under warn`() {
        codexUsageAlertTransition(70, prior = null, cfg) shouldBe
            CodexUsageAlertDecision.BelowThreshold
    }

    @Test
    fun `alert transition fires warn on first threshold entry`() {
        val decision = codexUsageAlertTransition(82, prior = null, cfg)
        decision.shouldBeInstanceOf<CodexUsageAlertDecision.Fire>()
        decision.level shouldBe "warn"
    }

    @Test
    fun `alert transition fires critical when escalating from warn`() {
        val decision = codexUsageAlertTransition(96, prior = "warn", cfg)
        decision.shouldBeInstanceOf<CodexUsageAlertDecision.Fire>()
        decision.level shouldBe "critical"
    }

    @Test
    fun `alert transition does not re-fire on the same level`() {
        val decision = codexUsageAlertTransition(85, prior = "warn", cfg)
        decision.shouldBeInstanceOf<CodexUsageAlertDecision.NoFire>()
        decision.level shouldBe "warn"
    }

    @Test
    fun `alert transition does not re-fire critical once already at critical`() {
        val decision = codexUsageAlertTransition(97, prior = "critical", cfg)
        decision.shouldBeInstanceOf<CodexUsageAlertDecision.NoFire>()
        decision.level shouldBe "critical"
    }

    @Test
    fun `alert transition drops below threshold after a prior alert`() {
        // prior=warm 인 상태에서 usage 가 warn 미만으로 떨어지면 BelowThreshold → 모니터가 reset.
        codexUsageAlertTransition(60, prior = "warn", cfg) shouldBe
            CodexUsageAlertDecision.BelowThreshold
    }
}
