package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.FcmTokenRegisterRequestDto
import com.siamakerlab.vibecoder.shared.dto.NotificationAckRequestDto
import com.siamakerlab.vibecoder.shared.dto.NotificationsResponseDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * v0.68.0 — Phase 47. Polling-based notification system.
 *
 * 모든 인증 사용자가 호출 가능 (viewer/member/admin). [requireDevice] 통과.
 * Project ACL 검증은 emit 시점에서 처리 (사용자가 자기 ACL 외 project 알림은 받지 않음).
 *
 * FCM token 등록은 Firebase 설정 시만 의미 — endpoint 자체는 항상 200 OK 반환.
 */
fun Routing.notificationRoutes(svc: NotificationService) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.NOTIFICATIONS) {
            val device = call.requireDevice().device
            val list = svc.list(device.userId)
            call.respond(NotificationsResponseDto(
                events = list,
                unreadTotal = svc.count(device.userId),
            ))
        }
        post(ApiPath.NOTIFICATIONS_ACK) {
            val device = call.requireDevice().device
            val req = call.receive<NotificationAckRequestDto>()
            svc.ack(device.userId, req.ids)
            call.respond(HttpStatusCode.NoContent)
        }
        post(ApiPath.FCM_TOKEN_REGISTER) {
            val device = call.requireDevice().device
            val req = call.receive<FcmTokenRegisterRequestDto>()
            // v0.68.0 stub — Firebase 설정 (FCM_PROJECT_ID + service account) 시 활성화.
            // 미설정 시엔 token 만 로그 — 외부 push 발송 없음.
            log.info { "FCM token registered (stub): userId=${device.userId} deviceId=${req.deviceId} tokenLen=${req.token.length}" }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
