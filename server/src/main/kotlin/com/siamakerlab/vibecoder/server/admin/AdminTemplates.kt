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
        /**
         * v0.77.0 — Phase 64 i18n. WebSession.language ("en"/"ko"). nav/tabBar 라벨 분기.
         * v1.24.0 — default 제거. 모든 호출자가 명시적으로 sess.language 전달해야 함.
         * 정밀 점검에서 default 가 누락 site 를 silently fall-through 시켜 14개 회귀
         * 발견됐기에, 컴파일 단계에서 강제 노출하도록 변경.
         */
        lang: String,
        /**
         * v1.16.0 — `.content.fullbleed` 변형 사용. ProjectTabsTemplate 처럼 자체적으로
         * viewport 100% 안에서 layout 을 구성하는 page 가 true 로 호출. .content 의 padding
         * / max-width 제거 + overflow hidden → 자식이 직접 100% 박스 사용.
         */
        fullbleed: Boolean = false,
    ): String {
        val nav = if (showNav) navHtml(currentPath, username, csrf, lang) else ""
        val layoutCls = if (showNav) "layout" else "layout no-nav"
        val contentCls = if (fullbleed) "content fullbleed" else "content"
        // v1.27.3 Q1 회수: fullbleed 페이지 (예: /settings/tabs, /tools/tabs) 는 자체
        // iframe 탭 UI 를 갖는다. 여기에 settings tabBar 까지 주입하면 탭바가 2줄로
        // 중복 노출 (SettingsTabsTemplate 가 currentPath="/settings" 라 topLevelOf=="settings"
        // → maybeTabs 주입 + iframe 내부 .tab-bar 동시). fullbleed 면 외부 tabBar 생략.
        // 일반 settings sub-page (직접 접근, fullbleed=false) 는 그대로 tabBar 노출.
        val maybeTabs =
            if (showNav && !fullbleed && SettingsNav.topLevelOf(currentPath) == "settings")
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
  <link rel="stylesheet" href="/static/admin.css?v=1.70.2">
  <script>
    // v1.6.2 — 사이드바 접힘 상태를 first paint 전에 :root data-attribute 로 적용 (FOUC 회피).
    // CSS 의 :root[data-sidebar-collapsed="1"] .layout 가 grid-template-columns 축소.
    // v1.33.2 — 모바일 폭(≤768px)에선 collapsed 를 무시한다. 전역 collapsed 룰
    // (specificity (0,2,1))이 768px media 의 가로 헤더 룰((0,1,0))을 이겨, 데스크톱에서
    // 접어둔 사용자가 모바일로 오면 가로 헤더가 56px 로 찌그러지던 문제(17차 BUG-1).
    // first-paint + resize(matchMedia change) 양쪽에서 폭 인지하여 attribute 토글.
    (function(){
      var KEY = 'vibe.sidebar.collapsed';
      var mq = window.matchMedia('(max-width: 768px)');
      function apply(){
        var collapsed = false;
        try { collapsed = localStorage.getItem(KEY) === '1'; } catch(e) {}
        document.documentElement.dataset.sidebarCollapsed = (collapsed && !mq.matches) ? '1' : '0';
      }
      apply();
      if (mq.addEventListener) mq.addEventListener('change', apply);
    })();
  </script>
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
    <main class="$contentCls">
      $maybeTabs
      $body
    </main>
  </div>
</body>
</html>
"""
    }

    /**
     * v1.7.6 — Lucide-styled inline SVG (MIT). 외부 CDN 미사용 정책 일관.
     * 24x24 viewBox + stroke currentColor → 다크/라이트 테마 자동 적응. width/height
     * 20px 는 sidebar nav 의 적정 시각 크기.
     */
    private fun navIcon(name: String): String {
        val inner = when (name) {
            // Lucide "home"
            "home" -> """<path d="M3 12 L12 3 L21 12"/><path d="M5 10 V20 H10 V14 H14 V20 H19 V10"/>"""
            // Lucide "folder"
            "folder" -> """<path d="M3 7 a2 2 0 0 1 2-2 h4 l2 2 h8 a2 2 0 0 1 2 2 v8 a2 2 0 0 1-2 2 H5 a2 2 0 0 1-2-2 z"/>"""
            // Lucide "message-square"
            "chat" -> """<path d="M21 15 a2 2 0 0 1-2 2 H7 l-4 4 V5 a2 2 0 0 1 2-2 h14 a2 2 0 0 1 2 2 z"/>"""
            // Lucide "wrench" (단순화)
            "tools" -> """<path d="M14.7 6.3 a4 4 0 0 0-5.4 5.4 L3 18 l3 3 L12.3 14.7 a4 4 0 0 0 5.4-5.4 l-2 2 l-2-2 l2-2 z"/>"""
            // Lucide "settings"
            "settings" -> """<circle cx="12" cy="12" r="3"/><path d="M19.4 15 a1.65 1.65 0 0 0 .33 1.82 l.06.06 a2 2 0 1 1-2.83 2.83 l-.06-.06 a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51 V21 a2 2 0 0 1-4 0 v-.09 A1.65 1.65 0 0 0 9 19.4 a1.65 1.65 0 0 0-1.82.33 l-.06.06 a2 2 0 1 1-2.83-2.83 l.06-.06 a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1 H3 a2 2 0 0 1 0-4 h.09 A1.65 1.65 0 0 0 4.6 9 a1.65 1.65 0 0 0-.33-1.82 l-.06-.06 a2 2 0 1 1 2.83-2.83 l.06.06 a1.65 1.65 0 0 0 1.82.33 H9 a1.65 1.65 0 0 0 1-1.51 V3 a2 2 0 0 1 4 0 v.09 a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33 l.06-.06 a2 2 0 1 1 2.83 2.83 l-.06.06 a1.65 1.65 0 0 0-.33 1.82 V9 a1.65 1.65 0 0 0 1.51 1 H21 a2 2 0 0 1 0 4 h-.09 a1.65 1.65 0 0 0-1.51 1 z"/>"""
            // v1.27.0 — Lucide "terminal-square" (단순화). > prompt + horizontal cursor.
            "terminal" -> """<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9 L10 12 L7 15"/><path d="M12 16 H17"/>"""
            else -> ""
        }
        return """<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="20" height="20" aria-hidden="true">$inner</svg>"""
    }

    // v1.24.1 — lang default 제거. shell() 의 lang required 변경과 일관 — helper
    // 호출자가 무심코 lang 안 넘기면 "en" 으로 fall-through 회귀 가능성 차단.
    private fun navHtml(currentPath: String, username: String?, csrf: String?, lang: String): String {
        val activeTop = SettingsNav.topLevelOf(currentPath)
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        fun link(href: String, label: String, key: String, icon: String): String {
            val cls = if (activeTop == key) "active" else ""
            return """<a href="${esc(href)}" class="${esc(cls)}" data-key="${esc(key)}" title="${esc(label)}">${navIcon(icon)}<span class="nav-label">${esc(label)}</span></a>"""
        }
        val userBoxHtml: String = if (username != null) {
            val u = esc(username)
            "<div class=\"user\">$u</div>"
        } else ""
        val csrfInput = CsrfTokens.hiddenInput(csrf)
        return """
<nav class="sidebar">
  <!-- v1.7.6 — 브랜드: 아이콘 위 + 타이틀 아래 (column flex). 전체가 홈으로 가는 link.
       접힘 상태에선 .brand-title 숨김 + 아이콘만 노출. -->
  <a href="/" class="brand" title="Vibe Coder">
    <img src="/static/icon.png" alt="Vibe Coder" class="brand-icon">
    <span class="brand-title">Vibe Coder</span>
  </a>
  <button type="button" class="sidebar-toggle" id="sidebar-toggle"
          title="${esc(t("nav.collapseToggle"))}"
          aria-label="${esc(t("nav.collapseToggle"))}">⇆</button>
  <div class="nav-links">
    ${link("/", t("nav.home"), "dashboard", "home")}
    ${link("/projects", t("nav.projects"), "projects", "folder")}
    ${link("/chat", t("nav.chat"), "chat", "chat")}
    ${link("/tools/tabs", t("nav.tools"), "tools", "tools")}
    ${link("/terminal", t("nav.terminal"), "terminal", "terminal")}
    ${link("/settings/tabs", t("nav.settings"), "settings", "settings")}
  </div>
  <!-- v1.65.0 — 무선디버깅 상태 pill (Claude 사용량 위). adb 미설치면 hidden. 클릭 시 /adb. -->
  <a id="adb-pill" class="quota-pill adb-pill" href="/adb" title="${esc(t("nav.adb.title"))}" hidden>
    <div class="qp-header">
      <span class="qp-h-title">${esc(t("nav.adb.title"))}</span>
      <span id="adb-pill-dot" class="adb-dot"></span>
    </div>
    <div class="adb-pill-body"><span id="adb-pill-status" class="dim">…</span> · <strong id="adb-pill-n">0</strong> ${esc(t("nav.adb.devices"))}</div>
  </a>
  <!-- v1.3.2 — 전역 Claude 쿼타 pill. v1.6.2 — header 에 refresh 버튼 + 타임존 제거. -->
  <div id="quota-pill" class="quota-pill" hidden></div>
  <div class="user-box">
    $userBoxHtml
    <form method="post" action="/logout">
      $csrfInput
      <button type="submit" class="logout">${esc(t("nav.logout"))}</button>
    </form>
  </div>
</nav>
<script>
(function(){
  // 1) Sidebar collapse toggle.
  var sbBtn = document.getElementById('sidebar-toggle');
  if (sbBtn) {
    sbBtn.addEventListener('click', function(){
      var collapsed = document.documentElement.dataset.sidebarCollapsed === '1';
      var next = !collapsed;
      document.documentElement.dataset.sidebarCollapsed = next ? '1' : '0';
      try { localStorage.setItem('vibe.sidebar.collapsed', next ? '1' : '0'); } catch(e){}
    });
  }
  // 2) Quota pill — refresh 버튼 + 타임존 (괄호) strip.
  var el = document.getElementById('quota-pill');
  if (!el) return;
  var sessLabel = '${esc(t("quota.session"))}';
  var weekLabel = '${esc(t("quota.weekly"))}';
  var resetLabel = '${esc(t("quota.resetPrefix"))}';
  var refreshTitle = '${esc(t("quota.refresh"))}';
  function stripTz(reset) {
    if (!reset) return '';
    // v1.6.2 — "Resets 10:20pm (Asia/Seoul)" → "10:20pm".
    // v1.7.1 — Kotlin raw string 안에서 \\s 가 JS 로 그대로 새서 매칭 실패하던
    // 회귀 fix. raw string 은 escape 안 하므로 \s 한 backslash 로 작성.
    return reset
      .replace(/^Resets\s+/i, '')
      .replace(/\s*\([^)]*\)\s*/g, '')
      .trim();
  }
  function bar(label, pct, reset) {
    var safePct = Math.max(0, Math.min(100, pct|0));
    var color = safePct >= 95 ? '#dc2626' : (safePct >= 80 ? '#e08300' : 'var(--accent, #3b82f6)');
    // v1.7.2 — "초기화" / "resets" prefix 도 제거 (사용자 요구 — 사이드바 좁은 공간 노이즈).
    // stripTz 가 이미 "Resets" 단어를 제거하므로 cleanReset 만 그대로 표시.
    var cleanReset = stripTz(reset);
    var resetHtml = cleanReset ? '<div class="qp-reset">' + cleanReset + '</div>' : '';
    return '<div class="qp-row"><div class="qp-row-head"><span class="qp-label">' + label + '</span><span class="qp-pct" style="color:' + color + '">' + safePct + '%</span></div>'
      + '<div class="qp-track"><div class="qp-fill" style="width:' + safePct + '%;background:' + color + '"></div></div>'
      + resetHtml + '</div>';
  }
  function render(dto) {
    var rows = '';
    if (dto && (dto.sessionUsagePercent != null || dto.weeklyUsagePercent != null)) {
      if (dto.sessionUsagePercent != null)
        rows += bar(sessLabel, dto.sessionUsagePercent, dto.sessionResetAt);
      if (dto.weeklyUsagePercent != null)
        rows += bar(weekLabel, dto.weeklyUsagePercent, dto.weeklyResetAt);
    } else if (dto && dto.usagePercent != null) {
      rows += bar(sessLabel, dto.usagePercent, dto.resetAt);
    }
    if (!rows) { el.hidden = true; return; }
    el.innerHTML = '<div class="qp-header"><span class="qp-h-title">Claude</span>'
      + '<button type="button" class="qp-refresh" title="' + refreshTitle + '" aria-label="' + refreshTitle + '">↻</button>'
      + '</div>' + rows;
    el.hidden = false;
    var btn = el.querySelector('.qp-refresh');
    if (btn) btn.addEventListener('click', function(){
      btn.disabled = true; btn.textContent = '…';
      tick().then(function(){ btn.disabled = false; });
    });
  }
  function tick() {
    return fetch('/api/server/quota', { credentials: 'same-origin' })
      .then(function(r){ return r.ok ? r.json() : null; })
      .then(render)
      .catch(function(){ el.hidden = true; });
  }
  tick();
  setInterval(tick, 60000);

  // v1.65.0 — 무선디버깅 pill 폴링. adb 사용 가능 시 노출(연결 0대도 표시), 미설치면 hidden.
  var adbEl = document.getElementById('adb-pill');
  var adbN = document.getElementById('adb-pill-n');
  var adbStatus = document.getElementById('adb-pill-status');
  var adbDot = document.getElementById('adb-pill-dot');
  if (adbEl && adbN) {
    var ADB_AVAIL = '${esc(t("nav.adb.available"))}';
    var ADB_NONE = '${esc(t("nav.adb.noDevice"))}';
    function adbTick() {
      fetch('/api/adb/status', { credentials: 'same-origin' })
        .then(function(r){ return r.ok ? r.json() : null; })
        .then(function(s){
          if (!s || !s.available) { adbEl.hidden = true; return; }
          var n = s.connected || 0;
          adbN.textContent = n;
          adbStatus.textContent = n > 0 ? ADB_AVAIL : ADB_NONE;
          adbStatus.className = n > 0 ? 'ok' : 'dim';
          if (adbDot) adbDot.className = 'adb-dot' + (n > 0 ? ' on' : '');
          adbEl.hidden = false;
        })
        .catch(function(){ adbEl.hidden = true; });
    }
    adbTick();
    setInterval(adbTick, 30000);
  }
})();
</script>
"""
    }

    // ────────────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────────────

    fun setupPage(error: String? = null, lang: String): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (error != null) """<div class="error">${esc(error)}</div>""" else ""
        return shell(
            title = t("auth.setup.title"),
            showNav = false,
            lang = lang,
            body = """
<div class="auth-card">
  <h1>${esc(t("auth.setup.heading"))}</h1>
  <p class="dim">${esc(t("auth.setup.intro"))}</p>
  $errHtml
  <form method="post" action="/setup">
    <label>${esc(t("auth.setup.usernameLabel"))}
      <input name="username" required minlength="3" maxlength="32" autofocus
             pattern="[A-Za-z0-9._-]{3,32}">
    </label>
    <label>${esc(t("auth.setup.passwordHint"))}
      <input name="password" type="password" required minlength="8">
    </label>
    <label>${esc(t("auth.setup.passwordConfirm"))}
      <input name="passwordConfirm" type="password" required minlength="8">
    </label>
    <button type="submit" class="primary">${esc(t("auth.setup.submit"))}</button>
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
        /** v0.78.0 — Phase 64 i18n. 로그인 전이라 user 식별 안 됨 → server default 만 사용. */
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (error != null) """<div class="error">${esc(error)}</div>""" else ""
        val nextField = if (next != null) """<input type="hidden" name="next" value="${esc(next)}">""" else ""
        // v0.26.0 — 2단계 (TOTP) 폼.
        if (totpUsername != null && totpPassword != null) {
            return shell(
                title = t("auth.login.totp.title"),
                showNav = false,
                lang = lang,
                body = """
<div class="auth-card">
  <h1>${esc(t("auth.login.totp.heading"))}</h1>
  <p class="hint">${esc(t("auth.login.totp.body"))}</p>
  $errHtml
  <form method="post" action="/login">
    $nextField
    <input type="hidden" name="username" value="${esc(totpUsername)}">
    <input type="hidden" name="password" value="${esc(totpPassword)}">
    <label>${esc(t("auth.login.totp.code"))}
      <input name="totpCode" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autofocus>
    </label>
    <button type="submit" class="primary">${esc(t("auth.login.totp.submit"))}</button>
  </form>
  <p class="hint" style="margin-top:12px"><a href="/login">${esc(t("auth.login.totp.back"))}</a></p>
</div>
"""
            )
        }
        return shell(
            title = t("auth.login.title"),
            showNav = false,
            lang = lang,
            body = """
<div class="auth-card">
  <h1>${esc(t("auth.login.heading"))}</h1>
  $errHtml
  <form method="post" action="/login" id="login-form">
    $nextField
    <label>${esc(t("auth.login.usernameLabel"))}
      <input name="username" id="login-username" required autofocus>
    </label>
    <label>${esc(t("auth.login.passwordLabel"))}
      <input name="password" type="password" required>
    </label>
    <button type="submit" class="primary">${esc(t("auth.login.submit"))}</button>
  </form>
  <hr style="margin:18px 0;border-color:#222">
  <div style="text-align:center">
    <button type="button" id="passkey-login-btn" class="chip chip-link"
            style="font-size:13px;padding:8px 14px" disabled>
      ${esc(t("auth.login.passkey.btn"))}
    </button>
    <p class="hint" id="passkey-status" style="margin:8px 0 0;font-size:11px">${esc(t("auth.login.passkey.hint.disabled"))}</p>
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
    status.textContent = ${jsLitString(t("auth.login.passkey.fetching"))};
    try {
      var optsRes = await fetch('/api/webauthn/assert/options', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: usernameInput.value.trim() }),
      });
      if (!optsRes.ok) throw new Error('options ' + optsRes.status);
      var opts = await optsRes.json();
      if (!opts.allowCredentialIds || opts.allowCredentialIds.length === 0) {
        status.textContent = ${jsLitString(t("auth.login.passkey.noCred"))}; btn.disabled = false; return;
      }
      status.textContent = ${jsLitString(t("auth.login.passkey.using"))};
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
      status.textContent = ${jsLitString(t("auth.login.passkey.fail"))} + (e.message || e);
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
        /** v1.9.0 — git global identity 미설정 시 dashboard 상단에 yellow banner. */
        gitIdentityMissing: Boolean = false,
        csrf: String? = null,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val tArgs = { key: String, args: Array<Any?> -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, *args) }
        // v1.9.0 — git identity 미설정 banner. 사용자가 첫 빌드 / 첫 commit 전에 입력하지
        // 않으면 의도와 다른 author 로 commit 되어 GitHub 매칭 누락. dashboard 가 첫 진입
        // 페이지라 여기에 노출.
        val gitIdentityBanner = if (gitIdentityMissing) """
<div class="card" style="margin-bottom:16px;border-color:var(--warn);background:rgba(255,200,0,0.06)">
  <h2 style="margin:0 0 6px;color:var(--warn)">⚠ ${esc(t("dashboard.gitIdentity.title"))}</h2>
  <p style="margin:0 0 8px;font-size:13px;line-height:1.5">${esc(t("dashboard.gitIdentity.body"))}</p>
  <a href="/env-setup#git-identity" class="primary chip" style="display:inline-block;padding:8px 16px">
    ${esc(t("dashboard.gitIdentity.cta"))}
  </a>
</div>
""" else ""
        val claudeBadge = if (status.claudeAvailable) "<span class=\"ok\">${esc(t("dashboard.claudeOk"))}</span>" else "<span class=\"warn\">${esc(t("dashboard.claudeMissing"))}</span>"
        val sdkBadge = if (status.androidSdkAvailable) "<span class=\"ok\">${esc(t("dashboard.sdkOk"))}</span>" else "<span class=\"warn\">${esc(t("dashboard.sdkMissing"))}</span>"
        // v1.7.8 — WARNING 의 본 의미가 "Claude 비활성" 이 아니라 "만료 임박 / 형식 확인 불가"
        // 인데 dashboard.disabled "(비활성)" 라벨이 false positive 였음 — 사용자 보고. 의미
        // 명확한 dashboard.signinExpiring 으로 대체.
        val authBadge = when (claudeAuth?.status) {
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.OK -> "<span class=\"ok\">${esc(t("dashboard.signedIn"))}</span>"
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.ERROR -> "<span class=\"warn\">${esc(t("dashboard.signinRequired"))}</span>"
            com.siamakerlab.vibecoder.shared.dto.CheckStatus.WARNING -> "<span class=\"warn\">${esc(t("dashboard.signinExpiring"))}</span>"
            null -> "<span class=\"dim\">-</span>"
        }
        val authHint = if (claudeAuth?.status == com.siamakerlab.vibecoder.shared.dto.CheckStatus.ERROR) {
            """<p class="hint">${t("dashboard.claudeLoginHint")}</p>"""
        } else ""

        return shell(
            title = t("dashboard.title"),
            username = username,
            currentPath = "/",
            csrf = csrf,
            lang = lang,
            body = """
<header><h1>${esc(t("dashboard.heading"))}</h1></header>
$gitIdentityBanner
<section class="grid">
  <div class="card">
    <h2>${esc(t("dashboard.card.server"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.card.server.name"))}</dt><dd>${esc(status.serverName)}</dd>
      <dt>${esc(t("dashboard.card.server.version"))}</dt><dd>${esc(status.serverVersion)}</dd>
      <dt>${esc(t("dashboard.card.server.jvm"))}</dt><dd>${esc(status.javaVersion)}</dd>
      <dt>${esc(t("dashboard.card.server.os"))}</dt><dd>${esc(status.osName)}</dd>
      <dt>${esc(t("dashboard.card.server.workspace"))}</dt><dd>${esc(status.workspaceRoot)}</dd>
    </dl>
  </div>

  <div class="card">
    <h2>${esc(t("dashboard.card.env"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.card.env.claudeCli"))}</dt><dd>$claudeBadge</dd>
      <dt>${esc(t("dashboard.card.env.claudeAuth"))}</dt><dd>$authBadge</dd>
      <dt>${esc(t("dashboard.card.env.androidSdk"))}</dt><dd>$sdkBadge</dd>
    </dl>
    $authHint
    <p class="hint">${t("dashboard.card.env.doctorHint")}</p>
  </div>

  <div class="card">
    <h2>${esc(t("dashboard.card.activity"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.card.activity.projects"))}</dt><dd>${esc(tArgs("dashboard.card.count", arrayOf<Any?>(status.projectCount)))}</dd>
      <dt>${esc(t("dashboard.card.activity.runningBuilds"))}</dt><dd>${esc(tArgs("dashboard.card.count", arrayOf<Any?>(runningBuilds)))}</dd>
      <dt>${esc(t("dashboard.card.activity.devices"))}</dt><dd>${esc(tArgs("dashboard.card.count", arrayOf<Any?>(deviceCount)))}</dd>
    </dl>
  </div>

  ${renderClaudeUsageCard(claudeUsage, lang)}
  ${renderDiskUsageCard(diskSnapshot, lang)}

  <!-- v1.63.0 — 무선 ADB 기기 상태 카드. /api/adb/status 폴링(클라이언트). -->
  <div class="card" id="adb-card"
       data-avail="${esc(t("dashboard.adb.available"))}"
       data-unavail="${esc(t("dashboard.adb.unavailable"))}">
    <h2>${esc(t("dashboard.adb.title"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.adb.statusLabel"))}</dt><dd id="adb-card-avail" class="dim">…</dd>
      <dt>${esc(t("dashboard.adb.connected"))}</dt><dd id="adb-card-count" class="dim">-</dd>
    </dl>
    <p class="hint"><a href="/adb">${esc(t("dashboard.adb.manage"))}</a></p>
  </div>
</section>
<script>
(function() {
  var card = document.getElementById('adb-card');
  if (!card) return;
  var availEl = document.getElementById('adb-card-avail');
  var cntEl = document.getElementById('adb-card-count');
  function tick() {
    fetch('/api/adb/status', { credentials: 'same-origin' })
      .then(function(r) { return r.ok ? r.json() : null; })
      .then(function(s) {
        if (!s) return;
        if (s.available) { availEl.textContent = card.dataset.avail; availEl.className = 'ok'; }
        else { availEl.textContent = card.dataset.unavail; availEl.className = 'warn'; }
        var n = s.connected || 0;
        cntEl.textContent = n;
        cntEl.className = n > 0 ? 'ok' : 'dim';
      }).catch(function() {});
  }
  tick();
  setInterval(tick, 30000);
})();
</script>
"""
        )
    }

    /**
     * v0.29.0 — 대시보드 디스크 사용량 카드.
     */
    private fun renderDiskUsageCard(snap: com.siamakerlab.vibecoder.server.disk.DiskMonitor.Snapshot?, lang: String): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (snap == null) {
            return """
  <div class="card">
    <h2>${esc(t("dashboard.disk.title"))}</h2>
    <p class="hint">${esc(t("dashboard.disk.empty"))}</p>
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
    <h2>${esc(t("dashboard.disk.title"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.disk.usage"))}</dt><dd>${pct}%</dd>
      <dt>${esc(t("dashboard.disk.total"))}</dt><dd>${"%.1f".format(totalGb)} GB</dd>
      <dt>${esc(t("dashboard.disk.free"))}</dt><dd>${"%.1f".format(snap.freeGb)} GB</dd>
    </dl>
    <div style="margin-top:8px; background:#e5e7eb; border-radius:4px; height:8px; overflow:hidden;">
      <div style="width:${pct}%; background:${color}; height:100%;"></div>
    </div>
    <p class="hint">${t("dashboard.diskHint")} <a href="/settings/cache">/settings/cache</a></p>
  </div>"""
    }

    /**
     * v0.21.0 — 대시보드 Claude 사용량 카드.
     *
     * `ClaudeUsageMonitor` 의 마지막 snapshot 이 없거나 percent 추출 실패 시 비활성
     * 안내. 추출된 percent 가 있으면 80%↑ 노랑 / 95%↑ 빨강 strip + reset 시각.
     */
    private fun renderClaudeUsageCard(snapshot: com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto?, lang: String): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (snapshot == null) {
            return """
  <div class="card">
    <h2>${esc(t("dashboard.claude.title"))}</h2>
    <p class="hint">${t("dashboard.claude.empty")}</p>
  </div>"""
        }
        // v1.0.1 — Pro/Max plan 의 세션 (5h) + 주간 (7d) 분리 표시.
        val sessionPct = snapshot.sessionUsagePercent
        val weeklyPct = snapshot.weeklyUsagePercent
        val pct = snapshot.usagePercent
        if (pct == null && sessionPct == null && weeklyPct == null) {
            return """
  <div class="card">
    <h2>${esc(t("dashboard.claude.title"))}</h2>
    <dl>
      <dt>${esc(t("dashboard.claude.lastPolled"))}</dt><dd>${esc(snapshot.updatedAt)}</dd>
      <dt>${esc(t("dashboard.usageQuotaLine"))}</dt><dd><code>${esc(snapshot.quotaRemaining ?: t("dashboard.usageParseFailed"))}</code></dd>
    </dl>
    <p class="hint">${t("dashboard.claude.quotaParseFail")}</p>
  </div>"""
        }

        fun barColor(p: Int): String = when {
            p >= 95 -> "#dc2626"
            p >= 80 -> "#d97706"
            else -> "#059669"
        }
        fun levelClass(p: Int): String = if (p >= 80) "warn" else "ok"
        fun renderBar(p: Int, label: String, resetLabel: String?, resetVal: String?): String {
            val w = p.coerceIn(0, 100)
            val resetHtml = if (resetVal != null && resetLabel != null) {
                """<div class="dim" style="font-size:11px;margin-top:2px">${esc(resetLabel)}: ${esc(resetVal)}</div>"""
            } else ""
            return """
      <div style="margin-bottom:10px">
        <div style="display:flex;justify-content:space-between;font-size:12px">
          <span>${esc(label)}</span>
          <span class="${levelClass(p)}">${p}%</span>
        </div>
        <div style="background:#e5e7eb;border-radius:4px;height:8px;overflow:hidden;margin-top:3px">
          <div style="width:${w}%;background:${barColor(p)};height:100%"></div>
        </div>
        $resetHtml
      </div>"""
        }

        val sessionBar = if (sessionPct != null) {
            renderBar(sessionPct, t("dashboard.usage.session"),
                t("dashboard.usage.sessionReset"), snapshot.sessionResetAt)
        } else ""
        val weeklyBar = if (weeklyPct != null) {
            renderBar(weeklyPct, t("dashboard.usage.weekly"),
                t("dashboard.usage.weeklyReset"), snapshot.weeklyResetAt)
        } else ""
        // 둘 다 없고 legacy pct 만 있는 경우 — 한 줄 bar 만 (parsing 이 weekly/session
        // 구분 못 한 경우 fallback).
        val legacyBar = if (sessionPct == null && weeklyPct == null && pct != null) {
            renderBar(pct, t("dashboard.disk.usage"),
                t("dashboard.usageReset"), snapshot.resetAt)
        } else ""

        return """
  <div class="card">
    <h2>${esc(t("dashboard.claude.title"))}</h2>
    $sessionBar
    $weeklyBar
    $legacyBar
    <dl style="margin-top:6px;font-size:12px">
      <dt>${esc(t("dashboard.claude.plan"))}</dt><dd>${esc(snapshot.plan ?: "-")}</dd>
      <dt>${esc(t("dashboard.claude.model"))}</dt><dd>${esc(snapshot.model ?: "-")}</dd>
    </dl>
    <p class="hint">${t("dashboard.usageEmailHint")} <a href="/settings/email">/settings/email</a></p>
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
        lang: String,
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

<!-- v1.31.3 — 기존 quicklinks(모든 sub-page 평면 중복 나열)를 제거하고 각 sub-page 를
     소속 카테고리 대표 페이지의 sub-nav(SettingsNav.categoryNav)로 분산. 일반설정에는
     language + 서버/워크스페이스/Claude/빌드 config 만 남긴다. -->

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
    <legend>${esc(t("settings.legend.server"))}</legend>
    <label>${esc(t("common.name"))} <input name="server.name" value="${esc(settings.serverName)}"></label>
    <label>${esc(t("settings.field.port"))} <input name="server.port" type="number" value="${settings.serverPort}" min="1" max="65535"></label>
    <label>${esc(t("settings.field.host"))} <input name="server.host" value="${esc(settings.serverHost)}"></label>
  </fieldset>

  <fieldset>
    <legend>${esc(t("settings.legend.workspace"))}</legend>
    <label>${esc(t("settings.field.maxUploadMb"))} <input name="workspace.maxUploadSizeMb" type="number" value="${settings.maxUploadMb}"></label>
    <label>${esc(t("settings.field.artifactKeepCount"))} <input name="workspace.artifactKeepCount" type="number" value="${settings.artifactKeep}"></label>
  </fieldset>

  <fieldset>
    <legend>${esc(t("settings.legend.claude"))}</legend>
    <label><input name="claude.enabled" type="checkbox" ${if (settings.claudeEnabled) "checked" else ""}> ${esc(t("common.enabled"))}</label>
    <label>${esc(t("settings.field.path"))} <input name="claude.path" value="${esc(settings.claudePath)}"></label>
    <label>${esc(t("settings.field.timeoutMin"))} <input name="claude.timeoutMinutes" type="number" value="${settings.claudeTimeoutMin}"></label>
    <label>${esc(t("settings.field.maxConcurrentTurns"))} <input name="claude.maxConcurrentTurns" type="number" min="0" max="20" value="${settings.claudeMaxConcurrent}"></label>
    <p class="hint">${esc(t("settings.field.maxConcurrentTurns.hint"))}</p>
  </fieldset>

  <fieldset>
    <legend>${esc(t("settings.legend.build"))}</legend>
    <label>${esc(t("settings.field.timeoutMin"))} <input name="build.timeoutMinutes" type="number" value="${settings.buildTimeoutMin}"></label>
    <label>${esc(t("settings.field.defaultDebugTask"))} <input name="build.defaultDebugTask" value="${esc(settings.defaultDebugTask)}"></label>
  </fieldset>

  <button type="submit" class="primary">${esc(t("common.save"))}</button>
  <p class="hint">${t("settings.persist.hint")}</p>
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
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        return shell(
            title = t("password.title"),
            username = username,
            currentPath = "/password",
            csrf = csrf,
            lang = lang,
            body = """
<header><h1>${esc(t("password.heading"))}</h1></header>
${SettingsNav.categoryNav("/password", lang)}
$okHtml
$errHtml
<form method="post" action="/password" class="auth-card narrow">
  ${CsrfTokens.hiddenInput(csrf)}
  <label>${esc(t("password.currentLabel"))}
    <input name="currentPassword" type="password" required>
  </label>
  <label>${esc(t("password.newLabel"))}
    <input name="newPassword" type="password" required minlength="8">
  </label>
  <label>${esc(t("password.confirmLabel"))}
    <input name="newPasswordConfirm" type="password" required minlength="8">
  </label>
  <button type="submit" class="primary">${esc(t("password.submit"))}</button>
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
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val rows = devices.joinToString("\n") { d ->
            val isCurrent = d.id == currentDeviceId
            val action = if (isCurrent) {
                """<span class="dim">${esc(t("devices.currentSession"))}</span>"""
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
            title = t("devices.title"),
            username = username,
            currentPath = "/devices",
            csrf = csrf,
            lang = lang,
            body = """
<header><h1>${esc(t("devices.title"))}</h1></header>
$okHtml
<table class="devices">
  <thead>
    <tr><th>${esc(t("devices.column.name"))}</th><th>${esc(t("common.type"))}</th><th>${esc(t("common.date"))}</th><th>${esc(t("devices.column.lastSeen"))}</th><th></th></tr>
  </thead>
  <tbody>
    $rows
  </tbody>
</table>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // 에러 페이지
    // ────────────────────────────────────────────────────────────────────

    fun errorPage(code: Int, message: String, lang: String): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val tArgs = { key: String, args: Array<Any?> -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, *args) }
        val title = tArgs("error.page.title", arrayOf<Any?>(code.toString()))
        return shell(
            title = title,
            showNav = false,
            lang = lang,
            body = """
<div class="auth-card">
  <h1>${esc(title)}</h1>
  <p>${esc(message)}</p>
  <a href="/" class="primary-link">${esc(t("error.page.toDashboard"))}</a>
</div>
"""
        )
    }

    data class SettingsView(
        val serverName: String,
        val serverPort: Int,
        val serverHost: String,
        val maxUploadMb: Long,
        val artifactKeep: Int,
        val claudeEnabled: Boolean,
        val claudePath: String,
        val claudeTimeoutMin: Int,
        val claudeMaxConcurrent: Int,
        val buildTimeoutMin: Int,
        val defaultDebugTask: String,
    )
}
