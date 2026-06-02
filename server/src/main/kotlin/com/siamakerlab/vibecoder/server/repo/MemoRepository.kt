package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.Memos
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * v1.91.0 — 독립 메모(전역/프로젝트별) 저장소.
 *
 * `projectId == null` → 전역 메모(모든 프로젝트 화면에 노출).
 * `projectId != null` → 해당 프로젝트 전용.
 */
data class MemoRow(
    val id: String,
    val projectId: String?,
    val content: String,
    val createdAt: String,
    val updatedAt: String,
)

class MemoRepository(private val clock: Clock) {

    /** 메모 본문 상한 — 프롬프트 템플릿(16K)과 동일 정책. */
    private fun clamp(content: String) = content.take(MAX_CONTENT)

    fun create(projectId: String?, content: String): MemoRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        val body = clamp(content)
        Memos.insert {
            it[Memos.id] = id
            it[Memos.projectId] = projectId
            it[Memos.content] = body
            it[createdAt] = now
            it[updatedAt] = now
        }
        MemoRow(id, projectId, body, now, now)
    }

    /** content 만 갱신 (scope 유지). */
    fun updateContent(memoId: String, content: String): Boolean = transaction {
        Memos.update({ Memos.id eq memoId }) {
            it[Memos.content] = clamp(content)
            it[updatedAt] = clock.nowIso()
        } > 0
    }

    /** content + scope (projectId, null 허용) 동시 갱신. */
    fun update(memoId: String, projectId: String?, content: String): Boolean = transaction {
        Memos.update({ Memos.id eq memoId }) {
            it[Memos.projectId] = projectId
            it[Memos.content] = clamp(content)
            it[updatedAt] = clock.nowIso()
        } > 0
    }

    fun get(memoId: String): MemoRow? = transaction {
        Memos.selectAll().where { Memos.id eq memoId }.map { it.toRow() }.singleOrNull()
    }

    /** 사이드바 `/memos` — 전역 + 모든 프로젝트 메모 (최근 갱신 우선). */
    fun listAll(limit: Int = 500): List<MemoRow> = transaction {
        Memos.selectAll()
            .orderBy(Memos.updatedAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    /**
     * 프로젝트 rail — 전역 메모 + 해당 프로젝트 메모 (최근 갱신 우선).
     * 전역이 항상 먼저(프로젝트 무관 공통 노출)는 아니고 updatedAt DESC 단일 정렬.
     */
    fun listForScope(projectId: String, limit: Int = 200): List<MemoRow> = transaction {
        Memos.selectAll()
            .where { Memos.projectId.isNull() or (Memos.projectId eq projectId) }
            .orderBy(Memos.updatedAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun delete(memoId: String): Boolean = transaction {
        Memos.deleteWhere { Memos.id eq memoId } > 0
    }

    /**
     * 프로젝트 삭제 cascade — 해당 프로젝트 전용 메모만 정리. 전역 메모(NULL) 는 보존.
     * ProjectService.delete 의 단일 transaction 안에서 호출.
     */
    fun deleteForProject(projectId: String): Int = transaction {
        Memos.deleteWhere { Memos.projectId eq projectId }
    }

    private fun ResultRow.toRow() = MemoRow(
        id = this[Memos.id],
        projectId = this[Memos.projectId],
        content = this[Memos.content],
        createdAt = this[Memos.createdAt],
        updatedAt = this[Memos.updatedAt],
    )

    private companion object {
        const val MAX_CONTENT = 16_000
    }
}
