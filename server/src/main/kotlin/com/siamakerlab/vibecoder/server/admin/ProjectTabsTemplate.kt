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
        // v1.65.0 — 스토어 자산(앱 아이콘/그래픽/스크린샷) 탭.
        Tab("assets", "tabs.assets", "/assets", "tab-assets"),
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
        Tab("agentDefs", "tabs.agentDefs", "/agent-defs", "tab-agent-defs"),
        Tab("mcpProject", "tabs.mcpProject", "/mcp", "tab-mcp-project"),
        Tab("skills", "tabs.skills", "/skills", "tab-skills"),
        Tab("plugins", "tabs.plugins", "/plugins", "tab-plugins"),
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
        /** v1.49.0 — 헤더 프로젝트명 콤보박스(빠른 전환)용 전체 프로젝트 목록. */
        allProjects: List<ProjectDto> = emptyList(),
        /**
         * v1.56.0 — projectId → 상태 키 ("responding" | "ready" | "idle"). 콤보박스
         * 각 항목 좌측 상태칩(`.pstat`)에 사용. 누락 시 "idle". 목록 페이지와 동일 체계.
         */
        projectStatuses: Map<String, String> = emptyMap(),
        /** v1.50.0 — 우측 overview rail 데이터. */
        keystoreReady: Boolean = false,
        tokensTotal: Long = 0,
        cacheHitRate: Double? = null,
        promptCount: Long = 0,
        /** 최신순(latest-first) user 프롬프트 본문 — 최대 7개. */
        recentPrompts: List<String> = emptyList(),
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml

        // v1.50.0 — 우측 고정 overview rail (모든 탭 공통, 부모 레이어라 iframe 스크롤과 무관).
        val keystoreHtml = if (keystoreReady)
            """<span class="pt-ks ok">✓ ${esc(t("tabs.rail.keystore.ready"))}</span>"""
        else """<span class="pt-ks miss">✗ ${esc(t("tabs.rail.keystore.missing"))}</span>"""
        val tokensHtml = fmtTokens(tokensTotal) +
            (cacheHitRate?.let { """ <span class="dim" style="font-size:10px">· ${esc(t("tabs.rail.cacheHit"))} ${"%.0f".format(it)}%</span>""" } ?: "")
        val historyHtml = if (recentPrompts.isEmpty())
            """<div class="pt-hist-empty">${esc(t("tabs.rail.history.empty"))}</div>"""
        else recentPrompts.take(7).mapIndexed { i, content ->
            // 0~4 = 불투명, 5번째부터 점점 흐리게.
            val opacity = when (i) { in 0..4 -> "1"; 5 -> "0.55"; else -> "0.32" }
            """<button type="button" class="pt-hist-item" style="opacity:$opacity"
                       data-prompt="${esc(content)}" title="${esc(t("tabs.rail.history.hint"))}"
                >${esc(content)}</button>"""
        }.joinToString("")
        val railHtml = """
  <aside class="pt-rail" aria-label="${esc(t("tabs.rail.overview"))}">
    <div class="pt-rail-card">
      <div class="pt-rail-h">${esc(t("tabs.rail.overview"))}</div>
      <div class="pt-ov">
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.title"))}</span><span class="v">${esc(project.name)}</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.package"))}</span><span class="v mono">${esc(project.packageName)}</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.keystore"))}</span><span class="v">$keystoreHtml</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.tokens"))}</span><span class="v">$tokensHtml</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.prompts"))}</span><span class="v">${promptCount}</span></div>
      </div>
    </div>
    <div class="pt-rail-card pt-hist-card">
      <div class="pt-rail-h">${esc(t("tabs.rail.history"))}</div>
      <div class="pt-hist-list" data-hist-hint="${esc(t("tabs.rail.history.hint"))}">$historyHtml</div>
    </div>
  </aside>
  <button type="button" class="pt-rail-toggle" id="pt-rail-toggle"
          title="${esc(t("tabs.rail.hide"))}" data-hide="${esc(t("tabs.rail.hide"))}" data-show="${esc(t("tabs.rail.show"))}">⟩</button>"""

        // v1.49.0 — 상단 프로젝트명을 <details> 콤보박스로: 클릭 → 다른 프로젝트로 즉시 이동.
        // 현재 프로젝트는 active 표시. 항목이 많으면 상단 필터 input 으로 즉시 검색(project-tabs.js).
        // v1.60.0 — 사용자 정의 순서(sort_order)를 그대로 — allProjects 는 이미 그 순서.
        val switcherItems = allProjects
            .joinToString("") { pr ->
                val active = pr.id == project.id
                // v1.56.0 — 좌측 상태칩 (목록 페이지와 동일 .pstat / data-state, /ws/projects 로 실시간 patch).
                // v1.60.0 — 3-state (응답중/대기중/중지됨). 누락 시 ready.
                val state = projectStatuses[pr.id] ?: "ready"
                val chip = """<span class="pstat pstat-$state" data-pid="${esc(pr.id)}" data-state="$state"
                                    title="${esc(t("projects.status.$state"))}">${esc(t("projects.status.$state"))}</span>"""
                """<a href="/projects/${esc(pr.id)}" class="pt-switch-item${if (active) " active" else ""}"
                      data-name="${esc((pr.name + " " + pr.id).lowercase())}">
                     $chip
                     <span class="pt-si-name">${esc(pr.name)}</span>
                     <span class="pt-si-id">${esc(pr.id)}</span>
                   </a>"""
            }
        val projectSwitcher = """
    <details class="pt-switcher">
      <summary title="${esc(t("tabs.switch.title"))}">
        <span class="pt-proj-name">${esc(project.name)}</span><span class="pt-caret">▾</span>
      </summary>
      <div class="pt-switcher-menu">
        <input type="text" class="pt-switch-filter" placeholder="${esc(t("tabs.switch.filter"))}" autocomplete="off" spellcheck="false">
        <div class="pt-switch-list">
          $switcherItems
        </div>
        <hr>
        <a href="/projects" class="pt-switch-item pt-switch-all">${esc(t("tabs.switch.all"))}</a>
      </div>
    </details>"""

        // primary 탭만 상단 탭바에. overflow 는 더보기 드롭다운에 (아래 moreLinks).
        val tabBtns = PRIMARY_TABS.joinToString("") { tab ->
            """<button type="button" class="tab-btn" data-tab-btn="${esc(tab.id)}"
                       title="${esc(t(tab.labelKey))}">${esc(t(tab.labelKey))}</button>"""
        }
        // v1.47.0 — 콘솔 우선 로딩 + 나머지 백그라운드 프리로딩.
        //  - console: HTML 파싱 즉시 fetch (eager + fetchpriority=high). JS 대기 없이 가장 빨리 표시.
        //  - 나머지: src 대신 data-src 로 둬 진입 시 fetch 안 함(콘솔과 자원 경쟁 제거).
        //    project-tabs.js 가 콘솔 load 후 data-src 를 순차로 src 에 옮겨 백그라운드 프리로딩.
        //    프리로드 전 클릭 시엔 activate() 가 즉시 on-demand 로드.
        val tabPanes = TABS.joinToString("\n") { tab ->
            val src = "/projects/${esc(project.id)}${tab.urlSuffix}"
            val srcAttr = if (tab.id == "console")
                """src="${esc(src)}" loading="eager" fetchpriority="high""""
            else
                """data-src="${esc(src)}" loading="lazy""""
            """<div class="tab-pane" data-tab="${esc(tab.id)}">
                <iframe class="tab-frame" $srcAttr name="${esc(tab.frameName)}"
                        title="${esc(t(tab.labelKey))}"
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
  /* v1.49.0 — 프로젝트명 콤보박스(빠른 전환). */
  #project-tabs-root .pt-switcher { position: relative; }
  #project-tabs-root .pt-switcher > summary {
    list-style: none; cursor: pointer; font-size: 16px; font-weight: 600;
    color: var(--text, #ddd); display: inline-flex; align-items: center; gap: 5px;
    padding: 2px 4px; border-radius: 4px;
  }
  #project-tabs-root .pt-switcher > summary::-webkit-details-marker { display: none; }
  #project-tabs-root .pt-switcher > summary:hover { background: #1a1f2c; }
  #project-tabs-root .pt-switcher .pt-caret { color: var(--text-dim, #888); font-size: 11px; }
  #project-tabs-root .pt-switcher[open] > summary { color: var(--accent, #6aa9ff); }
  #project-tabs-root .pt-switcher-menu {
    position: absolute; left: 0; top: calc(100% + 6px); z-index: 200;
    background: #131722; border: 1px solid #2a3145; border-radius: 6px;
    min-width: 280px; max-width: 440px; padding: 8px;
    box-shadow: 0 8px 24px rgba(0,0,0,0.45);
  }
  #project-tabs-root .pt-switch-filter {
    width: 100%; box-sizing: border-box; padding: 6px 8px; margin-bottom: 6px;
    background: #0c0f17; border: 1px solid #2a3145; border-radius: 4px;
    color: var(--text, #ddd); font-size: 13px; font-family: inherit;
  }
  #project-tabs-root .pt-switch-list {
    max-height: 320px; overflow-y: auto; display: flex; flex-direction: column;
  }
  #project-tabs-root .pt-switch-item {
    display: flex; justify-content: space-between; align-items: center; gap: 10px;
    padding: 7px 9px; border-radius: 4px; text-decoration: none;
    color: var(--text, #ddd); font-size: 13px;
  }
  #project-tabs-root .pt-switch-item:hover { background: #1a1f2c; }
  #project-tabs-root .pt-switch-item.active {
    background: rgba(106,169,255,0.12); color: var(--accent, #6aa9ff);
  }
  #project-tabs-root .pt-switch-item .pt-si-id {
    color: var(--text-dim, #888); font-size: 11px;
    font-family: ui-monospace, Menlo, monospace; flex-shrink: 0;
  }
  /* v1.56.0 — 콤보 항목: [상태칩] 이름(신축) [id]. 이름이 남는 폭을 차지하고 id 는 우측. */
  #project-tabs-root .pt-switch-item .pt-si-name {
    flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  #project-tabs-root .pt-switch-item .pstat {
    flex: none; font-size: 11px; padding: 2px 8px; gap: 5px;
  }
  /* active 항목이라도 칩 색(warn/ok/dim)은 유지 — accent 색 상속 차단. */
  #project-tabs-root .pt-switch-item.active .pstat-responding { color: var(--warn, #ffa94d); }
  #project-tabs-root .pt-switch-item.active .pstat-ready { color: var(--ok, #69db7c); }
  #project-tabs-root .pt-switch-item.active .pstat-stopped { color: var(--danger, #ff6b6b); }
  #project-tabs-root .pt-switch-item.active .pstat-idle { color: var(--text-dim, #888); }
  #project-tabs-root .pt-switcher-menu hr {
    border: 0; border-top: 1px solid #1f2330; margin: 6px 0;
  }
  #project-tabs-root .pt-switch-all { color: var(--text-dim, #aaa); }
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

  /* v1.50.0 — 우측 고정 overview rail. tab-content(position:relative) 안에서 우측 오버레이.
     폭은 디스플레이 크기에 따라 동적(clamp). iframe 은 풀폭 유지(스크롤바 최우측) + JS 가
     실제 rail 폭만큼 .content padding-right 주입 → 콘텐츠가 rail 뒤로 가려지지 않음. */
  #project-tabs-root .pt-rail {
    position: absolute; top: 0; right: 0; bottom: 0; z-index: 6;
    width: clamp(248px, 22vw, 360px);
    background: #0d1018; border-left: 1px solid #1f2330;
    overflow-y: auto; overflow-x: hidden;
    padding: 12px; box-sizing: border-box;
    display: flex; flex-direction: column; gap: 12px;
  }
  #project-tabs-root[data-rail="hidden"] .pt-rail { display: none; }
  #project-tabs-root .pt-rail-card {
    background: #131722; border: 1px solid #1f2330; border-radius: 8px;
    padding: 10px 12px;
  }
  #project-tabs-root .pt-rail-h {
    font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em;
    color: #5a6175; margin-bottom: 8px;
  }
  #project-tabs-root .pt-ov-row {
    display: flex; justify-content: space-between; gap: 10px; align-items: baseline;
    padding: 4px 0; font-size: 12px; border-top: 1px solid #161b26;
  }
  #project-tabs-root .pt-ov-row:first-child { border-top: 0; }
  #project-tabs-root .pt-ov-row .k { color: #5a6175; flex-shrink: 0; }
  #project-tabs-root .pt-ov-row .v { color: var(--text, #ddd); text-align: right; word-break: break-all; }
  #project-tabs-root .pt-ov-row .v.mono { font-family: ui-monospace, Menlo, monospace; font-size: 11px; }
  #project-tabs-root .pt-ks.ok { color: var(--ok, #69db7c); }
  #project-tabs-root .pt-ks.miss { color: var(--text-dim, #888); }
  /* 프롬프트 히스토리 카드 — 최신 위, 2줄 축약, 5개 밑으로 점점 흐리게(inline opacity).
     v1.50.2 — flex:1 제거: 카드를 내용 높이로(이전엔 남는 높이를 채워 하단 빈 여백으로 길어짐).
     rail 하단 빈 공간은 카드가 아닌 배경. 항목이 많아도 list 자체 max-height 로만 스크롤. */
  #project-tabs-root .pt-rail { justify-content: flex-start; }
  #project-tabs-root .pt-hist-card { display: flex; flex-direction: column; flex: 0 0 auto; }
  #project-tabs-root .pt-hist-list {
    max-height: 50vh; overflow-y: auto; display: flex; flex-direction: column; gap: 6px;
  }
  #project-tabs-root .pt-hist-item {
    text-align: left; background: #0c0f17; border: 1px solid #1f2330; border-radius: 6px;
    color: var(--text, #ddd); font: inherit; font-size: 12px; line-height: 1.35;
    padding: 7px 9px; cursor: pointer; width: 100%;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  }
  #project-tabs-root .pt-hist-item:hover { background: #1a1f2c; border-color: #2a3145; opacity: 1 !important; }
  #project-tabs-root .pt-hist-empty { color: #5a6175; font-size: 12px; padding: 6px 2px; }
  /* rail 접기/펼치기 토글 — rail 좌측 가장자리 손잡이. */
  /* v1.50.1 — 토글을 rail 세로 중앙에 배치(이전 top:8px 는 콘솔 헤더의 "새 세션" 버튼과
     겹쳐 오클릭 위험). transform 으로 세로 중앙 정렬. */
  #project-tabs-root .pt-rail-toggle {
    position: absolute; top: 50%; transform: translateY(-50%); z-index: 7;
    right: clamp(248px, 22vw, 360px);
    background: #131722; color: var(--text-dim, #888);
    border: 1px solid #1f2330; border-right: 0; border-radius: 6px 0 0 6px;
    width: 20px; height: 48px; cursor: pointer; font-size: 12px; line-height: 1;
    display: flex; align-items: center; justify-content: center;
    box-shadow: -2px 0 6px rgba(0,0,0,0.25);
  }
  #project-tabs-root .pt-rail-toggle:hover { color: var(--text, #ddd); background: #1a1f2c; }
  #project-tabs-root[data-rail="hidden"] .pt-rail-toggle { right: 0; border-right: 1px solid #1f2330; border-radius: 6px 0 0 6px; }
  /* 좁은 화면: rail 자동 숨김(JS 가 measured width 0 으로 패딩 제거). */
  @media (max-width: 760px) {
    #project-tabs-root .pt-rail, #project-tabs-root .pt-rail-toggle { display: none; }
  }
</style>

<div id="project-tabs-root" data-project-id="${esc(project.id)}" data-rail="shown">
  <div class="pt-header">
    <a href="/projects" style="color:var(--text-dim);text-decoration:none;font-size:12px">← ${esc(t("tabs.backToList"))}</a>
    $projectSwitcher
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
$railHtml
  </div>
</div>

<script src="/static/project-tabs.js?v=1.59.2" defer></script>
<!-- v1.56.0 — 콤보박스 상태칩 실시간 동기. 목록 페이지와 동일하게 `/ws/projects`
     (단방향) 의 ProjectBusyChanged 로 responding↔ready patch. -->
<script>
(function() {
  // v1.60.0 — 3-state. ProjectBusyChanged.state 우선, 없으면 busy 폴백.
  var LABELS = {
    responding: ${jsLit(t("projects.status.responding"))},
    ready: ${jsLit(t("projects.status.ready"))},
    stopped: ${jsLit(t("projects.status.stopped"))}
  };
  function patch(pid, state) {
    if (!state) return;
    var el = document.querySelector('.pstat[data-pid="' + (window.CSS && CSS.escape ? CSS.escape(pid) : pid) + '"]');
    if (!el) return;
    el.className = 'pstat pstat-' + state;
    el.dataset.state = state;
    el.textContent = LABELS[state] || state;
    el.title = LABELS[state] || state;
  }
  var ws = null;
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects');
    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        if (f.type === 'project_busy_changed' && f.projectId != null) {
          patch(f.projectId, f.state || (f.busy ? 'responding' : 'ready'));
        }
      } catch (e) { /* ignore malformed frame */ }
    };
    ws.onclose = function() { setTimeout(connect, 5000); };
    ws.onerror = function() { try { ws.close(); } catch (e) {} };
  }
  connect();
})();
</script>
""",
        )
    }

    /** v1.50.0 — 토큰 수 사람이 읽기 쉬운 단위(K/M)로. */
    private fun fmtTokens(n: Long): String = when {
        n <= 0L -> "0"
        n >= 1_000_000L -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000L -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
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
