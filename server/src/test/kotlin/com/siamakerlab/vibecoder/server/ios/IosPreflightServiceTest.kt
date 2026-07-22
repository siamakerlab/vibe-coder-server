package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IosPreflightServiceTest {
    @Test
    fun `linux docker mode does not execute xcode commands`() {
        val runner = RecordingRunner(emptyMap())
        val dto = IosPreflightService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).check()

        dto.mode shouldBe "linux"
        dto.macAvailable shouldBe false
        dto.simulatorUiEnabled shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode parses xcode and simulator inventory`() {
        val simctlJson = """
            {
              "devicetypes": [
                {"name": "iPhone 17"},
                {"name": "iPad Pro 13-inch (M6)"},
                {"name": "Apple TV"}
              ]
            }
        """.trimIndent()
        val runner = RecordingRunner(
            mapOf(
                "/usr/bin/xcode-select -p" to CommandResult(0, "/Applications/Xcode.app/Contents/Developer\n", ""),
                "xcrun --find xcodebuild" to CommandResult(0, "/usr/bin/xcodebuild\n", ""),
                "xcodebuild -version" to CommandResult(0, "Xcode 18.0\nBuild version 18A1\n", ""),
                "xcrun --sdk iphoneos --show-sdk-version" to CommandResult(0, "26.0\n", ""),
                "xcrun --sdk iphonesimulator --show-sdk-version" to CommandResult(0, "26.0\n", ""),
                "xcrun simctl list devicetypes -j" to CommandResult(0, simctlJson, ""),
                "security find-identity -v -p codesigning" to CommandResult(
                    0,
                    """
                    1) 0123456789ABCDEF0123456789ABCDEF01234567 "Apple Development: Wody (TEAMID)"
                       1 valid identities found
                    """.trimIndent(),
                    "",
                ),
            )
        )

        val dto = IosPreflightService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).check()

        dto.mode shouldBe "mac_local"
        dto.macAvailable shouldBe true
        dto.xcodeAvailable shouldBe true
        dto.simctlAvailable shouldBe true
        dto.simulatorUiEnabled shouldBe true
        dto.xcodeVersion shouldBe "Xcode 18.0 / Build version 18A1"
        dto.iphoneDeviceTypes shouldContain "iPhone 17"
        dto.ipadDeviceTypes shouldContain "iPad Pro 13-inch (M6)"
        dto.codesigningIdentities shouldContain "0123456789ABCDEF0123456789ABCDEF01234567 \"Apple Development: Wody (TEAMID)\""
        dto.blockedReason shouldBe null
    }

    @Test
    fun `linux server can check remote mac agent when ssh agent is enabled`() {
        val simctlJson = """{"devicetypes":[{"name":"iPhone 17 Pro"}]}"""
        val runner = RecordingRunner(
            mapOf(
                ssh("/usr/bin/xcode-select -p") to CommandResult(0, "/Applications/Xcode.app/Contents/Developer\n", ""),
                ssh("xcrun --find xcodebuild") to CommandResult(0, "/usr/bin/xcodebuild\n", ""),
                ssh("xcodebuild -version") to CommandResult(0, "Xcode 18.0\n", ""),
                ssh("xcrun --sdk iphoneos --show-sdk-version") to CommandResult(0, "26.0\n", ""),
                ssh("xcrun --sdk iphonesimulator --show-sdk-version") to CommandResult(0, "26.0\n", ""),
                ssh("xcrun simctl list devicetypes -j") to CommandResult(0, simctlJson, ""),
                ssh("security find-identity -v -p codesigning") to CommandResult(0, "", ""),
            )
        )

        val dto = IosPreflightService(
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
        ).check()

        dto.mode shouldBe "mac_ssh"
        dto.macAvailable shouldBe true
        dto.xcodeAvailable shouldBe true
        dto.simctlAvailable shouldBe true
        dto.iphoneDeviceTypes shouldContain "iPhone 17 Pro"
    }

    @Test
    fun `ssh preflight reports unreachable agent separately`() {
        val runner = RecordingRunner(
            IosAgentCommand.entries.associate { command ->
                ssh(command.argv.joinToString(" ")) to CommandResult(255, "", "ssh: connect to host")
            }
        )

        val dto = IosPreflightService(
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
        ).check()

        dto.mode shouldBe "mac_ssh"
        dto.blockedReason shouldBe "agent_unreachable"
        dto.warnings shouldContain "agent_unreachable"
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
        private fun ssh(command: String): String =
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new builder@mac-mini.local " +
                command.split(" ").joinToString(" ") { "'$it'" }
    }
}
