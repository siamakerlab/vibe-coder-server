package com.siamakerlab.vibecoder.server.agent.opencode

import com.siamakerlab.vibecoder.shared.dto.OpenCodeUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * v1.151.0 — OpenCode usage 백그라운드 폴링. [OpenCodeStatusService.snapshot] 을 정기 호출해
 * 캐시를 갱신한다 (UI/Android 가 즉시 보여줄 수 있도록).
 *
 * 임계치 이메일 알림은 opencode CLI 가 usage % 게이지를 노출하지 않아 비활성. CodexUsageMonitor
 * (v1.147.0) 와 달리 transition 기반 warn/critical 발송 없음. 추후 z.ai API 직접 연동(Phase 3.1)
 * 으로 잔여량을 알게 되면 동일 정책 추가.
 */
class OpenCodeUsageMonitor(
    private val statusService: OpenCodeStatusService,
    private val intervalProvider: () -> Duration = { Duration.ofMinutes(5) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var lastSnapshot: OpenCodeUsageDto? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            log.info { "OpenCode usage monitor started" }
            while (isActive) {
                runCatching { statusService.snapshot() }
                    .onFailure { log.debug(it) { "OpenCode usage monitor tick failed: ${it.message}" } }
                    .onSuccess { lastSnapshot = it }
                delay(intervalProvider().toMillis().coerceAtLeast(60_000L))
            }
        }
    }

    fun snapshot(): OpenCodeUsageDto = lastSnapshot ?: statusService.cachedSnapshot()

    fun shutdown() {
        job?.cancel()
        job = null
        scope.cancel()
    }
}
