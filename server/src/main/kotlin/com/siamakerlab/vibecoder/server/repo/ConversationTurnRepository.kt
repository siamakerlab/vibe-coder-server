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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

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
    /** v1.146.0 — main console provider namespace. */
    val provider: String = ConversationTurnRepository.PROVIDER_CLAUDE,
    /** v0.49.0 — null = main project console; non-null = sub-agent. */
    val agentName: String? = null,
    /** v0.61.0 — user memo on this turn (UI inline editor). */
    val userMemo: String? = null,
    /** v0.61.0 — starred flag (UI ☆ toggle). */
    val starred: Boolean = false,
)

/**
 * v0.16.0 — Conversation turn repository.
 *
 * 적재는 [insert] 한 번. 읽기는 페이지네이션 list + 필터.
 *
 * turnIdx 는 (projectId, sessionId) 단위로 **best-effort 단조 증가**. session 이 새로
 * 시작되면 0 부터 다시 시작.
 *
 * 21차 점검(minor) — [nextTurnIdx] 의 `SELECT max(turn_idx)+1` 과 INSERT 는 비원자적
 * (READ COMMITTED). 같은 (projectId, sessionId) 에 두 코루틴(예: sendPrompt 의 user
 * turn 적재 vs stdout reader 의 event 적재)이 동시에 적재하면 같은 turnIdx 가 INSERT
 * 될 수 있다((projectId, sessionId, turnIdx) 인덱스가 non-unique 라 DB 가 막지 않음).
 * **단 모든 읽기 경로(list/검색/export)가 ts ASC 로 정렬하고 turnIdx 를 정렬 키로 쓰지
 * 않으므로 사용자 체감 영향은 없다** — SSOT 는 ts. 엄밀한 보장이 필요해지면 인덱스를
 * unique 로 올리고 충돌 재시도 루프를 추가할 것.
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
        provider: String = PROVIDER_CLAUDE,
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
        val normalizedProvider = normalizeProvider(provider)
        val next = nextTurnIdx(projectId, normalizedProvider, sessionId)
        ConversationTurns.insert {
            it[ConversationTurns.id] = id
            it[ConversationTurns.projectId] = projectId
            it[ConversationTurns.provider] = normalizedProvider
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
            id = id,
            projectId = projectId,
            sessionId = sessionId,
            turnIdx = next,
            ts = now,
            role = role,
            content = content,
            toolName = toolName,
            toolUseId = toolUseId,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            raw = raw,
            provider = normalizedProvider,
            agentName = agentName,
        )
    }

    private fun nextTurnIdx(projectId: String, provider: String, sessionId: String?): Int {
        val cond: Op<Boolean> = if (sessionId == null) {
            (ConversationTurns.projectId eq projectId) and
                (ConversationTurns.provider eq provider) and
                IsNullOp(ConversationTurns.sessionId)
        } else {
            (ConversationTurns.projectId eq projectId) and
                (ConversationTurns.provider eq provider) and
                (ConversationTurns.sessionId eq sessionId)
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
        /** null = all providers. Console restore passes the selected provider explicitly. */
        val provider: String? = null,
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
        /** v0.61.0 — true = `starred=true` 만, false (기본) = 필터 안 함. */
        val starredOnly: Boolean = false,
        /** v1.129.0 — 이 turnIdx 미만(과거)만. 콘솔 "더보기" 페이지네이션용. */
        val beforeTurnIdx: Int? = null,
    )

    private fun Filter.toCondition(): Op<Boolean> {
        var c: Op<Boolean> = ConversationTurns.projectId eq projectId
        provider?.let { c = c and (ConversationTurns.provider eq normalizeProvider(it)) }
        sessionId?.let { c = c and (ConversationTurns.sessionId eq it) }
        role?.let { c = c and (ConversationTurns.role eq it) }
        toolName?.let { c = c and (ConversationTurns.toolName eq it) }
        fromTs?.let { c = c and (ConversationTurns.ts greaterEq it) }
        toTs?.let { c = c and (ConversationTurns.ts lessEq it) }
        beforeTurnIdx?.let { c = c and (ConversationTurns.turnIdx lessEq (it - 1)) }
        // v0.53.0 — Phase 32 PG tsvector + GIN 풀텍스트 검색.
        // v0.62.0 — Phase 41 한국어 / non-ASCII 포함 query 는 trigram (ILIKE %q%) 으로
        //          자동 분기. simple tsvector 가 한국어 형태소 분석을 안 해서 정확도가
        //          낮은 문제 회피. pg_trgm GIN 인덱스가 ILIKE 도 인덱스 사용.
        q?.let { rawQ ->
            // v0.75.0 — Phase 58 #8: 환경 변수 VIBECODER_MECAB_ENABLED=true 시 mecab-ko
            // PG 함수 사용 (siamakerlab/postgres-mecab-ko:17 image + init-mecab.sql 실행 전제).
            // 미설정 / 함수 부재 시 기존 trigram (ILIKE) fallback.
            c = c and when {
                isAsciiOnly(rawQ) -> TsvectorMatchOp(rawQ)
                MECAB_ENABLED -> MecabTokenMatchOp(rawQ)
                else -> TrigramIlikeOp(rawQ)
            }
        }
        // v0.52.0 — agent_name 필터링.
        when (agentName) {
            null -> c = c and IsNullOp(ConversationTurns.agentName)
            "" -> {}  // 모든 turn (필터 없음)
            else -> c = c and (ConversationTurns.agentName eq agentName)
        }
        // v0.61.0 — starred 필터.
        if (starredOnly) {
            c = c and (ConversationTurns.starred eq true)
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

    /**
     * v0.62.0 — Phase 41 한국어 부분 매치용 ILIKE %q%. pg_trgm GIN 인덱스가 활성화되어
     * 있으므로 N 만 줄여도 인덱스 사용. tsvector 가 못 잡는 형태소-mid substring 매치
     * (예: "개발자가" 에 대한 "개발자" query) 를 처리.
     *
     * %, _, \ 같은 LIKE 메타문자는 escape — 사용자 input 그대로 substring 매치.
     */
    private class TrigramIlikeOp(private val q: String) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            queryBuilder.append("content ILIKE ")
            queryBuilder.registerArgument(TextColumnType(), "%$escaped%")
        }
    }

    /**
     * v0.75.0 — Phase 58 #8. mecab-ko 형태소 분석 array overlap.
     *
     * 활성화 전제:
     *  - PG 가 siamakerlab/postgres-mecab-ko:17 이미지 (docker/postgres-mecab/Dockerfile).
     *  - init-mecab.sql 실행 (mecab_kor_tokens, mecab_kor_query SQL 함수 정의).
     *  - 환경 변수 VIBECODER_MECAB_ENABLED=true.
     *
     * Query: `mecab_kor_tokens(content) && mecab_kor_query(?)` — array overlap.
     * generated column + GIN 인덱스 추가 시 빠름 (init-mecab.sql 의 주석 참조).
     */
    private class MecabTokenMatchOp(private val q: String) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            queryBuilder.append("mecab_kor_tokens(content) && mecab_kor_query(")
            queryBuilder.registerArgument(TextColumnType(), q)
            queryBuilder.append(")")
        }
    }

    companion object {
        const val PROVIDER_CLAUDE = "claude"

        fun normalizeProvider(provider: String): String =
            provider.trim().lowercase().ifBlank { PROVIDER_CLAUDE }.take(16)

        /** v0.75.0 — mecab 활성화 flag. 부팅 시 한 번만 평가. */
        private val MECAB_ENABLED: Boolean =
            System.getenv("VIBECODER_MECAB_ENABLED")?.lowercase()?.let { it == "true" || it == "1" } == true
    }

    /** ASCII printable 만 포함하면 tsvector (영어), 한 글자라도 non-ASCII 면 trigram. */
    private fun isAsciiOnly(s: String): Boolean = s.all { it.code in 0x20..0x7e }

    fun list(filter: Filter, limit: Int = 200, offset: Long = 0): List<ConversationTurnRow> = transaction {
        ConversationTurns.selectAll().where { filter.toCondition() }
            .orderBy(ConversationTurns.ts to SortOrder.ASC)
            .limit(limit.coerceIn(1, 1000)).offset(offset.coerceAtLeast(0))
            .map { it.toRow() }
    }

    fun count(filter: Filter): Long = transaction {
        ConversationTurns.selectAll().where { filter.toCondition() }.count()
    }

    /**
     * v1.133.0 — (projectId, sessionId, turnIdx) 단건 조회. 콘솔 이력 복원 시
     * `/claude/console/image?turn=N&idx=M` 이 이 row 들에서 이미지 base64 를 꺼내 서빙.
     * turnIdx 가 race 로 중복될 수 있어(클래스 KDoc 참조) List 반환 — 호출자가
     * 이미지를 가진 첫 row 를 고른다. agent_name IS NULL(메인 콘솔)만.
     */
    fun byTurnIdx(
        projectId: String,
        sessionId: String,
        turnIdx: Int,
        provider: String = PROVIDER_CLAUDE,
    ): List<ConversationTurnRow> = transaction {
        ConversationTurns.selectAll()
            .where {
                (ConversationTurns.projectId eq projectId) and
                    (ConversationTurns.provider eq normalizeProvider(provider)) and
                    (ConversationTurns.sessionId eq sessionId) and
                    (ConversationTurns.turnIdx eq turnIdx) and
                    IsNullOp(ConversationTurns.agentName)
            }
            .orderBy(ConversationTurns.ts to SortOrder.ASC)
            .limit(10)
            .map { it.toRow() }
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

    /**
     * v1.54.0 — Chat 목록의 제목 자동 생성용. 해당 프로젝트의 가장 이른 사용자 turn
     * (role="user", 메인 console = agent_name IS NULL) 본문. 없으면 null.
     */
    fun firstUserContent(projectId: String): String? = transaction {
        ConversationTurns.selectAll()
            .where {
                (ConversationTurns.projectId eq projectId) and
                    (ConversationTurns.role eq "user") and
                    IsNullOp(ConversationTurns.agentName)
            }
            .orderBy(ConversationTurns.ts to SortOrder.ASC)
            .limit(1)
            .firstOrNull()?.get(ConversationTurns.content)
    }

    /**
     * v1.54.0 — Chat 목록 "최근 활동순" 정렬용. 해당 프로젝트의 마지막 turn ts.
     * turn 이 하나도 없으면 null.
     */
    fun lastTs(projectId: String): String? = transaction {
        val agg = ConversationTurns.ts.max()
        ConversationTurns.select(agg)
            .where { ConversationTurns.projectId eq projectId }
            .firstOrNull()?.get(agg)
    }

    /**
     * v1.137.2 — 모든 프로젝트의 마지막 turn ts 일괄 조회 (projectId → max(ts)).
     * 프로젝트 이동 콤보박스의 "최근 활동순" 2차 정렬용 — busy(in-memory) 가 서버
     * 재시작으로 사라져도 영속 데이터 기반이라 순서가 유지된다. (project_id, ts)
     * 인덱스로 GROUP BY 한 번에 해석. ts 는 동일 포맷 ISO-8601 이라 문자열 max 로 충분.
     */
    fun lastTsByProject(): Map<String, String> = transaction {
        val agg = ConversationTurns.ts.max()
        ConversationTurns.select(ConversationTurns.projectId, agg)
            .groupBy(ConversationTurns.projectId)
            .mapNotNull { row -> row[agg]?.let { row[ConversationTurns.projectId] to it } }
            .toMap()
    }

    /**
     * v1.157.2 — 프로젝트 이동 콤보 정렬용 마지막 사용자 프롬프트 송신 시각.
     * assistant/tool/system turn 은 세션 진행 중 계속 추가되므로 콤보 순서를 흔들 수 있다.
     * 메인 콘솔의 user turn 만 기준으로 삼아 "사용자가 프롬프트를 보낸 순서"를 유지한다.
     */
    fun lastUserPromptTsByProject(): Map<String, String> = transaction {
        val agg = ConversationTurns.ts.max()
        ConversationTurns.select(ConversationTurns.projectId, agg)
            .where {
                (ConversationTurns.role eq "user") and
                    IsNullOp(ConversationTurns.agentName)
            }
            .groupBy(ConversationTurns.projectId)
            .mapNotNull { row -> row[agg]?.let { row[ConversationTurns.projectId] to it } }
            .toMap()
    }

    /**
     * v1.60.0 — 상태칩 "중지됨" 판정용. 메인 콘솔(agent_name IS NULL)의 **최신 user
     * 프롬프트 이후 완료 row(assistant/usage)가 없으면 true** (= 응답이 시작/완료되지
     * 못하고 끊김: cancel / crash / 서버중단). user 프롬프트가 아예 없으면 false.
     *
     * ts 는 ISO 문자열이라 lexicographic max = chronological (lastTs 와 동일 가정).
     */
    fun lastPromptInterrupted(projectId: String): Boolean = transaction {
        val agg = ConversationTurns.ts.max()
        val lastUserTs = ConversationTurns.select(agg)
            .where {
                (ConversationTurns.projectId eq projectId) and
                    (ConversationTurns.role eq "user") and
                    IsNullOp(ConversationTurns.agentName)
            }
            .firstOrNull()?.get(agg)
            ?: return@transaction false
        val completed = ConversationTurns.selectAll()
            .where {
                (ConversationTurns.projectId eq projectId) and
                    (ConversationTurns.role inList listOf("assistant", "usage")) and
                    IsNullOp(ConversationTurns.agentName) and
                    (ConversationTurns.ts greater lastUserTs)
            }
            .limit(1)
            .firstOrNull() != null
        !completed
    }

    private fun ResultRow.toRow() = ConversationTurnRow(
        id = this[ConversationTurns.id],
        projectId = this[ConversationTurns.projectId],
        provider = this[ConversationTurns.provider],
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
        userMemo = this[ConversationTurns.userMemo],
        starred = this[ConversationTurns.starred],
    )

    // ── v0.61.0 — Phase 40 memo + star ─────────────────────────────────────

    /** Returns true if row existed + was updated. */
    fun setMemo(turnId: String, memo: String?): Boolean = transaction {
        ConversationTurns.update({ ConversationTurns.id eq turnId }) {
            it[ConversationTurns.userMemo] = memo?.takeIf { v -> v.isNotBlank() }?.take(8000)
        } > 0
    }

    fun setStarred(turnId: String, starred: Boolean): Boolean = transaction {
        ConversationTurns.update({ ConversationTurns.id eq turnId }) {
            it[ConversationTurns.starred] = starred
        } > 0
    }

    /**
     * v1.91.5 — 새 세션 첫 턴에서 sessionId 미발급(NULL) 상태로 저장된 메인 콘솔 turn 을,
     * Claude init 이벤트로 확정된 실제 session_id 로 backfill.
     *
     * 콘솔 복원(initialHistory)이 현재 session_id 로 필터하므로, NULL 로 남은 user 프롬프트는
     * 페이지 재방문 시 누락됐다("가끔 직전 프롬프트만 안 보임"). agent_name IS NULL 로
     * 메인 콘솔 turn 만 대상(sub-agent turn 불간섭). 반환값은 갱신된 row 수.
     */
    fun adoptNullSession(
        projectId: String,
        sessionId: String,
        provider: String = PROVIDER_CLAUDE,
    ): Int = transaction {
        ConversationTurns.update({
            (ConversationTurns.projectId eq projectId) and
                (ConversationTurns.provider eq normalizeProvider(provider)) and
                ConversationTurns.sessionId.isNull() and
                ConversationTurns.agentName.isNull()
        }) {
            it[ConversationTurns.sessionId] = sessionId
        }
    }

    fun findById(turnId: String): ConversationTurnRow? = transaction {
        ConversationTurns.selectAll().where { ConversationTurns.id eq turnId }
            .firstOrNull()?.toRow()
    }

    /**
     * v0.63.0 — Phase 42 prompt cache usage 집계. `role = "usage"` row 의 content
     * JSON 을 walk 해 input / output / cacheRead / cacheCreate 합산.
     */
    data class UsageSummary(
        val turns: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
        val cacheCreationTokens: Long,
    ) {
        val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
        /** 0..100 (Double) — totalInput 의 cacheRead 비중. */
        val cacheHitRate: Double? = if (totalInput == 0L) null
            else cacheReadTokens.toDouble() * 100 / totalInput
    }

    fun usageSummary(projectId: String): UsageSummary = transaction {
        val rows = ConversationTurns
            .selectAll()
            .where { (ConversationTurns.projectId eq projectId) and (ConversationTurns.role eq "usage") }
            .map { it[ConversationTurns.content] }
        var input = 0L; var output = 0L; var cr = 0L; var cc = 0L
        val re = Regex("\"(input|output|cacheRead|cacheCreate)\":(\\d+)")
        for (content in rows) {
            for (m in re.findAll(content)) {
                val v = m.groupValues[2].toLongOrNull() ?: continue
                when (m.groupValues[1]) {
                    "input" -> input += v
                    "output" -> output += v
                    "cacheRead" -> cr += v
                    "cacheCreate" -> cc += v
                }
            }
        }
        UsageSummary(
            turns = rows.size,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cr,
            cacheCreationTokens = cc,
        )
    }
}
