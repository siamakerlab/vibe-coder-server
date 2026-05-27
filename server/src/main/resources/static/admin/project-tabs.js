// v1.11.0 — Project Tabs page client logic.
//
// Each tab is an <iframe data-tab="<id>"> that loads the existing SSR page
// (/projects/{id}/console, /builds, /files, /git, /agents). All iframes are
// inserted on first page load — Claude Code WebSockets / build log streams
// stay connected in the background.
//
// Three responsibilities:
//   1. Switch which iframe is visible (CSS display toggle), driven by tab
//      buttons + URL hash (#console, #builds, ...).
//   2. After each iframe loads, hide its inner admin shell `<nav>` so only
//      the parent tab bar is visible (visibility:hidden until then to avoid
//      a flash of the nested nav).
//   3. Persist the last active tab per project in localStorage so refresh
//      lands on the tab the user was on.

(function () {
  'use strict';

  function init() {
    const root = document.getElementById('project-tabs-root');
    if (!root) return;
    const projectId = root.dataset.projectId || '';
    const storageKey = 'vibe.projectTabs.' + projectId;
    const validTabs = Array.from(root.querySelectorAll('.tab-pane'))
      .map(p => p.dataset.tab);
    const buttons = Array.from(root.querySelectorAll('[data-tab-btn]'));

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
      try { localStorage.setItem(storageKey, tab); } catch (e) {}
      if (window.location.hash !== '#' + tab) {
        history.replaceState(null, '', '#' + tab);
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

    // Inner-iframe cleanup: same-origin → we can reach contentDocument and
    // hide the nested admin <nav> + tab bar so the layout looks like a single
    // page. visibility:hidden until cleanup runs, to avoid the nested nav
    // flashing in.
    root.querySelectorAll('iframe.tab-frame').forEach(iframe => {
      iframe.style.visibility = 'hidden';
      iframe.addEventListener('load', function () {
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
        } catch (e) {
          // cross-origin fallback — should not happen for same-origin SSR.
          console && console.debug && console.debug('iframe cleanup failed', e);
        }
        iframe.style.visibility = 'visible';
      });
    });

    activate(resolveInitialTab());
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
