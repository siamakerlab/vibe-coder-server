package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v0.31.0 — `/agents` SSR.
 *
 *   GET  /agents                — 목록 + 신규 작성 폼
 *   GET  /agents/{name}/edit    — 본문 편집 (기존)
 *   POST /agents/save           — 생성/수정 (이름 + 본문)
 *   POST /agents/{name}/delete  — 삭제
 *
 * Audit: 모든 변경에 `agent.save` / `agent.delete` 액션 기록.
 */
fun Routing.agentRoutes(authDeps: AdminRoutesDeps, registry: AgentRegistry) {
    get("/agents") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val agents = runCatching { registry.list() }.getOrElse { emptyList() }
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(AgentTemplates.listPage(sess.username, agents, ok, err, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()), ContentType.Text.Html)
    }

    get("/agents/{name}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val name = call.parameters["name"] ?: run {
            call.respondRedirect("/agents"); return@get
        }
        val body = registry.read(name)
        if (body == null) {
            call.respondRedirect("/agents?err=${enc("agent '$name' not found")}")
            return@get
        }
        call.respondText(AgentTemplates.editPage(sess.username, name, body, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()), ContentType.Text.Html)
    }

    post("/agents/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val params = call.receiveParameters()
        val name = params["name"]?.trim().orEmpty()
        val body = params["body"].orEmpty()
        runCatching { registry.write(name, body) }
            .onSuccess {
                authDeps.audit.agentSave(sess.userId, call.request.origin.remoteHost, name)
                call.respondRedirect("/agents?ok=${enc("agent '$name' 저장됨")}")
            }
            .onFailure { e ->
                log.warn(e) { "agent save failed: $name" }
                call.respondRedirect("/agents?err=${enc(e.message ?: "save failed")}")
            }
    }

    post("/agents/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val name = call.parameters["name"] ?: run {
            call.respondRedirect("/agents"); return@post
        }
        val ok = registry.delete(name)
        authDeps.audit.agentDelete(sess.userId, call.request.origin.remoteHost, name, ok)
        val q = if (ok) "ok=${enc("agent '$name' 삭제됨")}" else "err=${enc("'$name' 삭제 실패")}"
        call.respondRedirect("/agents?$q")
    }

    // v0.36.0 — JSON API for console UI agent-dispatch dropdown.
    authenticate(AUTH_BEARER) {
        get("/api/agents") {
            val list = runCatching { registry.list() }.getOrElse { emptyList() }
            call.respond(mapOf("agents" to list.map {
                mapOf(
                    "name" to it.name,
                    "sizeBytes" to it.sizeBytes,
                    "preview" to it.preview.take(200),
                )
            }))
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

private object AgentTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    // v1.51.0 — 25차: onclick JS 문자열 컨텍스트용. esc(escJs(x)) 순서(JS escape 후 HTML escape).
    // 디스크 스캔 agent 파일명에 ' / 가 있으면 esc 만으론 HTML 디코드 후 JS 탈출(XSS).
    private fun escJs(s: String?): String =
        s.orEmpty().replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    fun listPage(
        username: String,
        agents: List<AgentRegistry.Agent>,
        ok: String?,
        err: String?,
        csrf: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        val rowsHtml = if (agents.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">No custom agents yet. Add one with the form below — saved to <code>$CLAUDE_DIR_NOTE</code>.</td></tr>"""
        } else {
            agents.joinToString("") { a ->
                val sizeKb = (a.sizeBytes + 512L) / 1024L
                val ts = java.time.Instant.ofEpochMilli(a.updatedAtMs).toString()
                """<tr>
                  <td><a href="/agents/${esc(a.name)}/edit"><code>${esc(a.name)}</code></a></td>
                  <td>${sizeKb}KB</td>
                  <td class="dim" style="font-family:ui-monospace,Menlo,monospace;font-size:11px">${esc(ts)}</td>
                  <td>
                    <pre style="margin:0;font-size:11px;white-space:pre-wrap;word-break:break-word;max-width:600px;opacity:0.7">${esc(a.preview.take(200))}</pre>
                    <form method="post" action="/agents/${esc(a.name)}/delete" style="display:inline">
                      ${CsrfTokens.hiddenInput(csrf)}
                      <button type="submit" class="chip chip-danger" onclick="return confirm('${esc(escJs(a.name))} 삭제?')">삭제</button>
                    </form>
                  </td>
                </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "Custom agents",
            username = username,
            currentPath = "/agents",
            csrf = csrf,
            embed = embed,
            body = """
<header>
  <h1>Custom agents <small class="dim" style="font-size:14px;font-weight:400">~/.claude/agents/*.md</small></h1>
</header>

$okHtml
$errHtml

<table class="devices" style="margin-bottom:16px">
  <thead><tr><th>이름</th><th>크기</th><th>마지막 수정 (UTC)</th><th>미리보기 / 동작</th></tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<div class="card">
  <h2 style="margin-top:0">새 agent</h2>
  <form method="post" action="/agents/save" style="display:grid;gap:10px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>이름 (영문/숫자/.-_, 64자 이내)
      <input name="name" required pattern="[A-Za-z0-9._\\-]{1,64}" placeholder="e.g. code-reviewer">
    </label>
    <label>본문 (Markdown, 최대 64 KB)
      <textarea name="body" rows="14" required placeholder="---&#10;name: code-reviewer&#10;description: Reviews PRs for security issues&#10;---&#10;&#10;You are a security-focused code reviewer. ..."></textarea>
    </label>
    <div>
      <button type="submit" class="primary">저장</button>
      <a href="https://docs.anthropic.com/en/docs/claude-code/sub-agents" target="_blank" class="chip chip-link">Anthropic docs ↗</a>
    </div>
  </form>
</div>

<p class="hint" style="margin-top:12px;font-size:12px">
  파일은 <code>$CLAUDE_DIR_NOTE</code> 에 저장. Claude Code CLI 가 부팅 시
  읽어 sub-agent 로 사용. 컨테이너 재시작 / 이미지 업그레이드와 무관 (볼륨).
</p>
""",
            lang = lang,
        )
    }

    fun editPage(
        username: String,
        name: String,
        body: String,
        csrf: String?,

        lang: String,
        embed: Boolean = false,
    ): String = AdminTemplates.shell(
        title = "Edit agent: $name",
        username = username,
        currentPath = "/agents",
        csrf = csrf,
        embed = embed,
        body = """
<div style="margin-bottom:14px">${AdminTemplates.backButton("/agents", com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "scope.back"))}</div>
<header>
  <h1>Edit agent <code>${esc(name)}</code></h1>
</header>

<form method="post" action="/agents/save" class="card" style="display:grid;gap:10px">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="name" value="${esc(name)}">
  <label>본문 (Markdown, 최대 64 KB)
    <textarea name="body" rows="22">${esc(body)}</textarea>
  </label>
  <div style="display:flex;gap:6px">
    <button type="submit" class="primary">저장</button>
    ${AdminTemplates.backButton("/agents", com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "scope.back"))}
  </div>
</form>
""",
        lang = lang,
    )

    private const val CLAUDE_DIR_NOTE = "/home/vibe/.claude/agents/"
}
