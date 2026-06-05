package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.build.GradleWrapperService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * v0.35.0 — 코드 분석 묶음:
 *   /projects/{id}/wrapper     — Gradle wrapper 버전 표시 + 업그레이드
 *   /projects/{id}/stats        — 코드 통계 (LoC / 언어별)
 *   /code-search                — 워크스페이스 grep (cross-project)
 */
fun Routing.codeAnalysisRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    wrapperService: GradleWrapperService,
    statsService: CodeStatsService,
    searchService: CodeSearchService,
) {
    // ── Gradle wrapper ─────────────────────────────────────────────
    get("/projects/{id}/wrapper") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val info = wrapperService.inspect(id)
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            WrapperTemplates.page(sess.username, p, info, ok, err, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/wrapper") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val version = form["version"]?.trim().orEmpty()
        val type = form["distributionType"]?.trim()?.ifBlank { "bin" } ?: "bin"
        runCatching { wrapperService.setVersion(id, version, type) }
            .onSuccess {
                log.info { "wrapper version: $id → $version ($type) by ${sess.username}" }
                authDeps.audit.wrapperUpdate(sess.userId, call.request.origin.remoteHost, id, version)
                call.respondRedirect("/projects/$id/wrapper?ok=${enc("wrapper $version ($type) 설정됨")}")
            }
            .onFailure { e ->
                call.respondRedirect("/projects/$id/wrapper?err=${enc(e.message ?: "update failed")}")
            }
    }

    // ── 코드 통계 ─────────────────────────────────────────────────
    get("/projects/{id}/stats") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val result = statsService.analyze(id)
        call.respondText(
            StatsTemplates.page(sess.username, p, result, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    // ── 워크스페이스 grep ─────────────────────────────────────────
    get("/code-search") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
        val projectFilter = call.request.queryParameters["project"]?.trim()?.ifBlank { null }
        val caseSensitive = call.request.queryParameters["case"] == "1"
        val matches = if (q == null) emptyList()
        else searchService.search(q, projectFilter, caseSensitive)
        call.respondText(
            SearchTemplates.page(sess.username, sess.csrf, q, projectFilter, caseSensitive, matches, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private object WrapperTemplates {
    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        info: GradleWrapperService.Info,
        ok: String?,
        err: String?,
        csrf: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""
        val statusHtml = when {
            !info.present -> """<div class="error">Gradle wrapper 가 없습니다 (<code>${esc(info.propertiesPath)}</code>). 프로젝트가 wrapper 를 쓰지 않거나 손상됨.</div>"""
            info.currentVersion == null -> """<div class="error">distributionUrl 파싱 실패. raw: <code>${esc(info.distributionUrl)}</code></div>"""
            else -> """
              <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 14px;margin:0">
                <dt class="dim">현재 버전</dt><dd><code>${esc(info.currentVersion)}</code></dd>
                <dt class="dim">Distribution</dt><dd><code>${esc(info.distributionType)}</code> (${if (info.distributionType == "all") "소스/문서 포함" else "binary only"})</dd>
                <dt class="dim">distributionUrl</dt><dd><code style="font-size:11px;word-break:break-all">${esc(info.distributionUrl)}</code></dd>
                <dt class="dim">properties</dt><dd><code style="font-size:11px">${esc(info.propertiesPath)}</code></dd>
              </dl>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Gradle wrapper",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>Gradle wrapper <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small></h1>
</header>

$okHtml
$errHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">현재 상태</h2>
  $statusHtml
</div>

<div class="card">
  <h2 style="margin-top:0">버전 변경</h2>
  <p class="hint">새 버전을 입력하면 <code>distributionUrl</code> 만 atomic write 로 교체합니다. 다음 빌드가 새 wrapper 를 자동 다운로드.</p>
  <form method="post" action="/projects/${esc(p.id)}/wrapper" style="display:grid;grid-template-columns:1fr 200px auto;gap:8px;align-items:end">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">새 버전 (예: 9.5.1)
      <input name="version" required pattern="[0-9]+(\\.[0-9]+)*(-rc-[0-9]+)?" placeholder="9.5.1">
    </label>
    <label style="margin:0">Distribution
      <select name="distributionType">
        <option value="bin"${if (info.distributionType == "bin") " selected" else ""}>bin (권장)</option>
        <option value="all"${if (info.distributionType == "all") " selected" else ""}>all (소스 포함, +400 MB)</option>
      </select>
    </label>
    <div>
      <button type="submit" class="primary" style="padding:8px 14px">설정</button>
    </div>
  </form>
  <p class="hint" style="margin-top:8px;font-size:12px">참고 — 최신 안정 버전 확인: <a href="https://gradle.org/releases/" target="_blank">gradle.org/releases ↗</a></p>
</div>
""",
            lang = lang,
            embed = embed,
        )
    }
}

private object StatsTemplates {
    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        result: CodeStatsService.Result,
        csrf: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val rows = if (result.byLanguage.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">no code files indexed</td></tr>"""
        } else {
            val maxLines = result.byLanguage.maxOfOrNull { it.lines }?.coerceAtLeast(1L) ?: 1L
            result.byLanguage.joinToString("") { l ->
                val barPct = (l.lines.toDouble() / maxLines * 100).toInt()
                """<tr>
                  <td><strong>${esc(l.language)}</strong></td>
                  <td style="text-align:right">${l.files}</td>
                  <td style="text-align:right">${l.lines}</td>
                  <td>
                    <div style="background:#e5e7eb;border-radius:3px;height:6px;overflow:hidden;width:200px">
                      <div style="width:${barPct}%;background:#059669;height:100%"></div>
                    </div>
                  </td>
                </tr>"""
            }
        }
        val totalMb = "%.2f".format(result.totalBytes / 1_048_576.0)
        val errBanner = result.errorMessage?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Code stats",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>코드 통계 <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small></h1>
</header>

$errBanner

<section class="grid" style="margin-bottom:14px">
  <div class="card"><h2>파일</h2><dl><dt>총</dt><dd>${result.totalFiles}</dd></dl></div>
  <div class="card"><h2>라인 수</h2><dl><dt>총</dt><dd>${result.totalLines}</dd></dl></div>
  <div class="card"><h2>크기</h2><dl><dt>총</dt><dd>${totalMb} MB</dd></dl></div>
  <div class="card"><h2>측정 시간</h2><dl><dt>walk</dt><dd>${result.durationMs} ms</dd></dl></div>
</section>

<div class="card">
  <h2 style="margin-top:0">언어별 (lines DESC)</h2>
  <table class="devices" style="margin:0">
    <thead><tr><th>언어</th><th style="text-align:right">파일</th><th style="text-align:right">라인</th><th>비율</th></tr></thead>
    <tbody>$rows</tbody>
  </table>
</div>

<p class="hint" style="margin-top:14px;font-size:12px">
  단순 line count (cloc 처럼 주석/공백 구분 안 함). 제외: .git, build, .gradle,
  node_modules, .idea, 5 MB 초과 파일, 바이너리 확장자. 외부 도구 (cloc, scc) 없이
  in-process walk.
</p>
""",
            lang = lang,
            embed = embed,
        )
    }
}

private object SearchTemplates {
    private fun highlight(line: String, q: String, caseSensitive: Boolean): String {
        val safe = esc(line)
        if (q.isBlank()) return safe
        val safeQ = esc(q)
        val opts = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
        val rx = Regex(Regex.escape(safeQ), opts)
        return rx.replace(safe) { """<mark style="background:#facc15;color:#111">${it.value}</mark>""" }
    }

    fun page(
        username: String,
        csrf: String?,
        q: String?,
        projectFilter: String?,
        caseSensitive: Boolean,
        matches: List<CodeSearchService.Match>,

        lang: String,
        embed: Boolean = false,
    ): String {
        val rows = if (matches.isEmpty() && q != null) {
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:14px">"${esc(q)}" 에 매치되는 줄이 없습니다.</td></tr>"""
        } else if (matches.isEmpty()) {
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:14px">검색어를 입력하면 모든 프로젝트의 source 트리를 grep 합니다.</td></tr>"""
        } else {
            // v0.73.0 — Phase 53 #16: file viewer 진입 시 line jump (?line=N) 추가 +
            // monospace + better contrast. SymbolFinder (v0.54.0) 의 file viewer 가 같은 query 사용.
            matches.joinToString("") { m ->
                val href = "/projects/${esc(m.projectId)}/view?path=${enc(m.relPath)}&line=${m.lineNumber}"
                """<tr>
                  <td style="vertical-align:top">
                    <a href="$href" style="text-decoration:none">
                      <code style="font-size:11px;color:var(--primary)">${esc(m.projectId.take(20))}</code>
                      <span class="dim" style="font-size:11px">/${esc(m.relPath)}</span>
                    </a>
                  </td>
                  <td class="dim" style="text-align:right;font-size:11px;vertical-align:top;padding-top:6px">
                    <a href="$href" class="dim" style="text-decoration:none">L${m.lineNumber}</a>
                  </td>
                  <td style="vertical-align:top"><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px;background:rgba(0,0,0,0.2);padding:6px 10px;border-radius:4px">${highlight(m.line, q ?: "", caseSensitive)}</pre></td>
                </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "코드 검색",
            username = username,
            currentPath = "/code-search",
            csrf = csrf,
            body = """
<header>
  <h1>코드 검색 <small class="dim" style="font-size:14px;font-weight:400">모든 워크스페이스 source 트리</small></h1>
</header>

<form method="get" action="/code-search" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:1fr 200px auto auto;gap:8px;align-items:end">
  <label style="margin:0">검색어
    <input type="text" name="q" value="${esc(q)}" placeholder="TODO / fun main / val x = " autofocus>
  </label>
  <label style="margin:0">프로젝트 필터 (선택)
    <input type="text" name="project" value="${esc(projectFilter)}" placeholder="my-app">
  </label>
  <label style="margin:0">
    <input type="checkbox" name="case" value="1"${if (caseSensitive) " checked" else ""}> case-sensitive
  </label>
  <div>
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
  <small class="dim">${if (q == null) "" else "Top ${matches.size} 매치 (cap=200). 5 MB 초과 / 바이너리 확장자 제외."}</small>
</div>

<table class="devices">
  <thead><tr>
    <th>Project / File</th>
    <th style="width:50px">L#</th>
    <th>Match</th>
  </tr></thead>
  <tbody>$rows</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  대화 검색은 <a href="/history">/history</a>, 빌드 로그는 <a href="/logs">/logs</a>.
  본 페이지는 source 트리 (workspace/&lt;projectId&gt;/) 만.
</p>
""",
            lang = lang,
            embed = embed,
        )
    }
}
