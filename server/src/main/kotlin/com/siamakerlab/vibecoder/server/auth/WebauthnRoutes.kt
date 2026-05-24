package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger {}

/**
 * v0.48.0 — Phase 27 WebAuthn (passkey) routes.
 *
 *   GET  /webauthn                        — admin SSR: list + register + delete
 *   POST /api/webauthn/register/options   — start registration ceremony
 *   POST /api/webauthn/register/verify    — finish registration
 *   POST /api/webauthn/assert/options     — start assertion (login 2FA)
 *   POST /api/webauthn/assert/verify      — finish assertion (used by /login flow)
 *   POST /webauthn/delete/{rowId}         — SSR delete
 *
 * The SSR page is open to any authenticated user (passkey is a personal
 * credential — same policy as `/2fa`). The assertion endpoints are public
 * (no Bearer required — they're the login flow itself).
 */
fun Routing.webauthnRoutes(
    authDeps: AdminRoutesDeps,
    webauthn: WebauthnService,
    auth: AuthService,
    tokens: TokenService,
) {
    // ── SSR: /webauthn — list + register + delete ───────────────────────────
    get("/webauthn") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val creds = webauthn.listCredentials(sess.userId)
        call.respondText(
            AdminTemplates.shell(
                title = "WebAuthn (passkey)",
                username = sess.username,
                currentPath = "/webauthn",
                csrf = sess.csrf,
                body = renderWebauthnPage(sess.username, sess.userId, creds, sess.csrf),
            ),
            ContentType.Text.Html,
        )
    }

    post("/webauthn/delete/{rowId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val rowId = call.parameters["rowId"]
            ?: return@post call.respondRedirect("/webauthn?err=missing_id")
        val removed = webauthn.deleteCredential(sess.userId, rowId)
        call.respondRedirect("/webauthn?${if (removed) "ok=deleted" else "err=not_found"}")
    }

    // ── JSON API: registration ──────────────────────────────────────────────
    post("/api/webauthn/register/options") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val opts = webauthn.beginRegistration(sess.userId, sess.username)
        call.respondText(
            buildString {
                append("{")
                append("\"challenge\":\"${opts.challengeBase64Url}\",")
                append("\"rpId\":\"${esc(opts.rpId)}\",")
                append("\"rpName\":\"${esc(opts.rpName)}\",")
                append("\"userId\":\"${opts.userIdBase64Url}\",")
                append("\"username\":\"${esc(opts.username)}\",")
                append("\"excludeCredentialIds\":[")
                append(opts.excludeCredentialIds.joinToString(",") { "\"$it\"" })
                append("]}")
            },
            ContentType.Application.Json,
        )
    }

    post("/api/webauthn/register/verify") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val obj = (Json.parseToJsonElement(call.receiveText()) as? JsonObject)
            ?: return@post call.respond(HttpStatusCode.BadRequest, "json object expected")
        val clientDataJSON = obj["clientDataJSON"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "clientDataJSON required")
        val attestationObject = obj["attestationObject"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "attestationObject required")
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val transports = (obj["transports"] as? JsonArray)?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        try {
            val row = webauthn.finishRegistration(
                userId = sess.userId,
                clientDataJSONBase64Url = clientDataJSON,
                attestationObjectBase64Url = attestationObject,
                transports = transports,
                name = name,
            )
            authDeps.audit.passkeyRegister(sess.userId, row.id, call.request.local.remoteHost)
            call.respondText(
                """{"id":"${row.id}","name":"${esc(row.name)}","ok":true}""",
                ContentType.Application.Json,
            )
        } catch (e: Throwable) {
            log.warn(e) { "webauthn registration failed for user=${sess.userId}" }
            call.respond(HttpStatusCode.BadRequest, e.message ?: "registration failed")
        }
    }

    // ── JSON API: assertion (login 2FA) ─────────────────────────────────────
    // No Bearer required. Username is the hint — server resolves to userId and returns
    // allowCredentials. If the username doesn't exist, returns an empty allowCredentials
    // (timing-safe with TOTP discovery).
    post("/api/webauthn/assert/options") {
        val obj = (Json.parseToJsonElement(call.receiveText()) as? JsonObject)
            ?: return@post call.respond(HttpStatusCode.BadRequest, "json object expected")
        val username = obj["username"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "username required")
        val user = authDeps.userRepo.findByUsername(username)
        val opts = webauthn.beginAssertion(usernameHint = username, userId = user?.id)
        call.respondText(
            buildString {
                append("{")
                append("\"challenge\":\"${opts.challengeBase64Url}\",")
                append("\"rpId\":\"${esc(opts.rpId)}\",")
                append("\"allowCredentialIds\":[")
                append(opts.allowCredentialIds.joinToString(",") { "\"$it\"" })
                append("]}")
            },
            ContentType.Application.Json,
        )
    }

    post("/api/webauthn/assert/verify") {
        val obj = (Json.parseToJsonElement(call.receiveText()) as? JsonObject)
            ?: return@post call.respond(HttpStatusCode.BadRequest, "json object expected")
        val credentialId = obj["credentialId"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "credentialId required")
        val authenticatorData = obj["authenticatorData"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "authenticatorData required")
        val clientDataJSON = obj["clientDataJSON"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "clientDataJSON required")
        val signature = obj["signature"]?.jsonPrimitive?.contentOrNull
            ?: return@post call.respond(HttpStatusCode.BadRequest, "signature required")
        val userHandle = obj["userHandle"]?.jsonPrimitive?.contentOrNull

        val result = try {
            webauthn.finishAssertion(
                credentialIdBase64Url = credentialId,
                authenticatorDataBase64Url = authenticatorData,
                clientDataJSONBase64Url = clientDataJSON,
                signatureBase64Url = signature,
                userHandleBase64Url = userHandle,
            )
        } catch (e: Throwable) {
            log.warn(e) { "webauthn assertion failed" }
            return@post call.respond(HttpStatusCode.Unauthorized, e.message ?: "assertion failed")
        }

        // Mint a session for the resolved user — same path as a successful password+TOTP login.
        val user = authDeps.userRepo.findById(result.userId)
            ?: return@post call.respond(HttpStatusCode.Unauthorized, "user vanished")
        val issue = tokens.issue()
        val device = authDeps.deviceRepo.insert(
            id = java.util.UUID.randomUUID().toString(),
            name = "webauthn:${user.username}",
            tokenHash = issue.tokenHash,
            userId = user.id,
            channel = "webauthn",
        )
        val token = issue.token
        authDeps.audit.passkeyLogin(user.id, result.credentialId, call.request.local.remoteHost)
        // SSR cookie + return Bearer for JSON consumers.
        call.response.cookies.append(io.ktor.http.Cookie(
            name = SESSION_COOKIE, value = token,
            httpOnly = true, maxAge = 60 * 60 * 24 * 14,
            path = "/", extensions = mapOf("SameSite" to "Lax"),
        ))
        call.respondText(
            """{"token":"$token","deviceId":"${device.id}","username":"${esc(user.username)}"}""",
            ContentType.Application.Json,
        )
    }
}

private fun renderWebauthnPage(
    username: String,
    userId: String,
    creds: List<com.siamakerlab.vibecoder.server.repo.WebauthnCredentialRow>,
    csrf: String?,
): String {
    val rows = if (creds.isEmpty()) {
        """<tr><td colspan="4" class="dim" style="padding:14px;text-align:center">
          등록된 passkey 가 없습니다. 아래 "이 디바이스에서 passkey 등록" 버튼을 눌러 시작하세요.
        </td></tr>"""
    } else creds.joinToString("\n") { c ->
        val lastUsed = c.lastUsedAt ?: "—"
        """
        <tr>
          <td><strong>${esc(c.name)}</strong>
            <div class="dim" style="font-size:11px;margin-top:2px">${esc(c.attestationType ?: "unknown")}${if (c.transports != null) " · " + esc(c.transports) else ""}</div></td>
          <td class="dim" style="font-size:11px">${esc(c.createdAt)}</td>
          <td class="dim" style="font-size:11px">${esc(lastUsed)}</td>
          <td>
            <form method="post" action="/webauthn/delete/${esc(c.id)}" style="display:inline"
                  onsubmit="return confirm('이 passkey 를 삭제할까요?')">
              ${com.siamakerlab.vibecoder.server.auth.CsrfTokens.hiddenInput(csrf)}
              <button type="submit" class="chip chip-danger">삭제</button>
            </form>
          </td>
        </tr>"""
    }

    val userIdJs = "\"" + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(userId.toByteArray(Charsets.UTF_8)) + "\""

    return """
<header>
  <h1>WebAuthn (passkey) <small class="dim" style="font-size:14px;font-weight:400">v0.48.0+ · 2FA</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 6px"><strong>Passkey 는 TOTP 의 phishing-resistant 대안입니다.</strong>
    한 번 등록하면 로그인 시 비밀번호 + passkey 또는 비밀번호 + TOTP 중 하나로 인증 가능.</p>
  <p class="dim" style="margin:0;font-size:12px">
    Same-origin 정책이 보장하는 phishing 방어가 핵심 — TOTP 의 OTP 입력란을 가짜 사이트가
    훔쳐도, passkey 는 정확한 origin 에서만 서명을 만듭니다.
  </p>
</div>

<div class="card" style="margin-bottom:16px">
  <button id="register-btn" class="primary" style="width:auto;padding:8px 16px">이 디바이스에서 passkey 등록</button>
  <input type="text" id="passkey-name" placeholder="이름 (예: MacBook Touch ID)" maxlength="64"
         style="margin-left:8px;padding:6px 10px;background:#1a1a1a;color:var(--text);border:1px solid #333">
  <p class="dim" id="webauthn-status" style="margin:10px 0 0;font-size:12px">대기 중…</p>
</div>

<div class="card">
  <h2 style="margin-top:0;font-size:16px">등록된 passkey (${creds.size}개)</h2>
  <table class="table" style="width:100%">
    <thead><tr><th>이름 / 종류</th><th>등록 시각</th><th>마지막 사용</th><th></th></tr></thead>
    <tbody>$rows</tbody>
  </table>
</div>

<script>
(function() {
  var statusEl = document.getElementById('webauthn-status');
  var btn = document.getElementById('register-btn');
  var nameInput = document.getElementById('passkey-name');

  if (!window.PublicKeyCredential) {
    statusEl.textContent = '이 브라우저는 WebAuthn 을 지원하지 않습니다.';
    btn.disabled = true;
    return;
  }

  function b64UrlToBuf(b64) {
    var pad = '='.repeat((4 - b64.length % 4) % 4);
    var s = (b64 + pad).replace(/-/g, '+').replace(/_/g, '/');
    var raw = atob(s);
    var buf = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) buf[i] = raw.charCodeAt(i);
    return buf.buffer;
  }
  function bufToB64Url(buf) {
    var bytes = new Uint8Array(buf);
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/=+${'$'}/, '').replace(/\+/g, '-').replace(/\//g, '_');
  }

  btn.addEventListener('click', async function() {
    btn.disabled = true;
    statusEl.textContent = '서버 challenge 받는 중…';
    try {
      var optsRes = await fetch('/api/webauthn/register/options', {
        method: 'POST', credentials: 'same-origin',
      });
      if (!optsRes.ok) throw new Error('options ' + optsRes.status);
      var opts = await optsRes.json();

      statusEl.textContent = '인증기 (Touch ID / Windows Hello / 보안키) 를 사용해 주세요.';
      var cred = await navigator.credentials.create({
        publicKey: {
          challenge: b64UrlToBuf(opts.challenge),
          rp: { id: opts.rpId, name: opts.rpName },
          user: {
            id: b64UrlToBuf(opts.userId),
            name: opts.username,
            displayName: opts.username,
          },
          pubKeyCredParams: [
            { type: 'public-key', alg: -7 },    // ES256
            { type: 'public-key', alg: -257 },  // RS256
          ],
          excludeCredentials: (opts.excludeCredentialIds || []).map(function(id) {
            return { type: 'public-key', id: b64UrlToBuf(id) };
          }),
          authenticatorSelection: {
            residentKey: 'preferred',
            userVerification: 'preferred',
          },
          timeout: 60000,
          attestation: 'none',
        },
      });

      var resp = cred.response;
      var transports = resp.getTransports ? resp.getTransports() : [];
      var verifyRes = await fetch('/api/webauthn/register/verify', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          clientDataJSON: bufToB64Url(resp.clientDataJSON),
          attestationObject: bufToB64Url(resp.attestationObject),
          transports: transports,
          name: (nameInput.value || '').trim() || ('passkey@' + new Date().toISOString().slice(0,10)),
        }),
      });
      if (!verifyRes.ok) {
        var msg = await verifyRes.text();
        throw new Error('verify ' + verifyRes.status + ' ' + msg);
      }
      statusEl.textContent = '등록 완료! 페이지를 새로고침합니다…';
      setTimeout(function() { location.reload(); }, 800);
    } catch (e) {
      statusEl.textContent = '실패: ' + (e.message || e);
      btn.disabled = false;
    }
  });
})();
</script>
"""
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
