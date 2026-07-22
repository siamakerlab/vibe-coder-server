package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.env.ClaudeAuthService
import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.EnvSetupService
import com.siamakerlab.vibecoder.server.env.GitConfigService
import com.siamakerlab.vibecoder.server.env.SetupComponent
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
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
 * v0.7.0       — Claude 웹 OAuth 로그인 카드.
 */
fun Routing.envSetupRoutes(
    authDeps: AdminRoutesDeps,
    setupService: EnvSetupService,
    claudeAuth: ClaudeAuthService,
    claudeLogin: ClaudeLoginService,
    /** v1.9.0 — Git global identity (`user.name` / `user.email`) 입력 카드. */
    gitConfig: GitConfigService,
    /** v1.159.0 — SSH 인바운드 접속 인증키(authorized_keys) + 접속용 키 발급. */
    sshAccess: SshAccessService,
) {
    get("/env-setup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val states = setupService.detectAll(sess.language)
        val claudeFlash = call.request.queryParameters["claude"]
        val gitFlash = call.request.queryParameters["git"]
        val sshFlash = call.request.queryParameters["ssh"]
        val gitIdentity = runCatching { gitConfig.get() }
            .getOrDefault(com.siamakerlab.vibecoder.server.env.GitIdentity(null, null))
        val sshCard = SshCardData(
            host = runCatching { call.request.host() }.getOrNull()?.ifBlank { null } ?: "<host>",
            port = setupService.sshServerPort(),
            authorizedKeys = runCatching { sshAccess.listAuthorizedKeys() }.getOrDefault(emptyList()),
            accessKey = runCatching { sshAccess.accessKey() }.getOrNull(),
        )
        call.respondText(
            EnvSetupTemplates.envSetupPage(
                username = sess.username,
                states = states,
                claudeFlash = claudeFlash,
                gitIdentity = gitIdentity,
                gitFlash = gitFlash,
                sshPort = setupService.sshServerPort(),
                sshFlash = sshFlash,
                sshCard = sshCard,
                csrf = sess.csrf,
                lang = sess.language,
                iosEnv = runCatching { setupService.iosEnvSnapshot() }.getOrNull(),
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // v1.164.0 (Phase 9) — SwiftLint/SwiftFormat 설치 (Homebrew, macOS 전용). vibe-doctor 가 아니라
    // IosSwiftToolsInstallService 재사용 → spawnSwiftToolsInstall 이 task 로 감싸 로그 스트리밍.
    post("/env-setup/swift-tools/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val taskId = setupService.spawnSwiftToolsInstall()
        log.info { "env-setup swift-tools install: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    // v1.9.0 — Git global identity 등록 / 갱신. 미설정 / 빈 입력 시 ApiException → flash err.
    post("/env-setup/git-config") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val name = form["name"].orEmpty()
        val email = form["email"].orEmpty()
        val flash = runCatching {
            gitConfig.set(name, email)
            "saved"
        }.getOrElse { e ->
            log.warn(e) { "git config set rejected" }
            val code = (e as? ApiException)?.code ?: "save_failed"
            "err:$code"
        }
        call.respondRedirect("/env-setup?git=$flash")
    }

    // 사용자가 잘못 입력 후 초기화 원할 때.
    post("/env-setup/git-config/clear") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        runCatching { gitConfig.clear() }
            .onFailure { log.warn(it) { "git config clear failed" } }
        call.respondRedirect("/env-setup?git=cleared")
    }

    post("/env-setup/install-all") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val taskId = setupService.spawnInstallAll()
        log.info { "env-setup install-all: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    post("/env-setup/{componentId}/install") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["componentId"]!!
        val comp = SetupComponent.byId(id)
            ?: throw ApiException.localized(404, "unknown_component", messageKey = "api.envSetup.unknownComponent", args = listOf(id))
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

    post("/env-setup/ssh-server/config") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val taskId = runCatching {
            setupService.saveSshServerPort(form["port"].orEmpty())
            setupService.spawnInstall(SetupComponent.SSH_SERVER)
        }.getOrElse { e ->
            log.warn(e) { "ssh server config rejected" }
            val code = (e as? ApiException)?.code ?: "save_failed"
            call.respondRedirect("/env-setup?ssh=err:$code")
            return@post
        }
        log.info { "env-setup ssh-server configure/install: task $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    // v1.159.0 — 내 공개키를 authorized_keys 에 등록 (키 접속 provisioning ①).
    post("/env-setup/ssh-server/authorized-keys/add") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val flash = runCatching {
            val info = sshAccess.addAuthorizedKey(form["publicKey"].orEmpty())
            log.info { "env-setup ssh authorized-key add: ${info.fingerprint} by ${sess.username}" }
            "key-added"
        }.getOrElse { e ->
            log.warn { "ssh authorized-key add rejected: ${e.message}" }
            "err:invalid_public_key"
        }
        call.respondRedirect("/env-setup?ssh=$flash#ssh-server")
    }

    // v1.159.0 — 등록된 공개키를 fingerprint 로 삭제.
    post("/env-setup/ssh-server/authorized-keys/remove") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        runCatching { sshAccess.removeAuthorizedKey(form["fingerprint"].orEmpty()) }
            .onFailure { log.warn { "ssh authorized-key remove failed: ${it.message}" } }
        call.respondRedirect("/env-setup?ssh=key-removed#ssh-server")
    }

    // v1.159.0 — 전용 접속 키쌍 발급 (키 접속 provisioning ②). 공개키는 authorized_keys 에 자동 등록.
    post("/env-setup/ssh-server/access-key/generate") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val flash = runCatching {
            val info = sshAccess.generateAccessKey()
            log.info { "env-setup ssh access-key generated: ${info.fingerprint} by ${sess.username}" }
            "access-key-generated"
        }.getOrElse { e ->
            log.warn { "ssh access-key generate failed: ${e.message}" }
            "err:access_key_failed"
        }
        call.respondRedirect("/env-setup?ssh=$flash#ssh-server")
    }

    // v1.159.0 — 발급된 접속용 개인키 다운로드. GET(다운로드) — no-store, attachment.
    get("/env-setup/ssh-server/access-key/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val key = sshAccess.accessPrivateKey()
        if (key == null) {
            call.respondRedirect("/env-setup?ssh=err:no_access_key#ssh-server")
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "vibe-access").toString(),
        )
        log.info { "env-setup ssh access-key downloaded by ${sess.username}" }
        call.respondBytes(key, ContentType.Application.OctetStream)
    }

    post("/env-setup/codex-login/start") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val taskId = setupService.spawnCodexLogin()
        log.info { "env-setup codex-login: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    // v1.156.0 — z.ai coding plan API key 를 auth.json 에 직접 등록 (서버 무인 환경용).
    // v1.160.3 — 대화형 opencode providers login 라우트 제거(헤드리스 hang → 큐 점유). API key 경로만.
    post("/env-setup/opencode-auth/api-key") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val form = requireCsrf()
        val apiKey = form["apiKey"].orEmpty()
        val taskId = runCatching { setupService.spawnOpenCodeApiKeyLogin(apiKey) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "env.error.keyRejected")
            call.respondText(
                EnvSetupTemplates.errorBlurb(msg, sess.language),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        log.info { "env-setup opencode-api-key: $taskId by ${sess.username}" }
        call.respondRedirect("/env-setup/tasks/$taskId")
    }

    get("/env-setup/tasks/{taskId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val taskId = call.parameters["taskId"]!!
        call.respondText(
            EnvSetupTemplates.taskProgressPage(sess.username, taskId, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
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
        if (!requireAdminOrRedirect(sess)) return@get
        val state = claudeLogin.status()
        call.respondText(
            EnvSetupTemplates.claudeLoginPage(sess.username, state, csrf = sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/env-setup/claude-login/start") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
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
        if (!requireAdminOrRedirect(sess)) return@post
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
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        claudeLogin.cancel()
        log.info { "claude login canceled by ${sess.username}" }
        call.respondRedirect("/env-setup/claude-login")
    }

    /** 진행 페이지가 1초 폴링하는 JSON 상태 엔드포인트. */
    get("/env-setup/claude-login/status.json") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val state = claudeLogin.status()
        val body = if (state == null) "null" else Json.encodeToString(state)
        call.respondText(body, ContentType.Application.Json)
    }
}
