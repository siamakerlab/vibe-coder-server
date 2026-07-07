package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * 빌드환경 페이지 SSR 템플릿.
 *
 * v0.6.0 Phase A — 상태 카드 + 사용자 절차 안내.
 * v0.6.1 Phase B — 카드별 원클릭 설치 버튼 + "모두 설치/업데이트" 일괄 버튼
 *   + 진행 페이지 (실시간 WS 로그).
 * v0.84.0 Phase 64.8 — 모든 사용자 가시 한국어 문자열 i18n 키화.
 *
 * AdminTemplates.shell() 레이아웃을 공유한다 (좌측 nav 의 "빌드환경" 메뉴와 동일 경로).
 */
object EnvSetupTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun envSetupPage(
        username: String,
        states: List<ComponentState>,
        claudeFlash: String? = null,
        /** v1.9.0 — 현재 git global identity (둘 다 null 가능). */
        gitIdentity: com.siamakerlab.vibecoder.server.env.GitIdentity =
            com.siamakerlab.vibecoder.server.env.GitIdentity(null, null),
        /** v1.9.0 — `?git=saved|cleared|err:<code>` flash. */
        gitFlash: String? = null,
        sshPort: Int = 2222,
        sshFlash: String? = null,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        // 21차 점검 후속 — 빌드환경 페이지 우선순위 재정렬. 이전엔 quick-links → 장문 welcome →
        // preserved → git → grid(JDK/Git/Node 가 맨 앞) 순이라 정작 중요한 Claude 인증/Android SDK
        // 가 한참 아래에 묻혔다. 이제 ① 핵심(설치/인증 필요, 우선순위순) → ② Git → ③ 이미지 내장
        // (접힘, 문제 있을 때만 자동 펼침) → ④ 관련 설정 링크 → ⑤ 안내·데이터보존(접힘) 순.
        // v1.34.6 — MCP 카탈로그는 별도 settings 탭(/env-setup/mcp)으로 분리. 빌드환경
        // 페이지의 컴포넌트 grid·quick-link 에선 제외(install-all/진단에는 그대로 포함).
        val ordered = states.sortedBy { priorityRank(it.component) }
            .filterNot { it.component == SetupComponent.MCP_DEFAULTS }
        val (coreStates, builtinStates) = ordered.partition { it.component.doctorCmd != null }
        val coreCards = coreStates.joinToString("\n") { renderCard(it, csrf, lang, sshPort) }
        val builtinCards = builtinStates.joinToString("\n") { renderCard(it, csrf, lang, sshPort) }
        val builtinNeedsAttention = builtinStates.any { it.status != ComponentStatus.INSTALLED }
        val flashHtml = claudeFlashBlurb(claudeFlash, lang) + gitFlashBlurb(gitFlash, lang) + sshFlashBlurb(sshFlash, lang)
        val gitCard = renderGitIdentityCard(gitIdentity, csrf, lang)
        return AdminTemplates.shell(
            title = t("env.heading"),
            username = username,
            currentPath = "/env-setup",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("env.heading"))}</h1>
    <form method="post" action="/env-setup/install-all" style="display:inline"
          onsubmit="return confirm(${jsLit(t("env.installAllConfirm"))})">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">${esc(t("env.installAllBtn"))}</button>
    </form>
  </div>
</header>
$flashHtml

<!-- ① 핵심 — 설치 / 인증 필요 (우선순위순: Claude 인증 → Android SDK → Gradle → …) -->
<h2 style="margin:4px 0 10px;font-size:15px">${esc(t("env.section.core"))}</h2>
<section class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">
  $coreCards
</section>

<!-- ② Git identity -->
$gitCard

<!-- ③ 이미지 내장 — 보통 손댈 필요 없음. 문제 있을 때만 자동 펼침. -->
<div class="card" style="margin-bottom:16px">
  <details ${if (builtinNeedsAttention) "open" else ""}>
    <summary style="cursor:pointer"><strong>${esc(t("env.section.builtin"))}</strong>
      <span class="dim" style="font-size:12px">— ${esc(t("env.section.builtinHint"))}</span></summary>
    <section class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr));margin-top:12px">
      $builtinCards
    </section>
  </details>
</div>

<!-- ④ 빌드환경 관련 sub-settings quick links -->
<div class="card" style="margin-bottom:16px">
  <h2 style="margin-bottom:8px">${esc(t("env.subsettings.title"))}</h2>
  <p class="dim" style="font-size:12px;margin-bottom:10px">${esc(t("env.subsettings.body"))}</p>
  <div style="display:flex;flex-wrap:wrap;gap:8px">
    <a href="/settings/keystores" class="chip chip-link">${esc(t("env.subsettings.keystores"))}</a>
    <a href="/settings/ssh-key" class="chip chip-link">${esc(t("env.subsettings.sshKey"))}</a>
    <a href="/settings/cache" class="chip chip-link">${esc(t("env.subsettings.cache"))}</a>
    <a href="/settings/git-integrations" class="chip chip-link">${esc(t("env.subsettings.gitIntegrations"))}</a>
  </div>
</div>

<!-- ⑤ 처음 사용 안내 + 데이터 보존 (참고용 — 접힘) -->
<div class="card">
  <details>
    <summary style="cursor:pointer"><strong>${esc(t("env.section.help"))}</strong></summary>
    <div style="margin-top:12px">
      <h3 style="margin:0 0 6px">${esc(t("env.welcome.title"))}</h3>
      <p style="margin:0 0 4px">${esc(t("env.welcome.intro"))}</p>
      <ol style="margin:8px 0 0 20px;line-height:1.8">
        <li>${t("env.welcome.step1")}</li>
        <li>${t("env.welcome.step2")}</li>
        <li>${t("env.welcome.step3")}</li>
        <li>${t("env.welcome.step4")}</li>
      </ol>
      <hr style="border:none;border-top:1px solid var(--border);margin:14px 0">
      <h3 style="margin:0 0 6px;color:var(--ok)">${esc(t("env.preserved.title"))}</h3>
      <p style="margin:0 0 4px">${t("env.preserved.desc")}</p>
      <pre class="diff-block">docker pull siamakerlab/vibe-coder-server:&lt;new-version&gt;
docker compose up -d --force-recreate</pre>
      <p class="hint">${t("env.preserved.warn")}</p>
    </div>
  </details>
</div>
""",
            embed = embed,
        )
    }

    /**
     * 빌드환경 카드 정렬 우선순위 (낮을수록 위). 핵심 설치/인증 항목을 먼저, 이미지 내장
     * (doctorCmd == null) 은 partition 으로 별도 섹션 처리되므로 여기선 9 로 후순위.
     */
    private fun priorityRank(c: SetupComponent): Int = when (c) {
        SetupComponent.CLAUDE_AUTH -> 0      // 인증 — 이게 없으면 아무것도 안 됨
        SetupComponent.ANDROID_SDK -> 1
        SetupComponent.GRADLE -> 2
        SetupComponent.FLUTTER -> 3          // v1.124.0 — Flutter (Android 전용); SDK/Gradle 위에서 동작
        SetupComponent.PLATFORM_TOOLS -> 4
        SetupComponent.ANDROID_EMULATOR -> 5
        SetupComponent.MCP_DEFAULTS -> 6
        SetupComponent.CODEX -> 7            // v1.145.0 — Codex CLI (옵션 도구)
        SetupComponent.SSH_SERVER -> 8        // 원격 접속은 선택 기능 — 명시 설치만
        else -> 9                            // built-in (JDK/Git/Node/Claude CLI)
    }

    /**
     * v1.9.0 — Git global identity 카드. 빌드환경 page 의 상단 (welcome / preserved 뒤,
     * 컴포넌트 grid 앞) 에 두어 첫 진입 사용자 시선에 닿게 한다.
     *
     * 미설정 상태면 카드 헤더에 경고 배지. 입력은 name + email 2-field form +
     * 저장 / 초기화 두 버튼. JS 없이 SSR + CSRF.
     */
    private fun renderGitIdentityCard(
        identity: com.siamakerlab.vibecoder.server.env.GitIdentity,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val configured = identity.isConfigured
        val badge = if (configured)
            """<span class="ok" style="font-size:12px;white-space:nowrap">${esc(t("git.id.badge.configured"))}</span>"""
        else
            """<span class="warn" style="font-size:12px;white-space:nowrap">${esc(t("git.id.badge.unset"))}</span>"""
        val border = if (configured) "" else "border-color:var(--warn)"
        return """<div class="card" id="git-identity" style="margin-bottom:16px;$border">
  <div style="display:flex;justify-content:space-between;align-items:start;gap:8px">
    <h2 style="margin:0">${esc(t("git.id.title"))}</h2>
    $badge
  </div>
  <p class="dim" style="font-size:13px;margin:6px 0 12px;line-height:1.5">${esc(t("git.id.intro"))}</p>
  <form method="post" action="/env-setup/git-config"
        style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("git.id.field.name"))}</div>
      <input name="name" required maxlength="100"
             placeholder="${esc(t("git.id.placeholder.name"))}"
             value="${esc(identity.name.orEmpty())}"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <label>
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("git.id.field.email"))}</div>
      <input name="email" type="email" required maxlength="200"
             placeholder="${esc(t("git.id.placeholder.email"))}"
             value="${esc(identity.email.orEmpty())}"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <div style="grid-column:1/3;display:flex;gap:8px;align-items:center;margin-top:4px">
      <button type="submit" class="primary" style="padding:8px 18px;width:auto">${esc(t("git.id.saveBtn"))}</button>
      ${if (configured) """<button type="submit" formaction="/env-setup/git-config/clear"
            class="chip chip-action" style="background:#7f1d1d;color:#fff;padding:8px 14px"
            onclick="return confirm(${jsLit(t("git.id.clearConfirm"))})">${esc(t("git.id.clearBtn"))}</button>""" else ""}
    </div>
  </form>
  <details style="margin-top:6px">
    <summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("git.id.advanced"))}</summary>
    <div style="margin-top:8px;font-size:12px;line-height:1.5">
      <p class="dim" style="margin:0 0 6px">${esc(t("git.id.advanced.body"))}</p>
      <pre class="diff-block" style="font-size:11px">git config --global user.name "&lt;name&gt;"
git config --global user.email "&lt;email&gt;"
# v1.9.0+ — 영속 위치: /home/vibe/.config/git/config (GIT_CONFIG_GLOBAL env)</pre>
    </div>
  </details>
</div>"""
    }

    private fun gitFlashBlurb(code: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when {
            code == null -> ""
            code == "saved" -> blurb("ok", t("git.id.flash.saved"))
            code == "cleared" -> blurb("warn", t("git.id.flash.cleared"))
            code.startsWith("err:") -> {
                val errCode = code.removePrefix("err:")
                // 메시지 lookup: api.gitConfig.<errCode> 우선, 없으면 raw code 표시.
                val key = "api.gitConfig.${camelize(errCode)}"
                val msg = Messages.t(lang, key).takeIf { it != key } ?: errCode
                blurb("err", "${t("git.id.flash.err")}: $msg")
            }
            else -> ""
        }
    }

    private fun sshFlashBlurb(code: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when {
            code == null -> ""
            code.startsWith("err:") -> {
                val errCode = code.removePrefix("err:")
                val key = "api.envSetup.${camelize(errCode)}"
                val msg = Messages.t(lang, key).takeIf { it != key } ?: errCode
                blurb("err", "${t("env.ssh.flash.err")}: $msg")
            }
            else -> ""
        }
    }

    private fun camelize(s: String): String {
        // git_failed → gitFailed, name_required → nameRequired
        val parts = s.split("_")
        if (parts.size <= 1) return s
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun renderCard(s: ComponentState, csrf: String?, lang: String, sshPort: Int): String {
        val c = s.component
        // v1.7.16 — c.displayName / sizeHint / description 는 i18n 키 (String).
        // Messages.t 로 lookup. 영어 사용자에 영어, 한국어 사용자에 한글 표시.
        val (badgeCls, badgeText) = badgeFor(c, s.status, lang)
        val actionHtml = renderAction(c, s.status, csrf, lang, sshPort)
        val name = Messages.t(lang, c.displayName)
        val size = Messages.t(lang, c.sizeHint)
        val desc = Messages.t(lang, c.description)
        return """<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:start;gap:8px">
    <h2 style="margin-bottom:8px">${esc(name)}</h2>
    <span class="$badgeCls" style="white-space:nowrap;font-size:12px">${esc(badgeText)}</span>
  </div>
  <p class="dim" style="font-size:12px;margin:0 0 8px">${esc(size)}</p>
  <p style="font-size:13px;line-height:1.5">${esc(desc)}</p>
  <p style="font-size:12px;color:var(--text-dim);margin:8px 0 0">${esc(s.message)}</p>
  $actionHtml
</div>"""
    }

    private fun badgeFor(c: SetupComponent, status: ComponentStatus, lang: String): Pair<String, String> {
        val t = { key: String -> Messages.t(lang, key) }
        return if (c == SetupComponent.CLAUDE_AUTH) {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to t("env.badge.loggedIn")
                ComponentStatus.PARTIAL -> "warn" to t("env.badge.partialAuth")
                ComponentStatus.MISSING -> "warn" to t("env.badge.loginNeeded")
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        } else {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to t("env.badge.installed")
                ComponentStatus.PARTIAL -> "warn" to t("env.badge.partial")
                ComponentStatus.MISSING -> "warn" to t("env.badge.missing")
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        }
    }

    private fun renderAction(c: SetupComponent, status: ComponentStatus, csrf: String?, lang: String, sshPort: Int): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when (c) {
            // 이미지 내장 — 진단 실패 시에만 경고. 정상이면 액션 없음.
            SetupComponent.JAVA,
            SetupComponent.GIT,
            SetupComponent.NODE,
            SetupComponent.CLAUDE_CLI ->
                if (status == ComponentStatus.INSTALLED) ""
                else """<p class="hint" style="margin-top:8px">${esc(t("env.action.builtinFail"))}</p>"""

            // Claude 로그인 — 세 가지 경로 제공.
            //  1) 터미널에서 직접 `claude login` (가장 표준).
            //  2) 다른 머신에서 받은 .credentials.json 업로드 (web-only 환경 대응).
            //  3) ANTHROPIC_API_KEY 등록 (OAuth 미사용 / API 키 사용자).
            // v0.7.0 — terminal 접근 불가능한 운영 환경 (외부 호스팅 / 모바일) 에서도
            // 100% 웹으로 인증 완료 가능. raw-shell UI 미사용 (CLAUDE.md §3 정책 준수).
            SetupComponent.CLAUDE_AUTH -> renderClaudeAuthActions(status, csrf, lang)

            // Android SDK — 원클릭 설치 + 진행 페이지.
            SetupComponent.ANDROID_SDK -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.androidLabel.installed")
                    ComponentStatus.PARTIAL -> t("env.action.androidLabel.partial")
                    else -> t("env.action.androidLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.androidConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.cliHint"))}</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor android</pre>
                </details>"""
            }

            SetupComponent.PLATFORM_TOOLS ->
                """<p class="hint" style="margin-top:8px">${esc(t("env.action.platformToolsNote"))}</p>"""

            SetupComponent.MCP_DEFAULTS ->
                """<a href="/env-setup/mcp" class="primary chip" style="display:inline-block;padding:8px 16px;margin-top:10px">${esc(t("env.action.mcpLink"))}</a>
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.mcpNote"))}</p>"""

            // Gradle — wrapper bootstrap 용. 최신 stable 자동 다운로드.
            SetupComponent.GRADLE -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.gradleLabel.installed")
                    ComponentStatus.PARTIAL -> t("env.action.gradleLabel.partial")
                    else -> t("env.action.gradleLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                       onsubmit="return confirm(${jsLit(t("env.action.gradleConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.gradleNote"))}</p>"""
            }

            // v1.73.0 — 에뮬레이터(헤드리스). 설치는 SDK 와 같은 vibe-doctor android 흐름.
            // 실행/AVD 생성은 /emulator. logcat/install 은 Claude 가 콘솔에서 adb 직접.
            SetupComponent.ANDROID_EMULATOR -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.emulatorLabel.installed")
                    ComponentStatus.PARTIAL -> t("env.action.emulatorLabel.partial")
                    else -> t("env.action.emulatorLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.emulatorConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.emulatorNote"))}
                  <a href="/emulator" class="chip-link" style="margin-left:4px">${esc(t("env.action.emulatorOpen"))}</a></p>"""
            }

            // v1.124.0 — Flutter SDK (Android 앱 빌드 전용). git stable channel clone +
            // Android-only precache (iOS/web/desktop artifact 미다운로드 → 리소스 절약).
            // 빌드 파이프라인 연동(FlutterToolchain)은 후속 Phase. 여기선 설치 메뉴만.
            SetupComponent.FLUTTER -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.flutterLabel.installed")
                    else -> t("env.action.flutterLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.flutterConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.flutterNote"))}</p>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.cliHint"))}</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor flutter</pre>
                </details>"""
            }

            // v1.145.0 — Codex CLI (OpenAI, 옵션). npm `@openai/codex` 를 /home/vibe/.local 에
            // 설치(영속). 로그인은 CODEX_HOME(.config bind mount)에 저장 → 이미지 업데이트에도 유지.
            SetupComponent.CODEX -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.codexLabel.installed")
                    else -> t("env.action.codexLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.codexConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                ${renderCodexLoginAction(status, csrf, lang)}
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.codexNote"))}</p>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.cliHint"))}</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor codex
docker exec -it vibe-coder-server codex login --device-auth</pre>
                </details>"""
            }

            // v1.156.0 — opencode CLI (z.ai coding plan). 설치 버튼 + API key 입력 + 브라우저 login.
            SetupComponent.OPENCODE -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.opencodeLabel.installed")
                    else -> t("env.action.opencodeLabel.missing")
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.opencodeConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                ${renderOpenCodeLoginAction(status, csrf, lang)}
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.cliHint"))}</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor opencode
docker exec -it --user vibe vibe-coder-server opencode providers login</pre>
                </details>"""
            }

            SetupComponent.SSH_SERVER -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> t("env.action.sshServerLabel.installed")
                    else -> t("env.action.sshServerLabel.missing")
                }
                """<form method="post" action="/env-setup/ssh-server/config" style="margin-top:10px"
                        onsubmit="return confirm(${jsLit(t("env.action.sshServerConfirm"))})">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <label>
                    <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("env.action.sshServerPort"))}</div>
                    <input name="port" type="number" min="1024" max="65535" required value="${esc(sshPort.toString())}"
                           style="width:140px;padding:8px;font-family:ui-monospace,Menlo,monospace">
                  </label>
                  <div style="margin-top:8px">
                    <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                  </div>
                </form>
                <p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.sshServerNote"))}</p>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.cliHint"))}</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor ssh-server
ssh -p $sshPort vibe@&lt;host&gt;</pre>
                </details>"""
            }
        }
    }

    private fun renderCodexLoginAction(status: ComponentStatus, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return if (status == ComponentStatus.INSTALLED) {
            """
            <form method="post" action="/env-setup/codex-login/start" style="margin-top:8px"
                    onsubmit="return confirm(${jsLit(t("env.action.codexLoginConfirm"))})">
              ${CsrfTokens.hiddenInput(csrf)}
              <button type="submit" class="chip chip-action" style="padding:8px 16px">${esc(t("env.action.codexLogin"))}</button>
            </form>
            <details style="margin-top:8px">
              <summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.codexAccessTokenTitle"))}</summary>
              <p class="hint" style="margin:6px 0 8px">${esc(t("env.action.codexAccessTokenDesc"))}</p>
              <form method="post" action="/env-setup/codex-auth/access-token"
                    style="display:flex;flex-direction:column;gap:8px"
                    onsubmit="return confirm(${jsLit(t("env.action.codexAccessTokenConfirm"))})">
                ${CsrfTokens.hiddenInput(csrf)}
                <input type="password" name="accessToken" placeholder="codex access token" required
                       autocomplete="off" spellcheck="false"
                       style="font-size:13px;padding:6px 8px">
                <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">${esc(t("env.action.codexAccessTokenBtn"))}</button>
              </form>
            </details>
            <details style="margin-top:8px">
              <summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.codexApiKeyTitle"))}</summary>
              <p class="hint" style="margin:6px 0 8px">${esc(t("env.action.codexApiKeyDesc"))}</p>
              <form method="post" action="/env-setup/codex-auth/api-key"
                    style="display:flex;flex-direction:column;gap:8px"
                    onsubmit="return confirm(${jsLit(t("env.action.codexApiKeyConfirm"))})">
                ${CsrfTokens.hiddenInput(csrf)}
                <input type="password" name="apiKey" placeholder="sk-..." required minlength="20"
                       autocomplete="off" spellcheck="false"
                       style="font-size:13px;padding:6px 8px">
                <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">${esc(t("env.action.codexApiKeyBtn"))}</button>
              </form>
            </details>
            """
        } else {
            """<p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.codexLoginInstallFirst"))}</p>"""
        }
    }

    /**
     * v1.151.0/v1.156.0 — OpenCode(z.ai coding plan) 로그인. SetupComponent.OPENCODE 카드 안.
     * ① 브라우저 기반 providers login, ② API key 직접 입력(auth.json 작성 — 서버 무인 환경용).
     */
    private fun renderOpenCodeLoginAction(status: ComponentStatus, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return if (status == ComponentStatus.INSTALLED) {
            """
            <form method="post" action="/env-setup/opencode-login/start" style="margin-top:8px"
                    onsubmit="return confirm(${jsLit(t("env.action.opencodeLoginConfirm"))})">
              ${CsrfTokens.hiddenInput(csrf)}
              <button type="submit" class="chip chip-action" style="padding:8px 16px">${esc(t("env.action.opencodeLogin"))}</button>
            </form>
            <details style="margin-top:8px">
              <summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.action.opencodeApiKeyTitle"))}</summary>
              <p class="hint" style="margin:6px 0 8px">${esc(t("env.action.opencodeApiKeyDesc"))}</p>
              <form method="post" action="/env-setup/opencode-auth/api-key"
                    style="display:flex;flex-direction:column;gap:8px"
                    onsubmit="return confirm(${jsLit(t("env.action.opencodeApiKeyConfirm"))})">
                ${CsrfTokens.hiddenInput(csrf)}
                <input type="password" name="apiKey" placeholder="z.ai coding plan API key" required
                       autocomplete="off" spellcheck="false"
                       style="font-size:13px;padding:6px 8px">
                <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">${esc(t("env.action.opencodeApiKeyBtn"))}</button>
              </form>
            </details>
            """
        } else {
            """<p class="hint" style="margin-top:8px;font-size:12px">${esc(t("env.action.opencodeLoginInstallFirst"))}</p>"""
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // v0.7.0 — Claude 로그인 카드 (3-옵션 + flash blurb)
    // ────────────────────────────────────────────────────────────────────

    /** /env-setup?claude=<...> redirect 후 표시되는 한 줄 알림. */
    private fun claudeFlashBlurb(code: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when (code) {
            "uploaded" -> blurb("ok", t("env.flash.uploaded"))
            "api-key" -> blurb("ok", t("env.flash.apiKey"))
            "api-key-deleted" -> blurb("warn", t("env.flash.apiKeyDeleted"))
            else -> ""
        }
    }

    private fun blurb(cls: String, text: String): String =
        """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.08);border-color:var(--$cls)">
        <p style="margin:0;color:var(--$cls)">${esc(text)}</p></div>"""

    private fun renderClaudeAuthActions(status: ComponentStatus, csrf: String?, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        val statusHint = when (status) {
            ComponentStatus.INSTALLED -> """<p class="hint" style="margin-top:8px">${esc(t("env.auth.installed.hint"))}</p>"""
            else -> ""
        }
        return """
$statusHint

<div style="margin-top:10px;padding:10px;border:1px solid var(--ok);border-radius:6px;background:rgba(105,219,124,0.06)">
  <strong style="color:var(--ok)">${esc(t("env.auth.opt0.title"))}</strong>
  <p class="hint" style="margin:6px 0 8px">${esc(t("env.auth.opt0.desc"))}</p>
  <a href="/env-setup/claude-login" class="primary chip" style="padding:8px 16px;display:inline-block">${esc(t("env.auth.opt0.btn"))}</a>
</div>

<details style="margin-top:10px"><summary class="dim" style="cursor:pointer;font-size:13px">${esc(t("env.auth.opt1.title"))}</summary>
  <pre class="diff-block" style="margin-top:6px">docker exec -it --user vibe vibe-coder-server claude login</pre>
  <p class="hint">${esc(t("env.auth.opt1.hint"))}</p>
</details>

<details style="margin-top:10px" ${if (status != ComponentStatus.INSTALLED) "open" else ""}>
  <summary class="dim" style="cursor:pointer;font-size:13px">${t("env.auth.opt2.title")}</summary>
  <p class="hint" style="margin-top:6px">${t("env.auth.opt2.desc")}</p>
  <!-- multipart 라 _csrf 를 query string 으로 전달 -->
  <form method="post" action="/env-setup/claude-auth/upload?_csrf=${esc(csrf)}" enctype="multipart/form-data"
        style="margin-top:8px;display:flex;flex-direction:column;gap:8px"
        onsubmit="return confirm(${jsLit(t("env.auth.opt2.confirm"))})">
    <input type="file" name="credentials" accept=".json,application/json" required
           style="font-size:13px">
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">${esc(t("env.auth.opt2.btn"))}</button>
  </form>
  <p class="hint" style="font-size:11px;margin-top:6px">${esc(t("env.auth.opt2.warn"))}</p>
</details>

<details style="margin-top:10px">
  <summary class="dim" style="cursor:pointer;font-size:13px">${t("env.auth.opt3.title")}</summary>
  <p class="hint" style="margin-top:6px">${t("env.auth.opt3.desc")}</p>
  <form method="post" action="/env-setup/claude-auth/api-key"
        style="margin-top:8px;display:flex;flex-direction:column;gap:8px"
        onsubmit="return confirm(${jsLit(t("env.auth.opt3.confirm"))})">
    ${CsrfTokens.hiddenInput(csrf)}
    <input type="password" name="apiKey" placeholder="sk-ant-..." required minlength="20" autocomplete="off"
           style="font-size:13px;padding:6px 8px">
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">${esc(t("env.auth.opt3.btn"))}</button>
  </form>
  <form method="post" action="/env-setup/claude-auth/api-key/delete"
        style="margin-top:6px"
        onsubmit="return confirm(${jsLit(t("env.auth.opt3.deleteConfirm"))})">
    ${CsrfTokens.hiddenInput(csrf)}
    <button type="submit" style="width:auto;padding:6px 12px;font-size:12px">${esc(t("env.auth.opt3.deleteBtn"))}</button>
  </form>
  <p class="hint" style="font-size:11px;margin-top:6px">${esc(t("env.auth.opt3.warn"))}</p>
</details>
"""
    }

    // ────────────────────────────────────────────────────────────────────
    // v0.7.0 옵션 A — 반자동 웹 OAuth 진행 페이지 (/env-setup/claude-login)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Claude 웹 로그인 진행 페이지.
     *
     * 사용자에게 보이는 것은 단순 폼 3개 (시작 / URL 클릭 / 코드 입력). pty 는
     * 서버 내부 디테일이며 브라우저에는 노출되지 않는다 (CLAUDE.md §3 정책 준수).
     * 1초마다 `/env-setup/claude-login/status.json` 을 폴링해 상태를 갱신.
     */
    fun claudeLoginPage(
        username: String,
        state: ClaudeLoginService.SessionDto?,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val (statusText, statusCls) = stateLabel(state?.state, lang)
        val urlBlock = if (state?.url != null) {
            """<div class="card" style="margin-top:12px;background:rgba(80,150,255,0.08)">
              <strong>${esc(t("env.login.urlBlock.title"))}</strong>
              <div style="margin:8px 0;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
                <a href="${esc(state.url)}" target="_blank" rel="noreferrer" class="primary chip" style="padding:8px 14px">${esc(t("env.login.urlBlock.newTab"))}</a>
                <button type="button" class="chip" onclick="navigator.clipboard.writeText(${"'" + escJs(state.url) + "'"});this.textContent=${jsLit(t("env.login.urlBlock.copied"))};setTimeout(()=>this.textContent=${jsLit(t("env.login.urlBlock.copy"))},2000)">${esc(t("env.login.urlBlock.copy"))}</button>
              </div>
              <pre class="diff-block" style="margin:0;font-size:11px;overflow-x:auto">${esc(state.url)}</pre>
              <p class="hint" style="margin-top:8px">${t("env.login.urlBlock.hint")}</p>
            </div>"""
        } else ""

        val codeForm = if (state?.state == "AWAITING_CODE") {
            // v0.10.1 — id="code-input" / id="code-form" 추가 (JS 가 autofocus + sessionStorage
            // 복원/백업/clear 에 사용). autofocus 속성도 같이 — JS 비활성 환경 대비.
            """<div class="card" style="margin-top:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
              <strong>${esc(t("env.login.codeForm.title"))}</strong>
              <form method="post" action="/env-setup/claude-login/submit" id="code-form"
                    style="margin-top:8px;display:flex;flex-direction:column;gap:8px">
                ${CsrfTokens.hiddenInput(csrf)}
                <input type="text" name="code" id="code-input"
                       placeholder="${esc(t("env.login.codeForm.placeholder"))}" required autofocus
                       autocomplete="off" autocapitalize="off" spellcheck="false"
                       style="font-size:13px;padding:8px;font-family:ui-monospace,Menlo,monospace">
                <div style="display:flex;gap:8px">
                  <button type="submit" class="primary" style="padding:8px 18px">${esc(t("env.login.codeForm.submit"))}</button>
                  <button type="submit" formaction="/env-setup/claude-login/cancel" formmethod="post"
                          formnovalidate style="padding:8px 14px">${esc(t("env.login.codeForm.cancel"))}</button>
                </div>
              </form>
              <p class="hint" style="font-size:11px;margin-top:6px">
                ${esc(t("env.login.codeForm.autoReload"))}
              </p>
            </div>"""
        } else ""

        val finalBlock = when (state?.state) {
            "DONE" -> """<div class="card" style="margin-top:12px;background:rgba(105,219,124,0.10);border-color:var(--ok)">
              <strong style="color:var(--ok)">${esc(t("env.login.done.title"))}</strong>
              <p style="margin-top:6px">${esc(t("env.login.done.desc"))}</p>
              ${AdminTemplates.backButton("/env-setup", t("env.login.done.btn"))}
            </div>"""
            "FAILED" -> """<div class="card" style="margin-top:12px;background:rgba(255,150,80,0.08);border-color:var(--warn)">
              <strong style="color:var(--warn)">${esc(t("env.login.failed.title"))}</strong>
              <p style="margin-top:6px">${esc(state.errorMessage ?: t("env.login.failed.unknown"))}</p>
              <p class="hint" style="margin-top:8px">${t("env.login.failed.hint")}</p>
              <form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
                ${CsrfTokens.hiddenInput(csrf)}
                <button type="submit" class="primary" style="padding:8px 14px">${esc(t("env.login.failed.retry"))}</button>
              </form>
            </div>"""
            "CANCELED" -> """<div class="card" style="margin-top:12px;background:rgba(160,160,160,0.05)">
              <strong class="dim">${esc(t("env.login.canceled.title"))}</strong>
              <form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
                ${CsrfTokens.hiddenInput(csrf)}
                <button type="submit" class="primary" style="padding:8px 14px">${esc(t("env.login.canceled.newSession"))}</button>
              </form>
            </div>"""
            else -> ""
        }

        val startForm = if (state == null || state.state in listOf("DONE", "FAILED", "CANCELED")) {
            """<form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
              ${CsrfTokens.hiddenInput(csrf)}
              <button type="submit" class="primary" style="padding:10px 20px">${esc(t("env.login.startBtn"))}</button>
            </form>"""
        } else ""

        val lastLinesBlock = if (!state?.lastLines.isNullOrEmpty()) {
            val lines = state!!.lastLines.takeLast(8).joinToString("\n") { esc(it) }
            """<details style="margin-top:12px"><summary class="dim" style="cursor:pointer;font-size:12px">${esc(t("env.login.lastLines.title"))}</summary>
              <pre class="diff-block" style="margin-top:6px;font-size:11px;max-height:160px;overflow:auto">$lines</pre>
            </details>"""
        } else ""

        return AdminTemplates.shell(
            title = t("env.login.title"),
            username = username,
            currentPath = "/env-setup",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
<header>
  <h1>${esc(t("env.login.title"))} <small class="dim" style="font-size:14px;font-weight:400">${esc(t("env.login.subtitle"))}</small></h1>
</header>

<div class="card" style="margin-bottom:12px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <div><strong>${esc(t("env.login.statusLabel"))}</strong> · <span id="state-chip" class="$statusCls">${esc(statusText)}</span></div>
    ${AdminTemplates.backButton("/env-setup", t("env.login.backToEnv"))}
  </div>
  <p class="hint" style="margin-top:8px">${esc(t("env.login.intro"))}</p>
  $startForm
</div>

$urlBlock
$codeForm
$finalBlock
$lastLinesBlock

<script>
(function() {
  // v0.10.1 — 사용자 입력 영속성 + 포커스 안정성 fix:
  //   1) input 값 sessionStorage 자동 백업/복원 — reload 돼도 paste 보존
  //   2) AWAITING_CODE 에서는 폴링 disable — 사용자 입력 도중 reload 방지
  //   3) input 자동 focus
  //   4) submit/cancel 직전 sessionStorage clear
  // 알려진 trade-off: AWAITING_CODE 에서 child process 가 죽어도 사용자는
  // 즉시 알 수 없음 → submit 시 wrong_state(409) 에러로 안내 (errorBlurb 가 표시).

  var STORAGE_KEY = 'claude_login_code_buf';
  var initial = ${if (state == null) "null" else "\"${state.state}\""};
  var POLLING_STATES = ['STARTING', 'VERIFYING'];   // 사용자 액션 대기 외만 폴링

  // ── input 영속성 ─────────────────────────────────────────
  var input = document.getElementById('code-input');
  var form = document.getElementById('code-form');
  if (input) {
    // 복원
    try {
      var saved = sessionStorage.getItem(STORAGE_KEY);
      if (saved && !input.value) input.value = saved;
    } catch (e) { /* private mode 등 */ }

    // 자동 백업
    input.addEventListener('input', function() {
      try { sessionStorage.setItem(STORAGE_KEY, input.value); } catch (e) {}
    });
    input.addEventListener('paste', function() {
      // paste 후 input event 가 발화되지 않는 일부 환경 대비 — 다음 tick 에 save
      setTimeout(function() {
        try { sessionStorage.setItem(STORAGE_KEY, input.value); } catch (e) {}
      }, 0);
    });

    // 자동 focus (autofocus 속성이 안 먹는 일부 케이스 대비)
    setTimeout(function() {
      try { input.focus(); } catch (e) {}
    }, 50);
  }
  if (form) {
    form.addEventListener('submit', function() {
      try { sessionStorage.removeItem(STORAGE_KEY); } catch (e) {}
    });
  }

  // ── 폴링 ─────────────────────────────────────────────────
  // AWAITING_CODE / DONE / FAILED / CANCELED / IDLE 면 폴링 skip.
  if (!initial || POLLING_STATES.indexOf(initial) < 0) return;

  var lastState = initial;
  var timer = setInterval(function() {
    fetch('/env-setup/claude-login/status.json', { credentials: 'same-origin' })
      .then(function(r) { return r.json(); })
      .then(function(s) {
        if (s == null) { clearInterval(timer); return; }
        if (s.state !== lastState) {
          clearInterval(timer);
          window.location.reload();
        }
      })
      .catch(function() { /* 일시 오류 무시 */ });
  }, 1000);
})();
</script>
"""
        )
    }

    private fun stateLabel(state: String?, lang: String): Pair<String, String> {
        val t = { key: String -> Messages.t(lang, key) }
        return when (state) {
            null, "IDLE" -> t("env.state.idle") to "dim"
            "STARTING" -> t("env.state.starting") to "dim"
            "AWAITING_CODE" -> t("env.state.awaitingCode") to "ok"
            "VERIFYING" -> t("env.state.verifying") to "warn"
            "DONE" -> t("env.state.done") to "ok"
            "FAILED" -> t("env.state.failed") to "warn"
            "CANCELED" -> t("env.state.canceled") to "dim"
            else -> (state ?: "") to "dim"
        }
    }

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    /** JS 문자열 리터럴로 안전한 형태로 변환 (single-quoted, fully escaped). */
    private fun jsLit(s: String): String = "'${escJs(s)}'"

    // ────────────────────────────────────────────────────────────────────
    // 진행 페이지 (/env-setup/tasks/{taskId})
    // ────────────────────────────────────────────────────────────────────

    fun taskProgressPage(username: String, taskId: String, lang: String, embed: Boolean = false): String {
        val t = { key: String -> Messages.t(lang, key) }
        val safeId = esc(taskId)
        // JS 안에서 쓰일 i18n 문자열은 jsLit 으로 single-quoted literal 화.
        val jsConnecting = jsLit(t("env.task.connecting"))
        val jsConnected = jsLit(t("env.task.connected"))
        val jsInProgress = jsLit(t("env.task.inProgress"))
        val jsDone = jsLit(t("env.task.done"))
        val jsErrorPrefix = jsLit(t("env.task.errorPrefix"))
        val jsJustNow = jsLit(t("env.task.justNow"))
        val jsSecondsSuffix = jsLit(t("env.task.secondsAgoSuffix"))
        val jsEnded = jsLit(t("env.task.ended"))
        val jsConnLost = jsLit(t("env.task.connectionLost"))
        // %s 가 들어가는 메시지는 클라이언트에서 ${'$'}{total} 로 치환되도록 ___TOTAL___ sentinel 사용.
        val successTpl = Messages.t(lang, "env.task.successMsg", "___TOTAL___")
        val failureTpl = Messages.t(lang, "env.task.failureMsg", "___TOTAL___")
        val jsSuccessTpl = jsLit(successTpl)
        val jsFailureTpl = jsLit(failureTpl)
        return AdminTemplates.shell(
            title = t("env.task.title"),
            username = username,
            currentPath = "/env-setup",
            lang = lang,
            embed = embed,
            body = """
<header>
  <h1>${esc(t("env.task.title"))} <small class="dim" style="font-size:14px;font-weight:400">$safeId</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <div style="display:flex;flex-wrap:wrap;gap:14px;align-items:center">
      <span><strong>${esc(t("env.task.statusLabel"))}</strong> · <span id="job-status" class="dim">${esc(t("env.task.connecting"))}</span></span>
      <span><strong>${esc(t("env.task.elapsed"))}</strong> · <span id="job-elapsed" style="font-variant-numeric:tabular-nums">00:00:00</span></span>
      <span class="dim" style="font-size:12px"><span id="job-lines">0</span> ${esc(t("env.task.lastActivity"))} <span id="job-last">-</span></span>
    </div>
    ${AdminTemplates.backButton("/env-setup", t("env.task.backToEnv"))}
  </div>
  <p class="hint" id="progress-hint" style="margin-top:10px">${esc(t("env.task.intro"))}</p>
</div>

<div class="card">
  <h2>${esc(t("env.task.liveLog"))}</h2>
  <div id="job-log" class="console-log" aria-live="polite"></div>
</div>

<script>
(function() {
  var taskId = "$safeId";
  var logEl = document.getElementById('job-log');
  var statusEl = document.getElementById('job-status');
  var linesEl = document.getElementById('job-lines');
  var elapsedEl = document.getElementById('job-elapsed');
  var lastEl = document.getElementById('job-last');
  var hintEl = document.getElementById('progress-hint');
  var lineCount = 0;
  var startedAt = Date.now();   // WS open 시점에 0 으로 리셋
  var lastActivityAt = startedAt;
  var finished = false;
  var elapsedTimer = null;

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDuration(ms) {
    var s = Math.floor(ms / 1000);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var sec = s % 60;
    return pad2(h) + ':' + pad2(m) + ':' + pad2(sec);
  }
  function setStatus(text, cls) {
    statusEl.textContent = text;
    statusEl.className = cls || 'dim';
  }
  function tickElapsed() {
    if (finished) return;
    var now = Date.now();
    elapsedEl.textContent = fmtDuration(now - startedAt);
    var idleSec = Math.floor((now - lastActivityAt) / 1000);
    lastEl.textContent = idleSec < 5 ? $jsJustNow : (idleSec + $jsSecondsSuffix);
  }
  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
    lineCount += 1;
    linesEl.textContent = lineCount;
    lastActivityAt = Date.now();
    tickElapsed();
  }
  function classOfLevel(level) {
    if (level === 'ERROR' || level === 'STDERR') return 'err';
    if (level === 'WARN') return 'tool';
    if (level === 'STDOUT') return 'assistant';
    if (level === 'INFO') return 'sys';
    return 'sys';
  }
  function markFinished(ok, message) {
    finished = true;
    if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
    var total = fmtDuration(Date.now() - startedAt);
    elapsedEl.textContent = total;
    if (ok) {
      setStatus($jsDone, 'ok');
      hintEl.innerHTML = ($jsSuccessTpl).replace('___TOTAL___', total);
    } else {
      setStatus($jsErrorPrefix + (message ? ' · ' + message : ''), 'warn');
      hintEl.innerHTML = ($jsFailureTpl).replace('___TOTAL___', total);
    }
    lastEl.textContent = $jsEnded;
  }
  function renderFrame(f) {
    if (f.type === 'log') {
      append(classOfLevel(f.level), f.level, f.message);
      if (!finished) setStatus($jsInProgress, 'ok');
    } else if (f.type === 'done') {
      var ok = f.status === 'SUCCESS';
      append(ok ? 'sys' : 'err', 'done', f.status + (f.errorMessage ? ' · ' + f.errorMessage : ''));
      markFinished(ok, f.errorMessage);
    } else if (f.type === 'error') {
      append('err', 'ws', (f.code || '') + ': ' + (f.message || ''));
    }
  }
  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + location.host + '/ws/env-setup/' + taskId + '/logs');
    ws.onopen = function() {
      startedAt = Date.now();
      lastActivityAt = startedAt;
      setStatus($jsConnected, 'dim');
      append('sys', 'ws', 'connected');
      elapsedTimer = setInterval(tickElapsed, 1000);
      tickElapsed();
    };
    ws.onmessage = function(ev) {
      try { renderFrame(JSON.parse(ev.data)); }
      catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) {
      append('sys', 'ws', 'closed (' + ev.code + ')');
      if (!finished) {
        // 서버가 done 을 보내기 전에 끊긴 경우 — 알 수 없음으로 표시.
        setStatus($jsConnLost, 'warn');
        if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
      }
    };
    ws.onerror = function() { append('err', 'ws', 'error'); };
  }
  connect();
})();
</script>
"""
        )
    }

    /** POST 실패 시 inline 으로 안내. */
    fun errorBlurb(message: String, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        val htmlLang = if (lang == "ko") "ko" else "en"
        return """<!doctype html><html lang="$htmlLang"><head><meta charset="utf-8"><title>${esc(t("env.error.title"))}</title>
        <link rel="stylesheet" href="/static/admin.css?v=1.158.10"></head><body class="layout no-nav">
        <main class="content"><div class="auth-card"><h1>${esc(t("env.error.installFailed"))}</h1>
        <div class="error">${esc(message)}</div>
        ${AdminTemplates.backButton("/env-setup", t("env.error.backToEnv"))}</div></main></body></html>"""
    }
}
