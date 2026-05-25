package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.auth.AuthService
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.PasswordPolicy
import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.UsernamePolicy
import com.siamakerlab.vibecoder.server.auth.bearerTokenOrNull
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
    val audit: AuditLogger,
    /** v0.21.0 — 대시보드 사용량 카드용 최신 snapshot 조회. */
    val claudeUsageMonitor: com.siamakerlab.vibecoder.server.claude.ClaudeUsageMonitor,
    /** v0.29.0 — 대시보드 디스크 사용량 카드. */
    val diskMonitor: com.siamakerlab.vibecoder.server.disk.DiskMonitor,
    /** v0.57.0 — Phase 36 passwordless-only 검사용 hasCredentials lookup. */
    val webauthnService: com.siamakerlab.vibecoder.server.auth.WebauthnService,
)

/**
 * 서버 사이드 렌더링 웹 라우트.
 * 모든 POST는 form-urlencoded. 인증은 SESSION_COOKIE 기반.
 *
 * v0.5.0 부터 `admin` prefix 를 제거하고 루트 레벨로 평탄화.
 * 함수명 `adminRoutes` 는 호환을 위해 유지 (호출부 변경 최소화).
 */
fun Routing.adminRoutes(deps: AdminRoutesDeps) {
    // 정적 파일 (CSS) — resources/static/admin/* 가 /static/* 으로 노출
    staticResources("/static", "static/admin")

    // ── 진입점: 대시보드 = 루트 ────────────────────────────────────
    get("/") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        val status = deps.statusService.snapshot()
        val deviceCount = deps.deviceRepo.listAll().size
        // running build count는 status에 없으므로 0 표시. (간단함을 위해 PoC에선 보류)
        // Claude 인증 진단도 같이 — 사용자가 콘솔에서 처음으로 에러를 만나기 전에 대시보드에서 알아채도록.
        val claudeAuth = runCatching { deps.envDiagnostics.run().claudeAuth }.getOrNull()
        // v0.21.0 — 백그라운드 모니터의 최신 snapshot. 미수집 시 null → 카드가
        // "아직 정보 없음" 메시지로 graceful degrade.
        val claudeUsage = deps.claudeUsageMonitor.snapshot()
        // v0.29.0 — 디스크 monitor snapshot. 미측정이면 null → graceful.
        val diskSnapshot = deps.diskMonitor.snapshot()
        val html = AdminTemplates.dashboardPage(
            username = sess.username,
            status = status,
            deviceCount = deviceCount,
            runningBuilds = 0,
            claudeAuth = claudeAuth,
            claudeUsage = claudeUsage,
            diskSnapshot = diskSnapshot,
            csrf = sess.csrf,
        )
        call.respondText(html, ContentType.Text.Html)
    }

    // ── Setup ─────────────────────────────────────────────────────
    get("/setup") {
        if (deps.authService.adminExists()) {
            call.respondRedirect("/login")
            return@get
        }
        call.respondText(AdminTemplates.setupPage(), ContentType.Text.Html)
    }

    post("/setup") {
        if (deps.authService.adminExists()) {
            call.respondRedirect("/login")
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
        val ip = call.request.local.remoteHost
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
        deps.audit.setupAdmin(username, outcome.user.id, ip)
        setSessionCookie(call, outcome.token)
        call.respondRedirect("/")
    }

    // ── Login ─────────────────────────────────────────────────────
    get("/login") {
        if (!deps.authService.adminExists()) {
            call.respondRedirect("/setup")
            return@get
        }
        val next = call.request.queryParameters["next"]
        call.respondText(AdminTemplates.loginPage(next = next), ContentType.Text.Html)
    }

    post("/login") {
        if (!deps.authService.adminExists()) {
            call.respondRedirect("/setup")
            return@post
        }
        val params = call.receiveParameters()
        val username = params["username"]?.trim().orEmpty()
        val password = params["password"].orEmpty()
        val totpCode = params["totpCode"]?.trim()?.takeIf { it.isNotBlank() }
        // open-redirect 방지: 외부 도메인이나 `//` 로 시작하는 schemeless URL 차단
        val next = params["next"]?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"

        val ip = call.request.local.remoteHost
        val outcome = runCatching {
            deps.authService.login(
                username = username,
                password = password,
                deviceName = browserDeviceName(call),
                channel = "web",
                remoteIp = ip,
                totpCode = totpCode,
                hasPasskey = { uid -> deps.webauthnService.hasCredentials(uid) },
            )
        }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: "로그인 실패"
            val reasonCode = (e as? ApiException)?.code ?: "unknown"
            // v0.26.0 — TOTP 1단계 통과 → 같은 폼에 코드 입력 단계 노출.
            if (reasonCode == "totp_required") {
                call.respondText(
                    AdminTemplates.loginPage(next = next, totpUsername = username, totpPassword = password),
                    ContentType.Text.Html,
                    HttpStatusCode.OK,
                )
                return@post
            }
            deps.audit.loginFailure(username, ip, reasonCode)
            call.respondText(
                AdminTemplates.loginPage(
                    error = msg,
                    next = next,
                    // invalid_totp 면 2단계 폼에 머무름.
                    totpUsername = if (reasonCode == "invalid_totp") username else null,
                    totpPassword = if (reasonCode == "invalid_totp") password else null,
                ),
                ContentType.Text.Html,
                HttpStatusCode.Unauthorized,
            )
            return@post
        }
        deps.audit.loginSuccess(outcome.user.username, outcome.user.id, outcome.device.id, ip, "web")
        setSessionCookie(call, outcome.token)
        call.respondRedirect(next)
    }

    // ── Logout ────────────────────────────────────────────────────
    post("/logout") {
        // v0.12.4 — cookie + Bearer 둘 다 처리, 그리고 SSR(cookie) 의 경우 CSRF 검증.
        // Bearer 헤더 호출은 Authorization 자체가 SOP/CORS preflight 의 trigger 라
        // 외부 origin 에서 임의로 못 붙이므로 CSRF 불필요.
        val cookieToken = call.request.cookies[SESSION_COOKIE]
        if (!cookieToken.isNullOrBlank()) {
            requireCsrf()
        }
        val token = call.bearerTokenOrNull()
        val ip = call.request.local.remoteHost
        var userId: String? = null
        var deviceId: String? = null
        if (token != null) {
            val hash = com.siamakerlab.vibecoder.server.core.Sha256.hashString(token)
            deps.deviceRepo.findByTokenHash(hash)?.let { d ->
                userId = d.userId
                deviceId = d.id
                deps.deviceRepo.deleteById(d.id)
            }
        }
        deps.audit.logout(userId, deviceId, ip)
        clearSessionCookie(call)
        call.respondRedirect("/login")
    }

    // ── Dashboard 외 페이지 ───────────────────────────────────────

    get("/settings") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
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
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        // v0.77.0 — Phase 64 i18n. user.language (null = server default) + server default fallback.
        val user = deps.userRepo.findById(sess.userId)
        call.respondText(
            AdminTemplates.settingsPage(
                sess.username, view, flashOk = ok, flashErr = err, csrf = sess.csrf,
                lang = sess.language,
                userLanguage = user?.language,
                serverDefaultLanguage = cfg.i18n.defaultLanguage,
            ),
            ContentType.Text.Html,
        )
    }

    // v0.77.0 — Phase 64 i18n. 사용자 별 language 변경.
    // 빈 문자열 → null (서버 default 사용).
    post("/settings/language") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        val params = requireCsrf()
        val raw = params["language"]?.trim()?.ifBlank { null }
        if (raw != null && raw !in com.siamakerlab.vibecoder.server.i18n.Messages.SUPPORTED) {
            call.respondRedirect("/settings?err=invalid_language")
            return@post
        }
        deps.userRepo.setLanguage(sess.userId, raw)
        val msg = com.siamakerlab.vibecoder.server.i18n.Messages.t(
            com.siamakerlab.vibecoder.server.i18n.Messages.resolve(raw, deps.config.i18n.defaultLanguage),
            "settings.general.language.saved",
        )
        call.respondRedirect("/settings?ok=${java.net.URLEncoder.encode(msg, "UTF-8")}")
    }

    post("/settings") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val params = requireCsrf()

        // 파싱 — 부재/빈 값은 기존 값 유지
        val cur = deps.config
        val newConfig = try {
            cur.copy(
                server = cur.server.copy(
                    name = params["server.name"]?.trim()?.ifBlank { null } ?: cur.server.name,
                    port = params["server.port"]?.toIntOrNull()?.also {
                        require(it in 1..65535) { "port must be in 1..65535 (got $it)" }
                    } ?: cur.server.port,
                    host = params["server.host"]?.trim()?.ifBlank { null } ?: cur.server.host,
                ),
                workspace = cur.workspace.copy(
                    maxUploadSizeMb = params["workspace.maxUploadSizeMb"]?.toLongOrNull()?.also {
                        require(it in 1..10240) { "maxUploadSizeMb must be in 1..10240 (got $it)" }
                    } ?: cur.workspace.maxUploadSizeMb,
                    artifactKeepCount = params["workspace.artifactKeepCount"]?.toIntOrNull()?.also {
                        require(it in 1..1000) { "artifactKeepCount must be in 1..1000 (got $it)" }
                    } ?: cur.workspace.artifactKeepCount,
                ),
                claude = cur.claude.copy(
                    enabled = params["claude.enabled"] != null,  // checkbox: 존재 → true
                    path = params["claude.path"]?.trim()?.ifBlank { null } ?: cur.claude.path,
                    timeoutMinutes = params["claude.timeoutMinutes"]?.toIntOrNull()?.also {
                        require(it in 1..600) { "claude.timeoutMinutes must be in 1..600 (got $it)" }
                    } ?: cur.claude.timeoutMinutes,
                ),
                build = cur.build.copy(
                    timeoutMinutes = params["build.timeoutMinutes"]?.toIntOrNull()?.also {
                        require(it in 1..600) { "build.timeoutMinutes must be in 1..600 (got $it)" }
                    } ?: cur.build.timeoutMinutes,
                    defaultDebugTask = params["build.defaultDebugTask"]?.trim()?.ifBlank { null }
                        ?: cur.build.defaultDebugTask,
                ),
            )
        } catch (e: IllegalArgumentException) {
            call.respondRedirect("/settings?err=${java.net.URLEncoder.encode(e.message ?: "invalid", "UTF-8")}")
            return@post
        }

        val result = try {
            com.siamakerlab.vibecoder.server.config.ConfigPersistence.save(newConfig)
        } catch (e: Throwable) {
            log.error(e) { "config persist failed" }
            call.respondRedirect("/settings?err=${java.net.URLEncoder.encode("저장 실패: ${e.message}", "UTF-8")}")
            return@post
        }
        log.info { "settings persisted by ${sess.username} → ${result.targetPath} (backup=${result.backupPath})" }
        call.respondRedirect("/settings?ok=1")
    }

    get("/password") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        call.respondText(
            AdminTemplates.passwordPage(sess.username, csrf = sess.csrf),
            ContentType.Text.Html,
        )
    }

    post("/password") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        val params = requireCsrf()
        val current = params["currentPassword"].orEmpty()
        val new = params["newPassword"].orEmpty()
        val confirm = params["newPasswordConfirm"].orEmpty()
        if (new != confirm) {
            call.respondText(
                AdminTemplates.passwordPage(
                    sess.username, csrf = sess.csrf,
                    flashErr = "새 비밀번호 확인이 일치하지 않습니다.",
                ),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        val ip = call.request.local.remoteHost
        val result = runCatching {
            deps.authService.changePassword(sess.userId, current, new)
        }
        if (result.isFailure) {
            deps.audit.passwordChange(sess.userId, ip, ok = false)
            val msg = (result.exceptionOrNull() as? ApiException)?.message
                ?: "비밀번호 변경 실패"
            call.respondText(
                AdminTemplates.passwordPage(sess.username, csrf = sess.csrf, flashErr = msg),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        deps.audit.passwordChange(sess.userId, ip, ok = true)
        call.respondText(
            AdminTemplates.passwordPage(
                sess.username, csrf = sess.csrf,
                flashOk = "비밀번호가 변경되었습니다.",
            ),
            ContentType.Text.Html,
        )
    }

    get("/devices") {
        val sess = requireSessionOrRedirect(deps) ?: return@get
        val devices = deps.deviceRepo.listAll()
        val ok = call.request.queryParameters["ok"]?.let { "디바이스 토큰이 무효화되었습니다." }
        call.respondText(
            AdminTemplates.devicesPage(
                sess.username, devices, sess.deviceId,
                flashOk = ok, csrf = sess.csrf,
            ),
            ContentType.Text.Html,
        )
    }

    post("/devices/{id}/revoke") {
        val sess = requireSessionOrRedirect(deps) ?: return@post
        requireCsrf()
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
        deps.audit.deviceRevoke(sess.userId, id, call.request.local.remoteHost)
        call.respondRedirect("/devices?ok=1")
    }

    // ── 레거시 호환: /admin/* 경로는 항상 루트로 영구 리다이렉트 ─────
    // 북마크/구버전 안드로이드 앱 보호용. v0.6.0 에서 제거 예정.
    get("/admin{path...}") {
        val sub = call.parameters.getAll("path")?.joinToString("/") ?: ""
        val target = if (sub.isBlank()) "/" else "/$sub"
        call.respondRedirect(target, permanent = true)
    }
}

// ─── 세션 헬퍼 ─────────────────────────────────────────────────────────────

internal data class WebSession(
    val token: String,
    val userId: String,
    val username: String,
    val deviceId: String,
    /**
     * v0.12.4 — 같은 cookie token 으로부터 HMAC-derive 한 CSRF 토큰. 모든 SSR
     * 폼에 hidden `_csrf` input 으로 박아 보내고 POST 핸들러가 CsrfTokens.requireCsrf
     * 로 검증한다.
     */
    val csrf: String,
    /**
     * v0.37.0 — "admin" | "member". 관리 페이지 가드용.
     * v0.40.0 — "viewer" 추가. read-only.
     */
    val role: String = "admin",
    /**
     * v0.77.0 — Phase 64 i18n. 이미 [com.siamakerlab.vibecoder.server.i18n.Messages.resolve]
     * 로 fallback 처리된 최종 코드 ("en" 또는 "ko"). SSR 렌더 시 `t(key)` 의 1st arg.
     */
    val language: String = "en",
) {
    val isAdmin: Boolean get() = role == "admin"
    /** v0.40.0 — admin / member 만 write. viewer 는 read-only. */
    val canWrite: Boolean get() = role == "admin" || role == "member"
}

/** 세션 유효 시 WebSession, 아니면 적절한 곳으로 redirect 후 null 반환. */
internal suspend fun io.ktor.server.routing.RoutingContext.requireSessionOrRedirect(
    deps: AdminRoutesDeps,
): WebSession? {
    val token = call.request.cookies[SESSION_COOKIE]
    if (token.isNullOrBlank()) {
        if (!deps.authService.adminExists()) call.respondRedirect("/setup")
        else call.respondRedirect("/login?next=${call.request.local.uri}")
        return null
    }
    val hash = com.siamakerlab.vibecoder.server.core.Sha256.hashString(token)
    val device = deps.deviceRepo.findByTokenHash(hash)
    if (device == null || device.userId == null) {
        clearSessionCookie(call)
        call.respondRedirect("/login")
        return null
    }
    val user = deps.userRepo.findById(device.userId)
    if (user == null) {
        clearSessionCookie(call)
        call.respondRedirect("/login")
        return null
    }
    // v0.26.0 — idle timeout 검사 (security.sessionIdleTimeoutMinutes, 0=무제한).
    // SSR 흐름에서도 동일 정책 적용 → Bearer / cookie 양쪽이 같은 timeout.
    val idleMin = deps.config.security.sessionIdleTimeoutMinutes.coerceAtLeast(0)
    if (idleMin > 0 && device.lastSeenAt != null) {
        val ageMs = runCatching {
            java.time.Duration.between(java.time.Instant.parse(device.lastSeenAt), java.time.Instant.now()).toMillis()
        }.getOrNull()
        if (ageMs != null && ageMs > idleMin * 60_000L) {
            deps.deviceRepo.deleteById(device.id)
            clearSessionCookie(call)
            deps.audit.sessionTimeout(device.userId, device.id, call.request.local.remoteHost)
            call.respondRedirect("/login?err=session_timeout")
            return null
        }
    }
    deps.deviceRepo.touchLastSeen(device.id)
    return WebSession(
        token = token, userId = user.id, username = user.username, deviceId = device.id,
        csrf = com.siamakerlab.vibecoder.server.auth.CsrfTokens.tokenFor(token),
        role = user.role,
        language = com.siamakerlab.vibecoder.server.i18n.Messages.resolve(
            user.language, deps.config.i18n.defaultLanguage,
        ),
    )
}

/**
 * v0.37.0 — admin role 가드. Member 가 접근하면 dashboard 로 redirect (403 redirect).
 * `requireSessionOrRedirect` 다음에 chain 으로 사용.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.requireAdminOrRedirect(
    sess: WebSession,
): Boolean {
    if (sess.isAdmin) return true
    val msg = java.net.URLEncoder.encode("관리자 전용 페이지입니다.", Charsets.UTF_8)
    call.respondRedirect("/?err=$msg")
    return false
}

/**
 * v0.40.0 — write 권한 가드. viewer 는 read-only 라 거절.
 * POST 핸들러 (콘솔 prompt / 빌드 enqueue / git commit / settings 등) 에 chain.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.requireWriteAccessOrRedirect(
    sess: WebSession,
): Boolean {
    if (sess.canWrite) return true
    val msg = java.net.URLEncoder.encode("viewer 권한으로는 변경할 수 없습니다.", Charsets.UTF_8)
    call.respondRedirect("/?err=$msg")
    return false
}

/**
 * v0.49.0 — Project ACL guard. Admin bypasses. Non-admin must either have no ACL rows OR
 * an explicit grant for [projectId]. Used by every per-project SSR / mutating endpoint.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.requireProjectAccessOrRedirect(
    sess: WebSession,
    projects: com.siamakerlab.vibecoder.server.projects.ProjectService,
    projectId: String,
): Boolean {
    if (projects.canUserAccess(sess.userId, sess.isAdmin, projectId)) return true
    val msg = java.net.URLEncoder.encode("이 프로젝트에 대한 권한이 없습니다.", Charsets.UTF_8)
    call.respondRedirect("/projects?err=$msg")
    return false
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
