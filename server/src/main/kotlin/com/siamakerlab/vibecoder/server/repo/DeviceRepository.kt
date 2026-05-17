package com.siamakerlab.vibecoder.server.repo

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.db.Devices
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class DeviceRow(
    val id: String,
    val name: String,
    val tokenHash: String,
    val createdAt: String,
    val lastSeenAt: String?,
)

class DeviceRepository(private val clock: Clock) {

    fun insert(id: String, name: String, tokenHash: String): DeviceRow = transaction {
        val now = clock.nowIso()
        Devices.insert {
            it[Devices.id] = id
            it[Devices.name] = name
            it[Devices.tokenHash] = tokenHash
            it[createdAt] = now
        }
        DeviceRow(id, name, tokenHash, now, null)
    }

    fun findByTokenHash(tokenHash: String): DeviceRow? = transaction {
        Devices.select { Devices.tokenHash eq tokenHash }
            .map { it.toRow() }
            .singleOrNull()
    }

    fun touchLastSeen(id: String) = transaction {
        Devices.update({ Devices.id eq id }) { it[lastSeenAt] = clock.nowIso() }
    }

    private fun ResultRow.toRow() = DeviceRow(
        id = this[Devices.id],
        name = this[Devices.name],
        tokenHash = this[Devices.tokenHash],
        createdAt = this[Devices.createdAt],
        lastSeenAt = this[Devices.lastSeenAt],
    )
}
