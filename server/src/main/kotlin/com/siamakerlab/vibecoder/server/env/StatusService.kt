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
    /** v1.25.2 — Q4 회수: service-to-service / API entry 가 사용자 lang 모를 때 사용. */
    fun snapshot(): ServerStatusDto = snapshot(env.run())

    fun snapshot(lang: String): ServerStatusDto = snapshot(env.run(lang))

    /**
     * v1.25.2 — overload. caller 가 이미 보유한 EnvSnapshot 재사용 — `EnvDiagnostics.run()`
     * 의 process spawn × 3 비용 회피. Dashboard render 등 같은 cycle 안에서 두 번 호출
     * 되던 중복 패턴 (`/admin` 의 statusService.snapshot + envDiagnostics.run) 해소.
     */
    fun snapshot(envSnap: com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto): ServerStatusDto {
        val workspaceRoot = Path.of(config.workspace.root).toAbsolutePath()
        val freeSpace = runCatching { workspaceRoot.toFile().freeSpace }.getOrDefault(-1L)
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
