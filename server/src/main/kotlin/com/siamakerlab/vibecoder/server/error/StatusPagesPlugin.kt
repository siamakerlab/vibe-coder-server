package com.siamakerlab.vibecoder.server.error

import com.siamakerlab.vibecoder.shared.dto.ApiErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ApiErrorDto(code = cause.code, message = cause.message ?: cause.code, detail = cause.detail),
            )
        }
        exception<Throwable> { call, cause ->
            log.error(cause) { "unhandled error: ${cause.message}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiErrorDto(code = "internal_error", message = cause.message ?: "internal_error"),
            )
        }
    }
}
