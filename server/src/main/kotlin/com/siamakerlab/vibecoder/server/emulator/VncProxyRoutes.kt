package com.siamakerlab.vibecoder.server.emulator

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * v0.42.0 — emulator/vnc reverse proxy.
 *
 * noVNC 가 컨테이너 안 localhost:6080 의 websockify 에 떠 있고, 이를
 * vibe-coder admin 인증 boundary 안으로 끌어들이는 proxy. HTTP 정적 자원
 * (vnc.html, JS, CSS, 이미지) + WebSocket (websockify path) 둘 다 forward.
 *
 * Admin 가드 — 모든 요청에 requireAdminOrRedirect. viewer / member 는 거절.
 *
 * 외부 의존성 없음 — JDK 11+ java.net.http.HttpClient + WebSocket.
 */
fun Routing.vncProxyRoutes(authDeps: AdminRoutesDeps) {

    /** HTTP GET — noVNC 정적 자원 (vnc.html / app JS / images 등) forward. */
    get("/emulator/vnc/{path...}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get

        val segments = call.parameters.getAll("path").orEmpty()
        val path = if (segments.isEmpty()) "vnc.html" else segments.joinToString("/")
        val query = call.request.queryParameters.entries()
            .flatMap { (k, vs) -> vs.map { "$k=${java.net.URLEncoder.encode(it, Charsets.UTF_8)}" } }
            .joinToString("&")
        val targetUri = "http://$NOVNC_HOST:$NOVNC_PORT/$path" + if (query.isBlank()) "" else "?$query"

        try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(targetUri))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val ct = resp.headers().firstValue(HttpHeaders.ContentType).orElse(guessContentType(path))
            // CORP 제거 (iframe embed 차단 헤더가 따라오면 곤란).
            call.respondBytes(resp.body(), contentType = ContentType.parse(ct), status = HttpStatusCode.fromValue(resp.statusCode())) {
                // no extra headers
            }
        } catch (e: Throwable) {
            log.warn(e) { "vnc proxy GET failed: $targetUri" }
            call.respondText(
                "VNC proxy failed: ${e.message ?: "unknown"} (path=$path)",
                ContentType.Text.Plain,
                HttpStatusCode.BadGateway,
            )
        }
    }

    /**
     * WebSocket — noVNC client 가 `wss://<host>/emulator/vnc/websockify` 로
     * 연결. 우리는 backend `ws://localhost:6080/websockify` 로 별도 WS 열어
     * 양방향 binary frame forward.
     *
     * `wss://` (TLS) 는 reverse proxy 가 처리. 본 endpoint 는 plain WS.
     */
    webSocket("/emulator/vnc/websockify") {
        // v1.27.4 (Q4 회수) — CSWSH 방어: Origin ↔ Host 검증. WsRoutes / TerminalRoutes
        // 가 일관 적용하는 표준인데 본 endpoint 만 누락돼 있었음. 실행 중 에뮬레이터의
        // live VNC 채널은 화면 캡처 / 입력 주입이 가능하므로 cross-origin WS 차단 필수.
        // Origin 빈 클라(curl / 네이티브)는 통과 — 인증은 어차피 cookie+admin 으로.
        val origin = call.request.headers["Origin"]
        if (!origin.isNullOrBlank()) {
            val host = call.request.headers["Host"]
            val originHost = runCatching { java.net.URI(origin).host }.getOrNull()
            if (host != null && originHost != null && originHost != host.substringBefore(':')) {
                log.warn { "vnc ws origin mismatch: origin=$origin host=$host — closing" }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin_denied"))
                return@webSocket
            }
        }
        // 인증 가드 — Ktor WS handshake 에서 같은 cookie 가 흘러옴.
        // call.principal 직접 못 쓰니 cookie 기반 직접 검증 (간단화):
        val token = call.request.cookies["vibe_session"]
        if (token.isNullOrBlank()) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no session"))
            return@webSocket
        }
        val hash = com.siamakerlab.vibecoder.server.core.Sha256.hashString(token)
        val device = authDeps.deviceRepo.findByTokenHash(hash)
        val user = device?.userId?.let { authDeps.userRepo.findById(it) }
        if (user == null || !user.isAdmin) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "admin only"))
            return@webSocket
        }

        val serverWs = this
        val backendUri = URI.create("ws://$NOVNC_HOST:$NOVNC_PORT/websockify")
        val backendClosed = java.util.concurrent.atomic.AtomicBoolean(false)

        // Backend WS listener — backend 가 보낸 binary 를 클라이언트로 forward.
        val listener = object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) {
                ws.request(1)
            }
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): java.util.concurrent.CompletionStage<*> {
                val bytes = ByteArray(data.remaining()); data.get(bytes)
                serverWs.launch {
                    runCatching { serverWs.send(Frame.Binary(true, bytes)) }
                }
                ws.request(1)
                return CompletableFuture.completedFuture<Void>(null)
            }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*> {
                serverWs.launch {
                    runCatching { serverWs.send(Frame.Text(data.toString())) }
                }
                ws.request(1)
                return CompletableFuture.completedFuture<Void>(null)
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String?): java.util.concurrent.CompletionStage<*> {
                backendClosed.set(true)
                serverWs.launch {
                    runCatching { close(CloseReason(CloseReason.Codes.NORMAL, reason ?: "backend closed")) }
                }
                return CompletableFuture.completedFuture<Void>(null)
            }
            override fun onError(ws: WebSocket, error: Throwable) {
                log.debug(error) { "backend WS error" }
                backendClosed.set(true)
                serverWs.launch {
                    runCatching { close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, error.message ?: "ws error")) }
                }
            }
        }

        val backendFuture: WebSocket? = try {
            httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .subprotocols("binary")  // noVNC 가 `binary` subprotocol 협상.
                .buildAsync(backendUri, listener)
                .get(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Throwable) {
            log.warn(e) { "VNC backend WS connect failed: $backendUri" }
            close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "backend connect failed"))
            return@webSocket
        }
        val backend = backendFuture ?: return@webSocket

        try {
            // 클라이언트가 보낸 binary / text 를 backend 로 forward.
            for (frame in incoming) {
                if (backendClosed.get()) break
                when (frame) {
                    // 21차 점검(minor) — .get() 동기 블로킹을 .await() suspend 로 전환.
                    // 이전엔 매 프레임 송신마다 WS 핸들러 코루틴 스레드를 블로킹해
                    // latency spike 시 다른 WS/HTTP 처리에 backpressure 를 유발했다.
                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        backend.sendBinary(ByteBuffer.wrap(bytes), true).await()
                    }
                    is Frame.Text -> {
                        backend.sendText(frame.readText(), true).await()
                    }
                    is Frame.Close -> break
                    else -> { /* ping/pong — Ktor 자동 처리 */ }
                }
            }
        } catch (e: Throwable) {
            log.debug(e) { "VNC server-side WS loop exited" }
        } finally {
            runCatching { backend.sendClose(WebSocket.NORMAL_CLOSURE, "client closed") }
        }
    }
}

private const val NOVNC_HOST = "127.0.0.1"
private const val NOVNC_PORT = 6080

private val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

private fun guessContentType(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

