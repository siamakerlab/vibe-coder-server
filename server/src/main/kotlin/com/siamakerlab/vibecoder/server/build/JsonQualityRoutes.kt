package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.LintIssueDto
import com.siamakerlab.vibecoder.shared.dto.LintResultDto
import com.siamakerlab.vibecoder.shared.dto.QualityFixRequestDto
import com.siamakerlab.vibecoder.shared.dto.QualityFixResponseDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.119.0 — 품질·접근성 검사 JSON API (Bearer 토큰 인증). SSR `qualityRoutes`
 * (`/projects/{id}/quality`)의 **Lint(정적 분석)** 부분을 `/api/...` 로 노출(android `/quality`).
 *
 * 인스트루먼트 테스트(connectedDebugAndroidTest)는 에뮬레이터(서버측 화면) 의존이라 제외.
 * Lint 실행은 gradle 프로세스를 spawn 하는 write 성 작업 → [requireApiWrite].
 */
fun Routing.jsonQualityRoutes(
    projects: ProjectService,
    svc: LintQualityService,
    sessionManager: ClaudeSessionManager,
) {
    authenticate(AUTH_BEARER) {
        // Lint(:module:lintDebug) 실행 + 결과. 동기 실행(수십 초~수 분) — 클라가 로딩 표시.
        post("/api/projects/{projectId}/quality/lint") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val module = safeModule(call.request.queryParameters["module"], "app")
            // lint 는 gradle 프로세스를 spawn 해 수십 초~수 분 블로킹 → Ktor 워커 스레드
            // 점유 방지 위해 IO 디스패처로 분리(P1-1 정밀재검).
            val result = withContext(Dispatchers.IO) { svc.lint(projectId, module) }
            call.respond(
                LintResultDto(
                    ok = result.ok,
                    moduleName = result.moduleName,
                    durationMs = result.durationMs,
                    issues = result.issues.map {
                        LintIssueDto(
                            id = it.id, category = it.category, severity = it.severity,
                            message = it.message, file = it.file, line = it.line,
                        )
                    },
                    errorMessage = result.errorMessage,
                    rawTail = result.rawTail,
                ),
            )
        }

        // 선택 lint 이슈 → 콘솔(Claude 세션)로 수정요청 전송. 진행은 콘솔 WS 로.
        post("/api/projects/{projectId}/quality/fix") {
            call.requireApiWrite()
            val projectId = call.parameters["projectId"]
                ?: throw ApiException.localized(400, "bad_request", messageKey = "api.common.projectIdRequired")
            call.requireProjectAcl(projects, projectId)
            val req = call.receive<QualityFixRequestDto>()
            val module = safeModule(req.module, "app")
            val selected = req.selected.filter { it.isNotBlank() }
            if (selected.isEmpty()) {
                throw ApiException.localized(400, "no_selection", messageKey = "api.quality.noSelection")
            }
            val prompt = if (req.kind == "test") buildTestFixPrompt(module, selected)
                         else buildLintFixPrompt(module, selected)
            sessionManager.sendPrompt(projectId, prompt)
            call.respond(HttpStatusCode.Accepted, QualityFixResponseDto(sent = selected.size))
        }
    }
}
