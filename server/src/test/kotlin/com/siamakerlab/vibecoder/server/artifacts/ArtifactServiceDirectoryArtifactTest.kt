package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.config.BuildSection
import com.siamakerlab.vibecoder.server.config.ClaudeSection
import com.siamakerlab.vibecoder.server.config.GitSection
import com.siamakerlab.vibecoder.server.config.SecuritySection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.config.ServerSection
import com.siamakerlab.vibecoder.server.config.WorkspaceSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.db.Artifacts
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile

class ArtifactServiceDirectoryArtifactTest {
    private lateinit var dbFile: Path
    private lateinit var workspaceRoot: Path

    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-22T00:00:00Z")
        override fun nowIso(): String = "2026-07-22T00:00:00"
    }

    @Before
    fun setup() {
        dbFile = Files.createTempFile("vibe-artifact-dir-test", ".db")
        workspaceRoot = Files.createTempDirectory("vibe-artifact-dir-workspace")
        Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Artifacts)
        }
    }

    @After
    fun teardown() {
        Files.deleteIfExists(dbFile)
        if (this::workspaceRoot.isInitialized) {
            Files.walk(workspaceRoot).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `directory artifact is zipped and registered as artifact row`() {
        val source = Files.createTempDirectory("Demo.xcresult")
        Files.createDirectories(source.resolve("nested"))
        Files.writeString(source.resolve("nested/summary.txt"), "failed test summary")

        val service = ArtifactService(
            config = testConfig(),
            workspace = WorkspacePath(workspaceRoot),
            repo = ArtifactRepository(clock),
            buildRepo = BuildRepository(clock),
            clock = clock,
        )

        val artifact = service.storeBuildDirectoryArtifact(
            projectId = "demo",
            buildId = "build-1",
            sourceDir = source,
            packageName = "kr.codr.demo",
            variant = "ios-test-ios-xcresult",
            ext = "xcresult.zip",
            type = "ios-xcresult",
        )

        artifact.type shouldBe "ios-xcresult"
        artifact.fileName shouldBe "kr.codr.demo-ios-test-ios-xcresult.xcresult.zip"
        ZipFile(Path.of(artifact.filePath).toFile()).use { zip ->
            zip.entries().asSequence().map { it.name }.toList() shouldContain "nested/summary.txt"
        }
    }

    private fun testConfig(): ServerConfig = ServerConfig(
        server = ServerSection(),
        workspace = WorkspaceSection(artifactKeepCount = 20),
        security = SecuritySection(),
        claude = ClaudeSection(),
        build = BuildSection(),
        git = GitSection(),
    )
}
