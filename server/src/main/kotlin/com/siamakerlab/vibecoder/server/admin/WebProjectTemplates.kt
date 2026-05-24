package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.files.ProjectFileBrowser
import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto

/**
 * 프로젝트 / 콘솔 / 빌드 SSR 템플릿. v0.5.0 Phase 2 추가.
 *
 * 안드로이드 앱 없이도 브라우저만으로 프로젝트 등록 -> Claude 프롬프트 ->
 * Gradle 빌드 -> APK 다운로드까지 완결되도록 하는 화면들.
 *
 * AdminTemplates.kt 와 동일한 `shell()` 레이아웃 셸 + admin.css 를 공유한다.
 */
object WebProjectTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /**
     * v0.12.4 — JavaScript 문자열 리터럴 컨텍스트 전용 escape.
     *
     * HTML escape 만으로는 안전하지 않음: `<script>` 안에서는 HTML 엔티티가
     * 디코드되지 않으므로 `&quot;` 가 JS 파서에 그대로 노출돼 syntax error 가 나거나,
     * projectId 검증이 향후 느슨해질 경우 XSS 로 발전 가능. 본 함수는 JS 문자열
     * 리터럴 안에서 안전한 escape 만 수행 (BOTH " 와 ' 안전).
     *
     * 출력값은 항상 따옴표를 포함한 완전한 리터럴이므로 사용 측은
     *   `var x = ${jsLit(value)};`
     * 와 같이 따옴표 없이 박는다.
     */
    private fun jsLit(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003C")  // </script> 닫힘 차단
                '>' -> sb.append("\\u003E")
                '&' -> sb.append("\\u0026")
                ' ' -> sb.append("\\u2028")  // line separator
                ' ' -> sb.append("\\u2029")  // paragraph separator
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Claude CLI / 인증 누락 안내 카드. 콘솔 페이지 상단에 표시되며,
     * 사용자가 그대로 복사해 실행할 수 있는 명령 한 줄 + 추가 설명을 노출한다.
     */
    private fun renderClaudeBanner(
        title: String,
        body: String,
        cmd: String,
        detail: String?,
    ): String {
        val detailHtml = if (detail.isNullOrBlank()) "" else
            """<details style="margin-top:8px"><summary class="dim" style="cursor:pointer">자세히</summary>
               <pre class="diff-block" style="margin-top:8px">${esc(detail)}</pre></details>"""
        return """<div class="error" style="margin-bottom:16px;padding:16px">
          <strong style="font-size:14px">${esc(title)}</strong>
          <p style="margin:6px 0">${esc(body)}</p>
          <pre class="diff-block" style="margin:8px 0">${esc(cmd)}</pre>
          <small class="dim">로그인이 끝나면 이 페이지를 새로고침하세요.</small>
          $detailHtml
        </div>"""
    }

    /** 로그 라인 1개의 CSS 클래스. WS 라이브 흐름과 동일한 색상 팔레트. */
    private fun classOfLevel(level: String): String = when (level.uppercase()) {
        "ERROR", "STDERR" -> "err"
        "WARN" -> "tool"
        "STDOUT" -> "assistant"
        "INFO" -> "sys"
        else -> "sys"
    }

    /**
     * v0.22.0 — Play Console 업로드 카드.
     *
     * 빌드 상태가 SUCCESS 가 아니면 카드 미표시. SUCCESS 면 precheck (MCP 설치/등록)
     * 결과 + AAB 경로 입력 폼 + Internal/Alpha/Beta/Production 선택 + Release Notes.
     * 사전조건이 모자라더라도 폼은 노출 — 사용자가 우선 prompt 를 보내본 후 Claude
     * 응답에서 부족한 점을 다시 확인할 수 있도록.
     */
    private fun renderPlayUploadCard(
        p: ProjectDto,
        b: BuildDto,
        precheck: com.siamakerlab.vibecoder.server.publish.PlayPublishService.Precheck?,
        flashOk: String?,
        flashErr: String?,
        csrf: String?,
    ): String {
        if (b.status.name != "SUCCESS") return ""
        val readyBadge = when {
            precheck == null -> """<span class="dim">precheck 미실행</span>"""
            precheck.ready -> """<span class="ok">✓ 준비됨</span>"""
            else -> """<span class="warn">⚠ 사전조건 부족</span>"""
        }
        val mcpStatusLine = precheck?.let { """<dt>MCP</dt><dd>${esc(it.mcpStatus)}</dd>""" } ?: ""
        val pkgLine = precheck?.configuredPackageName?.let {
            """<dt>MCP packageName</dt><dd><code>${esc(it)}</code></dd>"""
        } ?: ""
        val warnHtml = if (precheck != null && precheck.warnings.isNotEmpty()) {
            val items = precheck.warnings.joinToString("") { """<li>${esc(it)}</li>""" }
            """<ul class="hint" style="margin:8px 0 0 18px">$items</ul>"""
        } else ""
        val okBanner = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errBanner = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        // .aab 기본 경로 추정 — 사용자가 다른 위치면 수정 가능.
        val defaultAab = "app/build/outputs/bundle/release/app-release.aab"
        return """
<div class="card" style="margin-bottom:16px">
  <h2>Play Console 업로드 (v0.22.0)</h2>
  <p>$readyBadge — google-play-publisher MCP 를 통해 Claude 가 Internal Track 으로 AAB 를 업로드합니다.</p>
  $okBanner
  $errBanner
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 12px;margin-top:8px">
    <dt>프로젝트 패키지</dt><dd><code>${esc(p.packageName)}</code></dd>
    $pkgLine
    $mcpStatusLine
  </dl>
  $warnHtml
  <form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/play-upload" style="margin-top:12px;display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>AAB 경로 (project root 기준)
      <input name="aabPath" value="${esc(defaultAab)}" required>
    </label>
    <label>Track
      <select name="track">
        <option value="internal" selected>internal (테스터만, 즉시 반영)</option>
        <option value="alpha">alpha</option>
        <option value="beta">beta</option>
        <option value="production">production</option>
      </select>
    </label>
    <label>Release notes (선택)
      <textarea name="releaseNotes" rows="3" placeholder="비우면 Claude 가 git log 등으로 추론"></textarea>
    </label>
    <div>
      <button type="submit" class="primary">Claude 에게 업로드 위임</button>
      <a href="/env-setup/mcp" class="chip chip-link">MCP 설정으로</a>
    </div>
  </form>
  <p class="hint" style="margin-top:8px">업로드 진행 / 결과는 콘솔 페이지에서 실시간으로 확인합니다. publish 단계는 Claude 가 자동 commit 하지 않고 review 상태로 남깁니다.</p>
</div>"""
    }

    /**
     * v0.23.0 — TestFlight 업로드 카드.
     *
     * vibe-coder 는 iOS 빌드를 직접 수행하지 않으므로 BuildDto 의 status 와 무관
     * 하게 항상 노출 (다만 이 페이지 자체는 빌드 detail). .ipa 경로 입력만 받으면
     * MCP `app-store-connect` 가 처리하므로 빌드 SUCCESS 와 연동할 의미 없음.
     */
    private fun renderTestFlightUploadCard(
        p: ProjectDto,
        b: BuildDto,
        precheck: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService.Precheck?,
        flashOk: String?,
        flashErr: String?,
        csrf: String?,
    ): String {
        val readyBadge = when {
            precheck == null -> """<span class="dim">precheck 미실행</span>"""
            precheck.ready -> """<span class="ok">✓ 준비됨</span>"""
            else -> """<span class="warn">⚠ 사전조건 부족</span>"""
        }
        val mcpStatusLine = precheck?.let { """<dt>MCP</dt><dd>${esc(it.mcpStatus)}</dd>""" } ?: ""
        val warnHtml = if (precheck != null && precheck.warnings.isNotEmpty()) {
            val items = precheck.warnings.joinToString("") { """<li>${esc(it)}</li>""" }
            """<ul class="hint" style="margin:8px 0 0 18px">$items</ul>"""
        } else ""
        val okBanner = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errBanner = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        // 사용자가 별도 머신에서 빌드한 .ipa 를 워크스페이스에 올린 시나리오.
        val defaultIpa = "out/app-release.ipa"
        return """
<div class="card" style="margin-bottom:16px">
  <h2>TestFlight 업로드 (v0.23.0)</h2>
  <p>$readyBadge — app-store-connect MCP 를 통해 Claude 가 TestFlight 로 .ipa 를 업로드합니다.</p>
  <p class="hint">vibe-coder 는 iOS 빌드를 직접 수행하지 않습니다. macOS+Xcode 빌드 농장에서 산출된 .ipa 를 워크스페이스에 미리 올려 두세요 (scp / git lfs / shared mount).</p>
  $okBanner
  $errBanner
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 12px;margin-top:8px">
    $mcpStatusLine
  </dl>
  $warnHtml
  <form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/testflight-upload" style="margin-top:12px;display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>.ipa 경로 (project root 기준)
      <input name="ipaPath" value="${esc(defaultIpa)}" required>
    </label>
    <label>외부 테스터 그룹 (선택, 콤마 구분)
      <input name="distributionGroups" placeholder="QA, Beta-Insiders">
    </label>
    <label>Release notes (선택)
      <textarea name="releaseNotes" rows="3" placeholder="비우면 Claude 가 최근 커밋 / CHANGELOG 로 추론"></textarea>
    </label>
    <div>
      <button type="submit" class="primary">Claude 에게 업로드 위임</button>
      <a href="/env-setup/mcp" class="chip chip-link">MCP 설정으로</a>
    </div>
  </form>
  <p class="hint" style="margin-top:8px">processing 시간이 길 수 있으므로 진행은 콘솔에서 monitor. compliance / export-compliance 같은 사용자 결정이 필요한 단계는 자동 진행 안 함.</p>
</div>"""
    }

    /**
     * v0.28.0 — APK 서명 검사 결과 (apksigner verify).
     */
    private fun renderSignerInspection(insp: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector.Inspection?): String {
        if (insp == null) return ""
        if (!insp.verified && insp.errorMessage != null && insp.schemes.isEmpty() && insp.signers.isEmpty()) {
            return """
<div style="margin-top:14px;padding:10px;border-radius:6px;background:rgba(255,150,80,0.08)">
  <strong>⚠ 서명 검사 실패</strong>
  <div class="dim" style="font-size:13px;margin-top:4px">${esc(insp.errorMessage)}</div>
</div>"""
        }
        val verifiedBadge = if (insp.verified)
            """<span class="ok">✓ verified</span>"""
        else
            """<span class="warn">✗ not verified</span>"""
        val schemes = if (insp.schemes.isNotEmpty()) insp.schemes.joinToString(", ")
        else "(없음)"
        val signersHtml = if (insp.signers.isEmpty()) {
            """<p class="dim" style="font-size:13px">서명자 정보를 추출하지 못했습니다.</p>"""
        } else {
            insp.signers.joinToString("") { s ->
                val fp = s.sha256?.let { it.chunked(4).joinToString(" ").take(80) } ?: "-"
                """
                <div style="margin-top:8px;padding:6px 10px;background:rgba(255,255,255,0.04);border-radius:6px;font-size:12px">
                  <div><strong>Signer #${s.signerIndex}</strong></div>
                  <div class="dim">DN: <code>${esc(s.subjectDn ?: "-")}</code></div>
                  <div class="dim">SHA-256: <code style="word-break:break-all">${esc(fp)}</code></div>
                </div>"""
            }
        }
        return """
<div style="margin-top:14px">
  <h3 style="margin:0 0 8px 0;font-size:14px">서명 검사 (v0.28.0)</h3>
  <p style="margin:0">$verifiedBadge — 활성 schemes: <code>$schemes</code></p>
  $signersHtml
</div>"""
    }

    /** 종료된 빌드의 파일 로그를 prerender. null 이면 빈 문자열. */
    private fun renderReplay(replay: BuildLogReplay?): String {
        if (replay == null) return ""
        return replay.lines.joinToString("\n") { ln ->
            val cls = classOfLevel(ln.level)
            """<div class="log-line $cls"><span class="log-label">${esc(ln.level)}</span><span class="log-body">${esc(ln.message)}</span></div>"""
        }
    }

    /** 로그 카드 하단 caption — replay 출처/잘림 안내 또는 라이브 안내. */
    private fun replayCaption(replay: BuildLogReplay?, attachWs: Boolean): String {
        if (attachWs) return ""
        if (replay == null) {
            return """<p class="hint">로그 파일을 찾을 수 없습니다. 워크스페이스의
                <code>.vibecoder/&lt;projectId&gt;/logs/&lt;buildId&gt;.log</code> 위치를 확인하세요.</p>"""
        }
        val kb = (replay.sizeBytes + 512L) / 1024L
        val trunc = if (replay.truncated) {
            """, 화면에 마지막 ${replay.lines.size} / ${replay.totalLines} 줄만 표시 (앞부분 잘림)"""
        } else ""
        return """<p class="hint">파일 로그 replay — <code>${esc(replay.sourcePath)}</code>
            (${kb}KB, ${replay.totalLines}줄$trunc).</p>"""
    }

    /**
     * 콘솔 슬래시 chip 1개. 동일 form 안에 hidden command + 버튼.
     * `danger=true` 면 빨간색 (예: /clear). v0.12.4 — csrf 토큰 함께 박음.
     */
    private fun slashChip(projectId: String, command: String, label: String, csrf: String?, danger: Boolean = false): String {
        val cls = if (danger) "chip chip-danger" else "chip"
        return """<form method="post" action="/projects/${esc(projectId)}/console/slash" style="display:inline">
          ${CsrfTokens.hiddenInput(csrf)}
          <input type="hidden" name="command" value="${esc(command)}">
          <button type="submit" class="$cls">${esc(label)}</button>
        </form>"""
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects — 목록 + 등록 폼
    // ────────────────────────────────────────────────────────────────────

    fun projectsPage(
        username: String,
        projects: List<ProjectDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (projects.isEmpty()) {
            """<tr><td colspan="4" class="dim">등록된 프로젝트가 없습니다. 오른쪽 폼으로 새로 만드세요.</td></tr>"""
        } else {
            projects.joinToString("\n") { p ->
                val statusBadge = when (p.lastBuildStatus) {
                    "SUCCESS" -> """<span class="ok">SUCCESS</span>"""
                    "FAILED", "TIMEOUT" -> """<span class="warn">${esc(p.lastBuildStatus)}</span>"""
                    "RUNNING", "PENDING" -> """<span>${esc(p.lastBuildStatus)}</span>"""
                    null -> """<span class="dim">-</span>"""
                    else -> """<span>${esc(p.lastBuildStatus)}</span>"""
                }
                """<tr>
                    <td><a href="/projects/${esc(p.id)}"><strong>${esc(p.name)}</strong><br><small class="dim">${esc(p.id)}</small></a></td>
                    <td><code>${esc(p.packageName)}</code></td>
                    <td>$statusBadge</td>
                    <td><a href="/projects/${esc(p.id)}/console" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px">콘솔 열기</a></td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "프로젝트",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header><h1>프로젝트</h1></header>
$okHtml
$errHtml

<section class="grid" style="grid-template-columns: 2fr 1fr">
  <div class="card">
    <h2>등록된 프로젝트</h2>
    <table class="devices">
      <thead>
        <tr><th>이름 / ID</th><th>패키지</th><th>최근 빌드</th><th></th></tr>
      </thead>
      <tbody>
        $rowsHtml
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2>새 프로젝트</h2>
    <form method="post" action="/projects" id="new-project-form">
      ${CsrfTokens.hiddenInput(csrf)}
      <label>템플릿 (v0.18.0+)
        <select name="templateId">
          ${com.siamakerlab.vibecoder.server.projects.ProjectTemplates.all.joinToString("") {
              """<option value="${esc(it.id)}">${esc(it.title)}</option>"""
          }}
        </select>
      </label>
      <label>프로젝트 ID (kebab-case)
        <input name="projectId" required pattern="[a-z0-9][a-z0-9._-]*" maxlength="64"
               placeholder="my-android-app">
      </label>
      <label>앱 이름 (사람이 읽는 이름)
        <input name="appName" required maxlength="80" placeholder="My Android App">
      </label>
      <label>패키지명 (applicationId)
        <input name="packageName" required pattern="[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+"
               placeholder="com.example.myapp">
      </label>

      <fieldset style="margin-top:10px;border:1px solid #333;padding:10px;border-radius:6px">
        <legend style="padding:0 6px;font-size:13px">소스</legend>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer">
          <input type="radio" name="sourceType" value="empty" checked
                 onclick="document.getElementById('clone-fields').style.display='none'">
          <span><strong>빈 프로젝트</strong> — 빈 폴더 + CLAUDE.md 템플릿 (Claude 가 처음부터 scaffold)</span>
        </label>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer;margin-top:6px">
          <input type="radio" name="sourceType" value="clone"
                 onclick="document.getElementById('clone-fields').style.display='block'">
          <span><strong>기존 레포 clone</strong> — git URL 에서 가져옴</span>
        </label>

        <div id="clone-fields" style="display:none;margin-top:10px;padding-left:24px">
          <label>Clone URL
            <input name="cloneUrl" type="text"
                   placeholder="https://github.com/owner/repo.git  또는  git@github.com:owner/repo.git">
          </label>
          <label>Branch (선택)
            <input name="cloneBranch" type="text" placeholder="비우면 default (main 등)">
          </label>
          <p class="hint" style="font-size:12px">
            <strong>Public 레포</strong>: https URL 그대로 입력.<br>
            <strong>Private 레포 (HTTPS)</strong>: <a href="/settings/git-integrations">환경설정 → Git 통합</a> 에서 토큰 등록 후 시도.<br>
            <strong>Private 레포 (SSH)</strong>: <code>git@host:owner/repo</code> 형식 + 위 환경설정에서 공개키 등록.
          </p>
        </div>
      </fieldset>

      <button type="submit" class="primary" style="margin-top:10px">생성</button>
      <p class="hint">빈 프로젝트의 경우 콘솔에서 Claude 에게 "Android 앱을 만들어줘" 같은 프롬프트로 시작합니다.</p>
    </form>
  </div>
</section>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id} — 상세 (요약 + 하위 페이지 링크)
    // ────────────────────────────────────────────────────────────────────

    fun projectDetailPage(
        username: String,
        p: ProjectDto,
        recentBuilds: List<BuildDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val recentRows = if (recentBuilds.isEmpty()) {
            """<tr><td colspan="3" class="dim">아직 빌드 이력이 없습니다.</td></tr>"""
        } else {
            recentBuilds.joinToString("\n") { b ->
                """<tr>
                    <td><a href="/projects/${esc(p.id)}/builds/${esc(b.id)}"><code>${esc(b.id.take(12))}</code></a></td>
                    <td>${esc(b.status.name)}</td>
                    <td>${esc(b.startedAt)}</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = esc(p.name),
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>${esc(p.name)} <small class="dim" style="font-size:14px;font-weight:400">${esc(p.id)}</small></h1>
</header>
$okHtml
$errHtml

<section class="grid">
  <div class="card">
    <h2>요약</h2>
    <dl>
      <dt>패키지</dt><dd><code>${esc(p.packageName)}</code></dd>
      <dt>소스 경로</dt><dd><code>${esc(p.sourcePath)}</code></dd>
      <dt>모듈</dt><dd>${esc(p.moduleName)}</dd>
      <dt>Debug task</dt><dd><code>${esc(p.debugTask)}</code></dd>
      <dt>최근 빌드</dt><dd>${esc(p.lastBuildStatus ?: "-")}</dd>
      <dt>업데이트</dt><dd>${esc(p.updatedAt)}</dd>
    </dl>
  </div>

  <div class="card">
    <h2>작업</h2>
    <p><a href="/projects/${esc(p.id)}/console" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">콘솔 / Claude 프롬프트 →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/builds" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">빌드 / APK →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/history" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">대화 히스토리 →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/tree" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">파일 트리 / 편집 →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/files" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">파일 업로드 / 다운로드 →</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/zip" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="source zip 백업 (build/, .git/, node_modules/ 제외)">🗜 Source zip 다운로드</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/git" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">git status / diff / log →</a></p>
    <form method="post" action="/projects/${esc(p.id)}/delete" style="margin-top:24px"
          onsubmit="return confirm('정말 삭제하시겠습니까? 워크스페이스 폴더는 그대로 남고 DB 항목만 제거됩니다.')">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="danger" style="width:100%">프로젝트 삭제 (메타데이터만)</button>
    </form>
  </div>

  <div class="card">
    <h2>최근 빌드 (5건)</h2>
    <table class="devices">
      <thead><tr><th>ID</th><th>상태</th><th>시작</th></tr></thead>
      <tbody>$recentRows</tbody>
    </table>
  </div>
</section>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/console — Claude 콘솔
    // ────────────────────────────────────────────────────────────────────

    fun consolePage(
        username: String,
        p: ProjectDto,
        sessionId: String?,
        isAlive: Boolean,
        claudeCli: CheckItemDto? = null,
        claudeAuth: CheckItemDto? = null,
        csrf: String? = null,
        /** v0.13.0 — true 면 nav 의 "프롬프트/Chat" 활성화 + 사이드 링크 (프로젝트로 / 빌드 등) 숨김. */
        isChat: Boolean = false,
        /** v0.18.0 — 프로젝트 등록 직후 첫 console 진입 시 자동 입력될 starter prompt. */
        starterPrompt: String? = null,
    ): String {
        val statusBadge = when {
            isAlive -> """<span class="ok">running</span>"""
            sessionId != null -> """<span class="dim">idle (will resume)</span>"""
            else -> """<span class="dim">no session</span>"""
        }
        // Claude CLI 미설치 또는 인증 누락 시 큰 안내 카드 + 프롬프트 폼 비활성화.
        val cliMissing = claudeCli != null && claudeCli.status != CheckStatus.OK
        val authMissing = claudeAuth != null && claudeAuth.status == CheckStatus.ERROR
        val blocking = cliMissing || authMissing
        val authBannerHtml = when {
            cliMissing -> renderClaudeBanner(
                title = "Claude CLI 가 설치되지 않았습니다",
                body = "프롬프트를 보내기 전에 컨테이너 안에서 vibe-doctor 로 설치를 마치세요.",
                cmd = "docker exec -it vibe-coder-server vibe-doctor claude",
                detail = claudeCli?.detail,
            )
            authMissing -> renderClaudeBanner(
                title = "Claude CLI 로그인이 필요합니다",
                body = "Claude Code 자격증명이 없어 새 세션을 시작할 수 없습니다. 도커 컨테이너에서 한 번만 로그인하면 됩니다.",
                cmd = "docker exec -it --user vibe vibe-coder-server claude login",
                detail = claudeAuth?.detail,
            )
            else -> ""
        }
        // v0.12.4 — JS 문자열 컨텍스트는 jsLit() 사용 (HTML escape 만으로는 < / </script>
        // 차단·따옴표 처리가 충분치 않다). projectId 가 PROJECT_ID_PATTERN 검증을
        // 통과하긴 하나 defense-in-depth.
        val projectIdJs = jsLit(p.id)

        val navPath = if (isChat) "/chat" else "/projects"
        val titleSuffix = if (isChat) "General Chat" else "콘솔"
        val sideLinks = if (isChat) """
      <a href="/chat/history" class="chip chip-link">히스토리 →</a>"""
        else """
      <a href="/projects/${esc(p.id)}" class="chip chip-link">← 프로젝트</a>
      <a href="/projects/${esc(p.id)}/builds" class="chip chip-link">빌드 / APK →</a>
      <a href="/projects/${esc(p.id)}/history" class="chip chip-link">히스토리 →</a>
      <a href="/projects/${esc(p.id)}/files" class="chip chip-link">파일 →</a>
      <a href="/projects/${esc(p.id)}/git" class="chip chip-link">git →</a>"""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · $titleSuffix",
            username = username,
            currentPath = navPath,
            csrf = csrf,
            body = """
<header>
  <h1>$titleSuffix
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)}${if (isChat) "" else " (${esc(p.id)})"}</small>
  </h1>
</header>

$authBannerHtml

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>세션:</strong> $statusBadge
      ${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      $sideLinks
      <button type="button" id="stop-btn" class="chip chip-danger" style="display:none"
              title="현재 turn 을 즉시 중단 (같은 세션으로 다음 prompt 가능)">■ 중지</button>
      <form method="post" action="/projects/${esc(p.id)}/console/new" style="display:inline"
            onsubmit="return confirm('현재 세션을 종료하고 새 대화를 시작할까요?')">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="chip chip-danger">새 세션</button>
      </form>
    </div>
  </div>
  <div class="chip-row" style="margin-top:12px;display:flex;gap:6px;flex-wrap:wrap;align-items:center">
    <small class="dim" style="margin-right:4px">슬래시:</small>
    ${slashChip(p.id, "status", "/status", csrf)}
    ${slashChip(p.id, "cost", "/cost", csrf)}
    ${slashChip(p.id, "model", "/model", csrf)}
    ${slashChip(p.id, "memory", "/memory", csrf)}
    ${slashChip(p.id, "plan", "/plan", csrf)}
    ${slashChip(p.id, "compact", "/compact", csrf)}
    ${slashChip(p.id, "clear", "/clear", csrf, danger = true)}
  </div>
</div>

<div id="console-log" class="console-log" aria-live="polite"></div>

<div style="display:flex;justify-content:flex-end;margin-bottom:6px">
  <select id="template-picker" style="font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333">
    <option value="">▼ 프롬프트 템플릿 가져오기 …</option>
  </select>
  <a href="/prompts" class="chip chip-link" style="font-size:11px;margin-left:6px">관리</a>
</div>

<form id="prompt-form" class="prompt-form" autocomplete="off">
  <!-- maxlength 는 char 단위라 ASCII 기준 32K. 한국어 등 multi-byte 입력은
       실제 UTF-8 byte 가 32K 를 넘으면 서버에서 prompt_too_large (400) 로 거절. -->
  <textarea id="prompt-input" rows="${if (starterPrompt != null) 8 else 3}" maxlength="32768"
            placeholder="${if (blocking) "Claude 인증을 완료한 뒤 사용할 수 있습니다." else "Claude 에게 보낼 프롬프트를 입력하세요. Ctrl+Enter 로 전송.&#10;예) Android 빈 프로젝트를 생성하고 Compose 로 'Hello' 화면을 띄워줘."}"
            ${if (blocking) "disabled" else "required"}>${esc(starterPrompt)}</textarea>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
    <small class="dim">${if (blocking) "위쪽 안내의 명령을 실행한 뒤 페이지를 새로고침하세요." else "전송: Ctrl+Enter · 줄바꿈: Enter"}</small>
    <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px" ${if (blocking) "disabled" else ""}>전송</button>
  </div>
</form>

<script>
(function() {
  var projectId = $projectIdJs;
  var logEl = document.getElementById('console-log');
  var form = document.getElementById('prompt-form');
  var input = document.getElementById('prompt-input');
  var sendBtn = document.getElementById('send-btn');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }

  // Claude 응답에 'Not logged in' 같은 인증 실패 패턴이 보이면 즉시 폼을 disable 하고
  // 빨간 배너를 띄운다. 진단 (EnvDiagnostics) 이 false positive 였을 때의 라이브 fallback.
  var AUTH_FAIL_RE = /(not logged in|please run \/login|invalid api key|unauthorized|authentication required)/i;
  function detectAuthFailure(text) {
    if (!text) return false;
    if (AUTH_FAIL_RE.test(String(text))) { showAuthBanner(); return true; }
    return false;
  }
  function showAuthBanner() {
    var existing = document.getElementById('live-auth-banner');
    if (existing) return;
    var banner = document.createElement('div');
    banner.id = 'live-auth-banner';
    banner.className = 'error';
    banner.style.cssText = 'margin-bottom:16px;padding:16px';
    banner.innerHTML = '<strong style="font-size:14px">Claude CLI 로그인이 필요합니다 (라이브 감지)</strong>' +
      '<p style="margin:6px 0">현재 응답에서 인증 실패 신호가 감지되었습니다. 컨테이너 안에서 재로그인 후 페이지를 새로고침하세요.</p>' +
      '<pre class="diff-block" style="margin:8px 0">docker exec -it --user vibe vibe-coder-server claude login</pre>';
    var header = document.querySelector('header');
    if (header && header.parentNode) header.parentNode.insertBefore(banner, header.nextSibling);
    var input = document.getElementById('prompt-input');
    var btn = document.getElementById('send-btn');
    if (input) { input.disabled = true; input.placeholder = 'Claude 재로그인 후 새로고침하세요.'; }
    if (btn) btn.disabled = true;
  }

  // v0.13.0 — tool_use 도구별 친화적 렌더링. raw JSON 대신 읽기 쉬운 한 줄.
  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }
  function renderToolUse(name, input) {
    var i = input || {};
    switch (name) {
      case 'Bash': {
        var cmd = i.command || '';
        var desc = i.description ? ' — ' + i.description : '';
        return { label: '$', body: clip(cmd, 400) + desc };
      }
      case 'Read': {
        var p = i.file_path || i.path || '';
        var range = (i.offset != null || i.limit != null)
          ? ' [' + (i.offset || 0) + ', +' + (i.limit || '?') + ']' : '';
        return { label: '📄 Read', body: p + range };
      }
      case 'Write': {
        var p2 = i.file_path || i.path || '';
        var sz = (i.content || '').length;
        return { label: '✏️ Write', body: p2 + ' (' + sz + ' chars)' };
      }
      case 'Edit': {
        var p3 = i.file_path || i.path || '';
        var oldS = clip(i.old_string || '', 80);
        var newS = clip(i.new_string || '', 80);
        var ra = i.replace_all ? ' [all]' : '';
        return { label: '✎ Edit' + ra, body: p3 + '\n  - ' + oldS + '\n  + ' + newS };
      }
      case 'Glob':
        return { label: '🔍 Glob', body: (i.pattern || '') + (i.path ? ' in ' + i.path : '') };
      case 'Grep':
        return { label: '🔎 Grep', body: '"' + clip(i.pattern || '', 80) + '"' +
          (i.path ? ' in ' + i.path : '') + (i.glob ? ' (' + i.glob + ')' : '') };
      case 'TaskCreate':
        return { label: '📋 TaskCreate', body: i.subject || i.description || '' };
      case 'TaskUpdate':
        return { label: '📋 TaskUpdate',
          body: 'id=' + (i.taskId || '?') + (i.status ? ' status=' + i.status : '') +
                (i.subject ? ' "' + i.subject + '"' : '') };
      case 'TodoWrite':
        var n = (i.todos || []).length;
        return { label: '📋 TodoWrite', body: n + ' todo(s)' };
      case 'WebSearch':
        return { label: '🌐 WebSearch', body: '"' + clip(i.query || '', 200) + '"' };
      case 'WebFetch':
        return { label: '🌐 WebFetch', body: i.url || '' };
      default: {
        var raw = typeof input === 'string' ? input : JSON.stringify(input || {});
        return { label: name || 'tool', body: clip(raw, 500) };
      }
    }
  }

  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') {
      append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12) + (f.model ? ' · ' + f.model : ''));
    } else if (t === 'console_assistant') {
      append('assistant', 'assistant', f.text || '');
      detectAuthFailure(f.text);
    } else if (t === 'console_tool_use') {
      var rendered = renderToolUse(f.toolName, f.input);
      append('tool', rendered.label, rendered.body);
    } else if (t === 'console_tool_result') {
      var out = typeof f.output === 'string' ? f.output : JSON.stringify(f.output);
      var resultLabel = f.isError ? 'tool-err' : '✓ result';
      append(f.isError ? 'tool-err' : 'tool-out', resultLabel,
             out.length > 500 ? out.slice(0,500) + ' …(+' + (out.length - 500) + ')' : out);
      detectAuthFailure(out);
    } else if (t === 'console_error') {
      append('err', 'error', (f.code || '') + ': ' + (f.message || ''));
      detectAuthFailure(f.message);
    } else if (t === 'console_done') {
      append('sys', 'done', f.reason || 'end_turn');
      setInFlight(false);
    } else if (t === 'console_system') {
      // 'turn_cancelled' / 'process_crashed' 등 종료 신호 — stop 버튼 숨김
      if (f.code === 'turn_cancelled' || f.code === 'process_crashed' || f.code === 'idle_terminated') {
        setInFlight(false);
      }
      append('sys', f.code || 'system', f.message || '');
      detectAuthFailure(f.message);
    } else if (t === 'console_replay_begin') {
      append('sys', 'replay', 'history begin (' + f.fromSeq + ' → ' + f.toSeq + ')');
    } else if (t === 'console_replay_end') {
      append('sys', 'replay', 'history end — live frames follow');
    }
  }

  var ws = null;
  var wsAuthed = false;

  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/console/logs');

    ws.onopen = function() {
      append('sys', 'ws', 'connected');
      // 인증은 WS handshake 의 cookie 헤더로 처리 (vibe_session 은 httpOnly 이므로
      // JS 가 읽지 못함 — XSS 방어). 서버가 handshake 시점에 cookie 에서 토큰을 추출.
    };

    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        // 서버는 인증 성공 시 별도 응답 없이 바로 frame을 보낸다.
        // 실패 시엔 type=error + CloseReason 으로 응답 후 close.
        if (f.type === 'error') { append('err', 'ws', (f.code || '') + ': ' + (f.message || '')); return; }
        renderFrame(f);
      } catch (e) {
        append('err', 'parse', String(e));
      }
    };

    ws.onclose = function(ev) {
      append('sys', 'ws', 'closed (code ' + ev.code + '); 재연결 5초 후');
      setTimeout(connect, 5000);
    };

    ws.onerror = function() {
      append('err', 'ws', 'error');
    };
  }

  connect();

  // v0.13.0 — 진행 중 turn cancel 버튼
  var stopBtn = document.getElementById('stop-btn');
  var inFlight = false;
  function setInFlight(on) {
    inFlight = on;
    if (stopBtn) stopBtn.style.display = on ? 'inline-block' : 'none';
  }
  async function cancelTurn() {
    if (!inFlight) return;
    try {
      await fetch('/api/projects/' + projectId + '/claude/console/cancel', {
        method: 'POST', credentials: 'same-origin',
      });
      append('sys', 'cancel', '사용자 중단 요청 전송됨');
    } catch (e) {
      append('err', 'cancel', String(e));
    } finally {
      setInFlight(false);
    }
  }
  if (stopBtn) stopBtn.addEventListener('click', cancelTurn);

  async function sendPrompt(text) {
    sendBtn.disabled = true;
    setInFlight(true);
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/prompt', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({text: text}),
      });
      if (!res.ok) {
        var msg = await res.text();
        append('err', 'send', res.status + ' ' + msg);
        setInFlight(false);
      } else {
        append('user', 'user', text);
        input.value = '';
      }
    } catch (e) {
      append('err', 'send', String(e));
      setInFlight(false);
    } finally {
      sendBtn.disabled = false;
      input.focus();
    }
  }

  form.addEventListener('submit', function(ev) {
    ev.preventDefault();
    var text = input.value.trim();
    if (text) sendPrompt(text);
  });

  input.addEventListener('keydown', function(ev) {
    if ((ev.ctrlKey || ev.metaKey) && ev.key === 'Enter') {
      ev.preventDefault();
      form.requestSubmit();
    }
  });

  // v0.13.0 — 프롬프트 템플릿 드롭다운 채우기. JSON API → optgroup by category.
  var picker = document.getElementById('template-picker');
  if (picker) {
    fetch('/api/prompt-templates', { credentials: 'same-origin' })
      .then(function(r) { return r.ok ? r.json() : { templates: [] }; })
      .then(function(d) {
        var byCat = {};
        (d.templates || []).forEach(function(t) {
          if (!byCat[t.category]) byCat[t.category] = [];
          byCat[t.category].push(t);
        });
        var cats = Object.keys(byCat).sort();
        cats.forEach(function(cat) {
          var og = document.createElement('optgroup');
          og.label = cat;
          byCat[cat].sort(function(a, b) { return a.title.localeCompare(b.title); }).forEach(function(t) {
            var opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.title;
            opt.dataset.body = t.body;
            og.appendChild(opt);
          });
          picker.appendChild(og);
        });
      })
      .catch(function() { /* 빈 채로 두기 */ });

    picker.addEventListener('change', function() {
      var opt = picker.options[picker.selectedIndex];
      if (!opt || !opt.value) return;
      var body = opt.dataset.body || '';
      // 기존 입력이 있으면 줄바꿈 후 append, 없으면 그대로 채움.
      if (input.value && input.value.trim().length > 0) {
        input.value = input.value.replace(/\s+$/,'') + '\n\n' + body;
      } else {
        input.value = body;
      }
      input.focus();
      picker.selectedIndex = 0;
    });
  }
})();
</script>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/builds — 빌드 목록 + APK 다운로드
    // ────────────────────────────────────────────────────────────────────

    fun buildsPage(
        username: String,
        p: ProjectDto,
        builds: List<BuildDto>,
        artifactsByBuild: Map<String, ArtifactRow>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (builds.isEmpty()) {
            """<tr><td colspan="5" class="dim">아직 빌드가 없습니다. 위 버튼으로 첫 빌드를 시작하세요.</td></tr>"""
        } else {
            builds.joinToString("\n") { b ->
                val art = artifactsByBuild[b.id]
                val downloadCell = if (art != null) {
                    val sizeKb = (art.sizeBytes + 512L) / 1024L
                    """<a href="/api/projects/${esc(p.id)}/artifacts/${esc(art.id)}/download" class="primary-link" style="width:auto;display:inline-block;padding:4px 10px">APK · ${sizeKb}KB</a>"""
                } else {
                    """<span class="dim">-</span>"""
                }
                val statusCls = when (b.status.name) {
                    "SUCCESS" -> "ok"
                    "FAILED", "TIMEOUT" -> "warn"
                    "RUNNING", "PENDING" -> ""
                    else -> "dim"
                }
                """<tr>
                    <td><a href="/projects/${esc(p.id)}/builds/${esc(b.id)}"><code>${esc(b.id.take(12))}</code></a></td>
                    <td><span class="$statusCls">${esc(b.status.name)}</span></td>
                    <td>${esc(b.startedAt)}</td>
                    <td>${esc(b.finishedAt ?: "-")}</td>
                    <td>$downloadCell</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 빌드",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>빌드
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>모듈:</strong> ${esc(p.moduleName)} · <strong>Task:</strong> <code>${esc(p.debugTask)}</code>
    </div>
    <div style="display:flex;gap:8px">
      <a href="/projects/${esc(p.id)}" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px;background:transparent;border:1px solid var(--border);color:var(--text-dim)">← 프로젝트로</a>
      <form method="post" action="/projects/${esc(p.id)}/builds" style="display:inline">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="primary" style="width:auto;padding:8px 16px">Debug 빌드 큐 등록</button>
      </form>
    </div>
  </div>
  <p class="hint">큐 등록 후엔 콘솔에서 실시간 로그를 볼 수 있으며, 완료되면 APK 다운로드 링크가 이 표에 나타납니다.</p>
</div>

<table class="devices">
  <thead>
    <tr><th>빌드 ID</th><th>상태</th><th>시작</th><th>종료</th><th>APK</th></tr>
  </thead>
  <tbody>$rowsHtml</tbody>
</table>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/builds/{buildId} — 빌드 상세 + 실시간 로그
    // ────────────────────────────────────────────────────────────────────

    fun buildDetailPage(
        username: String,
        p: ProjectDto,
        b: BuildDto,
        artifact: ArtifactRow?,
        replay: BuildLogReplay? = null,
        playPrecheck: com.siamakerlab.vibecoder.server.publish.PlayPublishService.Precheck? = null,
        playFlashOk: String? = null,
        playFlashErr: String? = null,
        testFlightPrecheck: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService.Precheck? = null,
        tfFlashOk: String? = null,
        tfFlashErr: String? = null,
        signerInspection: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector.Inspection? = null,
        csrf: String? = null,
    ): String {
        val statusCls = when (b.status.name) {
            "SUCCESS" -> "ok"
            "FAILED", "TIMEOUT" -> "warn"
            "RUNNING", "PENDING" -> ""
            else -> "dim"
        }
        val downloadHtml = if (artifact != null) {
            val sizeKb = (artifact.sizeBytes + 512L) / 1024L
            """<a href="/api/projects/${esc(p.id)}/artifacts/${esc(artifact.id)}/download" class="primary-link"
                  style="width:auto;display:inline-block;padding:8px 16px">APK 다운로드 (${sizeKb}KB)</a>
               <p class="hint">sha256: <code>${esc(artifact.sha256.take(16))}…</code> · ${esc(artifact.fileName)}</p>"""
        } else if (b.status.name == "SUCCESS") {
            """<p class="dim">APK 가 attach 되어 있지 않습니다. ArtifactService 로그를 확인하세요.</p>"""
        } else {
            """<p class="dim">빌드 완료 후 APK 다운로드 링크가 여기에 나타납니다.</p>"""
        }
        val errorHtml = if (b.errorMessage != null) {
            """<div class="error">${esc(b.errorMessage)}</div>"""
        } else ""

        val isTerminal = b.status.name in setOf("SUCCESS", "FAILED", "CANCELED", "TIMEOUT")
        val cancelHtml = if (!isTerminal) {
            """<form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/cancel" style="display:inline"
                    onsubmit="return confirm('이 빌드를 취소할까요? 진행 중인 Gradle 작업이 즉시 종료됩니다.')">
               ${CsrfTokens.hiddenInput(csrf)}
               <button type="submit" class="chip chip-danger">빌드 취소</button>
               </form>"""
        } else ""

        // v0.12.4 — JS 문자열 컨텍스트 전용 escape. esc() 는 HTML 컨텍스트용.
        val projectIdJs = jsLit(p.id)
        val buildIdJs = jsLit(b.id)
        // 종료 상태이면 JS가 connect 안 함 (로그는 이미 stdout 으로 흘러갔고 ring 에서 evicted).
        // PENDING/RUNNING 이면 WS 연결.
        val attachWs = !isTerminal

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 빌드 ${esc(b.id.take(8))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>빌드 <code style="font-size:0.7em">${esc(b.id.take(12))}</code>
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>상태:</strong> <span class="$statusCls">${esc(b.status.name)}</span>
      · <span class="dim">${esc(b.variant)}</span>
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      <a href="/projects/${esc(p.id)}/builds" class="chip chip-link">← 빌드 목록</a>
      <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔로</a>
      $cancelHtml
    </div>
  </div>
  <dl style="margin-top:12px;display:grid;grid-template-columns:max-content 1fr;gap:6px 12px">
    <dt class="dim">시작</dt><dd>${esc(b.startedAt)}</dd>
    <dt class="dim">종료</dt><dd>${esc(b.finishedAt ?: "-")}</dd>
  </dl>
  $errorHtml
</div>

<div class="card" style="margin-bottom:16px">
  <h2>APK</h2>
  $downloadHtml
  ${renderSignerInspection(signerInspection)}
</div>

${renderPlayUploadCard(p, b, playPrecheck, playFlashOk, playFlashErr, csrf)}
${renderTestFlightUploadCard(p, b, testFlightPrecheck, tfFlashOk, tfFlashErr, csrf)}

<div class="card">
  <h2>로그 ${if (attachWs) """<small class="dim" style="font-size:11px;text-transform:none;letter-spacing:0">실시간</small>""" else """<small class="dim" style="font-size:11px;text-transform:none;letter-spacing:0">파일 replay</small>"""}</h2>
  <div id="build-log" class="console-log" aria-live="polite">${renderReplay(replay)}</div>
  ${replayCaption(replay, attachWs)}
</div>

${if (attachWs) """
<script>
(function() {
  var projectId = $projectIdJs;
  var buildId = $buildIdJs;
  var logEl = document.getElementById('build-log');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }

  function classOfLevel(level) {
    if (level === 'ERROR' || level === 'STDERR') return 'err';
    if (level === 'WARN') return 'tool';
    if (level === 'STDOUT') return 'assistant';
    return 'sys';
  }

  function renderFrame(f) {
    if (f.type === 'log') {
      append(classOfLevel(f.level), f.level, f.message);
    } else if (f.type === 'done') {
      append(f.status === 'SUCCESS' ? 'sys' : 'err',
             'done', f.status + (f.errorMessage ? ' · ' + f.errorMessage : ''));
      // 5초 후 페이지를 새로고침해 최종 상태 + APK 링크를 갱신.
      setTimeout(function() { location.reload(); }, 5000);
    } else if (f.type === 'error') {
      append('err', 'ws', (f.code || '') + ': ' + (f.message || ''));
    }
  }

  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/builds/' + buildId + '/logs');
    ws.onopen = function() {
      // 인증은 WS handshake cookie 로 처리 (vibe_session 은 httpOnly).
      append('sys', 'ws', 'connected');
    };
    ws.onmessage = function(ev) {
      try { renderFrame(JSON.parse(ev.data)); }
      catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) {
      append('sys', 'ws', 'closed (code ' + ev.code + ')');
    };
    ws.onerror = function() { append('err', 'ws', 'error'); };
  }

  connect();
})();
</script>
""" else ""}
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/files — 업로드된 파일 관리
    // ────────────────────────────────────────────────────────────────────

    fun filesPage(
        username: String,
        p: ProjectDto,
        files: List<FileEntryDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (files.isEmpty()) {
            """<tr><td colspan="5" class="dim">업로드된 파일이 없습니다.</td></tr>"""
        } else {
            files.joinToString("\n") { f ->
                val sizeKb = (f.sizeBytes + 512L) / 1024L
                """<tr>
                    <td>${esc(f.originalName)}</td>
                    <td><span class="dim">${esc(f.mimeType ?: "-")}</span></td>
                    <td>${sizeKb}KB</td>
                    <td>${esc(f.createdAt)}</td>
                    <td style="display:flex;gap:6px">
                      <a href="/projects/${esc(p.id)}/files/${esc(f.id)}/download" class="chip chip-link">다운로드</a>
                      <form method="post" action="/projects/${esc(p.id)}/files/${esc(f.id)}/delete" style="display:inline"
                            onsubmit="return confirm('정말 삭제하시겠습니까?')">
                        ${CsrfTokens.hiddenInput(csrf)}
                        <button type="submit" class="chip chip-danger">삭제</button>
                      </form>
                    </td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 파일",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>파일
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:16px">
  <h2>파일 업로드</h2>
  <!-- multipart 업로드는 receiveParameters 가 불가능하므로 _csrf 를 query string 으로 -->
  <form method="post" action="/projects/${esc(p.id)}/files/upload?_csrf=${esc(csrf)}" enctype="multipart/form-data">
    <input type="file" name="file" required>
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;margin-left:8px">업로드</button>
  </form>
  <p class="hint">업로드된 파일은 <code>&lt;workspace&gt;/.vibecoder/&lt;projectId&gt;/uploads/YYYYMMDD/</code> 에 저장됩니다.
  확장자 블랙리스트 (<code>exe/bat/cmd/ps1/sh</code>) 와 최대 크기는 <code>server.yml</code> 에서 관리.</p>
</div>

<table class="devices">
  <thead>
    <tr><th>이름</th><th>MIME</th><th>크기</th><th>업로드</th><th></th></tr>
  </thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:16px">
  <a href="/projects/${esc(p.id)}" class="chip chip-link">← 프로젝트로</a>
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔로</a>
</p>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/git — 읽기 전용 git status / diff / log
    // ────────────────────────────────────────────────────────────────────

    fun gitPage(
        username: String,
        p: ProjectDto,
        status: GitStatusDto?,
        diff: GitDiffDto?,
        log: GitLogDto?,
        unavailable: Boolean,
        csrf: String? = null,
        commitFlash: String? = null,
    ): String {
        val unavailableHtml = if (unavailable) {
            """<div class="error">이 프로젝트 폴더는 git repository 가 아니거나 git CLI 실행이 실패했습니다.
            Claude 에게 "git 초기화해줘" 같은 프롬프트를 보내거나, 컨테이너 안에서 직접 <code>git init</code> 하세요.</div>"""
        } else ""

        val statusHtml = if (status == null) "" else {
            val entries = if (status.entries.isEmpty()) {
                """<p class="dim">clean — 변경 사항 없음.</p>"""
            } else {
                val rows = status.entries.joinToString("\n") { e ->
                    """<tr><td><code>${esc(e.status)}</code></td><td>${esc(e.path)}</td></tr>"""
                }
                """<table class="devices"><thead><tr><th>상태</th><th>경로</th></tr></thead><tbody>$rows</tbody></table>"""
            }
            """<div class="card">
              <h2>status</h2>
              <p><strong>branch:</strong> <code>${esc(status.branch)}</code>
                · <span class="dim">ahead</span> ${status.ahead}
                · <span class="dim">behind</span> ${status.behind}</p>
              $entries
            </div>"""
        }

        val diffHtml = if (diff == null) "" else {
            val body = if (diff.diff.isBlank()) {
                """<p class="dim">no diff</p>"""
            } else {
                """<pre class="diff-block">${esc(diff.diff.take(20_000))}</pre>"""
            }
            """<div class="card">
              <h2>diff</h2>
              $body
              ${if (diff.diff.length > 20_000) """<p class="hint">${diff.diff.length - 20_000} 바이트 더 있음 (UI에서 잘림)</p>""" else ""}
            </div>"""
        }

        val logHtml = if (log == null) "" else {
            val rows = if (log.entries.isEmpty()) {
                """<tr><td colspan="2" class="dim">no commits yet</td></tr>"""
            } else {
                log.entries.joinToString("\n") { e ->
                    """<tr><td><code>${esc(e.sha.take(8))}</code></td><td>${esc(e.message)}</td></tr>"""
                }
            }
            """<div class="card">
              <h2>log (recent 10)</h2>
              <table class="devices"><thead><tr><th>sha</th><th>message</th></tr></thead><tbody>$rows</tbody></table>
            </div>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · git",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>git
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

$unavailableHtml
${if (commitFlash != null) """<div class="ok-banner" style="white-space:pre-wrap;font-family:ui-monospace,Menlo,monospace;font-size:12px">${esc(commitFlash)}</div>""" else ""}

<section style="display:grid;gap:16px">
  $statusHtml
  $diffHtml
  $logHtml
</section>

${if (status != null && !unavailable) """
<div class="card" style="margin-top:16px">
  <h2>커밋 / 푸시 (v0.18.0+)</h2>
  <p class="dim" style="font-size:12px">변경된 파일을 한 번에 stage → commit → (옵션) push.
  Push 는 git CLI 의 ~/.git-credentials (HTTPS PAT) 또는 ~/.ssh/id_ed25519 (SSH) 자동 사용.
  push 실패해도 로컬 commit 은 유지됩니다.</p>
  <form method="post" action="/projects/${esc(p.id)}/git/commit" style="display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>Commit message
      <textarea name="message" required minlength="3" maxlength="4000" rows="3"
                placeholder="feat: ..."></textarea>
    </label>
    <label style="font-size:12px">
      <input type="checkbox" name="onlyTracked" value="1">
      Only tracked files (`git add -u`) — 새 파일은 stage 안 함
    </label>
    <label style="font-size:12px">
      <input type="checkbox" name="push" value="1" checked>
      Commit 후 `git push origin &lt;branch&gt;` 실행
    </label>
    <div>
      <button type="submit" class="primary" style="padding:8px 18px">커밋 & 푸시</button>
    </div>
  </form>
</div>""" else ""}

<p class="hint" style="margin-top:16px">
  <a href="/projects/${esc(p.id)}" class="chip chip-link">← 프로젝트로</a>
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔로</a>
</p>
<p class="hint">v0.18.0 부터 단순한 commit & push 가 본 페이지에서 가능. <code>git reset --hard</code>
같은 destructive 작업은 여전히 콘솔에서 Claude 에게 부탁 (위험 명령 노출 안 함).</p>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/tree — 디렉토리 listing (v0.13.0)
    // ────────────────────────────────────────────────────────────────────

    fun fileTreePage(
        username: String,
        p: ProjectDto,
        subPath: String,
        entries: List<ProjectFileBrowser.Entry>,
        flashErr: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val crumbs = renderBreadcrumbs(p.id, subPath)
        val rowsHtml = if (entries.isEmpty()) {
            """<tr><td colspan="3" class="dim">비어 있습니다.</td></tr>"""
        } else {
            entries.joinToString("\n") { e ->
                val sizeKb = if (e.isDirectory) "-" else "${(e.sizeBytes + 512L) / 1024L}KB"
                val icon = if (e.isDirectory) "📁" else "📄"
                val href = if (e.isDirectory)
                    "/projects/${esc(p.id)}/tree?path=${e.relPath.encodeUrl()}"
                else
                    "/projects/${esc(p.id)}/view?path=${e.relPath.encodeUrl()}"
                """<tr>
                    <td><a href="$href">$icon ${esc(e.name)}</a></td>
                    <td class="dim">$sizeKb</td>
                    <td class="dim" style="font-size:11px">${esc(e.modifiedAt)}</td>
                  </tr>"""
            }
        }
        return AdminTemplates.shell(
            title = "${esc(p.name)} · 파일트리",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>파일트리
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$errHtml

<div class="card" style="margin-bottom:14px">
  <div style="font-size:13px">$crumbs</div>
</div>

<table class="devices">
  <thead><tr><th>이름</th><th>크기</th><th>수정</th></tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:16px">
  <a href="/projects/${esc(p.id)}" class="chip chip-link">← 프로젝트로</a>
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔로</a>
</p>
<p class="hint" style="font-size:12px">읽기 + 가벼운 편집만 지원. 이진 파일/1MB 초과/심볼릭 링크는 차단.
.vibecoder / .gradle / build / node_modules 는 숨김.</p>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/view — 단일 파일 read-only view + 편집 (v0.13.0)
    // ────────────────────────────────────────────────────────────────────

    fun fileViewPage(
        username: String,
        p: ProjectDto,
        relPath: String,
        view: ProjectFileBrowser.FileView?,
        flashErr: String? = null,
        csrf: String? = null,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val crumbs = renderBreadcrumbs(p.id, relPath, isFile = true)

        val bodyHtml = if (view == null) {
            errHtml.ifEmpty { """<p class="dim">파일을 열 수 없습니다.</p>""" }
        } else {
            val sizeKb = (view.sizeBytes + 512L) / 1024L
            val hlLang = mapMimeToHljs(view.mimeGuess)
            val hlSkip = view.content.length > MAX_HL_CHARS
            """
<div class="card" style="margin-bottom:12px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div><strong><code>${esc(view.relPath)}</code></strong>
      <span class="dim" style="font-size:12px;margin-left:8px">${sizeKb}KB · ${esc(view.mimeGuess)}</span>
    </div>
    <div style="display:flex;gap:6px">
      <button type="button" id="toggle-mode" class="chip chip-link" style="font-size:12px;padding:4px 10px">View 모드 ↔ Edit 모드</button>
      <a href="/projects/${esc(p.id)}/tree?path=${parentOf(relPath).encodeUrl()}" class="chip chip-link">← 상위 폴더</a>
    </div>
  </div>
</div>

<!-- View 모드 (신택스 하이라이트, read-only) — 기본. -->
<div id="file-view-pane" class="card" style="padding:0;overflow:hidden">
  <pre style="margin:0"><code id="file-view-code" class="${if (hlSkip) "" else "language-${esc(hlLang)}"}" style="display:block;padding:12px;font-size:13px;line-height:1.5;tab-size:2;white-space:pre;overflow-x:auto">${esc(view.content)}</code></pre>
</div>

<!-- Edit 모드 (textarea) — toggle 로 전환. -->
<form method="post" action="/projects/${esc(p.id)}/edit" id="file-form" style="display:none">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="path" value="${esc(view.relPath)}">
  <textarea name="content" id="file-content" rows="28" spellcheck="false"
            style="width:100%;font-family:ui-monospace,Menlo,monospace;font-size:13px;tab-size:2;padding:8px;background:#0e0e0e;color:#e8e8e8;border:1px solid #333;border-radius:4px"
  >${esc(view.content)}</textarea>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-top:8px">
    <small class="dim">Ctrl+S 로 저장. 이진/심볼릭/1MB 초과는 서버에서 차단.</small>
    <div style="display:flex;gap:8px">
      <a href="/projects/${esc(p.id)}/tree?path=${parentOf(relPath).encodeUrl()}" class="chip chip-link">취소</a>
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">저장</button>
    </div>
  </div>
</form>

<link rel="stylesheet" href="/static/highlight-github-dark.min.css">
<script src="/static/highlight.min.js"></script>
<script>
(function() {
  var skip = ${hlSkip};
  if (!skip && window.hljs) {
    try { hljs.highlightElement(document.getElementById('file-view-code')); }
    catch (e) { console.warn('hljs failed:', e); }
  }

  var ta = document.getElementById('file-content');
  var form = document.getElementById('file-form');
  var viewPane = document.getElementById('file-view-pane');
  var toggleBtn = document.getElementById('toggle-mode');
  var mode = 'view';
  function applyMode() {
    if (mode === 'view') {
      viewPane.style.display = '';
      form.style.display = 'none';
    } else {
      viewPane.style.display = 'none';
      form.style.display = '';
      ta.focus();
    }
  }
  toggleBtn.addEventListener('click', function() {
    mode = mode === 'view' ? 'edit' : 'view';
    applyMode();
  });

  if (!ta || !form) return;
  ta.addEventListener('keydown', function(ev) {
    if ((ev.ctrlKey || ev.metaKey) && ev.key === 's') {
      ev.preventDefault();
      form.requestSubmit();
    }
    // Tab 키는 indent 로 (form submit 방지).
    if (ev.key === 'Tab' && !ev.shiftKey) {
      ev.preventDefault();
      var s = ta.selectionStart, e = ta.selectionEnd;
      ta.value = ta.value.slice(0, s) + '  ' + ta.value.slice(e);
      ta.selectionStart = ta.selectionEnd = s + 2;
    }
  });
})();
</script>
"""
        }

        val ok = "" // 라우트가 ok=saved query 를 붙이긴 하지만 본 view 에서는 별도 처리 안 함
        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(relPath)}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>파일 보기 / 편집
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:12px">
  <div style="font-size:13px">$crumbs</div>
</div>

$ok
$bodyHtml
"""
        )
    }

    private fun renderBreadcrumbs(projectId: String, subPath: String, isFile: Boolean = false): String {
        val parts = subPath.split('/').filter { it.isNotEmpty() }
        val sb = StringBuilder()
        sb.append("""<a href="/projects/${esc(projectId)}/tree">📁 ${esc(projectId)}</a>""")
        var acc = ""
        for ((idx, part) in parts.withIndex()) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val isLast = idx == parts.size - 1
            sb.append(" / ")
            if (isLast && isFile) {
                sb.append("<strong>${esc(part)}</strong>")
            } else {
                sb.append("""<a href="/projects/${esc(projectId)}/tree?path=${acc.encodeUrl()}">${esc(part)}</a>""")
            }
        }
        return sb.toString()
    }

    private fun parentOf(relPath: String): String =
        relPath.substringBeforeLast('/', missingDelimiterValue = "")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    /**
     * v0.15.0 — ProjectFileBrowser 의 mime guess 를 highlight.js 의 language 이름으로 매핑.
     * 미매칭은 plaintext (highlight 없이 그대로 표시).
     */
    private fun mapMimeToHljs(mime: String): String = when (mime) {
        "text/x-kotlin", "text/x-gradle" -> "kotlin"
        "text/x-java" -> "java"
        "text/xml" -> "xml"
        "application/json" -> "json"
        "text/yaml" -> "yaml"
        "text/markdown" -> "markdown"
        "text/x-properties" -> "properties"
        "text/x-shellscript" -> "bash"
        else -> "plaintext"
    }

    /** highlight.js 적용 최대 길이 — 그 이상이면 적용 skip (브라우저 freeze 방지). */
    private const val MAX_HL_CHARS = 200_000
}

