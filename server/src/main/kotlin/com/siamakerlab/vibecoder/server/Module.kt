package com.siamakerlab.vibecoder.server

import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.actions.projectActionRoutes
import com.siamakerlab.vibecoder.server.artifacts.ArtifactService
import com.siamakerlab.vibecoder.server.artifacts.artifactRoutes
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.auth.authRoutes
import com.siamakerlab.vibecoder.server.auth.installAuth
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.build.GradleBuilder
import com.siamakerlab.vibecoder.server.build.buildRoutes
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.claude.consoleRoutes
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.env.envRoutes
import com.siamakerlab.vibecoder.server.error.installStatusPages
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.files.fileRoutes
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
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.server.ws.wsRoutes
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json

data class ServerContext(
    val config: ServerConfig,
    val workspace: WorkspacePath,
    val deviceRepo: DeviceRepository,
    val projectRepo: ProjectRepository,
    val buildRepo: BuildRepository,
    val artifactRepo: ArtifactRepository,
    val uploadedFileRepo: UploadedFileRepository,
    val clock: Clock,
    val tokens: TokenService,
    val pairing: PairingCodeStore,
    val queue: TaskQueue,
    val hub: LogHub,
    val projects: ProjectService,
    val sessionManager: ClaudeSessionManager,
    val gradle: GradleBuilder,
    val artifacts: ArtifactService,
    val build: BuildService,
    val git: GitReader,
    val uploads: UploadService,
    val status: StatusService,
    val env: EnvDiagnostics,
    val actionRegistry: ProjectActionRegistry,
    val actionHandler: ServerActionHandler,
    val claudeStatusService: ClaudeStatusService,
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
        anyHost()
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
    installAuth(ctx.deviceRepo, ctx.tokens)

    routing {
        authRoutes(ctx.config.server.name, ctx.pairing, ctx.tokens, ctx.deviceRepo)
        envRoutes(ctx.status, ctx.env)
        projectRoutes(ctx.projects)
        consoleRoutes(ctx.projects, ctx.sessionManager, ctx.hub, ctx.claudeStatusService)
        projectActionRoutes(ctx.projects, ctx.actionRegistry, ctx.actionHandler)
        buildRoutes(ctx.build, ctx.hub)
        artifactRoutes(ctx.artifactRepo, ctx.workspace, ctx.artifacts)
        gitRoutes(ctx.projects, ctx.git)
        fileRoutes(ctx.uploads)
        wsRoutes(ctx.hub, ctx.deviceRepo, ctx.tokens, ctx.sessionManager,
            ctx.actionRegistry, ctx.actionHandler)
    }
}
