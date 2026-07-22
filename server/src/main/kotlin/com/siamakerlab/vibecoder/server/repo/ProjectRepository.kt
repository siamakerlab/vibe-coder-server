package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Projects
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class ProjectRow(
    val id: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String,
    val debugTask: String,
    val createdAt: String,
    val updatedAt: String,
    /** v1.125.0 — "kotlin" | "flutter" (둘 다 Android 빌드 타깃). [com.siamakerlab.vibecoder.shared.dto.ProjectTypes]. */
    val projectType: String = "kotlin",
)

class ProjectRepository(private val clock: Clock) {

    fun insert(
        id: String,
        name: String,
        packageName: String,
        sourcePath: String,
        moduleName: String,
        debugTask: String,
        projectType: String = "kotlin",   // v1.125.0
    ): ProjectRow = transaction {
        val now = clock.nowIso()
        // v1.60.0 — 새 프로젝트는 맨 위: 현재 최소 sort_order - 1 (없으면 0).
        // 프로젝트 테이블은 단일 사용자라 행 수가 적어 메모리 min 으로 충분.
        val minOrder = Projects.selectAll().minOfOrNull { it[Projects.sortOrder] }
        val topOrder = (minOrder ?: 1) - 1
        Projects.insert {
            it[Projects.id] = id
            it[Projects.name] = name
            it[Projects.packageName] = packageName
            it[Projects.sourcePath] = sourcePath
            it[Projects.moduleName] = moduleName
            it[Projects.debugTask] = debugTask
            it[Projects.projectType] = projectType
            it[createdAt] = now
            it[updatedAt] = now
            it[sortOrder] = topOrder
        }
        ProjectRow(id, name, packageName, sourcePath, moduleName, debugTask, now, now, projectType)
    }

    fun findById(id: String): ProjectRow? = transaction {
        Projects.selectAll().where { Projects.id eq id }.map { it.toRow() }.singleOrNull()
    }

    fun list(): List<ProjectRow> = transaction {
        // v1.60.0 — 사용자 정의 순서 우선, 동률은 최신 갱신순.
        Projects.selectAll()
            .orderBy(
                Projects.sortOrder to org.jetbrains.exposed.sql.SortOrder.ASC,
                Projects.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            )
            .map { it.toRow() }
    }

    /** v1.60.0 — 현재 정렬 순서의 전체 id (ghost 제외는 호출부에서). reorder 정규화용. */
    fun listIdsInOrder(): List<String> = transaction {
        Projects.selectAll()
            .orderBy(
                Projects.sortOrder to org.jetbrains.exposed.sql.SortOrder.ASC,
                Projects.updatedAt to org.jetbrains.exposed.sql.SortOrder.DESC,
            )
            .map { it[Projects.id] }
    }

    /** v1.60.0 — 주어진 id 순서대로 sort_order = index 일괄 기록(단일 transaction). */
    fun applyOrder(ids: List<String>): Unit = transaction {
        ids.forEachIndexed { idx, id ->
            Projects.update({ Projects.id eq id }) { it[sortOrder] = idx }
        }
    }

    fun delete(id: String): Int = transaction {
        Projects.deleteWhere { Projects.id eq id }
    }

    /** v1.33.0 — scratch 디렉토리 이동(workspace → .vibecoder) 마이그레이션용 source_path 갱신. */
    fun updateSourcePath(id: String, sourcePath: String): Int = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.sourcePath] = sourcePath
            it[updatedAt] = clock.nowIso()
        }
    }

    /** v1.54.0 — Chat 세션 제목(rename / 첫 프롬프트 자동 제목) 갱신. */
    fun updateName(id: String, name: String): Int = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.name] = name
            it[updatedAt] = clock.nowIso()
        }
    }

    /** v1.71.0 — 패키지명(applicationId) 갱신. 실제 코드/파일 리네임은 콘솔 프롬프트로 Claude 가 수행. */
    fun updatePackageName(id: String, packageName: String): Int = transaction {
        Projects.update({ Projects.id eq id }) {
            it[Projects.packageName] = packageName
            it[updatedAt] = clock.nowIso()
        }
    }

    /**
     * v1.71.0 — 프로젝트 id(=PK=폴더명) 변경. PK 마이그레이션 + 모든 자식 테이블 project_id repoint.
     *
     * FK 가 NO_ACTION(즉시 체크)이라 단순 `UPDATE projects SET id` 는 위반. 순서로 회피:
     *   1) 새 id 로 Projects 행 복제(자식이 가리킬 부모 먼저 존재)
     *   2) 모든 자식 테이블 project_id: old → new
     *   3) 옛 Projects 행 삭제(자식이 모두 이동한 뒤)
     * 전부 단일 transaction → 부분 실패 시 롤백.
     *
     * @return 성공 true. oldId 미존재 또는 newId 이미 존재 시 false (호출부 사전 검증 권장).
     */
    fun renameId(oldId: String, newId: String): Boolean = transaction {
        val old = Projects.selectAll().where { Projects.id eq oldId }.singleOrNull()
            ?: return@transaction false
        if (Projects.selectAll().where { Projects.id eq newId }.any()) return@transaction false
        Projects.insert {
            it[Projects.id] = newId
            it[Projects.name] = old[Projects.name]
            it[Projects.packageName] = old[Projects.packageName]
            it[Projects.sourcePath] = old[Projects.sourcePath]
            it[Projects.moduleName] = old[Projects.moduleName]
            it[Projects.debugTask] = old[Projects.debugTask]
            it[Projects.projectType] = old[Projects.projectType]
            it[Projects.createdAt] = old[Projects.createdAt]
            it[Projects.updatedAt] = clock.nowIso()
            it[Projects.sortOrder] = old[Projects.sortOrder]
        }
        // 자식 테이블 repoint. (NotificationEvents.projectId 는 nullable·FK 없음이나 일관성 위해 함께 갱신.)
        com.siamakerlab.vibecoder.server.db.Builds.update({ com.siamakerlab.vibecoder.server.db.Builds.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.Builds.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.Artifacts.update({ com.siamakerlab.vibecoder.server.db.Artifacts.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.Artifacts.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.UploadedFiles.update({ com.siamakerlab.vibecoder.server.db.UploadedFiles.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.UploadedFiles.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.ConversationTurns.update({ com.siamakerlab.vibecoder.server.db.ConversationTurns.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.ConversationTurns.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.BuildSchedules.update({ com.siamakerlab.vibecoder.server.db.BuildSchedules.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.BuildSchedules.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.BuildWebhookSecrets.update({ com.siamakerlab.vibecoder.server.db.BuildWebhookSecrets.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.BuildWebhookSecrets.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.PromptAutomationRuns.update({ com.siamakerlab.vibecoder.server.db.PromptAutomationRuns.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.PromptAutomationRuns.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs.update({ com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.TestFlightUploadJobs.projectId] = newId }
        com.siamakerlab.vibecoder.server.db.NotificationEvents.update({ com.siamakerlab.vibecoder.server.db.NotificationEvents.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.NotificationEvents.projectId] = newId }
        // v1.71.0 (정밀점검 C1) — project_acls 는 RESTRICT FK. 미repoint 시 아래 옛 PK DELETE 가
        // FK 위반으로 throw → 트랜잭션 abort. (멀티유저 제거로 보통 0행이나 업그레이드 DB 대비.)
        com.siamakerlab.vibecoder.server.db.ProjectAcls.update({ com.siamakerlab.vibecoder.server.db.ProjectAcls.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.ProjectAcls.projectId] = newId }
        // v1.91.0 — Memos.projectId 는 nullable FK. 프로젝트 전용 메모만 repoint (전역 메모는 NULL → 매칭 안 됨).
        com.siamakerlab.vibecoder.server.db.Memos.update({ com.siamakerlab.vibecoder.server.db.Memos.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.Memos.projectId] = newId }
        // v1.130.0 — ScheduledPrompts.projectId 는 Projects FK. pending 예약을 새 id 로 이전.
        com.siamakerlab.vibecoder.server.db.ScheduledPrompts.update({ com.siamakerlab.vibecoder.server.db.ScheduledPrompts.projectId eq oldId }) { it[com.siamakerlab.vibecoder.server.db.ScheduledPrompts.projectId] = newId }
        Projects.deleteWhere { Projects.id eq oldId }
        true
    }

    fun count(): Int = transaction { Projects.selectAll().count().toInt() }

    private fun ResultRow.toRow() = ProjectRow(
        id = this[Projects.id],
        name = this[Projects.name],
        packageName = this[Projects.packageName],
        sourcePath = this[Projects.sourcePath],
        moduleName = this[Projects.moduleName],
        debugTask = this[Projects.debugTask],
        createdAt = this[Projects.createdAt],
        updatedAt = this[Projects.updatedAt],
        projectType = this[Projects.projectType],
    )
}
