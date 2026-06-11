package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.ScheduledPrompts
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * v1.130.0 — 프롬프트 예약 전송(one-shot) 저장소.
 *
 * pending 예약은 [ScheduledPromptManager] 가 주기 폴링([listPending])해 발사하고,
 * 발사 결과를 markSent / markFailed 로 적재한다. 사용자 취소는 cancel.
 */
data class ScheduledPromptRow(
    val id: String,
    val projectId: String,
    val prompt: String,
    val triggerType: String,
    val fireAtEpochMs: Long?,
    val triggerLabel: String?,
    val baselinePercent: Int?,
    val status: String,
    val createdAt: String,
    val sentAt: String?,
    val lastError: String?,
)

class ScheduledPromptRepository(private val clock: Clock) {

    fun create(
        projectId: String,
        prompt: String,
        triggerType: String,
        fireAtEpochMs: Long?,
        triggerLabel: String?,
        baselinePercent: Int?,
    ): ScheduledPromptRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        ScheduledPrompts.insert {
            it[ScheduledPrompts.id] = id
            it[ScheduledPrompts.projectId] = projectId
            it[ScheduledPrompts.prompt] = prompt
            it[ScheduledPrompts.triggerType] = triggerType
            it[ScheduledPrompts.fireAtEpochMs] = fireAtEpochMs
            it[ScheduledPrompts.triggerLabel] = triggerLabel
            it[ScheduledPrompts.baselinePercent] = baselinePercent
            it[status] = ScheduledPromptStatus.PENDING
            it[createdAt] = now
        }
        ScheduledPromptRow(
            id, projectId, prompt, triggerType, fireAtEpochMs, triggerLabel,
            baselinePercent, ScheduledPromptStatus.PENDING, now, null, null,
        )
    }

    /** 스케줄러 폴링용 — 모든 프로젝트의 pending 예약. */
    fun listPending(): List<ScheduledPromptRow> = transaction {
        ScheduledPrompts.selectAll()
            .where { ScheduledPrompts.status eq ScheduledPromptStatus.PENDING }
            .orderBy(ScheduledPrompts.createdAt to SortOrder.ASC)
            .map { it.toRow() }
    }

    fun listForProject(projectId: String, limit: Int = 50): List<ScheduledPromptRow> = transaction {
        ScheduledPrompts.selectAll()
            .where { ScheduledPrompts.projectId eq projectId }
            .orderBy(ScheduledPrompts.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun get(id: String): ScheduledPromptRow? = transaction {
        ScheduledPrompts.selectAll()
            .where { ScheduledPrompts.id eq id }
            .limit(1)
            .map { it.toRow() }
            .firstOrNull()
    }

    fun markSent(id: String): Int = transaction {
        ScheduledPrompts.update({ (ScheduledPrompts.id eq id) and (ScheduledPrompts.status eq ScheduledPromptStatus.PENDING) }) {
            it[status] = ScheduledPromptStatus.SENT
            it[sentAt] = clock.nowIso()
            it[lastError] = null
        }
    }

    fun markFailed(id: String, error: String): Int = transaction {
        ScheduledPrompts.update({ (ScheduledPrompts.id eq id) and (ScheduledPrompts.status eq ScheduledPromptStatus.PENDING) }) {
            it[status] = ScheduledPromptStatus.FAILED
            it[sentAt] = clock.nowIso()
            it[lastError] = error.take(2000)
        }
    }

    /** 사용자 취소 — pending 일 때만. 취소된 행 수 반환. */
    fun cancel(id: String, projectId: String): Int = transaction {
        ScheduledPrompts.update({
            (ScheduledPrompts.id eq id) and
                (ScheduledPrompts.projectId eq projectId) and
                (ScheduledPrompts.status eq ScheduledPromptStatus.PENDING)
        }) {
            it[status] = ScheduledPromptStatus.CANCELLED
            it[sentAt] = clock.nowIso()
        }
    }

    fun deleteForProject(projectId: String): Int = transaction {
        ScheduledPrompts.deleteWhere { ScheduledPrompts.projectId eq projectId }
    }

    /** v1.130.0 — 프로젝트 이름변경(renameId) 시 자식 행을 새 projectId 로 repoint. */
    fun repointProject(oldId: String, newId: String): Int = transaction {
        ScheduledPrompts.update({ ScheduledPrompts.projectId eq oldId }) {
            it[projectId] = newId
        }
    }

    private fun ResultRow.toRow() = ScheduledPromptRow(
        id = this[ScheduledPrompts.id],
        projectId = this[ScheduledPrompts.projectId],
        prompt = this[ScheduledPrompts.prompt],
        triggerType = this[ScheduledPrompts.triggerType],
        fireAtEpochMs = this[ScheduledPrompts.fireAtEpochMs],
        triggerLabel = this[ScheduledPrompts.triggerLabel],
        baselinePercent = this[ScheduledPrompts.baselinePercent],
        status = this[ScheduledPrompts.status],
        createdAt = this[ScheduledPrompts.createdAt],
        sentAt = this[ScheduledPrompts.sentAt],
        lastError = this[ScheduledPrompts.lastError],
    )
}
