package com.siamakerlab.vibecoder.server.automation

import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.repo.ScheduledPromptRepository
import com.siamakerlab.vibecoder.server.repo.ScheduledPromptRow
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptTriggers
import com.siamakerlab.vibecoder.shared.ws.WsFrame
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
 * v1.130.0 — 프롬프트 예약 전송(one-shot) 스케줄러.
 *
 * [ScheduledPromptRepository] 의 pending 예약을 30초마다 폴링해, 트리거 조건이 충족되고
 * 콘솔이 **유휴**(turn 미진행)일 때 [ClaudeSessionManager.sendPrompt] 로 1회 전송한다.
 *
 * 트리거:
 *  - time          : `fireAtEpochMs` 도달.
 *  - session_reset : Claude 세션(5h) 사용 한도 해제 감지.
 *  - weekly_reset  : Claude 주간(7d) 사용 한도 해제 감지.
 *
 * 한도 해제 감지는 [ClaudeStatusService.cachedSnapshot] 의 session/weekly UsagePercent 를
 * 사용한다(계정 전역값이라 SCRATCH 캐시로 폴백, 백그라운드 ClaudeUsageMonitor 가 5분 주기 갱신).
 * 예약 시점의 사용량%(baseline)대비 **큰 하락**([RESET_DROP_POINTS]p 이상) 또는 절대 **저점**
 * ([RESET_LOW_PERCENT]% 이하)이면 "리셋됨"으로 판정한다. 관측 불가(null)면 보수적으로 보류.
 *
 * 발사는 항상 비-busy 일 때만 → 진행 중 turn 에 follow-up 으로 끼지 않고 깔끔한 새 turn 시작.
 */
class ScheduledPromptManager(
    private val repo: ScheduledPromptRepository,
    private val sessionManager: ClaudeSessionManager,
    private val statusService: ClaudeStatusService,
    private val hub: LogHub,
    private val clock: Clock,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "ScheduledPromptManager started (tick=${TICK_MS / 1000}s)" }
            while (isActive) {
                runCatching { tick() }.onFailure { log.warn(it) { "scheduled-prompt tick failed" } }
                delay(TICK_MS)
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    /** Public for tests / manual fire. */
    suspend fun tick() {
        val pending = repo.listPending()
        if (pending.isEmpty()) return
        val nowMs = clock.nowInstant().toEpochMilli()
        for (row in pending) {
            if (!shouldFire(row, nowMs)) continue
            // 유휴일 때만 발사 — busy 면 다음 tick 으로 보류(깔끔한 새 turn 보장).
            if (sessionManager.isBusy(row.projectId)) continue
            fire(row)
        }
    }

    private fun shouldFire(row: ScheduledPromptRow, nowMs: Long): Boolean = when (row.triggerType) {
        ScheduledPromptTriggers.TIME ->
            row.fireAtEpochMs != null && nowMs >= row.fireAtEpochMs
        ScheduledPromptTriggers.SESSION_RESET ->
            isLimitReleased(row.projectId, session = true, baseline = row.baselinePercent)
        ScheduledPromptTriggers.WEEKLY_RESET ->
            isLimitReleased(row.projectId, session = false, baseline = row.baselinePercent)
        else -> false
    }

    /**
     * Claude 세션/주간 사용 한도가 "해제됨"으로 볼 수 있는지.
     * 관측값이 없으면(false) 보수적으로 보류한다.
     */
    private fun isLimitReleased(projectId: String, session: Boolean, baseline: Int?): Boolean {
        val snap = statusService.cachedSnapshot(projectId)
        val cur = (if (session) snap.sessionUsagePercent else snap.weeklyUsagePercent) ?: return false
        if (baseline != null && baseline - cur >= RESET_DROP_POINTS) return true
        return cur <= RESET_LOW_PERCENT
    }

    private suspend fun fire(row: ScheduledPromptRow) {
        val label = row.triggerLabel ?: row.triggerType
        try {
            sessionManager.sendPrompt(row.projectId, row.prompt)
            repo.markSent(row.id)
            log.info { "scheduled prompt fired: ${row.projectId} (${row.triggerType})" }
            emitSystem(row.projectId, "schedule_sent", "⏰ 예약 프롬프트 전송 — $label")
        } catch (e: Throwable) {
            repo.markFailed(row.id, e.message ?: "send_failed")
            log.warn(e) { "scheduled prompt send failed: ${row.projectId}" }
            emitSystem(row.projectId, "schedule_failed", "⏰ 예약 프롬프트 전송 실패 — ${e.message ?: "오류"}")
        }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(LogHub.consoleTopic(projectId)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
        /** baseline 대비 이만큼(p) 떨어지면 리셋 점프로 간주. */
        const val RESET_DROP_POINTS = 30
        /** 절대 사용량이 이 % 이하면 한도 여유 충분 = 해제로 간주. */
        const val RESET_LOW_PERCENT = 15
    }
}
