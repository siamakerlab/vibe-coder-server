package com.siamakerlab.vibecoder.server.auth

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.statuspages.StatusPages
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Ktor 3.x 본문 1회-수신 제약 회귀 가드.
 *
 * 배경: SSR 폼 POST 핸들러가 [com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf]
 * (내부에서 receiveParameters() 1회 수행) 호출 후 다시 call.receiveParameters() 를
 * 부르면 RequestAlreadyConsumedException(500) 이 발생한다. (AdMob 저장 500 의 근본 원인)
 *
 * 올바른 패턴은 `val form = requireCsrf()` 처럼 1회 수신 결과를 재사용하는 것.
 * 이 테스트는 (1) 더블 수신이 500 이 됨을 고정하고, (2) 단일 수신 재사용이 200 임을 보장한다.
 */
class ReceiveParametersConstraintTest {

    @Test fun `receiveParameters twice throws RequestAlreadyConsumed`() = testApplication {
        application {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respondText("ERR:${cause::class.simpleName}", status = HttpStatusCode.InternalServerError)
                }
            }
            routing {
                post("/twice") {
                    call.receiveParameters()
                    call.receiveParameters() // 두 번째 수신 → 예외
                    call.respondText("ok")
                }
            }
        }
        val res = client.post("/twice") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("x=hello&_csrf=tok")
        }
        res.status shouldBe HttpStatusCode.InternalServerError
        res.bodyAsText() shouldBe "ERR:RequestAlreadyConsumedException"
    }

    @Test fun `receiveParameters once reused works`() = testApplication {
        application {
            routing {
                post("/once") {
                    val form = call.receiveParameters() // 1회 수신 후 재사용 (수정된 패턴)
                    call.respondText("x=${form["x"]}")
                }
            }
        }
        val res = client.post("/once") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("x=hello&_csrf=tok")
        }
        res.status shouldBe HttpStatusCode.OK
        res.bodyAsText() shouldBe "x=hello"
    }
}
