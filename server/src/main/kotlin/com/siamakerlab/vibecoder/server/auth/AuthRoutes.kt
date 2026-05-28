package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.audit.AuditLogger
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ChangePasswordRequestDto
import com.siamakerlab.vibecoder.shared.dto.LoginRequestDto
import com.siamakerlab.vibecoder.shared.dto.LoginResponseDto
import com.siamakerlab.vibecoder.shared.dto.MeDto
import com.siamakerlab.vibecoder.shared.dto.PairRequestDto
import com.siamakerlab.vibecoder.shared.dto.PairResponseDto
import com.siamakerlab.vibecoder.shared.dto.SetupRequestDto
import com.siamakerlab.vibecoder.shared.dto.SetupStatusDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.authRoutes(
    serverName: String,
    pairing: PairingCodeStore,
    tokens: TokenService,
    deviceRepo: DeviceRepository,
    userRepo: AdminUserRepository,
    authService: AuthService,
    audit: AuditLogger,
    /** v0.57.0 — passwordless-only check via hasCredentials lookup. */
    webauthn: WebauthnService,
) {
    // ── 신규 통합 인증 (v0.4.0+) ──────────────────────────────────────────

    // 첫 admin 존재 여부 (브라우저가 /admin 진입 시 setup vs login 분기용)
    get(ApiPath.AUTH_SETUP_STATUS) {
        call.respond(SetupStatusDto(adminExists = authService.adminExists()))
    }

    // 첫 admin 생성 (admin 없을 때만)
    post(ApiPath.AUTH_SETUP) {
        val body = call.receive<SetupRequestDto>()
        val ip = call.request.origin.remoteHost
        val outcome = authService.setup(
            username = body.username,
            password = body.password,
            deviceName = body.deviceName ?: "setup-client",
            channel = "app",
        )
        audit.setupAdmin(body.username, outcome.user.id, ip)
        call.respond(
            HttpStatusCode.Created,
            LoginResponseDto(
                token = outcome.token,
                deviceId = outcome.device.id,
                serverName = serverName,
                username = outcome.user.username,
            )
        )
    }

    // 로그인 (페어링 대체)
    post(ApiPath.AUTH_LOGIN) {
        val body = call.receive<LoginRequestDto>()
        val ip = call.request.origin.remoteHost
        val outcome = try {
            authService.login(
                username = body.username,
                password = body.password,
                deviceName = body.deviceName ?: "unknown",
                channel = "app",
                remoteIp = ip,
                totpCode = body.totpCode,
                hasPasskey = { uid -> webauthn.hasCredentials(uid) },
            )
        } catch (e: ApiException) {
            // totp_required 는 정상적인 2단계 진행 신호 — audit 에 fail 로 남기지 않음.
            if (e.code != "totp_required") {
                audit.loginFailure(body.username, ip, e.code)
            }
            throw e
        }
        audit.loginSuccess(outcome.user.username, outcome.user.id, outcome.device.id, ip, "app")
        call.respond(
            HttpStatusCode.OK,
            LoginResponseDto(
                token = outcome.token,
                deviceId = outcome.device.id,
                serverName = serverName,
                username = outcome.user.username,
            )
        )
    }

    // 비밀번호 변경 (인증 필요)
    authenticate(AUTH_BEARER) {
        post(ApiPath.AUTH_PASSWORD) {
            val p = call.requireDevice()
            val userId = p.device.userId
                ?: throw ApiException.localized(403, "no_user_link", messageKey = "api.auth.noUserLink")
            val body = call.receive<ChangePasswordRequestDto>()
            authService.changePassword(userId, body.currentPassword, body.newPassword)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ── 레거시 페어링 (deprecated) ────────────────────────────────────────
    // v0.4.0: admin이 없을 때만 허용 (백워드 호환). admin 생성 후엔 401.

    post(ApiPath.AUTH_PAIR) {
        if (authService.adminExists()) {
            throw ApiException.localized(410, "pairing_deprecated", messageKey = "api.auth.pairingDeprecated")
        }
        val body = call.receive<PairRequestDto>()
        if (body.pairingCode.isBlank() || body.deviceName.isBlank()) {
            throw ApiException.localized(400, "bad_request", messageKey = "api.auth.pairBadRequest")
        }
        if (!pairing.tryConsume(body.pairingCode)) {
            throw ApiException.localized(401, "invalid_pairing_code", messageKey = "api.auth.invalidPairing")
        }
        val issued = tokens.issue()
        val device = deviceRepo.insert(
            id = Ids.deviceId(),
            name = body.deviceName,
            tokenHash = issued.tokenHash,
        )
        pairing.rotate()
        call.respond(
            HttpStatusCode.OK,
            PairResponseDto(token = issued.token, deviceId = device.id, serverName = serverName)
        )
    }

    authenticate(AUTH_BEARER) {
        get(ApiPath.AUTH_ME) {
            val p = call.requireDevice()
            val username = p.device.userId?.let { userRepo.findById(it)?.username }
            call.respond(
                MeDto(
                    deviceId = p.device.id,
                    deviceName = p.device.name,
                    username = username,
                )
            )
        }
        post(ApiPath.AUTH_LOGOUT) {
            val p = call.requireDevice()
            deviceRepo.deleteById(p.device.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
