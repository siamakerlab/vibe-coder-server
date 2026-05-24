package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private val log = KotlinLogging.logger {}

/**
 * v0.32.0 — `/projects/{id}/deps` — Gradle 의존성 트리 + 좌표 추출.
 *
 * 캐싱: 호출당 새로 실행. Gradle dependencies 는 의외로 빠르고 (캐시 따뜻하면
 * 수 초), 결과를 stale 하게 보관하면 사용자가 헷갈림.
 */
fun Routing.dependencyAuditRoutes(authDeps: AdminRoutesDeps, projects: ProjectService, svc: DependencyAudit) {
    get("/projects/{id}/deps") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val moduleName = call.request.queryParameters["module"]?.ifBlank { null } ?: p.moduleName
        val configuration = call.request.queryParameters["config"]?.ifBlank { null } ?: "releaseRuntimeClasspath"
        val run = call.request.queryParameters["run"] == "1"
        val result = if (run) svc.audit(id, moduleName, configuration) else null
        call.respondText(
            DependencyAuditTemplates.page(sess.username, p, moduleName, configuration, result, sess.csrf),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/deps") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val form = call.receiveParameters()
        val moduleName = form["module"]?.trim()?.ifBlank { null } ?: "app"
        val configuration = form["config"]?.trim()?.ifBlank { null } ?: "releaseRuntimeClasspath"
        call.respondRedirect("/projects/$id/deps?module=$moduleName&config=$configuration&run=1")
    }
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

private object DependencyAuditTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        configuration: String,
        result: DependencyAudit.Result?,
        csrf: String?,
    ): String {
        val resultHtml = when {
            result == null -> """<p class="hint">아래 "실행" 버튼으로 의존성 트리를 가져옵니다 (10-90s).</p>"""
            !result.ok -> """
                <div class="error">⚠ ${esc(result.errorMessage ?: "audit 실패")} (${result.durationMs}ms)</div>
                ${if (result.rawOutput != null) """<details><summary>raw output</summary><pre class="diff-block">${esc(result.rawOutput)}</pre></details>""" else ""}
            """
            else -> {
                val coords = result.coordinates
                val coordsRows = if (coords.isEmpty()) {
                    """<tr><td colspan="3" class="dim">의존성을 추출하지 못했습니다 (raw output 확인).</td></tr>"""
                } else coords.joinToString("") { c ->
                    """<tr><td><code>${esc(c.group)}</code></td><td><code>${esc(c.name)}</code></td><td><code>${esc(c.version)}</code></td></tr>"""
                }
                """
                <div class="ok-banner">✓ ${coords.size}개 distinct 의존성 추출 (${result.durationMs}ms)</div>
                <table class="devices" style="margin-top:10px">
                  <thead><tr><th>group</th><th>name</th><th>version</th></tr></thead>
                  <tbody>$coordsRows</tbody>
                </table>
                <details style="margin-top:10px"><summary>raw output (200 KB cap)</summary><pre class="diff-block">${esc(result.rawOutput ?: "")}</pre></details>
                """
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Dependencies",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>의존성 audit
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)}) · v0.32.0</small>
  </h1>
</header>

<form method="post" action="/projects/${esc(p.id)}/deps" class="card" style="display:grid;grid-template-columns:1fr 1fr auto;gap:8px;align-items:end;margin-bottom:14px">
  ${CsrfTokens.hiddenInput(csrf)}
  <label style="margin:0">Module
    <input name="module" value="${esc(moduleName)}" placeholder="app">
  </label>
  <label style="margin:0">Configuration
    <input name="config" value="${esc(configuration)}" placeholder="releaseRuntimeClasspath">
  </label>
  <div>
    <button type="submit" class="primary" style="padding:8px 14px">실행</button>
  </div>
</form>

$resultHtml

<p class="hint" style="margin-top:14px;font-size:12px">
  좌표는 <code>group:name:version</code> 만 표시 — 알려진 CVE 매칭은 후속
  minor (osv-scanner / OWASP dependencyCheckAnalyze 통합 검토).
  타임아웃 90 s, raw output 200 KB cap.
</p>
"""
        )
    }
}
