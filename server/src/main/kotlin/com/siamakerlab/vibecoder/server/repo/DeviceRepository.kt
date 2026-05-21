package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Devices
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

data class DeviceRow(
    val id: String,
    val name: String,
    val tokenHash: String,
    val createdAt: String,
    val lastSeenAt: String?,
    val userId: String?,
    val channel: String,
)

class DeviceRepository(private val clock: Clock) {

    fun insert(
        id: String,
        name: String,
        tokenHash: String,
        userId: String? = null,
        channel: String = "app",
    ): DeviceRow = transaction {
        val now = clock.nowIso()
        Devices.insert {
            it[Devices.id] = id
            it[Devices.name] = name
            it[Devices.tokenHash] = tokenHash
            it[createdAt] = now
            it[Devices.userId] = userId
            it[Devices.channel] = channel
        }
        DeviceRow(id, name, tokenHash, now, null, userId, channel)
    }

    fun findByTokenHash(tokenHash: String): DeviceRow? = transaction {
        Devices.selectAll().where { Devices.tokenHash eq tokenHash }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun listAll(): List<DeviceRow> = transaction {
        Devices.selectAll()
            .orderBy(Devices.createdAt to SortOrder.DESC)
            .map { it.toRow() }
    }

    fun touchLastSeen(id: String) = transaction {
        Devices.update({ Devices.id eq id }) { it[lastSeenAt] = clock.nowIso() }
    }

    fun deleteById(id: String): Boolean = transaction {
        Devices.deleteWhere { Devices.id eq id } > 0
    }

    private fun ResultRow.toRow() = DeviceRow(
        id = this[Devices.id],
        name = this[Devices.name],
        tokenHash = this[Devices.tokenHash],
        createdAt = this[Devices.createdAt],
        lastSeenAt = this[Devices.lastSeenAt],
        userId = this[Devices.userId],
        channel = this[Devices.channel],
    )
}
