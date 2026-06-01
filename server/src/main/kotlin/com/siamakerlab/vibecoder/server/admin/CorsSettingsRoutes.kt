package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.CorsSection
import com.siamakerlab.vibecoder.server.i18n.Messages
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
 *
 * v0.87.0 Phase 64.11 — 모든 사용자 가시 한국어 i18n 키화 (cors.*).
 */
fun Routing.corsSettingsRoutes(authDeps: AdminRoutesDeps) {
    get("/settings/cors") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.respondText(
            CorsSettingsTemplates.page(sess.username, authDeps.config.cors, csrf = sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }
}

object CorsSettingsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(username: String, cors: CorsSection, csrf: String? = null, lang: String, embed: Boolean = false): String {
        val t = { key: String -> Messages.t(lang, key) }
        val hosts = cors.allowedHosts
        val isAnyHost = hosts.contains("*")
        val statusColor = if (isAnyHost) "warn" else "ok"
        val statusText = if (isAnyHost)
            t("cors.statusAnyHost")
        else
            Messages.t(lang, "cors.statusExplicit", hosts.size)
        val hostRows = if (hosts.isEmpty())
            """<tr><td colspan="2" class="dim" style="text-align:center;padding:14px">${esc(t("cors.empty"))}</td></tr>"""
        else hosts.joinToString("\n") { h ->
            """<tr><td><code style="font-family:ui-monospace,Menlo,monospace">${esc(h)}</code></td>
            <td class="dim" style="font-size:12px">${esc(describeHost(h, lang))}</td></tr>"""
        }

        return AdminTemplates.shell(
            title = t("cors.title"),
            username = username,
            currentPath = "/settings/cors",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("cors.title"))}</h1>
    <a href="/settings" class="chip chip-link">${esc(t("cors.backToSettings"))}</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">
    ${esc(t("cors.intro"))}
  </p>
</header>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("cors.current"))}</h2>
  <p><strong class="$statusColor">●</strong> ${esc(statusText)}</p>
  <p><strong>allowCredentials:</strong> <code>${cors.allowCredentials}</code>
  <span class="dim">${esc(t("cors.allowCreds"))}</span></p>

  <table style="width:100%;border-collapse:collapse;margin-top:12px">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">${esc(t("table.allowedHost"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("cors.col.meaning"))}</th>
      </tr>
    </thead>
    <tbody>
      $hostRows
    </tbody>
  </table>
</div>

<div class="card" style="margin-bottom:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0;color:var(--warn)">${esc(t("cors.warn.title"))}</h2>
  <ul style="margin:6px 0 0 20px;line-height:1.7;font-size:13px">
    <li>${t("cors.warn.item1")}</li>
    <li>${t("cors.warn.item2")}</li>
    <li>${t("cors.warn.item3")}</li>
  </ul>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("cors.howChange.title"))}</h2>
  <p>${esc(t("cors.howChange.intro"))}</p>

  <h3 style="margin-top:14px">${esc(t("cors.optA.title"))}</h3>
  <p>${t("cors.optA.desc")}</p>
  <pre class="diff-block">VIBECODER_CORS_ALLOWED_HOSTS=https://my-app.example.com,https://staging.example.com,*.dev.example.com
VIBECODER_CORS_ALLOW_CREDENTIALS=true</pre>
  <p>${esc(t("cors.optA.restart"))}</p>
  <pre class="diff-block">docker compose up -d --force-recreate</pre>
  <p class="hint">${esc(t("cors.optA.note"))}</p>

  <h3 style="margin-top:14px">${esc(t("cors.optB.title"))}</h3>
  <p>${t("cors.optB.desc")}</p>
  <pre class="diff-block">mkdir -p vibe-coder-data/config
cat &gt; vibe-coder-data/config/server.yml &lt;&lt;'YML'
cors:
  allowedHosts:
    - "https://my-app.example.com"
    - "*.dev.example.com"
  allowCredentials: true
YML</pre>
  <p>${esc(t("cors.optB.volumes"))}</p>
  <pre class="diff-block">- ./vibe-coder-data/config:/config
  environment:
    VIBECODER_CONFIG_DIR: /config</pre>
  <p>${esc(t("cors.optB.restart"))}</p>
  <pre class="diff-block">docker compose up -d --force-recreate</pre>
  <p class="hint">${esc(t("cors.optB.note"))}</p>

  <h3 style="margin-top:14px">${esc(t("cors.verify.title"))}</h3>
  <p>${esc(t("cors.verify.desc"))}</p>
  <pre class="diff-block">curl -i -H "Origin: https://my-app.example.com" http://localhost:17880/api/server/status \
  -H "Authorization: Bearer ${'$'}TOKEN" | grep -i 'access-control-'</pre>
</div>

<div class="card" style="background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">${esc(t("cors.pattern.title"))}</h2>
  <table style="width:100%;border-collapse:collapse;font-size:13px">
    <thead><tr style="border-bottom:1px solid #333">
      <th style="text-align:left;padding:6px">${esc(t("cors.pattern.col.pattern"))}</th>
      <th style="text-align:left;padding:6px">${esc(t("cors.pattern.col.behavior"))}</th>
    </tr></thead>
    <tbody>
      <tr><td><code>*</code></td><td>${esc(t("cors.pattern.anyHost"))}</td></tr>
      <tr><td><code>example.com</code></td><td><code>http://example.com</code> + <code>https://example.com</code></td></tr>
      <tr><td><code>https://example.com</code></td><td>${t("cors.pattern.httpsOnly")}</td></tr>
      <tr><td><code>http://example.com:8080</code></td><td>${esc(t("cors.pattern.portExplicit"))}</td></tr>
      <tr><td><code>*.example.com</code></td><td>${esc(t("cors.pattern.allSubdomains"))}</td></tr>
    </tbody>
  </table>
</div>
"""
        )
    }

    private fun describeHost(h: String, lang: String): String {
        val t = { key: String -> Messages.t(lang, key) }
        return when {
            h == "*" -> t("cors.desc.anyHostFull")
            h.startsWith("https://") -> t("cors.desc.httpsOnly")
            h.startsWith("http://") -> t("cors.desc.httpOnly")
            h.startsWith("*.") -> "wildcard subdomain (http + https)"
            else -> t("cors.desc.bothProtocols")
        }
    }
}
