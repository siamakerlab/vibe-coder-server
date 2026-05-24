package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.db.BuildWebhookSecrets
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class BuildWebhookSecretRow(
    val id: String,
    val projectId: String,
    val name: String,
    val secretHash: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

class BuildWebhookSecretRepository(private val clock: Clock) {

    fun create(projectId: String, name: String, secretHash: String): BuildWebhookSecretRow = transaction {
        val now = clock.nowIso()
        val id = Ids.taskId()
        BuildWebhookSecrets.insert {
            it[BuildWebhookSecrets.id] = id
            it[BuildWebhookSecrets.projectId] = projectId
            it[BuildWebhookSecrets.name] = name
            it[BuildWebhookSecrets.secretHash] = secretHash
            it[createdAt] = now
        }
        BuildWebhookSecretRow(id, projectId, name, secretHash, now, null)
    }

    fun listForProject(projectId: String): List<BuildWebhookSecretRow> = transaction {
        BuildWebhookSecrets.selectAll().where { BuildWebhookSecrets.projectId eq projectId }
            .orderBy(BuildWebhookSecrets.createdAt to SortOrder.DESC)
            .map { it.toRow() }
    }

    fun touchLastUsed(id: String, ts: String): Int = transaction {
        BuildWebhookSecrets.update({ BuildWebhookSecrets.id eq id }) {
            it[lastUsedAt] = ts
        }
    }

    fun delete(id: String): Int = transaction {
        BuildWebhookSecrets.deleteWhere { BuildWebhookSecrets.id eq id }
    }

    fun deleteForProject(projectId: String): Int = transaction {
        BuildWebhookSecrets.deleteWhere { BuildWebhookSecrets.projectId eq projectId }
    }

    private fun ResultRow.toRow() = BuildWebhookSecretRow(
        id = this[BuildWebhookSecrets.id],
        projectId = this[BuildWebhookSecrets.projectId],
        name = this[BuildWebhookSecrets.name],
        secretHash = this[BuildWebhookSecrets.secretHash],
        createdAt = this[BuildWebhookSecrets.createdAt],
        lastUsedAt = this[BuildWebhookSecrets.lastUsedAt],
    )
}
