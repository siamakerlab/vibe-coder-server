package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.WebauthnCredentials
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

data class WebauthnCredentialRow(
    val id: String,
    val userId: String,
    val credentialId: String,
    val attestationObject: String,
    val signCount: Long,
    val transports: String?,
    val attestationType: String?,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

/**
 * v0.48.0 — WebAuthn (passkey) credential storage.
 *
 * `credentialId` is the natural unique key — re-registering the same authenticator
 * is not supported (clients should detect via the list and skip).
 */
class WebauthnCredentialRepository(private val clock: Clock) {

    fun insert(
        userId: String,
        credentialId: String,
        attestationObject: String,
        signCount: Long,
        transports: String?,
        attestationType: String?,
        name: String,
    ): WebauthnCredentialRow = transaction {
        val id = UUID.randomUUID().toString()
        val now = clock.nowIso()
        WebauthnCredentials.insert {
            it[WebauthnCredentials.id] = id
            it[WebauthnCredentials.userId] = userId
            it[WebauthnCredentials.credentialId] = credentialId
            it[WebauthnCredentials.attestationObject] = attestationObject
            it[WebauthnCredentials.signCount] = signCount
            it[WebauthnCredentials.transports] = transports
            it[WebauthnCredentials.attestationType] = attestationType
            it[WebauthnCredentials.name] = name
            it[WebauthnCredentials.createdAt] = now
            it[WebauthnCredentials.lastUsedAt] = null
        }
        WebauthnCredentialRow(
            id, userId, credentialId, attestationObject, signCount, transports, attestationType,
            name, now, null,
        )
    }

    fun listForUser(userId: String): List<WebauthnCredentialRow> = transaction {
        WebauthnCredentials.selectAll().where { WebauthnCredentials.userId eq userId }
            .map { it.toRow() }
    }

    fun findByCredentialId(credentialId: String): WebauthnCredentialRow? = transaction {
        WebauthnCredentials.selectAll().where { WebauthnCredentials.credentialId eq credentialId }
            .firstOrNull()?.toRow()
    }

    fun findById(id: String): WebauthnCredentialRow? = transaction {
        WebauthnCredentials.selectAll().where { WebauthnCredentials.id eq id }
            .firstOrNull()?.toRow()
    }

    fun deleteById(id: String): Boolean = transaction {
        WebauthnCredentials.deleteWhere { WebauthnCredentials.id eq id } > 0
    }

    fun deleteByUserId(userId: String): Int = transaction {
        WebauthnCredentials.deleteWhere { WebauthnCredentials.userId eq userId }
    }

    fun touchAfterAssertion(id: String, newSignCount: Long) {
        transaction {
            WebauthnCredentials.update({ WebauthnCredentials.id eq id }) {
                it[WebauthnCredentials.signCount] = newSignCount
                it[WebauthnCredentials.lastUsedAt] = clock.nowIso()
            }
        }
    }

    fun countForUser(userId: String): Int = transaction {
        WebauthnCredentials.selectAll().where { WebauthnCredentials.userId eq userId }
            .count().toInt()
    }

    private fun ResultRow.toRow() = WebauthnCredentialRow(
        id = this[WebauthnCredentials.id],
        userId = this[WebauthnCredentials.userId],
        credentialId = this[WebauthnCredentials.credentialId],
        attestationObject = this[WebauthnCredentials.attestationObject],
        signCount = this[WebauthnCredentials.signCount],
        transports = this[WebauthnCredentials.transports],
        attestationType = this[WebauthnCredentials.attestationType],
        name = this[WebauthnCredentials.name],
        createdAt = this[WebauthnCredentials.createdAt],
        lastUsedAt = this[WebauthnCredentials.lastUsedAt],
    )
}
