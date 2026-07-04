package com.siamakerlab.vibecoder.server.automation

import io.kotest.matchers.shouldBe
import org.junit.Test

class ScheduledPromptLimitReleasedTest {
    @Test
    fun `treats large drop from baseline as limit release`() {
        // 예약 생성 시 baseline=80 이었고 현재 40 → 40p 하락 = 해제.
        isLimitReleasedAgainstBaseline(currentPercent = 40, baselinePercent = 80) shouldBe true
    }

    @Test
    fun `does not release when drop is below the threshold`() {
        // 20p 하락(=RESET_DROP_POINTS 미만) + 절대값 60% → 해제 아님.
        isLimitReleasedAgainstBaseline(currentPercent = 60, baselinePercent = 80) shouldBe false
    }

    @Test
    fun `treats low absolute usage as release even without baseline`() {
        // baseline 모름(codex 캡처 전 예약 등)이라도 절대 15% 이하면 해제.
        isLimitReleasedAgainstBaseline(currentPercent = 10, baselinePercent = null) shouldBe true
    }

    @Test
    fun `exact drop threshold counts as release`() {
        // 30p 정확히 하락(>= RESET_DROP_POINTS) → 해제.
        isLimitReleasedAgainstBaseline(currentPercent = 50, baselinePercent = 80) shouldBe true
    }

    @Test
    fun `exact low threshold counts as release`() {
        // 절대 15% (<= RESET_LOW_PERCENT) → 해제.
        isLimitReleasedAgainstBaseline(currentPercent = 15, baselinePercent = null) shouldBe true
    }

    @Test
    fun `keeps pending when usage is high and baseline unknown`() {
        // baseline 도 없고 절대값도 높으면 보수적으로 보류.
        isLimitReleasedAgainstBaseline(currentPercent = 70, baselinePercent = null) shouldBe false
    }
}
