package com.siamakerlab.vibecoder.server.ios

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class IosSimulatorRunServiceTest {
    @Test
    fun `linux docker mode blocks run without simctl`() {
        val root = Files.createTempDirectory("ios-run-linux")
        val runner = RecordingRunner(emptyMap())

        val dto = IosSimulatorRunService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).run("demo", root, "kr.codr.demo", "IPHONE-UDID")

        dto.ok shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode installs launches and screenshots latest debug app`() {
        val root = Files.createTempDirectory("ios-run-local")
        val app = root.resolve(".vibecoder-ios-build/ios-debug/DerivedData/Build/Products/Debug-iphonesimulator/Demo.app")
        Files.createDirectories(app)
        val runner = RecordingRunner(
            mapOf(
                "xcrun simctl install IPHONE-UDID $app" to CommandResult(0, "", ""),
                "xcrun simctl launch IPHONE-UDID kr.codr.demo" to CommandResult(0, "", ""),
                "xcrun simctl io IPHONE-UDID screenshot ${root.resolve(".vibecoder-ios-build/simulator/latest-screenshot.png")}" to CommandResult(0, "", ""),
            )
        )

        val dto = IosSimulatorRunService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).run("demo", root, "kr.codr.demo", "IPHONE-UDID")

        dto.ok shouldBe true
        dto.installed shouldBe true
        dto.launched shouldBe true
        dto.screenshotCaptured shouldBe true
        runner.commands[0] shouldContainExactly listOf("xcrun", "simctl", "install", "IPHONE-UDID", app.toString())
        runner.commands[1] shouldContainExactly listOf("xcrun", "simctl", "launch", "IPHONE-UDID", "kr.codr.demo")
        runner.commands[2] shouldContainExactly listOf(
            "xcrun",
            "simctl",
            "io",
            "IPHONE-UDID",
            "screenshot",
            root.resolve(".vibecoder-ios-build/simulator/latest-screenshot.png").toString(),
        )
    }

    @Test
    fun `run blocks when debug simulator app is missing`() {
        val root = Files.createTempDirectory("ios-run-missing")

        val dto = IosSimulatorRunService(
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).run("demo", root, "kr.codr.demo", "IPHONE-UDID")

        dto.ok shouldBe false
        dto.blockedReason shouldBe "debug_app_not_found"
    }

    private class RecordingRunner(
        private val responses: Map<String, CommandResult>,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return responses[command.joinToString(" ")] ?: CommandResult(127, "", "not found")
        }
    }
}
