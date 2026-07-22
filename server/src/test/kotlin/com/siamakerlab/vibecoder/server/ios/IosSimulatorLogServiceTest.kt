package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

class IosSimulatorLogServiceTest {
    @Test
    fun `linux docker mode blocks recent logs without running simctl`() {
        val runner = RecordingRunner(emptyMap())

        val dto = IosSimulatorLogService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).recent("demo", "kr.codr.demo", "IPHONE-UDID")

        dto.mode shouldBe "linux"
        dto.ok shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode reads recent app logs by bundle id predicate`() {
        val runner = RecordingRunner(
            mapOf(
                "xcrun simctl spawn IPHONE-UDID log show --style compact --last 5m --predicate process == \"kr.codr.demo\"" to
                    CommandResult(0, "line-1\nline-2\n", ""),
            )
        )

        val dto = IosSimulatorLogService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).recent("demo", "kr.codr.demo", "IPHONE-UDID")

        dto.ok shouldBe true
        dto.lines shouldContainExactly listOf("line-1", "line-2")
        runner.commands.single() shouldContainExactly listOf(
            "xcrun",
            "simctl",
            "spawn",
            "IPHONE-UDID",
            "log",
            "show",
            "--style",
            "compact",
            "--last",
            "5m",
            "--predicate",
            "process == \"kr.codr.demo\"",
        )
    }

    @Test
    fun `ssh mode shell quotes predicate as one remote argument`() {
        val runner = RecordingRunner(
            mapOf(
                ssh(
                    listOf(
                        "xcrun",
                        "simctl",
                        "spawn",
                        "booted",
                        "log",
                        "show",
                        "--style",
                        "compact",
                        "--last",
                        "5m",
                        "--predicate",
                        "process == \"kr.codr.demo\"",
                    )
                ) to CommandResult(0, "remote-line\n", ""),
            )
        )

        val dto = IosSimulatorLogService(
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
        ).recent("demo", "kr.codr.demo", "booted")

        dto.mode shouldBe "mac_ssh"
        dto.ok shouldBe true
        dto.lines shouldContainExactly listOf("remote-line")
    }

    @Test
    fun `recent logs keep only bounded tail`() {
        val raw = (1..205).joinToString("\n") { "line-$it" } + "\n"
        val runner = RecordingRunner(
            mapOf(
                "xcrun simctl spawn IPHONE-UDID log show --style compact --last 5m --predicate process == \"kr.codr.demo\"" to
                    CommandResult(0, raw, ""),
            )
        )

        val dto = IosSimulatorLogService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
        ).recent("demo", "kr.codr.demo", "IPHONE-UDID")

        dto.lines.size shouldBe 200
        dto.lines.first() shouldBe "line-6"
        dto.lines.last() shouldBe "line-205"
    }

    @Test
    fun `invalid udid and bundle id are rejected before command construction`() {
        assertFailsWith<IllegalArgumentException> {
            IosSimulatorLogService(osNameProvider = { "Mac OS X" }).recent("demo", "kr.codr.demo", "; rm -rf /")
        }
        assertFailsWith<IllegalArgumentException> {
            IosSimulatorLogService(osNameProvider = { "Mac OS X" }).recent("demo", "bad bundle", "IPHONE-UDID")
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
        private fun ssh(remoteArgv: List<String>): String =
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new builder@mac-mini.local " +
                remoteArgv.joinToString(" ") { it.shellSingleQuoted() }
    }
}
