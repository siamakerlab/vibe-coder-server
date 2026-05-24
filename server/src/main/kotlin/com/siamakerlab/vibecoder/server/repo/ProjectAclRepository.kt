package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.ProjectAcls
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class ProjectAclRow(
    val projectId: String,
    val userId: String,
    val grantedBy: String,
    val createdAt: String,
)

/**
 * v0.49.0 — Project ACL persistence.
 *
 * Semantics (intentional opt-in restriction):
 * - User with **zero** ACL rows → sees ALL projects (default = no restriction).
 * - User with **1+** ACL rows → sees ONLY those projects.
 *
 * Admin role bypasses this entirely.
 */
class ProjectAclRepository(private val clock: Clock) {

    fun grant(projectId: String, userId: String, grantedBy: String): Boolean = transaction {
        val now = clock.nowIso()
        val inserted = ProjectAcls.insertIgnore {
            it[ProjectAcls.projectId] = projectId
            it[ProjectAcls.userId] = userId
            it[ProjectAcls.grantedBy] = grantedBy
            it[ProjectAcls.createdAt] = now
        }
        inserted.insertedCount > 0
    }

    fun revoke(projectId: String, userId: String): Boolean = transaction {
        ProjectAcls.deleteWhere {
            (ProjectAcls.projectId eq projectId) and (ProjectAcls.userId eq userId)
        } > 0
    }

    /** Replace the user's whole ACL with [projectIds]. Empty list clears all restrictions. */
    fun replaceForUser(userId: String, projectIds: Collection<String>, grantedBy: String) {
        transaction {
            ProjectAcls.deleteWhere { ProjectAcls.userId eq userId }
            val now = clock.nowIso()
            projectIds.distinct().forEach { pid ->
                ProjectAcls.insertIgnore {
                    it[ProjectAcls.projectId] = pid
                    it[ProjectAcls.userId] = userId
                    it[ProjectAcls.grantedBy] = grantedBy
                    it[ProjectAcls.createdAt] = now
                }
            }
        }
    }

    fun listForUser(userId: String): List<String> = transaction {
        ProjectAcls.selectAll().where { ProjectAcls.userId eq userId }
            .map { it[ProjectAcls.projectId] }
    }

    fun listUsersForProject(projectId: String): List<String> = transaction {
        ProjectAcls.selectAll().where { ProjectAcls.projectId eq projectId }
            .map { it[ProjectAcls.userId] }
    }

    fun hasAnyRowFor(userId: String): Boolean = transaction {
        ProjectAcls.selectAll().where { ProjectAcls.userId eq userId }.limit(1).any()
    }

    fun isGranted(projectId: String, userId: String): Boolean = transaction {
        ProjectAcls.selectAll().where {
            (ProjectAcls.projectId eq projectId) and (ProjectAcls.userId eq userId)
        }.limit(1).any()
    }

    fun deleteAllForProject(projectId: String) {
        transaction {
            ProjectAcls.deleteWhere { ProjectAcls.projectId eq projectId }
        }
    }
}
