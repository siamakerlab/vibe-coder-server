package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.shared.dto.IosSwiftToolsInstallDto
import com.siamakerlab.vibecoder.shared.dto.IosSwiftToolsInstallRequestDto
import java.time.Duration

class IosSwiftToolsInstallService(
    private val clock: Clock = SystemClock(),
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val runnerFactory: (IosAgentSection) -> IosAgentCommandRunner = { IosAgentCommandRunner(it) },
) {
    fun install(req: IosSwiftToolsInstallRequestDto): IosSwiftToolsInstallDto {
        if (!req.installSwiftLint && !req.installSwiftFormat) {
            return dto(mode = mode(), ok = true, message = "No Swift tools selected.")
        }
        val agent = agentConfigProvider()
        val requestedSsh = agent.mode.trim().lowercase() in setOf("ssh", "remote")
        if (requestedSsh && !agent.enabled) {
            return dto(mode = "ssh", ok = false, blockedReason = "agent_disabled", warnings = listOf("agent_disabled"))
        }
        val effectiveMode = if (requestedSsh) "ssh" else "local"
        val runner = runnerFactory(if (effectiveMode == "ssh") agent.copy(mode = "ssh") else IosAgentSection(mode = "local"))
        val result = runner.run(listOf("bash", "-lc", buildScript(req)), COMMAND_TIMEOUT)
        if (!result.ok) {
            val blocked = when {
                effectiveMode == "ssh" && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
                "homebrew_missing" in result.stderr || "homebrew_missing" in result.stdout -> "homebrew_missing"
                else -> "swift_tools_install_failed"
            }
            return dto(
                mode = effectiveMode,
                ok = false,
                blockedReason = blocked,
                message = (result.stderr.ifBlank { result.stdout }).take(500),
                warnings = listOf(blocked),
            )
        }
        return dto(
            mode = effectiveMode,
            ok = true,
            swiftLintInstalled = "__VC_SWIFTLINT_OK__" in result.stdout,
            swiftLintVersion = markerValue(result.stdout, "__VC_SWIFTLINT_VERSION__"),
            swiftFormatInstalled = "__VC_SWIFTFORMAT_OK__" in result.stdout,
            swiftFormatVersion = markerValue(result.stdout, "__VC_SWIFTFORMAT_VERSION__"),
        )
    }

    private fun buildScript(req: IosSwiftToolsInstallRequestDto): String {
        val lint = if (req.installSwiftLint) installToolScript("swiftlint", "swiftlint", "__VC_SWIFTLINT") else ""
        val format = if (req.installSwiftFormat) installToolScript("swiftformat", "swiftformat", "__VC_SWIFTFORMAT") else ""
        return """
            set -e
            if ! command -v brew >/dev/null 2>&1; then
              echo homebrew_missing >&2
              exit 69
            fi
            $lint
            $format
        """.trimIndent()
    }

    private fun installToolScript(binary: String, formula: String, marker: String): String =
        """
            if ! command -v $binary >/dev/null 2>&1; then
              brew install $formula
            fi
            if command -v $binary >/dev/null 2>&1; then
              echo ${marker}_OK__
              echo ${marker}_VERSION__ "${'$'}($binary --version 2>/dev/null | head -1)"
            fi
        """.trimIndent()

    private fun dto(
        mode: String,
        ok: Boolean,
        swiftLintInstalled: Boolean = false,
        swiftLintVersion: String? = null,
        swiftFormatInstalled: Boolean = false,
        swiftFormatVersion: String? = null,
        blockedReason: String? = null,
        message: String? = null,
        warnings: List<String> = emptyList(),
    ): IosSwiftToolsInstallDto =
        IosSwiftToolsInstallDto(
            checkedAt = clock.nowIso(),
            mode = mode,
            ok = ok,
            swiftLintInstalled = swiftLintInstalled,
            swiftLintVersion = swiftLintVersion,
            swiftFormatInstalled = swiftFormatInstalled,
            swiftFormatVersion = swiftFormatVersion,
            blockedReason = blockedReason,
            message = message,
            warnings = warnings,
        )

    private fun mode(): String {
        val agent = agentConfigProvider()
        return if (agent.mode.trim().lowercase() in setOf("ssh", "remote")) "ssh" else "local"
    }

    private fun markerValue(raw: String, marker: String): String? =
        raw.lineSequence()
            .firstOrNull { it.startsWith(marker) }
            ?.removePrefix(marker)
            ?.trim()
            ?.ifBlank { null }

    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(10)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}
