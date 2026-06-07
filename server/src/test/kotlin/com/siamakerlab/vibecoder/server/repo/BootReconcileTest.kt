package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Builds
import com.siamakerlab.vibecoder.server.db.Projects
import com.siamakerlab.vibecoder.server.db.PromptAutomationRuns
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatus
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * v1.114.0 (P3) — **부팅 reconcile 규약** 회귀 테스트.
 *
 * 규약: "진행중(in-progress) 상태를 갖는 모든 DB 테이블은 부팅 시 reconcileOrphans() 로
 * 종료 상태로 정리돼야 한다." 서버는 in-process 로 빌드·자동화를 돌리므로, 도중에 재시작/
 * 크래시되면 종료 콜백이 실행되지 못해 row 가 RUNNING/PENDING 으로 영구 고착되고, 그 결과
 * isBuildRunning()/isActive() 가 영구 true → idle 가드(키스토어·AdMob 저장 등)가 무한 차단됐다.
 *
 * 이 테스트는 현재 "진행중" 상태를 갖는 두 테이블(Builds, PromptAutomationRuns)의
 * reconcileOrphans() 가 실제로 고아 row 를 종료 상태로 바꾸는지 검증한다. 새 "진행중" 테이블을
 * 추가할 때 같은 패턴의 테스트를 반드시 함께 추가한다.
 */
class BootReconcileTest {

    private lateinit var dbFile: Path

    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-06-07T00:00:00Z")
        override fun nowIso(): String = "2026-06-07T00:00:00"
    }

    @Before
    fun setup() {
        dbFile = Files.createTempFile("vibe-reconcile-test", ".db")
        Database.connect("jdbc:sqlite:${dbFile.toAbsolutePath()}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(Projects, Builds, PromptAutomationRuns)
        }
    }

    @After
    fun teardown() {
        Files.deleteIfExists(dbFile)
    }

    @Test
    fun `build reconcileOrphans 는 RUNNING_PENDING 을 FAILED 로 정리한다`() {
        val repo = BuildRepository(clock)
        // 고아 후보: RUNNING / PENDING.
        transaction {
            Builds.insert {
                it[id] = "b-running"; it[projectId] = "p1"; it[variant] = "debug"
                it[status] = TaskStatus.RUNNING.name; it[createdAt] = "2026-06-05T00:00:00"
            }
            Builds.insert {
                it[id] = "b-pending"; it[projectId] = "p1"; it[variant] = "debug"
                it[status] = TaskStatus.PENDING.name; it[createdAt] = "2026-06-05T00:00:01"
            }
            // 정상 종료된 row 는 건드리면 안 됨.
            Builds.insert {
                it[id] = "b-success"; it[projectId] = "p1"; it[variant] = "release"
                it[status] = TaskStatus.SUCCESS.name; it[createdAt] = "2026-06-05T00:00:02"
            }
        }

        val cleaned = repo.reconcileOrphans()
        cleaned shouldBe 2

        val running = repo.get("b-running")!!
        running.status shouldBe TaskStatus.FAILED
        running.errorMessage shouldBe "orphaned_by_restart"
        running.finishedAt shouldBe "2026-06-07T00:00:00"

        repo.get("b-pending")!!.status shouldBe TaskStatus.FAILED
        // 종료 상태였던 row 는 불변.
        repo.get("b-success")!!.status shouldBe TaskStatus.SUCCESS
    }

    @Test
    fun `automation reconcileOrphans 는 RUNNING 을 STOPPED 로 정리한다`() {
        val repo = PromptAutomationRunRepository(clock)
        transaction {
            PromptAutomationRuns.insert {
                it[id] = "r-running"; it[projectId] = "p1"; it[name] = "auto"; it[mode] = "repeat"
                it[total] = 5; it[sent] = 2; it[status] = PromptAutomationStatus.RUNNING
                it[startedAt] = "2026-06-05T00:00:00"
            }
            // 이미 종료된 run 은 불변.
            PromptAutomationRuns.insert {
                it[id] = "r-done"; it[projectId] = "p1"; it[name] = "auto"; it[mode] = "repeat"
                it[total] = 5; it[sent] = 5; it[status] = PromptAutomationStatus.DONE
                it[startedAt] = "2026-06-05T00:00:01"; it[finishedAt] = "2026-06-05T00:01:00"
            }
        }

        val cleaned = repo.reconcileOrphans()
        cleaned shouldBe 1

        val rows = transaction {
            PromptAutomationRuns.selectAll().associate {
                it[PromptAutomationRuns.id] to Pair(it[PromptAutomationRuns.status], it[PromptAutomationRuns.finishedAt])
            }
        }
        // 고아 RUNNING → STOPPED + finishedAt 세팅(orphaned_by_restart).
        rows["r-running"]!!.first shouldBe PromptAutomationStatus.STOPPED
        rows["r-running"]!!.second shouldBe "2026-06-07T00:00:00"
        // 이미 종료된 run 은 상태·finishedAt 모두 불변.
        rows["r-done"]!!.first shouldBe PromptAutomationStatus.DONE
        rows["r-done"]!!.second shouldBe "2026-06-05T00:01:00"
    }
}
