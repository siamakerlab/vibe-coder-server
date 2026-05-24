package com.siamakerlab.vibecoder.server.disk

import com.siamakerlab.vibecoder.server.notify.Notifiers
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * v0.29.0 — 디스크 사용량 모니터링 + 임계치 알림.
 *
 * Claude usage monitor 와 같은 패턴 (transition 기반 + cooldown).
 *
 * 측정:
 *   - VIBE_DATA_ROOT (또는 workspace.root) 의 FileStore 정보 사용.
 *   - 사용량 percent = (total - usable) / total * 100.
 *
 * 알림 정책:
 *   - usedPercent ≥ warnThresholdPercent (기본 85%) 로 transition 시 1회 발송.
 *   - 다시 아래로 내려가면 상태 reset → 다음 transition 시 재발송 가능.
 *   - 최소 30분 cooldown (alert spam 방지).
 */
class DiskMonitor(
    /** 측정 대상 경로. 보통 workspace.root 또는 VIBE_DATA_ROOT. */
    private val rootProvider: () -> Path,
    private val notifiers: Notifiers,
    private val warnThresholdPercentProvider: () -> Int = { 85 },
    private val pollIntervalMinutes: Long = 10,
) {

    data class Snapshot(
        val totalBytes: Long,
        val usableBytes: Long,
        val usedBytes: Long,
        val usedPercent: Int,
        val takenAt: Instant,
    ) {
        val freeGb: Double get() = usableBytes / 1_073_741_824.0
    }

    @Volatile private var lastSnapshot: Snapshot? = null
    private val lastAlertAt = AtomicReference<Instant?>(null)
    private val alertActive = AtomicReference<Boolean>(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun snapshot(): Snapshot? = lastSnapshot

    /** 즉시 한 번 측정 + 캐시 갱신. UI 가 호출. */
    fun measureNow(): Snapshot {
        val root = rootProvider()
        val store = Files.getFileStore(root)
        val total = store.totalSpace
        val usable = store.usableSpace
        val used = (total - usable).coerceAtLeast(0)
        val pct = if (total > 0) ((used.toDouble() / total) * 100).toInt().coerceIn(0, 100) else 0
        val snap = Snapshot(total, usable, used, pct, Instant.now())
        lastSnapshot = snap
        return snap
    }

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "Disk monitor started" }
            while (isActive) {
                runCatching { tick() }.onFailure { log.debug(it) { "disk monitor tick failed" } }
                delay(Duration.ofMinutes(pollIntervalMinutes.coerceAtLeast(1)).toMillis())
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    private fun tick() {
        val snap = runCatching { measureNow() }.getOrNull() ?: return
        val threshold = warnThresholdPercentProvider().coerceIn(1, 100)
        val above = snap.usedPercent >= threshold

        if (!above) {
            if (alertActive.get()) {
                log.info { "Disk usage dropped below threshold (${snap.usedPercent}% < $threshold%). Reset." }
                alertActive.set(false)
                lastAlertAt.set(null)
            }
            return
        }

        if (alertActive.get()) return  // 같은 high 상태 — 한 번만 알림

        val now = Instant.now()
        val last = lastAlertAt.get()
        if (last != null && Duration.between(last, now).toMinutes() < 30) return

        log.info { "Disk usage above threshold: ${snap.usedPercent}% >= $threshold% (free=${"%.1f".format(snap.freeGb)} GB)" }
        notifiers.diskUsageWarn(snap.usedPercent, snap.freeGb)
        alertActive.set(true)
        lastAlertAt.set(now)
    }
}
