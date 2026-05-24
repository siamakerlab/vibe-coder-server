package com.siamakerlab.vibecoder.server.db

import org.jetbrains.exposed.sql.Table

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val tokenHash = varchar("token_hash", 128)
    val createdAt = varchar("created_at", 64)
    val lastSeenAt = varchar("last_seen_at", 64).nullable()
    // v0.4.0+: admin 사용자와 채널 연결 (마이그레이션 시 nullable / default 로 추가)
    val userId = varchar("user_id", 64).nullable()
    val channel = varchar("channel", 16).default("app")  // "app" | "web"
    override val primaryKey = PrimaryKey(id)
}

object AdminUsers : Table("admin_users") {
    val id = varchar("id", 64)
    val username = varchar("username", 32).uniqueIndex()
    val passwordHash = varchar("password_hash", 96)
    val createdAt = varchar("created_at", 64)
    val lastLoginAt = varchar("last_login_at", 64).nullable()
    val passwordChangedAt = varchar("password_changed_at", 64)
    /**
     * v0.26.0 — TOTP (2FA, Google Authenticator 호환).
     *   - totpSecret: Base32 shared secret (160 bits). null = 2FA 비활성.
     *   - totpEnabledAt: 활성화 시각 (사용자에게 "활성 중" 표시용). null = 비활성.
     * 활성 사용자 로그인 흐름: password 통과 → 별도 단계에서 6자리 코드 검증.
     */
    val totpSecret = varchar("totp_secret", 64).nullable()
    val totpEnabledAt = varchar("totp_enabled_at", 64).nullable()
    /**
     * v0.37.0 — Role-based access control.
     *
     *   admin   — 모든 권한 (사용자 관리 / 설정 / audit / backup / agents / 2FA)
     *   member  — 프로젝트 / 콘솔 / 빌드 (자기 작업), 관리 페이지 차단
     *
     * 첫 admin (setup) 은 항상 admin. 신규 사용자 default = member.
     * viewer (read-only) 는 후속 minor 에서 추가 (현재는 admin / member 만).
     *
     * 새 컬럼이 nullable + default("admin") 이라 기존 row 는 자동 admin 으로 마이그.
     * (단일 사용자 환경 가정의 안전한 default — 권한 축소를 자동 적용하지 않음.)
     */
    val role = varchar("role", 16).default("admin")
    override val primaryKey = PrimaryKey(id)
}

object Projects : Table("projects") {
    val id = varchar("id", 64)
    val name = varchar("name", 256)
    val packageName = varchar("package_name", 256)
    val sourcePath = text("source_path")
    val moduleName = varchar("module_name", 128)
    val debugTask = varchar("debug_task", 128)
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Builds : Table("builds") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val variant = varchar("variant", 32)
    val status = varchar("status", 32)
    val logPath = text("log_path").nullable()
    val artifactId = varchar("artifact_id", 64).nullable()
    val errorMessage = text("error_message").nullable()
    val startedAt = varchar("started_at", 64).nullable()
    val finishedAt = varchar("finished_at", 64).nullable()
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object Artifacts : Table("artifacts") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val buildId = varchar("build_id", 64)
    val type = varchar("type", 32)
    val fileName = varchar("file_name", 256)
    val filePath = text("file_path")
    val sizeBytes = long("size_bytes")
    val sha256 = varchar("sha256", 128)
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object UploadedFiles : Table("uploaded_files") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val originalName = text("original_name")
    val filePath = text("file_path")
    val mimeType = varchar("mime_type", 128).nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

/**
 * v0.15.0 — 운영 audit log.
 *
 * 보존 대상: 로그인 / 비번 변경 / 디바이스 revoke / 프로젝트 create-delete /
 * 빌드 enqueue-cancel / MCP install / settings 변경 / git 토큰 / claude 콘솔
 * new/cancel 등 운영 정책상 추적 가치가 있는 사건만. 모든 API 호출을 적재하지는
 * 않음 (request log 가 아님 — IAM-level audit).
 *
 * 인덱스 정책:
 *  - 기본 PK + ts 내림차순 — `/audit` 페이지의 최근순 listing.
 *  - action / userId 별 필터를 자주 쓰면 추가 인덱스 권장 (v0.15.x 후속).
 *
 * Rotation 정책: 별도. 1인 LAN 도구라 무한 누적해도 수년 단위 안전. 정말 커지면
 * `DELETE FROM audit_log WHERE ts < now() - interval '90 days'` 등으로 별도 정리.
 */
object AuditLog : Table("audit_log") {
    val id = varchar("id", 64)
    val ts = varchar("ts", 64)
    val userId = varchar("user_id", 64).nullable()
    val deviceId = varchar("device_id", 64).nullable()
    val ip = varchar("ip", 64).nullable()
    val action = varchar("action", 64)
    val resourceType = varchar("resource_type", 64).nullable()
    val resourceId = varchar("resource_id", 256).nullable()
    val result = varchar("result", 16)   // OK / FAIL / DENIED
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(ts))
        index(isUnique = false, columns = arrayOf(action))
    }
}

/**
 * v0.16.0 — Conversation turn 영구 히스토리.
 *
 * Claude 콘솔의 모든 turn (user prompt / assistant message / tool_use / tool_result /
 * system / error) 을 DB 에 영구 적재. 콘솔 LogHub ring 은 200 프레임 휘발성이라
 * 콘솔 화면 새로고침/재접속 시 옛 대화를 못 봤다. 본 테이블이 그 휘발성 해결 +
 * 전체 history 검색/열람 기반.
 *
 * 적재 정책:
 *  - User prompt: ClaudeSessionManager.sendPrompt 가 호출 직후 한 row.
 *  - Assistant / tool_use / tool_result / system / error: ClaudeStreamParser 가
 *    파싱한 ClaudeEvent 마다 한 row. ConversationHistoryService 가 hub.emitConsole
 *    과 동시에 INSERT.
 *
 * 관련 데이터:
 *  - projectId — `__scratch__` 도 그대로 저장 (General Chat).
 *  - sessionId — Claude 자식 프로세스의 system/init 에서 받은 id. null 가능
 *    (사용자 prompt 가 spawn 직전 도착).
 *  - turnIdx — projectId+sessionId 내 단조 증가 (LogHub seq 와 별개; replay 와 다른 정렬).
 *  - role — `user|assistant|tool_use|tool_result|system|error|unknown`.
 *  - content — text/JSON 본문. tool_use 는 input JSON, tool_result 는 output JSON.
 *  - toolName / toolUseId — tool_use/tool_result 매칭용.
 *  - tokensIn/Out — Anthropic API 가 메시지에 포함 시 (없으면 null).
 *  - raw — 원본 stream-json line (forensic / replay). 큰 turn 일 수 있어 nullable.
 */
object ConversationTurns : Table("conversation_turns") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64)
    val sessionId = varchar("session_id", 64).nullable()
    val turnIdx = integer("turn_idx")
    val ts = varchar("ts", 64)
    val role = varchar("role", 16)
    val content = text("content")
    val toolName = varchar("tool_name", 64).nullable()
    val toolUseId = varchar("tool_use_id", 128).nullable()
    val tokensIn = integer("tokens_in").nullable()
    val tokensOut = integer("tokens_out").nullable()
    val raw = text("raw").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(projectId, ts))
        index(isUnique = false, columns = arrayOf(projectId, sessionId, turnIdx))
        index(isUnique = false, columns = arrayOf(toolUseId))
    }
}

/**
 * v0.33.0 — Cron 빌드 schedule.
 *
 * cronExpr 는 단순 `H:MM` (예: "02:00") 또는 `*:MM` (매시간 MM 분) 형태.
 * Full vixie-cron expression 은 v0.33.x 후속에서 별도 라이브러리 검토.
 *
 *  - enabled 가 false 면 scheduler 가 skip.
 *  - lastFiredAt 은 중복 발사 방지용 (같은 minute 한 번만).
 */
object BuildSchedules : Table("build_schedules") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val cronExpr = varchar("cron_expr", 32)            // "02:00" 또는 "*:30"
    val variant = varchar("variant", 32).default("debug")
    val enabled = bool("enabled").default(true)
    val createdAt = varchar("created_at", 64)
    val lastFiredAt = varchar("last_fired_at", 64).nullable()
    val description = varchar("description", 256).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(enabled))
    }
}

/**
 * v0.33.0 — Build webhook secret.
 *
 * 외부 시스템 (GitHub Actions / GitLab CI / monitoring) 이
 * POST /api/webhooks/build/{projectId} 로 빌드 트리거. body 의 HMAC-SHA256
 * 서명을 `X-Vibe-Signature` 헤더로 보내 검증.
 *
 * secretHash 는 BCrypt — plaintext 는 한 번 생성 시점에만 노출.
 */
object BuildWebhookSecrets : Table("build_webhook_secrets") {
    val id = varchar("id", 64)
    val projectId = varchar("project_id", 64).references(Projects.id)
    val name = varchar("name", 64)
    val secretHash = varchar("secret_hash", 96)
    val createdAt = varchar("created_at", 64)
    val lastUsedAt = varchar("last_used_at", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * v0.48.0 — WebAuthn (passkey) credential registry. Phase 27 — one row per
 * registered authenticator. A single user can have multiple passkeys
 * (multi-device, multi-platform).
 *
 * credentialId is the unique handle the browser passes back during assertion;
 * publicKeyCose is the CBOR-encoded COSE_Key produced at registration; signCount
 * is bumped on every successful assertion to detect cloning. attestationType is
 * informational (e.g. "none", "self", "packed", "fido-u2f").
 */
object WebauthnCredentials : Table("webauthn_credentials") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(AdminUsers.id)
    val credentialId = text("credential_id")     // base64url encoded; can be long
    val attestationObject = text("attestation_object")  // base64url CBOR — full attestation object from registration; used to rebuild AttestedCredentialData on assertion
    val signCount = long("sign_count").default(0)
    val transports = varchar("transports", 256).nullable() // comma-joined hints
    val attestationType = varchar("attestation_type", 32).nullable()
    val name = varchar("name", 64)               // user-friendly label
    val createdAt = varchar("created_at", 64)
    val lastUsedAt = varchar("last_used_at", 64).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index(isUnique = true, columns = arrayOf(credentialId))
        index(isUnique = false, columns = arrayOf(userId))
    }
}

/**
 * v0.46.0 — Web Push subscription registry. Each row is one browser registration
 * (PushManager.subscribe() result). Keyed by endpoint URL (unique per browser+vendor).
 *
 * Bound to a userId so admin can revoke a specific user's notifications; null userId
 * means a pre-login subscription (currently impossible — kept for future relaxation).
 */
object PushSubscriptions : Table("push_subscriptions") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).references(AdminUsers.id).nullable()
    val endpoint = text("endpoint")              // e.g. https://fcm.googleapis.com/fcm/send/<token>
    val p256dh = varchar("p256dh", 128)          // base64url, may be unused for payload-less push
    val auth = varchar("auth", 64)               // base64url, may be unused for payload-less push
    val userAgent = varchar("user_agent", 256).nullable()
    val createdAt = varchar("created_at", 64)
    val lastUsedAt = varchar("last_used_at", 64).nullable()
    override val primaryKey = PrimaryKey(id)
    init {
        index(isUnique = true, columns = arrayOf(endpoint))
        index(isUnique = false, columns = arrayOf(userId))
    }
}

val AllTables = arrayOf(
    AdminUsers, Devices, Projects, Builds, Artifacts, UploadedFiles, AuditLog, ConversationTurns,
    BuildSchedules, BuildWebhookSecrets, PushSubscriptions, WebauthnCredentials,
)
