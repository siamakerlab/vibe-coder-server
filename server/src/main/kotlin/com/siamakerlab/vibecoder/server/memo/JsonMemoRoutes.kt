package com.siamakerlab.vibecoder.server.memo

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.MemoRepository
import com.siamakerlab.vibecoder.server.repo.MemoRow
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.MemoCreateRequestDto
import com.siamakerlab.vibecoder.shared.dto.MemoDto
import com.siamakerlab.vibecoder.shared.dto.MemoListResponseDto
import com.siamakerlab.vibecoder.shared.dto.MemoMutationAckDto
import com.siamakerlab.vibecoder.shared.dto.MemoUpdateRequestDto
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json

/**
 * v1.91.0 — 독립 메모 (전역/프로젝트별) JSON API.
 *
 * - GET  [ApiPath.MEMOS]            — `?projectId=X` 면 전역 + 프로젝트 X 메모, 없으면 전체.
 * - POST [ApiPath.MEMOS]            — [MemoCreateRequestDto] 로 생성 (write 권한).
 * - PUT  /api/memos/{memoId}        — [MemoUpdateRequestDto] 로 본문/scope 수정 (write 권한).
 * - DELETE /api/memos/{memoId}      — 삭제 (write 권한).
 *
 * Bearer 토큰 또는 cookie 세션 토큰 인증(AUTH_BEARER 가 양쪽 수용). 단일 admin 운영이라
 * Project ACL 검증은 생략 — projectId 가 주어지면 존재 검사만(없으면 404).
 * CSRF 는 다른 `/api` 하위 Bearer 라우트와 동일하게 미적용.
 */
fun Routing.jsonMemoRoutes(
    projects: ProjectService,
    repo: MemoRepository,
) {
    val json = Json { ignoreUnknownKeys = true }

    authenticate(AUTH_BEARER) {

        // ── 목록 ────────────────────────────────────────────────────────
        get(ApiPath.MEMOS) {
            call.requireDevice()
            val pid = call.request.queryParameters["projectId"]?.trim()?.ifBlank { null }
            val rows = if (pid != null) repo.listForScope(pid) else repo.listAll()
            call.respond(MemoListResponseDto(memos = rows.map { it.toDto() }))
        }

        // ── 생성 ────────────────────────────────────────────────────────
        post(ApiPath.MEMOS) {
            call.requireApiWrite()
            val req = decode<MemoCreateRequestDto>(json, call.receiveText())
            val pid = req.projectId?.trim()?.ifBlank { null }
            ensureProjectExists(projects, pid)
            val content = req.content.trim()
            if (content.isEmpty()) throw ApiException.localized(400, "empty_content", messageKey = "memos.error.empty")
            val row = repo.create(pid, content)
            call.respond(row.toDto())
        }

        // ── 수정 ────────────────────────────────────────────────────────
        put("/api/memos/{memoId}") {
            call.requireApiWrite()
            val memoId = call.parameters["memoId"]!!
            val existing = repo.get(memoId)
                ?: throw ApiException.localized(404, "memo_not_found", messageKey = "memos.error.notFound")
            val req = decode<MemoUpdateRequestDto>(json, call.receiveText())
            val content = req.content.trim()
            if (content.isEmpty()) throw ApiException.localized(400, "empty_content", messageKey = "memos.error.empty")
            val ok = if (req.keepScope) {
                repo.updateContent(memoId, content)
            } else {
                val pid = req.projectId?.trim()?.ifBlank { null }
                ensureProjectExists(projects, pid)
                repo.update(memoId, pid, content)
            }
            if (!ok) throw ApiException.localized(404, "memo_not_found", messageKey = "memos.error.notFound")
            val updated = repo.get(memoId) ?: existing
            call.respond(updated.toDto())
        }

        // ── 삭제 ────────────────────────────────────────────────────────
        delete("/api/memos/{memoId}") {
            call.requireApiWrite()
            val memoId = call.parameters["memoId"]!!
            val ok = repo.delete(memoId)
            if (!ok) throw ApiException.localized(404, "memo_not_found", messageKey = "memos.error.notFound")
            call.respond(MemoMutationAckDto(ok = true))
        }
    }
}

private inline fun <reified T> decode(json: Json, body: String): T =
    runCatching { json.decodeFromString<T>(body) }.getOrElse {
        throw ApiException.localized(400, "bad_request", messageKey = "memos.error.badRequest")
    }

/** projectId 가 non-null 이면 실제 존재하는 프로젝트인지 확인 (없으면 404). null(전역) 은 통과. */
private fun ensureProjectExists(projects: ProjectService, projectId: String?) {
    if (projectId == null) return
    runCatching { projects.get(projectId) }.getOrElse {
        throw ApiException.localized(404, "project_not_found", messageKey = "api.common.projectNotFound", args = listOf(projectId))
    }
}

private fun MemoRow.toDto(): MemoDto = MemoDto(
    id = id,
    projectId = projectId,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
