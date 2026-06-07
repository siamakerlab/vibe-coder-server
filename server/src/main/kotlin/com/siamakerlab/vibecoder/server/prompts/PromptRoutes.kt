package com.siamakerlab.vibecoder.server.prompts

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger {}

/**
 * 프롬프트 템플릿 라우트 — v0.13.0.
 *
 * SSR:
 *   GET  /prompts                  — 목록 + 신규 추가 폼
 *   POST /prompts                  — 신규 생성
 *   POST /prompts/{id}/update      — 수정
 *   POST /prompts/{id}/delete      — 삭제
 *
 * JSON API (안드로이드 / 콘솔 JS 가 fetch):
 *   GET  /api/prompt-templates     — 전체 목록 (그룹화)
 */
fun Routing.promptRoutes(
    authDeps: AdminRoutesDeps,
    store: PromptTemplateStore,
) {
    // ── SSR ────────────────────────────────────────────────────────
    get("/prompts") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val templates = store.listAll()
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            PromptTemplates.listPage(sess.username, templates, csrf = sess.csrf, flashErr = err, flashOk = ok, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/prompts") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post // v1.44.0 — viewer 차단(prompt 템플릿은 workspace 전역)
        val params = requireCsrf()
        try {
            val t = store.create(
                title = params["title"].orEmpty(),
                category = params["category"].orEmpty(),
                body = params["body"].orEmpty(),
            )
            call.respondRedirect("/prompts?ok=created:${t.id}")
        } catch (e: Throwable) {
            val msg = (e as? ApiException)?.message ?: e.message ?: "생성 실패"
            call.respondRedirect("/prompts?err=${java.net.URLEncoder.encode(msg, "UTF-8")}")
        }
    }

    post("/prompts/{id}/update") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post // v1.44.0 — viewer 차단
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        try {
            store.update(
                id = id,
                title = params["title"].orEmpty(),
                category = params["category"].orEmpty(),
                body = params["body"].orEmpty(),
            )
            call.respondRedirect("/prompts?ok=updated:$id")
        } catch (e: Throwable) {
            val msg = (e as? ApiException)?.message ?: e.message ?: "수정 실패"
            call.respondRedirect("/prompts?err=${java.net.URLEncoder.encode(msg, "UTF-8")}")
        }
    }

    post("/prompts/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post // v1.44.0 — viewer 차단
        requireCsrf()
        val id = call.parameters["id"]!!
        val removed = store.delete(id)
        log.info { "prompt template delete by ${sess.username}: $id removed=$removed" }
        call.respondRedirect("/prompts?ok=${if (removed) "deleted" else "missing"}")
    }

    // ── JSON API (Bearer 인증) — 콘솔 JS / 안드로이드 양쪽 사용 ─────
    // v0.13.0 부터 동일 shape 노출. v0.20.0 부터 wire DTO (PromptTemplateListResponseDto) 로
    // 응답해 안드로이드 client 가 직접 PromptTemplateDto 로 받을 수 있도록 정식화.
    authenticate(AUTH_BEARER) {
        val base = com.siamakerlab.vibecoder.shared.ApiPath.PROMPT_TEMPLATES

        // v1.115.0 — 콘솔 관리 다이얼로그용 JSON CRUD (쿠키=Bearer 동일 인증). 모두 admin write.
        // 콘솔 mutation 관례(쿠키+JSON, SameSite)와 동일하게 별도 CSRF 토큰 없음.
        get(base) {
            // 읽기 — 인증만(콘솔 picker·Android 공용). write 불요.
            call.respond(com.siamakerlab.vibecoder.shared.dto.PromptTemplateListResponseDto(
                templates = store.listAll().map { it.toDto() },
            ))
        }

        post(base) {
            call.requireApiWrite()
            val req = call.receive<UpsertReq>()
            val t = store.create(title = req.title, category = req.category, body = req.body)
            call.respond(HttpStatusCode.Created, t.toDto())
        }

        put("$base/{id}") {
            call.requireApiWrite()
            val id = call.parameters["id"]!!
            val req = call.receive<UpsertReq>()
            val t = store.update(id = id, title = req.title, category = req.category, body = req.body)
            call.respond(t.toDto())
        }

        delete("$base/{id}") {
            call.requireApiWrite()
            val id = call.parameters["id"]!!
            val removed = store.delete(id)
            if (!removed) throw ApiException.localized(404, "template_not_found", messageKey = "api.prompt.notFound", args = listOf(id))
            call.respond(HttpStatusCode.NoContent)
        }

        // 삽입 시 사용 빈도 +1 (best-effort — 실패해도 삽입엔 영향 없게 콘솔이 무시).
        post("$base/{id}/use") {
            call.requireApiWrite()
            val id = call.parameters["id"]!!
            val t = store.recordUse(id) ?: throw ApiException.localized(404, "template_not_found", messageKey = "api.prompt.notFound", args = listOf(id))
            call.respond(t.toDto())
        }

        post("$base/{id}/pin") {
            call.requireApiWrite()
            val id = call.parameters["id"]!!
            val req = call.receive<PinReq>()
            val t = store.setPinned(id, req.pinned) ?: throw ApiException.localized(404, "template_not_found", messageKey = "api.prompt.notFound", args = listOf(id))
            call.respond(t.toDto())
        }

        post("$base/{id}/duplicate") {
            call.requireApiWrite()
            val id = call.parameters["id"]!!
            call.respond(HttpStatusCode.Created, store.duplicate(id).toDto())
        }

        // 가져오기 — replace=true 전체 교체 / false 병합. 반환: 적재 개수.
        post("$base/import") {
            call.requireApiWrite()
            val req = call.receive<ImportReq>()
            val added = store.importTemplates(req.templates, replace = req.replace)
            call.respond(ImportResult(added = added, total = store.listAll().size))
        }
    }
}

private fun PromptTemplateStore.Template.toDto() =
    com.siamakerlab.vibecoder.shared.dto.PromptTemplateDto(
        id = id, title = title, category = category, body = body,
        createdAt = createdAt, updatedAt = updatedAt,
        pinned = pinned, useCount = useCount, lastUsedAt = lastUsedAt,
    )

@Serializable
private data class UpsertReq(val title: String = "", val category: String = "", val body: String = "")

@Serializable
private data class PinReq(val pinned: Boolean = false)

@Serializable
private data class ImportReq(
    val replace: Boolean = false,
    val templates: List<PromptTemplateStore.ImportItem> = emptyList(),
)

@Serializable
private data class ImportResult(val added: Int, val total: Int)
