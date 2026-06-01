package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
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
    /** v0.63.0 — Phase 42 prompt cache 누적 통계. */
    conversationRepo: com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository,
) {
    get("/usage") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get

        val snapshots = statusService.allRawSnapshots()
        val allProjects = projects.list().associateBy { it.id }
        // v0.63.0 — 모든 프로젝트의 cache stats 합산.
        val cacheStatsByProject = allProjects.keys.associateWith { conversationRepo.usageSummary(it) }
            .filterValues { it.turns > 0 }
        val body = renderUsagePage(snapshots, allProjects, cacheStatsByProject, sess.language)
        call.respondText(
            AdminTemplates.shell(
                title = "Claude 사용량 / Cache 조회",
                username = sess.username,
                currentPath = "/usage",
                csrf = sess.csrf,
                body = body,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }
}

private fun renderUsagePage(
    snapshots: Map<String, com.siamakerlab.vibecoder.server.claude.ClaudeStatusService.RawSnapshot>,
    projects: Map<String, com.siamakerlab.vibecoder.shared.dto.ProjectDto>,
    cacheStats: Map<String, com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository.UsageSummary> = emptyMap(),
    lang: String,
): String {
    val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
    fun fmt(n: Long): String = "%,d".format(n)
    val cacheCard = if (cacheStats.isEmpty()) {
        """<div class="card dim" style="text-align:center;padding:18px;margin-bottom:14px">
          <strong>Prompt cache stats:</strong> 아직 적재된 usage turn 이 없습니다.
          콘솔로 prompt 를 한 번 보내면 turn 종료 시점에 자동 적재됩니다.
        </div>"""
    } else {
        // 합산.
        var totalIn = 0L; var totalOut = 0L; var totalCR = 0L; var totalCC = 0L; var totalTurns = 0
        for ((_, s) in cacheStats) {
            totalIn += s.inputTokens
            totalOut += s.outputTokens
            totalCR += s.cacheReadTokens
            totalCC += s.cacheCreationTokens
            totalTurns += s.turns
        }
        val totalAllInput = totalIn + totalCR + totalCC
        val hitRate = if (totalAllInput == 0L) 0.0
        else totalCR.toDouble() * 100 / totalAllInput
        val perProject = cacheStats.entries
            .sortedByDescending { it.value.cacheReadTokens + it.value.inputTokens }
            .joinToString("\n") { (pid, s) ->
                val rateStr = s.cacheHitRate?.let { "%.1f%%".format(it) } ?: "-"
                """<tr>
                  <td><strong>${esc(projects[pid]?.name ?: pid)}</strong>
                    <div class="dim" style="font-size:11px">${esc(pid)}</div></td>
                  <td style="text-align:right">${fmt(s.turns.toLong())}</td>
                  <td style="text-align:right">${fmt(s.inputTokens)}</td>
                  <td style="text-align:right">${fmt(s.outputTokens)}</td>
                  <td style="text-align:right" class="ok">${fmt(s.cacheReadTokens)}</td>
                  <td style="text-align:right">${fmt(s.cacheCreationTokens)}</td>
                  <td style="text-align:right"><strong>$rateStr</strong></td>
                </tr>"""
            }
        """
<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">Prompt Cache 통계</h2>
  <p class="dim" style="margin:0 0 8px;font-size:12px">
    Claude stream-json 의 <code>usage</code> 객체에서 추출한 누적 토큰 사용량.
    <code>cache_read</code> 가 높으면 prompt 가 cache hit (저렴) — 비율은 효율 지표.
  </p>
  <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:10px">
    <div><div class="dim" style="font-size:11px">총 usage turns</div><div style="font-size:18px;font-weight:600">${fmt(totalTurns.toLong())}</div></div>
    <div><div class="dim" style="font-size:11px">input (fresh)</div><div style="font-size:18px;font-weight:600">${fmt(totalIn)}</div></div>
    <div><div class="dim" style="font-size:11px">output</div><div style="font-size:18px;font-weight:600">${fmt(totalOut)}</div></div>
    <div><div class="dim" style="font-size:11px">cache read (hit)</div><div style="font-size:18px;font-weight:600" class="ok">${fmt(totalCR)}</div></div>
    <div><div class="dim" style="font-size:11px">cache create</div><div style="font-size:18px;font-weight:600">${fmt(totalCC)}</div></div>
    <div><div class="dim" style="font-size:11px">cache hit rate</div><div style="font-size:18px;font-weight:600">${"%.1f%%".format(hitRate)}</div></div>
  </div>
  <table class="table" style="width:100%;font-size:12px">
    <thead><tr>
      <th>${esc(t("table.project"))}</th>
      <th style="text-align:right">turns</th>
      <th style="text-align:right">input</th>
      <th style="text-align:right">output</th>
      <th style="text-align:right">cache read</th>
      <th style="text-align:right">cache create</th>
      <th style="text-align:right">hit rate</th>
    </tr></thead>
    <tbody>$perProject</tbody>
  </table>
</div>"""
    }
    // 이후 raw snapshot 섹션은 기존 로직.
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
  <h1>Claude 사용량 / Cache 조회 <small class="dim" style="font-size:14px;font-weight:400"></small></h1>
</header>
${com.siamakerlab.vibecoder.server.admin.SettingsNav.categoryNav("/usage", lang)}

$cacheCard

<div class="card" style="margin-bottom:16px">
  <p style="margin:0 0 6px"><strong>아래 섹션은 <code>claude /status</code> 출력의 raw text 를 그대로 보여줍니다.</strong>
    임계치 알림용으로 이미 5분 마다 폴링 중인 결과를 재사용 (추가 비용 없음).</p>
</div>

$sections
"""
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
