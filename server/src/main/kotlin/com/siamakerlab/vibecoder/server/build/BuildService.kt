package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.platform.PlatformBuildToolchains
import com.siamakerlab.vibecoder.server.platform.PlatformEngineRegistry
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
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path

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
    private val resourceGuard: com.siamakerlab.vibecoder.server.resources.ResourceGuard? = null,
    private val platformEngines: PlatformEngineRegistry = PlatformEngineRegistry.default,
) {

    // v1.162.5 — BuildService 는 후보 toolchain 만 구성하고, projectType별 선택은
    // PlatformEngineRegistry/ProjectPlatformEngine 이 소유한다.
    private val buildToolchains = PlatformBuildToolchains(
        gradle = GradleToolchain(builder),
        flutter = FlutterToolchain(config),
        ios = IosBuildToolchain({ config.ios.agent }),
    )
    private fun toolchainFor(projectType: String): BuildToolchain =
        platformEngines.forType(projectType).buildToolchain(buildToolchains)

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
        requireSupportedBuildTarget(row, BuildVariant.DEBUG)
        requireKeystoreOrThrow(row)   // v1.26.1 — SSOT 가드
        return enqueueBuild(row, hub, BuildVariant.DEBUG)
    }

    /**
     * v1.107.0 — release APK 빌드(assembleRelease). 키스토어 서명 주입 적용
     * (`-Pandroid.injected.signing.*`). debug 와 동일 SSOT 가드.
     */
    fun enqueueRelease(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.RELEASE)
        requireKeystoreOrThrow(row)
        return enqueueBuild(row, hub, BuildVariant.RELEASE)
    }

    /**
     * v1.107.0 — release AAB 번들 빌드(bundleRelease). Play Console 업로드용(.aab).
     * 키스토어 서명 주입 적용. debug 와 동일 SSOT 가드.
     */
    fun enqueueBundle(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.BUNDLE)
        requireKeystoreOrThrow(row)
        return enqueueBuild(row, hub, BuildVariant.BUNDLE)
    }

    fun enqueueIosDebug(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.IOS_BUILD_DEBUG)
        requireIPhoneBuildEnvironment()
        return enqueueBuild(row, hub, BuildVariant.IOS_BUILD_DEBUG)
    }

    fun enqueueIosTest(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.IOS_TEST)
        requireIPhoneBuildEnvironment()
        return enqueueBuild(row, hub, BuildVariant.IOS_TEST)
    }

    fun enqueueIosArchive(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.IOS_ARCHIVE)
        requireIPhoneBuildEnvironment()
        return enqueueBuild(row, hub, BuildVariant.IOS_ARCHIVE)
    }

    fun enqueueIosExportIpa(projectId: String, hub: LogHub): BuildRow {
        val row = projects.rowOrThrow(projectId)
        requireSupportedBuildTarget(row, BuildVariant.IOS_EXPORT_IPA)
        requireIPhoneBuildEnvironment()
        return enqueueBuild(row, hub, BuildVariant.IOS_EXPORT_IPA)
    }

    private fun requireSupportedBuildTarget(row: com.siamakerlab.vibecoder.server.repo.ProjectRow, variant: BuildVariant) {
        val engine = platformEngines.forType(row.projectType)
        val projectRoot = Path.of(row.sourcePath)
        if (!engine.supportsBuildVariant(variant, projectRoot)) {
            if (variant.isIos) {
                throw ApiException.localized(409, "iphone_build_target_required",
                    messageKey = "api.build.iphoneTargetRequired")
            }
            throw ApiException.localized(409, "iphone_build_requires_xcode_pipeline",
                messageKey = "api.build.iphoneRequiresXcodePipeline")
        }
    }

    private fun requireIPhoneBuildEnvironment() {
        val agent = config.ios.agent
        val mode = agent.mode.trim().lowercase()
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        if (mode !in setOf("ssh", "remote") && !osName.contains("mac")) {
            throw ApiException.localized(409, "iphone_build_requires_mac_agent",
                messageKey = "api.build.iphoneRequiresMacAgent")
        }
        if (mode in setOf("ssh", "remote") && !agent.enabled) {
            throw ApiException.localized(409, "iphone_agent_disabled",
                messageKey = "api.build.iphoneAgentDisabled")
        }
    }

    /**
     * v1.107.0 — debug/release/bundle 공통 enqueue. 큐/로그/서명/알림 흐름은 동일.
     * v1.126.0 (P3) — variant·gradleTask·산출물 finder·확장자·artifact type 파라미터를
     * [BuildVariant] + [BuildToolchain] 으로 대체. 실제 빌드 명령/산출물 해석은
     * `row.projectType` 으로 선택한 toolchain 에 위임(Gradle/Flutter).
     */
    private fun enqueueBuild(
        row: com.siamakerlab.vibecoder.server.repo.ProjectRow,
        hub: LogHub,
        variant: BuildVariant,
    ): BuildRow {
        resourceGuard?.ensureCanStart("build ${variant.wire}")
        val toolchain = toolchainFor(row.projectType)
        val projectId = row.id
        val buildId = Ids.buildId()
        val logFile = workspace.buildLogFile(projectId, buildId)
        // v0.71.0 — Phase 51 #9: git 메타데이터 수집 (실패 시 graceful — null).
        val (branch, sha) = collectGitMetadata(java.nio.file.Path.of(row.sourcePath))
        val build = buildRepo.create(buildId, projectId, variant.wire, logFile.toString(),
            gitBranch = branch, gitSha = sha)

        val signing = resolveSigning(row, hub, buildId, variant)
        queue.submit(
            projectId = projectId, taskId = buildId,
            onStart = { buildRepo.setStatus(buildId, TaskStatus.RUNNING) },
            executor = { cancel ->
                val logger = TaskLogger(buildId, logFile, hub, clock)
                try {
                    val sourcePath = java.nio.file.Path.of(row.sourcePath)
                    val exit = try {
                        toolchain.runBuild(
                            source = sourcePath,
                            moduleName = row.moduleName,
                            variant = variant,
                            debugTask = row.debugTask,
                            logger = logger,
                            cancellation = cancel,
                            signing = signing,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        storeSupplementaryArtifacts(toolchain, sourcePath, row, buildId, variant, logger)
                        throw e
                    }
                    if (exit != 0) {
                        storeSupplementaryArtifacts(toolchain, sourcePath, row, buildId, variant, logger)
                        throw ApiException.localized(500, "build_failed",
                            messageKey = "api.build.gradleExit", args = listOf(exit))
                    }
                    val out = toolchain.findArtifact(sourcePath, row.moduleName, variant)
                    if (out == null) {
                        if (variant.artifactRequired) {
                            throw ApiException.localized(500, "artifact_not_found", messageKey = "api.build.apkNotFound")
                        }
                        logger.info("No stored artifact expected for ${variant.wire}")
                    } else {
                        logger.info("Found output (${variant.wire}): $out")
                        val artifact = artifactService.storeBuildArtifact(
                            projectId, buildId, out, row.packageName, variant.wire, variant.ext, variant.artifactType,
                        )
                        buildRepo.attachArtifact(buildId, artifact.id)
                        logger.info("Stored artifact ${artifact.id} (sha256=${artifact.sha256.take(12)}..., size=${artifact.sizeBytes} bytes)")
                    }
                    storeSupplementaryArtifacts(toolchain, sourcePath, row, buildId, variant, logger)
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
                val failureKind = (e as? BuildToolchainFailureException)?.failureKind
                buildRepo.setStatus(buildId, TaskStatus.FAILED, e.message, failureKind = failureKind)
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

    private suspend fun storeSupplementaryArtifacts(
        toolchain: BuildToolchain,
        sourcePath: java.nio.file.Path,
        row: com.siamakerlab.vibecoder.server.repo.ProjectRow,
        buildId: String,
        variant: BuildVariant,
        logger: TaskLogger,
    ) {
        val artifacts = runCatching {
            toolchain.findSupplementaryArtifacts(sourcePath, row.moduleName, variant)
        }.onFailure {
            logger.warn("Supplementary artifact lookup failed: ${it.message}")
        }.getOrDefault(emptyList())
        for (artifact in artifacts) {
            runCatching {
                if (Files.isDirectory(artifact.path)) {
                    artifactService.storeBuildDirectoryArtifact(
                        projectId = row.id,
                        buildId = buildId,
                        sourceDir = artifact.path,
                        packageName = row.packageName,
                        variant = "${variant.wire}-${artifact.type}",
                        ext = artifact.ext,
                        type = artifact.type,
                    )
                } else {
                    artifactService.storeBuildArtifact(
                        projectId = row.id,
                        buildId = buildId,
                        sourceFile = artifact.path,
                        packageName = row.packageName,
                        variant = "${variant.wire}-${artifact.type}",
                        ext = artifact.ext,
                        type = artifact.type,
                    )
                }
            }.onSuccess {
                logger.info("Stored supplementary artifact ${it.id} (${artifact.type}, size=${it.sizeBytes} bytes)")
            }.onFailure {
                logger.warn("Supplementary artifact store failed (${artifact.type}): ${it.message}")
            }
        }
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
        artifactId = artifactId, errorMessage = errorMessage, failureKind = failureKind,
        gitBranch = gitBranch, gitSha = gitSha,
    )

    /**
     * v1.8.0 — 빌드 시작 시 프로젝트 packageName 으로 KeystoreService.loadSigning 호출.
     *
     * 매칭되는 키스토어 (`.keystore` + `.properties`) 가 있으면 builder 에 전달해서
     * Gradle CLI 에 `-Pandroid.injected.signing.*` 4종 inject. WS 빌드 로그에
     * "signing inject" 사실만 한 줄 publish (비밀번호 미포함). null 이면 silent skip
     * (대부분의 프로젝트가 키스토어 없이도 debug 빌드 가능).
     *
     * v1.144.5 — [variant] 에 맞는 키스토어를 선택한다: DEBUG 는 `<pkg>-debug.keystore`,
     * RELEASE/BUNDLE 은 `<pkg>.keystore`. `android.injected.signing.*` 는 빌드되는
     * variant(debug task 포함)에 그대로 적용되므로, 이전엔 디버그 빌드에도 릴리즈 키가
     * 주입되던 회귀를 바로잡는다.
     */
    private fun resolveSigning(
        row: com.siamakerlab.vibecoder.server.repo.ProjectRow,
        hub: LogHub,
        buildId: String,
        variant: BuildVariant,
    ): com.siamakerlab.vibecoder.server.admin.SigningCredentials? {
        val ks = keystores ?: return null
        val signing = ks.loadSigning(row.packageName, debug = variant == BuildVariant.DEBUG) ?: return null
        // 실제 사용자 가시 빌드 로그는 GradleBuilder.runAssembleDebug 의 logger.info("Signing injected: ...")
        // 가 처리 — 여기선 서버 로그 한 줄로만 흔적 남김 (passwords 미포함).
        log.info {
            "[build $buildId] signing inject candidate: package=${row.packageName} " +
                "variant=${variant.wire} storeFile=${signing.storeFile} keyAlias=${signing.keyAlias}"
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
