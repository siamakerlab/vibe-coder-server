package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Projects
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class ProjectRow(
    val id: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String,
    val debugTask: String,
    val createdAt: String,
    val updatedAt: String,
)

class ProjectRepository(private val clock: Clock) {

    fun insert(
        id: String,
        name: String,
        packageName: String,
        sourcePath: String,
        moduleName: String,
        debugTask: String,
    ): ProjectRow = transaction {
        val now = clock.nowIso()
        Projects.insert {
            it[Projects.id] = id
            it[Projects.name] = name
            it[Projects.packageName] = packageName
            it[Projects.sourcePath] = sourcePath
            it[Projects.moduleName] = moduleName
            it[Projects.debugTask] = debugTask
            it[createdAt] = now
            it[updatedAt] = now
        }
        ProjectRow(id, name, packageName, sourcePath, moduleName, debugTask, now, now)
    }

    fun findById(id: String): ProjectRow? = transaction {
        Projects.select { Projects.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun list(): List<ProjectRow> = transaction {
        Projects.selectAll().orderBy(Projects.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { it.toRow() }
    }

    fun delete(id: String): Int = transaction {
        Projects.deleteWhere { Projects.id eq id }
    }

    fun count(): Int = transaction { Projects.selectAll().count().toInt() }

    private fun ResultRow.toRow() = ProjectRow(
        id = this[Projects.id],
        name = this[Projects.name],
        packageName = this[Projects.packageName],
        sourcePath = this[Projects.sourcePath],
        moduleName = this[Projects.moduleName],
        debugTask = this[Projects.debugTask],
        createdAt = this[Projects.createdAt],
        updatedAt = this[Projects.updatedAt],
    )
}
