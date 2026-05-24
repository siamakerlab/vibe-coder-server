package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * v0.16.0 — `/projects/{id}/history` + `/chat/history` 페이지.
 *
 * 일반 프로젝트 history: `/projects/{id}/history`
 * General Chat history (`__scratch__`): `/chat/history`
 */
fun Routing.historyRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    repo: ConversationTurnRepository,
    /** v0.31.0 — conversation export/import. */
    exportService: ConversationExportService,
) {
    get("/projects/{id}/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondText("project not found", ContentType.Text.Plain, io.ktor.http.HttpStatusCode.NotFound)
            return@get
        }
        renderHistory(call, sess.username, sess.csrf, id, p.name, isChat = false, repo = repo)
    }

    get("/chat/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val p = projects.ensureScratchProject()
        renderHistory(call, sess.username, sess.csrf, p.id, p.name, isChat = true, repo = repo)
    }

    // v0.31.0 — JSON export. Application/json + Content-Disposition.
    get("/projects/{id}/history/export") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val json = exportService.exportProject(id)
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())
        val fname = "$id-conversation-$ts.json"
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fname\"")
        call.respondText(json, ContentType.Application.Json)
    }

    // v0.31.0 — multipart import. CSRF query string.
    post("/projects/{id}/history/import") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        // multipart 는 requireCsrf() (form param 기반) 안 됨 — _csrf query param 으로 검증.
        val csrfQuery = call.request.queryParameters["_csrf"] ?: ""
        if (csrfQuery != sess.csrf) {
            call.respondRedirect(
                "/projects/${call.parameters["id"]}/history?err=${enc("CSRF 검증 실패 — 새로고침 후 재시도")}"
            )
            return@post
        }
        val id = call.parameters["id"]!!
        val dryRun = call.request.queryParameters["dryRun"] != "false"

        var json: String? = null
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && json == null) {
                json = part.provider().toInputStream().bufferedReader().readText().take(5 * 1024 * 1024)
            }
            part.dispose()
        }
        if (json == null) {
            call.respondRedirect("/projects/$id/history?err=${enc("파일이 첨부되지 않았습니다")}")
            return@post
        }
        val result = exportService.importToProject(id, json!!, dryRun = dryRun)
        val mode = if (dryRun) "dry-run" else "imported"
        val msg = "$mode: accepted=${result.accepted}, skipped=${result.skipped}" +
            if (result.warnings.isNotEmpty()) "; warnings=${result.warnings.size}" else ""
        call.respondRedirect("/projects/$id/history?ok=${enc(msg)}")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

private suspend fun renderHistory(
    call: io.ktor.server.application.ApplicationCall,
    username: String,
    csrf: String?,
    projectId: String,
    displayName: String,
    isChat: Boolean,
    repo: ConversationTurnRepository,
) {
    val params = call.request.queryParameters
    // v0.52.0 — agent filter via ?agent=. UI semantics:
    //   omitted        → main console only (Filter.agentName = null)
    //   ?agent=*       → all turns (main + every sub-agent)   (Filter.agentName = "")
    //   ?agent=<name>  → that sub-agent only
    val agentParam = params["agent"]
    val filter = ConversationTurnRepository.Filter(
        projectId = projectId,
        sessionId = params["session"]?.ifBlank { null },
        role = params["role"]?.ifBlank { null },
        toolName = params["tool"]?.ifBlank { null },
        fromTs = params["from"]?.ifBlank { null },
        toTs = params["to"]?.ifBlank { null },
        q = params["q"]?.ifBlank { null },
        agentName = when {
            agentParam == null -> null          // default: main only
            agentParam == "*" -> ""             // all
            agentParam.isBlank() -> null
            else -> agentParam
        },
    )
    val page = (params["p"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    val pageSize = 100
    val rows = repo.list(filter, limit = pageSize, offset = page * pageSize.toLong())
    val total = repo.count(filter)
    val sessions = repo.distinctSessions(projectId)
    val agents = repo.distinctAgents(projectId)
    val ok = params["ok"]?.ifBlank { null }
    val err = params["err"]?.ifBlank { null }
    call.respondText(
        HistoryTemplates.page(
            username = username,
            projectId = projectId,
            displayName = displayName,
            isChat = isChat,
            rows = rows,
            filter = filter,
            sessions = sessions,
            agents = agents,
            page = page,
            pageSize = pageSize,
            total = total,
            flashOk = ok,
            flashErr = err,
            csrf = csrf,
        ),
        ContentType.Text.Html,
    )
}

object HistoryTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        projectId: String,
        displayName: String,
        isChat: Boolean,
        rows: List<com.siamakerlab.vibecoder.server.repo.ConversationTurnRow>,
        filter: ConversationTurnRepository.Filter,
        sessions: List<String>,
        /** v0.52.0 — distinct sub-agent names for the filter dropdown. */
        agents: List<String> = emptyList(),
        page: Int,
        pageSize: Int,
        total: Long,
        flashOk: String? = null,
        flashErr: String? = null,
        csrf: String? = null,
    ): String {
        val okHtml = flashOk?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = flashErr?.let { """<div class="error">${esc(it)}</div>""" } ?: ""
        val navPath = if (isChat) "/chat" else "/projects"
        val backLink = if (isChat)
            """<a href="/chat" class="chip chip-link">← Chat</a>"""
        else
            """<a href="/projects/${esc(projectId)}" class="chip chip-link">← 프로젝트</a>
               <a href="/projects/${esc(projectId)}/console" class="chip chip-link">콘솔로</a>"""

        val sessionOpts = ("""<option value="">(all sessions)</option>""" +
            sessions.joinToString("") { s ->
                val sel = if (s == filter.sessionId) " selected" else ""
                """<option value="${esc(s)}"$sel>${esc(s.take(20))}…</option>"""
            })
        val roleOpts = listOf("", "user", "assistant", "tool_use", "tool_result", "tool_result_error", "system", "error", "unknown")
            .joinToString("") { v ->
                val label = if (v.isEmpty()) "(all roles)" else v
                val sel = if (v == filter.role) " selected" else ""
                """<option value="${esc(v)}"$sel>${esc(label)}</option>"""
            }
        // v0.52.0 — agent dropdown.
        // selectedAgentParam value 결정: filter.agentName == null → "" (default = main),
        //                              filter.agentName == "" → "*" (all),
        //                              그 외 → 그 이름.
        val selectedAgentParam = when (filter.agentName) {
            null -> ""
            "" -> "*"
            else -> filter.agentName
        }
        val agentOpts = buildString {
            val mainSel = if (selectedAgentParam == "") " selected" else ""
            val allSel = if (selectedAgentParam == "*") " selected" else ""
            append("""<option value=""$mainSel>(main console only)</option>""")
            append("""<option value="*"$allSel>(all — main + sub-agents)</option>""")
            agents.forEach { a ->
                val sel = if (a == selectedAgentParam) " selected" else ""
                append("""<option value="${esc(a)}"$sel>@${esc(a)}</option>""")
            }
        }

        val rowsHtml = if (rows.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:14px">no conversation history</td></tr>"""
        } else {
            rows.joinToString("\n") { r ->
                val roleCls = when (r.role) {
                    "user" -> "user"
                    "assistant" -> "assistant"
                    "tool_use" -> "tool"
                    "tool_result" -> "tool-out"
                    "tool_result_error", "error" -> "err"
                    else -> "sys"
                }
                val previewLen = 800
                val preview = if (r.content.length > previewLen)
                    r.content.take(previewLen) + " …(+" + (r.content.length - previewLen) + ")"
                else r.content
                val toolBadge = r.toolName?.let { """<span class="dim" style="font-size:11px"> · $it</span>""" } ?: ""
                // v0.52.0 — sub-agent 출처 표시.
                val agentBadge = r.agentName?.let {
                    """<div style="font-size:10px;color:#7aa;margin-top:2px">@${esc(it)}</div>"""
                } ?: ""
                """<tr>
                  <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px;white-space:nowrap">${esc(r.ts)}</td>
                  <td><span class="$roleCls" style="font-size:11px;text-transform:uppercase">${esc(r.role)}</span>$toolBadge$agentBadge</td>
                  <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px">${esc(preview)}</pre></td>
                </tr>"""
            }
        }

        val from = page * pageSize + 1
        val to = (page * pageSize + rows.size).coerceAtMost(total.toInt())
        val nextHref = buildHref(projectId, isChat, filter, page + 1)
        val prevHref = buildHref(projectId, isChat, filter, (page - 1).coerceAtLeast(0))
        val hasNext = (page + 1) * pageSize < total
        val hasPrev = page > 0

        val title = if (isChat) "Chat 히스토리" else "$displayName · 히스토리"
        val baseAction = if (isChat) "/chat/history" else "/projects/${esc(projectId)}/history"

        return AdminTemplates.shell(
            title = title,
            username = username,
            currentPath = navPath,
            csrf = csrf,
            body = """
<header>
  <h1>${esc(if (isChat) "General Chat 히스토리" else "대화 히스토리")}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(displayName)} · $total turns</small>
  </h1>
</header>

$okHtml
$errHtml

<div style="display:flex;gap:8px;margin-bottom:14px;flex-wrap:wrap">
  <a href="/projects/${esc(projectId)}/history/export" class="chip chip-link" title="모든 turn 을 JSON 으로 다운로드 (v0.31.0+)">📥 JSON 다운로드</a>
  <details style="display:inline-block">
    <summary class="chip chip-link" style="cursor:pointer">📤 JSON 가져오기</summary>
    <form method="post" action="/projects/${esc(projectId)}/history/import?_csrf=${esc(csrf ?: "")}&dryRun=false" enctype="multipart/form-data" style="margin-top:8px;display:flex;gap:6px;align-items:center;background:rgba(255,255,255,0.04);padding:8px;border-radius:6px">
      <input type="file" name="file" accept=".json,application/json" required>
      <label style="margin:0;font-size:12px"><input type="checkbox" onclick="this.form.action=this.form.action.replace('dryRun=false','dryRun='+!this.checked)" checked> dry-run 미리보기</label>
      <button type="submit" class="primary" style="padding:6px 12px">업로드</button>
    </form>
    <p class="hint" style="margin:6px 0 0;font-size:11px">v0.31.0+ — 같은 sessionId 가 이미 존재하면 wholesale skip (idempotent).</p>
  </details>
</div>

<form method="get" action="$baseAction" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;align-items:end">
  <label style="margin:0">Agent (v0.52.0+)
    <select name="agent" style="width:100%">$agentOpts</select>
  </label>
  <label style="margin:0">Session
    <select name="session" style="width:100%">$sessionOpts</select>
  </label>
  <label style="margin:0">Role
    <select name="role" style="width:100%">$roleOpts</select>
  </label>
  <label style="margin:0">Tool name (e.g. Bash)
    <input type="text" name="tool" value="${esc(filter.toolName)}" placeholder="Bash / Read / Edit ...">
  </label>
  <label style="margin:0;grid-column:span 2">Content contains (LIKE)
    <input type="text" name="q" value="${esc(filter.q)}" placeholder="search content">
  </label>
  <label style="margin:0">From (ISO ts)
    <input type="text" name="from" value="${esc(filter.fromTs)}" placeholder="2026-05-24T00:00:00Z">
  </label>
  <label style="margin:0">To (ISO ts)
    <input type="text" name="to" value="${esc(filter.toTs)}" placeholder="2026-05-25T00:00:00Z">
  </label>
  <div style="display:flex;gap:6px">
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
    <a href="$baseAction" class="chip chip-link" style="padding:8px 14px">초기화</a>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;gap:8px;flex-wrap:wrap">
  <div style="display:flex;gap:6px">$backLink</div>
  <div style="display:flex;gap:6px">
    ${if (hasPrev) """<a href="$prevHref" class="chip chip-link">← Prev</a>""" else """<span class="chip" style="opacity:0.4">← Prev</span>"""}
    <small class="dim" style="align-self:center">${if (rows.isEmpty()) "0 / $total" else "$from–$to / $total"}</small>
    ${if (hasNext) """<a href="$nextHref" class="chip chip-link">Next →</a>""" else """<span class="chip" style="opacity:0.4">Next →</span>"""}
  </div>
</div>

<table class="devices">
  <thead><tr>
    <th style="width:160px">Time (UTC)</th>
    <th style="width:140px">Role / Tool</th>
    <th>Content (clip 800)</th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  ts 정렬은 ascending (오래된 → 최근). assistant partial chunks 는 영구 적재되지 않음 (turn 단위 final 만).
  ${if (filter.q != null) "Content 검색은 PostgreSQL tsvector + GIN 인덱스 (v0.53.0+). 'simple' 토크나이저 — 정확 매칭 best-effort." else ""}
</p>
"""
        )
    }

    private fun buildHref(
        projectId: String,
        isChat: Boolean,
        filter: ConversationTurnRepository.Filter,
        page: Int,
    ): String {
        val base = if (isChat) "/chat/history" else "/projects/${esc(projectId)}/history"
        // v0.52.0 — agent param round-trip. filter.agentName == null 은 default 라 query 생략.
        val agentQuery: String? = when (filter.agentName) {
            null -> null
            "" -> "agent=*"
            else -> "agent=${enc(filter.agentName)}"
        }
        val params = listOfNotNull(
            agentQuery,
            filter.sessionId?.let { "session=${enc(it)}" },
            filter.role?.let { "role=${enc(it)}" },
            filter.toolName?.let { "tool=${enc(it)}" },
            filter.fromTs?.let { "from=${enc(it)}" },
            filter.toTs?.let { "to=${enc(it)}" },
            filter.q?.let { "q=${enc(it)}" },
            "p=$page".takeIf { page > 0 },
        )
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
