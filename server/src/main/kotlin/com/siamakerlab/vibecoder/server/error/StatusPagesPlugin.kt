package com.siamakerlab.vibecoder.server.error

import com.siamakerlab.vibecoder.shared.dto.ApiErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

fun Application.installStatusPages(
    /** v0.92.0 — server default language for ApiException localization fallback. */
    serverDefaultLanguage: String = "en",
    /** v0.96.0 — Phase 67 closure: optional metrics for ApiException counter. */
    metrics: com.siamakerlab.vibecoder.server.metrics.MetricsRegistry? = null,
) {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            // v1.30.1 (BUG-1) — CSRF 실패 + 브라우저 폼 navigation(HTML accept)이면 JSON
            // 대신 referer 로 SSR redirect. query/header csrf 검증(verifyCsrfFromQueryOrHeader)
            // 경로는 실패 시 ApiException 을 던져 여기로 오는데, 폼 navigation 이라 JSON 이
            // 전체 페이지로 표시되며 UI 가 붕괴하던 문제(keystore 와 동일 클래스, body 경로는
            // v1.28.1 에서 이미 SSR redirect 로 회수). open-redirect 방지 위해 referer 의
            // path 만 사용(host 무시). JSON API(Accept: application/json)는 기존 JSON 유지.
            val isCsrf = cause.code == "csrf_token_mismatch" || cause.code == "csrf_no_session"
            val wantsHtml = call.request.headers["Accept"]?.contains("text/html") == true
            if (isCsrf && wantsHtml) {
                val refPath = call.request.headers["Referer"]
                    ?.let { runCatching { java.net.URI(it).path }.getOrNull() }
                    ?.takeIf { it.startsWith("/") } ?: "/"
                val sep = if (refPath.contains("?")) "&" else "?"
                call.respondRedirect("$refPath${sep}err=csrf")
                return@exception
            }
            // v0.92.0 — Phase 67 i18n. messageKey 가 있으면 Accept-Language 기반 localize.
            // 없으면 cause.message (legacy English) 그대로. backward compatible.
            val msg = if (cause.messageKey != null) {
                val lang = com.siamakerlab.vibecoder.server.i18n.Messages.resolveFromRequest(
                    acceptLanguage = call.request.headers["Accept-Language"],
                    userLang = null,
                    serverDefault = serverDefaultLanguage,
                )
                com.siamakerlab.vibecoder.server.i18n.Messages.t(
                    lang, cause.messageKey, *cause.messageArgs.toTypedArray(),
                )
            } else cause.message ?: cause.code
            // v0.96.0 — Phase 67 closure: vibe_api_errors_total{code} 증가.
            // /metrics 에서 ApiException 분포를 본다 (어떤 code 가 가장 자주 발생하는지).
            // cardinality 안전: code 는 enum-like 짧은 식별자 (`unauthorized`, `bad_request` 등).
            metrics?.inc(
                name = "vibe_api_errors_total",
                help = "Total ApiException by code (v0.96.0 — Phase 67 closure)",
                labels = mapOf("code" to cause.code),
            )
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ApiErrorDto(code = cause.code, message = msg, detail = cause.detail),
            )
        }
        // kotlinx-serialization wraps MissingFieldException etc. into JsonConvertException.
        // Map to 400 with the original message so clients see exactly which field is wrong.
        exception<JsonConvertException> { call, cause ->
            val msg = cause.cause?.message ?: cause.message ?: "invalid JSON body"
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = msg),
            )
        }
        // Ktor also throws BadRequestException for parameter / receive failures.
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = cause.message ?: "bad request"),
            )
        }
        // require() inside services throws IllegalArgumentException — surface as 400.
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorDto(code = "bad_request", message = cause.message ?: "illegal argument"),
            )
        }
        exception<Throwable> { call, cause ->
            log.error(cause) { "unhandled error: ${cause.message}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorDto(code = "internal_error", message = cause.message ?: "internal_error"),
            )
        }
        // Catch-all route miss: log the actual method + uri so client-side URL bugs
        // are diagnosable from server logs, and return a structured ApiErrorDto so
        // clients can deserialize uniformly.
        status(HttpStatusCode.NotFound) { call, status ->
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            log.warn { "route miss: $method $uri" }
            call.respond(
                status,
                ApiErrorDto(code = "route_not_found", message = "$method $uri has no matching route"),
            )
        }
    }
}
