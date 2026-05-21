package com.siamakerlab.vibecoder.server

import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
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
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.projects.KeystoreGenerator
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
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

    val dbFile = workspaceRoot.resolve(".vibecoder").resolve("vibecoder.db")
    VibeDb.init(dbFile)

    val clock = SystemClock()
    val deviceRepo = DeviceRepository(clock)
    val projectRepo = ProjectRepository(clock)
    val buildRepo = BuildRepository(clock)
    val artifactRepo = ArtifactRepository(clock)
    val uploadedRepo = UploadedFileRepository(clock)

    val tokens = TokenService()
    val pairing = PairingCodeStore(
        clock = clock,
        ttl = Duration.ofMinutes(config.security.pairingCodeExpireMinutes.toLong()),
    )
    pairing.rotate()

    val queue = TaskQueue()
    val hub = LogHub()
    val keystoreGen = KeystoreGenerator(workspace)
    val projects = ProjectService(workspace, projectRepo, buildRepo, keystoreGen)
    val sessionManager = ClaudeSessionManager(config, workspace, hub)
    val gradle = GradleBuilder(config)
    val artifacts = ArtifactService(workspace, artifactRepo, clock)
    val build = BuildService(config, workspace, projects, buildRepo, queue, gradle, artifacts, clock)
    val git = GitReader()
    val uploads = UploadService(config, workspace, uploadedRepo, clock)
    val env = EnvDiagnostics(config)
    val status = StatusService(config, projectRepo, buildRepo, env)
    val actionRegistry = ProjectActionRegistry(workspace)
    val actionHandler = ServerActionHandler(projects, build, git, hub, sessionManager)
    val claudeStatusService = ClaudeStatusService(config, workspace, sessionManager)

    val ctx = ServerContext(
        config, workspace, deviceRepo, projectRepo, buildRepo, artifactRepo,
        uploadedRepo, clock, tokens, pairing, queue, hub, projects,
        sessionManager, gradle,
        artifacts, build, git, uploads, status, env,
        actionRegistry, actionHandler, claudeStatusService,
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking { sessionManager.shutdown() }
    })

    printBanner(config, workspaceRoot, pairing)

    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        module(ctx)
    }.start(wait = true)
}

private fun printBanner(config: ServerConfig, workspaceRoot: Path, pairing: PairingCodeStore) {
    val rec = pairing.peek() ?: return
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val host = if (config.server.host == "0.0.0.0")
        runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("localhost")
    else config.server.host
    val url = "http://$host:${config.server.port}"
    println(">>> Vibe Coder Server started")
    println(">>> Pairing code: ${rec.code}   (expires at ${fmt.format(rec.expiresAt)})")
    println(">>> Server URL  : $url")
    println(">>> Workspace   : $workspaceRoot")
    log.info {
        "server listening on $url, workspace=$workspaceRoot, pairing rotated (expires ${rec.expiresAt})"
    }
}
