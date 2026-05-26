package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto
import java.nio.file.Path

class StatusService(
    private val config: ServerConfig,
    private val projectRepo: ProjectRepository,
    private val buildRepo: BuildRepository,
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
            // v1.4.2 — __scratch__ ghost 프로젝트 제외. projectRepo.count() 는 raw
            // DB row count 라 SCRATCH 포함 → Dashboard 의 "Projects" 메트릭이
            // 실제 사용자 프로젝트 + 1 로 잘못 표시되던 문제. list().size 도
            // 옵션이지만 buildRepo.lastForProject 가 N번 호출되어 비효율 → 직접
            // ProjectRepository.list() 의 raw row 만 필터.
            projectCount = projectRepo.list().count {
                it.id != com.siamakerlab.vibecoder.server.projects.ProjectService.SCRATCH_ID
            },
            runningTaskCount = buildRepo.countRunning(),
            claudeAvailable = envSnap.claude.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            androidSdkAvailable = envSnap.androidSdk.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            gitAvailable = envSnap.git.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK,
            freeDiskSpaceBytes = freeSpace,
        )
    }
}
