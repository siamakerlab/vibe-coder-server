package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosSimulatorActionDto
import java.time.Duration
import java.time.Instant

class IosSimulatorControlService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun boot(udid: String): IosSimulatorActionDto = runAction(udid, "boot")

    fun shutdown(udid: String): IosSimulatorActionDto = runAction(udid, "shutdown")

    private fun runAction(rawUdid: String, action: String): IosSimulatorActionDto {
        val udid = rawUdid.trim()
        require(SAFE_UDID.matches(udid) || udid.equals("booted", ignoreCase = true)) {
            "invalid simulator udid"
        }
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return IosSimulatorActionDto(
                checkedAt = clock().toString(),
                mode = "linux",
                udid = udid,
                action = action,
                ok = false,
                exitCode = 0,
                blockedReason = "mac_required",
            )
        }

        val command = listOf("xcrun", "simctl", action, udid)
        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val result = agentRunner.run(command, DEFAULT_TIMEOUT)
        val blockedReason = when {
            useSshAgent && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
            result.exitCode == 72 && action == "boot" -> "boot_failed"
            result.exitCode != 0 -> "simctl_action_failed"
            else -> null
        }
        return IosSimulatorActionDto(
            checkedAt = clock().toString(),
            mode = if (useSshAgent) "mac_ssh" else "mac_local",
            udid = udid,
            action = action,
            ok = result.ok,
            exitCode = result.exitCode,
            message = (result.stderr.ifBlank { result.stdout }).trim().ifBlank { null },
            blockedReason = blockedReason,
        )
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val SAFE_UDID = Regex("[A-Za-z0-9-]{8,80}")
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}
