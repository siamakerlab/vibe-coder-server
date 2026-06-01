package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.32.0 — `/logs` — 워크스페이스 안의 빌드 로그 파일을 가로질러 grep.
 *
 * Source:
 *   - `<workspace>/.vibecoder/<projectId>/logs/<buildId>.log` (BuildService 가 저장)
 *
 * 단순 in-process grep — 각 파일을 line by line 읽어 매치 라인만 수집.
 * tail (마지막 N MB) 만 읽어 큰 로그에서 성능 ↓ 방지.
 *
 * 안전 정책:
 *   - workspace 외부 경로 접근 불가 (WorkspacePath 안에서만).
 *   - q 빈 문자열 = 빈 결과 (대량 dump 방지).
 *   - matches 200 hard cap.
 */
fun Routing.logSearchRoutes(authDeps: AdminRoutesDeps, svc: LogSearchService) {
    get("/logs") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
        val projectFilter = call.request.queryParameters["project"]?.trim()?.ifBlank { null }
        val matches = if (q == null) emptyList() else svc.search(q, projectFilter).map {
            LogMatch(it.projectId, it.buildId, it.lineNumber, it.line)
        }
        call.respondText(
            renderPage(sess.username, sess.csrf, q, projectFilter, matches, sess.language, call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }
}

// v0.70.0 — Phase 49 #1: SSR HTML 렌더링 호환용 wrapper. 실제 검색 로직은 LogSearchService.
data class LogMatch(
    val projectId: String,
    val buildId: String,
    val lineNumber: Int,
    val line: String,
)

private const val MAX_MATCHES = 200

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun highlight(line: String, q: String): String {
    val safe = esc(line)
    if (q.isBlank()) return safe
    val rx = Regex(Regex.escape(esc(q)), RegexOption.IGNORE_CASE)
    return rx.replace(safe) { """<mark style="background:#facc15;color:#111">${it.value}</mark>""" }
}

private fun renderPage(
    username: String,
    csrf: String?,
    q: String?,
    projectFilter: String?,
    matches: List<LogMatch>,
    lang: String,
    embed: Boolean = false,
): String {
    val t = { key: String -> Messages.t(lang, key) }
    val rowsHtml = if (matches.isEmpty() && q != null) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">${esc(Messages.t(lang, "logsearch.noMatch", q))}</td></tr>"""
    } else if (matches.isEmpty()) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">${esc(t("logsearch.empty"))}</td></tr>"""
    } else {
        matches.joinToString("") { m ->
            """<tr>
              <td><a href="/projects/${esc(m.projectId)}/builds/${esc(m.buildId)}"><code style="font-size:11px">${esc(m.projectId.take(20))}/${esc(m.buildId.take(8))}</code></a></td>
              <td class="dim" style="text-align:right;font-size:11px">L${m.lineNumber}</td>
              <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px">${highlight(m.line, q ?: "")}</pre></td>
            </tr>"""
        }
    }

    return AdminTemplates.shell(
        title = t("logsearch.title"),
        username = username,
        currentPath = "/logs",
        csrf = csrf,
        lang = lang,
        embed = embed,
        body = """
<header>
  <h1>${esc(t("logsearch.title"))} <small class="dim" style="font-size:14px;font-weight:400">${esc(t("logsearch.subtitle"))}</small></h1>
</header>

<form method="get" action="/logs" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:1fr 200px auto;gap:8px;align-items:end">
  <label style="margin:0">${esc(t("logsearch.q.label"))}
    <input type="text" name="q" value="${esc(q)}" placeholder="FAILED / OutOfMemory / lint ..." autofocus>
  </label>
  <label style="margin:0">${esc(t("logsearch.project.label"))}
    <input type="text" name="project" value="${esc(projectFilter)}" placeholder="my-app">
  </label>
  <div>
    <button type="submit" class="primary" style="padding:8px 14px">${esc(t("logsearch.searchBtn"))}</button>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
  <small class="dim">${if (q == null) "" else esc(Messages.t(lang, "logsearch.summary", matches.size, MAX_MATCHES))}</small>
</div>

<table class="devices">
  <thead><tr>
    <th style="width:280px">${esc(t("table.projectBuild"))}</th>
    <th style="width:50px">L#</th>
    <th>${esc(t("table.match"))}</th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  ${t("logsearch.bottomHint")}
</p>
"""
    )
}
