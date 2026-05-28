package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.DevicePrincipal
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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

private val log = KotlinLogging.logger {}
private val wsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

// v1.27.1 Q-6 — WS terminal_input frame 의 data char 상한. 64K = paste 등 정상
// 시나리오 여유 + 악성 / 실수성 spike 방어.
private const val MAX_INPUT_CHARS = 64 * 1024

/**
 * v1.6.0 — Workspace terminal routes.
 *
 * REST:
 *   POST   /api/terminal/sessions     — 신규 bash PTY spawn → { sessionId, workdir }
 *   GET    /api/terminal/sessions     — 활성 session 목록
 *   DELETE /api/terminal/sessions/{id} — 강제 종료
 *
 * SSR:
 *   GET /terminal                     — xterm.js + WS 연결 페이지 (v1.27.0 — 글로벌
 *                                       사이드바 메뉴. workspace 폴더 cwd).
 *   GET /settings/terminal            — 301 redirect → /terminal (legacy alias).
 *
 * WS:
 *   /ws/terminal/{sessionId}          — 양방향 (TerminalInput/Output/Resize/Exit)
 *
 * 모든 라우트는 `security.allowTerminal=true` 필수. 미설정 시 404.
 */
/**
 * v1.26.3 — 8차 정밀 점검 Critical 5건 일괄 회수:
 *  - C1 (REST 무인증) : REST 3 routes 가 `authenticate(AUTH_BEARER)` 블록 + admin role 가드.
 *  - C2 (WS Origin / admin) : WS 핸드셰이크에 Origin ↔ Host 검증 + admin role 검증.
 *  - C3 (브라우저 인증 깨짐) : cookie-first 패턴 — httpOnly cookie 가 handshake 시 자동 첨부.
 *  - C4 (lifecycle) : manager 가 [Module] 의 ServerContext 에서 hoist, `ApplicationStopping` 후크 등록.
 *  - C5 (idle + per-user 한도) : `TerminalSessionManager` 자체 구현 (별도 파일).
 *  + B3 (handshake timeout) : 5s `withTimeout` 도입.
 */
fun Routing.terminalRoutes(
    authDeps: AdminRoutesDeps,
    manager: TerminalSessionManager,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    userRepo: AdminUserRepository,
) {
    // v1.27.1 — B-1 회수: allowTerminal=false 일 때도 SSR 만은 등록 + "비활성화됨"
    // 페이지 노출. 사이드바의 "터미널" 메뉴는 무조건 렌더되므로 클릭 시 404 가
    // 아니라 "이 환경에선 비활성화" 안내가 나와야 사용자 UX 가 일관됨. REST/WS 는
    // 그대로 skip — 외부 노출 환경의 보안 차단 의도 보존.
    if (!authDeps.config.security.allowTerminal) {
        log.info { "Terminal routes: SSR-only mode (security.allowTerminal=false). REST/WS skipped." }
        get("/terminal") {
            val sess = requireSessionOrRedirect(authDeps) ?: return@get
            if (!requireAdminOrRedirect(sess)) return@get
            call.respondText(
                TerminalTemplates.disabledPage(sess.username, csrf = sess.csrf, lang = sess.language),
                ContentType.Text.Html,
            )
        }
        get("/settings/terminal") {
            call.respondRedirect("/terminal", permanent = false)
        }
        return
    }

    // SSR — v1.27.0: 글로벌 사이드바 메뉴 (/terminal). workspace 폴더 cwd 기본.
    get("/terminal") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            TerminalTemplates.page(sess.username, csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    // v1.27.0 — Legacy alias /settings/terminal → /terminal. 북마크 / 외부 문서 / 사이드바
    // 미패치 클라이언트 보호. v1.27.1 Q-8: 영구(301) → 임시(302). alias 의 본성에 일관.
    get("/settings/terminal") {
        call.respondRedirect("/terminal", permanent = false)
    }

    // REST — v1.26.3 C1: Bearer 가드 + admin role 검증. SSR cookie 도 같은 Auth provider
    // 가 인식 (`installAuth` 의 vibe-session validator) — 브라우저 fetch credentials:
    // 'same-origin' 으로 통과 가능.
    authenticate(AUTH_BEARER) {
        post(ApiPath.TERMINAL_SESSIONS) {
            if (!requireApiAdminOrFail()) return@post
            try {
                val s = manager.create(userIdOf())
                call.respond(mapOf("sessionId" to s.id, "workdir" to s.workdir))
            } catch (e: TerminalSessionManager.SessionLimitException) {
                // v1.27.0 — per-user 한도 초과. 429 + 명시적 에러코드 (Android client
                // 가 사용자 친화적 메시지로 surface 가능).
                log.info { "terminal session limit hit: ${e.message}" }
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    mapOf(
                        "error" to "session_limit",
                        "max" to TerminalSessionManager.MAX_SESSIONS_PER_USER,
                    ),
                )
            }
        }

        get(ApiPath.TERMINAL_SESSIONS) {
            if (!requireApiAdminOrFail()) return@get
            val list = manager.list().map {
                mapOf(
                    "sessionId" to it.id,
                    "workdir" to it.workdir,
                    "createdAt" to it.createdAt.toString(),
                    "alive" to it.isAlive(),
                )
            }
            call.respond(mapOf("sessions" to list))
        }

        delete(ApiPath.terminalSession("{id}")) {
            if (!requireApiAdminOrFail()) return@delete
            val id = call.parameters["id"].orEmpty()
            manager.close(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // WS bidirectional — v1.26.3 C2/C3/B3 회수:
    //  - Origin ↔ Host 검증 (CSWSH 방어)
    //  - cookie-first 인증 (httpOnly + 브라우저 자동 첨부) → Auth-frame fallback (Android client)
    //  - 5s withTimeout (handshake 영구 점유 방지)
    //  - admin role 검증
    // v1.27.1 B-3 회수: raw path string → `ApiPath.wsTerminal("{id}")` (shared SSOT).
    // CLAUDE.md §8.A 의 "신규 REST/WS endpoint 는 반드시 shared/ApiPath.kt 에 먼저
    // 등록되고 라우터는 그 상수를 참조" 강제 룰 준수. Android client 와 path drift 방어.
    webSocket(ApiPath.wsTerminal("{id}")) {
        val id = call.parameters["id"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing id"))

        val deviceUserId = authenticateTerminalWs(deviceRepo, tokens, userRepo)
            ?: return@webSocket   // 위 헬퍼가 이미 close + log
        val sess = manager.get(id) ?: run {
            close(CloseReason(CloseReason.Codes.NORMAL, "session not found"))
            return@webSocket
        }
        // 본인 user 만 본인 session 사용 (per-user owner 검증).
        if (sess.ownerUserId != null && sess.ownerUserId != deviceUserId) {
            log.warn { "ws terminal: session owner mismatch (session.owner=${sess.ownerUserId} caller=$deviceUserId)" }
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not_session_owner"))
            return@webSocket
        }

        sess.touch()   // v1.26.3 C5: WS connect → idle 시계 reset

        coroutineScope {
            // server → client: PTY stdout → TerminalOutput frame.
            val outJob = launch {
                sess.output.collect { data ->
                    runCatching {
                        send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalOutput(data))))
                    }
                }
            }
            // exit 도 한 번 보내고 종료.
            val exitJob = launch {
                sess.exit.collect { code ->
                    runCatching {
                        send(Frame.Text(wsJson.encodeToString(WsFrame.serializer(), WsFrame.TerminalExit(code))))
                    }
                    close(CloseReason(CloseReason.Codes.NORMAL, "exited"))
                }
            }
            // client → server: input / resize.
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val parsed = runCatching {
                        wsJson.decodeFromString(WsFrame.serializer(), frame.readText())
                    }.getOrNull() ?: continue
                    when (parsed) {
                        is WsFrame.TerminalInput -> {
                            // v1.27.1 Q-6: input data size sanity cap. xterm.js 정상 keystroke
                            // 단위는 수 byte 라 64KB 는 paste 시나리오까지 안전 여유. 초과 시 drop
                            // + warn log — 악성 / 자동화 실수로 인한 메모리 spike 차단.
                            if (parsed.data.length > MAX_INPUT_CHARS) {
                                log.warn { "ws terminal input dropped: ${parsed.data.length} chars > $MAX_INPUT_CHARS cap" }
                            } else {
                                sess.write(parsed.data)
                            }
                        }
                        is WsFrame.TerminalResize -> sess.resize(parsed.cols, parsed.rows)
                        else -> Unit
                    }
                }
            } finally {
                outJob.cancel()
                exitJob.cancel()
            }
        }
    }
}

/**
 * v1.26.3 — REST 라우트의 admin role 검증. Bearer principal 의 `userRole` (이미
 * AuthPlugin 이 device 와 user join 으로 채워놓음). admin 아니면 403 + false 반환
 * (호출자가 즉시 return).
 *
 * v1.27.0 — `DevicePrincipal.isAdmin` 활용으로 userRepo 별도 조회 제거. Bearer
 * principal 자체가 userRole 을 이미 보유 (AuthPlugin 의 vibe-bearer validator
 * 가 device → user → role join). 라우트가 admin-only 라는 의도가 코드에서 더 명확.
 *
 * v1.27.1 Q-2 회수: PTY = 호스트 root 등가 (컨테이너 vibe NOPASSWD sudo) 라
 * legacy unbound device 의 fallback admin 허용 (v0.45.0 DevicePrincipal.isAdmin
 * 의 null → "admin" backward-compat) 을 거부 — strict 검사. WS 핸드셰이크의
 * `authenticateTerminalWs` 정책 ("userRole == \"admin\"") 과 비대칭이던 점도 동시 해소.
 * 단일 admin 환경에선 영향 0 — admin user 의 device 는 항상 userRole="admin" 으로
 * bind 되어 있음.
 */
private suspend fun io.ktor.server.routing.RoutingContext.requireApiAdminOrFail(): Boolean {
    val principal = call.principal<DevicePrincipal>()
    val strictRole = principal?.userRole
    if (strictRole != "admin") {
        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin_required"))
        return false
    }
    return true
}

private fun io.ktor.server.routing.RoutingContext.userIdOf(): String? =
    call.principal<DevicePrincipal>()?.device?.userId

/**
 * v1.26.3 — Terminal WS 핸드셰이크 인증. 반환값:
 *  - non-null userId: 인증 통과. 호출자가 PTY session ownership 검증에 사용.
 *  - null: 인증 실패 (이미 close + log). 호출자는 즉시 return.
 *
 * 보안 체크:
 *  1. Origin ↔ Host 검증 (CSWSH 방어).
 *  2. cookie 우선 (httpOnly + handshake 자동 첨부) → Auth-frame fallback (Android client).
 *  3. user.role == "admin" 검증 (PTY 는 admin 전용).
 *  4. 5s withTimeout — 악성 클라가 handshake 만 잡고 첫 frame 안 보내는 케이스 차단.
 */
private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.authenticateTerminalWs(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    userRepo: AdminUserRepository,
): String? {
    // 1. Origin 검증.
    val origin = call.request.headers["Origin"]
    if (!origin.isNullOrBlank()) {
        val host = call.request.headers["Host"]
        val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
        if (host != null && originHost != null) {
            val hostName = host.substringBefore(':')
            if (originHost != hostName) {
                log.warn { "ws terminal origin mismatch: origin=$origin host=$host" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied"))
                return null
            }
        }
    }
    // 2. cookie-first (브라우저 handshake) → Auth-frame fallback (Android).
    val device = run {
        val cookieToken = call.request.cookies[SESSION_COOKIE]
        if (!cookieToken.isNullOrBlank()) {
            return@run deviceRepo.findByTokenHash(Sha256.hashString(cookieToken))
        }
        // v1.27.1 B-2 회수: incoming.receive() 가 채널 close 시 ClosedReceiveChannelException
        // 던짐 (정상 handshake 후 첫 frame 없이 즉시 disconnect 하는 케이스 — 브라우저
        // 탭 close / 모바일 네트워크 끊김 등). 이전엔 TimeoutCancellationException 만
        // catch 해서 정상 disconnect 도 error log 양산. runCatching 으로 모두 흡수.
        runCatching {
            withTimeout(5_000) {
                val firstRaw = (incoming.receive() as? Frame.Text)?.readText() ?: return@withTimeout null
                val first = runCatching { wsJson.decodeFromString(WsFrame.serializer(), firstRaw) }
                    .getOrNull() as? WsFrame.Auth ?: return@withTimeout null
                deviceRepo.findByTokenHash(tokens.hashOf(first.token))
            }
        }.onFailure { e ->
            when (e) {
                is TimeoutCancellationException -> log.warn { "ws terminal auth timeout" }
                else -> log.debug(e) { "ws terminal auth aborted: ${e.javaClass.simpleName}" }
            }
        }.getOrNull()
    }
    if (device == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated"))
        return null
    }
    deviceRepo.touchLastSeen(device.id)
    // 3. admin role.
    val uid = device.userId
    val role = uid?.let { userRepo.findById(it)?.role }
    if (role != "admin") {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "admin_required"))
        return null
    }
    return uid
}

internal object TerminalTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * v1.27.1 B-1 회수: `security.allowTerminal=false` 환경 (외부 노출 시 보안
     * 차단 사용) 에서 사이드바 "터미널" 메뉴 클릭 시 404 가 아니라 비활성화 안내를
     * 보여준다. REST/WS 는 그대로 미등록 — UI 만 placeholder.
     */
    fun disabledPage(username: String, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return AdminTemplates.shell(
            title = t("term.title"),
            username = username,
            currentPath = "/terminal",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1 style="margin:0">${esc(t("term.title"))}</h1>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("term.disabled.body"))}</p>
</header>
<div class="card" style="margin-top:14px">
  <p style="margin:0">${esc(t("term.disabled.hint"))}</p>
  <code style="display:block;margin-top:8px;padding:10px;background:var(--code-bg,#f4f4f5);border-radius:6px;font-size:12px">security:\n  allowTerminal: true</code>
</div>
""",
        )
    }

    fun page(username: String, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return AdminTemplates.shell(
            title = t("term.title"),
            username = username,
            currentPath = "/terminal",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1 style="margin:0">${esc(t("term.title"))}</h1>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("term.intro"))}</p>
</header>

<!-- xterm.js (BSD) — v1.7.19 로컬 번들. CLAUDE.md §3 의 "외부 CDN 미사용"
     정책 일관. v1.7.20 — ?v=xterm-5.5.0-fit-0.10.0 cache-bust 로 브라우저
     이전 CDN 응답 캐시 무력화 (사용자 보고: force-reload 안 해도 새 번들 로드). -->
<link rel="stylesheet" href="/static/vendor/xterm/xterm.min.css?v=5.5.0">

<div id="term-host" style="background:#000;padding:10px;border-radius:8px;height:70vh;min-height:400px"></div>
<div id="term-status" class="dim" style="font-size:12px;margin-top:6px">${esc(t("term.status.connecting"))}</div>

<script src="/static/vendor/xterm/xterm.min.js?v=5.5.0"></script>
<script src="/static/vendor/xterm/addon-fit.min.js?v=0.10.0"></script>
<script>
(function(){
  var status = document.getElementById('term-status');
  function setStatus(s){ status.textContent = s; }

  var term = new Terminal({
    cursorBlink: true,
    convertEol: true,
    fontFamily: 'ui-monospace, Menlo, Consolas, monospace',
    fontSize: 13,
    theme: { background: '#000', foreground: '#e5e5e5', cursor: '#e5e5e5' },
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('term-host'));
  fit.fit();

  // 1) session 생성.
  // v1.26.3 — credentials:same-origin 으로 vibe_session 쿠키 자동 첨부 (server 가 Bearer
  // 가드 안에서 vibe-session provider 로 인증). 이전엔 ?_csrf 만 있었고 인증 무가드.
  fetch('/api/terminal/sessions', { method: 'POST', credentials: 'same-origin' })
    .then(function(r){
      if (!r.ok) throw new Error('session create failed: HTTP ' + r.status);
      return r.json();
    })
    .then(function(s){
      setStatus('${esc(t("term.status.connectingWs"))} ' + s.sessionId);
      var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      // v1.26.3 — WS handshake 시 브라우저가 vibe_session 쿠키 자동 첨부 →
      // server 의 authenticateTerminalWs 가 cookie-first 로 인증. JS 가 cookie 를
      // 읽을 필요 없음 (httpOnly). 첫 Auth frame 도 보내지 않음.
      var ws = new WebSocket(proto + '//' + location.host + '/ws/terminal/' + s.sessionId);
      ws.onopen = function(){
        setStatus('${esc(t("term.status.connected"))} ' + s.sessionId);
        fit.fit();
        ws.send(JSON.stringify({ type: 'terminal_resize', cols: term.cols, rows: term.rows }));
      };
      ws.onmessage = function(ev){
        try {
          var f = JSON.parse(ev.data);
          if (f.type === 'terminal_output') term.write(f.data);
          else if (f.type === 'terminal_exit') {
            term.write('\r\n\r\n[process exited code=' + f.exitCode + ']');
            setStatus('${esc(t("term.status.exited"))}');
          }
        } catch(e){}
      };
      ws.onclose = function(){ setStatus('${esc(t("term.status.disconnected"))}'); };
      term.onData(function(d){
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'terminal_input', data: d }));
      });
      window.addEventListener('resize', function(){
        fit.fit();
        if (ws.readyState === 1) {
          ws.send(JSON.stringify({ type: 'terminal_resize', cols: term.cols, rows: term.rows }));
        }
      });
    })
    .catch(function(e){ setStatus('error: ' + e); });
})();
</script>
""",
        )
    }
}
