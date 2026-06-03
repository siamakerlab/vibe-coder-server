package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.ArchivedProjectRow

/**
 * v1.98.0 — 아카이브 목록/복원 페이지(Tools 탭 inner). 카드별 복원/다운로드/삭제.
 */
internal object ArchiveTemplates {

    private fun esc(s: String?): String = s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "%.0f KB".format(b / 1024.0)
    }

    fun page(
        username: String,
        archives: List<ArchivedProjectRow>,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
        embed: Boolean,
    ): String {
        val t = { k: String -> Messages.t(lang, k) }
        val csrfIn = CsrfTokens.hiddenInput(csrf)
        val flash = buildString {
            if (!ok.isNullOrBlank()) append("""<div class="flash ok">${esc(ok)}</div>""")
            if (!err.isNullOrBlank()) append("""<div class="flash err">${esc(err)}</div>""")
        }
        val restoreConfirm = esc(t("archive.restoreConfirm"))
        val deleteConfirm = esc(t("archive.deleteConfirm"))

        val list = if (archives.isEmpty()) {
            """<p class="dim" style="padding:24px;text-align:center">${esc(t("archive.empty"))}</p>"""
        } else archives.joinToString("\n") { a ->
            """
<div class="card" style="margin-bottom:10px">
  <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
    <div style="flex:1;min-width:220px">
      <div style="font-weight:600">${esc(a.name)}</div>
      <div class="dim" style="font-size:12px;font-family:ui-monospace,Menlo,monospace">${esc(a.packageName)}</div>
      <div class="dim" style="font-size:11px;margin-top:2px">
        <code>${esc(a.id)}</code> · ${esc(fmtSize(a.sizeBytes))} · ${esc(a.archivedAt.take(19).replace('T', ' '))}
      </div>
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      <form method="post" action="/archive/${esc(a.id)}/restore" style="margin:0" onsubmit="return confirm('$restoreConfirm')">
        $csrfIn<button type="submit" class="primary">${esc(t("archive.restore"))}</button>
      </form>
      <a href="/archive/${esc(a.id)}/download" class="chip-link" style="padding:8px 12px">${esc(t("archive.download"))}</a>
      <form method="post" action="/archive/${esc(a.id)}/delete" style="margin:0" onsubmit="return confirm('$deleteConfirm')">
        $csrfIn<button type="submit" style="background:#7f1d1d;color:#fff;border:0;padding:8px 12px;border-radius:6px;cursor:pointer">${esc(t("archive.delete"))}</button>
      </form>
    </div>
  </div>
</div>"""
        }

        return AdminTemplates.shell(
            title = t("archive.title"),
            username = username,
            currentPath = "/tools",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
<header><h1>🗄 ${esc(t("archive.title"))}
  <small class="dim" style="font-size:14px;font-weight:400">${esc(t("archive.subtitle"))}</small>
</h1></header>
$flash
$list
""",
        )
    }
}
