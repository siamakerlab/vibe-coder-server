package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.shared.dto.NotificationEventDto
import com.siamakerlab.vibecoder.shared.dto.NotificationKind
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.68.0 — Phase 47. Polling-based notification system.
 *
 * In-memory event queue per user (process restart 시 잃음 — DB persistence 는 다음 cycle).
 * BuildService / ClaudeSessionManager 등이 [emit] 호출 → fan-out to all users.
 *
 * Why in-memory only (v0.68.0):
 *  - 알림은 본질적으로 ephemeral (몇 분 ~ 몇 시간 후 가치 없음).
 *  - 사용자가 process 재시작 후 못 본 알림은 next build/turn 시 다시 emit.
 *  - DB persistence 는 retention + indexing 비용 — 후속 cycle (v0.69.0+) 에서 PG 영속화.
 *
 * Retention: per-user max [USER_MAX] = 500. 오래된 것 부터 자동 prune (FIFO).
 * Read tracking: ack 된 id 는 list 에서 제외 (delete from queue).
 */
class NotificationService(
    private val clock: Clock,
) {
    /** userId → events. userId 가 null 인 경우 (legacy single-user) 는 BUCKET_LEGACY 키 사용. */
    private val queues = ConcurrentHashMap<String, ArrayDeque<NotificationEventDto>>()

    companion object {
        const val USER_MAX = 500
        /** v0.4.0 이전 single-user 모드 호환 — 모든 anonymous device 가 공유. */
        const val BUCKET_LEGACY = "__legacy__"
    }

    /**
     * 모든 사용자에게 fan-out. emitter 는 어떤 user 에게 보내야 하는지 알 필요 없음 —
     * 본 함수가 admin/member/viewer 모두에게 broadcast.
     *
     * 호출자: BuildService (빌드 완료/실패), ClaudeSessionManager (turn 완료), ...
     *
     * Fan-out 대상 결정: NotificationService 는 user 리스트를 직접 모름 → 호출자가
     * [userIds] 로 명시. 빈 list 면 `BUCKET_LEGACY` bucket 하나에만 push (single-user 호환).
     */
    fun emit(
        kind: String,
        title: String,
        body: String = "",
        deepLink: String? = null,
        projectId: String? = null,
        userIds: List<String?> = emptyList(),
    ) {
        val ev = NotificationEventDto(
            id = Ids.taskId(),
            ts = clock.nowIso(),
            kind = kind,
            title = title,
            body = body,
            deepLink = deepLink,
            projectId = projectId,
        )
        val targets = if (userIds.isEmpty()) listOf(BUCKET_LEGACY)
        else userIds.map { it ?: BUCKET_LEGACY }
        for (uid in targets.distinct()) {
            val q = queues.getOrPut(uid) { ArrayDeque(64) }
            synchronized(q) {
                q.addLast(ev)
                while (q.size > USER_MAX) q.removeFirst()
            }
        }
    }

    /** 호출자가 자기 user 의 unread 만 조회. userId null 이면 [BUCKET_LEGACY]. */
    fun list(userId: String?, limit: Int = 100): List<NotificationEventDto> {
        val key = userId ?: BUCKET_LEGACY
        val q = queues[key] ?: return emptyList()
        return synchronized(q) { q.toList() }.takeLast(limit.coerceIn(1, USER_MAX))
    }

    fun count(userId: String?): Int {
        val key = userId ?: BUCKET_LEGACY
        val q = queues[key] ?: return 0
        return synchronized(q) { q.size }
    }

    /** 사용자가 본 id 들 제거. 없는 id 는 무음 skip. */
    fun ack(userId: String?, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val key = userId ?: BUCKET_LEGACY
        val q = queues[key] ?: return
        val toRemove = ids.toHashSet()
        synchronized(q) {
            val keeper = q.filterNot { it.id in toRemove }
            q.clear()
            q.addAll(keeper)
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
