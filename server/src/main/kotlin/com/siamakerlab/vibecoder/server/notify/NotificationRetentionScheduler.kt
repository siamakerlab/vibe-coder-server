package com.siamakerlab.vibecoder.server.notify

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * v0.76.0 (M5 fix) — `notification_events` retention prune.
 *
 * 일 1회 (24h) [NotificationService.pruneAckedOlderThan] 호출 →
 * `ackedAt is not null and createdAt < N일 전` row 모두 hard delete.
 *
 * Without this scheduler, `notification_events` 가 빌드/usage 알림으로 무한
 * 누적 (KDoc 만 "별도 cycle" 명시, 실 scheduler 없음 — Phase 51 v0.71.0 누락).
 *
 * Default: 30일 retention. 첫 prune 은 시작 5분 후 실행 (boot 직후 부하 회피).
 */
class NotificationRetentionScheduler(
    private val service: NotificationService,
    private val retentionDaysProvider: () -> Int = { 30 },
    private val intervalMillis: Long = 24 * 60 * 60 * 1000L,
    private val initialDelayMillis: Long = 5 * 60 * 1000L,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            log.info { "NotificationRetentionScheduler started (initial delay ${initialDelayMillis / 1000}s, interval ${intervalMillis / 1000}s)" }
            delay(initialDelayMillis)
            while (isActive) {
                runCatching {
                    val days = retentionDaysProvider()
                    val n = service.pruneAckedOlderThan(days)
                    if (n > 0) log.info { "Pruned $n acked notification rows older than $days days" }
                }.onFailure { e ->
                    log.warn(e) { "notification retention prune failed: ${e.message}" }
                }
                delay(intervalMillis)
            }
        }
    }

    fun shutdown() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
