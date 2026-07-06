package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.requireDevice
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
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
                pool = runCatching { emulator.poolStatus() }.getOrNull(),
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

    post("/emulator/start/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"].orEmpty()
        val r = runCatching { emulator.start(id) }.getOrElse {
            log.warn(it) { "emulator start 실패: $id" }
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

    post("/emulator/stop/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"].orEmpty()
        val r = runCatching { emulator.stop(id) }.getOrElse {
            log.warn(it) { "emulator stop 실패: $id" }
            EmulatorService.StartResult(false, it.message ?: "stop failed")
        }
        call.respondRedirect("/emulator?${if (r.ok) "ok" else "err"}=${enc(r.message)}")
    }

    // 사이드바 pill — 설치/실행 여부. v1.77.0 — quota/adb 와 일괄 인증 게이트.
    // 사이드바 pill 은 fetch(credentials:'same-origin') 로 vibe_session 쿠키 전송 → 통과.
    authenticate(AUTH_BEARER) {
        get(ApiPath.EMULATOR_STATUS) {
            call.requireDevice() // 인증만 강제(단일 admin)
            val s = runCatching { emulator.status() }.getOrElse {
                EmulatorService.Status(false, false, false, emulator.serial, null)
            }
            val pool = runCatching { emulator.poolStatus() }.getOrNull()
            call.respondText(
                """{"available":${s.available},"running":${s.running},"booted":${s.booted},"serial":"${s.serial}","external":${s.external},"runningCount":${pool?.running ?: if (s.running) 1 else 0},"bootedCount":${pool?.booted ?: if (s.booted) 1 else 0},"max":${pool?.max ?: emulator.maxEmulators}}""",
                ContentType.Application.Json,
            )
        }

        get(ApiPath.EMULATORS) {
            call.requireDevice()
            call.respondText(poolJson(emulator.poolStatus()), ContentType.Application.Json)
        }

        post(ApiPath.emulatorStart("{id}")) {
            call.requireDevice()
            val id = call.parameters["id"].orEmpty()
            call.respondText(resultJson(emulator.start(id)), ContentType.Application.Json)
        }

        post(ApiPath.emulatorStop("{id}")) {
            call.requireDevice()
            val id = call.parameters["id"].orEmpty()
            call.respondText(resultJson(emulator.stop(id)), ContentType.Application.Json)
        }

        get(ApiPath.projectEmulatorLease("{projectId}")) {
            call.requireDevice()
            val projectId = call.parameters["projectId"].orEmpty()
            val lease = emulator.leaseForProject(projectId)
            call.respondText(leaseJson(lease), ContentType.Application.Json)
        }

        post(ApiPath.projectEmulatorLease("{projectId}")) {
            call.requireDevice()
            val projectId = call.parameters["projectId"].orEmpty()
            val kind = call.request.queryParameters["kind"]
            call.respondText(resultJson(emulator.acquireLease(projectId, preferredKind = kind, mode = "project")), ContentType.Application.Json)
        }

        delete(ApiPath.projectEmulatorLease("{projectId}")) {
            call.requireDevice()
            val projectId = call.parameters["projectId"].orEmpty()
            call.respondText(resultJson(emulator.releaseLease(projectId)), ContentType.Application.Json)
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

private fun resultJson(r: EmulatorService.StartResult): String =
    """{"ok":${r.ok},"message":"${jsonEsc(r.message)}"}"""

private fun poolJson(p: EmulatorService.PoolStatus): String =
    """{"available":${p.available},"max":${p.max},"running":${p.running},"booted":${p.booted},"slots":[${p.slots.joinToString(",") { statusJson(it) }}]}"""

private fun statusJson(s: EmulatorService.Status): String =
    """{"id":"${jsonEsc(s.id)}","label":"${jsonEsc(s.label)}","kind":"${jsonEsc(s.kind)}","available":${s.available},"running":${s.running},"booted":${s.booted},"serial":"${jsonEsc(s.serial)}","consolePort":${s.consolePort},"avdName":"${jsonEsc(s.avdName)}","systemImage":"${jsonEsc(s.systemImage)}","external":${s.external},"startedAtIso":${nullable(s.startedAtIso)},"leasedByProjectId":${nullable(s.leasedByProjectId)},"leaseExpiresAtIso":${nullable(s.leaseExpiresAtIso)}}"""

private fun leaseJson(l: EmulatorService.Lease?): String =
    if (l == null) """{"lease":null}""" else
        """{"lease":{"emulatorId":"${jsonEsc(l.emulatorId)}","serial":"${jsonEsc(l.serial)}","projectId":"${jsonEsc(l.projectId)}","acquiredAtIso":"${jsonEsc(l.acquiredAtIso)}","lastSeenAtIso":"${jsonEsc(l.lastSeenAtIso)}","expiresAtIso":"${jsonEsc(l.expiresAtIso)}","mode":"${jsonEsc(l.mode)}"}}"""

private fun nullable(s: String?): String = s?.let { """"${jsonEsc(it)}"""" } ?: "null"

private fun jsonEsc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
