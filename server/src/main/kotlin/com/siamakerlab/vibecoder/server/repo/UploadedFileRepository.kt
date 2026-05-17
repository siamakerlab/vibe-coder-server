package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.UploadedFiles
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

data class UploadedFileRow(
    val id: String,
    val projectId: String,
    val originalName: String,
    val filePath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val createdAt: String,
)

class UploadedFileRepository(private val clock: Clock) {

    fun create(
        id: String, projectId: String, originalName: String, filePath: String,
        mimeType: String?, sizeBytes: Long,
    ): UploadedFileRow = transaction {
        val now = clock.nowIso()
        UploadedFiles.insert {
            it[UploadedFiles.id] = id
            it[UploadedFiles.projectId] = projectId
            it[UploadedFiles.originalName] = originalName
            it[UploadedFiles.filePath] = filePath
            it[UploadedFiles.mimeType] = mimeType
            it[UploadedFiles.sizeBytes] = sizeBytes
            it[createdAt] = now
        }
        UploadedFileRow(id, projectId, originalName, filePath, mimeType, sizeBytes, now)
    }

    fun get(projectId: String, id: String): UploadedFileRow? = transaction {
        UploadedFiles.select { (UploadedFiles.projectId eq projectId) and (UploadedFiles.id eq id) }
            .map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String): List<UploadedFileRow> = transaction {
        UploadedFiles.select { UploadedFiles.projectId eq projectId }
            .orderBy(UploadedFiles.createdAt to SortOrder.DESC)
            .map { it.toRow() }
    }

    fun delete(projectId: String, id: String): Int = transaction {
        UploadedFiles.deleteWhere { (UploadedFiles.projectId eq projectId) and (UploadedFiles.id eq id) }
    }

    private fun ResultRow.toRow() = UploadedFileRow(
        id = this[UploadedFiles.id],
        projectId = this[UploadedFiles.projectId],
        originalName = this[UploadedFiles.originalName],
        filePath = this[UploadedFiles.filePath],
        mimeType = this[UploadedFiles.mimeType],
        sizeBytes = this[UploadedFiles.sizeBytes],
        createdAt = this[UploadedFiles.createdAt],
    )
}
