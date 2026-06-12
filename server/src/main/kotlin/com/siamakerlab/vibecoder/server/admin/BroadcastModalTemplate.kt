package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.ApiPath

/**
 * v1.136.0 — 프롬프트 일괄 보내기 모달 (self-contained: style + markup + script).
 *
 * 오버뷰 rail(`ProjectTabsTemplate` 자동화 카드)과 프로젝트 목록(`WebProjectTemplates`)
 * 양쪽에 같은 모달을 띄우기 위해 외부 정적 JS/CSS 에 의존하지 않는 단일 조각으로 렌더
 * — 정적 자산 캐시버스트(?v + sw CACHE_VERSION) 관리도 불필요.
 *
 * 여는 버튼은 각 페이지가 `class="vb-bcast-open"` 으로 렌더하면 스크립트가 일괄 와이어.
 * 프로젝트 목록은 모달 오픈 시점에 `GET /api/projects` 로 조회 (목록 페이지는 페이지네이션
 * 슬라이스만 갖고 있어 서버 렌더로는 전체를 못 싣는다). 전송은
 * `POST ${ApiPath.CLAUDE_BROADCAST}` — 202 + accepted/rejected.
 *
 * @param preselectId 모달 오픈 시 미리 체크할 프로젝트 (오버뷰에선 현재 프로젝트).
 */
object BroadcastModalTemplate {

    fun render(lang: String, preselectId: String? = null): String {
        val t = { key: String -> Messages.t(lang, key) }
        fun esc(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        return """
<style>
  .vb-bcast-modal { position: fixed; inset: 0; z-index: 2147483000; background: rgba(0,0,0,.55);
                    display: flex; align-items: center; justify-content: center; }
  .vb-bcast-modal[hidden] { display: none; }
  .vb-bcast-box { background: #161616; border: 1px solid #333; border-radius: 10px; padding: 18px;
                  width: min(480px, calc(100vw - 32px)); max-height: calc(100vh - 64px);
                  display: flex; flex-direction: column; gap: 10px; color: var(--text, #ddd); }
  .vb-bcast-box h2 { margin: 0; font-size: 16px; }
  .vb-bcast-sub { margin: 0; font-size: 12px; color: var(--text-dim, #888); }
  .vb-bcast-toolbar { display: flex; align-items: center; gap: 8px; font-size: 13px; }
  .vb-bcast-toolbar .vb-bcast-count { margin-left: auto; color: var(--text-dim, #888); font-size: 12px; }
  .vb-bcast-list { overflow-y: auto; max-height: 220px; min-height: 60px; border: 1px solid #2a2a2a;
                   border-radius: 7px; padding: 6px; display: flex; flex-direction: column; gap: 2px; }
  .vb-bcast-list label { display: flex; align-items: center; gap: 8px; padding: 5px 7px; border-radius: 5px;
                         font-size: 13px; cursor: pointer; }
  .vb-bcast-list label:hover { background: #1f1f1f; }
  .vb-bcast-list .vb-pid { color: var(--text-dim, #777); font-size: 11px; margin-left: auto; }
  .vb-bcast-list .vb-busy { color: #e2b714; font-size: 11px; }
  .vb-bcast-empty { color: var(--text-dim, #777); font-size: 12px; padding: 12px; text-align: center; }
  .vb-bcast-box textarea { background: #1a1a1a; color: var(--text, #ddd); border: 1px solid #333;
                           border-radius: 7px; padding: 8px; font: inherit; font-size: 13px; resize: vertical; }
  .vb-bcast-err { font-size: 12px; min-height: 14px; color: #e25b4b; white-space: pre-wrap; }
  .vb-bcast-err.ok { color: #6abf69; }
  .vb-bcast-foot { display: flex; gap: 8px; }
  .vb-bcast-foot .sp { flex: 1; }
  .vb-bcast-btn { padding: 7px 14px; border-radius: 7px; border: 1px solid #3a3a3a; background: #222;
                  color: var(--text, #ddd); cursor: pointer; font-size: 13px; }
  .vb-bcast-btn.primary { background: #2e5c34; border-color: #3f7a47; }
  .vb-bcast-btn[disabled] { opacity: .5; cursor: default; }
</style>
<div class="vb-bcast-modal" id="vb-bcast-modal" hidden
     data-preselect="${esc(preselectId.orEmpty())}"
     data-busy="${esc(t("projects.status.responding"))}"
     data-empty="${esc(t("console.broadcast.empty"))}"
     data-select-required="${esc(t("console.broadcast.selectRequired"))}"
     data-sent="${esc(t("console.broadcast.sent"))}"
     data-failed="${esc(t("console.broadcast.failed"))}">
  <div class="vb-bcast-box" role="dialog" aria-modal="true" aria-labelledby="vb-bcast-title">
    <h2 id="vb-bcast-title">📢 ${esc(t("console.broadcast.title"))}</h2>
    <p class="vb-bcast-sub">${esc(t("console.broadcast.hint"))}</p>
    <div class="vb-bcast-toolbar">
      <label><input type="checkbox" id="vb-bcast-all"> ${esc(t("console.broadcast.selectAll"))}</label>
      <span class="vb-bcast-count" id="vb-bcast-count"></span>
    </div>
    <div class="vb-bcast-list" id="vb-bcast-list"></div>
    <textarea id="vb-bcast-prompt" rows="4" placeholder="${esc(t("console.broadcast.placeholder"))}"></textarea>
    <div class="vb-bcast-err" id="vb-bcast-err"></div>
    <div class="vb-bcast-foot">
      <button type="button" class="vb-bcast-btn" id="vb-bcast-close">${esc(t("console.broadcast.close"))}</button>
      <span class="sp"></span>
      <button type="button" class="vb-bcast-btn primary" id="vb-bcast-send">${esc(t("console.broadcast.send"))}</button>
    </div>
  </div>
</div>
<script>
(function () {
  var modal = document.getElementById('vb-bcast-modal');
  if (!modal) return;
  var listEl = document.getElementById('vb-bcast-list');
  var allEl = document.getElementById('vb-bcast-all');
  var countEl = document.getElementById('vb-bcast-count');
  var promptEl = document.getElementById('vb-bcast-prompt');
  var errEl = document.getElementById('vb-bcast-err');
  var sendBtn = document.getElementById('vb-bcast-send');
  var closeBtn = document.getElementById('vb-bcast-close');

  function boxes() { return Array.prototype.slice.call(listEl.querySelectorAll('input[type=checkbox]')); }
  function syncCount() {
    var n = boxes().filter(function (b) { return b.checked; }).length;
    countEl.textContent = n ? (n + ' ✓') : '';
  }
  function renderList(items) {
    listEl.innerHTML = '';
    if (!items.length) {
      var d = document.createElement('div');
      d.className = 'vb-bcast-empty'; d.textContent = modal.getAttribute('data-empty');
      listEl.appendChild(d); return;
    }
    var pre = modal.getAttribute('data-preselect');
    items.forEach(function (p) {
      var label = document.createElement('label');
      var cb = document.createElement('input');
      cb.type = 'checkbox'; cb.value = p.id; cb.checked = (p.id === pre);
      cb.addEventListener('change', syncCount);
      var name = document.createElement('span'); name.textContent = p.name || p.id;
      label.appendChild(cb); label.appendChild(name);
      if (p.busy) {
        var busy = document.createElement('span');
        busy.className = 'vb-busy'; busy.textContent = '● ' + modal.getAttribute('data-busy');
        label.appendChild(busy);
      }
      var pid = document.createElement('span'); pid.className = 'vb-pid'; pid.textContent = p.id;
      label.appendChild(pid);
      listEl.appendChild(label);
    });
    syncCount();
  }
  function loadProjects() {
    fetch('${ApiPath.PROJECTS}', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(renderList)
      .catch(function () { renderList([]); });
  }

  allEl.addEventListener('change', function () {
    boxes().forEach(function (b) { b.checked = allEl.checked; });
    syncCount();
  });

  function openModal() {
    modal.hidden = false; errEl.textContent = ''; errEl.classList.remove('ok');
    allEl.checked = false; loadProjects(); promptEl.focus();
  }
  function closeModal() { modal.hidden = true; }
  Array.prototype.forEach.call(document.querySelectorAll('.vb-bcast-open'), function (btn) {
    btn.addEventListener('click', openModal);
  });
  closeBtn.addEventListener('click', closeModal);
  modal.addEventListener('click', function (ev) { if (ev.target === modal) closeModal(); });
  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape' && !modal.hidden) closeModal(); });

  sendBtn.addEventListener('click', function () {
    var ids = boxes().filter(function (b) { return b.checked; }).map(function (b) { return b.value; });
    var prompt = (promptEl.value || '').trim();
    errEl.classList.remove('ok');
    if (!ids.length) { errEl.textContent = modal.getAttribute('data-select-required'); return; }
    if (!prompt) { promptEl.focus(); return; }
    sendBtn.disabled = true; errEl.textContent = '…';
    fetch('${ApiPath.CLAUDE_BROADCAST}', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: prompt, projectIds: ids }),
    }).then(function (r) {
      if (!r.ok) return r.json().then(function (e) { throw new Error((e && e.message) || r.status); });
      return r.json();
    }).then(function (d) {
      var ok = (d.accepted || []).length;
      var msg = (modal.getAttribute('data-sent') || '{n}').replace('{n}', ok);
      if ((d.rejected || []).length) {
        msg += '\n' + d.rejected.map(function (x) { return x.projectId + ': ' + x.reason; }).join('\n');
        errEl.classList.remove('ok');
      } else {
        errEl.classList.add('ok');
        promptEl.value = '';
        setTimeout(closeModal, 1200);
      }
      errEl.textContent = msg;
    }).catch(function (e) {
      errEl.textContent = modal.getAttribute('data-failed') + ': ' + e.message;
    }).then(function () { sendBtn.disabled = false; });
  });
})();
</script>"""
    }
}
