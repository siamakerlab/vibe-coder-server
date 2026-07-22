package com.siamakerlab.vibecoder.server.resources

import com.siamakerlab.vibecoder.server.config.ResourceGuardSection
import com.siamakerlab.vibecoder.server.error.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class ResourceGuardTest {
    @Test
    fun `allows heavy work below soft limit`() {
        val guard = guardWithSnapshots(snapshot(7_000, 10_000))

        guard.decision().allowed shouldBe true
    }

    @Test
    fun `blocks heavy work above hard limit`() {
        val guard = guardWithSnapshots(snapshot(97, 100))

        val error = shouldThrow<ApiException> {
            guard.ensureCanStart("console claude")
        }

        error.statusCode shouldBe 503
        error.code shouldBe "resource_guard_memory_pressure"
    }

    @Test
    fun `runs cleanup but allows heavy work above soft limit when free memory remains`() {
        var cleanupCalls = 0
        val guard = guardWithSnapshots(
            snapshot(9_000, 10_000),
            snapshot(8_600, 10_000),
        )

        val decision = guard.decision {
            cleanupCalls += 1
            1
        }

        decision.allowed shouldBe true
        cleanupCalls shouldBe 1
    }

    @Test
    fun `allows heavy work above soft limit without cleanup when free memory remains`() {
        val guard = guardWithSnapshots(snapshot(9_000, 10_000))

        guard.decision().allowed shouldBe true
    }

    @Test
    fun `blocks when free memory floor is crossed`() {
        val mb = ResourceGuard.BYTES_PER_MB
        val guard = ResourceGuard(
            configProvider = {
                ResourceGuardSection(
                    memorySoftLimitPercent = 90,
                    memoryHardLimitPercent = 98,
                    minFreeMemoryMb = 1024,
                )
            },
            memoryProbe = {
                ResourceGuard.MemorySnapshot(
                    usedBytes = 4_500L * mb,
                    totalBytes = 5_000L * mb,
                    source = "test",
                )
            },
        )

        guard.decision().allowed shouldBe false
    }

    private fun guardWithSnapshots(vararg snapshots: ResourceGuard.MemorySnapshot): ResourceGuard {
        var index = 0
        return ResourceGuard(
            configProvider = { ResourceGuardSection() },
            memoryProbe = {
                snapshots[index.coerceAtMost(snapshots.lastIndex)]
                    .also { index += 1 }
            },
        )
    }

    private fun snapshot(used: Long, total: Long): ResourceGuard.MemorySnapshot =
        ResourceGuard.MemorySnapshot(
            usedBytes = used * ResourceGuard.BYTES_PER_MB,
            totalBytes = total * ResourceGuard.BYTES_PER_MB,
            source = "test",
        )
}
