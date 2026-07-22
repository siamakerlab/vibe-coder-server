package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.shared.dto.IosKeychainImportRequestDto
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.time.Duration
import java.time.Instant

class IosKeychainImportServiceTest {
    @Test
    fun `imports p12 into dedicated local keychain`() {
        val captured = mutableListOf<List<String>>()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, timeout: Duration): CommandResult {
                captured += command
                return CommandResult(
                    exitCode = 0,
                    stdout = """
                        __VC_KEYCHAIN_CREATED__
                        __VC_KEYCHAIN_UNLOCKED__
                        __VC_CERT_IMPORTED__
                        __VC_PARTITION_LIST_UPDATED__
                        __VC_SEARCH_LIST_UPDATED__
                          1) ABCDEF0123456789ABCDEF0123456789ABCDEF01 "Apple Distribution: Demo"
                    """.trimIndent(),
                    stderr = "",
                )
            }
        }
        val service = IosKeychainImportService(
            clock = fixedClock(),
            agentConfigProvider = { IosAgentSection(mode = "local") },
            runnerFactory = { IosAgentCommandRunner(it, runner) },
        )

        val dto = service.importCertificate(
            IosKeychainImportRequestDto(
                p12Path = "/Users/builder/certs/dist.p12",
                p12Password = "p12-secret",
                keychainName = "vibe-test",
                keychainPassword = "keychain-secret",
            )
        )

        dto.ok.shouldBeTrue()
        dto.keychainPath shouldBe "~/Library/Keychains/vibe-test.keychain-db"
        dto.created.shouldBeTrue()
        dto.unlocked.shouldBeTrue()
        dto.imported.shouldBeTrue()
        dto.partitionListUpdated.shouldBeTrue()
        dto.searchListUpdated.shouldBeTrue()
        dto.codesigningIdentities shouldBe listOf("""ABCDEF0123456789ABCDEF0123456789ABCDEF01 "Apple Distribution: Demo"""")
        captured.single().take(2) shouldBe listOf("bash", "-lc")
        captured.single().last() shouldContain "security import"
        captured.single().last() shouldContain "security set-key-partition-list"
    }

    @Test
    fun `ssh mode is blocked when agent is disabled`() {
        val service = IosKeychainImportService(
            clock = fixedClock(),
            agentConfigProvider = {
                IosAgentSection(enabled = false, mode = "ssh", host = "mac-mini.local", user = "builder")
            },
            runnerFactory = { error("runner must not be called") },
        )

        val dto = service.importCertificate(
            IosKeychainImportRequestDto(
                p12Path = "/Users/builder/certs/dist.p12",
                p12Password = "p12-secret",
                keychainPassword = "keychain-secret",
            )
        )

        dto.ok.shouldBeFalse()
        dto.blockedReason shouldBe "agent_disabled"
    }

    private fun fixedClock(): Clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }
}
