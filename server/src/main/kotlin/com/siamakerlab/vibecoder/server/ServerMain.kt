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
                com.siamakerlab.vibecoder.server.notify.WebPushNotifier.PushSubscription(it.id, it.endpoint)
            }
        },
        onGoneSubscription = { id -> runCatching { pushSubscriptionRepo.deleteById(id) } },
    )
    val notifiers = com.siamakerlab.vibecoder.server.notify.Notifiers(
        email = emailNotifier, webhook = webhookNotifier, webPush = webPushNotifier,
    )
    // v0.49.0 — Project ACL persistence (member 가 일부 프로젝트만 보기).
    val projectAclRepo = com.siamakerlab.vibecoder.server.repo.ProjectAclRepository(clock)
    val projects = ProjectService(
        workspace, projectRepo, buildRepo, keystoreGen, gitClone,
        artifactRepo = artifactRepo, uploadedFileRepo = uploadedRepo,
        conversationRepo = conversationRepo,
        projectAclRepo = projectAclRepo,
    )
    val sessionManager = ClaudeSessionManager(config, workspace, hub, history = conversationHistory)
    // v0.44.0 — Phase 23 sub-agent process pool (real multi-agent). Independent of the main
    // ClaudeSessionManager so a project can run its primary console plus multiple sub-agents
    // (reviewer / frontend / backend / ...) concurrently in the same workspace.
    val subAgentManager = com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager(
        config = config, workspace = workspace, hub = hub, history = conversationHistory,
    )
    val gradle = GradleBuilder(config)
    val artifacts = ArtifactService(config, workspace, artifactRepo, buildRepo, clock)
    val build = BuildService(config, workspace, projects, buildRepo, queue, gradle, artifacts, clock, notifier = notifiers)
    val git = GitReader()
    val gitWriter = com.siamakerlab.vibecoder.server.git.GitWriter()
    val emulator = com.siamakerlab.vibecoder.server.emulator.EmulatorService()
    val uploads = UploadService(config, workspace, uploadedRepo, clock)
    val fileBrowser = com.siamakerlab.vibecoder.server.files.ProjectFileBrowser(workspace)
    val promptStore = com.siamakerlab.vibecoder.server.prompts.PromptTemplateStore(workspace, clock)
    val env = EnvDiagnostics(config)
    val envSetup = EnvSetupService(config, queue, hub, clock)
    val claudeAuth = ClaudeAuthService(clock)
    val claudeLogin = ClaudeLoginService(clock, claudeAuth)
    val mcp = McpService(clock, queue, hub)
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
    // v0.29.0 — 프로젝트 zip + 디스크 monitor (Notifiers 와 email warn percent 공유).
    val projectArchiver = com.siamakerlab.vibecoder.server.projects.ProjectArchiver(workspace)
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
        emulator = emulator,
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
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking { sessionManager.shutdown() }
        kotlinx.coroutines.runBlocking { subAgentManager.shutdown() }
        runCatching { claudeUsageMonitor.shutdown() }
        runCatching { diskMonitor.shutdown() }
        runCatching { buildScheduler.shutdown() }
        runCatching { conversationArchiver.shutdown() }
        runCatching { notifiers.shutdown() }
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
