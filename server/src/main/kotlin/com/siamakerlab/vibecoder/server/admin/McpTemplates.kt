package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.McpCatalog
import com.siamakerlab.vibecoder.server.env.McpService

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
 */
object McpTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun catalogPage(
        username: String,
        states: Map<String, McpService.EntryState>,
        flash: String?,
    ): String {
        val total = McpCatalog.size
        val recommended = McpCatalog.recommendedIds.size
        val installedCount = states.values.count { it.status == McpService.Status.INSTALLED }

        val flashHtml = flashBlurb(flash)
        val categoriesHtml = McpCatalog.byCategory.entries.joinToString("\n") { (cat, list) ->
            renderCategory(cat, list, states)
        }
        return AdminTemplates.shell(
            title = "MCP 카탈로그",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <h1 style="margin:0">MCP 카탈로그</h1>
      <p class="dim" style="margin:4px 0 0;font-size:13px">총 $total 개 · 추천 $recommended 개 · 현재 설치 $installedCount 개</p>
    </div>
    <a href="/env-setup" class="chip chip-link">← 빌드환경</a>
  </div>
</header>

$flashHtml

<div class="card" style="margin-bottom:16px">
  <h2 style="margin-top:0">사용법</h2>
  <ol style="margin:6px 0 0 20px;line-height:1.8;font-size:13px">
    <li>원하는 MCP 의 체크박스를 선택하세요. ★ 표시는 vibe-coder 에서 검증된 추천 항목.</li>
    <li>토큰 / API 키 / URL 등이 필요한 항목은 항목 안의 입력란에 직접 채워주세요.</li>
    <li>하단의 <strong>"선택 항목 설치"</strong> 또는 <strong>"선택 항목 제거"</strong> 버튼.</li>
    <li>설치된 MCP 는 Claude 콘솔에서 즉시 사용 가능 (자식 프로세스 재기동 불요).</li>
  </ol>
  <p class="hint" style="margin-top:10px;font-size:12px">
    <strong>Trust tier</strong> —
    <span class="ok">VERIFIED</span>: Anthropic 또는 1st-party 벤더 공식 ·
    <span class="warn">COMMUNITY</span>: 인기 3rd party (패키지명 변동 가능) ·
    <span class="dim">EXPERIMENTAL</span>: 패키지 이름 미확정 — 설치 실패 가능 (직접 수정 권장)
  </p>
</div>

<form method="post" id="mcp-form" action="/env-setup/mcp/install">
  $categoriesHtml

  <div class="card" style="margin-top:16px;position:sticky;bottom:0;background:var(--card-bg,#1a1a1a);border:2px solid var(--ok);padding:14px">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
      <div>
        <strong>선택한 항목</strong> <span id="select-count" class="ok">0</span> 개
        <span class="dim" style="font-size:12px">— 토큰 입력 누락 시 검증 오류로 거부됩니다</span>
      </div>
      <div style="display:flex;gap:8px;flex-wrap:wrap">
        <button type="submit" class="primary" style="padding:8px 18px"
                formaction="/env-setup/mcp/install"
                onclick="return confirmInstall()">선택 항목 설치</button>
        <button type="submit" style="padding:8px 14px"
                formaction="/env-setup/mcp/unregister" formnovalidate
                onclick="return confirm('선택한 MCP 들을 .mcp.json 에서 제거합니다. npm 패키지 자체는 디스크에 남습니다. 계속할까요?')">선택 항목 제거</button>
      </div>
    </div>
  </div>
</form>

<div class="card" style="margin-top:24px;background:rgba(160,120,200,0.06)">
  <h2 style="margin-top:0">📦 카탈로그에 없는 MCP 를 직접 설치하려면</h2>
  <p>다음 명령으로 컨테이너에 들어가 vibe 사용자로 직접 npm 설치 후
  <code>~/.claude/.mcp.json</code> 의 <code>mcpServers</code> 에 entry 를 추가하세요.</p>

  <pre class="diff-block">docker exec -it --user vibe vibe-coder-server bash

# 1) npm 설치 — prefix 가 /home/vibe/.local 이라 bind volume 에 영구 저장됩니다
npm install -g &lt;패키지명&gt;

# 2) .mcp.json 에 등록 (.claude/.mcp.json 직접 편집 또는 아래 한 줄)
cat &gt; ~/.claude/.mcp.json &lt;&lt;'JSON'
{
  "mcpServers": {
    "my-mcp": {
      "command": "npx",
      "args": ["-y", "&lt;패키지명&gt;"],
      "env": { "MY_TOKEN": "..." }
    }
  }
}
JSON</pre>

  <p class="hint" style="margin-top:10px;font-size:12px">
    ✅ <strong>영구 보존</strong>: <code>/home/vibe/.local</code> 와
    <code>/home/vibe/.claude</code> 는 호스트 <code>./vibe-coder-data/</code> 의
    bind mount 입니다. 직접 설치한 MCP 도 <code>docker compose pull && up -d</code>
    이후 사라지지 않습니다.
  </p>
  <p class="hint" style="font-size:12px">
    🔍 잘 만든 MCP 를 찾으려면 <a href="https://github.com/modelcontextprotocol/servers" target="_blank" rel="noreferrer">modelcontextprotocol/servers</a>,
    <a href="https://glama.ai/mcp/servers" target="_blank" rel="noreferrer">Glama MCP Registry</a>,
    <a href="https://www.npmjs.com/search?q=keywords%3Amcp" target="_blank" rel="noreferrer">npm 검색 (mcp 키워드)</a> 추천.
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
      alert('설치할 MCP 를 하나 이상 선택하세요.');
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
      alert('파일 업로드 대기 중: ' + pendingFile + '. 업로드 완료 후 다시 시도하세요.');
      return false;
    }
    return confirm(n + '개 MCP 를 설치합니다 (예상 1~5분 / 항목). 진행 페이지로 이동합니다. 계속할까요?');
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
      statusEl.textContent = '⏳ 업로드 중... (' + (f.size / 1024).toFixed(1) + ' KB)';
      var fd = new FormData();
      fd.append('file', f);
      fetch('/env-setup/mcp/' + encodeURIComponent(mcpId) + '/file/' + encodeURIComponent(fieldKey), {
        method: 'POST', body: fd, credentials: 'same-origin',
      }).then(function(r) {
        if (!r.ok) return r.text().then(function(t) { throw new Error('HTTP ' + r.status + ': ' + t.slice(0, 200)); });
        return r.json();
      }).then(function(resp) {
        pathInput.value = resp.path || '';
        statusEl.className = 'mcp-file-status ok';
        statusEl.textContent = '✓ 업로드 완료 → ' + resp.path;
      }).catch(function(err) {
        pathInput.value = '';
        statusEl.className = 'mcp-file-status warn';
        statusEl.textContent = '✗ ' + (err.message || '업로드 실패');
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

    private fun flashBlurb(code: String?): String = when (code) {
        "no-selection" -> """<div class="card" style="margin-bottom:12px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
          <p style="margin:0;color:var(--warn)">⚠ 선택된 MCP 가 없습니다. 체크박스를 하나 이상 선택하세요.</p>
        </div>"""
        "unregistered" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
          <p style="margin:0;color:var(--ok)">✓ 선택한 항목이 .mcp.json 에서 제거되었습니다. npm 패키지 자체는 디스크에 남아 있어 재등록 시 즉시 사용 가능.</p>
        </div>"""
        else -> ""
    }

    private fun renderCategory(
        category: McpCatalog.Category,
        entries: List<McpCatalog.McpEntry>,
        states: Map<String, McpService.EntryState>,
    ): String {
        val cards = entries.joinToString("\n") { renderEntry(it, states[it.id]) }
        return """
<section style="margin-top:18px">
  <h2 style="margin-bottom:10px;font-size:16px;border-bottom:1px solid #333;padding-bottom:6px">${esc(category.label)}</h2>
  <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:12px">
    $cards
  </div>
</section>"""
    }

    private fun renderEntry(entry: McpCatalog.McpEntry, state: McpService.EntryState?): String {
        val checked = state?.status == McpService.Status.INSTALLED ||
                      state?.status == McpService.Status.REGISTERED_ONLY
        val recClass = if (entry.recommended) "recommended" else ""
        val (badgeCls, badgeText) = when (entry.trust) {
            McpCatalog.Trust.VERIFIED -> "ok" to "VERIFIED"
            McpCatalog.Trust.COMMUNITY -> "warn" to "COMMUNITY"
            McpCatalog.Trust.EXPERIMENTAL -> "dim" to "EXPERIMENTAL"
        }
        val statusChip = when (state?.status) {
            McpService.Status.INSTALLED -> """<span class="ok" style="font-size:11px">✓ 설치됨</span>"""
            McpService.Status.REGISTERED_ONLY -> """<span class="warn" style="font-size:11px">△ 등록만</span>"""
            McpService.Status.NOT_INSTALLED -> ""
            else -> ""
        }
        val starHtml = if (entry.recommended) """<span title="추천" style="color:#ffd700">★</span>""" else ""
        val configHtml = renderConfigFields(entry, state?.configValues.orEmpty())
        val homepageLink = entry.homepage?.let {
            """<a href="${esc(it)}" target="_blank" rel="noreferrer" class="dim" style="font-size:11px">↗ 문서</a>"""
        }.orEmpty()

        return """
<div class="card mcp-entry $recClass" style="padding:12px">
  <label style="display:flex;gap:10px;align-items:flex-start;cursor:pointer">
    <input type="checkbox" name="select" value="${esc(entry.id)}" ${if (checked) "checked" else ""}
           style="margin-top:4px;width:18px;height:18px;flex-shrink:0">
    <div style="flex:1;min-width:0">
      <div style="display:flex;justify-content:space-between;gap:6px;align-items:start;flex-wrap:wrap">
        <strong style="font-size:14px">$starHtml ${esc(entry.displayName)}</strong>
        <div style="display:flex;gap:4px;align-items:center">
          <span class="$badgeCls" style="font-size:10px;padding:2px 6px;border-radius:3px">${esc(badgeText)}</span>
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
    ): String {
        if (entry.configFields.isEmpty()) return ""
        val fields = entry.configFields.joinToString("\n") { f ->
            if (f.isFile) renderFileField(entry, f, existing)
            else renderTextField(entry, f, existing)
        }
        return """
<div class="config-block" style="margin-top:8px;padding-top:8px;border-top:1px dashed #333;display:none">
  <p class="dim" style="font-size:11px;margin:0 0 4px">설정 필요:</p>
  $fields
</div>"""
    }

    private fun renderFileField(
        entry: McpCatalog.McpEntry,
        f: McpCatalog.ConfigField,
        existing: Map<String, String>,
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
        return """
<div class="mcp-file-field" style="margin-top:6px"
     data-mcp-id="${esc(entry.id)}" data-field-key="${esc(f.key)}">
  <label style="display:block;font-size:11px;color:var(--text-dim);margin-bottom:2px">
    ${esc(f.label)} <span class="dim" style="font-size:10px">(파일)</span>${if (f.required) " <span class=\"warn\">*</span>" else ""}
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
