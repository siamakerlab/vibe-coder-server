package com.siamakerlab.vibecoder.server.prompts

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens

object PromptTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun listPage(
        username: String,
        templates: List<PromptTemplateStore.Template>,
        csrf: String? = null,
        flashErr: String? = null,
        flashOk: String? = null,

        lang: String,
        embed: Boolean = false,
    ): String {
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val grouped = templates.sortedWith(compareBy({ it.category.lowercase() }, { it.title.lowercase() }))
            .groupBy { it.category }

        val listHtml = if (grouped.isEmpty()) {
            """<p class="dim">아직 저장된 템플릿이 없습니다. 우측 폼에서 추가하세요.</p>"""
        } else {
            grouped.entries.joinToString("\n") { (cat, items) ->
                val rows = items.joinToString("\n") { t ->
                    """<details style="margin:6px 0;border:1px solid #2a2a2a;border-radius:6px;padding:8px">
                  <summary style="cursor:pointer;display:flex;justify-content:space-between;align-items:center;gap:8px">
                    <strong>${esc(t.title)}</strong>
                    <span class="dim" style="font-size:11px">${esc(t.updatedAt)}</span>
                  </summary>
                  <pre class="diff-block" style="margin:8px 0;max-height:200px;overflow:auto">${esc(t.body)}</pre>
                  <div style="display:flex;gap:6px;flex-wrap:wrap">
                    <form method="post" action="/prompts/${esc(t.id)}/delete" style="display:inline"
                          onsubmit="return confirm('이 템플릿을 삭제할까요?')">
                      ${CsrfTokens.hiddenInput(csrf)}
                      <button type="submit" class="chip chip-danger" style="padding:4px 10px;font-size:11px">삭제</button>
                    </form>
                    <button type="button" class="chip" style="padding:4px 10px;font-size:11px"
                            onclick="prefillEdit('${esc(t.id)}', ${jsLitString(t.title)}, ${jsLitString(t.category)}, ${jsLitString(t.body)})">편집</button>
                  </div>
                </details>"""
                }
                """<section style="margin-top:12px">
              <h3 style="font-size:13px;margin:0 0 6px;color:var(--text-dim);text-transform:uppercase;letter-spacing:1px">${esc(cat)}</h3>
              $rows
            </section>"""
            }
        }

        return AdminTemplates.shell(
            title = "프롬프트 템플릿",
            username = username,
            currentPath = "/prompts",
            csrf = csrf,
            body = """
<header>
  <h1>프롬프트 템플릿 <small class="dim" style="font-size:14px;font-weight:400">${templates.size} 개</small></h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">자주 쓰는 프롬프트를 저장해 콘솔의 ▼ 드롭다운에서 즉시 가져다 씁니다.</p>
</header>

$okHtml
$errHtml

<section class="grid" style="grid-template-columns: 1.4fr 1fr">
  <div class="card">
    <h2>저장된 템플릿</h2>
    $listHtml
  </div>

  <div class="card">
    <h2 id="form-title">새 템플릿</h2>
    <form method="post" id="prompt-form" action="/prompts">
      ${CsrfTokens.hiddenInput(csrf)}
      <input type="hidden" name="_id" id="form-id" value="">
      <label>제목
        <input type="text" name="title" id="form-title-input" required maxlength="200" placeholder="예: Android 신규 화면 추가">
      </label>
      <label>카테고리 (선택)
        <input type="text" name="category" id="form-cat-input" maxlength="100" placeholder="예: Android · 비우면 General">
      </label>
      <label>본문 (Claude 프롬프트)
        <textarea name="body" id="form-body-input" required rows="10" maxlength="16000"
                  style="font-family:ui-monospace,Menlo,monospace;font-size:13px"
                  placeholder="Compose 로 Settings 화면을 추가하고 Material3 Switch 로 다크모드 토글을 넣어줘."></textarea>
      </label>
      <div style="display:flex;gap:8px;margin-top:8px">
        <button type="submit" class="primary" id="form-submit" style="padding:8px 18px">저장</button>
        <button type="button" class="chip chip-link" onclick="resetForm()">새로 시작</button>
      </div>
    </form>
  </div>
</section>

<script>
function resetForm() {
  document.getElementById('prompt-form').action = '/prompts';
  document.getElementById('form-id').value = '';
  document.getElementById('form-title-input').value = '';
  document.getElementById('form-cat-input').value = '';
  document.getElementById('form-body-input').value = '';
  document.getElementById('form-title').textContent = '새 템플릿';
  document.getElementById('form-submit').textContent = '저장';
}
function prefillEdit(id, title, cat, body) {
  document.getElementById('prompt-form').action = '/prompts/' + encodeURIComponent(id) + '/update';
  document.getElementById('form-id').value = id;
  document.getElementById('form-title-input').value = title;
  document.getElementById('form-cat-input').value = cat;
  document.getElementById('form-body-input').value = body;
  document.getElementById('form-title').textContent = '템플릿 편집';
  document.getElementById('form-submit').textContent = '수정 저장';
  document.getElementById('form-title-input').focus();
}
</script>
""",
            lang = lang,
            embed = embed,
        )
    }

    /** JS string literal context 전용 safe escape. */
    private fun jsLitString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c.code) {
                0x5C -> sb.append("\\\\")
                0x22 -> sb.append("\\\"")
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                0x09 -> sb.append("\\t")
                0x3C -> sb.append("\\u003C")
                0x3E -> sb.append("\\u003E")
                0x26 -> sb.append("\\u0026")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
