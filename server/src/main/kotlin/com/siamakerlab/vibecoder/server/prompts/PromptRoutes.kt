package com.siamakerlab.vibecoder.server.prompts

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

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
            PromptTemplates.listPage(sess.username, templates, csrf = sess.csrf, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/prompts") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
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
        get(com.siamakerlab.vibecoder.shared.ApiPath.PROMPT_TEMPLATES) {
            val wire = store.listAll().map {
                com.siamakerlab.vibecoder.shared.dto.PromptTemplateDto(
                    id = it.id,
                    title = it.title,
                    category = it.category,
                    body = it.body,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            call.respond(com.siamakerlab.vibecoder.shared.dto.PromptTemplateListResponseDto(templates = wire))
        }
    }
}
