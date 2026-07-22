package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeySaveRequestDto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

class AppStoreConnectKeyStoreTest {
    @Test
    fun `stores asc key metadata and private key without returning key body`() {
        val root = Files.createTempDirectory("asc-key-store-")
        try {
            val store = AppStoreConnectKeyStore(WorkspacePath(root), fixedClock())

            val dto = store.save(
                IosAppStoreConnectKeySaveRequestDto(
                    keyId = "ab12cd34ef",
                    issuerId = "12345678-1234-1234-1234-123456789abc",
                    privateKeyPem = TEST_PRIVATE_KEY,
                )
            )

            dto.configured shouldBe true
            dto.keyId shouldBe "AB12CD34EF"
            dto.issuerId shouldBe "12345678-1234-1234-1234-123456789abc"
            dto.privateKeyPresent shouldBe true
            dto.updatedAt shouldBe "2026-07-22T00:00:00Z"
            dto.privateKeyPath!!.contains("BEGIN PRIVATE KEY") shouldBe false
            Files.readString(java.nio.file.Path.of(dto.privateKeyPath)) shouldContain "BEGIN PRIVATE KEY"
            assertOwnerReadWriteOnly(java.nio.file.Path.of(dto.privateKeyPath))
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `metadata update preserves existing private key when body omitted`() {
        val root = Files.createTempDirectory("asc-key-store-preserve-")
        try {
            val store = AppStoreConnectKeyStore(WorkspacePath(root), fixedClock())
            val first = store.save(
                IosAppStoreConnectKeySaveRequestDto(
                    keyId = "AB12CD34EF",
                    issuerId = "12345678-1234-1234-1234-123456789abc",
                    privateKeyPem = TEST_PRIVATE_KEY,
                )
            )

            val second = store.save(
                IosAppStoreConnectKeySaveRequestDto(
                    keyId = "AB12CD34EF",
                    issuerId = "abcdefab-1234-1234-1234-abcdefabcdef",
                    privateKeyPem = null,
                )
            )

            second.configured shouldBe true
            second.privateKeyPath shouldBe first.privateKeyPath
            second.issuerId shouldBe "abcdefab-1234-1234-1234-abcdefabcdef"
            Files.readString(java.nio.file.Path.of(second.privateKeyPath)) shouldBe TEST_PRIVATE_KEY.trim() + "\n"
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `first registration requires private key pem`() {
        val root = Files.createTempDirectory("asc-key-store-required-")
        try {
            val store = AppStoreConnectKeyStore(WorkspacePath(root), fixedClock())

            shouldThrow<IllegalArgumentException> {
                store.save(
                    IosAppStoreConnectKeySaveRequestDto(
                        keyId = "AB12CD34EF",
                        issuerId = "12345678-1234-1234-1234-123456789abc",
                        privateKeyPem = null,
                    )
                )
            }
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun fixedClock(): Clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }

    private fun assertOwnerReadWriteOnly(path: java.nio.file.Path) {
        runCatching { Files.getPosixFilePermissions(path) }.onSuccess { perms ->
            perms shouldBe setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
    }

    companion object {
        private val TEST_PRIVATE_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAChRANCAASAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
