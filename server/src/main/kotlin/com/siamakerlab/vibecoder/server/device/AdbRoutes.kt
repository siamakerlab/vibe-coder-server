package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.requireDevice
import io.ktor.server.auth.authenticate
import com.siamakerlab.vibecoder.server.core.Sha256
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v1.40.0 — 무선 ADB 기기 logcat UI. admin 전용.
 *
 *   GET  /adb                  — 페어/연결 + 자동탐지(HOST) + 기기 목록 + logcat 뷰어
 *   POST /adb/pair|connect|disconnect
 *   GET  /api/adb/status       — { available, connected } (사이드바 뱃지, 무인증·저민감)
 *   WS   /ws/adb/logcat?serial=&pkg=  — logcat 라인 스트림 (쿠키 admin)
 */
fun Routing.adbRoutes(
    authDeps: AdminRoutesDeps,
    adb: AdbService,
    deviceRepo: DeviceRepository,
    userRepo: AdminUserRepository,
) {
    get("/adb") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            AdbTemplates.page(
                username = sess.username,
                available = adb.available(),
                devices = if (adb.available()) runCatching { adb.devices() }.getOrElse { emptyList() } else emptyList(),
                discovered = if (adb.available()) runCatching { adb.discover() }.getOrElse { emptyList() } else emptyList(),
                ok = ok, err = err, csrf = sess.csrf, lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/adb/pair") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val pairHostPort = form["hostPort"]?.trim().orEmpty()
        val r = adb.pair(pairHostPort, form["code"]?.trim().orEmpty())
        if (!r.ok) {
            redirectResult(false, r.output)
            return@post
        }
        // v1.59.1 — 페어링 성공 시 같은 IP 의 mDNS connect 포트로 자동 connect 1회 시도.
        // (페어링 포트와 connect 포트는 다름. 자동 탐지가 connect 서비스를 찾으면 바로 연결.)
        val ip = pairHostPort.substringBefore(':')
        val connectHp = runCatching { adb.connectPortFor(ip) }.getOrNull()
        if (connectHp != null) {
            val cr = runCatching { adb.connect(connectHp) }.getOrNull()
            if (cr != null && cr.ok) {
                redirectResult(true, Messages.t(sess.language, "adb.pair.autoConnected", connectHp))
                return@post
            }
        }
        // 자동 connect 못 했으면 페어링 성공 + 수동 연결 안내.
        redirectResult(true, Messages.t(sess.language, "adb.pair.okConnectHint"))
    }

    post("/adb/connect") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val r = adb.connect(requireCsrf()["hostPort"]?.trim().orEmpty())
        if (!r.ok && adb.isLikelyUnpaired(r.output)) {
            // v1.59.1 — 포트는 열려 있으나 미페어링 TLS 거부 → 페어링 먼저 안내.
            redirectResult(false, Messages.t(sess.language, "adb.connect.needPair"))
            return@post
        }
        redirectResult(r.ok, r.output)
    }

    post("/adb/disconnect") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val r = adb.disconnect(requireCsrf()["hostPort"]?.trim().orEmpty())
        redirectResult(r.ok, r.output)
    }

    // 사이드바 뱃지 — 연결 수 + adb 유무. v1.77.0 — quota 와 일괄 인증 게이트(미인증 노출 회수).
    // 사이드바 pill 은 fetch(credentials:'same-origin') 로 vibe_session 쿠키 전송 → 통과.
    authenticate(AUTH_BEARER) {
        get(ApiPath.ADB_STATUS) {
            call.requireDevice() // 인증만 강제(단일 admin)
            val available = adb.available()
            val connected = if (available) runCatching { adb.connectedCount() }.getOrElse { 0 } else 0
            call.respondText(
                """{"available":$available,"connected":$connected}""",
                ContentType.Application.Json,
            )
        }
    }

    // logcat 스트림 — 쿠키 기반 admin 검증(브라우저 핸드셰이크가 vibe_session 전송).
    webSocket(ApiPath.WS_ADB_LOGCAT) {
        if (!authAdminWs(deviceRepo, userRepo)) return@webSocket
        val serial = call.request.queryParameters["serial"]?.trim().orEmpty()
        val pkg = call.request.queryParameters["pkg"]?.trim()?.ifBlank { null }
        if (serial.isEmpty()) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "missing serial")); return@webSocket }
        val proc = adb.startLogcat(serial, pkg)
            ?: run { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "logcat spawn failed")); return@webSocket }
        try {
            val reader = java.io.InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)
            val buf = CharArray(8192)
            val pump = launch(Dispatchers.IO) {
                runCatching {
                    while (isActive) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) send(Frame.Text(String(buf, 0, n)))
                    }
                }
                runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "logcat ended")) }
            }
            // 클라이언트 close 대기.
            for (frame in incoming) { /* 클라 입력 무시 — 단방향 스트림 */ }
            pump.cancel()
        } finally {
            runCatching { proc.destroyForcibly() }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.redirectResult(ok: Boolean, output: String) {
    val tail = output.lines().lastOrNull { it.isNotBlank() }?.take(160) ?: ""
    val q = if (ok) "ok=${enc(tail.ifBlank { "성공" })}" else "err=${enc(tail.ifBlank { "실패" })}"
    call.respondRedirect("/adb?$q")
}

/** WS 핸드셰이크 admin 검증 — Origin + vibe_session 쿠키 → device → admin role. */
private suspend fun DefaultWebSocketServerSession.authAdminWs(
    deviceRepo: DeviceRepository,
    userRepo: AdminUserRepository,
): Boolean {
    val origin = call.request.headers["Origin"]
    if (!origin.isNullOrBlank()) {
        val host = call.request.headers["Host"]
        val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
        val hostName = host?.substringBefore(':')
        if (originHost == null || hostName == null || originHost != hostName) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied")); return false
        }
    }
    val cookie = call.request.cookies[SESSION_COOKIE]
    val device = cookie?.takeIf { it.isNotBlank() }?.let { deviceRepo.findByTokenHash(Sha256.hashString(it)) }
    if (device == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthenticated")); return false }
    val role = device.userId?.let { userRepo.findById(it)?.role }
    if (role != "admin") { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "admin_required")); return false }
    return true
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
