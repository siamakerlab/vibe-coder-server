package com.siamakerlab.vibecoder.server.auth

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
) {
    // ── 신규 통합 인증 (v0.4.0+) ──────────────────────────────────────────

    // 첫 admin 존재 여부 (브라우저가 /admin 진입 시 setup vs login 분기용)
    get(ApiPath.AUTH_SETUP_STATUS) {
        call.respond(SetupStatusDto(adminExists = authService.adminExists()))
    }

    // 첫 admin 생성 (admin 없을 때만)
    post(ApiPath.AUTH_SETUP) {
        val body = call.receive<SetupRequestDto>()
        val outcome = authService.setup(
            username = body.username,
            password = body.password,
            deviceName = body.deviceName ?: "setup-client",
            channel = "app",
        )
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
        val outcome = authService.login(
            username = body.username,
            password = body.password,
            deviceName = body.deviceName ?: "unknown",
            channel = "app",
        )
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
                ?: throw ApiException(403, "no_user_link", "이 토큰은 admin 계정과 연결되어 있지 않습니다.")
            val body = call.receive<ChangePasswordRequestDto>()
            authService.changePassword(userId, body.currentPassword, body.newPassword)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ── 레거시 페어링 (deprecated) ────────────────────────────────────────
    // v0.4.0: admin이 없을 때만 허용 (백워드 호환). admin 생성 후엔 401.

    post(ApiPath.AUTH_PAIR) {
        if (authService.adminExists()) {
            throw ApiException(
                410, "pairing_deprecated",
                "페어링 방식은 deprecated되었습니다. /api/auth/login 을 사용하세요."
            )
        }
        val body = call.receive<PairRequestDto>()
        if (body.pairingCode.isBlank() || body.deviceName.isBlank()) {
            throw ApiException(400, "bad_request", "deviceName and pairingCode are required")
        }
        if (!pairing.tryConsume(body.pairingCode)) {
            throw ApiException(401, "invalid_pairing_code", "pairing code invalid or expired")
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
