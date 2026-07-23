package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test

class IosSimulatorLogStreamServiceTest {
    @Test
    fun `linux docker mode blocks stream without running simctl`() {
        runBlocking {
            val runner = RecordingStreamRunner()

            val result = IosSimulatorLogStreamService(
                osNameProvider = { "Linux" },
                streamRunner = runner,
            ).stream("kr.codr.demo", "IPHONE-UDID") { _, _ -> }

            result.blockedReason shouldBe "mac_required"
            runner.commands.size shouldBe 0
        }
    }

    @Test
    fun `mac local mode streams app logs by bundle id predicate`() {
        runBlocking {
            val runner = RecordingStreamRunner(lines = listOf("line-1", "line-2"))
            val received = mutableListOf<String>()

            val result = IosSimulatorLogStreamService(
                osNameProvider = { "Mac OS X" },
                streamRunner = runner,
            ).stream("kr.codr.demo", "IPHONE-UDID") { level, line ->
                received += "$level:$line"
            }

            result.blockedReason shouldBe null
            received shouldContainExactly listOf("STDOUT:line-1", "STDOUT:line-2")
            runner.commands.single() shouldContainExactly listOf(
                "xcrun",
                "simctl",
                "spawn",
                "IPHONE-UDID",
                "log",
                "stream",
                "--level",
                "debug",
                "--style",
                "compact",
                "--predicate",
                "process == \"kr.codr.demo\"",
            )
        }
    }

    @Test
    fun `ssh mode wraps stream command as one remote shell argument`() {
        runBlocking {
            val runner = RecordingStreamRunner()

            IosSimulatorLogStreamService(
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
                streamRunner = runner,
            ).stream("kr.codr.demo", "booted") { _, _ -> }

            runner.commands.single() shouldContainExactly ssh(
                listOf(
                    "xcrun",
                    "simctl",
                    "spawn",
                    "booted",
                    "log",
                    "stream",
                    "--level",
                    "debug",
                    "--style",
                    "compact",
                    "--predicate",
                    "process == \"kr.codr.demo\"",
                )
            )
        }
    }

    @Test
    fun `ssh unreachable maps to agent unreachable`() {
        runBlocking {
            val runner = RecordingStreamRunner(exitCode = 255)

            val result = IosSimulatorLogStreamService(
                agentConfigProvider = {
                    IosAgentSection(enabled = true, mode = "ssh", host = "mac-mini.local", user = "builder")
                },
                osNameProvider = { "Linux" },
                streamRunner = runner,
            ).stream("kr.codr.demo", "booted") { _, _ -> }

            result.blockedReason shouldBe "agent_unreachable"
        }
    }

    private class RecordingStreamRunner(
        private val exitCode: Int = 0,
        private val lines: List<String> = emptyList(),
    ) : SimulatorLogStreamRunner {
        val commands = mutableListOf<List<String>>()

        override suspend fun stream(
            command: List<String>,
            onLine: suspend (level: String, line: String) -> Unit,
        ): IosSimulatorLogStreamService.StreamResult {
            commands += command
            lines.forEach { onLine("STDOUT", it) }
            return IosSimulatorLogStreamService.StreamResult(exitCode = exitCode)
        }
    }

    companion object {
        private fun ssh(remoteArgv: List<String>): List<String> = listOf(
            "ssh",
            "-p",
            "2222",
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "builder@mac-mini.local",
            "export PATH=\"/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:\$PATH\"; " + remoteArgv.joinToString(" ") { it.shellSingleQuoted() },
        )
    }
}
