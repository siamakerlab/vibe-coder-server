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
        // confirm/alert/upload status 메시지 5개 + parametric 2개 (sentinel-replace 패턴).
        val jsAlertNoSelection = jsLit(t("mcp.js.alertNoSelection"))
        // alertPendingFile: "%s" → ___FNAME___ sentinel → JS .replace
        val jsAlertPendingFile = jsLit(Messages.t(lang, "mcp.js.alertPendingFile", "___FNAME___"))
        // confirmInstall: "%d" → ___COUNT___ sentinel → JS .replace
        val jsConfirmInstall = jsLit(Messages.t(lang, "mcp.js.confirmInstall", "___COUNT___"))
        val jsUploadingPrefix = jsLit(t("mcp.js.uploadingPrefix"))
        val jsUploadingSuffix = jsLit(t("mcp.js.uploadingSuffix"))
        val jsUploadDonePrefix = jsLit(t("mcp.js.uploadDonePrefix"))
        val jsUploadFailed = jsLit(t("mcp.js.uploadFailed"))
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

<form method="post" id="mcp-form" action="/env-setup/mcp/install">
  ${CsrfTokens.hiddenInput(csrf)}
  $categoriesHtml

  <div class="card" style="margin-top:16px;position:sticky;bottom:0;background:var(--card-bg,#1a1a1a);border:2px solid var(--ok);padding:14px">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
      <div>
        <strong>${esc(t("mcp.selected"))}</strong> <span id="select-count" class="ok">0</span> ${esc(t("mcp.selectedSuffix"))}
        <span class="dim" style="font-size:12px">${esc(t("mcp.selectedHint"))}</span>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button type="submit" class="primary" style="padding:8px 18px"
                formaction="/env-setup/mcp/install"
                onclick="return confirmInstall()">${esc(t("mcp.installBtn"))}</button>
        <button type="submit" style="padding:8px 14px"
                formaction="/env-setup/mcp/unregister" formnovalidate
                onclick="return confirm(${jsLit(t("mcp.unregisterConfirm"))})">${esc(t("mcp.unregisterBtn"))}</button>
      </div>
    </div>
  </div>
</form>

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
  var form = document.getElementById('mcp-form');
  var countEl = document.getElementById('select-count');

  function updateCount() {
    var n = form.querySelectorAll('input[name="select"]:checked').length;
    countEl.textContent = n;
    countEl.className = n > 0 ? 'ok' : 'dim';
    // 선택된 항목의 토큰 입력란 강조
    form.querySelectorAll('.mcp-entry').forEach(function(el) {
      var cb = el.querySelector('input[name="select"]');
      var configBlock = el.querySelector('.config-block');
      if (configBlock) {
        configBlock.style.display = cb && cb.checked ? 'block' : 'none';
      }
    });
  }
  form.addEventListener('change', function(e) {
    if (e.target.name === 'select') updateCount();
  });
  updateCount();

  window.confirmInstall = function() {
    var n = form.querySelectorAll('input[name="select"]:checked').length;
    if (n === 0) {
      alert($jsAlertNoSelection);
      return false;
    }
    // 파일 입력이 있는데 path hidden 이 비어있으면 차단 — 사용자가 file 선택만 하고
    // 업로드 응답을 기다리지 않은 경우 또는 업로드 실패한 경우.
    var pendingFile = null;
    form.querySelectorAll('.mcp-file-field').forEach(function(el) {
      var cb = el.closest('.mcp-entry').querySelector('input[name="select"]');
      if (!cb || !cb.checked) return;
      var fileInput = el.querySelector('.mcp-file-input');
      var pathInput = el.querySelector('.mcp-file-path');
      var required = fileInput && fileInput.hasAttribute('required');
      if (required && !(pathInput && pathInput.value)) {
        pendingFile = el.dataset.mcpId + '.' + el.dataset.fieldKey;
      }
    });
    if (pendingFile) {
      alert(($jsAlertPendingFile).replace('___FNAME___', pendingFile));
      return false;
    }
    return confirm(($jsConfirmInstall).replace('___COUNT___', n));
  };

  // v0.11.0 — file input onChange 시 즉시 ajax 업로드 → 응답 path 를 hidden 에 채움.
  form.querySelectorAll('.mcp-file-field').forEach(function(el) {
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
      form.querySelectorAll('.mcp-entry').forEach(function(el) {
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
        val checked = state?.status == McpService.Status.INSTALLED ||
                      state?.status == McpService.Status.REGISTERED_ONLY
        val recClass = if (entry.recommended) "recommended" else ""
        val (badgeCls, badgeText) = when (entry.trust) {
            McpCatalog.Trust.VERIFIED -> "ok" to "VERIFIED"
            McpCatalog.Trust.COMMUNITY -> "warn" to "COMMUNITY"
            McpCatalog.Trust.EXPERIMENTAL -> "dim" to "EXPERIMENTAL"
        }
        val statusChip = when (state?.status) {
            McpService.Status.INSTALLED -> """<span class="ok" style="font-size:11px">${esc(t("mcp.entry.installed"))}</span>"""
            McpService.Status.REGISTERED_ONLY -> """<span class="warn" style="font-size:11px">${esc(t("mcp.entry.registeredOnly"))}</span>"""
            McpService.Status.NOT_INSTALLED -> ""
            else -> ""
        }
        val starHtml = if (entry.recommended) """<span title="${esc(t("mcp.entry.recommendedTitle"))}" style="color:#ffd700">★</span>""" else ""
        val configHtml = if (entry.comingSoon) "" else renderConfigFields(entry, state?.configValues.orEmpty(), lang)
        val homepageLink = entry.homepage?.let {
            """<a href="${esc(it)}" target="_blank" rel="noreferrer" class="dim" style="font-size:11px">${esc(t("mcp.entry.docsLink"))}</a>"""
        }.orEmpty()

        // v0.12.1 — comingSoon 항목은 checkbox disabled + "준비중" 배지 + 카드 흐리게.
        val comingSoonChip = if (entry.comingSoon) {
            """<span class="dim" style="font-size:10px;padding:2px 6px;border-radius:3px;border:1px solid var(--text-dim)">${esc(t("mcp.entry.comingSoon"))}</span>"""
        } else ""
        val cardStyle = if (entry.comingSoon) "padding:12px;opacity:0.55;cursor:not-allowed" else "padding:12px"
        val cbDisabled = if (entry.comingSoon) "disabled" else ""
        val cbTitle = if (entry.comingSoon) {
            "title=\"${esc(t("mcp.entry.comingSoonTitle"))}\""
        } else ""

        return """
<div class="card mcp-entry $recClass" style="$cardStyle">
  <label style="display:flex;gap:10px;align-items:flex-start;cursor:${if (entry.comingSoon) "not-allowed" else "pointer"}">
    <input type="checkbox" name="select" value="${esc(entry.id)}" ${if (checked) "checked" else ""} $cbDisabled $cbTitle
           style="margin-top:4px;width:18px;height:18px;flex-shrink:0">
    <div style="flex:1;min-width:0">
      <div style="display:flex;justify-content:space-between;gap:6px;align-items:start;flex-wrap:wrap">
        <strong style="font-size:14px">$starHtml ${esc(entry.displayName)}</strong>
        <div style="display:flex;gap:4px;align-items:center;flex-wrap:wrap">
          <span class="$badgeCls" style="font-size:10px;padding:2px 6px;border-radius:3px">${esc(badgeText)}</span>
          $comingSoonChip
          $statusChip
        </div>
      </div>
      <p style="font-size:12px;color:var(--text-dim);margin:4px 0 0;line-height:1.5">${esc(entry.description)}</p>
      <p class="dim" style="font-size:11px;margin:4px 0 0;font-family:ui-monospace,Menlo,monospace">${esc(entry.pkg)} $homepageLink</p>
    </div>
  </label>
  $configHtml
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
        return """
<div class="config-block" style="margin-top:8px;padding-top:8px;border-top:1px dashed #333;display:none">
  <p class="dim" style="font-size:11px;margin:0 0 4px">${esc(Messages.t(lang, "mcp.field.required"))}</p>
  $fields
</div>"""
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
