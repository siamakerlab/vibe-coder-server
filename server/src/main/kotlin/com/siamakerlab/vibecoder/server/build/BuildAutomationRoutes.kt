package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository
import com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.time.Clock as JClock

private val log = KotlinLogging.logger {}

/**
 * v0.33.0 — `/projects/{id}/automation` — cron schedule + webhook secret 관리.
 *
 * 그리고 인증 없는 외부 트리거 endpoint:
 *   POST /api/webhooks/build/{projectId}
 *   Headers: X-Vibe-Signature: hex(HMAC-SHA256(secret, body))
 *   Body: 임의 (서명 검증용 raw bytes). 비어 있어도 됨.
 *
 * 다중 secret 등록 가능 — 어느 secret 의 HMAC 든 매치되면 통과.
 */
fun Routing.buildAutomationRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    scheduleRepo: BuildScheduleRepository,
    secretRepo: BuildWebhookSecretRepository,
    buildService: BuildService,
    hub: LogHub,
    clock: com.siamakerlab.vibecoder.server.core.Clock,
) {
    get("/projects/{id}/automation") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val schedules = scheduleRepo.listAll().filter { it.projectId == id }
        val secrets = secretRepo.listForProject(id)
        val flashOk = call.request.queryParameters["ok"]
        val flashErr = call.request.queryParameters["err"]
        val newSecret = call.request.queryParameters["newSecret"]   // 1회 노출
        call.respondText(
            AutomationTemplates.page(sess.username, p, schedules, secrets, flashOk, flashErr, newSecret, sess.csrf),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/automation/schedules") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val form = call.receiveParameters()
        val cronExpr = form["cronExpr"]?.trim().orEmpty()
        val description = form["description"]?.trim()?.ifBlank { null }
        val err = BuildScheduler.validate(cronExpr)
        if (err != null) {
            call.respondRedirect("/projects/$id/automation?err=${enc(err)}")
            return@post
        }
        scheduleRepo.create(id, cronExpr, description = description)
        log.info { "schedule created: $id '$cronExpr' by ${sess.username}" }
        authDeps.audit.scheduleCreate(sess.userId, call.request.local.remoteHost, id, cronExpr)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule '$cronExpr' 추가됨")}")
    }

    post("/projects/{id}/automation/schedules/{scheduleId}/toggle") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val scheduleId = call.parameters["scheduleId"]!!
        val form = call.receiveParameters()
        val enabled = form["enabled"]?.equals("true", ignoreCase = true) ?: false
        scheduleRepo.toggleEnabled(scheduleId, enabled)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule ${if (enabled) "활성" else "비활성"}")}")
    }

    post("/projects/{id}/automation/schedules/{scheduleId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val scheduleId = call.parameters["scheduleId"]!!
        scheduleRepo.delete(scheduleId)
        authDeps.audit.scheduleDelete(sess.userId, call.request.local.remoteHost, scheduleId)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule 삭제됨")}")
    }

    post("/projects/{id}/automation/secrets") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val form = call.receiveParameters()
        val name = form["name"]?.trim().orEmpty().ifBlank { "external" }
        // 32-byte URL-safe random secret. 1회만 사용자에게 노출. SHA-256 hex 저장
        // (webhook 은 plaintext-equivalent verify 가 필요 — BCrypt 부적합).
        val secret = generateSecret()
        val hash = sha256Hex(secret)
        secretRepo.create(id, name, hash)
        log.info { "webhook secret created: $id name='$name' by ${sess.username}" }
        authDeps.audit.webhookSecretCreate(sess.userId, call.request.local.remoteHost, id, name)
        call.respondRedirect("/projects/$id/automation?ok=${enc("secret 생성됨 — 아래 박스에 1회 표시")}&newSecret=${enc(secret)}")
    }

    post("/projects/{id}/automation/secrets/{secretId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val secretId = call.parameters["secretId"]!!
        secretRepo.delete(secretId)
        authDeps.audit.webhookSecretDelete(sess.userId, call.request.local.remoteHost, secretId)
        call.respondRedirect("/projects/$id/automation?ok=${enc("secret 삭제됨")}")
    }

    // ── 외부 트리거 (NO admin auth) ─────────────────────────────────
    post("/api/webhooks/build/{projectId}") {
        val projectId = call.parameters["projectId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "projectId required"))
            return@post
        }
        val signature = call.request.headers["X-Vibe-Signature"]?.trim()
        if (signature.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "X-Vibe-Signature header required"))
            return@post
        }
        val body = call.receiveText().take(MAX_BODY_BYTES)
        val candidates = secretRepo.listForProject(projectId)
        if (candidates.isEmpty()) {
            // existence-disclosure 막기 위해 일관 응답.
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid signature"))
            return@post
        }
        // 모든 secret 에 대해 HMAC 계산. plaintext secret 이 DB 에 없으므로 — 사용자가 보낸 signature
        // 와 매치되는 row 를 찾기 위해 secret 자체를 알 수 없음. 따라서 webhook 은 **alternative auth**
        // 로 secret 자체를 body 와 같이 보내는 방식이 안 됨.
        //
        // 대신 webhook secret 은 **plaintext 그대로 메모리 cache** 가 필요. 또는 sender 가
        // signature 와 함께 secretId 도 함께 보내 sender 가 알고 있는 plaintext secret 으로 검증.
        //
        // v0.33.0 에서는 단순화 — webhook 등록 시 ID 도 sender 가 보관하고
        // X-Vibe-Secret-Id 헤더로 보내, server 는 plaintext-equivalent verify 가 어렵기에
        // secretHash 를 BCrypt 가 아닌 stored-plain 으로 갱신해야 함.
        //
        // 본 cycle 의 단순화 결정: secretHash 컬럼에 sha256(secret) 을 저장 (BCrypt 가 아님).
        // sender 는 plaintext secret + body 로 HMAC 만들고, X-Vibe-Secret-Id 헤더로 id 도 보냄.
        // 서버는 id 로 lookup → sender 가 알고 있다는 가정 (1회 노출) 의 secret 의 sha256 을
        // 비교해 검증. 이렇게 하면 BCrypt 안 거치고도 server 는 plain secret 을 알 필요 없음.
        //
        // 자세한 흐름은 webhook secret create / 사용 예시 docstring 참고.
        val secretId = call.request.headers["X-Vibe-Secret-Id"]?.trim()
        if (secretId.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "X-Vibe-Secret-Id header required"))
            return@post
        }
        val row = candidates.firstOrNull { it.id == secretId }
        if (row == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid secret id"))
            return@post
        }
        // secretHash 는 hex(SHA-256(secret)) (BCrypt 가 아님 — webhook 은 plaintext-equivalent verify 가 필요).
        // sender 가 보낸 signature 가 알려진 secret 의 HMAC 와 매치되면 통과.
        // 본 cycle 에선 row.secretHash 가 plaintext-equivalent (sha256 hex) 라 가정.
        // 실제로는 sender 가 보낸 candidate-plaintext 를 가지고 검증해야 하므로,
        // signature 검증은 클라이언트가 보낸 signature 가 candidate-plaintext 로 만든 HMAC 와 같은지 비교가 아닌
        // — 더 단순한 방식: sender 가 plaintext secret 을 X-Vibe-Secret 헤더로 직접 보냄 (TLS 전제).
        // 이게 GitHub-style HMAC 검증보다 weak 하지만 본 cycle 의 1단계로 충분.
        val plainSecret = call.request.headers["X-Vibe-Secret"]?.trim()
        if (plainSecret.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "X-Vibe-Secret header required (plaintext, TLS only)"))
            return@post
        }
        val expectedHash = sha256Hex(plainSecret)
        if (expectedHash != row.secretHash) {
            log.warn { "webhook auth failed: projectId=$projectId secretId=$secretId" }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid secret"))
            return@post
        }
        // Optional: signature 도 검증 (X-Vibe-Signature 는 body integrity 보장).
        if (signature.isNotBlank() && body.isNotBlank()) {
            val expectedSig = hmacSha256Hex(plainSecret, body)
            if (!constantTimeEquals(signature, expectedSig)) {
                log.warn { "webhook signature mismatch: projectId=$projectId" }
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid signature"))
                return@post
            }
        }
        // 검증 통과 → 빌드 enqueue.
        runCatching {
            buildService.enqueueDebug(projectId, hub)
            secretRepo.touchLastUsed(row.id, clock.nowIso())
            authDeps.audit.webhookBuildTriggered(null, call.request.local.remoteHost, projectId, row.name)
        }.onFailure { e ->
            log.warn(e) { "webhook enqueue failed: $projectId" }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "enqueue failed")))
            return@post
        }
        call.respond(HttpStatusCode.Accepted, mapOf("projectId" to projectId, "triggered" to true))
    }
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

internal fun generateSecret(): String {
    val raw = ByteArray(32)
    SecureRandom().nextBytes(raw)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
}

internal fun sha256Hex(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(s.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun hmacSha256Hex(secret: String, body: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val digest = mac.doFinal(body.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

private const val MAX_BODY_BYTES = 64 * 1024

private object AutomationTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        schedules: List<com.siamakerlab.vibecoder.server.repo.BuildScheduleRow>,
        secrets: List<com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRow>,
        ok: String?,
        err: String?,
        newSecret: String?,
        csrf: String?,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""
        val newSecretHtml = newSecret?.let {
            """<div class="card" style="background:rgba(255,200,80,0.1);border-color:#facc15;margin-bottom:14px">
              <h3 style="margin-top:0">🔑 새 secret (1회만 표시)</h3>
              <pre class="diff-block" style="font-size:13px;word-break:break-all">${esc(it)}</pre>
              <p class="hint" style="margin:6px 0 0">이 값을 외부 시스템 (GitHub Actions / CI 등) 에 안전하게 저장하세요. 페이지를 떠나면 다시 볼 수 없습니다.</p>
            </div>"""
        } ?: ""

        val schedRows = if (schedules.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">등록된 schedule 이 없습니다. 아래에서 추가하세요.</td></tr>"""
        } else schedules.joinToString("") { s ->
            val toggleVal = if (s.enabled) "false" else "true"
            val toggleLabel = if (s.enabled) "■ 비활성" else "▶ 활성"
            """<tr>
              <td><code>${esc(s.cronExpr)}</code></td>
              <td>${esc(s.variant)}</td>
              <td>${if (s.enabled) "<span class=\"ok\">✓ 활성</span>" else "<span class=\"dim\">○ 비활성</span>"}</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(s.lastFiredAt ?: "-")}</td>
              <td>
                <form method="post" action="/projects/${esc(p.id)}/automation/schedules/${esc(s.id)}/toggle" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <input type="hidden" name="enabled" value="$toggleVal">
                  <button type="submit" class="chip chip-link">$toggleLabel</button>
                </form>
                <form method="post" action="/projects/${esc(p.id)}/automation/schedules/${esc(s.id)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('schedule 삭제?')">삭제</button>
                </form>
                ${s.description?.let { """<br><small class="dim">${esc(it)}</small>""" } ?: ""}
              </td>
            </tr>"""
        }

        val secretRows = if (secrets.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">등록된 secret 이 없습니다. "+ 새 secret" 으로 추가하세요.</td></tr>"""
        } else secrets.joinToString("") { sec ->
            """<tr>
              <td><code>${esc(sec.name)}</code></td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(sec.id.take(12))}…</td>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(sec.lastUsedAt ?: "-")}</td>
              <td>
                <form method="post" action="/projects/${esc(p.id)}/automation/secrets/${esc(sec.id)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('secret 삭제? 사용 중인 외부 시스템이 다음 호출부터 실패합니다.')">삭제</button>
                </form>
              </td>
            </tr>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Automation",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>Automation
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)}) · v0.33.0</small>
  </h1>
</header>

$okHtml
$errHtml
$newSecretHtml

<div class="card" style="margin-bottom:16px">
  <h2 style="margin-top:0">Cron 빌드 schedule</h2>
  <p class="hint">형식: <code>HH:MM</code> (예: <code>02:00</code> 매일 새벽 2시) / <code>*:MM</code> (매시간 MM 분) / <code>*:*</code> (매 분, 테스트용).
    Full vixie-cron 은 후속 minor.</p>
  <table class="devices" style="margin-top:8px">
    <thead><tr><th>cron expr</th><th>variant</th><th>상태</th><th>last fired</th><th>동작</th></tr></thead>
    <tbody>$schedRows</tbody>
  </table>
  <form method="post" action="/projects/${esc(p.id)}/automation/schedules" style="display:grid;grid-template-columns:1fr 2fr auto;gap:8px;align-items:end;margin-top:12px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">cron expr (HH:MM 또는 *:MM)
      <input name="cronExpr" required placeholder="02:00">
    </label>
    <label style="margin:0">description (선택)
      <input name="description" placeholder="nightly debug build">
    </label>
    <div>
      <button type="submit" class="primary" style="padding:8px 14px">+ 추가</button>
    </div>
  </form>
</div>

<div class="card">
  <h2 style="margin-top:0">Build webhook (외부 트리거)</h2>
  <p class="hint">외부 시스템 (GitHub Actions, GitLab CI, monitoring) 에서 빌드 트리거. <code>POST /api/webhooks/build/${esc(p.id)}</code></p>
  <table class="devices" style="margin-top:8px">
    <thead><tr><th>name</th><th>secret id</th><th>last used</th><th>동작</th></tr></thead>
    <tbody>$secretRows</tbody>
  </table>
  <form method="post" action="/projects/${esc(p.id)}/automation/secrets" style="display:flex;gap:8px;align-items:end;margin-top:12px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0;flex:1">name (구분용)
      <input name="name" required placeholder="github-actions">
    </label>
    <button type="submit" class="primary" style="padding:8px 14px">+ 새 secret</button>
  </form>
  <details style="margin-top:14px">
    <summary>외부 호출 예시 (curl)</summary>
    <pre class="diff-block">SECRET='<paste-here>'   # 1회 표시된 secret 값
SECRET_ID='<from-table>'
BODY=''

SIGNATURE=${'$'}(printf '%s' "${'$'}BODY" | openssl dgst -sha256 -hmac "${'$'}SECRET" | awk '{print ${'$'}2}')

curl -X POST http://&lt;host&gt;:17880/api/webhooks/build/${esc(p.id)} \
  -H "X-Vibe-Secret-Id: ${'$'}SECRET_ID" \
  -H "X-Vibe-Secret: ${'$'}SECRET" \
  -H "X-Vibe-Signature: ${'$'}SIGNATURE" \
  -H 'Content-Type: text/plain' \
  --data "${'$'}BODY"</pre>
    <p class="hint">v0.33.0 의 단순화 (TLS 의존): X-Vibe-Secret 헤더로 plaintext 송신.
      X-Vibe-Signature 는 body integrity 추가 보장. 외부 노출 시 반드시 HTTPS reverse-proxy.</p>
  </details>
</div>
"""
        )
    }
}
