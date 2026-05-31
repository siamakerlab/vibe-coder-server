package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.env.McpCatalog
import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * MCP 카탈로그 페이지 SSR — v0.8.0.
 *
 * 50+개의 MCP 서버를 카테고리별로 그룹 표시. 사용자는:
 *   1) 원하는 항목 체크박스 선택
 *   2) 필요한 토큰/URL 등을 per-entry 폼에 입력
 *   3) "선택 항목 설치" → 진행 페이지 (라이브 로그)
 *
 * 추천 항목엔 ★, trust tier (VERIFIED/COMMUNITY/EXPERIMENTAL) 도 chip 으로 표시.
 *
 * 페이지 하단에 "추가 MCP 직접 설치" 안내 — 카탈로그에 없는 MCP 도
 * `docker exec -it vibe-coder-server bash` 로 들어가 `npm install -g <pkg>` 하면
 * v0.7.0 의 npm-global bind mount 덕에 영구 보존됨.
 *
 * v0.86.0 Phase 64.10 — 모든 사용자 가시 한국어 i18n 키화 + JS strings 도 jsLit 으로
 * render 시점 치환.
 */
object McpTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    private fun jsLit(s: String): String = "'${escJs(s)}'"

    fun catalogPage(
        username: String,
        states: Map<String, McpService.EntryState>,
        flash: String?,
        csrf: String? = null,
        lang: String,
        /** v1.35.0 — 카탈로그에 없지만 .mcp.json 에 등록된 server (터미널/Claude 직접 추가) — 감지 표시용. */
        detectedCustom: List<String> = emptyList(),
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val total = McpCatalog.size
        val recommended = McpCatalog.recommendedIds.size
        val installedCount = states.values.count { it.status == McpService.Status.INSTALLED }

        val flashHtml = flashBlurb(flash, lang)
        val detectedHtml = if (detectedCustom.isEmpty()) "" else """
<div class="card" style="margin-bottom:16px;border-color:var(--warn)">
  <h2 style="margin:0 0 4px;font-size:15px">${esc(t("mcp.detected.title"))} (${detectedCustom.size})</h2>
  <p class="dim" style="font-size:12px;margin:0 0 10px">${esc(t("mcp.detected.hint"))}</p>
  <div style="display:flex;flex-wrap:wrap;gap:6px">${detectedCustom.joinToString(" ") { """<span class="chip">${esc(it)}</span>""" }}</div>
</div>"""
        val categoriesHtml = McpCatalog.byCategory.entries.joinToString("\n") { (cat, list) ->
            renderCategory(cat, list, states, csrf, lang)
        }
        // JS 안에서 쓰이는 i18n 메시지들 — render 시점 jsLit 화.
        // v1.61.0 — 카드별 폼으로 전환하며 batch confirm/no-selection 제거.
        // alertPendingFile: "%s" → ___FNAME___ sentinel → JS .replace
        val jsAlertPendingFile = jsLit(Messages.t(lang, "mcp.js.alertPendingFile", "___FNAME___"))
        val jsUploadingPrefix = jsLit(t("mcp.js.uploadingPrefix"))
        val jsUploadingSuffix = jsLit(t("mcp.js.uploadingSuffix"))
        val jsUploadDonePrefix = jsLit(t("mcp.js.uploadDonePrefix"))
        val jsUploadFailed = jsLit(t("mcp.js.uploadFailed"))
        // v1.62.0 — 인라인 설치/제거 진행 라벨.
        val jsLabelInstall = jsLit(t("mcp.entry.install"))
        val jsLabelReinstall = jsLit(t("mcp.entry.reinstall"))
        val jsLabelInstalled = jsLit(t("mcp.entry.installed"))
        val jsLabelNotInstalled = jsLit(t("mcp.entry.notInstalled"))
        val jsInstalling = jsLit(t("mcp.js.installing"))
        val jsInstallDone = jsLit(t("mcp.js.installDone"))
        val jsInstallFailed = jsLit(t("mcp.js.installFailed"))
        val jsRemoving = jsLit(t("mcp.js.removing"))
        val jsRemoved = jsLit(t("mcp.js.removed"))
        return AdminTemplates.shell(
            title = t("mcp.title"),
            username = username,
            // v1.34.6 — MCP 를 build-env 에서 별도 탭으로 분리. currentPath 를 /env-setup/mcp
            // 로 넘겨 settings 탭바에서 build-env 대신 MCP 탭이 active 가 되게 한다.
            currentPath = "/env-setup/mcp",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <h1 style="margin:0">${esc(t("mcp.title"))}</h1>
      <p class="dim" style="margin:4px 0 0;font-size:13px">${esc(Messages.t(lang, "mcp.subtitle", total, recommended, installedCount))}</p>
    </div>
    <a href="/env-setup" class="chip chip-link">${esc(t("mcp.backToEnv"))}</a>
  </div>
</header>

$flashHtml
$detectedHtml

<div class="card" style="margin-bottom:16px">
  <h2 style="margin-top:0">${esc(t("mcp.howto.title"))}</h2>
  <ol style="margin:6px 0 0 20px;line-height:1.8;font-size:13px">
    <li>${esc(t("mcp.howto.step1"))}</li>
    <li>${esc(t("mcp.howto.step2"))}</li>
    <li>${t("mcp.howto.step3")}</li>
    <li>${esc(t("mcp.howto.step4"))}</li>
  </ol>
  <p class="hint" style="margin-top:10px;font-size:12px">
    ${t("mcp.trust.line")}
  </p>
</div>

<!-- v1.61.0 — 마켓플레이스형: 카드마다 설치/제거 버튼 + 상태. 일괄 체크박스 폼 제거. -->
$categoriesHtml

<div class="card" style="margin-top:24px;background:rgba(160,120,200,0.06)">
  <h2 style="margin-top:0">${esc(t("mcp.customCard.title"))}</h2>
  <p>${t("mcp.customCard.desc")}</p>

  <pre class="diff-block">docker exec -it --user vibe vibe-coder-server bash

${esc(t("mcp.customCard.npmComment"))}
npm install -g ${esc(t("mcp.customCard.placeholder"))}

${esc(t("mcp.customCard.registerComment"))}
cat &gt; ~/.claude/.mcp.json &lt;&lt;'JSON'
{
  "mcpServers": {
    "my-mcp": {
      "command": "npx",
      "args": ["-y", "${esc(t("mcp.customCard.placeholder"))}"],
      "env": { "MY_TOKEN": "..." }
    }
  }
}
JSON</pre>

  <p class="hint" style="margin-top:10px;font-size:12px">
    ${t("mcp.customCard.persistHint")}
  </p>
  <p class="hint" style="font-size:12px">
    ${t("mcp.customCard.findHint")}
  </p>
</div>

<script>
(function() {
  // v1.62.0 — 인라인 설치/제거. 페이지 이동 없이 카드에서 스피너 + 진행 + 상태 갱신.
  var L = {
    install: $jsLabelInstall, reinstall: $jsLabelReinstall,
    installed: $jsLabelInstalled, notInstalled: $jsLabelNotInstalled,
    installing: $jsInstalling, done: $jsInstallDone, failed: $jsInstallFailed,
    removing: $jsRemoving, removed: $jsRemoved
  };
  document.querySelectorAll('.mcp-card-form').forEach(function(form) {
    var card = form.closest('.mcp-entry');
    var prog = form.querySelector('.mcp-progress');
    var progText = form.querySelector('.mcp-progress-text');
    var spinner = form.querySelector('.mcp-spinner');
    var installBtn = form.querySelector('.mcp-install-btn');
    var removeBtn = form.querySelector('.mcp-remove-btn');
    var pill = card ? card.querySelector('.mcp-pill') : null;
    var mcpId = form.dataset.mcpId;

    function setProg(text, cls) { prog.style.display = 'flex'; progText.textContent = text; progText.className = 'mcp-progress-text ' + (cls || 'dim'); }
    function spin(on) { spinner.style.display = on ? '' : 'none'; }
    function setPill(state, label) { if (!pill) return; pill.className = 'pstat pstat-' + state + ' mcp-pill'; pill.textContent = label; }
    function enable(on) { installBtn.disabled = !on; removeBtn.disabled = !on; }
    function csrf() { var el = form.querySelector('input[name="_csrf"]'); return (el && el.value) || (window.__VIBE_CSRF__ || ''); }
    function setInstallIcon(reinstall) {
      installBtn.textContent = reinstall ? L.reinstall : L.install;
    }

    // v1.66.3 — 설치 클릭 시 접힌 설정(<details>)을 펼쳐 collapsed required 필드의 validation 포커스 보장.
    installBtn.addEventListener('click', function() {
      form.querySelectorAll('details.mcp-config').forEach(function(d) { d.open = true; });
    });

    form.addEventListener('submit', function(e) {
      e.preventDefault();
      if (e.submitter && e.submitter.classList.contains('mcp-remove-btn')) { doRemove(); }
      else { doInstall(); }
    });

    function doInstall() {
      var pending = null;
      form.querySelectorAll('.mcp-file-field').forEach(function(el) {
        var fi = el.querySelector('.mcp-file-input'), pi = el.querySelector('.mcp-file-path');
        if (fi && fi.hasAttribute('required') && !(pi && pi.value)) pending = el.dataset.mcpId + '.' + el.dataset.fieldKey;
      });
      if (pending) { alert(($jsAlertPendingFile).replace('___FNAME___', pending)); return; }
      enable(false); spin(true); setProg(L.installing, 'dim');
      var body = new URLSearchParams(new FormData(form)).toString();
      fetch('/env-setup/mcp/install.json', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body,
      }).then(function(r) {
        if (r.ok) return r.json();
        return r.json().catch(function() { return { error: 'HTTP ' + r.status }; }).then(function(j) { throw new Error(j.error || 'error'); });
      }).then(function(d) { watchTask(d.taskId); })
        .catch(function(err) { spin(false); setProg(L.failed + ' · ' + err.message, 'warn'); enable(true); });
    }

    function watchTask(taskId) {
      var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      var ws = new WebSocket(proto + '//' + location.host + '/ws/env-setup/' + encodeURIComponent(taskId) + '/logs');
      var done = false;
      ws.onmessage = function(ev) {
        try {
          var f = JSON.parse(ev.data);
          if (f.type === 'log') { setProg(L.installing + ' · ' + (f.message || '').slice(0, 56), 'dim'); }
          else if (f.type === 'done') {
            done = true;
            if (f.status === 'SUCCESS') {
              spin(false); setProg(L.done, 'ok'); setPill('ready', L.installed);
              setInstallIcon(true); installBtn.classList.remove('primary'); removeBtn.style.display = '';
            } else {
              spin(false); setProg(L.failed + (f.errorMessage ? ' · ' + f.errorMessage : ''), 'warn');
            }
            enable(true); try { ws.close(); } catch (_) {}
          }
        } catch (e) {}
      };
      ws.onclose = function() { if (!done) { spin(false); setProg(L.failed + ' · connection lost', 'warn'); enable(true); } };
      ws.onerror = function() { try { ws.close(); } catch (_) {} };
    }

    function doRemove() {
      if (!confirm(form.dataset.confirmRemove || '')) return;
      enable(false); spin(true); setProg(L.removing, 'dim');
      var body = new URLSearchParams();
      body.set('_csrf', csrf()); body.set('select', mcpId);
      fetch('/env-setup/mcp/unregister', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString(),
      }).then(function(r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        spin(false); setProg(L.removed, 'ok'); setPill('idle', L.notInstalled);
        setInstallIcon(false); installBtn.classList.add('primary'); removeBtn.style.display = 'none'; enable(true);
      }).catch(function(err) { spin(false); setProg('✗ ' + err.message, 'warn'); enable(true); });
    }
  });

  // v0.11.0 — file input onChange 시 즉시 ajax 업로드 → 응답 path 를 hidden 에 채움.
  document.querySelectorAll('.mcp-file-field').forEach(function(el) {
    var mcpId = el.dataset.mcpId;
    var fieldKey = el.dataset.fieldKey;
    var fileInput = el.querySelector('.mcp-file-input');
    var pathInput = el.querySelector('.mcp-file-path');
    var statusEl = el.querySelector('.mcp-file-status');
    if (!fileInput || !pathInput) return;
    fileInput.addEventListener('change', function() {
      var f = fileInput.files && fileInput.files[0];
      if (!f) { pathInput.value = ''; statusEl.textContent = ''; return; }
      statusEl.className = 'mcp-file-status dim';
      statusEl.textContent = $jsUploadingPrefix + (f.size / 1024).toFixed(1) + $jsUploadingSuffix;
      var fd = new FormData();
      fd.append('file', f);
      fetch('/env-setup/mcp/' + encodeURIComponent(mcpId) + '/file/' + encodeURIComponent(fieldKey) +
            '?_csrf=' + encodeURIComponent(window.__VIBE_CSRF__ || ''), {
        method: 'POST', body: fd, credentials: 'same-origin',
        headers: { 'X-CSRF-Token': window.__VIBE_CSRF__ || '' },
      }).then(function(r) {
        if (!r.ok) return r.text().then(function(t) { throw new Error('HTTP ' + r.status + ': ' + t.slice(0, 200)); });
        return r.json();
      }).then(function(resp) {
        pathInput.value = resp.path || '';
        statusEl.className = 'mcp-file-status ok';
        statusEl.textContent = $jsUploadDonePrefix + resp.path;
      }).catch(function(err) {
        pathInput.value = '';
        statusEl.className = 'mcp-file-status warn';
        statusEl.textContent = '✗ ' + (err.message || $jsUploadFailed);
      });
    });
  });

  // toggle 추천만 / 전체 보기
  var btnRecommended = document.getElementById('filter-recommended');
  var btnAll = document.getElementById('filter-all');
  if (btnRecommended && btnAll) {
    function show(filter) {
      document.querySelectorAll('.mcp-entry').forEach(function(el) {
        if (filter === 'recommended') {
          el.style.display = el.classList.contains('recommended') ? '' : 'none';
        } else {
          el.style.display = '';
        }
      });
    }
    btnRecommended.addEventListener('click', function() { show('recommended'); });
    btnAll.addEventListener('click', function() { show('all'); });
  }
})();
</script>
"""
        )
    }

    private fun flashBlurb(code: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when (code) {
            "no-selection" -> """<div class="card" style="margin-bottom:12px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
              <p style="margin:0;color:var(--warn)">${esc(t("mcp.flash.noSelection"))}</p>
            </div>"""
            "unregistered" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
              <p style="margin:0;color:var(--ok)">${esc(t("mcp.flash.unregistered"))}</p>
            </div>"""
            else -> ""
        }
    }

    private fun renderCategory(
        category: McpCatalog.Category,
        entries: List<McpCatalog.McpEntry>,
        states: Map<String, McpService.EntryState>,
        csrf: String?,
        lang: String,
    ): String {
        val cards = entries.joinToString("\n") { renderEntry(it, states[it.id], csrf, lang) }
        return """
<section style="margin-top:18px">
  <h2 style="margin-bottom:10px;font-size:16px;border-bottom:1px solid #333;padding-bottom:6px">${esc(category.label)}</h2>
  <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:12px">
    $cards
  </div>
</section>"""
    }

    private fun renderEntry(entry: McpCatalog.McpEntry, state: McpService.EntryState?, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        val recClass = if (entry.recommended) "recommended" else ""
        val (badgeCls, badgeText) = when (entry.trust) {
            McpCatalog.Trust.VERIFIED -> "ok" to "VERIFIED"
            McpCatalog.Trust.COMMUNITY -> "warn" to "COMMUNITY"
            McpCatalog.Trust.EXPERIMENTAL -> "dim" to "EXPERIMENTAL"
        }
        val installed = state?.status == McpService.Status.INSTALLED
        val registeredOnly = state?.status == McpService.Status.REGISTERED_ONLY
        // v1.61.0 — 카드 우상단 상태 pill. v1.62.0 — JS 가 인라인 설치 후 갱신할 수 있게 클래스/속성.
        val pillState = if (installed || registeredOnly) "ready" else "idle"
        val pillLabel = when {
            installed -> t("mcp.entry.installed")
            registeredOnly -> t("mcp.entry.registeredOnly")
            else -> t("mcp.entry.notInstalled")
        }
        val statusPill = """<span class="pstat pstat-$pillState mcp-pill" data-mcp-pill="${esc(entry.id)}" style="font-size:11px;padding:2px 9px">${esc(pillLabel)}</span>"""
        val starHtml = if (entry.recommended) """<span title="${esc(t("mcp.entry.recommendedTitle"))}" style="color:#ffd700">★</span>""" else ""
        val defaultBadge = if (entry.defaultInstall) """ <span class="ok" style="font-size:10px;padding:1px 6px;border-radius:3px;border:1px solid var(--ok)" title="${esc(t("mcp.entry.defaultTitle"))}">${esc(t("mcp.entry.default"))}</span>""" else ""
        val homepageLink = entry.homepage?.let {
            """<a href="${esc(it)}" target="_blank" rel="noreferrer" class="dim" style="font-size:11px">${esc(t("mcp.entry.docsLink"))}</a>"""
        }.orEmpty()
        val cardStyle = if (entry.comingSoon) "padding:12px;opacity:0.55" else "padding:12px;display:flex;flex-direction:column"

        // v1.62.0 — 카드 하단 액션: comingSoon → 준비중, 그 외 → 단일 폼(설치/제거 두 submit
        // 버튼, formaction 분기 — 중첩 폼 회피) + 인라인 진행 영역(스피너+로그 tail).
        val actionHtml = if (entry.comingSoon) {
            """<div style="margin-top:10px"><span class="dim" style="font-size:11px;padding:2px 8px;border-radius:3px;border:1px solid var(--text-dim)" title="${esc(t("mcp.entry.comingSoonTitle"))}">${esc(t("mcp.entry.comingSoon"))}</span></div>"""
        } else {
            val configHtml = renderConfigFields(entry, state?.configValues.orEmpty(), lang)
            val installLabel = if (installed || registeredOnly) t("mcp.entry.reinstall") else t("mcp.entry.install")
            val installClass = if (installed) "" else "primary"
            val removeStyle = if (installed || registeredOnly) "" else "display:none"
            // v1.66.4 — 설치 버튼은 라벨 너비만큼만(우측 정렬). `button.primary` 의 width:100%
            //  를 inline `width:auto` 로 덮어 전체폭 사용 방지.
            //  진행영역은 처음엔 display:none(이전 `hidden`+inline `display:flex` 충돌로 항상
            //  스피너가 돌던 버그 수정 — JS 가 설치 시 flex 로 노출).
            """
  <form method="post" action="/env-setup/mcp/install" class="mcp-card-form" data-mcp-id="${esc(entry.id)}"
        data-confirm-remove="${esc(t("mcp.entry.removeConfirm"))}" style="margin-top:auto">
    ${CsrfTokens.hiddenInput(csrf)}
    <input type="hidden" name="select" value="${esc(entry.id)}">
    $configHtml
    <div class="mcp-progress" style="display:none;align-items:center;gap:8px;margin-top:10px;font-size:12px">
      <span class="mcp-spinner"></span><span class="mcp-progress-text dim"></span>
    </div>
    <div class="mcp-actions" style="display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:10px">
      <button type="submit" class="mcp-remove-btn chip chip-danger" formaction="/env-setup/mcp/unregister"
              formnovalidate style="font-size:12px;$removeStyle">${esc(t("mcp.entry.remove"))}</button>
      <button type="submit" class="mcp-install-btn $installClass"
              style="width:auto;margin-top:0;padding:6px 16px;font-size:13px">${esc(installLabel)}</button>
    </div>
  </form>"""
        }

        return """
<div class="card mcp-entry $recClass" style="$cardStyle">
  <div style="display:flex;justify-content:space-between;gap:6px;align-items:start;flex-wrap:wrap">
    <strong style="font-size:14px">$starHtml ${esc(entry.displayName)}</strong>$defaultBadge
    <div style="display:flex;gap:4px;align-items:center;flex-wrap:wrap">
      <span class="$badgeCls" style="font-size:10px;padding:2px 6px;border-radius:3px">${esc(badgeText)}</span>
      $statusPill
    </div>
  </div>
  <p style="font-size:12px;color:var(--text-dim);margin:4px 0 0;line-height:1.5">${esc(entry.description)}</p>
  <p class="dim" style="font-size:11px;margin:4px 0 0;font-family:ui-monospace,Menlo,monospace">${esc(entry.pkg)} $homepageLink</p>
  $actionHtml
</div>"""
    }

    private fun renderConfigFields(
        entry: McpCatalog.McpEntry,
        existing: Map<String, String>,
        lang: String,
    ): String {
        if (entry.configFields.isEmpty()) return ""
        val fields = entry.configFields.joinToString("\n") { f ->
            if (f.isFile) renderFileField(entry, f, existing, lang)
            else renderTextField(entry, f, existing)
        }
        // v1.66.3 — 설정필요 항목은 접어놓고(<details> closed), 설치 시 JS 가 자동 펼침
        //  (collapsed 안의 required 필드 validation 포커스 문제 회피).
        return """
<details class="config-block mcp-config" style="margin-top:8px">
  <summary style="cursor:pointer;font-size:12px;color:#cfa763;list-style:none">⚙ ${esc(Messages.t(lang, "mcp.entry.configToggle"))} (${entry.configFields.size})</summary>
  <div style="margin-top:8px;padding-top:8px;border-top:1px dashed #333">
    <p class="dim" style="font-size:11px;margin:0 0 4px">${esc(Messages.t(lang, "mcp.field.required"))}</p>
    $fields
  </div>
</details>"""
    }

    private fun renderFileField(
        entry: McpCatalog.McpEntry,
        f: McpCatalog.ConfigField,
        existing: Map<String, String>,
        lang: String,
    ): String {
        // 기존 업로드된 path 가 있으면 표시 + "교체" 버튼.
        // 새 파일 선택 시 즉시 ajax POST → 응답 path 를 hidden input 에 채움.
        val existingPath = existing[f.key].orEmpty()
        val accept = f.acceptMime.orEmpty()
        val req = if (f.required && existingPath.isEmpty()) "required" else ""
        val helpHtml = f.help?.let {
            """<small class="dim" style="font-size:10px">${esc(it)}</small>"""
        }.orEmpty()
        val pathDisplay = if (existingPath.isNotEmpty()) {
            """<p class="dim" style="font-size:11px;margin:2px 0 0;font-family:ui-monospace,Menlo,monospace">📎 ${esc(existingPath)}</p>"""
        } else ""
        val fileLabel = Messages.t(lang, "mcp.entry.fileLabel")
        return """
<div class="mcp-file-field" style="margin-top:6px"
     data-mcp-id="${esc(entry.id)}" data-field-key="${esc(f.key)}">
  <label style="display:block;font-size:11px;color:var(--text-dim);margin-bottom:2px">
    ${esc(f.label)} <span class="dim" style="font-size:10px">${esc(fileLabel)}</span>${if (f.required) " <span class=\"warn\">*</span>" else ""}
  </label>
  <input type="file" class="mcp-file-input" accept="${esc(accept)}" $req
         style="font-size:11px">
  <input type="hidden" name="cfg.${esc(entry.id)}.${esc(f.key)}" value="${esc(existingPath)}"
         class="mcp-file-path">
  <p class="mcp-file-status dim" style="font-size:10px;margin:2px 0 0"></p>
  $pathDisplay
  $helpHtml
</div>"""
    }

    private fun renderTextField(
        entry: McpCatalog.McpEntry,
        f: McpCatalog.ConfigField,
        existing: Map<String, String>,
    ): String {
        val type = if (f.isSecret) "password" else "text"
        val value = existing[f.key].orEmpty()
        val placeholder = f.placeholder.orEmpty()
        val req = if (f.required) "required" else ""
        val helpHtml = f.help?.let {
            """<small class="dim" style="font-size:10px">${esc(it)}</small>"""
        }.orEmpty()
        return """
<div style="margin-top:6px">
  <label style="display:block;font-size:11px;color:var(--text-dim);margin-bottom:2px">
    ${esc(f.label)}${if (f.required) " <span class=\"warn\">*</span>" else ""}
  </label>
  <input type="$type" name="cfg.${esc(entry.id)}.${esc(f.key)}"
         value="${esc(value)}" placeholder="${esc(placeholder)}" $req
         autocomplete="off" spellcheck="false"
         style="width:100%;font-size:12px;padding:4px 6px;font-family:ui-monospace,Menlo,monospace">
  $helpHtml
</div>"""
    }
}
