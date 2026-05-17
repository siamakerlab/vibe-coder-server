package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRow
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.Principal
import io.ktor.server.auth.UnauthorizedResponse
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

const val AUTH_BEARER = "bearer"

data class DevicePrincipal(val device: DeviceRow) : Principal

fun Application.installAuth(deviceRepo: DeviceRepository, tokens: TokenService) {
    install(Authentication) {
        bearer(AUTH_BEARER) {
            realm = "vibe-coder"
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
    val header = request.header("Authorization") ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.removePrefix("Bearer ").trim().ifBlank { null }
}

fun ApplicationCall.requireDevice(): DevicePrincipal =
    principal<DevicePrincipal>() ?: throw com.siamakerlab.vibecoder.server.error.ApiException(
        statusCode = 401, code = "unauthorized", message = "missing or invalid Bearer token"
    )
