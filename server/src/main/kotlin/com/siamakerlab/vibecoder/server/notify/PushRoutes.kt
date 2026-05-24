package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.repo.PushSubscriptionRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * v0.46.0 — Phase 25 Web Push routes.
 *
 * Browser flow:
 *   1. GET /api/push/vapid-public-key                 → { publicKey: base64url }
 *   2. PushManager.subscribe({ applicationServerKey: publicKey })
 *   3. POST /api/push/subscribe { endpoint, p256dh, auth, userAgent }
 *   4. SW receives `push` event → showNotification('Vibe Coder', ...)
 *
 * SSR /settings/push:
 *   - Shows current device subscription status
 *   - Allows admins to view + revoke other devices' subscriptions
 *   - "Send test" button
 */
fun Routing.pushRoutes(
    authDeps: AdminRoutesDeps,
    notifier: WebPushNotifier,
    subscriptionRepo: PushSubscriptionRepository,
) {
    // ── JSON API (Bearer) ──────────────────────────────────────────────────
    authenticate(AUTH_BEARER) {
        get("/api/push/vapid-public-key") {
            call.respondText(
                """{"publicKey":"${notifier.publicKeyBase64Url()}"}""",
                ContentType.Application.Json,
            )
        }

        post("/api/push/subscribe") {
            val device = call.requireApiWrite()
            val raw = call.receiveText()
            val obj = (Json.parseToJsonElement(raw) as? JsonObject)
                ?: return@post call.respond(HttpStatusCode.BadRequest, "json object expected")
            val endpoint = obj["endpoint"]?.jsonPrimitive?.contentOrNull
                ?: return@post call.respond(HttpStatusCode.BadRequest, "endpoint required")
            val p256dh = obj["p256dh"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val auth = obj["auth"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ua = obj["userAgent"]?.jsonPrimitive?.contentOrNull
            val row = subscriptionRepo.upsert(
                userId = device.device.userId,
                endpoint = endpoint, p256dh = p256dh, auth = auth, userAgent = ua,
            )
            call.respondText(
                """{"id":"${row.id}","ok":true}""",
                ContentType.Application.Json,
            )
        }

        delete("/api/push/subscriptions/{id}") {
            call.requireApiWrite()
            val id = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val removed = subscriptionRepo.deleteById(id)
            call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }

    // ── SSR settings page ──────────────────────────────────────────────────
    get("/settings/push") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val list = subscriptionRepo.list()
        val body = renderPushSettings(notifier, list, sess.csrf, sess.userId)
        call.respondText(
            AdminTemplates.shell(
                title = "Web Push 알림",
                username = sess.username,
                currentPath = "/settings",
                csrf = sess.csrf,
                body = body,
            ),
            ContentType.Text.Html,
        )
    }

    post("/settings/push/test") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        notifier.broadcast("Vibe Coder 테스트", "이 알림을 보면 Web Push 가 정상 동작합니다.")
        call.respondRedirect("/settings/push?ok=test_sent")
    }

    post("/settings/push/delete/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]
            ?: return@post call.respondRedirect("/settings/push?err=missing_id")
        subscriptionRepo.deleteById(id)
        call.respondRedirect("/settings/push?ok=removed")
    }
}

private fun renderPushSettings(
    notifier: WebPushNotifier,
    list: List<com.siamakerlab.vibecoder.server.repo.PushSubscriptionRow>,
    csrf: String?,
    currentUserId: String,
): String {
    val rows = if (list.isEmpty()) {
        """<tr><td colspan="4" class="dim" style="padding:14px;text-align:center">
          등록된 push subscription 이 없습니다. 아래 "이 브라우저에서 구독" 버튼을 눌러 등록하세요.
        </td></tr>"""
    } else list.joinToString("\n") { row ->
        val mine = row.userId == currentUserId
        val ua = row.userAgent.orEmpty().take(80)
        """
        <tr>
          <td>${if (mine) "<strong>(나)</strong>" else "다른 사용자"}
            <div class="dim" style="font-size:11px;margin-top:2px">${esc(ua)}</div></td>
          <td class="dim" style="font-size:11px">${esc(row.createdAt)}</td>
          <td class="dim" style="font-size:11px;max-width:280px;word-break:break-all">${esc(row.endpoint.take(80))}…</td>
          <td>
            <form method="post" action="/settings/push/delete/${esc(row.id)}" style="display:inline"
                  onsubmit="return confirm('이 subscription 을 삭제할까요?')">
              ${CsrfTokens.hiddenInput(csrf)}
              <button type="submit" class="chip chip-danger">삭제</button>
            </form>
          </td>
        </tr>"""
    }

    return """
<header>
  <h1>Web Push 알림 <small class="dim" style="font-size:14px;font-weight:400">v0.46.0+</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 6px"><strong>현재 구현 한계 (payload-less).</strong> 이 버전은
    VAPID 인증만 구현되어 모든 알림이 generic 제목 + body 로 표시됩니다.
    AES-128-GCM payload 암호화는 추후 확장 (외부 dep 또는 in-house crypto module).</p>
  <p class="dim" style="margin:0;font-size:12px">
    VAPID public key (base64url, P-256):
    <code style="word-break:break-all;font-size:11px">${esc(notifier.publicKeyBase64Url())}</code>
  </p>
</div>

<div class="card" style="margin-bottom:16px">
  <button id="subscribe-btn" class="primary" style="width:auto;padding:8px 16px">이 브라우저에서 구독</button>
  <button id="unsubscribe-btn" class="chip chip-danger" style="display:none">구독 해제</button>
  <form method="post" action="/settings/push/test" style="display:inline">
    ${CsrfTokens.hiddenInput(csrf)}
    <button type="submit" class="chip chip-link">전체 subscription 에 테스트 알림 전송</button>
  </form>
  <p class="dim" id="push-status" style="margin:10px 0 0;font-size:12px">상태 확인 중…</p>
</div>

<div class="card">
  <h2 style="margin-top:0;font-size:16px">등록된 구독 (${list.size}개)</h2>
  <table class="table" style="width:100%">
    <thead><tr><th>사용자</th><th>등록 시각</th><th>endpoint</th><th></th></tr></thead>
    <tbody>$rows</tbody>
  </table>
</div>

<script>
(async function() {
  var statusEl = document.getElementById('push-status');
  var subBtn = document.getElementById('subscribe-btn');
  var unsubBtn = document.getElementById('unsubscribe-btn');

  if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
    statusEl.textContent = '이 브라우저는 Web Push 를 지원하지 않습니다.';
    subBtn.disabled = true;
    return;
  }
  var reg = await navigator.serviceWorker.register('/static/sw.js');
  await navigator.serviceWorker.ready;

  function b64UrlToUint8(b64) {
    var pad = '='.repeat((4 - b64.length % 4) % 4);
    var b = (b64 + pad).replace(/-/g, '+').replace(/_/g, '/');
    var raw = atob(b);
    var out = new Uint8Array(raw.length);
    for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }

  function arrBufToB64Url(buf) {
    var bytes = new Uint8Array(buf);
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  }

  async function refresh() {
    var existing = await reg.pushManager.getSubscription();
    if (existing) {
      statusEl.textContent = '구독됨 — endpoint: ' + existing.endpoint.substring(0, 60) + '…';
      subBtn.style.display = 'none';
      unsubBtn.style.display = 'inline-block';
    } else {
      statusEl.textContent = '구독 안 됨.';
      subBtn.style.display = 'inline-block';
      unsubBtn.style.display = 'none';
    }
  }

  subBtn.addEventListener('click', async function() {
    subBtn.disabled = true;
    try {
      var perm = await Notification.requestPermission();
      if (perm !== 'granted') { statusEl.textContent = '알림 권한이 거부되었습니다.'; return; }
      var keyRes = await fetch('/api/push/vapid-public-key', { credentials: 'same-origin' });
      var keyJson = await keyRes.json();
      var sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: b64UrlToUint8(keyJson.publicKey),
      });
      var keys = sub.toJSON().keys || {};
      await fetch('/api/push/subscribe', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          endpoint: sub.endpoint,
          p256dh: keys.p256dh || arrBufToB64Url(sub.getKey('p256dh') || new ArrayBuffer(0)),
          auth: keys.auth || arrBufToB64Url(sub.getKey('auth') || new ArrayBuffer(0)),
          userAgent: navigator.userAgent,
        }),
      });
      location.reload();
    } catch (e) {
      statusEl.textContent = '구독 실패: ' + e.message;
    } finally {
      subBtn.disabled = false;
    }
  });

  unsubBtn.addEventListener('click', async function() {
    unsubBtn.disabled = true;
    try {
      var existing = await reg.pushManager.getSubscription();
      if (existing) await existing.unsubscribe();
      // 서버 측 row 도 정리 — 이번 페이지의 endpoint 만 알면 되나 단순화 위해 reload.
      location.reload();
    } catch (e) {
      statusEl.textContent = '구독 해제 실패: ' + e.message;
    } finally {
      unsubBtn.disabled = false;
    }
  });

  refresh();
})();
</script>
"""
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
