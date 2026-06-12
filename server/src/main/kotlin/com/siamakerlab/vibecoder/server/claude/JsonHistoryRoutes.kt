package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiAdmin
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRow
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.HistoryAgentFilter
import com.siamakerlab.vibecoder.shared.dto.HistoryImportResponseDto
import com.siamakerlab.vibecoder.shared.dto.HistoryPageDto
import com.siamakerlab.vibecoder.shared.dto.HistorySearchHitDto
import com.siamakerlab.vibecoder.shared.dto.HistorySearchResponseDto
import com.siamakerlab.vibecoder.shared.dto.HistoryTurnDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream

/**
 * v0.64.0 — Phase 43. JSON variant of history endpoints (Bearer 토큰 인증).
 *
 * 기존 SSR `/projects/{id}/history` / `/chat/history` / `/history` /
 * `/projects/{id}/history/export` / `/projects/{id}/history/import` 는 그대로 유지.
 * 본 라우터는 같은 정보를 JSON 으로 노출하는 별도 path (`/api/...`) 를 추가하고,
 * 모두 Bearer 토큰 (또는 cookie 세션 토큰) 인증 통과 — CSRF 검증 없음.
 *
 * 페이징은 단순 page-based — `page=N` (0-base) query 로 100개씩. `nextCursor`
 * 는 다음 page 번호 (string) 또는 null (마지막).
 *
 * Project ACL: project-scoped endpoint 는 [requireProjectAcl] 통과.
 * Bearer 인증 client 가 자기 ACL 외 프로젝트 호출하면 403 project_forbidden.
 *
 * Chat history 는 scratch project (`__scratch__`) 의 history 로 매핑.
 * Cross-search 는 모든 프로젝트 가로지름 — admin 만 허용 (cross-tenant 보안).
 *
 * Export / Import 는 ConversationExportService 를 그대로 재사용 — SSR 과 동일
 * 로직, 응답만 JSON 으로.
 */
fun Routing.jsonHistoryRoutes(
    projects: ProjectService,
    repo: ConversationTurnRepository,
    exportService: ConversationExportService,
) {
    authenticate(AUTH_BEARER) {

        // ── 프로젝트별 history ──────────────────────────────────────────
        // Note: 라우터 등록은 hardcoded path template. ApiPath.projectHistory(...) 는 client 호출용.
        get("/api/projects/{projectId}/history") {
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            // 존재 검사 — 모르는 projectId 면 404.
            runCatching { projects.get(pid) }.getOrElse {
                throw ApiException.localized(404, "project_not_found", messageKey = "api.common.projectNotFound", args = listOf(pid))
            }
            call.respond(call.loadHistoryPage(repo,pid))
        }

        // ── Chat (scratch project) history ──────────────────────────────
        get(ApiPath.CHAT_HISTORY) {
            call.requireDevice()
            val scratch = projects.ensureScratchProject()
            call.respond(call.loadHistoryPage(repo,scratch.id))
        }

        // ── Cross-project search (admin only) ──────────────────────────
        get(ApiPath.HISTORY_SEARCH_JSON) {
            call.requireApiAdmin()
            val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
            val role = call.request.queryParameters["role"]?.trim()?.ifBlank { null }
            val rows = if (q == null) emptyList() else globalSearchAll(q, role, limit = 200)
            val hits = rows.map { r ->
                HistorySearchHitDto(
                    projectId = r.projectId,
                    sessionId = r.sessionId,
                    turnId = r.id,
                    ts = r.ts,
                    role = r.role,
                    preview = excerpt(r.content, q ?: "", radius = 150),
                )
            }
            call.respond(HistorySearchResponseDto(hits = hits))
        }

        // ── Export (JSON envelope, write 권한) ─────────────────────────
        get("/api/projects/{projectId}/history/export") {
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val json = exportService.exportProject(pid)
            val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now())
            val fname = "$pid-conversation-$ts.json"
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fname\"")
            call.respondText(json, ContentType.Application.Json)
        }

        // ── Import (multipart, write 권한) ─────────────────────────────
        post("/api/projects/{projectId}/history/import") {
            val pid = call.parameters["projectId"]!!
            call.requireApiWrite()
            call.requireProjectAcl(projects, pid)
            val dryRun = call.request.queryParameters["dryRun"] != "false"
            var jsonText: String? = null
            call.receiveMultipart().forEachPart { part ->
                if (part is PartData.FileItem && jsonText == null) {
                    jsonText = part.provider().toInputStream()
                        .bufferedReader().readText()
                        .take(5 * 1024 * 1024)
                }
                part.dispose()
            }
            val body = jsonText
                ?: throw ApiException.localized(400, "empty", messageKey = "api.envSetup.emptyFile")
            val result = exportService.importToProject(pid, body, dryRun = dryRun)
            call.respond(HistoryImportResponseDto(
                accepted = result.accepted,
                skipped = result.skipped,
                warnings = result.warnings,
                dryRun = result.dryRun,
            ))
        }
    }
}

/**
 * `page=N` (default 0) + `limit` (default 100, max 500) + filters 로 한 페이지.
 * 다음 page 가 더 있으면 `nextCursor=(page+1).toString()`.
 */
private fun io.ktor.server.application.ApplicationCall.loadHistoryPage(
    repo: ConversationTurnRepository,
    projectId: String,
): HistoryPageDto = run {
    val params = request.queryParameters
    val limit = (params["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
    // `before` 가 정수면 page 로 해석. 후속 cycle 에서 cursor-by-id 로 교체 가능.
    val page = (params["before"]?.toIntOrNull()
        ?: params["page"]?.toIntOrNull()
        ?: 0).coerceAtLeast(0)
    val sessionId = params["sessionId"]?.ifBlank { null } ?: params["session"]?.ifBlank { null }
    val agentParam = params["agent"]?.ifBlank { null }
    val agentName = when (agentParam) {
        null, HistoryAgentFilter.MAIN -> null    // null filter = main only (DB IS NULL)
        HistoryAgentFilter.ALL -> ""             // empty filter = all turns
        else -> agentParam.removePrefix("@")
    }
    val starredOnly = params["starred"]?.equals("true", ignoreCase = true) == true
        || params["starred"] == "1"
    val filter = ConversationTurnRepository.Filter(
        projectId = projectId,
        sessionId = sessionId,
        agentName = agentName,
        starredOnly = starredOnly,
    )
    val rows = repo.list(filter, limit = limit, offset = page.toLong() * limit.toLong())
    val total = repo.count(filter)
    val nextOffset = (page + 1).toLong() * limit.toLong()
    val hasNext = nextOffset < total
    HistoryPageDto(
        turns = rows.map { it.toHistoryTurnDto() },
        nextCursor = if (hasNext) (page + 1).toString() else null,
    )
}

internal fun ConversationTurnRow.toHistoryTurnDto(): HistoryTurnDto = HistoryTurnDto(
    id = id,
    sessionId = sessionId,
    turnIdx = turnIdx,
    ts = ts,
    role = role,
    content = content,
    toolName = toolName,
    toolUseId = toolUseId,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    agentName = agentName,
    userMemo = userMemo,
    starred = starred,
    // v1.138.0 — user turn 첨부 이미지 수(raw 파싱). 클라이언트가 [image] 마커 + 탭 시
    // /claude/console/image?turn&idx 로 로드. user 외 role 은 0.
    imageCount = if (role == "user") ConsoleImages.fromUserRaw(raw).size else 0,
)

/**
 * Match 위치 주변만 발췌해서 wire 크기를 줄임. SSR `highlightMatch` 가 HTML 을
 * 만드는 것과 달리 JSON 응답이므로 raw 텍스트 그대로 (클라이언트가 highlight).
 */
private fun excerpt(content: String, query: String, radius: Int): String {
    if (query.isBlank()) return content.take(radius * 2)
    val idx = content.indexOf(query, ignoreCase = true)
    if (idx < 0) return content.take(radius * 2)
    val from = (idx - radius).coerceAtLeast(0)
    val to = (idx + query.length + radius).coerceAtMost(content.length)
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < content.length) "…" else ""
    return prefix + content.substring(from, to) + suffix
}
