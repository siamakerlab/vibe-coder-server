package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository
import com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
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
 * 그리고 인증 없는 외부 트리거 endpoint (v1.27.4 — GitHub-style HMAC-only):
 *   POST /api/webhooks/build/{projectId}
 *   Headers:
 *     X-Vibe-Secret-Id: <secret 등록 시 받은 id>
 *     X-Vibe-Signature: hex(HMAC-SHA256(secret, body))
 *   Body: 임의 (서명 대상 raw bytes). 비어 있어도 됨.
 *
 * sender 는 secret 평문을 전송하지 않는다 (이전 v0.33.0 의 X-Vibe-Secret 평문
 * 헤더 제거). server 는 X-Vibe-Secret-Id 로 row 를 찾아 저장된 secret 으로
 * HMAC 을 재계산·constant-time 비교한다.
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
        requireProjectAccessOrThrow(sess, projects, id)
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
            AutomationTemplates.page(sess.username, p, schedules, secrets, flashOk, flashErr, newSecret, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/automation/schedules") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val cronExpr = form["cronExpr"]?.trim().orEmpty()
        val description = form["description"]?.trim()?.ifBlank { null }
        val err = BuildScheduler.validate(cronExpr)
        if (err != null) {
            call.respondRedirect("/projects/$id/automation?err=${enc(err)}")
            return@post
        }
        scheduleRepo.create(id, cronExpr, description = description)
        log.info { "schedule created: $id '$cronExpr' by ${sess.username}" }
        authDeps.audit.scheduleCreate(sess.userId, call.request.origin.remoteHost, id, cronExpr)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule '$cronExpr' 추가됨")}")
    }

    post("/projects/{id}/automation/schedules/{scheduleId}/toggle") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val scheduleId = call.parameters["scheduleId"]!!
        // C2 (21차 점검) — row 의 projectId 가 path {id} 와 일치하는지 검증.
        // repo.toggleEnabled/delete 가 id 만으로 동작하므로 소속 확인이 없으면
        // scheduleId 만 알면 임의 프로젝트의 schedule 을 조작할 수 있다.
        if (scheduleRepo.listAll().find { it.id == scheduleId }?.projectId != id) {
            call.respondRedirect("/projects/$id/automation?err=${enc("schedule not found")}")
            return@post
        }
        val enabled = form["enabled"]?.equals("true", ignoreCase = true) ?: false
        scheduleRepo.toggleEnabled(scheduleId, enabled)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule ${if (enabled) "활성" else "비활성"}")}")
    }

    post("/projects/{id}/automation/schedules/{scheduleId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val scheduleId = call.parameters["scheduleId"]!!
        if (scheduleRepo.listAll().find { it.id == scheduleId }?.projectId != id) {
            call.respondRedirect("/projects/$id/automation?err=${enc("schedule not found")}")
            return@post
        }
        scheduleRepo.delete(scheduleId)
        authDeps.audit.scheduleDelete(sess.userId, call.request.origin.remoteHost, scheduleId)
        call.respondRedirect("/projects/$id/automation?ok=${enc("schedule 삭제됨")}")
    }

    post("/projects/{id}/automation/secrets") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = form["name"]?.trim().orEmpty().ifBlank { "external" }
        // 32-byte URL-safe random secret. 1회만 사용자에게 노출.
        // v1.27.4 (Q3) — 평문 저장 (대칭 HMAC 키). sender 는 secret 을 전송하지 않고
        // HMAC(secret, body) 만 X-Vibe-Signature 로 보냄 → server 가 저장 secret 으로
        // 재계산·비교. GitHub-style HMAC-only.
        val secret = generateSecret()
        secretRepo.create(id, name, secret)
        log.info { "webhook secret created: $id name='$name' by ${sess.username}" }
        authDeps.audit.webhookSecretCreate(sess.userId, call.request.origin.remoteHost, id, name)
        call.respondRedirect("/projects/$id/automation?ok=${enc("secret 생성됨 — 아래 박스에 1회 표시")}&newSecret=${enc(secret)}")
    }

    post("/projects/{id}/automation/secrets/{secretId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val secretId = call.parameters["secretId"]!!
        if (secretRepo.listForProject(id).none { it.id == secretId }) {
            call.respondRedirect("/projects/$id/automation?err=${enc("secret not found")}")
            return@post
        }
        secretRepo.delete(secretId)
        authDeps.audit.webhookSecretDelete(sess.userId, call.request.origin.remoteHost, secretId)
        call.respondRedirect("/projects/$id/automation?ok=${enc("secret 삭제됨")}")
    }

    // ── 외부 트리거 (NO admin auth) — v1.27.4 (Q3): GitHub-style HMAC-only ──────
    // sender 는 secret 평문을 전송하지 않는다. `X-Vibe-Secret-Id` (어떤 secret 인지)
    // + `X-Vibe-Signature` (= hex(HMAC-SHA256(secret, body))) 만 보낸다. server 는
    // 저장된 secret 평문으로 HMAC 을 재계산해 constant-time 비교. 평문 secret 전송이
    // 사라져 TLS 평문 구간 secret 유출 위험 제거.
    post(ApiPath.buildWebhook("{projectId}")) {
        val projectId = call.parameters["projectId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "projectId required"))
            return@post
        }
        val signature = call.request.headers["X-Vibe-Signature"]?.trim()
        val secretId = call.request.headers["X-Vibe-Secret-Id"]?.trim()
        if (signature.isNullOrBlank() || secretId.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("error" to "X-Vibe-Secret-Id and X-Vibe-Signature headers required"),
            )
            return@post
        }
        // v1.28.0 (Q-1 회수) — body 크기 가드. 이전엔 `receiveText().take(MAX_BODY_BYTES)`
        // 로 char 단위 truncate(멀티바이트면 byte 한도 초과 가능) + 전체 body 를 먼저
        // 메모리 적재. Content-Length 선체크로 거대 body 를 받기 전에 413 거절, byte 기준.
        val declaredLen = call.request.headers["Content-Length"]?.toLongOrNull()
        if (declaredLen != null && declaredLen > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "body too large"))
            return@post
        }
        val body = call.receiveText()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "body too large"))
            return@post
        }
        // secretId 로 row lookup. 못 찾으면 existence-disclosure 막기 위해 일관 메시지.
        val row = secretRepo.listForProject(projectId).firstOrNull { it.id == secretId }
        if (row == null) {
            log.warn { "webhook auth failed (no such secret): projectId=$projectId secretId=$secretId" }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid signature"))
            return@post
        }
        // 저장된 secret 평문으로 HMAC 재계산 → constant-time 비교. body 가 비어도 검증
        // (sender 와 server 가 동일하게 HMAC("") 계산 — replay 외엔 위조 불가).
        val expectedSig = hmacSha256Hex(row.secret, body)
        if (!constantTimeEquals(signature, expectedSig)) {
            log.warn { "webhook signature mismatch: projectId=$projectId secretId=$secretId" }
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid signature"))
            return@post
        }
        // 검증 통과 → 빌드 enqueue.
        runCatching {
            buildService.enqueueDebug(projectId, hub)
            secretRepo.touchLastUsed(row.id, clock.nowIso())
            authDeps.audit.webhookBuildTriggered(null, call.request.origin.remoteHost, projectId, row.name)
        }.onFailure { e ->
            log.warn(e) { "webhook enqueue failed: $projectId" }
            // v1.31.0 (B-BUG2) — ApiException(예: 키스토어 미등록 409 keystore_required)은
            // 그 status code 로 응답. 이전엔 모두 500 으로 변질돼 외부 CI 가 "서버 내부
            // 오류"로 오인 (실제론 운영자가 키스토어를 등록해야 하는 client-fixable 상황).
            val ae = e as? com.siamakerlab.vibecoder.server.error.ApiException
            if (ae != null) {
                call.respond(HttpStatusCode.fromValue(ae.statusCode), mapOf("error" to ae.code))
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "enqueue failed")))
            }
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

        lang: String,
        embed: Boolean = false,
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
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(AdminTemplates.fmtTs(sec.lastUsedAt, lang))}</td>
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
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">Automation
      <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
    </h1>
    <a href="/projects/${esc(p.id)}/automation/prompts" class="chip chip-action" style="background:#1e40af;color:#fff">🤖 프롬프트 자동화 →</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">빌드 cron / webhook 은 여기서, 콘솔 프롬프트 반복·순차 자동화는 "프롬프트 자동화" 에서.</p>
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
  -H "X-Vibe-Signature: ${'$'}SIGNATURE" \
  -H 'Content-Type: text/plain' \
  --data "${'$'}BODY"</pre>
    <p class="hint">GitHub-style HMAC-only. secret 평문은 전송하지 않습니다
      (X-Vibe-Signature 의 HMAC 만). server 가 X-Vibe-Secret-Id 로 secret 을 찾아
      HMAC 을 재계산·비교합니다. 외부 노출 시 HTTPS 권장 (replay 방어).</p>
  </details>
</div>
""",
            lang = lang,
            embed = embed,
        )
    }
}
