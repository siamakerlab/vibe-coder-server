package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Projects
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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
        // v1.60.0 — 새 프로젝트는 맨 위: 현재 최소 sort_order - 1 (없으면 0).
        // 프로젝트 테이블은 단일 사용자라 행 수가 적어 메모리 min 으로 충분.
        val minOrder = Projects.selectAll().minOfOrNull { it[Projects.sortOrder] }
        val topOrder = (minOrder ?: 1) - 1
        Projects.insert {
            it[Projects.id] = id
            it[Projects.name] = name
            it[Projects.packageName] = packageName
            it[Projects.sourcePath] = sourcePath
            it[Projects.moduleName] = moduleName
            it[Projects.debugTask] = debugTask
            it[createdAt] = now
            it[updatedAt] = now
            it[sortOrder] = topOrder
        }
        ProjectRow(id, name, packageName, sourcePath, moduleName, debugTask, now, now)
    }

    fun findById(id: String): ProjectRow? = transaction {
        Projects.selectAll().where { Projects.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun list(): List<ProjectRow> = transaction {
        // v1.60.0 — 사용자 정의 순서 우선, 동률은 최신 갱신순.
        Projects.selectAll()
            .orderBy(
                Projects.sortOrder to org.jetbrains.exposed.sql.SortOrder.ASC,
                Projects.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            )
            .map { it.toRow() }
    }

    /** v1.60.0 — 현재 정렬 순서의 전체 id (ghost 제외는 호출부에서). reorder 정규화용. */
    fun listIdsInOrder(): List<String> = transaction {
        Projects.selectAll()
            .orderBy(
                Projects.sortOrder to org.jetbrains.exposed.sql.SortOrder.ASC,
                Projects.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            )
            .map { it[Projects.id] }
    }

    /** v1.60.0 — 주어진 id 순서대로 sort_order = index 일괄 기록(단일 transaction). */
    fun applyOrder(ids: List<String>): Unit = transaction {
        ids.forEachIndexed { idx, id ->
            Projects.update({ Projects.id eq id }) { it[sortOrder] = idx }
        }
    }

    fun delete(id: String): Int = transaction {
        Projects.deleteWhere { Projects.id eq id }
    }

    /** v1.33.0 — scratch 디렉토리 이동(workspace → .vibecoder) 마이그레이션용 source_path 갱신. */
    fun updateSourcePath(id: String, sourcePath: String): Int = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.sourcePath] = sourcePath
            it[updatedAt] = clock.nowIso()
        }
    }

    /** v1.54.0 — Chat 세션 제목(rename / 첫 프롬프트 자동 제목) 갱신. */
    fun updateName(id: String, name: String): Int = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.name] = name
            it[updatedAt] = clock.nowIso()
        }
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
