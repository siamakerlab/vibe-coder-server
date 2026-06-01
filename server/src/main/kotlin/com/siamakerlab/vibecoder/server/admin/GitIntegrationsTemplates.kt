package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.git.GitCredentialStore
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * 환경설정 > Git 통합 페이지 SSR — v0.9.0.
 *
 * 두 섹션:
 *  1. SSH 키 — 컨테이너의 vibe 사용자 공개키 표시. 사용자가 GitHub/GitLab/Gitea 등에
 *     "Deploy key" 또는 "SSH key" 로 등록 → SSH URL clone 가능.
 *  2. PAT (HTTPS) — provider 별 토큰 등록 폼 + 현재 등록된 토큰 목록 (마스킹).
 *
 * v0.86.0 Phase 64.10 — 모든 사용자 가시 한국어 i18n 키화.
 */
object GitIntegrationsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    private fun jsLit(s: String): String = "'${escJs(s)}'"

    fun page(
        username: String,
        tokens: List<GitCredentialStore.TokenView>,
        sshPubKey: String?,
        flash: String?,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val jsCopied = jsLit(t("gitint.ssh.copied"))
        val jsCopy = jsLit(t("gitint.ssh.copy"))
        val sshBlock = if (sshPubKey == null) """
<div style="background:rgba(255,150,80,0.08);padding:10px;border-radius:6px;border:1px solid var(--warn)">
  <p style="margin:0 0 8px">${esc(t("gitint.ssh.notGen"))}</p>
  <form method="post" action="/settings/git-integrations/ssh-keygen"
        onsubmit="return confirm(${jsLit(t("gitint.ssh.confirmGen"))})">
    ${CsrfTokens.hiddenInput(csrf)}
    <button type="submit" class="primary" style="padding:8px 14px">${esc(t("gitint.ssh.genBtn"))}</button>
  </form>
  <p class="hint" style="font-size:12px;margin-top:8px">${esc(t("gitint.ssh.genHint"))}</p>
</div>
""" else """
<p style="margin:0 0 6px">${t("gitint.ssh.pubLabel")}</p>
<div style="display:flex;gap:8px;align-items:flex-start;margin-top:6px">
  <pre id="ssh-pub" class="diff-block" style="flex:1;margin:0;font-size:11px;overflow-x:auto;white-space:pre-wrap;word-break:break-all">${esc(sshPubKey)}</pre>
  <button type="button" class="chip" style="flex-shrink:0;padding:6px 12px"
          onclick="navigator.clipboard.writeText(document.getElementById('ssh-pub').textContent.trim()).then(()=>{this.textContent=$jsCopied;setTimeout(()=>this.textContent=$jsCopy,2000)})">${esc(t("gitint.ssh.copy"))}</button>
</div>
<p class="hint" style="font-size:12px;margin-top:8px">
  ${esc(t("gitint.ssh.guideLabel"))}
  <a href="https://github.com/settings/keys" target="_blank" rel="noreferrer">GitHub</a> ·
  <a href="https://gitlab.com/-/user_settings/ssh_keys" target="_blank" rel="noreferrer">GitLab</a> ·
  Gitea (User Settings > SSH/GPG Keys) ·
  Bitbucket (Personal settings > SSH keys)
</p>
"""

        val tokenRows = if (tokens.isEmpty())
            """<tr><td colspan="6" class="dim" style="text-align:center;padding:14px">${esc(t("gitint.token.empty"))}</td></tr>"""
        else tokens.joinToString("\n") { tk ->
            val delConfirm = Messages.t(lang, "gitint.token.confirmDel", tk.host)
            """<tr>
  <td>${esc(tk.provider)}</td>
  <td><code>${esc(tk.host)}</code></td>
  <td><code>${esc(tk.username)}</code></td>
  <td><code style="font-family:ui-monospace,Menlo,monospace">${esc(tk.tokenMasked)}</code></td>
  <td class="dim" style="font-size:11px">${esc(tk.createdAt)}</td>
  <td>
    <form method="post" action="/settings/git-integrations/delete" style="display:inline"
          onsubmit="return confirm(${jsLit(delConfirm)})">
      ${CsrfTokens.hiddenInput(csrf)}
      <input type="hidden" name="host" value="${esc(tk.host)}">
      <button type="submit" style="padding:4px 10px;font-size:11px">${esc(t("gitint.token.delBtn"))}</button>
    </form>
  </td>
</tr>"""
        }

        return AdminTemplates.shell(
            title = t("gitint.title"),
            username = username,
            currentPath = "/settings/git-integrations",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("gitint.title"))}</h1>
    ${AdminTemplates.backButton("/settings", t("gitint.backToSettings"))}
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">
    ${esc(t("gitint.intro"))}
  </p>
</header>

${flashBlurb(flash, lang)}

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("gitint.ssh.cardTitle"))}</h2>
  $sshBlock
</div>

<div class="card">
  <h2 style="margin-top:0">${esc(t("gitint.pat.cardTitle"))}</h2>

  <table style="width:100%;border-collapse:collapse;margin-bottom:14px">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">${esc(t("table.provider"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("table.host"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("table.username"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("table.tokenMasked"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("gitint.pat.col.createdAt"))}</th>
        <th style="padding:8px"></th>
      </tr>
    </thead>
    <tbody>
      $tokenRows
    </tbody>
  </table>

  <details ${if (tokens.isEmpty()) "open" else ""}>
    <summary style="cursor:pointer;font-size:13px"><strong>${esc(t("gitint.pat.newSection"))}</strong></summary>
    <form method="post" action="/settings/git-integrations" style="margin-top:10px;display:grid;grid-template-columns:1fr 1fr;gap:8px">
      ${CsrfTokens.hiddenInput(csrf)}
      <label>Provider
        <select name="provider" required style="width:100%;padding:6px">
          <option value="github">GitHub</option>
          <option value="gitlab">GitLab</option>
          <option value="gitea">Gitea / Forgejo</option>
          <option value="bitbucket">Bitbucket</option>
          <option value="generic">${esc(t("gitint.pat.providerOther"))}</option>
        </select>
      </label>
      <label>${esc(t("gitint.pat.hostLabel"))}
        <input name="host" required placeholder="${esc(t("gitint.pat.hostPlaceholder"))}">
      </label>
      <label style="grid-column:1 / -1">Personal Access Token
        <input type="password" name="token" required minlength="20"
               placeholder="${esc(t("gitint.pat.tokenPlaceholder"))}" autocomplete="off">
      </label>
      <label>${esc(t("gitint.pat.usernameLabel"))}
        <input name="username" placeholder="x-access-token / oauth2 / your-username">
      </label>
      <label>${esc(t("gitint.pat.noteLabel"))}
        <input name="note" placeholder="${esc(t("gitint.pat.notePlaceholder"))}">
      </label>
      <div style="grid-column:1 / -1;display:flex;justify-content:flex-end">
        <button type="submit" class="primary" style="padding:8px 18px">${esc(t("gitint.pat.submit"))}</button>
      </div>
    </form>

    <details style="margin-top:14px">
      <summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("gitint.pat.guideTitle"))}</summary>
      <ul style="margin-top:6px;font-size:12px;line-height:1.7">
        <li>${t("gitint.pat.guide.github")}</li>
        <li>${t("gitint.pat.guide.gitlab")}</li>
        <li>${t("gitint.pat.guide.gitea")}</li>
        <li>${t("gitint.pat.guide.bitbucket")}</li>
      </ul>
    </details>
  </details>
</div>
"""
        )
    }

    private fun flashBlurb(code: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when (code) {
            "registered" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
              <p style="margin:0;color:var(--ok)">${esc(t("gitint.flash.registered"))}</p>
            </div>"""
            "deleted" -> """<div class="card" style="margin-bottom:12px;background:rgba(160,160,160,0.06)">
              <p style="margin:0" class="dim">${esc(t("gitint.flash.deleted"))}</p>
            </div>"""
            "not-found" -> """<div class="card" style="margin-bottom:12px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
              <p style="margin:0;color:var(--warn)">${esc(t("gitint.flash.notFound"))}</p>
            </div>"""
            "ssh-generated" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
              <p style="margin:0;color:var(--ok)">${esc(t("gitint.flash.sshGenerated"))}</p>
            </div>"""
            else -> ""
        }
    }
}
