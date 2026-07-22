package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.shared.dto.IosSwiftToolsInstallRequestDto
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IosSwiftToolsInstallServiceTest {
    @Test
    fun `installs optional swift tools on local mac agent`() {
        val captured = mutableListOf<List<String>>()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, timeout: Duration): CommandResult {
                captured += command
                return CommandResult(
                    exitCode = 0,
                    stdout = """
                        __VC_SWIFTLINT_OK__
                        __VC_SWIFTLINT_VERSION__ 0.59.1
                        __VC_SWIFTFORMAT_OK__
                        __VC_SWIFTFORMAT_VERSION__ 0.55.5
                    """.trimIndent(),
                    stderr = "",
                )
            }
        }
        val service = IosSwiftToolsInstallService(
            clock = fixedClock(),
            agentConfigProvider = { IosAgentSection(mode = "local") },
            runnerFactory = { IosAgentCommandRunner(it, runner) },
        )

        val dto = service.install(IosSwiftToolsInstallRequestDto())

        dto.ok.shouldBeTrue()
        dto.swiftLintInstalled.shouldBeTrue()
        dto.swiftLintVersion shouldBe "0.59.1"
        dto.swiftFormatInstalled.shouldBeTrue()
        dto.swiftFormatVersion shouldBe "0.55.5"
        captured.single().last() shouldContain "brew install swiftlint"
        captured.single().last() shouldContain "brew install swiftformat"
    }

    @Test
    fun `reports homebrew missing without marking tools installed`() {
        val runner = object : CommandRunner {
            override fun run(command: List<String>, timeout: Duration): CommandResult =
                CommandResult(exitCode = 69, stdout = "", stderr = "homebrew_missing")
        }
        val service = IosSwiftToolsInstallService(
            clock = fixedClock(),
            agentConfigProvider = { IosAgentSection(mode = "local") },
            runnerFactory = { IosAgentCommandRunner(it, runner) },
        )

        val dto = service.install(IosSwiftToolsInstallRequestDto())

        dto.ok.shouldBeFalse()
        dto.blockedReason shouldBe "homebrew_missing"
        dto.swiftLintInstalled.shouldBeFalse()
        dto.swiftFormatInstalled.shouldBeFalse()
    }

    private fun fixedClock(): Clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }
}
