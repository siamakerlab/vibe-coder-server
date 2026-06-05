package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class ArtifactService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val repo: ArtifactRepository,
    private val buildRepo: BuildRepository,
    private val clock: Clock,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun storeDebugApk(
        projectId: String,
        buildId: String,
        sourceApk: Path,
        /** v1.87.0 — 산출물 파일명 `<packageName>-<variant>-v<versionName>.apk` 용. */
        packageName: String,
        variant: String = "debug",
    ): ArtifactRow = storeBuildArtifact(projectId, buildId, sourceApk, packageName, variant, ext = "apk", type = "debug-apk")

    /**
     * v1.107.0 — debug/release APK + release AAB 공통 산출물 저장. [ext] 로 확장자(apk/aab),
     * [type] 로 artifact 종류(debug-apk/release-apk/release-aab) 구분. APK 만 versionName 을
     * aapt best-effort 로 파일명에 포함(AAB 는 badging 불가 → 버전 생략).
     */
    fun storeBuildArtifact(
        projectId: String,
        buildId: String,
        sourceFile: Path,
        packageName: String,
        variant: String,
        ext: String,
        type: String,
    ): ArtifactRow {
        val targetDir = workspace.debugArtifactDir(projectId, buildId)
        val versionName = if (ext.equals("apk", ignoreCase = true)) ApkBadgingReader.versionName(sourceFile) else null
        val fileName = artifactFileName(packageName, variant, versionName, ext)
        val targetApk = targetDir.resolve(fileName)
        Files.copy(sourceFile, targetApk, StandardCopyOption.REPLACE_EXISTING)
        val sha = Sha256.hashFile(targetApk)
        val size = targetApk.fileSize()
        val artifact = repo.create(
            id = Ids.artifactId(),
            projectId = projectId,
            buildId = buildId,
            type = type,
            fileName = targetApk.fileName.toString(),
            filePath = targetApk.toString(),
            sizeBytes = size,
            sha256 = sha,
        )
        val metadata = ArtifactMetadata(
            artifactId = artifact.id,
            projectId = projectId,
            buildId = buildId,
            variant = variant,
            status = "success",
            fileName = artifact.fileName,
            sizeBytes = size,
            sha256 = sha,
            createdAt = clock.nowIso(),
        )
        targetDir.resolve("metadata.json").writeText(json.encodeToString(ArtifactMetadata.serializer(), metadata))

        // Best-effort: keep only the N most recent artifacts per project on disk.
        runCatching { pruneOldArtifacts(projectId, config.workspace.artifactKeepCount) }
            .onFailure { log.warn(it) { "[$projectId] artifact prune raised; latest store unaffected" } }

        return artifact
    }

    /**
     * Keep only the [keepCount] newest artifacts for [projectId]; delete the rest.
     *
     * For each pruned artifact this removes:
     *   - the artifact's enclosing directory on disk (APK + metadata.json), if it lives under the workspace root
     *   - any `Builds.artifactId` reference (set to null) so the build row remains as history
     *   - the `Artifacts` row itself
     *
     * Per-artifact failures are logged and skipped; one bad row doesn't abort the rest.
     * `keepCount <= 0` is treated as "no limit" (no pruning).
     *
     * Returns the number of artifacts actually removed.
     */
    fun pruneOldArtifacts(projectId: String, keepCount: Int): Int {
        if (keepCount <= 0) return 0
        val all = repo.listForProjectAll(projectId)
        if (all.size <= keepCount) return 0
        val toPrune = all.drop(keepCount)
        var removed = 0
        for (row in toPrune) {
            runCatching { deleteArtifactFiles(row) }
                .onFailure { log.warn(it) { "[$projectId] failed to delete files for artifact ${row.id} at ${row.filePath}" } }
            runCatching { buildRepo.detachArtifact(row.id) }
                .onFailure { log.warn(it) { "[$projectId] failed to detach artifact ${row.id} from builds" } }
            val n = runCatching { repo.delete(row.id) }
                .onFailure { log.warn(it) { "[$projectId] failed to delete artifact row ${row.id}" } }
                .getOrDefault(0)
            if (n > 0) removed++
        }
        if (removed > 0) {
            log.info { "[$projectId] pruned $removed old artifact(s); kept ${all.size - removed}" }
        }
        return removed
    }

    /**
     * Delete the artifact's enclosing directory on disk
     * (`.vibecoder/<projectId>/artifacts/debug/<buildId>/`).
     * Validates the path stays under the workspace root before touching anything.
     */
    private fun deleteArtifactFiles(row: ArtifactRow) {
        val file = Path.of(row.filePath)
        workspace.ensureUnderWorkspace(file)
        val dir = file.parent ?: return
        workspace.ensureUnderWorkspace(dir)
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { p -> runCatching { Files.deleteIfExists(p) } }
        }
    }

    /**
     * v1.87.0 — 산출물 APK 파일명: `<packageName>-<variant>-v<versionName>.apk`.
     * versionName 미상(aapt 부재 등) 시 버전 부분 생략. 파일시스템 안전 문자만 남기고
     * 나머지는 `_` 로 치환(패키지명의 `.` 와 versionName 의 `.` 는 안전 문자라 유지).
     *
     * 예: `com.example.app-debug-v1.2.3.apk`, 버전 미상 시 `com.example.app-debug.apk`.
     */
    internal fun apkFileName(packageName: String, variant: String, versionName: String?): String =
        artifactFileName(packageName, variant, versionName, "apk")

    /** v1.107.0 — 산출물 파일명 일반화: `<packageName>-<variant>-v<versionName>.<ext>`. */
    internal fun artifactFileName(packageName: String, variant: String, versionName: String?, ext: String): String {
        val base = buildString {
            append(packageName.ifBlank { "app" })
            append('-').append(variant.ifBlank { "debug" })
            if (!versionName.isNullOrBlank()) append("-v").append(versionName)
        }
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safe.${ext.ifBlank { "apk" }}"
    }

    fun toDto(row: ArtifactRow): ArtifactDto = ArtifactDto(
        id = row.id, projectId = row.projectId, buildId = row.buildId, type = row.type,
        fileName = row.fileName, sizeBytes = row.sizeBytes, sha256 = row.sha256,
        downloadUrl = ApiPath.artifactDownload(row.projectId, row.id),
        createdAt = row.createdAt,
    )

    @Serializable
    private data class ArtifactMetadata(
        val artifactId: String,
        val projectId: String,
        val buildId: String,
        val variant: String,
        val status: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val createdAt: String,
    )
}
