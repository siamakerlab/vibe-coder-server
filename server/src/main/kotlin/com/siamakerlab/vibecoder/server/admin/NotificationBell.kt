package com.siamakerlab.vibecoder.server.admin

/**
 * v1.88.0 — 화면 우상단 알림 벨 UI (모든 admin SSR 페이지 공통, [AdminTemplates.shell] 주입).
 *
 * 구성: 벨 아이콘 + unread 개수 빨강 배지. 클릭 시 미니창(드롭다운)에 최신 알림을 위부터
 * 나열(빌드 완료/오류, 작업 완료/중지, 사용량, 시스템 등). 미니창 우하단에 "모두 삭제".
 *
 * 백엔드: `GET /api/notifications`(목록+unreadTotal), `POST /api/notifications/ack`(단건),
 * `POST /api/notifications/ack-all`(모두). 모두 cookie 세션(AUTH_BEARER cookie fallback)으로
 * 호출 — `/api/` 경로라 SameSite=Lax + Bearer 로 CSRF 방어(별도 토큰 불요, 헤더는 무해하게 첨부).
 *
 * SW/?v 캐시 정책 회피를 위해 CSS/JS 를 shell HTML 에 inline (별도 정적 자산 미생성).
 * embed(iframe inner)·비-chrome 페이지에선 [AdminTemplates] 가 주입하지 않는다.
 */
internal object NotificationBell {

    /** `<head>` 에 들어갈 인라인 스타일. */
    fun headStyle(): String = """
<style>
  .vibe-notif { position: fixed; top: 12px; right: 16px; z-index: 1200; }
  .vibe-notif-btn {
    /* v1.90.1 — 버튼:아이콘 비율 조정(이전 40px 버튼 + 20px 아이콘 → 아이콘이 작아 보임). */
    position: relative; width: 36px; height: 36px; border-radius: 50%;
    border: 1px solid var(--border, #2a2f3a); background: var(--card, #161a22);
    color: var(--fg, #e5e7eb); cursor: pointer; display: flex;
    align-items: center; justify-content: center; box-shadow: 0 2px 8px rgba(0,0,0,.25);
  }
  .vibe-notif-btn svg { width: 22px; height: 22px; }
  .vibe-notif-btn:hover { background: var(--card-hover, #1d2230); }
  .vibe-notif-btn.has-unread { color: #f87171; border-color: #f8717155; }
  .vibe-notif-badge {
    position: absolute; top: -4px; right: -4px; min-width: 18px; height: 18px;
    padding: 0 5px; border-radius: 9px; background: #ef4444; color: #fff;
    font-size: 11px; font-weight: 700; line-height: 18px; text-align: center;
    box-shadow: 0 0 0 2px var(--bg, #0b0d12);
  }
  .vibe-notif-panel {
    position: absolute; top: 48px; right: 0; width: 360px; max-width: calc(100vw - 32px);
    max-height: 70vh; display: flex; flex-direction: column;
    background: var(--card, #161a22); border: 1px solid var(--border, #2a2f3a);
    border-radius: 12px; box-shadow: 0 12px 32px rgba(0,0,0,.45); overflow: hidden;
  }
  /* v1.89.1 — author display:flex 가 hidden 속성(display:none)을 이겨 패널이 상시
     열려 보이던 버그. [hidden] 일 때 명시적으로 숨긴다(더 높은 specificity). */
  .vibe-notif-panel[hidden] { display: none; }
  .vibe-notif-head {
    padding: 12px 14px; font-weight: 600; font-size: 14px;
    border-bottom: 1px solid var(--border, #2a2f3a); color: var(--fg, #e5e7eb);
  }
  .vibe-notif-list { overflow-y: auto; flex: 1; }
  .vibe-notif-item {
    padding: 10px 14px; border-bottom: 1px solid var(--border, #21262f);
    border-left: 3px solid #6b7280; cursor: default;
  }
  .vibe-notif-item[data-link]:not([data-link=""]) { cursor: pointer; }
  .vibe-notif-item:hover { background: var(--card-hover, #1d2230); }
  .vibe-notif-item-title { font-size: 13px; font-weight: 600; color: var(--fg, #e5e7eb); word-break: break-word; }
  .vibe-notif-item-body { font-size: 12px; color: var(--muted, #9ca3af); margin-top: 2px; word-break: break-word; }
  .vibe-notif-item-ts { font-size: 11px; color: var(--muted, #6b7280); margin-top: 4px; }
  .vibe-notif-empty { padding: 28px 14px; text-align: center; color: var(--muted, #6b7280); font-size: 13px; }
  .vibe-notif-foot {
    display: flex; justify-content: flex-end; padding: 8px 12px;
    border-top: 1px solid var(--border, #2a2f3a);
  }
  .vibe-notif-clear {
    background: transparent; border: 1px solid var(--border, #2a2f3a);
    color: var(--muted, #9ca3af); font-size: 12px; padding: 5px 12px;
    border-radius: 8px; cursor: pointer;
  }
  .vibe-notif-clear:hover { color: #f87171; border-color: #f8717155; }
  @media (max-width: 768px) { .vibe-notif { top: 8px; right: 10px; } }
</style>"""

    /** `<body>` 에 들어갈 벨 + 미니창 마크업. */
    fun bodyHtml(lang: String): String {
        val ko = lang == "ko"
        val titleLabel = if (ko) "알림" else "Notifications"
        val clearLabel = if (ko) "모두 삭제" else "Clear all"
        // Lucide "bell".
        val bell = """<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/></svg>"""
        return """
<div id="vibe-notif" class="vibe-notif">
  <button id="vibe-notif-btn" class="vibe-notif-btn" type="button" aria-label="${esc(titleLabel)}" title="${esc(titleLabel)}">
    $bell
    <span id="vibe-notif-badge" class="vibe-notif-badge" hidden>0</span>
  </button>
  <div id="vibe-notif-panel" class="vibe-notif-panel" hidden>
    <div class="vibe-notif-head">${esc(titleLabel)}</div>
    <div id="vibe-notif-list" class="vibe-notif-list"></div>
    <div class="vibe-notif-foot">
      <button id="vibe-notif-clear" class="vibe-notif-clear" type="button">${esc(clearLabel)}</button>
    </div>
  </div>
</div>"""
    }

    /** `<body>` 끝 인라인 스크립트. polling(30s) + 토글 + ack/ack-all. */
    fun bodyScript(lang: String): String {
        val ko = lang == "ko"
        val emptyLabel = if (ko) "새 알림이 없습니다." else "No new notifications."
        // 상대시각 라벨 (JS 안에서 사용).
        val now = if (ko) "방금" else "just now"
        val minA = if (ko) "분 전" else "m ago"
        val hourA = if (ko) "시간 전" else "h ago"
        val dayA = if (ko) "일 전" else "d ago"
        return """
<script>
(function(){
  var root = document.getElementById('vibe-notif');
  var btn = document.getElementById('vibe-notif-btn');
  var panel = document.getElementById('vibe-notif-panel');
  var list = document.getElementById('vibe-notif-list');
  var badge = document.getElementById('vibe-notif-badge');
  var clearBtn = document.getElementById('vibe-notif-clear');
  if (!root || !btn || !panel || !list) return;
  var open = false;

  function esc(s){ return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
  function kindColor(k){
    switch(k){
      case 'build.success': return '#22c55e';
      case 'claude.turn_done': return '#3b82f6';
      case 'build.failed':
      case 'claude.error': return '#ef4444';
      case 'claude.stopped':
      case 'usage.threshold': return '#f59e0b';
      case 'system': return '#6b7280';
      default: return '#6b7280';
    }
  }
  function relTime(ts){
    if(!ts) return '';
    var t = Date.parse(ts); if(isNaN(t)) return '';
    var diff = Math.max(0, Date.now() - t), s = Math.floor(diff/1000);
    if(s < 60) return ${jsStr(now)};
    var m = Math.floor(s/60); if(m < 60) return m + ${jsStr(minA)};
    var h = Math.floor(m/60); if(h < 24) return h + ${jsStr(hourA)};
    return Math.floor(h/24) + ${jsStr(dayA)};
  }
  function csrfHeaders(){ var t = window.__VIBE_CSRF__; return t ? {'X-CSRF-Token': t} : {}; }

  function render(events){
    if(!events || !events.length){ list.innerHTML = '<div class="vibe-notif-empty">' + ${jsStr(emptyLabel)} + '</div>'; return; }
    // 서버는 createdAt ASC 정렬 → 최신을 위로 보이려 역순.
    var rev = events.slice().reverse();
    list.innerHTML = rev.map(function(e){
      var link = e.deepLink ? ('/' + String(e.deepLink).replace(/^\/+/, '')) : '';
      var body = e.body ? ('<div class="vibe-notif-item-body">' + esc(e.body) + '</div>') : '';
      return '<div class="vibe-notif-item" data-id="' + esc(e.id) + '" data-link="' + esc(link) + '"'
        + ' style="border-left-color:' + kindColor(e.kind) + '">'
        + '<div class="vibe-notif-item-title">' + esc(e.title) + '</div>'
        + body
        + '<div class="vibe-notif-item-ts">' + esc(relTime(e.ts)) + '</div>'
        + '</div>';
    }).join('');
  }
  function setBadge(n){
    n = n || 0;
    if(n > 0){ badge.hidden = false; badge.textContent = n > 99 ? '99+' : String(n); btn.classList.add('has-unread'); }
    else { badge.hidden = true; btn.classList.remove('has-unread'); }
  }
  function fetchNotifs(){
    fetch('/api/notifications', { credentials: 'same-origin' })
      .then(function(r){ return r.ok ? r.json() : null; })
      .then(function(d){ if(!d) return; setBadge(d.unreadTotal); if(open) render(d.events); })
      .catch(function(){});
  }
  function ackIds(ids){
    fetch('/api/notifications/ack', {
      method: 'POST', credentials: 'same-origin',
      headers: Object.assign({'Content-Type':'application/json'}, csrfHeaders()),
      body: JSON.stringify({ ids: ids })
    }).catch(function(){});
  }

  btn.addEventListener('click', function(e){
    e.stopPropagation();
    open = !open; panel.hidden = !open;
    if(open) fetchNotifs();
  });
  document.addEventListener('click', function(e){
    if(open && !root.contains(e.target)){ open = false; panel.hidden = true; }
  });
  list.addEventListener('click', function(e){
    var item = e.target.closest ? e.target.closest('.vibe-notif-item') : null;
    if(!item) return;
    var id = item.getAttribute('data-id');
    var link = item.getAttribute('data-link');
    if(id) ackIds([id]);
    if(link){ window.location.href = link; }
  });
  if(clearBtn){
    clearBtn.addEventListener('click', function(){
      fetch('/api/notifications/ack-all', { method: 'POST', credentials: 'same-origin', headers: csrfHeaders() })
        .then(function(){ setBadge(0); render([]); })
        .catch(function(){});
    });
  }

  fetchNotifs();
  setInterval(fetchNotifs, 30000);
})();
</script>"""
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JS 문자열 리터럴 (단일 따옴표). */
    private fun jsStr(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
}
