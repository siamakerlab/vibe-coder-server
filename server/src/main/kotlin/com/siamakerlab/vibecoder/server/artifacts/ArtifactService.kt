package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

class ArtifactService(
    private val workspace: WorkspacePath,
    private val repo: ArtifactRepository,
    private val clock: Clock,
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun storeDebugApk(
        projectId: String,
        buildId: String,
        sourceApk: Path,
    ): ArtifactRow {
        val targetDir = workspace.debugArtifactDir(projectId, buildId)
        val targetApk = targetDir.resolve(sourceApk.fileName.toString())
        Files.copy(sourceApk, targetApk, StandardCopyOption.REPLACE_EXISTING)
        val sha = Sha256.hashFile(targetApk)
        val size = targetApk.fileSize()
        val artifact = repo.create(
            id = Ids.artifactId(),
            projectId = projectId,
            buildId = buildId,
            type = "debug-apk",
            fileName = targetApk.fileName.toString(),
            filePath = targetApk.toString(),
            sizeBytes = size,
            sha256 = sha,
        )
        val metadata = ArtifactMetadata(
            artifactId = artifact.id,
            projectId = projectId,
            buildId = buildId,
            variant = "debug",
            status = "success",
            fileName = artifact.fileName,
            sizeBytes = size,
            sha256 = sha,
            createdAt = clock.nowIso(),
        )
        targetDir.resolve("metadata.json").writeText(json.encodeToString(ArtifactMetadata.serializer(), metadata))
        return artifact
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
