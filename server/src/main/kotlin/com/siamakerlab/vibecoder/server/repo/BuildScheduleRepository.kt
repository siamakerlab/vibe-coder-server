package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.BuildSchedules
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class BuildScheduleRow(
    val id: String,
    val projectId: String,
    val cronExpr: String,
    val variant: String,
    val enabled: Boolean,
    val createdAt: String,
    val lastFiredAt: String?,
    val description: String?,
)

class BuildScheduleRepository(private val clock: Clock) {

    fun create(projectId: String, cronExpr: String, variant: String = "debug", description: String? = null): BuildScheduleRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        BuildSchedules.insert {
            it[BuildSchedules.id] = id
            it[BuildSchedules.projectId] = projectId
            it[BuildSchedules.cronExpr] = cronExpr
            it[BuildSchedules.variant] = variant
            it[enabled] = true
            it[createdAt] = now
            it[BuildSchedules.description] = description
        }
        BuildScheduleRow(id, projectId, cronExpr, variant, true, now, null, description)
    }

    fun listAll(): List<BuildScheduleRow> = transaction {
        BuildSchedules.selectAll()
            .orderBy(BuildSchedules.createdAt to SortOrder.DESC)
            .map { it.toRow() }
    }

    fun listEnabled(): List<BuildScheduleRow> = transaction {
        BuildSchedules.selectAll().where { BuildSchedules.enabled eq true }
            .map { it.toRow() }
    }

    fun toggleEnabled(id: String, enabled: Boolean): Int = transaction {
        BuildSchedules.update({ BuildSchedules.id eq id }) {
            it[BuildSchedules.enabled] = enabled
        }
    }

    fun markFired(id: String, ts: String): Int = transaction {
        BuildSchedules.update({ BuildSchedules.id eq id }) {
            it[lastFiredAt] = ts
        }
    }

    fun delete(id: String): Int = transaction {
        BuildSchedules.deleteWhere { BuildSchedules.id eq id }
    }

    fun deleteForProject(projectId: String): Int = transaction {
        BuildSchedules.deleteWhere { BuildSchedules.projectId eq projectId }
    }

    private fun ResultRow.toRow() = BuildScheduleRow(
        id = this[BuildSchedules.id],
        projectId = this[BuildSchedules.projectId],
        cronExpr = this[BuildSchedules.cronExpr],
        variant = this[BuildSchedules.variant],
        enabled = this[BuildSchedules.enabled],
        createdAt = this[BuildSchedules.createdAt],
        lastFiredAt = this[BuildSchedules.lastFiredAt],
        description = this[BuildSchedules.description],
    )
}
