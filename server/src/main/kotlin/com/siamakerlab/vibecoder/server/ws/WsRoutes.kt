package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.server.auth.TokenService
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
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

fun Routing.wsRoutes(hub: LogHub, deviceRepo: DeviceRepository, tokens: TokenService) {
    webSocket("/ws/projects/{projectId}/tasks/{taskId}/logs") {
        handleLogStream(hub, deviceRepo, tokens, topic = call.parameters["taskId"]!!)
    }
    webSocket("/ws/projects/{projectId}/builds/{buildId}/logs") {
        handleLogStream(hub, deviceRepo, tokens, topic = call.parameters["buildId"]!!)
    }
}

private suspend fun WebSocketServerSession.handleLogStream(
    hub: LogHub, deviceRepo: DeviceRepository, tokens: TokenService, topic: String,
) {
    val authed = try {
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
    if (!authed) return

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

private suspend fun WebSocketServerSession.sendFrame(frame: WsFrame) {
    send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), frame)))
}
