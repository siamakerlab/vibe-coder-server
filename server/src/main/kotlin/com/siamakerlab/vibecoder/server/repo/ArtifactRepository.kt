package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Artifacts
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

data class ArtifactRow(
    val id: String,
    val projectId: String,
    val buildId: String,
    val type: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: String,
)

class ArtifactRepository(private val clock: Clock) {

    fun create(
        id: String,
        projectId: String,
        buildId: String,
        type: String,
        fileName: String,
        filePath: String,
        sizeBytes: Long,
        sha256: String,
    ): ArtifactRow = transaction {
        val now = clock.nowIso()
        Artifacts.insert {
            it[Artifacts.id] = id
            it[Artifacts.projectId] = projectId
            it[Artifacts.buildId] = buildId
            it[Artifacts.type] = type
            it[Artifacts.fileName] = fileName
            it[Artifacts.filePath] = filePath
            it[Artifacts.sizeBytes] = sizeBytes
            it[Artifacts.sha256] = sha256
            it[createdAt] = now
        }
        ArtifactRow(id, projectId, buildId, type, fileName, filePath, sizeBytes, sha256, now)
    }

    fun get(projectId: String, artifactId: String): ArtifactRow? = transaction {
        Artifacts.select { (Artifacts.projectId eq projectId) and (Artifacts.id eq artifactId) }
            .map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 50): List<ArtifactRow> = transaction {
        Artifacts.select { Artifacts.projectId eq projectId }
            .orderBy(Artifacts.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    private fun ResultRow.toRow() = ArtifactRow(
        id = this[Artifacts.id],
        projectId = this[Artifacts.projectId],
        buildId = this[Artifacts.buildId],
        type = this[Artifacts.type],
        fileName = this[Artifacts.fileName],
        filePath = this[Artifacts.filePath],
        sizeBytes = this[Artifacts.sizeBytes],
        sha256 = this[Artifacts.sha256],
        createdAt = this[Artifacts.createdAt],
    )
}
