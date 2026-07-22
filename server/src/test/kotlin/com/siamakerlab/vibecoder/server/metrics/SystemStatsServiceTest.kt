package com.siamakerlab.vibecoder.server.metrics

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SystemStatsServiceTest {
    @Test
    fun `reports pc total separately from cgroup container usage`() {
        val root = Files.createTempDirectory("system-stats-test")
        try {
            val proc = root.resolve("proc")
            val cgroup = root.resolve("cgroup")
            Files.createDirectories(proc.resolve("self"))
            Files.createDirectories(cgroup)
            write(proc.resolve("meminfo"), "MemTotal: 1048576 kB\nMemAvailable: 524288 kB\n")
            write(proc.resolve("self/status"), "Name:\tvibe\nVmRSS:\t65536 kB\n")
            write(cgroup.resolve("cpu.max"), "100000 100000\n")
            write(cgroup.resolve("memory.current"), "268435456\n")
            write(cgroup.resolve("memory.max"), "536870912\n")

            var now = 1_000_000_000L
            val service = SystemStatsService(
                procRoot = proc,
                cgroupRoot = cgroup,
                nanoTime = { now },
            )

            write(proc.resolve("stat"), "cpu  100 0 200 700 0 0 0 0 0 0\n")
            write(cgroup.resolve("cpu.stat"), "usage_usec 1000000\n")
            service.snapshot()

            now += 1_000_000_000L
            write(proc.resolve("stat"), "cpu  150 0 200 750 0 0 0 0 0 0\n")
            write(cgroup.resolve("cpu.stat"), "usage_usec 1500000\n")
            val snapshot = service.snapshot()

            snapshot.cpuPercent shouldBe 50.0
            snapshot.cpuScope shouldBe "pc"
            snapshot.vibeCpuPercent shouldBe 50.0
            snapshot.vibeCpuScope shouldBe "container"
            snapshot.ramUsedMb shouldBe 512
            snapshot.ramTotalMb shouldBe 1024
            snapshot.ramPercent shouldBe 50.0
            snapshot.ramScope shouldBe "pc"
            snapshot.vibeRamUsedMb shouldBe 256
            snapshot.vibeRamLimitMb shouldBe 512
            snapshot.vibeRamPercent shouldBe 50.0
            snapshot.vibeRamSharePercent shouldBe 25.0
            snapshot.vibeRamScope shouldBe "container"
            snapshot.processRssMb shouldBe 64
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `falls back to jvm process scope when cgroup files are unavailable`() {
        val root = Files.createTempDirectory("system-stats-test")
        try {
            val proc = root.resolve("proc")
            val cgroup = root.resolve("missing-cgroup")
            Files.createDirectories(proc.resolve("self"))
            write(proc.resolve("stat"), "cpu  100 0 200 700 0 0 0 0 0 0\n")
            write(proc.resolve("meminfo"), "MemTotal: 1048576 kB\nMemAvailable: 524288 kB\n")
            write(proc.resolve("self/status"), "Name:\tvibe\nVmRSS:\t32768 kB\n")

            val snapshot = SystemStatsService(procRoot = proc, cgroupRoot = cgroup).snapshot()

            snapshot.vibeCpuScope shouldBe "jvm"
            snapshot.vibeRamScope shouldBe "jvm"
            snapshot.vibeRamUsedMb shouldBe 32
            snapshot.vibeRamLimitMb shouldBe 0
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not treat host root cgroup cpu stat alone as container scope`() {
        val root = Files.createTempDirectory("system-stats-test")
        try {
            val proc = root.resolve("proc")
            val cgroup = root.resolve("cgroup")
            Files.createDirectories(proc.resolve("self"))
            Files.createDirectories(cgroup)
            write(proc.resolve("stat"), "cpu  100 0 200 700 0 0 0 0 0 0\n")
            write(proc.resolve("meminfo"), "MemTotal: 1048576 kB\nMemAvailable: 524288 kB\n")
            write(proc.resolve("self/status"), "Name:\tvibe\nVmRSS:\t32768 kB\n")
            write(cgroup.resolve("cpu.stat"), "usage_usec 1000000\n")

            val snapshot = SystemStatsService(procRoot = proc, cgroupRoot = cgroup).snapshot()

            snapshot.vibeCpuScope shouldBe "jvm"
            snapshot.vibeRamScope shouldBe "jvm"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun write(path: Path, text: String) {
        Files.writeString(path, text)
    }
}
