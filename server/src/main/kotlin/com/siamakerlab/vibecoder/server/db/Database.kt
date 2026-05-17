package com.siamakerlab.vibecoder.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.io.path.createDirectories

/**
 * SQLite + Hikari pool with WAL enabled. Single shared connection is fine for
 * an embedded server, but Hikari gives us connection lifecycle management.
 */
object VibeDb {

    private lateinit var dataSource: DataSource

    fun init(dbFile: Path): Database {
        dbFile.parent?.createDirectories()
        val cfg = HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:${dbFile.toAbsolutePath()}?journal_mode=WAL&busy_timeout=5000"
            maximumPoolSize = 1            // SQLite is single-writer
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
        }
        dataSource = HikariDataSource(cfg)
        val db = Database.connect(dataSource)
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(*AllTables)
        }
        return db
    }

    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }
}
