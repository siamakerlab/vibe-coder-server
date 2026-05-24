package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.ConversationTurns
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.IsNullOp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class ConversationTurnRow(
    val id: String,
    val projectId: String,
    val sessionId: String?,
    val turnIdx: Int,
    val ts: String,
    val role: String,
    val content: String,
    val toolName: String?,
    val toolUseId: String?,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val raw: String?,
    /** v0.49.0 — null = main project console; non-null = sub-agent. */
    val agentName: String? = null,
)

/**
 * v0.16.0 — Conversation turn repository.
 *
 * 적재는 [insert] 한 번. 읽기는 페이지네이션 list + 필터.
 *
 * turnIdx 는 (projectId, sessionId) 단위로 단조 증가. 같은 sessionId 안에서 row
 * 정렬을 사용자에게 안정적으로 보여주기 위함. session 이 새로 시작되면 turnIdx 가
 * 0 부터 다시 시작 (시각 정렬은 ts 로).
 *
 * **성능 메모**: tool_use/tool_result content 가 수십 KB 일 수 있음 (Read tool 의
 * 큰 파일 결과 등). 본문 검색은 v0.16.0 에선 LIKE (성능 비추), 다음 cycle 에서
 * PostgreSQL tsvector + GIN 으로 교체 예정.
 */
class ConversationTurnRepository(private val clock: Clock) {

    fun insert(
        projectId: String,
        sessionId: String?,
        role: String,
        content: String,
        toolName: String? = null,
        toolUseId: String? = null,
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        raw: String? = null,
        /** v0.49.0 — null = main project console; non-null = sub-agent name. */
        agentName: String? = null,
    ): ConversationTurnRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        val next = nextTurnIdx(projectId, sessionId)
        ConversationTurns.insert {
            it[ConversationTurns.id] = id
            it[ConversationTurns.projectId] = projectId
            it[ConversationTurns.sessionId] = sessionId
            it[turnIdx] = next
            it[ts] = now
            it[ConversationTurns.role] = role
            it[ConversationTurns.content] = content
            it[ConversationTurns.toolName] = toolName
            it[ConversationTurns.toolUseId] = toolUseId
            it[ConversationTurns.tokensIn] = tokensIn
            it[ConversationTurns.tokensOut] = tokensOut
            it[ConversationTurns.raw] = raw
            it[ConversationTurns.agentName] = agentName
        }
        ConversationTurnRow(
            id, projectId, sessionId, next, now, role, content,
            toolName, toolUseId, tokensIn, tokensOut, raw, agentName,
        )
    }

    private fun nextTurnIdx(projectId: String, sessionId: String?): Int {
        val cond: Op<Boolean> = if (sessionId == null) {
            (ConversationTurns.projectId eq projectId) and IsNullOp(ConversationTurns.sessionId)
        } else {
            (ConversationTurns.projectId eq projectId) and (ConversationTurns.sessionId eq sessionId)
        }
        val agg = ConversationTurns.turnIdx.max()
        val current = ConversationTurns
            .select(agg)
            .where { cond }
            .single()[agg]
        return (current ?: -1) + 1
    }

    data class Filter(
        val projectId: String,
        val sessionId: String? = null,
        val role: String? = null,
        val toolName: String? = null,
        val fromTs: String? = null,
        val toTs: String? = null,
        /** content LIKE %query% — v0.16.0 는 단순. 다음 cycle 에서 FTS 교체. */
        val q: String? = null,
    )

    private fun Filter.toCondition(): Op<Boolean> {
        var c: Op<Boolean> = ConversationTurns.projectId eq projectId
        sessionId?.let { c = c and (ConversationTurns.sessionId eq it) }
        role?.let { c = c and (ConversationTurns.role eq it) }
        toolName?.let { c = c and (ConversationTurns.toolName eq it) }
        fromTs?.let { c = c and (ConversationTurns.ts greaterEq it) }
        toTs?.let { c = c and (ConversationTurns.ts lessEq it) }
        q?.let { c = c and (ConversationTurns.content like "%${escapeLike(it)}%") }
        return c
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun list(filter: Filter, limit: Int = 200, offset: Long = 0): List<ConversationTurnRow> = transaction {
        ConversationTurns.selectAll().where { filter.toCondition() }
            .orderBy(ConversationTurns.ts to SortOrder.ASC)
            .limit(limit.coerceIn(1, 1000)).offset(offset.coerceAtLeast(0))
            .map { it.toRow() }
    }

    fun count(filter: Filter): Long = transaction {
        ConversationTurns.selectAll().where { filter.toCondition() }.count()
    }

    /** Project 내 distinct sessionId 목록 — UI dropdown 채움. */
    fun distinctSessions(projectId: String): List<String> = transaction {
        ConversationTurns
            .select(ConversationTurns.sessionId)
            .where { ConversationTurns.projectId eq projectId }
            .map { it[ConversationTurns.sessionId] }
            .filterNotNull()
            .distinct()
            .sorted()
    }

    /** Project 통째 삭제 (ProjectService.delete cascade). */
    fun deleteForProject(projectId: String): Int = transaction {
        ConversationTurns.deleteWhere { ConversationTurns.projectId eq projectId }
    }

    private fun ResultRow.toRow() = ConversationTurnRow(
        id = this[ConversationTurns.id],
        projectId = this[ConversationTurns.projectId],
        sessionId = this[ConversationTurns.sessionId],
        turnIdx = this[ConversationTurns.turnIdx],
        ts = this[ConversationTurns.ts],
        role = this[ConversationTurns.role],
        content = this[ConversationTurns.content],
        toolName = this[ConversationTurns.toolName],
        toolUseId = this[ConversationTurns.toolUseId],
        tokensIn = this[ConversationTurns.tokensIn],
        tokensOut = this[ConversationTurns.tokensOut],
        raw = this[ConversationTurns.raw],
        agentName = this[ConversationTurns.agentName],
    )
}
