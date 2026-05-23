package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.ClaudeLoginService
import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent

/**
 * 빌드환경 페이지 SSR 템플릿.
 *
 * v0.6.0 Phase A — 상태 카드 + 사용자 절차 안내.
 * v0.6.1 Phase B — 카드별 원클릭 설치 버튼 + "모두 설치/업데이트" 일괄 버튼
 *   + 진행 페이지 (실시간 WS 로그).
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

    fun envSetupPage(username: String, states: List<ComponentState>, claudeFlash: String? = null): String {
        val cards = states.joinToString("\n") { renderCard(it) }
        val flashHtml = claudeFlashBlurb(claudeFlash)
        return AdminTemplates.shell(
            title = "빌드환경",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">빌드환경</h1>
    <form method="post" action="/env-setup/install-all" style="display:inline"
          onsubmit="return confirm('자동 설치 가능한 모든 컴포넌트(Android SDK / MCP 등)를 순차로 설치/업데이트합니다. 진행 페이지로 이동합니다. 계속할까요?')">
      <button type="submit" class="primary" style="width:auto;padding:8px 18px">⚡ 모두 설치/업데이트</button>
    </form>
  </div>
</header>
$flashHtml

<div class="card" style="margin-bottom:16px">
  <h2>처음 사용하시나요?</h2>
  <p>도커 이미지는 의도적으로 슬림화되어 있어, 안드로이드 빌드에 필요한
  컴포넌트는 컨테이너 첫 부팅 후 사용자가 직접 다운로드해야 합니다.</p>
  <ol style="margin:8px 0 0 20px;line-height:1.8">
    <li><strong>이미지 내장 컴포넌트</strong> (JDK / Git / Node / Claude CLI) 는 이미 설치되어 있으므로 그대로 두세요.</li>
    <li><strong>Claude 로그인</strong> 은 OAuth 가 필요해 터미널에서 한 번만 <code>docker exec -it --user vibe vibe-coder-server claude login</code> 실행. (자동화 불가)</li>
    <li>위 우측 <strong>"모두 설치/업데이트"</strong> 버튼 또는 카드 개별 버튼으로 Android SDK / MCP 를 설치. 진행은 실시간 로그로 확인.</li>
    <li>설치가 모두 ✓ 로 바뀌면 <a href="/projects">/projects</a> 로 이동해 첫 프로젝트를 만들고 콘솔에서 Claude 에게 안드로이드 앱 생성을 부탁하세요.</li>
  </ol>
  <p class="hint">에뮬레이터(AVD) 는 LAN-only PoC 도구 특성상 기본 제공하지 않습니다. 실 디바이스(USB / 무선 ADB) 또는 호스트 PC 의 Android Studio 에뮬레이터를 추천합니다.</p>
</div>

<div class="card" style="margin-bottom:16px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
  <h2 style="color:var(--ok)">✅ 설치한 빌드환경은 이미지 pull 후에도 보존됩니다</h2>
  <p>Android SDK / Gradle 캐시 / Claude 인증은 <strong>Docker named volume</strong> 또는
  <strong>호스트 bind mount</strong> 에 저장되므로, 새 이미지로 서버를 업그레이드해도
  사라지지 않습니다.</p>
  <pre class="diff-block">docker pull siamakerlab/vibe-coder-server:&lt;새 버전&gt;
docker compose up -d --force-recreate</pre>
  <p class="hint">⚠️ <code>docker compose down -v</code> 는 named volume 까지 삭제합니다 (SDK 3~4GB 재다운로드). 일반 업그레이드 시 사용하지 마세요. 자세한 내용은 README 의 "빌드환경은 이미지를 갈아끼워도 보존됩니다" 섹션 참고.</p>
</div>

<section class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">
  $cards
</section>
"""
        )
    }

    private fun renderCard(s: ComponentState): String {
        val c = s.component
        // CLAUDE_AUTH 만 "로그인됨/로그인 필요" 로 표기, 나머지는 설치됨/미설치.
        val (badgeCls, badgeText) = badgeFor(c, s.status)
        val actionHtml = renderAction(c, s.status)
        return """<div class="card">
  <div style="display:flex;justify-content:space-between;align-items:start;gap:8px">
    <h2 style="margin-bottom:8px">${esc(c.displayName)}</h2>
    <span class="$badgeCls" style="white-space:nowrap;font-size:12px">${esc(badgeText)}</span>
  </div>
  <p class="dim" style="font-size:12px;margin:0 0 8px">${esc(c.sizeHint)}</p>
  <p style="font-size:13px;line-height:1.5">${esc(c.description)}</p>
  <p style="font-size:12px;color:var(--text-dim);margin:8px 0 0">${esc(s.message)}</p>
  $actionHtml
</div>"""
    }

    private fun badgeFor(c: SetupComponent, status: ComponentStatus): Pair<String, String> =
        if (c == SetupComponent.CLAUDE_AUTH) {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to "✓ 로그인됨"
                ComponentStatus.PARTIAL -> "warn" to "△ 부분 인증"
                ComponentStatus.MISSING -> "warn" to "✗ 로그인 필요"
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        } else {
            when (status) {
                ComponentStatus.INSTALLED -> "ok" to "✓ 설치됨"
                ComponentStatus.PARTIAL -> "warn" to "△ 일부 설치"
                ComponentStatus.MISSING -> "warn" to "✗ 미설치"
                ComponentStatus.UNKNOWN -> "dim" to "?"
            }
        }

    private fun renderAction(c: SetupComponent, status: ComponentStatus): String {
        return when (c) {
            // 이미지 내장 — 진단 실패 시에만 경고. 정상이면 액션 없음.
            SetupComponent.JAVA,
            SetupComponent.GIT,
            SetupComponent.NODE,
            SetupComponent.CLAUDE_CLI ->
                if (status == ComponentStatus.INSTALLED) ""
                else """<p class="hint" style="margin-top:8px">⚠ 이미지 내장 컴포넌트인데 진단 실패. 컨테이너 재기동 또는 이미지 재pull 을 시도하세요.</p>"""

            // Claude 로그인 — 세 가지 경로 제공.
            //  1) 터미널에서 직접 `claude login` (가장 표준).
            //  2) 다른 머신에서 받은 .credentials.json 업로드 (web-only 환경 대응).
            //  3) ANTHROPIC_API_KEY 등록 (OAuth 미사용 / API 키 사용자).
            // v0.7.0 — terminal 접근 불가능한 운영 환경 (외부 호스팅 / 모바일) 에서도
            // 100% 웹으로 인증 완료 가능. raw-shell UI 미사용 (CLAUDE.md §3 정책 준수).
            SetupComponent.CLAUDE_AUTH -> renderClaudeAuthActions(status)

            // Android SDK — 원클릭 설치 + 진행 페이지.
            SetupComponent.ANDROID_SDK -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> "재설치 / 업데이트"
                    ComponentStatus.PARTIAL -> "이어서 설치"
                    else -> "설치"
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                        onsubmit="return confirm('Android SDK 설치를 시작합니다 (3~4GB, 5~15분). 진행 페이지로 이동합니다. 계속할까요?')">
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">CLI 로 직접 실행하려면</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder-server vibe-doctor android</pre>
                </details>"""
            }

            SetupComponent.PLATFORM_TOOLS ->
                """<p class="hint" style="margin-top:8px">Android SDK 설치에 포함됩니다. 위 "Android SDK" 카드의 설치 버튼을 사용하세요.</p>"""

            SetupComponent.MCP_DEFAULTS ->
                """<a href="/env-setup/mcp" class="primary chip" style="display:inline-block;padding:8px 16px;margin-top:10px">MCP 카탈로그 열기 (50+) →</a>
                <p class="hint" style="margin-top:8px;font-size:12px">체크박스 다중 선택 + 토큰 입력 + 추천 별표. 카탈로그에 없는 MCP 도 컨테이너 직접 설치 시 영구 보존.</p>"""

            // Gradle — wrapper bootstrap 용. 최신 stable 자동 다운로드.
            SetupComponent.GRADLE -> {
                val label = when (status) {
                    ComponentStatus.INSTALLED -> "재설치"
                    ComponentStatus.PARTIAL -> "최신 버전으로 업데이트"
                    else -> "설치 (최신 stable)"
                }
                """<form method="post" action="/env-setup/${esc(c.id)}/install" style="margin-top:10px"
                       onsubmit="return confirm('Gradle 최신 stable 을 다운로드해 /home/vibe/.local/gradle 에 설치합니다 (~130MB). 신규 프로젝트의 wrapper bootstrap 에 사용됩니다. 계속할까요?')">
                  <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(label)}</button>
                </form>
                <p class="hint" style="margin-top:8px;font-size:12px">Wrapper bootstrap 도구. 한 번 설치 후엔 사용자 build.gradle.kts 의 wrapper 버전이 실제 빌드에 사용됨. 영구 보존 (bind mount).</p>"""
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // v0.7.0 — Claude 로그인 카드 (3-옵션 + flash blurb)
    // ────────────────────────────────────────────────────────────────────

    /** /env-setup?claude=<...> redirect 후 표시되는 한 줄 알림. */
    private fun claudeFlashBlurb(code: String?): String = when (code) {
        "uploaded" -> blurb("ok", "✓ Claude 자격증명 파일이 등록되었습니다. 콘솔에서 즉시 사용할 수 있습니다.")
        "api-key" -> blurb("ok", "✓ ANTHROPIC_API_KEY 가 등록되었습니다. 이후 모든 Claude 자식 프로세스가 자동 사용합니다.")
        "api-key-deleted" -> blurb("warn", "API 키 모드가 해제되었습니다. OAuth 자격증명 모드로 돌아갑니다.")
        else -> ""
    }

    private fun blurb(cls: String, text: String): String =
        """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.08);border-color:var(--$cls)">
        <p style="margin:0;color:var(--$cls)">${esc(text)}</p></div>"""

    private fun renderClaudeAuthActions(status: ComponentStatus): String {
        val statusHint = when (status) {
            ComponentStatus.INSTALLED -> """<p class="hint" style="margin-top:8px">이미 인증되어 있습니다. 토큰을 교체하거나 인증 방식을 바꾸려면 아래를 사용하세요.</p>"""
            else -> ""
        }
        return """
$statusHint

<div style="margin-top:10px;padding:10px;border:1px solid var(--ok);border-radius:6px;background:rgba(105,219,124,0.06)">
  <strong style="color:var(--ok)">★ 옵션 0 — 웹에서 한 번에 로그인</strong>
  <p class="hint" style="margin:6px 0 8px">브라우저만으로 OAuth 완료. 터미널/다른 머신 불요. 가장 빠릅니다.</p>
  <a href="/env-setup/claude-login" class="primary chip" style="padding:8px 16px;display:inline-block">웹으로 로그인 →</a>
</div>

<details style="margin-top:10px"><summary class="dim" style="cursor:pointer;font-size:13px">옵션 1 — 컨테이너 터미널에서 직접 로그인 (표준)</summary>
  <pre class="diff-block" style="margin-top:6px">docker exec -it --user vibe vibe-coder-server claude login</pre>
  <p class="hint">한 번만 진행. refresh token 으로 access token 은 자동 갱신됩니다. 터미널 접근이 가능하면 가장 단순.</p>
</details>

<details style="margin-top:10px" ${if (status != ComponentStatus.INSTALLED) "open" else ""}>
  <summary class="dim" style="cursor:pointer;font-size:13px">옵션 2 — 다른 머신에서 받은 <code>.credentials.json</code> 업로드</summary>
  <p class="hint" style="margin-top:6px">먼저 터미널이 있는 머신(노트북/데스크톱) 에서 <code>claude login</code> 으로 인증을 완료한 뒤,
  그 머신의 <code>~/.claude/.credentials.json</code> 을 아래에 업로드하세요. 컨테이너 터미널 접근이 불가능한 환경(원격 호스팅 / 모바일 운영)에서 사용.</p>
  <form method="post" action="/env-setup/claude-auth/upload" enctype="multipart/form-data"
        style="margin-top:8px;display:flex;flex-direction:column;gap:8px"
        onsubmit="return confirm('업로드한 파일이 vibe 홈의 .credentials.json 을 덮어씁니다 (기존 파일은 자동 백업). 계속할까요?')">
    <input type="file" name="credentials" accept=".json,application/json" required
           style="font-size:13px">
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">자격증명 업로드</button>
  </form>
  <p class="hint" style="font-size:11px;margin-top:6px">⚠ 자격증명은 토큰을 포함합니다. 신뢰할 수 있는 네트워크에서만 업로드하세요. 파일 권한은 0600 으로 설정됩니다.</p>
</details>

<details style="margin-top:10px">
  <summary class="dim" style="cursor:pointer;font-size:13px">옵션 3 — <code>ANTHROPIC_API_KEY</code> 사용 (OAuth 미사용)</summary>
  <p class="hint" style="margin-top:6px">Anthropic API 종량제 결제 사용자 또는 Pro/Max 구독을 OAuth 없이 쓰고 싶은 경우.
  키는 <a href="https://console.anthropic.com/" target="_blank" rel="noreferrer">console.anthropic.com</a> 의 API Keys 에서 발급.
  키 등록 즉시 모든 Claude 자식 프로세스가 이 키를 환경변수로 받아 동작합니다 (컨테이너 재기동 불필요).</p>
  <form method="post" action="/env-setup/claude-auth/api-key"
        style="margin-top:8px;display:flex;flex-direction:column;gap:8px"
        onsubmit="return confirm('API 키를 vibe 홈의 .env.api-key 파일에 저장합니다. 등록 후엔 OAuth 자격증명보다 API 키가 우선합니다. 계속할까요?')">
    <input type="password" name="apiKey" placeholder="sk-ant-..." required minlength="20" autocomplete="off"
           style="font-size:13px;padding:6px 8px">
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;align-self:flex-start">API 키 등록</button>
  </form>
  <form method="post" action="/env-setup/claude-auth/api-key/delete"
        style="margin-top:6px"
        onsubmit="return confirm('API 키를 삭제하면 OAuth 자격증명 모드로 돌아갑니다. 계속할까요?')">
    <button type="submit" style="width:auto;padding:6px 12px;font-size:12px">API 키 모드 해제</button>
  </form>
  <p class="hint" style="font-size:11px;margin-top:6px">⚠ API 키는 OAuth 와 빌링이 다릅니다 (Pro/Max 구독이 아니라 API 종량제). 본인의 결제 상황을 확인하세요.</p>
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
    fun claudeLoginPage(username: String, state: ClaudeLoginService.SessionDto?): String {
        val (statusText, statusCls) = stateLabel(state?.state)
        val urlBlock = if (state?.url != null) {
            """<div class="card" style="margin-top:12px;background:rgba(80,150,255,0.08)">
              <strong>2. 아래 URL 을 새 탭에서 열어 인증하세요</strong>
              <div style="margin:8px 0;display:flex;gap:8px;flex-wrap:wrap;align-items:center">
                <a href="${esc(state.url)}" target="_blank" rel="noreferrer" class="primary chip" style="padding:8px 14px">새 탭에서 열기 ↗</a>
                <button type="button" class="chip" onclick="navigator.clipboard.writeText(${"'" + escJs(state.url) + "'"});this.textContent='✓ 복사됨';setTimeout(()=>this.textContent='URL 복사',2000)">URL 복사</button>
              </div>
              <pre class="diff-block" style="margin:0;font-size:11px;overflow-x:auto">${esc(state.url)}</pre>
              <p class="hint" style="margin-top:8px">Anthropic 페이지에서 인증을 완료하면 화면 또는 콜백 URL 에서 <strong>authorization code</strong> 가 표시됩니다. 그 코드를 아래 폼에 paste 하세요.</p>
            </div>"""
        } else ""

        val codeForm = if (state?.state == "AWAITING_CODE") {
            // v0.10.1 — id="code-input" / id="code-form" 추가 (JS 가 autofocus + sessionStorage
            // 복원/백업/clear 에 사용). autofocus 속성도 같이 — JS 비활성 환경 대비.
            """<div class="card" style="margin-top:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
              <strong>3. 받은 코드를 paste 후 제출</strong>
              <form method="post" action="/env-setup/claude-login/submit" id="code-form"
                    style="margin-top:8px;display:flex;flex-direction:column;gap:8px">
                <input type="text" name="code" id="code-input"
                       placeholder="여기에 authorization code 를 paste" required autofocus
                       autocomplete="off" autocapitalize="off" spellcheck="false"
                       style="font-size:13px;padding:8px;font-family:ui-monospace,Menlo,monospace">
                <div style="display:flex;gap:8px">
                  <button type="submit" class="primary" style="padding:8px 18px">제출</button>
                  <button type="submit" formaction="/env-setup/claude-login/cancel" formmethod="post"
                          formnovalidate style="padding:8px 14px">취소</button>
                </div>
              </form>
              <p class="hint" style="font-size:11px;margin-top:6px">
                ※ 입력 중에는 페이지가 자동 갱신되지 않습니다. 코드 입력 후 "제출" 버튼을 직접 눌러주세요.
              </p>
            </div>"""
        } else ""

        val finalBlock = when (state?.state) {
            "DONE" -> """<div class="card" style="margin-top:12px;background:rgba(105,219,124,0.10);border-color:var(--ok)">
              <strong style="color:var(--ok)">✓ 로그인 완료</strong>
              <p style="margin-top:6px">자격증명이 vibe 홈에 저장되었습니다. 콘솔에서 즉시 사용 가능합니다.</p>
              <a href="/env-setup" class="chip primary" style="padding:8px 16px;margin-top:8px;display:inline-block">← 빌드환경으로</a>
            </div>"""
            "FAILED" -> """<div class="card" style="margin-top:12px;background:rgba(255,150,80,0.08);border-color:var(--warn)">
              <strong style="color:var(--warn)">✗ 실패</strong>
              <p style="margin-top:6px">${esc(state.errorMessage ?: "원인 미상")}</p>
              <p class="hint" style="margin-top:8px">웹 로그인이 실패하면 옵션 B(자격증명 업로드) 또는 옵션 C(API 키)를 사용하세요. <a href="/env-setup">빌드환경으로 돌아가기</a>.</p>
              <form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
                <button type="submit" class="primary" style="padding:8px 14px">다시 시도</button>
              </form>
            </div>"""
            "CANCELED" -> """<div class="card" style="margin-top:12px;background:rgba(160,160,160,0.05)">
              <strong class="dim">취소됨</strong>
              <form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
                <button type="submit" class="primary" style="padding:8px 14px">새 세션 시작</button>
              </form>
            </div>"""
            else -> ""
        }

        val startForm = if (state == null || state.state in listOf("DONE", "FAILED", "CANCELED")) {
            """<form method="post" action="/env-setup/claude-login/start" style="margin-top:8px">
              <button type="submit" class="primary" style="padding:10px 20px">▶ 1. 로그인 시작</button>
            </form>"""
        } else ""

        val lastLinesBlock = if (!state?.lastLines.isNullOrEmpty()) {
            val lines = state!!.lastLines.takeLast(8).joinToString("\n") { esc(it) }
            """<details style="margin-top:12px"><summary class="dim" style="cursor:pointer;font-size:12px">자식 프로세스 출력 (디버그)</summary>
              <pre class="diff-block" style="margin-top:6px;font-size:11px;max-height:160px;overflow:auto">$lines</pre>
            </details>"""
        } else ""

        return AdminTemplates.shell(
            title = "Claude 웹 로그인",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <h1>Claude 웹 로그인 <small class="dim" style="font-size:14px;font-weight:400">반자동 OAuth</small></h1>
</header>

<div class="card" style="margin-bottom:12px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <div><strong>상태</strong> · <span id="state-chip" class="$statusCls">${esc(statusText)}</span></div>
    <a href="/env-setup" class="chip chip-link">← 빌드환경</a>
  </div>
  <p class="hint" style="margin-top:8px">브라우저만으로 OAuth 인증을 완료합니다. 터미널 / 다른 머신 / 파일 업로드 모두 불요. 사용자가 임의 shell 명령을 칠 수 있는 UI 는 아니며, 정해진 OAuth 코드 한 줄 입력 폼입니다.</p>
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

    private fun stateLabel(state: String?): Pair<String, String> = when (state) {
        null, "IDLE" -> "● 대기" to "dim"
        "STARTING" -> "● 시작 중 (URL 대기)" to "dim"
        "AWAITING_CODE" -> "▶ 코드 입력 대기" to "ok"
        "VERIFYING" -> "● 검증 중" to "warn"
        "DONE" -> "✓ 완료" to "ok"
        "FAILED" -> "✗ 실패" to "warn"
        "CANCELED" -> "취소됨" to "dim"
        else -> state to "dim"
    }

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    // ────────────────────────────────────────────────────────────────────
    // 진행 페이지 (/env-setup/tasks/{taskId})
    // ────────────────────────────────────────────────────────────────────

    fun taskProgressPage(username: String, taskId: String): String {
        val safeId = esc(taskId)
        return AdminTemplates.shell(
            title = "설치 진행",
            username = username,
            currentPath = "/env-setup",
            body = """
<header>
  <h1>설치 진행 <small class="dim" style="font-size:14px;font-weight:400">$safeId</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
    <div style="display:flex;flex-wrap:wrap;gap:14px;align-items:center">
      <span><strong>상태</strong> · <span id="job-status" class="dim">연결 중…</span></span>
      <span><strong>경과</strong> · <span id="job-elapsed" style="font-variant-numeric:tabular-nums">00:00:00</span></span>
      <span class="dim" style="font-size:12px"><span id="job-lines">0</span> 줄 · 마지막 활동 <span id="job-last">-</span></span>
    </div>
    <a href="/env-setup" class="chip chip-link">← 빌드환경</a>
  </div>
  <p class="hint" id="progress-hint" style="margin-top:10px">설치 시간은 작업/네트워크에 따라 5초~수십 분까지 다양합니다. Android SDK 첫 설치는 3~4GB 다운로드로 5~15분이 일반적.</p>
</div>

<div class="card">
  <h2>실시간 로그</h2>
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
    lastEl.textContent = idleSec < 5 ? '방금' : (idleSec + '초 전');
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
      setStatus('✓ 완료', 'ok');
      hintEl.innerHTML = '✅ 완료 (총 ' + total + ') — <a href="/env-setup">빌드환경 페이지</a>로 돌아가 다음 단계를 확인하세요.';
    } else {
      setStatus('✗ 오류' + (message ? ' · ' + message : ''), 'warn');
      hintEl.innerHTML = '✗ 실패 (총 ' + total + ') — 위 로그에서 원인을 확인한 뒤 다시 시도하세요.';
    }
    lastEl.textContent = '종료됨';
  }
  function renderFrame(f) {
    if (f.type === 'log') {
      append(classOfLevel(f.level), f.level, f.message);
      if (!finished) setStatus('▶ 진행 중', 'ok');
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
      setStatus('● 연결됨, 작업 시작 대기 중…', 'dim');
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
        setStatus('● 연결 끊김 (재기동 또는 네트워크)', 'warn');
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
    fun errorBlurb(message: String): String =
        """<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>오류</title>
        <link rel="stylesheet" href="/static/admin.css"></head><body class="layout no-nav">
        <main class="content"><div class="auth-card"><h1>설치 시작 실패</h1>
        <div class="error">${esc(message)}</div>
        <a href="/env-setup" class="primary-link">← 빌드환경으로</a></div></main></body></html>"""
}
