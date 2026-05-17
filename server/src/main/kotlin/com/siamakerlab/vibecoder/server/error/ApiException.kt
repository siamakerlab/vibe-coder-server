package com.siamakerlab.vibecoder.server.error

/**
 * Domain exception that maps to a JSON `ApiErrorDto` and an HTTP [statusCode].
 * Throw freely from services; [StatusPagesPlugin] converts to a response.
 */
class ApiException(
    val statusCode: Int,
    val code: String,
    message: String,
    val detail: String? = null,
) : RuntimeException(message)
