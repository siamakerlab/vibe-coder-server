package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Builds
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class BuildRow(
    val id: String,
    val projectId: String,
    val variant: String,
    val status: TaskStatus,
    val logPath: String?,
    val artifactId: String?,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val createdAt: String,
)

class BuildRepository(private val clock: Clock) {

    fun create(id: String, projectId: String, variant: String, logPath: String): BuildRow = transaction {
        val now = clock.nowIso()
        Builds.insert {
            it[Builds.id] = id
            it[Builds.projectId] = projectId
            it[Builds.variant] = variant
            it[status] = TaskStatus.PENDING.name
            it[Builds.logPath] = logPath
            it[createdAt] = now
        }
        BuildRow(id, projectId, variant, TaskStatus.PENDING, logPath, null, null, null, null, now)
    }

    fun setStatus(id: String, status: TaskStatus, errorMessage: String? = null) = transaction {
        val now = clock.nowIso()
        Builds.update({ Builds.id eq id }) {
            it[Builds.status] = status.name
            when (status) {
                TaskStatus.RUNNING -> it[startedAt] = now
                TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED, TaskStatus.TIMEOUT -> {
                    it[finishedAt] = now
                    if (errorMessage != null) it[Builds.errorMessage] = errorMessage
                }
                else -> Unit
            }
        }
    }

    fun attachArtifact(id: String, artifactId: String) = transaction {
        Builds.update({ Builds.id eq id }) { it[Builds.artifactId] = artifactId }
    }

    fun get(id: String): BuildRow? = transaction {
        Builds.selectAll().where { Builds.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 50): List<BuildRow> = transaction {
        Builds.selectAll().where { Builds.projectId eq projectId }
            .orderBy(Builds.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun lastForProject(projectId: String): BuildRow? = listForProject(projectId, 1).firstOrNull()

    /** Number of builds currently in PENDING or RUNNING state across the whole server. */
    fun countRunning(): Int = transaction {
        Builds.selectAll()
            .where { (Builds.status eq TaskStatus.RUNNING.name) or (Builds.status eq TaskStatus.PENDING.name) }
            .count()
            .toInt()
    }

    private fun ResultRow.toRow() = BuildRow(
        id = this[Builds.id],
        projectId = this[Builds.projectId],
        variant = this[Builds.variant],
        status = TaskStatus.valueOf(this[Builds.status]),
        logPath = this[Builds.logPath],
        artifactId = this[Builds.artifactId],
        errorMessage = this[Builds.errorMessage],
        startedAt = this[Builds.startedAt],
        finishedAt = this[Builds.finishedAt],
        createdAt = this[Builds.createdAt],
    )
}
