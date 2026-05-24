package com.siamakerlab.vibecoder.server.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val server: ServerSection,
    val workspace: WorkspaceSection,
    val security: SecuritySection,
    val claude: ClaudeSection,
    val build: BuildSection,
    val git: GitSection,
    val cors: CorsSection = CorsSection(),
    val database: DatabaseSection = DatabaseSection(),
    val email: EmailSection = EmailSection(),
    val webhook: WebhookSection = WebhookSection(),
    val webauthn: WebauthnSection = WebauthnSection(),
)

/**
 * v0.48.0 — WebAuthn Relying Party identity. Set [rpId] to the bare hostname users
 * type in the browser (e.g. `localhost`, `vibe.local`, `vibe.example.com`) — must NOT
 * include scheme or port. [origin] is the full origin used to validate the
 * `clientDataJSON.origin` field — must exactly match what the browser sends.
 *
 * For LAN access via `http://vibe.local:17880`, set:
 *   webauthn:
 *     rpId: "vibe.local"
 *     rpName: "Vibe Coder"
 *     origin: "http://vibe.local:17880"
 */
@Serializable
data class WebauthnSection(
    val rpId: String = "localhost",
    val rpName: String = "Vibe Coder",
    val origin: String = "http://localhost:17880",
)

@Serializable
data class ServerSection(
    val name: String = "Vibe Coder Server",
    val host: String = "0.0.0.0",
    val port: Int = 17880,
    val version: String = "0.1.0",
)

@Serializable
data class WorkspaceSection(
    val root: String = "./workspace",
    val maxUploadSizeMb: Long = 100,
    val artifactKeepCount: Int = 20,
    val uploadDeniedExtensions: List<String> = listOf("exe", "bat", "cmd", "ps1", "sh"),
)

@Serializable
data class SecuritySection(
    val pairingEnabled: Boolean = true,
    val pairingCodeExpireMinutes: Int = 10,
    val allowRawShell: Boolean = false,
    /**
     * v0.26.0 — 토큰 idle timeout (분). 0 = 무제한 (legacy behavior).
     * device.lastSeenAt 가 N 분 이상 갱신되지 않으면 자동 로그아웃 (토큰 거절).
     * 기본 30분.
     */
    val sessionIdleTimeoutMinutes: Int = 30,
)

@Serializable
data class ClaudeSection(
    val enabled: Boolean = true,
    val path: String = "auto",
    val timeoutMinutes: Int = 60,
    val autoBuildAfterTask: Boolean = false,
    /** v0.21.0 — usage 모니터링 정책. */
    val usage: ClaudeUsageSection = ClaudeUsageSection(),
)

/**
 * v0.21.0 — Claude 사용량 모니터링 + 임계치 알림.
 *
 * - [enabled]                : 폴링 + 임계치 트리거 활성화. 비활성 시 모든 알림 no-op.
 * - [pollIntervalMinutes]    : 백그라운드 폴링 주기 (기본 5분). claude /status 호출은
 *                              ClaudeStatusService TTL (60s) 캐시를 우회하므로 너무
 *                              짧게 잡으면 비용↑.
 * - [warnThresholdPercent]   : usagePercent 가 이 값 이상으로 transition 할 때 1회 알림.
 *                              기본 80 (= "사용량 80% 도달").
 * - [criticalThresholdPercent]: 더 강한 임계치. 기본 95.
 * - [scratchOnly]            : true 면 __scratch__ 프로젝트만 폴링. 단일 사용자 가정
 *                              에선 모든 프로젝트가 같은 quota 를 공유하므로 기본값.
 */
@Serializable
data class ClaudeUsageSection(
    val enabled: Boolean = true,
    val pollIntervalMinutes: Int = 5,
    val warnThresholdPercent: Int = 80,
    val criticalThresholdPercent: Int = 95,
    val scratchOnly: Boolean = true,
)

@Serializable
data class BuildSection(
    val timeoutMinutes: Int = 30,
    val defaultDebugTask: String = "assembleDebug",
)

@Serializable
data class GitSection(
    val enabled: Boolean = true,
    val path: String = "auto",
)

/**
 * v0.12.0 — CORS 정책.
 *
 * 기본값 `["*"]` 는 anyHost — LAN 격리 환경 가정. 외부 origin 에서 호출하는
 * web 앱이 있다면 그 origin 만 명시. `*` 가 포함되면 다른 entries 는 무시되고
 * anyHost. 그 외엔 entries 만 명시적 허용 (Ktor allowHost — wildcard
 * subdomain `*.example.com` 패턴 지원).
 *
 * 환경변수 `VIBECODER_CORS_ALLOWED_HOSTS` (콤마 구분) 가 있으면 server.yml
 * 값을 override — compose.yml 에서 server.yml 수정 없이 바꿀 때 사용.
 *
 * 보안 경고: 외부 IP 노출 환경에서 `*` 는 CSRF 위험. 신뢰 origin 만 명시.
 */
@Serializable
data class CorsSection(
    val allowedHosts: List<String> = listOf("*"),
    val allowCredentials: Boolean = false,
)

/**
 * v0.14.0 — PostgreSQL 연결 설정.
 *
 * 기본값은 docker compose 의 postgres 서비스에 맞춤 (host=postgres, port=5432).
 * 환경변수 override 우선순위:
 *   - VIBECODER_DB_URL          전체 JDBC URL (jdbc:postgresql://host:port/dbname)
 *   - VIBECODER_DB_HOST         host 만 (다른 값과 조합)
 *   - VIBECODER_DB_PORT         port
 *   - VIBECODER_DB_NAME         database 이름
 *   - VIBECODER_DB_USER         계정
 *   - VIBECODER_DB_PASSWORD     비밀번호 (직접)
 *   - VIBECODER_DB_PASSWORD_FILE  비밀번호가 들어있는 파일 경로 (Docker secret).
 *                                  존재하면 _PASSWORD 보다 우선.
 *   - VIBECODER_DB_MAX_POOL     Hikari maximumPoolSize (기본 10)
 *
 * 비밀번호 누락 시 startup 실패 (명시적 오류) — production 환경에서 빈 비밀번호로
 * 우연 connect 되는 일을 막음.
 */
/**
 * v0.17.0 — SMTP 알림.
 *
 * 기본은 비활성 (enabled=false). 사용자가 `/settings/email` UI 또는 env 로
 * SMTP 설정을 입력하면 활성. 빌드 결과 / Claude 사용량 임계치 / 디스크 경고
 * 등 운영 이벤트를 등록된 to 주소(들)로 발송.
 *
 * env override:
 *   VIBECODER_SMTP_ENABLED / _HOST / _PORT / _USER / _PASSWORD / _PASSWORD_FILE
 *   VIBECODER_SMTP_FROM / _TO  (콤마 구분 가능)
 *   VIBECODER_SMTP_TLS  (true=STARTTLS, false=plain — production 은 true)
 */
@Serializable
data class EmailSection(
    val enabled: Boolean = false,
    val host: String = "smtp.gmail.com",
    val port: Int = 587,
    val user: String = "",
    val password: String = "",
    val passwordFile: String = "",
    val from: String = "",
    /** Comma-separated 가능 (multiple recipients). */
    val to: String = "",
    val tls: Boolean = true,
    /** Claude 사용량 임계치 — 잔여 % 가 이 값 아래로 떨어지면 알림. */
    val claudeUsageWarnPercent: Int = 20,
    /** 디스크 사용량 임계치 — 사용 % 가 이 값 이상이면 알림. */
    val diskUsageWarnPercent: Int = 85,
)

/**
 * v0.27.0 — Slack / Discord / Telegram webhook 알림.
 *
 * 같은 트리거 (빌드 성공/실패, Claude 사용량 임계치, 디스크 임계치) 가 SMTP
 * 이메일과 동시에 발송될 수 있음 (`enabled=true` + provider 마다 endpoint 채워짐).
 *
 * 각 provider 는 비어 있으면 (`webhookUrl`/`botToken` 등) skip — 부분 활성 가능.
 *   - Slack: incoming webhook URL 1개. https://hooks.slack.com/services/T../B../...
 *   - Discord: webhook URL 1개. https://discord.com/api/webhooks/<id>/<token>
 *   - Telegram: bot token + chat id. https://api.telegram.org/bot<token>/sendMessage
 *
 * env override (모두 nullable 텍스트, 빈 문자열 = 비활성):
 *   VIBECODER_WEBHOOK_ENABLED, _SLACK_URL, _DISCORD_URL,
 *   VIBECODER_WEBHOOK_TELEGRAM_BOT_TOKEN, _TELEGRAM_CHAT_ID
 */
@Serializable
data class WebhookSection(
    val enabled: Boolean = false,
    val slackUrl: String = "",
    val discordUrl: String = "",
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
)

@Serializable
data class DatabaseSection(
    val host: String = "postgres",
    val port: Int = 5432,
    val name: String = "vibecoder",
    val user: String = "vibecoder",
    val password: String = "",
    val passwordFile: String = "",
    val maxPoolSize: Int = 10,
    /** SSL 모드 — disable / prefer / require / verify-ca / verify-full. */
    val sslMode: String = "disable",
)
