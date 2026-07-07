/*
 * prompt-templates.js — v1.115.0
 *
 * 콘솔 프롬프트 템플릿: 입력창 하단 picker 채우기 + 삽입(변수 치환) + 관리 다이얼로그(CRUD).
 * 별도 페이지(/prompts) 대신 콘솔 내 <dialog> 로 관리한다. JSON CRUD(/api/prompt-templates)를
 * 쿠키 세션(=Bearer) 으로 호출. 의존성 없음(vanilla). 다크 테마(admin.css) 클래스 재사용.
 *
 * 노출: window.PromptTemplates.open() — 관리 다이얼로그 열기. picker/버튼은 자동 와이어링.
 */
(function () {
  'use strict';

  var API = '/api/prompt-templates';
  var cache = [];          // 최신 템플릿 목록
  var editingId = null;    // 다이얼로그 편집 중 id (null = 신규)
  var dlg = null;          // 관리 <dialog>
  var els = {};            // 다이얼로그 내부 엘리먼트 캐시

  function $(id) { return document.getElementById(id); }
  function inputEl() { return $('prompt-input'); }
  function pickerEl() { return $('template-picker'); }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // ── fetch helpers ──────────────────────────────────────────────
  function loadList() {
    return fetch(API, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : { templates: [] }; })
      .then(function (d) { cache = (d && d.templates) || []; return cache; })
      .catch(function () { cache = []; return cache; });
  }
  function send(method, url, body) {
    return fetch(url, {
      method: method, credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
  }
  function errText(res) {
    return res.text().then(function (t) {
      try { var j = JSON.parse(t); return j.message || j.error || t; } catch (e) { return t || (res.status + ''); }
    });
  }

  // ── 정렬: 고정 먼저, 그다음 사용횟수 desc, 그다음 제목 ─────────────
  function sortTemplates(list) {
    return list.slice().sort(function (a, b) {
      if (!!b.pinned !== !!a.pinned) return b.pinned ? 1 : -1;
      if ((b.useCount || 0) !== (a.useCount || 0)) return (b.useCount || 0) - (a.useCount || 0);
      return String(a.title).localeCompare(String(b.title));
    });
  }

  // ── 변수 치환 {{var}} ──────────────────────────────────────────
  function extractVars(body) {
    var re = /\{\{\s*([\w.\- ]+?)\s*\}\}/g, m, seen = {}, out = [];
    while ((m = re.exec(body)) !== null) {
      var name = m[1].trim();
      if (name && !seen[name]) { seen[name] = 1; out.push(name); }
    }
    return out;
  }
  function applyVars(body, values) {
    return body.replace(/\{\{\s*([\w.\- ]+?)\s*\}\}/g, function (full, name) {
      var k = name.trim();
      return Object.prototype.hasOwnProperty.call(values, k) ? values[k] : full;
    });
  }

  function insertBody(body) {
    var input = inputEl();
    if (!input) return;
    if (input.value && input.value.trim().length > 0) {
      input.value = input.value.replace(/\s+$/, '') + '\n\n' + body;
    } else {
      input.value = body;
    }
    input.focus();
    try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
  }

  function recordUse(id) {
    send('POST', API + '/' + encodeURIComponent(id) + '/use').catch(function () {});
  }

  function insertTemplate(t) {
    var vars = extractVars(t.body || '');
    if (vars.length === 0) {
      insertBody(t.body || '');
      recordUse(t.id);
      return;
    }
    openVarsDialog(t, vars);
  }

  // ── 변수 입력 다이얼로그 ───────────────────────────────────────
  var varsDlg = null;
  function openVarsDialog(t, vars) {
    if (!varsDlg) {
      varsDlg = document.createElement('dialog');
      varsDlg.className = 'pt-dialog';
      varsDlg.style.cssText = 'max-width:460px;width:92vw;border:1px solid #333;border-radius:10px;background:#161616;color:var(--text,#ddd);padding:0';
      document.body.appendChild(varsDlg);
    }
    var rows = vars.map(function (v, i) {
      return '<label style="display:block;margin:8px 0">' + esc(v) +
        '<input type="text" data-var="' + esc(v) + '" ' + (i === 0 ? 'autofocus' : '') +
        ' style="width:100%;margin-top:3px;padding:6px 8px;background:#1a1a1a;color:var(--text,#ddd);border:1px solid #333;border-radius:6px"></label>';
    }).join('');
    varsDlg.innerHTML =
      '<form method="dialog" style="padding:16px">' +
      '<h3 style="margin:0 0 4px;font-size:15px">변수 입력 — ' + esc(t.title) + '</h3>' +
      '<p class="dim" style="font-size:12px;margin:0 0 8px">{{변수}} 자리에 채워 넣을 값을 입력하세요. 비우면 자리표시자 그대로 둡니다.</p>' +
      rows +
      '<div style="display:flex;gap:8px;justify-content:flex-end;margin-top:12px">' +
      '<button value="cancel" class="chip chip-link" style="padding:6px 14px">취소</button>' +
      '<button value="ok" class="primary" style="padding:6px 16px">삽입</button>' +
      '</div></form>';
    varsDlg.onclose = function () {
      if (varsDlg.returnValue !== 'ok') return;
      var values = {};
      varsDlg.querySelectorAll('input[data-var]').forEach(function (el) {
        var val = el.value;
        if (val !== '') values[el.getAttribute('data-var')] = val;
      });
      insertBody(applyVars(t.body || '', values));
      recordUse(t.id);
    };
    varsDlg.showModal();
  }

  // ── picker(콘솔 입력창 하단) ───────────────────────────────────
  function renderPicker() {
    var picker = pickerEl();
    if (!picker) return;
    // 첫 placeholder option 만 남기고 비움.
    // v1.137.4 — select.options 컬렉션은 <option> 만 포함하고 <optgroup> 은 빠지므로,
    // 종전 remove(1) 루프는 option 만 지우고 **빈 optgroup(카테고리 라벨)** 을 DOM 에
    // 남겼다 → 재렌더(관리 다이얼로그 열기/닫기 등)마다 카테고리 헤더 한 벌씩 누적,
    // 모바일 네이티브 시트에서 General/즐겨찾기/... 가 반복 표시되던 버그.
    // children 기준으로 placeholder(첫 child option) 외 전부 제거한다.
    while (picker.children.length > 1) picker.removeChild(picker.lastChild);

    var pinned = sortTemplates(cache.filter(function (t) { return t.pinned; }));
    if (pinned.length) appendGroup(picker, '★ 즐겨찾기', pinned);

    var byCat = {};
    cache.filter(function (t) { return !t.pinned; }).forEach(function (t) {
      (byCat[t.category] = byCat[t.category] || []).push(t);
    });
    Object.keys(byCat).sort(function (a, b) { return a.toLowerCase().localeCompare(b.toLowerCase()); })
      .forEach(function (cat) { appendGroup(picker, cat, sortTemplates(byCat[cat])); });
  }
  function appendGroup(picker, label, items) {
    var og = document.createElement('optgroup');
    og.label = label;
    items.forEach(function (t) {
      var opt = document.createElement('option');
      opt.value = t.id;
      opt.textContent = t.title + (t.useCount ? '  (' + t.useCount + ')' : '');
      og.appendChild(opt);
    });
    picker.appendChild(og);
  }

  function wirePicker() {
    var picker = pickerEl();
    if (!picker || picker.dataset.ptWired) return;
    picker.dataset.ptWired = '1';
    picker.addEventListener('change', function () {
      var id = picker.value;
      picker.selectedIndex = 0;
      if (!id) return;
      var t = cache.filter(function (x) { return x.id === id; })[0];
      if (t) insertTemplate(t);
    });
  }

  // ── 관리 다이얼로그 ────────────────────────────────────────────
  function buildDialog() {
    if (dlg) return;
    dlg = document.createElement('dialog');
    dlg.className = 'pt-dialog';
    dlg.style.cssText = 'max-width:860px;width:94vw;max-height:88vh;border:1px solid #333;border-radius:12px;background:#141414;color:var(--text,#ddd);padding:0;overflow:hidden';
    dlg.innerHTML = [
      '<div style="display:flex;flex-direction:column;max-height:88vh">',
      '  <div style="display:flex;align-items:center;gap:10px;padding:14px 16px;border-bottom:1px solid #2a2a2a">',
      '    <h2 style="margin:0;font-size:16px;flex:1">프롬프트 템플릿 관리</h2>',
      '    <input id="pt-search" type="search" placeholder="검색 (제목·카테고리·본문)" style="flex:1;max-width:260px;padding:6px 10px;background:#1a1a1a;color:var(--text,#ddd);border:1px solid #333;border-radius:6px">',
      '    <button id="pt-close" class="chip chip-link" style="padding:6px 12px">닫기</button>',
      '  </div>',
      '  <div style="display:grid;grid-template-columns:1.3fr 1fr;gap:0;min-height:0;flex:1">',
      '    <div id="pt-list" style="overflow:auto;padding:12px 14px;border-right:1px solid #2a2a2a"></div>',
      '    <div style="overflow:auto;padding:12px 14px">',
      '      <h3 id="pt-form-title" style="margin:0 0 8px;font-size:14px">새 템플릿</h3>',
      '      <label style="display:block;font-size:12px;color:var(--text-dim,#999)">제목',
      '        <input id="pt-f-title" type="text" maxlength="200" style="width:100%;margin-top:3px;padding:7px 9px;background:#1a1a1a;color:var(--text,#ddd);border:1px solid #333;border-radius:6px"></label>',
      '      <label style="display:block;font-size:12px;color:var(--text-dim,#999);margin-top:8px">카테고리 (기존 선택 또는 새로 입력 · 비우면 General)',
      '        <input id="pt-f-cat" type="text" maxlength="100" list="pt-cat-list" autocomplete="off" placeholder="예: Android" style="width:100%;margin-top:3px;padding:7px 9px;background:#1a1a1a;color:var(--text,#ddd);border:1px solid #333;border-radius:6px">',
      '        <datalist id="pt-cat-list"></datalist></label>',
      '      <label style="display:block;font-size:12px;color:var(--text-dim,#999);margin-top:8px">본문 — {{변수}} 사용 가능',
      '        <textarea id="pt-f-body" rows="18" maxlength="100000" style="width:100%;margin-top:3px;padding:7px 9px;background:#1a1a1a;color:var(--text,#ddd);border:1px solid #333;border-radius:6px;font-family:ui-monospace,Menlo,monospace;font-size:12px;resize:vertical"></textarea></label>',
      '      <div id="pt-form-err" class="error" style="display:none;margin-top:8px"></div>',
      '      <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap">',
      '        <button id="pt-save" class="primary" style="padding:7px 18px">저장</button>',
      '        <button id="pt-new" class="chip chip-link" style="padding:7px 14px">새로 시작</button>',
      '      </div>',
      '    </div>',
      '  </div>',
      '  <div style="display:flex;align-items:center;gap:8px;padding:10px 16px;border-top:1px solid #2a2a2a;flex-wrap:wrap">',
      '    <span id="pt-count" class="dim" style="font-size:12px;flex:1"></span>',
      '    <button id="pt-export" class="chip" style="padding:6px 12px;font-size:12px">내보내기 (JSON)</button>',
      '    <button id="pt-import" class="chip" style="padding:6px 12px;font-size:12px">가져오기</button>',
      '    <input id="pt-import-file" type="file" accept="application/json,.json" style="display:none">',
      '  </div>',
      '</div>',
    ].join('\n');
    document.body.appendChild(dlg);

    els = {
      search: $('pt-search'), close: $('pt-close'), list: $('pt-list'),
      formTitle: $('pt-form-title'), fTitle: $('pt-f-title'), fCat: $('pt-f-cat'),
      catList: $('pt-cat-list'), fBody: $('pt-f-body'), formErr: $('pt-form-err'),
      save: $('pt-save'), newBtn: $('pt-new'), count: $('pt-count'),
      exportBtn: $('pt-export'), importBtn: $('pt-import'), importFile: $('pt-import-file'),
    };

    els.close.addEventListener('click', function () { dlg.close(); });
    els.search.addEventListener('input', renderList);
    els.save.addEventListener('click', onSave);
    els.newBtn.addEventListener('click', function () { resetForm(); });
    els.exportBtn.addEventListener('click', onExport);
    els.importBtn.addEventListener('click', function () { els.importFile.click(); });
    els.importFile.addEventListener('change', onImport);
    // 다이얼로그 닫히면 picker 도 최신화.
    dlg.addEventListener('close', function () { renderPicker(); });
    // v1.142.0 — 열린 동안 창 크기 변경 시 rail gutter 재계산(반응형 rail 폭 clamp 대응).
    window.addEventListener('resize', function () { if (dlg && dlg.open) applyRailGutter(); });
  }

  function refreshDialog() {
    return loadList().then(function () {
      renderDatalist();
      renderList();
      renderPicker();
    });
  }

  function renderDatalist() {
    var cats = {};
    cache.forEach(function (t) { if (t.category) cats[t.category] = 1; });
    els.catList.innerHTML = Object.keys(cats).sort(function (a, b) { return a.toLowerCase().localeCompare(b.toLowerCase()); })
      .map(function (c) { return '<option value="' + esc(c) + '">'; }).join('');
  }

  function renderList() {
    var q = (els.search.value || '').trim().toLowerCase();
    var list = cache.filter(function (t) {
      if (!q) return true;
      return (t.title + ' ' + t.category + ' ' + t.body).toLowerCase().indexOf(q) >= 0;
    });
    els.count.textContent = cache.length + '개' + (q ? (' · ' + list.length + '개 일치') : '');
    if (list.length === 0) {
      els.list.innerHTML = '<p class="dim" style="font-size:13px">' + (q ? '일치하는 템플릿이 없습니다.' : '아직 템플릿이 없습니다. 우측에서 추가하세요.') + '</p>';
      return;
    }
    // 고정 먼저, 그다음 카테고리.
    var pinned = sortTemplates(list.filter(function (t) { return t.pinned; }));
    var byCat = {};
    list.filter(function (t) { return !t.pinned; }).forEach(function (t) { (byCat[t.category] = byCat[t.category] || []).push(t); });
    var html = '';
    if (pinned.length) html += section('★ 즐겨찾기', pinned);
    Object.keys(byCat).sort(function (a, b) { return a.toLowerCase().localeCompare(b.toLowerCase()); })
      .forEach(function (cat) { html += section(cat, sortTemplates(byCat[cat])); });
    els.list.innerHTML = html;

    // 행 버튼 와이어링(이벤트 위임).
    els.list.querySelectorAll('[data-act]').forEach(function (btn) {
      btn.addEventListener('click', function () { onRowAction(btn.getAttribute('data-act'), btn.getAttribute('data-id')); });
    });
  }

  function section(label, items) {
    var rows = items.map(function (t) {
      return [
        '<div style="border:1px solid #2a2a2a;border-radius:7px;padding:8px 10px;margin:6px 0">',
        '  <div style="display:flex;align-items:center;gap:6px">',
        '    <strong style="flex:1;font-size:13px;word-break:break-word">' + esc(t.title) + '</strong>',
        (t.useCount ? '<span class="dim" style="font-size:11px">' + t.useCount + '회</span>' : ''),
        '  </div>',
        '  <div style="display:flex;gap:5px;flex-wrap:wrap;margin-top:6px">',
        '    <button class="chip" data-act="edit" data-id="' + esc(t.id) + '" style="padding:3px 9px;font-size:11px">편집</button>',
        '    <button class="chip" data-act="pin" data-id="' + esc(t.id) + '" style="padding:3px 9px;font-size:11px">' + (t.pinned ? '고정 해제' : '★ 고정') + '</button>',
        '    <button class="chip" data-act="dup" data-id="' + esc(t.id) + '" style="padding:3px 9px;font-size:11px">복제</button>',
        '    <button class="chip chip-danger" data-act="del" data-id="' + esc(t.id) + '" style="padding:3px 9px;font-size:11px">삭제</button>',
        '  </div>',
        '</div>',
      ].join('');
    }).join('');
    return '<section style="margin-bottom:8px"><h4 style="margin:8px 0 2px;font-size:11px;color:var(--text-dim,#999);letter-spacing:.5px">' + esc(label) + '</h4>' + rows + '</section>';
  }

  function onRowAction(act, id) {
    var t = cache.filter(function (x) { return x.id === id; })[0];
    if (!t) return;
    if (act === 'edit') {
      editingId = id;
      els.formTitle.textContent = '템플릿 편집';
      els.fTitle.value = t.title;
      els.fCat.value = t.category === 'General' ? '' : t.category;
      els.fBody.value = t.body;
      els.save.textContent = '수정 저장';
      els.formErr.style.display = 'none';
      els.fTitle.focus();
    } else if (act === 'pin') {
      send('POST', API + '/' + encodeURIComponent(id) + '/pin', { pinned: !t.pinned })
        .then(function (r) { if (r.ok) refreshDialog(); });
    } else if (act === 'dup') {
      send('POST', API + '/' + encodeURIComponent(id) + '/duplicate')
        .then(function (r) { if (r.ok) refreshDialog(); else r.text().then(function (m) { alert('복제 실패: ' + m); }); });
    } else if (act === 'del') {
      if (!confirm('이 템플릿을 삭제할까요?\n\n' + t.title)) return;
      send('DELETE', API + '/' + encodeURIComponent(id))
        .then(function (r) { if (r.ok) { if (editingId === id) resetForm(); refreshDialog(); } });
    }
  }

  function resetForm() {
    editingId = null;
    els.formTitle.textContent = '새 템플릿';
    els.fTitle.value = ''; els.fCat.value = ''; els.fBody.value = '';
    els.save.textContent = '저장';
    els.formErr.style.display = 'none';
    els.fTitle.focus();
  }

  function onSave() {
    var body = {
      title: els.fTitle.value, category: els.fCat.value, body: els.fBody.value,
    };
    var url = editingId ? (API + '/' + encodeURIComponent(editingId)) : API;
    var method = editingId ? 'PUT' : 'POST';
    els.save.disabled = true;
    send(method, url, body).then(function (r) {
      els.save.disabled = false;
      if (r.ok) { resetForm(); refreshDialog(); }
      else errText(r).then(function (m) { els.formErr.textContent = m; els.formErr.style.display = 'block'; });
    }).catch(function () { els.save.disabled = false; els.formErr.textContent = '네트워크 오류'; els.formErr.style.display = 'block'; });
  }

  function onExport() {
    var data = cache.map(function (t) {
      return { title: t.title, category: t.category, body: t.body, pinned: !!t.pinned, useCount: t.useCount || 0 };
    });
    var blob = new Blob([JSON.stringify({ templates: data }, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'prompt-templates.json';
    document.body.appendChild(a); a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 0);
  }

  function onImport(ev) {
    var file = ev.target.files && ev.target.files[0];
    ev.target.value = '';
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var parsed;
      try { parsed = JSON.parse(reader.result); } catch (e) { alert('JSON 파싱 실패: ' + e.message); return; }
      var items = Array.isArray(parsed) ? parsed : (parsed && parsed.templates) || [];
      if (!Array.isArray(items) || items.length === 0) { alert('가져올 템플릿이 없습니다.'); return; }
      var replace = confirm('가져온 ' + items.length + '개로 기존 템플릿을 모두 교체할까요?\n\n확인=전체 교체 / 취소=병합(추가)');
      send('POST', API + '/import', { replace: replace, templates: items }).then(function (r) {
        if (r.ok) r.json().then(function (res) { alert(res.added + '개 가져왔습니다 (총 ' + res.total + '개).'); refreshDialog(); });
        else errText(r).then(function (m) { alert('가져오기 실패: ' + m); });
      });
    };
    reader.readAsText(file);
  }

  // v1.142.0 — 통합 탭 콘솔은 iframe(풀폭)이고 우측 overview rail 은 부모 레이어(z-index)라,
  //   showModal 다이얼로그가 iframe 뷰포트 중앙에 뜨면 우측 ~330px 가 rail 뒤로 가린다.
  //   부모(project-tabs.js)가 inner page .content 에 주입한 padding-right(=rail 폭+24)를 읽어
  //   다이얼로그를 "가시 영역(뷰포트 - rail 폭)" 안으로 한정·중앙정렬한다. 단독 콘솔 페이지나
  //   rail 숨김 시엔 패딩이 작아 gutter=0 → 기존 동작.
  function railGutterPx() {
    try {
      var c = document.querySelector('.content');
      if (!c) return 0;
      var pr = parseInt(window.getComputedStyle(c).paddingRight, 10) || 0;
      return pr > 40 ? pr : 0;   // rail 주입(>=~272) 만 gutter 로 취급(단독=32 무시).
    } catch (e) { return 0; }
  }
  function applyRailGutter() {
    if (!dlg) return;
    var gutter = railGutterPx();
    var vw = window.innerWidth || document.documentElement.clientWidth || 0;
    var avail = Math.max(320, vw - gutter);
    var w = Math.min(860, avail - 24);
    dlg.style.left = '0';
    dlg.style.right = gutter + 'px';
    dlg.style.margin = 'auto';        // [0, vw-gutter] 밴드 안에서 수평/수직 중앙.
    dlg.style.width = w + 'px';
    dlg.style.maxWidth = 'none';
  }

  function open() {
    buildDialog();
    resetForm();
    applyRailGutter();
    refreshDialog().then(function () { if (!dlg.open) dlg.showModal(); });
    if (!dlg.open) dlg.showModal();
    setTimeout(function () { els.search.focus(); }, 30);
  }

  // ── init ───────────────────────────────────────────────────────
  function init() {
    wirePicker();
    var manageBtn = $('manage-templates-btn');
    if (manageBtn && !manageBtn.dataset.ptWired) {
      manageBtn.dataset.ptWired = '1';
      manageBtn.addEventListener('click', function (e) { e.preventDefault(); open(); });
    }
    loadList().then(renderPicker);
  }

  window.PromptTemplates = { open: open, refresh: function () { return loadList().then(renderPicker); } };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
