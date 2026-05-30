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
        setTimeout(function () { filter.focus(); }, 0);
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
      document.addEventListener('click', function (e) {
        if (switcher.open && !switcher.contains(e.target)) switcher.open = false;
      });
    })();

    // Inner-iframe cleanup: same-origin → reach contentDocument and hide the
    // nested admin <nav> + tab bar so the layout looks like a single page.
    // visibility:hidden until cleanup runs, to avoid the nested nav flashing.
    root.querySelectorAll('iframe.tab-frame').forEach(iframe => {
      iframe.style.visibility = 'hidden';
      function cleanup() {
        try {
          const doc = iframe.contentDocument;
          if (!doc) { iframe.style.visibility = 'visible'; return; }
          // 1. drop the nested sidebar nav + settings tabBar
          doc.querySelectorAll('nav.sidebar, .settings-tabs').forEach(n => {
            n.style.display = 'none';
          });
          // 2. layout grid → single-column (sidebar slot removed)
          doc.querySelectorAll('.layout').forEach(l => {
            l.style.gridTemplateColumns = '1fr';
          });
          // 3. tighten body padding — outer page already has padding.
          if (doc.body) {
            doc.body.style.margin = '0';
            doc.body.style.minWidth = '0';
          }
          // 4. v1.50.0 — left-align .content + reserve fixed-rail width on the right.
          applyRailToFrame(iframe);
        } catch (e) {
          // cross-origin fallback — should not happen for same-origin SSR.
          console && console.debug && console.debug('iframe cleanup failed', e);
        }
        iframe.style.visibility = 'visible';
      }
      iframe.addEventListener('load', cleanup);
      // v1.27.5 — load race fix: if already complete when we attach (light
      // sub-pages under defer), run cleanup once now. v1.47.0 — only for frames
      // that already have a real src (skip about:blank data-src frames not yet loaded).
      try {
        const d = iframe.contentDocument;
        if (iframe.getAttribute('src') && d && d.readyState === 'complete') cleanup();
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
    })();

    activate(resolveInitialTab());

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
