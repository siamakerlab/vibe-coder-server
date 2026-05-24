package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.BuildRow
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.flow.MutableSharedFlow

class BuildService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val projects: ProjectService,
    private val buildRepo: BuildRepository,
    private val queue: TaskQueue,
    private val builder: GradleBuilder,
    private val artifactService: ArtifactService,
    private val clock: Clock,
    /** v0.17.0 — 빌드 결과 이메일 알림. null 이면 알림 skip (테스트). v0.27.0+ Notifiers facade (email + webhook). */
    private val notifier: com.siamakerlab.vibecoder.server.notify.Notifiers? = null,
) {

    /**
     * Enqueue a debug build. Returns the BuildRow immediately (status=PENDING).
     */
    fun enqueueDebug(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        val build = buildRepo.create(buildId, projectId, "debug", logFile.toString())

        queue.submit(
            projectId = projectId, taskId = buildId,
            onStart = { buildRepo.setStatus(buildId, TaskStatus.RUNNING) },
            executor = { cancel ->
                val logger = TaskLogger(buildId, logFile, hub, clock)
                try {
                    val exit = builder.runAssembleDebug(
                        source = java.nio.file.Path.of(row.sourcePath),
                        moduleName = row.moduleName,
                        debugTask = row.debugTask,
                        logger = logger,
                        cancellation = cancel,
                    )
                    if (exit != 0) throw ApiException(500, "build_failed", "gradle exit $exit")
                    val apk = ApkFinder.findLatestDebug(java.nio.file.Path.of(row.sourcePath), row.moduleName)
                        ?: throw ApiException(500, "apk_not_found", "no apk under build/outputs/apk/debug")
                    logger.info("Found APK: $apk")
                    val artifact = artifactService.storeDebugApk(projectId, buildId, apk)
                    buildRepo.attachArtifact(buildId, artifact.id)
                    logger.info("Stored artifact ${artifact.id} (sha256=${artifact.sha256.take(12)}..., size=${artifact.sizeBytes} bytes)")
                } finally {
                    logger.close()
                }
            },
            onSuccess = {
                buildRepo.setStatus(buildId, TaskStatus.SUCCESS)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.SUCCESS.name))
                notifier?.buildResult(projectId, buildId, "SUCCESS", null)
            },
            onFailure = { e ->
                buildRepo.setStatus(buildId, TaskStatus.FAILED, e.message)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, e.message))
                notifier?.buildResult(projectId, buildId, "FAILED", e.message)
            },
            onCancel = {
                buildRepo.setStatus(buildId, TaskStatus.CANCELED)
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.CANCELED.name))
            },
        )
        return build
    }

    /**
     * Inline build used by Claude task's autoBuild option (already inside a queued task).
     * Not enqueued — runs in-place.
     */
    suspend fun runDebug(projectId: String, hub: LogHub) {
        val row = projects.rowOrThrow(projectId)
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        val build = buildRepo.create(buildId, projectId, "debug", logFile.toString())
        buildRepo.setStatus(buildId, TaskStatus.RUNNING)
        val logger = TaskLogger(buildId, logFile, hub, clock)
        try {
            val cancel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            val exit = builder.runAssembleDebug(
                source = java.nio.file.Path.of(row.sourcePath),
                moduleName = row.moduleName, debugTask = row.debugTask,
                logger = logger, cancellation = cancel,
            )
            if (exit != 0) {
                buildRepo.setStatus(buildId, TaskStatus.FAILED, "gradle exit $exit")
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, "gradle exit $exit"))
                return
            }
            val apk = ApkFinder.findLatestDebug(java.nio.file.Path.of(row.sourcePath), row.moduleName)
            if (apk == null) {
                buildRepo.setStatus(buildId, TaskStatus.FAILED, "apk not found")
                hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.FAILED.name, "apk not found"))
                return
            }
            val artifact = artifactService.storeDebugApk(projectId, buildId, apk)
            buildRepo.attachArtifact(buildId, artifact.id)
            buildRepo.setStatus(buildId, TaskStatus.SUCCESS)
            hub.publisher(buildId).emit(WsFrame.Done(buildId, TaskStatus.SUCCESS.name))
        } finally {
            logger.close()
        }
    }

    /**
     * v0.12.4 — 이전엔 `runBlocking` 으로 큐 cancel 을 호출해 Ktor 요청 스레드가
     * cancel 완료까지 블락됐다. suspend 로 노출하고 호출자(`SuspendableRoute`)에서
     * 일시 정지하도록 변경.
     */
    suspend fun cancel(buildId: String) {
        queue.cancel(buildId)
    }

    fun list(projectId: String): List<BuildDto> =
        buildRepo.listForProject(projectId).map { it.toDto() }

    fun get(projectId: String, buildId: String): BuildDto {
        val row = buildRepo.get(buildId) ?: throw ApiException(404, "build_not_found", buildId)
        if (row.projectId != projectId) throw ApiException(404, "build_not_found", buildId)
        return row.toDto()
    }

    /**
     * v0.58.0 — Phase 37 compare this build against the previous successful build of the
     * same project. `null` = no prior successful build (this is the first one).
     */
    fun compareWithPrevious(
        projectId: String,
        buildId: String,
        artifactRepo: com.siamakerlab.vibecoder.server.repo.ArtifactRepository,
    ): BuildComparison? {
        val current = buildRepo.get(buildId) ?: return null
        if (current.projectId != projectId) return null
        if (current.status != TaskStatus.SUCCESS) return null
        val previous = buildRepo.previousSuccessfulBefore(projectId, current.createdAt) ?: return null

        val curArt = current.artifactId?.let { artifactRepo.get(projectId, it) }
        val prevArt = previous.artifactId?.let { artifactRepo.get(projectId, it) }
        return BuildComparison(
            current = BuildSnapshot.of(current, curArt?.sizeBytes),
            previous = BuildSnapshot.of(previous, prevArt?.sizeBytes),
        )
    }

    data class BuildSnapshot(
        val id: String,
        val createdAt: String,
        val durationMs: Long?,
        val apkSizeBytes: Long?,
    ) {
        companion object {
            fun of(row: BuildRow, apkSizeBytes: Long?) = BuildSnapshot(
                id = row.id,
                createdAt = row.createdAt,
                durationMs = if (row.startedAt != null && row.finishedAt != null) {
                    runCatching {
                        java.time.Duration.between(
                            java.time.Instant.parse(row.startedAt),
                            java.time.Instant.parse(row.finishedAt),
                        ).toMillis()
                    }.getOrNull()
                } else null,
                apkSizeBytes = apkSizeBytes,
            )
        }
    }

    data class BuildComparison(val current: BuildSnapshot, val previous: BuildSnapshot) {
        val durationDeltaMs: Long? = if (current.durationMs != null && previous.durationMs != null)
            current.durationMs - previous.durationMs else null
        val apkSizeDeltaBytes: Long? = if (current.apkSizeBytes != null && previous.apkSizeBytes != null)
            current.apkSizeBytes - previous.apkSizeBytes else null
    }

    /**
     * v0.59.0 — Phase 38 빌드 통계 대시보드. 최근 [recentLimit] 개 row 기반.
     * 매번 메모리 walk 라 큰 프로젝트에선 cache 가 필요하지만 단일-사용자
     * 환경에선 N 이 보통 수십~수백 → ms 안에 끝남.
     */
    fun statistics(
        projectId: String,
        artifactRepo: com.siamakerlab.vibecoder.server.repo.ArtifactRepository,
        recentLimit: Int = 30,
    ): BuildStatistics {
        val rows = buildRepo.listForProject(projectId, limit = 200)
        val total = rows.size
        val byStatus = rows.groupingBy { it.status.name }.eachCount()
        val successDurations = rows
            .filter { it.status == TaskStatus.SUCCESS && it.startedAt != null && it.finishedAt != null }
            .mapNotNull {
                runCatching {
                    java.time.Duration.between(
                        java.time.Instant.parse(it.startedAt),
                        java.time.Instant.parse(it.finishedAt),
                    ).toMillis()
                }.getOrNull()
            }
        val avgSuccessDurationMs = if (successDurations.isEmpty()) null
        else successDurations.sum() / successDurations.size
        val recentStatuses = rows.take(recentLimit).map { it.status.name }
        // 마지막 10 SUCCESS 의 APK 사이즈 (newest first; UI 에서 reverse).
        val recentSuccessSizes = rows.asSequence()
            .filter { it.status == TaskStatus.SUCCESS }
            .take(10)
            .map { row ->
                row.artifactId
                    ?.let { artifactRepo.get(projectId, it) }
                    ?.sizeBytes
            }
            .toList()
        return BuildStatistics(
            total = total,
            successCount = byStatus["SUCCESS"] ?: 0,
            failedCount = byStatus["FAILED"] ?: 0,
            cancelledCount = byStatus["CANCELED"] ?: 0,
            runningCount = (byStatus["RUNNING"] ?: 0) + (byStatus["PENDING"] ?: 0),
            avgSuccessDurationMs = avgSuccessDurationMs,
            recentStatuses = recentStatuses,
            recentSuccessSizes = recentSuccessSizes,
        )
    }

    data class BuildStatistics(
        val total: Int,
        val successCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
        val runningCount: Int,
        val avgSuccessDurationMs: Long?,
        /** Most-recent-first status sequence (max recentLimit). */
        val recentStatuses: List<String>,
        /** Most-recent-first APK sizes (max 10). null = no artifact / not measured. */
        val recentSuccessSizes: List<Long?>,
    ) {
        /** 0..100 (Int) — total 가 0이면 null. */
        val successRatePercent: Int? = if (total == 0) null
            else (successCount.toDouble() / total * 100).toInt()
    }

    private fun BuildRow.toDto() = BuildDto(
        id = id, projectId = projectId, variant = variant, status = status,
        startedAt = startedAt ?: createdAt, finishedAt = finishedAt,
        artifactId = artifactId, errorMessage = errorMessage,
    )
}
