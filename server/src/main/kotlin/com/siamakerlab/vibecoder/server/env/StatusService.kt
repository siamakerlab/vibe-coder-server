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
    /**
     * v1.25.2 — service-to-service 호출자 (사용자 lang 모를 때) 용. config.i18n.defaultLanguage
     * 로 fall-through. 외부에 노출되는 JSON API 는 직접 `snapshot(lang)` 호출해야 함.
     *
     * v1.26.2 — Q-3 회수: deprecation 경고. 호출자가 무심코 이 overload 를 쓰면
     * 사용자 lang 가정이 깨질 수 있음 (Android client 가 ko Accept-Language 보낸
     * 경우 server default 가 "ko" 면 영문 가정 깨짐). EnvRoutes 처럼 명시적 lang
     * 전달 권장. service-to-service (CapabilityService 등) 외엔 사용 자제.
     *
     * v1.27.0 — Bug-1 회수: `replaceWith = ReplaceWith("snapshot(\"en\")")` 는 IDE
     * quick-fix 가 hardcoded "en" 으로 치환하게 만들어 사용자 lang 가정을 더
     * 강하게 깰 위험. ReplaceWith 제거 — 사용자가 손으로 적절한 lang 변수를
     * 전달하도록 유도.
     */
    @Deprecated(
        message = "Prefer snapshot(lang) — no-arg may fall back to server default that differs from client expectation. Pass sess.language or the resolved request language explicitly.",
    )
    fun snapshot(): ServerStatusDto = snapshot(env.run())

    /**
     * v1.25.0 — caller 가 lang 명시 (사용자 세션 언어).
     * v1.26.1 — Q5: AdminRoutes dashboard 가 envSnap fetch 실패 시 fallback path 로
     * 사용. envSnap 있을 땐 직접 [snapshot(envSnap)] overload 사용 (spawn 절약).
     */
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
