package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ConsoleTuiPromptAcceptedDto
import com.siamakerlab.vibecoder.shared.dto.ConsoleTuiSessionDto
import com.siamakerlab.vibecoder.shared.dto.ConsoleTuiSessionRequestDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

private val consoleTuiRouteLog = KotlinLogging.logger {}
private val consoleTuiWsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
private const val MAX_CONSOLE_TUI_INPUT_CHARS = 64 * 1024

fun Routing.consoleTuiRoutes(
    projects: ProjectService,
    agentRouter: AgentRouter,
    manager: ConsoleTuiSessionManager,
    dispatcher: ConsolePromptDispatcher,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
) {
    suspend fun io.ktor.server.routing.RoutingContext.sendTuiPrompt(
        projectId: String,
        text: String,
        source: String,
        interruptFirst: Boolean = false,
    ): ConsoleTuiPromptAcceptedDto {
        return dispatcher.send(
            ownerUserId = call.requireDevice().device.userId,
            projectId = projectId,
            text = text,
            requestedProvider = call.request.queryParameters["provider"],
            source = source,
            interruptFirst = interruptFirst,
        )
    }

    authenticate(AUTH_BEARER) {
        get(ApiPath.consoleTuiSession("{projectId}")) {
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val provider = selectedProvider(projectId, agentRouter, call.request.queryParameters["provider"])
            val owner = call.requireDevice().device.userId
            val existing = manager.find(owner, projectId, provider)
            if (existing != null) {
                call.respond(existing.toResponse())
            } else {
                manager.publishState(projectId, provider, ProjectState.READY)
                call.respond(ConsoleTuiSessionDto(projectId = projectId, provider = provider.id))
            }
        }

        post(ApiPath.consoleTuiSession("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val body = runCatching { call.receive<ConsoleTuiSessionRequestDto>() }
                .getOrDefault(ConsoleTuiSessionRequestDto())
            val provider = selectedProvider(projectId, agentRouter, body.provider)
            val session = manager.ensure(
                ownerUserId = call.requireDevice().device.userId,
                projectId = projectId,
                provider = provider,
                workdir = projects.sourcePathOrThrow(projectId),
                cols = body.cols.coerceIn(1, ConsoleTuiSession.MAX_TERMINAL_DIMENSION),
                rows = body.rows.coerceIn(1, ConsoleTuiSession.MAX_TERMINAL_DIMENSION),
            )
            call.respond(HttpStatusCode.Accepted, session.toResponse())
        }

        post(ApiPath.consoleTuiPrompt("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val text = validatedPromptText(call.receive<PromptRequestDto>())
            call.respond(HttpStatusCode.Accepted, sendTuiPrompt(projectId, text, "console_tui_prompt"))
        }

        post(ApiPath.consoleTuiInterrupt("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val text = validatedPromptText(call.receive<PromptRequestDto>())
            call.respond(HttpStatusCode.Accepted, sendTuiPrompt(projectId, text, "console_tui_interrupt", interruptFirst = true))
        }

        post(ApiPath.consoleTuiCompact("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            call.respond(HttpStatusCode.Accepted, sendTuiPrompt(projectId, "/compact", "console_tui_compact"))
        }

        delete(ApiPath.consoleTuiSessionClose("{projectId}", "{sessionId}")) {
            call.requireApiWrite()
            val projectId = projectIdParam()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val sessionId = call.parameters["sessionId"].orEmpty()
            val session = manager.get(sessionId)
            if (session != null && session.projectId != projectId) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "project_mismatch"))
                return@delete
            }
            val owner = call.requireDevice().device.userId
            if (session != null && session.ownerUserId != null && session.ownerUserId != owner) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_session_owner"))
                return@delete
            }
            manager.close(sessionId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    webSocket(ApiPath.wsConsoleTui("{projectId}", "{sessionId}")) {
        val projectId = call.parameters["projectId"].orEmpty()
        if (projectId.isBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
            return@webSocket
        }
        val callerUserId = authenticateConsoleTuiWs(deviceRepo, tokens)
            ?: return@webSocket
        if (!projects.canUserAccess(callerUserId, isAdmin = true, projectId = projectId)) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "project_forbidden"))
            return@webSocket
        }
        val sessionId = call.parameters["sessionId"].orEmpty()
        val session = manager.get(sessionId) ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "session not found"))
            return@webSocket
        }
        if (session.projectId != projectId) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "project_mismatch"))
            return@webSocket
        }
        if (session.ownerUserId != null && session.ownerUserId != callerUserId) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not_session_owner"))
            return@webSocket
        }

        session.attach()
        try {
            coroutineScope {
                val replay = session.scrollbackSnapshot()
                if (replay.isNotEmpty()) {
                    runCatching {
                        send(Frame.Text(consoleTuiWsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalOutput(replay))))
                    }
                }
                val outJob = launch {
                    session.output.collect { data ->
                        runCatching {
                            send(Frame.Text(consoleTuiWsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalOutput(data))))
                        }
                    }
                }
                val exitJob = launch {
                    session.exit.collect { code ->
                        runCatching {
                            send(Frame.Text(consoleTuiWsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalExit(code))))
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "exited"))
                    }
                }
                val directInput = StringBuilder()
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val parsed = runCatching {
                            consoleTuiWsJson.decodeFromString(WsFrame.serializer(), frame.readText())
                        }.getOrNull() ?: continue
                        when (parsed) {
                            is WsFrame.TerminalInput -> {
                                if (parsed.data.length > MAX_CONSOLE_TUI_INPUT_CHARS) {
                                    consoleTuiRouteLog.warn { "console TUI input dropped: ${parsed.data.length} chars" }
                                } else {
                                    if (!session.write(parsed.data)) {
                                        close(CloseReason(CloseReason.Codes.NORMAL, "session write failed"))
                                        break
                                    }
                                    if (parsed.data == "\u001b") {
                                        session.markUserInterrupted()
                                    }
                                    val recordPrompt = parsed.recordPrompt
                                        ?.let(::sanitizeDirectPromptLine)
                                        ?.takeIf { it.isNotBlank() }
                                    if (recordPrompt != null) {
                                        runCatching {
                                            dispatcher.recordDirectTerminalPrompt(
                                                projectId = projectId,
                                                provider = session.provider,
                                                sessionId = session.id,
                                                text = recordPrompt,
                                            )
                                        }.onFailure {
                                            consoleTuiRouteLog.warn(it) { "console TUI direct prompt record failed for $projectId/${session.id}" }
                                        }
                                    } else {
                                        captureDirectPromptLines(directInput, parsed.data).forEach { line ->
                                            runCatching {
                                                dispatcher.recordDirectTerminalPrompt(
                                                    projectId = projectId,
                                                    provider = session.provider,
                                                    sessionId = session.id,
                                                    text = line,
                                                )
                                            }.onFailure {
                                                consoleTuiRouteLog.warn(it) { "console TUI direct prompt record failed for $projectId/${session.id}" }
                                            }
                                        }
                                    }
                                }
                            }
                            is WsFrame.TerminalResize -> session.resize(parsed.cols, parsed.rows)
                            else -> Unit
                        }
                    }
                } finally {
                    outJob.cancel()
                    exitJob.cancel()
                }
            }
        } finally {
            session.detach()
        }
    }
}

private fun io.ktor.server.routing.RoutingContext.projectIdParam(): String =
    call.parameters["projectId"]
        ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")

private fun selectedProvider(projectId: String, router: AgentRouter, requested: String?): AgentProvider {
    val parsed = AgentProvider.parse(requested)
    return if (parsed != null && router.availableProviders().contains(parsed)) parsed else router.providerFor(projectId)
}

internal fun captureDirectPromptLines(buffer: StringBuilder, data: String): List<String> {
    if (data.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var i = 0
    while (i < data.length) {
        val ch = data[i]
        when {
            ch == '\u001b' -> {
                i++
                when (data.getOrNull(i)) {
                    '[' -> {
                        i++
                        while (i < data.length && data[i] !in '@'..'~') i++
                    }
                    ']' -> {
                        i++
                        while (i < data.length && data[i] != '\u0007') {
                            if (data[i] == '\u001b' && data.getOrNull(i + 1) == '\\') {
                                i++
                                break
                            }
                            i++
                        }
                    }
                    'O', 'P', '^', '_' -> {
                        i++
                        while (i < data.length && data[i] !in '@'..'~') i++
                    }
                }
            }
            ch == '\r' || ch == '\n' -> {
                val line = sanitizeDirectPromptLine(buffer.toString())
                buffer.clear()
                if (line.isNotBlank()) lines += line
            }
            ch == '\b' || ch == '\u007f' -> {
                if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
            }
            ch == '\u0003' || ch == '\u0015' -> {
                buffer.clear()
            }
            ch >= ' ' -> buffer.append(ch)
        }
        i++
    }
    return lines
}

internal fun sanitizeDirectPromptLine(value: String): String {
    var droppedPrivateOrFormat = 0
    val text = value
        .replace("\u001b[200~", "")
        .replace("\u001b[201~", "")
        .replace("\\x1b[200~", "")
        .replace("\\x1b[201~", "")
        .filter { ch ->
            val type = Character.getType(ch)
            val drop = Character.isISOControl(ch) && ch != '\t' ||
                type in setOf(
                    Character.FORMAT.toInt(),
                    Character.PRIVATE_USE.toInt(),
                    Character.SURROGATE.toInt(),
                    Character.UNASSIGNED.toInt(),
                )
            if (drop && !ch.isWhitespace()) droppedPrivateOrFormat++
            !drop
        }
        .trim()
    if (text.isBlank()) return ""
    if (droppedPrivateOrFormat > text.count { !it.isWhitespace() }) return ""
    if (TERMINAL_ESCAPE_ARTIFACT.matches(text)) return ""
    return text
}

private val TERMINAL_ESCAPE_ARTIFACT = Regex(
    """^(?:\\x1b)?(?:\[[0-9;?<>]*[~A-Za-z]|\][^\u0007]*|O[A-Za-z]|[0-9;?<>]+[~A-Za-z])$""",
)

private fun ConsoleTuiSession.toResponse(): ConsoleTuiSessionDto =
    turnSnapshot().let { turn ->
        ConsoleTuiSessionDto(
            sessionId = id,
            projectId = projectId,
            provider = provider.id,
            workdir = workdir.toString(),
            command = commandDisplay,
            alive = isAlive(),
            createdAt = createdAt.toString(),
            turnState = turn.state.name.lowercase(),
            lastPromptTurnId = turn.lastPromptTurnId,
            lastPromptAt = turn.lastPromptAt,
        )
    }

private fun validatedPromptText(body: PromptRequestDto): String =
    validatedConsoleTuiPromptText(body)

private suspend fun DefaultWebSocketServerSession.authenticateConsoleTuiWs(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
): String? {
    val origin = call.request.headers["Origin"]
    if (!origin.isNullOrBlank()) {
        val host = call.request.headers["Host"]
        val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
        if (host != null && originHost != null && originHost != host.substringBefore(':')) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied"))
            return null
        }
    }
    val device = run {
        val cookieToken = call.request.cookies[SESSION_COOKIE]
        if (!cookieToken.isNullOrBlank()) {
            return@run deviceRepo.findByTokenHash(Sha256.hashString(cookieToken))
        }
        runCatching {
            withTimeout(5_000) {
                val firstRaw = (incoming.receive() as? Frame.Text)?.readText() ?: return@withTimeout null
                val first = runCatching { consoleTuiWsJson.decodeFromString(WsFrame.serializer(), firstRaw) }
                    .getOrNull() as? WsFrame.Auth ?: return@withTimeout null
                deviceRepo.findByTokenHash(tokens.hashOf(first.token))
            }
        }.onFailure { e ->
            when (e) {
                is TimeoutCancellationException -> consoleTuiRouteLog.warn { "ws console TUI auth timeout" }
                else -> consoleTuiRouteLog.debug(e) { "ws console TUI auth aborted: ${e.javaClass.simpleName}" }
            }
        }.getOrNull()
    }
    if (device == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))
        return null
    }
    deviceRepo.touchLastSeen(device.id)
    return device.userId
}
