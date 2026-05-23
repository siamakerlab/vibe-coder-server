package com.siamakerlab.vibecoder.server.admin

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

    internal fun shell(
        title: String,
        body: String,
        username: String? = null,
        currentPath: String = "/",
        showNav: Boolean = true,
    ): String {
        val nav = if (showNav) navHtml(currentPath, username) else ""
        val layoutCls = if (showNav) "layout" else "layout no-nav"
        return """<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(title)} · Vibe Coder</title>
  <link rel="stylesheet" href="/static/admin.css">
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

    private fun navHtml(currentPath: String, username: String?): String {
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
  <div class="brand">Vibe Coder</div>
  <div class="nav-links">
    ${link("/", "대시보드", "dashboard")}
    ${link("/projects", "프로젝트", "projects")}
    ${link("/env-setup", "빌드환경", "env-setup")}
    ${link("/settings", "설정", "settings")}
    ${link("/devices", "디바이스", "devices")}
    ${link("/password", "비밀번호", "password")}
  </div>
  <div class="user-box">
    ${if (username != null) "<div class=\"user\">${esc(username)}</div>" else ""}
    <form method="post" action="/logout">
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

    fun loginPage(error: String? = null, next: String? = null): String {
        val errHtml = if (error != null) """<div class="error">${esc(error)}</div>""" else ""
        val nextField = if (next != null) """<input type="hidden" name="next" value="${esc(next)}">""" else ""
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
            """<p class="hint">로그인: <code>docker exec -it vibe-coder claude login</code></p>"""
        } else ""

        return shell(
            title = "대시보드",
            username = username,
            currentPath = "/",
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
</section>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Settings
    // ────────────────────────────────────────────────────────────────────

    fun settingsPage(
        username: String,
        settings: SettingsView,
        flashOk: String? = null,
        flashErr: String? = null,
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        return shell(
            title = "설정",
            username = username,
            currentPath = "/settings",
            body = """
<header><h1>운영 설정</h1></header>
$okHtml
$errHtml
<form method="post" action="/settings" class="settings-form">

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
  <p class="hint">저장 시 server.yml은 백업(<code>.bak.&lt;ts&gt;</code>) 후 교체됩니다. 일부 항목(포트/호스트/이름)은 서버 재시작 후 적용됩니다.</p>
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
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        return shell(
            title = "비밀번호 변경",
            username = username,
            currentPath = "/password",
            body = """
<header><h1>비밀번호 변경</h1></header>
$okHtml
$errHtml
<form method="post" action="/password" class="auth-card narrow">
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
    ): String {
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val rows = devices.joinToString("\n") { d ->
            val isCurrent = d.id == currentDeviceId
            val action = if (isCurrent) {
                """<span class="dim">(현재 세션)</span>"""
            } else {
                """<form method="post" action="/devices/${esc(d.id)}/revoke" style="display:inline">
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
