package com.siamakerlab.vibecoder.shared.ws

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test

class WsFrameTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun `terminal input recordPrompt is optional for older clients`() {
        val frame = json.decodeFromString(
            WsFrame.serializer(),
            """{"type":"terminal_input","data":"\r"}""",
        ) as WsFrame.TerminalInput

        frame.data shouldBe "\r"
        frame.recordPrompt shouldBe null
    }

    @Test
    fun `terminal input can carry explicit prompt history text`() {
        val frame = json.decodeFromString(
            WsFrame.serializer(),
            """{"type":"terminal_input","data":"\r","recordPrompt":"build app"}""",
        ) as WsFrame.TerminalInput

        frame.data shouldBe "\r"
        frame.recordPrompt shouldBe "build app"
    }
}
