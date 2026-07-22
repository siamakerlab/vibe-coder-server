package com.siamakerlab.vibecoder.server.publish

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.db.Projects
import com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs
import com.siamakerlab.vibecoder.server.ios.AppStoreConnectDiagnosticService
import com.siamakerlab.vibecoder.server.ios.AppStoreConnectKeyStore
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadJobRepository
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadStatus
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeySaveRequestDto
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class TestFlightUploadStatusPollerTest {
    private lateinit var dbFile: Path
    private lateinit var workspaceRoot: Path

    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }

    @Before
    fun setup() {
        dbFile = Files.createTempFile("vibe-testflight-poller-test", ".db")
        workspaceRoot = Files.createTempDirectory("vibe-testflight-poller-workspace-")
        Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Projects, TestFlightUploadJobs)
            Projects.insert {
                it[id] = "demo"
                it[name] = "Demo"
                it[packageName] = "kr.codr.demo"
                it[sourcePath] = "/workspace/demo"
                it[moduleName] = "Demo"
                it[debugTask] = "assembleDebug"
                it[createdAt] = "2026-07-22T00:00:00Z"
                it[updatedAt] = "2026-07-22T00:00:00Z"
                it[projectType] = "iphone"
            }
        }
    }

    @After
    fun teardown() {
        Files.deleteIfExists(dbFile)
        Files.walk(workspaceRoot).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `poller advances uploading job to processing and accepted`() {
        val repo = TestFlightUploadJobRepository(clock)
        val job = repo.createQueued(
            projectId = "demo",
            buildId = "build-1",
            artifactId = "artifact-1",
            ipaPath = "out/app.ipa",
            bundleId = "kr.codr.demo",
            appId = "1234567890",
            appName = "Demo",
            buildNumber = "43",
            distributionGroups = null,
            releaseNotes = null,
        )
        repo.mark(job.id, TestFlightUploadStatus.UPLOADING)

        val states = ArrayDeque(listOf("PROCESSING", "VALID"))
        val poller = TestFlightUploadStatusPoller(
            uploadJobs = repo,
            appStoreConnect = diagnostics(states),
        )

        poller.tick() shouldBe 1
        repo.get(job.id)!!.status shouldBe "processing"

        poller.tick() shouldBe 1
        val accepted = repo.get(job.id)!!
        accepted.status shouldBe "accepted"
        accepted.finishedAt shouldBe "2026-07-22T00:00:00Z"
    }

    @Test
    fun `poller maps invalid ASC processing state to failed`() {
        val repo = TestFlightUploadJobRepository(clock)
        val job = repo.createQueued("demo", null, null, "out/app.ipa", "kr.codr.demo", "1234567890", null, "43", null, null)
        repo.mark(job.id, TestFlightUploadStatus.UPLOADING)

        val poller = TestFlightUploadStatusPoller(
            uploadJobs = repo,
            appStoreConnect = diagnostics(ArrayDeque(listOf("INVALID"))),
        )

        poller.tick() shouldBe 1
        val failed = repo.get(job.id)!!
        failed.status shouldBe "failed"
        failed.errorCode shouldBe "asc_processing_failed"
    }

    private fun diagnostics(states: ArrayDeque<String>): AppStoreConnectDiagnosticService {
        val store = AppStoreConnectKeyStore(WorkspacePath(workspaceRoot), clock)
        store.save(
            IosAppStoreConnectKeySaveRequestDto(
                keyId = "AB12CD34EF",
                issuerId = "12345678-1234-1234-1234-123456789abc",
                privateKeyPem = generatePrivateKeyPem(),
            )
        )
        val client = object : AppStoreConnectDiagnosticService.AscHttpClient {
            override fun get(url: String, bearerToken: String): AppStoreConnectDiagnosticService.AscHttpResponse {
                val state = states.removeFirstOrNull() ?: "VALID"
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
                                "processingState": "$state"
                              }
                            }
                          ]
                        }
                    """.trimIndent(),
                )
            }
        }
        return AppStoreConnectDiagnosticService(store, client, clock)
    }

    private fun generatePrivateKeyPem(): String {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val privateKey = generator.generateKeyPair().private.encoded
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }
}
