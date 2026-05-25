package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.env.ClaudeAuthService
import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.EnvSetupService
import com.siamakerlab.vibecoder.server.env.SetupComponent
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * 빌드환경 SSR 라우트.
 *
 * v0.6.0 Phase A — 상태 진단 + 카드 UI + 명령 안내.
 * v0.6.1 Phase B — 원클릭 설치 (POST) + 일괄 설치 + 진행 페이지 + WS.
 * v0.7.0       — Claude 자격증명 파일 업로드 + ANTHROPIC_API_KEY 모드
 *                (raw-shell UI 정책 §3 미위반 — 단순 파일/폼 입력).
 */
fun Routing.envSetupRoutes(
    authDeps: AdminRoutesDeps,
    setupService: EnvSetupService,
    claudeAuth: ClaudeAuthService,
    claudeLogin: ClaudeLoginService,
) {
    get("/env-setup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val states = setupService.detectAll()
        val claudeFlash = call.request.queryParameters["claude"]
        call.respondText(
            EnvSetupTemplates.envSetupPage(sess.username, states, claudeFlash, csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/install-all") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val taskId = setupService.spawnInstallAll()
        log.info { "env-setup install-all: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    post("/env-setup/{componentId}/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["componentId"]!!
        val comp = SetupComponent.byId(id)
            ?: throw ApiException(404, "unknown_component", "Unknown component: $id")
        val taskId = runCatching { setupService.spawnInstall(comp) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "env.error.installStartFailed")
            call.respondText(
                EnvSetupTemplates.errorBlurb(msg, sess.language),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        log.info { "env-setup install: ${comp.id} → task $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    get("/env-setup/tasks/{taskId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val taskId = call.parameters["taskId"]!!
        call.respondText(
            EnvSetupTemplates.taskProgressPage(sess.username, taskId, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    // ───────────────────────────────────────────────────────────────
    // v0.7.0 — Claude 자격증명 웹 등록 (터미널 접근 불가 환경 대응)
    // ───────────────────────────────────────────────────────────────

    /**
     * 다른 머신에서 `claude login` 후 받은 `.credentials.json` 을 업로드.
     * 업로드 후엔 즉시 vibe 홈에 atomic 배치 → 콘솔/빌드환경이 자동 인식.
     */
    post("/env-setup/claude-auth/upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val multipart = call.receiveMultipart()
        var bytes: ByteArray? = null
        var fileName: String? = null
        try {
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem && bytes == null) {
                        fileName = part.originalFileName
                        bytes = part.provider().toInputStream().use { it.readBytes() }
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (e: Throwable) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(Messages.t(sess.language, "env.error.multipartParse", e.message ?: ""), sess.language),
                ContentType.Text.Html, HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (bytes == null) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(Messages.t(sess.language, "env.error.noFile"), sess.language),
                ContentType.Text.Html, HttpStatusCode.BadRequest,
            )
            return@post
        }
        val result = try {
            claudeAuth.uploadCredentials(bytes!!)
        } catch (e: ApiException) {
            log.warn { "credentials upload rejected (${e.code}): ${e.message}" }
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: Messages.t(sess.language, "env.error.uploadRejected"), sess.language),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        } catch (e: Throwable) {
            log.error(e) { "credentials upload failed" }
            call.respondText(
                EnvSetupTemplates.errorBlurb(Messages.t(sess.language, "env.error.uploadFailed", e.message ?: ""), sess.language),
                ContentType.Text.Html, HttpStatusCode.InternalServerError,
            )
            return@post
        }
        log.info { "credentials uploaded by ${sess.username}: file=$fileName → ${result.targetPath}" }
        call.respondRedirect("/env-setup?claude=uploaded")
    }

    /**
     * ANTHROPIC_API_KEY 등록 (OAuth 대신 API 키 모드). 폼 필드: `apiKey`.
     * 이후 모든 claude 자식 프로세스가 이 키를 환경변수로 받아 동작.
     */
    post("/env-setup/claude-auth/api-key") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = requireCsrf()
        val key = form["apiKey"].orEmpty()
        try {
            claudeAuth.registerApiKey(key)
        } catch (e: ApiException) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: Messages.t(sess.language, "env.error.keyRejected"), sess.language),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        } catch (e: Throwable) {
            log.error(e) { "API key register failed" }
            call.respondText(
                EnvSetupTemplates.errorBlurb(Messages.t(sess.language, "env.error.keyFailed", e.message ?: ""), sess.language),
                ContentType.Text.Html, HttpStatusCode.InternalServerError,
            )
            return@post
        }
        log.info { "API key registered by ${sess.username}" }
        call.respondRedirect("/env-setup?claude=api-key")
    }

    /** API 키 모드 해제 → OAuth 자격증명 모드로 복귀. */
    post("/env-setup/claude-auth/api-key/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        claudeAuth.deleteApiKey()
        log.info { "API key deleted by ${sess.username}" }
        call.respondRedirect("/env-setup?claude=api-key-deleted")
    }

    // ───────────────────────────────────────────────────────────────
    // v0.7.0 옵션 A — 반자동 웹 OAuth (ClaudeLoginService)
    //
    // 다른 머신/터미널 접근 없이 100% 웹으로 `claude auth login` 완료.
    // pty wrapping (script -q) 으로 TUI 자식 프로세스를 spawn → URL 캡처 →
    // 사용자가 새 탭에서 인증 → 받은 코드를 폼에 paste → stdin 으로 전달.
    // ───────────────────────────────────────────────────────────────

    get("/env-setup/claude-login") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val state = claudeLogin.status()
        call.respondText(
            EnvSetupTemplates.claudeLoginPage(sess.username, state, csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/claude-login/start") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        try {
            val s = claudeLogin.start()
            log.info { "claude login started by ${sess.username}: ${s.id}" }
        } catch (e: ApiException) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: Messages.t(sess.language, "env.error.startFailed"), sess.language),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        }
        call.respondRedirect("/env-setup/claude-login")
    }

    post("/env-setup/claude-login/submit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val form = requireCsrf()
        val code = form["code"].orEmpty()
        try {
            claudeLogin.submitCode(code)
            log.info { "claude login code submitted by ${sess.username}" }
        } catch (e: ApiException) {
            call.respondText(
                EnvSetupTemplates.errorBlurb(e.message ?: Messages.t(sess.language, "env.error.codeSubmitRejected"), sess.language),
                ContentType.Text.Html, HttpStatusCode.fromValue(e.statusCode),
            )
            return@post
        }
        call.respondRedirect("/env-setup/claude-login")
    }

    post("/env-setup/claude-login/cancel") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        claudeLogin.cancel()
        log.info { "claude login canceled by ${sess.username}" }
        call.respondRedirect("/env-setup/claude-login")
    }

    /** 진행 페이지가 1초 폴링하는 JSON 상태 엔드포인트. */
    get("/env-setup/claude-login/status.json") {
        requireSessionOrRedirect(authDeps) ?: return@get
        val state = claudeLogin.status()
        val body = if (state == null) "null" else Json.encodeToString(state)
        call.respondText(body, ContentType.Application.Json)
    }
}
