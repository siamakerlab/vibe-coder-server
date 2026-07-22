package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IosEnvComponentServiceTest {

    @Test
    fun `linux mode runs no commands and reports mac_required`() {
        val runner = RecordingRunner(emptyMap())
        val service = IosEnvComponentService(
            preflight = IosPreflightService(
                runner = runner,
                osNameProvider = { "Linux" },
                clock = { Instant.parse("2026-07-22T00:00:00Z") },
            ),
            agentConfigProvider = { IosAgentSection() },
            runner = runner,
            nowMs = { 1_000L },
        )

        val snap = service.snapshot()

        snap.mode shouldBe "linux"
        snap.isMac.shouldBeFalse()
        snap.macAvailable.shouldBeFalse()
        snap.blockedReason shouldBe "mac_required"
        snap.simulatorRuntimeAvailable.shouldBeFalse()
        // Linux 단독: preflight 도, 추가 감지 명령도 전혀 spawn 되지 않는다.
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode detects xcode simulator runtime and swift tools`() {
        val runner = RecordingRunner(MAC_RESPONSES)
        val service = macService(runner)

        val snap = service.snapshotBlocking()

        snap.mode shouldBe "mac_local"
        snap.isMac.shouldBeTrue()
        snap.xcodeAvailable.shouldBeTrue()
        snap.xcodeVersion shouldBe "Xcode 18.0 / Build version 18A1"
        snap.commandLineToolsAvailable.shouldBeTrue()
        snap.commandLineToolsFullXcode.shouldBeTrue()
        snap.simctlAvailable.shouldBeTrue()
        snap.simulatorRuntimeAvailable.shouldBeTrue()
        snap.iosRuntimes shouldContain "iOS 18.0"
        snap.swiftLintVersion shouldBe "0.55.1"
        snap.swiftFormatVersion shouldBe "0.54.0"
        snap.cocoapodsVersion shouldBe "1.15.2"
        snap.blockedReason shouldBe null
    }

    @Test
    fun `mac mode without swift tools reports them missing`() {
        val responses = MAC_RESPONSES.toMutableMap()
        responses["swiftlint version"] = CommandResult(127, "", "not found")
        responses["swiftformat --version"] = CommandResult(127, "", "not found")
        responses["pod --version"] = CommandResult(127, "", "not found")
        val snap = macService(RecordingRunner(responses)).snapshotBlocking()

        snap.swiftLintVersion shouldBe null
        snap.swiftFormatVersion shouldBe null
        snap.cocoapodsVersion shouldBe null
    }

    @Test
    fun `mac ssh unreachable agent short-circuits without running extra probes`() {
        val sshAgent = IosAgentSection(enabled = true, mode = "ssh", host = "mac.local", port = 2222, user = "builder")
        val cmdBuilder = IosAgentCommandRunner(sshAgent)
        val runner = RecordingRunner(
            IosAgentCommand.entries.associate { cmd ->
                cmdBuilder.buildCommand(cmd).joinToString(" ") to CommandResult(255, "", "ssh: connect to host")
            }
        )
        val service = IosEnvComponentService(
            preflight = IosPreflightService(
                runner = runner,
                agentConfigProvider = { sshAgent },
                osNameProvider = { "Linux" },
                clock = { Instant.parse("2026-07-22T00:00:00Z") },
            ),
            agentConfigProvider = { sshAgent },
            runner = runner,
            nowMs = { 1_000L },
        )

        val snap = service.snapshotBlocking()

        snap.mode shouldBe "mac_ssh"
        snap.blockedReason shouldBe "agent_unreachable"
        snap.isMac.shouldBeTrue()
        snap.simulatorRuntimeAvailable.shouldBeFalse()
        // preflight 7개만 spawn — runtimes/swiftlint/swiftformat/cocoapods 추가 probe 는 생략.
        runner.commands.size shouldBe 7
    }

    @Test
    fun `snapshot is cached within the ttl window`() {
        val runner = RecordingRunner(MAC_RESPONSES)
        val service = macService(runner)

        service.snapshotBlocking()
        val firstCount = runner.commands.size
        service.snapshot()

        // 두 번째 호출은 캐시라 추가 명령 spawn 이 없다.
        runner.commands.size shouldBe firstCount
        firstCount shouldBe 11 // preflight 7 + runtimes/swiftlint/swiftformat/cocoapods 4
    }

    private fun macService(runner: RecordingRunner): IosEnvComponentService =
        IosEnvComponentService(
            preflight = IosPreflightService(
                runner = runner,
                osNameProvider = { "Mac OS X" },
                clock = { Instant.parse("2026-07-22T00:00:00Z") },
            ),
            agentConfigProvider = { IosAgentSection(mode = "local") },
            runner = runner,
            nowMs = { 1_000L },
        )

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
        private val MAC_RESPONSES: Map<String, CommandResult> = mapOf(
            "/usr/bin/xcode-select -p" to CommandResult(0, "/Applications/Xcode.app/Contents/Developer\n", ""),
            "xcrun --find xcodebuild" to CommandResult(0, "/usr/bin/xcodebuild\n", ""),
            "xcodebuild -version" to CommandResult(0, "Xcode 18.0\nBuild version 18A1\n", ""),
            "xcrun --sdk iphoneos --show-sdk-version" to CommandResult(0, "26.0\n", ""),
            "xcrun --sdk iphonesimulator --show-sdk-version" to CommandResult(0, "26.0\n", ""),
            "xcrun simctl list devicetypes -j" to CommandResult(0, """{"devicetypes":[{"name":"iPhone 17"}]}""", ""),
            "security find-identity -v -p codesigning" to CommandResult(
                0,
                "1) 0123456789ABCDEF0123456789ABCDEF01234567 \"Apple Development: Wody (TEAMID)\"\n",
                "",
            ),
            "xcrun simctl list runtimes -j" to CommandResult(
                0,
                """{"runtimes":[{"name":"iOS 18.0","identifier":"com.apple.CoreSimulator.SimRuntime.iOS-18-0","isAvailable":true}]}""",
                "",
            ),
            "swiftlint version" to CommandResult(0, "0.55.1\n", ""),
            "swiftformat --version" to CommandResult(0, "0.54.0\n", ""),
            "pod --version" to CommandResult(0, "1.15.2\n", ""),
        )
    }
}
