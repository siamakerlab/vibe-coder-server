package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.TokenService
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.env.AgentRegistry
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val log = KotlinLogging.logger {}

private val AGENT_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/**
 * v0.44.0 — Phase 23 sub-agent console routes. A project can spawn one Claude session per
 * registered custom agent (~/.claude/agents). Each runs as an independent child process so
 * they execute in parallel — e.g. a 'reviewer' agent reading the codebase while a 'frontend'
 * agent writes Compose UI.
 *
 * The main console at /projects/{id}/console keeps using the standard ClaudeSessionManager.
 * This module's pages live under "/projects/{id}/agents/" and run on SubAgentSessionManager.
 */
fun Routing.subAgentRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    manager: SubAgentSessionManager,
    agentRegistry: AgentRegistry,
    /** v0.65.0 — JSON endpoint Bearer 토큰 dual-auth 용. */
    tokens: TokenService,
    deviceRepo: DeviceRepository,
) {
    // ── /projects/{id}/agents — index: active sessions + spawn form ─────────
    get("/projects/{id}/agents") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respondRedirect("/projects")
        if (ProjectService.isGhost(id)) return@get call.respondRedirect("/chat")
        if (!requireProjectAccessOrRedirect(sess, projects, id)) return@get
        val p = projects.get(id) ?: return@get call.respondRedirect("/projects?err=not_found")

        val active = manager.activeAgentsFor(id).toSet()
        val agents = agentRegistry.list()

        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(sess.language, key) }
        val rowsHtml = if (agents.isEmpty()) {
            """<tr><td colspan="3" class="dim" style="padding:14px;text-align:center">
              ${t("agents.index.empty")}
            </td></tr>"""
        } else agents.joinToString("\n") { a ->
            val live = a.name in active
            val badge = if (live) """<span class="ok">${esc(t("agents.badge.running"))}</span>""" else """<span class="dim">${esc(t("agents.badge.idle"))}</span>"""
            val openHref = "/projects/${esc(p.id)}/agents/${esc(a.name)}/console"
            """
            <tr>
              <td><strong>@${esc(a.name)}</strong>
                <div class="dim" style="font-size:11px;margin-top:2px;max-width:520px">
                  ${esc(a.preview.lineSequence().firstOrNull().orEmpty().take(160))}
                </div>
              </td>
              <td>$badge</td>
              <td><a href="$openHref" class="chip chip-link">${esc(t("agents.openConsole"))}</a></td>
            </tr>"""
        }

        val body = """
<header>
  <h1>${esc(t("agents.index.heading"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 8px"><strong>${esc(t("agents.index.intro.title"))}</strong>
    ${esc(t("agents.index.intro.body"))}</p>
  <p class="dim" style="margin:0;font-size:12px">
    ${t("agents.index.lifecycle")}</p>
</div>

<div class="card">
  <table class="table" style="width:100%">
    <thead>
      <tr><th>${esc(t("table.agent"))}</th><th>${esc(t("table.status"))}</th><th></th></tr>
    </thead>
    <tbody>
$rowsHtml
    </tbody>
  </table>
</div>

<div style="margin-top:18px">
  ${AdminTemplates.backButton("/projects/${p.id}/console", t("agents.backToMainConsole"))}
  <a href="/agents" class="chip chip-link">${esc(t("agents.manage"))}</a>
</div>
"""
        call.respondText(
            AdminTemplates.shell(
                title = "${p.name} · sub-agent",
                username = sess.username,
                currentPath = "/projects",
                csrf = sess.csrf,
                body = body,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // ── /projects/{id}/agents/{agent}/console — per-agent console SSR ───────
    get("/projects/{id}/agents/{agent}/console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respondRedirect("/projects")
        val agentName = call.parameters["agent"] ?: return@get call.respondRedirect("/projects/$id/agents")
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@get call.respondRedirect("/projects/$id/agents?err=bad_name")
        }
        if (ProjectService.isGhost(id)) return@get call.respondRedirect("/chat")
        if (!requireProjectAccessOrRedirect(sess, projects, id)) return@get
        val p = projects.get(id) ?: return@get call.respondRedirect("/projects?err=not_found")
        // 등록된 agent 인지 확인 (없으면 dispatch 무의미).
        val agentBody = agentRegistry.read(agentName)
            ?: return@get call.respondRedirect("/projects/$id/agents?err=agent_unknown")

        val alive = manager.isAlive(id, agentName)
        val sessionId = manager.currentSessionId(id, agentName)
        val body = renderSubAgentConsole(p.id, p.name, agentName, agentBody, alive, sessionId, sess.csrf, sess.language)
        call.respondText(
            AdminTemplates.shell(
                title = "${p.name} · @$agentName",
                username = sess.username,
                currentPath = "/projects",
                csrf = sess.csrf,
                body = body,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // ── JSON: prompt ────────────────────────────────────────────────────────
    // v0.65.0 — dual-auth: Bearer 토큰 (Android client) OR cookie 세션 (admin SSR fetch).
    post("/api/projects/{id}/agents/{agent}/console/prompt") {
        val id = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "missing id")
        val agentName = call.parameters["agent"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "missing agent")
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respond(HttpStatusCode.BadRequest, "invalid agent name")
        }
        authorizeAgentJson(authDeps, tokens, deviceRepo, projects, id, requireWrite = true)
            ?: return@post
        projects.get(id) ?: return@post call.respond(HttpStatusCode.NotFound, "project not found")
        if (agentRegistry.read(agentName) == null) {
            return@post call.respond(HttpStatusCode.NotFound, "agent not registered")
        }

        val raw = call.receiveText()
        val text = runCatching {
            (Json.parseToJsonElement(raw) as? JsonObject)
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (text.isNullOrBlank()) return@post call.respond(HttpStatusCode.BadRequest, "missing text")

        runCatching { manager.sendPrompt(id, agentName, text) }
            .onFailure {
                log.warn(it) { "[$id::$agentName] sub-agent prompt failed" }
                return@post call.respond(HttpStatusCode.InternalServerError, it.message ?: "send failed")
            }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/api/projects/{id}/agents/{agent}/console/cancel") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val agentName = call.parameters["agent"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        authorizeAgentJson(authDeps, tokens, deviceRepo, projects, id, requireWrite = true)
            ?: return@post
        manager.cancelTurn(id, agentName)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    // ── JSON: 새 세션 강제 시작 (v0.66.0 신규 — main console 의 .../console/new 와 대칭) ─
    post("/api/projects/{id}/agents/{agent}/console/new") {
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val agentName = call.parameters["agent"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        authorizeAgentJson(authDeps, tokens, deviceRepo, projects, id, requireWrite = true)
            ?: return@post
        if (agentRegistry.read(agentName) == null) {
            return@post call.respond(HttpStatusCode.NotFound, "agent not registered")
        }
        manager.startNew(id, agentName)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/projects/{id}/agents/{agent}/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"] ?: return@post call.respondRedirect("/projects")
        val agentName = call.parameters["agent"] ?: return@post call.respondRedirect("/projects/$id/agents")
        // v1.31.0 (A-B4) — CSRF 검증. 폼은 _csrf hidden input 을 렌더하나 핸들러가
        // 빠뜨려 cross-site POST 로 sub-agent 세션 강제 재시작 가능했음.
        val form = call.receiveParameters()
        if (!CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            return@post call.respondRedirect("/projects/$id/agents?err=csrf")
        }
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respondRedirect("/projects/$id/agents?err=bad_name")
        }
        if (!requireProjectAccessOrRedirect(sess, projects, id)) return@post
        manager.startNew(id, agentName)
        call.respondRedirect("/projects/$id/agents/$agentName/console")
    }

    // ── JSON list of active sub-agents for a project (used by main console badge) ─
    // v0.65.0 — dual-auth: Bearer 토큰 (Android) OR cookie 세션 (SSR fetch).
    get("/api/projects/{id}/agents/active") {
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        authorizeAgentJson(authDeps, tokens, deviceRepo, projects, id, requireWrite = false)
            ?: return@get
        val active = manager.activeAgentsFor(id)
        val payload = buildJsonObject {
            put("projectId", JsonPrimitive(id))
            put("agents", buildJsonArray {
                active.forEach { add(JsonPrimitive(it)) }
            })
        }
        call.respondText(Json.encodeToString(JsonObject.serializer(), payload), ContentType.Application.Json)
    }
}

/**
 * v0.65.0 — sub-agent JSON endpoint 의 dual-auth helper.
 *
 * 반환 null = 응답 이미 보냄 (호출자는 return). non-null = 인증 통과 + Project ACL OK.
 *
 * 흐름:
 *   1. `Authorization: Bearer <token>` 헤더가 있으면 token hash 로 device 검증 + Project ACL.
 *      통과 시 (userId, isAdmin) 반환. 토큰 invalid 면 401.
 *      device.userId 가 null 인 legacy single-user 모드는 admin 으로 간주 (ACL skip).
 *   2. Bearer 없으면 cookie 세션 fallback (`requireSessionOrRedirect`).
 *      write 권한 요구 시 viewer 차단, ACL 검증.
 *
 * 같은 path 를 Android Bearer client 와 admin SSR fetch 양쪽에서 호출하므로 dual-mode 필요.
 * [com.siamakerlab.vibecoder.server.claude.HistoryRoutes] 의 `authorizeMemoStar` 와 동일 패턴
 * (그쪽은 ACL 없음 — memo/star 는 turn 단위 globally accessible).
 */
private suspend fun io.ktor.server.routing.RoutingContext.authorizeAgentJson(
    authDeps: AdminRoutesDeps,
    tokens: TokenService,
    deviceRepo: DeviceRepository,
    projects: ProjectService,
    projectId: String,
    requireWrite: Boolean,
): AgentJsonAuth? {
    val authHeader = call.request.headers["Authorization"]
    val bearer = if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true))
        authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim().ifBlank { null }
    else null

    if (bearer != null) {
        val hash = tokens.hashOf(bearer)
        val device = deviceRepo.findByTokenHash(hash)
        if (device == null) {
            call.respond(HttpStatusCode.Unauthorized, "invalid token")
            return null
        }
        val userId = device.userId
        // Legacy single-user 모드 (device.userId == null) 는 admin 으로 간주 — ACL skip.
        if (userId != null && !projects.canUserAccess(userId, isAdmin = false, projectId = projectId)) {
            call.respond(HttpStatusCode.Forbidden, "project_forbidden")
            return null
        }
        // v1.43.0 — 22차 정밀점검 회수: Bearer 분기가 requireWrite 를 무시해 viewer 토큰으로
        // 콘솔 prompt/cancel/new 같은 mutation 이 통과되던 권한 상승. cookie 분기
        // (requireWriteAccessOrRedirect) 와 정렬 — viewer 는 403.
        if (requireWrite && userId != null && authDeps.userRepo.findById(userId)?.canWrite != true) {
            call.respond(HttpStatusCode.Forbidden, "viewer_readonly")
            return null
        }
        return AgentJsonAuth(userId = userId, isAdmin = userId == null)
    }

    // Cookie 세션 fallback (admin SSR fetch).
    val sess = requireSessionOrRedirect(authDeps) ?: return null
    if (requireWrite) {
        // v1.31.1 (A-Q1) — cookie(SSR) 분기 mutation 은 CSRF 검증 (Bearer 분기는
        // cookie 미첨부라 CSRF 무관). HistoryRoutes.authorizeMemoStar 와 동일 패턴 —
        // 이전엔 cookie 분기가 CSRF 없이 mutate 허용하던 비대칭. JS 는 ?_csrf= 송신.
        if (!CsrfTokens.isValidCsrf(call, call.request.queryParameters["_csrf"])) {
            call.respond(HttpStatusCode.Forbidden, "csrf")
            return null
        }
        if (!requireWriteAccessOrRedirect(sess)) return null
    }
    if (!projects.canUserAccess(sess.userId, sess.isAdmin, projectId)) {
        call.respond(HttpStatusCode.Forbidden, "project_forbidden")
        return null
    }
    return AgentJsonAuth(userId = sess.userId, isAdmin = sess.isAdmin)
}

private data class AgentJsonAuth(val userId: String?, val isAdmin: Boolean)

/** Small console page. Trimmed version of the main console — no slash chips, no template / agent picker
 *  (you're already inside an agent), and the WS / REST endpoints all point at the sub-agent routes. */
private fun renderSubAgentConsole(
    projectId: String,
    projectName: String,
    agentName: String,
    agentBody: String,
    isAlive: Boolean,
    sessionId: String?,
    csrf: String?,
    lang: String,
): String {
    // v1.90.6 — clamp(접기/펼치기) 라벨 i18n.
    val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
    val statusBadge = when {
        isAlive -> """<span class="ok">running</span>"""
        sessionId != null -> """<span class="dim">idle (will resume)</span>"""
        else -> """<span class="dim">no session</span>"""
    }
    val projectIdJs = jsLit(projectId)
    val agentNameJs = jsLit(agentName)
    val firstLine = agentBody.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(180)

    return """
<header>
  <h1>@${esc(agentName)} <small class="dim" style="font-size:14px;font-weight:400">sub-agent · ${esc(projectName)} (${esc(projectId)})</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>세션:</strong> $statusBadge
      ${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      ${AdminTemplates.backButton("/projects/${projectId}/agents", "Sub-agent 목록")}
      <a href="/projects/${esc(projectId)}/console" class="chip chip-link">메인 콘솔 →</a>
      <button type="button" id="stop-btn" class="chip chip-danger" style="display:none">■ 중지</button>
      <form method="post" action="/projects/${esc(projectId)}/agents/${esc(agentName)}/new" style="display:inline"
            onsubmit="return confirm('현재 sub-agent 세션을 종료하고 새 대화를 시작할까요?')">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="chip chip-danger">새 세션</button>
      </form>
    </div>
  </div>
  <p class="dim" style="margin:8px 0 0;font-size:12px">
    Agent 정의: <em>${esc(firstLine)}</em>
  </p>
</div>

<!-- v1.90.4 — 마크다운 렌더 스타일(메인 콘솔 WebProjectTemplates 와 동기). sub-agent
     응답(assistant)도 renderMarkdown 적용하므로 동일 .md 스타일 필요. -->
<style>
  .log-body.md { line-height:1.55; }
  .log-body.md > :first-child { margin-top:0; }
  .log-body.md > :last-child { margin-bottom:0; }
  .log-body.md h1,.log-body.md h2,.log-body.md h3,.log-body.md h4,.log-body.md h5,.log-body.md h6 { margin:0.6em 0 0.3em; line-height:1.3; }
  .log-body.md h1 { font-size:1.4em; } .log-body.md h2 { font-size:1.25em; }
  .log-body.md h3 { font-size:1.12em; } .log-body.md h4 { font-size:1.02em; }
  .log-body.md ul,.log-body.md ol { margin:0.4em 0; padding-left:1.5em; }
  .log-body.md li { margin:0.15em 0; }
  .log-body.md code.md-code { background:rgba(255,255,255,0.08); padding:1px 5px; border-radius:4px; font-size:0.9em; }
  .log-body.md pre.md-pre { background:#161616; border:1px solid #2a2a2a; border-radius:6px; padding:10px 12px; overflow-x:auto; margin:0.5em 0; }
  .log-body.md pre.md-pre code { background:none; padding:0; font-size:0.88em; line-height:1.45; }
  .log-body.md blockquote { border-left:3px solid #444; margin:0.5em 0; padding:2px 0 2px 12px; color:var(--text-dim,#aaa); }
  .log-body.md a { color:#74b9ff; text-decoration:underline; }
  .log-body.md hr { border:none; border-top:1px solid #333; margin:0.8em 0; }
  .log-body.md strong { font-weight:600; }
  .log-body.md table.md-table { border-collapse:collapse; margin:0.5em 0; font-size:0.9em; display:block; overflow-x:auto; max-width:100%; }
  .log-body.md table.md-table th, .log-body.md table.md-table td { border:1px solid #3a3a3a; padding:4px 9px; text-align:left; }
  .log-body.md table.md-table th { background:rgba(255,255,255,0.06); font-weight:600; }
  .log-body.md table.md-table tr:nth-child(even) td { background:rgba(255,255,255,0.02); }
  /* v1.90.6 — 모든 메시지 접기/펼치기(메인 콘솔과 일관). */
  .log-content[data-clampable="1"] { position:relative; cursor:pointer; }
  .log-content.clamped .log-body { max-height:180px; overflow:hidden; }
  .log-content.clamped::after {
    content:'${t("console.message.expand")}'; position:absolute; left:0; right:0; bottom:0;
    text-align:center; font-size:11px; color:#74b9ff; padding:22px 0 4px;
    background:linear-gradient(to bottom, transparent, #1e1e1e 75%); pointer-events:none;
  }
  .log-content[data-clampable="1"]:not(.clamped)::after {
    content:'${t("console.message.collapse")}'; display:block; text-align:center;
    font-size:11px; color:#74b9ff; padding:6px 0 2px;
  }
</style>
<div id="console-log" class="console-log" aria-live="polite"></div>

<form id="prompt-form" class="prompt-form" autocomplete="off">
  <textarea id="prompt-input" rows="3" maxlength="32768"
            placeholder="이 sub-agent 에게 보낼 prompt 를 입력하세요. Enter 로 전송, Ctrl+Enter 로 줄바꿈."></textarea>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
    <small class="dim">첫 prompt 에 자동으로 'Use the @${esc(agentName)} sub-agent to …' prefix 가 붙습니다.</small>
    <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px">전송</button>
  </div>
</form>

<!-- v1.70.0 — 콘솔 친화 렌더러 (메인 콘솔과 공유). inline 스크립트보다 먼저 동기 로드. -->
<script src="/static/console-render.js?v=1.133.0"></script>
<script>
(function() {
  var projectId = $projectIdJs;
  var agentName = $agentNameJs;
  var logEl = document.getElementById('console-log');
  var form = document.getElementById('prompt-form');
  var input = document.getElementById('prompt-input');
  var sendBtn = document.getElementById('send-btn');
  var stopBtn = document.getElementById('stop-btn');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    // v1.90.4 — 메인 콘솔과 동일하게 assistant(sub-agent 응답)는 마크다운 렌더(escape 우선 →
    // XSS 안전). 그 외(tool/sys/err)는 raw escape. 이전엔 sub-agent 콘솔이 전부 escHtml 이라
    // code review 같은 마크다운 응답이 raw 로 보였다(메인 콘솔 v1.85.0 renderMarkdown 미반영분).
    var bodyHtml = (cls === 'assistant' && window.VibeConsole && window.VibeConsole.renderMarkdown)
      ? '<div class="log-body md">' + window.VibeConsole.renderMarkdown(body) + '</div>'
      : '<div class="log-body">' + escHtml(body) + '</div>';
    // v1.90.6 — 메인 콘솔과 동일하게 .log-content wrapper + 길이 기반 접기/펼치기.
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span>' +
      '<div class="log-content">' + bodyHtml + '</div>';
    logEl.appendChild(row);
    // user 프롬프트는 별도 2줄 클램프(.log-line.user.expanded)가 있어 제외.
    var contentEl = row.querySelector('.log-content');
    var bodyEl = row.querySelector('.log-body');
    if (cls !== 'user' && contentEl && bodyEl && bodyEl.scrollHeight > CLAMP_PX) {
      contentEl.dataset.clampable = '1';
      contentEl.classList.add('clamped');
    }
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }
  var CLAMP_PX = 180;
  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }
  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12));
    else if (t === 'console_assistant') append('assistant', 'assistant', f.text || '');
    else if (t === 'console_tool_use') {
      // v1.70.0 — 공용 친화 렌더러 (raw JSON 대신 한 줄 요약 + MCP/Task 등 인식).
      var ru = window.VibeConsole.renderToolUse(f.toolName, f.input);
      append('tool', ru.label, clip(ru.body, 500));
    } else if (t === 'console_tool_result') {
      // v1.70.0 — content 배열 평문 추출.
      var out = window.VibeConsole.extractToolResult(f.output);
      append(f.isError ? 'tool-err' : 'tool-out', f.isError ? 'tool-err' : '✓ result', clip(out, 1000));
    } else if (t === 'console_error') append('err', 'error', (f.code || '') + ': ' + (f.message || ''));
    else if (t === 'console_done') { append('sys', 'done', f.reason || 'end_turn'); setInFlight(false); }
    else if (t === 'console_system') {
      if (f.code === 'turn_cancelled' || f.code === 'process_crashed' || f.code === 'idle_terminated') setInFlight(false);
      append('sys', f.code || 'system', f.message || '');
    } else if (t === 'console_replay_begin') append('sys', 'replay', 'history begin (' + f.fromSeq + ' → ' + f.toSeq + ')');
    else if (t === 'console_replay_end') append('sys', 'replay', 'history end — live frames follow');
    else if (t === 'console_unknown') {
      // v1.70.0 — thinking / system task / rate_limit 등을 친화적으로. null 이면 숨김.
      var u = window.VibeConsole.renderUnknown(f.raw);
      if (u) append(u.cls, u.label, u.body);
    }
  }

  // v1.70.4 — 재연결 UX (메인 콘솔과 동일). 백그라운드 복귀/네트워크 복구 시 즉시 재연결.
  var ws = null;
  var reconnectTimer = null;
  var everConnected = false;
  var wsClosedNotified = false;
  function scheduleReconnect(delay) {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(function() { reconnectTimer = null; connect(); }, delay);
  }
  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/agents/' + agentName + '/console/logs');
    ws.onopen = function() {
      if (!everConnected) append('sys', 'ws', 'connected');
      else if (wsClosedNotified) append('sys', 'ws', '재연결됨');
      everConnected = true; wsClosedNotified = false;
    };
    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        if (f.type === 'error') { append('err', 'ws', (f.code || '') + ': ' + (f.message || '')); return; }
        renderFrame(f);
      } catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) {
      if (ev.code === 1000) return;
      if (!wsClosedNotified) { append('sys', 'ws', '연결 끊김 — 재연결 중…'); wsClosedNotified = true; }
      scheduleReconnect(2000);
    };
    ws.onerror = function() {};
  }
  function reconnectNow() {
    if (document.hidden) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    connect();
  }
  document.addEventListener('visibilitychange', function() { if (!document.hidden) reconnectNow(); });
  window.addEventListener('online', reconnectNow);
  window.addEventListener('focus', reconnectNow);
  connect();

  // v1.70.1 — 사용자 프롬프트 카드(2줄 클램프) 클릭 시 펼침/접힘.
  logEl.addEventListener('click', function(e) {
    var sel = window.getSelection && window.getSelection();
    if (sel && !sel.isCollapsed) return;  // 텍스트 선택 중엔 토글 안 함
    var card = e.target.closest && e.target.closest('.log-line.user');
    if (card) { card.classList.toggle('expanded'); return; }
    // v1.90.6 — 그 외 메시지: 접힌 콘텐츠 클릭 시 펼침/접힘(메인 콘솔과 일관).
    var content = e.target.closest && e.target.closest('.log-content[data-clampable="1"]');
    if (content) content.classList.toggle('clamped');
  });

  var inFlight = false;
  function setInFlight(on) { inFlight = on; if (stopBtn) stopBtn.style.display = on ? 'inline-block' : 'none'; }
  async function cancelTurn() {
    if (!inFlight) return;
    try {
      await fetch('/api/projects/' + projectId + '/agents/' + agentName + '/console/cancel?_csrf=' + encodeURIComponent(window.__VIBE_CSRF__ || ''),
        { method: 'POST', credentials: 'same-origin' });
      append('sys', 'cancel', '사용자 중단 요청 전송됨');
    } catch (e) { append('err', 'cancel', String(e)); }
    finally { setInFlight(false); }
  }
  if (stopBtn) stopBtn.addEventListener('click', cancelTurn);

  async function sendPrompt(text) {
    sendBtn.disabled = true;
    setInFlight(true);
    try {
      var res = await fetch('/api/projects/' + projectId + '/agents/' + agentName + '/console/prompt?_csrf=' + encodeURIComponent(window.__VIBE_CSRF__ || ''), {
        method: 'POST', credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({text: text}),
      });
      if (!res.ok) { var msg = await res.text(); append('err', 'send', res.status + ' ' + msg); setInFlight(false); }
      else { append('user', 'user', text); input.value = ''; }
    } catch (e) { append('err', 'send', String(e)); setInFlight(false); }
    finally { sendBtn.disabled = false; input.focus(); }
  }
  form.addEventListener('submit', function(ev) {
    ev.preventDefault();
    var text = input.value.trim();
    if (text) sendPrompt(text);
  });
  input.addEventListener('keydown', function(ev) {
    // v1.69.0 — 전송: Enter · 줄바꿈: Ctrl/Cmd+Enter (또는 Shift+Enter)
    if (ev.key !== 'Enter' || ev.isComposing) return;
    if (ev.ctrlKey || ev.metaKey) {
      ev.preventDefault();
      var s = input.selectionStart, e = input.selectionEnd, nl = String.fromCharCode(10);
      input.value = input.value.substring(0, s) + nl + input.value.substring(e);
      input.selectionStart = input.selectionEnd = s + 1;
      return;
    }
    if (ev.shiftKey || ev.altKey) return;
    ev.preventDefault();
    form.requestSubmit();
  });
})();
</script>
"""
}

private fun esc(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

private fun jsLit(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '<' -> sb.append("\\u003c")
            '>' -> sb.append("\\u003e")
            '&' -> sb.append("\\u0026")
            else -> sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
