// v1.11.0 — Project Tabs page client logic.
// v1.47.0 — Console-first loading + background preloading.
//
// Each tab is an <iframe data-tab="<id>"> that loads the existing SSR page
// (/projects/{id}/console, /builds, /files, ...).
//
//   - The console iframe has a real `src` (eager, fetchpriority=high) so it
//     starts fetching at HTML parse time — visible as fast as possible, with
//     no competition from the other 16 iframes.
//   - Every other iframe ships with `data-src` (no `src`), so the browser
//     does NOT fetch it on entry. After the console iframe finishes loading,
//     we preload the rest **sequentially in the background** (one at a time)
//     so they're warm when clicked but never delay the console.
//   - Clicking a tab that hasn't been preloaded yet loads it immediately
//     (on-demand), so there is never a wait beyond that one frame.
//
// Other responsibilities (unchanged):
//   - Switch which iframe is visible (CSS display toggle) via tab buttons +
//     URL hash (#console, #builds, ...).
//   - After each iframe loads, hide its inner admin shell <nav> so only the
//     parent tab bar shows.
//   - Persist the last active tab per project in localStorage.

(function () {
  'use strict';

  function init() {
    const root = document.getElementById('project-tabs-root');
    if (!root) return;
    const projectId = root.dataset.projectId || '';
    const storageKey = 'vibe.projectTabs.' + projectId;
    const panes = Array.from(root.querySelectorAll('.tab-pane'));
    const validTabs = panes.map(p => p.dataset.tab);
    const buttons = Array.from(root.querySelectorAll('[data-tab-btn]'));

    function syncVisualViewport() {
      var vv = window.visualViewport;
      var h = vv && vv.height ? vv.height : window.innerHeight;
      if (h > 0) document.documentElement.style.setProperty('--app-viewport-height', Math.round(h) + 'px');
    }
    syncVisualViewport();
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', syncVisualViewport);
      window.visualViewport.addEventListener('scroll', syncVisualViewport);
    } else {
      window.addEventListener('resize', syncVisualViewport);
    }

    function frameOf(tab) {
      const pane = panes.find(p => p.dataset.tab === tab);
      return pane ? pane.querySelector('iframe.tab-frame') : null;
    }

    // v1.47.0 — promote data-src → src (lazy iframe → start loading). Idempotent.
    function loadFrame(iframe) {
      if (!iframe) return;
      const pending = iframe.getAttribute('data-src');
      if (pending && !iframe.getAttribute('src')) {
        iframe.setAttribute('src', pending);
      }
    }

    // ── v1.50.0 — overview rail. Parent CSS reserves the rail column. ──
    // Inside each same-origin iframe: keep content left-aligned and wide. Rail reservation is now
    // owned by the parent tab pane CSS, so iframe documents don't receive fragile padding patches.
    function applyRailToFrame(iframe) {
      try {
        var doc = iframe && iframe.contentDocument;
        if (!doc) return;
        doc.querySelectorAll('.content').forEach(function (c) {
          c.style.justifySelf = 'start';
          c.style.maxWidth = 'none';
          c.style.paddingRight = '';
        });
      } catch (e) { /* same-origin SSR → unreachable */ }
    }
    function applyRailAll() {
      root.querySelectorAll('iframe.tab-frame').forEach(applyRailToFrame);
    }

    function resolveInitialTab() {
      const hash = (window.location.hash || '').replace('#', '');
      if (validTabs.indexOf(hash) >= 0) return hash;
      try {
        const saved = localStorage.getItem(storageKey);
        if (saved && validTabs.indexOf(saved) >= 0) return saved;
      } catch (e) { /* localStorage unavailable */ }
      return validTabs[0] || 'console';
    }

    function activate(tab) {
      if (validTabs.indexOf(tab) < 0) tab = validTabs[0];
      root.querySelectorAll('.tab-pane').forEach(p => {
        var active = p.dataset.tab === tab;
        p.classList.toggle('active', active);
        p.hidden = !active;
        p.setAttribute('aria-hidden', active ? 'false' : 'true');
      });
      buttons.forEach(b => {
        var active = b.dataset.tabBtn === tab;
        b.classList.toggle('active', active);
        b.setAttribute('aria-selected', active ? 'true' : 'false');
        b.setAttribute('tabindex', active ? '0' : '-1');
      });
      // v1.47.0 — on-demand load: a tab clicked before the background preload
      // reaches it loads right away (no wait beyond this single frame).
      loadFrame(frameOf(tab));
      // v1.99.1 — 다시 보이게 된 frame(특히 콘솔)에 visible 신호 → 내부가 최하단 재고정.
      //  탭 전환으로 display:none→block 된 콘솔이 어긋난 스크롤로 보이던 문제 회수.
      try {
        var vf = frameOf(tab);
        if (vf && vf.contentWindow) {
          vf.contentWindow.postMessage({ type: 'pt:tab-visible', tab: tab }, location.origin);
        }
      } catch (e) {}
      try { localStorage.setItem(storageKey, tab); } catch (e) {}
      if (window.location.hash !== '#' + tab) {
        history.replaceState(null, '', '#' + tab);
      }
      // v1.29.0 — "더보기" 드롭다운: 항목 선택 후 닫고, overflow 탭이 활성일 때
      // summary 에 현재 탭명을 표시(상단 탭바엔 primary 만 있어 위치 파악용).
      var moreDd = root.querySelector('details.more-dropdown');
      if (moreDd) {
        moreDd.removeAttribute('open');
        var summary = moreDd.querySelector('summary');
        if (summary) {
          var baseLabel = summary.getAttribute('data-more-label') || '';
          var activeOverflow = moreDd.querySelector('[data-tab-btn="' + tab + '"]');
          summary.textContent = (activeOverflow
            ? baseLabel + ': ' + activeOverflow.textContent.trim()
            : baseLabel) + ' ▾';
        }
      }
    }

    buttons.forEach(b => {
      b.addEventListener('click', function (e) {
        e.preventDefault();
        activate(b.dataset.tabBtn);
      });
      b.addEventListener('keydown', function (e) {
        var keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
        if (keys.indexOf(e.key) < 0) return;
        e.preventDefault();
        var current = buttons.indexOf(b);
        var next = current;
        if (e.key === 'Home') next = 0;
        else if (e.key === 'End') next = buttons.length - 1;
        else if (e.key === 'ArrowLeft') next = current <= 0 ? buttons.length - 1 : current - 1;
        else if (e.key === 'ArrowRight') next = current >= buttons.length - 1 ? 0 : current + 1;
        var target = buttons[next];
        if (!target) return;
        target.focus();
        activate(target.dataset.tabBtn);
      });
    });
    window.addEventListener('hashchange', function () {
      const hash = (window.location.hash || '').replace('#', '');
      if (validTabs.indexOf(hash) >= 0) activate(hash);
    });

    // ── v1.49.0 — project switcher combobox (header project name) ──────
    // Filter-as-you-type, Esc closes, Enter jumps to first match, click
    // outside closes. The items are plain <a href> so selecting one navigates
    // the whole tabs page to that project.
    (function () {
      var switcher = root.querySelector('details.pt-switcher');
      if (!switcher) return;
      var filter = switcher.querySelector('.pt-switch-filter');
      var items = Array.from(switcher.querySelectorAll('.pt-switch-list .pt-switch-item'));
      switcher.addEventListener('toggle', function () {
        if (!switcher.open || !filter) return;
        filter.value = '';
        items.forEach(function (it) { it.style.display = ''; });
        // v1.89.0 — 터치(모바일)에선 자동 포커스가 소프트 키보드를 띄워 불편하므로,
        // fine pointer(데스크톱 마우스)에서만 검색창에 포커스를 준다.
        var coarse = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
        if (!coarse) setTimeout(function () { filter.focus(); }, 0);
      });
      if (filter) {
        filter.addEventListener('input', function () {
          var q = filter.value.trim().toLowerCase();
          items.forEach(function (it) {
            var hay = it.getAttribute('data-name') || '';
            it.style.display = (!q || hay.indexOf(q) >= 0) ? '' : 'none';
          });
        });
        filter.addEventListener('keydown', function (e) {
          if (e.key === 'Escape') { switcher.open = false; }
          else if (e.key === 'Enter') {
            var first = items.find(function (it) { return it.style.display !== 'none'; });
            if (first) { e.preventDefault(); window.location.href = first.getAttribute('href'); }
          }
        });
      }
    })();

    // ── v1.89.0 — 바깥 클릭 시 프로젝트 화면의 열린 팝업을 모두 닫기 ──────────
    // 포커스 해제(팝업 바깥 영역 클릭)되면 콤보박스(pt-switcher)·설정(pt-settings)·
    // 더보기(more-dropdown)를 자동으로 닫는다. <details> 는 기본적으로 바깥 클릭으로
    // 닫히지 않아 직접 처리. switcher 가 없는 페이지에서도 동작하도록 IIFE 밖에 둔다.
    function closeAllPopups() {
      root.querySelectorAll('details.pt-switcher[open], details.pt-settings[open], details.more-dropdown[open]')
        .forEach(function (d) { d.removeAttribute('open'); });
    }
    document.addEventListener('click', function (e) {
      root.querySelectorAll('details.pt-switcher[open], details.pt-settings[open], details.more-dropdown[open]')
        .forEach(function (d) { if (!d.contains(e.target)) d.removeAttribute('open'); });
    });
    // v1.90.7 — 콘솔 등 탭 내용은 iframe(별도 document)이라 그 영역 클릭은 부모의 document
    // click 이 안 울려 콤보박스가 안 닫혔다. 부모 window 포커스 상실(iframe/다른 영역 이동)
    // 시 모든 팝업을 닫는다. Esc 로도 닫는다.
    window.addEventListener('blur', closeAllPopups);
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeAllPopups(); });

    // v1.72.0 — inner shell 의 nav/탭바 제거는 이제 **서버가 embed 요청에 미렌더**한다
    // (AdminTemplates.shell(embed=true); ?_embed=1 + 표준 Sec-Fetch-Dest:iframe 로 자동 판정).
    // 따라서 과거의 "load 후 JS 로 nav 숨김(cleanup) + visibility:hidden 으로 flash 방지"는
    // 모두 제거했다 — 숨길 nav 자체가 HTML 에 없고, visibility:hidden 잔재는 cleanup 미실행
    // race 시 iframe 이 영구 invisible 이 되는 새 결함을 만들었다. v1.157.0 부터 rail 공간
    // 예약도 parent pane CSS 가 담당하므로, 여기서는 iframe content alignment 만 보정한다.
    root.querySelectorAll('iframe.tab-frame').forEach(iframe => {
      iframe.addEventListener('load', function () { applyRailToFrame(iframe); });
      // load race: 이미 complete 면(가벼운 sub-page) 한 번 즉시 적용.
      try {
        const d = iframe.contentDocument;
        if (iframe.getAttribute('src') && d && d.readyState === 'complete') applyRailToFrame(iframe);
      } catch (e) { /* same-origin SSR 이라 도달하지 않음 */ }
    });

    // ── v1.47.0 — background preloader ──────────────────────────────────
    // After the console (priority) frame finishes, load the remaining
    // data-src frames one at a time so they're warm without ever competing
    // with the console for connections / CPU.
    var preloadStarted = false;
    function startPreload() {
      if (preloadStarted) return;
      preloadStarted = true;
      var pending = Array.from(root.querySelectorAll('iframe.tab-frame[data-src]'));
      var i = 0;
      (function next() {
        // skip any that got loaded meanwhile (e.g. user clicked the tab).
        while (i < pending.length && pending[i].getAttribute('src')) i++;
        if (i >= pending.length) return;
        var f = pending[i++];
        var go = function () { setTimeout(next, 200); };
        f.addEventListener('load', go, { once: true });
        f.addEventListener('error', go, { once: true });
        loadFrame(f);
      })();
    }
    function whenIdle(fn) {
      if (window.requestIdleCallback) window.requestIdleCallback(fn, { timeout: 1500 });
      else setTimeout(fn, 300);
    }

    // ── v1.50.0 — rail toggle (hide/show, global pref) + resize + history click ──
    (function () {
      var toggle = document.getElementById('pt-rail-toggle');
      var RAIL_KEY = 'vibe.projectRail'; // 전역(프로젝트 공통) 선호.
      try { if (localStorage.getItem(RAIL_KEY) === 'hidden') root.setAttribute('data-rail', 'hidden'); } catch (e) {}
      function syncToggle() {
        if (!toggle) return;
        var hidden = root.getAttribute('data-rail') === 'hidden';
        toggle.textContent = hidden ? '⟨' : '⟩';
        toggle.title = hidden ? (toggle.getAttribute('data-show') || '') : (toggle.getAttribute('data-hide') || '');
        toggle.setAttribute('aria-expanded', hidden ? 'false' : 'true');
        toggle.setAttribute('aria-label', toggle.title || toggle.textContent);
      }
      if (toggle) {
        toggle.addEventListener('click', function () {
          var hidden = root.getAttribute('data-rail') === 'hidden';
          root.setAttribute('data-rail', hidden ? 'shown' : 'hidden');
          try { localStorage.setItem(RAIL_KEY, hidden ? 'shown' : 'hidden'); } catch (e) {}
          syncToggle();
          applyRailAll();
        });
        syncToggle();
      }
      var rzTimer = null;
      window.addEventListener('resize', function () {
        clearTimeout(rzTimer);
        rzTimer = setTimeout(applyRailAll, 150);
      });
      // 프롬프트 히스토리 항목 클릭 → 콘솔 탭으로 전환 + 프롬프트 입력창에 채움.
      root.addEventListener('click', function (e) {
        var item = e.target && e.target.closest ? e.target.closest('.pt-hist-item') : null;
        if (!item) return;
        var txt = item.getAttribute('data-prompt') || '';
        activate('console');
        var tries = 0;
        (function fill() {
          var input = null;
          try {
            var cf = frameOf('console');
            input = cf && cf.contentDocument && cf.contentDocument.getElementById('prompt-input');
          } catch (e2) {}
          if (input) {
            input.value = txt;
            input.focus();
            try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (e3) {}
          } else if (tries++ < 50) {
            setTimeout(fill, 100);
          }
        })();
      });

      // v1.59.2 — 콘솔 iframe 에서 프롬프트를 보내면 우측 히스토리에 즉시 prepend.
      // (서버 렌더 snapshot 만으론 reload 전까지 stale.) 콘솔이 postMessage 로 통지.
      // v1.134.0 — 7개 제한/opacity 램프 제거: 전체 프롬프트 스크롤 목록(서버 렌더와 동일).
      // 하단 페이드(마스크 그라데이션)는 스크롤 위치에 따라 .at-end 클래스로 토글 —
      // 더 볼 내용이 있을 때만 마지막 ~2개가 흐려진다.
      function updateHistFade() {
        var list = root.querySelector('.pt-hist-list');
        if (!list) return;
        var atEnd = list.scrollHeight - list.scrollTop - list.clientHeight < 8;
        list.classList.toggle('at-end', atEnd);
      }
      (function initHistFade() {
        var list = root.querySelector('.pt-hist-list');
        if (!list) return;
        list.addEventListener('scroll', updateHistFade, { passive: true });
        updateHistFade();
      })();
      function prependHistory(text) {
        text = (text || '').trim();
        if (!text) return;
        var list = root.querySelector('.pt-hist-list');
        if (!list) return;
        var empty = list.querySelector('.pt-hist-empty');
        if (empty) empty.parentNode.removeChild(empty);
        var first = list.querySelector('.pt-hist-item');
        if (first && (first.getAttribute('data-prompt') || '') === text) return;  // 직전과 동일하면 skip
        var hint = list.getAttribute('data-hist-hint') || '';
        // 표시/title 은 미리보기(2줄 축약 + hover 안내), 클릭 채움용 data-prompt 만 원문.
        var preview = text.length > 300 ? text.slice(0, 300) + '…' : text;
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'pt-hist-item';
        btn.setAttribute('data-prompt', text);
        btn.setAttribute('title', hint ? preview + '\n\n' + hint : preview);
        btn.textContent = preview;
        list.insertBefore(btn, list.firstChild);
        // 새 항목이 최신(맨 위)으로 보이게 목록 맨 위로 + 페이드 갱신.
        list.scrollTop = 0;
        updateHistFade();
      }
      // v1.106.2 — 우측 rail 컨텍스트 점유율 미터(콘솔 iframe 이 매 turn postMessage).
      function ptCtxFmt(n) {
        n = Number(n) || 0;
        if (n >= 1000000) return (n / 1000000).toFixed(n >= 10000000 ? 0 : 1) + 'M';
        if (n >= 1000) return Math.round(n / 1000) + 'K';
        return String(n);
      }
      function updateRailContextMeter(d) {
        var el = document.getElementById('pt-ctx-meter'); if (!el) return;
        var empty = document.getElementById('pt-ctx-empty');
        var input = Number(d.input) || 0, cacheRead = Number(d.cacheRead) || 0,
            cacheCreation = Number(d.cacheCreation) || 0, limit = Number(d.limit) || 0;
        var providerEl = document.getElementById('pt-ctx-provider');
        if (providerEl) providerEl.textContent = d.provider ? '· ' + String(d.provider) : '';
        var used = input + cacheRead + cacheCreation;
        function setMeterA11y(now, max, text) {
          max = Math.max(1, Math.round(Number(max) || 1));
          now = Math.max(0, Math.min(max, Math.round(Number(now) || 0)));
          el.setAttribute('aria-valuemin', '0');
          el.setAttribute('aria-valuemax', String(max));
          el.setAttribute('aria-valuenow', String(now));
          el.setAttribute('aria-valuetext', text || '');
        }
        if (limit <= 0 || used <= 0) {
          el.hidden = true;
          if (empty) empty.hidden = false;
          setMeterA11y(0, 1, empty ? empty.textContent : '');
          return;
        }
        el.hidden = false; if (empty) empty.hidden = true;
        var pct = Math.min(100, used / limit * 100);
        function w(x) { return (Math.max(0, x) / limit * 100) + '%'; }
        var r = el.querySelector('.ctx-seg-read'); if (r) r.style.width = w(cacheRead);
        var c = el.querySelector('.ctx-seg-create'); if (c) c.style.width = w(cacheCreation);
        var i = el.querySelector('.ctx-seg-input'); if (i) i.style.width = w(input);
        function setT(id, v) { var n = document.getElementById(id); if (n) n.textContent = v; }
        setT('pt-ctx-used', ptCtxFmt(used));
        setT('pt-ctx-limit', ptCtxFmt(limit));
        setT('pt-ctx-pct', Math.round(pct) + '%');
        setT('pt-ctx-free', ptCtxFmt(Math.max(0, limit - used)));
        el.classList.toggle('warn', pct >= 70);
        function msg(name, fallback) { return el.getAttribute('data-' + name) || fallback; }
        var title = msg('tooltip-prefix', 'Conversation context') + ' ' + ptCtxFmt(used) + ' / ' + ptCtxFmt(limit) + ' (' + Math.round(pct) +
          '%). ' + msg('tooltip-reuse', 'Cache reused') + ' ' + ptCtxFmt(cacheRead) +
          ' · ' + msg('tooltip-create', 'New cache') + ' ' + ptCtxFmt(cacheCreation) +
          ' · ' + msg('tooltip-input', 'Input') + ' ' + ptCtxFmt(input) +
          '. ' + msg('tooltip-suffix', 'Higher usage increases each turn cost. Start a new session to reset.');
        el.title = title;
        setMeterA11y(used, limit, title);
      }
      // v1.106.3 — /compact 버튼: 콘솔 iframe 에 vibe:send-prompt 로 '/compact' 전달
      // (콘솔의 정상 전송 경로 사용 → busy 면 큐, 에코/미터 갱신 일관). 우측 rail 에 위치.
      var compactBtn = document.getElementById('pt-compact-btn');
      if (compactBtn) {
        compactBtn.addEventListener('click', function () {
          var cf = frameOf('console');
          if (!cf || !cf.contentWindow) return;
          var confirmText = compactBtn.getAttribute('data-confirm') || 'Send /compact to the console?';
          if (!window.confirm(confirmText)) return;
          cf.contentWindow.postMessage({ type: 'vibe:send-prompt', text: '/compact' }, location.origin);
          compactBtn.classList.add('busy');
          compactBtn.disabled = true;
          compactBtn.setAttribute('aria-busy', 'true');
          setTimeout(function () {
            compactBtn.classList.remove('busy');
            compactBtn.disabled = false;
            compactBtn.setAttribute('aria-busy', 'false');
          }, 4000);
        });
      }
      // v1.108.0 — '자동(auto-compact)' 체크박스: 콘솔 iframe 으로 전달해 서버에 영속(fetch).
      var autoCompactCb = document.getElementById('pt-autocompact');
      if (autoCompactCb) {
        autoCompactCb.addEventListener('change', function () {
          var cf = frameOf('console');
          if (cf && cf.contentWindow) {
            cf.contentWindow.postMessage({ type: 'vibe:set-autocompact', enabled: autoCompactCb.checked }, location.origin);
          }
        });
      }
      // v1.110.0 — rail 카드 접기/펼치기(헤더 클릭). 카드별 상태 localStorage 영속.
      (function initRailCollapse() {
        var rail = document.querySelector('#project-tabs-root .pt-rail');
        if (!rail) return;
        var cards = rail.querySelectorAll('.pt-rail-card');
        Array.prototype.forEach.call(cards, function (card) {
          var head = card.querySelector('.pt-rail-h');
          if (!head) return;
          var key = card.getAttribute('data-card');
          var lsKey = key ? 'vibe.rail.collapsed.' + key : null;
          if (lsKey) {
            try { if (localStorage.getItem(lsKey) === '1') card.classList.add('pt-collapsed'); } catch (e) {}
          }
          head.setAttribute('role', 'button');
          head.setAttribute('tabindex', '0');
          function syncExpanded() {
            head.setAttribute('aria-expanded', card.classList.contains('pt-collapsed') ? 'false' : 'true');
          }
          function toggle() {
            var collapsed = card.classList.toggle('pt-collapsed');
            if (lsKey) { try { localStorage.setItem(lsKey, collapsed ? '1' : '0'); } catch (e) {} }
            syncExpanded();
          }
          syncExpanded();
          head.addEventListener('click', function (ev) {
            // 헤더 내 액션(버튼/링크/입력/체크박스 라벨)은 토글 트리거 제외.
            if (ev.target.closest('button, a, input, select, label')) return;
            toggle();
          });
          head.addEventListener('keydown', function (ev) {
            if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); toggle(); }
          });
        });
      })();
      // v1.109.0 — 프롬프트 자동화(콘솔 인라인 패널에서 우측 오버뷰 rail 로 이동). 자체 입력으로
      // /claude/automation/* REST 직접 실행 + 활성 시 status 3s 폴링. 진행 프레임은 콘솔 iframe 의
      // vibe:automation postMessage 로 즉시 갱신(아래 message 핸들러 → __ptAutoRender).
      (function initAutomation() {
        var card = document.querySelector('.pt-auto-card');
        if (!card) return;
        var badge = document.getElementById('pt-auto-badge');
        var runningEl = document.getElementById('pt-auto-running');
        var idleEl = document.getElementById('pt-auto-idle');
        var progEl = document.getElementById('pt-auto-progress');
        var startBtn = document.getElementById('pt-auto-start');
        var presetSel = document.getElementById('pt-auto-preset');
        var presetStartBtn = document.getElementById('pt-auto-preset-start');
        var promptsEl = document.getElementById('pt-auto-prompts');
        var countEl = document.getElementById('pt-auto-count');
        var base = '/api/projects/' + encodeURIComponent(projectId) + '/claude/automation/';
        var pollTimer = null;

        function api(method, url, body) {
          return fetch(url, {
            method: method, credentials: 'same-origin',
            headers: body ? { 'Content-Type': 'application/json' } : undefined,
            body: body ? JSON.stringify(body) : undefined,
          });
        }
        function poll() {
          api('GET', base + 'status').then(function (r) { return r.ok ? r.json() : null; })
            .then(function (st) { if (st) render(st); }).catch(function () {});
        }
        function render(st) {
          if (st && st.active) {
            // v1.131.0 — 인디케이터(badge)는 "사용중" 상태 표시(● 진행 중). 진행률은 running 영역.
            runningEl.hidden = false;
            idleEl.style.display = 'none';
            progEl.textContent = (st.name ? st.name + ' · ' : '') + (st.sent || 0) + '/' + (st.total || 0);
            badge.textContent = '● ' + (badge.getAttribute('data-running') || '');
            badge.classList.add('on');
            if (!pollTimer) pollTimer = setInterval(poll, 3000);   // 폴백(즉시 갱신은 postMessage)
          } else {
            runningEl.hidden = true;
            idleEl.style.display = '';
            badge.textContent = '';
            badge.classList.remove('on');
            if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
          }
        }
        window.__ptAutoRender = render;   // message 핸들러가 진행 프레임 push 시 사용.

        // 프리셋 로드(전역).
        api('GET', '/api/prompt-automations').then(function (r) { return r.ok ? r.json() : null; }).then(function (d) {
          if (!d || !d.presets || !d.presets.length) return;
          presetSel.innerHTML = '';
          d.presets.forEach(function (p) {
            var o = document.createElement('option'); o.value = p.id; o.textContent = p.name + ' (' + p.mode + ')';
            presetSel.appendChild(o);
          });
          presetSel.hidden = false; presetStartBtn.hidden = false;
        }).catch(function () {});

        poll();   // 초기 상태 1회.

        function doStart(body) {
          api('POST', base + 'start', body).then(function (r) {
            if (!r.ok) { return r.text().then(function (m) { window.alert('automation ' + r.status + ': ' + m); }); }
            return r.json().then(render);
          }).catch(function (e) { window.alert('automation: ' + e); });
        }
        if (startBtn) startBtn.addEventListener('click', function () {
          var mode = (card.querySelector('input[name=pt-auto-mode]:checked') || {}).value || 'repeat';
          var raw = (promptsEl.value || '').trim();
          var prompts = raw.split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
          if (!prompts.length) { promptsEl.focus(); return; }
          var count = parseInt(countEl.value, 10) || 1;
          doStart({ mode: mode, prompts: prompts, repeatCount: count, loops: 1, stopOnError: false });
        });
        if (presetStartBtn) presetStartBtn.addEventListener('click', function () {
          if (!presetSel.value) return;
          doStart({ presetId: presetSel.value });
        });
      })();
      // v1.131.0 — 프롬프트 예약 보내기(one-shot). 자동화 카드 상단 "⏰ 예약 보내기" → 부모 레이어 모달.
      // 시각(N분/시간 뒤·정확한 시각) / 세션·주간 한도 해제 트리거를 JSON API 로 등록/취소.
      (function initSchedule() {
        var openBtn = document.getElementById('pt-sched-open');
        var modal = document.getElementById('pt-sched-modal');
        if (!openBtn || !modal) return;
        var promptEl = document.getElementById('pt-sched-prompt');
        var timeBox = document.getElementById('pt-sched-time');
        var inBox = document.getElementById('pt-sched-in');
        var atEl = document.getElementById('pt-sched-at');
        var amtEl = document.getElementById('pt-sched-amt');
        var unitEl = document.getElementById('pt-sched-unit');
        var resetHint = document.getElementById('pt-sched-reset-hint');
        var errEl = document.getElementById('pt-sched-err');
        var listEl = document.getElementById('pt-sched-list');
        var addBtn = document.getElementById('pt-sched-add');
        var closeBtn = document.getElementById('pt-sched-close');
        var base = '/api/projects/' + encodeURIComponent(projectId) + '/claude/schedule';

        function api(method, url, body) {
          return fetch(url, {
            method: method, credentials: 'same-origin',
            headers: body ? { 'Content-Type': 'application/json' } : undefined,
            body: body ? JSON.stringify(body) : undefined,
          });
        }
        function trig() { return (modal.querySelector('input[name=pt-sched-trig]:checked') || {}).value || 'time'; }
        function whenMode() { return (modal.querySelector('input[name=pt-sched-when]:checked') || {}).value || 'in'; }
        function syncTrig() { var isTime = trig() === 'time'; timeBox.style.display = isTime ? 'flex' : 'none'; resetHint.hidden = isTime; }
        function syncWhen() { var m = whenMode(); inBox.style.display = (m === 'in') ? 'inline-flex' : 'none'; atEl.hidden = (m !== 'at'); }
        Array.prototype.forEach.call(modal.querySelectorAll('input[name=pt-sched-trig]'), function (r) { r.addEventListener('change', syncTrig); });
        Array.prototype.forEach.call(modal.querySelectorAll('input[name=pt-sched-when]'), function (r) { r.addEventListener('change', syncWhen); });

        function esc(s) { var d = document.createElement('div'); d.textContent = (s == null) ? '' : s; return d.innerHTML; }
        function icon(s) { return s === 'pending' ? '⏳' : s === 'sent' ? '✓' : s === 'failed' ? '⚠' : '■'; }
        function renderList(items) {
          if (!items || !items.length) { listEl.innerHTML = '<div class="pt-sched-empty">' + esc(listEl.getAttribute('data-empty')) + '</div>'; return; }
          var cancelLbl = listEl.getAttribute('data-cancel') || 'Cancel';
          listEl.innerHTML = '';
          items.forEach(function (it) {
            var row = document.createElement('div');
            row.className = 'pt-sched-item'; row.setAttribute('data-status', it.status);
            var firstLine = (it.prompt || '').split('\n')[0];
            var cancelBtn = (it.status === 'pending')
              ? '<button type="button" class="pt-sched-cancel" data-id="' + esc(it.id) + '">' + esc(cancelLbl) + '</button>' : '';
            row.innerHTML =
              '<span class="si-icon">' + icon(it.status) + '</span>' +
              '<span class="si-main"><div class="si-label">' + esc(it.triggerLabel || it.triggerType) + '</div>' +
              '<div class="si-prompt">' + esc(firstLine) + '</div></span>' + cancelBtn;
            listEl.appendChild(row);
          });
        }
        function loadList() {
          api('GET', base).then(function (r) { return r.ok ? r.json() : null; })
            .then(function (d) { if (d) renderList(d.schedules || []); }).catch(function () {});
        }
        listEl.addEventListener('click', function (ev) {
          var btn = ev.target.closest ? ev.target.closest('.pt-sched-cancel') : null;
          if (!btn) return;
          if (!window.confirm(listEl.getAttribute('data-confirm') || 'Cancel?')) return;
          api('DELETE', base + '/' + encodeURIComponent(btn.getAttribute('data-id'))).then(loadList).catch(function () {});
        });

        function openModal() { modal.hidden = false; errEl.textContent = ''; syncTrig(); syncWhen(); loadList(); promptEl.focus(); }
        function closeModal() { modal.hidden = true; }
        openBtn.addEventListener('click', openModal);
        closeBtn.addEventListener('click', closeModal);
        modal.addEventListener('click', function (ev) { if (ev.target === modal) closeModal(); });

        addBtn.addEventListener('click', function () {
          var prompt = (promptEl.value || '').trim();
          if (!prompt) { promptEl.focus(); return; }
          var t = trig();
          var body = { prompt: prompt, triggerType: t };
          if (t === 'time') {
            if (whenMode() === 'at') {
              if (!atEl.value) { atEl.focus(); return; }
              body.atEpochMs = new Date(atEl.value).getTime();
            } else {
              var amt = parseInt(amtEl.value, 10) || 0;
              if (amt <= 0) { amtEl.focus(); return; }
              body.delayMinutes = (unitEl.value === 'hours') ? amt * 60 : amt;
            }
          }
          errEl.textContent = '';
          api('POST', base, body).then(function (r) {
            if (!r.ok) { return r.text().then(function (m) { errEl.textContent = r.status + ': ' + m; }); }
            promptEl.value = ''; loadList();
          }).catch(function (e) { errEl.textContent = String(e); });
        });
      })();
      // v1.111.0 — Todo 요약 + 백그라운드 작업 카드(콘솔에서 이동). 콘솔이 보낸 스냅샷 HTML 을
      // rail 카드에 렌더하고, 내용 있을 때만 카드를 표시한다.
      function renderRailTodo(d) {
        var card = document.querySelector('#project-tabs-root .pt-todo-card');
        if (!card) return;
        var list = document.getElementById('pt-todo-list');
        var summ = document.getElementById('pt-todo-summary');
        if (list) list.innerHTML = d.html || '';
        if (summ) summ.textContent = d.summary || '';
        card.hidden = !(d.count > 0);
      }
      function renderRailBgTasks(d) {
        var card = document.querySelector('#project-tabs-root .pt-bg-card');
        if (!card) return;
        var list = document.getElementById('pt-bg-list');
        var cnt = document.getElementById('pt-bg-count');
        if (list) list.innerHTML = d.html || '';
        if (cnt) cnt.textContent = (d.running > 0) ? '· ' + d.running : '';
        card.hidden = !(d.count > 0);
      }
      window.addEventListener('message', function (ev) {
        if (ev.origin !== location.origin) return;
        var d = ev.data;
        if (!d) return;
        if (d.type === 'vibe:prompt-sent') { prependHistory(d.text); return; }
        if (d.type === 'vibe:context-usage') { updateRailContextMeter(d); return; }
        // v1.109.0 — 콘솔 iframe 이 포워딩한 자동화 진행/종료 프레임 → rail 카드 즉시 갱신.
        if (d.type === 'vibe:automation') { if (window.__ptAutoRender) window.__ptAutoRender(d.state); return; }
        // v1.111.0 — Todo / 백그라운드 작업 스냅샷 → rail 카드 렌더.
        if (d.type === 'vibe:todo') { renderRailTodo(d); return; }
        if (d.type === 'vibe:bgtasks') { renderRailBgTasks(d); return; }
        // v1.103.0 — 콘솔 iframe 의 busy-badge 가 turn 상태를 헤더 칩으로 미러링.
        if (d.type === 'console:busy') {
          var badge = document.getElementById('console-busy-badge');
          if (badge) {
            badge.dataset.state = d.state || 'ready'; // v1.114.1 — 'idle' 별칭 폐지, 서버 wire 'ready' 통일
            if (typeof d.text === 'string') { badge.textContent = d.text; badge.title = d.text; }
          }
          return;
        }
      });
    })();

    activate(resolveInitialTab());

    // v1.93.3 — reveal gate 해제. CSS 가 #project-tabs-root 를 opacity:0 으로 숨겨둔 채
    // 시작하므로, 올바른 탭이 활성화되고 헤더/탭바/rail 레이아웃이 한 프레임 정리된 뒤
    // 한 번에 보여준다(요소가 따로 그려졌다 재조합되는 모습 제거). JS 가 실패해도
    // CSS 애니메이션 폴백(약 2.5s)이 결국 표시하므로 영구 숨김 위험 없음.
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { root.classList.add('pt-ready'); });
    });

    // Kick off preloading once the console frame is loaded (so it never
    // competes). Fallbacks guarantee preload still runs if load already fired
    // or the console frame is missing.
    var consoleFrame = frameOf('console');
    if (consoleFrame) {
      var already = false;
      try {
        var cd = consoleFrame.contentDocument;
        already = !!consoleFrame.getAttribute('src') && cd && cd.readyState === 'complete'
          && (cd.location ? cd.location.href !== 'about:blank' : true);
      } catch (e) { /* unreachable for same-origin */ }
      if (already) {
        whenIdle(startPreload);
      } else {
        consoleFrame.addEventListener('load', function () { whenIdle(startPreload); }, { once: true });
        // Safety net: if the console never fires load (rare), still preload.
        setTimeout(startPreload, 4000);
      }
    } else {
      whenIdle(startPreload);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
