package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.TaskRepository
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto
import java.nio.file.Path

class StatusService(
    private val config: ServerConfig,
    private val projectRepo: ProjectRepository,
    private val taskRepo: TaskRepository,
    private val env: EnvDiagnostics,
) {
    fun snapshot(): ServerStatusDto {
        val workspaceRoot = Path.of(config.workspace.root).toAbsolutePath()
        val freeSpace = runCatching { workspaceRoot.toFile().freeSpace }.getOrDefault(-1L)
        val envSnap = env.run()
        return ServerStatusDto(
            serverName = config.server.name,
            serverVersion = config.server.version,
            osName = System.getProperty("os.name"),
            javaVersion = System.getProperty("java.version"),
            workspaceRoot = workspaceRoot.toString(),
            projectCount = projectRepo.count(),
            runningTaskCount = taskRepo.countRunning(),
            claudeAvailable = envSnap.claude.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            androidSdkAvailable = envSnap.androidSdk.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            gitAvailable = envSnap.git.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            freeDiskSpaceBytes = freeSpace,
        )
    }
}
