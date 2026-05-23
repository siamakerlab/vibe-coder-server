package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent

/**
 * 빌드환경 페이지 SSR 템플릿.
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

    fun envSetupPage(username: String, states: List<ComponentState>): String {
        val cards = states.joinToString("\n") { renderCard(it) }
        return AdminTemplates.shell(
            title = "빌드환경",
            username = username,
            currentPath = "/env-setup",
            body = """
<header><h1>빌드환경</h1></header>

<div class="card" style="margin-bottom:16px">
  <h2>처음 사용하시나요?</h2>
  <p>도커 이미지는 의도적으로 슬림화되어 있어, 안드로이드 빌드에 필요한
  컴포넌트는 컨테이너 첫 부팅 후 사용자가 직접 다운로드해야 합니다.
  아래 순서를 따라 진행하세요.</p>
  <ol style="margin:8px 0 0 20px;line-height:1.8">
    <li><strong>이미지 내장 컴포넌트</strong> (JDK / Git / Node / Claude CLI) 는 이미 설치되어 있으므로 그대로 두세요.</li>
    <li><strong>Claude 로그인</strong> 카드의 안내에 따라 컨테이너 터미널에서 한 번만 <code>claude login</code> 실행.</li>
    <li><strong>Android SDK</strong> 카드의 "설치" 버튼을 눌러 cmdline-tools + platform-tools(ADB) + platforms;android-35 + build-tools 를 일괄 설치 (3~4GB, 5~15분).</li>
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
        val (badgeCls, badgeText) = when (s.status) {
            ComponentStatus.INSTALLED -> "ok" to "✓ 설치됨"
            ComponentStatus.PARTIAL -> "warn" to "△ 일부 설치"
            ComponentStatus.MISSING -> "warn" to "✗ 미설치"
            ComponentStatus.UNKNOWN -> "dim" to "?"
        }
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

    private fun renderAction(c: SetupComponent, status: ComponentStatus): String {
        // Phase A — 실제 설치 트리거(POST)는 v0.6.0 Phase B 에서 추가.
        // 지금은 "어떻게 설치하는지" 명령어를 그대로 보여준다.
        return when (c) {
            SetupComponent.JAVA,
            SetupComponent.GIT,
            SetupComponent.NODE,
            SetupComponent.CLAUDE_CLI ->
                if (status == ComponentStatus.INSTALLED) ""
                else """<p class="hint" style="margin-top:8px">⚠ 이미지 내장 컴포넌트인데 진단 실패. 컨테이너 재기동 또는 이미지 재pull 을 시도하세요.</p>"""

            SetupComponent.CLAUDE_AUTH -> {
                if (status == ComponentStatus.INSTALLED) ""
                else """<details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">설치 방법</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder claude login</pre>
                  <p class="hint">OAuth 콜백을 위해 터미널에서 직접 실행해 주세요. 완료 후 이 페이지를 새로고침.</p>
                </details>"""
            }

            SetupComponent.ANDROID_SDK -> {
                if (status == ComponentStatus.INSTALLED) ""
                else """<details style="margin-top:8px" open><summary class="dim" style="cursor:pointer;font-size:12px">설치 방법</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder vibe-doctor android</pre>
                  <p class="hint">sdkmanager 가 라이선스를 자동 수락하고 cmdline-tools → platform-tools → platforms;android-35 → build-tools 를 순서대로 받습니다. <strong>3~4GB, 5~15분 소요.</strong></p>
                  <p class="hint">v0.6.0 Phase B 에서 이 카드에 "원터치 설치" 버튼 + 실시간 progress 가 추가될 예정입니다.</p>
                </details>"""
            }

            SetupComponent.PLATFORM_TOOLS -> {
                if (status == ComponentStatus.INSTALLED) ""
                else """<p class="hint" style="margin-top:8px">Android SDK 설치에 포함됩니다. 위 "Android SDK" 카드를 사용하세요.</p>"""
            }

            SetupComponent.MCP_DEFAULTS ->
                """<details style="margin-top:8px"><summary class="dim" style="cursor:pointer;font-size:12px">설치 방법</summary>
                  <pre class="diff-block" style="margin-top:6px">docker exec -it vibe-coder vibe-doctor mcp</pre>
                  <p class="hint">filesystem / sqlite / fetch / playwright 등을 개별 동의 후 설치합니다.</p>
                </details>"""
        }
    }
}
