package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.ConversationTurns
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.IsNullOp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.TextColumnType
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
 * 큰 파일 결과 등). v0.16.0–v0.52.0 까지 본문 검색은 LIKE (full scan). v0.53.0
 * 부터 PostgreSQL tsvector + GIN 인덱스로 마이그 — `content_tsv` generated
 * column ([Database.init] 에서 `IF NOT EXISTS` 로 첨가) 가 매 row 의
 * `to_tsvector('simple', content)` 를 저장하고 GIN 이 인덱스. Filter.q 매칭은
 * `content_tsv @@ plainto_tsquery('simple', ?)` 로 인덱스 사용.
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
        /**
         * v0.52.0 — agent_name filter.
         * - `null` (기본) → 메인 console 만 (`agent_name IS NULL`).
         * - 빈 string `""` → 모든 turn (메인 + 모든 sub-agent).
         * - `"<name>"` → 그 sub-agent 만.
         *
         * 기존 호출자는 agentName 을 안 넘기므로 메인 console 만 보임 — backward
         * compatible. 새 UI 가 명시적으로 `""` (all) 또는 agent 이름을 전달.
         */
        val agentName: String? = null,
    )

    private fun Filter.toCondition(): Op<Boolean> {
        var c: Op<Boolean> = ConversationTurns.projectId eq projectId
        sessionId?.let { c = c and (ConversationTurns.sessionId eq it) }
        role?.let { c = c and (ConversationTurns.role eq it) }
        toolName?.let { c = c and (ConversationTurns.toolName eq it) }
        fromTs?.let { c = c and (ConversationTurns.ts greaterEq it) }
        toTs?.let { c = c and (ConversationTurns.ts lessEq it) }
        // v0.53.0 — Phase 32 PG tsvector + GIN 풀텍스트 검색.
        // content_tsv 생성 컬럼 (Database.init() 의 raw SQL 마이그) + GIN 인덱스로
        // LIKE 의 O(N) full-scan → 인덱스 사용 O(log N) match.
        q?.let { c = c and TsvectorMatchOp(it) }
        // v0.52.0 — agent_name 필터링.
        when (agentName) {
            null -> c = c and IsNullOp(ConversationTurns.agentName)
            "" -> {}  // 모든 turn (필터 없음)
            else -> c = c and (ConversationTurns.agentName eq agentName)
        }
        return c
    }

    /**
     * v0.53.0 — `content_tsv @@ plainto_tsquery('simple', ?)`.
     *
     * `simple` configuration 은 language-agnostic — stemming / lemmatization 없이
     * 단순 토큰화. 한국어 / 영어 모두 그럭저럭 (정확 매치 best-effort). 다국어 stemming
     * 이 필요하면 PG 의 `unaccent` extension + custom config 로 교체.
     *
     * `plainto_tsquery` 가 input 을 AND 로 토큰화하므로 query 의 따옴표 / 메타문자가
     * 안전. parameter binding 으로 SQL injection 방어.
     */
    private class TsvectorMatchOp(private val q: String) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("content_tsv @@ plainto_tsquery('simple', ")
            queryBuilder.registerArgument(TextColumnType(), q)
            queryBuilder.append(")")
        }
    }

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

    /**
     * v0.52.0 — Project 내 distinct agent_name 목록 (non-null).
     * /history 페이지의 agent 필터 dropdown 을 채움.
     */
    fun distinctAgents(projectId: String): List<String> = transaction {
        ConversationTurns
            .select(ConversationTurns.agentName)
            .where { ConversationTurns.projectId eq projectId }
            .map { it[ConversationTurns.agentName] }
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
