// v0.30.0 — Global keyboard shortcuts.
//
// Conventions inspired by vim and GitHub:
//   g p   → /projects
//   g h   → /history (global search)
//   g e   → /env-setup
//   g s   → /settings
//   g d   → / (dashboard)
//   g a   → /audit
//   ?     → toggle help overlay
//
// Behavior:
//   - Ignored when focus is in <input>, <textarea>, [contenteditable],
//     <select>, or when modifier keys (Ctrl/Cmd/Alt) are held.
//   - Two-key sequences time out after 800 ms.
//   - Single-key `?` opens / closes the help overlay (Esc also closes).
(function() {
  var SEQUENCE_TIMEOUT_MS = 800;
  var pending = null;
  var pendingTimer = null;

  var shortcuts = {
    'g p': '/projects',
    'g h': '/history',
    'g e': '/env-setup',
    'g s': '/settings',
    'g d': '/',
    'g a': '/audit',
    'g c': '/chat',
    'g l': '/logs',
  };

  function isTypingTarget(el) {
    if (!el) return false;
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
    if (el.isContentEditable) return true;
    return false;
  }

  function clearPending() {
    pending = null;
    if (pendingTimer) { clearTimeout(pendingTimer); pendingTimer = null; }
  }

  function showHelp() {
    var existing = document.getElementById('vibe-kbd-help');
    if (existing) { existing.remove(); return; }
    var overlay = document.createElement('div');
    overlay.id = 'vibe-kbd-help';
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.7);z-index:9999;display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif';
    overlay.innerHTML = ''
      + '<div style="background:#1a1a1a;color:#eee;padding:20px 28px;border-radius:8px;min-width:320px;max-width:480px;border:1px solid #333">'
      + '  <h3 style="margin:0 0 12px;font-size:14px;text-transform:uppercase;letter-spacing:0.05em">Keyboard shortcuts (v0.30.0)</h3>'
      + '  <table style="width:100%;font-size:13px;border-collapse:collapse">'
      + '    <tr><td style="padding:4px 0;width:80px"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g p</kbd></td><td>Projects list</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g c</kbd></td><td>General Chat</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g h</kbd></td><td>History search (cross-project)</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g e</kbd></td><td>Build environment</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g s</kbd></td><td>Settings</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g a</kbd></td><td>Audit log</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">g d</kbd></td><td>Dashboard</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">?</kbd></td><td>Toggle this overlay</td></tr>'
      + '    <tr><td style="padding:4px 0"><kbd style="background:#333;padding:2px 6px;border-radius:3px">Esc</kbd></td><td>Close overlay</td></tr>'
      + '  </table>'
      + '  <p style="margin:12px 0 0;font-size:11px;opacity:0.7">Disabled when an input is focused. Click anywhere outside to close.</p>'
      + '</div>';
    overlay.addEventListener('click', function(e) { if (e.target === overlay) overlay.remove(); });
    document.body.appendChild(overlay);
  }

  document.addEventListener('keydown', function(e) {
    // Modifier-key combos are reserved for the browser/OS.
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (isTypingTarget(e.target)) return;

    if (e.key === '?') {
      e.preventDefault();
      showHelp();
      return;
    }
    if (e.key === 'Escape') {
      var existing = document.getElementById('vibe-kbd-help');
      if (existing) { existing.remove(); e.preventDefault(); }
      clearPending();
      return;
    }
    var key = e.key.toLowerCase();
    if (pending) {
      var combo = pending + ' ' + key;
      clearPending();
      var target = shortcuts[combo];
      if (target) {
        e.preventDefault();
        window.location.href = target;
      }
      return;
    }
    if (key === 'g') {
      pending = 'g';
      pendingTimer = setTimeout(clearPending, SEQUENCE_TIMEOUT_MS);
    }
  });
})();
