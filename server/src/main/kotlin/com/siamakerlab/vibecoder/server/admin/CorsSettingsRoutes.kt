package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.CorsSection
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val log = KotlinLogging.logger {}

/**
 * v0.12.0 — `/settings/cors` 읽기 전용 페이지.
 *
 * **의도적으로 UI 직접 편집은 제외**. CORS 정책 변경은 보안 영향이 커서
 * docker compose 의 env 또는 server.yml 만 편집 가능. 사용자는 이 페이지에서
 * 현재 적용된 정책을 확인하고, 변경 방법을 step-by-step 안내받음.
 *
 * 정책 변경 후엔 컨테이너 재기동이 필요 (Ktor CORS install 은 startup 시 1회).
 */
fun Routing.corsSettingsRoutes(authDeps: AdminRoutesDeps) {
    get("/settings/cors") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            CorsSettingsTemplates.page(sess.username, authDeps.config.cors, csrf = sess.csrf),
            ContentType.Text.Html,
        )
    }
}

object CorsSettingsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(username: String, cors: CorsSection, csrf: String? = null): String {
        val hosts = cors.allowedHosts
        val isAnyHost = hosts.contains("*")
        val statusColor = if (isAnyHost) "warn" else "ok"
        val statusText = if (isAnyHost)
            "anyHost (모든 origin 허용) — LAN 격리 환경 기본값"
        else
            "${hosts.size}개 origin 명시 허용 — 외부 노출 환경 권장"
        val hostRows = if (hosts.isEmpty())
            """<tr><td colspan="2" class="dim" style="text-align:center;padding:14px">없음</td></tr>"""
        else hosts.joinToString("\n") { h ->
            """<tr><td><code style="font-family:ui-monospace,Menlo,monospace">${esc(h)}</code></td>
            <td class="dim" style="font-size:12px">${esc(describeHost(h))}</td></tr>"""
        }

        return AdminTemplates.shell(
            title = "CORS 정책",
            username = username,
            currentPath = "/settings/cors",
            csrf = csrf,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">CORS 정책</h1>
    <a href="/settings" class="chip chip-link">← 설정</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">
    Cross-Origin Resource Sharing — 외부 웹 앱이 vibe-coder API 를 호출할 수 있는
    origin 목록.
  </p>
</header>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">현재 상태</h2>
  <p><strong class="$statusColor">●</strong> $statusText</p>
  <p><strong>allowCredentials:</strong> <code>${cors.allowCredentials}</code>
  <span class="dim">— credentials (cookies/Authorization) 포함 요청 허용 여부</span></p>

  <table style="width:100%;border-collapse:collapse;margin-top:12px">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">Allowed Host</th>
        <th style="text-align:left;padding:8px">의미</th>
      </tr>
    </thead>
    <tbody>
      $hostRows
    </tbody>
  </table>
</div>

<div class="card" style="margin-bottom:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0;color:var(--warn)">⚠ 보안 경고</h2>
  <ul style="margin:6px 0 0 20px;line-height:1.7;font-size:13px">
    <li><code>*</code> (anyHost) 는 <strong>LAN 격리 환경 가정</strong>. 외부 IP
        로 노출하면 임의 origin 의 web 앱이 사용자의 brower 세션을 통해 vibe-coder API
        를 호출 (CSRF 위험).</li>
    <li>외부 노출 시 <strong>신뢰 origin 만 명시</strong>. wildcard subdomain
        (<code>*.example.com</code>) 도 가능.</li>
    <li><code>allowCredentials=true</code> 와 <code>allowedHosts=["*"]</code> 조합은
        Ktor 가 거부 (CORS spec 위반). 둘 중 하나만.</li>
  </ul>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">변경 방법 — 두 가지</h2>
  <p>UI 직접 편집은 의도적으로 제외 (보안 정책 우발 변경 방지). 둘 중 하나로 진행:</p>

  <h3 style="margin-top:14px">옵션 A — docker compose env (운영 권장)</h3>
  <p><code>docker/.env</code> (또는 compose.yml 의 environment) 에 콤마 구분 host
  목록 설정:</p>
  <pre class="diff-block">VIBECODER_CORS_ALLOWED_HOSTS=https://my-app.example.com,https://staging.example.com,*.dev.example.com
VIBECODER_CORS_ALLOW_CREDENTIALS=true</pre>
  <p>그 다음 컨테이너 재기동:</p>
  <pre class="diff-block">docker compose up -d --force-recreate</pre>
  <p class="hint">env 가 server.yml 의 값을 override 합니다.</p>

  <h3 style="margin-top:14px">옵션 B — server.yml 직접 편집</h3>
  <p>이미지 안의 <code>/opt/vibe-coder/lib/...</code> 가 아니라 <strong>외부 config
  디렉토리</strong> 를 만들어 마운트:</p>
  <pre class="diff-block">mkdir -p vibe-coder-data/config
cat &gt; vibe-coder-data/config/server.yml &lt;&lt;'YML'
cors:
  allowedHosts:
    - "https://my-app.example.com"
    - "*.dev.example.com"
  allowCredentials: true
YML</pre>
  <p>compose.yml 의 volumes 에 추가:</p>
  <pre class="diff-block">- ./vibe-coder-data/config:/config
  environment:
    VIBECODER_CONFIG_DIR: /config</pre>
  <p>재기동:</p>
  <pre class="diff-block">docker compose up -d --force-recreate</pre>
  <p class="hint">외부 server.yml 이 있으면 이미지 내장 default 보다 우선.</p>

  <h3 style="margin-top:14px">검증</h3>
  <p>재기동 후 이 페이지를 새로고침하면 적용된 정책이 위 표에 표시됩니다.</p>
  <pre class="diff-block">curl -i -H "Origin: https://my-app.example.com" http://localhost:17880/api/server/status \
  -H "Authorization: Bearer ${'$'}TOKEN" | grep -i 'access-control-'</pre>
</div>

<div class="card" style="background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">Host 패턴 예시</h2>
  <table style="width:100%;border-collapse:collapse;font-size:13px">
    <thead><tr style="border-bottom:1px solid #333">
      <th style="text-align:left;padding:6px">패턴</th>
      <th style="text-align:left;padding:6px">동작</th>
    </tr></thead>
    <tbody>
      <tr><td><code>*</code></td><td>anyHost (모든 origin)</td></tr>
      <tr><td><code>example.com</code></td><td><code>http://example.com</code> + <code>https://example.com</code></td></tr>
      <tr><td><code>https://example.com</code></td><td><code>https://example.com</code> 만</td></tr>
      <tr><td><code>http://example.com:8080</code></td><td>포트 명시</td></tr>
      <tr><td><code>*.example.com</code></td><td>모든 subdomain</td></tr>
    </tbody>
  </table>
</div>
"""
        )
    }

    private fun describeHost(h: String): String = when {
        h == "*" -> "anyHost — 모든 origin"
        h.startsWith("https://") -> "https 만"
        h.startsWith("http://") -> "http 만"
        h.startsWith("*.") -> "wildcard subdomain (http + https)"
        else -> "http + https 모두"
    }
}
