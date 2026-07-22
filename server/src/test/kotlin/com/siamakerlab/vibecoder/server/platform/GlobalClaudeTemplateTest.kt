package com.siamakerlab.vibecoder.server.platform

import io.kotest.matchers.shouldBe
import org.junit.Test

class GlobalClaudeTemplateTest {
    @Test
    fun `global claude templates stay platform common`() {
        val ko = resource("templates/container-global-claude.ko.md")
        val en = resource("templates/container-global-claude.en.md")
        val combined = "$ko\n$en"

        listOf(
            "Gradle",
            "Compose",
            "Flutter",
            "SwiftUI",
            "Xcode",
            "keystore",
            "Play Store",
            "Simulator",
        ).forEach { token ->
            combined.contains(token, ignoreCase = true) shouldBe false
        }
        combined.contains("project-local `CLAUDE.md`") shouldBe true
    }

    private fun resource(path: String): String {
        val url = Thread.currentThread().contextClassLoader.getResource(path)
            ?: error("missing resource: $path")
        return url.readText()
    }
}
