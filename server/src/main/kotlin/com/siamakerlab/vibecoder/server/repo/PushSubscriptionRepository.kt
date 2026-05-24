package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.PushSubscriptions
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * v0.46.0 — Web Push subscription persistence. One row per (browser, vendor) install.
 *
 * Endpoint is the natural unique key. Upsert on POST /api/push/subscribe so a browser
 * re-registering replaces its previous row (keeps `userId` fresh, no orphans).
 */
data class PushSubscriptionRow(
    val id: String,
    val userId: String?,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val userAgent: String?,
    val createdAt: String,
    val lastUsedAt: String?,
)

class PushSubscriptionRepository(private val clock: Clock) {

    fun upsert(
        userId: String?,
        endpoint: String,
        p256dh: String,
        auth: String,
        userAgent: String?,
    ): PushSubscriptionRow = transaction {
        val now = clock.nowIso()
        val existing = PushSubscriptions
            .selectAll().where { PushSubscriptions.endpoint eq endpoint }
            .firstOrNull()
        if (existing != null) {
            val id = existing[PushSubscriptions.id]
            PushSubscriptions.update({ PushSubscriptions.id eq id }) {
                it[PushSubscriptions.userId] = userId
                it[PushSubscriptions.p256dh] = p256dh
                it[PushSubscriptions.auth] = auth
                it[PushSubscriptions.userAgent] = userAgent
                it[PushSubscriptions.lastUsedAt] = now
            }
            PushSubscriptionRow(
                id = id, userId = userId, endpoint = endpoint, p256dh = p256dh, auth = auth,
                userAgent = userAgent, createdAt = existing[PushSubscriptions.createdAt], lastUsedAt = now,
            )
        } else {
            val id = UUID.randomUUID().toString()
            PushSubscriptions.insert {
                it[PushSubscriptions.id] = id
                it[PushSubscriptions.userId] = userId
                it[PushSubscriptions.endpoint] = endpoint
                it[PushSubscriptions.p256dh] = p256dh
                it[PushSubscriptions.auth] = auth
                it[PushSubscriptions.userAgent] = userAgent
                it[PushSubscriptions.createdAt] = now
                it[PushSubscriptions.lastUsedAt] = now
            }
            PushSubscriptionRow(id, userId, endpoint, p256dh, auth, userAgent, now, now)
        }
    }

    fun list(): List<PushSubscriptionRow> = transaction {
        PushSubscriptions.selectAll().map { it.toRow() }
    }

    fun listForUser(userId: String): List<PushSubscriptionRow> = transaction {
        PushSubscriptions.selectAll().where { PushSubscriptions.userId eq userId }.map { it.toRow() }
    }

    fun deleteById(id: String): Boolean = transaction {
        PushSubscriptions.deleteWhere { PushSubscriptions.id eq id } > 0
    }

    fun deleteByEndpoint(endpoint: String): Boolean = transaction {
        PushSubscriptions.deleteWhere { PushSubscriptions.endpoint eq endpoint } > 0
    }

    fun count(): Int = transaction {
        PushSubscriptions.selectAll().count().toInt()
    }

    private fun ResultRow.toRow() = PushSubscriptionRow(
        id = this[PushSubscriptions.id],
        userId = this[PushSubscriptions.userId],
        endpoint = this[PushSubscriptions.endpoint],
        p256dh = this[PushSubscriptions.p256dh],
        auth = this[PushSubscriptions.auth],
        userAgent = this[PushSubscriptions.userAgent],
        createdAt = this[PushSubscriptions.createdAt],
        lastUsedAt = this[PushSubscriptions.lastUsedAt],
    )
}
