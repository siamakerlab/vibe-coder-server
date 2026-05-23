package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.EnvSetupService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val log = KotlinLogging.logger {}

/**
 * 빌드환경 SSR 라우트 (v0.6.0 Phase A).
 *
 * Phase A 는 status 진단 + UI 카드 + 사용자 절차 안내만. 설치 실행(POST)
 * 은 Phase B 에서 추가.
 */
fun Routing.envSetupRoutes(
    authDeps: AdminRoutesDeps,
    setupService: EnvSetupService,
) {
    get("/env-setup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val states = setupService.detectAll()
        call.respondText(
            EnvSetupTemplates.envSetupPage(sess.username, states),
            ContentType.Text.Html,
        )
    }
}
