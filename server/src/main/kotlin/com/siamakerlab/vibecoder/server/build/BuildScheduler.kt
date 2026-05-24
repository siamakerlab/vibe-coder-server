package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository
import com.siamakerlab.vibecoder.server.repo.BuildScheduleRow
import com.siamakerlab.vibecoder.server.ws.LogHub
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val log = KotlinLogging.logger {}

/**
 * v0.33.0 — Cron 빌드 트리거.
 *
 * Tick every 60 s. 각 enabled schedule 에 대해 현재 분이 cronExpr 와 일치하면
 * `BuildService.enqueueDebug(projectId, hub)` 호출 + lastFiredAt 갱신.
 *
 * cronExpr 형식:
 *   - "HH:MM"   고정 시각 (예: "02:00" = 매일 새벽 2시).
 *   - "*:MM"    매 시간 MM 분 (예: "*:30" = 매시 30분).
 *   - "*:*"     매 분 (개발/테스트용. 절대 production 권장 안 함).
 *
 * Full cron expression (요일 / 월 등) 은 다음 cycle. 본 cycle 은 매일 빌드
 * + 시간 빌드 use case 만 우선 처리.
 *
 * 같은 minute 안에서 중복 발사 안 함 — `lastFiredAt` 의 minute precision
 * 으로 dedupe.
 */
class BuildScheduler(
    private val scheduleRepo: BuildScheduleRepository,
    private val buildService: BuildService,
    private val hub: LogHub,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "BuildScheduler started (zone=$zoneId)" }
            while (isActive) {
                runCatching { tick() }.onFailure { log.warn(it) { "scheduler tick failed" } }
                // 매 분 정각에 가까운 시점 tick. 단순화 (분 단위 정확도면 충분).
                delay(60_000)
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    /** Public for tests / manual fire. */
    fun tick(now: ZonedDateTime = ZonedDateTime.now(zoneId)) {
        val schedules = scheduleRepo.listEnabled()
        if (schedules.isEmpty()) return
        val nowMinute = now.format(MINUTE_FMT)  // "yyyy-MM-dd'T'HH:mm"
        val currentHour = now.hour
        val currentMinute = now.minute
        for (sched in schedules) {
            if (matches(sched.cronExpr, currentHour, currentMinute)) {
                // dedupe: lastFiredAt 의 minute precision 이 같으면 skip
                if (sched.lastFiredAt?.startsWith(nowMinute) == true) continue
                fire(sched, now)
            }
        }
    }

    private fun fire(sched: BuildScheduleRow, now: ZonedDateTime) {
        log.info { "scheduled build firing: ${sched.projectId} cron=${sched.cronExpr}" }
        runCatching {
            buildService.enqueueDebug(sched.projectId, hub)
            scheduleRepo.markFired(sched.id, now.toString())
        }.onFailure { e ->
            log.warn(e) { "scheduled build enqueue failed: ${sched.projectId}" }
        }
    }

    /**
     * cronExpr 가 현재 시각과 매치하는지.
     *   "HH:MM"  → hour==H && minute==MM
     *   "*:MM"   → minute==MM
     *   "*:*"    → 매 분 (개발용)
     */
    private fun matches(cronExpr: String, hour: Int, minute: Int): Boolean {
        val parts = cronExpr.trim().split(":")
        if (parts.size != 2) return false
        val hPart = parts[0]
        val mPart = parts[1]
        val hourOk = hPart == "*" || hPart.toIntOrNull() == hour
        val minOk = mPart == "*" || mPart.toIntOrNull() == minute
        return hourOk && minOk
    }

    companion object {
        /**
         * Validate user-input cronExpr. Returns null on success, error message on failure.
         */
        fun validate(cronExpr: String): String? {
            val parts = cronExpr.trim().split(":")
            if (parts.size != 2) return "형식이 HH:MM 또는 *:MM 또는 *:* 이어야 합니다."
            val h = parts[0]
            val m = parts[1]
            if (h != "*") {
                val n = h.toIntOrNull() ?: return "시(hour)는 0~23 사이의 숫자 또는 * 이어야 합니다."
                if (n !in 0..23) return "시는 0~23 사이여야 합니다."
            }
            if (m != "*") {
                val n = m.toIntOrNull() ?: return "분(minute)은 0~59 사이의 숫자 또는 * 이어야 합니다."
                if (n !in 0..59) return "분은 0~59 사이여야 합니다."
            }
            return null
        }

        private val MINUTE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}
