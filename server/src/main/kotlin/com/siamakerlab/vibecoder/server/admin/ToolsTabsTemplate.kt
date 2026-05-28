package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.23.0 — 도구 통합 탭 페이지. [ProjectTabsTemplate] / [SettingsTabsTemplate] 와
 * 일관 디자인. tools 카테고리 sub-페이지 (/tools / /multi-console / /emulator /
 * /logs / /code-search / /history) 를 한 페이지의 iframe prerender 로 통합 —
 * 사용자가 도구 간 전환 시 page reload 없이 즉시.
 */
internal object ToolsTabsTemplate {

    private data class Tab(
        val id: String,
        val labelKey: String,
        val src: String,
        val frameName: String,
    )

    private val TABS = listOf(
        Tab("tools",         "tools.tab.overview",   "/tools",         "ttab-tools"),
        Tab("multiConsole",  "tools.tab.multi",      "/multi-console", "ttab-multi"),
        Tab("emulator",      "tools.tab.emulator",   "/emulator",      "ttab-emulator"),
        Tab("codeSearch",    "tools.tab.codeSearch", "/code-search",   "ttab-code-search"),
        Tab("logs",          "tools.tab.logs",       "/logs",          "ttab-logs"),
        Tab("history",       "tools.tab.history",    "/history",       "ttab-history"),
    )

    fun page(username: String, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml

        val tabBtns = TABS.joinToString("") { tab ->
            """<button type="button" class="tab-btn" data-tab-btn="${esc(tab.id)}"
                       title="${esc(t(tab.labelKey))}">${esc(t(tab.labelKey))}</button>"""
        }
        val tabPanes = TABS.joinToString("\n") { tab ->
            """<div class="tab-pane" data-tab="${esc(tab.id)}">
                <iframe class="tab-frame" src="${esc(tab.src)}" name="${esc(tab.frameName)}"
                        title="${esc(t(tab.labelKey))}" loading="eager"
                        referrerpolicy="same-origin"></iframe>
              </div>"""
        }

        return AdminTemplates.shell(
            title = t("tools.tabs.title"),
            username = username,
            currentPath = "/tools",
            csrf = csrf,
            lang = lang,
            fullbleed = true,
            body = """
<style>
  /* v1.23.0 — 도구 통합 탭. ProjectTabs / SettingsTabs 와 일관 layout. */
  #project-tabs-root {
    display: flex; flex-direction: column;
    height: 100%;
    background: var(--bg, #0b0d12);
  }
  #project-tabs-root .pt-header {
    display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
    padding: 10px 16px 8px; border-bottom: 1px solid #1f2330;
  }
  #project-tabs-root .pt-header h1 {
    font-size: 16px; margin: 0; font-weight: 600;
  }
  #project-tabs-root .pt-header .spacer { flex: 1; }
  #project-tabs-root .tab-bar {
    display: flex; gap: 2px; padding: 0;
    border-bottom: 1px solid #1f2330; background: #0d1018;
    align-items: stretch;
  }
  #project-tabs-root .tab-scroll {
    flex: 1; min-width: 0;
    display: flex; gap: 2px; padding: 0 16px;
    overflow-x: auto;
  }
  #project-tabs-root .tab-btn {
    background: transparent; border: 0; color: var(--text-dim, #888);
    padding: 10px 16px; cursor: pointer; font-size: 13px;
    border-bottom: 2px solid transparent; white-space: nowrap;
    font-family: inherit;
  }
  #project-tabs-root .tab-btn:hover { color: var(--text, #ddd); }
  #project-tabs-root .tab-btn.active {
    color: var(--accent, #6aa9ff);
    border-bottom-color: var(--accent, #6aa9ff);
  }
  #project-tabs-root .tab-content {
    flex: 1; position: relative; overflow: hidden;
  }
  #project-tabs-root .tab-pane {
    position: absolute; inset: 0; display: none;
  }
  #project-tabs-root .tab-pane.active { display: block; }
  #project-tabs-root .tab-frame {
    width: 100%; height: 100%; border: 0; background: var(--bg, #0b0d12);
  }
</style>

<div id="project-tabs-root" data-project-id="__tools__">
  <div class="pt-header">
    <h1>🛠 ${esc(t("tools.tabs.title"))}</h1>
    <span class="spacer"></span>
  </div>
  <div class="tab-bar" role="tablist">
    <div class="tab-scroll">
      $tabBtns
    </div>
  </div>
  <div class="tab-content">
$tabPanes
  </div>
</div>

<script src="/static/project-tabs.js?v=1.27.5" defer></script>
""",
        )
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
