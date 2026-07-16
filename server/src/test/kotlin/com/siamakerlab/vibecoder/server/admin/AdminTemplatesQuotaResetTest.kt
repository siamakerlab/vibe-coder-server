package com.siamakerlab.vibecoder.server.admin

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AdminTemplatesQuotaResetTest {
    private val zone: ZoneId = ZoneId.systemDefault()

    @Test
    fun `formats Claude reset time with date in Korean`() {
        val now = ZonedDateTime.of(2026, 7, 18, 21, 0, 0, 0, zone)

        AdminTemplates.fmtQuotaReset("Resets 10:49pm (Asia/Seoul)", "ko", now) shouldBe
            "7월 18일 22:49"
    }

    @Test
    fun `formats Codex weekly reset with day month wording in Korean`() {
        val now = ZonedDateTime.of(2026, 6, 29, 9, 0, 0, 0, zone)

        AdminTemplates.fmtQuotaReset("11:03 on 30 Jun", "ko", now) shouldBe
            "6월 30일 11:03"
    }

    @Test
    fun `time only reset rolls forward to tomorrow when already passed`() {
        val now = ZonedDateTime.of(2026, 7, 18, 23, 0, 0, 0, zone)

        AdminTemplates.fmtQuotaReset("22:49", "ko", now) shouldBe
            "7월 19일 22:49"
    }

    @Test
    fun `keeps English quota reset compact`() {
        val now = ZonedDateTime.of(2026, 7, 18, 21, 0, 0, 0, zone)

        AdminTemplates.fmtQuotaReset("Jul 18, 10:49pm", "en", now) shouldBe
            "Jul 18, 10:49 PM"
    }
}
