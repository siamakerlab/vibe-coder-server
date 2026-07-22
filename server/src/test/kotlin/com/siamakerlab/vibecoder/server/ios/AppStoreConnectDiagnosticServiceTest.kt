package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeySaveRequestDto
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class AppStoreConnectDiagnosticServiceTest {
    @Test
    fun `jwt signer emits three part ES256 token`() {
        val credentials = AppStoreConnectKeyStore.Credentials(
            keyId = "AB12CD34EF",
            issuerId = "12345678-1234-1234-1234-123456789abc",
            privateKeyPem = generatePrivateKeyPem(),
            privateKeyPath = Files.createTempFile("asc-jwt-", ".p8"),
        )

        val token = AscJwtSigner.sign(credentials, issuedAtEpochSeconds = 1_783_000_000L)

        val parts = token.split('.')
        parts.size shouldBe 3
        Base64.getUrlDecoder().decode(parts[2]).size shouldBe 64
        String(Base64.getUrlDecoder().decode(parts[0])) shouldBe """{"alg":"ES256","kid":"AB12CD34EF","typ":"JWT"}"""
    }

    @Test
    fun `diagnose signs token and reports matching app`() {
        val root = Files.createTempDirectory("asc-diagnostics-")
        try {
            val store = AppStoreConnectKeyStore(WorkspacePath(root), fixedClock())
            store.save(
                IosAppStoreConnectKeySaveRequestDto(
                    keyId = "AB12CD34EF",
                    issuerId = "12345678-1234-1234-1234-123456789abc",
                    privateKeyPem = generatePrivateKeyPem(),
                )
            )
            var capturedUrl = ""
            var capturedToken = ""
            val client = object : AppStoreConnectDiagnosticService.AscHttpClient {
                override fun get(url: String, bearerToken: String): AppStoreConnectDiagnosticService.AscHttpResponse {
                    capturedUrl = url
                    capturedToken = bearerToken
                    return AppStoreConnectDiagnosticService.AscHttpResponse(
                        statusCode = 200,
                        body = """
                            {
                              "data": [
                                {
                                  "id": "1234567890",
                                  "type": "apps",
                                  "attributes": {
                                    "name": "Demo",
                                    "bundleId": "kr.codr.demo"
                                  }
                                }
                              ],
                              "meta": { "paging": { "total": 1 } }
                            }
                        """.trimIndent(),
                    )
                }
            }

            val dto = AppStoreConnectDiagnosticService(store, client, fixedClock()).diagnose("kr.codr.demo")

            dto.configured.shouldBeTrue()
            dto.authenticated.shouldBeTrue()
            dto.appsReachable.shouldBeTrue()
            dto.appCount shouldBe 1
            dto.matchingAppId shouldBe "1234567890"
            dto.matchingAppName shouldBe "Demo"
            dto.statusCode shouldBe 200
            capturedUrl shouldBe "https://api.appstoreconnect.apple.com/v1/apps?limit=1&filter%5BbundleId%5D=kr.codr.demo"
            capturedToken.split('.').size shouldBe 3
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `diagnose reports unconfigured key without network request`() {
        val root = Files.createTempDirectory("asc-diagnostics-empty-")
        try {
            var called = false
            val client = object : AppStoreConnectDiagnosticService.AscHttpClient {
                override fun get(url: String, bearerToken: String): AppStoreConnectDiagnosticService.AscHttpResponse {
                    called = true
                    return AppStoreConnectDiagnosticService.AscHttpResponse(500, "")
                }
            }

            val dto = AppStoreConnectDiagnosticService(
                AppStoreConnectKeyStore(WorkspacePath(root), fixedClock()),
                client,
                fixedClock(),
            ).diagnose("kr.codr.demo")

            dto.configured shouldBe false
            dto.warnings shouldBe listOf("app_store_connect_key_missing")
            called shouldBe false
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `lookup build reads processing state for app build number`() {
        val root = Files.createTempDirectory("asc-build-lookup-")
        try {
            val store = AppStoreConnectKeyStore(WorkspacePath(root), fixedClock())
            store.save(
                IosAppStoreConnectKeySaveRequestDto(
                    keyId = "AB12CD34EF",
                    issuerId = "12345678-1234-1234-1234-123456789abc",
                    privateKeyPem = generatePrivateKeyPem(),
                )
            )
            var capturedUrl = ""
            val client = object : AppStoreConnectDiagnosticService.AscHttpClient {
                override fun get(url: String, bearerToken: String): AppStoreConnectDiagnosticService.AscHttpResponse {
                    capturedUrl = url
                    return AppStoreConnectDiagnosticService.AscHttpResponse(
                        statusCode = 200,
                        body = """
                            {
                              "data": [
                                {
                                  "id": "build-123",
                                  "type": "builds",
                                  "attributes": {
                                    "version": "43",
                                    "uploadedDate": "2026-07-22T00:00:00-07:00",
                                    "expired": false,
                                    "processingState": "PROCESSING"
                                  }
                                }
                              ],
                              "meta": { "paging": { "total": 1 } }
                            }
                        """.trimIndent(),
                    )
                }
            }

            val dto = AppStoreConnectDiagnosticService(store, client, fixedClock())
                .lookupBuild(appId = "1234567890", bundleId = null, buildNumber = "43")

            dto.authenticated.shouldBeTrue()
            dto.buildId shouldBe "build-123"
            dto.version shouldBe "43"
            dto.processingState shouldBe "PROCESSING"
            dto.expired shouldBe false
            capturedUrl shouldBe "https://api.appstoreconnect.apple.com/v1/builds?limit=1&sort=-uploadedDate&fields%5Bbuilds%5D=version,uploadedDate,expirationDate,expired,processingState&filter%5Bapp%5D=1234567890&filter%5Bversion%5D=43"
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun generatePrivateKeyPem(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val privateKey = generator.generateKeyPair().private.encoded
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private fun fixedClock(): Clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }
}
