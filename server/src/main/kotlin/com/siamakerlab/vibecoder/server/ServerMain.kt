package com.siamakerlab.vibecoder.server

import com.siamakerlab.vibecoder.server.actions.CapabilityService
import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.PasswordHasher
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.build.GradleBuilder
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.config.ConfigLoader
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.db.VibeDb
import com.siamakerlab.vibecoder.server.env.ClaudeAuthService
import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.EnvSetupService
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.git.GitCloneService
import com.siamakerlab.vibecoder.server.git.GitCredentialStore
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.projects.KeystoreGenerator
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.UploadedFileRepository
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetAddress
import java.nio.file.Path
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

private data class CliOverrides(val port: Int? = null, val workspace: String? = null)

private fun parseArgs(args: Array<String>): CliOverrides {
    var port: Int? = null
    var workspace: String? = null
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--port", "-p" -> {
                val v = args.getOrNull(i + 1) ?: usageError("--port requires a value")
                port = v.toIntOrNull() ?: usageError("--port must be an integer: $v")
                require(port in 1..65535) { usageError("--port out of range: $port") }
                i += 2
            }
            "--workspace", "-w" -> {
                workspace = args.getOrNull(i + 1) ?: usageError("--workspace requires a value")
                i += 2
            }
            "--help", "-h" -> {
                printUsage()
                exitProcess(0)
            }
            else -> usageError("unknown argument: $a")
        }
    }
    return CliOverrides(port, workspace)
}

private fun usageError(msg: String): Nothing {
    System.err.println("error: $msg")
    printUsage()
    exitProcess(2)
}

private fun printUsage() {
    System.err.println(
        """
        |Usage: vibe-coder-server [options]
        |
        |Options:
        |  --port, -p <int>        TCP port to listen on (default from server.yml, 17880)
        |  --workspace, -w <path>  Workspace root (default ./workspace)
        |  --help, -h              Show this help and exit
        |
        |Workspace layout:
        |  <workspace>/<projectId>/        ← your Android project root (gradlew, settings, app/)
        |  <workspace>/.vibecoder/<id>/    ← server-owned metadata sidecar
        """.trimMargin(),
    )
}

fun main(args: Array<String>) {
    val overrides = parseArgs(args)
    val loaded = ConfigLoader.load()
    val config = loaded.copy(
        server = loaded.server.copy(port = overrides.port ?: loaded.server.port),
        workspace = loaded.workspace.copy(root = overrides.workspace ?: loaded.workspace.root),
    )

    val workspaceRoot = Path.of(config.workspace.root).toAbsolutePath().normalize()
    val workspace = WorkspacePath(workspaceRoot)

    // v0.14.0 — PostgreSQL connection. compose 의 postgres 컨테이너 또는 외부 PG.
    VibeDb.init(config.database)

    val clock = SystemClock()
    val deviceRepo = DeviceRepository(clock)
    val adminUserRepo = AdminUserRepository(clock)
    val projectRepo = ProjectRepository(clock)
    val buildRepo = BuildRepository(clock)
    val artifactRepo = ArtifactRepository(clock)
    val uploadedRepo = UploadedFileRepository(clock)

    val tokens = TokenService()
    val passwordHasher = PasswordHasher()
    val authService = AuthService(adminUserRepo, deviceRepo, tokens, passwordHasher, clock)

    // 첫 부팅 시 환경변수로 admin 자동 부트스트랩 (Docker 운영 편의)
    bootstrapAdminFromEnv(authService)

    val pairing = PairingCodeStore(
        clock = clock,
        ttl = Duration.ofMinutes(config.security.pairingCodeExpireMinutes.toLong()),
    )
    pairing.rotate()

    val queue = TaskQueue()
    val hub = LogHub()
    val keystoreGen = KeystoreGenerator(workspace)
    val gitCredentials = GitCredentialStore()
    val gitClone = GitCloneService(gitCredentials)
    val auditRepo = com.siamakerlab.vibecoder.server.repo.AuditLogRepository(clock)
    val auditLogger = com.siamakerlab.vibecoder.server.audit.AuditLogger(auditRepo)
    val conversationRepo = com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository(clock)
    val conversationHistory = com.siamakerlab.vibecoder.server.claude.ConversationHistoryService(conversationRepo)
    val emailNotifier = com.siamakerlab.vibecoder.server.notify.EmailNotifier { config.email }
    // v0.27.0 — webhook (Slack / Discord / Telegram) provider. enabled=false 시 silent.
    val webhookNotifier = com.siamakerlab.vibecoder.server.notify.WebhookNotifier({ config.webhook })
    // v0.48.0 — Phase 27 WebAuthn (passkey 2FA). Credential repo + service that wraps webauthn4j.
    val webauthnCredentialRepo = com.siamakerlab.vibecoder.server.repo.WebauthnCredentialRepository(clock)
    val webauthnService = com.siamakerlab.vibecoder.server.auth.WebauthnService(
        credentialRepo = webauthnCredentialRepo,
        rpIdProvider = { config.webauthn.rpId },
        rpNameProvider = { config.webauthn.rpName },
        originProvider = { config.webauthn.origin },
    )
    // v0.46.0 — Phase 25 Web Push (browser PushManager). subscriptionRepo wired below; the
    // notifier reads it on each broadcast so subscriptions registered after startup are visible.
    val pushSubscriptionRepo = com.siamakerlab.vibecoder.server.repo.PushSubscriptionRepository(clock)
    val webPushNotifier = com.siamakerlab.vibecoder.server.notify.WebPushNotifier(
        workspace = workspace,
        subscriptionListProvider = {
            pushSubscriptionRepo.list().map {
                com.siamakerlab.vibecoder.server.notify.WebPushNotifier.PushSubscription(
                    id = it.id,
                    endpoint = it.endpoint,
                    p256dh = it.p256dh.takeIf { v -> v.isNotBlank() },
                    auth = it.auth.takeIf { v -> v.isNotBlank() },
                )
            }
        },
        onGoneSubscription = { id -> runCatching { pushSubscriptionRepo.deleteById(id) } },
    )
    // v0.68.0 — Phase 47 polling-based notification (Android Group C).
    val notificationService = com.siamakerlab.vibecoder.server.notify.NotificationService(clock)
    // v0.72.0 — Phase 52 #4 FCM 실 발송 (Firebase env var 시 활성).
    val fcmSender = com.siamakerlab.vibecoder.server.notify.FcmSender()
    val notifiers = com.siamakerlab.vibecoder.server.notify.Notifiers(
        email = emailNotifier, webhook = webhookNotifier, webPush = webPushNotifier,
        notifications = notificationService,
        userIdsProvider = {
            // 모든 admin/member/viewer 사용자에게 fan-out. AdminUserRepository.listAll() 결과의 id 사용.
            runCatching { adminUserRepo.listAll().map { it.id as String? } }.getOrDefault(emptyList())
        },
        fcm = fcmSender,
    )
    // v0.49.0 — Project ACL persistence (member 가 일부 프로젝트만 보기).
    val projectAclRepo = com.siamakerlab.vibecoder.server.repo.ProjectAclRepository(clock)
    val sessionManager = ClaudeSessionManager(config, workspace, hub, history = conversationHistory)
    // v1.1.0 — ProjectDto.busy 필드를 위해 sessionManager 를 lambda 로 주입.
    // 구성 순서: sessionManager 가 먼저 생성되어야 lambda 가 안전하게 호출 가능.
    val projects = ProjectService(
        workspace, projectRepo, buildRepo, keystoreGen, gitClone,
        artifactRepo = artifactRepo, uploadedFileRepo = uploadedRepo,
        conversationRepo = conversationRepo,
        projectAclRepo = projectAclRepo,
        isBusyOf = sessionManager::isBusy,
    )
    // v1.7.2 — SCRATCH 프로젝트 (__scratch__) 를 server startup 시 자동 ensure.
    // 이전엔 사용자가 /chat 메뉴 한 번 진입해야 lazy bootstrap 되어
    // (1) ClaudeStatusService 가 cwd 없어 quota 호출 실패 → 사이드바 pill null
    // (2) 다른 프로젝트의 conversation history 로딩이 SCRATCH 의 ClaudeSession
    //     초기화 path 에 부분 의존 → 빈 history
    // 두 회귀 모두 startup eager 로 해결.
    runCatching { projects.ensureScratchProject() }
        .onFailure { log.warn(it) { "scratch project bootstrap failed" } }
    // v0.44.0 — Phase 23 sub-agent process pool (real multi-agent). Independent of the main
    // ClaudeSessionManager so a project can run its primary console plus multiple sub-agents
    // (reviewer / frontend / backend / ...) concurrently in the same workspace.
    val subAgentManager = com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager(
        config = config, workspace = workspace, hub = hub, history = conversationHistory,
    )
    val gradle = GradleBuilder(config)
    val artifacts = ArtifactService(config, workspace, artifactRepo, buildRepo, clock)
    // v1.8.0 — 키스토어 디렉토리는 호스트 영속 볼륨 (`/home/vibe/keystores`).
    // 빌드 시 packageName 매칭으로 Gradle signing inject (Phase 1) 와
    // keystoreRoutes 의 "Apply to project" Claude prompt (Phase 2) 가
    // 같은 KeystoreService 인스턴스를 공유.
    val keystoreService = com.siamakerlab.vibecoder.server.admin.KeystoreService(
        defaults = config.keystore.defaults,
    )
    val build = BuildService(
        config, workspace, projects, buildRepo, queue, gradle, artifacts, clock,
        notifier = notifiers, keystores = keystoreService,
    )
    val git = GitReader()
    val gitWriter = com.siamakerlab.vibecoder.server.git.GitWriter()
    val uploads = UploadService(config, workspace, uploadedRepo, clock)
    val fileBrowser = com.siamakerlab.vibecoder.server.files.ProjectFileBrowser(workspace)
    val promptStore = com.siamakerlab.vibecoder.server.prompts.PromptTemplateStore(workspace, clock)
    val env = EnvDiagnostics(config)
    val envSetup = EnvSetupService(config, queue, hub, clock)
    val claudeAuth = ClaudeAuthService(clock)
    val claudeLogin = ClaudeLoginService(clock, claudeAuth)
    // v1.9.0 — git global user.name/user.email 영속화. 컨테이너 안 git 자식 프로세스
    // (clone / commit / log) 가 GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config 를
    // 자동 인식. 본 service 는 그 파일을 `git config --global` CLI 로 read/write.
    val gitConfig = com.siamakerlab.vibecoder.server.env.GitConfigService()
    // v1.35.0 — 전역 CLAUDE.md (user-memory `~/.claude/CLAUDE.md`, 모든 프로젝트 공통 적용).
    val globalClaudeMd = com.siamakerlab.vibecoder.server.env.GlobalClaudeMdService()
    // v1.41.0 — first-run 시드(파일 없을 때만). 빌드환경 경로 + 버전-무관 gradle 정책.
    // v1.42.0 — 서버 기본 언어(en/ko) 템플릿으로 시드.
    globalClaudeMd.seedDefaultIfAbsent(config.i18n.defaultLanguage)
    val mcp = McpService(clock, queue, hub)
    // v1.37.0 — 도커 첫 설치 시 기본 MCP(fetch/memory/sequential-thinking) 자동 등록.
    // marker 기반 first-run only — 이미지 업데이트(재부팅) 시 사용자 선택 변경 안 함.
    mcp.bootstrapDefaultsIfFirstRun()
    // v1.38.0 — Claude Code 플러그인/마켓플레이스 관리 (전역 + 프로젝트, admin 전용).
    val pluginService = com.siamakerlab.vibecoder.server.env.PluginService(clock, queue, hub)
    val status = StatusService(config, projectRepo, buildRepo, env)
    val actionRegistry = ProjectActionRegistry(workspace)
    val actionHandler = ServerActionHandler(projects, build, git, hub, sessionManager)
    val capabilityService = CapabilityService(env, actionRegistry)
    val claudeStatusService = ClaudeStatusService(config, workspace, sessionManager)
    // v0.21.0 — usage 백그라운드 폴링 + 임계치 알림.
    val claudeUsageMonitor = com.siamakerlab.vibecoder.server.claude.ClaudeUsageMonitor(
        statusService = claudeStatusService,
        notifiers = notifiers,
        configProvider = { config.claude.usage },
        activeProjectsProvider = { projectRepo.list().map { it.id } },
    )
    claudeUsageMonitor.start()
    // v1.7.9 — 컨테이너 구동 중 토큰 자동 갱신. workspace.root 를 cwd 로 사용해
    // SCRATCH 디렉토리 유무와 무관하게 동작 보장.
    val claudeTokenRefresher = com.siamakerlab.vibecoder.server.claude.ClaudeTokenRefresher(
        claudeAuthService = claudeAuth,
        cwd = workspace.root,
    )
    claudeTokenRefresher.start()
    // v0.22.0 — Play Console 업로드 트리거 (MCP google-play-publisher 위임).
    val playPublishService = com.siamakerlab.vibecoder.server.publish.PlayPublishService(
        mcpService = mcp,
        sessionManager = sessionManager,
    )
    // v0.23.0 — TestFlight 업로드 트리거 (MCP app-store-connect 위임).
    val testFlightPublishService = com.siamakerlab.vibecoder.server.publish.TestFlightPublishService(
        mcpService = mcp,
        sessionManager = sessionManager,
    )
    // v0.28.0 — APK 서명 검사 + 빌드 캐시 관리.
    val apkSignerInspector = com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector()
    val buildCacheService = com.siamakerlab.vibecoder.server.build.BuildCacheService()
    // v0.31.0 — Claude .agents/ UI + 대화 export/import + prompt suggestion.
    val agentRegistry = com.siamakerlab.vibecoder.server.env.AgentRegistry()
    val conversationExport = com.siamakerlab.vibecoder.server.claude.ConversationExportService(conversationRepo)
    val promptSuggestionService = com.siamakerlab.vibecoder.server.claude.PromptSuggestionService()
    // v0.32.0 — Gradle 의존성 audit.
    val dependencyAudit = com.siamakerlab.vibecoder.server.build.DependencyAudit(workspace)
    // v0.33.0 — Cron 빌드 schedule + webhook secret + conversation archive.
    val buildScheduleRepo = com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository(clock)
    val buildWebhookSecretRepo = com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRepository(clock)
    val buildScheduler = com.siamakerlab.vibecoder.server.build.BuildScheduler(buildScheduleRepo, build, hub)
    buildScheduler.start()
    val conversationArchiver = com.siamakerlab.vibecoder.server.claude.ConversationArchiver(workspace)
    conversationArchiver.start()
    // v0.35.0 — 코드 분석 묶음 (wrapper / stats / search).
    val gradleWrapperService = com.siamakerlab.vibecoder.server.build.GradleWrapperService(workspace)
    val codeStatsService = com.siamakerlab.vibecoder.server.projects.CodeStatsService(workspace)
    val codeSearchService = com.siamakerlab.vibecoder.server.projects.CodeSearchService(workspace)
    // v0.70.0 — Phase 49 #1.
    val logSearchService = com.siamakerlab.vibecoder.server.admin.LogSearchService(workspace)
    // v0.70.0 — Phase 49 #14.
    val apkVerifier = com.siamakerlab.vibecoder.server.artifacts.ApkVerifier(workspace, artifactRepo)
    // v0.54.0 — Phase 33 best-effort symbol definition finder.
    val symbolFinder = com.siamakerlab.vibecoder.server.projects.SymbolFinder(workspace)
    // v0.74.0 — Phase 57 #7 Kotlin LSP (KOTLIN_LSP_PATH 시 활성). 미설정 시 stub.
    val kotlinLspService = com.siamakerlab.vibecoder.server.projects.KotlinLspService(workspace)
    // v0.60.0 — Phase 39 Backup service + scheduler.
    val backupService = com.siamakerlab.vibecoder.server.admin.BackupService(workspace)
    val backupScheduler = com.siamakerlab.vibecoder.server.admin.BackupScheduler(
        service = backupService,
        cronProvider = { config.backup.cron },
        retentionProvider = { config.backup.retentionCount.coerceAtLeast(1) },
        enabledProvider = { config.backup.enabled },
    )
    backupScheduler.start()

    // v0.76.0 (M5 fix) — notification_events retention prune (acked + 30일 이상).
    // 무한 누적 막아 DB 크기 GB 단위 증가 방지.
    val notificationRetentionScheduler = com.siamakerlab.vibecoder.server.notify.NotificationRetentionScheduler(
        service = notificationService,
    )
    notificationRetentionScheduler.start()

    // v0.55.0 — Phase 34 Prometheus metrics registry.
    val metrics = com.siamakerlab.vibecoder.server.metrics.MetricsRegistry().also { r ->
        // JVM baseline.
        r.gauge("vibe_jvm_memory_used_bytes", "Current JVM heap usage in bytes") {
            val rt = Runtime.getRuntime(); rt.totalMemory() - rt.freeMemory()
        }
        r.gauge("vibe_jvm_memory_max_bytes", "JVM heap maximum in bytes (-Xmx)") {
            Runtime.getRuntime().maxMemory()
        }
        r.gauge("vibe_jvm_threads", "Live JVM thread count") {
            Thread.activeCount()
        }
        // Domain state — re-read on every scrape, so values are always live.
        r.gauge("vibe_projects_total", "Registered projects (excluding __scratch__)") {
            runCatching { projectRepo.list().count { it.id != com.siamakerlab.vibecoder.server.projects.ProjectService.SCRATCH_ID } }
                .getOrDefault(0)
        }
        r.gauge("vibe_users_total", "Total admin/member/viewer users") {
            runCatching { adminUserRepo.listAll().size }.getOrDefault(0)
        }
        r.gauge("vibe_devices_total", "Live device tokens (sessions + Bearer)") {
            runCatching { deviceRepo.listAll().size }.getOrDefault(0)
        }
        r.gauge("vibe_push_subscriptions_total", "Registered Web Push subscriptions") {
            runCatching { pushSubscriptionRepo.count() }.getOrDefault(0)
        }
        r.gauge("vibe_console_sessions_active", "Live Claude main-console child processes") {
            // Best-effort via ClaudeSessionManager internals; defined below by adding a
            // public alive-count accessor — declared inline as a defensive lookup using
            // the public `isAlive(projectId)` over project list.
            runCatching {
                projectRepo.list().count { p ->
                    sessionManager.isAlive(p.id)
                }
            }.getOrDefault(0)
        }
        r.gauge("vibe_sub_agent_sessions_active", "Live Claude sub-agent child processes (total)") {
            runCatching { subAgentManager.activeAgents().size }.getOrDefault(0)
        }
    }
    // v0.55.0 — Notifiers 가 먼저 만들어져서 (build/usage/disk 알림이 sessionManager 등보다
    // 앞에 와이어업되므로), 여기서 뒤늦게 metrics 를 set. 이후 buildResult / claudeUsageWarn /
    // diskUsageWarn 트리거가 카운터를 증가시킴.
    notifiers.metrics = metrics

    // v0.56.0 — Phase 35 per-IP rate limiters. api bucket 은 console / build 트리거가
    // 빈번한 정상 사용 흐름에서 절대 거치지 않도록 capacity 120 + 2 tok/s. auth bucket 은
    // 로그인 시도를 분당 12회 정도로 강하게 제한.
    val rlCfg = config.security.rateLimit
    val rateLimitApi = com.siamakerlab.vibecoder.server.security.RateLimiter(
        capacity = rlCfg.apiCapacity, refillTokensPerSecond = rlCfg.apiRefillPerSecond,
    )
    val rateLimitAuth = com.siamakerlab.vibecoder.server.security.RateLimiter(
        capacity = rlCfg.authCapacity, refillTokensPerSecond = rlCfg.authRefillPerSecond,
    )
    // 추가 gauge: 두 limiter 의 현재 활성 IP 수.
    metrics.gauge("vibe_rate_limit_buckets_active", "Active IP buckets in the API limiter",
        labels = mapOf("bucket" to "api")) { rateLimitApi.currentBucketCount() }
    metrics.gauge("vibe_rate_limit_buckets_active", "Active IP buckets in the auth limiter",
        labels = mapOf("bucket" to "auth")) { rateLimitAuth.currentBucketCount() }
    // v0.29.0 — 프로젝트 zip + 디스크 monitor (Notifiers 와 email warn percent 공유).
    val projectArchiver = com.siamakerlab.vibecoder.server.projects.ProjectArchiver(workspace)
    // v1.27.0 — Workspace PTY 등록부. 컨테이너 안 `/workspace` 가 기본 cwd. 글로벌
    // 사이드바 메뉴 (/terminal) + WS (/ws/terminal/{id}) 가 공유. ApplicationStopping
    // 후크 (Runtime.getRuntime addShutdownHook 안) 에서 shutdownAll() 호출.
    val terminalManager = com.siamakerlab.vibecoder.server.terminal.TerminalSessionManager(
        workspaceRoot = workspaceRoot.toString(),
    )
    // v1.40.0 — 무선 ADB 기기 logcat (admin). adb 없으면 기능 페이지가 안내.
    val adbService = com.siamakerlab.vibecoder.server.device.AdbService()
    val diskMonitor = com.siamakerlab.vibecoder.server.disk.DiskMonitor(
        rootProvider = { workspace.root },
        notifiers = notifiers,
        warnThresholdPercentProvider = { config.email.diskUsageWarnPercent },
    )
    diskMonitor.start()

    val ctx = ServerContext(
        config = config,
        workspace = workspace,
        deviceRepo = deviceRepo,
        adminUserRepo = adminUserRepo,
        projectRepo = projectRepo,
        buildRepo = buildRepo,
        artifactRepo = artifactRepo,
        uploadedFileRepo = uploadedRepo,
        clock = clock,
        tokens = tokens,
        pairing = pairing,
        authService = authService,
        queue = queue,
        hub = hub,
        projects = projects,
        sessionManager = sessionManager,
        gradle = gradle,
        artifacts = artifacts,
        build = build,
        git = git,
        gitWriter = gitWriter,
        uploads = uploads,
        fileBrowser = fileBrowser,
        promptStore = promptStore,
        auditRepo = auditRepo,
        auditLogger = auditLogger,
        conversationRepo = conversationRepo,
        emailNotifier = emailNotifier,
        webhookNotifier = webhookNotifier,
        status = status,
        env = env,
        envSetup = envSetup,
        claudeAuth = claudeAuth,
        claudeLogin = claudeLogin,
        mcp = mcp,
        gitCredentials = gitCredentials,
        gitClone = gitClone,
        actionRegistry = actionRegistry,
        actionHandler = actionHandler,
        capabilityService = capabilityService,
        claudeStatusService = claudeStatusService,
        claudeUsageMonitor = claudeUsageMonitor,
        playPublishService = playPublishService,
        testFlightPublishService = testFlightPublishService,
        apkSignerInspector = apkSignerInspector,
        buildCacheService = buildCacheService,
        projectArchiver = projectArchiver,
        diskMonitor = diskMonitor,
        agentRegistry = agentRegistry,
        conversationExport = conversationExport,
        promptSuggestionService = promptSuggestionService,
        dependencyAudit = dependencyAudit,
        buildScheduleRepo = buildScheduleRepo,
        buildScheduler = buildScheduler,
        buildWebhookSecretRepo = buildWebhookSecretRepo,
        conversationArchiver = conversationArchiver,
        gradleWrapperService = gradleWrapperService,
        codeStatsService = codeStatsService,
        codeSearchService = codeSearchService,
        hasher = passwordHasher,
        subAgentManager = subAgentManager,
        pushSubscriptionRepo = pushSubscriptionRepo,
        webPushNotifier = webPushNotifier,
        webauthnService = webauthnService,
        projectAclRepo = projectAclRepo,
        symbolFinder = symbolFinder,
        metrics = metrics,
        rateLimitApi = rateLimitApi,
        rateLimitAuth = rateLimitAuth,
        backupService = backupService,
        notificationService = notificationService,
        logSearchService = logSearchService,
        apkVerifier = apkVerifier,
        fcmSender = fcmSender,
        kotlinLspService = kotlinLspService,
        keystoreService = keystoreService,
        gitConfig = gitConfig,
        globalClaudeMd = globalClaudeMd,
        plugins = pluginService,
        terminalManager = terminalManager,
        adb = adbService,
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking { sessionManager.shutdown() }
        kotlinx.coroutines.runBlocking { subAgentManager.shutdown() }
        runCatching { claudeUsageMonitor.shutdown() }
        runCatching { diskMonitor.shutdown() }
        runCatching { kotlinLspService.shutdown() }
        runCatching { buildScheduler.shutdown() }
        runCatching { backupScheduler.shutdown() }
        runCatching { notificationRetentionScheduler.shutdown() }
        runCatching { conversationArchiver.shutdown() }
        runCatching { notifiers.shutdown() }
        // v0.76.0 (L2 fix) — FcmSender shutdown hook 등록. 현재는 no-op 본문이지만
        // 향후 connection pool / scope 정리 코드 추가 시 leak 방지.
        runCatching { fcmSender.shutdown() }
        // v1.27.0 — Terminal PTY 들 graceful 종료. 컨테이너 SIGTERM → 활성 bash
        // 프로세스 정리 (SIGTERM → 2s 후 destroyForcibly fallback) + reaper coroutine
        // cancel. 누락 시 docker stop 후에도 zombie bash 가 남아 다음 부팅에서 FD
        // 누수 가능.
        runCatching { terminalManager.shutdownAll() }
        // v1.31.0 (B-BUG1) — 진행 중 claude OAuth 로그인 세션의 script/claude 자식
        // 프로세스 + drainOutput/watchProcess job graceful 종료.
        runCatching { claudeLogin.shutdown() }
        // v1.31.1 (B-Q1) — TaskQueue 내부 scope cancel (진행 중 빌드 job 정리 신호).
        runCatching { queue.shutdown() }
        // v1.34.2 (20차 Q2) — Claude OAuth 토큰 자동 갱신 폴링 코루틴 정리. 다른
        // start 매니저와 비대칭으로 hook 에서 누락돼 있었음(graceful-restart 시 leak).
        runCatching { claudeTokenRefresher.shutdown() }
        // v1.34.2 (20차 BUG-2) — LogHub 레거시 토픽 idle reaper coroutine 정리.
        runCatching { hub.shutdown() }
    })

    printBanner(config, workspaceRoot, pairing, authService.adminExists())

    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        module(ctx)
    }.start(wait = true)
}

/**
 * VIBECODER_ADMIN_USERNAME / VIBECODER_ADMIN_PASSWORD 환경변수가 둘 다 설정되어
 * 있고 DB에 admin이 없으면 자동으로 생성한다. Docker compose 환경에서 수동 셋업
 * 단계 없이 부팅하기 위함.
 *
 * 부트 후엔 `.env` 의 plain text 비밀번호를 변경(`/password`)할 것을 권장.
 */
private fun bootstrapAdminFromEnv(auth: AuthService) {
    val u = System.getenv("VIBECODER_ADMIN_USERNAME")?.trim().orEmpty()
    val p = System.getenv("VIBECODER_ADMIN_PASSWORD")?.trim().orEmpty()
    if (u.isEmpty() || p.isEmpty()) return
    if (auth.adminExists()) {
        log.info { "Admin 부트스트랩 env 감지됐으나 admin이 이미 존재 → 무시" }
        return
    }
    runCatching {
        auth.setup(username = u, password = p, deviceName = "bootstrap-env", channel = "bootstrap")
        log.info { "환경변수로 admin 자동 생성: $u" }
    }.onFailure {
        log.warn(it) { "admin 부트스트랩 실패: ${it.message}" }
    }
}

private fun printBanner(
    config: ServerConfig,
    workspaceRoot: Path,
    pairing: PairingCodeStore,
    adminExists: Boolean,
) {
    val host = if (config.server.host == "0.0.0.0")
        runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("localhost")
    else config.server.host
    val url = "http://$host:${config.server.port}"

    println(">>> Vibe Coder Server started")
    println(">>> URL         : $url")
    println(">>> Workspace   : $workspaceRoot")
    if (!adminExists) {
        println(">>> ⚠ Admin 계정이 없습니다. 브라우저로 $url 접속하여 초기 설정을 진행하세요.")
        // 백워드 호환을 위해 페어링 코드도 표시 (admin 미설정 시에만 의미 있음)
        pairing.peek()?.let { rec ->
            val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
            println(">>> (레거시) Pairing code: ${rec.code}   (expires at ${fmt.format(rec.expiresAt)})")
        }
    }
    log.info { "server listening on $url, workspace=$workspaceRoot, adminExists=$adminExists" }
}
