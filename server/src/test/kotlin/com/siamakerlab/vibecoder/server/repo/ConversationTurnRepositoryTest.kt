package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.ConversationTurns
import io.kotest.matchers.collections.shouldContainExactly
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

class ConversationTurnRepositoryTest {
    private lateinit var dbFile: Path

    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-07-20T00:00:00Z")
        override fun nowIso(): String = "2026-07-20T00:00:00"
    }

    @Before
    fun setup() {
        dbFile = Files.createTempFile("vibe-conversation-turn-test", ".db")
        Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(ConversationTurns)
        }
    }

    @After
    fun teardown() {
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `byTurnIdx with null session id omits session filter for TUI fallback image restore`() {
        val repo = ConversationTurnRepository(clock)
        repo.insert(projectId = "app1", provider = "claude", sessionId = "tui-a", role = "user", content = "a")
        repo.insert(projectId = "app1", provider = "claude", sessionId = "tui-b", role = "user", content = "b")
        repo.insert(projectId = "app1", provider = "codex", sessionId = "tui-c", role = "user", content = "c")
        repo.insert(projectId = "app1", provider = "claude", sessionId = "tui-a", role = "user", content = "a-1")

        repo.byTurnIdx("app1", "tui-a", 0, provider = "claude").map { it.content } shouldContainExactly listOf("a")
        repo.byTurnIdx("app1", null, 0, provider = "claude").map { it.content } shouldContainExactly listOf("a", "b")
        repo.byTurnIdx("app1", null, 1, provider = "claude").map { it.content } shouldContainExactly listOf("a-1")
    }

    @Test
    fun `byTurnIdx keeps agent turns out of main console image restore`() {
        val repo = ConversationTurnRepository(clock)
        repo.insert(projectId = "app1", provider = "claude", sessionId = "tui-a", role = "user", content = "main")
        repo.insert(projectId = "app1", provider = "claude", sessionId = "tui-b", role = "user", content = "agent", agentName = "reviewer")

        repo.byTurnIdx("app1", null, 0, provider = "claude").single().content shouldBe "main"
    }

    @Test
    fun `latestUsageContext can be scoped to current session`() {
        val repo = ConversationTurnRepository(clock)
        repo.insert(
            projectId = "app1",
            provider = "claude",
            sessionId = "old-session",
            role = "usage",
            content = """{"input":1000,"output":200,"cacheRead":3000,"cacheCreate":400}""",
        )
        repo.insert(
            projectId = "app1",
            provider = "claude",
            sessionId = "current-session",
            role = "usage",
            content = """{"input":10,"output":20,"cacheRead":30,"cacheCreate":40}""",
        )

        repo.latestUsageContext("app1", "claude", "current-session")!!.usedInput shouldBe 80
        repo.latestUsageContext("app1", "claude", "missing-session") shouldBe null
    }
}
