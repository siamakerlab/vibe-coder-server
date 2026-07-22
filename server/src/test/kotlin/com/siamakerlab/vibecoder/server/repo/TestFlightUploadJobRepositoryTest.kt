package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Projects
import com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs
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
import java.time.Instant

class TestFlightUploadJobRepositoryTest {
    private lateinit var dbFile: Path

    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00Z"
    }

    @Before
    fun setup() {
        dbFile = Files.createTempFile("vibe-testflight-upload-test", ".db")
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
    }

    @Test
    fun `creates queued job and marks uploading`() {
        val repo = TestFlightUploadJobRepository(clock)

        val queued = repo.createQueued(
            projectId = "demo",
            buildId = "build-1",
            artifactId = "artifact-1",
            ipaPath = "out/app.ipa",
            bundleId = "kr.codr.demo",
            appId = "1234567890",
            appName = "Demo",
            buildNumber = "43",
            distributionGroups = "QA",
            releaseNotes = "notes",
        )

        queued.status shouldBe "queued"
        queued.buildNumber shouldBe "43"

        val uploading = repo.mark(queued.id, TestFlightUploadStatus.UPLOADING, "sent")!!
        uploading.status shouldBe "uploading"
        uploading.message shouldBe "sent"
        uploading.finishedAt shouldBe null
    }

    @Test
    fun `terminal status records finishedAt`() {
        val repo = TestFlightUploadJobRepository(clock)
        val queued = repo.createQueued("demo", null, null, "out/app.ipa", null, null, null, null, null, null)

        val failed = repo.mark(queued.id, TestFlightUploadStatus.FAILED, "boom", "prompt_send_failed")!!

        failed.status shouldBe "failed"
        failed.errorCode shouldBe "prompt_send_failed"
        failed.finishedAt shouldBe "2026-07-22T00:00:00Z"
    }

    @Test
    fun `failed mark normalizes known transporter messages`() {
        val repo = TestFlightUploadJobRepository(clock)
        val queued = repo.createQueued("demo", null, null, "out/app.ipa", null, null, null, null, null, null)

        val failed = repo.mark(
            queued.id,
            TestFlightUploadStatus.FAILED,
            "ERROR ITMS-90189: Redundant Binary Upload. You've already uploaded a build with this build number.",
        )!!

        failed.status shouldBe "failed"
        failed.errorCode shouldBe "duplicate_build_number"
    }

    @Test
    fun `status polling list includes only active upload jobs`() {
        val repo = TestFlightUploadJobRepository(clock)
        val queued = repo.createQueued("demo", null, null, "out/queued.ipa", null, null, null, null, null, null)
        val uploading = repo.createQueued("demo", null, null, "out/uploading.ipa", null, null, null, null, null, null)
        val processing = repo.createQueued("demo", null, null, "out/processing.ipa", null, null, null, null, null, null)
        val failed = repo.createQueued("demo", null, null, "out/failed.ipa", null, null, null, null, null, null)
        repo.mark(uploading.id, TestFlightUploadStatus.UPLOADING)
        repo.mark(processing.id, TestFlightUploadStatus.PROCESSING)
        repo.mark(failed.id, TestFlightUploadStatus.FAILED, "failed")

        repo.listForStatusPolling().map { it.id }.toSet() shouldBe setOf(uploading.id, processing.id)
        queued.status shouldBe "queued"
    }

    @Test
    fun `failure classifier maps release blocking categories`() {
        TestFlightFailureClassifier.classify("No matching provisioning profiles found") shouldBe "invalid_provisioning"
        TestFlightFailureClassifier.classify("Export compliance is required for this build") shouldBe "missing_compliance"
        TestFlightFailureClassifier.classify("Invalid screenshot dimensions for App Store metadata") shouldBe "invalid_icon_screenshot_metadata"
        TestFlightFailureClassifier.classify("Authentication failed: invalid issuer id in JWT") shouldBe "authentication_failed"
        TestFlightFailureClassifier.classify("Invalid IPA: missing Info.plist") shouldBe "invalid_ipa"
    }
}
