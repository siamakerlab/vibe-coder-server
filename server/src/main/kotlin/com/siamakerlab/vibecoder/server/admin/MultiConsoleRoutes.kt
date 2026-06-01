package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val log = KotlinLogging.logger {}

/**
 * v0.36.0 — `/multi-console?projects=id1,id2,id3` — N개 프로젝트 콘솔을
 * iframe grid 로 동시 노출.
 *
 * 같은 origin 의 SSR 라우트라 cookie 가 그대로 흘러가 인증 추가 호출 불필요.
 * 단순 multi-pane orchestration — 동시 작업이 필요한 경우 (예: backend +
 * frontend 동시 진행) 한 화면에서 진행 상황 모니터.
 *
 * Layout:
 *   - 1 project   → 100% 너비 (single column)
 *   - 2 projects  → 50/50
 *   - 3+ projects → grid (auto-fit minmax(400px, 1fr))
 *
 * 최대 6개. 그 이상은 reject (browser performance).
 */
fun Routing.multiConsoleRoutes(authDeps: AdminRoutesDeps, projects: ProjectService) {
    get("/multi-console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val raw = call.request.queryParameters["projects"]?.trim().orEmpty()
        val ids = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.matches(Regex("[a-zA-Z0-9._-]+")) }
            .distinct()
            .take(MAX_PANES)
        val knownIds = ids.filter { id -> runCatching { projects.get(id) }.isSuccess }
        val all = projects.list()
        call.respondText(
            renderPage(sess.username, sess.csrf, knownIds, all, sess.language, call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }
}

private const val MAX_PANES = 6

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun renderPage(
    username: String,
    csrf: String?,
    selectedIds: List<String>,
    allProjects: List<com.siamakerlab.vibecoder.shared.dto.ProjectDto>,
    lang: String,
    embed: Boolean = false,
): String {
    val t = { key: String -> Messages.t(lang, key) }
    val n = selectedIds.size
    // grid-template 결정
    val gridStyle = when (n) {
        0, 1 -> "grid-template-columns: 1fr;"
        2 -> "grid-template-columns: repeat(2, 1fr);"
        else -> "grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));"
    }
    val checkboxes = allProjects.joinToString("") { p ->
        val checked = if (p.id in selectedIds) " checked" else ""
        """<label style="display:inline-flex;gap:4px;margin-right:10px;align-items:center;font-size:13px">
          <input type="checkbox" name="projects" value="${esc(p.id)}"$checked>
          <code>${esc(p.id)}</code>
        </label>"""
    }

    val panesHtml = if (n == 0) {
        """<div class="card" style="text-align:center;padding:30px">
          <p>${esc(Messages.t(lang, "multiconsole.pickAndOpen", MAX_PANES))}</p>
        </div>"""
    } else {
        selectedIds.joinToString("\n") { id ->
            """
<div style="border:1px solid var(--border);border-radius:8px;overflow:hidden;display:flex;flex-direction:column;min-height:500px">
  <div style="background:var(--surface);padding:8px 12px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--border)">
    <strong><code>${esc(id)}</code></strong>
    <a href="/projects/${esc(id)}/console" target="_blank" rel="noopener" class="chip chip-link" title="${esc(t("multiconsole.newTab"))}">↗</a>
  </div>
  <iframe src="/projects/${esc(id)}/console" style="border:0;width:100%;flex:1;background:#000"></iframe>
</div>"""
        }
    }

    return AdminTemplates.shell(
        title = "Multi-console",
        username = username,
        currentPath = "/multi-console",
        csrf = csrf,
        lang = lang,
        embed = embed,
        body = """
<header>
  <h1>Multi-console <small class="dim" style="font-size:14px;font-weight:400">${esc(t("multiconsole.subtitle"))}</small></h1>
</header>

<form method="get" action="/multi-console" class="card" style="margin-bottom:14px">
  <p style="margin:0 0 8px"><strong>${esc(t("multiconsole.pickLabel"))}</strong> ${esc(Messages.t(lang, "multiconsole.maxPanesSuffix", MAX_PANES))}</p>
  <div style="margin-bottom:10px">$checkboxes</div>
  <input type="hidden" name="_csrf_placeholder">
  <button type="submit" class="primary" style="padding:8px 14px"
    onclick="
      var form = this.form;
      var ids = [];
      form.querySelectorAll('input[name=projects]:checked').forEach(function(cb){ ids.push(cb.value); });
      ids = ids.slice(0, $MAX_PANES);
      // form GET 으로 보내는 대신 직접 URL 조립 (체크박스 multiple 가 form 에서 그대로 가지만 안전하게)
      window.location = '/multi-console?projects=' + encodeURIComponent(ids.join(','));
      return false;
    ">${esc(t("multiconsole.openBtn"))}</button>
  <p class="hint" style="margin:8px 0 0;font-size:11px">
    ${esc(t("multiconsole.iframeHint"))}
  </p>
</form>

<div style="display:grid;gap:12px;$gridStyle">
$panesHtml
</div>

<p class="hint" style="margin-top:14px;font-size:12px">
  ${t("multiconsole.subagentHint")}
</p>
"""
    )
}
