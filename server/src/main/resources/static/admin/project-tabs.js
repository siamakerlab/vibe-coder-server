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

    // ── v1.50.0 — overview rail (fixed right; width dynamic by display) ──
    var railEl = root.querySelector('.pt-rail');
    function railWidthPx() {
      // display:none (toggle hidden OR media-query) → offsetParent null → 0.
      if (!railEl || root.getAttribute('data-rail') === 'hidden' || railEl.offsetParent === null) return 0;
      return Math.round(railEl.getBoundingClientRect().width);
    }
    // Inside each (same-origin) iframe: left-align .content + reserve rail width on the right
    // so the page content isn't hidden behind the fixed rail. iframe stays full-width so its
    // scrollbar is at the display's far right.
    function applyRailToFrame(iframe) {
      try {
        var doc = iframe && iframe.contentDocument;
        if (!doc) return;
        var w = railWidthPx();
        doc.querySelectorAll('.content').forEach(function (c) {
          c.style.justifySelf = 'start';      // was center → left-align
          c.style.maxWidth = 'none';
          c.style.paddingRight = (w > 0 ? (w + 24) : 32) + 'px';
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
        p.classList.toggle('active', p.dataset.tab === tab);
      });
      buttons.forEach(b => {
        b.classList.toggle('active', b.dataset.tabBtn === tab);
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
    // race 시 iframe 이 영구 invisible 이 되는 새 결함을 만들었다. 남은 책임은 우측 overview
    // rail 폭만큼 inner .content 우측 패딩을 보정하는 것뿐(applyRailToFrame).
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
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'pt-hist-item';
        btn.setAttribute('data-prompt', text);
        if (hint) btn.setAttribute('title', hint);
        btn.textContent = text;
        list.insertBefore(btn, list.firstChild);
        // 최대 7개 + opacity ramp (0~4 불투명, 5 흐림, 6+ 더 흐림) — 서버 렌더와 동일 체계.
        var items = list.querySelectorAll('.pt-hist-item');
        for (var i = items.length - 1; i >= 7; i--) items[i].parentNode.removeChild(items[i]);
        items = list.querySelectorAll('.pt-hist-item');
        for (var j = 0; j < items.length; j++) {
          items[j].style.opacity = j <= 4 ? '1' : (j === 5 ? '0.55' : '0.32');
        }
      }
      window.addEventListener('message', function (ev) {
        if (ev.origin !== location.origin) return;
        var d = ev.data;
        if (!d || d.type !== 'vibe:prompt-sent') return;
        prependHistory(d.text);
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
