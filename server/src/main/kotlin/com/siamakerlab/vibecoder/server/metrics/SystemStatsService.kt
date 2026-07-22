package com.siamakerlab.vibecoder.server.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

private val log = KotlinLogging.logger {}

/**
 * v1.74.0 — 홈 대시보드 "서버 상태" 카드용 시스템 리소스 스냅샷.
 *
 * 호출 시점 측정. 대시보드의 "전체"는 PC/호스트 기준(`/proc/stat`, `/proc/meminfo`)이고,
 * "vibe-coder"는 가능하면 cgroup v2 기준 컨테이너 전체(서버 JVM + Claude/Codex/Gradle 등
 * 자식 프로세스 포함)를 표시한다. cgroup 을 읽을 수 없는 로컬 실행에서는 JVM 프로세스 값으로
 * 폴백한다.
 *
 * - PC CPU: `/proc/stat` delta. 첫 샘플은 MXBean `cpuLoad()`로 폴백.
 * - PC 메모리: `/proc/meminfo` MemTotal/MemAvailable. 실패 시 MXBean.
 * - vibe-coder CPU: cgroup v2 `cpu.stat` usage delta / `cpu.max` capacity.
 * - vibe-coder 메모리: cgroup v2 `memory.current` / `memory.max`.
 * - 프로세스 메모리(RSS): `/proc/self/status` VmRSS.
 */
class SystemStatsService(
    private val procRoot: Path = Path.of("/proc"),
    private val cgroupRoot: Path = Path.of("/sys/fs/cgroup"),
    private val nanoTime: () -> Long = System::nanoTime,
) {

    private val osBean = ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean
    private val memBean = ManagementFactory.getMemoryMXBean()
    private val runtimeBean = ManagementFactory.getRuntimeMXBean()
    private var lastHostCpu: CpuTicks? = null
    private var lastCgroupCpu: CgroupCpuSample? = null

    data class Snapshot(
        /** PC/호스트 전체 CPU 사용률(%) — N/A 면 -1. */
        val cpuPercent: Double,
        /** 이 vibe-coder 서버 JVM 프로세스 CPU 사용률(%) — N/A 면 -1. */
        val processCpuPercent: Double,
        /** vibe-coder 컨테이너 전체 CPU 사용률(%). cgroup 불가 시 JVM 프로세스 값. */
        val vibeCpuPercent: Double,
        val cpuScope: String,
        val vibeCpuScope: String,
        /** PC/호스트 메모리 (MB) + 사용률(%). */
        val ramUsedMb: Long,
        val ramTotalMb: Long,
        val ramPercent: Double,
        val ramScope: String,
        /** vibe-coder 컨테이너 메모리. cgroup 불가 시 JVM RSS. */
        val vibeRamUsedMb: Long,
        val vibeRamLimitMb: Long,
        val vibeRamPercent: Double,
        val vibeRamSharePercent: Double,
        val vibeRamScope: String,
        /** vibe-coder 서버 JVM 프로세스 상주 메모리 RSS (MB). */
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

    @Synchronized
    fun snapshot(): Snapshot {
        val cpu = hostCpuPercent() ?: mxBeanCpuPercent() ?: -1.0
        val procCpu = osBean?.processCpuLoad?.takeIf { it.isFinite() && it >= 0 }?.let { it * 100.0 } ?: -1.0
        val cgroupCpu = cgroupCpuPercent()
        val vibeCpu = cgroupCpu ?: procCpu
        val vibeCpuScope = if (cgroupCpu != null) "container" else "jvm"

        val (ramUsed, ramTotal) = hostMemory()
        val ramPct = if (ramTotal > 0) (ramUsed.toDouble() / ramTotal * 100.0) else -1.0
        val cgroupMem = cgroupMemory()
        val processRss = procRssMb()
        val vibeRamUsed = cgroupMem?.usedBytes ?: (processRss * 1024L * 1024L)
        val vibeRamLimit = cgroupMem?.limitBytes ?: 0L
        val vibeRamPct = if (vibeRamLimit > 0) vibeRamUsed.toDouble() / vibeRamLimit * 100.0 else -1.0
        val vibeRamSharePct = if (ramTotal > 0) vibeRamUsed.toDouble() / ramTotal * 100.0 else -1.0

        val heap = memBean.heapMemoryUsage
        val loadAvg = osBean?.systemLoadAverage ?: -1.0

        return Snapshot(
            cpuPercent = round1(cpu),
            processCpuPercent = round1(procCpu),
            vibeCpuPercent = round1(vibeCpu),
            cpuScope = "pc",
            vibeCpuScope = vibeCpuScope,
            ramUsedMb = toMb(ramUsed),
            ramTotalMb = toMb(ramTotal),
            ramPercent = round1(ramPct),
            ramScope = "pc",
            vibeRamUsedMb = toMb(vibeRamUsed),
            vibeRamLimitMb = toMb(vibeRamLimit),
            vibeRamPercent = round1(vibeRamPct),
            vibeRamSharePercent = round1(vibeRamSharePct),
            vibeRamScope = if (cgroupMem != null) "container" else "jvm",
            processRssMb = processRss,
            heapUsedMb = toMb(heap.used),
            heapMaxMb = toMb(if (heap.max > 0) heap.max else heap.committed),
            loadAvg = if (loadAvg >= 0) round2(loadAvg) else -1.0,
            cores = Runtime.getRuntime().availableProcessors(),
            uptimeSec = runtimeBean.uptime / 1000,
        )
    }

    private fun hostCpuPercent(): Double? {
        val ticks = readHostCpuTicks() ?: return null
        val prev = lastHostCpu
        lastHostCpu = ticks
        if (prev == null) return null
        val totalDelta = ticks.total - prev.total
        val idleDelta = ticks.idle - prev.idle
        if (totalDelta <= 0 || idleDelta < 0) return null
        return (1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0
    }

    private fun readHostCpuTicks(): CpuTicks? = runCatching {
        val line = Files.readAllLines(procRoot.resolve("stat")).firstOrNull { it.startsWith("cpu ") } ?: return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 5) return null
        val idle = values[3] + values[4]
        val total = values.take(8.coerceAtMost(values.size)).sum()
        CpuTicks(total = total, idle = idle)
    }.getOrNull()

    private fun mxBeanCpuPercent(): Double? =
        osBean?.cpuLoad?.takeIf { it.isFinite() && it >= 0 }?.let { it * 100.0 }

    private fun hostMemory(): Pair<Long, Long> =
        readHostMemory() ?: systemMemory()

    private fun readHostMemory(): Pair<Long, Long>? = runCatching {
        val values = Files.readAllLines(procRoot.resolve("meminfo"))
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) parts[0].removeSuffix(":") to (parts[1].toLongOrNull() ?: return@mapNotNull null) else null
            }
            .toMap()
        val totalKb = values["MemTotal"] ?: return null
        val availableKb = values["MemAvailable"] ?: return null
        if (totalKb <= 0 || availableKb < 0) return null
        ((totalKb - availableKb) * 1024L) to (totalKb * 1024L)
    }.getOrNull()

    private fun systemMemory(): Pair<Long, Long> {
        val total = osBean?.totalMemorySize ?: 0L
        val free = osBean?.freeMemorySize ?: 0L
        return (total - free) to total
    }

    private fun cgroupCpuPercent(): Double? = runCatching {
        if (!hasContainerCgroupFiles()) return null
        val usageUsec = readCgroupCpuUsageUsec() ?: return null
        val now = nanoTime()
        val current = CgroupCpuSample(usageNs = usageUsec * 1000L, timeNs = now)
        val prev = lastCgroupCpu
        lastCgroupCpu = current
        if (prev == null) return null
        val usageDelta = current.usageNs - prev.usageNs
        val elapsed = current.timeNs - prev.timeNs
        if (usageDelta < 0 || elapsed <= 0) return null
        val capacity = cgroupCpuCapacityCores()
        if (capacity <= 0.0) return null
        usageDelta.toDouble() / elapsed.toDouble() / capacity * 100.0
    }.getOrNull()

    private fun readCgroupCpuUsageUsec(): Long? = runCatching {
        Files.readAllLines(cgroupRoot.resolve("cpu.stat"))
            .firstOrNull { it.startsWith("usage_usec ") }
            ?.substringAfter(' ')
            ?.trim()
            ?.toLongOrNull()
    }.getOrNull()

    private fun cgroupCpuCapacityCores(): Double {
        val maxRaw = runCatching { Files.readString(cgroupRoot.resolve("cpu.max")).trim() }.getOrNull()
        if (!maxRaw.isNullOrBlank()) {
            val parts = maxRaw.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[0] != "max") {
                val quota = parts[0].toDoubleOrNull()
                val period = parts[1].toDoubleOrNull()
                if (quota != null && period != null && quota > 0.0 && period > 0.0) {
                    return max(0.01, quota / period)
                }
            }
        }
        return Runtime.getRuntime().availableProcessors().coerceAtLeast(1).toDouble()
    }

    private fun cgroupMemory(): CgroupMemory? = runCatching {
        if (!hasContainerCgroupFiles()) return null
        val used = readLongOrNull(cgroupRoot.resolve("memory.current")) ?: return null
        val maxRaw = runCatching { Files.readString(cgroupRoot.resolve("memory.max")).trim() }.getOrNull()
        val limit = maxRaw?.takeIf { it != "max" }?.toLongOrNull()?.takeIf { it > 0 }
        CgroupMemory(usedBytes = used, limitBytes = limit ?: 0L)
    }.getOrNull()

    private fun hasContainerCgroupFiles(): Boolean =
        Files.isRegularFile(cgroupRoot.resolve("memory.current")) ||
            Files.isRegularFile(cgroupRoot.resolve("cpu.max"))

    /** /proc/self/status 의 VmRSS (kB) → MB. 실패 시 0. */
    private fun procRssMb(): Long = runCatching {
        Files.readAllLines(procRoot.resolve("self/status"))
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

    private data class CpuTicks(val total: Long, val idle: Long)
    private data class CgroupCpuSample(val usageNs: Long, val timeNs: Long)
    private data class CgroupMemory(val usedBytes: Long, val limitBytes: Long)
}
