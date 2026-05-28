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
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

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
    /**
     * v1.8.0 — 빌드 시작 시점에 프로젝트 packageName 으로 키스토어 조회 → Gradle
     * `-Pandroid.injected.signing.*` inject. null 이면 비활성 (단위 테스트용).
     */
    private val keystores: com.siamakerlab.vibecoder.server.admin.KeystoreService? = null,
) {

    /**
     * v1.26.1 — 운영 정책 "키스토어 임의 생성 금지" SSOT 가드. SSR / JSON API / WS
     * action / Cron / Webhook 5개 외부 진입 모두 `enqueueDebug` 를 거치므로 단일
     * enforcement point. 매칭 keystore set 없으면 즉시 [ApiException] 으로 거부.
     *
     * v1.26.2 — KDoc 정정. v1.26.1 의 "Claude autoBuild" 표기는 narrative drift
     * (runDebug 가 dead code 라 미발화), 실제 5번째는 `BuildAutomationRoutes` 의
     * Webhook fire. runDebug 함수 자체는 v1.26.2 에서 제거됨.
     *
     * UI 사전 disable (`/projects/X/builds` 페이지) 은 UX 안내용으로 유지.
     */
    private fun requireKeystoreOrThrow(row: com.siamakerlab.vibecoder.server.repo.ProjectRow) {
        val ks = keystores ?: return   // null 이면 단위 테스트 컨텍스트 — skip
        if (ks.get(row.packageName) == null) {
            throw ApiException.localized(409, "keystore_required",
                messageKey = "api.build.keystoreRequired", args = listOf(row.packageName))
        }
    }

    /**
     * Enqueue a debug build. Returns the BuildRow immediately (status=PENDING).
     */
    fun enqueueDebug(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireKeystoreOrThrow(row)   // v1.26.1 — SSOT 가드
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        // v0.71.0 — Phase 51 #9: git 메타데이터 수집 (실패 시 graceful — null).
        val (branch, sha) = collectGitMetadata(java.nio.file.Path.of(row.sourcePath))
        val build = buildRepo.create(buildId, projectId, "debug", logFile.toString(),
            gitBranch = branch, gitSha = sha)

        val signing = resolveSigning(row, hub, buildId)
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
                        signing = signing,
                    )
                    if (exit != 0) throw ApiException.localized(500, "build_failed",
                        messageKey = "api.build.gradleExit", args = listOf(exit))
                    val apk = ApkFinder.findLatestDebug(java.nio.file.Path.of(row.sourcePath), row.moduleName)
                        ?: throw ApiException.localized(500, "apk_not_found", messageKey = "api.build.apkNotFound")
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

    // v1.26.2 — 이전 `runDebug` (Claude task autoBuild 용 inline build) 함수 제거.
    // grep 결과 caller 0건 dead code 였음. autoBuild 기능 자체가 config flag
    // (`config.tasks.autoBuildAfterTask`) 만 정의됐고 실 호출 path 미구현. 향후
    // autoBuild 가 실제로 wire-up 될 때 enqueueDebug 를 호출하도록 — SSOT 가드
    // 자동 적용. 7차 점검 Bug-2 회수.

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
        val row = buildRepo.get(buildId) ?: throw ApiException.localized(404, "build_not_found", messageKey = "api.build.notFound", args = listOf(buildId))
        if (row.projectId != projectId) throw ApiException.localized(404, "build_not_found", messageKey = "api.build.notFound", args = listOf(buildId))
        return row.toDto()
    }

    /**
     * v0.58.0 — Phase 37 compare this build against the previous successful build of the
     * same project. `null` = no prior successful build (this is the first one).
     *
     * v0.89.0 — Phase 65 #4 PR 별 비교. 기본적으로 같은 git branch 의 직전 SUCCESS
     * 만 매치 (PR-level apples-to-apples). [crossBranch]=true 면 v0.58.0 의 기존
     * 동작 (브랜치 무관 직전 SUCCESS) 으로 fallback — main 머지 직후 PR vs main
     * 비교 같은 use case. branch 가 null (git 미초기화) 인 빌드는 자동으로
     * cross-branch 로 처리.
     */
    fun compareWithPrevious(
        projectId: String,
        buildId: String,
        artifactRepo: com.siamakerlab.vibecoder.server.repo.ArtifactRepository,
        crossBranch: Boolean = false,
    ): BuildComparison? {
        val current = buildRepo.get(buildId) ?: return null
        if (current.projectId != projectId) return null
        if (current.status != TaskStatus.SUCCESS) return null
        val previous = if (crossBranch || current.gitBranch.isNullOrBlank()) {
            buildRepo.previousSuccessfulBefore(projectId, current.createdAt)
        } else {
            buildRepo.previousSuccessfulInBranch(projectId, current.gitBranch, current.createdAt)
        } ?: return null

        val curArt = current.artifactId?.let { artifactRepo.get(projectId, it) }
        val prevArt = previous.artifactId?.let { artifactRepo.get(projectId, it) }
        return BuildComparison(
            current = BuildSnapshot.of(current, curArt?.sizeBytes),
            previous = BuildSnapshot.of(previous, prevArt?.sizeBytes),
            scope = if (crossBranch || current.gitBranch.isNullOrBlank())
                BuildComparison.Scope.ANY else BuildComparison.Scope.SAME_BRANCH,
        )
    }

    data class BuildSnapshot(
        val id: String,
        val createdAt: String,
        val durationMs: Long?,
        val apkSizeBytes: Long?,
        val gitBranch: String? = null,
        val gitSha: String? = null,
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
                gitBranch = row.gitBranch,
                gitSha = row.gitSha,
            )
        }
    }

    data class BuildComparison(
        val current: BuildSnapshot,
        val previous: BuildSnapshot,
        /** v0.89.0 — 비교 scope. SAME_BRANCH 이 PR-level 비교, ANY 는 시계열 직전. */
        val scope: Scope = Scope.SAME_BRANCH,
    ) {
        enum class Scope { SAME_BRANCH, ANY }

        val durationDeltaMs: Long? = if (current.durationMs != null && previous.durationMs != null)
            current.durationMs - previous.durationMs else null
        val apkSizeDeltaBytes: Long? = if (current.apkSizeBytes != null && previous.apkSizeBytes != null)
            current.apkSizeBytes - previous.apkSizeBytes else null
        /** current vs previous 가 동일 branch 인지. ANY scope 일 때만 false 가능. */
        val sameBranch: Boolean = current.gitBranch != null && current.gitBranch == previous.gitBranch
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
        gitBranch = gitBranch, gitSha = gitSha,
    )

    /**
     * v1.8.0 — 빌드 시작 시 프로젝트 packageName 으로 KeystoreService.loadSigning 호출.
     *
     * 매칭되는 키스토어 (release `.keystore` + `.properties`) 가 있으면 builder 에
     * 전달해서 Gradle CLI 에 `-Pandroid.injected.signing.*` 4종 inject. WS 빌드 로그에
     * "signing inject" 사실만 한 줄 publish (비밀번호 미포함). null 이면 silent skip
     * (대부분의 프로젝트가 키스토어 없이도 debug 빌드 가능).
     */
    private fun resolveSigning(
        row: com.siamakerlab.vibecoder.server.repo.ProjectRow,
        hub: LogHub,
        buildId: String,
    ): com.siamakerlab.vibecoder.server.admin.SigningCredentials? {
        val ks = keystores ?: return null
        val signing = ks.loadSigning(row.packageName) ?: return null
        // 실제 사용자 가시 빌드 로그는 GradleBuilder.runAssembleDebug 의 logger.info("Signing injected: ...")
        // 가 처리 — 여기선 서버 로그 한 줄로만 흔적 남김 (passwords 미포함).
        log.info {
            "[build $buildId] signing inject candidate: package=${row.packageName} " +
                "storeFile=${signing.storeFile} keyAlias=${signing.keyAlias}"
        }
        return signing
    }

    /**
     * v0.71.0 — Phase 51 #9: 빌드 시작 시점의 git branch/sha 수집. 실패 graceful.
     * `.git` 없는 프로젝트 / Git CLI 미설치 / detached HEAD 모두 null fallback.
     * Timeout 3s — 빌드 enqueue 가 git hung 으로 막히지 않게.
     */
    private fun collectGitMetadata(sourcePath: java.nio.file.Path): Pair<String?, String?> {
        if (!java.nio.file.Files.isDirectory(sourcePath.resolve(".git"))) return null to null
        val branch = runGitCmd(sourcePath, listOf("git", "symbolic-ref", "--short", "HEAD"))
            ?: runGitCmd(sourcePath, listOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
        val sha = runGitCmd(sourcePath, listOf("git", "rev-parse", "HEAD"))
        return branch?.takeIf { it.isNotBlank() && it != "HEAD" } to sha?.takeIf { it.isNotBlank() }
    }

    private fun runGitCmd(dir: java.nio.file.Path, cmd: List<String>): String? = try {
        val pb = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
        val proc = pb.start()
        if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly(); null
        } else if (proc.exitValue() != 0) null
        else proc.inputStream.bufferedReader().readText().trim().lines().firstOrNull()
    } catch (e: Throwable) { null }
}
