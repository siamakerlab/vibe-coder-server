package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import java.time.Duration

/**
 * Typed allowlist for commands that may be executed on a macOS iPhone agent.
 * Keep this closed: never pass browser/user supplied shell text into the agent.
 */
enum class IosAgentCommand(val argv: List<String>) {
    XCODE_SELECT(listOf("/usr/bin/xcode-select", "-p")),
    XCODEBUILD_PATH(listOf("xcrun", "--find", "xcodebuild")),
    XCODEBUILD_VERSION(listOf("xcodebuild", "-version")),
    IPHONEOS_SDK_VERSION(listOf("xcrun", "--sdk", "iphoneos", "--show-sdk-version")),
    SIMULATOR_SDK_VERSION(listOf("xcrun", "--sdk", "iphonesimulator", "--show-sdk-version")),
    SIMULATOR_DEVICE_TYPES(listOf("xcrun", "simctl", "list", "devicetypes", "-j")),
    SIMULATOR_DEVICES(listOf("xcrun", "simctl", "list", "-j", "devices", "available")),
    CODESIGNING_IDENTITIES(listOf("security", "find-identity", "-v", "-p", "codesigning")),
}

class IosAgentCommandRunner(
    private val config: IosAgentSection,
    private val processRunner: CommandRunner = ProcessCommandRunner,
) {
    fun run(command: IosAgentCommand, timeout: Duration): CommandResult {
        return processRunner.run(buildCommand(command), timeout)
    }

    fun run(argv: List<String>, timeout: Duration): CommandResult {
        return processRunner.run(buildCommand(argv), timeout)
    }

    fun buildCommand(command: IosAgentCommand): List<String> {
        return buildCommand(command.argv)
    }

    fun buildCommand(argv: List<String>): List<String> {
        val mode = config.mode.trim().lowercase()
        return when (mode) {
            "local" -> argv
            "ssh", "remote" -> buildSshCommand(argv)
            else -> throw IllegalArgumentException("unsupported ios agent mode: ${config.mode}")
        }
    }

    private fun buildSshCommand(remoteArgv: List<String>): List<String> {
        val host = config.host.trim()
        val user = config.user.trim()
        val port = config.port
        require(host.isNotEmpty()) { "ios agent host is required for ssh mode" }
        require(user.isNotEmpty()) { "ios agent user is required for ssh mode" }
        require(port in 1..65535) { "ios agent port must be between 1 and 65535" }
        return listOf(
            "ssh",
            "-p",
            port.toString(),
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "$user@$host",
            remoteArgv.joinToString(" ") { it.shellSingleQuoted() },
        )
    }
}

internal fun String.shellSingleQuoted(): String {
    require('\u0000' !in this) { "command argument must not contain NUL" }
    return "'" + replace("'", "'\"'\"'") + "'"
}
