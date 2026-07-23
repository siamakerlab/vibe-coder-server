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
    SIMULATOR_RUNTIMES(listOf("xcrun", "simctl", "list", "runtimes", "-j")),
    CODESIGNING_IDENTITIES(listOf("security", "find-identity", "-v", "-p", "codesigning")),
    // v1.164.0 (Phase 9) — 빌드환경(/env-setup) iPhone 카드 감지용. 모두 read-only version probe.
    SWIFTLINT_VERSION(listOf("swiftlint", "version")),
    SWIFTFORMAT_VERSION(listOf("swiftformat", "--version")),
    COCOAPODS_VERSION(listOf("pod", "--version")),
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
        val effective = withDeveloperDir(argv)
        val mode = config.mode.trim().lowercase()
        return when (mode) {
            "local" -> effective
            "ssh", "remote" -> buildSshCommand(effective)
            else -> throw IllegalArgumentException("unsupported ios agent mode: ${config.mode}")
        }
    }

    /**
     * v1.171.0 — `ios.agent.xcodePath` 가 지정되면(≠auto, 절대경로) 모든 명령 앞에
     * `env DEVELOPER_DIR=<path>` 를 붙여 특정 Xcode 를 강제한다(여러 Xcode 설치 대응).
     * auto 면 원격 `xcode-select` 기본값을 그대로 쓴다. local/ssh 양쪽 동일.
     */
    private fun withDeveloperDir(argv: List<String>): List<String> {
        val xp = config.xcodePath.trim()
        if (xp.isEmpty() || xp.equals("auto", ignoreCase = true) || !xp.startsWith("/")) return argv
        return listOf("env", "DEVELOPER_DIR=$xp") + argv
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
            REMOTE_ENV_PREFIX + remoteArgv.joinToString(" ") { it.shellSingleQuoted() },
        )
    }

    companion object {
        /**
         * v1.171.0 — `ssh host 'cmd'` 는 원격을 **non-login 셸**로 돌려 `.zprofile`/`.bash_profile`
         * 의 Homebrew PATH 를 로드하지 않는다 → `brew`/`swiftlint`/`swiftformat`/`pod` 미발견
         * (`homebrew_missing` 등). Apple Silicon(`/opt/homebrew`)·Intel(`/usr/local`) Homebrew 경로를
         * 명시 prepend 해 원격 명령이 이들을 찾게 한다. `env DEVELOPER_DIR=…` prepend 도 이 PATH 를 상속.
         */
        private const val REMOTE_ENV_PREFIX =
            "export PATH=\"/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin:\$PATH\"; "
    }
}

internal fun String.shellSingleQuoted(): String {
    require('\u0000' !in this) { "command argument must not contain NUL" }
    return "'" + replace("'", "'\"'\"'") + "'"
}
