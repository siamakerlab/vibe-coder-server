package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosPreflightDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit

class IosPreflightService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun check(): IosPreflightDto {
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return IosPreflightDto(
                checkedAt = clock().toString(),
                mode = "linux",
                macAvailable = false,
                xcodeAvailable = false,
                simctlAvailable = false,
                simulatorUiEnabled = false,
                blockedReason = "mac_required",
            )
        }

        val warnings = mutableListOf<String>()
        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val xcodeSelect = agentRunner.run(IosAgentCommand.XCODE_SELECT, DEFAULT_TIMEOUT)
        val xcodebuildPath = agentRunner.run(IosAgentCommand.XCODEBUILD_PATH, DEFAULT_TIMEOUT)
        val xcodeVersion = agentRunner.run(IosAgentCommand.XCODEBUILD_VERSION, DEFAULT_TIMEOUT)
        val iphoneOsSdk = agentRunner.run(IosAgentCommand.IPHONEOS_SDK_VERSION, DEFAULT_TIMEOUT)
        val simSdk = agentRunner.run(IosAgentCommand.SIMULATOR_SDK_VERSION, DEFAULT_TIMEOUT)
        val deviceTypes = agentRunner.run(IosAgentCommand.SIMULATOR_DEVICE_TYPES, DEFAULT_TIMEOUT)
        val signingIdentities = agentRunner.run(IosAgentCommand.CODESIGNING_IDENTITIES, DEFAULT_TIMEOUT)

        val agentUnreachable = useSshAgent && listOf(
            xcodeSelect,
            xcodebuildPath,
            xcodeVersion,
            iphoneOsSdk,
            simSdk,
            deviceTypes,
            signingIdentities,
        ).all { it.exitCode == SSH_UNREACHABLE_EXIT_CODE }

        if (!xcodeSelect.ok) warnings += "xcode_select_unavailable"
        if (!xcodebuildPath.ok) warnings += "xcodebuild_unavailable"
        if (!xcodeVersion.ok) warnings += "xcode_version_unavailable"
        if (!deviceTypes.ok) warnings += "simctl_devicetypes_unavailable"
        if (!signingIdentities.ok) warnings += "codesigning_identities_unavailable"
        if (agentUnreachable) warnings += "agent_unreachable"

        val (iphoneTypes, ipadTypes) = parseSimulatorDeviceTypes(deviceTypes.stdout)
        val codesigningIdentities = parseCodesigningIdentities(signingIdentities.stdout)
        val xcodeAvailable = xcodeSelect.ok && xcodebuildPath.ok && xcodeVersion.ok
        val simctlAvailable = deviceTypes.ok && (iphoneTypes.isNotEmpty() || ipadTypes.isNotEmpty())
        val blockedReason = when {
            agentUnreachable -> "agent_unreachable"
            !xcodeAvailable -> "xcode_missing"
            !simctlAvailable -> "simctl_missing"
            else -> null
        }

        return IosPreflightDto(
            checkedAt = clock().toString(),
            mode = if (useSshAgent) "mac_ssh" else "mac_local",
            macAvailable = true,
            xcodeAvailable = xcodeAvailable,
            simctlAvailable = simctlAvailable,
            simulatorUiEnabled = xcodeAvailable && simctlAvailable,
            xcodeSelectPath = xcodeSelect.stdout.trim().ifBlank { null },
            xcodebuildPath = xcodebuildPath.stdout.trim().ifBlank { null },
            xcodeVersion = xcodeVersion.stdout.trim().lines().joinToString(" / ").ifBlank { null },
            iphoneOsSdkVersion = iphoneOsSdk.stdout.trim().ifBlank { null },
            iphoneSimulatorSdkVersion = simSdk.stdout.trim().ifBlank { null },
            iphoneDeviceTypes = iphoneTypes,
            ipadDeviceTypes = ipadTypes,
            codesigningIdentities = codesigningIdentities,
            blockedReason = blockedReason,
            warnings = warnings,
        )
    }

    private fun parseSimulatorDeviceTypes(raw: String): Pair<List<String>, List<String>> {
        if (raw.isBlank()) return emptyList<String>() to emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            val deviceTypes = root["devicetypes"] as? JsonArray ?: return@runCatching emptyList<String>() to emptyList()
            val names = deviceTypes.mapNotNull { item ->
                (item as? JsonObject)?.get("name")?.jsonPrimitive?.content
            }.distinct()
            names.filter { it.startsWith("iPhone") } to names.filter { it.startsWith("iPad") }
        }.getOrDefault(emptyList<String>() to emptyList())
    }

    private fun parseCodesigningIdentities(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("""\d+\)\s+[0-9A-Fa-f]{40}\s+".+"""")) }
            .map { it.substringAfter(") ").trim() }
            .distinct()
            .toList()
    }

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}

interface CommandRunner {
    fun run(command: List<String>, timeout: Duration): CommandResult
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

object ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeout: Duration): CommandResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val stdout = Collections.synchronizedList(mutableListOf<String>())
            val stderr = Collections.synchronizedList(mutableListOf<String>())
            val stdoutReader = process.inputStream.drainTo(stdout, "ios-command-stdout")
            val stderrReader = process.errorStream.drainTo(stderr, "ios-command-stderr")
            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                stdoutReader.join(1000)
                stderrReader.join(1000)
                return CommandResult(124, stdout.joinToString(""), stderr.joinToString("").ifBlank { "timeout" })
            }
            stdoutReader.join(1000)
            stderrReader.join(1000)
            CommandResult(
                exitCode = process.exitValue(),
                stdout = stdout.joinToString(""),
                stderr = stderr.joinToString(""),
            )
        }.recoverCatching { e ->
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
                CommandResult(130, "", "interrupted")
            } else {
                throw e
            }
        }.getOrElse { e ->
            CommandResult(127, "", e.message ?: e::class.java.simpleName)
        }
    }
}

private fun java.io.InputStream.drainTo(target: MutableList<String>, threadName: String): Thread =
    Thread {
        bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                target += String(buffer, 0, read)
            }
        }
    }.also {
        it.name = threadName
        it.isDaemon = true
        it.start()
    }
