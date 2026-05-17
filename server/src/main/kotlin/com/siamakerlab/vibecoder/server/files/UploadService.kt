package com.siamakerlab.vibecoder.server.files

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.PathSafety
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.UploadedFileRepository
import com.siamakerlab.vibecoder.server.repo.UploadedFileRow
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

class UploadService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val repo: UploadedFileRepository,
    private val clock: Clock,
) {
    fun upload(
        projectId: String,
        originalName: String,
        mimeType: String?,
        input: InputStream,
        sizeHint: Long?,
    ): UploadedFileRow {
        val safeName = sanitizeFileName(originalName)
        val ext = safeName.substringAfterLast('.', "").lowercase()
        if (ext.isNotBlank() && ext in config.workspace.uploadDeniedExtensions) {
            throw ApiException(400, "extension_blocked", ".$ext is not allowed")
        }
        if (sizeHint != null && sizeHint > config.workspace.maxUploadSizeMb * 1024L * 1024L) {
            throw ApiException(413, "file_too_large", "max ${config.workspace.maxUploadSizeMb} MB")
        }
        val day = LocalDate.now().toString().replace("-", "")
        val uploadsDir = workspace.uploadsDir(projectId).resolve(day).also { Files.createDirectories(it) }
        val id = Ids.fileId()
        val target = uploadsDir.resolve("${id}_$safeName")
        // Defense-in-depth: ensure target is inside workspace.
        workspace.ensureUnderWorkspace(target.parent)

        var copied: Long = 0
        Files.newOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            val cap = config.workspace.maxUploadSizeMb * 1024L * 1024L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                copied += n
                if (copied > cap) {
                    Files.deleteIfExists(target)
                    throw ApiException(413, "file_too_large", "max ${config.workspace.maxUploadSizeMb} MB")
                }
                out.write(buf, 0, n)
            }
        }

        return repo.create(
            id = id, projectId = projectId,
            originalName = safeName,
            filePath = target.toString(),
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    fun list(projectId: String): List<FileEntryDto> =
        repo.listForProject(projectId).map { it.toDto() }

    fun resolveForDownload(projectId: String, id: String): UploadedFileRow {
        val row = repo.get(projectId, id) ?: throw ApiException(404, "file_not_found", id)
        workspace.ensureUnderWorkspace(Path.of(row.filePath))
        return row
    }

    fun delete(projectId: String, id: String) {
        val row = repo.get(projectId, id) ?: throw ApiException(404, "file_not_found", id)
        runCatching { Files.deleteIfExists(Path.of(row.filePath)) }
        repo.delete(projectId, id)
    }

    private fun sanitizeFileName(name: String): String {
        // Strip any path components. Replace forbidden characters.
        val base = name.substringAfterLast('/').substringAfterLast('\\').take(200)
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload" }
    }

    private fun UploadedFileRow.toDto() = FileEntryDto(
        id = id, originalName = originalName, mimeType = mimeType,
        sizeBytes = sizeBytes, createdAt = createdAt,
    )
}
