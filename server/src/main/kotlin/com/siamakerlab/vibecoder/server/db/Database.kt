package com.siamakerlab.vibecoder.server.db

import com.siamakerlab.vibecoder.server.config.DatabaseSection
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * PostgreSQL + Hikari pool — v0.14.0.
 *
 * 이전 v0.13.x 까지는 SQLite + WAL 사용. PG 전환 동기:
 *   - 동시성: SQLite 는 single-writer. 영구 conversation_turns 적재 + 콘솔 스트림이
 *     동시 진행되면 lock 경합 가능. PG 는 row-level locking.
 *   - JSONB: tool_use input/output 의 가변 구조를 nested JSON column 으로 저장.
 *   - tsvector + GIN: full-text search 강력.
 *   - 운영 표준: pg_dump / 백업 / 모니터링 도구가 풍부.
 *
 * Connection 정책:
 *   - URL: jdbc:postgresql://<host>:<port>/<name>?ApplicationName=vibe-coder
 *   - Password: [DatabaseSection.password] 또는 [DatabaseSection.passwordFile] (Docker secret 등).
 *     둘 다 비어 있으면 startup 실패 (실수로 빈 비밀번호로 connect 되는 일 방지).
 *   - Pool size: 기본 10 (PG 는 multi-connection 가능). 콘솔/빌드/Claude 세션이
 *     동시에 query 할 수 있어 1보다 큰 값이 필수.
 *   - Retry: startup 시 connect 실패 시 [STARTUP_RETRY] 회 재시도 (docker compose 의
 *     postgres healthcheck 가 늦게 ready 일 수 있음).
 */
object VibeDb {

    private lateinit var dataSource: DataSource

    fun init(db: DatabaseSection): Database {
        val password = resolvePassword(db)
            ?: throw IllegalStateException(
                "VIBECODER_DB_PASSWORD (또는 _PASSWORD_FILE) 가 비어 있습니다. " +
                    "compose 의 .env 또는 server.yml 에 db 비밀번호를 지정하세요."
            )

        val cfg = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            // sslmode 는 query string 으로. ApplicationName 은 pg_stat_activity 에서 식별 용도.
            jdbcUrl = "jdbc:postgresql://${db.host}:${db.port}/${db.name}" +
                "?sslmode=${db.sslMode}&ApplicationName=vibe-coder"
            username = db.user
            this.password = password
            maximumPoolSize = db.maxPoolSize.coerceAtLeast(1)
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"  // PG default. SERIALIZABLE 은 콘솔 stream 과 충돌
            poolName = "vibe-coder-pg"
            connectionTimeout = 30_000     // 30s
            initializationFailTimeout = 60_000   // 60s — PG ready 까지 한 번 더 기회
        }

        var lastError: Throwable? = null
        repeat(STARTUP_RETRY) { attempt ->
            try {
                dataSource = HikariDataSource(cfg)
                val database = Database.connect(dataSource)
                transaction(database) {
                    SchemaUtils.createMissingTablesAndColumns(*AllTables)
                    // v0.53.0 — Phase 32 풀텍스트 검색 (tsvector + GIN).
                    // Exposed 0.55 의 schema DSL 이 generated column / GIN index 미지원 →
                    // raw SQL 로 idempotent 마이그. `IF NOT EXISTS` 가 두 번째 부팅 부터 no-op.
                    exec(
                        """
                        ALTER TABLE conversation_turns
                          ADD COLUMN IF NOT EXISTS content_tsv tsvector
                          GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED
                        """.trimIndent(),
                    )
                    exec(
                        """
                        CREATE INDEX IF NOT EXISTS conversation_turns_content_tsv_idx
                          ON conversation_turns USING GIN (content_tsv)
                        """.trimIndent(),
                    )
                }
                log.info {
                    "PostgreSQL connected → ${db.host}:${db.port}/${db.name} (pool=${db.maxPoolSize}, sslmode=${db.sslMode})"
                }
                return database
            } catch (e: Throwable) {
                lastError = e
                log.warn { "DB connect attempt ${attempt + 1}/$STARTUP_RETRY failed: ${e.message}" }
                if (attempt + 1 < STARTUP_RETRY) {
                    Thread.sleep(STARTUP_BACKOFF_MS)
                }
            }
        }
        throw IllegalStateException(
            "PostgreSQL 에 연결할 수 없습니다 (${STARTUP_RETRY}회 재시도 실패). " +
                "host=${db.host}:${db.port}/${db.name} — postgres 컨테이너가 실행 중인지 확인하세요.",
            lastError,
        )
    }

    /**
     * Password 결정 우선순위:
     *   1. passwordFile 이 지정되어 있고 읽을 수 있으면 그 내용 (trim).
     *      Docker secret (`/run/secrets/db_password`) 패턴 지원.
     *   2. password (yml 또는 env).
     *   3. 둘 다 비어 있으면 null → 호출자가 startup 실패.
     */
    private fun resolvePassword(db: DatabaseSection): String? {
        if (db.passwordFile.isNotBlank()) {
            val p = Path.of(db.passwordFile)
            if (Files.exists(p)) {
                val v = Files.readString(p).trim()
                if (v.isNotEmpty()) return v
                log.warn { "passwordFile ($p) 가 비어 있습니다. password 값으로 fallback." }
            } else {
                log.warn { "passwordFile ($p) 가 존재하지 않습니다. password 값으로 fallback." }
            }
        }
        return db.password.takeIf { it.isNotBlank() }
    }

    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    private const val STARTUP_RETRY = 30          // 30 * 2s = 최대 60초 대기
    private const val STARTUP_BACKOFF_MS = 2_000L
}
