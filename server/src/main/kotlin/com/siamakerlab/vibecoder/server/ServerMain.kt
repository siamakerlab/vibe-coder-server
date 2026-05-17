package com.siamakerlab.vibecoder.server

import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.build.GradleBuilder
import com.siamakerlab.vibecoder.server.claude.ClaudeRunner
import com.siamakerlab.vibecoder.server.config.ConfigLoader
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.db.VibeDb
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.TaskRepository
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

private val log = KotlinLogging.logger {}

fun main() {
    val config = ConfigLoader.load()
    val workspaceRoot = Path.of(config.workspace.root).toAbsolutePath().normalize()
    val workspace = WorkspacePath(workspaceRoot)

    // DB
    val dbFile = Path.of("vibe-coder-server-data/data/vibecoder.db")
    VibeDb.init(dbFile)

    val clock = SystemClock()
    val deviceRepo = DeviceRepository(clock)
    val projectRepo = ProjectRepository(clock)
    val taskRepo = TaskRepository(clock)
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
    val projects = ProjectService(config, workspace, projectRepo, buildRepo)
    val claudeRunner = ClaudeRunner(config, workspace)
    val gradle = GradleBuilder(config)
    val artifacts = ArtifactService(workspace, artifactRepo, clock)
    val build = BuildService(config, workspace, projects, buildRepo, queue, gradle, artifacts, clock)
    val git = GitReader()
    val uploads = UploadService(config, workspace, uploadedRepo, clock)
    val env = EnvDiagnostics(config)
    val status = StatusService(config, projectRepo, taskRepo, env)

    val ctx = ServerContext(
        config, workspace, deviceRepo, projectRepo, taskRepo, buildRepo, artifactRepo,
        uploadedRepo, clock, tokens, pairing, queue, hub, projects, claudeRunner, gradle,
        artifacts, build, git, uploads, status, env,
    )

    printBanner(config, pairing)

    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        module(ctx)
    }.start(wait = true)
}

private fun printBanner(config: com.siamakerlab.vibecoder.server.config.ServerConfig, pairing: PairingCodeStore) {
    val rec = pairing.peek() ?: return
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    val host = if (config.server.host == "0.0.0.0")
        runCatching { InetAddress.getLocalHost().hostAddress }.getOrDefault("localhost")
    else config.server.host
    val url = "http://$host:${config.server.port}"
    println(">>> Vibe Coder Server started")
    println(">>> Pairing code: ${rec.code}   (expires at ${fmt.format(rec.expiresAt)})")
    println(">>> Server URL  : $url")
    log.info { "server listening on $url, pairing code rotated (expires ${rec.expiresAt})" }
}
