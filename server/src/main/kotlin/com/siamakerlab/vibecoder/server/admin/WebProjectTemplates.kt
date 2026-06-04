package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.files.ProjectFileBrowser
import com.siamakerlab.vibecoder.server.repo.ArtifactRow
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto

/**
 * 프로젝트 / 콘솔 / 빌드 SSR 템플릿. v0.5.0 Phase 2 추가.
 *
 * 안드로이드 앱 없이도 브라우저만으로 프로젝트 등록 -> Claude 프롬프트 ->
 * Gradle 빌드 -> APK 다운로드까지 완결되도록 하는 화면들.
 *
 * AdminTemplates.kt 와 동일한 `shell()` 레이아웃 셸 + admin.css 를 공유한다.
 */
object WebProjectTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /** v1.54.0 — URL path/query segment 안전 인코딩 (chat id 등). */
    private fun String.encodeUrlSeg(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    /**
     * v0.12.4 — JavaScript 문자열 리터럴 컨텍스트 전용 escape.
     *
     * HTML escape 만으로는 안전하지 않음: `<script>` 안에서는 HTML 엔티티가
     * 디코드되지 않으므로 `&quot;` 가 JS 파서에 그대로 노출돼 syntax error 가 나거나,
     * projectId 검증이 향후 느슨해질 경우 XSS 로 발전 가능. 본 함수는 JS 문자열
     * 리터럴 안에서 안전한 escape 만 수행 (BOTH " 와 ' 안전).
     *
     * 출력값은 항상 따옴표를 포함한 완전한 리터럴이므로 사용 측은
     *   `var x = ${jsLit(value)};`
     * 와 같이 따옴표 없이 박는다.
     */
    private fun jsLit(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003C")  // </script> 닫힘 차단
                '>' -> sb.append("\\u003E")
                '&' -> sb.append("\\u0026")
                ' ' -> sb.append("\\u2028")  // line separator
                ' ' -> sb.append("\\u2029")  // paragraph separator
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Claude CLI / 인증 누락 안내 카드. 콘솔 페이지 상단에 표시되며,
     * 사용자가 그대로 복사해 실행할 수 있는 명령 한 줄 + 추가 설명을 노출한다.
     */
    private fun renderClaudeBanner(
        title: String,
        body: String,
        cmd: String,
        detail: String?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val detailHtml = if (detail.isNullOrBlank()) "" else
            """<details style="margin-top:8px"><summary class="dim" style="cursor:pointer">${esc(t("console.banner.detail"))}</summary>
               <pre class="diff-block" style="margin-top:8px">${esc(detail)}</pre></details>"""
        return """<div class="error" style="margin-bottom:16px;padding:16px">
          <strong style="font-size:14px">${esc(title)}</strong>
          <p style="margin:6px 0">${esc(body)}</p>
          <pre class="diff-block" style="margin:8px 0">${esc(cmd)}</pre>
          <small class="dim">${esc(t("console.banner.refreshHint"))}</small>
          $detailHtml
        </div>"""
    }

    /**
     * v1.7.3 — DB ConversationTurn 을 inline JSON 으로 emit. 페이지 load 직후 JS 가
     * parse 해서 console-log 에 prepend — 서버 재시작 후에도 기존 대화 즉시 가시.
     *
     * 각 row → `{ role, text, tool?, ts }`. token usage report (`role=usage`) 는 UI 노이즈
     * 라 skip. content 가 너무 길면 (예: tool_result Read 의 큰 파일) `MAX_CONTENT_PER_ROW`
     * 로 클립. `<` / `&` / 따옴표 등은 `jsLit` 의 escape 규칙 (< 등) 으로 `</script>`
     * 닫힘 + XSS 차단. ConversationTurnRow.content 는 raw — tool_use 의 input JSON 그대로
     * 노출되지만 JS 측 renderToolUse 가 라이브 흐름에서 처리하는 것과 동일 의도.
     */
    /**
     * v1.85.0 — replay 에서 숨겨야 할 노이즈 unknown 판정(문자열 패턴, JSON 파싱 불요).
     * content 는 JsonElement.toString() 형태(공백 없는 compact JSON)라 패턴 매칭이 견고하다.
     * - thinking_tokens: 토큰 추정 노이즈.
     * - task_*: 백그라운드 카드로 노출(중복).
     * - type=user: 서브에이전트 위임 prompt / 이미지 좌표 메타(메인은 Task 카드).
     * v1.86.0 — thinking 은 더 이상 skip 안 함("💭 Thinking…" 뱃지로 표시). 대신 아래
     * renderInitialHistoryJson 이 빈 thinking 의 긴 signature 를 버려 경량 마커로 단축한다.
     */
    private fun isNoiseUnknownContent(c: String): Boolean {
        if (c.contains("\"subtype\":\"thinking_tokens\"")) return true
        if (c.contains("\"subtype\":\"task_")) return true
        if (c.contains("\"type\":\"user\"")) return true
        return false
    }

    private fun renderInitialHistoryJson(
        rows: List<com.siamakerlab.vibecoder.server.repo.ConversationTurnRow>,
    ): String {
        if (rows.isEmpty()) return "[]"
        val maxContent = 4000
        val sb = StringBuilder("[")
        var first = true
        for (row in rows) {
            if (row.role == "usage") continue
            // v1.85.0 — 과거 DB 에 쌓인 노이즈 unknown 은 inline history 에서 제외. 특히 빈
            // thinking(signature-only)은 긴 signature 가 maxContent 절단 시 JSON 이 깨져
            // replay 의 renderUnknown(JSON.parse) 이 실패→raw 노출되던 버그의 원인이었다.
            // (live 경로는 v1.84.0 파서가 이미 드롭. 여기선 v1.84.0 이전 적재분 처리.)
            if (row.role == "unknown" && isNoiseUnknownContent(row.content)) continue
            if (!first) sb.append(',')
            first = false
            // v1.86.0 — 빈 thinking 의 signature(수천 자)는 버리고 경량 마커로 단축. 그대로
            // 두면 maxContent 절단으로 JSON 이 깨져 raw 노출되던 문제 → renderUnknown 이
            // "💭 Thinking…" 뱃지로 렌더할 수 있는 온전한 JSON 만 inline 에 싣는다.
            val raw = if (row.role == "unknown" &&
                row.content.contains("\"type\":\"thinking\"") &&
                row.content.contains("\"thinking\":\"\""))
                "{\"type\":\"thinking\",\"thinking\":\"\"}"
            else row.content
            val text = if (raw.length > maxContent)
                raw.substring(0, maxContent) + " …(+${raw.length - maxContent})"
            else raw
            sb.append('{')
            sb.append("\"role\":").append(jsLit(row.role))
            sb.append(",\"text\":").append(jsLit(text))
            if (row.toolName != null) sb.append(",\"tool\":").append(jsLit(row.toolName))
            if (row.ts.isNotBlank()) sb.append(",\"ts\":").append(jsLit(row.ts))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** 로그 라인 1개의 CSS 클래스. WS 라이브 흐름과 동일한 색상 팔레트. */
    private fun classOfLevel(level: String): String = when (level.uppercase()) {
        "ERROR", "STDERR" -> "err"
        "WARN" -> "tool"
        "STDOUT" -> "assistant"
        "INFO" -> "sys"
        else -> "sys"
    }

    /**
     * v0.22.0 — Play Console 업로드 카드.
     *
     * 빌드 상태가 SUCCESS 가 아니면 카드 미표시. SUCCESS 면 precheck (MCP 설치/등록)
     * 결과 + AAB 경로 입력 폼 + Internal/Alpha/Beta/Production 선택 + Release Notes.
     * 사전조건이 모자라더라도 폼은 노출 — 사용자가 우선 prompt 를 보내본 후 Claude
     * 응답에서 부족한 점을 다시 확인할 수 있도록.
     */
    private fun renderPlayUploadCard(
        p: ProjectDto,
        b: BuildDto,
        precheck: com.siamakerlab.vibecoder.server.publish.PlayPublishService.Precheck?,
        flashOk: String?,
        flashErr: String?,
        csrf: String?,
        lang: String,
    ): String {
        if (b.status.name != "SUCCESS") return ""
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val readyBadge = when {
            precheck == null -> """<span class="dim">${esc(t("publish.precheckNotRun"))}</span>"""
            precheck.ready -> """<span class="ok">${esc(t("publish.ready"))}</span>"""
            else -> """<span class="warn">${esc(t("publish.notReady"))}</span>"""
        }
        val mcpStatusLine = precheck?.let { """<dt>MCP</dt><dd>${esc(it.mcpStatus)}</dd>""" } ?: ""
        val pkgLine = precheck?.configuredPackageName?.let {
            """<dt>MCP packageName</dt><dd><code>${esc(it)}</code></dd>"""
        } ?: ""
        val warnHtml = if (precheck != null && precheck.warnings.isNotEmpty()) {
            val items = precheck.warnings.joinToString("") { """<li>${esc(it)}</li>""" }
            """<ul class="hint" style="margin:8px 0 0 18px">$items</ul>"""
        } else ""
        val okBanner = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errBanner = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        // .aab 기본 경로 추정 — 사용자가 다른 위치면 수정 가능.
        val defaultAab = "app/build/outputs/bundle/release/app-release.aab"
        return """
<div class="card" style="margin-bottom:16px">
  <h2>${esc(t("play.title"))}</h2>
  <p>${com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "play.desc", readyBadge)}</p>
  $okBanner
  $errBanner
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 12px;margin-top:8px">
    <dt>${esc(t("play.col.projectPackage"))}</dt><dd><code>${esc(p.packageName)}</code></dd>
    $pkgLine
    $mcpStatusLine
  </dl>
  $warnHtml
  <form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/play-upload" style="margin-top:12px;display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("play.aabPath"))}
      <input name="aabPath" value="${esc(defaultAab)}" required>
    </label>
    <label>${esc(t("play.track"))}
      <select name="track">
        <option value="internal" selected>${esc(t("play.track.internal"))}</option>
        <option value="alpha">alpha</option>
        <option value="beta">beta</option>
        <option value="production">production</option>
      </select>
    </label>
    <label>${esc(t("play.releaseNotes"))}
      <textarea name="releaseNotes" rows="3" placeholder="${esc(t("releaseNotes.placeholder.git"))}"></textarea>
    </label>
    <div>
      <button type="submit" class="primary">${esc(t("publish.delegateBtn"))}</button>
      <a href="/env-setup/mcp" class="chip chip-link">${esc(t("publish.mcpLink"))}</a>
    </div>
  </form>
  <p class="hint" style="margin-top:8px">${esc(t("play.hint"))}</p>
</div>"""
    }

    /**
     * v0.23.0 — TestFlight 업로드 카드.
     *
     * vibe-coder 는 iOS 빌드를 직접 수행하지 않으므로 BuildDto 의 status 와 무관
     * 하게 항상 노출 (다만 이 페이지 자체는 빌드 detail). .ipa 경로 입력만 받으면
     * MCP `app-store-connect` 가 처리하므로 빌드 SUCCESS 와 연동할 의미 없음.
     */
    private fun renderTestFlightUploadCard(
        p: ProjectDto,
        b: BuildDto,
        precheck: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService.Precheck?,
        flashOk: String?,
        flashErr: String?,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val readyBadge = when {
            precheck == null -> """<span class="dim">${esc(t("publish.precheckNotRun"))}</span>"""
            precheck.ready -> """<span class="ok">${esc(t("publish.ready"))}</span>"""
            else -> """<span class="warn">${esc(t("publish.notReady"))}</span>"""
        }
        val mcpStatusLine = precheck?.let { """<dt>MCP</dt><dd>${esc(it.mcpStatus)}</dd>""" } ?: ""
        val warnHtml = if (precheck != null && precheck.warnings.isNotEmpty()) {
            val items = precheck.warnings.joinToString("") { """<li>${esc(it)}</li>""" }
            """<ul class="hint" style="margin:8px 0 0 18px">$items</ul>"""
        } else ""
        val okBanner = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val errBanner = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        // 사용자가 별도 머신에서 빌드한 .ipa 를 워크스페이스에 올린 시나리오.
        val defaultIpa = "out/app-release.ipa"
        return """
<div class="card" style="margin-bottom:16px">
  <h2>${esc(t("tf.title"))}</h2>
  <p>${com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "tf.desc", readyBadge)}</p>
  <p class="hint">${esc(t("tf.iosHint"))}</p>
  $okBanner
  $errBanner
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 12px;margin-top:8px">
    $mcpStatusLine
  </dl>
  $warnHtml
  <form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/testflight-upload" style="margin-top:12px;display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("tf.ipaPath"))}
      <input name="ipaPath" value="${esc(defaultIpa)}" required>
    </label>
    <label>${esc(t("tf.distributionGroups"))}
      <input name="distributionGroups" placeholder="QA, Beta-Insiders">
    </label>
    <label>${esc(t("tf.releaseNotes"))}
      <textarea name="releaseNotes" rows="3" placeholder="${esc(t("releaseNotes.placeholder.changelog"))}"></textarea>
    </label>
    <div>
      <button type="submit" class="primary">${esc(t("publish.delegateBtn"))}</button>
      <a href="/env-setup/mcp" class="chip chip-link">${esc(t("publish.mcpLink"))}</a>
    </div>
  </form>
  <p class="hint" style="margin-top:8px">${esc(t("tf.hint"))}</p>
</div>"""
    }

    /**
     * v0.28.0 — APK 서명 검사 결과 (apksigner verify).
     */
    /**
     * v0.59.0 — Phase 38 빌드 통계 카드 (`/projects/{id}/builds` 상단). 성공률 / 평균
     * 빌드 시간 / 최근 30 status sparkline / 최근 10 APK 사이즈 trend.
     */
    private fun renderBuildStatistics(
        stats: com.siamakerlab.vibecoder.server.build.BuildService.BuildStatistics?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (stats == null || stats.total == 0) return ""
        fun fmtMs(ms: Long?): String = when {
            ms == null -> "-"
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "%.1fs".format(ms / 1000.0)
            else -> "%dm %ds".format(ms / 60_000, (ms / 1000) % 60)
        }
        // Sparkline: recentStatuses 가 most-recent-first 라서 SVG 는 reverse 해서 left-old → right-new.
        val sparkline = run {
            val seq = stats.recentStatuses.reversed()
            if (seq.isEmpty()) ""
            else {
                val w = 6
                val totalW = seq.size * w
                val bars = seq.mapIndexed { i, s ->
                    val color = when (s) {
                        "SUCCESS" -> "#22c55e"
                        "FAILED", "TIMEOUT" -> "#ef4444"
                        "CANCELED" -> "#9ca3af"
                        "RUNNING", "PENDING" -> "#facc15"
                        else -> "#6b7280"
                    }
                    """<rect x="${i * w}" y="0" width="${w - 1}" height="20" fill="$color"><title>${esc(s)}</title></rect>"""
                }.joinToString("")
                """<svg width="$totalW" height="20" style="vertical-align:middle" aria-label="${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "bar.aria", seq.size))}">$bars</svg>"""
            }
        }
        // APK size trend: recentSuccessSizes most-recent-first → reverse 후 SVG line.
        val sizeTrend = run {
            val sizes = stats.recentSuccessSizes.reversed().filterNotNull()
            if (sizes.size < 2) ""
            else {
                val w = 240; val h = 40; val pad = 4
                val maxV = sizes.max().toDouble()
                val minV = sizes.min().toDouble()
                val range = (maxV - minV).coerceAtLeast(1.0)
                val step = if (sizes.size > 1) (w - 2 * pad).toDouble() / (sizes.size - 1) else 0.0
                val pts = sizes.mapIndexed { i, v ->
                    val x = pad + i * step
                    val y = h - pad - ((v - minV) / range) * (h - 2 * pad)
                    "$x,$y"
                }.joinToString(" ")
                val deltaKb = (sizes.last() - sizes.first()) / 1024
                val deltaSign = if (deltaKb >= 0) "+" else ""
                val deltaCls = if (deltaKb > 0) "warn" else if (deltaKb < 0) "ok" else "dim"
                """<div style="display:flex;gap:12px;align-items:center">
                  <svg width="$w" height="$h" style="background:rgba(255,255,255,0.03);border-radius:4px">
                    <polyline points="$pts" fill="none" stroke="#5eb1ef" stroke-width="2"/>
                  </svg>
                  <div class="dim" style="font-size:12px">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "builds.stats.recentSuccess", sizes.size))}<br>
                    Δ <span class="$deltaCls">$deltaSign${deltaKb} KB</span></div>
                </div>"""
            }
        }
        val rateCls = when {
            stats.successRatePercent == null -> "dim"
            stats.successRatePercent >= 90 -> "ok"
            stats.successRatePercent >= 70 -> "warn"
            else -> "warn"
        }
        val statsSummary = com.siamakerlab.vibecoder.server.i18n.Messages.t(
            lang, "builds.stats.summary", stats.successCount, stats.failedCount, stats.cancelledCount,
        )
        val statsRunning = if (stats.runningCount > 0)
            com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "builds.stats.running", stats.runningCount) else ""
        return """
<div class="card" style="margin-bottom:16px">
  <h2 style="margin-top:0">${esc(t("builds.stats.title"))}</h2>
  <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin-bottom:12px">
    <div>
      <div class="dim" style="font-size:11px">${esc(t("builds.stats.total"))}</div>
      <div style="font-size:20px;font-weight:600">${stats.total}</div>
    </div>
    <div>
      <div class="dim" style="font-size:11px">${esc(t("builds.stats.successRate"))}</div>
      <div style="font-size:20px;font-weight:600"><span class="$rateCls">${stats.successRatePercent ?: "-"}%</span></div>
      <div class="dim" style="font-size:11px">${esc(statsSummary)}${esc(statsRunning)}</div>
    </div>
    <div>
      <div class="dim" style="font-size:11px">${esc(t("builds.stats.avgDuration"))}</div>
      <div style="font-size:20px;font-weight:600">${fmtMs(stats.avgSuccessDurationMs)}</div>
    </div>
  </div>
  ${if (sparkline.isNotEmpty()) """
  <div style="margin-bottom:10px">
    <div class="dim" style="font-size:11px;margin-bottom:4px">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "builds.stats.recentLabel", stats.recentStatuses.size))}</div>
    $sparkline
  </div>""" else ""}
  $sizeTrend
</div>"""
    }

    /**
     * v0.58.0 — Phase 37 이전 성공 빌드와의 비교 카드. null = 비교 대상 없음 (첫 성공 빌드)
     * 또는 현재 빌드가 SUCCESS 아님.
     */
    private fun renderBuildComparison(
        cmp: com.siamakerlab.vibecoder.server.build.BuildService.BuildComparison?,
        lang: String,
        /** v0.89.0 — cross-branch toggle link 용. null 이면 토글 link 안 보임. */
        projectId: String? = null,
        buildId: String? = null,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (cmp == null) return ""
        fun fmtSize(b: Long?): String = when {
            b == null -> "-"
            b < 1024 -> "$b B"
            b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.2f MB".format(b / (1024.0 * 1024))
            else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
        }
        fun fmtDuration(ms: Long?): String = when {
            ms == null -> "-"
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "%.1fs".format(ms / 1000.0)
            else -> "%dm %ds".format(ms / 60_000, (ms / 1000) % 60)
        }
        fun deltaBadge(delta: Long?, formatter: (Long?) -> String, lowerIsBetter: Boolean = true): String {
            if (delta == null) return ""
            val sign = if (delta > 0) "+" else ""
            val cls = when {
                delta == 0L -> "dim"
                (delta > 0) == lowerIsBetter -> "warn"  // worse
                else -> "ok"                            // better
            }
            return """<span class="$cls" style="font-size:12px;margin-left:6px">($sign${formatter(delta)})</span>"""
        }
        // v0.89.0 — Phase 65 #4 scope badge + branch info + toggle link.
        val scopeHtml = if (cmp.scope == com.siamakerlab.vibecoder.server.build.BuildService.BuildComparison.Scope.SAME_BRANCH) {
            """<span class="ok" style="font-size:11px">${esc(t("build.compare.scopeSameBranch"))}</span>"""
        } else {
            """<span class="warn" style="font-size:11px">${esc(t("build.compare.scopeAny"))}</span>"""
        }
        val branchInfoHtml = if (cmp.current.gitBranch != null || cmp.previous.gitBranch != null) {
            val info = com.siamakerlab.vibecoder.server.i18n.Messages.t(
                lang, "build.compare.branchInfo",
                cmp.previous.gitBranch ?: "-", cmp.previous.gitSha?.take(8) ?: "-",
                cmp.current.gitBranch ?: "-", cmp.current.gitSha?.take(8) ?: "-",
            )
            """<p class="dim" style="margin:4px 0 8px;font-size:11px;font-family:ui-monospace,Menlo,monospace">${esc(info)}</p>"""
        } else ""
        val toggleHtml = if (projectId != null && buildId != null) {
            val label = if (cmp.scope == com.siamakerlab.vibecoder.server.build.BuildService.BuildComparison.Scope.SAME_BRANCH)
                t("build.compare.crossBranchLink") else t("build.compare.sameBranchLink")
            val href = if (cmp.scope == com.siamakerlab.vibecoder.server.build.BuildService.BuildComparison.Scope.SAME_BRANCH)
                "/projects/${esc(projectId)}/builds/${esc(buildId)}?compare=any"
            else "/projects/${esc(projectId)}/builds/${esc(buildId)}"
            """<a href="$href" class="chip chip-link" style="font-size:11px;margin-left:8px">${esc(label)}</a>"""
        } else ""
        return """
<div class="card" style="margin-bottom:16px">
  <h2>${esc(t("build.compare.title"))} $scopeHtml $toggleHtml</h2>
  <p class="dim" style="margin:0 0 8px;font-size:12px">
    ${com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "build.compare.desc", esc(cmp.previous.id.take(12)), esc(cmp.previous.createdAt))}
  </p>
  $branchInfoHtml
  <table class="table" style="width:100%">
    <thead>
      <tr><th>${esc(t("build.compare.col.metric"))}</th><th>${esc(t("build.compare.col.previous"))}</th><th>${esc(t("build.compare.col.current"))}</th><th>${esc(t("build.compare.col.delta"))}</th></tr>
    </thead>
    <tbody>
      <tr>
        <td>${esc(t("build.compare.apkSize"))}</td>
        <td class="dim">${fmtSize(cmp.previous.apkSizeBytes)}</td>
        <td><strong>${fmtSize(cmp.current.apkSizeBytes)}</strong></td>
        <td>${deltaBadge(cmp.apkSizeDeltaBytes, ::fmtSize, lowerIsBetter = true)}</td>
      </tr>
      <tr>
        <td>${esc(t("build.compare.duration"))}</td>
        <td class="dim">${fmtDuration(cmp.previous.durationMs)}</td>
        <td><strong>${fmtDuration(cmp.current.durationMs)}</strong></td>
        <td>${deltaBadge(cmp.durationDeltaMs, ::fmtDuration, lowerIsBetter = true)}</td>
      </tr>
    </tbody>
  </table>
  <p class="dim" style="margin:8px 0 0;font-size:11px">
    ${t("build.compare.dexHint")}
  </p>
</div>"""
    }

    private fun renderSignerInspection(
        insp: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector.Inspection?,
        lang: String,
    ): String {
        if (insp == null) return ""
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (!insp.verified && insp.errorMessage != null && insp.schemes.isEmpty() && insp.signers.isEmpty()) {
            return """
<div style="margin-top:14px;padding:10px;border-radius:6px;background:rgba(255,150,80,0.08)">
  <strong>${esc(t("signer.failed"))}</strong>
  <div class="dim" style="font-size:13px;margin-top:4px">${esc(insp.errorMessage)}</div>
</div>"""
        }
        val verifiedBadge = if (insp.verified)
            """<span class="ok">${esc(t("signer.verified"))}</span>"""
        else
            """<span class="warn">${esc(t("signer.notVerified"))}</span>"""
        val schemes = if (insp.schemes.isNotEmpty()) insp.schemes.joinToString(", ")
        else t("build.detail.signersNone")
        val signersHtml = if (insp.signers.isEmpty()) {
            """<p class="dim" style="font-size:13px">${esc(t("build.detail.signersUnknown"))}</p>"""
        } else {
            insp.signers.joinToString("") { s ->
                val fp = s.sha256?.let { it.chunked(4).joinToString(" ").take(80) } ?: "-"
                """
                <div style="margin-top:8px;padding:6px 10px;background:rgba(255,255,255,0.04);border-radius:6px;font-size:12px">
                  <div><strong>Signer #${s.signerIndex}</strong></div>
                  <div class="dim">DN: <code>${esc(s.subjectDn ?: "-")}</code></div>
                  <div class="dim">SHA-256: <code style="word-break:break-all">${esc(fp)}</code></div>
                </div>"""
            }
        }
        return """
<div style="margin-top:14px">
  <h3 style="margin:0 0 8px 0;font-size:14px">${esc(t("signer.title"))}</h3>
  <p style="margin:0">$verifiedBadge — ${esc(t("signer.activeSchemes"))}: <code>$schemes</code></p>
  $signersHtml
</div>"""
    }

    /** 종료된 빌드의 파일 로그를 prerender. null 이면 빈 문자열. */
    private fun renderReplay(replay: BuildLogReplay?): String {
        if (replay == null) return ""
        return replay.lines.joinToString("\n") { ln ->
            val cls = classOfLevel(ln.level)
            """<div class="log-line $cls"><span class="log-label">${esc(ln.level)}</span><span class="log-body">${esc(ln.message)}</span></div>"""
        }
    }

    /** 로그 카드 하단 caption — replay 출처/잘림 안내 또는 라이브 안내. */
    private fun replayCaption(replay: BuildLogReplay?, attachWs: Boolean, lang: String): String {
        if (attachWs) return ""
        if (replay == null) {
            return """<p class="hint">${com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "build.detail.replayNotFound")}</p>"""
        }
        val kb = (replay.sizeBytes + 512L) / 1024L
        val trunc = if (replay.truncated) {
            com.siamakerlab.vibecoder.server.i18n.Messages.t(
                lang, "build.detail.logsTruncated", replay.lines.size, replay.totalLines,
            )
        } else ""
        return """<p class="hint">${com.siamakerlab.vibecoder.server.i18n.Messages.t(
            lang, "build.detail.replayFileLog", replay.sourcePath, kb, replay.totalLines, trunc,
        )}</p>"""
    }

    /**
     * 콘솔 슬래시 chip 1개. 동일 form 안에 hidden command + 버튼.
     * `danger=true` 면 빨간색 (예: /clear). v0.12.4 — csrf 토큰 함께 박음.
     */
    // v0.75.0 — slashChip 제거. Claude Code 의 interactive slash commands 가 vibe-coder 의
    // non-interactive streaming mode 에서 동작 안 함. UI/wire 모두 정리.
    @Suppress("unused") private fun slashChipRemovedInV075() {}

    // ────────────────────────────────────────────────────────────────────
    // /projects — 목록 + 등록 폼
    // ────────────────────────────────────────────────────────────────────

    fun projectsPage(
        username: String,
        projects: List<ProjectDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
        /**
         * v1.53.0 — projectId → 상태 키 ("responding" | "ready" | "idle").
         * 누락 시 "idle" 로 폴백. 목록 진입 시점 snapshot 이며 이후 `/ws/projects`
         * 의 ProjectBusyChanged 로 responding↔ready 가 실시간 patch 된다.
         */
        statuses: Map<String, String> = emptyMap(),
        /** v1.60.0 — 페이지네이션(1-base) / 페이지당 개수 / 전체 개수. */
        page: Int = 1,
        size: Int = 20,
        total: Int = 0,
        /** v1.64.0 — 행별 앱 versionName(없으면 null) + 런처 아이콘 존재 여부(false면 placeholder). */
        versions: Map<String, String?> = emptyMap(),
        appIcons: Map<String, Boolean> = emptyMap(),
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val offset = (page - 1) * size
        val pageCount = ((total + size - 1) / size).coerceAtLeast(1)

        // v1.14.3 — 등록된 프로젝트 list: lastBuild / openConsole 컬럼 제거. name + package 만.
        // v1.14.4 — row 전체 영역이 클릭 가능 + 첫 화면 무조건 console (#console hash 명시).
        // v1.60.0 — 우측 드래그 핸들(☰) 열 추가 + 3-state 상태칩(idle 제거 → ready 폴백).
        val rowsHtml = if (projects.isEmpty()) {
            """<tr><td colspan="4" class="dim">${esc(t("projects.list.empty"))}</td></tr>"""
        } else {
            projects.joinToString("\n") { p ->
                val href = "/projects/${esc(p.id)}#console"
                val cellLinkStyle = "display:block;color:inherit;text-decoration:none"
                // v1.53.0 — 제일 왼쪽 상태칩. data-pid 로 WS patch 대상 식별, data-state 로 색 분기.
                val state = statuses[p.id] ?: "ready"
                val chip = """<span class="pstat pstat-$state" data-pid="${esc(p.id)}" data-state="$state">${esc(t("projects.status.$state"))}</span>"""
                // v1.64.0 — 앱 아이콘(없으면 placeholder vibe-coder 아이콘) + 이름 우측 버전.
                val iconSrc = if (appIcons[p.id] == true) "/projects/${p.id.encodeUrlSeg()}/app-icon" else "/static/icon.png"
                val verBadge = versions[p.id]?.takeIf { it.isNotBlank() }
                    ?.let { """ <span class="proj-ver">v${esc(it)}</span>""" } ?: ""
                """<tr class="row-link proj-row" data-pid="${esc(p.id)}">
                    <td><a href="$href" style="$cellLinkStyle">$chip</a></td>
                    <td><a href="$href" style="$cellLinkStyle;display:flex;align-items:center;gap:10px">
                        <img class="proj-icon" src="$iconSrc" alt="" loading="lazy"
                             onerror="this.onerror=null;this.src='/static/icon.png'">
                        <span style="min-width:0"><strong>${esc(p.name)}</strong>$verBadge<br><small class="dim">${esc(p.id)}</small></span>
                      </a></td>
                    <td><a href="$href" style="$cellLinkStyle"><code>${esc(p.packageName)}</code></a></td>
                    <td class="proj-handle" title="${esc(t("projects.reorder.handle"))}" aria-label="${esc(t("projects.reorder.handle"))}">☰</td>
                  </tr>"""
            }
        }

        // v1.60.0 — 페이지당 개수 콤보 + 페이지 네비.
        fun sizeOpt(n: Int) = """<option value="$n"${if (n == size) " selected" else ""}>$n</option>"""
        val sizeCombo = """
          <label style="font-size:13px;color:var(--text-dim);display:flex;align-items:center;gap:6px">
            ${esc(t("projects.pageSize"))}
            <select id="proj-page-size" style="padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333;border-radius:5px">
              ${sizeOpt(20)}${sizeOpt(50)}${sizeOpt(100)}
            </select>
          </label>"""
        val prevHref = if (page > 1) """href="/projects?page=${page - 1}&size=$size"""" else ""
        val nextHref = if (page < pageCount) """href="/projects?page=${page + 1}&size=$size"""" else ""
        val navHtml = """
          <div style="display:flex;align-items:center;gap:8px">
            <a class="chip chip-link"${if (page > 1) "" else " style=\"opacity:.4;pointer-events:none\""} $prevHref>‹ ${esc(t("projects.page.prev"))}</a>
            <span class="dim" style="font-size:12px">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "projects.page.of", page, pageCount))} · ${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "projects.page.total", total))}</span>
            <a class="chip chip-link"${if (page < pageCount) "" else " style=\"opacity:.4;pointer-events:none\""} $nextHref>${esc(t("projects.page.next"))} ›</a>
          </div>"""

        return AdminTemplates.shell(
            title = t("projects.title"),
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            wide = true,  // v1.104.0 — 넓은 폭 활용(중앙정렬 1200px 해제)
            body = """
<header><h1>${esc(t("projects.heading"))}</h1></header>
$okHtml
$errHtml

<!-- v1.14.1 — 좌우 (2fr 1fr) 배치 → 상하 single-column. 신규 등록 카드가 상단,
     기존 프로젝트 목록이 하단. 사이드바 접힘 시에도 가로 폭 부족 없음.
     v1.14.2 — 신규 등록 카드는 default closed (<details> native 토글). 사용자가
     실제로 새 프로젝트 만들 때만 펼침. -->
<section class="grid" style="grid-template-columns: 1fr">
  <details class="card">
    <summary style="cursor:pointer;font-weight:600;font-size:18px;list-style:none">
      <span style="display:inline-block;width:1em">▸</span>${esc(t("projects.new.title"))}
    </summary>
    <form method="post" action="/projects" id="new-project-form" style="margin-top:12px">
      ${CsrfTokens.hiddenInput(csrf)}

      <!-- v1.7.0 — 소스 유형 선택을 가장 먼저. clone 선택 시 다른 fields 자동 hide +
           required 해제 (cloneUrl 만 입력하면 됨). -->
      <fieldset style="border:1px solid #333;padding:10px;border-radius:6px">
        <legend style="padding:0 6px;font-size:13px">${esc(t("projects.new.source"))}</legend>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer">
          <input type="radio" name="sourceType" value="empty" checked
                 onclick="toggleSource('empty')">
          <span><strong>${esc(t("projects.new.empty"))}</strong>${esc(t("projects.new.emptyDesc"))}</span>
        </label>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer;margin-top:6px">
          <input type="radio" name="sourceType" value="clone"
                 onclick="toggleSource('clone')">
          <span><strong>${esc(t("projects.new.clone"))}</strong>${esc(t("projects.new.cloneDesc"))}</span>
        </label>
      </fieldset>

      <!-- clone path: cloneUrl 만 필수 + branch optional. 다른 정보는 자동 도출. -->
      <div id="clone-fields" style="display:none;margin-top:10px">
        <label>${esc(t("projects.new.cloneUrl"))}
          <input name="cloneUrl" type="text"
                 placeholder="https://github.com/owner/repo.git  /  git@github.com:owner/repo.git">
        </label>
        <label>${esc(t("projects.new.branch"))}
          <input name="cloneBranch" type="text" placeholder="${esc(t("projects.new.branchPlaceholder"))}">
        </label>
        <p class="hint" style="font-size:12px;margin:6px 0 0">${esc(t("projects.new.cloneAutoHint"))}</p>
        <!-- v1.7.18 — 기존 폴더 (orphan, DB row 없음) 있을 때 강제 덮어쓰기. -->
        <label style="display:flex;align-items:center;gap:6px;margin-top:8px;cursor:pointer">
          <input type="checkbox" name="overwrite" value="true">
          <span>${esc(t("projects.new.overwrite"))}</span>
        </label>
        <p class="hint" style="font-size:11px;margin:2px 0 0">${esc(t("projects.new.overwriteHint"))}</p>
      </div>

      <!-- empty path: 모든 필드 명시 입력. -->
      <div id="empty-fields" style="margin-top:10px">
        <label>${esc(t("projects.new.template"))}
          <select name="templateId">
            ${com.siamakerlab.vibecoder.server.projects.ProjectTemplates.all.joinToString("") {
                """<option value="${esc(it.id)}">${esc(it.title)}</option>"""
            }}
          </select>
        </label>
        <label>${esc(t("projects.new.idLabel"))}
          <input name="projectId" required pattern="[a-z0-9][a-z0-9._-]*" maxlength="64"
                 placeholder="my-android-app">
        </label>
        <label>${esc(t("projects.new.appName"))}
          <input name="appName" required maxlength="80" placeholder="My Android App">
        </label>
        <label>${esc(t("projects.new.packageName"))}
          <input name="packageName" required pattern="[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+"
                 placeholder="com.example.myapp">
        </label>
      </div>

      <button type="submit" class="primary" style="margin-top:10px">${esc(t("projects.new.submit"))}</button>
      <p class="hint">${esc(t("projects.new.emptyHint"))}</p>
    </form>
    <script>
      function toggleSource(kind) {
        var cloneFields = document.getElementById('clone-fields');
        var emptyFields = document.getElementById('empty-fields');
        var emptyInputs = emptyFields.querySelectorAll('input[required], input[pattern]');
        if (kind === 'clone') {
          cloneFields.style.display = 'block';
          emptyFields.style.display = 'none';
          // 빈 / pattern 필드 의 required + pattern 일시 비활성 — HTML5 validation 통과.
          emptyInputs.forEach(function(i) {
            if (i.hasAttribute('required')) { i.dataset._req = '1'; i.removeAttribute('required'); }
            if (i.hasAttribute('pattern')) { i.dataset._pat = i.getAttribute('pattern'); i.removeAttribute('pattern'); }
          });
        } else {
          cloneFields.style.display = 'none';
          emptyFields.style.display = 'block';
          emptyInputs.forEach(function(i) {
            if (i.dataset._req) { i.setAttribute('required', ''); delete i.dataset._req; }
            if (i.dataset._pat) { i.setAttribute('pattern', i.dataset._pat); delete i.dataset._pat; }
          });
        }
      }
    </script>
  </details>

  <div class="card">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap">
      <h2 style="margin:0">${esc(t("projects.list.title"))}</h2>
      $sizeCombo
    </div>
    <style>
      /* v1.14.4 — row 전체 영역 클릭 가능. cell padding 안에서도 hover 강조 / cursor 변경. */
      table.devices tr.row-link td { padding: 0; }
      table.devices tr.row-link td a { padding: 10px 12px; }
      table.devices tr.row-link:hover { background: #1a1f2c; cursor: pointer; }
      /* v1.60.0 — 드래그 핸들 열. */
      table.devices td.proj-handle { padding: 10px 12px; width: 40px; text-align: center;
        color: #6c7a93; cursor: grab; user-select: none; font-size: 15px; }
      table.devices td.proj-handle:active { cursor: grabbing; }
      table.devices tr.proj-row.dragging { opacity: 0.55; background: #243049; }
      table.devices tr.proj-row.drop-target td { box-shadow: inset 0 2px 0 var(--accent,#3b82f6); }
      /* v1.64.0 — 앱 아이콘 + 이름 우측 버전 배지. */
      .proj-icon { width:30px; height:30px; border-radius:7px; object-fit:cover; flex:none;
        background:#11151e; border:1px solid #222; }
      .proj-ver { font-size:11px; color:var(--text-dim); margin-left:6px; font-weight:500;
        font-family:ui-monospace,Menlo,monospace; }
    </style>
    <table class="devices">
      <thead>
        <tr><th style="width:84px">${esc(t("projects.list.col.status"))}</th><th>${esc(t("projects.list.col.name"))}</th><th>${esc(t("projects.list.col.package"))}</th><th style="width:40px"></th></tr>
      </thead>
      <tbody>
        $rowsHtml
      </tbody>
    </table>
    <div style="display:flex;justify-content:flex-end;margin-top:10px">$navHtml</div>
  </div>
</section>

<!-- v1.53.0 — 프로젝트 목록 상태칩 실시간 동기. `/ws/projects` (단방향) 구독 후
     ProjectBusyChanged frame 으로 해당 projectId 칩을 responding↔ready 로 patch.
     인증은 handshake 의 vibe_session 쿠키로 자동 처리 (콘솔 WS 와 동일 패턴). -->
<script>
(function() {
  // v1.100.0 — 5-state(유휴/응답중/대기중/중단됨/에러). ProjectBusyChanged.state 우선, 없으면 busy 폴백.
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

// v1.60.0 — 페이지당 개수 콤보(기억) + 드래그 순서변경.
(function() {
  var KEY = 'vibe-projects-page-size';
  var sel = document.getElementById('proj-page-size');
  if (sel) {
    var params = new URLSearchParams(location.search);
    if (!params.has('size')) {
      // url 에 size 없고 저장값이 현재(기본 ${size})와 다르면 1회 이동.
      var saved = localStorage.getItem(KEY);
      if (saved && ['20','50','100'].indexOf(saved) >= 0 && saved !== '${size}') {
        params.set('size', saved); params.set('page', '1');
        location.replace(location.pathname + '?' + params.toString());
        return;
      }
    }
    sel.addEventListener('change', function() {
      try { localStorage.setItem(KEY, sel.value); } catch (e) {}
      var p = new URLSearchParams(location.search);
      p.set('size', sel.value); p.set('page', '1');
      location.assign(location.pathname + '?' + p.toString());
    });
  }

  var tbody = document.querySelector('table.devices tbody');
  if (!tbody) return;
  var OFFSET = ${offset};
  var dragRow = null;
  tbody.addEventListener('mousedown', function(e) {
    var h = e.target.closest && e.target.closest('.proj-handle');
    if (h) { var tr = h.closest('tr.proj-row'); if (tr) tr.setAttribute('draggable', 'true'); }
  });
  tbody.addEventListener('dragstart', function(e) {
    var tr = e.target.closest && e.target.closest('tr.proj-row');
    if (!tr || tr.getAttribute('draggable') !== 'true') { return; }
    dragRow = tr; tr.classList.add('dragging');
    try { e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', tr.getAttribute('data-pid') || ''); } catch (_) {}
  });
  tbody.addEventListener('dragover', function(e) {
    if (!dragRow) return;
    e.preventDefault();
    var over = e.target.closest && e.target.closest('tr.proj-row');
    if (!over || over === dragRow) return;
    var rect = over.getBoundingClientRect();
    var after = (e.clientY - rect.top) > rect.height / 2;
    tbody.insertBefore(dragRow, after ? over.nextSibling : over);
  });
  tbody.addEventListener('drop', function(e) { if (dragRow) e.preventDefault(); });
  tbody.addEventListener('dragend', function() {
    if (!dragRow) return;
    dragRow.classList.remove('dragging');
    dragRow.removeAttribute('draggable');
    dragRow = null;
    var ids = Array.prototype.map.call(tbody.querySelectorAll('tr.proj-row'), function(tr) {
      return tr.getAttribute('data-pid');
    });
    fetch('/api/projects/reorder', {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ offset: OFFSET, order: ids }),
    }).then(function(r) { if (!r.ok) { location.reload(); } })
      .catch(function() { location.reload(); });
  });
})();
</script>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id} — 상세 (요약 + 하위 페이지 링크)
    // ────────────────────────────────────────────────────────────────────

    fun projectDetailPage(
        username: String,
        p: ProjectDto,
        recentBuilds: List<BuildDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
        /** v1.71.0 — 폴더명·패키지명 변경 가능 여부(대기중 = turn/빌드 미진행). */
        structuralEnabled: Boolean = true,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val recentRows = if (recentBuilds.isEmpty()) {
            """<tr><td colspan="3" class="dim">${esc(t("projects.detail.recentEmpty"))}</td></tr>"""
        } else {
            recentBuilds.joinToString("\n") { b ->
                """<tr>
                    <td><a href="/projects/${esc(p.id)}/builds/${esc(b.id)}"><code>${esc(b.id.take(12))}</code></a></td>
                    <td>${esc(b.status.name)}</td>
                    <td>${esc(b.startedAt)}</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = esc(p.name),
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(p.name)} <small class="dim" style="font-size:14px;font-weight:400">${esc(p.id)}</small></h1>
</header>
$okHtml
$errHtml

<section class="grid">
  <div class="card">
    <h2>${esc(t("projects.detail.summary"))}</h2>
    <dl>
      <dt>${esc(t("projects.detail.package"))}</dt><dd><code>${esc(p.packageName)}</code></dd>
      <dt>${esc(t("projects.detail.source"))}</dt><dd><code>${esc(p.sourcePath)}</code></dd>
      <dt>${esc(t("projects.detail.module"))}</dt><dd>${esc(p.moduleName)}</dd>
      <dt>${esc(t("projects.detail.debugTask"))}</dt><dd><code>${esc(p.debugTask)}</code></dd>
      <dt>${esc(t("projects.lastBuild"))}</dt><dd>${esc(p.lastBuildStatus ?: "-")}</dd>
      <dt>${esc(t("projects.detail.updated"))}</dt><dd>${esc(p.updatedAt)}</dd>
    </dl>
  </div>

  <div class="card">
    <h2>${esc(t("projects.edit.title"))}</h2>
    <form method="post" action="/projects/${esc(p.id)}/rename-name" style="margin-bottom:14px">
      ${CsrfTokens.hiddenInput(csrf)}
      <label style="font-size:12px;color:var(--text-dim)">${esc(t("projects.edit.name"))}</label>
      <div style="display:flex;gap:6px;margin-top:4px">
        <input type="text" name="name" value="${esc(p.name)}" maxlength="256" required style="flex:1;min-width:0">
        <button type="submit" class="primary" style="width:auto;padding:6px 14px;white-space:nowrap">${esc(t("projects.edit.save"))}</button>
      </div>
    </form>
    ${if (!structuralEnabled) """<p class="hint" style="color:#e0a13a;margin:0 0 12px">⚠ ${esc(t("projects.edit.idleOnly"))}</p>""" else ""}
    <form method="post" action="/projects/${esc(p.id)}/rename-package" style="margin-bottom:14px"
          onsubmit="return confirm('${esc(t("projects.edit.package.confirm")).replace("'", "&#39;")}')">
      ${CsrfTokens.hiddenInput(csrf)}
      <label style="font-size:12px;color:var(--text-dim)">${esc(t("projects.edit.package"))}</label>
      <div style="display:flex;gap:6px;margin-top:4px">
        <input type="text" name="packageName" value="${esc(p.packageName)}" maxlength="256"
               pattern="[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+" required
               style="flex:1;min-width:0" ${if (structuralEnabled) "" else "disabled"}>
        <button type="submit" class="primary" style="width:auto;padding:6px 14px;white-space:nowrap" ${if (structuralEnabled) "" else "disabled"}>${esc(t("projects.edit.save"))}</button>
      </div>
      <p class="hint" style="margin:4px 0 0;font-size:11px">${esc(t("projects.edit.package.hint"))}</p>
    </form>
    <form method="post" action="/projects/${esc(p.id)}/rename-folder"
          onsubmit="return confirm('${esc(t("projects.edit.folder.confirm")).replace("'", "&#39;")}')">
      ${CsrfTokens.hiddenInput(csrf)}
      <label style="font-size:12px;color:var(--text-dim)">${esc(t("projects.edit.folder"))}</label>
      <div style="display:flex;gap:6px;margin-top:4px">
        <input type="text" name="newId" value="${esc(p.id)}" maxlength="64"
               pattern="[a-z0-9][a-z0-9._-]{0,63}" required
               style="flex:1;min-width:0" ${if (structuralEnabled) "" else "disabled"}>
        <button type="submit" class="danger" style="width:auto;padding:6px 14px;white-space:nowrap" ${if (structuralEnabled) "" else "disabled"}>${esc(t("projects.edit.save"))}</button>
      </div>
      <p class="hint" style="margin:4px 0 0;font-size:11px">${esc(t("projects.edit.folder.hint"))}</p>
    </form>
  </div>

  <div class="card">
    <h2>${esc(t("projects.detail.actions"))}</h2>
    <p><a href="/projects/${esc(p.id)}/console" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">${esc(t("projects.detail.console"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/builds" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px">${esc(t("projects.detail.builds"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/history" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">${esc(t("projects.detail.history"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/tree" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">${esc(t("projects.detail.tree"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/zip" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.zip.title"))}">${esc(t("projects.detail.zip"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/env-files" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.envFiles.title"))}">${esc(t("projects.detail.envFiles"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/deps" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.deps.title"))}">${esc(t("projects.detail.deps"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/automation" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.automation.title"))}">${esc(t("projects.detail.automation"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/wrapper" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.wrapper.title"))}">${esc(t("projects.detail.wrapper"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/stats" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)" title="${esc(t("projects.detail.stats.title"))}">${esc(t("projects.detail.stats"))}</a></p>
    <p style="margin-top:12px"><a href="/projects/${esc(p.id)}/git" class="primary-link" style="width:auto;display:inline-block;padding:8px 16px;background:transparent;border:1px solid var(--border);color:var(--text)">${esc(t("projects.detail.git"))}</a></p>
    <form method="post" action="/projects/${esc(p.id)}/delete" style="margin-top:24px"
          onsubmit="return confirm('${esc(t("projects.detail.deleteConfirm")).replace("'", "&#39;")}')">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="danger" style="width:100%">${esc(t("projects.detail.delete"))}</button>
    </form>
  </div>

  <div class="card">
    <h2>${esc(t("projects.detail.recentBuilds"))}</h2>
    <table class="devices">
      <thead><tr><th>${esc(t("projects.detail.col.id"))}</th><th>${esc(t("projects.detail.col.status"))}</th><th>${esc(t("projects.detail.col.startedAt"))}</th></tr></thead>
      <tbody>$recentRows</tbody>
    </table>
  </div>
</section>
""",
            embed = embed,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/console — Claude 콘솔
    // ────────────────────────────────────────────────────────────────────

    fun consolePage(
        username: String,
        p: ProjectDto,
        sessionId: String?,
        isAlive: Boolean,
        claudeCli: CheckItemDto? = null,
        claudeAuth: CheckItemDto? = null,
        csrf: String? = null,
        /** v0.13.0 — true 면 nav 의 "프롬프트/Chat" 활성화 + 사이드 링크 (프로젝트로 / 빌드 등) 숨김. */
        isChat: Boolean = false,
        /** v0.18.0 — 프로젝트 등록 직후 첫 console 진입 시 자동 입력될 starter prompt. */
        starterPrompt: String? = null,
        /**
         * v1.7.3 — 서버 재시작 후에도 기존 conversation history 가 콘솔에 즉시 보이도록
         * 호출자가 DB 의 ConversationTurn 을 ASC 정렬해 전달. 빈 list 면 표시 안 함.
         * inline JSON 으로 embed 되어 페이지 load 직후 JS 가 prepend.
         */
        initialHistory: List<com.siamakerlab.vibecoder.server.repo.ConversationTurnRow> = emptyList(),
        /**
         * v1.54.0 — ChatGPT 스타일 다중 채팅. non-null 이면 좌측에 채팅 목록 사이드바를
         * 두고 기존 콘솔 본문을 우측 메인 영역으로 감싼다 (isChat 전용).
         */
        chatSidebar: String? = null,
        /** v1.54.0 — 활성 채팅 표시 제목 (헤더). null 이면 "General Chat". */
        chatTitle: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val statusBadge = when {
            isAlive -> """<span class="ok">running</span>"""
            sessionId != null -> """<span class="dim">idle (will resume)</span>"""
            else -> """<span class="dim">no session</span>"""
        }
        // Claude CLI 미설치 또는 인증 누락 시 큰 안내 카드 + 프롬프트 폼 비활성화.
        val cliMissing = claudeCli != null && claudeCli.status != CheckStatus.OK
        val authMissing = claudeAuth != null && claudeAuth.status == CheckStatus.ERROR
        val blocking = cliMissing || authMissing
        val authBannerHtml = when {
            cliMissing -> renderClaudeBanner(
                title = t("console.banner.cli.title"),
                body = t("console.banner.cli.body"),
                cmd = "docker exec -it vibe-coder-server vibe-doctor claude",
                detail = claudeCli?.detail,
                lang = lang,
            )
            authMissing -> renderClaudeBanner(
                title = t("console.banner.auth.title"),
                body = t("console.banner.auth.body"),
                cmd = "docker exec -it --user vibe vibe-coder-server claude login",
                detail = claudeAuth?.detail,
                lang = lang,
            )
            else -> ""
        }
        // v0.12.4 — JS 문자열 컨텍스트는 jsLit() 사용 (HTML escape 만으로는 < / </script>
        // 차단·따옴표 처리가 충분치 않다). projectId 가 PROJECT_ID_PATTERN 검증을
        // 통과하긴 하나 defense-in-depth.
        val projectIdJs = jsLit(p.id)

        // v1.58.0 — 입력창 상단 빠른 프롬프트 버블 버튼. 클릭 시 input 에 채우고
        // form.requestSubmit() → 기존 송신/큐잉 경로 그대로 재사용. blocking(인증 미비)
        // 이면 비활성. 코드 전용(fixAll/review) 버튼은 대화 전용 General Chat 에선 제외.
        val quickBarHtml = run {
            val dis = if (blocking) " disabled" else ""
            val optionBtns = listOf("A", "B", "C", "D").joinToString("") { o ->
                """<button type="button" class="qp-btn qp-opt" data-prompt="$o" title="${esc(t("console.quick.optionTip").format(o))}"$dis>$o</button>"""
            }
            val textKeys = buildList {
                add("continue")
                // v1.91.3 — 코드 작업 전용 (대화 전용 General Chat 제외).
                if (!isChat) { add("restart"); add("fixAll"); add("review") }
                add("recommended")
            }
            val textBtns = textKeys.joinToString("") { key ->
                val label = t("console.quick.$key.label")
                val prompt = t("console.quick.$key.prompt")
                """<button type="button" class="qp-btn" data-prompt="${esc(prompt)}" title="${esc(prompt)}"$dis>${esc(label)}</button>"""
            }
            """
<style>
  .quick-prompts { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin:0 0 8px; }
  .quick-prompts .qp-btn {
    font-size:12px; padding:5px 12px; background:#1a1a1a; color:var(--text);
    border:1px solid #333; border-radius:999px; cursor:pointer; white-space:nowrap;
    line-height:1.2; font-family:inherit;
  }
  .quick-prompts .qp-btn:hover:not(:disabled) { background:#252525; border-color:#3a82f6; }
  .quick-prompts .qp-btn:active:not(:disabled) { transform:translateY(1px); }
  .quick-prompts .qp-btn:disabled { opacity:.4; cursor:not-allowed; }
  .quick-prompts .qp-opt {
    width:32px; padding:5px 0; text-align:center; font-weight:600;
    font-family:ui-monospace,Menlo,monospace;
  }
  .quick-prompts .qp-sep { width:1px; align-self:stretch; min-height:20px; background:#333; margin:0 2px; }
</style>
<div id="quick-prompts" class="quick-prompts" role="toolbar"
     aria-label="${esc(t("console.quick.title"))}" title="${esc(t("console.quick.title"))}">
  $optionBtns
  <span class="qp-sep" aria-hidden="true"></span>
  $textBtns
</div>"""
        }

        // v1.59.0 — 프롬프트 자동화 패널 (서버 백그라운드 autopilot). 대화 전용 General
        // Chat 에선 코드 작업 자동화가 부적합하므로 숨김. blocking(인증 미비)이면 비활성.
        val automationPanelHtml = if (isChat) "" else run {
            val dis = if (blocking) " disabled" else ""
            """
<details class="auto-panel" id="auto-panel" style="margin:8px 0 0">
  <summary style="cursor:pointer;font-size:13px;font-weight:600;color:#6aa9ff">🤖 ${esc(t("console.automation.title"))} <span id="auto-badge" class="dim" style="font-weight:400;font-size:12px"></span></summary>
  <div style="border:1px solid #243049;border-radius:8px;padding:10px;margin-top:6px;background:rgba(30,64,175,0.05)">
    <p class="dim" style="font-size:12px;margin:0 0 8px">${esc(t("console.automation.hint"))}</p>
    <!-- 진행 중 뷰 -->
    <div id="auto-running" hidden style="display:flex;align-items:center;gap:10px;margin-bottom:6px">
      <strong style="color:#6aa9ff">▶ ${esc(t("console.automation.running"))}</strong>
      <span id="auto-progress" style="font-size:13px"></span>
      <button type="button" id="auto-stop-btn" class="chip chip-danger"$dis>${esc(t("console.automation.stop"))}</button>
    </div>
    <!-- 시작 뷰 -->
    <div id="auto-idle">
      <div style="display:flex;gap:14px;flex-wrap:wrap;align-items:center;margin-bottom:6px">
        <label style="margin:0;font-size:13px"><input type="radio" name="auto-mode" value="repeat" checked> ${esc(t("console.automation.repeat"))}</label>
        <label style="margin:0;font-size:13px"><input type="radio" name="auto-mode" value="sequence"> ${esc(t("console.automation.sequence"))}</label>
        <label style="margin:0;font-size:13px">${esc(t("console.automation.count"))}<input type="number" id="auto-count" min="1" max="200" value="20" style="width:70px;margin-left:6px;padding:4px"></label>
      </div>
      <textarea id="auto-prompts" rows="2" style="width:100%;padding:8px" placeholder="${esc(t("console.automation.placeholder"))}"></textarea>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:6px">
        <button type="button" id="auto-start-btn" class="chip chip-action" style="background:#1e40af;color:#fff"$dis>${esc(t("console.automation.start"))}</button>
        <select id="auto-preset" style="font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333;display:none"></select>
        <button type="button" id="auto-preset-start" class="chip chip-link" style="display:none"$dis>${esc(t("console.automation.presetStart"))}</button>
        <a href="/projects/${esc(p.id)}/automation/prompts" target="_top" class="chip chip-link" style="margin-left:auto">${esc(t("console.automation.manage"))}</a>
      </div>
    </div>
  </div>
</details>"""
        }

        val navPath = if (isChat) "/chat" else "/projects"
        // v1.54.0 — 다중 채팅이면 헤더 제목을 활성 채팅명으로. 단일 General Chat 호환 fallback.
        val titleSuffix = if (isChat) (chatTitle?.takeIf { it.isNotBlank() } ?: "General Chat") else t("console.title")
        // v1.54.0 — chatSidebar 가 주어지면 좌측 목록 + 우측 메인 flex 레이아웃으로 감싼다.
        val chatShellOpen = if (chatSidebar != null) """
<style>
  .chat-shell { display:flex; gap:14px; align-items:flex-start; }
  .chat-side {
    flex:0 0 264px; min-width:0; box-sizing:border-box;
    position:sticky; top:0; max-height:calc(100vh - 70px); overflow-y:auto;
    background:#0d1018; border:1px solid #1f2330; border-radius:10px; padding:10px;
  }
  .chat-main { flex:1; min-width:0; }
  .chat-new-btn {
    display:flex; align-items:center; justify-content:center; gap:6px; width:100%;
    padding:9px 12px; background:var(--accent,#6aa9ff); color:#06121f; border:0;
    border-radius:8px; font-size:13px; font-weight:600; cursor:pointer; font-family:inherit;
  }
  .chat-new-btn:hover { filter:brightness(1.08); }
  .chat-list { margin-top:10px; display:flex; flex-direction:column; gap:2px; }
  .chat-item { position:relative; display:flex; align-items:center; border-radius:7px; }
  .chat-item:hover { background:#161b26; }
  .chat-item.active { background:rgba(106,169,255,0.14); }
  .chat-item-link {
    flex:1; min-width:0; display:flex; align-items:center; gap:7px;
    padding:8px 9px; text-decoration:none; color:var(--text,#ddd); font-size:13px;
  }
  .chat-item.active .chat-item-link { color:var(--accent,#6aa9ff); }
  .chat-item-title { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .chat-busy-dot {
    flex-shrink:0; width:7px; height:7px; border-radius:50%; background:#69db7c;
    animation:vibe-busy-pulse 1.4s ease-in-out infinite;
  }
  .chat-item-menu { position:relative; flex-shrink:0; }
  .chat-item-menu > summary {
    list-style:none; cursor:pointer; color:var(--text-dim,#888); padding:6px 9px; font-size:14px; line-height:1;
  }
  .chat-item-menu > summary::-webkit-details-marker { display:none; }
  .chat-item-menu[open] > summary { color:var(--text,#ddd); }
  .chat-item-pop {
    position:absolute; right:0; top:calc(100% + 2px); z-index:30; min-width:200px;
    background:#131722; border:1px solid #2a3145; border-radius:8px; padding:8px;
    box-shadow:0 8px 24px rgba(0,0,0,0.45); display:flex; flex-direction:column; gap:8px;
  }
  .chat-rename-row { display:flex; gap:5px; }
  .chat-rename-row input {
    flex:1; min-width:0; box-sizing:border-box; padding:5px 7px; font-size:12px;
    background:#0c0f17; border:1px solid #2a3145; border-radius:5px; color:var(--text,#ddd); font-family:inherit;
  }
  .chat-pop-btn {
    padding:5px 9px; font-size:12px; border-radius:5px; border:1px solid #2a3145;
    background:#1a1f2c; color:var(--text,#ddd); cursor:pointer; font-family:inherit; white-space:nowrap;
  }
  .chat-pop-btn:hover { background:#222838; }
  .chat-pop-btn.danger { color:#ff9e9e; border-color:#3a2424; }
  .chat-pop-btn.danger:hover { background:#2c1a1a; }
  .chat-empty { color:#5a6175; font-size:12px; padding:10px 4px; text-align:center; }
  /* v1.54.1 — 사이드바 접기/펼치기 토글. */
  .chat-side-head { display:flex; justify-content:flex-end; margin-bottom:4px; }
  .chat-collapse-btn {
    background:transparent; border:0; color:var(--text-dim,#888); cursor:pointer;
    font-size:15px; line-height:1; padding:3px 7px; border-radius:5px; font-family:inherit;
  }
  .chat-collapse-btn:hover { background:#1a1f2c; color:var(--text,#ddd); }
  .chat-expand-btn {
    display:none; align-items:center; gap:7px; margin-bottom:10px;
    background:#0d1018; border:1px solid #1f2330; color:var(--text,#ddd); cursor:pointer;
    font-size:12px; padding:7px 12px; border-radius:8px; font-family:inherit;
  }
  .chat-expand-btn:hover { background:#1a1f2c; border-color:#2a3145; }
  #chat-shell.collapsed .chat-side { display:none; }
  #chat-shell.collapsed .chat-expand-btn { display:inline-flex; }
  @media (max-width:760px) {
    .chat-shell { flex-direction:column; }
    .chat-side { flex:none; width:100%; position:static; max-height:240px; }
  }
</style>
<div class="chat-shell" id="chat-shell">
  <aside class="chat-side" aria-label="${esc(t("chat.sidebar.label"))}">
    <div class="chat-side-head">
      <button type="button" class="chat-collapse-btn" data-chat-toggle
              title="${esc(t("chat.collapse"))}" aria-label="${esc(t("chat.collapse"))}">⟨</button>
    </div>
    $chatSidebar
  </aside>
  <div class="chat-main">
    <button type="button" class="chat-expand-btn" data-chat-toggle
            title="${esc(t("chat.expand"))}">☰ ${esc(t("chat.show"))}</button>""" else ""
        val chatShellClose = if (chatSidebar != null) """
  </div>
</div>
<script>(function(){
  var shell = document.getElementById('chat-shell');
  if (!shell) return;
  var KEY = 'vibe-chat-side-collapsed';
  try { if (localStorage.getItem(KEY) === '1') shell.classList.add('collapsed'); } catch (e) {}
  function toggle() {
    var c = shell.classList.toggle('collapsed');
    try { localStorage.setItem(KEY, c ? '1' : '0'); } catch (e) {}
  }
  var btns = shell.querySelectorAll('[data-chat-toggle]');
  for (var i = 0; i < btns.length; i++) btns[i].addEventListener('click', toggle);
})();</script>""" else ""
        // v1.48.0 — 프로젝트 콘솔의 nav chip(빌드/히스토리/파일/Git/심볼/에이전트) 은 모두
        // 상단 프로젝트 탭으로 대체돼 중복 → 제거. 일반 Chat(isChat) 은 탭 바깥 독립 페이지라
        // history 링크만 유지.
        val sideLinks = if (isChat) """
      <a href="/chat/history" class="chip chip-link">${esc(t("console.nav.history"))}</a>"""
        else ""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · $titleSuffix",
            username = username,
            currentPath = navPath,
            csrf = csrf,
            lang = lang,
            wide = isChat,  // v1.104.0 — 채팅(/chat)만 넓은 폭. 콘솔은 통합 탭 inner 라 무관.
            body = """$chatShellOpen
<!-- v1.48.0 — 세션 카드 제거. 세션 상태 + 남은 액션(중지/새 세션)을 헤더 우측(탭 바로 밑)으로
     이동. 나머지 버블 버튼(빌드/파일/Git/에이전트/히스토리/심볼)은 상단 프로젝트 탭으로
     대체돼 제거(isChat 만 history 링크 유지). busy-badge 는 전송 버튼 라인에 있어 id 유지. -->
<style>
  @keyframes vibe-busy-pulse {
    0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(105,219,124,0.55); }
    50% { opacity: 0.85; box-shadow: 0 0 0 6px rgba(105,219,124,0); }
  }
  #busy-badge { transition: background 0.2s, color 0.2s; }
  #busy-badge[data-state="responding"] {
    background: rgba(105,219,124,0.18); color: #69db7c;
    animation: vibe-busy-pulse 1.4s ease-in-out infinite;
  }
  #busy-badge[data-state="idle"], #busy-badge[data-state="ready"] {
    background: rgba(255,255,255,0.06); color: var(--text-dim, #888);
  }
  /* v1.100.0 — 백그라운드 작업 진행 중이라 turn 이 재개 대기. 노랑(pulse 없이 정적). */
  #busy-badge[data-state="waiting"] {
    background: rgba(250,176,5,0.18); color: #fab005;
  }
  /* v1.100.0 — cancel/crash/idle/rate-limit 소진으로 중단된 turn. 보라. */
  #busy-badge[data-state="stopped"] {
    background: rgba(151,117,250,0.18); color: #b197fc;
  }
  /* v1.100.0 — API/turn 에러로 종료. 빨강. */
  #busy-badge[data-state="error"] {
    background: rgba(255,107,107,0.18); color: #ff8787;
  }
  /* v1.84.0 — 백그라운드 작업 진행 카드 패널. */
  .bg-tasks {
    margin-bottom:8px; border:1px solid #2a3a2a; border-radius:8px;
    background:rgba(105,219,124,0.05); padding:8px 10px;
  }
  .bg-tasks-head { font-size:11px; color:var(--text-dim,#888); margin-bottom:6px; font-weight:600; }
  .bg-task-card {
    display:flex; align-items:center; gap:8px; padding:6px 8px; border-radius:6px;
    background:rgba(255,255,255,0.03); margin-top:4px; font-size:12px;
  }
  .bg-task-card:first-child { margin-top:0; }
  .bg-task-icon { flex-shrink:0; width:16px; text-align:center; }
  .bg-task-card[data-status="running"] .bg-task-icon {
    color:#69db7c; animation:vibe-busy-pulse 1.4s ease-in-out infinite; border-radius:50%;
  }
  .bg-task-card[data-status="completed"] .bg-task-icon { color:#69db7c; }
  .bg-task-card[data-status="failed"] .bg-task-icon { color:#ff8787; }
  .bg-task-desc { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .bg-task-meta { flex-shrink:0; color:var(--text-dim,#888); font-size:10px; font-family:monospace; }
  .bg-task-card[data-status="completed"] { opacity:0.7; transition:opacity 0.4s; }
  /* v1.85.0 — assistant 마크다운 렌더 */
  .log-body.md { line-height:1.55; }
  .log-body.md > :first-child { margin-top:0; }
  .log-body.md > :last-child { margin-bottom:0; }
  .log-body.md h1,.log-body.md h2,.log-body.md h3,.log-body.md h4,.log-body.md h5,.log-body.md h6 {
    margin:0.7em 0 0.35em; font-weight:600; line-height:1.3;
  }
  .log-body.md h1 { font-size:1.4em; } .log-body.md h2 { font-size:1.25em; }
  .log-body.md h3 { font-size:1.12em; } .log-body.md h4 { font-size:1.02em; }
  .log-body.md ul,.log-body.md ol { margin:0.4em 0; padding-left:1.5em; }
  .log-body.md li { margin:0.15em 0; }
  .log-body.md code.md-code { background:rgba(255,255,255,0.08); padding:1px 5px; border-radius:4px; font-size:0.9em; }
  /* v1.90.12 — .md-pre 코드블록 스타일을 assistant(.log-body.md) 뿐 아니라 tool 결과
     (.log-body, md 아님)에도 적용되게 .log-body 로 확장. */
  .log-body pre.md-pre { background:#161616; border:1px solid #2a2a2a; border-radius:6px; padding:10px 12px; overflow-x:auto; margin:0.5em 0; }
  .log-body pre.md-pre code { background:none; padding:0; font-size:0.88em; line-height:1.45; }
  .log-body.md blockquote { border-left:3px solid #444; margin:0.5em 0; padding:2px 0 2px 12px; color:var(--text-dim,#aaa); }
  .log-body.md a { color:#74b9ff; text-decoration:underline; }
  .log-body.md hr { border:none; border-top:1px solid #333; margin:0.8em 0; }
  .log-body.md strong { font-weight:600; }
  /* v1.86.4 — 마크다운 테이블 */
  .log-body.md table.md-table { border-collapse:collapse; margin:0.5em 0; font-size:0.9em; display:block; overflow-x:auto; max-width:100%; }
  .log-body.md table.md-table th, .log-body.md table.md-table td { border:1px solid #3a3a3a; padding:4px 9px; text-align:left; }
  .log-body.md table.md-table th { background:rgba(255,255,255,0.06); font-weight:600; }
  .log-body.md table.md-table tr:nth-child(even) td { background:rgba(255,255,255,0.02); }
  /* v1.85.0 — assistant 긴 메시지 접기 */
  /* v1.90.5 — 접기/펼치기를 모든 콘솔 메시지에 적용(이전엔 .assistant 한정). */
  .log-content[data-clampable="1"] { position:relative; cursor:pointer; }
  .log-content.clamped .log-body { max-height:180px; overflow:hidden; }
  .log-content.clamped::after {
    content:'${t("console.message.expand")}'; position:absolute; left:0; right:0; bottom:0;
    text-align:center; font-size:11px; color:#74b9ff; padding:22px 0 4px;
    background:linear-gradient(to bottom, transparent, #1e1e1e 75%); pointer-events:none;
  }
  .log-content[data-clampable="1"]:not(.clamped)::after {
    content:'${t("console.message.collapse")}'; display:block; text-align:center;
    font-size:11px; color:#74b9ff; padding:6px 0 2px;
  }
</style>
<header style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
  <h1 style="margin:0">$titleSuffix
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)}${if (isChat) "" else " (${esc(p.id)})"}</small>
  </h1>
  <div class="console-actions" style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;justify-content:flex-end">
    <span class="dim" style="font-size:12px">${esc(t("console.session"))} $statusBadge${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}</span>
    $sideLinks
    <button type="button" id="stop-btn" class="chip chip-danger" style="display:none"
            title="${esc(t("console.stop.title"))}">${esc(t("console.stop"))}</button>
    <form method="post" action="/projects/${esc(p.id)}/console/new" style="display:inline"
          onsubmit="return confirm('${esc(t("console.newSession.confirm")).replace("'", "&#39;")}')">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="chip chip-danger">${esc(t("console.newSession"))}</button>
    </form>
  </div>
</header>

$authBannerHtml

<!-- v1.91.4 — 콘솔 메시지 필터(버튼 → 다이얼로그) + 자동 스크롤 토글을 한 줄에.
     좌측 필터 버튼 클릭 시 모달이 뜨고, 우측 끝에 자동 스크롤 토글. 필터 체크박스
     로직(.filter-cb / #filter-summary / #filter-reset)은 그대로 — 모달 안으로 이동만. -->
<style>
  #filter-modal { position:fixed; inset:0; z-index:50; display:flex; align-items:center; justify-content:center; background:rgba(0,0,0,0.55); padding:16px; }
  #filter-modal[hidden] { display:none; }
  #filter-modal .filter-box { background:#15171c; border:1px solid #2a2a2a; border-radius:10px; padding:16px 18px; width:100%; max-width:520px; max-height:85vh; overflow-y:auto; box-shadow:0 12px 40px rgba(0,0,0,0.5); box-sizing:border-box; }
  #filter-modal .filter-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; }
  #filter-modal .filter-head strong { font-size:14px; }
  #filter-modal .filter-x { background:transparent; border:0; color:var(--text-dim); font-size:16px; cursor:pointer; line-height:1; padding:2px 6px; }
  #filter-modal .filter-x:hover { color:var(--text); }
</style>
<div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
  <button type="button" id="filter-open" class="chip chip-link"
          style="font-size:11px;padding:4px 11px;display:inline-flex;align-items:center;gap:5px"
          title="${esc(t("console.filter.title"))}">
    🔍 ${esc(t("console.filter.title"))} <span id="filter-summary" class="dim" style="font-size:11px"></span>
  </button>
  <label for="autoscroll-toggle" style="display:flex;align-items:center;gap:4px;font-size:11px;color:var(--text-dim);cursor:pointer;user-select:none;margin-left:auto"
         title="${esc(t("console.autoscroll.tip"))}">
    <input type="checkbox" id="autoscroll-toggle" style="margin:0">
    📌 ${esc(t("console.autoscroll"))}
  </label>
</div>

<div id="filter-modal" hidden>
  <div class="filter-box" role="dialog" aria-modal="true" aria-label="${esc(t("console.filter.title"))}">
    <div class="filter-head">
      <strong>🔍 ${esc(t("console.filter.title"))}</strong>
      <button type="button" id="filter-close" class="filter-x" aria-label="${esc(t("memos.close"))}">✕</button>
    </div>
    <p class="hint" style="margin:0 0 8px;font-size:11px">${esc(t("console.filter.hint"))}</p>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px">
      <div>
        <div class="dim" style="font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:4px">${esc(t("console.filter.mandatory"))}</div>
        <label style="display:block;padding:2px 0;opacity:0.7;cursor:not-allowed"><input type="checkbox" class="filter-cb" data-cat="assistant" checked disabled> ${esc(t("console.filter.cat.assistant"))}</label>
        <label style="display:block;padding:2px 0;opacity:0.7;cursor:not-allowed"><input type="checkbox" class="filter-cb" data-cat="error" checked disabled> ${esc(t("console.filter.cat.error"))}</label>
        <label style="display:block;padding:2px 0;opacity:0.7;cursor:not-allowed"><input type="checkbox" class="filter-cb" data-cat="system" checked disabled> ${esc(t("console.filter.cat.system"))}</label>
      </div>
      <div>
        <div class="dim" style="font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:4px">${esc(t("console.filter.optional"))}</div>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="tool_use" checked> ${esc(t("console.filter.cat.tool_use"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="tool_result" checked> ${esc(t("console.filter.cat.tool_result"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="session" checked> ${esc(t("console.filter.cat.session"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="done" checked> ${esc(t("console.filter.cat.done"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="replay" checked> ${esc(t("console.filter.cat.replay"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="ws" checked> ${esc(t("console.filter.cat.ws"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="todo" checked> ${esc(t("console.filter.cat.todo"))}</label>
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="thinking" checked> ${esc(t("console.filter.cat.thinking"))}</label>
      </div>
    </div>
    <div style="margin-top:12px;display:flex;justify-content:flex-end;gap:8px">
      <button type="button" id="filter-reset" class="chip chip-link" style="font-size:11px;padding:3px 10px">${esc(t("console.filter.reset"))}</button>
      <button type="button" id="filter-done" class="chip" style="font-size:11px;padding:3px 12px">${esc(t("memos.close"))}</button>
    </div>
  </div>
</div>

<!-- v1.6.4 — 스크롤 + 우하단 jump-to-bottom 버튼 wrapper. -->
<div class="console-log-wrap">
  <div id="console-log" class="console-log" aria-live="polite"></div>
  <!-- v1.7.12 — 응답중 indicator. setInFlight 가 hidden 속성 토글. -->
  <div id="console-spinner" class="console-spinner" hidden aria-hidden="true">
    <span class="spinner"></span>
    <span class="spinner-label">${esc(t("console.busy.responding"))}</span>
  </div>
  <!--
    v1.7.3 — 서버 재시작 후에도 기존 conversation 이 즉시 보이도록 DB 의 ConversationTurn
    을 inline JSON 으로 embed. WS ring buffer 는 in-memory 이라 재시작 시 휘발 → 이전엔
    빈 화면 + "no session" 만 보였음. 페이지 load 직후 inline JS 가 parse 후 append().
    `<` / `&` 는 jsLit() 처럼 < / & escape 되어 </script> 닫힘 차단.
  -->
  <script id="initial-history" type="application/json">${renderInitialHistoryJson(initialHistory)}</script>
  <button type="button" id="console-jump-bottom" class="console-jump-bottom"
          title="${esc(t("console.jumpToLatest"))}" aria-label="${esc(t("console.jumpToLatest"))}">
    ↓<span class="badge" id="console-jump-badge" style="display:none">0</span>
  </button>
</div>

<!--
  v1.6.3 — 사용자 요구 순서 (콘솔 본문 → 아래로):
    1) Todo 요약 패널
    2) 입력창 + Send
    3) 프롬프트 템플릿 + 관리 버튼 (한 줄)
    4) Agent dispatch + 관리 버튼 (한 줄)

  v1.3.0 — Todo 요약 패널. <details> 의 open 상태는 localStorage 영속.
  콘솔 카드 표시 여부는 필터의 'todo' 카테고리로 별도 토글 가능.
-->
<!-- v1.84.0 — 백그라운드 작업(Bash run_in_background 등) 진행 카드. 실행 중인 task 가
     있을 때만 표시. claude 가 작업을 띄우고 turn 을 끝내도 진행 상황을 알 수 있게 한다. -->
<div id="bg-tasks" class="bg-tasks" hidden>
  <div class="bg-tasks-head">⚙ <span id="bg-tasks-title">${esc(t("console.bgtasks.title"))}</span></div>
  <div id="bg-tasks-list"></div>
</div>

<details id="todo-panel" style="margin-bottom:6px;font-size:12px">
  <summary style="cursor:pointer;color:var(--text-dim);padding:4px 0;user-select:none">
    📋 <span id="todo-panel-title">${esc(t("console.todo.title"))}</span>
    <span id="todo-panel-summary" class="dim" style="font-size:11px"></span>
  </summary>
  <div style="padding:8px 10px;background:rgba(255,255,255,0.03);border:1px solid #2a2a2a;border-radius:6px;margin-top:4px;max-height:200px;overflow:auto">
    <div id="todo-panel-empty" class="dim" style="font-size:11px">${esc(t("console.todo.empty"))}</div>
    <ul id="todo-panel-list" style="list-style:none;margin:0;padding:0;display:none"></ul>
  </div>
</details>

$quickBarHtml
$automationPanelHtml

<form id="prompt-form" class="prompt-form" autocomplete="off">
  <!-- maxlength 는 char 단위라 ASCII 기준 32K. 한국어 등 multi-byte 입력은
       실제 UTF-8 byte 가 32K 를 넘으면 서버에서 prompt_too_large (400) 로 거절. -->
  <!-- v1.16.1 — textarea + voice/send 버튼을 동일 row 에 가로 배치. send 가
       textarea 의 우측 (사용자 요청). 버튼들은 column flex 로 stack, 하단 정렬. -->
  <div style="display:flex;gap:8px;align-items:stretch">
    <textarea id="prompt-input" rows="${if (starterPrompt != null) 8 else 3}" maxlength="32768"
              placeholder="${esc(if (blocking) t("console.input.disabled") else t("console.input.placeholder")).replace("\n", "&#10;")}"
              style="flex:1;width:auto;min-width:0"
              ${if (blocking) "disabled" else "required"}>${esc(starterPrompt)}</textarea>
    <div style="display:flex;flex-direction:column;gap:6px;justify-content:flex-end;flex-shrink:0">
      <!-- v1.15.0 — Web Speech API 음성 입력. 미지원 브라우저는 voice-input.js 가 자동 hide. -->
      <button type="button" id="voice-btn" hidden
              data-title-start="${esc(t("console.voice.start"))}"
              data-title-stop="${esc(t("console.voice.stop"))}"
              title="${esc(t("console.voice.start"))}"
              style="width:auto;padding:8px 12px;background:#1a1a1a;color:var(--text);border:1px solid #2a2a2a;border-radius:6px;cursor:pointer;font-size:16px"
              ${if (blocking) "disabled" else ""}>🎤</button>
      <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px;white-space:nowrap" ${if (blocking) "disabled" else ""}>${esc(t("console.input.send"))}</button>
    </div>
  </div>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px;gap:8px;flex-wrap:wrap">
    <!-- v1.7.4 — busy 뱃지 + hint 라벨 한 줄. busy 뱃지가 좌측 끝, 그 다음 hint. -->
    <div style="display:flex;align-items:center;gap:8px;min-width:0;flex:1">
      <span id="busy-badge" data-state="idle"
            style="${if (embed) "display:none;" else ""}font-size:12px;padding:3px 10px;border-radius:12px;font-weight:500;white-space:nowrap;flex-shrink:0">${esc(t("console.busy.idle"))}</span>
      <small class="dim" style="min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(if (blocking) t("console.input.blockedHint") else t("console.input.hint"))}</small>
    </div>
    <!-- v1.15.1 — "자동 전송" 옵션 (voice input). checked 시 발화 종료 시 자동 submit. -->
    <label id="voice-auto-send-wrap" for="voice-auto-send"
           style="display:flex;align-items:center;gap:4px;font-size:11px;color:var(--text-dim);cursor:pointer;flex-shrink:0;user-select:none"
           title="${esc(t("console.voice.autoSend.tip"))}">
      <input type="checkbox" id="voice-auto-send" style="margin:0">
      ${esc(t("console.voice.autoSend"))}
    </label>
  </div>
</form>
<script src="/static/voice-input.js" defer></script>
<style>
  /* v1.15.0 — 음성 입력 listening 시각 강조. */
  #voice-btn.listening {
    background: #7f1d1d; color: #fff; border-color: #b91c1c;
    animation: voice-pulse 1.2s ease-in-out infinite;
  }
  @keyframes voice-pulse {
    0%, 100% { box-shadow: 0 0 0 0 rgba(220,38,38,0.6); }
    50% { box-shadow: 0 0 0 8px rgba(220,38,38,0); }
  }
</style>

<!-- v1.6.3 — 프롬프트 템플릿 + 관리 버튼 (한 줄). 입력창 바로 아래. -->
<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:8px">
  <select id="template-picker" style="flex:1;min-width:0;font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333">
    <option value="">${esc(t("console.template.placeholder"))}</option>
  </select>
  <a href="/prompts" class="chip chip-link" style="font-size:11px;margin-left:0;flex-shrink:0">${esc(t("console.template.manage"))}</a>
</div>

<!-- v1.6.3 — Agent dispatch + 관리 버튼 (한 줄). 가장 하단. -->
<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:6px">
  <select id="agent-picker" style="flex:1;min-width:0;font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333" title="Dispatch a registered sub-agent into the prompt">
    <option value="">${esc(t("console.agent.placeholder"))}</option>
  </select>
  <a href="/agents" class="chip chip-link" style="font-size:11px;margin-left:0;flex-shrink:0">${esc(t("console.agent.manage"))}</a>
</div>

<!-- v1.90.12 — 코드블록 syntax highlight (assistant 마크다운 + tool 결과). 동기 로드해
     append 시점에 window.hljs 준비. 이전엔 콘솔이 highlight.js 를 로드하지 않아 hljs 부재로
     highlight 가 항상 skip 됐다. -->
<link rel="stylesheet" href="/static/highlight-github-dark.min.css">
<script src="/static/highlight.min.js"></script>
<!-- v1.70.0 — 콘솔 친화 렌더러 (tool_use/tool_result/unknown). inline 스크립트보다 먼저 동기 로드. -->
<script src="/static/console-render.js?v=1.90.15"></script>
<script>
  // v1.86.3 — 구 SW 가 console-render.js 를 깨진/구버전(renderMarkdown 부재)으로 박제하면
  // 마크다운/접기가 동작하지 않는다. 감지 시 SW·캐시를 전부 제거하고 1회 reload(sessionStorage
  // 가드)해 SW 없는 상태로 최신 자산을 직접 받는다. reload 후 AdminTemplates 의 register 가
  // 새 SW(?v 우회)를 재설치한다.
  (function () {
    if (window.VibeConsole && window.VibeConsole.renderMarkdown) return;
    try {
      if (sessionStorage.getItem('__vibeSwPurged')) return;
      sessionStorage.setItem('__vibeSwPurged', '1');
    } catch (e) {}
    var reload = function () { location.reload(); };
    var ps = [];
    if (navigator.serviceWorker) {
      ps.push(navigator.serviceWorker.getRegistrations()
        .then(function (rs) { return Promise.all(rs.map(function (r) { return r.unregister(); })); })
        .catch(function () {}));
    }
    if (window.caches) {
      ps.push(caches.keys()
        .then(function (ks) { return Promise.all(ks.map(function (k) { return caches.delete(k); })); })
        .catch(function () {}));
    }
    Promise.all(ps).then(reload, reload);
  })();
</script>
<script>
(function() {
  var projectId = $projectIdJs;
  var logEl = document.getElementById('console-log');
  var form = document.getElementById('prompt-form');
  var input = document.getElementById('prompt-input');
  var sendBtn = document.getElementById('send-btn');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // v0.97.0 — 콘솔 메시지 필터링.
  // mandatory 카테고리 (assistant/error/system) 는 disabled checkbox — 항상 표시.
  // optional 카테고리 6 개는 localStorage 영속 토글.
  // append(cls, label, body, cat) → row.dataset.filterCat = cat, 필터 적용.
  var FILTER_KEY = 'vibe-console-filter-' + projectId;
  var MANDATORY_CATS = ['assistant', 'error', 'system'];
  var OPTIONAL_CATS = ['tool_use', 'tool_result', 'session', 'done', 'replay', 'ws', 'todo', 'thinking'];
  var filterState = (function() {
    try {
      var raw = localStorage.getItem(FILTER_KEY);
      if (!raw) return {};
      var parsed = JSON.parse(raw);
      return typeof parsed === 'object' && parsed !== null ? parsed : {};
    } catch (e) { return {}; }
  })();
  function isVisible(cat) {
    if (MANDATORY_CATS.indexOf(cat) >= 0) return true;
    return filterState[cat] !== false;  // 기본 true.
  }
  function persistFilter() {
    try { localStorage.setItem(FILTER_KEY, JSON.stringify(filterState)); } catch (e) {}
  }
  function applyFilterToAll() {
    var rows = logEl.querySelectorAll('.log-line');
    for (var i = 0; i < rows.length; i++) {
      var c = rows[i].dataset.filterCat || 'ws';
      rows[i].style.display = isVisible(c) ? '' : 'none';
    }
    updateFilterSummary();
  }
  function updateFilterSummary() {
    var sumEl = document.getElementById('filter-summary');
    if (!sumEl) return;
    var hidden = OPTIONAL_CATS.filter(function(c) { return filterState[c] === false; });
    sumEl.textContent = hidden.length === 0 ? '' : '— ' + hidden.length + ' hidden';
  }

  // v1.6.4 — 우하단 jump-to-bottom 버튼 + unread badge.
  var jumpBtn = document.getElementById('console-jump-bottom');
  var jumpBadge = document.getElementById('console-jump-badge');
  var unreadCount = 0;

  function isAtBottom() {
    return logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 12;
  }

  // v1.20.0 — Auto-scroll 모드 토글. ON 시 사용자 스크롤 위치 무시하고 항상 stick.
  var autoScrollOn = true;
  try {
    var saved = localStorage.getItem('vibe.console.autoscroll');
    autoScrollOn = (saved === null) ? true : (saved === '1');   // default ON
  } catch (e) {}
  var autoScrollCb = document.getElementById('autoscroll-toggle');
  if (autoScrollCb) {
    autoScrollCb.checked = autoScrollOn;
    autoScrollCb.addEventListener('change', function () {
      autoScrollOn = autoScrollCb.checked;
      try { localStorage.setItem('vibe.console.autoscroll', autoScrollOn ? '1' : '0'); } catch (e) {}
      if (autoScrollOn) {
        // 켜는 순간 즉시 최하단으로 jump (사용자 의도 명확).
        logEl.scrollTop = logEl.scrollHeight;
      }
    });
  }
  // append() 등이 사용. autoScrollOn 이면 무조건 stick, 아니면 사용자 위치 따라.
  function shouldStick() { return autoScrollOn || isAtBottom(); }
  function setJumpVisible(v) {
    if (!jumpBtn) return;
    if (v) jumpBtn.classList.add('visible');
    else jumpBtn.classList.remove('visible');
  }
  function setUnread(n) {
    unreadCount = n;
    if (!jumpBadge) return;
    if (n > 0) {
      jumpBadge.textContent = n > 99 ? '99+' : String(n);
      jumpBadge.style.display = 'inline-block';
    } else {
      jumpBadge.style.display = 'none';
    }
  }
  function scrollToBottom() {
    logEl.scrollTop = logEl.scrollHeight;
    setUnread(0);
    setJumpVisible(false);
  }
  if (jumpBtn) jumpBtn.addEventListener('click', scrollToBottom);

  // v1.93.1 — 콘솔 초기 진입 시 스크롤 위치 결정 + 마지막 위치 영속.
  //  문제: 기존엔 초기 히스토리 렌더 끝에서 scrollTop=scrollHeight 를 "한 번"만 호출 →
  //   부모 ProjectTabs 의 rail padding 주입 / hljs / iframe 레이아웃 등 비동기 reflow 가
  //   그 뒤에 일어나면 최하단이 어긋나고, 이후 append/reflow 로 화면이 위에서 아래로
  //   "주르륵" 내려가 보였다.
  //  해결:
  //   - 자동스크롤 ON: 즉시 최하단으로 고정하되, 비동기 reflow 구간(rAF + 짧은 timeout
  //     몇 회) 동안 instant(애니메이션 없음)로 재고정 → 첫 페인트부터 최하단, 시각적
  //     이동 없음. 사용자가 그 사이 직접 스크롤(wheel/touch)하면 즉시 중단.
  //   - 자동스크롤 OFF: 직전에 보던 위치(localStorage, 프로젝트별)를 그대로 복원. 없으면 최하단.
  var SCROLL_KEY = 'vibe.console.scroll.' + projectId;
  function pinBottomNow() { logEl.scrollTop = logEl.scrollHeight; }
  function applyInitialView() {
    if (autoScrollOn) {
      pinBottomNow();
      var cancelled = false;
      function stop() { cancelled = true; }
      logEl.addEventListener('wheel', stop, { passive: true, once: true });
      logEl.addEventListener('touchstart', stop, { passive: true, once: true });
      requestAnimationFrame(function () { if (!cancelled) pinBottomNow(); });
      [0, 50, 150, 350].forEach(function (d) {
        setTimeout(function () { if (!cancelled) pinBottomNow(); }, d);
      });
    } else {
      var saved = null;
      try { saved = localStorage.getItem(SCROLL_KEY); } catch (e) {}
      var v = (saved === null || saved === '') ? NaN : parseInt(saved, 10);
      if (!isNaN(v)) {
        var max = logEl.scrollHeight - logEl.clientHeight;
        logEl.scrollTop = Math.max(0, Math.min(v, max));
      } else {
        pinBottomNow();
      }
    }
  }

  var scrollSaveTimer = null;
  logEl.addEventListener('scroll', function(){
    if (isAtBottom()) {
      setUnread(0);
      setJumpVisible(false);
    } else if (!jumpBtn || !jumpBtn.classList.contains('visible')) {
      setJumpVisible(true);
    }
    // v1.93.1 — 마지막으로 보던 위치 저장(자동스크롤 OFF 복원용). 디바운스 250ms.
    if (scrollSaveTimer) clearTimeout(scrollSaveTimer);
    scrollSaveTimer = setTimeout(function () {
      try { localStorage.setItem(SCROLL_KEY, String(Math.round(logEl.scrollTop))); } catch (e) {}
    }, 250);
  });

  // v1.99.1 — 탭 전환으로 이 콘솔이 다시 보일 때(display:none→block) 최하단 재고정.
  //  applyInitialView 는 최초 1회뿐이라, 다른 탭(빌드/파일 등) 갔다 돌아오면 숨겨진 동안
  //  append 된 메시지로(display:none 이라 scrollHeight 기반 stick 무효) 스크롤이 어긋난 채
  //  보였다. 부모 ProjectTabs 가 activate 시 보내는 postMessage 를 받아, 자동스크롤 ON 이면
  //  reflow 구간 instant 재고정(애니메이션 없음). OFF 면 사용자가 보던 위치 그대로 둔다.
  window.addEventListener('message', function (ev) {
    if (ev.origin !== location.origin) return;
    var d = ev.data;
    if (!d || d.type !== 'pt:tab-visible' || !autoScrollOn) return;
    pinBottomNow();
    requestAnimationFrame(pinBottomNow);
    [0, 50, 150].forEach(function (dl) { setTimeout(pinBottomNow, dl); });
  });

  // v1.7.7 — 시각 포맷 HH:mm:ss. ISO string 받으면 parse, 없으면 now.
  function fmtTime(input) {
    var d;
    if (input instanceof Date) { d = input; }
    else if (typeof input === 'string' && input) {
      d = new Date(input);
      if (isNaN(d.getTime())) d = new Date();
    } else { d = new Date(); }
    var pad = function(n){ return n < 10 ? '0' + n : '' + n; };
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  // v1.7.7 — Lucide "copy" inline SVG. stroke currentColor → 테마 적응.
  var COPY_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15 H4 a2 2 0 0 1-2-2 V4 a2 2 0 0 1 2-2 h9 a2 2 0 0 1 2 2 v1"/></svg>';

  var MD_CLAMP_PX = 180;  // v1.85.0 — assistant 메시지 접기 임계 높이.
  function append(cls, label, body, cat, opts) {
    cat = cat || 'ws';
    opts = opts || {};
    // v1.20.0 — autoScrollOn 이면 위치 무관 stick. 아니면 기존 isAtBottom 만.
    var atBottom = shouldStick();
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.dataset.filterCat = cat;
    if (!isVisible(cat)) row.style.display = 'none';
    var timeStr = fmtTime(opts.ts);
    // v1.85.0 — assistant 메시지는 마크다운 렌더(escape 우선 → XSS 안전). 그 외(tool/sys/
    // err/user)는 raw escape 유지.
    var isAsst = (cls === 'assistant');
    // v1.90.12 — tool 결과(파일 내용/명령 출력)는 코드블록(.md-pre)으로 감싸 monospace 박스
    //   + 가로스크롤 + syntax highlight(아래 hljs)로 가독성↑. 줄번호(cat -n)도 정렬 유지.
    var isToolOut = (cls === 'tool-out' || cls === 'tool-err');
    var bodyInner;
    if (isAsst && window.VibeConsole && window.VibeConsole.renderMarkdown)
      bodyInner = '<div class="log-body md">' + window.VibeConsole.renderMarkdown(body) + '</div>';
    else if (isToolOut)
      bodyInner = '<div class="log-body"><pre class="md-pre"><code>' + escHtml(body) + '</code></pre></div>';
    else
      bodyInner = '<div class="log-body">' + escHtml(body) + '</div>';
    // v1.7.9 — meta (시각 + 복사 버튼) 를 응답 카드 내부 하단으로 이동.
    //          .log-content wrapper 안에 .log-body 위 + .log-meta 아래.
    row.innerHTML =
      '<span class="log-label">' + escHtml(label) + '</span>' +
      '<div class="log-content">' +
        bodyInner +
        '<div class="log-meta">' +
          '<span class="log-time" title="' + escHtml(timeStr) + '">' + escHtml(timeStr) + '</span>' +
          '<button type="button" class="log-copy" title="Copy" aria-label="Copy">' + COPY_SVG + '</button>' +
        '</div>' +
      '</div>';
    var btn = row.querySelector('.log-copy');
    if (btn) {
      btn.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        // v1.90.9 — 복사는 항상 원문(clip 되기 전 전체). opts.raw 가 있으면 그것을 우선.
        var txt = (opts && opts.raw != null) ? String(opts.raw) : (body == null ? '' : String(body));
        var doneOk = function() {
          btn.classList.add('copied');
          setTimeout(function(){ btn.classList.remove('copied'); }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(txt).then(doneOk).catch(function(){
            // fallback: select + execCommand.
            try {
              var ta = document.createElement('textarea');
              ta.value = txt; ta.style.position = 'fixed'; ta.style.opacity = '0';
              document.body.appendChild(ta); ta.select();
              document.execCommand('copy'); document.body.removeChild(ta);
              doneOk();
            } catch (err) {}
          });
        }
      });
    }
    logEl.appendChild(row);
    if ((isAsst || isToolOut) && window.hljs) {
      // v1.85.0 — assistant 마크다운 코드블록 + v1.90.12 tool 결과 코드블록 syntax highlight.
      // tool 결과는 언어 class 가 없어 highlightElement 의 auto-detect 에 맡긴다.
      var codeEls = row.querySelectorAll('pre.md-pre > code');
      for (var ci = 0; ci < codeEls.length; ci++) {
        try { window.hljs.highlightElement(codeEls[ci]); } catch (e) {}
      }
    }
    // v1.90.5 — 긴 메시지는 접어서 표시(탭하면 전문 펼침). assistant 뿐 아니라 tool/결과/
    // system/error/user 등 모든 메시지에 동일 임계(MD_CLAMP_PX)로 적용 — 일관성.
    var contentEl = row.querySelector('.log-content');
    var bodyEl = row.querySelector('.log-body');
    if (contentEl && bodyEl && bodyEl.scrollHeight > MD_CLAMP_PX) {
      contentEl.dataset.clampable = '1';
      contentEl.classList.add('clamped');
    }
    if (atBottom && row.style.display !== 'none') {
      logEl.scrollTop = logEl.scrollHeight;
    } else if (row.style.display !== 'none') {
      // 사용자가 위로 스크롤 중 — unread 카운트 + jump 버튼 표시.
      setUnread(unreadCount + 1);
      setJumpVisible(true);
    }
  }

  // v1.70.1 — 내가 보낸 프롬프트 카드는 기본 2줄 클램프. 카드 클릭 시 펼침/접힘.
  //           텍스트 선택(drag) 중이거나 복사 버튼 클릭 시엔 토글 안 함.
  logEl.addEventListener('click', function(e) {
    if (e.target.closest('.log-copy')) return;
    if (e.target.closest('a')) return;  // 마크다운 링크 클릭은 토글 안 함
    var sel = window.getSelection && window.getSelection();
    if (sel && !sel.isCollapsed) return;  // 드래그 선택 중엔 무시
    var card = e.target.closest('.log-line.user');
    if (card) { card.classList.toggle('expanded'); return; }
    // v1.90.8 — 긴 메시지 카드 탭 → 전문 펼침/접힘. v1.90.5 에서 clamp 를 모든 메시지로
    // 확장했으나 이 토글 핸들러만 .assistant 한정이 남아, tool 결과 등은 접히기만 하고
    // 펼쳐지지 않았다. 셀렉터를 모든 .log-content 로 일치시킨다.
    var content = e.target.closest('.log-content');
    if (content && content.dataset.clampable === '1') {
      content.classList.toggle('clamped');
    }
  });

  // 필터 체크박스 wiring — 페이지 로드 직후 1회.
  (function initFilter() {
    var cbs = document.querySelectorAll('.filter-cb');
    for (var i = 0; i < cbs.length; i++) {
      var cb = cbs[i];
      var cat = cb.dataset.cat;
      if (OPTIONAL_CATS.indexOf(cat) >= 0 && filterState[cat] === false) {
        cb.checked = false;
      }
      cb.addEventListener('change', function(e) {
        var c = e.target.dataset.cat;
        if (MANDATORY_CATS.indexOf(c) >= 0) return;  // disabled, but defensive.
        if (e.target.checked) {
          delete filterState[c];  // default=true → 키 비우면 'true' 와 같음.
        } else {
          filterState[c] = false;
        }
        persistFilter();
        applyFilterToAll();
      });
    }
    var reset = document.getElementById('filter-reset');
    if (reset) {
      reset.addEventListener('click', function() {
        filterState = {};
        persistFilter();
        for (var j = 0; j < cbs.length; j++) cbs[j].checked = true;
        applyFilterToAll();
      });
    }
    // v1.91.4 — 필터 버튼 → 다이얼로그 토글.
    var fModal = document.getElementById('filter-modal');
    var fOpen = document.getElementById('filter-open');
    function closeFilter() { if (fModal) fModal.hidden = true; }
    if (fOpen && fModal) fOpen.addEventListener('click', function() { fModal.hidden = false; });
    var fClose = document.getElementById('filter-close');
    var fDone = document.getElementById('filter-done');
    if (fClose) fClose.addEventListener('click', closeFilter);
    if (fDone) fDone.addEventListener('click', closeFilter);
    if (fModal) fModal.addEventListener('click', function(e) { if (e.target === fModal) closeFilter(); });
    document.addEventListener('keydown', function(e) { if (e.key === 'Escape' && fModal && !fModal.hidden) closeFilter(); });
    updateFilterSummary();
  })();

  // Claude 응답에 'Not logged in' 같은 인증 실패 패턴이 보이면 즉시 폼을 disable 하고
  // 빨간 배너를 띄운다. 진단 (EnvDiagnostics) 이 false positive 였을 때의 라이브 fallback.
  // v1.90.4 — CLI 특유 문구만 매칭. 이전엔 'unauthorized'/'authentication required'/
  // 'invalid api key' 같은 일반 단어까지 포함해, 결제/인증을 다루는 프로젝트에서 Claude 가
  // 정상 출력한 코드·설명이 "CLI 로그인 필요" 로 오탐됐다(입력창까지 잠김).
  var AUTH_FAIL_RE = /(not logged in|please run \/login|run ['"`]?claude login['"`]?|invalid api key[^.\n]{0,40}\/login)/i;
  function detectAuthFailure(text) {
    if (!text) return false;
    if (AUTH_FAIL_RE.test(String(text))) { showAuthBanner(); return true; }
    return false;
  }
  function showAuthBanner() {
    var existing = document.getElementById('live-auth-banner');
    if (existing) return;
    var banner = document.createElement('div');
    banner.id = 'live-auth-banner';
    banner.className = 'error';
    banner.style.cssText = 'margin-bottom:16px;padding:16px';
    banner.innerHTML = '<strong style="font-size:14px">' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.live.authNeeded"))} + '</strong>' +
      '<p style="margin:6px 0">' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.live.authDesc"))} + '</p>' +
      '<pre class="diff-block" style="margin:8px 0">docker exec -it --user vibe vibe-coder-server claude login</pre>';
    var header = document.querySelector('header');
    if (header && header.parentNode) header.parentNode.insertBefore(banner, header.nextSibling);
    var input = document.getElementById('prompt-input');
    var btn = document.getElementById('send-btn');
    if (input) { input.disabled = true; input.placeholder = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.live.placeholder"))}; }
    if (btn) btn.disabled = true;
  }

  // v1.3.0 — Todo 패널 store.
  // Claude 가 호출한 TaskCreate / TaskUpdate / TodoWrite 를 누적해
  // 입력창 위 패널에 진행 상황을 렌더.
  // 키 우선순위: taskId > subject (재호출 시 동일 항목으로 갱신되도록).
  // localStorage 영속은 의도적으로 생략 — 세션 단위 휘발 (서버 재시작 / 새 세션 시 초기화).
  // 콘솔 카드 자체는 별도로 cat='todo' 로 분류돼 필터에서 토글 가능.
  var TODO_PANEL_OPEN_KEY = 'vibe-todo-panel-open-' + projectId;
  var todoStore = [];

  function todoStatusIcon(status) {
    if (status === 'completed' || status === 'done') return { icon: '✓', color: '#5fb95f' };
    if (status === 'in_progress' || status === 'inprogress' || status === 'active') return { icon: '▸', color: '#f3c14a' };
    if (status === 'cancelled' || status === 'canceled' || status === 'skipped') return { icon: '✕', color: '#888' };
    return { icon: '○', color: '#888' };
  }

  function todoUpsert(key, fields) {
    for (var i = 0; i < todoStore.length; i++) {
      if (todoStore[i].key === key) {
        if (fields.subject) todoStore[i].subject = fields.subject;
        if (fields.status) todoStore[i].status = fields.status;
        todoStore[i].ts = Date.now();
        return;
      }
    }
    todoStore.push({
      key: key,
      subject: fields.subject || '(no subject)',
      status: fields.status || 'pending',
      ts: Date.now()
    });
  }

  function updateTodoStore(name, input) {
    var i = input || {};
    if (name === 'TodoWrite') {
      // 전체 교체. TodoWrite 는 항상 todos 배열로 완전한 현재 상태를 보냄.
      var arr = Array.isArray(i.todos) ? i.todos : [];
      todoStore = arr.map(function(t, idx) {
        return {
          key: (t.id != null ? String(t.id) : 'tw-' + idx + '-' + (t.content || t.subject || '').slice(0, 24)),
          subject: t.content || t.subject || t.description || t.activeForm || '(no subject)',
          status: t.status || 'pending',
          ts: Date.now()
        };
      });
    } else if (name === 'TaskCreate') {
      var ckey = (i.taskId != null ? String(i.taskId) : null) ||
                 (i.subject ? 'sub:' + i.subject : null) ||
                 ('tc-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8));
      todoUpsert(ckey, {
        subject: i.subject || i.description || '(no subject)',
        status: i.status || 'pending'
      });
    } else if (name === 'TaskUpdate') {
      var ukey = (i.taskId != null ? String(i.taskId) : null) ||
                 (i.subject ? 'sub:' + i.subject : null);
      if (!ukey) return;  // 매칭 키가 없으면 무시.
      todoUpsert(ukey, {
        subject: i.subject || null,
        status: i.status || null
      });
    }
    renderTodoPanel();
  }

  function renderTodoPanel() {
    var listEl = document.getElementById('todo-panel-list');
    var emptyEl = document.getElementById('todo-panel-empty');
    var summaryEl = document.getElementById('todo-panel-summary');
    if (!listEl || !emptyEl || !summaryEl) return;
    if (todoStore.length === 0) {
      listEl.style.display = 'none';
      emptyEl.style.display = '';
      summaryEl.textContent = '';
      return;
    }
    emptyEl.style.display = 'none';
    listEl.style.display = '';
    var done = 0, active = 0;
    var html = '';
    for (var j = 0; j < todoStore.length; j++) {
      var t = todoStore[j];
      var si = todoStatusIcon(t.status);
      if (t.status === 'completed' || t.status === 'done') done++;
      else if (t.status === 'in_progress' || t.status === 'inprogress' || t.status === 'active') active++;
      var strike = (t.status === 'completed' || t.status === 'done') ? 'text-decoration:line-through;opacity:0.6;' : '';
      html += '<li style="padding:3px 0;display:flex;gap:8px;align-items:flex-start;font-size:12px">' +
              '<span style="color:' + si.color + ';font-family:monospace;min-width:14px;text-align:center">' + si.icon + '</span>' +
              '<span style="' + strike + 'flex:1;word-break:break-word">' + escHtml(t.subject) + '</span>' +
              (t.status && t.status !== 'pending' && t.status !== 'completed' && t.status !== 'done'
                ? '<span class="dim" style="font-size:10px;text-transform:uppercase;letter-spacing:0.5px">' + escHtml(t.status) + '</span>'
                : '') +
              '</li>';
    }
    listEl.innerHTML = html;
    var parts = [done + '/' + todoStore.length + ' ' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.todo.summary.done"))}];
    if (active > 0) parts.push(active + ' ' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.todo.summary.active"))});
    summaryEl.textContent = '— ' + parts.join(' · ');
  }

  // 패널 open/close 상태 영속.
  (function initTodoPanel() {
    var panel = document.getElementById('todo-panel');
    if (!panel) return;
    var saved;
    try { saved = localStorage.getItem(TODO_PANEL_OPEN_KEY); } catch (e) { saved = null; }
    // 기본값: 열림 (저장된 값 없으면 open).
    panel.open = (saved === null) ? true : (saved === '1');
    panel.addEventListener('toggle', function() {
      try { localStorage.setItem(TODO_PANEL_OPEN_KEY, panel.open ? '1' : '0'); } catch (e) {}
    });
  })();

  // v0.13.0 → v1.70.0 — tool_use 친화 렌더링은 공용 /static/console-render.js 로 추출.
  // clip / renderToolUse 는 그 모듈을 위임 사용 (메인 콘솔 · /chat · sub-agent 공통).
  function clip(s, n) { return window.VibeConsole.clip(s, n); }
  function renderToolUse(name, input) { return window.VibeConsole.renderToolUse(name, input); }

  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') {
      append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12) + (f.model ? ' · ' + f.model : ''), 'session');
    } else if (t === 'console_assistant') {
      // v1.90.4 — Claude 응답 본문은 인증 상태와 무관(코드/설명에 'login' 등이 정상 등장).
      // CLI 로그인 실패 감지는 서버 진단성 error/system 프레임에서만 수행한다.
      append('assistant', 'assistant', f.text || '', 'assistant');
    } else if (t === 'console_tool_use') {
      var rendered = renderToolUse(f.toolName, f.input);
      // v1.3.0 — task 계열 tool 은 별도 'todo' 카테고리로 분류해 콘솔/패널 양쪽에 반영.
      var isTodoTool = (f.toolName === 'TaskCreate' || f.toolName === 'TaskUpdate' || f.toolName === 'TodoWrite');
      append('tool', rendered.label, rendered.body, isTodoTool ? 'todo' : 'tool_use');
      if (isTodoTool) updateTodoStore(f.toolName, f.input);
    } else if (t === 'console_tool_result') {
      // v1.70.0 — content 배열([{type:text,text}]) 을 평문으로 추출 (raw JSON 노출 제거).
      var out = window.VibeConsole.extractToolResult(f.output);
      var resultLabel = f.isError ? 'tool-err' : '✓ result';
      // v1.90.9 — 표시는 clip(8000)이되 복사용 원문(raw)은 전체 보존.
      append(f.isError ? 'tool-err' : 'tool-out', resultLabel, clip(out, 8000), 'tool_result', { raw: out });
      // v1.90.4 — tool 결과(파일 내용/명령 출력)에도 'unauthorized' 등이 정상 등장하므로 감지 제외.
    } else if (t === 'console_error') {
      append('err', 'error', (f.code || '') + ': ' + (f.message || ''), 'error');
      detectAuthFailure(f.message);
    } else if (t === 'console_done') {
      append('sys', 'done', f.reason || 'end_turn', 'done');
      setInFlight(false);
    } else if (t === 'console_system') {
      // 'turn_cancelled' / 'process_crashed' 등 종료 신호 — stop 버튼 숨김
      if (f.code === 'turn_cancelled' || f.code === 'process_crashed' || f.code === 'idle_terminated') {
        setInFlight(false);
      }
      append('sys', f.code || 'system', f.message || '', 'system');
      detectAuthFailure(f.message);
    } else if (t === 'console_replay_begin') {
      append('sys', 'replay', 'history begin (' + f.fromSeq + ' → ' + f.toSeq + ')', 'replay');
    } else if (t === 'console_busy_state') {
      // v0.98.0 — 서버 측 busy 전이 알림. 다중 탭/디바이스 + 다른 클라이언트의 prompt
      // 발송 시점 sync. 로컬 inFlight 와 항상 같은 값으로 수렴.
      // v1.83.0 — state="stopped"(rate-limit 재시도 소진/취소/크래시) 면 "중단됨" 뱃지.
      // v1.100.0 — waiting(백그라운드 대기, 노랑) / error(API·turn 에러, 빨강) 도 분기.
      setInFlight(!!f.busy);
      if (f.state === 'stopped') showStopped();
      else if (f.state === 'waiting') showWaiting();
      else if (f.state === 'error') showError();
    } else if (t === 'console_background_task') {
      // v1.84.0 — 백그라운드 작업(Bash run_in_background) 진행 카드.
      handleBgTask(f);
    } else if (t === 'console_replay_end') {
      append('sys', 'replay', 'history end — live frames follow', 'replay');
    } else if (t === 'automation_progress') {
      // v1.59.0 — 프롬프트 자동화 진행/종료 프레임 → 패널 뱃지 갱신.
      if (window.__vibeAutoRender) window.__vibeAutoRender(f);
    } else if (t === 'console_unknown') {
      // v1.70.0 — 이전엔 미처리(드롭)되거나 raw 로 보이던 이벤트(thinking / system task /
      // rate_limit 등)를 친화적으로 렌더. null 이면 노이즈로 판단해 숨김.
      var u = window.VibeConsole.renderUnknown(f.raw);
      if (u) append(u.cls, u.label, u.body, u.cat);
    }
  }

  // v1.7.3 — 서버 재시작 후에도 기존 conversation 가시. inline JSON (DB 의 ConversationTurn)
  // 을 parse 후 console-log 의 첫 메시지로 prepend. WS connect 보다 먼저 실행되어 ring
  // buffer 의 replay (있다면) 가 그 위에 누적. 서버 재시작 직후 ring 빈 상태에선 history
  // 만 보이고, ring 에 잔존 frame 있으면 약간 중복 — 정보 손실보단 노이즈 우선.
  (function replayInitialHistory() {
    var el = document.getElementById('initial-history');
    if (!el) return;
    var raw = el.textContent || '';
    if (!raw.trim() || raw.trim() === '[]') return;
    var arr;
    try { arr = JSON.parse(raw); } catch (e) { return; }
    if (!Array.isArray(arr) || arr.length === 0) return;
    append('sys', 'history', '— ' + arr.length + ' previous turn(s) restored —', 'replay');
    // v1.70.0 — 저장된 content 도 친화적으로 렌더 (이전엔 tool_use input / tool_result 배열 /
    // system {kind:…} / unknown 이 raw JSON 으로 노출됐음). 라이브 흐름과 동일 변환 사용.
    function tryParse(s) { try { return JSON.parse(s); } catch (e) { return null; } }
    for (var i = 0; i < arr.length; i++) {
      var r = arr[i] || {};
      var role = r.role || '';
      var text = r.text || '';
      var opts = r.ts ? { ts: r.ts } : null;
      if (role === 'user') {
        append('user', 'user', text, 'assistant', opts);
      } else if (role === 'assistant') {
        append('assistant', 'assistant', text, 'assistant', opts);
      } else if (role === 'tool_use') {
        var inp = tryParse(text);
        var ru = renderToolUse(r.tool || 'tool', inp != null ? inp : text);
        var isTodoTool = (r.tool === 'TaskCreate' || r.tool === 'TaskUpdate' || r.tool === 'TodoWrite');
        append('tool', ru.label, ru.body, isTodoTool ? 'todo' : 'tool_use', opts);
      } else if (role === 'tool_result' || role === 'tool_result_error') {
        var parsed = tryParse(text);
        // v1.90.16 — parsed 가 null(clip 된 불완전 JSON 등)이어도 extractToolResult 를 거쳐
        // unescape(\t/\n)가 적용되게 한다. 이전엔 null 이면 raw text 를 직접 써서 escape 가 남았다.
        var out = window.VibeConsole.extractToolResult(parsed != null ? parsed : text);
        var isErr = (role === 'tool_result_error');
        // v1.90.9 — 복사용 원문 보존(clip 8000 표시).
        opts.raw = out;
        append(isErr ? 'tool-err' : 'tool-out', isErr ? 'tool-err' : '✓ result', clip(out, 8000), 'tool_result', opts);
      } else if (role === 'system') {
        var sys = tryParse(text) || {};
        if (sys.kind === 'session_started') {
          append('sys', 'session', 'started' + (sys.model ? ' · ' + sys.model : ''), 'session', opts);
        } else if (sys.kind === 'done') {
          append('sys', 'done', sys.reason || 'end_turn', 'done', opts);
        } else if (sys.kind === 'system') {
          append('sys', sys.code || 'system', sys.message || '', 'system', opts);
        } else {
          append('sys', 'system', clip(text, 1000), 'system', opts);
        }
      } else if (role === 'error') {
        var er = tryParse(text) || {};
        append('err', 'error', (er.code || '') + ': ' + (er.message || text), 'error', opts);
      } else if (role === 'unknown') {
        var u = window.VibeConsole.renderUnknown(text);
        if (u) append(u.cls, u.label, u.body, u.cat, opts);
      }
    }
    append('sys', 'history', '— end of history, live frames follow —', 'replay');
    // v1.93.1 — 초기 진입 뷰 결정: ON=최하단(비동기 reflow 구간까지 instant 재고정),
    // OFF=직전에 보던 위치 복원. (이전엔 여기서 scrollTop=scrollHeight 1회만 호출해
    // 비동기 reflow 후 위치가 어긋나 "주르륵" 내려가 보였다.)
    applyInitialView();
  })();

  var ws = null;
  var wsAuthed = false;
  // v1.70.4 — 재연결 UX. 탭을 백그라운드로 보내면 브라우저가 소켓을 얼려 서버
  // timeout(45s)으로 1006 close 가 발생한다(정상). 매번 "closed (code 1006)" 를
  // 띄우는 대신 한 번만 부드럽게 안내하고, 탭 복귀/네트워크 복구 시 즉시 재연결한다.
  var WS_DISCONNECTED = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.ws.disconnected"))};
  var WS_RECONNECTED = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.ws.reconnected"))};
  var reconnectTimer = null;
  var everConnected = false;     // 최초 연결 후 true → 이후 onopen 은 "재연결됨"
  var wsClosedNotified = false;  // 끊김 메시지 1회만

  function scheduleReconnect(delay) {
    if (reconnectTimer) return;  // 이미 예약됨
    reconnectTimer = setTimeout(function() { reconnectTimer = null; connect(); }, delay);
  }

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/console/logs');

    ws.onopen = function() {
      // 인증은 WS handshake 의 cookie 헤더로 처리 (vibe_session 은 httpOnly).
      if (!everConnected) append('sys', 'ws', 'connected', 'ws');
      else if (wsClosedNotified) append('sys', 'ws', WS_RECONNECTED, 'ws');
      everConnected = true;
      wsClosedNotified = false;
    };

    ws.onmessage = function(ev) {
      try {
        var f = JSON.parse(ev.data);
        // 서버는 인증 성공 시 별도 응답 없이 바로 frame을 보낸다.
        // 실패 시엔 type=error + CloseReason 으로 응답 후 close.
        if (f.type === 'error') { append('err', 'ws', (f.code || '') + ': ' + (f.message || ''), 'error'); return; }
        renderFrame(f);
      } catch (e) {
        append('err', 'parse', String(e), 'error');
      }
    };

    ws.onclose = function(ev) {
      if (ev.code === 1000) return;  // 정상 종료(페이지 이탈 등)는 알림/재연결 불필요
      if (!wsClosedNotified) { append('sys', 'ws', WS_DISCONNECTED, 'ws'); wsClosedNotified = true; }
      // 포그라운드면 빠르게(2s), 백그라운드면 복귀 이벤트가 즉시 깨운다.
      scheduleReconnect(2000);
    };

    ws.onerror = function() { /* onclose 가 뒤따르므로 별도 메시지 생략 */ };
  }

  // 탭 복귀 / 네트워크 복구 / 포커스 시 5초 기다리지 않고 즉시 재연결.
  function reconnectNow() {
    if (document.hidden) return;
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    connect();
  }
  document.addEventListener('visibilitychange', function() { if (!document.hidden) reconnectNow(); });
  window.addEventListener('online', reconnectNow);
  window.addEventListener('focus', reconnectNow);

  connect();

  // v0.13.0 — 진행 중 turn cancel 버튼
  // v0.98.0 — busy badge 추가 — 응답중/대기중 시각화.
  // v0.99.0 — pendingPrompts 큐. busy 중에도 submit 허용, console_done 후 자동 발사.
  var stopBtn = document.getElementById('stop-btn');
  var busyBadge = document.getElementById('busy-badge');
  var BUSY_RESPONDING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.responding"))};
  var BUSY_IDLE = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.idle"))};
  var BUSY_WAITING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.waiting"))};
  var BUSY_STOPPED = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.stopped"))};
  var BUSY_ERROR = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.error"))};
  var BUSY_QUEUED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.responding.queued", "___N___"))};
  var QUEUE_ADDED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.added", "___N___", "___PREVIEW___"))};
  var QUEUE_DRAINING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.draining"))};
  var QUEUE_CLEARED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.cleared", "___N___"))};
  var inFlight = false;
  var pendingPrompts = [];  // 큐: busy 중 submit 된 prompt 들.
  // v1.103.0 — 통합 탭 헤더의 #console-busy-badge 로 turn 상태 미러링(iframe → 부모 shell).
  // 콘솔 하단 busy-badge 를 헤더 콤보박스 좌측으로 "이동" 한 효과. 단독 콘솔 페이지
  // (부모=자기자신, project-tabs 없음)면 수신자가 없어 무해.
  function notifyParentBusy() {
    if (!busyBadge) return;
    try {
      window.parent.postMessage(
        { type: 'console:busy', state: busyBadge.dataset.state, text: busyBadge.textContent },
        location.origin,
      );
    } catch (e) {}
  }
  function updateBusyBadge() {
    if (!busyBadge) return;
    if (inFlight) {
      busyBadge.dataset.state = 'responding';
      busyBadge.textContent = pendingPrompts.length > 0
        ? BUSY_QUEUED_TPL.replace('___N___', pendingPrompts.length)
        : BUSY_RESPONDING;
    } else {
      busyBadge.dataset.state = 'idle';
      busyBadge.textContent = BUSY_IDLE;
    }
    notifyParentBusy();
  }
  // v1.83.0 — rate-limit 재시도 소진/취소/크래시로 turn 이 비정상 종료된 상태.
  // setInFlight(false) 직후 호출돼 idle 뱃지를 "중단됨"(빨강) 으로 덮어쓴다.
  // 다음 prompt 전송 시 setInFlight(true) 가 다시 responding 으로 자연 복귀.
  function showStopped() {
    if (!busyBadge) return;
    busyBadge.dataset.state = 'stopped';
    busyBadge.textContent = BUSY_STOPPED;
    notifyParentBusy();
  }
  // v1.100.0 — 백그라운드 작업 진행 중 turn 재개 대기(노랑). busy 는 true 유지 →
  // setInFlight(true) 가 'responding' 으로 세팅한 직후 호출돼 'waiting' 으로 덮어쓴다.
  function showWaiting() {
    if (!busyBadge) return;
    busyBadge.dataset.state = 'waiting';
    busyBadge.textContent = BUSY_WAITING;
    notifyParentBusy();
  }
  // v1.100.0 — API/turn 에러 종료(빨강). cancel/crash 의 '중단됨'(보라) 과 색 구분.
  function showError() {
    if (!busyBadge) return;
    busyBadge.dataset.state = 'error';
    busyBadge.textContent = BUSY_ERROR;
    notifyParentBusy();
  }
  // v1.84.0 — 백그라운드 작업 카드 패널. task_started → 카드 추가(실행 중 spinner),
  // task_updated/notification 의 status 가 종료(completed/failed)면 ✓/✗ 후 6초 뒤 제거.
  var bgTasks = {};  // taskId -> { el, status }
  var BG_TYPE_LABELS = { local_bash: 'shell' };
  function bgIcon(status) { return status === 'completed' ? '✓' : (status === 'failed' ? '✗' : '●'); }
  function refreshBgPanel() {
    var p = document.getElementById('bg-tasks');
    if (p) p.hidden = Object.keys(bgTasks).length === 0;
  }
  function bgMeta(f) {
    var parts = [BG_TYPE_LABELS[f.taskType] || f.taskType || 'task', String(f.taskId).slice(0, 8)];
    if (f.lastTool) parts.push(f.lastTool);
    if (f.toolUses) parts.push(f.toolUses + ' tools');
    return parts.join(' · ');
  }
  function ensureBgCard(f) {
    var rec = bgTasks[f.taskId];
    if (rec) return rec;
    var list = document.getElementById('bg-tasks-list');
    if (!list) return null;
    var card = document.createElement('div');
    card.className = 'bg-task-card';
    card.dataset.status = 'running';
    card.innerHTML = '<span class="bg-task-icon">●</span><span class="bg-task-desc"></span><span class="bg-task-meta"></span>';
    list.appendChild(card);
    rec = bgTasks[f.taskId] = { el: card, status: 'running' };
    refreshBgPanel();
    return rec;
  }
  function handleBgTask(f) {
    if (!f || !f.taskId) return;
    if (f.kind === 'started' || f.kind === 'progress') {
      // task_progress 가 task_started 없이 첫 도착(서브에이전트)해도 카드 생성.
      var rec = ensureBgCard(f);
      if (!rec) return;
      rec.status = 'running';
      rec.el.dataset.status = 'running';
      rec.el.querySelector('.bg-task-icon').textContent = '●';
      if (f.description) rec.el.querySelector('.bg-task-desc').textContent = f.description;
      rec.el.querySelector('.bg-task-meta').textContent = bgMeta(f);
    } else {  // 'updated' | 'notification'
      var rec2 = bgTasks[f.taskId];
      if (!rec2) return;
      var st = f.status || (f.kind === 'notification' ? 'completed' : 'running');
      var active = (st === 'running' || st === 'in_progress' || st === 'started' || st === 'pending');
      rec2.status = active ? 'running'
        : ((st === 'failed' || st === 'error' || st === 'killed') ? 'failed' : 'completed');
      rec2.el.dataset.status = rec2.status;
      rec2.el.querySelector('.bg-task-icon').textContent = bgIcon(rec2.status);
      if (!active) {
        var tid = f.taskId;
        setTimeout(function() {
          var r = bgTasks[tid];
          if (r && r.el && r.el.parentNode) r.el.parentNode.removeChild(r.el);
          delete bgTasks[tid];
          refreshBgPanel();
        }, 6000);
      }
    }
  }
  // v1.7.12 — 응답중 spinner element. console-log 하단에 표시.
  var spinnerEl = document.getElementById('console-spinner');
  function setInFlight(on) {
    var wasOn = inFlight;
    inFlight = on;
    if (stopBtn) stopBtn.style.display = on ? 'inline-block' : 'none';
    if (spinnerEl) spinnerEl.hidden = !on;
    updateBusyBadge();
    // busy → idle 전이 시 큐에서 하나 꺼내 자동 발사. 작은 delay 로 UI/server 안정.
    if (wasOn && !on && pendingPrompts.length > 0) {
      var next = pendingPrompts.shift();
      append('sys', 'queue', QUEUE_DRAINING, 'system');
      updateBusyBadge();  // 카운트 즉시 갱신
      setTimeout(function() { sendPrompt(next); }, 150);
    }
  }
  async function cancelTurn() {
    if (!inFlight) return;
    try {
      await fetch('/api/projects/' + projectId + '/claude/console/cancel', {
        method: 'POST', credentials: 'same-origin',
      });
      append('sys', 'cancel', ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.cancel.sent"))}, 'system');
    } catch (e) {
      append('err', 'cancel', String(e), 'error');
    } finally {
      setInFlight(false);
    }
  }
  if (stopBtn) stopBtn.addEventListener('click', cancelTurn);

  async function sendPrompt(text) {
    sendBtn.disabled = true;
    setInFlight(true);
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/prompt', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({text: text}),
      });
      if (!res.ok) {
        var msg = await res.text();
        append('err', 'send', res.status + ' ' + msg, 'error');
        setInFlight(false);
      } else {
        append('user', 'user', text, 'assistant');
        input.value = '';
        // v1.20.0 — prompt 전송 직후엔 토글 모드 무관 항상 최하단으로 jump.
        // 사용자가 자기 prompt + 응답을 바로 봐야 함이 명확.
        scrollToBottom();
        // v1.59.2 — 부모 ProjectTabs 의 우측 오버뷰 프롬프트 히스토리에 즉시 반영
        // (콘솔은 iframe — 부모가 서버 렌더 시점 snapshot 만 갖고 있어 reload 전엔 stale).
        try { window.parent.postMessage({ type: 'vibe:prompt-sent', text: text }, location.origin); } catch (e) {}
      }
    } catch (e) {
      append('err', 'send', String(e), 'error');
      setInFlight(false);
    } finally {
      sendBtn.disabled = false;
      // v1.7.11 — 전송 후 textarea blur. 이전엔 input.focus() 자동 호출 →
      // 모바일 키보드 다시 올라오고 PC 에서 다음 자동 입력 의도 안 했는데 유지되던
      // UX 문제. 사용자가 답변 본 후 명시적으로 클릭해서 다음 prompt 입력.
      input.blur();
    }
  }

  form.addEventListener('submit', function(ev) {
    ev.preventDefault();
    var text = input.value.trim();
    if (!text) return;
    // v0.99.0 — busy 면 큐로 push. console_done 후 setInFlight(false) 가 자동 발사.
    if (inFlight) {
      pendingPrompts.push(text);
      var preview = text.length > 60 ? text.slice(0, 60) + '…' : text;
      append('sys', 'queue',
             QUEUE_ADDED_TPL.replace('___N___', pendingPrompts.length).replace('___PREVIEW___', preview),
             'system');
      input.value = '';
      input.blur();  // v1.7.11 — 큐에 push 후에도 동일하게 포커스 해제.
      updateBusyBadge();
      return;
    }
    sendPrompt(text);
  });

  // v1.58.0 — 입력창 상단 빠른 프롬프트 버블 버튼. 클릭 시 textarea 에 값을 채우고
  // form.requestSubmit() 으로 위 submit 핸들러를 그대로 거친다 → busy 면 큐로,
  // 아니면 즉시 sendPrompt. 전송 후 input 비우기/blur 도 기존 경로가 처리.
  var quickBar = document.getElementById('quick-prompts');
  if (quickBar) {
    quickBar.addEventListener('click', function(ev) {
      var btn = ev.target.closest ? ev.target.closest('.qp-btn') : null;
      if (!btn || btn.disabled || input.disabled) return;
      input.value = btn.getAttribute('data-prompt') || '';
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true }));
    });
  }

  // v1.59.0 — 프롬프트 자동화 패널 (서버 백그라운드 autopilot).
  (function initAutomation() {
    var panel = document.getElementById('auto-panel');
    if (!panel) return;  // chat / blocking 숨김
    var badge = document.getElementById('auto-badge');
    var runningEl = document.getElementById('auto-running');
    var idleEl = document.getElementById('auto-idle');
    var progEl = document.getElementById('auto-progress');
    var startBtn = document.getElementById('auto-start-btn');
    var stopBtn2 = document.getElementById('auto-stop-btn');
    var presetSel = document.getElementById('auto-preset');
    var presetStartBtn = document.getElementById('auto-preset-start');

    // 진행 프레임/상태 → UI. 전역 노출(프레임 핸들러가 호출).
    window.__vibeAutoRender = function(st) {
      if (st && st.active) {
        runningEl.hidden = false;
        idleEl.style.display = 'none';
        progEl.textContent = (st.name ? st.name + ' · ' : '') + (st.sent || 0) + '/' + (st.total || 0);
        badge.textContent = '· ' + (st.sent || 0) + '/' + (st.total || 0);
      } else {
        runningEl.hidden = true;
        idleEl.style.display = '';
        badge.textContent = (st && st.status) ? '· ' + st.status : '';
      }
    };

    function api(method, url, body) {
      return fetch(url, {
        method: method, credentials: 'same-origin',
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
    }
    var base = '/api/projects/' + encodeURIComponent(projectId) + '/claude/automation/';

    // 프리셋 로드 (전역 /api/prompt-automations).
    api('GET', '/api/prompt-automations').then(function(r){ return r.ok ? r.json() : null; }).then(function(d) {
      if (!d || !d.presets || !d.presets.length) return;
      presetSel.innerHTML = '';
      d.presets.forEach(function(p) {
        var o = document.createElement('option'); o.value = p.id; o.textContent = p.name + ' (' + p.mode + ')';
        presetSel.appendChild(o);
      });
      presetSel.style.display = ''; presetStartBtn.style.display = '';
    }).catch(function(){});

    // 현재 상태 1회 조회.
    api('GET', base + 'status').then(function(r){ return r.ok ? r.json() : null; })
      .then(function(st){ if (st) window.__vibeAutoRender(st); }).catch(function(){});

    function doStart(body) {
      api('POST', base + 'start', body).then(function(r) {
        if (!r.ok) { return r.text().then(function(m){ append('err', 'automation', r.status + ' ' + m, 'error'); }); }
        return r.json().then(function(st){ window.__vibeAutoRender(st); panel.open = true; });
      }).catch(function(e){ append('err', 'automation', String(e), 'error'); });
    }
    if (startBtn) startBtn.addEventListener('click', function() {
      var mode = (document.querySelector('input[name=auto-mode]:checked') || {}).value || 'repeat';
      var raw = (document.getElementById('auto-prompts').value || '').trim();
      var prompts = raw.split('\n').map(function(s){ return s.trim(); }).filter(Boolean);
      if (!prompts.length) { document.getElementById('auto-prompts').focus(); return; }
      var count = parseInt(document.getElementById('auto-count').value, 10) || 1;
      doStart({ mode: mode, prompts: prompts, repeatCount: count, loops: 1, stopOnError: false });
    });
    if (presetStartBtn) presetStartBtn.addEventListener('click', function() {
      if (!presetSel.value) return;
      doStart({ presetId: presetSel.value });
    });
    if (stopBtn2) stopBtn2.addEventListener('click', function() {
      if (!confirm(${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.automation.confirmStop"))})) return;
      api('POST', base + 'stop').then(function(r){ return r.ok ? r.json() : null; })
        .then(function(st){ if (st) window.__vibeAutoRender(st); }).catch(function(){});
    });
  })();

  // v0.99.0 — 사용자가 큐 명시적으로 비우고 싶을 때. window 에 노출 — 콘솔에서
  // `window.vibeClearQueue()` 로 호출 가능. UI 버튼은 차후 옵션.
  window.vibeClearQueue = function() {
    if (pendingPrompts.length === 0) return 0;
    var n = pendingPrompts.length;
    pendingPrompts.length = 0;
    append('sys', 'queue', QUEUE_CLEARED_TPL.replace('___N___', n), 'system');
    updateBusyBadge();
    return n;
  };

  input.addEventListener('keydown', function(ev) {
    // v1.69.0 — 전송: Enter · 줄바꿈: Ctrl/Cmd+Enter (또는 Shift+Enter)
    // ev.isComposing: 한글 등 IME 조합 확정 Enter 는 전송하지 않음 (오발송 방지)
    if (ev.key !== 'Enter' || ev.isComposing) return;
    if (ev.ctrlKey || ev.metaKey) {
      // Ctrl/Cmd+Enter → 커서 위치에 줄바꿈 삽입 (textarea 기본 동작 아님)
      ev.preventDefault();
      var s = input.selectionStart, e = input.selectionEnd, nl = String.fromCharCode(10);
      input.value = input.value.substring(0, s) + nl + input.value.substring(e);
      input.selectionStart = input.selectionEnd = s + 1;
      if (typeof input.dispatchEvent === 'function') input.dispatchEvent(new Event('input', { bubbles: true }));
      return;
    }
    if (ev.shiftKey || ev.altKey) return; // Shift/Alt+Enter 는 기본 줄바꿈 유지
    ev.preventDefault();
    form.requestSubmit();
  });

  // v0.13.0 — 프롬프트 템플릿 드롭다운 채우기. JSON API → optgroup by category.
  var picker = document.getElementById('template-picker');
  if (picker) {
    fetch('/api/prompt-templates', { credentials: 'same-origin' })
      .then(function(r) { return r.ok ? r.json() : { templates: [] }; })
      .then(function(d) {
        var byCat = {};
        (d.templates || []).forEach(function(t) {
          if (!byCat[t.category]) byCat[t.category] = [];
          byCat[t.category].push(t);
        });
        var cats = Object.keys(byCat).sort();
        cats.forEach(function(cat) {
          var og = document.createElement('optgroup');
          og.label = cat;
          byCat[cat].sort(function(a, b) { return a.title.localeCompare(b.title); }).forEach(function(t) {
            var opt = document.createElement('option');
            opt.value = t.id;
            opt.textContent = t.title;
            opt.dataset.body = t.body;
            og.appendChild(opt);
          });
          picker.appendChild(og);
        });
      })
      .catch(function() { /* 빈 채로 두기 */ });

    picker.addEventListener('change', function() {
      var opt = picker.options[picker.selectedIndex];
      if (!opt || !opt.value) return;
      var body = opt.dataset.body || '';
      // 기존 입력이 있으면 줄바꿈 후 append, 없으면 그대로 채움.
      if (input.value && input.value.trim().length > 0) {
        input.value = input.value.replace(/\s+$/,'') + '\n\n' + body;
      } else {
        input.value = body;
      }
      input.focus();
      picker.selectedIndex = 0;
    });
  }

  // v0.41.0 — agent dispatch dropdown.
  // GET /api/agents 결과로 등록된 sub-agent 목록을 채운 뒤 선택 시
  // "Use the <agent> sub-agent to " prefix 를 input 에 삽입.
  // Claude Code 의 표준 sub-agent dispatch 메커니즘을 1-click 으로 활용.
  var aPicker = document.getElementById('agent-picker');
  if (aPicker) {
    fetch('/api/agents', { credentials: 'same-origin' })
      .then(function(r) { return r.ok ? r.json() : { agents: [] }; })
      .then(function(d) {
        var arr = (d.agents || []).slice().sort(function(a, b) {
          return a.name.localeCompare(b.name);
        });
        if (arr.length === 0) {
          var none = document.createElement('option');
          none.value = '';
          none.textContent = '(등록된 agent 없음 — /agents)';
          none.disabled = true;
          aPicker.appendChild(none);
          return;
        }
        arr.forEach(function(a) {
          var opt = document.createElement('option');
          opt.value = a.name;
          opt.textContent = '@' + a.name;
          opt.title = (a.preview || '').substring(0, 200);
          aPicker.appendChild(opt);
        });
      })
      .catch(function() { /* 빈 채로 두기 */ });

    aPicker.addEventListener('change', function() {
      var opt = aPicker.options[aPicker.selectedIndex];
      if (!opt || !opt.value) return;
      var prefix = 'Use the ' + opt.value + ' sub-agent to ';
      var input = document.getElementById('prompt-input');
      if (input.value && input.value.trim().length > 0) {
        // 이미 prefix 가 있으면 중복 안 함.
        if (input.value.startsWith('Use the ')) {
          // 기존 agent 이름만 교체.
          input.value = input.value.replace(/^Use the [^ ]+ sub-agent to /, prefix);
        } else {
          input.value = prefix + input.value;
        }
      } else {
        input.value = prefix;
      }
      input.focus();
      // 커서를 맨 뒤로.
      input.selectionStart = input.selectionEnd = input.value.length;
      aPicker.selectedIndex = 0;
    });
  }
})();
</script>$chatShellClose
""",
            embed = embed,
        )
    }

    /**
     * v1.54.0 — ChatGPT 스타일 채팅 목록 사이드바 HTML.
     * 새 채팅 버튼 + 채팅 항목(제목 / 응답중 점 / ⋯ 메뉴의 rename·delete).
     */
    fun chatSidebar(
        chats: List<com.siamakerlab.vibecoder.server.projects.ProjectService.ChatSummary>,
        activeId: String,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val csrfInput = CsrfTokens.hiddenInput(csrf)
        val items = if (chats.isEmpty()) {
            """<div class="chat-empty">${esc(t("chat.empty"))}</div>"""
        } else chats.joinToString("") { c ->
            val active = c.id == activeId
            val busyDot = if (c.busy) """<span class="chat-busy-dot" title="${esc(t("chat.busy"))}"></span>""" else ""
            """
      <div class="chat-item${if (active) " active" else ""}">
        <a class="chat-item-link" href="/chat?c=${esc(c.id.encodeUrlSeg())}">
          <span class="chat-item-title" title="${esc(c.title)}">${esc(c.title)}</span>
          $busyDot
        </a>
        <details class="chat-item-menu">
          <summary title="${esc(t("chat.menu"))}">⋯</summary>
          <div class="chat-item-pop">
            <form method="post" action="/chat/${esc(c.id.encodeUrlSeg())}/rename" class="chat-rename-row">
              $csrfInput
              <input type="text" name="title" maxlength="120" value="${esc(c.title)}"
                     placeholder="${esc(t("chat.rename.placeholder"))}" autocomplete="off">
              <button type="submit" class="chat-pop-btn">${esc(t("chat.rename.save"))}</button>
            </form>
            <form method="post" action="/chat/${esc(c.id.encodeUrlSeg())}/delete"
                  onsubmit="return confirm('${esc(t("chat.delete.confirm")).replace("'", "&#39;")}')">
              $csrfInput
              <button type="submit" class="chat-pop-btn danger">${esc(t("chat.delete"))}</button>
            </form>
          </div>
        </details>
      </div>"""
        }
        return """
      <form method="post" action="/chat/new">
        $csrfInput
        <button type="submit" class="chat-new-btn">＋ ${esc(t("chat.new"))}</button>
      </form>
      <div class="chat-list">$items</div>"""
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/builds — 빌드 목록 + APK 다운로드
    // ────────────────────────────────────────────────────────────────────

    fun buildsPage(
        username: String,
        p: ProjectDto,
        builds: List<BuildDto>,
        artifactsByBuild: Map<String, ArtifactRow>,
        /** v0.59.0 — Phase 38 통계 카드 (null = repo / artifact 조회 실패). */
        stats: com.siamakerlab.vibecoder.server.build.BuildService.BuildStatistics? = null,
        /** v1.26.0 — packageName 매칭 키스토어 존재 여부. false 면 빌드 버튼 비활성화 + 안내.
         *  v1.26.1 — fail-secure default false. 호출자가 명시 안 하면 안전한 쪽 (차단). */
        keystoreReady: Boolean = false,
        /** v1.57.0 — 인라인 키스토어 생성 폼 prefill (DN 메타 + 비번 기본값). null=폼 생략. */
        keystorePrefill: com.siamakerlab.vibecoder.server.config.KeystoreDefaults? = null,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (builds.isEmpty()) {
            """<tr><td colspan="5" class="dim">${esc(t("builds.empty"))}</td></tr>"""
        } else {
            builds.joinToString("\n") { b ->
                val art = artifactsByBuild[b.id]
                val downloadCell = if (art != null) {
                    val sizeKb = (art.sizeBytes + 512L) / 1024L
                    """<a href="/api/projects/${esc(p.id)}/artifacts/${esc(art.id)}/download" class="primary-link" style="width:auto;display:inline-block;padding:4px 10px">APK · ${sizeKb}KB</a>"""
                } else {
                    """<span class="dim">-</span>"""
                }
                val statusCls = when (b.status.name) {
                    "SUCCESS" -> "ok"
                    "FAILED", "TIMEOUT" -> "warn"
                    "RUNNING", "PENDING" -> ""
                    else -> "dim"
                }
                """<tr>
                    <td><a href="/projects/${esc(p.id)}/builds/${esc(b.id)}"><code>${esc(b.id.take(12))}</code></a></td>
                    <td><span class="$statusCls">${esc(b.status.name)}</span></td>
                    <td>${esc(b.startedAt)}</td>
                    <td>${esc(b.finishedAt ?: "-")}</td>
                    <td>$downloadCell</td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(t("builds.title"))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("builds.title"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>${esc(t("builds.module"))}:</strong> ${esc(p.moduleName)} · <strong>${esc(t("builds.task"))}:</strong> <code>${esc(p.debugTask)}</code>
    </div>
    <div style="display:flex;gap:8px">
      ${AdminTemplates.backButton("/projects/${p.id}", t("builds.back"), topTarget = true)}
      <form method="post" action="/projects/${esc(p.id)}/builds" style="display:inline">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="primary" style="width:auto;padding:8px 16px"${if (!keystoreReady) " disabled title=\"${esc(t("builds.disabled.noKeystore"))}\"" else ""}>${esc(t("builds.queue"))}</button>
      </form>
    </div>
  </div>
  <p class="hint">${esc(t("builds.queueHint"))}</p>
  ${if (!keystoreReady) """
  <!-- v1.26.0 — 키스토어 미준비 시 빌드 차단 안내. 운영 정책: AGP 의 default
       debug.keystore 자동 생성도 허용 X (CLAUDE.md "키스토어 임의 생성 금지").
       v1.57.0 — 설정 페이지로 떠나지 않고 여기서 바로 생성하는 인라인 폼 추가. -->
  <div class="warn" style="margin-top:10px;padding:10px 12px;background:rgba(234,179,8,0.08);border:1px solid rgba(234,179,8,0.35);border-radius:6px">
    <strong>⚠ ${esc(t("builds.disabled.title"))}</strong>
    <p style="margin:4px 0 0;font-size:13px;line-height:1.5">${esc(t("builds.disabled.body"))}</p>
    <p style="margin:6px 0 0;font-size:12px">
      <a href="/settings/keystores" class="chip chip-link" target="_top">${esc(t("builds.disabled.openKeystores"))}</a>
      <code style="margin-left:8px">${esc(p.packageName)}</code> ${esc(t("builds.disabled.expected"))}
    </p>
${if (keystorePrefill != null) renderInlineKeystoreForm(p, keystorePrefill, csrf, lang) else ""}
  </div>
  """ else ""}
</div>

${renderBuildStatistics(stats, lang)}

${renderBuildHistoryChart(builds, artifactsByBuild, lang)}

<table class="devices">
  <thead>
    <tr><th>${esc(t("builds.col.id"))}</th><th>${esc(t("builds.col.status"))}</th><th>${esc(t("builds.col.started"))}</th><th>${esc(t("builds.col.finished"))}</th><th>${esc(t("builds.col.apk"))}</th></tr>
  </thead>
  <tbody>$rowsHtml</tbody>
</table>
""",
            embed = embed,
        )
    }

    /**
     * v1.57.0 — 빌드 페이지 인라인 키스토어 생성 폼.
     *
     * `/settings/keystores` 의 생성 폼과 같은 필드지만:
     *  - packageName 은 이 프로젝트 것으로 readonly 고정 (자동 연결 — POST 핸들러가
     *    프로젝트 메타로 강제 set 생성).
     *  - DN 메타는 [prefill](= server.yml defaults + 마지막 입력 캐시) 로 미리 채움.
     *  - 비밀번호만 매번 입력 (기본값이 server.yml 에 있으면 그 값 prefill).
     *  - action 은 `/projects/{id}/keystore` → 성공 시 이 빌드 페이지로 돌아옴.
     */
    private fun renderInlineKeystoreForm(
        p: ProjectDto,
        prefill: com.siamakerlab.vibecoder.server.config.KeystoreDefaults,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val pwPrefill = if (prefill.defaultPassword.isNotBlank())
            """ value="${esc(prefill.defaultPassword)}"""" else ""
        return """
    <details class="ks-inline" style="margin-top:12px;border-top:1px solid rgba(234,179,8,0.25);padding-top:10px">
      <summary style="cursor:pointer;font-size:13px;font-weight:600;color:#eab308">${esc(t("builds.ks.summary"))}</summary>
      <p class="dim" style="font-size:12px;margin:8px 0 10px">${esc(t("builds.ks.hint"))}</p>
      <form method="post" action="/projects/${esc(p.id)}/keystore" target="_top">
        ${CsrfTokens.hiddenInput(csrf)}
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
          <label style="grid-column:1/3">
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.package"))} · ${esc(t("builds.ks.locked"))}</div>
            <input name="packageName" value="${esc(p.packageName)}" readonly
                   style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace;opacity:.75;cursor:not-allowed">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.password"))}</div>
            <input name="password" type="text" required$pwPrefill
                   style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.validity"))}</div>
            <input name="validityYears" type="number" min="1" max="100"
                   value="${prefill.validityYears}" style="width:100%;padding:8px">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.name"))}</div>
            <input name="name" value="${esc(prefill.name)}" style="width:100%;padding:8px">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.organization"))}</div>
            <input name="organization" value="${esc(prefill.organization)}" style="width:100%;padding:8px">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.unit"))}</div>
            <input name="unit" value="${esc(prefill.unit)}" style="width:100%;padding:8px">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.country"))}</div>
            <input name="country" value="${esc(prefill.country)}" maxlength="2"
                   style="width:100%;padding:8px;text-transform:uppercase">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.state"))}</div>
            <input name="state" value="${esc(prefill.state)}" style="width:100%;padding:8px">
          </label>
          <label>
            <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.city"))}</div>
            <input name="city" value="${esc(prefill.city)}" style="width:100%;padding:8px">
          </label>
        </div>
        <details style="margin-top:12px">
          <summary style="cursor:pointer;font-size:13px;color:#aaa">${esc(t("ks.admob.toggle"))}</summary>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">
            <label style="grid-column:1/3">
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appId"))}</div>
              <input name="admobAppId" placeholder="ca-app-pub-XXXX~YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.banner"))}</div>
              <input name="admobBannerUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appOpen"))}</div>
              <input name="admobAppOpenUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.native"))}</div>
              <input name="admobNativeUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.interstitial"))}</div>
              <input name="admobInterstitialUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.rewarded"))}</div>
              <input name="admobRewardedUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
            <label>
              <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.rewardedInterstitial"))}</div>
              <input name="admobRewardedInterstitialUnitId" placeholder="ca-app-pub-XXXX/YYYY"
                     style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
            </label>
          </div>
        </details>
        <p class="dim" style="font-size:12px;margin:12px 0 8px">${esc(t("ks.create.warn"))}</p>
        <button type="submit" class="primary" style="width:auto;padding:8px 16px">${esc(t("builds.ks.submit"))}</button>
      </form>
    </details>"""
    }

    /**
     * v0.30.0 — 빌드 history 차트. 최근 30 개 빌드의 duration (s) + status 를
     * SVG line chart 로 렌더. 외부 라이브러리 없이 inline SVG.
     *
     * X 축: oldest 빌드 (좌) → newest (우)
     * Y 축: duration (s). 0 = 하단, max = 상단.
     * 점 색상: SUCCESS=초록 / FAILED/TIMEOUT=빨강 / CANCELED=회색 / 그 외=노랑.
     * SUCCESS 라인만 연결 (실패는 점만 찍어 통계 distortion 방지).
     *
     * APK 크기 추세는 작은 second-axis 처럼 노란 점으로 같은 그래프에.
     */
    private fun renderBuildHistoryChart(
        builds: List<BuildDto>,
        artifacts: Map<String, ArtifactRow>,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        if (builds.size < 2) return ""
        // builds 는 보통 최신 → 오래된 순. 차트는 시간 순.
        val ordered = builds.reversed().takeLast(30)
        val durations = ordered.map { b ->
            durationSeconds(b.startedAt, b.finishedAt)
        }
        val maxDur = durations.filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val apkSizes = ordered.map { b -> artifacts[b.id]?.sizeBytes }
        val maxApk = apkSizes.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L

        val w = 720
        val h = 160
        val pad = 28
        val plotW = w - pad * 2
        val plotH = h - pad * 2
        val n = ordered.size
        val xStep = if (n > 1) plotW.toDouble() / (n - 1) else 0.0

        fun x(i: Int) = pad + i * xStep
        fun yDur(d: Double) = pad + plotH - (d / maxDur) * plotH

        val successPath = StringBuilder()
        var lastX: Double? = null
        ordered.forEachIndexed { i, b ->
            val d = durations[i] ?: return@forEachIndexed
            if (b.status.name != "SUCCESS") return@forEachIndexed
            val px = x(i)
            val py = yDur(d)
            if (lastX == null) successPath.append("M %.1f %.1f".format(px, py))
            else successPath.append(" L %.1f %.1f".format(px, py))
            lastX = px
        }

        val pointsSb = StringBuilder()
        ordered.forEachIndexed { i, b ->
            val d = durations[i]
            val color = when (b.status.name) {
                "SUCCESS" -> "#059669"
                "FAILED", "TIMEOUT" -> "#dc2626"
                "CANCELED" -> "#6b7280"
                else -> "#d97706"
            }
            if (d != null) {
                val px = x(i)
                val py = yDur(d)
                pointsSb.append("""<circle cx="%.1f" cy="%.1f" r="3" fill="$color"><title>${esc(b.id.take(8))} · ${b.status.name} · %.1fs</title></circle>""".format(px, py, d))
            }
            val apk = apkSizes[i]
            if (apk != null) {
                val px = x(i)
                val py = pad + plotH - (apk.toDouble() / maxApk) * plotH * 0.6 - 4
                val mb = "%.1f".format(apk / 1_048_576.0)
                pointsSb.append("""<rect x="%.1f" y="%.1f" width="3" height="3" fill="#facc15"><title>APK ${mb} MB</title></rect>""".format(px - 1.5, py))
            }
        }

        // Axes (very light, just for grounding)
        val axes = """
            <line x1="$pad" y1="${pad + plotH}" x2="${pad + plotW}" y2="${pad + plotH}" stroke="rgba(255,255,255,0.15)" />
            <line x1="$pad" y1="$pad" x2="$pad" y2="${pad + plotH}" stroke="rgba(255,255,255,0.15)" />
        """
        val maxLabel = if (maxDur >= 60) "%.1f min".format(maxDur / 60) else "%.0fs".format(maxDur)

        return """
<div class="card" style="margin-bottom:16px">
  <h2 style="margin-top:0">${esc(t("builds.summary.title"))} <small class="dim" style="font-size:11px;font-weight:400">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "builds.summary.note", n))}</small></h2>
  <svg viewBox="0 0 $w $h" width="100%" height="$h" style="font-family:system-ui,sans-serif">
    $axes
    <text x="${pad - 6}" y="${pad + 4}" text-anchor="end" font-size="10" fill="rgba(255,255,255,0.5)">$maxLabel</text>
    <text x="${pad - 6}" y="${pad + plotH}" text-anchor="end" font-size="10" fill="rgba(255,255,255,0.5)">0</text>
    <path d="$successPath" stroke="#059669" stroke-width="1.5" fill="none" stroke-linejoin="round" />
    $pointsSb
  </svg>
  <p class="hint" style="margin:4px 0 0">${esc(t("builds.summary.hint"))}</p>
</div>"""
    }

    /** ISO timestamp 두 개에서 duration in seconds. 둘 중 하나 null 이면 null. */
    private fun durationSeconds(startedAt: String?, finishedAt: String?): Double? {
        if (startedAt.isNullOrBlank() || finishedAt.isNullOrBlank()) return null
        return runCatching {
            val s = java.time.Instant.parse(startedAt)
            val e = java.time.Instant.parse(finishedAt)
            java.time.Duration.between(s, e).toMillis() / 1000.0
        }.getOrNull()
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/builds/{buildId} — 빌드 상세 + 실시간 로그
    // ────────────────────────────────────────────────────────────────────

    fun buildDetailPage(
        username: String,
        p: ProjectDto,
        b: BuildDto,
        artifact: ArtifactRow?,
        replay: BuildLogReplay? = null,
        playPrecheck: com.siamakerlab.vibecoder.server.publish.PlayPublishService.Precheck? = null,
        playFlashOk: String? = null,
        playFlashErr: String? = null,
        testFlightPrecheck: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService.Precheck? = null,
        tfFlashOk: String? = null,
        tfFlashErr: String? = null,
        signerInspection: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector.Inspection? = null,
        /** v0.58.0 — Phase 37 이전 성공 빌드와의 비교 카드 (null = no prior success). */
        comparison: com.siamakerlab.vibecoder.server.build.BuildService.BuildComparison? = null,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val statusCls = when (b.status.name) {
            "SUCCESS" -> "ok"
            "FAILED", "TIMEOUT" -> "warn"
            "RUNNING", "PENDING" -> ""
            else -> "dim"
        }
        val downloadHtml = if (artifact != null) {
            val sizeKb = (artifact.sizeBytes + 512L) / 1024L
            """<a href="/api/projects/${esc(p.id)}/artifacts/${esc(artifact.id)}/download" class="primary-link"
                  style="width:auto;display:inline-block;padding:8px 16px">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "build.detail.apkDownload", sizeKb))}</a>
               <p class="hint">sha256: <code>${esc(artifact.sha256.take(16))}…</code> · ${esc(artifact.fileName)}</p>"""
        } else if (b.status.name == "SUCCESS") {
            """<p class="dim">${esc(t("build.detail.apkMissing"))}</p>"""
        } else {
            """<p class="dim">${esc(t("build.detail.apkPending"))}</p>"""
        }
        val errorHtml = if (b.errorMessage != null) {
            """<div class="error">${esc(b.errorMessage)}</div>"""
        } else ""

        val isTerminal = b.status.name in setOf("SUCCESS", "FAILED", "CANCELED", "TIMEOUT")
        val cancelHtml = if (!isTerminal) {
            """<form method="post" action="/projects/${esc(p.id)}/builds/${esc(b.id)}/cancel" style="display:inline"
                    onsubmit="return confirm(${jsLit(t("build.detail.cancelConfirm"))})">
               ${CsrfTokens.hiddenInput(csrf)}
               <button type="submit" class="chip chip-danger">${esc(t("build.detail.cancel"))}</button>
               </form>"""
        } else ""

        // v0.12.4 — JS 문자열 컨텍스트 전용 escape. esc() 는 HTML 컨텍스트용.
        val projectIdJs = jsLit(p.id)
        val buildIdJs = jsLit(b.id)
        // 종료 상태이면 JS가 connect 안 함 (로그는 이미 stdout 으로 흘러갔고 ring 에서 evicted).
        // PENDING/RUNNING 이면 WS 연결.
        val attachWs = !isTerminal

        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(t("build.detail.heading"))} ${esc(b.id.take(8))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("build.detail.heading"))} <code style="font-size:0.7em">${esc(b.id.take(12))}</code>
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>${esc(t("build.detail.statusLabel"))}:</strong> <span class="$statusCls">${esc(b.status.name)}</span>
      · <span class="dim">${esc(b.variant)}</span>
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      ${AdminTemplates.backButton("/projects/${p.id}/builds", t("build.detail.backToBuilds"))}
      <a href="/projects/${esc(p.id)}/console" class="chip chip-link">${esc(t("build.detail.toConsole"))}</a>
      $cancelHtml
    </div>
  </div>
  <dl style="margin-top:12px;display:grid;grid-template-columns:max-content 1fr;gap:6px 12px">
    <dt class="dim">${esc(t("build.detail.startedAt"))}</dt><dd>${esc(b.startedAt)}</dd>
    <dt class="dim">${esc(t("build.detail.finishedAt"))}</dt><dd>${esc(b.finishedAt ?: "-")}</dd>
    ${b.gitBranch?.let { """<dt class="dim">${esc(t("build.detail.gitBranch"))}</dt><dd><code>${esc(it)}</code></dd>""" } ?: ""}
    ${b.gitSha?.let { """<dt class="dim">${esc(t("build.detail.gitSha"))}</dt><dd><code title="${esc(it)}">${esc(it.take(12))}</code></dd>""" } ?: ""}
  </dl>
  $errorHtml
</div>

<div class="card" style="margin-bottom:16px">
  <h2>${esc(t("build.detail.apkSection"))}</h2>
  $downloadHtml
  ${renderSignerInspection(signerInspection, lang)}
</div>

${renderBuildComparison(comparison, lang, p.id, b.id)}

${renderPlayUploadCard(p, b, playPrecheck, playFlashOk, playFlashErr, csrf, lang)}
<!-- v1.7.21 — TestFlight 카드 렌더 제거. vibe-coder-server 는 Android 전용 도구
     (CLAUDE.md §1) — iOS 빌드 미지원이라 사용자에 노이즈. POST 라우트는 그대로
     남겨 둠 (API 호환). 다시 켜려면 아래 라인 복원:
     ${'$'}{renderTestFlightUploadCard(p, b, testFlightPrecheck, tfFlashOk, tfFlashErr, csrf, lang)} -->


<div class="card">
  <h2>${esc(t("build.detail.logs"))} ${if (attachWs) """<small class="dim" style="font-size:11px;text-transform:none;letter-spacing:0">${esc(t("build.detail.logs.live"))}</small>""" else """<small class="dim" style="font-size:11px;text-transform:none;letter-spacing:0">${esc(t("build.detail.logs.replay"))}</small>"""}</h2>
  <div id="build-log" class="console-log" aria-live="polite">${renderReplay(replay)}</div>
  ${replayCaption(replay, attachWs, lang)}
</div>

${if (attachWs) """
<script>
(function() {
  var projectId = $projectIdJs;
  var buildId = $buildIdJs;
  var logEl = document.getElementById('build-log');

  function escHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function append(cls, label, body) {
    var atBottom = logEl.scrollTop + logEl.clientHeight >= logEl.scrollHeight - 10;
    var row = document.createElement('div');
    row.className = 'log-line ' + cls;
    row.innerHTML = '<span class="log-label">' + escHtml(label) + '</span><span class="log-body">' + escHtml(body) + '</span>';
    logEl.appendChild(row);
    if (atBottom) logEl.scrollTop = logEl.scrollHeight;
  }

  function classOfLevel(level) {
    if (level === 'ERROR' || level === 'STDERR') return 'err';
    if (level === 'WARN') return 'tool';
    if (level === 'STDOUT') return 'assistant';
    return 'sys';
  }

  function renderFrame(f) {
    if (f.type === 'log') {
      append(classOfLevel(f.level), f.level, f.message);
    } else if (f.type === 'done') {
      append(f.status === 'SUCCESS' ? 'sys' : 'err',
             'done', f.status + (f.errorMessage ? ' · ' + f.errorMessage : ''));
      // 5초 후 페이지를 새로고침해 최종 상태 + APK 링크를 갱신.
      setTimeout(function() { location.reload(); }, 5000);
    } else if (f.type === 'error') {
      append('err', 'ws', (f.code || '') + ': ' + (f.message || ''));
    }
  }

  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/builds/' + buildId + '/logs');
    ws.onopen = function() {
      // 인증은 WS handshake cookie 로 처리 (vibe_session 은 httpOnly).
      append('sys', 'ws', 'connected');
    };
    ws.onmessage = function(ev) {
      try { renderFrame(JSON.parse(ev.data)); }
      catch (e) { append('err', 'parse', String(e)); }
    };
    ws.onclose = function(ev) {
      append('sys', 'ws', 'closed (code ' + ev.code + ')');
    };
    ws.onerror = function() { append('err', 'ws', 'error'); };
  }

  connect();
})();
</script>
""" else ""}
""",
            embed = embed,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/files — 업로드된 파일 관리
    // ────────────────────────────────────────────────────────────────────

    fun filesPage(
        username: String,
        p: ProjectDto,
        files: List<FileEntryDto>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        val rowsHtml = if (files.isEmpty()) {
            """<tr><td colspan="5" class="dim">${esc(t("files.empty"))}</td></tr>"""
        } else {
            files.joinToString("\n") { f ->
                val sizeKb = (f.sizeBytes + 512L) / 1024L
                """<tr>
                    <td>${esc(f.originalName)}</td>
                    <td><span class="dim">${esc(f.mimeType ?: "-")}</span></td>
                    <td>${sizeKb}KB</td>
                    <td>${esc(f.createdAt)}</td>
                    <td style="display:flex;gap:6px">
                      <a href="/projects/${esc(p.id)}/files/${esc(f.id)}/download" class="chip chip-link">${esc(t("files.action.download"))}</a>
                      <form method="post" action="/projects/${esc(p.id)}/files/${esc(f.id)}/delete" style="display:inline"
                            onsubmit="return confirm(${jsLit(t("files.deleteConfirm"))})">
                        ${CsrfTokens.hiddenInput(csrf)}
                        <button type="submit" class="chip chip-danger">${esc(t("files.action.delete"))}</button>
                      </form>
                    </td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(t("files.title"))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("files.title"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:16px">
  <h2>${esc(t("files.upload.title"))}</h2>
  <!-- multipart 업로드는 receiveParameters 가 불가능하므로 _csrf 를 query string 으로 -->
  <form method="post" action="/projects/${esc(p.id)}/files/upload?_csrf=${esc(csrf)}" enctype="multipart/form-data">
    <input type="file" name="file" required>
    <button type="submit" class="primary" style="width:auto;padding:8px 16px;margin-left:8px">${esc(t("files.upload.submit"))}</button>
  </form>
  <p class="hint">${t("files.upload.hint")}</p>
</div>

<table class="devices">
  <thead>
    <tr><th>${esc(t("files.col.name"))}</th><th>${esc(t("files.col.mime"))}</th><th>${esc(t("files.col.size"))}</th><th>${esc(t("files.col.uploaded"))}</th><th></th></tr>
  </thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:16px">
  ${AdminTemplates.backButton("/projects/${p.id}", t("files.back"), topTarget = true)}
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">${esc(t("files.toConsole"))}</a>
</p>
"""
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/git — 읽기 전용 git status / diff / log
    // ────────────────────────────────────────────────────────────────────

    fun gitPage(
        username: String,
        p: ProjectDto,
        status: GitStatusDto?,
        diff: GitDiffDto?,
        log: GitLogDto?,
        unavailable: Boolean,
        csrf: String? = null,
        commitFlash: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val unavailableHtml = if (unavailable) {
            """<div class="error">${t("git.notInit")}</div>"""
        } else ""

        val statusHtml = if (status == null) "" else {
            val entries = if (status.entries.isEmpty()) {
                """<p class="dim">${esc(t("git.clean"))}</p>"""
            } else {
                val rows = status.entries.joinToString("\n") { e ->
                    """<tr><td><code>${esc(e.status)}</code></td><td>${esc(e.path)}</td></tr>"""
                }
                """<table class="devices"><thead><tr><th>${esc(t("git.col.status"))}</th><th>${esc(t("git.col.path"))}</th></tr></thead><tbody>$rows</tbody></table>"""
            }
            """<div class="card">
              <h2>status</h2>
              <p><strong>${esc(t("git.status.branch"))}:</strong> <code>${esc(status.branch)}</code>
                · <span class="dim">${esc(t("git.status.ahead"))}</span> ${status.ahead}
                · <span class="dim">${esc(t("git.status.behind"))}</span> ${status.behind}</p>
              $entries
            </div>"""
        }

        val diffHtml = if (diff == null) "" else {
            val body = if (diff.diff.isBlank()) {
                """<p class="dim">${esc(t("git.diff.empty"))}</p>"""
            } else {
                """<pre class="diff-block">${esc(diff.diff.take(20_000))}</pre>"""
            }
            """<div class="card">
              <h2>${esc(t("git.diff.title"))}</h2>
              $body
              ${if (diff.diff.length > 20_000) """<p class="hint">${esc(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "git.diff.truncated", diff.diff.length - 20_000))}</p>""" else ""}
            </div>"""
        }

        val logHtml = if (log == null) "" else {
            val rows = if (log.entries.isEmpty()) {
                """<tr><td colspan="2" class="dim">${esc(t("git.log.empty"))}</td></tr>"""
            } else {
                log.entries.joinToString("\n") { e ->
                    """<tr><td><code>${esc(e.sha.take(8))}</code></td><td>${esc(e.message)}</td></tr>"""
                }
            }
            """<div class="card">
              <h2>${esc(t("git.log.recent10"))}</h2>
              <table class="devices"><thead><tr><th>${esc(t("git.log.colSha"))}</th><th>${esc(t("git.log.colMessage"))}</th></tr></thead><tbody>$rows</tbody></table>
            </div>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(t("git.heading"))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("git.heading"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

$unavailableHtml
${if (commitFlash != null) """<div class="ok-banner" style="white-space:pre-wrap;font-family:ui-monospace,Menlo,monospace;font-size:12px">${esc(commitFlash)}</div>""" else ""}

<section style="display:grid;gap:16px">
  $statusHtml
  $diffHtml
  $logHtml
</section>

${if (status != null && !unavailable) """
<div class="card" style="margin-top:16px">
  <h2>${esc(t("git.commit.title"))}</h2>
  <p class="dim" style="font-size:12px">${esc(t("git.commit.desc"))}</p>
  <form method="post" action="/projects/${esc(p.id)}/git/commit" style="display:grid;gap:8px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label>${esc(t("git.commit.messageLabel"))}
      <textarea name="message" required minlength="3" maxlength="4000" rows="3"
                placeholder="feat: ..."></textarea>
    </label>
    <label style="font-size:12px">
      <input type="checkbox" name="onlyTracked" value="1">
      ${esc(t("git.commit.onlyTracked"))}
    </label>
    <label style="font-size:12px">
      <input type="checkbox" name="push" value="1" checked>
      ${t("git.commit.push")}
    </label>
    <div>
      <button type="submit" class="primary" style="padding:8px 18px">${esc(t("git.commit.submit"))}</button>
    </div>
  </form>
</div>""" else ""}

<p class="hint" style="margin-top:16px">
  ${AdminTemplates.backButton("/projects/${p.id}", t("git.back"), topTarget = true)}
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">${esc(t("git.toConsole"))}</a>
</p>
<p class="hint">${t("git.bottomHint")}</p>
""",
            embed = embed,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/tree — 디렉토리 listing (v0.13.0)
    // ────────────────────────────────────────────────────────────────────

    fun fileTreePage(
        username: String,
        p: ProjectDto,
        subPath: String,
        entries: List<ProjectFileBrowser.Entry>,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""
        val crumbs = renderBreadcrumbs(p.id, subPath)
        // v1.14.0 — toolbar / per-row actions 가 form POST 로 작동. 모든 path 는 subPath
        // (현재 디렉토리) 기준 상대명만 사용 — 서버에서 PathSafety + projectRoot 검증.
        val csrfHidden = com.siamakerlab.vibecoder.server.auth.CsrfTokens.hiddenInput(csrf)
        val parentEnc = subPath.encodeUrl()

        val rowsHtml = if (entries.isEmpty()) {
            """<tr><td colspan="4" class="dim">${esc(t("fileTree.empty"))}</td></tr>"""
        } else {
            entries.joinToString("\n") { e ->
                val sizeKb = if (e.isDirectory) "-" else "${(e.sizeBytes + 512L) / 1024L}KB"
                val icon = if (e.isDirectory) "📁" else "📄"
                val href = if (e.isDirectory)
                    "/projects/${esc(p.id)}/tree?path=${e.relPath.encodeUrl()}"
                else
                    "/projects/${esc(p.id)}/view?path=${e.relPath.encodeUrl()}"
                // v1.14.0 — row 우측에 Rename / Delete inline form.
                val renameJsLit = "'" + e.name.replace("\\", "\\\\").replace("'", "\\'") + "'"
                val confirmDelete = t("fileTree.confirm.delete").replace("{0}", e.name)
                val renamePrompt = t("fileTree.prompt.rename")
                // C4 (21차 점검) — renameJsLit 은 backslash·single-quote 만 escape 하므로
                // double-quote HTML 속성(onsubmit="...") 안에 raw 보간하면 파일명에 포함된
                // `"`·`<`·`>` 로 속성을 breakout 해 이벤트 핸들러를 주입할 수 있었다(stored XSS).
                // 파일명은 디스크 listing 에서 무검증으로 옴(Claude Write/git clone/upload).
                // delete 폼처럼 esc() 로 감싸 속성 컨텍스트를 안전화(브라우저가 attr decode 후
                // JS literal 로 복원되므로 JS 도 유효 유지).
                val actions = """<form method="post" action="/projects/${esc(p.id)}/files/rename"
                          style="display:inline" onsubmit="var v=prompt('${esc(renamePrompt)}',${esc(renameJsLit)}); if(!v||v===${esc(renameJsLit)}){return false;} this.newName.value=v;">
                      $csrfHidden
                      <input type="hidden" name="path" value="${esc(e.relPath)}">
                      <input type="hidden" name="newName" value="">
                      <button type="submit" class="chip chip-link" title="${esc(t("fileTree.rename"))}">✎</button>
                    </form>
                    <form method="post" action="/projects/${esc(p.id)}/files/delete"
                          style="display:inline" onsubmit="return confirm('${esc(confirmDelete)}')">
                      $csrfHidden
                      <input type="hidden" name="path" value="${esc(e.relPath)}">
                      <button type="submit" class="chip chip-link" style="color:#ff9e9e" title="${esc(t("fileTree.delete"))}">🗑</button>
                    </form>"""
                // v1.19.0 — row 에 data-rel-path / data-is-dir 추가. row 자체 class "row-link"
                // (long-press / contextmenu / click 핸들러 가 JS 에서 binding).
                """<tr class="row-link" data-rel-path="${esc(e.relPath)}" data-is-dir="${if (e.isDirectory) "1" else "0"}">
                    <td><a href="$href">$icon ${esc(e.name)}</a></td>
                    <td class="dim">$sizeKb</td>
                    <td class="dim" style="font-size:11px">${esc(e.modifiedAt)}</td>
                    <td style="text-align:right;white-space:nowrap">$actions</td>
                  </tr>"""
            }
        }
        // v1.14.0 — 상단 toolbar: Upload / New file / New folder.
        // v1.19.0 — 다중 선택 모드 toolbar 추가 (기본 toolbar 와 토글). long-press / 우클릭 시
        // selection toolbar 표시. clipboard 는 sessionStorage 영속.
        val newFilePrompt = t("fileTree.prompt.newFile")
        val newFolderPrompt = t("fileTree.prompt.newFolder")
        val confirmDeleteN = t("fileTree.confirm.deleteN")
        val toolbar = """<div class="card" style="margin-bottom:10px">
  <div id="fts-toolbar-default" style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
    <!-- v1.27.4 (B1) — parent 를 query param 으로도 전달. multipart part 순서에
         의존하지 않도록 (file part 가 parent FormItem 보다 먼저 와도 정확한 위치).
         hidden input 은 fallback 으로 유지. -->
    <form method="post" action="/projects/${esc(p.id)}/files/upload?_csrf=${csrf?.encodeUrl() ?: ""}&parent=${subPath.encodeUrl()}"
          enctype="multipart/form-data" style="display:inline-flex;gap:6px;align-items:center">
      <input type="hidden" name="parent" value="${esc(subPath)}">
      <label class="chip chip-link" style="cursor:pointer">
        ⬆ ${esc(t("fileTree.upload"))}
        <input type="file" name="file" style="display:none" onchange="this.form.submit()">
      </label>
    </form>
    <form method="post" action="/projects/${esc(p.id)}/files/new-file"
          style="display:inline" onsubmit="var v=prompt('${esc(newFilePrompt)}',''); if(!v){return false;} this.name.value=v;">
      $csrfHidden
      <input type="hidden" name="parent" value="${esc(subPath)}">
      <input type="hidden" name="name" value="">
      <button type="submit" class="chip chip-link">＋ ${esc(t("fileTree.newFile"))}</button>
    </form>
    <form method="post" action="/projects/${esc(p.id)}/files/new-folder"
          style="display:inline" onsubmit="var v=prompt('${esc(newFolderPrompt)}',''); if(!v){return false;} this.name.value=v;">
      $csrfHidden
      <input type="hidden" name="parent" value="${esc(subPath)}">
      <input type="hidden" name="name" value="">
      <button type="submit" class="chip chip-link">📁＋ ${esc(t("fileTree.newFolder"))}</button>
    </form>
    <button type="button" id="fts-paste-btn" class="chip chip-link" style="display:none">
      <span id="fts-paste-label">⎘ paste</span>
    </button>
    <span class="dim" style="font-size:12px;margin-left:auto">${esc(t("fileTree.hint.toolbar"))}</span>
  </div>
  <div id="fts-toolbar-select" style="display:none;gap:8px;flex-wrap:wrap;align-items:center">
    <span style="font-weight:600">${esc(t("fileTree.select.title"))}: <span id="fts-count">0</span></span>
    <button type="button" id="fts-copy-btn" class="chip chip-link">⎘ ${esc(t("fileTree.select.copy"))}</button>
    <button type="button" id="fts-cut-btn" class="chip chip-link">✂ ${esc(t("fileTree.select.cut"))}</button>
    <button type="button" id="fts-download-btn" class="chip chip-link">⬇ ${esc(t("fileTree.select.download"))}</button>
    <button type="button" id="fts-delete-btn" class="chip chip-link" style="color:#ff9e9e"
            data-confirm="${esc(confirmDeleteN)}">🗑 ${esc(t("fileTree.select.delete"))}</button>
    <button type="button" id="fts-close-btn" class="chip chip-link" style="margin-left:auto">✕ ${esc(t("fileTree.select.close"))}</button>
  </div>
</div>"""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(t("fileTree.heading"))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("fileTree.heading"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>
$okHtml
$errHtml

<div class="card" style="margin-bottom:14px">
  <div style="font-size:13px">$crumbs</div>
</div>

<div id="file-tree-root"
     data-project-id="${esc(p.id)}"
     data-sub-path="${esc(subPath)}"
     data-csrf="${esc(csrf ?: "")}">
$toolbar

<style>
  /* v1.19.0 — 다중 선택 모드 시각 강조. */
  table.devices tr.fts-selectable td a { pointer-events: none; }
  table.devices tr.fts-selectable { cursor: pointer; user-select: none; }
  table.devices tr.fts-selected { background: rgba(106,169,255,0.15) !important; }
  /* v1.23.1 — 회귀 회수: inset shadow 가 모든 td 좌측에 그려져 cell 사이가 세로 라인
     처럼 보였음. 첫 td 에만 적용해서 row 전체에서 좌측 accent bar 한 개만. */
  table.devices tr.fts-selected td:first-child { box-shadow: inset 3px 0 0 var(--accent, #6aa9ff); }
</style>

<table class="devices">
  <thead><tr>
    <th>${esc(t("fileTree.col.name"))}</th>
    <th>${esc(t("fileTree.col.size"))}</th>
    <th>${esc(t("fileTree.col.modified"))}</th>
    <th></th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="font-size:12px">${esc(t("fileTree.hint"))} · ${esc(t("fileTree.hint.select"))}</p>
</div>

<script src="/static/file-tree-select.js" defer></script>
""",
            embed = embed,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // /projects/{id}/view — 단일 파일 read-only view + 편집 (v0.13.0)
    // ────────────────────────────────────────────────────────────────────

    fun fileViewPage(
        username: String,
        p: ProjectDto,
        relPath: String,
        view: ProjectFileBrowser.FileView?,
        flashErr: String? = null,
        /**
         * v1.17.0 — 이미지 모드. relPath 가 이미지 확장자인 경우 [view] 대신 본 size
         * 가 non-null 로 전달. body 는 `<img src="/raw?path=...">` viewer.
         */
        imageSizeBytes: Long? = null,
        csrf: String? = null,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val crumbs = renderBreadcrumbs(p.id, relPath, isFile = true)

        val bodyHtml = if (imageSizeBytes != null) {
            // v1.17.0 — 이미지 뷰어 모드. raw stream endpoint 를 직접 가리킴.
            val sizeKb = (imageSizeBytes + 512L) / 1024L
            val rawUrl = "/projects/${esc(p.id)}/raw?path=${relPath.encodeUrl()}"
            """
<div class="card" style="margin-bottom:12px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div><strong><code>${esc(relPath)}</code></strong>
      <span class="dim" style="font-size:12px;margin-left:8px">${sizeKb}KB · ${esc(t("fileView.image.label"))}</span>
      <span id="img-dims" class="dim" style="font-size:12px;margin-left:8px"></span>
    </div>
    <div style="display:flex;gap:6px">
      <a href="$rawUrl" download class="chip chip-link">⬇ ${esc(t("fileView.image.download"))}</a>
      <a href="/projects/${esc(p.id)}/tree?path=${parentOf(relPath).encodeUrl()}" class="chip chip-link">${esc(t("fileView.parentDir"))}</a>
    </div>
  </div>
</div>
<div class="card" style="padding:12px;display:flex;justify-content:center;align-items:center;background:repeating-conic-gradient(#1a1a1a 0% 25%, #222 0% 50%) 50% / 24px 24px">
  <img id="img-viewer" src="$rawUrl" alt="${esc(relPath)}"
       style="max-width:100%;height:auto;display:block;background:#0e0e0e;border:1px solid #2a2a2a;border-radius:4px">
</div>
<script>
(function() {
  var img = document.getElementById('img-viewer');
  var dims = document.getElementById('img-dims');
  if (!img || !dims) return;
  img.addEventListener('load', function() {
    dims.textContent = img.naturalWidth + ' × ' + img.naturalHeight + ' px';
  });
  img.addEventListener('error', function() {
    dims.textContent = '${esc(t("fileView.image.loadFailed"))}';
  });
})();
</script>
"""
        } else if (view == null) {
            errHtml.ifEmpty { """<p class="dim">${esc(t("fileView.cannotOpen"))}</p>""" }
        } else {
            // v1.18.0 — view/edit 토글 분리 모드 제거. CodeMirror 5 단일 에디터로 통합:
            //   - line numbers gutter, syntax highlight (자동 mode 매핑), active line
            //   - 직접 편집 + Ctrl/Cmd+S 저장 + 변경 시 dirty indicator
            //   - 큰 파일 (MAX_HL_CHARS 초과) 도 CodeMirror 로 표시 (mode 만 plain)
            val sizeKb = (view.sizeBytes + 512L) / 1024L
            val cmMode = mapMimeToCodeMirror(view.mimeGuess, view.relPath)
            """
<div class="card" style="margin-bottom:12px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div><strong><code>${esc(view.relPath)}</code></strong>
      <span class="dim" style="font-size:12px;margin-left:8px">${sizeKb}KB · ${esc(view.mimeGuess)}</span>
      <span id="dirty-indicator" class="dim" style="font-size:12px;margin-left:8px;display:none;color:var(--warn)">●&nbsp;${esc(t("fileView.dirty"))}</span>
    </div>
    <div style="display:flex;gap:6px;align-items:center">
      <small class="dim">${esc(t("fileView.editHint"))}</small>
      <a href="/projects/${esc(p.id)}/tree?path=${parentOf(relPath).encodeUrl()}" class="chip chip-link">${esc(t("fileView.parentDir"))}</a>
      <button type="submit" form="file-form" id="cm-save-btn" class="primary" style="width:auto;padding:6px 14px;font-size:13px">${esc(t("fileView.save"))}</button>
    </div>
  </div>
</div>

<form method="post" action="/projects/${esc(p.id)}/edit" id="file-form" style="margin:0">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="path" value="${esc(view.relPath)}">
  <textarea name="content" id="file-content" spellcheck="false">${esc(view.content)}</textarea>
</form>

<link rel="stylesheet" href="/static/vendor/codemirror/lib/codemirror.css">
<link rel="stylesheet" href="/static/vendor/codemirror/theme/material-darker.css">
<style>
  /* v1.18.0 — CodeMirror 통합 에디터. 카드 안에서 사용 가능한 높이 모두 사용. */
  .CodeMirror {
    height: calc(100vh - 240px); min-height: 320px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 13px; line-height: 1.5; border: 1px solid #2a2a2a; border-radius: 6px;
  }
  .CodeMirror-gutters { background: #0d0d0d; border-right: 1px solid #2a2a2a; }
  .CodeMirror-linenumber { color: #5a5a5a; padding: 0 8px 0 6px; }
  .CodeMirror-activeline-background { background: rgba(255,255,255,0.04); }
  .CodeMirror-activeline-gutter { background: rgba(255,255,255,0.05); }
  .CodeMirror-matchingbracket { color: #facc15 !important; font-weight: 700; }
</style>
<script src="/static/vendor/codemirror/lib/codemirror.js"></script>
<script src="/static/vendor/codemirror/addon/active-line.js"></script>
<script src="/static/vendor/codemirror/addon/matchbrackets.js"></script>
<script src="/static/vendor/codemirror/addon/closebrackets.js"></script>
<script src="/static/vendor/codemirror/mode/xml.js"></script>
<script src="/static/vendor/codemirror/mode/javascript.js"></script>
<script src="/static/vendor/codemirror/mode/clike.js"></script>
<script src="/static/vendor/codemirror/mode/yaml.js"></script>
<script src="/static/vendor/codemirror/mode/markdown.js"></script>
<script src="/static/vendor/codemirror/mode/properties.js"></script>
<script src="/static/vendor/codemirror/mode/shell.js"></script>
<script src="/static/vendor/codemirror/mode/css.js"></script>
<script src="/static/vendor/codemirror/mode/htmlmixed.js"></script>
<script src="/static/vendor/codemirror/mode/dockerfile.js"></script>
<script>
(function() {
  var ta = document.getElementById('file-content');
  var form = document.getElementById('file-form');
  var dirtyEl = document.getElementById('dirty-indicator');
  if (!ta || !form || !window.CodeMirror) return;

  var cm = CodeMirror.fromTextArea(ta, {
    mode: ${jsLit(cmMode)},
    theme: 'material-darker',
    lineNumbers: true,
    lineWrapping: false,
    indentUnit: 2,
    tabSize: 2,
    indentWithTabs: false,
    styleActiveLine: true,
    matchBrackets: true,
    autoCloseBrackets: true,
    extraKeys: {
      'Ctrl-S': function() { form.requestSubmit(); return false; },
      'Cmd-S':  function() { form.requestSubmit(); return false; },
      'Tab':    function(cm) {
        if (cm.somethingSelected()) cm.indentSelection('add');
        else cm.replaceSelection('  ', 'end');
      },
    },
  });
  var initial = cm.getValue();
  cm.on('change', function() {
    if (dirtyEl) dirtyEl.style.display = (cm.getValue() === initial) ? 'none' : '';
  });
  // submit 직전에 CodeMirror 의 값을 textarea 로 sync (fromTextArea 가 자동 sync 하지만
  // 명시적으로 한 번 더 — 일부 브라우저에서 안전).
  // v1.24.0 — `initial = ...` 갱신 제거. 서버 실패 시 dirty indicator 가 잘못 사라지던
  // 회귀. submit 후 redirect 가 페이지 reload → dirty state 자연스럽게 reset (성공 시).
  // 실패 시엔 redirect 의 ?err 와 함께 페이지 reload — dirty 비교는 다시 fresh load 값.
  form.addEventListener('submit', function() {
    cm.save();
  });

  // v0.54.0 — ?line=N 으로 들어오면 해당 라인으로 scroll + 잠시 강조.
  try {
    var sp = new URLSearchParams(location.search);
    var lineParam = parseInt(sp.get('line') || '0', 10);
    if (lineParam > 0) {
      cm.setCursor({ line: lineParam - 1, ch: 0 });
      cm.scrollIntoView({ line: lineParam - 1, ch: 0 }, 100);
      cm.addLineClass(lineParam - 1, 'background', 'cm-jump-highlight');
      setTimeout(function() {
        cm.removeLineClass(lineParam - 1, 'background', 'cm-jump-highlight');
      }, 1500);
    }
  } catch (e) { /* ignore */ }
})();
</script>
<style>
  .cm-jump-highlight { background: rgba(250, 204, 21, 0.15) !important; }
</style>
"""
        }

        val ok = "" // 라우트가 ok=saved query 를 붙이긴 하지만 본 view 에서는 별도 처리 안 함
        return AdminTemplates.shell(
            title = "${esc(p.name)} · ${esc(relPath)}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>${esc(t("fileView.heading"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

<div class="card" style="margin-bottom:12px">
  <div style="font-size:13px">$crumbs</div>
</div>

$ok
$bodyHtml
""",
            embed = embed,
        )
    }

    private fun renderBreadcrumbs(projectId: String, subPath: String, isFile: Boolean = false): String {
        val parts = subPath.split('/').filter { it.isNotEmpty() }
        val sb = StringBuilder()
        sb.append("""<a href="/projects/${esc(projectId)}/tree">📁 ${esc(projectId)}</a>""")
        var acc = ""
        for ((idx, part) in parts.withIndex()) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val isLast = idx == parts.size - 1
            sb.append(" / ")
            if (isLast && isFile) {
                sb.append("<strong>${esc(part)}</strong>")
            } else {
                sb.append("""<a href="/projects/${esc(projectId)}/tree?path=${acc.encodeUrl()}">${esc(part)}</a>""")
            }
        }
        return sb.toString()
    }

    private fun parentOf(relPath: String): String =
        relPath.substringBeforeLast('/', missingDelimiterValue = "")

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    /**
     * v0.15.0 — ProjectFileBrowser 의 mime guess 를 highlight.js 의 language 이름으로 매핑.
     * 미매칭은 plaintext (highlight 없이 그대로 표시).
     */
    private fun mapMimeToHljs(mime: String): String = when (mime) {
        "text/x-kotlin", "text/x-gradle" -> "kotlin"
        "text/x-java" -> "java"
        "text/xml" -> "xml"
        "application/json" -> "json"
        "text/yaml" -> "yaml"
        "text/markdown" -> "markdown"
        "text/x-properties" -> "properties"
        "text/x-shellscript" -> "bash"
        else -> "plaintext"
    }

    /**
     * v1.18.0 — ProjectFileBrowser.mimeGuess + 파일 확장자 → CodeMirror 5 mode name.
     * CodeMirror 가 자체 mode 인지 또는 MIME 등록을 가지며, 본 함수는 그 매핑을
     * 단순화 — addModeAlias 호출 없이 직접 mode 값 반환.
     *
     * 우리가 bundle 한 mode 파일: xml / javascript / clike (Java/Kotlin/C-family) /
     * yaml / markdown / properties / shell / css / htmlmixed / dockerfile. 그 외엔
     * `null` 반환하면 CodeMirror 가 plain text mode 로 동작.
     */
    private fun mapMimeToCodeMirror(mime: String, relPath: String): String {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return when {
            mime == "text/x-kotlin" || ext == "kt" || ext == "kts" -> "text/x-kotlin"
            mime == "text/x-gradle" || ext == "gradle" -> "text/x-kotlin"
            mime == "text/x-java" || ext == "java" -> "text/x-java"
            ext == "c" || ext == "cpp" || ext == "h" || ext == "hpp" -> "text/x-c++src"
            ext == "cs" -> "text/x-csharp"
            mime == "application/json" || ext == "json" -> "application/json"
            mime == "text/xml" || ext == "xml" -> "application/xml"
            ext == "html" || ext == "htm" -> "text/html"
            ext == "css" -> "text/css"
            ext == "js" || ext == "mjs" -> "text/javascript"
            ext == "ts" -> "text/typescript"
            mime == "text/yaml" || ext == "yml" || ext == "yaml" -> "text/x-yaml"
            mime == "text/markdown" || ext == "md" || ext == "markdown" -> "text/x-markdown"
            mime == "text/x-properties" || ext == "properties" -> "text/x-properties"
            mime == "text/x-shellscript" || ext == "sh" || ext == "bash" -> "text/x-sh"
            relPath.endsWith("Dockerfile", ignoreCase = true) || ext == "dockerfile" -> "text/x-dockerfile"
            else -> "text/plain"
        }
    }

    /** highlight.js 적용 최대 길이 — 그 이상이면 적용 skip (브라우저 freeze 방지). */
    private const val MAX_HL_CHARS = 200_000
}

