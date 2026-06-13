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
    val backup: BackupSection = BackupSection(),
    /** v0.77.0 — Phase 64 i18n. SSR 기본 언어 (사용자 별 설정이 없을 때 fallback). */
    val i18n: I18nSection = I18nSection(),
    /** v1.5.0 — Android 키스토어 관리. defaults 가 UI form prefill 값. */
    val keystore: KeystoreSection = KeystoreSection(),
)

/**
 * v1.5.0 — Android 앱 키스토어 관리 설정.
 *
 * [defaults] 는 `/settings/keystores` 의 "Create keystore" form 에 미리 채워질
 * distinguished-name 값들. 운영자가 본인 정보 한 번 입력해두면 매 키스토어 생성 시
 * 패키지명만 새로 입력하면 됨.
 */
@Serializable
data class KeystoreSection(
    val defaults: KeystoreDefaults = KeystoreDefaults(),
)

@Serializable
data class KeystoreDefaults(
    val name: String = "",
    val organization: String = "",
    val unit: String = "",
    val country: String = "",
    val state: String = "",
    val city: String = "",
    /** 평문 password — 운영자 본인이 server.yml 에서만 설정 (또는 env override). 비어있으면 form 필수. */
    val defaultPassword: String = "",
    val validityYears: Int = 100,
)

/**
 * v0.77.0 — Phase 64. SSR 다국어 설정.
 *
 * - [defaultLanguage]: 사용자 별 설정이 없을 때 fallback. 기본 "en" — CLAUDE.md §5
 *   "공개 문서 영문 표준" 정책 일치. Docker compose 의 `VIBECODER_DEFAULT_LANGUAGE`
 *   env 로 override 가능 (`server.yml` 수정 없이 환경별 분리).
 *   허용 값: "en", "ko".
 *
 * 신규 언어 추가 시 [com.siamakerlab.vibecoder.server.i18n.Messages] 의 SUPPORTED
 * 와 동기. 사용자는 Settings → General → Language dropdown 에서 본인 언어 변경.
 */
@Serializable
data class I18nSection(
    val defaultLanguage: String = "en",
)

/**
 * v0.60.0 — Phase 39 자동 backup. cron 분해는 BuildScheduler 와 동일 (HH:MM, asterisk wildcard).
 *
 * `enabled=false` (default) 면 BackupScheduler 가 polling 자체를 skip — 기존 수동
 * /backup 흐름만 동작.
 */
@Serializable
data class BackupSection(
    val enabled: Boolean = false,
    /** "HH:MM" / "*:MM" / "*:*". 예: "03:00" 매일 새벽 3시. */
    val cron: String = "03:00",
    /** 보관할 최근 자동 backup 파일 수. 초과 시 oldest 삭제. */
    val retentionCount: Int = 7,
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
     * v1.6.0 — Workspace terminal (PTY bash) 활성.
     * v1.27.0 — 사이드바 글로벌 메뉴로 이전. SSR 경로는 `/terminal`,
     * WS 는 `/ws/terminal/{id}` (둘 다 `ApiPath.wsTerminal()` SSOT 참조).
     * v1.27.1 — false 일 때도 SSR `/terminal` 은 등록되어 "비활성화됨" 안내를
     * 보여주고 (사이드바 메뉴 404 회피), REST/WS 만 미등록 — 외부 노출 환경의
     * 보안 차단 의도는 그대로.
     * v1.6.1 — default true. 컨테이너 sandbox + admin 인증 두 단계 가드로 충분.
     * 외부 노출 환경에서 비활성화하려면 false 명시.
     */
    val allowTerminal: Boolean = true,
    /**
     * v0.26.0 — 토큰 idle timeout (분). 0 = 무제한.
     * device.lastSeenAt 가 N 분 이상 갱신되지 않으면 자동 로그아웃 (토큰 거절).
     *
     * v0.96.0 — default 30 → 0 (무제한). LAN 단일 사용자 도구 특성상 idle 로그아웃은
     * UX 비용만 크고 보안 이득은 낮다. 특히 android client 가 백그라운드에 들어갔다
     * 돌아올 때 매번 재로그인을 요구하는 회귀가 있었다.
     * 외부 노출 환경에서는 server.yml 에서 30~60 같은 양수로 override 권장.
     */
    val sessionIdleTimeoutMinutes: Int = 0,
    /** v0.56.0 — Phase 35 per-IP rate limit. */
    val rateLimit: RateLimitSection = RateLimitSection(),
    /**
     * v1.28.0 — 신뢰 리버스 프록시 뒤 배포 시 X-Forwarded-For 로 실제 클라이언트
     * IP 식별. true 면 `XForwardedHeaders` 플러그인 설치 → `request.origin.remoteHost`
     * 가 XFF 의 클라이언트 IP 반영. false (기본) 면 직접 TCP peer (LAN 직노출).
     *
     * **중요**: 신뢰할 수 있는 프록시(openresty 등) 뒤에서만 true. 직노출 환경에서
     * true 면 클라이언트가 X-Forwarded-For 헤더를 스푸핑해 IP 차단/rate-limit 우회
     * 가능. 운영(https://vibe.wody.work — openresty→localhost:17880)처럼 프록시가
     * XFF 를 세팅·전달하는 환경에서 켠다. 안 켜면 모든 외부 IP 가 프록시 IP 하나로
     * 합쳐져 IP 차단/rate-limit 의 per-IP 격리가 무의미해진다(v1.27.4 의 IP 차단
     * 회수가 프록시 뒤에서 무력화되던 문제 — B-1).
     */
    val trustForwardedFor: Boolean = false,
)

/**
 * v0.56.0 — Phase 35 per-IP token bucket rate limit (default: lenient for LAN single-user).
 *
 * - [enabled] turns the entire feature off if you're running behind a reverse proxy that
 *   already does rate limiting (nginx, Cloudflare, etc).
 * - The **api** bucket covers /api/ and /ws/ paths — generous because the browser console
 *   easily fires 30+ requests per minute on a long Claude session.
 * - The **auth** bucket covers `/login` (SSR) and `/api/auth/login` — strict, since
 *   credential-stuffing wants high throughput.
 * - Admin Bearer tokens / admin cookie sessions bypass both buckets.
 */
@Serializable
data class RateLimitSection(
    val enabled: Boolean = true,
    val apiCapacity: Int = 120,
    val apiRefillPerSecond: Double = 2.0,
    val authCapacity: Int = 10,
    val authRefillPerSecond: Double = 0.2,
)

@Serializable
data class ClaudeSection(
    val enabled: Boolean = true,
    val path: String = "auto",
    val timeoutMinutes: Int = 60,
    val autoBuildAfterTask: Boolean = false,
    /**
     * v1.69.0 — 동시에 진행 가능한 Claude turn 수 상한. 여러 프로젝트/sub-agent 콘솔에서
     * 동시에 prompt 를 던질 때 같은 계정+IP burst 로 인한 서버측 throttle(429) 을 막는다.
     * 상한 도달 시 새 turn 은 대기(queue). **0 이하면 무제한(기존 동작)**. 기본 3 (1M
     * 컨텍스트 + throttle 회피를 위한 보수적 기본값). 변경은 서버 재시작 후 적용.
     */
    val maxConcurrentTurns: Int = 3,
    /**
     * v1.135.0 — 메인 콘솔의 **상주 세션 수 상한** (LRU 조기 회수). 세션 1개는 claude CLI
     * 프로세스 + MCP 사이드카 트리(~900MB)를 통째로 점유하므로, idle timeout(30분)만으로는
     * 다수 프로젝트를 오가며 작업할 때 메모리가 누적된다. 상한 초과 시 **가장 오래 유휴**
     * (busy 아님 + spawn 직후 grace 지난) 세션부터 SIGTERM — session-id 는 보존되어 다음
     * 프롬프트에서 `--resume` 으로 이어진다. **0 이하면 비활성(기존 idle reaper 만)**.
     * `/settings` 저장 시 즉시 반영.
     */
    val maxResidentSessions: Int = 6,
    /**
     * v1.106.0 / v1.107.2 — 프로젝트별 모델 미설정 시의 전역 기본. `claude --model <model>` 전달.
     * "default"(=CLI 기본, --model 미전달) / "sonnet" / "opus" / "fable" / "haiku" / 전체 모델 ID.
     * v1.107.2 — 운영 기본을 **"default"(CLI 기본)** 로. 모델은 프로젝트별로 콘솔 콤보박스에서
     * 개별 관리(.vibecoder/claude-model)하며, 선택값이 그대로 콤보박스에 고정 표시된다.
     * (토큰 절감을 원하면 프로젝트별로 Sonnet 선택, 또는 env VIBECODER_CLAUDE_MODEL=sonnet.)
     */
    val model: String = "default",
    /**
     * v1.106.0 — 컨텍스트(직전 turn 의 cache_read 토큰) 가 이 값을 넘으면 콘솔에 경고 +
     * 자동 재개 보류. 1M 상한 누적으로 매 turn 전체 맥락을 과금하는 것을 사용자가
     * 인지하고 "새 세션(컨텍스트 리셋)" 하도록 유도. 0 이하면 비활성.
     */
    val contextWarnTokens: Int = 350_000,
    /**
     * v1.108.0 — 자동 /compact 임계(직전 turn cache_read 토큰). 프로젝트별 auto-compact 가
     * ON(기본)이고 컨텍스트가 이 값을 넘으면, turn 종료 후 자동으로 `/compact` 를 실행해
     * 맥락을 줄이고 작업을 이어간다(Claude CLI 자동 compact 와 동형). 0 이하면 비활성.
     * env VIBECODER_CLAUDE_AUTO_COMPACT_TOKENS 로 override.
     * v1.123.1 — 700K → 500K. 더 일찍/자주 compact 해 컨텍스트를 작게 유지(긴 turn 의
     * step 당 cacheRead 누적을 줄임). warn(350K) < compact(500K) < critical(600K) 순.
     */
    val autoCompactTokens: Int = 500_000,
    /**
     * v1.123.0 — 2단계 컨텍스트 경고의 CRITICAL 임계(직전 turn cache_read 토큰).
     * [contextWarnTokens] 가 1차(주의)라면 이 값은 2차(위험): 매 turn 전체 맥락이 반복
     * 과금되는 구간임을 더 강하게 알리고 즉시 '새 세션' 을 권한다. 0 이하면 비활성.
     * env VIBECODER_CLAUDE_CONTEXT_CRITICAL_TOKENS 로 override.
     */
    val contextCriticalTokens: Int = 600_000,
    /**
     * v1.123.0 — 세션 길이 캡(컨텍스트). turn 정상 종료 후 직전 cache_read 가 이 값을 넘으면
     * 자동으로 새 세션을 시작(savedId 폐기 → 다음 prompt 는 fresh)한다. 누적 세션이
     * 길어질수록 매 step cache_read 가 비례 증가하는 폭주를 끊는 근본 차단막. /compact 와
     * 달리 컨텍스트를 완전 리셋한다(작업 파일·CLAUDE.md 는 유지). **맥락 손실이 크므로
     * 기본 0(비활성) — 운영자가 의도적으로 켠다.** 권장 900000(1M 윈도우에서 compact 700K
     * 로도 안 줄 때의 안전판). env VIBECODER_CLAUDE_SESSION_RESET_TOKENS.
     */
    val sessionResetTokens: Int = 0,
    /**
     * v1.123.0 — 세션 길이 캡(turn 수). 한 세션의 정상 완료 turn 수가 이 값에 도달하면
     * 자동으로 새 세션을 시작한다(컨텍스트 캡과 OR). 토큰과 무관하게 "오래된 세션 끊기".
     * 기본 0(비활성). env VIBECODER_CLAUDE_SESSION_TURN_CAP.
     */
    val sessionTurnCap: Int = 0,
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
