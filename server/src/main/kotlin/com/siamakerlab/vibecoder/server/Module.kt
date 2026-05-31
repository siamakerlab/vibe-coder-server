package com.siamakerlab.vibecoder.server

import com.siamakerlab.vibecoder.server.actions.CapabilityService
import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.actions.projectActionRoutes
import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.adminRoutes
import com.siamakerlab.vibecoder.server.admin.backupRoutes
import com.siamakerlab.vibecoder.server.admin.logSearchRoutes
import com.siamakerlab.vibecoder.server.admin.multiConsoleRoutes
import com.siamakerlab.vibecoder.server.build.buildAutomationRoutes
import com.siamakerlab.vibecoder.server.automation.promptAutomationRoutes
import com.siamakerlab.vibecoder.server.build.buildCacheRoutes
import com.siamakerlab.vibecoder.server.build.dependencyAuditRoutes
import com.siamakerlab.vibecoder.server.projects.codeAnalysisRoutes
import com.siamakerlab.vibecoder.server.projects.envFilesRoutes
import com.siamakerlab.vibecoder.server.projects.projectClaudeMdRoutes
import com.siamakerlab.vibecoder.server.projects.projectAgentRoutes
import com.siamakerlab.vibecoder.server.projects.projectMcpRoutes
import com.siamakerlab.vibecoder.server.admin.twoFactorRoutes
import com.siamakerlab.vibecoder.server.admin.corsSettingsRoutes
import com.siamakerlab.vibecoder.server.admin.SshKeyService
import com.siamakerlab.vibecoder.server.admin.sshKeyRoutes
import com.siamakerlab.vibecoder.server.admin.quotaRoutes
import com.siamakerlab.vibecoder.server.admin.keystoreRoutes
import com.siamakerlab.vibecoder.server.admin.KeystoreService
import com.siamakerlab.vibecoder.server.terminal.terminalRoutes
import com.siamakerlab.vibecoder.server.terminal.TerminalSessionManager
import com.siamakerlab.vibecoder.server.admin.envSetupRoutes
import com.siamakerlab.vibecoder.server.admin.gitIntegrationsRoutes
import com.siamakerlab.vibecoder.server.admin.mcpRoutes
import com.siamakerlab.vibecoder.server.admin.globalClaudeMdRoutes
import com.siamakerlab.vibecoder.server.admin.skillRoutes
import com.siamakerlab.vibecoder.server.admin.pluginRoutes
import com.siamakerlab.vibecoder.server.projects.projectSkillRoutes
import com.siamakerlab.vibecoder.server.projects.projectPluginRoutes
import com.siamakerlab.vibecoder.server.device.adbRoutes
import com.siamakerlab.vibecoder.server.admin.webProjectRoutes
import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.artifacts.artifactRoutes
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.auth.authRoutes
import com.siamakerlab.vibecoder.server.auth.installAuth
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.build.GradleBuilder
import com.siamakerlab.vibecoder.server.build.buildRoutes
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager
import com.siamakerlab.vibecoder.server.claude.subAgentRoutes
import com.siamakerlab.vibecoder.server.metrics.metricsRoutes
import com.siamakerlab.vibecoder.server.security.installRateLimit
import com.siamakerlab.vibecoder.server.projects.symbolRoutes
import com.siamakerlab.vibecoder.server.auth.webauthnRoutes
import com.siamakerlab.vibecoder.server.claude.usageRoutes
import com.siamakerlab.vibecoder.server.notify.pushRoutes
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.claude.consoleRoutes
import com.siamakerlab.vibecoder.server.claude.globalHistorySearchRoutes
import com.siamakerlab.vibecoder.server.claude.historyRoutes
import com.siamakerlab.vibecoder.server.claude.jsonHistoryRoutes
import com.siamakerlab.vibecoder.server.claude.jsonUsageRoutes
import com.siamakerlab.vibecoder.server.projects.jsonProjectZipRoutes
import com.siamakerlab.vibecoder.server.projects.projectTemplateRoutes
import com.siamakerlab.vibecoder.server.admin.jsonAdminRoutes
import com.siamakerlab.vibecoder.server.admin.toolsRoutes
import com.siamakerlab.vibecoder.server.notify.notificationRoutes
import com.siamakerlab.vibecoder.server.notify.emailSettingsRoutes
import com.siamakerlab.vibecoder.server.notify.webhookSettingsRoutes
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.env.agentRoutes
import com.siamakerlab.vibecoder.server.env.ClaudeAuthService
import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.EnvSetupService
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.env.envRoutes
import com.siamakerlab.vibecoder.server.env.envSetupApiRoutes
import com.siamakerlab.vibecoder.server.error.installStatusPages
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.files.fileRoutes
import com.siamakerlab.vibecoder.server.git.GitCloneService
import com.siamakerlab.vibecoder.server.git.GitCredentialStore
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.git.gitRoutes
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.projects.projectRoutes
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.UploadedFileRepository
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.audit.auditRoutes
import com.siamakerlab.vibecoder.server.prompts.promptRoutes
import com.siamakerlab.vibecoder.server.repo.AuditLogRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.server.ws.wsRoutes
import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.response.respond
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ServerContext(
    val config: ServerConfig,
    val workspace: WorkspacePath,
    val deviceRepo: DeviceRepository,
    val adminUserRepo: AdminUserRepository,
    val projectRepo: ProjectRepository,
    val buildRepo: BuildRepository,
    val artifactRepo: ArtifactRepository,
    val uploadedFileRepo: UploadedFileRepository,
    val clock: Clock,
    val tokens: TokenService,
    val pairing: PairingCodeStore,
    val authService: AuthService,
    val queue: TaskQueue,
    val hub: LogHub,
    val projects: ProjectService,
    val sessionManager: ClaudeSessionManager,
    val gradle: GradleBuilder,
    val artifacts: ArtifactService,
    val build: BuildService,
    val git: GitReader,
    val gitWriter: com.siamakerlab.vibecoder.server.git.GitWriter,
    val uploads: UploadService,
    val fileBrowser: com.siamakerlab.vibecoder.server.files.ProjectFileBrowser,
    val promptStore: com.siamakerlab.vibecoder.server.prompts.PromptTemplateStore,
    val auditRepo: AuditLogRepository,
    val auditLogger: AuditLogger,
    val conversationRepo: com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository,
    val emailNotifier: com.siamakerlab.vibecoder.server.notify.EmailNotifier,
    /** v0.27.0 — Slack / Discord / Telegram webhook notifier. */
    val webhookNotifier: com.siamakerlab.vibecoder.server.notify.WebhookNotifier,
    val status: StatusService,
    val env: EnvDiagnostics,
    val envSetup: EnvSetupService,
    val claudeAuth: ClaudeAuthService,
    val claudeLogin: ClaudeLoginService,
    val mcp: McpService,
    val gitCredentials: GitCredentialStore,
    val gitClone: GitCloneService,
    val actionRegistry: ProjectActionRegistry,
    val actionHandler: ServerActionHandler,
    val capabilityService: CapabilityService,
    val claudeStatusService: ClaudeStatusService,
    /** v0.21.0 — 백그라운드 사용량 폴링 + 임계치 알림. */
    val claudeUsageMonitor: com.siamakerlab.vibecoder.server.claude.ClaudeUsageMonitor,
    /** v0.22.0 — Play Console 업로드 트리거 (MCP google-play-publisher 위임). */
    val playPublishService: com.siamakerlab.vibecoder.server.publish.PlayPublishService,
    /** v0.23.0 — TestFlight 업로드 트리거 (MCP app-store-connect 위임). */
    val testFlightPublishService: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService,
    /** v0.28.0 — APK 서명 검사 (apksigner verify). */
    val apkSignerInspector: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector,
    /** v0.28.0 — Gradle / Android / npm 캐시 측정 + 정리. */
    val buildCacheService: com.siamakerlab.vibecoder.server.build.BuildCacheService,
    /** v0.29.0 — 프로젝트 source zip stream. */
    val projectArchiver: com.siamakerlab.vibecoder.server.projects.ProjectArchiver,
    /** v0.29.0 — 디스크 사용량 monitor + 임계치 알림. */
    val diskMonitor: com.siamakerlab.vibecoder.server.disk.DiskMonitor,
    /** v0.31.0 — Claude `.agents/` UI 관리. */
    val agentRegistry: com.siamakerlab.vibecoder.server.env.AgentRegistry,
    /** v0.31.0 — 대화 export/import. */
    val conversationExport: com.siamakerlab.vibecoder.server.claude.ConversationExportService,
    /** v0.31.0 — prompt 자동완성. */
    val promptSuggestionService: com.siamakerlab.vibecoder.server.claude.PromptSuggestionService,
    /** v0.32.0 — Gradle 의존성 audit. */
    val dependencyAudit: com.siamakerlab.vibecoder.server.build.DependencyAudit,
    /** v0.33.0 — Cron 빌드 schedule. */
    val buildScheduleRepo: com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository,
    val buildScheduler: com.siamakerlab.vibecoder.server.build.BuildScheduler,
    /** v0.33.0 — Build webhook secret. */
    val buildWebhookSecretRepo: com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRepository,
    /** v0.33.0 — Claude 세션 자동 archive. */
    val conversationArchiver: com.siamakerlab.vibecoder.server.claude.ConversationArchiver,
    /** v0.35.0 — Gradle wrapper / 코드 통계 / 워크스페이스 grep. */
    val gradleWrapperService: com.siamakerlab.vibecoder.server.build.GradleWrapperService,
    val codeStatsService: com.siamakerlab.vibecoder.server.projects.CodeStatsService,
    val codeSearchService: com.siamakerlab.vibecoder.server.projects.CodeSearchService,
    /** v0.37.0 — usersRoutes 가 신규 password 해싱에 사용. */
    val hasher: com.siamakerlab.vibecoder.server.auth.PasswordHasher,
    /** v0.44.0 — Phase 23 sub-agent process pool. */
    val subAgentManager: SubAgentSessionManager,
    /** v0.46.0 — Phase 25 Web Push subscription store. */
    val pushSubscriptionRepo: com.siamakerlab.vibecoder.server.repo.PushSubscriptionRepository,
    /** v0.46.0 — Phase 25 Web Push VAPID + sender. */
    val webPushNotifier: com.siamakerlab.vibecoder.server.notify.WebPushNotifier,
    /** v0.48.0 — Phase 27 WebAuthn (passkey 2FA). */
    val webauthnService: com.siamakerlab.vibecoder.server.auth.WebauthnService,
    /** v0.49.0 — Phase 28 Project ACL (member 가 일부 프로젝트만 보기). */
    val projectAclRepo: com.siamakerlab.vibecoder.server.repo.ProjectAclRepository,
    /** v0.54.0 — Phase 33 best-effort symbol definition finder. */
    val symbolFinder: com.siamakerlab.vibecoder.server.projects.SymbolFinder,
    /** v0.55.0 — Phase 34 Prometheus metrics registry. */
    val metrics: com.siamakerlab.vibecoder.server.metrics.MetricsRegistry,
    /** v0.56.0 — Phase 35 per-IP rate limiters (api + auth buckets). */
    val rateLimitApi: com.siamakerlab.vibecoder.server.security.RateLimiter,
    val rateLimitAuth: com.siamakerlab.vibecoder.server.security.RateLimiter,
    /** v0.60.0 — Phase 39 backup service (manual download + auto-rotation). */
    val backupService: com.siamakerlab.vibecoder.server.admin.BackupService,
    /** v0.68.0 — Phase 47 polling-based notification (Android Group C). */
    val notificationService: com.siamakerlab.vibecoder.server.notify.NotificationService,
    /** v0.70.0 — Phase 49 #1 LogSearchService 추출. SSR + JSON 양측 reuse. */
    val logSearchService: com.siamakerlab.vibecoder.server.admin.LogSearchService,
    /** v0.70.0 — Phase 49 #14 APK 시그너처 on-demand verify. */
    val apkVerifier: com.siamakerlab.vibecoder.server.artifacts.ApkVerifier,
    /** v0.72.0 — Phase 52 #4 FCM 실 발송 (Firebase 환경 변수 시 활성). */
    val fcmSender: com.siamakerlab.vibecoder.server.notify.FcmSender,
    /** v0.74.0 — Phase 57 #7 Kotlin LSP (KOTLIN_LSP_PATH 환경 변수 시 활성). */
    val kotlinLspService: com.siamakerlab.vibecoder.server.projects.KotlinLspService,
    /**
     * v1.8.0 — `/home/vibe/keystores` 영속 볼륨의 Android 키스토어 관리 + Gradle
     * signing inject. [BuildService] 와 [keystoreRoutes] 가 같은 인스턴스 공유.
     */
    val keystoreService: KeystoreService,
    /**
     * v1.9.0 — Git global identity (`user.name` / `user.email`). 컨테이너 안
     * GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config 파일을 통해 영속화.
     */
    val gitConfig: com.siamakerlab.vibecoder.server.env.GitConfigService,
    /** v1.35.0 — 전역 CLAUDE.md (user-memory, 모든 프로젝트 공통). /settings/claude-md 탭. */
    val globalClaudeMd: com.siamakerlab.vibecoder.server.env.GlobalClaudeMdService,
    /** v1.38.0 — Claude Code 플러그인/마켓플레이스 관리 (전역 /settings/plugins + 프로젝트 탭). */
    val plugins: com.siamakerlab.vibecoder.server.env.PluginService,
    /**
     * v1.27.0 — Workspace bash PTY 등록부. 사이드바 글로벌 `/terminal` 메뉴 +
     * `/ws/terminal/{id}` WebSocket 가 공유. lifecycle 은 [ServerMain] 에서
     * `ApplicationStopping` 후크로 graceful 종료.
     */
    val terminalManager: TerminalSessionManager,
    /** v1.40.0 — 무선 ADB 기기 logcat (admin). */
    val adb: com.siamakerlab.vibecoder.server.device.AdbService,
    /** v1.59.0 — 프롬프트 자동화 (서버 백그라운드 autopilot): 프리셋 저장 / 실행 이력 / 오케스트레이터. */
    val promptAutomationPresetStore: com.siamakerlab.vibecoder.server.automation.PromptAutomationPresetStore,
    val promptAutomationRunRepo: com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRepository,
    val promptAutomationManager: com.siamakerlab.vibecoder.server.automation.PromptAutomationManager,
)

fun Application.module(ctx: ServerContext) {
    val jsonCfg = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    install(DefaultHeaders)
    // v1.28.0 (B-1) — 신뢰 프록시 뒤 배포 시 X-Forwarded-For 로 실제 클라 IP 식별.
    // 활성 시 request.origin.remoteHost 가 XFF 반영 → IP 차단/rate-limit/audit 이
    // 프록시 IP 가 아닌 실제 클라 IP 기준. 직노출(LAN)에선 스푸핑 위험으로 기본 off.
    if (ctx.config.security.trustForwardedFor) {
        install(io.ktor.server.plugins.forwardedheaders.XForwardedHeaders)
    }
    install(CallLogging)
    install(IgnoreTrailingSlash) // accept `/api/path` and `/api/path/` as the same route
    install(ContentNegotiation) { json(jsonCfg) }
    install(CORS) {
        // v0.12.0 — config 기반 host 허용. `*` 포함 시 anyHost (LAN 기본).
        // 외부 노출 시엔 신뢰 origin 만 명시 (CSRF 보호).
        val hosts = ctx.config.cors.allowedHosts
        if (hosts.contains("*")) {
            anyHost()
        } else {
            hosts.forEach { entry ->
                val (host, schemes) = parseCorsHostEntry(entry)
                allowHost(host, schemes = schemes)
            }
        }
        if (ctx.config.cors.allowCredentials) {
            allowCredentials = true
        }
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }
    install(WebSockets) {
        // Ktor 3.1.x exposes pingPeriodMillis / timeoutMillis (Long) on WebSocketOptions.
        pingPeriodMillis = 20_000L
        timeoutMillis = 45_000L
        // v1.25.0 — 이전엔 Long.MAX_VALUE 라 단일 frame 으로 메모리 고갈 DoS surface.
        // 인증된 사용자만 도달하므로 실 위험 낮으나 외부 노출 (vibe.wody.work) 환경에서
        // 잘못 만든 클라이언트의 무한 buffer 차단.
        // v1.26.1 — 8MB → 16MB 절충. v1.25.2 의 8MB 환원이 noVNC 풀-HD AVD 첫 framebuffer
        // (RAW encoding ~8.3MB) 를 차단할 수 있어 16MB 로 여유. Claude stream / 콘솔 /
        // 빌드 로그 는 그대로 8MB 이내라 영향 없음. ktor 3.x 가 per-route maxFrameSize
        // override 를 직접 지원 안 함 → 글로벌 절충이 가장 단순. 단일 사용자 가정.
        maxFrameSize = 16L * 1024 * 1024
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(jsonCfg)
    }
    installStatusPages(
        serverDefaultLanguage = ctx.config.i18n.defaultLanguage,
        metrics = ctx.metrics,
    )
    // v0.56.0 — Phase 35 per-IP rate limit. Runs BEFORE auth so credential-stuffing
    // attempts get throttled even when they fail. Disabled if config flag off.
    if (ctx.config.security.rateLimit.enabled) {
        installRateLimit(
            api = ctx.rateLimitApi,
            auth = ctx.rateLimitAuth,
            deviceRepo = ctx.deviceRepo,
            tokens = ctx.tokens,
            userRepo = ctx.adminUserRepo,
            metrics = ctx.metrics,
        )
    }
    installAuth(
        ctx.deviceRepo, ctx.tokens,
        idleTimeoutMinutesProvider = { ctx.config.security.sessionIdleTimeoutMinutes },
        userRepo = ctx.adminUserRepo,
    )

    routing {
        // 인증 없이 노출되는 헬스 프로브 (Docker HEALTHCHECK / 외부 모니터링용)
        get(ApiPath.HEALTH) {
            call.respond(
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "version" to JsonPrimitive(ctx.config.server.version),
                    )
                )
            )
        }
        authRoutes(
            serverName = ctx.config.server.name,
            pairing = ctx.pairing,
            tokens = ctx.tokens,
            deviceRepo = ctx.deviceRepo,
            userRepo = ctx.adminUserRepo,
            authService = ctx.authService,
            audit = ctx.auditLogger,
            webauthn = ctx.webauthnService,
        )
        val adminDeps = AdminRoutesDeps(
            config = ctx.config,
            serverName = ctx.config.server.name,
            serverVersion = ctx.config.server.version,
            workspaceRoot = ctx.workspace.root.toString(),
            authService = ctx.authService,
            userRepo = ctx.adminUserRepo,
            deviceRepo = ctx.deviceRepo,
            statusService = ctx.status,
            envDiagnostics = ctx.env,
            audit = ctx.auditLogger,
            claudeUsageMonitor = ctx.claudeUsageMonitor,
            diskMonitor = ctx.diskMonitor,
            webauthnService = ctx.webauthnService,
            gitConfig = ctx.gitConfig,
            globalClaudeMd = ctx.globalClaudeMd,
        )
        // v1.35.0 — 전역 스킬 레지스트리 (~/.claude/skills). 전역 탭 + 프로젝트 탭 공용.
        val globalSkillRegistry = com.siamakerlab.vibecoder.server.env.SkillRegistry(
            com.siamakerlab.vibecoder.server.env.SkillRegistry.Companion::globalRoot,
        )
        adminRoutes(adminDeps)
        // v0.26.0 — 2FA SSR routes.
        twoFactorRoutes(adminDeps, ctx.adminUserRepo)
        envSetupRoutes(adminDeps, ctx.envSetup, ctx.claudeAuth, ctx.claudeLogin, ctx.gitConfig)
        mcpRoutes(adminDeps, ctx.mcp)
        globalClaudeMdRoutes(adminDeps, ctx.globalClaudeMd)
        skillRoutes(adminDeps, globalSkillRegistry)
        pluginRoutes(adminDeps, ctx.plugins)
        gitIntegrationsRoutes(adminDeps, ctx.gitCredentials, ctx.gitClone, ctx.clock)
        corsSettingsRoutes(adminDeps)
        // v1.2.0 — SSH key 관리 (자동 발급은 entrypoint, 본 routes 는 열람 + 재생성).
        sshKeyRoutes(adminDeps, SshKeyService())
        // v1.3.2 — 전역 (계정 단위) Claude 쿼타 — 사이드바 / Android 헤더용.
        quotaRoutes(ctx.claudeStatusService)
        // v1.5.0 — Android 키스토어 관리 (설정 → Keystores).
        // v1.8.0 — 같은 service 인스턴스를 BuildService 도 공유 (Gradle signing inject).
        keystoreRoutes(adminDeps, ctx.keystoreService, ctx.projectRepo, ctx.sessionManager)
        // v1.6.0 — Workspace terminal (security.allowTerminal=true 일 때만 등록).
        // v1.27.0 — 글로벌 사이드바 메뉴 (/terminal) 로 이전. manager 는 ServerMain
        // 에서 hoist + ApplicationStopping 후크로 graceful 종료. admin role 가드 +
        // owner-only ACL + idle reaper + per-user 한도 (자세히 TerminalSessionManager).
        terminalRoutes(adminDeps, ctx.terminalManager, ctx.deviceRepo, ctx.tokens, ctx.adminUserRepo)
        adbRoutes(adminDeps, ctx.adb, ctx.deviceRepo, ctx.adminUserRepo)
        // v0.10.0 — admin SSR 라우트들의 JSON API 이중 노출 (vibe-coder-android wire)
        envSetupApiRoutes(
            envSetup = ctx.envSetup,
            claudeAuth = ctx.claudeAuth,
            claudeLogin = ctx.claudeLogin,
            mcp = ctx.mcp,
            credentials = ctx.gitCredentials,
            cloneSvc = ctx.gitClone,
            clock = ctx.clock,
        )
        webProjectRoutes(
            authDeps = adminDeps,
            projects = ctx.projects,
            builds = ctx.build,
            buildRepo = ctx.buildRepo,
            artifactRepo = ctx.artifactRepo,
            sessionManager = ctx.sessionManager,
            hub = ctx.hub,
            gitReader = ctx.git,
            gitWriter = ctx.gitWriter,
            workspace = ctx.workspace,
            fileBrowser = ctx.fileBrowser,
            playPublishService = ctx.playPublishService,
            testFlightPublishService = ctx.testFlightPublishService,
            apkSignerInspector = ctx.apkSignerInspector,
            projectArchiver = ctx.projectArchiver,
            conversationRepo = ctx.conversationRepo,
            keystoreService = ctx.keystoreService,
        )
        // v0.28.0 — /settings/cache 라우트.
        buildCacheRoutes(adminDeps, ctx.buildCacheService)
        envRoutes(ctx.status, ctx.env)
        projectRoutes(ctx.projects)
        consoleRoutes(ctx.projects, ctx.sessionManager, ctx.hub, ctx.claudeStatusService, ctx.env, ctx.auditLogger, ctx.promptSuggestionService)
        projectActionRoutes(ctx.projects, ctx.actionRegistry, ctx.actionHandler, ctx.capabilityService)
        buildRoutes(ctx.build, ctx.hub, ctx.projects)
        artifactRoutes(ctx.artifactRepo, ctx.workspace, ctx.artifacts, ctx.apkVerifier, ctx.projects)
        gitRoutes(ctx.projects, ctx.git, ctx.gitWriter, ctx.auditLogger)
        fileRoutes(ctx.uploads, ctx.projects)
        promptRoutes(adminDeps, ctx.promptStore)
        auditRoutes(adminDeps, ctx.auditRepo)
        historyRoutes(adminDeps, ctx.projects, ctx.conversationRepo, ctx.conversationExport,
            ctx.tokens, ctx.deviceRepo, ctx.adminUserRepo)
        // v0.30.0 — cross-project conversation search (SSR HTML).
        globalHistorySearchRoutes(adminDeps)
        // v0.64.0 — Phase 43. JSON variant of history / chat history / cross-search /
        // export / import (Bearer 토큰 인증, vibe-coder-android v0.7.19+ wire).
        jsonHistoryRoutes(ctx.projects, ctx.conversationRepo, ctx.conversationExport)
        // v0.64.0 — Phase 43. /api/usage Anthropic 토큰/캐시 합산 JSON.
        jsonUsageRoutes(ctx.projects, ctx.conversationRepo)
        // v0.31.0 — `.agents/` 디렉토리 UI.
        agentRoutes(adminDeps, ctx.agentRegistry)
        // v0.32.0 — Env files + 의존성 audit + 로그 검색.
        envFilesRoutes(adminDeps, ctx.projects, ctx.workspace)
        projectClaudeMdRoutes(adminDeps, ctx.projects, ctx.workspace)
        projectAgentRoutes(adminDeps, ctx.projects, ctx.workspace, ctx.agentRegistry)
        projectMcpRoutes(adminDeps, ctx.projects, ctx.workspace, ctx.mcp)
        projectSkillRoutes(adminDeps, ctx.projects, ctx.workspace, globalSkillRegistry)
        projectPluginRoutes(adminDeps, ctx.projects, ctx.workspace, ctx.plugins)
        dependencyAuditRoutes(adminDeps, ctx.projects, ctx.dependencyAudit)
        logSearchRoutes(adminDeps, ctx.logSearchService)
        // v0.33.0 — Cron 빌드 + webhook trigger.
        buildAutomationRoutes(
            adminDeps, ctx.projects, ctx.buildScheduleRepo, ctx.buildWebhookSecretRepo,
            ctx.build, ctx.hub, ctx.clock,
        )
        // v1.59.0 — 프롬프트 자동화 (서버 백그라운드 autopilot): JSON + SSR.
        promptAutomationRoutes(
            adminDeps, ctx.projects, ctx.promptAutomationManager,
            ctx.promptAutomationPresetStore, ctx.promptAutomationRunRepo,
        )
        // v0.34.0 — 백업 / 복원 UI.
        backupRoutes(adminDeps, ctx.workspace, ctx.backupService)
        // v0.35.0 — 코드 분석 묶음 (wrapper / stats / search).
        codeAnalysisRoutes(
            adminDeps, ctx.projects, ctx.gradleWrapperService, ctx.codeStatsService, ctx.codeSearchService,
        )
        // v0.36.0 — N-pane multi-console.
        multiConsoleRoutes(adminDeps, ctx.projects)
        // v1.45.0 — 단일 admin 화: usersRoutes(멀티유저/역할 관리) 제거.
        emailSettingsRoutes(adminDeps, ctx.emailNotifier)
        webhookSettingsRoutes(adminDeps, ctx.webhookNotifier)
        // v0.44.0 — Phase 23 sub-agent process pool (real multi-agent).
        subAgentRoutes(adminDeps, ctx.projects, ctx.subAgentManager, ctx.agentRegistry,
            ctx.tokens, ctx.deviceRepo)
        // v0.65.0 — Phase 44 `/api/projects/{id}/zip` JSON variant (Bearer 토큰 인증).
        jsonProjectZipRoutes(ctx.projects, ctx.projectArchiver)
        // v0.66.0 — Phase 45 신규 프로젝트 starter 템플릿 카탈로그 (Bearer).
        projectTemplateRoutes()
        // v0.68.0 — Phase 47 polling-based notification (Android Group C).
        // v0.72.0 — Phase 52 #4: FCM 실 발송 wiring.
        notificationRoutes(ctx.notificationService, ctx.fcmSender)
        // v0.69.0 — Phase 48 UI 리뉴얼: /tools hub.
        toolsRoutes(adminDeps)
        // v0.67.0 — Phase 46 Group B: admin / 운영 JSON API (Bearer, admin only).
        jsonAdminRoutes(
            users = ctx.adminUserRepo,
            deviceRepo = ctx.deviceRepo,
            hasher = ctx.hasher,
            schedules = ctx.buildScheduleRepo,
            projects = ctx.projects,
            backup = ctx.backupService,
            workspace = ctx.workspace,
            audit = ctx.auditRepo,
            codeSearch = ctx.codeSearchService,
            codeStats = ctx.codeStatsService,
            deps = ctx.dependencyAudit,
            wrapper = ctx.gradleWrapperService,
            logSearch = ctx.logSearchService,
        )
        // v0.46.0 — Phase 25 Web Push (VAPID, payload-less).
        pushRoutes(adminDeps, ctx.webPushNotifier, ctx.pushSubscriptionRepo)
        // v0.47.0 — Phase 26 Claude /status raw 노출 (cache 통계 등 미래 정보 자동 가시화).
        usageRoutes(adminDeps, ctx.projects, ctx.claudeStatusService, ctx.conversationRepo)
        // v0.48.0 — Phase 27 WebAuthn (passkey 2FA).
        webauthnRoutes(adminDeps, ctx.webauthnService, ctx.authService, ctx.tokens)
        // v1.45.0 — 단일 admin 화: projectAclRoutes(Project ACL 관리 UI) 제거.
        // v0.54.0 — Phase 33 symbol definition lookup (best-effort regex).
        symbolRoutes(adminDeps, ctx.projects, ctx.symbolFinder, ctx.kotlinLspService)
        // v0.55.0 — Phase 34 Prometheus /metrics endpoint.
        metricsRoutes(adminDeps, ctx.metrics)
        wsRoutes(ctx.hub, ctx.deviceRepo, ctx.tokens, ctx.sessionManager,
            ctx.actionRegistry, ctx.actionHandler, ctx.subAgentManager, ctx.adminUserRepo, ctx.projects)
    }
}

/**
 * v0.12.0 — CORS allowed host 문자열 파싱.
 *
 * 입력 패턴 (모두 지원):
 *   `example.com`              → host="example.com", schemes=["http", "https"]
 *   `https://example.com`      → host="example.com", schemes=["https"]
 *   `http://example.com:8080`  → host="example.com:8080", schemes=["http"]
 *   `*.example.com`            → host="*.example.com", schemes=["http", "https"]
 *
 * 포트는 host 의 일부로 그대로 통과 (Ktor allowHost 가 지원).
 */
internal fun parseCorsHostEntry(entry: String): Pair<String, List<String>> {
    val trimmed = entry.trim()
    return when {
        trimmed.startsWith("https://") ->
            trimmed.removePrefix("https://").trimEnd('/') to listOf("https")
        trimmed.startsWith("http://") ->
            trimmed.removePrefix("http://").trimEnd('/') to listOf("http")
        else ->
            trimmed.trimEnd('/') to listOf("http", "https")
    }
}
