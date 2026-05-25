package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.repo.DeviceRow
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto

/**
 * 정적 HTML 템플릿. SPA 없이 서버 사이드 렌더링.
 *
 * 디자인 원칙
 *   - 모든 경로에서 같은 레이아웃 셸 사용
 *   - 1인 LAN 도구 → 자바스크립트 의존도 최소화 (post는 form action)
 *   - 외부 CDN 의존 없음 (LAN-only 환경 가정)
 */
object AdminTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /** JS 문자열 literal context 전용. csrf 토큰 (base64-url 문자 + 영숫자) 안전. */
    private fun jsLitString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    internal fun shell(
        title: String,
        body: String,
        username: String? = null,
        currentPath: String = "/",
        showNav: Boolean = true,
        /** v0.12.4 — 인증된 페이지에서 nav 의 logout 폼 등에 박을 CSRF 토큰. */
        csrf: String? = null,
        /** v0.77.0 — Phase 64 i18n. WebSession.language ("en"/"ko"). nav/tabBar 라벨 분기. */
        lang: String = "en",
    ): String {
        val nav = if (showNav) navHtml(currentPath, username, csrf, lang) else ""
        val layoutCls = if (showNav) "layout" else "layout no-nav"
        val maybeTabs =
            if (showNav && SettingsNav.topLevelOf(currentPath) == "settings")
                SettingsNav.tabBar(currentPath, lang)
            else ""
        // v0.12.4 — JS 가 ajax POST 시 CSRF 토큰을 첨부할 수 있도록 meta + global.
        // body 가 아닌 head 에 있어야 inline script 보다 먼저 실행됨.
        val csrfMeta = if (csrf != null)
            """<meta name="csrf-token" content="${esc(csrf)}">
  <script>window.__VIBE_CSRF__ = ${jsLitString(csrf)};</script>"""
        else ""
        return """<!doctype html>
<html lang="${esc(lang)}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  $csrfMeta
  <title>${esc(title)} · Vibe Coder</title>
  <link rel="icon" type="image/png" href="/static/icon.png">
  <link rel="manifest" href="/static/manifest.json">
  <meta name="theme-color" content="#0b0d12">
  <link rel="stylesheet" href="/static/admin.css">
  <script src="/static/keyboard.js" defer></script>
  <script>
    // v0.39.0 — PWA service worker. Same-origin install only.
    if ('serviceWorker' in navigator) {
      window.addEventListener('load', function() {
        navigator.serviceWorker.register('/static/sw.js').catch(function(){ /* ignore */ });
      });
    }
  </script>
</head>
<body>
  <div class="$layoutCls">
    $nav
    <main class="content">
      $maybeTabs
      $body
    </main>
  </div>
</body>
</html>
"""
    }

    private fun navHtml(currentPath: String, username: String?, csrf: String?, lang: String = "en"): String {
        val activeTop = SettingsNav.topLevelOf(currentPath)
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        fun link(href: String, label: String, key: String): String {
            val cls = if (activeTop == key) "active" else ""
            return """<a href="${esc(href)}" class="${esc(cls)}" data-key="${esc(key)}">${esc(label)}</a>"""
        }
        val userBoxHtml: String = if (username != null) {
            val u = esc(username)
            "<div class=\"user\">$u</div>"
        } else ""
        val csrfInput = CsrfTokens.hiddenInput(csrf)
        return """
<nav class="sidebar">
  <div class="brand" style="display:flex;align-items:center;gap:10px">
    <img src="/static/icon.png" alt=""
         style="width:32px;height:32px;border-radius:50%;object-fit:cover;flex-shrink:0">
    <span>Vibe Coder</span>
  </div>
  <div class="nav-links">
    ${link("/", t("nav.home"), "dashboard")}
    ${link("/projects", t("nav.projects"), "projects")}
    ${link("/chat", "Chat", "chat")}
    ${link("/tools", t("nav.tools"), "tools")}
    ${link("/settings", t("nav.settings"), "settings")}
  </div>
  <div class="user-box">
    $userBoxHtml
    <form method="post" action="/logout">
      $csrfInput
      <button type="submit" class="logout">${esc(t("nav.logout"))}</button>
    </form>
  </div>
</nav>
"""
    }

    // ────────────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────────────

    fun setupPage(error: String? = null): String {
        val errHtml = if (error != null) """<div class="error">${esc(error)}</div>""" else ""
        return shell(
            title = "초기 설정",
            showNav = false,
            body = """
<div class="auth-card">
  <h1>Vibe Coder 초기 설정</h1>
  <p class="dim">처음 사용 시 admin 계정을 만드세요. 이 계정으로 웹/앱 모두 로그인합니다.</p>
  $errHtml
  <form method="post" action="/setup">
    <label>사용자명
      <input name="username" required minlength="3" maxlength="32" autofocus
             pattern="[A-Za-z0-9._-]{3,32}">
    </label>
    <label>비밀번호 (영문+숫자 8자 이상)
      <input name="password" type="password" required minlength="8">
    </label>
    <label>비밀번호 확인
      <input name="passwordConfirm" type="password" required minlength="8">
    </label>
    <button type="submit" class="primary">계정 생성하고 시작</button>
  </form>
</div>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Login
    // ────────────────────────────────────────────────────────────────────

    fun loginPage(
        error: String? = null,
        next: String? = null,
        /** v0.26.0 — TOTP 단계 진입 시 1단계 (username/password) 값을 hidden 으로 보존
         *  하고 코드 입력 필드만 노출. null 이면 1단계 폼. */
        totpUsername: String? = null,
        totpPassword: String? = null,
    ): String {
        val errHtml = if (error != null) """<div class="error">${esc(error)}</div>""" else ""
        val nextField = if (next != null) """<input type="hidden" name="next" value="${esc(next)}">""" else ""
        // v0.26.0 — 2단계 (TOTP) 폼.
        if (totpUsername != null && totpPassword != null) {
            return shell(
                title = "2단계 인증",
                showNav = false,
                body = """
<div class="auth-card">
  <h1>2단계 인증</h1>
  <p class="hint">Authenticator 앱에 표시된 6자리 코드를 입력하세요.</p>
  $errHtml
  <form method="post" action="/login">
    $nextField
    <input type="hidden" name="username" value="${esc(totpUsername)}">
    <input type="hidden" name="password" value="${esc(totpPassword)}">
    <label>TOTP 코드
      <input name="totpCode" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autofocus>
    </label>
    <button type="submit" class="primary">확인</button>
  </form>
  <p class="hint" style="margin-top:12px"><a href="/login">← 사용자 다시 선택</a></p>
</div>
"""
            )
        }
        return shell(
            title = "로그인",
            showNav = false,
            body = """
<div class="auth-card">
  <h1>로그인</h1>
  $errHtml
  <form method="post" action="/login" id="login-form">
    $nextField
    <label>사용자명
      <input name="username" id="login-username" required autofocus>
    </label>
    <label>비밀번호
      <input name="password" type="password" required>
    </label>
    <button type="submit" class="primary">로그인</button>
  </form>
  <hr style="margin:18px 0;border-color:#222">
  <div style="text-align:center">
    <button type="button" id="passkey-login-btn" class="chip chip-link"
            style="font-size:13px;padding:8px 14px" disabled>
      🔑 Passkey 로 로그인
    </button>
    <p class="hint" id="passkey-status" style="margin:8px 0 0;font-size:11px">사용자명 입력 후 활성화…</p>
  </div>
</div>
<script>
(function() {
  if (!window.PublicKeyCredential) return;
  var usernameInput = document.getElementById('login-username');
  var btn = document.getElementById('passkey-login-btn');
  var status = document.getElementById('passkey-status');
  function refresh() { btn.disabled = !usernameInput.value.trim(); }
  usernameInput.addEventListener('input', refresh);

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
    status.textContent = 'challenge 받는 중…';
    try {
      var optsRes = await fetch('/api/webauthn/assert/options', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: usernameInput.value.trim() }),
      });
      if (!optsRes.ok) throw new Error('options ' + optsRes.status);
      var opts = await optsRes.json();
      if (!opts.allowCredentialIds || opts.allowCredentialIds.length === 0) {
        status.textContent = '이 사용자에게는 등록된 passkey 가 없습니다.'; btn.disabled = false; return;
      }
      status.textContent = '인증기 사용 중…';
      var cred = await navigator.credentials.get({
        publicKey: {
          challenge: b64UrlToBuf(opts.challenge),
          rpId: opts.rpId,
          allowCredentials: opts.allowCredentialIds.map(function(id) {
            return { type: 'public-key', id: b64UrlToBuf(id) };
          }),
          userVerification: 'preferred',
          timeout: 60000,
        },
      });
      var resp = cred.response;
      var verifyRes = await fetch('/api/webauthn/assert/verify', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          credentialId: bufToB64Url(cred.rawId),
          authenticatorData: bufToB64Url(resp.authenticatorData),
          clientDataJSON: bufToB64Url(resp.clientDataJSON),
          signature: bufToB64Url(resp.signature),
          userHandle: resp.userHandle ? bufToB64Url(resp.userHandle) : null,
        }),
      });
      if (!verifyRes.ok) {
        var msg = await verifyRes.text();
        throw new Error('verify ' + verifyRes.status + ' ' + msg);
      }
      // 인증 성공 — vibe_session 쿠키가 설정됨. dashboard 로 이동.
      var dest = ${if (next != null) "\"${esc(next)}\"" else "'/'"};
      location.href = dest;
    } catch (e) {
      status.textContent = '실패: ' + (e.message || e);
      btn.disabled = false;
    }
  });
})();
</script>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Dashboard
    // ────────────────────────────────────────────────────────────────────

    fun dashboardPage(
        username: String,
        status: ServerStatusDto,
        deviceCount: Int,
        runningBuilds: Int,
        claudeAuth: com.siamakerlab.vibecoder.shared.dto.CheckItemDto? = null,
        claudeUsage: com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto? = null,
        diskSnapshot: com.siamakerlab.vibecoder.server.disk.DiskMonitor.Snapshot? = null,
        csrf: String? = null,
    ): String {
        val claudeBadge = if (status.claudeAvailable) "<span class=\"ok\">✓ OK</span>" else "<span class=\"warn\">✗ 미설치</span>"
        val sdkBadge = if (status.androidSdkAvailable) "<span class=\"ok\">✓ OK</span>" else "<span class=\"warn\">✗ doctor 실행 필요</span>"
        val authBadge = when (claudeAuth?.status) {
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK -> "<span class=\"ok\">✓ 로그인됨</span>"
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.ERROR -> "<span class=\"warn\">✗ 로그인 필요</span>"
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.WARNING -> "<span class=\"dim\">(비활성)</span>"
            null -> "<span class=\"dim\">-</span>"
        }
        val authHint = if (claudeAuth?.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.ERROR) {
            """<p class="hint">로그인: <code>docker exec -it --user vibe vibe-coder-server claude login</code></p>"""
        } else ""

        return shell(
            title = "대시보드",
            username = username,
            currentPath = "/",
            csrf = csrf,
            body = """
<header><h1>대시보드</h1></header>

<section class="grid">
  <div class="card">
    <h2>서버</h2>
    <dl>
      <dt>이름</dt><dd>${esc(status.serverName)}</dd>
      <dt>버전</dt><dd>${esc(status.serverVersion)}</dd>
      <dt>JVM</dt><dd>${esc(status.javaVersion)}</dd>
      <dt>OS</dt><dd>${esc(status.osName)}</dd>
      <dt>워크스페이스</dt><dd>${esc(status.workspaceRoot)}</dd>
    </dl>
  </div>

  <div class="card">
    <h2>환경</h2>
    <dl>
      <dt>Claude CLI</dt><dd>$claudeBadge</dd>
      <dt>Claude 로그인</dt><dd>$authBadge</dd>
      <dt>Android SDK</dt><dd>$sdkBadge</dd>
    </dl>
    $authHint
    <p class="hint">SDK가 미설치면 컨테이너 안에서 <code>vibe-doctor</code> 를 실행하세요.</p>
  </div>

  <div class="card">
    <h2>활동</h2>
    <dl>
      <dt>프로젝트</dt><dd>${status.projectCount}개</dd>
      <dt>실행 중 빌드</dt><dd>${runningBuilds}개</dd>
      <dt>연결된 디바이스</dt><dd>${deviceCount}개</dd>
    </dl>
  </div>

  ${renderClaudeUsageCard(claudeUsage)}
  ${renderDiskUsageCard(diskSnapshot)}
</section>
"""
        )
    }

    /**
     * v0.29.0 — 대시보드 디스크 사용량 카드.
     */
    private fun renderDiskUsageCard(snap: com.siamakerlab.vibecoder.server.disk.DiskMonitor.Snapshot?): String {
        if (snap == null) {
            return """
  <div class="card">
    <h2>디스크 사용량 (v0.29.0)</h2>
    <p class="hint">아직 측정 안 됨. 백그라운드 monitor 가 다음 사이클(10분)에 갱신합니다.</p>
  </div>"""
        }
        val pct = snap.usedPercent
        val color = when {
            pct >= 95 -> "#dc2626"
            pct >= 85 -> "#d97706"
            else -> "#059669"
        }
        val totalGb = snap.totalBytes / 1_073_741_824.0
        return """
  <div class="card">
    <h2>디스크 사용량 (v0.29.0)</h2>
    <dl>
      <dt>사용량</dt><dd>${pct}%</dd>
      <dt>총 용량</dt><dd>${"%.1f".format(totalGb)} GB</dd>
      <dt>가용</dt><dd>${"%.1f".format(snap.freeGb)} GB</dd>
    </dl>
    <div style="margin-top:8px; background:#e5e7eb; border-radius:4px; height:8px; overflow:hidden;">
      <div style="width:${pct}%; background:${color}; height:100%;"></div>
    </div>
    <p class="hint">임계치 도달 시 등록된 이메일 / webhook 으로 알림. 캐시 정리: <a href="/settings/cache">/settings/cache</a></p>
  </div>"""
    }

    /**
     * v0.21.0 — 대시보드 Claude 사용량 카드.
     *
     * `ClaudeUsageMonitor` 의 마지막 snapshot 이 없거나 percent 추출 실패 시 비활성
     * 안내. 추출된 percent 가 있으면 80%↑ 노랑 / 95%↑ 빨강 strip + reset 시각.
     */
    private fun renderClaudeUsageCard(snapshot: com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto?): String {
        if (snapshot == null) {
            return """
  <div class="card">
    <h2>Claude 사용량 (v0.21.0)</h2>
    <p class="hint">아직 사용량 정보 없음. 백그라운드 폴링이 다음 사이클(기본 5분)에 갱신합니다. <code>/settings/email</code> 에서 임계치 조정 가능.</p>
  </div>"""
        }
        val pct = snapshot.usagePercent
        if (pct == null) {
            return """
  <div class="card">
    <h2>Claude 사용량 (v0.21.0)</h2>
    <dl>
      <dt>마지막 폴링</dt><dd>${esc(snapshot.updatedAt)}</dd>
      <dt>quota line</dt><dd><code>${esc(snapshot.quotaRemaining ?: "(파싱 실패)")}</code></dd>
    </dl>
    <p class="hint">Claude CLI <code>/status</code> 출력에서 percent 를 추출하지 못했습니다. CLI 버전이 새 포맷일 가능성.</p>
  </div>"""
        }
        val level = when {
            pct >= 95 -> "warn"
            pct >= 80 -> "warn"
            else -> "ok"
        }
        val color = when {
            pct >= 95 -> "#dc2626"
            pct >= 80 -> "#d97706"
            else -> "#059669"
        }
        val resetLine = if (snapshot.resetAt != null) {
            """<dt>리셋</dt><dd>${esc(snapshot.resetAt)}</dd>"""
        } else ""
        val barWidth = pct.coerceIn(0, 100)
        return """
  <div class="card">
    <h2>Claude 사용량 (v0.21.0)</h2>
    <dl>
      <dt>사용량</dt><dd><span class="$level">${pct}%</span></dd>
      $resetLine
      <dt>plan</dt><dd>${esc(snapshot.plan ?: "-")}</dd>
      <dt>model</dt><dd>${esc(snapshot.model ?: "-")}</dd>
    </dl>
    <div style="margin-top:8px; background:#e5e7eb; border-radius:4px; height:8px; overflow:hidden;">
      <div style="width:${barWidth}%; background:${color}; height:100%;"></div>
    </div>
    <p class="hint">임계치 도달 시 등록된 이메일로 알림. 설정: <a href="/settings/email">/settings/email</a></p>
  </div>"""
    }

    // ────────────────────────────────────────────────────────────────────
    // Settings
    // ────────────────────────────────────────────────────────────────────

    fun settingsPage(
        username: String,
        settings: SettingsView,
        flashOk: String? = null,
        flashErr: String? = null,
        csrf: String? = null,
        /** v0.77.0 — Phase 64 i18n. WebSession.language ("en"/"ko"). 모든 t() 호출에 사용. */
        lang: String = "en",
        /** v0.77.0 — 사용자 선택값 (null = 서버 default 사용). dropdown 의 currentValue. */
        userLanguage: String? = null,
        /** v0.77.0 — 서버 default ("en"/"ko"). dropdown 의 "Use server default (xx)" 라벨. */
        serverDefaultLanguage: String = "en",
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val tArgs = { key: String, args: Array<Any?> -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, *args) }
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val sysLabel = tArgs("settings.general.language.option.system", arrayOf<Any?>(serverDefaultLanguage))
        val sel = { v: String? -> if ((userLanguage ?: "") == (v ?: "")) "selected" else "" }
        return shell(
            title = t("settings.title"),
            username = username,
            currentPath = "/settings",
            csrf = csrf,
            lang = lang,
            body = """
<header><h1>${esc(t("settings.title"))}</h1></header>
$okHtml
$errHtml

<form method="post" action="/settings/language" class="settings-form">
  ${CsrfTokens.hiddenInput(csrf)}
  <fieldset>
    <legend>${esc(t("settings.general.language.title"))}</legend>
    <p class="hint">${esc(t("settings.general.language.body"))}</p>
    <label>${esc(t("settings.general.language.title"))}
      <select name="language">
        <option value="" ${sel(null)}>${esc(sysLabel)}</option>
        <option value="en" ${sel("en")}>${esc(t("settings.general.language.option.en"))}</option>
        <option value="ko" ${sel("ko")}>${esc(t("settings.general.language.option.ko"))}</option>
      </select>
    </label>
    <button type="submit" class="primary">${esc(t("settings.general.language.save"))}</button>
  </fieldset>
</form>

<form method="post" action="/settings" class="settings-form">
  ${CsrfTokens.hiddenInput(csrf)}

  <fieldset>
    <legend>${esc(t("common.name"))} (Server)</legend>
    <label>${esc(t("common.name"))} <input name="server.name" value="${esc(settings.serverName)}"></label>
    <label>Port <input name="server.port" type="number" value="${settings.serverPort}" min="1" max="65535"></label>
    <label>Host <input name="server.host" value="${esc(settings.serverHost)}"></label>
  </fieldset>

  <fieldset>
    <legend>Workspace</legend>
    <label>Max upload (MB) <input name="workspace.maxUploadSizeMb" type="number" value="${settings.maxUploadMb}"></label>
    <label>Artifact keep count <input name="workspace.artifactKeepCount" type="number" value="${settings.artifactKeep}"></label>
  </fieldset>

  <fieldset>
    <legend>Claude</legend>
    <label><input name="claude.enabled" type="checkbox" ${if (settings.claudeEnabled) "checked" else ""}> ${esc(t("common.enabled"))}</label>
    <label>Path <input name="claude.path" value="${esc(settings.claudePath)}"></label>
    <label>Timeout (min) <input name="claude.timeoutMinutes" type="number" value="${settings.claudeTimeoutMin}"></label>
  </fieldset>

  <fieldset>
    <legend>Build</legend>
    <label>Timeout (min) <input name="build.timeoutMinutes" type="number" value="${settings.buildTimeoutMin}"></label>
    <label>Default debug task <input name="build.defaultDebugTask" value="${esc(settings.defaultDebugTask)}"></label>
  </fieldset>

  <button type="submit" class="primary">${esc(t("common.save"))}</button>
  <p class="hint">Saves to external <code>server.yml</code> atomically (<code>.bak.&lt;ts&gt;</code> rotation, keeps 5). Path: <code>${'$'}VIBECODER_CONFIG_DIR/server.yml</code> or <code>./config/server.yml</code>. <strong>host / port / name require container restart.</strong></p>
</form>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Password
    // ────────────────────────────────────────────────────────────────────

    fun passwordPage(
        username: String,
        flashOk: String? = null,
        flashErr: String? = null,
        csrf: String? = null,
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        return shell(
            title = "비밀번호 변경",
            username = username,
            currentPath = "/password",
            csrf = csrf,
            body = """
<header><h1>비밀번호 변경</h1></header>
$okHtml
$errHtml
<form method="post" action="/password" class="auth-card narrow">
  ${CsrfTokens.hiddenInput(csrf)}
  <label>현재 비밀번호
    <input name="currentPassword" type="password" required>
  </label>
  <label>새 비밀번호 (영문+숫자 8자 이상)
    <input name="newPassword" type="password" required minlength="8">
  </label>
  <label>새 비밀번호 확인
    <input name="newPasswordConfirm" type="password" required minlength="8">
  </label>
  <button type="submit" class="primary">변경</button>
</form>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Devices
    // ────────────────────────────────────────────────────────────────────

    fun devicesPage(
        username: String,
        devices: List<DeviceRow>,
        currentDeviceId: String,
        flashOk: String? = null,
        csrf: String? = null,
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val rows = devices.joinToString("\n") { d ->
            val isCurrent = d.id == currentDeviceId
            val action = if (isCurrent) {
                """<span class="dim">(현재 세션)</span>"""
            } else {
                """<form method="post" action="/devices/${esc(d.id)}/revoke" style="display:inline">
                     ${CsrfTokens.hiddenInput(csrf)}
                     <button type="submit" class="danger">revoke</button>
                   </form>"""
            }
            """<tr>
                <td>${esc(d.name)}</td>
                <td>${esc(d.channel)}</td>
                <td>${esc(d.createdAt)}</td>
                <td>${esc(d.lastSeenAt ?: "-")}</td>
                <td>$action</td>
              </tr>"""
        }
        return shell(
            title = "디바이스",
            username = username,
            currentPath = "/devices",
            csrf = csrf,
            body = """
<header><h1>연결된 디바이스</h1></header>
$okHtml
<table class="devices">
  <thead>
    <tr><th>이름</th><th>채널</th><th>생성</th><th>최근 접속</th><th></th></tr>
  </thead>
  <tbody>
    $rows
  </tbody>
</table>
<p class="hint">revoke 시 해당 토큰이 즉시 무효화됩니다. 사용 중이던 앱/브라우저는 재로그인 필요.</p>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // 에러 페이지
    // ────────────────────────────────────────────────────────────────────

    fun errorPage(code: Int, message: String): String = shell(
        title = "오류 $code",
        showNav = false,
        body = """
<div class="auth-card">
  <h1>오류 $code</h1>
  <p>${esc(message)}</p>
  <a href="/" class="primary-link">대시보드로</a>
</div>
"""
    )

    data class SettingsView(
        val serverName: String,
        val serverPort: Int,
        val serverHost: String,
        val maxUploadMb: Long,
        val artifactKeep: Int,
        val claudeEnabled: Boolean,
        val claudePath: String,
        val claudeTimeoutMin: Int,
        val buildTimeoutMin: Int,
        val defaultDebugTask: String,
    )
}
