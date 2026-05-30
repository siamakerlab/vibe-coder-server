package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.call
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
    get(ApiPath.SERVER_QUOTA) {
        // v1.46.0 — 비차단 캐시-온리. 캡처(usage 갱신)는 백그라운드 ClaudeUsageMonitor 가
        // 주기적으로 수행하므로 이 요청은 즉시 마지막 캐시(또는 light DTO)를 반환한다.
        // 이전엔 캐시 미스 시 동기 TUI 캡처를 호출해 quota 가 25~80s hang 했음(근본 회수).
        call.respond(claudeStatus.cachedSnapshot(ProjectService.SCRATCH_ID))
    }
}
