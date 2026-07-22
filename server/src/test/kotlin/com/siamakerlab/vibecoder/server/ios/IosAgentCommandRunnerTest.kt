package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration

class IosAgentCommandRunnerTest {
    @Test
    fun `local mode returns allowlisted argv directly`() {
        val runner = IosAgentCommandRunner(IosAgentSection(mode = "local"))

        runner.buildCommand(IosAgentCommand.XCODEBUILD_VERSION) shouldContainExactly listOf(
            "xcodebuild",
            "-version",
        )
    }

    @Test
    fun `ssh mode wraps allowlisted command with batch ssh`() {
        val runner = IosAgentCommandRunner(
            IosAgentSection(
                enabled = true,
                mode = "ssh",
                host = "mac-mini.local",
                port = 2200,
                user = "builder",
            )
        )

        runner.buildCommand(IosAgentCommand.SIMULATOR_DEVICE_TYPES) shouldContainExactly listOf(
            "ssh",
            "-p",
            "2200",
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "builder@mac-mini.local",
            "'xcrun' 'simctl' 'list' 'devicetypes' '-j'",
        )
    }

    @Test
    fun `simulator devices inventory command is allowlisted`() {
        val runner = IosAgentCommandRunner(IosAgentSection(mode = "local"))

        runner.buildCommand(IosAgentCommand.SIMULATOR_DEVICES) shouldContainExactly listOf(
            "xcrun",
            "simctl",
            "list",
            "-j",
            "devices",
            "available",
        )
    }

    @Test
    fun `shell quote escapes single quotes`() {
        "a'b".shellSingleQuoted() shouldBe "'a'\"'\"'b'"
    }

    @Test
    fun `process runner drains large stdout before process exit`() {
        val script = """
            for i in ${'$'}(seq 1 20000); do
              printf 'line-%05d abcdefghijklmnopqrstuvwxyz\n' "${'$'}i"
            done
        """.trimIndent()

        val result = ProcessCommandRunner.run(listOf("bash", "-lc", script), Duration.ofSeconds(10))

        result.exitCode shouldBe 0
        result.stdout.lines().filter { it.isNotBlank() }.size shouldBe 20000
    }

    @Test
    fun `process runner returns timeout with partial output`() {
        val result = ProcessCommandRunner.run(
            listOf("bash", "-lc", "printf before-timeout; sleep 5"),
            Duration.ofMillis(200),
        )

        result.exitCode shouldBe 124
        result.stdout shouldBe "before-timeout"
    }
}
