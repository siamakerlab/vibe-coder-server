package com.siamakerlab.vibecoder.server.audit

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.repo.AuditLogRepository
import com.siamakerlab.vibecoder.server.repo.AuditLogRow

object AuditTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        rows: List<AuditLogRow>,
        filter: AuditLogRepository.Filter,
        actions: List<String>,
        page: Int,
        pageSize: Int,
        total: Long,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val actionOpts = ("""<option value="">(all)</option>""" +
            actions.joinToString("") { a ->
                val sel = if (a == filter.action) " selected" else ""
                """<option value="${esc(a)}"$sel>${esc(a)}</option>"""
            })
        fun resultOpt(v: String, label: String): String {
            val sel = if (v == filter.result) " selected" else ""
            return """<option value="${esc(v)}"$sel>${esc(label)}</option>"""
        }

        val rowsHtml = if (rows.isEmpty()) {
            """<tr><td colspan="7" class="dim" style="text-align:center;padding:14px">no matching events</td></tr>"""
        } else {
            rows.joinToString("\n") { r ->
                val resultCls = when (r.result) {
                    "OK" -> "ok"
                    "FAIL", "DENIED" -> "warn"
                    else -> "dim"
                }
                """<tr>
                  <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px;white-space:nowrap">${esc(r.ts)}</td>
                  <td><code>${esc(r.action)}</code></td>
                  <td class="$resultCls">${esc(r.result)}</td>
                  <td>${esc(r.userId ?: "-")}</td>
                  <td class="dim" style="font-size:11px">${esc(r.ip ?: "-")}</td>
                  <td>${esc(r.resourceType?.let { "$it: ${r.resourceId ?: ""}" } ?: "-")}</td>
                  <td class="dim" style="font-size:11px;max-width:300px;overflow:hidden;text-overflow:ellipsis">${esc(r.detail ?: "")}</td>
                </tr>"""
            }
        }

        val from = page * pageSize + 1
        val to = (page * pageSize + rows.size).coerceAtMost(total.toInt())
        val nextPage = page + 1
        val prevPage = (page - 1).coerceAtLeast(0)
        val hasNext = (page + 1) * pageSize < total
        val hasPrev = page > 0

        val nextHref = buildHref(filter, nextPage)
        val prevHref = buildHref(filter, prevPage)

        return AdminTemplates.shell(
            title = "감사 로그",
            username = username,
            currentPath = "/audit",
            csrf = csrf,
            embed = embed,
            body = """
<header>
  <h1>감사 로그 <small class="dim" style="font-size:14px;font-weight:400">$total 개</small></h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">운영 행위 추적. 로그인 / 비번 변경 / 디바이스 revoke / 프로젝트 / 빌드 / MCP / settings / git 토큰 / 콘솔 new-cancel.</p>
</header>

<form method="get" action="/audit" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;align-items:end">
  <label style="margin:0">Action
    <select name="action" style="width:100%">$actionOpts</select>
  </label>
  <label style="margin:0">Result
    <select name="result" style="width:100%">
      <option value="">(all)</option>
      ${resultOpt("OK", "OK")}
      ${resultOpt("FAIL", "FAIL")}
      ${resultOpt("DENIED", "DENIED")}
    </select>
  </label>
  <label style="margin:0">User ID
    <input type="text" name="user" value="${esc(filter.userId)}" placeholder="user id">
  </label>
  <label style="margin:0">From (ISO ts)
    <input type="text" name="from" value="${esc(filter.fromTs)}" placeholder="2026-05-24T00:00:00Z">
  </label>
  <label style="margin:0">To (ISO ts)
    <input type="text" name="to" value="${esc(filter.toTs)}" placeholder="2026-05-25T00:00:00Z">
  </label>
  <div style="display:flex;gap:6px">
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
    <a href="/audit" class="chip chip-link" style="padding:8px 14px">초기화</a>
  </div>
</form>

<table class="devices">
  <thead>
    <tr>
      <th style="width:160px">Time (UTC)</th>
      <th>Action</th>
      <th style="width:80px">Result</th>
      <th style="width:120px">User</th>
      <th style="width:120px">IP</th>
      <th>Resource</th>
      <th>Detail</th>
    </tr>
  </thead>
  <tbody>
    $rowsHtml
  </tbody>
</table>

<div style="display:flex;justify-content:space-between;align-items:center;margin-top:12px;gap:8px;flex-wrap:wrap">
  <small class="dim">${if (rows.isEmpty()) "0 / $total" else "$from–$to / $total"}</small>
  <div style="display:flex;gap:6px">
    ${if (hasPrev) """<a href="$prevHref" class="chip chip-link">← Prev</a>""" else """<span class="chip" style="opacity:0.4">← Prev</span>"""}
    ${if (hasNext) """<a href="$nextHref" class="chip chip-link">Next →</a>""" else """<span class="chip" style="opacity:0.4">Next →</span>"""}
  </div>
</div>

<p class="hint" style="font-size:12px;margin-top:14px">
  Time 은 UTC ISO-8601. ts range 비교는 문자열 사전식 비교라 ISO-8601 인덱스 친화적.
  100개씩 page. 미래 사이클에서 ts 인덱스 + JSON detail 검색 추가 예정.
</p>
""",
            lang = lang,
        )
    }

    private fun buildHref(filter: AuditLogRepository.Filter, page: Int): String {
        val params = listOfNotNull(
            filter.action?.let { "action=${enc(it)}" },
            filter.result?.let { "result=${enc(it)}" },
            filter.userId?.let { "user=${enc(it)}" },
            filter.fromTs?.let { "from=${enc(it)}" },
            filter.toTs?.let { "to=${enc(it)}" },
            "p=$page".takeIf { page > 0 },
        )
        return if (params.isEmpty()) "/audit" else "/audit?" + params.joinToString("&")
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
