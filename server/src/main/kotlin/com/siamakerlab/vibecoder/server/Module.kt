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
import com.siamakerlab.vibecoder.server.admin.usersRoutes
import com.siamakerlab.vibecoder.server.build.buildAutomationRoutes
import com.siamakerlab.vibecoder.server.build.buildCacheRoutes
import com.siamakerlab.vibecoder.server.build.dependencyAuditRoutes
import com.siamakerlab.vibecoder.server.projects.codeAnalysisRoutes
import com.siamakerlab.vibecoder.server.projects.envFilesRoutes
import com.siamakerlab.vibecoder.server.admin.twoFactorRoutes
import com.siamakerlab.vibecoder.server.admin.corsSettingsRoutes
import com.siamakerlab.vibecoder.server.admin.envSetupRoutes
import com.siamakerlab.vibecoder.server.admin.gitIntegrationsRoutes
import com.siamakerlab.vibecoder.server.admin.mcpRoutes
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
import com.siamakerlab.vibecoder.server.admin.projectAclRoutes
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
import com.siamakerlab.vibecoder.server.emulator.emulatorRoutes
import com.siamakerlab.vibecoder.server.emulator.vncProxyRoutes
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
    val emulator: com.siamakerlab.vibecoder.server.emulator.EmulatorService,
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
)

fun Application.module(ctx: ServerContext) {
    val jsonCfg = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    install(DefaultHeaders)
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
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(jsonCfg)
    }
    installStatusPages()
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
        )
        adminRoutes(adminDeps)
        // v0.26.0 — 2FA SSR routes.
        twoFactorRoutes(adminDeps, ctx.adminUserRepo)
        envSetupRoutes(adminDeps, ctx.envSetup, ctx.claudeAuth, ctx.claudeLogin)
        mcpRoutes(adminDeps, ctx.mcp)
        gitIntegrationsRoutes(adminDeps, ctx.gitCredentials, ctx.gitClone, ctx.clock)
        corsSettingsRoutes(adminDeps)
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
            uploads = ctx.uploads,
            gitReader = ctx.git,
            gitWriter = ctx.gitWriter,
            workspace = ctx.workspace,
            fileBrowser = ctx.fileBrowser,
            playPublishService = ctx.playPublishService,
            testFlightPublishService = ctx.testFlightPublishService,
            apkSignerInspector = ctx.apkSignerInspector,
            projectArchiver = ctx.projectArchiver,
        )
        // v0.28.0 — /settings/cache 라우트.
        buildCacheRoutes(adminDeps, ctx.buildCacheService)
        envRoutes(ctx.status, ctx.env)
        projectRoutes(ctx.projects)
        consoleRoutes(ctx.projects, ctx.sessionManager, ctx.hub, ctx.claudeStatusService, ctx.env, ctx.auditLogger, ctx.promptSuggestionService)
        projectActionRoutes(ctx.projects, ctx.actionRegistry, ctx.actionHandler, ctx.capabilityService)
        buildRoutes(ctx.build, ctx.hub, ctx.projects)
        artifactRoutes(ctx.artifactRepo, ctx.workspace, ctx.artifacts)
        gitRoutes(ctx.projects, ctx.git, ctx.gitWriter, ctx.auditLogger)
        fileRoutes(ctx.uploads, ctx.projects)
        promptRoutes(adminDeps, ctx.promptStore)
        auditRoutes(adminDeps, ctx.auditRepo)
        historyRoutes(adminDeps, ctx.projects, ctx.conversationRepo, ctx.conversationExport,
            ctx.tokens, ctx.deviceRepo)
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
        dependencyAuditRoutes(adminDeps, ctx.projects, ctx.dependencyAudit)
        logSearchRoutes(adminDeps, ctx.workspace)
        // v0.33.0 — Cron 빌드 + webhook trigger.
        buildAutomationRoutes(
            adminDeps, ctx.projects, ctx.buildScheduleRepo, ctx.buildWebhookSecretRepo,
            ctx.build, ctx.hub, ctx.clock,
        )
        // v0.34.0 — 백업 / 복원 UI.
        backupRoutes(adminDeps, ctx.workspace, ctx.backupService)
        // v0.35.0 — 코드 분석 묶음 (wrapper / stats / search).
        codeAnalysisRoutes(
            adminDeps, ctx.projects, ctx.gradleWrapperService, ctx.codeStatsService, ctx.codeSearchService,
        )
        // v0.36.0 — N-pane multi-console.
        multiConsoleRoutes(adminDeps, ctx.projects)
        // v0.37.0 — 멀티 사용자 / 팀 (admin / member).
        usersRoutes(adminDeps, ctx.adminUserRepo, ctx.deviceRepo, ctx.hasher)
        emailSettingsRoutes(adminDeps, ctx.emailNotifier)
        webhookSettingsRoutes(adminDeps, ctx.webhookNotifier)
        emulatorRoutes(adminDeps, ctx.emulator)
        // v0.42.0 — noVNC reverse proxy (admin-only).
        vncProxyRoutes(adminDeps)
        // v0.44.0 — Phase 23 sub-agent process pool (real multi-agent).
        subAgentRoutes(adminDeps, ctx.projects, ctx.subAgentManager, ctx.agentRegistry,
            ctx.tokens, ctx.deviceRepo)
        // v0.65.0 — Phase 44 `/api/projects/{id}/zip` JSON variant (Bearer 토큰 인증).
        jsonProjectZipRoutes(ctx.projects, ctx.projectArchiver)
        // v0.46.0 — Phase 25 Web Push (VAPID, payload-less).
        pushRoutes(adminDeps, ctx.webPushNotifier, ctx.pushSubscriptionRepo)
        // v0.47.0 — Phase 26 Claude /status raw 노출 (cache 통계 등 미래 정보 자동 가시화).
        usageRoutes(adminDeps, ctx.projects, ctx.claudeStatusService, ctx.conversationRepo)
        // v0.48.0 — Phase 27 WebAuthn (passkey 2FA).
        webauthnRoutes(adminDeps, ctx.webauthnService, ctx.authService, ctx.tokens)
        // v0.49.0 — Phase 28 Project ACL 관리 UI.
        projectAclRoutes(adminDeps, ctx.projects, ctx.adminUserRepo, ctx.projectAclRepo)
        // v0.54.0 — Phase 33 symbol definition lookup (best-effort regex).
        symbolRoutes(adminDeps, ctx.projects, ctx.symbolFinder)
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
