package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.22.0 — 설정 통합 탭 페이지. [ProjectTabsTemplate] 의 패턴 그대로 — 사용자가
 * 설정 카테고리 사이를 page reload 없이 즉시 전환하고, 각 카테고리의 inner page
 * (예: Security 의 /password / /2fa / /webauthn / /devices / /settings/cors) 는
 * iframe 안에서 자체 navigation. iframe 8개가 첫 진입 시 prerender 되어
 * 사용자가 처음 보고 싶었던 카테고리 외 7개도 백그라운드 fetch (UX trade-off).
 *
 * SettingsNav 의 카테고리와 1:1 매핑 — 각 탭의 src 는 그 카테고리의 대표 URL.
 *
 * 같은 origin → inner admin shell 의 `<nav class="sidebar">` / `.settings-tabs`
 * 는 [project-tabs.js] 의 cleanup 이 hide. 시각적으로 단일 페이지.
 *
 * localStorage key 는 project-tabs.js 가 `data-project-id` 를 prefix 로 사용
 * 하므로 `data-project-id="__settings__"` 로 별도 namespace.
 */
internal object SettingsTabsTemplate {

    private data class Tab(
        val id: String,
        val labelKey: String,
        val src: String,
        val frameName: String,
    )

    // SettingsNav.tabBar 의 10개 카테고리와 1:1. (v1.34.6 MCP 분리 + v1.35.0 CLAUDE.md 분리)
    private val TABS = listOf(
        Tab("general",       "settings.tab.general",       "/settings",                 "stab-general"),
        Tab("security",      "settings.tab.security",      "/password",                 "stab-security"),
        Tab("notifications", "settings.tab.notifications", "/settings/email",           "stab-notifications"),
        Tab("buildEnv",      "settings.tab.buildEnv",      "/env-setup",                "stab-buildenv"),
        Tab("mcp",           "settings.tab.mcp",           "/env-setup/mcp",            "stab-mcp"),
        Tab("claudeMd",      "settings.tab.claudeMd",      "/settings/claude-md",       "stab-claude-md"),
        Tab("skills",        "settings.tab.skills",        "/settings/skills",          "stab-skills"),
        Tab("prompts",       "settings.tab.prompts",       "/prompts",                  "stab-prompts"),
        Tab("backup",        "settings.tab.backup",        "/backup",                   "stab-backup"),
        Tab("monitoring",    "settings.tab.monitoring",    "/usage",                    "stab-monitoring"),
        Tab("users",         "settings.tab.users",         "/users",                    "stab-users"),
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
            title = t("settings.tabs.title"),
            username = username,
            currentPath = "/settings",
            csrf = csrf,
            lang = lang,
            fullbleed = true,
            body = """
<style>
  /* v1.22.0 — 설정 통합 탭. ProjectTabsTemplate 와 동일한 layout / 색상 — 일관성. */
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

<div id="project-tabs-root" data-project-id="__settings__">
  <div class="pt-header">
    <h1>⚙ ${esc(t("settings.tabs.title"))}</h1>
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

<script src="/static/project-tabs.js?v=1.29.0" defer></script>
""",
        )
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
