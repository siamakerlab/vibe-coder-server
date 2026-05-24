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
    ): String {
        val nav = if (showNav) navHtml(currentPath, username, csrf) else ""
        val layoutCls = if (showNav) "layout" else "layout no-nav"
        // v0.12.4 — JS 가 ajax POST 시 CSRF 토큰을 첨부할 수 있도록 meta + global.
        // body 가 아닌 head 에 있어야 inline script 보다 먼저 실행됨.
        val csrfMeta = if (csrf != null)
            """<meta name="csrf-token" content="${esc(csrf)}">
  <script>window.__VIBE_CSRF__ = ${jsLitString(csrf)};</script>"""
        else ""
        return """<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  $csrfMeta
  <title>${esc(title)} · Vibe Coder</title>
  <link rel="icon" type="image/png" href="/static/icon.png">
  <link rel="stylesheet" href="/static/admin.css">
  <script src="/static/keyboard.js" defer></script>
</head>
<body>
  <div class="$layoutCls">
    $nav
    <main class="content">
      $body
    </main>
  </div>
</body>
</html>
"""
    }

    private fun navHtml(currentPath: String, username: String?, csrf: String?): String {
        fun link(href: String, label: String, key: String): String {
            // 루트("/") 는 정확히 일치할 때만 active. 그 외는 prefix 매칭.
            val cls = when {
                href == "/" && (currentPath == "/" || currentPath.isBlank()) -> "active"
                href != "/" && currentPath.startsWith(href) -> "active"
                else -> ""
            }
            return """<a href="${esc(href)}" class="${esc(cls)}" data-key="${esc(key)}">${esc(label)}</a>"""
        }
        return """
<nav class="sidebar">
  <div class="brand" style="display:flex;align-items:center;gap:10px">
    <img src="/static/icon.png" alt=""
         style="width:32px;height:32px;border-radius:50%;object-fit:cover;flex-shrink:0">
    <span>Vibe Coder</span>
  </div>
  <div class="nav-links">
    ${link("/", "대시보드", "dashboard")}
    ${link("/projects", "프로젝트", "projects")}
    ${link("/chat", "Chat", "chat")}
    ${link("/prompts", "프롬프트", "prompts")}
    ${link("/env-setup", "빌드환경", "env-setup")}
    ${link("/agents", "Agents", "agents")}
    ${link("/emulator", "Emulator", "emulator")}
    ${link("/settings", "설정", "settings")}
    ${link("/settings/cache", "빌드 캐시", "cache")}
    ${link("/devices", "디바이스", "devices")}
    ${link("/audit", "감사 로그", "audit")}
    ${link("/logs", "빌드 로그 검색", "logs")}
    ${link("/history", "대화 검색", "history")}
    ${link("/settings/email", "이메일 알림", "email")}
    ${link("/settings/webhook", "Webhook 알림", "webhook")}
    ${link("/password", "비밀번호", "password")}
    ${link("/2fa", "2단계 인증", "2fa")}
  </div>
  <div class="user-box">
    ${if (username != null) "<div class=\"user\">${esc(username)}</div>" else ""}
    <form method="post" action="/logout">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="logout">로그아웃</button>
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
  <form method="post" action="/login">
    $nextField
    <label>사용자명
      <input name="username" required autofocus>
    </label>
    <label>비밀번호
      <input name="password" type="password" required>
    </label>
    <button type="submit" class="primary">로그인</button>
  </form>
</div>
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
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        return shell(
            title = "설정",
            username = username,
            currentPath = "/settings",
            csrf = csrf,
            body = """
<header><h1>운영 설정</h1></header>
$okHtml
$errHtml
<form method="post" action="/settings" class="settings-form">
  ${CsrfTokens.hiddenInput(csrf)}

  <fieldset>
    <legend>서버 (재시작 필요)</legend>
    <label>이름 <input name="server.name" value="${esc(settings.serverName)}"></label>
    <label>포트 <input name="server.port" type="number" value="${settings.serverPort}" min="1" max="65535"></label>
    <label>호스트 <input name="server.host" value="${esc(settings.serverHost)}"></label>
  </fieldset>

  <fieldset>
    <legend>워크스페이스</legend>
    <label>최대 업로드 (MB) <input name="workspace.maxUploadSizeMb" type="number" value="${settings.maxUploadMb}"></label>
    <label>아티팩트 보관 개수 <input name="workspace.artifactKeepCount" type="number" value="${settings.artifactKeep}"></label>
  </fieldset>

  <fieldset>
    <legend>Claude</legend>
    <label><input name="claude.enabled" type="checkbox" ${if (settings.claudeEnabled) "checked" else ""}> 활성화</label>
    <label>경로 <input name="claude.path" value="${esc(settings.claudePath)}"></label>
    <label>타임아웃 (분) <input name="claude.timeoutMinutes" type="number" value="${settings.claudeTimeoutMin}"></label>
  </fieldset>

  <fieldset>
    <legend>Build</legend>
    <label>타임아웃 (분) <input name="build.timeoutMinutes" type="number" value="${settings.buildTimeoutMin}"></label>
    <label>기본 debug task <input name="build.defaultDebugTask" value="${esc(settings.defaultDebugTask)}"></label>
  </fieldset>

  <button type="submit" class="primary">저장</button>
  <p class="hint">저장 시 외부 <code>server.yml</code> 이 atomic 갱신되고 이전 파일은 <code>.bak.&lt;ts&gt;</code> 로 백업됩니다 (최근 5개 유지). <strong>일부 항목(host/port/name)은 컨테이너 재시작 후 적용</strong>됩니다. 경로는 <code>${'$'}VIBECODER_CONFIG_DIR/server.yml</code> 또는 <code>./config/server.yml</code>.</p>
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
