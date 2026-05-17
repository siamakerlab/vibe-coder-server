package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.MeDto
import com.siamakerlab.vibecoder.shared.dto.PairRequestDto
import com.siamakerlab.vibecoder.shared.dto.PairResponseDto
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
) {
    // OPEN — pair endpoint
    post(ApiPath.AUTH_PAIR) {
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
        // Auto-rotate so a fresh code is ready for the next device.
        pairing.rotate()
        call.respond(
            HttpStatusCode.OK,
            PairResponseDto(token = issued.token, deviceId = device.id, serverName = serverName)
        )
    }

    authenticate(AUTH_BEARER) {
        get(ApiPath.AUTH_ME) {
            val p = call.requireDevice()
            call.respond(MeDto(deviceId = p.device.id, deviceName = p.device.name))
        }
        post(ApiPath.AUTH_LOGOUT) {
            // Stateless tokens — MVP simply responds 204. (Hard revoke is out of scope.)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
