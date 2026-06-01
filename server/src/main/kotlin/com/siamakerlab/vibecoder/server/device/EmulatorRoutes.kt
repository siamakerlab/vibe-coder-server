package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v1.73.0 — 안드로이드 에뮬레이터(헤드리스, Claude 로그분석용) 상태/제어. admin 전용.
 *
 *   GET  /emulator               — 상태 카드 + 시작/중지 + adb 사용 안내 (SSR, embed 대응)
 *   POST /emulator/start|stop    — 수동 lifecycle (admin + CSRF)
 *   GET  /api/emulator/status    — { available, running, booted, serial } (사이드바 pill, 저민감·무인증)
 *
 * 설치는 빌드환경(/env-setup)의 "Android Emulator" 컴포넌트. logcat/install 은 Claude 가
 * 콘솔에서 `adb -s emulator-5554 ...` 로 직접 — 본 라우트는 lifecycle/상태만 책임.
 */
fun Routing.emulatorRoutes(
    authDeps: AdminRoutesDeps,
    emulator: EmulatorService,
) {
    get("/emulator") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            EmulatorTemplates.page(
                username = sess.username,
                status = runCatching { emulator.status() }.getOrElse {
                    EmulatorService.Status(false, false, false, emulator.serial, null)
                },
                avdName = emulator.avdName,
                systemImage = emulator.systemImage,
                ok = call.request.queryParameters["ok"],
                err = call.request.queryParameters["err"],
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/emulator/start") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val r = runCatching { emulator.start() }.getOrElse {
            log.warn(it) { "emulator start 실패" }
            EmulatorService.StartResult(false, it.message ?: "start failed")
        }
        call.respondRedirect("/emulator?${if (r.ok) "ok" else "err"}=${enc(r.message)}")
    }

    post("/emulator/stop") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val r = runCatching { emulator.stop() }.getOrElse {
            log.warn(it) { "emulator stop 실패" }
            EmulatorService.StartResult(false, it.message ?: "stop failed")
        }
        call.respondRedirect("/emulator?${if (r.ok) "ok" else "err"}=${enc(r.message)}")
    }

    // 사이드바 pill — 저민감(설치/실행 여부). adb status 와 동일하게 무인증.
    get(ApiPath.EMULATOR_STATUS) {
        val s = runCatching { emulator.status() }.getOrElse {
            EmulatorService.Status(false, false, false, emulator.serial, null)
        }
        call.respondText(
            """{"available":${s.available},"running":${s.running},"booted":${s.booted},"serial":"${s.serial}"}""",
            ContentType.Application.Json,
        )
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
