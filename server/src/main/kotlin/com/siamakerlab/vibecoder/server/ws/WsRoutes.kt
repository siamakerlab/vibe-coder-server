package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.server.actions.ProjectActionRegistry
import com.siamakerlab.vibecoder.server.actions.ServerActionHandler
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager
import com.siamakerlab.vibecoder.server.ios.IosSimulatorLogStreamService
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptDispatcher
import com.siamakerlab.vibecoder.server.terminal.AgentStatusStore
import com.siamakerlab.vibecoder.server.terminal.validatedConsoleTuiPromptText
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
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
import java.time.Instant

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
    /** v0.45.0 — role lookup for WebSocket mutation guards (UserPrompt / ActionInvoke). */
    userRepo: AdminUserRepository,
    /** v0.51.0 — Project ACL check on console + sub-agent WebSocket handshake. */
    projects: ProjectService,
    agentRouter: AgentRouter? = null,
    consolePromptDispatcher: ConsolePromptDispatcher? = null,
    agentStatusStore: AgentStatusStore? = null,
    iosSimulatorLogStream: IosSimulatorLogStreamService = IosSimulatorLogStreamService(),
) {
    // v1.31.1 (A-Q2) — WS path 를 ApiPath SSOT 상수로 (Android client wire drift 방어).
    webSocket(ApiPath.wsBuildLogs("{projectId}", "{buildId}")) {
        // v1.31.0 (A-B2) — projectId scope ACL 검증 (console WS 와 대칭).
        handleLegacyLogStream(
            hub, deviceRepo, tokens, topic = call.parameters["buildId"]!!,
            aclProjectId = call.parameters["projectId"], userRepo = userRepo, projects = projects,
        )
    }
    webSocket(ApiPath.wsEnvSetupLogs("{taskId}")) {
        // 빌드환경 설치 작업 (vibe-doctor) 의 stdout 라인 + 종료 Done 을 흘려보낸다.
        // 빌드 로그와 동일한 legacy log stream 패턴이므로 그대로 재사용.
        // v1.31.0 — env-setup 은 글로벌 빌드환경 작업(특정 프로젝트 아님)이라 project
        // ACL 미적용. 인증(authenticateFirstFrame)은 그대로.
        handleLegacyLogStream(hub, deviceRepo, tokens, topic = call.parameters["taskId"]!!)
    }
    webSocket(ApiPath.wsConsoleLogs("{projectId}")) {
        val projectId = call.parameters["projectId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val provider = AgentProvider.parse(call.request.queryParameters["provider"]) ?: AgentProvider.CLAUDE
        handleConsoleStream(
            hub, deviceRepo, tokens, sessionManager,
            actionRegistry, actionHandler, projectId, since, provider, userRepo, projects, agentRouter,
            consolePromptDispatcher, agentStatusStore,
        )
    }
    webSocket(ApiPath.wsIosSimulatorLogs("{projectId}", "{udid}")) {
        val projectId = call.parameters["projectId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
        val udid = call.parameters["udid"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing udid"))
        handleIosSimulatorLogStream(
            iosSimulatorLogStream, deviceRepo, tokens, projects, projectId, udid,
        )
    }
    // v1.3.0 — cross-project busy state push (workspaces 목록 / 대시보드 실시간 동기).
    // 단방향 — 클라이언트는 auth frame 한 번 보낸 뒤 `ProjectBusyChanged` 만 받음.
    // since query param 으로 ring 의 missing slice replay 가능 (재연결 후 누락 방지).
    webSocket(ApiPath.WS_PROJECTS_STATE) {
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        handleProjectsStateStream(hub, deviceRepo, tokens, since)
    }

    // v0.44.0 — sub-agent 콘솔 (Phase 23). prompt 송신 채널이 main console 과 분리되어 있어
    // WS 양방향이 아니라 단방향 stream 만 처리하면 됨 (prompt 는 별도 REST 로 보냄).
    webSocket(ApiPath.wsAgentConsoleLogs("{projectId}", "{agentName}")) {
        val projectId = call.parameters["projectId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing projectId"))
        val agentName = call.parameters["agentName"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing agentName"))
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(agentName)) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad agentName"))
        }
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        handleSubAgentConsoleStream(hub, deviceRepo, tokens, projectId, agentName, since, userRepo, projects)
    }
}

private suspend fun WebSocketServerSession.handleIosSimulatorLogStream(
    service: IosSimulatorLogStreamService,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    projects: ProjectService,
    projectId: String,
    udid: String,
) {
    authenticateFirstFrame(deviceRepo, tokens) ?: return

    val row = runCatching { projects.rowOrThrow(projectId) }.getOrElse {
        runCatching {
            sendFrame(WsFrame.Error("project_not_found", "project not found"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "project_not_found"))
        }
        return
    }
    if (ProjectTypes.normalize(row.projectType) != ProjectTypes.IPHONE) {
        runCatching {
            sendFrame(WsFrame.Error("iphone_project_required", "iPhone project required"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "iphone_project_required"))
        }
        return
    }

    val taskId = "ios-sim-log:$projectId:$udid"
    val result = runCatching {
        service.stream(row.packageName, udid) { level, line ->
            runCatching {
                sendFrame(WsFrame.Log(
                    taskId = taskId,
                    level = level,
                    message = line.take(MAX_IOS_SIMULATOR_LOG_LINE_LENGTH),
                    ts = Instant.now().toString(),
                ))
            }.onFailure { log.debug { "ios simulator log ws send failed: ${it.message}" } }
        }
    }.getOrElse { e ->
        val code = if (e is IllegalArgumentException) "invalid_request" else "simulator_log_stream_failed"
        runCatching { sendFrame(WsFrame.Error(code, e.message ?: code)) }
        IosSimulatorLogStreamService.StreamResult(exitCode = 1, blockedReason = code)
    }
    result.blockedReason?.let { reason ->
        runCatching { sendFrame(WsFrame.Error(reason, reason)) }
    }
    runCatching {
        sendFrame(WsFrame.Done(
            taskId = taskId,
            status = if (result.blockedReason == null && result.exitCode == 0) "SUCCESS" else "FAILED",
            errorMessage = result.blockedReason,
        ))
    }
    runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "done")) }
}

private suspend fun WebSocketServerSession.authenticateFirstFrame(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
): String? {
    // ── v0.12.4: CSWSH 방어 — Origin 헤더 검증 ─────────────────────────────────
    // WebSocket 은 CORS preflight 미적용. cookie 가 첨부되는 cross-origin WS 가
    // 시도되면 SameSite=Lax 가 막아주긴 하지만, defense-in-depth 차원에서 Origin
    // ↔ Host 일치를 확인. Android 앱(쿠키 없음)·도구(curl/postman)는 Origin 가
    // 비어 있어 통과 — 인증은 어차피 토큰으로.
    val origin = call.request.headers["Origin"]
    if (!origin.isNullOrBlank()) {
        // 21차 점검(minor) — fail-closed. 이전엔 `host != null && originHost != null`
        // 가드 안에서만 mismatch 를 검사해, Origin 은 있으나 Host 가 없거나 URI 파싱이
        // 실패하면 검사 자체를 건너뛰고 통과했다(silent no-op). 실 브라우저는 Origin 과
        // Host 를 항상 함께 보내므로, Origin 존재 + (Host null | 파싱 실패 | mismatch) 는
        // 비정상/공격 핸드셰이크로 보고 거절.
        val host = call.request.headers["Host"]
        val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
        val hostName = host?.substringBefore(':')
        if (originHost == null || hostName == null || originHost != hostName) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}.warn {
                "ws origin denied: origin=$origin host=$host — closing"
            }
            runCatching {
                sendFrame(WsFrame.Error("origin_denied", "WebSocket from unexpected origin"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied"))
            }
            return null
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
            return device.userId
        }
        // 쿠키는 보내왔지만 hash 가 안 맞음 → 즉시 invalid_token 으로 끊음.
        runCatching {
            sendFrame(WsFrame.Error("invalid_token", "session cookie not recognized"))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
        }
        return null
    }

    // Path 2 — 안드로이드 앱 등 쿠키가 없는 클라이언트: 첫 텍스트 프레임이 WsFrame.Auth.
    return try {
        withTimeout(5_000) {
            val firstRaw = (incoming.receive() as? Frame.Text)?.readText()
            if (firstRaw == null) {
                sendFrame(WsFrame.Error("auth_required", "first frame must be Auth (text)"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_required"))
                return@withTimeout null
            }
            val first = runCatching { wsJson.decodeFromString(WsFrame.serializer(), firstRaw) }
                .getOrNull()
            if (first !is WsFrame.Auth) {
                sendFrame(WsFrame.Error("auth_required", "first frame must be Auth"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_required"))
                return@withTimeout null
            }
            val device = deviceRepo.findByTokenHash(tokens.hashOf(first.token))
            if (device == null) {
                sendFrame(WsFrame.Error("invalid_token", "bearer token not recognized"))
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
                return@withTimeout null
            }
            deviceRepo.touchLastSeen(device.id)
            device.userId
        }
    } catch (_: TimeoutCancellationException) {
        runCatching { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth_timeout")) }
        null
    } catch (e: Throwable) {
        log.debug { "ws auth failed: ${e.message}" }
        runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "auth_failed")) }
        null
    }
}

private const val MAX_IOS_SIMULATOR_LOG_LINE_LENGTH = 8_000

private suspend fun WebSocketServerSession.handleLegacyLogStream(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    topic: String,
    // v1.31.0 (A-B2) — build logs WS 의 Project ACL. console WS 와 동일하게 caller 가
    // 해당 프로젝트 ACL 을 가졌는지 검증. null 이면 (env-setup 같은 글로벌 작업) skip.
    aclProjectId: String? = null,
    userRepo: AdminUserRepository? = null,
    projects: ProjectService? = null,
) {
    authenticateFirstFrame(deviceRepo, tokens) ?: return

    // v1.45.0 — 단일 admin 화: Project ACL 검사 제거(인증만으로 충분, 모든 프로젝트 접근).

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
    provider: AgentProvider,
    userRepo: AdminUserRepository,
    projects: ProjectService,
    agentRouter: AgentRouter?,
    consolePromptDispatcher: ConsolePromptDispatcher?,
    agentStatusStore: AgentStatusStore?,
) {
    val ownerUserId = authenticateFirstFrame(deviceRepo, tokens) ?: return

    // v1.45.0 — 단일 admin 화: role 조회 / viewer 게이트 / Project ACL 검사 제거.
    // 인증(authenticateFirstFrame)만으로 충분 — 모든 프로젝트 접근 + write 허용.

    val topic = LogHub.consoleTopic(projectId, provider.id)
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

    // v1.122.1 — replay 직후 서버 in-memory busy 스냅샷을 1회 무조건 push.
    // ConsoleBusyState 는 setBusy() 의 "상태 전이" 시점에만 emit 돼 ring 에 적재되는데,
    // 도구 호출이 많은 긴 turn 이면 busy=true 를 켰던 프레임이 ring sliding window 밖으로
    // 밀려난다. 그러면 새로고침/탭전환/재연결 클라이언트의 replay slice 엔 busy=true 가 없고
    // (assistant/tool 프레임은 inFlight 를 켜지 않음), 콘솔 칩/통합 탭 헤더 칩이 'ready'(유휴)
    // 로 고착됐다 — 정작 응답 중인데. 프로젝트 목록은 projectStatus(busyStateOrNull) 로 같은
    // SSOT 를 직접 읽어 정확했던 것과의 불일치. busyStateOrNull 을 권위로 1회 내려보내 ring
    // window 와 무관하게 정확한 turn 상태로 수렴시킨다. null(부팅 직후 turn 이력 없음)이면
    // 클라이언트 기본값 ready 유지. seq=0L — 클라이언트는 busy_state 처리에 seq 를 쓰지 않는다.
    val busyState = agentStatusStore?.get(projectId, provider)?.legacyProjectState
        ?: if (provider == AgentProvider.CLAUDE) {
            sessionManager.busyStateOrNull(projectId)
        } else {
            agentRouter?.managerFor(provider)?.takeIf { it.isBusy(projectId) }?.let { com.siamakerlab.vibecoder.shared.dto.ProjectState.RESPONDING }
        }
    busyState?.let { st ->
        runCatching { sendFrame(WsFrame.ConsoleBusyState(busy = st.busy, seq = 0L, state = st.wire)) }
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
                        runCatching {
                            val text = validatedConsoleTuiPromptText(PromptRequestDto(parsed.text))
                            val dispatcher = consolePromptDispatcher
                                ?: error("console TUI dispatcher unavailable")
                            dispatcher.send(
                                ownerUserId = ownerUserId,
                                projectId = projectId,
                                text = text,
                                requestedProvider = provider.id,
                                source = "ws_console_prompt",
                            )
                        }
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
    userRepo: AdminUserRepository,
    projects: ProjectService,
) {
    authenticateFirstFrame(deviceRepo, tokens) ?: return

    // v1.45.0 — 단일 admin 화: Project ACL 검사 제거(인증만으로 충분).

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

/**
 * v1.3.0 — Cross-project busy state push.
 *
 * 단방향 stream. 클라이언트는 첫 frame 으로 `WsFrame.Auth(token)` 만 보내면 됨.
 * 이후 서버는 `ConsoleHub.subscribeConsole(PROJECTS_TOPIC, since)` 로 ring 의
 * 누락 slice + live flow 를 합쳐 흘려보낸다. since=0 (첫 접속) 이면 ring 이 비어
 * 있을 수도 — 클라이언트는 REST `/api/projects` 로 한 번 초기 snapshot 받고 본
 * stream 으로 patch.
 */
private suspend fun WebSocketServerSession.handleProjectsStateStream(
    hub: LogHub,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    since: Long,
) {
    authenticateFirstFrame(deviceRepo, tokens) ?: return

    val view = hub.subscribeConsole(
        com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager.PROJECTS_TOPIC,
        since,
    )

    // Replay slice — ring 에 남아있는 가장 최근 history. 첫 접속이면 비어있을 수 있음.
    for (sf in view.replay) {
        if (sf.seq <= since) continue
        runCatching { sendFrame(sf.frame) }
            .onFailure { log.debug { "ws projects replay send failed: ${it.message}" } }
    }

    view.live.collect { sf ->
        if (sf.seq <= since) return@collect
        if (view.replay.any { it.seq == sf.seq }) return@collect
        runCatching { sendFrame(sf.frame) }
            .onFailure { log.debug { "ws projects live send failed: ${it.message}" } }
    }
}
