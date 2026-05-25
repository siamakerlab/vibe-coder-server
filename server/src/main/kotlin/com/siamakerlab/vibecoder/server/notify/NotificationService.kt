package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.NotificationEvents
import com.siamakerlab.vibecoder.shared.dto.NotificationEventDto
import com.siamakerlab.vibecoder.shared.dto.NotificationKind
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * v0.68.0 → v0.71.0 — Phase 47 + Phase 51 #3.
 *
 * Polling-based notification system. v0.71.0 부터 in-memory queue 에서 PG 영속화로 전환.
 * BuildService / ClaudeSessionManager 등이 [emit] 호출 → fan-out to all users.
 *
 * Persistence: `notification_events` 테이블 (Schemas.kt).
 *  - 사용자별 max 500 (over-limit 시 가장 오래된 것 부터 ack 처리해서 list 에서 빠짐).
 *  - 일정 주기 cron job 으로 30일 이상 ack 된 항목 hard delete (별도 cycle).
 *
 * 호환성: list/count/ack API 는 in-memory 와 동일 — 호출처 (NotificationRoutes) 변경 X.
 */
class NotificationService(
    private val clock: Clock,
) {

    companion object {
        const val USER_MAX = 500
        /** v0.4.0 이전 single-user 모드 호환 — 모든 anonymous device 가 공유. */
        const val BUCKET_LEGACY = "__legacy__"
    }

    fun emit(
        kind: String,
        title: String,
        body: String = "",
        deepLink: String? = null,
        projectId: String? = null,
        userIds: List<String?> = emptyList(),
    ) {
        val now = clock.nowIso()
        val targets = if (userIds.isEmpty()) listOf(BUCKET_LEGACY)
        else userIds.map { it ?: BUCKET_LEGACY }.distinct()
        transaction {
            for (uid in targets) {
                NotificationEvents.insert {
                    it[id] = Ids.taskId()
                    it[userId] = uid
                    it[ts] = now
                    it[NotificationEvents.kind] = kind
                    it[NotificationEvents.title] = title
                    it[NotificationEvents.body] = body
                    it[NotificationEvents.deepLink] = deepLink
                    it[NotificationEvents.projectId] = projectId
                    it[createdAt] = now
                }
                // Retention: USER_MAX 초과 시 오래된 것 부터 ack 처리 (delete 는 별도 cron).
                pruneOverCap(uid)
            }
        }
    }

    /** unread 조회 (ack_at IS NULL). userId null = BUCKET_LEGACY. */
    fun list(userId: String?, limit: Int = 100): List<NotificationEventDto> = transaction {
        val key = userId ?: BUCKET_LEGACY
        NotificationEvents.selectAll().where {
            (NotificationEvents.userId eq key) and NotificationEvents.ackedAt.isNull()
        }
            .orderBy(NotificationEvents.createdAt to SortOrder.ASC)
            .limit(limit.coerceIn(1, USER_MAX))
            .map { row ->
                NotificationEventDto(
                    id = row[NotificationEvents.id],
                    ts = row[NotificationEvents.ts],
                    kind = row[NotificationEvents.kind],
                    title = row[NotificationEvents.title],
                    body = row[NotificationEvents.body],
                    deepLink = row[NotificationEvents.deepLink],
                    projectId = row[NotificationEvents.projectId],
                    read = false,
                )
            }
    }

    fun count(userId: String?): Int = transaction {
        val key = userId ?: BUCKET_LEGACY
        NotificationEvents.selectAll().where {
            (NotificationEvents.userId eq key) and NotificationEvents.ackedAt.isNull()
        }.count().toInt()
    }

    /** 사용자가 본 id 들 ack 처리 (delete 가 아닌 ackedAt set — audit 보존). */
    fun ack(userId: String?, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val key = userId ?: BUCKET_LEGACY
        val now = clock.nowIso()
        transaction {
            ids.forEach { eid ->
                NotificationEvents.update({
                    (NotificationEvents.id eq eid) and (NotificationEvents.userId eq key)
                }) {
                    it[ackedAt] = now
                }
            }
        }
    }

    /** USER_MAX 초과 시 가장 오래된 unread 를 ack 처리 (다음 list 에서 제외). */
    private fun pruneOverCap(uid: String) {
        val unread = NotificationEvents.selectAll().where {
            (NotificationEvents.userId eq uid) and NotificationEvents.ackedAt.isNull()
        }.count().toInt()
        if (unread <= USER_MAX) return
        val excess = unread - USER_MAX
        val oldest = NotificationEvents.selectAll().where {
            (NotificationEvents.userId eq uid) and NotificationEvents.ackedAt.isNull()
        }
            .orderBy(NotificationEvents.createdAt to SortOrder.ASC)
            .limit(excess)
            .map { it[NotificationEvents.id] }
        val ackTs = clock.nowIso()
        oldest.forEach { eid ->
            NotificationEvents.update({ NotificationEvents.id eq eid }) { it[ackedAt] = ackTs }
        }
    }

    /**
     * v0.76.0 (M5 fix) — 30일 이상 ack 된 row hard delete.
     *
     * 호출자: [NotificationRetentionScheduler] (일 1회). NotificationService 는
     * 단독 호출도 안전 — 다른 사용자 데이터에 영향 없음. createdAt < cutoff 만 삭제.
     *
     * 반환: 삭제된 row 수.
     */
    fun pruneAckedOlderThan(days: Int): Int = transaction {
        val cutoff = clock.cutoffIso(days)
        NotificationEvents.deleteWhere {
            ackedAt.isNotNull() and (createdAt less cutoff)
        }
    }

    /** 편의: kind 별 short-hand. */
    fun emitBuildSuccess(projectId: String, buildId: String, userIds: List<String?>) =
        emit(
            kind = NotificationKind.BUILD_SUCCESS,
            title = "✓ Build succeeded — $projectId",
            body = "buildId: $buildId",
            deepLink = "projects/$projectId/builds/$buildId/logs",
            projectId = projectId,
            userIds = userIds,
        )

    fun emitBuildFailed(projectId: String, buildId: String, errorMessage: String?, userIds: List<String?>) =
        emit(
            kind = NotificationKind.BUILD_FAILED,
            title = "✗ Build failed — $projectId",
            body = errorMessage?.take(200).orEmpty(),
            deepLink = "projects/$projectId/builds/$buildId/logs",
            projectId = projectId,
            userIds = userIds,
        )
}
