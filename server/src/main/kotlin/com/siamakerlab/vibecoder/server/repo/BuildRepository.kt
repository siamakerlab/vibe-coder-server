package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Builds
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class BuildRow(
    val id: String,
    val projectId: String,
    val variant: String,
    val status: TaskStatus,
    val logPath: String?,
    val artifactId: String?,
    val errorMessage: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val createdAt: String,
    /** v0.71.0 — Phase 51 #9 PR 별 빌드 비교용 git 메타데이터. null = git 정보 미수집. */
    val gitBranch: String? = null,
    val gitSha: String? = null,
)

class BuildRepository(private val clock: Clock) {

    fun create(
        id: String, projectId: String, variant: String, logPath: String,
        gitBranch: String? = null, gitSha: String? = null,
    ): BuildRow = transaction {
        val now = clock.nowIso()
        Builds.insert {
            it[Builds.id] = id
            it[Builds.projectId] = projectId
            it[Builds.variant] = variant
            it[status] = TaskStatus.PENDING.name
            it[Builds.logPath] = logPath
            it[createdAt] = now
            it[Builds.gitBranch] = gitBranch
            it[Builds.gitSha] = gitSha
        }
        BuildRow(id, projectId, variant, TaskStatus.PENDING, logPath, null, null, null, null, now,
            gitBranch = gitBranch, gitSha = gitSha)
    }

    /**
     * v0.71.0 — Phase 51 #9: 같은 branch 의 직전 SUCCESS 빌드 (PR-level 비교용).
     * branch null/blank 이면 [previousSuccessfulBefore] 와 동일 (전체 history).
     */
    fun previousSuccessfulInBranch(projectId: String, branch: String?, beforeCreatedAt: String): BuildRow? {
        if (branch.isNullOrBlank()) return previousSuccessfulBefore(projectId, beforeCreatedAt)
        return transaction {
            Builds.selectAll().where {
                (Builds.projectId eq projectId) and
                    (Builds.status eq TaskStatus.SUCCESS.name) and
                    (Builds.createdAt less beforeCreatedAt) and
                    (Builds.gitBranch eq branch)
            }
                .orderBy(Builds.createdAt to SortOrder.DESC)
                .limit(1)
                .map { it.toRow() }
                .singleOrNull()
        }
    }

    fun setStatus(id: String, status: TaskStatus, errorMessage: String? = null) = transaction {
        val now = clock.nowIso()
        Builds.update({ Builds.id eq id }) {
            it[Builds.status] = status.name
            when (status) {
                TaskStatus.RUNNING -> it[startedAt] = now
                TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELED, TaskStatus.TIMEOUT -> {
                    it[finishedAt] = now
                    if (errorMessage != null) it[Builds.errorMessage] = errorMessage
                }
                else -> Unit
            }
        }
    }

    fun attachArtifact(id: String, artifactId: String) = transaction {
        Builds.update({ Builds.id eq id }) { it[Builds.artifactId] = artifactId }
    }

    /** Null out [Builds.artifactId] for every build that points at [artifactId]. */
    fun detachArtifact(artifactId: String) = transaction {
        Builds.update({ Builds.artifactId eq artifactId }) { it[Builds.artifactId] = null }
    }

    fun get(id: String): BuildRow? = transaction {
        Builds.selectAll().where { Builds.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun listForProject(projectId: String, limit: Int = 50): List<BuildRow> = transaction {
        Builds.selectAll().where { Builds.projectId eq projectId }
            .orderBy(Builds.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
    }

    fun lastForProject(projectId: String): BuildRow? = listForProject(projectId, 1).firstOrNull()

    /**
     * v0.58.0 — Phase 37 빌드 결과 비교의 "이전" 후보.
     * 같은 projectId 의 SUCCEEDED 빌드 중 [beforeCreatedAt] 보다 createdAt 가 strictly
     * 이전인 가장 최근 row. null = 같은 프로젝트에 이전 성공 빌드 없음 (첫 성공 빌드).
     */
    fun previousSuccessfulBefore(projectId: String, beforeCreatedAt: String): BuildRow? = transaction {
        Builds.selectAll().where {
            (Builds.projectId eq projectId) and
                (Builds.status eq TaskStatus.SUCCESS.name) and
                (Builds.createdAt less beforeCreatedAt)
        }
            .orderBy(Builds.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it.toRow() }
            .singleOrNull()
    }

    /** ProjectService.delete cascade — 모든 build row 일괄 제거. */
    fun deleteForProject(projectId: String): Int = transaction {
        Builds.deleteWhere { Builds.projectId eq projectId }
    }

    /**
     * 부팅 reconcile — 재시작으로 끊긴 RUNNING/PENDING 빌드를 FAILED 로 정리. 반환값 = 정리된 행 수.
     *
     * 빌드는 in-process 큐(BuildService)로 실행되므로 서버가 빌드 도중 재시작/크래시되면
     * onSuccess/onFailure/onCancel 어느 콜백도 실행되지 못해 row 가 영원히 RUNNING/PENDING 으로
     * 남는다. 그러면 isBuildRunning() 이 영구히 true 를 반환해 idle 가드(키스토어/AdMob 저장·
     * 업로드·아카이브 등)가 무한 차단된다. 부팅 직후엔 진행 중인 빌드가 있을 수 없으므로
     * 모든 RUNNING/PENDING 을 고아로 보고 정리한다(automation run reconcile 과 동일 체계).
     */
    fun reconcileOrphans(): Int = transaction {
        val now = clock.nowIso()
        Builds.update({
            (Builds.status eq TaskStatus.RUNNING.name) or (Builds.status eq TaskStatus.PENDING.name)
        }) {
            it[status] = TaskStatus.FAILED.name
            it[finishedAt] = now
            it[errorMessage] = "orphaned_by_restart"
        }
    }

    /** Number of builds currently in PENDING or RUNNING state across the whole server. */
    fun countRunning(): Int = transaction {
        Builds.selectAll()
            .where { (Builds.status eq TaskStatus.RUNNING.name) or (Builds.status eq TaskStatus.PENDING.name) }
            .count()
            .toInt()
    }

    private fun ResultRow.toRow() = BuildRow(
        id = this[Builds.id],
        projectId = this[Builds.projectId],
        variant = this[Builds.variant],
        status = TaskStatus.valueOf(this[Builds.status]),
        logPath = this[Builds.logPath],
        artifactId = this[Builds.artifactId],
        errorMessage = this[Builds.errorMessage],
        startedAt = this[Builds.startedAt],
        finishedAt = this[Builds.finishedAt],
        createdAt = this[Builds.createdAt],
        gitBranch = this[Builds.gitBranch],
        gitSha = this[Builds.gitSha],
    )
}
