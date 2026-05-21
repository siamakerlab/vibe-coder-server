package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.PasswordPolicy
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.UsernamePolicy
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.server.env.StatusService
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

data class AdminRoutesDeps(
    val config: ServerConfig,
    val serverName: String,
    val serverVersion: String,
    val workspaceRoot: String,
    val authService: AuthService,
    val userRepo: AdminUserRepository,
    val deviceRepo: DeviceRepository,
    val statusService: StatusService,
    val envDiagnostics: EnvDiagnostics,
)

/**
 * 서버 사이드 렌더링 admin 웹 라우트.
 * 모든 POST는 form-urlencoded. 인증은 SESSION_COOKIE 기반.
 */
fun Routing.adminRoutes(deps: AdminRoutesDeps) {
    // 정적 파일 (CSS) — resources/static/admin/* 가 /admin/static/* 으로 노출
    staticResources("/admin/static", "static/admin")

    route("/admin") {

        // ── 진입점: 라우팅 ────────────────────────────────────────────
        get {
            val sess = requireSessionOrRedirect(deps) ?: return@get
            val status = deps.statusService.snapshot()
            val deviceCount = deps.deviceRepo.listAll().size
            // running build count는 status에 없으므로 0 표시. (간단함을 위해 PoC에선 보류)
            val html = AdminTemplates.dashboardPage(
                username = sess.username,
                status = status,
                deviceCount = deviceCount,
                runningBuilds = 0,
            )
            call.respondText(html, ContentType.Text.Html)
        }

        // ── Setup ─────────────────────────────────────────────────────
        get("/setup") {
            if (deps.authService.adminExists()) {
                call.respondRedirect("/admin/login")
                return@get
            }
            call.respondText(AdminTemplates.setupPage(), ContentType.Text.Html)
        }

        post("/setup") {
            if (deps.authService.adminExists()) {
                call.respondRedirect("/admin/login")
                return@post
            }
            val params = call.receiveParameters()
            val username = params["username"]?.trim().orEmpty()
            val password = params["password"].orEmpty()
            val confirm = params["passwordConfirm"].orEmpty()

            val err = when {
                username.isEmpty() -> "사용자명을 입력하세요."
                password.isEmpty() -> "비밀번호를 입력하세요."
                password != confirm -> "비밀번호 확인이 일치하지 않습니다."
                else -> UsernamePolicy.violation(username) ?: PasswordPolicy.violation(password)
            }
            if (err != null) {
                call.respondText(
                    AdminTemplates.setupPage(error = err),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val outcome = runCatching {
                deps.authService.setup(
                    username = username,
                    password = password,
                    deviceName = browserDeviceName(call),
                    channel = "web",
                )
            }.getOrElse { e ->
                val msg = (e as? ApiException)?.message ?: "초기 설정 실패"
                call.respondText(
                    AdminTemplates.setupPage(error = msg),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            setSessionCookie(call, outcome.token)
            call.respondRedirect("/admin")
        }

        // ── Login ─────────────────────────────────────────────────────
        get("/login") {
            if (!deps.authService.adminExists()) {
                call.respondRedirect("/admin/setup")
                return@get
            }
            val next = call.request.queryParameters["next"]
            call.respondText(AdminTemplates.loginPage(next = next), ContentType.Text.Html)
        }

        post("/login") {
            if (!deps.authService.adminExists()) {
                call.respondRedirect("/admin/setup")
                return@post
            }
            val params = call.receiveParameters()
            val username = params["username"]?.trim().orEmpty()
            val password = params["password"].orEmpty()
            val next = params["next"]?.takeIf { it.startsWith("/admin") } ?: "/admin"

            val outcome = runCatching {
                deps.authService.login(
                    username = username,
                    password = password,
                    deviceName = browserDeviceName(call),
                    channel = "web",
                )
            }.getOrElse { e ->
                val msg = (e as? ApiException)?.message ?: "로그인 실패"
                call.respondText(
                    AdminTemplates.loginPage(error = msg, next = next),
                    ContentType.Text.Html,
                    HttpStatusCode.Unauthorized,
                )
                return@post
            }
            setSessionCookie(call, outcome.token)
            call.respondRedirect(next)
        }

        // ── Logout ────────────────────────────────────────────────────
        post("/logout") {
            val token = call.request.cookies[SESSION_COOKIE]
            if (token != null) {
                // 단일 디바이스 row 삭제로 invalidate
                val hash = com.siamakerlab.vibecoder.server.core.Sha256.hashString(token)
                deps.deviceRepo.findByTokenHash(hash)?.let { deps.deviceRepo.deleteById(it.id) }
            }
            clearSessionCookie(call)
            call.respondRedirect("/admin/login")
        }

        // ── Dashboard 외 페이지 ───────────────────────────────────────

        get("/settings") {
            val sess = requireSessionOrRedirect(deps) ?: return@get
            val cfg = deps.config
            val view = AdminTemplates.SettingsView(
                serverName = cfg.server.name,
                serverPort = cfg.server.port,
                serverHost = cfg.server.host,
                maxUploadMb = cfg.workspace.maxUploadSizeMb,
                artifactKeep = cfg.workspace.artifactKeepCount,
                claudeEnabled = cfg.claude.enabled,
                claudePath = cfg.claude.path,
                claudeTimeoutMin = cfg.claude.timeoutMinutes,
                buildTimeoutMin = cfg.build.timeoutMinutes,
                defaultDebugTask = cfg.build.defaultDebugTask,
            )
            val ok = call.request.queryParameters["ok"]?.let { "저장됨." }
            call.respondText(
                AdminTemplates.settingsPage(sess.username, view, flashOk = ok),
                ContentType.Text.Html,
            )
        }

        post("/settings") {
            val sess = requireSessionOrRedirect(deps) ?: return@post
            // server.yml 직접 편집은 위험 → PoC에서는 폼 데이터만 받아 적용은 보류, UI 동작만 검증
            // (실제 디스크 쓰기는 후속 사이클에서 백업/롤백 포함 구현)
            log.info { "settings 저장 시도 by ${sess.username} (PoC: not yet persisted)" }
            call.respondRedirect("/admin/settings?ok=1")
        }

        get("/password") {
            val sess = requireSessionOrRedirect(deps) ?: return@get
            call.respondText(AdminTemplates.passwordPage(sess.username), ContentType.Text.Html)
        }

        post("/password") {
            val sess = requireSessionOrRedirect(deps) ?: return@post
            val params = call.receiveParameters()
            val current = params["currentPassword"].orEmpty()
            val new = params["newPassword"].orEmpty()
            val confirm = params["newPasswordConfirm"].orEmpty()
            if (new != confirm) {
                call.respondText(
                    AdminTemplates.passwordPage(sess.username, flashErr = "새 비밀번호 확인이 일치하지 않습니다."),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            val result = runCatching {
                deps.authService.changePassword(sess.userId, current, new)
            }
            if (result.isFailure) {
                val msg = (result.exceptionOrNull() as? ApiException)?.message
                    ?: "비밀번호 변경 실패"
                call.respondText(
                    AdminTemplates.passwordPage(sess.username, flashErr = msg),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            call.respondText(
                AdminTemplates.passwordPage(sess.username, flashOk = "비밀번호가 변경되었습니다."),
                ContentType.Text.Html,
            )
        }

        get("/devices") {
            val sess = requireSessionOrRedirect(deps) ?: return@get
            val devices = deps.deviceRepo.listAll()
            val ok = call.request.queryParameters["ok"]?.let { "디바이스 토큰이 무효화되었습니다." }
            call.respondText(
                AdminTemplates.devicesPage(sess.username, devices, sess.deviceId, flashOk = ok),
                ContentType.Text.Html,
            )
        }

        post("/devices/{id}/revoke") {
            val sess = requireSessionOrRedirect(deps) ?: return@post
            val id = call.parameters["id"]
            if (id == null || id == sess.deviceId) {
                call.respondText(
                    AdminTemplates.errorPage(400, "현재 세션은 revoke할 수 없습니다. 로그아웃을 사용하세요."),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            deps.deviceRepo.deleteById(id)
            call.respondRedirect("/admin/devices?ok=1")
        }
    }
}

// ─── 세션 헬퍼 ─────────────────────────────────────────────────────────────

private data class WebSession(
    val token: String,
    val userId: String,
    val username: String,
    val deviceId: String,
)

/** 세션 유효 시 WebSession, 아니면 적절한 곳으로 redirect 후 null 반환. */
private suspend fun io.ktor.server.routing.RoutingContext.requireSessionOrRedirect(
    deps: AdminRoutesDeps,
): WebSession? {
    val token = call.request.cookies[SESSION_COOKIE]
    if (token.isNullOrBlank()) {
        if (!deps.authService.adminExists()) call.respondRedirect("/admin/setup")
        else call.respondRedirect("/admin/login?next=${call.request.local.uri}")
        return null
    }
    val hash = com.siamakerlab.vibecoder.server.core.Sha256.hashString(token)
    val device = deps.deviceRepo.findByTokenHash(hash)
    if (device == null || device.userId == null) {
        clearSessionCookie(call)
        call.respondRedirect("/admin/login")
        return null
    }
    val user = deps.userRepo.findById(device.userId)
    if (user == null) {
        clearSessionCookie(call)
        call.respondRedirect("/admin/login")
        return null
    }
    deps.deviceRepo.touchLastSeen(device.id)
    return WebSession(token, user.id, user.username, device.id)
}

private fun setSessionCookie(call: ApplicationCall, token: String) {
    // LAN HTTP 환경 가정 → Secure 미지정 (reverse proxy 뒤라면 X-Forwarded-Proto로 감지 가능하나 PoC에서는 생략)
    call.response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = token,
            httpOnly = true,
            maxAge = 60 * 60 * 24 * 14,  // 14일
            path = "/",
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

private fun clearSessionCookie(call: ApplicationCall) {
    call.response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = "",
            httpOnly = true,
            maxAge = 0,
            path = "/",
            extensions = mapOf("SameSite" to "Lax"),
        )
    )
}

private fun browserDeviceName(call: ApplicationCall): String {
    val ua = call.request.headers["User-Agent"].orEmpty()
    return when {
        "Firefox" in ua -> "Firefox"
        "Edg/" in ua -> "Edge"
        "Chrome" in ua -> "Chrome"
        "Safari" in ua -> "Safari"
        else -> "Web client"
    }
}
