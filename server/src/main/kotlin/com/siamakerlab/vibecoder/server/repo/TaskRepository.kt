package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Tasks
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import com.siamakerlab.vibecoder.shared.dto.TaskType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class TaskRow(
    val id: String,
    val projectId: String,
    val type: TaskType,
    val status: TaskStatus,
    val title: String,
    val prompt: String?,
    val logPath: String?,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val createdAt: String,
)

class TaskRepository(private val clock: Clock) {

    fun create(
        id: String,
        projectId: String,
        type: TaskType,
        title: String,
        prompt: String?,
        logPath: String?,
    ): TaskRow = transaction {
        val now = clock.nowIso()
        Tasks.insert {
            it[Tasks.id] = id
            it[Tasks.projectId] = projectId
            it[Tasks.type] = type.name
            it[status] = TaskStatus.PENDING.name
            it[Tasks.title] = title
            it[Tasks.prompt] = prompt
            it[Tasks.logPath] = logPath
            it[createdAt] = now
        }
        TaskRow(id, projectId, type, TaskStatus.PENDING, title, prompt, logPath, null, null, null, now)
    }

    fun setStatus(id: String, status: TaskStatus, errorMessage: String? = null) = transaction {
        val now = clock.nowIso()
        Tasks.update({ Tasks.id eq id }) {
            it[Tasks.status] = status.name
            when (status) {
                TaskStatus.RUNNING -> it[startedAt] = now
                TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED, TaskStatus.TIMEOUT -> {
                    it[finishedAt] = now
                    if (errorMessage != null) it[Tasks.errorMessage] = errorMessage
                }
                else -> Unit
            }
        }
    }

    fun get(id: String): TaskRow? = transaction {
        Tasks.select { Tasks.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 50): List<TaskRow> = transaction {
        Tasks.select { Tasks.projectId eq projectId }
            .orderBy(Tasks.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun countRunning(): Int = transaction {
        Tasks.select { Tasks.status eq TaskStatus.RUNNING.name }.count().toInt()
    }

    fun listForProjectAndType(projectId: String, type: TaskType, limit: Int = 50): List<TaskRow> = transaction {
        Tasks.select { (Tasks.projectId eq projectId) and (Tasks.type eq type.name) }
            .orderBy(Tasks.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    private fun ResultRow.toRow() = TaskRow(
        id = this[Tasks.id],
        projectId = this[Tasks.projectId],
        type = TaskType.valueOf(this[Tasks.type]),
        status = TaskStatus.valueOf(this[Tasks.status]),
        title = this[Tasks.title],
        prompt = this[Tasks.prompt],
        logPath = this[Tasks.logPath],
        errorMessage = this[Tasks.errorMessage],
        startedAt = this[Tasks.startedAt],
        finishedAt = this[Tasks.finishedAt],
        createdAt = this[Tasks.createdAt],
    )
}
