package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

class IosSimulatorControlServiceTest {
    @Test
    fun `linux docker mode blocks simulator control without running simctl`() {
        val runner = RecordingRunner(emptyMap())

        val dto = IosSimulatorControlService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).boot("IPHONE-UDID")

        dto.mode shouldBe "linux"
        dto.ok shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode boots simulator by udid`() {
        val runner = RecordingRunner(
            mapOf("xcrun simctl boot IPHONE-UDID" to CommandResult(0, "", ""))
        )

        val dto = IosSimulatorControlService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).boot("IPHONE-UDID")

        dto.ok shouldBe true
        dto.action shouldBe "boot"
        runner.commands.single() shouldContainExactly listOf("xcrun", "simctl", "boot", "IPHONE-UDID")
    }

    @Test
    fun `ssh mode wraps shutdown command`() {
        val runner = RecordingRunner(
            mapOf(
                ssh("xcrun simctl shutdown booted") to CommandResult(0, "", ""),
            )
        )

        val dto = IosSimulatorControlService(
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
        ).shutdown("booted")

        dto.mode shouldBe "mac_ssh"
        dto.ok shouldBe true
    }

    @Test
    fun `invalid udid is rejected before command construction`() {
        assertFailsWith<IllegalArgumentException> {
            IosSimulatorControlService(osNameProvider = { "Mac OS X" }).boot("; rm -rf /")
        }
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
