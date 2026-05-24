package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRow
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

const val AUTH_BEARER = "bearer"

/** 웹 세션 쿠키 이름. Bearer 토큰과 동일한 문자열을 담는다. */
const val SESSION_COOKIE = "vibe_session"

/**
 * Authenticated request principal.
 *
 * - [device] is the resolved device row (every Bearer token maps to one device).
 * - [userRole] (v0.45.0+) is the owning user's role (`admin` / `member` / `viewer`) when the
 *   device is bound to a user. `null` for legacy / unbound devices — treated as `admin` for
 *   backward compatibility so existing automation doesn't break on upgrade.
 */
data class DevicePrincipal(
    val device: DeviceRow,
    val userRole: String? = null,
) {
    /** v0.45.0 — `admin` only. Mirrors SSR `WebSession.isAdmin`. */
    val isAdmin: Boolean get() = (userRole ?: "admin") == "admin"

    /**
     * v0.45.0 — write capability check. `admin` and `member` may mutate state; `viewer` is
     * read-only. Used by JSON API + WebSocket role guards.
     */
    val canWrite: Boolean get() {
        val r = userRole ?: "admin"
        return r == "admin" || r == "member"
    }
}

fun Application.installAuth(
    deviceRepo: DeviceRepository,
    tokens: TokenService,
    /**
     * v0.26.0 — idle timeout 분. 0 = 무제한. provider 형태라 런타임에 /settings 로 바뀌어도 즉시 반영.
     */
    idleTimeoutMinutesProvider: () -> Int = { 0 },
    /**
     * v0.45.0 — user role lookup. Optional so unit tests can omit it. When provided every
     * authenticated request resolves to its user's role, exposed via [DevicePrincipal.userRole].
     */
    userRepo: AdminUserRepository? = null,
) {
    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "vibe-coder"
            // 표준 Bearer 헤더 외에 `vibe_session` 쿠키도 토큰 운반 경로로 인정.
            // 같은 토큰이 두 경로 어느 쪽으로 와도 인증된다.
            authHeader { call ->
                val header = call.request.header("Authorization")
                if (!header.isNullOrBlank()) {
                    return@authHeader io.ktor.http.auth.parseAuthorizationHeader(header)
                }
                val cookieToken = call.request.cookies[SESSION_COOKIE]
                if (!cookieToken.isNullOrBlank()) {
                    return@authHeader io.ktor.http.auth.HttpAuthHeader.Single("Bearer", cookieToken)
                }
                null
            }
            authenticate { creds ->
                val raw = creds.token
                if (raw.isBlank()) return@authenticate null
                val hash = tokens.hashOf(raw)
                val device = deviceRepo.findByTokenHash(hash) ?: return@authenticate null

                // v0.26.0 — idle timeout 검사. lastSeenAt 가 N 분 이전이면 토큰 폐기.
                val idleMin = idleTimeoutMinutesProvider().coerceAtLeast(0)
                if (idleMin > 0) {
                    val lastSeen = device.lastSeenAt
                    if (lastSeen != null) {
                        val ageMs = runCatching {
                            val last = java.time.Instant.parse(lastSeen)
                            java.time.Duration.between(last, java.time.Instant.now()).toMillis()
                        }.getOrNull()
                        if (ageMs != null && ageMs > idleMin * 60_000L) {
                            // Idle 초과 → 토큰 자동 폐기 + 인증 거절. 클라이언트는 401 받고
                            // 재로그인 (cookie/Bearer 둘 다 해당).
                            deviceRepo.deleteById(device.id)
                            return@authenticate null
                        }
                    }
                }

                deviceRepo.touchLastSeen(device.id)
                val role = device.userId
                    ?.let { uid -> runCatching { userRepo?.findById(uid)?.role }.getOrNull() }
                DevicePrincipal(device = device, userRole = role)
            }
        }
    }
}

fun ApplicationCall.bearerTokenOrNull(): String? {
    val header = request.header("Authorization")
    if (!header.isNullOrBlank() && header.startsWith("Bearer ", ignoreCase = true)) {
        return header.removePrefix("Bearer ").trim().ifBlank { null }
    }
    return request.cookies[SESSION_COOKIE]?.ifBlank { null }
}

fun ApplicationCall.requireDevice(): DevicePrincipal =
    principal<DevicePrincipal>() ?: throw com.siamakerlab.vibecoder.server.error.ApiException(
        statusCode = 401, code = "unauthorized", message = "missing or invalid Bearer token"
    )

/**
 * v0.45.0 — JSON API write guard. Rejects viewer tokens with `403 viewer_readonly`.
 * Apply to every state-mutating endpoint (POST/PUT/DELETE) that didn't previously have its
 * own role check. SSR side keeps using `requireWriteAccessOrRedirect`.
 */
fun ApplicationCall.requireApiWrite(): DevicePrincipal {
    val p = requireDevice()
    if (!p.canWrite) {
        throw com.siamakerlab.vibecoder.server.error.ApiException(
            statusCode = 403, code = "viewer_readonly",
            message = "viewer role cannot mutate resources",
        )
    }
    return p
}

/**
 * v0.45.0 — admin-only JSON API guard. Used for server-wide management endpoints
 * (users, audit, backup, settings, etc).
 */
fun ApplicationCall.requireApiAdmin(): DevicePrincipal {
    val p = requireDevice()
    if (!p.isAdmin) {
        throw com.siamakerlab.vibecoder.server.error.ApiException(
            statusCode = 403, code = "admin_only",
            message = "this endpoint requires admin role",
        )
    }
    return p
}
