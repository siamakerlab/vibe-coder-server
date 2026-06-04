package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v1.91.0 — `/memos` — 독립 메모(전역/프로젝트별) 카드형 목록 + 미니창(다이얼로그) 보기·편집.
 *
 * 좌측 사이드바의 "Memos" 메뉴 진입점. 모든 메모(전역 + 모든 프로젝트)를 카드 그리드로
 * 나열하고, 카드 클릭 시 다이얼로그로 본문을 열람·편집한다. "새 메모" 버튼은 scope
 * (전역/특정 프로젝트) 선택 + 본문 입력 다이얼로그를 띄운다.
 *
 * CRUD 는 모두 [com.siamakerlab.vibecoder.server.memo.jsonMemoRoutes] 의 `/api/memos`
 * JSON API 를 fetch (same-origin cookie 인증) 로 호출 — 페이지는 thin shell + JS.
 * 메모 본문은 서버 SSR 로 직접 주입하지 않고 JS 가 `textContent` 로 채워 XSS 안전.
 */
fun Routing.memosRoutes(authDeps: AdminRoutesDeps, projects: ProjectService) {
    get("/memos") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val t = { key: String -> Messages.t(sess.language, key) }
        val projectList = projects.list()
        val projectOptions = projectList.joinToString("") { p ->
            """<option value="${esc(p.id)}">${esc(p.name)}</option>"""
        }
        val projectsJson = "[" + projectList.joinToString(",") { p ->
            """{"id":${jsStr(p.id)},"name":${jsStr(p.name)}}"""
        } + "]"

        val body = """
<style>
  .memos-head { display:flex; align-items:center; gap:12px; margin-bottom:16px; }
  .memos-head h1 { font-size:18px; margin:0; flex:1; }
  .memo-new-btn {
    background: var(--accent, #6aa9ff); color:#0b0d12; border:0; border-radius:6px;
    padding:8px 14px; font:inherit; font-weight:600; cursor:pointer;
  }
  .memo-new-btn:hover { filter:brightness(1.08); }
  .memo-grid {
    display:grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap:12px;
  }
  .memo-card {
    background:#131722; border:1px solid #1f2330; border-radius:8px; padding:12px;
    cursor:pointer; display:flex; flex-direction:column; gap:8px; min-height:96px;
    text-align:left; color:var(--text,#ddd); font:inherit;
  }
  .memo-card:hover { border-color:#2a3145; background:#161b29; }
  .memo-card-top { display:flex; align-items:center; gap:8px; justify-content:space-between; }
  .memo-badge {
    font-size:10px; text-transform:uppercase; letter-spacing:0.04em; padding:2px 8px;
    border-radius:999px; flex-shrink:0; max-width:60%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;
  }
  .memo-badge.global { background:rgba(106,169,255,0.15); color:var(--accent,#6aa9ff); }
  .memo-badge.project { background:rgba(105,219,124,0.13); color:var(--ok,#69db7c); }
  .memo-date { font-size:10px; color:#5a6175; flex-shrink:0; }
  .memo-body {
    font-size:13px; line-height:1.4; color:var(--text,#ddd); white-space:pre-wrap; word-break:break-word;
    display:-webkit-box; -webkit-line-clamp:5; -webkit-box-orient:vertical; overflow:hidden;
  }
  .memo-empty { color:#5a6175; font-size:14px; padding:40px 0; text-align:center; }

  /* 미니창(다이얼로그) */
  .memo-modal {
    position:fixed; inset:0; background:rgba(0,0,0,0.6); z-index:300;
    display:flex; align-items:center; justify-content:center; padding:16px;
  }
  .memo-modal[hidden] { display:none; }
  .memo-modal-box {
    background:#131722; border:1px solid #2a3145; border-radius:10px; padding:20px;
    width:100%; max-width:520px; max-height:85vh; overflow-y:auto;
    box-shadow:0 12px 40px rgba(0,0,0,0.5); display:flex; flex-direction:column; gap:12px;
  }
  .memo-modal-box h2 { margin:0; font-size:15px; }
  .memo-field { display:flex; flex-direction:column; gap:5px; }
  .memo-field label { font-size:11px; color:#5a6175; text-transform:uppercase; letter-spacing:0.04em; }
  .memo-field select, .memo-field textarea {
    background:#0c0f17; border:1px solid #2a3145; border-radius:6px; color:var(--text,#ddd);
    font:inherit; padding:8px 10px; box-sizing:border-box; width:100%;
  }
  .memo-field textarea { min-height:160px; resize:vertical; line-height:1.45; }
  /* v1.91.5 — #memo-modal ID prefix 로 admin.css 의 button:not(...) 전역(specificity 0,5,1)
     을 확실히 이긴다(ID → 1,1,0). 이전엔 .memo-btn(0,1,0)이 져서 닫기 버튼이 큰 padding +
     테두리를 받고, footer 가 넘쳐 버튼이 두 줄로 깨지거나 저장 버튼이 과하게 커 보였다. */
  #memo-modal .memo-modal-foot { display:flex; gap:8px; align-items:center; margin-top:4px; flex-wrap:nowrap; }
  #memo-modal .memo-modal-foot .spacer { flex:1; }
  #memo-modal .memo-btn { border:0; border-radius:6px; padding:8px 14px; font:inherit; cursor:pointer; width:auto; white-space:nowrap; box-sizing:border-box; line-height:1.2; flex:0 0 auto; }
  #memo-modal .memo-btn.primary { background:var(--accent,#6aa9ff); color:#0b0d12; font-weight:600; }
  #memo-modal .memo-btn.primary:hover { filter:brightness(1.08); }
  #memo-modal .memo-btn.ghost { background:transparent; color:var(--text-dim,#888); border:1px solid #1f2330; }
  #memo-modal .memo-btn.ghost:hover { color:var(--text,#ddd); border-color:#2a3145; }
  #memo-modal .memo-btn.danger { background:transparent; color:#ff9e9e; border:1px solid #3a2424; }
  #memo-modal .memo-btn.danger:hover { background:#2c1a1a; }
  .memo-err { color:#ff6b6b; font-size:12px; min-height:14px; }
</style>

<div class="memos-head">
  <h1>${esc(t("memos.heading"))}</h1>
  <button type="button" class="memo-new-btn" id="memo-new">+ ${esc(t("memos.new"))}</button>
</div>

<div class="memo-grid" id="memo-grid"></div>
<div class="memo-empty" id="memo-empty" hidden>${esc(t("memos.empty"))}</div>

<div class="memo-modal" id="memo-modal" hidden>
  <div class="memo-modal-box" role="dialog" aria-modal="true">
    <h2 id="memo-modal-title"></h2>
    <div class="memo-field">
      <label for="memo-scope">${esc(t("memos.label.scope"))}</label>
      <select id="memo-scope">
        <option value="">${esc(t("memos.scope.global"))}</option>
        $projectOptions
      </select>
    </div>
    <div class="memo-field">
      <label for="memo-content">${esc(t("memos.label.content"))}</label>
      <textarea id="memo-content" maxlength="16000" placeholder="${esc(t("memos.placeholder"))}"></textarea>
    </div>
    <div class="memo-err" id="memo-err"></div>
    <div class="memo-modal-foot">
      <button type="button" class="memo-btn danger" id="memo-delete" hidden>${esc(t("memos.delete"))}</button>
      <span class="spacer"></span>
      <button type="button" class="memo-btn ghost" id="memo-cancel">${esc(t("memos.close"))}</button>
      <button type="button" class="memo-btn primary" id="memo-save">${esc(t("memos.save"))}</button>
    </div>
  </div>
</div>

<script>
(function () {
  var PROJECTS = $projectsJson;
  var L = {
    global: ${jsStr(t("memos.scope.global"))},
    titleNew: ${jsStr(t("memos.new"))},
    titleEdit: ${jsStr(t("memos.edit"))},
    confirmDel: ${jsStr(t("memos.deleteConfirm"))},
    errEmpty: ${jsStr(t("memos.error.empty"))},
    errGeneric: ${jsStr(t("memos.error.generic"))}
  };
  var nameOf = {};
  PROJECTS.forEach(function (p) { nameOf[p.id] = p.name; });

  var grid = document.getElementById('memo-grid');
  var emptyEl = document.getElementById('memo-empty');
  var modal = document.getElementById('memo-modal');
  var titleEl = document.getElementById('memo-modal-title');
  var scopeEl = document.getElementById('memo-scope');
  var contentEl = document.getElementById('memo-content');
  var errEl = document.getElementById('memo-err');
  var delBtn = document.getElementById('memo-delete');
  var editingId = null;

  function fmtDate(iso) {
    if (!iso) return '';
    return String(iso).slice(0, 16).replace('T', ' ');
  }

  function api(method, url, body) {
    return fetch(url, {
      method: method,
      credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined
    });
  }

  function render(memos) {
    grid.textContent = '';
    if (!memos.length) { emptyEl.hidden = false; return; }
    emptyEl.hidden = true;
    memos.forEach(function (m) {
      var card = document.createElement('button');
      card.type = 'button';
      card.className = 'memo-card';
      var top = document.createElement('div');
      top.className = 'memo-card-top';
      var badge = document.createElement('span');
      if (m.projectId) {
        badge.className = 'memo-badge project';
        badge.textContent = nameOf[m.projectId] || m.projectId;
      } else {
        badge.className = 'memo-badge global';
        badge.textContent = L.global;
      }
      var date = document.createElement('span');
      date.className = 'memo-date';
      date.textContent = fmtDate(m.updatedAt);
      top.appendChild(badge);
      top.appendChild(date);
      var bodyEl = document.createElement('div');
      bodyEl.className = 'memo-body';
      bodyEl.textContent = m.content;
      card.appendChild(top);
      card.appendChild(bodyEl);
      card.addEventListener('click', function () { openEdit(m); });
      grid.appendChild(card);
    });
  }

  function load() {
    api('GET', '/api/memos').then(function (r) { return r.json(); })
      .then(function (d) { render((d && d.memos) || []); })
      .catch(function () { render([]); });
  }

  function openNew() {
    editingId = null;
    titleEl.textContent = L.titleNew;
    scopeEl.value = '';
    contentEl.value = '';
    errEl.textContent = '';
    delBtn.hidden = true;
    modal.hidden = false;
    setTimeout(function () { contentEl.focus(); }, 0);
  }

  function openEdit(m) {
    editingId = m.id;
    titleEl.textContent = L.titleEdit;
    scopeEl.value = m.projectId || '';
    contentEl.value = m.content;
    errEl.textContent = '';
    delBtn.hidden = false;
    modal.hidden = false;
    setTimeout(function () { contentEl.focus(); }, 0);
  }

  function close() { modal.hidden = true; editingId = null; }

  function save() {
    var content = contentEl.value.trim();
    if (!content) { errEl.textContent = L.errEmpty; return; }
    var pid = scopeEl.value || null;
    var req, url, method;
    if (editingId) {
      method = 'PUT'; url = '/api/memos/' + encodeURIComponent(editingId);
      req = { content: content, projectId: pid, keepScope: false };
    } else {
      method = 'POST'; url = '/api/memos';
      req = { content: content, projectId: pid };
    }
    api(method, url, req).then(function (r) {
      if (!r.ok) { errEl.textContent = L.errGeneric; return; }
      close(); load();
    }).catch(function () { errEl.textContent = L.errGeneric; });
  }

  function del() {
    if (!editingId) return;
    if (!window.confirm(L.confirmDel)) return;
    api('DELETE', '/api/memos/' + encodeURIComponent(editingId)).then(function (r) {
      if (!r.ok) { errEl.textContent = L.errGeneric; return; }
      close(); load();
    }).catch(function () { errEl.textContent = L.errGeneric; });
  }

  document.getElementById('memo-new').addEventListener('click', openNew);
  document.getElementById('memo-save').addEventListener('click', save);
  document.getElementById('memo-cancel').addEventListener('click', close);
  delBtn.addEventListener('click', del);
  modal.addEventListener('click', function (e) { if (e.target === modal) close(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !modal.hidden) close(); });

  load();
})();
</script>
"""

        call.respondText(
            AdminTemplates.shell(
                title = t("memos.title"),
                username = sess.username,
                currentPath = "/memos",
                csrf = sess.csrf,
                lang = sess.language,
                wide = true,  // v1.104.0 — 넓은 폭 활용(중앙정렬 1200px 해제)
                body = body,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/** JS literal string (script context 안전). */
private fun jsStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("<", "\\u003C").replace(">", "\\u003E")
        .replace("\n", "\\n") + "\""
