package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.env.McpJsonStore
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v1.35.0 — 프로젝트별 MCP 관리 (`<projectRoot>/.mcp.json`).
 *
 * 전역(`~/.claude/.mcp.json`) server 는 읽기 전용으로 표시(편집은 MCP 전역 탭),
 * 프로젝트 `.mcp.json` 은 raw JSON 으로 직접 편집. 두 목록 모두 디스크 파싱이라
 * 터미널/Claude 가 직접 등록한 server 도 감지된다. JSON 유효성 검증 후 atomic 저장.
 */
fun Routing.projectMcpRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    workspace: WorkspacePath,
    globalMcp: McpService,
) {
    fun esc(s: String?) = s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    get("/projects/{id}/mcp") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val t = { key: String -> Messages.t(sess.language, key) }
        val projectPath = workspace.projectRoot(id).resolve(".mcp.json")
        val globalNames = runCatching { globalMcp.registeredServerNames() }.getOrElse { emptyList() }
        val projectNames = McpJsonStore.serverNames(projectPath)
        val raw = McpJsonStore.readRaw(projectPath).ifBlank { "{\n  \"mcpServers\": {\n  }\n}\n" }
        val ok = call.request.queryParameters["ok"]?.let { if (it == "saved") t("mcpProject.flash.saved") else null }
        val err = call.request.queryParameters["err"]

        fun chips(names: List<String>) =
            if (names.isEmpty()) """<span class="dim">${esc(t("scope.empty"))}</span>"""
            else names.joinToString(" ") { """<span class="chip">${esc(it)}</span>""" }

        val body = """
<header><h1 style="margin:0">${esc(t("mcpProject.title"))}</h1></header>
${if (ok != null) """<div class="ok-banner">✓ ${esc(ok)}</div>""" else ""}
${if (!err.isNullOrBlank()) """<div class="error">${esc(err)}</div>""" else ""}
<p class="dim" style="font-size:13px;margin:6px 0 16px;line-height:1.5">${esc(t("mcpProject.intro"))}</p>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <h2 style="margin:0;font-size:15px">${esc(t("scope.global"))} <span class="dim" style="font-size:11px;font-weight:400;font-family:ui-monospace,Menlo,monospace">~/.claude/.mcp.json</span></h2>
    <a href="/env-setup/mcp" class="chip chip-link">${esc(t("scope.manageGlobal"))} →</a>
  </div>
  <div style="margin-top:10px;display:flex;flex-wrap:wrap;gap:6px">${chips(globalNames)}</div>
</div>

<div class="card">
  <h2 style="margin:0 0 4px;font-size:15px">${esc(t("scope.project"))} <span class="dim" style="font-size:11px;font-weight:400;font-family:ui-monospace,Menlo,monospace">$id/.mcp.json</span></h2>
  <div style="margin:6px 0 12px;display:flex;flex-wrap:wrap;gap:6px">${chips(projectNames)}</div>
  <form method="post" action="/projects/$id/mcp/save" id="mcp-form" style="display:grid;gap:10px">
    ${CsrfTokens.hiddenInput(sess.csrf)}
    <textarea name="content" id="mcp-content" rows="22" spellcheck="false"
              style="width:100%;font-family:ui-monospace,Menlo,monospace;font-size:13px;line-height:1.5;tab-size:2">${esc(raw)}</textarea>
    <div style="display:flex;gap:10px;align-items:center">
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">${esc(t("scope.save"))}</button>
      <span class="dim" style="font-size:12px">${esc(t("mcpProject.rawHint"))}</span>
    </div>
  </form>
</div>

<script>
(function(){
  var ta=document.getElementById('mcp-content'),f=document.getElementById('mcp-form');
  if(!ta||!f)return;
  document.addEventListener('keydown',function(e){if((e.ctrlKey||e.metaKey)&&(e.key==='s'||e.key==='S')){e.preventDefault();f.submit();}});
  ta.addEventListener('keydown',function(e){if(e.key==='Tab'){e.preventDefault();var s=ta.selectionStart,en=ta.selectionEnd;ta.value=ta.value.substring(0,s)+'  '+ta.value.substring(en);ta.selectionStart=ta.selectionEnd=s+2;}});
})();
</script>
"""
        call.respondText(
            AdminTemplates.shell(
                title = t("mcpProject.title"), username = sess.username, currentPath = "/projects",
                csrf = sess.csrf, lang = sess.language, body = body,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/mcp/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val content = call.receiveParameters()["content"].orEmpty()
        if (content.toByteArray(StandardCharsets.UTF_8).size > McpJsonStore.MAX_BYTES) {
            call.respondRedirect("/projects/$id/mcp?err=${enc("file too large (max 128KB)")}")
            return@post
        }
        val errMsg = runCatching {
            McpJsonStore.writeRawValidated(workspace.projectRoot(id).resolve(".mcp.json"), content)
        }.getOrElse { it.message ?: "save failed" }
        if (errMsg != null) {
            call.respondRedirect("/projects/$id/mcp?err=${enc(errMsg)}")
            return@post
        }
        log.info { "project .mcp.json saved: $id by ${sess.username}" }
        call.respondRedirect("/projects/$id/mcp?ok=saved")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
