package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class IosSimulatorLogStreamService(
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val streamRunner: SimulatorLogStreamRunner = ProcessSimulatorLogStreamRunner,
) {
    suspend fun stream(
        bundleId: String,
        udid: String,
        onLine: suspend (level: String, line: String) -> Unit,
    ): StreamResult {
        val cleanUdid = IosSimulatorLogService.validateUdid(udid)
        val cleanBundleId = IosSimulatorLogService.validateBundleId(bundleId)
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return StreamResult(exitCode = 0, blockedReason = "mac_required")
        }
        val command = streamCommand(cleanUdid, cleanBundleId)
        val argv = IosAgentCommandRunner(if (useSshAgent) agent else IosAgentSection(mode = "local"))
            .buildCommand(command)
        val result = streamRunner.stream(argv, onLine)
        return result.copy(
            blockedReason = when {
                useSshAgent && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
                result.exitCode != 0 -> "simulator_log_stream_failed"
                else -> null
            }
        )
    }

    fun streamCommand(udid: String, bundleId: String): List<String> =
        listOf(
            "xcrun",
            "simctl",
            "spawn",
            IosSimulatorLogService.validateUdid(udid),
            "log",
            "stream",
            "--level",
            "debug",
            "--style",
            "compact",
            "--predicate",
            "process == \"${IosSimulatorLogService.validateBundleId(bundleId)}\"",
        )

    data class StreamResult(
        val exitCode: Int,
        val blockedReason: String? = null,
    )

    companion object {
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}

interface SimulatorLogStreamRunner {
    suspend fun stream(
        command: List<String>,
        onLine: suspend (level: String, line: String) -> Unit,
    ): IosSimulatorLogStreamService.StreamResult
}

object ProcessSimulatorLogStreamRunner : SimulatorLogStreamRunner {
    override suspend fun stream(
        command: List<String>,
        onLine: suspend (level: String, line: String) -> Unit,
    ): IosSimulatorLogStreamService.StreamResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        try {
            coroutineScope {
                val stdout = launch(Dispatchers.IO) { drain(process.inputStream, "STDOUT", onLine) }
                val stderr = launch(Dispatchers.IO) { drain(process.errorStream, "STDERR", onLine) }
                while (isActive) {
                    if (process.waitFor(250, TimeUnit.MILLISECONDS)) break
                }
                if (isActive) {
                    stdout.join()
                    stderr.join()
                    IosSimulatorLogStreamService.StreamResult(process.exitValue())
                } else {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                    IosSimulatorLogStreamService.StreamResult(0)
                }
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    private suspend fun drain(
        stream: java.io.InputStream,
        level: String,
        onLine: suspend (level: String, line: String) -> Unit,
    ) {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(level, line)
            }
        }
    }
}
