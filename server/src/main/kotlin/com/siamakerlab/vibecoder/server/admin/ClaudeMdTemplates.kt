package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.35.0 — CLAUDE.md 편집기 (전역 / 프로젝트 공용).
 *
 *  - 전역:    GET/POST /settings/claude-md          (currentPath="/settings/claude-md")
 *  - 프로젝트: GET /projects/{id}/claude-md + POST .../save (currentPath="/projects")
 *
 * 둘 다 동일 레이아웃 — 단순 monospace textarea + 저장(Ctrl+S). Claude 가 매 세션 읽는
 * 규칙 파일이라는 점을 상단 안내로 명시.
 */
internal object ClaudeMdTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        currentPath: String,
        heading: String,
        intro: String,
        pathDisplay: String,
        content: String,
        exists: Boolean,
        sizeBytes: Long,
        saveAction: String,
        csrf: String?,
        lang: String,
        flashOk: String? = null,
        flashErr: String? = null,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val okHtml = if (!flashOk.isNullOrBlank()) """<div class="ok-banner">✓ ${esc(flashOk)}</div>""" else ""
        val errHtml = if (!flashErr.isNullOrBlank()) """<div class="error">${esc(flashErr)}</div>""" else ""
        val sizeKb = "%.1f".format(sizeBytes / 1024.0)
        val statusBadge = if (exists)
            """<span class="ok" style="font-size:12px">✓ ${sizeKb}KB</span>"""
        else
            """<span class="dim" style="font-size:12px">${esc(t("claudeMd.empty"))}</span>"""

        val body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(heading)}</h1>
    $statusBadge
  </div>
</header>

$okHtml
$errHtml

<div class="card">
  <p class="dim" style="font-size:13px;margin:0 0 6px;line-height:1.5">${esc(intro)}</p>
  <p class="dim" style="font-size:11px;margin:0 0 12px;font-family:ui-monospace,Menlo,monospace">${esc(t("claudeMd.pathLabel"))}: ${esc(pathDisplay)}</p>
  <form method="post" action="${esc(saveAction)}" id="claude-md-form" style="display:grid;gap:10px">
    ${CsrfTokens.hiddenInput(csrf)}
    <textarea name="content" id="claude-md-content" rows="28" spellcheck="false"
              style="width:100%;font-family:ui-monospace,Menlo,monospace;font-size:13px;line-height:1.5;tab-size:2">${esc(content)}</textarea>
    <div style="display:flex;gap:10px;align-items:center">
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">${esc(t("claudeMd.save"))}</button>
      <span class="dim" style="font-size:12px">${esc(t("claudeMd.saveHint"))}</span>
    </div>
  </form>
</div>

<script>
(function() {
  // Ctrl/Cmd+S 로 저장. Tab 키는 들여쓰기(2칸) 삽입.
  var ta = document.getElementById('claude-md-content');
  var form = document.getElementById('claude-md-form');
  if (!ta || !form) return;
  document.addEventListener('keydown', function(e) {
    if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      form.submit();
    }
  });
  ta.addEventListener('keydown', function(e) {
    if (e.key === 'Tab') {
      e.preventDefault();
      var s = ta.selectionStart, en = ta.selectionEnd;
      ta.value = ta.value.substring(0, s) + '  ' + ta.value.substring(en);
      ta.selectionStart = ta.selectionEnd = s + 2;
    }
  });
})();
</script>
"""
        return AdminTemplates.shell(
            title = heading,
            username = username,
            currentPath = currentPath,
            csrf = csrf,
            lang = lang,
            body = body,
            embed = embed,
        )
    }
}
