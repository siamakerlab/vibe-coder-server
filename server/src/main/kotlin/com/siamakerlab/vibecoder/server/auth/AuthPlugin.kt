package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRow
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.Principal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

const val AUTH_BEARER = "bearer"

/** 웹 세션 쿠키 이름. Bearer 토큰과 동일한 문자열을 담는다. */
const val SESSION_COOKIE = "vibe_session"

data class DevicePrincipal(val device: DeviceRow) : Principal

fun Application.installAuth(deviceRepo: DeviceRepository, tokens: TokenService) {
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
                deviceRepo.touchLastSeen(device.id)
                DevicePrincipal(device)
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
