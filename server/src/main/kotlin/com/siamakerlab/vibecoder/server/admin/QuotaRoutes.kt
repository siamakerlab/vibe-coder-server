package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val log = KotlinLogging.logger {}

/**
 * v1.3.2 — 전역 (계정 단위) Claude 쿼타 endpoint.
 *
 * Claude 쿼타는 프로젝트 단위가 아니라 사용자 계정 단위 (5h 세션 + 7d 주간) 라
 * 어떤 프로젝트의 status 든 같은 값. SSR 사이드바 / Android 헤더 등 전역 UI 가
 * 단일 endpoint 로 가져갈 수 있도록 노출.
 *
 *   GET /api/server/quota → ClaudeStatusDto (scratch 프로젝트 snapshot)
 *
 * 60s cache 적용 (ClaudeStatusService 내부) 라 부담 작음.
 */
fun Routing.quotaRoutes(claudeStatus: ClaudeStatusService) {
    // v1.52.0 — 25차 보안점검: 미인증 노출(model/plan/usage% 정보 누설) 회수 → 인증 요구.
    // 사이드바 pill 은 fetch(credentials:'same-origin') 로 vibe_session 쿠키를 보내 인증됨.
    authenticate(AUTH_BEARER) {
        get(ApiPath.SERVER_QUOTA) {
            call.requireDevice() // 인증만 강제(단일 admin → 추가 role 불필요)
            // v1.46.0 — 비차단 캐시-온리. 캡처는 백그라운드 ClaudeUsageMonitor 가 수행 → 즉시 반환.
            call.respond(claudeStatus.cachedSnapshot(ProjectService.SCRATCH_ID))
        }
    }
}
