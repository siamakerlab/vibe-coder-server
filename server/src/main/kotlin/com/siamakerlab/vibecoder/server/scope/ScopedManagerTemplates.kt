package com.siamakerlab.vibecoder.server.scope

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.35.0 — "scoped resource manager" 공용 SSR 템플릿. 전역(읽기 전용) + 프로젝트(편집)
 * 2-섹션 목록 + 편집 페이지. 에이전트 정의(.md) / 스킬(SKILL.md) 처럼 "이름 + 본문" 형태
 * 리소스를 프로젝트 탭에서 관리할 때 재사용. 두 목록 모두 호출자가 디스크 스캔 결과를
 * 넘기므로 터미널/Claude 가 직접 만든 항목도 표시된다(전역 항목은 버튼 없이 RO).
 */
internal object ScopedManagerTemplates {

    /** 한 줄 항목. name = 식별자(파일/디렉토리 stem), sizeLabel = "3KB" 등, preview = 발췌. */
    data class Item(val name: String, val sizeLabel: String, val preview: String)

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    // 21차 후속(M2) — JS 문자열 컨텍스트 escape. esc(escJs(x)) 순서로 써야 onclick 같은
    // 이벤트 핸들러 속성에서 HTML 엔티티 이중디코딩(&#39;→')으로 인한 JS 탈출(XSS)을 막는다.
    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    // 21차 후속(M3) — URL 경로 세그먼트용. 디스크 스캔 이름의 특수문자로 path param 이 깨지지 않게.
    private fun encPath(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun listPage(
        username: String,
        currentPath: String,
        heading: String,
        intro: String,
        globalLabel: String,
        globalManageHref: String,
        projectLabel: String,
        globalPathNote: String,
        projectPathNote: String,
        globalItems: List<Item>,
        projectItems: List<Item>,
        editBase: String,      // "{base}/{name}/edit"
        deleteBase: String,    // "{base}/{name}/delete"
        newFormAction: String,
        newNamePattern: String,
        bodyPlaceholder: String,
        csrf: String?,
        lang: String,
        flashOk: String? = null,
        flashErr: String? = null,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val okHtml = if (!flashOk.isNullOrBlank()) """<div class="ok-banner">✓ ${esc(flashOk)}</div>""" else ""
        val errHtml = if (!flashErr.isNullOrBlank()) """<div class="error">${esc(flashErr)}</div>""" else ""

        fun previewCell(p: String) =
            """<pre style="margin:0;font-size:11px;white-space:pre-wrap;word-break:break-word;max-width:560px;opacity:.7">${esc(p)}</pre>"""

        // 전역 — 읽기 전용 (버튼 없음). 항목 없으면 dim 안내.
        val globalRows = if (globalItems.isEmpty())
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:10px">${esc(t("scope.empty"))}</td></tr>"""
        else globalItems.joinToString("") { it ->
            """<tr>
              <td><code>${esc(it.name)}</code> <span class="dim" style="font-size:10px">${esc(it.sizeLabel)}</span></td>
              <td>${previewCell(it.preview)}</td>
              <td class="dim" style="font-size:11px;white-space:nowrap">${esc(t("scope.readonly"))}</td>
            </tr>"""
        }

        // 프로젝트 — 편집/삭제.
        val projectRows = if (projectItems.isEmpty())
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:10px">${esc(t("scope.empty"))}</td></tr>"""
        else projectItems.joinToString("") { it ->
            """<tr>
              <td><a href="$editBase/${encPath(it.name)}/edit"><code>${esc(it.name)}</code></a> <span class="dim" style="font-size:10px">${esc(it.sizeLabel)}</span></td>
              <td>${previewCell(it.preview)}</td>
              <td style="white-space:nowrap">
                <a href="$editBase/${encPath(it.name)}/edit" class="chip chip-link">${esc(t("scope.edit"))}</a>
                <form method="post" action="$deleteBase/${encPath(it.name)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('${esc(escJs(it.name))} ${esc(t("scope.delete"))}?')">${esc(t("scope.delete"))}</button>
                </form>
              </td>
            </tr>"""
        }

        val body = """
<header><h1 style="margin:0">${esc(heading)}</h1></header>
$okHtml
$errHtml
<p class="dim" style="font-size:13px;margin:6px 0 16px;line-height:1.5">${esc(intro)}</p>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <h2 style="margin:0;font-size:15px">${esc(globalLabel)} <span class="dim" style="font-size:11px;font-weight:400;font-family:ui-monospace,Menlo,monospace">${esc(globalPathNote)}</span></h2>
    <a href="${esc(globalManageHref)}" class="chip chip-link">${esc(t("scope.manageGlobal"))} →</a>
  </div>
  <table class="devices" style="margin-top:8px"><tbody>$globalRows</tbody></table>
</div>

<div class="card" style="margin-bottom:16px">
  <h2 style="margin:0 0 8px;font-size:15px">${esc(projectLabel)} <span class="dim" style="font-size:11px;font-weight:400;font-family:ui-monospace,Menlo,monospace">${esc(projectPathNote)}</span></h2>
  <table class="devices"><tbody>$projectRows</tbody></table>
</div>

<div class="card">
  <h2 style="margin-top:0;font-size:15px">${esc(t("scope.new"))}</h2>
  <form method="post" action="${esc(newFormAction)}" style="display:grid;gap:10px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("scope.nameLabel"))}
      <input name="name" required pattern="${esc(newNamePattern)}" placeholder="my-name">
    </label>
    <label>${esc(t("scope.bodyLabel"))}
      <textarea name="body" rows="14" required spellcheck="false"
                style="font-family:ui-monospace,Menlo,monospace;font-size:12px" placeholder="${esc(bodyPlaceholder)}"></textarea>
    </label>
    <div><button type="submit" class="primary">${esc(t("scope.save"))}</button></div>
  </form>
</div>
"""
        return AdminTemplates.shell(
            title = heading, username = username, currentPath = currentPath,
            csrf = csrf, lang = lang, body = body,
            embed = embed,
        )
    }

    /**
     * 전역 관리용 단일 편집 섹션(전역 RO 섹션 없음). 전역 설정 탭(예: /settings/skills)에서
     * 그 스코프 항목을 직접 CRUD 할 때 사용.
     */
    fun managePage(
        username: String,
        currentPath: String,
        heading: String,
        intro: String,
        pathNote: String,
        items: List<Item>,
        editBase: String,
        deleteBase: String,
        newFormAction: String,
        newNamePattern: String,
        bodyPlaceholder: String,
        csrf: String?,
        lang: String,
        flashOk: String? = null,
        flashErr: String? = null,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val okHtml = if (!flashOk.isNullOrBlank()) """<div class="ok-banner">✓ ${esc(flashOk)}</div>""" else ""
        val errHtml = if (!flashErr.isNullOrBlank()) """<div class="error">${esc(flashErr)}</div>""" else ""
        val rows = if (items.isEmpty())
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:10px">${esc(t("scope.empty"))}</td></tr>"""
        else items.joinToString("") { it ->
            """<tr>
              <td><a href="$editBase/${encPath(it.name)}/edit"><code>${esc(it.name)}</code></a> <span class="dim" style="font-size:10px">${esc(it.sizeLabel)}</span></td>
              <td><pre style="margin:0;font-size:11px;white-space:pre-wrap;word-break:break-word;max-width:560px;opacity:.7">${esc(it.preview)}</pre></td>
              <td style="white-space:nowrap">
                <a href="$editBase/${encPath(it.name)}/edit" class="chip chip-link">${esc(t("scope.edit"))}</a>
                <form method="post" action="$deleteBase/${encPath(it.name)}/delete" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="chip chip-danger" onclick="return confirm('${esc(escJs(it.name))} ${esc(t("scope.delete"))}?')">${esc(t("scope.delete"))}</button>
                </form>
              </td>
            </tr>"""
        }
        val body = """
<header><h1 style="margin:0">${esc(heading)}</h1></header>
$okHtml
$errHtml
<p class="dim" style="font-size:13px;margin:6px 0 14px;line-height:1.5">${esc(intro)}</p>

<div class="card" style="margin-bottom:16px">
  <h2 style="margin:0 0 8px;font-size:15px"><span class="dim" style="font-size:11px;font-weight:400;font-family:ui-monospace,Menlo,monospace">${esc(pathNote)}</span></h2>
  <table class="devices"><tbody>$rows</tbody></table>
</div>

<div class="card">
  <h2 style="margin-top:0;font-size:15px">${esc(t("scope.new"))}</h2>
  <form method="post" action="${esc(newFormAction)}" style="display:grid;gap:10px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("scope.nameLabel"))}
      <input name="name" required pattern="${esc(newNamePattern)}" placeholder="my-name">
    </label>
    <label>${esc(t("scope.bodyLabel"))}
      <textarea name="body" rows="14" required spellcheck="false"
                style="font-family:ui-monospace,Menlo,monospace;font-size:12px" placeholder="${esc(bodyPlaceholder)}"></textarea>
    </label>
    <div><button type="submit" class="primary">${esc(t("scope.save"))}</button></div>
  </form>
</div>
"""
        return AdminTemplates.shell(
            title = heading, username = username, currentPath = currentPath,
            csrf = csrf, lang = lang, body = body,
            embed = embed,
        )
    }

    fun editPage(
        username: String,
        currentPath: String,
        heading: String,
        name: String,
        body: String,
        saveAction: String,
        backHref: String,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val pageBody = """
<div class="settings-subnav" style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:14px"><a href="${esc(backHref)}" class="chip chip-link">← ${esc(t("scope.back"))}</a></div>
<header><h1 style="margin:0">${esc(heading)}</h1></header>
<form method="post" action="${esc(saveAction)}" class="card" style="display:grid;gap:10px">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="name" value="${esc(name)}">
  <label>${esc(t("scope.bodyLabel"))}
    <textarea name="body" rows="24" spellcheck="false"
              style="font-family:ui-monospace,Menlo,monospace;font-size:13px">${esc(body)}</textarea>
  </label>
  <div style="display:flex;gap:6px">
    <button type="submit" class="primary">${esc(t("scope.save"))}</button>
    <a href="${esc(backHref)}" class="chip chip-link">← ${esc(t("scope.back"))}</a>
  </div>
</form>
"""
        return AdminTemplates.shell(
            title = heading, username = username, currentPath = currentPath,
            csrf = csrf, lang = lang, body = pageBody,
        )
    }
}
