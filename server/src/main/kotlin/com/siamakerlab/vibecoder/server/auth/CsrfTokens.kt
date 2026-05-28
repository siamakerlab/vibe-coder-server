package com.siamakerlab.vibecoder.server.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingContext
import io.ktor.server.request.receiveParameters
import com.siamakerlab.vibecoder.server.error.ApiException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CSRF (Cross-Site Request Forgery) 토큰 시스템 — v0.12.4.
 *
 * 설계:
 *  - 서버 시작 시 32 바이트 `pepper` 를 SecureRandom 으로 생성. 서버 재시작 시
 *    pepper 가 회전되므로 모든 기존 세션의 csrf 토큰이 무효화 (의도된 보안 동작).
 *  - 토큰 = HMAC-SHA256(pepper, deviceToken). 같은 cookie 값이면 항상 같은 csrf
 *    토큰 → SSR 폼이 매 페이지 로드마다 같은 값을 박을 수 있다.
 *  - 검증 시 `_csrf` 폼 파라미터를 받아 cookie 의 device token 으로 재계산 후
 *    constant-time 비교.
 *  - 안전성: cookie 가 httpOnly + SameSite=Lax 이므로 외부 사이트의 스크립트는
 *    device token 을 알 수 없고, 따라서 유효한 csrf 토큰을 만들지도 못한다.
 *
 * REST API (Bearer 헤더, JSON) 는 cookie 를 자동 첨부하지 않으므로 CSRF 가
 * 적용되지 않는다. cookie 기반 SSR 폼 POST 만 보호 대상.
 */
object CsrfTokens {

    private val pepper: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /**
     * Device cookie token 으로부터 결정적인 csrf 토큰 derive. base64-url (44자) 반환.
     * cookie 가 없으면 null (인증 전 상태 → 폼 자체가 없어야 함).
     */
    fun tokenFor(deviceCookieToken: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper, "HmacSHA256"))
        val raw = mac.doFinal(deviceCookieToken.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    /** Cookie 가 있으면 csrf 토큰, 아니면 null. */
    fun tokenFromCall(call: ApplicationCall): String? {
        val cookie = call.request.cookies[SESSION_COOKIE]
        return if (cookie.isNullOrBlank()) null else tokenFor(cookie)
    }

    /**
     * SSR POST 핸들러용 검증 헬퍼. form 본문에서 `_csrf` 파라미터를 읽고 cookie 의
     * 예상 토큰과 constant-time 비교. 통과 시 폼 파라미터 전체를 반환 (호출자가
     * 다른 필드 읽기 위해 다시 receiveParameters 호출 안 해도 됨 — multipart 가 아닌 경우).
     *
     * 불일치 / 누락 시 403 ApiException → StatusPages 가 사용자 친화 응답으로 변환.
     */
    suspend fun RoutingContext.requireCsrf(): io.ktor.http.Parameters {
        val expected = tokenFromCall(call) ?: throw ApiException.localized(
            403, "csrf_no_session", messageKey = "api.auth.csrfMissing",
        )
        val params = call.receiveParameters()
        val provided = params["_csrf"].orEmpty()
        if (!constantTimeEquals(expected, provided)) {
            throw ApiException.localized(
                403, "csrf_token_mismatch", messageKey = "api.auth.csrfInvalid",
            )
        }
        return params
    }

    /**
     * multipart 업로드 폼처럼 receiveParameters 가 불가능한 경로용. 이 경우
     * 클라이언트는 query string `?_csrf=...` 또는 헤더 `X-CSRF-Token` 으로 전달.
     */
    fun verifyCsrfFromQueryOrHeader(call: ApplicationCall) {
        val expected = tokenFromCall(call) ?: throw ApiException.localized(
            403, "csrf_no_session", messageKey = "api.auth.csrfMissing",
        )
        val provided = call.request.queryParameters["_csrf"]
            ?: call.request.headers["X-CSRF-Token"]
            ?: ""
        if (!constantTimeEquals(expected, provided)) {
            throw ApiException.localized(
                403, "csrf_token_mismatch", messageKey = "api.auth.csrfInvalid",
            )
        }
    }

    /**
     * v1.28.1 — SSR 폼 body 의 `_csrf` 검증 (예외 없이 Boolean). 호출자가 이미
     * `receiveParameters()` 로 폼을 읽은 뒤, 그 안의 `_csrf` 를 넘겨 검증한다.
     * 실패 시 false 만 반환 → 호출자가 **JSON 예외 대신 SSR flash redirect** 로
     * 처리할 수 있다 (CSRF 만료 시 페이지가 JSON 으로 깨지는 것 방지).
     *
     * 배경: [verifyCsrfFromQueryOrHeader] 는 query/header 만 보므로 hidden input
     * (body) 으로 `_csrf` 를 보내는 일반 form POST 에는 맞지 않는다 (항상 실패).
     */
    fun isValidCsrf(call: ApplicationCall, providedFromBody: String?): Boolean {
        val expected = tokenFromCall(call) ?: return false
        return providedFromBody != null && constantTimeEquals(expected, providedFromBody)
    }

    /**
     * SSR 폼 안에 박는 hidden input 한 줄. csrf 가 null 이면 빈 문자열 — 호출자가
     * 인증된 페이지 안에서만 호출해야 함.
     */
    fun hiddenInput(csrf: String?): String =
        if (csrf == null) ""
        else """<input type="hidden" name="_csrf" value="${escAttr(csrf)}">"""

    /** base64-url 알파벳만 등장하지만 안전을 위해 attribute escape. */
    private fun escAttr(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
