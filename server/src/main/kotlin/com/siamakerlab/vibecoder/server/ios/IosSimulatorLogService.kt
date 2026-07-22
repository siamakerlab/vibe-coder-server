package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosSimulatorLogsDto
import java.time.Duration
import java.time.Instant

class IosSimulatorLogService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun recent(projectId: String, bundleId: String, udid: String): IosSimulatorLogsDto {
        val cleanUdid = validateUdid(udid)
        val cleanBundleId = validateBundleId(bundleId)

        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return blocked(projectId, cleanUdid, cleanBundleId, "linux", "mac_required")
        }

        val command = listOf(
            "xcrun",
            "simctl",
            "spawn",
            cleanUdid,
            "log",
            "show",
            "--style",
            "compact",
            "--last",
            "5m",
            "--predicate",
            "process == \"$cleanBundleId\"",
        )
        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val result = agentRunner.run(command, COMMAND_TIMEOUT)
        val output = result.stdout.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .takeLastBounded(MAX_LINES)
        val message = result.stderr.trim().ifBlank { null }
        return IosSimulatorLogsDto(
            checkedAt = clock().toString(),
            mode = if (useSshAgent) "mac_ssh" else "mac_local",
            projectId = projectId,
            udid = cleanUdid,
            bundleId = cleanBundleId,
            lines = output,
            ok = result.ok,
            blockedReason = when {
                useSshAgent && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
                result.exitCode != 0 -> "simulator_log_failed"
                else -> null
            },
            message = message,
        )
    }

    private fun blocked(projectId: String, udid: String, bundleId: String, mode: String, reason: String): IosSimulatorLogsDto =
        IosSimulatorLogsDto(
            checkedAt = clock().toString(),
            mode = mode,
            projectId = projectId,
            udid = udid,
            bundleId = bundleId,
            ok = false,
            blockedReason = reason,
        )

    companion object {
        fun validateUdid(raw: String): String {
            val clean = raw.trim()
            require(SAFE_UDID.matches(clean) || clean.equals("booted", ignoreCase = true)) {
                "invalid simulator udid"
            }
            return if (clean.equals("booted", ignoreCase = true)) "booted" else clean
        }

        fun validateBundleId(raw: String): String {
            val clean = raw.trim()
            require(SAFE_BUNDLE_ID.matches(clean)) { "invalid bundle id" }
            return clean
        }

        private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val SAFE_UDID = Regex("[A-Za-z0-9-]{8,80}")
        private val SAFE_BUNDLE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private const val MAX_LINES = 200
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}

private fun Sequence<String>.takeLastBounded(limit: Int): List<String> {
    val buffer = ArrayDeque<String>(limit)
    for (line in this) {
        if (buffer.size == limit) buffer.removeFirst()
        buffer.addLast(line)
    }
    return buffer.toList()
}
