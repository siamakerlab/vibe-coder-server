package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IosSimulatorInventoryServiceTest {
    @Test
    fun `linux docker mode does not execute simctl`() {
        val runner = RecordingRunner(emptyMap())

        val dto = IosSimulatorInventoryService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).list()

        dto.mode shouldBe "linux"
        dto.simulatorUiEnabled shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local inventory parses available iphone and ipad devices`() {
        val runner = RecordingRunner(
            mapOf(
                "xcrun simctl list -j devices available" to CommandResult(0, SIMCTL_DEVICES_JSON, ""),
            )
        )

        val dto = IosSimulatorInventoryService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).list()

        dto.mode shouldBe "mac_local"
        dto.simulatorUiEnabled shouldBe true
        dto.blockedReason shouldBe null
        dto.devices.map { it.name } shouldContain "iPhone 17"
        dto.devices.map { it.name } shouldContain "iPad Pro 13-inch (M6)"
        dto.devices.any { it.name == "Unavailable iPhone" } shouldBe false
        dto.devices.first { it.name == "iPhone 17" }.kind shouldBe "iphone"
        dto.devices.first { it.name == "iPad Pro 13-inch (M6)" }.kind shouldBe "ipad"
    }

    @Test
    fun `linux server can query remote mac simulator inventory`() {
        val runner = RecordingRunner(
            mapOf(
                ssh("xcrun simctl list -j devices available") to CommandResult(0, SIMCTL_DEVICES_JSON, ""),
            )
        )

        val dto = IosSimulatorInventoryService(
            runner = runner,
            agentConfigProvider = {
                IosAgentSection(
                    enabled = true,
                    mode = "ssh",
                    host = "mac-mini.local",
                    port = 2222,
                    user = "builder",
                )
            },
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).list()

        dto.mode shouldBe "mac_ssh"
        dto.simulatorUiEnabled shouldBe true
        dto.devices.map { it.udid } shouldContain "IPHONE-UDID"
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

    companion object {
        private val SIMCTL_DEVICES_JSON = """
            {
              "devices": {
                "com.apple.CoreSimulator.SimRuntime.iOS-26-0": [
                  {
                    "name": "iPhone 17",
                    "udid": "IPHONE-UDID",
                    "state": "Shutdown",
                    "isAvailable": true,
                    "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
                  },
                  {
                    "name": "Unavailable iPhone",
                    "udid": "UNAVAILABLE-UDID",
                    "state": "Shutdown",
                    "isAvailable": false,
                    "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-Old"
                  },
                  {
                    "name": "iPad Pro 13-inch (M6)",
                    "udid": "IPAD-UDID",
                    "state": "Booted",
                    "isAvailable": true,
                    "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M6"
                  }
                ]
              }
            }
        """.trimIndent()

        private fun ssh(command: String): String =
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new builder@mac-mini.local " + "export PATH=\"/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:\$PATH\"; " +
                command.split(" ").joinToString(" ") { "'$it'" }
    }
}
