package com.siamakerlab.vibecoder.server.resources

import com.siamakerlab.vibecoder.server.config.ResourceGuardSection
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

class ResourceGuard(
    private val configProvider: () -> ResourceGuardSection,
    private val memoryProbe: () -> MemorySnapshot? = ::defaultMemorySnapshot,
) {
    data class MemorySnapshot(
        val usedBytes: Long,
        val totalBytes: Long,
        val source: String,
        val takenAt: Instant = Instant.now(),
    ) {
        val usedPercent: Int =
            if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes) * 100.0).toInt().coerceIn(0, 100) else 0
        val freeMb: Long =
            if (totalBytes > usedBytes) (totalBytes - usedBytes) / BYTES_PER_MB else 0
    }

    data class Decision(
        val allowed: Boolean,
        val snapshot: MemorySnapshot?,
        val reason: String? = null,
    )

    private val lastSnapshotRef = AtomicReference<MemorySnapshot?>(null)

    fun lastSnapshot(): MemorySnapshot? = lastSnapshotRef.get()

    fun snapshot(): MemorySnapshot? =
        memoryProbe()?.also { lastSnapshotRef.set(it) }

    fun decision(cleanup: (() -> Int)? = null): Decision {
        val cfg = normalizedConfig()
        if (!cfg.enabled) return Decision(allowed = true, snapshot = snapshot())
        var snap = snapshot() ?: return Decision(allowed = true, snapshot = null)
        val overSoft = isOverSoftLimit(snap, cfg)
        if (overSoft && cfg.killIdleTuiSessionsOnPressure && cleanup != null) {
            val closed = runCatching { cleanup() }.getOrElse {
                log.warn(it) { "resource guard cleanup failed" }
                0
            }
            if (closed > 0) {
                log.warn { "resource guard closed $closed idle TUI session(s) under memory pressure" }
                snap = snapshot() ?: snap
            }
        }
        if (isOverHardLimit(snap, cfg)) {
            return Decision(false, snap, "hard")
        }
        if (isBelowFreeMemoryFloor(snap, cfg)) {
            return Decision(false, snap, "low_free")
        }
        return Decision(true, snap)
    }

    fun ensureCanStart(operation: String, cleanup: (() -> Int)? = null) {
        val d = decision(cleanup)
        if (d.allowed) return
        val snap = d.snapshot
        val detail = if (snap != null) {
            "operation=$operation source=${snap.source} used=${snap.usedPercent}% free=${snap.freeMb}MB reason=${d.reason}"
        } else {
            "operation=$operation reason=${d.reason}"
        }
        log.warn { "resource guard blocked heavy operation: $detail" }
        throw ApiException.localized(
            statusCode = 503,
            code = "resource_guard_memory_pressure",
            messageKey = "api.resource.memoryPressure",
            args = listOf(operation, snap?.usedPercent ?: -1, snap?.freeMb ?: -1),
            detail = detail,
        )
    }

    private fun normalizedConfig(): ResourceGuardSection {
        val raw = configProvider()
        val hard = raw.memoryHardLimitPercent.coerceIn(1, 100)
        val soft = raw.memorySoftLimitPercent.coerceIn(1, hard)
        return raw.copy(
            memorySoftLimitPercent = soft,
            memoryHardLimitPercent = hard,
            minFreeMemoryMb = raw.minFreeMemoryMb.coerceAtLeast(0),
        )
    }

    private fun isOverSoftLimit(s: MemorySnapshot, cfg: ResourceGuardSection): Boolean =
        s.usedPercent >= cfg.memorySoftLimitPercent || isBelowFreeMemoryFloor(s, cfg)

    private fun isOverHardLimit(s: MemorySnapshot, cfg: ResourceGuardSection): Boolean =
        s.usedPercent >= cfg.memoryHardLimitPercent

    private fun isBelowFreeMemoryFloor(s: MemorySnapshot, cfg: ResourceGuardSection): Boolean =
        s.freeMb < cfg.minFreeMemoryMb

    companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

private fun defaultMemorySnapshot(): ResourceGuard.MemorySnapshot? =
    cgroupMemorySnapshot() ?: systemMemorySnapshot()

private fun cgroupMemorySnapshot(): ResourceGuard.MemorySnapshot? = runCatching {
    val base = Path.of("/sys/fs/cgroup")
    val used = readLongOrNull(base.resolve("memory.current")) ?: return null
    val maxRaw = Files.readString(base.resolve("memory.max")).trim()
    if (maxRaw == "max") return null
    val max = maxRaw.toLongOrNull() ?: return null
    if (max <= 0) return null
    ResourceGuard.MemorySnapshot(usedBytes = used, totalBytes = max, source = "cgroup")
}.getOrNull()

private fun systemMemorySnapshot(): ResourceGuard.MemorySnapshot? = runCatching {
    val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        ?: return null
    val total = os.totalMemorySize
    val free = os.freeMemorySize
    if (total <= 0 || free < 0) return null
    ResourceGuard.MemorySnapshot(usedBytes = (total - free).coerceAtLeast(0), totalBytes = total, source = "system")
}.getOrNull()

private fun readLongOrNull(p: Path): Long? =
    runCatching { Files.readString(p).trim().toLongOrNull() }.getOrNull()
