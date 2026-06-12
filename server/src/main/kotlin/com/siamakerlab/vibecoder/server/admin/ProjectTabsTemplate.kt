package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState

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
 *  3. inner page 는 `?_embed=1`(+ 브라우저 표준 Sec-Fetch-Dest:iframe)로 요청되어 서버가
 *     nav/탭바 크롬을 **처음부터 미렌더**([AdminTemplates.shell] embed=true)하고 layout 도
 *     no-nav(1-column) — 시각적으로 단일 페이지. (v1.72.0 — 과거 onload JS hide 방식 대체.)
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
        // v1.108.0 — 키스토어/AdMob 탭을 '더보기'에서 빌드 우측 상단 탭으로 이동(사용자 요청).
        Tab("keystore", "tabs.keystore", "/keystore", "tab-keystore"),
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
        // v1.116.0 — 품질·접근성 검사 (Android Lint) 탭.
        Tab("quality", "tabs.quality", "/quality", "tab-quality"),
        Tab("wrapper", "tabs.wrapper", "/wrapper", "tab-wrapper"),
        Tab("automation", "tabs.automation", "/automation", "tab-automation"),
        Tab("envFiles", "tabs.envFiles", "/env-files", "tab-env-files"),
        Tab("claudeMd", "tabs.claudeMd", "/claude-md", "tab-claude-md"),
        Tab("agentDefs", "tabs.agentDefs", "/agent-defs", "tab-agent-defs"),
        Tab("mcpProject", "tabs.mcpProject", "/mcp", "tab-mcp-project"),
        Tab("skills", "tabs.skills", "/skills", "tab-skills"),
        Tab("plugins", "tabs.plugins", "/plugins", "tab-plugins"),
        // v1.132.0 — 프로젝트 백업(소스+키스토어+문서+설정 → tar.gz 다운로드). 복원은 설정→백업.
        Tab("backup", "tabs.backup", "/backup", "tab-backup"),
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
        /** v1.108.4 — AdMob 준비 상태(`<pkg>-admob.properties` 존재). 개요카드 키스토어 하단 행. */
        admobReady: Boolean = false,
        tokensTotal: Long = 0,
        cacheHitRate: Double? = null,
        promptCount: Long = 0,
        /** 최신순(latest-first) user 프롬프트 본문 — v1.134.0 부터 전체(상한 1000). */
        recentPrompts: List<String> = emptyList(),
        /** v1.108.0 — 자동 /compact ON 여부(기본 ON). 컨텍스트 카드 '자동' 체크박스 초기값. */
        autoCompact: Boolean = true,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml

        // v1.50.0 — 우측 고정 overview rail (모든 탭 공통, 부모 레이어라 iframe 스크롤과 무관).
        val keystoreHtml = if (keystoreReady)
            """<span class="pt-ks ok">✓ ${esc(t("tabs.rail.keystore.ready"))}</span>"""
        else """<span class="pt-ks miss">✗ ${esc(t("tabs.rail.keystore.missing"))}</span>"""
        // v1.108.4 — AdMob 준비 상태(키스토어 하단 행). admob.properties 존재 = 준비됨.
        val admobHtml = if (admobReady)
            """<span class="pt-ks ok">✓ ${esc(t("tabs.rail.admob.ready"))}</span>"""
        else """<span class="pt-ks miss">✗ ${esc(t("tabs.rail.admob.missing"))}</span>"""
        val tokensHtml = fmtTokens(tokensTotal) +
            (cacheHitRate?.let { """ <span class="dim" style="font-size:10px">· ${esc(t("tabs.rail.cacheHit"))} ${"%.0f".format(it)}%</span>""" } ?: "")
        // v1.134.0 — 전체 프롬프트(최신 위) 스크롤 목록. 항목별 inline opacity 램프 제거 —
        // "5개 선명 + 2개 페이드아웃" 은 목록 높이(7행) + 하단 마스크 그라데이션(CSS)이 담당.
        // 한 줄 축약(.pt-hist-item CSS)이라 표시 본문/title 은 절단, 클릭 채움용
        // data-prompt 만 원문 유지. title = 본문 미리보기 + 클릭 안내.
        val historyHtml = if (recentPrompts.isEmpty())
            """<div class="pt-hist-empty">${esc(t("tabs.rail.history.empty"))}</div>"""
        else recentPrompts.joinToString("") { content ->
            val preview = if (content.length > 300) content.take(300) + "…" else content
            """<button type="button" class="pt-hist-item"
                       data-prompt="${esc(content)}"
                       title="${esc(preview + "\n\n" + t("tabs.rail.history.hint"))}"
                >${esc(preview)}</button>"""
        }
        val railHtml = """
  <aside class="pt-rail" aria-label="${esc(t("tabs.rail.overview"))}">
    <div class="pt-rail-card" data-card="overview">
      <div class="pt-rail-h">${esc(t("tabs.rail.overview"))}</div>
      <div class="pt-ov">
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.title"))}</span><span class="v">${esc(project.name)}</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.package"))}</span><span class="v mono">${esc(project.packageName)}</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.keystore"))}</span><span class="v">$keystoreHtml</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.admob"))}</span><span class="v">$admobHtml</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.tokens"))}</span><span class="v">$tokensHtml</span></div>
        <div class="pt-ov-row"><span class="k">${esc(t("tabs.rail.prompts"))}</span><span class="v">${promptCount}</span></div>
      </div>
    </div>
    <!-- v1.106.3 — 컨텍스트 점유율 카드(독립, 프롬프트 히스토리 카드 위). 우상단 /compact 버튼.
         콘솔 iframe 이 vibe:context-usage postMessage 로 매 turn 미터 갱신(project-tabs.js). -->
    <div class="pt-rail-card pt-ctx-card" data-card="context">
      <div class="pt-rail-h pt-ctx-head">
        <span>${esc(t("tabs.rail.context"))}</span>
        <span class="pt-ctx-actions">
          <button type="button" class="pt-compact-btn" id="pt-compact-btn"
                  title="${esc(t("tabs.rail.compact.hint"))}">/compact</button>
          <label class="pt-autocompact" title="${esc(t("tabs.rail.autocompact.hint"))}">
            <input type="checkbox" id="pt-autocompact"${if (autoCompact) " checked" else ""}>${esc(t("tabs.rail.autocompact"))}
          </label>
        </span>
      </div>
      <div id="pt-ctx-empty" class="pt-ctx-empty">${esc(t("tabs.rail.context.empty"))}</div>
      <div id="pt-ctx-meter" class="pt-ctx-meter" hidden
           title="대화 컨텍스트 점유율 — 윈도우 한도 대비 사용/남음. 클수록 매 turn 비용↑.">
        <div class="pt-ctx-top">
          <span class="pt-ctx-text"><b id="pt-ctx-used">–</b> / <span id="pt-ctx-limit">–</span> · <span id="pt-ctx-pct">0%</span></span>
        </div>
        <div class="pt-ctx-bar">
          <div class="ctx-seg ctx-seg-read"></div>
          <div class="ctx-seg ctx-seg-create"></div>
          <div class="ctx-seg ctx-seg-input"></div>
        </div>
        <div class="pt-ctx-sub">남음 <span id="pt-ctx-free">–</span> <span class="pt-ctx-legend"><i class="ctx-seg-read"></i>재사용 <i class="ctx-seg-create"></i>신규 <i class="ctx-seg-input"></i>입력</span></div>
      </div>
    </div>
    <!-- v1.111.0 — Todo 요약 + 백그라운드 작업 카드(콘솔에서 이동, 컨텍스트 카드 하단). 콘솔 iframe 이
         vibe:todo / vibe:bgtasks postMessage 로 스냅샷 전달, project-tabs.js 가 렌더. 내용 있을 때만 표시. -->
    <div class="pt-rail-card pt-todo-card" data-card="todo" hidden>
      <div class="pt-rail-h">📋 ${esc(t("console.todo.title"))} <span id="pt-todo-summary" class="dim"></span></div>
      <ul id="pt-todo-list" class="pt-todo-list"></ul>
    </div>
    <div class="pt-rail-card pt-bg-card" data-card="bgtasks" hidden>
      <div class="pt-rail-h">⚙ ${esc(t("console.bgtasks.title"))} <span id="pt-bg-count" class="dim"></span></div>
      <div id="pt-bg-list" class="pt-bg-list"></div>
    </div>
    <div class="pt-rail-card pt-hist-card" data-card="history">
      <div class="pt-rail-h">${esc(t("tabs.rail.history"))}</div>
      <div class="pt-hist-list" data-hist-hint="${esc(t("tabs.rail.history.hint"))}">$historyHtml</div>
    </div>
    <!-- v1.109.0 — 프롬프트 자동화 카드(콘솔 인라인 패널에서 이동, 메모 위). 자체 입력으로
         /claude/automation/* REST 직접 실행 + 활성 시 status 폴링. 진행 프레임은 콘솔 iframe 이
         vibe:automation postMessage 로 즉시 전달(project-tabs.js initAutomation). -->
    <div class="pt-rail-card pt-auto-card" data-card="automation">
      <div class="pt-rail-h">🤖 ${esc(t("console.automation.title"))}
        <span id="pt-auto-badge" class="pt-auto-badge" data-running="${esc(t("console.automation.running"))}"></span>
      </div>
      <!-- v1.131.0 — 예약 보내기(one-shot). 카드 상단 진입점 → 부모 레이어 모달(pt-sched-modal). -->
      <!-- v1.136.0 — 일괄 보내기(broadcast). 우측 버튼 → 공용 모달(vb-bcast-modal, BroadcastModalTemplate). -->
      <div style="display:flex;gap:6px">
        <button type="button" id="pt-sched-open" class="pt-auto-btn pt-sched-open">⏰ ${esc(t("console.schedule.open"))}</button>
        <button type="button" class="pt-auto-btn pt-sched-open vb-bcast-open">📢 ${esc(t("console.broadcast.open"))}</button>
      </div>
      <div id="pt-auto-running" class="pt-auto-running" hidden>
        <div class="pt-auto-prog"><span class="pt-auto-dot"></span><span id="pt-auto-progress"></span></div>
      </div>
      <div id="pt-auto-idle" class="pt-auto-idle">
        <div class="pt-auto-modes">
          <label><input type="radio" name="pt-auto-mode" value="repeat" checked> ${esc(t("console.automation.repeat"))}</label>
          <label><input type="radio" name="pt-auto-mode" value="sequence"> ${esc(t("console.automation.sequence"))}</label>
          <label class="pt-auto-count">${esc(t("console.automation.count"))}<input type="number" id="pt-auto-count" min="1" max="200" value="20"></label>
        </div>
        <textarea id="pt-auto-prompts" rows="3" placeholder="${esc(t("console.automation.placeholder"))}"></textarea>
        <div class="pt-auto-actions">
          <button type="button" id="pt-auto-start" class="pt-auto-btn primary">${esc(t("console.automation.start"))}</button>
          <select id="pt-auto-preset" hidden></select>
          <button type="button" id="pt-auto-preset-start" class="pt-auto-btn" hidden>${esc(t("console.automation.presetStart"))}</button>
          <a href="/projects/${esc(project.id)}/automation/prompts" target="_top" class="pt-auto-manage">${esc(t("console.automation.manage"))}</a>
        </div>
      </div>
    </div>
    <!-- v1.91.0 — 메모 위젯 (전역 + 이 프로젝트). 프롬프트 히스토리 하단. -->
    <div class="pt-rail-card pt-memo-card" data-card="memo">
      <div class="pt-rail-h pt-memo-head">
        <span>${esc(t("tabs.rail.memos"))}</span>
        <button type="button" class="pt-memo-add" id="pt-memo-add"
                title="${esc(t("memos.new"))}" aria-label="${esc(t("memos.new"))}">＋</button>
      </div>
      <div class="pt-memo-list" id="pt-memo-list"></div>
    </div>
  </aside>
  <button type="button" class="pt-rail-toggle" id="pt-rail-toggle"
          title="${esc(t("tabs.rail.hide"))}" data-hide="${esc(t("tabs.rail.hide"))}" data-show="${esc(t("tabs.rail.show"))}">⟩</button>
  <!-- v1.91.0 — 메모 보기/편집 미니창(다이얼로그). 부모 레이어(fixed) — iframe 무관. -->
  <div class="pt-memo-modal" id="pt-memo-modal" hidden>
    <div class="pt-memo-box" role="dialog" aria-modal="true">
      <h2 id="pt-memo-title"></h2>
      <div class="pt-memo-field">
        <label for="pt-memo-scope">${esc(t("memos.label.scope"))}</label>
        <select id="pt-memo-scope">
          <option value="__project__">${esc(t("memos.scope.thisProject"))}</option>
          <option value="">${esc(t("memos.scope.global"))}</option>
        </select>
      </div>
      <div class="pt-memo-field">
        <label for="pt-memo-content">${esc(t("memos.label.content"))}</label>
        <textarea id="pt-memo-content" maxlength="16000" placeholder="${esc(t("memos.placeholder"))}"></textarea>
      </div>
      <div class="pt-memo-err" id="pt-memo-err"></div>
      <div class="pt-memo-foot">
        <button type="button" class="pt-memo-btn danger" id="pt-memo-del" hidden>${esc(t("memos.delete"))}</button>
        <span class="pt-memo-spacer"></span>
        <button type="button" class="pt-memo-btn ghost" id="pt-memo-cancel">${esc(t("memos.close"))}</button>
        <button type="button" class="pt-memo-btn primary" id="pt-memo-save">${esc(t("memos.save"))}</button>
      </div>
    </div>
  </div>
  <!-- v1.131.0 — 프롬프트 예약 보내기(one-shot) 미니창. 부모 레이어(fixed) — iframe 무관. -->
  <div class="pt-sched-modal" id="pt-sched-modal" hidden>
    <div class="pt-sched-box" role="dialog" aria-modal="true">
      <h2>${esc(t("console.schedule.title"))}</h2>
      <p class="pt-sched-sub">${esc(t("console.schedule.hint"))}</p>
      <textarea id="pt-sched-prompt" rows="3" placeholder="${esc(t("console.schedule.placeholder"))}"></textarea>
      <div class="pt-sched-trig">
        <label><input type="radio" name="pt-sched-trig" value="time" checked> ${esc(t("console.schedule.time"))}</label>
        <label><input type="radio" name="pt-sched-trig" value="session_reset"> ${esc(t("console.schedule.sessionReset"))}</label>
        <label><input type="radio" name="pt-sched-trig" value="weekly_reset"> ${esc(t("console.schedule.weeklyReset"))}</label>
      </div>
      <div id="pt-sched-time" class="pt-sched-time">
        <label><input type="radio" name="pt-sched-when" value="in" checked> ${esc(t("console.schedule.relative"))}</label>
        <span id="pt-sched-in" class="pt-sched-in">
          <input type="number" id="pt-sched-amt" min="1" max="10000" value="30">
          <select id="pt-sched-unit">
            <option value="minutes">${esc(t("console.schedule.minutes"))}</option>
            <option value="hours">${esc(t("console.schedule.hours"))}</option>
          </select>
        </span>
        <label><input type="radio" name="pt-sched-when" value="at"> ${esc(t("console.schedule.exact"))}</label>
        <input type="datetime-local" id="pt-sched-at" hidden>
      </div>
      <p id="pt-sched-reset-hint" class="pt-sched-hint" hidden>${esc(t("console.schedule.resetHint"))}</p>
      <div class="pt-sched-err" id="pt-sched-err"></div>
      <div class="pt-rail-h pt-sched-listh">${esc(t("console.schedule.list"))}</div>
      <div id="pt-sched-list" class="pt-sched-list"
           data-empty="${esc(t("console.schedule.empty"))}"
           data-cancel="${esc(t("console.schedule.cancel"))}"
           data-confirm="${esc(t("console.schedule.confirmCancel"))}"></div>
      <div class="pt-sched-foot">
        <button type="button" class="pt-memo-btn ghost" id="pt-sched-close">${esc(t("console.schedule.close"))}</button>
        <span class="pt-memo-spacer"></span>
        <button type="button" class="pt-memo-btn primary" id="pt-sched-add">${esc(t("console.schedule.add"))}</button>
      </div>
    </div>
  </div>
  ${BroadcastModalTemplate.render(lang, preselectId = project.id)}"""

        // v1.49.0 — 상단 프로젝트명을 <details> 콤보박스로: 클릭 → 다른 프로젝트로 즉시 이동.
        // 현재 프로젝트는 active 표시. 항목이 많으면 상단 필터 input 으로 즉시 검색(project-tabs.js).
        // v1.60.0 — 사용자 정의 순서(sort_order)를 그대로 — allProjects 는 이미 그 순서.
        // v1.128.3 — 유휴 아닌 busy(responding/waiting) 프로젝트를 상단으로(같은 그룹 내 sort_order
        // 유지 — stable sort). JS patch 가 실시간 상태 변경 시 busy 전환 항목을 추가로 최상단 이동.
        val switcherItems = allProjects
            .sortedByDescending { ProjectState.fromWire(projectStatuses[it.id])?.busy == true }
            .joinToString("") { pr ->
                val active = pr.id == project.id
                // v1.56.0 — 좌측 상태칩 (목록 페이지와 동일 .pstat / data-state, /ws/projects 로 실시간 patch).
                // v1.60.0 — 5-state (응답중/대기중/중지됨/에러/유휴). 누락 시 READY.
                val state = projectStatuses[pr.id] ?: ProjectState.READY.wire
                val chip = """<span class="pstat pstat-$state" data-pid="${esc(pr.id)}" data-state="$state"
                                    title="${esc(t("projects.status.$state"))}">${esc(t("projects.status.$state"))}</span>"""
                // v1.75.0 — 프로젝트 간 이동 시 이전에 보던 탭과 무관하게 콘솔을 우선 표시
                // (#console hash → project-tabs.js resolveInitialTab 이 localStorage 보다 hash 우선).
                """<a href="/projects/${esc(pr.id)}#console" class="pt-switch-item${if (active) " active" else ""}"
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
        // v1.93.3 — 기본 활성 탭(console)을 서버에서 미리 active 로 마킹 → 첫 페인트부터
        // 올바른 탭이 보인다(이전엔 모든 pane 이 display:none 으로 시작해 JS activate 전까지
        // 빈 화면 → 콘솔이 늦게 튀어나오는 flash). resolveInitialTab 이 localStorage 로 다른
        // 탭을 고르면 reveal gate 뒤에서 전환되므로 사용자에겐 안 보인다.
        val tabBtns = PRIMARY_TABS.joinToString("") { tab ->
            val act = if (tab.id == "console") " active" else ""
            """<button type="button" class="tab-btn$act" data-tab-btn="${esc(tab.id)}"
                       title="${esc(t(tab.labelKey))}">${esc(t(tab.labelKey))}</button>"""
        }
        // v1.47.0 — 콘솔 우선 로딩 + 나머지 백그라운드 프리로딩.
        //  - console: HTML 파싱 즉시 fetch (eager + fetchpriority=high). JS 대기 없이 가장 빨리 표시.
        //  - 나머지: src 대신 data-src 로 둬 진입 시 fetch 안 함(콘솔과 자원 경쟁 제거).
        //    project-tabs.js 가 콘솔 load 후 data-src 를 순차로 src 에 옮겨 백그라운드 프리로딩.
        //    프리로드 전 클릭 시엔 activate() 가 즉시 on-demand 로드.
        val tabPanes = TABS.joinToString("\n") { tab ->
            // v1.72.0 — ?_embed=1: inner page 가 nav/탭바 크롬을 미렌더하도록(서버 분기) 하는
            // 폴백 신호. 주 신호는 브라우저 표준 Sec-Fetch-Dest:iframe(내부 sub-navigation 도 커버).
            val src = "/projects/${esc(project.id)}${tab.urlSuffix}?_embed=1"
            val srcAttr = if (tab.id == "console")
                """src="${esc(src)}" loading="eager" fetchpriority="high""""
            else
                """data-src="${esc(src)}" loading="lazy""""
            val paneAct = if (tab.id == "console") " active" else ""
            """<div class="tab-pane$paneAct" data-tab="${esc(tab.id)}">
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
    /* v1.93.3 — reveal gate: 셸이 올바른 탭/레이아웃으로 한 번에 나타나도록 처음엔 숨김.
       project-tabs.js 가 활성 탭 확정 후 .pt-ready 추가 → 페이드인. JS 실패 시에도
       애니메이션 폴백(2.5s)이 결국 표시하므로 영구 숨김 위험 없음. */
    opacity: 0;
    transition: opacity 0.14s ease;
    animation: ptRevealFallback 0.01s linear 2.5s forwards;
  }
  #project-tabs-root.pt-ready { opacity: 1; animation: none; }
  @keyframes ptRevealFallback { to { opacity: 1; } }
  @media (prefers-reduced-motion: reduce) {
    #project-tabs-root { transition: none; }
  }
  #project-tabs-root .pt-header {
    display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
    /* v1.89.1 — 우상단 알림 벨(fixed, 약 56px)과 ⚙ 설정 버튼이 겹치지 않도록 우측 여백 확보. */
    padding: 10px 64px 8px 16px; border-bottom: 1px solid #1f2330;
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
  /* active 항목이라도 칩 색은 유지 — accent 색 상속 차단. v1.100.0 5-state 일관 팔레트. */
  #project-tabs-root .pt-switch-item.active .pstat-responding { color: var(--ok, #69db7c); }
  #project-tabs-root .pt-switch-item.active .pstat-ready,
  #project-tabs-root .pt-switch-item.active .pstat-idle { color: var(--text-dim, #888); }
  #project-tabs-root .pt-switch-item.active .pstat-waiting { color: var(--wait, #fab005); }
  #project-tabs-root .pt-switch-item.active .pstat-stopped { color: var(--halt, #b197fc); }
  #project-tabs-root .pt-switch-item.active .pstat-error { color: var(--danger, #ff6b6b); }
  /* v1.103.0 — 헤더 콘솔 상태칩(콘솔 busy-badge 미러). 콤보박스 좌측. 5-state 동일 팔레트. */
  #project-tabs-root #console-busy-badge {
    flex: none; font-size: 12px; padding: 3px 10px; border-radius: 12px;
    font-weight: 500; white-space: nowrap; transition: background .2s, color .2s;
  }
  #console-busy-badge[data-state="responding"] { background: rgba(105,219,124,.18); color: #69db7c; }
  #console-busy-badge[data-state="idle"],
  #console-busy-badge[data-state="ready"] { background: rgba(255,255,255,.06); color: var(--text-dim,#888); }
  #console-busy-badge[data-state="waiting"] { background: rgba(250,176,5,.18); color: #fab005; }
  #console-busy-badge[data-state="stopped"] { background: rgba(151,117,250,.18); color: #b197fc; }
  #console-busy-badge[data-state="error"] { background: rgba(255,107,107,.18); color: #ff8787; }
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
  /* v1.110.0 — rail 카드 접기/펼치기. 헤더(.pt-rail-h) 클릭 토글, 카드별 localStorage 영속.
     좌측 캐럿(▾/▸) 표시. 헤더 내 액션 버튼/입력 클릭은 토글 제외(JS). */
  #project-tabs-root .pt-rail-card > .pt-rail-h { cursor: pointer; justify-content: flex-start; }
  #project-tabs-root .pt-rail-card > .pt-rail-h::before {
    content: '▾'; display: inline-block; font-size: 9px; color: #5a6175;
    margin-right: 5px; transition: transform 0.15s ease; flex-shrink: 0;
  }
  #project-tabs-root .pt-rail-card.pt-collapsed > .pt-rail-h::before { transform: rotate(-90deg); }
  #project-tabs-root .pt-rail-card.pt-collapsed > .pt-rail-h { margin-bottom: 0; }
  #project-tabs-root .pt-rail-card.pt-collapsed > :not(.pt-rail-h) { display: none !important; }
  #project-tabs-root .pt-ctx-head .pt-ctx-actions { margin-left: auto; }
  #project-tabs-root .pt-memo-head .pt-memo-add { margin-left: auto; }
  /* 프롬프트 히스토리 카드 — 최신 위, 2줄 축약.
     v1.50.2 — flex:1 제거: 카드를 내용 높이로(이전엔 남는 높이를 채워 하단 빈 여백으로 길어짐).
     rail 하단 빈 공간은 카드가 아닌 배경. 항목이 많아도 list 자체 max-height 로만 스크롤.
     v1.134.0 — 7개 제한 → 전체 프롬프트 스크롤 목록. 약 7행 높이로 고정하고 하단 ~2행을
     마스크 그라데이션으로 페이드아웃(5개 선명 + 2개 흐림). 스크롤이 끝(at-end)이거나
     스크롤할 내용이 없으면 JS 가 .at-end 를 붙여 페이드 제거. */
  #project-tabs-root .pt-rail { justify-content: flex-start; }
  #project-tabs-root .pt-hist-card { display: flex; flex-direction: column; flex: 0 0 auto; }
  /* v1.106.2/.3 — 우측 rail 컨텍스트 점유율 카드 */
  #project-tabs-root .pt-ctx-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  #project-tabs-root .pt-ctx-actions { display: inline-flex; align-items: center; gap: 6px; }
  #project-tabs-root .pt-autocompact { display: inline-flex; align-items: center; gap: 3px; margin: 0; font-size: 11px; color: var(--text-dim,#888); cursor: pointer; user-select: none; }
  #project-tabs-root .pt-autocompact input { cursor: pointer; margin: 0; }
  #project-tabs-root .pt-compact-btn {
    font: inherit; font-size: 11px; line-height: 1; padding: 4px 9px; cursor: pointer;
    background: #1f2937; color: #cbd5e1; border: 1px solid #2b3648; border-radius: 6px;
    font-family: ui-monospace, Menlo, monospace;
  }
  #project-tabs-root .pt-compact-btn:hover:not(:disabled) { background: #253247; border-color: #3a82f6; color: #e6edf5; }
  #project-tabs-root .pt-compact-btn:disabled { opacity: .5; cursor: default; }
  #project-tabs-root .pt-compact-btn.busy { opacity: .6; cursor: progress; }
  #project-tabs-root .pt-ctx-empty { font-size: 11px; color: var(--text-dim,#888); margin-top: 8px; }
  #project-tabs-root .pt-ctx-meter { margin: 8px 0 0; }
  #project-tabs-root .pt-ctx-meter[hidden] { display: none; }
  #project-tabs-root .pt-ctx-top { display: flex; justify-content: space-between; align-items: baseline; gap: 6px; font-size: 10px; color: var(--text-dim,#888); margin-bottom: 4px; }
  #project-tabs-root .pt-ctx-cap { font-weight: 600; }
  #project-tabs-root .pt-ctx-text { font-family: ui-monospace, Menlo, monospace; white-space: nowrap; }
  #project-tabs-root .pt-ctx-bar { height: 7px; border-radius: 5px; background: #1f2330; overflow: hidden; display: flex; }
  #project-tabs-root .pt-ctx-meter .ctx-seg { height: 100%; width: 0; transition: width .3s ease; }
  #project-tabs-root .pt-ctx-meter .ctx-seg-read { background: #3a82f6; }
  #project-tabs-root .pt-ctx-meter .ctx-seg-create { background: #2dd4bf; }
  #project-tabs-root .pt-ctx-meter .ctx-seg-input { background: #ffb86b; }
  #project-tabs-root .pt-ctx-sub { font-size: 10px; color: var(--text-dim,#888); margin-top: 4px; display: flex; justify-content: space-between; gap: 6px; flex-wrap: wrap; }
  #project-tabs-root .pt-ctx-legend { display: inline-flex; gap: 7px; align-items: center; }
  #project-tabs-root .pt-ctx-legend i { display: inline-block; width: 7px; height: 7px; border-radius: 2px; margin-right: 2px; vertical-align: middle; }
  #project-tabs-root .pt-ctx-meter.warn .pt-ctx-text,
  #project-tabs-root .pt-ctx-meter.warn .pt-ctx-sub { color: #ffb86b; }
  #project-tabs-root .pt-ctx-meter.warn .pt-ctx-bar { box-shadow: 0 0 0 1px rgba(255,184,107,.45) inset; }
  #project-tabs-root .pt-hist-list {
    /* 2줄 항목(≈48px) 7개 + gap 6 ≈ 372px — 5개 선명 + 마지막 ~2개는 아래 마스크로 페이드. */
    max-height: 376px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px;
    -webkit-mask-image: linear-gradient(180deg, #000 calc(100% - 104px), transparent 100%);
    mask-image: linear-gradient(180deg, #000 calc(100% - 104px), transparent 100%);
  }
  /* 스크롤 끝이거나 스크롤 불필요(내용 적음) → 페이드 제거(project-tabs.js 가 토글). */
  #project-tabs-root .pt-hist-list.at-end {
    -webkit-mask-image: none; mask-image: none;
  }
  #project-tabs-root .pt-hist-item {
    text-align: left; background: #0c0f17; border: 1px solid #1f2330; border-radius: 6px;
    color: var(--text, #ddd); font: inherit; font-size: 12px; line-height: 1.35;
    padding: 7px 9px; cursor: pointer; width: 100%; flex-shrink: 0;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  }
  #project-tabs-root .pt-hist-item:hover { background: #1a1f2c; border-color: #2a3145; }
  #project-tabs-root .pt-hist-empty { color: #5a6175; font-size: 12px; padding: 6px 2px; }
  /* v1.109.0 — 프롬프트 자동화 카드(메모 위). 콘솔에서 이동 — 우측 오버뷰 별개 기능. */
  #project-tabs-root .pt-auto-card { display: flex; flex-direction: column; flex: 0 0 auto; gap: 8px; }
  #project-tabs-root .pt-auto-modes { display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; }
  #project-tabs-root .pt-auto-modes label { display: inline-flex; align-items: center; gap: 4px; margin: 0; font-size: 12px; color: var(--text, #ddd); cursor: pointer; }
  #project-tabs-root .pt-auto-modes input[type="radio"] { margin: 0; cursor: pointer; }
  #project-tabs-root .pt-auto-count { color: var(--text-dim, #888); }
  #project-tabs-root .pt-auto-count input {
    width: 56px; margin-left: 5px; padding: 3px 5px; font: inherit; font-size: 12px;
    background: #0c0f17; border: 1px solid #1f2330; border-radius: 5px; color: var(--text, #ddd);
  }
  #project-tabs-root .pt-auto-idle textarea {
    width: 100%; box-sizing: border-box; resize: vertical; min-height: 52px;
    background: #0c0f17; border: 1px solid #1f2330; border-radius: 6px; color: var(--text, #ddd);
    font: inherit; font-size: 12px; line-height: 1.4; padding: 7px 9px;
  }
  #project-tabs-root .pt-auto-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
  #project-tabs-root .pt-auto-btn {
    font: inherit; font-size: 12px; line-height: 1; padding: 6px 11px; cursor: pointer;
    background: #1f2937; color: #cbd5e1; border: 1px solid #2b3648; border-radius: 6px;
  }
  #project-tabs-root .pt-auto-btn:hover { background: #26303f; }
  #project-tabs-root .pt-auto-btn.primary { background: #1e40af; color: #fff; border-color: #1e40af; }
  #project-tabs-root .pt-auto-btn.primary:hover { filter: brightness(1.1); }
  #project-tabs-root .pt-auto-btn.danger { background: #7f1d1d; color: #fff; border-color: #991b1b; }
  #project-tabs-root .pt-auto-preset { font: inherit; font-size: 12px; padding: 5px 7px; background: #0c0f17; color: var(--text, #ddd); border: 1px solid #1f2330; border-radius: 5px; max-width: 100%; }
  #project-tabs-root .pt-auto-manage { font-size: 11px; color: var(--text-dim, #888); text-decoration: none; margin-left: auto; }
  #project-tabs-root .pt-auto-manage:hover { color: var(--accent, #6aa9ff); }
  #project-tabs-root .pt-auto-running { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
  /* v1.131.1 — hidden 속성이 위 display:flex(구체성 1,1,0)에 밀려 무력화 → 진행 중이 아닌데도
     점(pt-auto-dot)이 항상 깜빡이던 버그. [hidden] 규칙(1,2,0)으로 명시적으로 숨김. */
  #project-tabs-root .pt-auto-running[hidden] { display: none; }
  #project-tabs-root .pt-auto-prog { display: inline-flex; align-items: center; gap: 7px; font-size: 12px; color: #6aa9ff; min-width: 0; }
  #project-tabs-root .pt-auto-prog #pt-auto-progress { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #project-tabs-root .pt-auto-dot {
    flex-shrink: 0; width: 8px; height: 8px; border-radius: 50%; background: #6aa9ff;
    animation: vibe-busy-pulse 1.4s ease-in-out infinite;
  }
  /* vibe-busy-pulse 는 콘솔 iframe(WebProjectTemplates)에만 정의돼 부모 스코프엔 없으므로 여기서도 정의. */
  @keyframes vibe-busy-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
  /* v1.131.0 — 자동화 사용중 인디케이터(badge) + 예약 보내기 진입 버튼. */
  #project-tabs-root .pt-auto-badge { font-size: 11px; color: var(--text-dim, #888); }
  #project-tabs-root .pt-auto-badge.on { color: #34d399; font-weight: 600; }
  #project-tabs-root .pt-sched-open { align-self: flex-start; }
  /* v1.111.0 — Todo 요약 + 백그라운드 작업 카드(콘솔에서 이동, 컨텍스트 하단). */
  #project-tabs-root .pt-todo-card, #project-tabs-root .pt-bg-card { display: flex; flex-direction: column; flex: 0 0 auto; }
  #project-tabs-root .pt-todo-list { list-style: none; margin: 0; padding: 0; max-height: 30vh; overflow-y: auto; }
  #project-tabs-root .pt-bg-list { display: flex; flex-direction: column; gap: 4px; max-height: 30vh; overflow-y: auto; }
  #project-tabs-root .bg-task-card {
    display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px;
    background: rgba(255,255,255,0.03); font-size: 12px;
  }
  #project-tabs-root .bg-task-icon { flex-shrink: 0; width: 16px; text-align: center; }
  #project-tabs-root .bg-task-card[data-status="running"] .bg-task-icon { color: #69db7c; animation: vibe-busy-pulse 1.4s ease-in-out infinite; border-radius: 50%; }
  #project-tabs-root .bg-task-card[data-status="completed"] .bg-task-icon { color: #69db7c; }
  #project-tabs-root .bg-task-card[data-status="failed"] .bg-task-icon { color: #ff8787; }
  #project-tabs-root .bg-task-card[data-status="completed"] { opacity: 0.7; transition: opacity 0.4s; }
  #project-tabs-root .bg-task-desc { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #project-tabs-root .bg-task-meta { flex-shrink: 0; color: var(--text-dim,#888); font-size: 10px; font-family: monospace; }
  /* v1.91.0 — 메모 위젯 (프롬프트 히스토리 하단). */
  #project-tabs-root .pt-memo-card { display: flex; flex-direction: column; flex: 0 0 auto; }
  #project-tabs-root .pt-memo-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  #project-tabs-root .pt-memo-add {
    background: transparent; color: var(--text-dim, #888); border: 1px solid #1f2330;
    border-radius: 5px; width: 22px; height: 22px; line-height: 1; cursor: pointer;
    font-size: 13px; padding: 0; flex-shrink: 0;
  }
  #project-tabs-root .pt-memo-add:hover { color: var(--accent, #6aa9ff); border-color: #2a3145; }
  #project-tabs-root .pt-memo-list {
    max-height: 36vh; overflow-y: auto; display: flex; flex-direction: column; gap: 6px;
  }
  #project-tabs-root .pt-memo-item {
    text-align: left; background: #0c0f17; border: 1px solid #1f2330; border-radius: 6px;
    color: var(--text, #ddd); font: inherit; cursor: pointer; width: 100%; padding: 7px 9px;
    display: flex; flex-direction: column; gap: 4px;
  }
  #project-tabs-root .pt-memo-item:hover { background: #1a1f2c; border-color: #2a3145; }
  #project-tabs-root .pt-memo-item .mi-badge {
    font-size: 9px; text-transform: uppercase; letter-spacing: 0.04em; padding: 1px 7px;
    border-radius: 999px; align-self: flex-start;
  }
  #project-tabs-root .pt-memo-item .mi-badge.global { background: rgba(106,169,255,0.15); color: var(--accent, #6aa9ff); }
  #project-tabs-root .pt-memo-item .mi-badge.project { background: rgba(105,219,124,0.13); color: var(--ok, #69db7c); }
  #project-tabs-root .pt-memo-item .mi-body {
    font-size: 12px; line-height: 1.35; white-space: pre-wrap; word-break: break-word;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  }
  #project-tabs-root .pt-memo-empty { color: #5a6175; font-size: 12px; padding: 6px 2px; }
  /* 메모 미니창(다이얼로그) */
  #project-tabs-root .pt-memo-modal {
    position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 400;
    display: flex; align-items: center; justify-content: center; padding: 16px;
  }
  #project-tabs-root .pt-memo-modal[hidden] { display: none; }
  #project-tabs-root .pt-memo-box {
    background: #131722; border: 1px solid #2a3145; border-radius: 10px; padding: 18px;
    width: 100%; max-width: 460px; max-height: 85vh; overflow-y: auto;
    box-shadow: 0 12px 40px rgba(0,0,0,0.5); display: flex; flex-direction: column; gap: 11px;
  }
  #project-tabs-root .pt-memo-box h2 { margin: 0; font-size: 14px; }
  #project-tabs-root .pt-memo-field { display: flex; flex-direction: column; gap: 5px; }
  #project-tabs-root .pt-memo-field label { font-size: 10px; color: #5a6175; text-transform: uppercase; letter-spacing: 0.04em; }
  #project-tabs-root .pt-memo-field select, #project-tabs-root .pt-memo-field textarea {
    background: #0c0f17; border: 1px solid #2a3145; border-radius: 6px; color: var(--text, #ddd);
    font: inherit; padding: 8px 10px; box-sizing: border-box; width: 100%;
  }
  #project-tabs-root .pt-memo-field textarea { min-height: 150px; resize: vertical; line-height: 1.45; }
  #project-tabs-root .pt-memo-foot { display: flex; gap: 8px; align-items: center; margin-top: 2px; }
  #project-tabs-root .pt-memo-spacer { flex: 1; }
  #project-tabs-root .pt-memo-btn { border: 0; border-radius: 6px; padding: 8px 13px; font: inherit; cursor: pointer; }
  #project-tabs-root .pt-memo-btn.primary { background: var(--accent, #6aa9ff); color: #0b0d12; font-weight: 600; }
  #project-tabs-root .pt-memo-btn.ghost { background: transparent; color: var(--text-dim, #888); border: 1px solid #1f2330; }
  #project-tabs-root .pt-memo-btn.ghost:hover { color: var(--text, #ddd); border-color: #2a3145; }
  #project-tabs-root .pt-memo-btn.danger { background: transparent; color: #ff9e9e; border: 1px solid #3a2424; }
  #project-tabs-root .pt-memo-btn.danger:hover { background: #2c1a1a; }
  #project-tabs-root .pt-memo-err { color: #ff6b6b; font-size: 11px; min-height: 13px; }
  /* v1.131.0 — 예약 보내기 모달 (메모 모달과 동일 부모 레이어 패턴, pt-memo-btn 재사용). */
  #project-tabs-root .pt-sched-modal {
    position: fixed; inset: 0; background: rgba(0,0,0,0.6); z-index: 400;
    display: flex; align-items: center; justify-content: center; padding: 16px;
  }
  #project-tabs-root .pt-sched-modal[hidden] { display: none; }
  #project-tabs-root .pt-sched-box {
    background: #131722; border: 1px solid #2a3145; border-radius: 10px; padding: 18px;
    width: 100%; max-width: 460px; max-height: 85vh; overflow-y: auto;
    box-shadow: 0 12px 40px rgba(0,0,0,0.5); display: flex; flex-direction: column; gap: 10px;
  }
  #project-tabs-root .pt-sched-box h2 { margin: 0; font-size: 14px; }
  #project-tabs-root .pt-sched-sub { margin: 0; font-size: 11px; color: var(--text-dim, #888); line-height: 1.45; }
  #project-tabs-root .pt-sched-box textarea {
    background: #0c0f17; border: 1px solid #2a3145; border-radius: 6px; color: var(--text, #ddd);
    font: inherit; font-size: 12px; padding: 8px 10px; box-sizing: border-box; width: 100%;
    resize: vertical; min-height: 60px; line-height: 1.45;
  }
  #project-tabs-root .pt-sched-trig { display: flex; flex-direction: column; gap: 5px; }
  #project-tabs-root .pt-sched-trig label,
  #project-tabs-root .pt-sched-time label {
    display: inline-flex; align-items: center; gap: 5px; margin: 0; font-size: 12px; color: var(--text, #ddd); cursor: pointer;
  }
  #project-tabs-root .pt-sched-time { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; }
  #project-tabs-root .pt-sched-in { display: inline-flex; gap: 5px; align-items: center; }
  #project-tabs-root .pt-sched-time input[type="number"] { width: 64px; }
  #project-tabs-root .pt-sched-time input, #project-tabs-root .pt-sched-time select {
    background: #0c0f17; border: 1px solid #2a3145; border-radius: 5px; color: var(--text, #ddd);
    font: inherit; font-size: 12px; padding: 4px 6px;
  }
  #project-tabs-root .pt-sched-hint { margin: 0; font-size: 11px; color: #8b96a8; }
  #project-tabs-root .pt-sched-err { color: #ff6b6b; font-size: 11px; }
  #project-tabs-root .pt-sched-err:empty { display: none; }
  #project-tabs-root .pt-sched-listh { margin-top: 4px; }
  #project-tabs-root .pt-sched-list { display: flex; flex-direction: column; gap: 4px; max-height: 30vh; overflow-y: auto; }
  #project-tabs-root .pt-sched-item {
    display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px;
    background: rgba(255,255,255,0.03); font-size: 12px;
  }
  #project-tabs-root .pt-sched-item .si-icon { flex-shrink: 0; width: 16px; text-align: center; }
  #project-tabs-root .pt-sched-item .si-main { flex: 1; min-width: 0; overflow: hidden; }
  #project-tabs-root .pt-sched-item .si-label { color: var(--text, #ddd); }
  #project-tabs-root .pt-sched-item .si-prompt { color: var(--text-dim, #888); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  #project-tabs-root .pt-sched-item[data-status="pending"] .si-icon { color: #6aa9ff; }
  #project-tabs-root .pt-sched-item[data-status="sent"] .si-icon { color: #69db7c; }
  #project-tabs-root .pt-sched-item[data-status="failed"] .si-icon { color: #ff8787; }
  #project-tabs-root .pt-sched-item[data-status="sent"],
  #project-tabs-root .pt-sched-item[data-status="cancelled"] { opacity: 0.6; }
  #project-tabs-root .pt-sched-cancel {
    flex-shrink: 0; background: transparent; border: 1px solid #3a2424; color: #ff9e9e;
    border-radius: 5px; font-size: 11px; padding: 3px 7px; cursor: pointer;
  }
  #project-tabs-root .pt-sched-empty { color: #5a6175; font-size: 12px; padding: 4px 2px; }
  #project-tabs-root .pt-sched-foot { display: flex; gap: 8px; align-items: center; margin-top: 2px; }
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
    ${AdminTemplates.backButton("/projects", t("tabs.backToList"))}
    <!-- v1.103.0 — 콘솔 turn 상태칩(콤보박스 좌측). 콘솔 iframe 의 busy-badge 가
         postMessage(console:busy)로 미러링한다. 어느 탭에 있어도 콘솔 진행 상태 확인. -->
    <span id="console-busy-badge" data-state="ready" title="${esc(t("console.busy.idle"))}">${esc(t("console.busy.idle"))}</span>
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
            <dt>${esc(t("projects.detail.updated"))}</dt><dd>${esc(AdminTemplates.fmtTs(project.updatedAt, lang))}</dd>
          </dl>
        </div>
        <!-- v1.81.0 — 이름/패키지명/폴더명 변경 폼이 있는 설정 페이지 링크(이전엔 동선 누락). -->
        <a href="/projects/${esc(project.id)}/overview" class="item">${esc(t("tabs.settings.editProject"))}</a>
        <a href="/projects/${esc(project.id)}/zip" class="item">${esc(t("projects.detail.zip"))}</a>
        <a href="/projects/${esc(project.id)}/env-files" target="${esc("tab-env-files")}" class="item">${esc(t("projects.detail.envFiles"))}</a>
        <hr>
        <form method="post" action="/projects/${esc(project.id)}/archive" style="margin:0"
              onsubmit="return confirm(${jsLit(t("project.action.archiveConfirm"))})">
          ${CsrfTokens.hiddenInput(csrf)}
          <button type="submit" class="item">🗄 ${esc(t("project.action.archive"))}</button>
        </form>
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

<script src="/static/project-tabs.js?v=1.134.0" defer></script>
<!-- v1.56.0 — 콤보박스 상태칩 실시간 동기. 목록 페이지와 동일하게 `/ws/projects`
     (단방향) 의 ProjectBusyChanged 로 responding↔ready patch. -->
<script>
(function() {
  // v1.100.0 — 5-state. ProjectBusyChanged.state 우선, 없으면 busy 폴백.
  var LABELS = {
    responding: ${jsLit(t("projects.status.responding"))},
    ready: ${jsLit(t("projects.status.ready"))},
    idle: ${jsLit(t("projects.status.idle"))},
    waiting: ${jsLit(t("projects.status.waiting"))},
    stopped: ${jsLit(t("projects.status.stopped"))},
    error: ${jsLit(t("projects.status.error"))}
  };
  function patch(pid, state) {
    if (!state) return;
    var el = document.querySelector('.pstat[data-pid="' + (window.CSS && CSS.escape ? CSS.escape(pid) : pid) + '"]');
    if (!el) return;
    el.className = 'pstat pstat-' + state;
    el.dataset.state = state;
    el.textContent = LABELS[state] || state;
    el.title = LABELS[state] || state;
    // v1.128.3 — busy(responding/waiting)로 전환된 프로젝트를 콤보박스 리스트 최상단으로
    // (마지막 상태 변경이 위에 남도록). idle 전환은 위치 유지(다음 새로고침 시 SSR 정렬로 재배치).
    if (state === 'responding' || state === 'waiting') {
      var item = el.closest('.pt-switch-item');
      var list = item && item.parentNode;
      if (item && list && list.firstChild !== item) list.insertBefore(item, list.firstChild);
    }
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
<!-- v1.91.0 — rail 메모 위젯 (전역 + 이 프로젝트). /api/memos JSON API (same-origin cookie). -->
<script>
(function () {
  var PID = ${jsLit(project.id)};
  var L = {
    global: ${jsLit(t("memos.scope.global"))},
    thisProject: ${jsLit(t("memos.scope.thisProject"))},
    titleNew: ${jsLit(t("memos.new"))},
    titleEdit: ${jsLit(t("memos.edit"))},
    empty: ${jsLit(t("memos.empty.short"))},
    confirmDel: ${jsLit(t("memos.deleteConfirm"))},
    errEmpty: ${jsLit(t("memos.error.empty"))},
    errGeneric: ${jsLit(t("memos.error.generic"))}
  };
  var listEl = document.getElementById('pt-memo-list');
  var modal = document.getElementById('pt-memo-modal');
  if (!listEl || !modal) return;
  var titleEl = document.getElementById('pt-memo-title');
  var scopeEl = document.getElementById('pt-memo-scope');
  var contentEl = document.getElementById('pt-memo-content');
  var errEl = document.getElementById('pt-memo-err');
  var delBtn = document.getElementById('pt-memo-del');
  var editingId = null;

  function api(method, url, body) {
    return fetch(url, {
      method: method, credentials: 'same-origin',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined
    });
  }

  function render(memos) {
    listEl.textContent = '';
    if (!memos.length) {
      var e = document.createElement('div');
      e.className = 'pt-memo-empty'; e.textContent = L.empty;
      listEl.appendChild(e); return;
    }
    memos.forEach(function (m) {
      var item = document.createElement('button');
      item.type = 'button'; item.className = 'pt-memo-item';
      var badge = document.createElement('span');
      if (m.projectId) { badge.className = 'mi-badge project'; badge.textContent = L.thisProject; }
      else { badge.className = 'mi-badge global'; badge.textContent = L.global; }
      var body = document.createElement('span');
      body.className = 'mi-body'; body.textContent = m.content;
      item.appendChild(badge); item.appendChild(body);
      item.addEventListener('click', function () { openEdit(m); });
      listEl.appendChild(item);
    });
  }

  function load() {
    api('GET', '/api/memos?projectId=' + encodeURIComponent(PID))
      .then(function (r) { return r.json(); })
      .then(function (d) { render((d && d.memos) || []); })
      .catch(function () { render([]); });
  }

  function open() { errEl.textContent = ''; modal.hidden = false; setTimeout(function () { contentEl.focus(); }, 0); }
  function close() { modal.hidden = true; editingId = null; }

  function openNew() {
    editingId = null; titleEl.textContent = L.titleNew;
    scopeEl.value = '__project__'; contentEl.value = ''; delBtn.hidden = true; open();
  }
  function openEdit(m) {
    editingId = m.id; titleEl.textContent = L.titleEdit;
    scopeEl.value = m.projectId ? '__project__' : ''; contentEl.value = m.content;
    delBtn.hidden = false; open();
  }
  function scopePid() { return scopeEl.value === '__project__' ? PID : null; }

  function save() {
    var content = contentEl.value.trim();
    if (!content) { errEl.textContent = L.errEmpty; return; }
    var url, method, req;
    if (editingId) {
      method = 'PUT'; url = '/api/memos/' + encodeURIComponent(editingId);
      req = { content: content, projectId: scopePid(), keepScope: false };
    } else {
      method = 'POST'; url = '/api/memos';
      req = { content: content, projectId: scopePid() };
    }
    api(method, url, req).then(function (r) {
      if (!r.ok) { errEl.textContent = L.errGeneric; return; }
      close(); load();
    }).catch(function () { errEl.textContent = L.errGeneric; });
  }
  function del() {
    if (!editingId || !window.confirm(L.confirmDel)) return;
    api('DELETE', '/api/memos/' + encodeURIComponent(editingId)).then(function (r) {
      if (!r.ok) { errEl.textContent = L.errGeneric; return; }
      close(); load();
    }).catch(function () { errEl.textContent = L.errGeneric; });
  }

  document.getElementById('pt-memo-add').addEventListener('click', openNew);
  document.getElementById('pt-memo-save').addEventListener('click', save);
  document.getElementById('pt-memo-cancel').addEventListener('click', close);
  delBtn.addEventListener('click', del);
  modal.addEventListener('click', function (e) { if (e.target === modal) close(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !modal.hidden) close(); });

  load();
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
