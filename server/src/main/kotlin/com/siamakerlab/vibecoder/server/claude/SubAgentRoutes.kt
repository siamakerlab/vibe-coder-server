package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.env.AgentRegistry
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
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
) {
    // ── /projects/{id}/agents — index: active sessions + spawn form ─────────
    get("/projects/{id}/agents") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respondRedirect("/projects")
        if (id == ProjectService.SCRATCH_ID) return@get call.respondRedirect("/chat")
        val p = projects.get(id) ?: return@get call.respondRedirect("/projects?err=not_found")

        val active = manager.activeAgentsFor(id).toSet()
        val agents = agentRegistry.list()

        val rowsHtml = if (agents.isEmpty()) {
            """<tr><td colspan="3" class="dim" style="padding:14px;text-align:center">
              등록된 custom agent 가 없습니다. <a href="/agents" class="chip chip-link">/agents</a> 에서 먼저 추가하세요.
            </td></tr>"""
        } else agents.joinToString("\n") { a ->
            val live = a.name in active
            val badge = if (live) """<span class="ok">running</span>""" else """<span class="dim">idle</span>"""
            val openHref = "/projects/${esc(p.id)}/agents/${esc(a.name)}/console"
            """
            <tr>
              <td><strong>@${esc(a.name)}</strong>
                <div class="dim" style="font-size:11px;margin-top:2px;max-width:520px">
                  ${esc(a.preview.lineSequence().firstOrNull().orEmpty().take(160))}
                </div>
              </td>
              <td>$badge</td>
              <td><a href="$openHref" class="chip chip-link">콘솔 열기 →</a></td>
            </tr>"""
        }

        val body = """
<header>
  <h1>Sub-agent consoles
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 8px"><strong>Real multi-agent (v0.44.0+).</strong>
    각 sub-agent 마다 별도 Claude child process 가 spawn 되어 메인 콘솔과 병렬로 동작합니다.
    같은 프로젝트 워크스페이스를 공유하므로 reviewer / frontend / backend 같은 역할 분담에 적합합니다.</p>
  <p class="dim" style="margin:0;font-size:12px">
    Idle 30 분 후 자동 SIGTERM (다음 prompt 시 같은 sessionId 로 resume).
    Agent 별 session-id 는 <code>.vibecoder/agent-sessions/&lt;agent&gt;.id</code> 에 영속.</p>
</div>

<div class="card">
  <table class="table" style="width:100%">
    <thead>
      <tr><th>Agent</th><th>상태</th><th></th></tr>
    </thead>
    <tbody>
$rowsHtml
    </tbody>
  </table>
</div>

<div style="margin-top:18px">
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">← 메인 콘솔</a>
  <a href="/agents" class="chip chip-link">Agent 정의 관리</a>
</div>
"""
        call.respondText(
            AdminTemplates.shell(
                title = "${p.name} · sub-agent",
                username = sess.username,
                currentPath = "/projects",
                csrf = sess.csrf,
                body = body,
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
        if (id == ProjectService.SCRATCH_ID) return@get call.respondRedirect("/chat")
        val p = projects.get(id) ?: return@get call.respondRedirect("/projects?err=not_found")
        // 등록된 agent 인지 확인 (없으면 dispatch 무의미).
        val agentBody = agentRegistry.read(agentName)
            ?: return@get call.respondRedirect("/projects/$id/agents?err=agent_unknown")

        val alive = manager.isAlive(id, agentName)
        val sessionId = manager.currentSessionId(id, agentName)
        val body = renderSubAgentConsole(p.id, p.name, agentName, agentBody, alive, sessionId, sess.csrf)
        call.respondText(
            AdminTemplates.shell(
                title = "${p.name} · @$agentName",
                username = sess.username,
                currentPath = "/projects",
                csrf = sess.csrf,
                body = body,
            ),
            ContentType.Text.Html,
        )
    }

    // ── JSON: prompt ────────────────────────────────────────────────────────
    post("/api/projects/{id}/agents/{agent}/console/prompt") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "missing id")
        val agentName = call.parameters["agent"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, "missing agent")
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respond(HttpStatusCode.BadRequest, "invalid agent name")
        }
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
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val agentName = call.parameters["agent"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        manager.cancelTurn(id, agentName)
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    post("/projects/{id}/agents/{agent}/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"] ?: return@post call.respondRedirect("/projects")
        val agentName = call.parameters["agent"] ?: return@post call.respondRedirect("/projects/$id/agents")
        if (!AGENT_NAME_PATTERN.matches(agentName)) {
            return@post call.respondRedirect("/projects/$id/agents?err=bad_name")
        }
        manager.startNew(id, agentName)
        call.respondRedirect("/projects/$id/agents/$agentName/console")
    }

    // ── JSON list of active sub-agents for a project (used by main console badge) ─
    get("/api/projects/{id}/agents/active") {
        requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
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
): String {
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
      <a href="/projects/${esc(projectId)}/agents" class="chip chip-link">← Sub-agent 목록</a>
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

<div id="console-log" class="console-log" aria-live="polite"></div>

<form id="prompt-form" class="prompt-form" autocomplete="off">
  <textarea id="prompt-input" rows="3" maxlength="32768"
            placeholder="이 sub-agent 에게 보낼 prompt 를 입력하세요. Ctrl+Enter 로 전송."></textarea>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
    <small class="dim">첫 prompt 에 자동으로 'Use the @${esc(agentName)} sub-agent to …' prefix 가 붙습니다.</small>
    <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px">전송</button>
  </div>
</form>

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
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }
  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }
  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12));
    else if (t === 'console_assistant') append('assistant', 'assistant', f.text || '');
    else if (t === 'console_tool_use') {
      var raw = typeof f.input === 'string' ? f.input : JSON.stringify(f.input || {});
      append('tool', f.toolName || 'tool', clip(raw, 400));
    } else if (t === 'console_tool_result') {
      var out = typeof f.output === 'string' ? f.output : JSON.stringify(f.output);
      append(f.isError ? 'tool-err' : 'tool-out', f.isError ? 'tool-err' : '✓ result', clip(out, 500));
    } else if (t === 'console_error') append('err', 'error', (f.code || '') + ': ' + (f.message || ''));
    else if (t === 'console_done') { append('sys', 'done', f.reason || 'end_turn'); setInFlight(false); }
    else if (t === 'console_system') {
      if (f.code === 'turn_cancelled' || f.code === 'process_crashed' || f.code === 'idle_terminated') setInFlight(false);
      append('sys', f.code || 'system', f.message || '');
    } else if (t === 'console_replay_begin') append('sys', 'replay', 'history begin (' + f.fromSeq + ' → ' + f.toSeq + ')');
    else if (t === 'console_replay_end') append('sys', 'replay', 'history end — live frames follow');
  }

  var ws = null;
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/agents/' + agentName + '/console/logs');
    ws.onopen = function() { append('sys', 'ws', 'connected'); };
    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        if (f.type === 'error') { append('err', 'ws', (f.code || '') + ': ' + (f.message || '')); return; }
        renderFrame(f);
      } catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) { append('sys', 'ws', 'closed (code ' + ev.code + '); 재연결 5초 후'); setTimeout(connect, 5000); };
    ws.onerror = function() { append('err', 'ws', 'error'); };
  }
  connect();

  var inFlight = false;
  function setInFlight(on) { inFlight = on; if (stopBtn) stopBtn.style.display = on ? 'inline-block' : 'none'; }
  async function cancelTurn() {
    if (!inFlight) return;
    try {
      await fetch('/api/projects/' + projectId + '/agents/' + agentName + '/console/cancel',
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
      var res = await fetch('/api/projects/' + projectId + '/agents/' + agentName + '/console/prompt', {
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
    if ((ev.ctrlKey || ev.metaKey) && ev.key === 'Enter') { ev.preventDefault(); form.requestSubmit(); }
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
