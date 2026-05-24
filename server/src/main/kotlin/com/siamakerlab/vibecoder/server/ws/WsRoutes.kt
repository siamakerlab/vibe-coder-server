package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager
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
    subAgentManager: SubAgentSessionManager,
) {
    webSocket("/ws/projects/{projectId}/builds/{buildId}/logs") {
        handleLegacyLogStream(hub, deviceRepo, tokens, topic = call.parameters["buildId"]!!)
    }
    webSocket("/ws/env-setup/{taskId}/logs") {
        // 빌드환경 설치 작업 (vibe-doctor) 의 stdout 라인 + 종료 Done 을 흘려보낸다.
        // 빌드 로그와 동일한 legacy log stream 패턴이므로 그대로 재사용.
        handleLegacyLogStream(hub, deviceRepo, tokens, topic = call.parameters["taskId"]!!)
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
    // v0.44.0 — sub-agent 콘솔 (Phase 23). prompt 송신 채널이 main console 과 분리되어 있어
    // WS 양방향이 아니라 단방향 stream 만 처리하면 됨 (prompt 는 별도 REST 로 보냄).
    webSocket("/ws/projects/{projectId}/agents/{agentName}/console/logs") {
        val projectId = call.parameters["projectId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
        val agentName = call.parameters["agentName"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing agentName"))
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(agentName)) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad agentName"))
        }
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        handleSubAgentConsoleStream(hub, deviceRepo, tokens, projectId, agentName, since)
    }
}

private suspend fun WebSocketServerSession.authenticateFirstFrame(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
): Boolean {
    // ── v0.12.4: CSWSH 방어 — Origin 헤더 검증 ─────────────────────────────────
    // WebSocket 은 CORS preflight 미적용. cookie 가 첨부되는 cross-origin WS 가
    // 시도되면 SameSite=Lax 가 막아주긴 하지만, defense-in-depth 차원에서 Origin
    // ↔ Host 일치를 확인. Android 앱(쿠키 없음)·도구(curl/postman)는 Origin 가
    // 비어 있어 통과 — 인증은 어차피 토큰으로.
    val origin = call.request.headers["Origin"]
    if (!origin.isNullOrBlank()) {
        val host = call.request.headers["Host"]
        val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
        if (host != null && originHost != null) {
            // host 는 보통 "vibe.local:17880" 형태. originHost 는 host 만.
            val hostName = host.substringBefore(':')
            if (originHost != hostName) {
                io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn {
                    "ws origin mismatch: origin=$origin host=$host — closing"
                }
                runCatching {
                    sendFrame(WsFrame.Error("origin_denied", "WebSocket from unexpected origin"))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied"))
                }
                return false
            }
        }
    }

    // Path 1 — WebSocket handshake 의 cookie 헤더에서 vibe_session 시도.
    //
    // 웹 클라이언트는 SESSION_COOKIE 를 httpOnly 로 받기 때문에 JavaScript 에서
    // document.cookie 로 읽을 수 없다 (의도된 XSS 방어). 따라서 첫 Auth 프레임으로
    // 토큰을 실어 보내는 건 브라우저에선 동작하지 않는다.
    //
    // 그러나 동일 origin WebSocket handshake 시 브라우저는 자동으로 쿠키를 첨부하므로,
    // 서버가 그걸 직접 읽어 인증하면 브라우저는 토큰을 알 필요가 없다.
    //
    // 안드로이드 앱은 쿠키가 없어 이 경로를 그냥 통과 → 기존 첫 Auth 프레임 인증으로 fallback.
    val cookieToken = call.request.cookies[SESSION_COOKIE]
    if (!cookieToken.isNullOrBlank()) {
        val device = deviceRepo.findByTokenHash(tokens.hashOf(cookieToken))
        if (device != null) {
            deviceRepo.touchLastSeen(device.id)
            return true
        }
        // 쿠키는 보내왔지만 hash 가 안 맞음 → 즉시 invalid_token 으로 끊음.
        runCatching {
            sendFrame(WsFrame.Error("invalid_token", "session cookie not recognized"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
        }
        return false
    }

    // Path 2 — 안드로이드 앱 등 쿠키가 없는 클라이언트: 첫 텍스트 프레임이 WsFrame.Auth.
    return try {
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

/**
 * v0.44.0 — sub-agent console stream. Same replay + live merge protocol as the main project console
 * but the prompt-send path is REST (POST /api/projects/{id}/agents/{agent}/console/prompt), so the
 * incoming-frame handling is a no-op (we still drain `incoming` to keep the WebSocket healthy).
 */
private suspend fun WebSocketServerSession.handleSubAgentConsoleStream(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    projectId: String,
    agentName: String,
    since: Long,
) {
    if (!authenticateFirstFrame(deviceRepo, tokens)) return

    val topic = LogHub.subAgentConsoleTopic(projectId, agentName)
    val view = hub.subscribeConsole(topic, since)

    if (since > 0L && view.replay.isNotEmpty() && view.ringFloor > since + 1L) {
        runCatching {
            sendFrame(WsFrame.ConsoleSystem(
                code = "replay_partial",
                message = "Some history was evicted. seq ${since + 1}..${view.ringFloor - 1} permanently lost.",
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

    val drainJob = launch {
        runCatching {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                // sub-agent 측은 client-to-server frame 처리 안 함 (Ping 만 무시). 단 client 가
                // 메인 콘솔과 같은 UserPrompt 프레임을 보내도 거절하지 않고 그냥 흘려보낸다.
            }
        }
    }

    try {
        view.live.collect { sf ->
            if (sf.seq <= since) return@collect
            if (view.replay.any { it.seq == sf.seq }) return@collect
            runCatching { sendFrame(sf.frame) }
                .onFailure { log.debug { "ws send failed: ${it.message}" } }
        }
    } finally {
        drainJob.cancel()
    }
}

private suspend fun WebSocketServerSession.sendFrame(frame: WsFrame) {
    send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), frame)))
}
