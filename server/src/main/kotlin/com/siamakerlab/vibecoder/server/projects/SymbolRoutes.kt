package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * v0.54.0 — Phase 33 symbol definition lookup.
 *
 *   SSR: `GET /projects/{id}/symbols?q=<symbol>`  — search page
 *   JSON: `GET /api/projects/{id}/symbols?name=<symbol>` — `{ hits: [...] }`
 *
 * Hits are clickable links into the existing `/projects/{id}/view?path=...&line=N`
 * page (file browser handles the highlight).
 */
fun Routing.symbolRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    finder: SymbolFinder,
    /** v0.74.0 — Phase 57 #7: optional Kotlin LSP. 활성화 시 우선 + regex fallback. */
    lsp: KotlinLspService? = null,
) {
    fun lookup(projectId: String, name: String): List<SymbolFinder.Hit> {
        if (lsp?.isAvailable == true) {
            val lspHits = lsp.definition(projectId, name)
            if (lspHits.isNotEmpty()) {
                return lspHits.map { SymbolFinder.Hit(it.relPath, it.lineNumber, it.line, it.kind) }
            }
        }
        return finder.find(projectId, name)
    }

    // ── JSON API ────────────────────────────────────────────────────────────
    authenticate(AUTH_BEARER) {
        get("/api/projects/{projectId}/symbols") {
            val projectId = call.parameters["projectId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing projectId")
            call.requireProjectAcl(projects, projectId)
            val name = call.request.queryParameters["name"].orEmpty()
            val hits = lookup(projectId, name)
            val json = buildString {
                append("{\"hits\":[")
                hits.forEachIndexed { i, h ->
                    if (i > 0) append(',')
                    append('{')
                    append("\"relPath\":\"${escJson(h.relPath)}\",")
                    append("\"lineNumber\":${h.lineNumber},")
                    append("\"kind\":\"${escJson(h.kind)}\",")
                    append("\"line\":\"${escJson(h.line)}\"")
                    append('}')
                }
                append("]}")
            }
            call.respondText(json, ContentType.Application.Json)
        }
    }

    // ── SSR ─────────────────────────────────────────────────────────────────
    get("/projects/{id}/symbols") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respondRedirect("/projects")
        if (!requireProjectAccessOrRedirect(sess, projects, id)) return@get
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        val hits = if (q.isNotEmpty()) lookup(id, q) else emptyList()
        val p = runCatching { projects.get(id) }.getOrNull()
            ?: return@get call.respondRedirect("/projects?err=not_found")

        val rows = if (q.isEmpty()) {
            """<tr><td colspan="3" class="dim" style="padding:14px;text-align:center">
              검색어를 입력하세요. 예: <code>onCreate</code>, <code>MainActivity</code>, <code>userPrompt</code>
            </td></tr>"""
        } else if (hits.isEmpty()) {
            """<tr><td colspan="3" class="dim" style="padding:14px;text-align:center">
              일치하는 정의가 없습니다. 키워드를 확인하세요.
            </td></tr>"""
        } else hits.joinToString("\n") { h ->
            val viewHref = "/projects/${esc(id)}/view?path=${enc(h.relPath)}&line=${h.lineNumber}"
            """
            <tr>
              <td style="white-space:nowrap"><span class="chip" style="font-size:11px">${esc(h.kind)}</span></td>
              <td><a href="$viewHref" class="chip-link" style="font-family:ui-monospace,Menlo,monospace;font-size:12px">${esc(h.relPath)}:${h.lineNumber}</a></td>
              <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word">${esc(h.line)}</pre></td>
            </tr>"""
        }

        val body = """
<header>
  <h1>심볼 정의 찾기
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:14px">
  <p style="margin:0 0 6px"><strong>Best-effort regex 기반 정의 검색.</strong>
    Kotlin / Java 의 <code>fun</code> / <code>class</code> / <code>object</code> /
    <code>interface</code> / <code>val</code> / <code>var</code> / <code>typealias</code>
    선언을 잡습니다. 90% 케이스 충분 — false positive 가능 (nested 동명 등).</p>
  <p class="dim" style="margin:0;font-size:12px">
    LSP 통합은 cold start / 메모리 부담이 큰 단독 작업으로 별도 phase 보류. 일반
    워크플로엔 이 방식이 빠르고 충분.
  </p>
</div>

<form method="get" action="/projects/${esc(p.id)}/symbols" class="card" style="margin-bottom:14px;display:flex;gap:8px;align-items:end">
  <label style="flex:1;margin:0">심볼 이름
    <input type="text" name="q" value="${esc(q)}" placeholder="onCreate, MainActivity, userPrompt"
           autofocus required pattern="[A-Za-z_][A-Za-z0-9_]*" maxlength="80"
           style="width:100%;font-family:ui-monospace,Menlo,monospace">
  </label>
  <button type="submit" class="primary" style="width:auto;padding:8px 16px">검색</button>
  <a href="/projects/${esc(p.id)}/code-search?q=${enc(q)}" class="chip chip-link" style="padding:8px 14px"
     title="content 단순 grep — 모든 등장 위치">grep으로 →</a>
</form>

<div class="card">
  <table class="table" style="width:100%">
    <thead><tr><th style="width:80px">Kind</th><th>Location</th><th>Source line</th></tr></thead>
    <tbody>$rows</tbody>
  </table>
  ${if (hits.size >= 100) """<p class="dim" style="font-size:11px;margin:10px 0 0">⚠ 100개 hit hard cap 도달 — 키워드를 더 구체적으로.</p>""" else ""}
</div>

<div style="margin-top:14px">
  <a href="/projects/${esc(p.id)}/files" class="chip chip-link">← 파일 브라우저</a>
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">콘솔로</a>
</div>
"""
        call.respondText(
            AdminTemplates.shell(
                title = "${p.name} · 심볼 검색",
                username = sess.username,
                currentPath = "/projects",
                csrf = sess.csrf,
                body = body,
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }
}

private fun enc(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

// 21차 점검(minor) — 이전엔 \n\r\t 외 0x20 미만 제어문자(\b \f vertical-tab NUL 등)를
// 미이스케이프해 그런 문자가 포함된 소스 라인을 응답할 때 RFC 8259 위반 JSON 이 됐다.
private fun escJson(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.toString()
}
