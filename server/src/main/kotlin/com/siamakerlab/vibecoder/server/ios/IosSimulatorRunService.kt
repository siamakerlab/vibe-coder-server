package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.build.XcodeArtifactFinder
import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosSimulatorRunDto
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class IosSimulatorRunService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun run(projectId: String, projectRoot: Path, bundleId: String, udid: String): IosSimulatorRunDto {
        val cleanUdid = udid.trim()
        require(SAFE_UDID.matches(cleanUdid) || cleanUdid.equals("booted", ignoreCase = true)) {
            "invalid simulator udid"
        }
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return blocked(projectId, cleanUdid, bundleId, "linux", "mac_required")
        }

        val sourceRoot = projectRoot.normalize()
        val localApp = XcodeArtifactFinder.findSimulatorApp(sourceRoot)
            ?: return blocked(projectId, cleanUdid, bundleId, mode(useSshAgent), "debug_app_not_found")
        val localScreenshot = screenshotPath(sourceRoot)
        val remoteApp = if (useSshAgent) remotePath(agent, projectId, sourceRoot, localApp) else localApp.toString()
        val remoteScreenshot = if (useSshAgent) remotePath(agent, projectId, sourceRoot, localScreenshot) else localScreenshot.toString()
        Files.createDirectories(localScreenshot.parent)

        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val install = agentRunner.run(listOf("xcrun", "simctl", "install", cleanUdid, remoteApp), COMMAND_TIMEOUT)
        if (!install.ok) {
            return result(projectId, cleanUdid, bundleId, localApp, localScreenshot, useSshAgent, install, "install_failed")
        }
        val launch = agentRunner.run(listOf("xcrun", "simctl", "launch", cleanUdid, bundleId), COMMAND_TIMEOUT)
        if (!launch.ok) {
            return result(projectId, cleanUdid, bundleId, localApp, localScreenshot, useSshAgent, launch, "launch_failed", installed = true)
        }
        val screenshot = agentRunner.run(listOf("xcrun", "simctl", "io", cleanUdid, "screenshot", remoteScreenshot), COMMAND_TIMEOUT)
        if (!screenshot.ok) {
            return result(
                projectId,
                cleanUdid,
                bundleId,
                localApp,
                localScreenshot,
                useSshAgent,
                screenshot,
                "screenshot_failed",
                installed = true,
                launched = true,
            )
        }
        if (useSshAgent) pullRemoteScreenshot(agent, remoteScreenshot, localScreenshot)?.let { failure ->
            return result(
                projectId,
                cleanUdid,
                bundleId,
                localApp,
                localScreenshot,
                useSshAgent,
                failure,
                "screenshot_pull_failed",
                installed = true,
                launched = true,
            )
        }
        return IosSimulatorRunDto(
            checkedAt = clock().toString(),
            mode = mode(useSshAgent),
            projectId = projectId,
            udid = cleanUdid,
            bundleId = bundleId,
            appPath = localApp.toString(),
            screenshotPath = localScreenshot.toString(),
            installed = true,
            launched = true,
            screenshotCaptured = true,
            ok = true,
        )
    }

    /**
     * v1.167.0 — 이미 부팅된 시뮬레이터의 현재 화면만 재캡처(install/launch 재실행 없이).
     * run() 의 스크린샷 단계만 떼어낸 것 — 원격이면 rsync 로 회수.
     */
    fun capture(projectId: String, projectRoot: Path, udid: String): IosSimulatorRunDto {
        val cleanUdid = udid.trim()
        require(SAFE_UDID.matches(cleanUdid) || cleanUdid.equals("booted", ignoreCase = true)) {
            "invalid simulator udid"
        }
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) return blocked(projectId, cleanUdid, "", "linux", "mac_required")

        val sourceRoot = projectRoot.normalize()
        val localScreenshot = screenshotPath(sourceRoot)
        val remoteScreenshot = if (useSshAgent) remotePath(agent, projectId, sourceRoot, localScreenshot) else localScreenshot.toString()
        Files.createDirectories(localScreenshot.parent)
        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val shot = agentRunner.run(listOf("xcrun", "simctl", "io", cleanUdid, "screenshot", remoteScreenshot), COMMAND_TIMEOUT)
        if (!shot.ok) {
            return captureResult(projectId, cleanUdid, localScreenshot, useSshAgent, shot, "screenshot_failed")
        }
        if (useSshAgent) pullRemoteScreenshot(agent, remoteScreenshot, localScreenshot)?.let { failure ->
            return captureResult(projectId, cleanUdid, localScreenshot, useSshAgent, failure, "screenshot_pull_failed")
        }
        return IosSimulatorRunDto(
            checkedAt = clock().toString(),
            mode = mode(useSshAgent),
            projectId = projectId,
            udid = cleanUdid,
            bundleId = "",
            screenshotPath = localScreenshot.toString(),
            screenshotCaptured = true,
            ok = true,
        )
    }

    private fun captureResult(
        projectId: String,
        udid: String,
        screenshot: Path,
        useSshAgent: Boolean,
        command: CommandResult,
        reason: String,
    ): IosSimulatorRunDto = IosSimulatorRunDto(
        checkedAt = clock().toString(),
        mode = mode(useSshAgent),
        projectId = projectId,
        udid = udid,
        bundleId = "",
        screenshotPath = screenshot.toString(),
        screenshotCaptured = false,
        ok = false,
        blockedReason = reason,
        message = (command.stderr.ifBlank { command.stdout }).trim().ifBlank { null },
    )

    private fun blocked(projectId: String, udid: String, bundleId: String, mode: String, reason: String): IosSimulatorRunDto =
        IosSimulatorRunDto(
            checkedAt = clock().toString(),
            mode = mode,
            projectId = projectId,
            udid = udid,
            bundleId = bundleId,
            ok = false,
            blockedReason = reason,
        )

    private fun result(
        projectId: String,
        udid: String,
        bundleId: String,
        app: Path,
        screenshot: Path,
        useSshAgent: Boolean,
        command: CommandResult,
        reason: String,
        installed: Boolean = false,
        launched: Boolean = false,
    ): IosSimulatorRunDto =
        IosSimulatorRunDto(
            checkedAt = clock().toString(),
            mode = mode(useSshAgent),
            projectId = projectId,
            udid = udid,
            bundleId = bundleId,
            appPath = app.toString(),
            screenshotPath = screenshot.toString(),
            installed = installed,
            launched = launched,
            screenshotCaptured = false,
            ok = false,
            blockedReason = reason,
            message = (command.stderr.ifBlank { command.stdout }).trim().ifBlank { null },
        )

    private fun screenshotPath(sourceRoot: Path): Path =
        sourceRoot.resolve(".vibecoder-ios-build").resolve("simulator").resolve("latest-screenshot.png")

    private fun remotePath(config: IosAgentSection, projectId: String, localRoot: Path, localPath: Path): String {
        val workspaceRoot = config.workspaceRoot.trim().trimEnd('/')
        require(workspaceRoot.startsWith("/")) { "ios agent workspaceRoot must be an absolute path" }
        val relative = localRoot.relativize(localPath.normalize()).joinToString("/")
        return "$workspaceRoot/$projectId/$relative"
    }

    private fun pullRemoteScreenshot(config: IosAgentSection, remoteScreenshot: String, localScreenshot: Path): CommandResult? {
        val command = listOf(
            "rsync",
            "-az",
            "-e",
            "ssh -p ${config.port} -o BatchMode=yes -o StrictHostKeyChecking=accept-new",
            "${config.user.trim()}@${config.host.trim()}:${remoteScreenshot.shellSingleQuoted()}",
            localScreenshot.toString(),
        )
        val result = runner.run(command, COMMAND_TIMEOUT)
        return result.takeUnless { it.ok }
    }

    private fun mode(useSshAgent: Boolean): String = if (useSshAgent) "mac_ssh" else "mac_local"

    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val SAFE_UDID = Regex("[A-Za-z0-9-]{8,80}")
    }
}
