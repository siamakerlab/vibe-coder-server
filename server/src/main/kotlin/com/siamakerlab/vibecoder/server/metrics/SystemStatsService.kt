package com.siamakerlab.vibecoder.server.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * v1.74.0 — 홈 대시보드 "서버 상태" 카드용 시스템 리소스 스냅샷.
 *
 * 호출 시점 측정(주기 수집 불필요). JDK 17 의 cgroup-aware MXBean 을 우선 사용하되,
 * 컨테이너 점유율(이 vibe-coder 서버 프로세스)은 cgroup v2 / `/proc/self/status` 로 보강.
 *
 * - 시스템 CPU/메모리: `com.sun.management.OperatingSystemMXBean` (컨테이너 한도 인지).
 * - 프로세스 CPU: `getProcessCpuLoad()` (이 JVM = vibe-coder 서버).
 * - 프로세스 메모리(RSS): `/proc/self/status` VmRSS.
 * - 컨테이너 메모리: cgroup v2 `memory.current` / `memory.max` (없으면 시스템 메모리로 대체).
 */
class SystemStatsService {

    private val osBean = ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean
    private val memBean = ManagementFactory.getMemoryMXBean()
    private val runtimeBean = ManagementFactory.getRuntimeMXBean()

    data class Snapshot(
        /** 시스템 전체 CPU 사용률(%) — N/A 면 -1. cgroup 한도 인지. */
        val cpuPercent: Double,
        /** 이 vibe-coder 서버 프로세스 CPU 사용률(%) — N/A 면 -1. */
        val processCpuPercent: Double,
        /** 시스템/컨테이너 메모리 (MB) + 사용률(%). */
        val ramUsedMb: Long,
        val ramTotalMb: Long,
        val ramPercent: Double,
        /** vibe-coder 서버 프로세스 상주 메모리 RSS (MB). 컨테이너 점유 지표. */
        val processRssMb: Long,
        /** JVM 힙 사용/최대 (MB). */
        val heapUsedMb: Long,
        val heapMaxMb: Long,
        /** 1분 load average (음수면 N/A). */
        val loadAvg: Double,
        val cores: Int,
        /** 서버 프로세스 uptime (초). */
        val uptimeSec: Long,
    )

    fun snapshot(): Snapshot {
        val cpu = osBean?.cpuLoad?.takeIf { it.isFinite() && it >= 0 }?.let { it * 100.0 } ?: -1.0
        val procCpu = osBean?.processCpuLoad?.takeIf { it.isFinite() && it >= 0 }?.let { it * 100.0 } ?: -1.0

        // 메모리: cgroup v2(컨테이너 한도) 우선, 없으면 MXBean(시스템/cgroup-aware).
        val (ramUsed, ramTotal) = containerMemory() ?: systemMemory()
        val ramPct = if (ramTotal > 0) (ramUsed.toDouble() / ramTotal * 100.0) else -1.0

        val heap = memBean.heapMemoryUsage
        val loadAvg = osBean?.systemLoadAverage ?: -1.0

        return Snapshot(
            cpuPercent = round1(cpu),
            processCpuPercent = round1(procCpu),
            ramUsedMb = toMb(ramUsed),
            ramTotalMb = toMb(ramTotal),
            ramPercent = round1(ramPct),
            processRssMb = procRssMb(),
            heapUsedMb = toMb(heap.used),
            heapMaxMb = toMb(if (heap.max > 0) heap.max else heap.committed),
            loadAvg = if (loadAvg >= 0) round2(loadAvg) else -1.0,
            cores = Runtime.getRuntime().availableProcessors(),
            uptimeSec = runtimeBean.uptime / 1000,
        )
    }

    /** cgroup v2 메모리 (used, limit). 한도가 max(=무제한)면 null → 시스템 메모리로 폴백. */
    private fun containerMemory(): Pair<Long, Long>? = runCatching {
        val base = Path.of("/sys/fs/cgroup")
        val cur = readLongOrNull(base.resolve("memory.current")) ?: return null
        val maxRaw = Files.readString(base.resolve("memory.max")).trim()
        if (maxRaw == "max") return null // 한도 없음 → 시스템 메모리가 더 의미 있음
        val max = maxRaw.toLongOrNull() ?: return null
        if (max <= 0) return null
        cur to max
    }.getOrNull()

    private fun systemMemory(): Pair<Long, Long> {
        val total = osBean?.totalMemorySize ?: 0L
        val free = osBean?.freeMemorySize ?: 0L
        return (total - free) to total
    }

    /** /proc/self/status 의 VmRSS (kB) → MB. 실패 시 0. */
    private fun procRssMb(): Long = runCatching {
        Files.readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.let { Regex("""(\d+)""").find(it)?.value?.toLongOrNull() }
            ?.let { it / 1024 } // kB → MB
            ?: 0L
    }.getOrElse { 0L }

    private fun readLongOrNull(p: Path): Long? =
        runCatching { Files.readString(p).trim().toLongOrNull() }.getOrNull()

    private fun toMb(bytes: Long): Long = if (bytes > 0) bytes / (1024 * 1024) else 0L
    private fun round1(v: Double): Double = if (v < 0) -1.0 else Math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
