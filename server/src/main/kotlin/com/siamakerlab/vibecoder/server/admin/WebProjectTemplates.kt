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
    private fun renderInitialHistoryJson(
        rows: List<com.siamakerlab.vibecoder.server.repo.ConversationTurnRow>,
    ): String {
        if (rows.isEmpty()) return "[]"
        val maxContent = 4000
        val sb = StringBuilder("[")
        var first = true
        for (row in rows) {
            if (row.role == "usage") continue
            if (!first) sb.append(',')
            first = false
            val raw = row.content
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
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val errHtml = if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""
        val okHtml = if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""

        // v1.14.3 — 등록된 프로젝트 list: lastBuild / openConsole 컬럼 제거. name + package 만.
        // v1.14.4 — row 전체 영역이 클릭 가능 + 첫 화면 무조건 console (#console hash 명시).
        //           각 td 의 자식을 inherit color + block link 로 채워서 셀 어디든 클릭 통함.
        val rowsHtml = if (projects.isEmpty()) {
            """<tr><td colspan="2" class="dim">${esc(t("projects.list.empty"))}</td></tr>"""
        } else {
            projects.joinToString("\n") { p ->
                val href = "/projects/${esc(p.id)}#console"
                val cellLinkStyle = "display:block;color:inherit;text-decoration:none"
                """<tr class="row-link">
                    <td><a href="$href" style="$cellLinkStyle"><strong>${esc(p.name)}</strong><br><small class="dim">${esc(p.id)}</small></a></td>
                    <td><a href="$href" style="$cellLinkStyle"><code>${esc(p.packageName)}</code></a></td>
                  </tr>"""
            }
        }

        return AdminTemplates.shell(
            title = t("projects.title"),
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
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
    <h2>${esc(t("projects.list.title"))}</h2>
    <style>
      /* v1.14.4 — row 전체 영역 클릭 가능. cell padding 안에서도 hover 강조 / cursor 변경. */
      table.devices tr.row-link td { padding: 0; }
      table.devices tr.row-link td a { padding: 10px 12px; }
      table.devices tr.row-link:hover { background: #1a1f2c; cursor: pointer; }
    </style>
    <table class="devices">
      <thead>
        <tr><th>${esc(t("projects.list.col.name"))}</th><th>${esc(t("projects.list.col.package"))}</th></tr>
      </thead>
      <tbody>
        $rowsHtml
      </tbody>
    </table>
  </div>
</section>
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
"""
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
        lang: String,
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

        val navPath = if (isChat) "/chat" else "/projects"
        val titleSuffix = if (isChat) "General Chat" else t("console.title")
        val sideLinks = if (isChat) """
      <a href="/chat/history" class="chip chip-link">${esc(t("console.nav.history"))}</a>"""
        else """
      <a href="/projects/${esc(p.id)}/builds" class="chip chip-link">${esc(t("projects.detail.builds"))}</a>
      <a href="/projects/${esc(p.id)}/history" class="chip chip-link">${esc(t("console.nav.history"))}</a>
      <a href="/projects/${esc(p.id)}/tree" class="chip chip-link">${esc(t("console.nav.files"))}</a>
      <a href="/projects/${esc(p.id)}/git" class="chip chip-link">${esc(t("console.nav.git"))}</a>
      <a href="/projects/${esc(p.id)}/symbols" class="chip chip-link" title="${esc(t("console.nav.symbols.title"))}">${esc(t("console.nav.symbols"))}</a>
      <a href="/projects/${esc(p.id)}/agents" class="chip chip-link" title="${esc(t("console.nav.agents.title"))}">${esc(t("console.nav.agents"))}</a>"""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · $titleSuffix",
            username = username,
            currentPath = navPath,
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>$titleSuffix
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)}${if (isChat) "" else " (${esc(p.id)})"}</small>
  </h1>
</header>

$authBannerHtml

<div class="card" style="margin-bottom:16px">
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <div>
      <strong>${esc(t("console.session"))}</strong> $statusBadge
      ${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}
    </div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <!-- v0.98.0 — busy badge: 사용자가 prompt 보낸 후 Claude 가 응답 중인지 한눈에.
           v1.7.4 — 사용자 요구로 전송 버튼 라인 (hint 라벨 좌측) 으로 이동.
           id="busy-badge" 는 동일 — JS selector + CSS 모두 변경 없이 그대로 동작. -->
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
        #busy-badge[data-state="idle"] {
          background: rgba(255,255,255,0.06); color: var(--text-dim, #888);
        }
      </style>
      $sideLinks
      <button type="button" id="stop-btn" class="chip chip-danger" style="display:none"
              title="${esc(t("console.stop.title"))}">${esc(t("console.stop"))}</button>
      <form method="post" action="/projects/${esc(p.id)}/console/new" style="display:inline"
            onsubmit="return confirm('${esc(t("console.newSession.confirm")).replace("'", "&#39;")}')">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="chip chip-danger">${esc(t("console.newSession"))}</button>
      </form>
    </div>
  </div>
  <!--
    v0.75.0 — slash chip 제거. vibe-coder 의 콘솔은 `claude --print --output-format
    stream-json` non-interactive 모드라 Claude Code 의 interactive slash commands
    (`/status` / `/cost` / `/model` / `/memory` / `/plan` / `/compact` / `/clear`)
    가 동작하지 않음 — 그냥 prompt 텍스트로 처리되어 Claude 가 못 알아들음.
    `/status` 의 사용량/모델 정보는 우측 상단 status snapshot (ClaudeStatusService)
    이 별도로 표시. `/clear` 는 "새 세션" 버튼이 같은 역할.
  -->
</div>

<!-- v0.97.0 — 콘솔 메시지 필터. 엑셀 필터 스타일. mandatory 항목은 disabled. -->
<details id="console-filter" style="margin-bottom:6px;font-size:12px">
  <summary style="cursor:pointer;color:var(--text-dim);padding:4px 0;user-select:none">
    🔍 ${esc(t("console.filter.title"))} <span id="filter-summary" class="dim" style="font-size:11px"></span>
  </summary>
  <div style="padding:8px 10px;background:rgba(255,255,255,0.03);border:1px solid #2a2a2a;border-radius:6px;margin-top:4px">
    <p class="hint" style="margin:0 0 8px;font-size:11px">${esc(t("console.filter.hint"))}</p>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px">
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
      </div>
    </div>
    <div style="margin-top:6px;display:flex;justify-content:flex-end">
      <button type="button" id="filter-reset" class="chip chip-link" style="font-size:11px;padding:3px 10px">${esc(t("console.filter.reset"))}</button>
    </div>
  </div>
</details>

<!-- v1.20.0 — 자동 스크롤 모드 토글. ON 이면 새 메시지 도착 시 사용자 스크롤
     위치 무시하고 항상 최하단. OFF 면 v1.6.4 의 stick-to-bottom (사용자가
     하단에 있을 때만 따라감). 사용자 선호는 localStorage 영속, default ON.
     prompt 전송 직후엔 토글 무관 항상 스크롤 (sendPrompt 안에서 명시 호출). -->
<div style="display:flex;justify-content:flex-end;align-items:center;gap:6px;margin-bottom:4px">
  <label for="autoscroll-toggle" style="display:flex;align-items:center;gap:4px;font-size:11px;color:var(--text-dim);cursor:pointer;user-select:none"
         title="${esc(t("console.autoscroll.tip"))}">
    <input type="checkbox" id="autoscroll-toggle" style="margin:0">
    📌 ${esc(t("console.autoscroll"))}
  </label>
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
            style="font-size:12px;padding:3px 10px;border-radius:12px;font-weight:500;white-space:nowrap;flex-shrink:0">${esc(t("console.busy.idle"))}</span>
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
  <select id="agent-picker" style="flex:1;min-width:0;font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333" title="v0.41.0+ — Dispatch a registered sub-agent into the prompt">
    <option value="">${esc(t("console.agent.placeholder"))}</option>
  </select>
  <a href="/agents" class="chip chip-link" style="font-size:11px;margin-left:0;flex-shrink:0">${esc(t("console.agent.manage"))}</a>
</div>

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
  var OPTIONAL_CATS = ['tool_use', 'tool_result', 'session', 'done', 'replay', 'ws', 'todo'];
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
  logEl.addEventListener('scroll', function(){
    if (isAtBottom()) {
      setUnread(0);
      setJumpVisible(false);
    } else if (!jumpBtn || !jumpBtn.classList.contains('visible')) {
      setJumpVisible(true);
    }
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
    // v1.7.9 — meta (시각 + 복사 버튼) 를 응답 카드 내부 하단으로 이동.
    //          .log-content wrapper 안에 .log-body 위 + .log-meta 아래.
    row.innerHTML =
      '<span class="log-label">' + escHtml(label) + '</span>' +
      '<div class="log-content">' +
        '<div class="log-body">' + escHtml(body) + '</div>' +
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
        var txt = body == null ? '' : String(body);
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
    if (atBottom && row.style.display !== 'none') {
      logEl.scrollTop = logEl.scrollHeight;
    } else if (row.style.display !== 'none') {
      // 사용자가 위로 스크롤 중 — unread 카운트 + jump 버튼 표시.
      setUnread(unreadCount + 1);
      setJumpVisible(true);
    }
  }

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
    updateFilterSummary();
  })();

  // Claude 응답에 'Not logged in' 같은 인증 실패 패턴이 보이면 즉시 폼을 disable 하고
  // 빨간 배너를 띄운다. 진단 (EnvDiagnostics) 이 false positive 였을 때의 라이브 fallback.
  var AUTH_FAIL_RE = /(not logged in|please run \/login|invalid api key|unauthorized|authentication required)/i;
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

  // v0.13.0 — tool_use 도구별 친화적 렌더링. raw JSON 대신 읽기 쉬운 한 줄.
  function clip(s, n) {
    s = String(s == null ? '' : s);
    return s.length > n ? s.slice(0, n) + ' …(+' + (s.length - n) + ')' : s;
  }
  function renderToolUse(name, input) {
    var i = input || {};
    switch (name) {
      case 'Bash': {
        var cmd = i.command || '';
        var desc = i.description ? ' — ' + i.description : '';
        return { label: '$', body: clip(cmd, 400) + desc };
      }
      case 'Read': {
        var p = i.file_path || i.path || '';
        var range = (i.offset != null || i.limit != null)
          ? ' [' + (i.offset || 0) + ', +' + (i.limit || '?') + ']' : '';
        return { label: '📄 Read', body: p + range };
      }
      case 'Write': {
        var p2 = i.file_path || i.path || '';
        var sz = (i.content || '').length;
        return { label: '✏️ Write', body: p2 + ' (' + sz + ' chars)' };
      }
      case 'Edit': {
        var p3 = i.file_path || i.path || '';
        var oldS = clip(i.old_string || '', 80);
        var newS = clip(i.new_string || '', 80);
        var ra = i.replace_all ? ' [all]' : '';
        return { label: '✎ Edit' + ra, body: p3 + '\n  - ' + oldS + '\n  + ' + newS };
      }
      case 'Glob':
        return { label: '🔍 Glob', body: (i.pattern || '') + (i.path ? ' in ' + i.path : '') };
      case 'Grep':
        return { label: '🔎 Grep', body: '"' + clip(i.pattern || '', 80) + '"' +
          (i.path ? ' in ' + i.path : '') + (i.glob ? ' (' + i.glob + ')' : '') };
      case 'TaskCreate':
        return { label: '📋 TaskCreate', body: i.subject || i.description || '' };
      case 'TaskUpdate':
        return { label: '📋 TaskUpdate',
          body: 'id=' + (i.taskId || '?') + (i.status ? ' status=' + i.status : '') +
                (i.subject ? ' "' + i.subject + '"' : '') };
      case 'TodoWrite':
        var n = (i.todos || []).length;
        return { label: '📋 TodoWrite', body: n + ' todo(s)' };
      case 'WebSearch':
        return { label: '🌐 WebSearch', body: '"' + clip(i.query || '', 200) + '"' };
      case 'WebFetch':
        return { label: '🌐 WebFetch', body: i.url || '' };
      default: {
        var raw = typeof input === 'string' ? input : JSON.stringify(input || {});
        return { label: name || 'tool', body: clip(raw, 500) };
      }
    }
  }

  function renderFrame(f) {
    var t = f.type;
    if (t === 'console_session_started') {
      append('sys', 'session', 'started ' + (f.sessionId || '').slice(0,12) + (f.model ? ' · ' + f.model : ''), 'session');
    } else if (t === 'console_assistant') {
      append('assistant', 'assistant', f.text || '', 'assistant');
      detectAuthFailure(f.text);
    } else if (t === 'console_tool_use') {
      var rendered = renderToolUse(f.toolName, f.input);
      // v1.3.0 — task 계열 tool 은 별도 'todo' 카테고리로 분류해 콘솔/패널 양쪽에 반영.
      var isTodoTool = (f.toolName === 'TaskCreate' || f.toolName === 'TaskUpdate' || f.toolName === 'TodoWrite');
      append('tool', rendered.label, rendered.body, isTodoTool ? 'todo' : 'tool_use');
      if (isTodoTool) updateTodoStore(f.toolName, f.input);
    } else if (t === 'console_tool_result') {
      var out = typeof f.output === 'string' ? f.output : JSON.stringify(f.output);
      var resultLabel = f.isError ? 'tool-err' : '✓ result';
      append(f.isError ? 'tool-err' : 'tool-out', resultLabel,
             out.length > 500 ? out.slice(0,500) + ' …(+' + (out.length - 500) + ')' : out,
             'tool_result');
      detectAuthFailure(out);
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
      setInFlight(!!f.busy);
    } else if (t === 'console_replay_end') {
      append('sys', 'replay', 'history end — live frames follow', 'replay');
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
        // ConversationTurn 의 content 는 input JSON. renderToolUse 와 동일 형식 best-effort.
        var label = (r.tool || 'tool');
        var body = text.length > 500 ? text.slice(0, 500) + ' …(+' + (text.length - 500) + ')' : text;
        var isTodoTool = (label === 'TaskCreate' || label === 'TaskUpdate' || label === 'TodoWrite');
        append('tool', label, body, isTodoTool ? 'todo' : 'tool_use', opts);
      } else if (role === 'tool_result') {
        var out = text.length > 500 ? text.slice(0, 500) + ' …(+' + (text.length - 500) + ')' : text;
        append('tool-out', '✓ result', out, 'tool_result', opts);
      } else if (role === 'system') {
        append('sys', r.tool || 'system', text, 'system', opts);
      } else if (role === 'done') {
        append('sys', 'done', text || 'end_turn', 'done', opts);
      } else if (role === 'session') {
        append('sys', 'session', text, 'session', opts);
      }
    }
    append('sys', 'history', '— end of history, live frames follow —', 'replay');
    // 자동으로 최하단 정렬.
    logEl.scrollTop = logEl.scrollHeight;
  })();

  var ws = null;
  var wsAuthed = false;

  function connect() {
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/console/logs');

    ws.onopen = function() {
      append('sys', 'ws', 'connected', 'ws');
      // 인증은 WS handshake 의 cookie 헤더로 처리 (vibe_session 은 httpOnly 이므로
      // JS 가 읽지 못함 — XSS 방어). 서버가 handshake 시점에 cookie 에서 토큰을 추출.
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
      append('sys', 'ws', (${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.ws.reconnect5s", "___CODE___"))}).replace('___CODE___', ev.code), 'ws');
      setTimeout(connect, 5000);
    };

    ws.onerror = function() {
      append('err', 'ws', 'error', 'ws');
    };
  }

  connect();

  // v0.13.0 — 진행 중 turn cancel 버튼
  // v0.98.0 — busy badge 추가 — 응답중/대기중 시각화.
  // v0.99.0 — pendingPrompts 큐. busy 중에도 submit 허용, console_done 후 자동 발사.
  var stopBtn = document.getElementById('stop-btn');
  var busyBadge = document.getElementById('busy-badge');
  var BUSY_RESPONDING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.responding"))};
  var BUSY_IDLE = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.idle"))};
  var BUSY_QUEUED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.responding.queued", "___N___"))};
  var QUEUE_ADDED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.added", "___N___", "___PREVIEW___"))};
  var QUEUE_DRAINING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.draining"))};
  var QUEUE_CLEARED_TPL = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.queue.cleared", "___N___"))};
  var inFlight = false;
  var pendingPrompts = [];  // 큐: busy 중 submit 된 prompt 들.
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
    if ((ev.ctrlKey || ev.metaKey) && ev.key === 'Enter') {
      ev.preventDefault();
      form.requestSubmit();
    }
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
</script>
"""
        )
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
        /** v1.26.0 — packageName 매칭 키스토어 존재 여부. false 면 빌드 버튼 비활성화 + 안내. */
        keystoreReady: Boolean = true,
        flashErr: String? = null,
        flashOk: String? = null,
        csrf: String? = null,
        lang: String,
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
      <a href="/projects/${esc(p.id)}" class="primary-link" style="width:auto;display:inline-block;padding:6px 12px;background:transparent;border:1px solid var(--border);color:var(--text-dim)">${esc(t("builds.back"))}</a>
      <form method="post" action="/projects/${esc(p.id)}/builds" style="display:inline">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" class="primary" style="width:auto;padding:8px 16px"${if (!keystoreReady) " disabled title=\"${esc(t("builds.disabled.noKeystore"))}\"" else ""}>${esc(t("builds.queue"))}</button>
      </form>
    </div>
  </div>
  <p class="hint">${esc(t("builds.queueHint"))}</p>
  ${if (!keystoreReady) """
  <!-- v1.26.0 — 키스토어 미준비 시 빌드 차단 안내. 운영 정책: AGP 의 default
       debug.keystore 자동 생성도 허용 X (CLAUDE.md "키스토어 임의 생성 금지"). -->
  <div class="warn" style="margin-top:10px;padding:10px 12px;background:rgba(234,179,8,0.08);border:1px solid rgba(234,179,8,0.35);border-radius:6px">
    <strong>⚠ ${esc(t("builds.disabled.title"))}</strong>
    <p style="margin:4px 0 0;font-size:13px;line-height:1.5">${esc(t("builds.disabled.body"))}</p>
    <p style="margin:6px 0 0;font-size:12px">
      <a href="/settings/keystores" class="chip chip-link">${esc(t("builds.disabled.openKeystores"))}</a>
      <code style="margin-left:8px">${esc(p.packageName)}</code> ${esc(t("builds.disabled.expected"))}
    </p>
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
"""
        )
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
      <a href="/projects/${esc(p.id)}/builds" class="chip chip-link">${esc(t("build.detail.backToBuilds"))}</a>
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
"""
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
  <a href="/projects/${esc(p.id)}" class="chip chip-link">${esc(t("files.back"))}</a>
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
  <a href="/projects/${esc(p.id)}" class="chip chip-link">${esc(t("git.back"))}</a>
  <a href="/projects/${esc(p.id)}/console" class="chip chip-link">${esc(t("git.toConsole"))}</a>
</p>
<p class="hint">${t("git.bottomHint")}</p>
"""
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
                val actions = """<form method="post" action="/projects/${esc(p.id)}/files/rename"
                          style="display:inline" onsubmit="var v=prompt('${esc(renamePrompt)}',$renameJsLit); if(!v||v===$renameJsLit){return false;} this.newName.value=v;">
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
    <form method="post" action="/projects/${esc(p.id)}/files/upload?_csrf=${csrf?.encodeUrl() ?: ""}"
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
"""
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
"""
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

