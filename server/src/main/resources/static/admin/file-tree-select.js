// v1.19.0 — 파일트리 다중 선택 모드.
//
// 트리거:
//   - 데스크탑: row 우클릭 또는 mousedown 500ms hold
//   - 모바일: touchstart 500ms hold
//
// 선택 모드 진입 후:
//   - 평범한 클릭 = 선택 토글 (navigation 차단)
//   - row hover 시 checkbox 시각 강조
//   - 상단 toolbar 가 #fts-toolbar (action bar) 로 교체
//
// 액션 (toolbar 버튼):
//   - Copy / Cut → sessionStorage 의 clipboard 에 저장 + 모드 해제
//   - Paste → 현재 디렉토리에 copy/move 폼 submit
//   - Delete → 다중 delete 폼 submit
//   - Download → 단일이면 GET /download, 다중 또는 폴더 포함이면 POST /download-zip
//
// 데이터:
//   - 각 row 에 data-rel-path + data-is-dir 속성 (서버가 렌더링)
//   - sessionStorage['vibe.fileClipboard.<projectId>'] = JSON { action, paths[] }

(function () {
  'use strict';
  function init() {
    var root = document.getElementById('file-tree-root');
    if (!root) return;
    var projectId = root.dataset.projectId || '';
    var subPath = root.dataset.subPath || '';
    var csrf = root.dataset.csrf || '';
    var clipboardKey = 'vibe.fileClipboard.' + projectId;

    var rows = Array.from(root.querySelectorAll('tr.row-link[data-rel-path]'));
    var defaultToolbar = document.getElementById('fts-toolbar-default');
    var selectToolbar  = document.getElementById('fts-toolbar-select');
    var countEl = document.getElementById('fts-count');
    var pasteBtn = document.getElementById('fts-paste-btn');
    var pasteLabel = document.getElementById('fts-paste-label');

    var selectMode = false;
    var selected = new Set();   // relPath strings

    function setMode(on) {
      selectMode = on;
      defaultToolbar.style.display = on ? 'none' : '';
      selectToolbar.style.display = on ? '' : 'none';
      rows.forEach(function (r) {
        if (on) r.classList.add('fts-selectable');
        else r.classList.remove('fts-selectable');
        if (!on) {
          r.classList.remove('fts-selected');
        }
      });
      if (!on) selected.clear();
      updateCount();
    }
    function updateCount() {
      if (!countEl) return;
      countEl.textContent = String(selected.size);
    }
    function toggleRow(r) {
      var rel = r.dataset.relPath;
      if (selected.has(rel)) {
        selected.delete(rel);
        r.classList.remove('fts-selected');
      } else {
        selected.add(rel);
        r.classList.add('fts-selected');
      }
      updateCount();
      if (selected.size === 0) setMode(false);
    }

    // long-press handling (touch + mouse). 500ms hold.
    var pressTimer = null;
    var pressedRow = null;
    function startPress(r) {
      pressedRow = r;
      pressTimer = setTimeout(function () {
        if (!selectMode) setMode(true);
        if (pressedRow) toggleRow(pressedRow);
        pressTimer = null;
      }, 500);
    }
    function cancelPress() {
      if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
      pressedRow = null;
    }

    rows.forEach(function (r) {
      // mousedown / touchstart → start press.
      r.addEventListener('mousedown', function (e) {
        if (e.button !== 0) return;   // 좌클릭만
        startPress(r);
      });
      r.addEventListener('touchstart', function () { startPress(r); }, { passive: true });
      ['mouseup', 'mouseleave', 'touchend', 'touchcancel', 'touchmove'].forEach(function (ev) {
        r.addEventListener(ev, cancelPress);
      });
      // 우클릭 → 즉시 선택 모드 + toggle.
      r.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        if (!selectMode) setMode(true);
        toggleRow(r);
      });
      // 일반 클릭: select mode 면 toggle, 아니면 기본 link 동작.
      r.addEventListener('click', function (e) {
        if (selectMode) {
          e.preventDefault();
          e.stopPropagation();
          toggleRow(r);
        }
      }, true);
    });

    // toolbar 버튼 동작.
    var closeBtn = document.getElementById('fts-close-btn');
    if (closeBtn) closeBtn.addEventListener('click', function () { setMode(false); });

    function selectedList() {
      return Array.from(selected);
    }
    function postForm(action, fields) {
      var f = document.createElement('form');
      f.method = 'post';
      f.action = action;
      Object.keys(fields).forEach(function (k) {
        var i = document.createElement('input');
        i.type = 'hidden'; i.name = k; i.value = fields[k]; f.appendChild(i);
      });
      var csrfI = document.createElement('input');
      csrfI.type = 'hidden'; csrfI.name = '_csrf'; csrfI.value = csrf; f.appendChild(csrfI);
      document.body.appendChild(f); f.submit();
    }
    function readClipboard() {
      try { return JSON.parse(sessionStorage.getItem(clipboardKey) || 'null'); }
      catch (e) { return null; }
    }
    function writeClipboard(v) {
      try { sessionStorage.setItem(clipboardKey, JSON.stringify(v)); }
      catch (e) {}
      updatePasteVisibility();
    }
    function clearClipboard() {
      try { sessionStorage.removeItem(clipboardKey); }
      catch (e) {}
      updatePasteVisibility();
    }
    function updatePasteVisibility() {
      var cb = readClipboard();
      if (!pasteBtn) return;
      if (cb && cb.paths && cb.paths.length > 0) {
        pasteBtn.style.display = '';
        if (pasteLabel) {
          pasteLabel.textContent = (cb.action === 'cut' ? '✂' : '⎘') + ' ' +
            cb.paths.length + (cb.action === 'cut' ? ' (cut)' : ' (copy)');
        }
      } else {
        pasteBtn.style.display = 'none';
      }
    }
    updatePasteVisibility();

    var copyBtn = document.getElementById('fts-copy-btn');
    if (copyBtn) copyBtn.addEventListener('click', function () {
      writeClipboard({ action: 'copy', paths: selectedList() });
      setMode(false);
    });
    var cutBtn = document.getElementById('fts-cut-btn');
    if (cutBtn) cutBtn.addEventListener('click', function () {
      writeClipboard({ action: 'cut', paths: selectedList() });
      setMode(false);
    });
    if (pasteBtn) pasteBtn.addEventListener('click', function () {
      var cb = readClipboard();
      if (!cb || !cb.paths || cb.paths.length === 0) return;
      var url = cb.action === 'cut'
        ? '/projects/' + projectId + '/files/move'
        : '/projects/' + projectId + '/files/copy';
      clearClipboard();
      postForm(url, { dstParent: subPath, paths: cb.paths.join('\n') });
    });
    var delBtn = document.getElementById('fts-delete-btn');
    if (delBtn) delBtn.addEventListener('click', function () {
      var n = selected.size;
      if (n === 0) return;
      if (!confirm(delBtn.dataset.confirm.replace('{0}', n))) return;
      postForm('/projects/' + projectId + '/files/delete-batch',
               { parent: subPath, paths: selectedList().join('\n') });
    });
    var dlBtn = document.getElementById('fts-download-btn');
    if (dlBtn) dlBtn.addEventListener('click', function () {
      var paths = selectedList();
      if (paths.length === 0) return;
      // 단일 파일이고 디렉토리 아니면 GET download (브라우저가 attachment 로 받음).
      if (paths.length === 1) {
        var row = rows.find(function (r) { return r.dataset.relPath === paths[0]; });
        var isDir = row && row.dataset.isDir === '1';
        if (!isDir) {
          window.location.href = '/projects/' + projectId + '/files/download?path=' + encodeURIComponent(paths[0]);
          setMode(false);
          return;
        }
      }
      // 그 외엔 zip stream POST (단일 디렉토리 또는 다중).
      postForm('/projects/' + projectId + '/files/download-zip', { paths: paths.join('\n') });
    });

    // ESC 키로 모드 해제.
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && selectMode) setMode(false);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
