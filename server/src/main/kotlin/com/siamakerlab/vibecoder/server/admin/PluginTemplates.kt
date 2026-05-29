package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.env.PluginService
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.38.0 — 플러그인/마켓플레이스 관리 UI (전역 + 프로젝트 공용). 모두 admin 전용.
 * 목록은 `claude plugin … --json` 스캔이라 터미널/CLI 로 설치한 플러그인도 그대로 표시.
 */
internal object PluginTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * @param scope          "user" (전역 탭) 또는 "project" (프로젝트 탭)
     * @param actionBase     POST prefix. 전역="/settings/plugins", 프로젝트="/projects/{id}/plugins"
     * @param editablePlugins 편집 가능한 플러그인(해당 scope). 전역=user-scope, 프로젝트=project-scope.
     * @param readonlyPlugins 읽기 전용으로 함께 표시(프로젝트 탭의 user-scope). 전역 탭은 빈 리스트.
     */
    fun page(
        username: String,
        currentPath: String,
        scope: String,
        heading: String,
        intro: String,
        actionBase: String,
        marketplaces: List<PluginService.Marketplace>,
        marketplacesReadonly: Boolean,
        editablePlugins: List<PluginService.Plugin>,
        readonlyPlugins: List<PluginService.Plugin>,
        readonlyLabel: String,
        readonlyManageHref: String?,
        csrf: String?,
        lang: String,
        flashOk: String?,
        flashErr: String?,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val okHtml = if (!flashOk.isNullOrBlank()) """<div class="ok-banner">✓ ${esc(flashOk)}</div>""" else ""
        val errHtml = if (!flashErr.isNullOrBlank()) """<div class="error">${esc(flashErr)}</div>""" else ""

        fun pluginRow(p: PluginService.Plugin, editable: Boolean): String {
            val statusChip = if (p.enabled)
                """<span class="ok" style="font-size:11px">${esc(t("plugins.enabled"))}</span>"""
            else """<span class="dim" style="font-size:11px">${esc(t("plugins.disabled"))}</span>"""
            val mcpNote = if (p.mcpServerNames.isEmpty()) "" else
                """<div class="dim" style="font-size:10px">MCP: ${esc(p.mcpServerNames.joinToString(", "))}</div>"""
            val actions = if (!editable) """<span class="dim" style="font-size:11px">${esc(t("scope.readonly"))}</span>""" else {
                val toggle = if (p.enabled) {
                    """<form method="post" action="$actionBase/disable" style="display:inline">
                      ${CsrfTokens.hiddenInput(csrf)}<input type="hidden" name="plugin" value="${esc(p.id)}">
                      <button type="submit" class="chip chip-link">${esc(t("plugins.disable"))}</button></form>"""
                } else {
                    """<form method="post" action="$actionBase/enable" style="display:inline">
                      ${CsrfTokens.hiddenInput(csrf)}<input type="hidden" name="plugin" value="${esc(p.id)}">
                      <button type="submit" class="chip chip-link">${esc(t("plugins.enable"))}</button></form>"""
                }
                """$toggle
                <form method="post" action="$actionBase/update" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}<input type="hidden" name="plugin" value="${esc(p.id)}">
                  <button type="submit" class="chip chip-link">${esc(t("plugins.update"))}</button></form>
                <form method="post" action="$actionBase/uninstall" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}<input type="hidden" name="plugin" value="${esc(p.id)}">
                  <button type="submit" class="chip chip-danger" onclick="return confirm('${esc(p.name)} ${esc(t("plugins.uninstall"))}?')">${esc(t("plugins.uninstall"))}</button></form>"""
            }
            return """<tr>
              <td><code>${esc(p.name)}</code><span class="dim" style="font-size:10px"> @${esc(p.marketplace)}</span>$mcpNote</td>
              <td class="dim" style="font-size:11px">${esc(p.version ?: "-")}</td>
              <td>$statusChip</td>
              <td style="white-space:nowrap">$actions</td>
            </tr>"""
        }

        fun pluginTable(items: List<PluginService.Plugin>, editable: Boolean): String {
            val rows = if (items.isEmpty())
                """<tr><td colspan="4" class="dim" style="text-align:center;padding:10px">${esc(t("plugins.empty"))}</td></tr>"""
            else items.joinToString("") { pluginRow(it, editable) }
            return """<table class="devices"><thead><tr>
              <th>${esc(t("plugins.col.name"))}</th><th>${esc(t("plugins.col.version"))}</th>
              <th>${esc(t("plugins.col.status"))}</th><th>${esc(t("plugins.col.actions"))}</th>
            </tr></thead><tbody>$rows</tbody></table>"""
        }

        // 마켓플레이스 섹션
        val mktRows = if (marketplaces.isEmpty())
            """<tr><td colspan="3" class="dim" style="text-align:center;padding:10px">${esc(t("plugins.empty"))}</td></tr>"""
        else marketplaces.joinToString("") { m ->
            val removeBtn = if (marketplacesReadonly) "" else """
              <form method="post" action="$actionBase/marketplace/remove" style="display:inline">
                ${CsrfTokens.hiddenInput(csrf)}<input type="hidden" name="name" value="${esc(m.name)}">
                <button type="submit" class="chip chip-danger" onclick="return confirm('${esc(m.name)} ${esc(t("plugins.marketplace.remove"))}?')">${esc(t("plugins.marketplace.remove"))}</button></form>"""
            """<tr><td><code>${esc(m.name)}</code></td><td class="dim" style="font-size:11px">${esc(m.repo ?: m.source)}</td><td>$removeBtn</td></tr>"""
        }
        val mktAddForm = if (marketplacesReadonly) {
            readonlyManageHref?.let { """<a href="${esc(it)}" class="chip chip-link" style="margin-top:8px;display:inline-block">${esc(t("scope.manageGlobal"))} →</a>""" } ?: ""
        } else """
          <form method="post" action="$actionBase/marketplace/add" style="display:flex;gap:8px;align-items:end;margin-top:10px;flex-wrap:wrap">
            ${CsrfTokens.hiddenInput(csrf)}
            <label style="margin:0;flex:1;min-width:240px">${esc(t("plugins.marketplace.source"))}
              <input name="source" required placeholder="owner/repo 또는 https://… 또는 /path">
            </label>
            <button type="submit" class="primary" style="padding:8px 14px">${esc(t("plugins.marketplace.addBtn"))}</button>
          </form>"""

        val readonlySection = if (readonlyPlugins.isEmpty() && scope != "project") "" else """
          <div class="card" style="margin-bottom:16px">
            <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
              <h2 style="margin:0;font-size:15px">${esc(readonlyLabel)}</h2>
              ${readonlyManageHref?.let { """<a href="${esc(it)}" class="chip chip-link">${esc(t("scope.manageGlobal"))} →</a>""" } ?: ""}
            </div>
            ${pluginTable(readonlyPlugins, editable = false)}
          </div>"""

        val installScopeNote = if (scope == "project") esc(t("plugins.install.projectNote")) else ""

        val body = """
<header><h1 style="margin:0">${esc(heading)}</h1></header>
$okHtml
$errHtml
<p class="dim" style="font-size:13px;margin:6px 0 16px;line-height:1.5">${esc(intro)}</p>

<div class="card" style="margin-bottom:16px">
  <h2 style="margin:0 0 8px;font-size:15px">${esc(t("plugins.marketplaces"))}</h2>
  <table class="devices"><tbody>$mktRows</tbody></table>
  $mktAddForm
</div>

$readonlySection

<div class="card" style="margin-bottom:16px">
  <h2 style="margin:0 0 8px;font-size:15px">${esc(if (scope == "project") t("scope.project") else t("plugins.installed"))}</h2>
  ${pluginTable(editablePlugins, editable = true)}
</div>

<div class="card">
  <h2 style="margin-top:0;font-size:15px">${esc(t("plugins.install"))}</h2>
  <form method="post" action="$actionBase/install" style="display:flex;gap:8px;align-items:end;flex-wrap:wrap">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0;flex:1;min-width:240px">${esc(t("plugins.install.field"))}
      <input name="plugin" required placeholder="plugin-name@marketplace-name">
    </label>
    <button type="submit" class="primary" style="padding:8px 14px">${esc(t("plugins.install.btn"))}</button>
  </form>
  <p class="hint" style="font-size:12px;margin-top:8px">${esc(t("plugins.install.hint"))} $installScopeNote</p>
</div>
"""
        return AdminTemplates.shell(
            title = heading, username = username, currentPath = currentPath,
            csrf = csrf, lang = lang, body = body,
        )
    }
}
