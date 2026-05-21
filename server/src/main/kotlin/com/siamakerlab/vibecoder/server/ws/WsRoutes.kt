package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * WebSocket framing for log streaming.
 *
 * We bypass Ktor's auto-converter and use explicit [WsFrame.serializer] because
 * kotlinx.serialization's plugin-generated serializer for a sealed class isn't
 * always discoverable via `serializer(typeOf<WsFrame>())` at runtime. Going
 * through `Frame.Text` + explicit serializer is unambiguous.
 */
private val wsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

fun Routing.wsRoutes(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    sessionManager: ClaudeSessionManager,
    actionRegistry: ProjectActionRegistry,
    actionHandler: ServerActionHandler,
) {
    webSocket("/ws/projects/{projectId}/builds/{buildId}/logs") {
        handleLegacyLogStream(hub, deviceRepo, tokens, topic = call.parameters["buildId"]!!)
    }
    webSocket("/ws/projects/{projectId}/console/logs") {
        val projectId = call.parameters["projectId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        handleConsoleStream(
            hub, deviceRepo, tokens, sessionManager,
            actionRegistry, actionHandler, projectId, since,
        )
    }
}

private suspend fun WebSocketServerSession.authenticateFirstFrame(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
): Boolean = try {
    withTimeout(5_000) {
        val firstRaw = (incoming.receive() as? Frame.Text)?.readText()
        if (firstRaw == null) {
            sendFrame(WsFrame.Error("auth_required", "first frame must be Auth (text)"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_required"))
            return@withTimeout false
        }
        val first = runCatching { wsJson.decodeFromString(WsFrame.serializer(), firstRaw) }
            .getOrNull()
        if (first !is WsFrame.Auth) {
            sendFrame(WsFrame.Error("auth_required", "first frame must be Auth"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_required"))
            return@withTimeout false
        }
        val device = deviceRepo.findByTokenHash(tokens.hashOf(first.token))
        if (device == null) {
            sendFrame(WsFrame.Error("invalid_token", "bearer token not recognized"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
            return@withTimeout false
        }
        deviceRepo.touchLastSeen(device.id)
        true
    }
} catch (_: TimeoutCancellationException) {
    runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_timeout")) }
    false
} catch (e: Throwable) {
    log.debug { "ws auth failed: ${e.message}" }
    runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "auth_failed")) }
    false
}

private suspend fun WebSocketServerSession.handleLegacyLogStream(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    topic: String,
) {
    if (!authenticateFirstFrame(deviceRepo, tokens)) return

    // Forward broadcast frames until Done arrives, then send Done and stop.
    hub.subscribe(topic)
        .takeWhile { frame ->
            if (frame is WsFrame.Done) {
                runCatching { sendFrame(frame) }
                false
            } else true
        }
        .collectLatest { frame ->
            runCatching { sendFrame(frame) }
                .onFailure { log.debug { "ws send failed: ${it.message}" } }
        }
}

private suspend fun WebSocketServerSession.handleConsoleStream(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    sessionManager: ClaudeSessionManager,
    actionRegistry: ProjectActionRegistry,
    actionHandler: ServerActionHandler,
    projectId: String,
    since: Long,
) {
    if (!authenticateFirstFrame(deviceRepo, tokens)) return

    val topic = LogHub.consoleTopic(projectId)
    val view = hub.subscribeConsole(topic, since)

    // Replay slice (if any). When since=0 (first connection ever) we still replay whatever's in
    // the ring so a reconnecting client gets the most recent context. If since>0 AND the ring's
    // floor moved past it, surface a partial-replay notice.
    if (since > 0L && view.replay.isNotEmpty() && view.ringFloor > since + 1L) {
        runCatching {
            sendFrame(WsFrame.ConsoleSystem(
                code = "replay_partial",
                message = "Some history was evicted from the in-memory buffer. seq ${since + 1}..${view.ringFloor - 1} permanently lost.",
                seq = 0L,
            ))
        }
    }
    if (view.replay.isNotEmpty()) {
        val from = view.replay.first().seq
        val to = view.replay.last().seq
        runCatching { sendFrame(WsFrame.ConsoleReplayBegin(fromSeq = from, toSeq = to)) }
        for (sf in view.replay) {
            runCatching { sendFrame(sf.frame) }
        }
        runCatching { sendFrame(WsFrame.ConsoleReplayEnd) }
    }

    // Concurrent task: read client → server frames (user_prompt / action_invoke).
    // This task is scoped to the session and cancels when collect() returns.
    val incomingJob = launch {
        runCatching {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                val parsed = runCatching { wsJson.decodeFromString(WsFrame.serializer(), raw) }
                    .getOrNull() ?: continue
                when (parsed) {
                    is WsFrame.UserPrompt -> {
                        runCatching { sessionManager.sendPrompt(projectId, parsed.text) }
                            .onFailure { log.warn(it) { "[$projectId] ws prompt failed" } }
                    }
                    is WsFrame.ActionInvoke -> {
                        val action = actionRegistry.findAction(projectId, parsed.actionId)
                        if (action == null) {
                            log.warn { "[$projectId] action_invoke unknown id: ${parsed.actionId}" }
                        } else {
                            runCatching { actionHandler.dispatch(projectId, action, parsed.params) }
                                .onFailure { log.warn(it) { "[$projectId] action_invoke dispatch failed: ${parsed.actionId}" } }
                        }
                    }
                    is WsFrame.Ping -> { /* keep-alive */ }
                    else -> log.debug { "[$projectId] unhandled client frame: ${parsed::class.simpleName}" }
                }
            }
        }
    }

    try {
        view.live.collect { sf ->
            // Skip frames already covered by replay (seq <= since OR already in replay slice).
            if (sf.seq <= since) return@collect
            if (view.replay.any { it.seq == sf.seq }) return@collect
            runCatching { sendFrame(sf.frame) }
                .onFailure { log.debug { "ws send failed: ${it.message}" } }
        }
    } finally {
        incomingJob.cancel()
    }
}

private suspend fun WebSocketServerSession.sendFrame(frame: WsFrame) {
    send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), frame)))
}
