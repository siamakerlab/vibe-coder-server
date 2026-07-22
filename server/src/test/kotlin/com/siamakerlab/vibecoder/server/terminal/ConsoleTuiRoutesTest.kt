package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.dto.PromptImageDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ConsoleTuiRoutesTest {
    @Test
    fun `validatedConsoleTuiPromptText trims text before injection`() {
        validatedConsoleTuiPromptText(PromptRequestDto("  build the next feature\n")) shouldBe "build the next feature"
    }

    @Test
    fun `validatedConsoleTuiPromptText rejects blank prompt`() {
        val error = shouldThrow<ApiException> {
            validatedConsoleTuiPromptText(PromptRequestDto(" \n\t "))
        }

        error.statusCode shouldBe 400
        error.code shouldBe "bad_request"
        error.messageKey shouldBe "api.console.textRequired"
    }

    @Test
    fun `validatedConsoleTuiPromptText rejects oversized prompt by utf8 bytes`() {
        val oversized = "한".repeat((MAX_CONSOLE_TUI_PROMPT_BYTES / 3) + 1)

        val error = shouldThrow<ApiException> {
            validatedConsoleTuiPromptText(PromptRequestDto(oversized))
        }

        error.statusCode shouldBe 400
        error.code shouldBe "prompt_too_large"
        error.messageKey shouldBe "api.console.promptTooLarge"
    }

    @Test
    fun `validatedConsoleTuiPromptText rejects images because TUI injection is text only`() {
        val error = shouldThrow<ApiException> {
            validatedConsoleTuiPromptText(
                PromptRequestDto(
                    text = "inspect screenshot",
                    images = listOf(PromptImageDto(mediaType = "image/png", data = "abc")),
                ),
            )
        }

        error.statusCode shouldBe 400
        error.code shouldBe "images_unsupported"
        error.messageKey shouldBe "api.consoleTui.imagesUnsupported"
    }

    @Test
    fun `captureDirectPromptLines records normal terminal input on enter`() {
        val buffer = StringBuilder()

        captureDirectPromptLines(buffer, "build app") shouldBe emptyList()
        captureDirectPromptLines(buffer, "\r") shouldBe listOf("build app")
        buffer.toString() shouldBe ""
    }

    @Test
    fun `captureDirectPromptLines handles backspace and ansi sequences`() {
        val buffer = StringBuilder()

        captureDirectPromptLines(buffer, "builx\u007fd\u001b[A app\r") shouldBe listOf("build app")
        buffer.toString() shouldBe ""
    }

    @Test
    fun `captureDirectPromptLines strips bracketed paste markers`() {
        val buffer = StringBuilder()

        captureDirectPromptLines(buffer, "\u001b[200~run tests\u001b[201~\r") shouldBe listOf("run tests")
        buffer.toString() shouldBe ""
    }

    @Test
    fun `captureDirectPromptLines ignores terminal scroll and mouse escape artifacts`() {
        val buffer = StringBuilder()

        captureDirectPromptLines(buffer, "\u001b[5~\r") shouldBe emptyList()
        captureDirectPromptLines(buffer, "\\x1b[5~\r") shouldBe emptyList()
        captureDirectPromptLines(buffer, "[6~\r") shouldBe emptyList()
        captureDirectPromptLines(buffer, "\u001b[<64;12;20M\r") shouldBe emptyList()
        buffer.toString() shouldBe ""
    }

    @Test
    fun `captureDirectPromptLines drops private use noise before recording`() {
        val buffer = StringBuilder()

        captureDirectPromptLines(buffer, "\uE00A\uE00A \uE00B\uE00B\r") shouldBe emptyList()
        captureDirectPromptLines(buffer, "\uE00A\uE00A \uE00A\uE00A\uE00B\uE00B\uE00Aㅡㅓ\r") shouldBe emptyList()
        captureDirectPromptLines(buffer, "\uE00A빌드 계속\uE00B\r") shouldBe listOf("빌드 계속")
        buffer.toString() shouldBe ""
    }
}
