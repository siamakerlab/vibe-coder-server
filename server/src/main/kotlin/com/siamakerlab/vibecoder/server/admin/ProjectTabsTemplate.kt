package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
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

    // v1.12.0 — 사용자 명시 요청으로 기존 모든 프로젝트 scope SSR 페이지를 prerender
    // 탭에 통합. 13개 iframe 모두 페이지 첫 진입 시 동시 fetch 라 첫 로드는 무거워
    // 지지만, 이후 탭 전환은 CSS display 토글로 즉시. WebSocket / SSE 도 모두 백그라
    // 운드 유지. /projects/X/usage 는 존재하지 않음 (global /usage 만) → 제외.
    // v1.13.0 — Overview 탭 제거. 그 안의 unique 기능 (메타데이터 / Delete /
    // Zip download) 은 sticky 헤더와 Settings 드롭다운으로 이동. Recent builds 는
    // 이미 Builds 탭에 있어 중복 제거. Action link 들은 모두 다른 탭으로 이미 존재.
    // v1.29.0 — 탭 빈도 분석으로 primary/overflow 분리 (사용자 요청).
    //  primary  = 일상 워크플로 (콘솔/빌드/파일/Git/에이전트/히스토리) → 상단 탭바.
    //  overflow = 저빈도 분석·도구 (심볼/통계/의존성/wrapper/자동화/env-files) → "더보기" 드롭다운.
    // iframe prerender 는 전체 유지(탭 전환 즉시성 보존) — 탭 "버튼"만 더보기로 이동.
    private val PRIMARY_TABS = listOf(
        Tab("console", "tabs.console", "/console", "tab-console"),
        Tab("builds", "tabs.builds", "/builds", "tab-builds"),
        Tab("files", "tabs.files", "/tree", "tab-files"),
        Tab("git", "tabs.git", "/git", "tab-git"),
        Tab("agents", "tabs.agents", "/agents", "tab-agents"),
        Tab("history", "tabs.history", "/history", "tab-history"),
    )
    private val OVERFLOW_TABS = listOf(
        Tab("symbols", "tabs.symbols", "/symbols", "tab-symbols"),
        Tab("stats", "tabs.stats", "/stats", "tab-stats"),
        Tab("deps", "tabs.deps", "/deps", "tab-deps"),
        Tab("wrapper", "tabs.wrapper", "/wrapper", "tab-wrapper"),
        Tab("automation", "tabs.automation", "/automation", "tab-automation"),
        Tab("envFiles", "tabs.envFiles", "/env-files", "tab-env-files"),
        Tab("claudeMd", "tabs.claudeMd", "/claude-md", "tab-claude-md"),
    )
    private val TABS = PRIMARY_TABS + OVERFLOW_TABS

    /**
     * Render 통합 페이지. [project] 는 sticky 헤더 + iframe 5개 src 의 base path.
     */
    fun page(
        username: String,
        project: ProjectDto,
        flashErr: String?,
        flashOk: String?,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml

        // primary 탭만 상단 탭바에. overflow 는 더보기 드롭다운에 (아래 moreLinks).
        val tabBtns = PRIMARY_TABS.joinToString("") { tab ->
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
        // v1.29.0 — 더보기 드롭다운: overflow 탭 버튼(data-tab-btn — project-tabs.js 가
        // 기존 탭 로직으로 처리, iframe pane 재사용) + 구분선 + global /usage 외부 link.
        val overflowBtns = OVERFLOW_TABS.joinToString("") { tab ->
            """<button type="button" class="more-item" data-tab-btn="${esc(tab.id)}"
                       title="${esc(t(tab.labelKey))}">${esc(t(tab.labelKey))}</button>"""
        }
        val moreLinks = overflowBtns +
            """<hr><a href="/usage" target="_blank" rel="noopener" class="more-item">${esc(t("tabs.more.usage"))} ↗</a>"""

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
            // v1.16.0 — .content.fullbleed 모드. ProjectTabs 가 viewport 100% 안에서
            // 자체 layout (sticky header + tab bar + iframe area) 구성.
            fullbleed = true,
            body = """
<style>
  /* v1.11.0 — Project tabs layout. Sticky header + tab bar + iframe area.
     v1.16.0 — admin shell 의 .content.fullbleed 안 → height 100% 부모 기준.
     이전 calc(100vh - 16px) + margin: -16px 트릭 제거 (.content 가 padding 0). */
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
  /* v1.13.1 — 메타데이터를 헤더 chip 에서 빼고 Settings 드롭다운 안의 dl 로 이동. */
  #project-tabs-root .pt-settings .meta-block {
    padding: 8px 14px; border-bottom: 1px solid #1f2330; margin-bottom: 4px;
    font-family: ui-monospace, Menlo, monospace; font-size: 11px;
  }
  #project-tabs-root .pt-settings .meta-block dl {
    margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 4px 10px;
  }
  #project-tabs-root .pt-settings .meta-block dt {
    color: #5a6175; font-size: 10px; text-transform: uppercase;
    letter-spacing: 0.05em; align-self: center;
  }
  #project-tabs-root .pt-settings .meta-block dd {
    margin: 0; color: var(--text, #ddd); word-break: break-all;
  }
  /* v1.13.0 — sticky 헤더 우측 Settings 드롭다운 (Delete / Zip 등 Overview 액션). */
  #project-tabs-root .pt-settings {
    position: relative;
  }
  #project-tabs-root .pt-settings summary {
    list-style: none; cursor: pointer; padding: 6px 10px; font-size: 12px;
    color: var(--text-dim, #888); border: 1px solid #1f2330; border-radius: 4px;
  }
  #project-tabs-root .pt-settings summary::-webkit-details-marker { display: none; }
  #project-tabs-root .pt-settings[open] summary { color: var(--text, #ddd); border-color: #2a3145; }
  #project-tabs-root .pt-settings .pt-settings-menu {
    position: absolute; right: 0; top: calc(100% + 4px); background: #131722;
    border: 1px solid #1f2330; border-radius: 4px; min-width: 320px;
    max-width: 480px; padding: 6px 0; z-index: 100; display: flex; flex-direction: column;
  }
  #project-tabs-root .pt-settings .pt-settings-menu .item {
    color: var(--text, #ddd); text-decoration: none; padding: 8px 14px;
    font-size: 13px; background: transparent; border: 0; text-align: left;
    cursor: pointer; font-family: inherit; display: block; width: 100%;
  }
  #project-tabs-root .pt-settings .pt-settings-menu .item:hover { background: #1a1f2c; }
  #project-tabs-root .pt-settings .pt-settings-menu .item.danger { color: #ff9e9e; }
  #project-tabs-root .pt-settings .pt-settings-menu .item.danger:hover { background: #2c1a1a; }
  #project-tabs-root .pt-settings .pt-settings-menu hr {
    border: 0; border-top: 1px solid #1f2330; margin: 4px 0;
  }
  #project-tabs-root .tab-bar {
    /* v1.12.1 — overflow-x: auto 가 자식 absolute 의 .more-menu 까지 잘랐던 회귀
       해소. 내부 .tab-scroll 만 가로 스크롤, more-dropdown 은 외부에 둬서
       absolute child 가 잘리지 않게. */
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
  #project-tabs-root .more-dropdown {
    position: relative; flex-shrink: 0; border-left: 1px solid #1f2330;
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
  /* v1.29.0 — 더보기 메뉴 안 overflow 탭 버튼 (a.more-item 과 동일 외형, button reset). */
  #project-tabs-root button.more-item {
    display: block; width: 100%; text-align: left; background: transparent;
    border: 0; cursor: pointer; font-family: inherit;
  }
  #project-tabs-root button.more-item.active { color: var(--accent, #6aa9ff); }
  #project-tabs-root .more-menu hr {
    border: 0; border-top: 1px solid #1f2330; margin: 4px 0;
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
    <span class="spacer"></span>
    <details class="pt-settings">
      <summary>⚙ ${esc(t("tabs.settings.label"))}</summary>
      <div class="pt-settings-menu">
        <!-- v1.13.1 — 메타데이터는 헤더 chip 대신 이 드롭다운 상단에. -->
        <div class="meta-block">
          <dl>
            <dt>${esc(t("projects.detail.package"))}</dt><dd>${esc(project.packageName)}</dd>
            <dt>${esc(t("projects.detail.module"))}</dt><dd>${esc(project.moduleName)}</dd>
            <dt>${esc(t("projects.detail.source"))}</dt><dd>${esc(project.sourcePath)}</dd>
            <dt>${esc(t("projects.detail.debugTask"))}</dt><dd>${esc(project.debugTask)}</dd>
            <dt>${esc(t("projects.lastBuild"))}</dt><dd>${esc(project.lastBuildStatus ?: "-")}</dd>
            <dt>${esc(t("projects.detail.updated"))}</dt><dd>${esc(project.updatedAt)}</dd>
          </dl>
        </div>
        <a href="/projects/${esc(project.id)}/zip" class="item">${esc(t("projects.detail.zip"))}</a>
        <a href="/projects/${esc(project.id)}/env-files" target="${esc("tab-env-files")}" class="item">${esc(t("projects.detail.envFiles"))}</a>
        <hr>
        <form method="post" action="/projects/${esc(project.id)}/delete" style="margin:0"
              onsubmit="return confirm(${jsLit(t("projects.detail.deleteConfirm"))})">
          ${CsrfTokens.hiddenInput(csrf)}
          <button type="submit" class="item danger">${esc(t("projects.detail.delete"))}</button>
        </form>
      </div>
    </details>
  </div>
  $flashHtml
  <div class="tab-bar" role="tablist">
    <div class="tab-scroll">
      $tabBtns
    </div>
    <details class="more-dropdown">
      <summary data-more-label="${esc(t("tabs.more.label"))}">${esc(t("tabs.more.label"))} ▾</summary>
      <div class="more-menu">$moreLinks</div>
    </details>
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

    /** JS literal context 안전. esc 보다 더 보수적. */
    private fun jsLit(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("<", "\\u003C").replace(">", "\\u003E")
            .replace("\n", "\\n") + "\""
}
