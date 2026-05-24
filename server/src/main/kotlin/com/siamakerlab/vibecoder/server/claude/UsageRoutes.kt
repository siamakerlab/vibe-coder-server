package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.projects.ProjectService
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.time.Duration
import java.time.Instant

/**
 * v0.47.0 — `/usage` page exposing the raw Claude `/status` output we already capture for
 * threshold alerts. Renders the most recent snapshot per project verbatim so any cache
 * hit/miss / billing context Anthropic ships in the future is immediately visible without
 * server changes.
 *
 * Admin-only — the raw text can include the user's plan + quota detail.
 */
fun Routing.usageRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    statusService: ClaudeStatusService,
) {
    get("/usage") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get

        val snapshots = statusService.allRawSnapshots()
        val allProjects = projects.list().associateBy { it.id }
        val body = renderUsagePage(snapshots, allProjects)
        call.respondText(
            AdminTemplates.shell(
                title = "Claude 사용량 / Cache 조회",
                username = sess.username,
                currentPath = "/usage",
                csrf = sess.csrf,
                body = body,
            ),
            ContentType.Text.Html,
        )
    }
}

private fun renderUsagePage(
    snapshots: Map<String, com.siamakerlab.vibecoder.server.claude.ClaudeStatusService.RawSnapshot>,
    projects: Map<String, com.siamakerlab.vibecoder.shared.dto.ProjectDto>,
): String {
    val now = Instant.now()
    val sections = if (snapshots.isEmpty()) {
        """<div class="card dim" style="text-align:center;padding:24px">
          아직 캡처된 /status snapshot 이 없습니다. ClaudeUsageMonitor 의 다음
          폴링 사이클 (기본 5분) 또는 사용자가 콘솔에 진입할 때 자동으로 채워집니다.
        </div>"""
    } else snapshots.entries.sortedBy { it.key }.joinToString("\n") { (pid, snap) ->
        val projectName = projects[pid]?.name ?: "(deleted)"
        val ageSec = Duration.between(snap.capturedAt, now).seconds.coerceAtLeast(0)
        val ageLabel = when {
            ageSec < 60 -> "${ageSec}s ago"
            ageSec < 3600 -> "${ageSec / 60}m ago"
            else -> "${ageSec / 3600}h ago"
        }
        // Bold any line mentioning cache so it stands out visually.
        val highlighted = snap.text.lineSequence().joinToString("\n") { line ->
            val lower = line.lowercase()
            if (lower.contains("cache")) "<strong>${esc(line)}</strong>" else esc(line)
        }
        """
        <div class="card" style="margin-bottom:14px">
          <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
            <h2 style="margin:0;font-size:16px">
              ${esc(projectName)} <span class="dim" style="font-weight:400;font-size:12px">(${esc(pid)})</span>
            </h2>
            <span class="dim" style="font-size:12px">captured $ageLabel · ${esc(snap.capturedAt.toString())}</span>
          </div>
          <pre class="diff-block" style="white-space:pre-wrap;font-size:12px;max-height:400px;overflow:auto;margin-top:10px">$highlighted</pre>
          <p class="dim" style="margin:6px 0 0;font-size:11px">
            <strong>cache</strong> 키워드 line 은 굵게 표시 — Anthropic 이 prompt cache 정보를
            <code>/status</code> 출력에 노출하면 자동으로 강조됩니다.
          </p>
        </div>"""
    }

    return """
<header>
  <h1>Claude 사용량 / Cache 조회 <small class="dim" style="font-size:14px;font-weight:400">v0.47.0+</small></h1>
</header>

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 6px"><strong>이 페이지는 <code>claude /status</code> 출력의 raw text 를 그대로 보여줍니다.</strong>
    임계치 알림용으로 이미 5분 마다 폴링 중인 결과를 재사용 (추가 비용 없음).</p>
  <p class="dim" style="margin:0;font-size:12px">
    Anthropic 이 prompt cache 통계 (hit / miss / saved tokens) 또는 billing 컨텍스트를
    <code>/status</code> 에 추가하면 즉시 여기서 확인 가능합니다. 구조화 파싱은 출력 포맷이
    안정화되면 별도 phase 에서 추가.
  </p>
</div>

$sections
"""
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
