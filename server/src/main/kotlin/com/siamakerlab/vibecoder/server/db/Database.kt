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
            var ds: HikariDataSource? = null
            try {
                ds = HikariDataSource(cfg)
                dataSource = ds
                val database = Database.connect(ds)
                transaction(database) {
                    SchemaUtils.createMissingTablesAndColumns(*AllTables)
                    // v1.111.2 — conversation_turns.role 16 → 32. createMissingTablesAndColumns 는
                    // 기존 컬럼 타입(길이)을 넓히지 않으므로 명시적 ALTER. "tool_result_error"(17자)
                    // 등이 16 한도를 넘겨 tool 에러 turn 이 이력에 적재되지 못하던 버그 수정.
                    // 이미 32 이상이면 Postgres no-op → idempotent.
                    exec("ALTER TABLE conversation_turns ALTER COLUMN role TYPE varchar(32)")
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
                    // v0.62.0 — Phase 41 한국어 / non-ASCII 부분 매치용 trigram 인덱스.
                    // mecab-ko 같은 형태소 분석 extension 보다 가벼움 (단일 사용자 dev 환경
                    // 가정). simple tsvector 가 못 잡는 한국어 substring (예: "개발자가" →
                    // "개발자") 매치를 ILIKE %q% 로 처리하되 인덱스 덕분에 빠름.
                    exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                    exec(
                        """
                        CREATE INDEX IF NOT EXISTS conversation_turns_content_trgm_idx
                          ON conversation_turns USING GIN (content gin_trgm_ops)
                        """.trimIndent(),
                    )
                }
                log.info {
                    "PostgreSQL connected → ${db.host}:${db.port}/${db.name} (pool=${db.maxPoolSize}, sslmode=${db.sslMode})"
                }
                return database
            } catch (e: Throwable) {
                // B9 (21차 점검) — 풀 생성 성공 후 마이그레이션(예: pg_trgm CREATE EXTENSION
                // 권한 부족)이 실패하면, 이전엔 직전 풀을 close 하지 않아 connection +
                // housekeeping 스레드가 누수됐다(최대 STARTUP_RETRY 개 orphan 풀이 startup
                // window 동안 누적). 다음 시도 전에 닫는다.
                runCatching { ds?.close() }
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
