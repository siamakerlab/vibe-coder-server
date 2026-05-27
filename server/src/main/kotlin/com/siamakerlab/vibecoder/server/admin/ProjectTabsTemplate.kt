package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.dto.ProjectDto

/**
 * v1.11.0 — 통합 프로젝트 탭 페이지.
 *
 * 기존엔 `/projects/{id}` 가 메타데이터 카드만 보여주고 console / builds / files /
 * git / agents 가 각각 별도 SSR 페이지였다. 사용자가 화면 간 이동할 때마다 전체 페이지
 * reload + 모든 자식 프로세스 (Claude session WS, 빌드 로그 stream 등) 의 클라이언트
 * 측 연결이 끊겼다 다시 붙어 UX 가 끊긴다.
 *
 * 본 페이지는:
 *  1. 5개 핵심 SSR 페이지를 `<iframe>` 으로 모두 한 번에 prerender (Console default
 *     active, 나머지 4개 hidden DOM). 같은 origin 이라 inner contentDocument 조작 가능.
 *  2. 탭 클릭 / URL hash 변경 시 [project-tabs.js] 가 CSS display 토글 — 즉시 전환.
 *  3. iframe.onload 에서 inner admin shell 의 `<nav class="sidebar">` / `.settings-tabs`
 *     를 숨기고 layout grid 를 1-column 으로 압축 — 시각적으로 단일 페이지처럼.
 *  4. iframe 5개가 항상 살아있으므로 WebSocket / SSE connection 도 백그라운드에서 그대로
 *     유지된다 (사용자 명시 선택).
 *
 * 기존 5개 SSR 페이지 URL (`/projects/{id}/console` 등) 은 직접 진입 시 그대로 작동
 * — 북마크 호환, iframe src 도 같은 URL.
 *
 * History / Symbols / Usage / Wrapper / Stats / Deps / Automation / Env-files 등
 * 자주 안 쓰는 페이지는 "More" 메뉴에 link 로 두어 별도 navigation 시 새 탭 open.
 */
internal object ProjectTabsTemplate {

    private data class Tab(
        val id: String,
        val labelKey: String,
        val urlSuffix: String,
        /** iframe `name=` 속성 — sub-page navigation (예: build detail) 이 같은 iframe 안에 머묾. */
        val frameName: String,
    )

    private val TABS = listOf(
        Tab("console", "tabs.console", "/console", "tab-console"),
        Tab("builds", "tabs.builds", "/builds", "tab-builds"),
        Tab("files", "tabs.files", "/tree", "tab-files"),
        Tab("git", "tabs.git", "/git", "tab-git"),
        Tab("agents", "tabs.agents", "/agents", "tab-agents"),
    )

    /**
     * Render 통합 페이지. [project] 는 sticky 헤더 + iframe 5개 src 의 base path.
     */
    fun page(
        username: String,
        project: ProjectDto,
        flashErr: String?,
        flashOk: String?,
        csrf: String?,
        lang: String = "en",
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml

        val tabBtns = TABS.joinToString("") { tab ->
            """<button type="button" class="tab-btn" data-tab-btn="${esc(tab.id)}"
                       title="${esc(t(tab.labelKey))}">${esc(t(tab.labelKey))}</button>"""
        }
        val tabPanes = TABS.joinToString("\n") { tab ->
            val src = "/projects/${esc(project.id)}${tab.urlSuffix}"
            """<div class="tab-pane" data-tab="${esc(tab.id)}">
                <iframe class="tab-frame" src="${esc(src)}" name="${esc(tab.frameName)}"
                        title="${esc(t(tab.labelKey))}" loading="eager"
                        referrerpolicy="same-origin"></iframe>
              </div>"""
        }
        // "More" 메뉴 — 자주 안 쓰는 페이지로의 직접 link. 새 브라우저 탭으로 open.
        val moreLinks = listOf(
            "tabs.more.history" to "/history",
            "tabs.more.symbols" to "/symbols",
            "tabs.more.usage" to "/usage",
            "tabs.more.wrapper" to "/wrapper",
            "tabs.more.stats" to "/stats",
            "tabs.more.deps" to "/deps",
            "tabs.more.automation" to "/automation",
            "tabs.more.envFiles" to "/env-files",
        ).joinToString("") { (key, suffix) ->
            """<a href="/projects/${esc(project.id)}${esc(suffix)}" target="_blank"
                  rel="noopener" class="more-item">${esc(t(key))} ↗</a>"""
        }

        val flashHtml = buildString {
            if (!flashOk.isNullOrBlank()) {
                append("""<div class="flash ok">✓ ${esc(flashOk)}</div>""")
            }
            if (!flashErr.isNullOrBlank()) {
                append("""<div class="flash err">⚠ ${esc(flashErr)}</div>""")
            }
        }

        return AdminTemplates.shell(
            title = "${project.name} · ${t("tabs.title")}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<style>
  /* v1.11.0 — Project tabs layout. Sticky header + tab bar + iframe area. */
  #project-tabs-root {
    display: flex; flex-direction: column;
    height: calc(100vh - 16px);   /* admin shell padding */
    margin: -16px; padding: 0;
    background: var(--bg, #0b0d12);
  }
  #project-tabs-root .pt-header {
    display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
    padding: 10px 16px 6px; border-bottom: 1px solid #1f2330;
  }
  #project-tabs-root .pt-header h1 {
    font-size: 16px; margin: 0; font-weight: 600;
  }
  #project-tabs-root .pt-header .meta {
    font-size: 12px; color: var(--text-dim, #888); font-family: ui-monospace, Menlo, monospace;
  }
  #project-tabs-root .pt-header .spacer { flex: 1; }
  #project-tabs-root .tab-bar {
    display: flex; gap: 2px; padding: 0 16px;
    border-bottom: 1px solid #1f2330; background: #0d1018;
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
  #project-tabs-root .more-dropdown {
    position: relative; margin-left: auto;
  }
  #project-tabs-root .more-dropdown summary {
    list-style: none; cursor: pointer; padding: 10px 12px;
    color: var(--text-dim, #888); font-size: 13px;
  }
  #project-tabs-root .more-dropdown summary::-webkit-details-marker { display: none; }
  #project-tabs-root .more-dropdown[open] summary { color: var(--text, #ddd); }
  #project-tabs-root .more-dropdown .more-menu {
    position: absolute; right: 0; top: 100%; background: #131722;
    border: 1px solid #1f2330; border-radius: 4px; min-width: 180px;
    padding: 4px 0; z-index: 100; display: flex; flex-direction: column;
  }
  #project-tabs-root .more-item {
    color: var(--text, #ddd); text-decoration: none; padding: 8px 14px;
    font-size: 13px;
  }
  #project-tabs-root .more-item:hover { background: #1a1f2c; }
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
  #project-tabs-root .flash {
    margin: 8px 16px 0; padding: 8px 12px; border-radius: 4px; font-size: 13px;
  }
  #project-tabs-root .flash.ok { background: rgba(105,219,124,0.08); color: var(--ok, #69db7c); }
  #project-tabs-root .flash.err { background: rgba(255,107,107,0.08); color: var(--err, #ff6b6b); }
</style>

<div id="project-tabs-root" data-project-id="${esc(project.id)}">
  <div class="pt-header">
    <a href="/projects" style="color:var(--text-dim);text-decoration:none;font-size:12px">← ${esc(t("tabs.backToList"))}</a>
    <h1>${esc(project.name)}</h1>
    <span class="meta">${esc(project.packageName)} · ${esc(project.moduleName)}</span>
    <span class="spacer"></span>
  </div>
  $flashHtml
  <div class="tab-bar" role="tablist">
    $tabBtns
    <details class="more-dropdown">
      <summary>${esc(t("tabs.more.label"))} ▾</summary>
      <div class="more-menu">$moreLinks</div>
    </details>
  </div>
  <div class="tab-content">
$tabPanes
  </div>
</div>

<script src="/static/project-tabs.js" defer></script>
""",
        )
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
