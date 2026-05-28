package com.siamakerlab.vibecoder.server.security

import com.siamakerlab.vibecoder.server.auth.SESSION_COOKIE
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.metrics.MetricsRegistry
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelinePhase

private val log = KotlinLogging.logger {}

/**
 * v0.56.0 — Phase 35 per-IP rate-limit interceptor.
 *
 * Applied to:
 *   - /api/ paths        — every JSON endpoint
 *   - /ws/ paths         — WebSocket handshakes
 *   - /login POST        — extra-strict bucket against credential stuffing
 *
 * Skipped:
 *   - /static/ / /setup / /health / SSR pages (cookie-authenticated humans
 *     hammering the UI shouldn't trip this)
 *   - admin Bearer / cookie session (verified via the same path
 *     [com.siamakerlab.vibecoder.server.auth.installAuth] uses)
 *
 * Returns `429 Too Many Requests` with `Retry-After: <seconds>` header on rejection.
 */
fun Application.installRateLimit(
    api: RateLimiter,
    auth: RateLimiter,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    userRepo: AdminUserRepository,
    metrics: MetricsRegistry,
) {
    val phase = PipelinePhase("RateLimit")
    intercept(ApplicationCallPipeline.Plugins) {
        // 위 phase 가 같은 코루틴에서 한 번만 실행되므로 별도 setup 없이 inline.
    }
    insertPhaseBefore(ApplicationCallPipeline.Plugins, phase)
    intercept(phase) {
        val path = call.request.path()
        if (!shouldThrottle(path)) return@intercept

        val ip = call.request.origin.remoteHost.takeIf { it.isNotBlank() }
            ?: return@intercept
        val isAdmin = resolveIsAdmin(call, deviceRepo, tokens, userRepo)
        // Pick the appropriate bucket — auth path is much stricter.
        val limiter = if (path == "/login" || path == "/api/auth/login") auth else api
        val rejection = limiter.tryAcquire(ip, isAdmin = isAdmin)
        if (rejection != null) {
            metrics.inc("vibe_rate_limit_429_total", "Rate-limit rejection responses",
                labels = mapOf("path_bucket" to if (limiter === auth) "auth" else "api"))
            call.response.header("Retry-After", rejection.retryAfterSeconds.toString())
            call.respondText(
                """{"code":"rate_limited","message":"too many requests","retryAfter":${rejection.retryAfterSeconds}}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            finish()
        }
    }
}

private fun shouldThrottle(path: String): Boolean {
    if (path.startsWith("/api/")) return true
    if (path.startsWith("/ws/")) return true
    if (path == "/login") return true  // SSR form POST
    return false
}

/**
 * Best-effort admin check. We re-resolve the device → user.role from the cookie / Bearer
 * because the standard [installAuth] interceptor hasn't run yet (this phase sits earlier).
 * Unknown / unauthenticated tokens get throttled normally — that's the safer default.
 */
private fun resolveIsAdmin(
    call: io.ktor.server.application.ApplicationCall,
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    userRepo: AdminUserRepository,
): Boolean {
    val raw = call.request.cookies[SESSION_COOKIE]?.takeIf { it.isNotBlank() }
        ?: call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
        ?: return false
    val device = runCatching { deviceRepo.findByTokenHash(tokens.hashOf(raw)) }.getOrNull()
        ?: return false
    val uid = device.userId ?: return false
    val role = runCatching { userRepo.findById(uid)?.role }.getOrNull()
    return role == "admin"
}
