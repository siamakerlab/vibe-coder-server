package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosSimulatorDto
import com.siamakerlab.vibecoder.shared.dto.IosSimulatorListDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

class IosSimulatorInventoryService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun list(): IosSimulatorListDto {
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return IosSimulatorListDto(
                checkedAt = clock().toString(),
                mode = "linux",
                simulatorUiEnabled = false,
                blockedReason = "mac_required",
            )
        }

        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val result = agentRunner.run(IosAgentCommand.SIMULATOR_DEVICES, DEFAULT_TIMEOUT)
        val warnings = mutableListOf<String>()
        if (!result.ok) warnings += if (useSshAgent && result.exitCode == SSH_UNREACHABLE_EXIT_CODE) {
            "agent_unreachable"
        } else {
            "simctl_devices_unavailable"
        }
        val devices = if (result.ok) parseDevices(result.stdout) else emptyList()
        val blockedReason = when {
            useSshAgent && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
            !result.ok -> "simctl_missing"
            devices.isEmpty() -> "no_available_simulators"
            else -> null
        }
        return IosSimulatorListDto(
            checkedAt = clock().toString(),
            mode = if (useSshAgent) "mac_ssh" else "mac_local",
            simulatorUiEnabled = result.ok && devices.isNotEmpty(),
            devices = devices,
            blockedReason = blockedReason,
            warnings = warnings,
        )
    }

    internal fun parseDevices(raw: String): List<IosSimulatorDto> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            val devicesObj = root["devices"] as? JsonObject ?: return@runCatching emptyList()
            devicesObj.entries.flatMap { (runtime, value) ->
                val devices = value as? JsonArray ?: return@flatMap emptyList()
                devices.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val name = obj.string("name") ?: return@mapNotNull null
                    val udid = obj.string("udid") ?: return@mapNotNull null
                    val available = obj.boolean("isAvailable") ?: obj.boolean("available") ?: true
                    if (!available) return@mapNotNull null
                    IosSimulatorDto(
                        udid = udid,
                        name = name,
                        runtime = runtime,
                        deviceTypeIdentifier = obj.string("deviceTypeIdentifier"),
                        state = obj.string("state") ?: "Unknown",
                        available = true,
                        kind = kindOf(name, obj.string("deviceTypeIdentifier")),
                    )
                }
            }.sortedWith(compareBy<IosSimulatorDto>({ it.kind }, { it.name }, { it.runtime }))
        }.getOrDefault(emptyList())
    }

    private fun kindOf(name: String, identifier: String?): String {
        val text = "$name ${identifier.orEmpty()}"
        return when {
            text.contains("iPhone", ignoreCase = true) -> "iphone"
            text.contains("iPad", ignoreCase = true) -> "ipad"
            else -> "other"
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.ifBlank { null }

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}
