package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.db.ArchivedProjects
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** v1.98.0 — 아카이브된 프로젝트 한 건. */
data class ArchivedProjectRow(
    val id: String,
    val originalId: String,
    val name: String,
    val packageName: String,
    val archivedAt: String,
    val archivePath: String,
    val sizeBytes: Long,
    val manifestJson: String,
)

/** v1.98.0 — `archived_projects` 레지스트리 접근. Projects FK 무관 독립 테이블. */
class ArchivedProjectRepository {

    fun insert(row: ArchivedProjectRow): ArchivedProjectRow = transaction {
        ArchivedProjects.insert {
            it[id] = row.id
            it[originalId] = row.originalId
            it[name] = row.name
            it[packageName] = row.packageName
            it[archivedAt] = row.archivedAt
            it[archivePath] = row.archivePath
            it[sizeBytes] = row.sizeBytes
            it[manifestJson] = row.manifestJson
        }
        row
    }

    fun list(): List<ArchivedProjectRow> = transaction {
        ArchivedProjects.selectAll()
            .orderBy(ArchivedProjects.archivedAt to SortOrder.DESC)
            .map { it.toRow() }
    }

    fun findById(id: String): ArchivedProjectRow? = transaction {
        ArchivedProjects.selectAll().where { ArchivedProjects.id eq id }.map { it.toRow() }.singleOrNull()
    }

    /** 복원 충돌 사전 검사용 — 같은 originalId 또는 packageName 의 아카이브가 이미 있나. */
    fun existsOriginalOrPackage(originalId: String, packageName: String): Boolean = transaction {
        ArchivedProjects.selectAll().where {
            (ArchivedProjects.originalId eq originalId) or (ArchivedProjects.packageName eq packageName)
        }.empty().not()
    }

    fun delete(id: String): Int = transaction {
        ArchivedProjects.deleteWhere { ArchivedProjects.id eq id }
    }

    private fun ResultRow.toRow() = ArchivedProjectRow(
        id = this[ArchivedProjects.id],
        originalId = this[ArchivedProjects.originalId],
        name = this[ArchivedProjects.name],
        packageName = this[ArchivedProjects.packageName],
        archivedAt = this[ArchivedProjects.archivedAt],
        archivePath = this[ArchivedProjects.archivePath],
        sizeBytes = this[ArchivedProjects.sizeBytes],
        manifestJson = this[ArchivedProjects.manifestJson],
    )
}
