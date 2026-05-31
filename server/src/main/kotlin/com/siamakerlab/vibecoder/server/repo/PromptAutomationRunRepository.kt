package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.PromptAutomationRuns
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * v1.59.0 — 프롬프트 자동화 실행 이력 저장소.
 *
 * active 진행 상태는 PromptAutomationManager 의 in-memory 맵이 SSOT 이고,
 * 본 repo 는 시작/진행/종료 이력 적재 + 부팅 reconcile 담당.
 */
data class PromptAutomationRunRow(
    val id: String,
    val projectId: String,
    val name: String,
    val mode: String,
    val total: Int,
    val sent: Int,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val lastError: String?,
)

class PromptAutomationRunRepository(private val clock: Clock) {

    /** RUNNING 상태로 새 run 생성. */
    fun create(projectId: String, name: String, mode: String, total: Int): PromptAutomationRunRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        PromptAutomationRuns.insert {
            it[PromptAutomationRuns.id] = id
            it[PromptAutomationRuns.projectId] = projectId
            it[PromptAutomationRuns.name] = name
            it[PromptAutomationRuns.mode] = mode
            it[PromptAutomationRuns.total] = total
            it[sent] = 0
            it[status] = PromptAutomationStatus.RUNNING
            it[startedAt] = now
        }
        PromptAutomationRunRow(id, projectId, name, mode, total, 0, PromptAutomationStatus.RUNNING, now, null, null)
    }

    /** 발사 카운트 갱신 (진행 중). */
    fun updateSent(id: String, sent: Int): Int = transaction {
        PromptAutomationRuns.update({ PromptAutomationRuns.id eq id }) {
            it[PromptAutomationRuns.sent] = sent
        }
    }

    /** run 종료 (done/stopped/failed). */
    fun finish(id: String, status: String, sent: Int, lastError: String? = null): Int = transaction {
        PromptAutomationRuns.update({ PromptAutomationRuns.id eq id }) {
            it[PromptAutomationRuns.status] = status
            it[PromptAutomationRuns.sent] = sent
            it[finishedAt] = clock.nowIso()
            it[PromptAutomationRuns.lastError] = lastError
        }
    }

    fun listForProject(projectId: String, limit: Int = 20): List<PromptAutomationRunRow> = transaction {
        PromptAutomationRuns.selectAll()
            .where { PromptAutomationRuns.projectId eq projectId }
            .orderBy(PromptAutomationRuns.startedAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun lastForProject(projectId: String): PromptAutomationRunRow? = transaction {
        PromptAutomationRuns.selectAll()
            .where { PromptAutomationRuns.projectId eq projectId }
            .orderBy(PromptAutomationRuns.startedAt to SortOrder.DESC)
            .limit(1)
            .map { it.toRow() }
            .firstOrNull()
    }

    /**
     * 부팅 reconcile — in-memory active 가 사라진 뒤 DB 에 남은 `running` 행을
     * `stopped` 로 정리. 반환값 = 정리된 행 수.
     */
    fun reconcileOrphans(): Int = transaction {
        PromptAutomationRuns.update({ PromptAutomationRuns.status eq PromptAutomationStatus.RUNNING }) {
            it[status] = PromptAutomationStatus.STOPPED
            it[finishedAt] = clock.nowIso()
            it[lastError] = "orphaned_by_restart"
        }
    }

    fun deleteForProject(projectId: String): Int = transaction {
        PromptAutomationRuns.deleteWhere { PromptAutomationRuns.projectId eq projectId }
    }

    private fun ResultRow.toRow() = PromptAutomationRunRow(
        id = this[PromptAutomationRuns.id],
        projectId = this[PromptAutomationRuns.projectId],
        name = this[PromptAutomationRuns.name],
        mode = this[PromptAutomationRuns.mode],
        total = this[PromptAutomationRuns.total],
        sent = this[PromptAutomationRuns.sent],
        status = this[PromptAutomationRuns.status],
        startedAt = this[PromptAutomationRuns.startedAt],
        finishedAt = this[PromptAutomationRuns.finishedAt],
        lastError = this[PromptAutomationRuns.lastError],
    )
}
