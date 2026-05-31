package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.db.ConversationTurns
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRow
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * v0.30.0 — `/history` 글로벌 검색.
 *
 * 기존 `/projects/{id}/history` 와 `/chat/history` 는 프로젝트 1개 안에서만
 * 검색. 본 페이지는 **모든 프로젝트의 conversation_turns** 를 한 번에 grep
 * 해서, "예전에 어디서 X 라는 말을 했더라" 같은 use case 를 처리.
 *
 * 안전 정책:
 *   - q 가 비면 빈 결과 반환 (수만 turn 전체 dump 방지).
 *   - LIKE escape (% / _ / \) → SQL injection 보호.
 *   - limit 200 hard cap.
 */
fun Routing.globalHistorySearchRoutes(authDeps: AdminRoutesDeps) {
    get("/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        // B3 (21차 점검) — cross-project 대화 검색은 JSON twin(/api/history/search)
        // 과 동일하게 admin 전용. 비-admin 은 프로젝트별 /projects/{id}/history 사용.
        if (!requireAdminOrRedirect(sess)) return@get
        val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
        val role = call.request.queryParameters["role"]?.trim()?.ifBlank { null }
        val rows = if (q == null) emptyList() else searchAll(q, role, limit = 200)
        call.respondText(
            renderPage(sess.username, sess.csrf, q, role, rows, sess.language),
            ContentType.Text.Html,
        )
    }
}

/**
 * v0.64.0 — JSON variant (`/api/history/search`) 가 같은 검색 로직을 재사용하도록
 * internal 로 노출. SSR `/history` 와 동일한 escape/limit 정책.
 */
internal fun globalSearchAll(q: String, role: String?, limit: Int): List<ConversationTurnRow> =
    searchAll(q, role, limit)

private fun searchAll(q: String, role: String?, limit: Int): List<ConversationTurnRow> = transaction {
    val escapedQ = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    var cond: Op<Boolean> = ConversationTurns.content like "%$escapedQ%"
    if (role != null) cond = cond and (ConversationTurns.role eq role)
    ConversationTurns.selectAll().where { cond }
        .orderBy(ConversationTurns.ts to SortOrder.DESC)
        .limit(limit)
        .map {
            ConversationTurnRow(
                id = it[ConversationTurns.id].toString(),
                projectId = it[ConversationTurns.projectId],
                sessionId = it[ConversationTurns.sessionId],
                turnIdx = it[ConversationTurns.turnIdx],
                ts = it[ConversationTurns.ts],
                role = it[ConversationTurns.role],
                content = it[ConversationTurns.content],
                toolName = it[ConversationTurns.toolName],
                toolUseId = it[ConversationTurns.toolUseId],
                tokensIn = it[ConversationTurns.tokensIn],
                tokensOut = it[ConversationTurns.tokensOut],
                raw = it[ConversationTurns.raw],
            )
        }
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun highlightMatch(content: String, q: String): String {
    val safe = esc(content)
    if (q.isBlank()) return safe
    val safeQ = esc(q)
    // case-insensitive replace (HTML 상에서 안전하게).
    val regex = Regex(Regex.escape(safeQ), RegexOption.IGNORE_CASE)
    return regex.replace(safe) { """<mark style="background:#facc15;color:#111">${it.value}</mark>""" }
}

private fun renderPage(
    username: String,
    csrf: String?,
    q: String?,
    role: String?,
    rows: List<ConversationTurnRow>,
    lang: String,
): String {
    val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
    val roleOpts = listOf("", "user", "assistant", "tool_use", "tool_result", "system", "error")
        .joinToString("") { v ->
            val label = if (v.isEmpty()) "(all roles)" else v
            val sel = if (v == role) " selected" else ""
            """<option value="${esc(v)}"$sel>${esc(label)}</option>"""
        }
    val rowsHtml = if (rows.isEmpty() && q != null) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">no matches for "${esc(q)}"</td></tr>"""
    } else if (rows.isEmpty()) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">검색어를 입력하면 모든 프로젝트의 대화 기록을 가로질러 매치를 찾아 보여줍니다.</td></tr>"""
    } else {
        rows.joinToString("\n") { r ->
            val roleCls = when (r.role) {
                "user" -> "user"
                "assistant" -> "assistant"
                "tool_use" -> "tool"
                "tool_result" -> "tool-out"
                "error", "tool_result_error" -> "err"
                else -> "sys"
            }
            val href = if (r.projectId == "__scratch__") "/chat/history" else "/projects/${esc(r.projectId)}/history"
            val previewLen = 500
            // v1.70.2 — raw JSON 대신 친화 변환 후 발췌/하이라이트(콘솔 렌더러의 서버측 짝).
            val raw = HistoryContentFormatter.friendly(r.role, r.toolName, r.content)
            // 매치 위치 주변만 발췌 (앞뒤 100자) 가독성 ↑
            val excerpted = run {
                val q0 = q ?: ""
                val idx = raw.indexOf(q0, ignoreCase = true)
                if (idx < 0 || q0.isBlank()) raw.take(previewLen)
                else {
                    val from = (idx - 100).coerceAtLeast(0)
                    val to = (idx + q0.length + 200).coerceAtMost(raw.length)
                    val prefix = if (from > 0) "…" else ""
                    val suffix = if (to < raw.length) "…" else ""
                    prefix + raw.substring(from, to) + suffix
                }
            }
            """<tr>
              <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px;white-space:nowrap">${esc(r.ts)}</td>
              <td><a href="$href"><code>${esc(r.projectId.take(20))}</code></a><br><span class="$roleCls" style="font-size:11px;text-transform:uppercase">${esc(r.role)}</span>${if (r.toolName != null) """<small class="dim" style="font-size:11px"> · ${esc(r.toolName)}</small>""" else ""}</td>
              <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px">${highlightMatch(excerpted, q ?: "")}</pre></td>
            </tr>"""
        }
    }

    return AdminTemplates.shell(
        title = "대화 검색",
        username = username,
        currentPath = "/history",
        csrf = csrf,
        body = """
<header>
  <h1>대화 검색 <small class="dim" style="font-size:14px;font-weight:400">모든 프로젝트 가로지름</small></h1>
</header>

<form method="get" action="/history" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:1fr 200px auto;gap:8px;align-items:end">
  <label style="margin:0">검색어 (대소문자 무시, LIKE)
    <input type="text" name="q" value="${esc(q)}" placeholder="settings screen / TODO / Bash ..." autofocus>
  </label>
  <label style="margin:0">Role
    <select name="role">$roleOpts</select>
  </label>
  <div>
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
  <small class="dim">${if (q == null) "" else "Top ${rows.size} 매치 (최신순, 200개 cap). 각 행은 매치 위치 ±100자 발췌."}</small>
</div>

<table class="devices">
  <thead><tr>
    <th style="width:160px">${esc(t("table.timeUtc"))}</th>
    <th style="width:180px">${esc(t("table.projectRole"))}</th>
    <th>${esc(t("table.matchPreview"))}</th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  Project 단위 풀 history 는 <a href="/projects">프로젝트 목록</a> 에서 각 프로젝트의
  "대화 히스토리" 링크. <a href="/chat/history">Chat 히스토리</a> 는 General Chat 전용.
  다음 cycle 에서 PostgreSQL FTS (tsvector) 로 정확도/성능 향상 예정.
</p>
""",
        lang = lang,
    )
}
