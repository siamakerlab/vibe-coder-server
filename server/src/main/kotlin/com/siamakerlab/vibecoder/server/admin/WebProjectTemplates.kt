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
import com.siamakerlab.vibecoder.shared.dto.ProjectState

/**
 * 프로젝트 / 콘솔 / 빌드 SSR 템플릿. v0.5.0 Phase 2 추가.
 *
 * 안드로이드 앱 없이도 브라우저만으로 프로젝트 등록 -> Claude 프롬프트 ->
 * Gradle 빌드 -> APK 다운로드까지 완결되도록 하는 화면들.
 *
 * AdminTemplates.kt 와 동일한 `shell()` 레이아웃 셸 + admin.css 를 공유한다.
 */
object WebProjectTemplates {

    /**
     * v1.132.1 — 짝 없는 UTF-16 surrogate 를 U+FFFD 로 치환. DB content 절단 등으로
     * lone surrogate 가 섞인 문자열이 respondText 의 UTF-8 인코딩(엄격/REPORT)을
     * MalformedInputException 으로 터뜨려 페이지 전체가 500 이 되는 것을 방지하는 방어선.
     */
    private fun String.sanitizeSurrogates(): String {
        if (none { it.isSurrogate() }) return this
        val sb = StringBuilder(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                    sb.append(c); sb.append(this[i + 1]); i += 2
                }
                c.isSurrogate() -> { sb.append('\uFFFD'); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun esc(s: String?): String =
        s.orEmpty()
            .sanitizeSurrogates()
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
    private fun jsLit(s0: String?): String {
        if (s0 == null) return "null"
        val s = s0.sanitizeSurrogates()
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

    internal fun renderInitialHistoryJson(
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
            // v1.133.0 — 이미지 메타: base64 는 inline 에 싣지 않고(페이지 비대 + 절단 깨짐)
            // mediaType 목록만 emit. 클라이언트가 /claude/console/image?turn=N&idx=M 로 로드.
            var imageTypes: List<String> = emptyList()
            val raw = when {
                row.role == "unknown" &&
                    row.content.contains("\"type\":\"thinking\"") &&
                    row.content.contains("\"thinking\":\"\"") ->
                    "{\"type\":\"thinking\",\"thinking\":\"\"}"
                (row.role == "tool_result" || row.role == "tool_result_error") &&
                    com.siamakerlab.vibecoder.server.claude.ConsoleImages.toolResultMayContainImage(row.content) -> {
                    val (cleaned, types) =
                        com.siamakerlab.vibecoder.server.claude.ConsoleImages.stripToolResultImages(row.content)
                    imageTypes = types
                    cleaned
                }
                else -> {
                    if (row.role == "user") {
                        imageTypes = com.siamakerlab.vibecoder.server.claude.ConsoleImages
                            .fromUserRaw(row.raw).map { it.mediaType }
                    }
                    row.content
                }
            }
            val text = if (raw.length > maxContent) {
                // surrogate pair(이모지 등) 중간 절단 방지: 경계 char 가 high surrogate 면
                // 한 칸 당겨 자른다. 짝 없는 surrogate 가 남으면 respondText 의 UTF-8
                // 인코딩이 MalformedInputException 으로 터져 페이지 전체가 500 이 된다.
                val end = if (Character.isHighSurrogate(raw[maxContent - 1])) maxContent - 1 else maxContent
                raw.substring(0, end) + " …(+${raw.length - end})"
            } else raw
            sb.append('{')
            sb.append("\"role\":").append(jsLit(row.role))
            sb.append(",\"text\":").append(jsLit(text))
            if (row.toolName != null) sb.append(",\"tool\":").append(jsLit(row.toolName))
            if (row.ts.isNotBlank()) sb.append(",\"ts\":").append(jsLit(row.ts))
            // v1.129.0 — "더보기" 페이지네이션 키(이보다 작은 turnIdx 를 과거로 로드).
            sb.append(",\"turnIdx\":").append(row.turnIdx)
            // v1.133.0 — 이미지 메타 (mediaType 등장 순서 = 서빙 idx).
            if (imageTypes.isNotEmpty()) {
                sb.append(",\"images\":[")
                imageTypes.forEachIndexed { i, mt ->
                    if (i > 0) sb.append(',')
                    sb.append("{\"mediaType\":").append(jsLit(mt)).append('}')
                }
                sb.append(']')
            }
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
    ${com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "build.compare.desc", esc(cmp.previous.id.take(12)), esc(AdminTemplates.fmtTs(cmp.previous.createdAt, lang)))}
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

    /**
     * v1.128.0 — clone 프로젝트 타입 불일치 확인 페이지. 사용자 선택([selected]: kotlin/flutter)과
     * 서버 감지([detected])가 다를 때(예: Kotlin 선택했지만 루트 pubspec.yaml 감지) 표시한다.
     * 두 버튼: 감지값 수용 / 선택값 강제 — 둘 다 `projectTypeAck=true` 로 재제출(직전 clone 재사용).
     */
    fun projectTypeMismatchPage(
        username: String,
        projectId: String,
        appName: String,
        packageName: String,
        cloneUrl: String?,
        cloneBranch: String?,
        selected: String,
        detected: String,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val tArgs = { key: String, a: String, b: String ->
            com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key, a, b)
        }
        fun typeLabel(x: String): String = if (x == "flutter") "Flutter" else "Kotlin"
        val hidden = """${CsrfTokens.hiddenInput(csrf)}
      <input type="hidden" name="sourceType" value="clone">
      <input type="hidden" name="projectId" value="${esc(projectId)}">
      <input type="hidden" name="appName" value="${esc(appName)}">
      <input type="hidden" name="packageName" value="${esc(packageName)}">
      <input type="hidden" name="cloneUrl" value="${esc(cloneUrl.orEmpty())}">
      <input type="hidden" name="cloneBranch" value="${esc(cloneBranch.orEmpty())}">
      <input type="hidden" name="projectTypeAck" value="true">"""
        return AdminTemplates.shell(
            title = t("projects.mismatch.title"),
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header><h1>${esc(t("projects.mismatch.title"))}</h1></header>
<div class="card" style="border-color:var(--warn)">
  <p style="margin:0 0 6px"><strong style="color:var(--warn)">${esc(t("projects.mismatch.heading"))}</strong></p>
  <p style="margin:0 0 14px;line-height:1.6">${esc(tArgs("projects.mismatch.body", typeLabel(selected), typeLabel(detected)))}</p>
  <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
    <form method="post" action="/projects" style="display:inline">
      $hidden
      <input type="hidden" name="projectType" value="${esc(detected)}">
      <button type="submit" class="primary" style="padding:10px 18px">${esc(tArgs("projects.mismatch.useDetected", typeLabel(detected), ""))}</button>
    </form>
    <form method="post" action="/projects" style="display:inline">
      $hidden
      <input type="hidden" name="projectType" value="${esc(selected)}">
      <button type="submit" style="padding:10px 18px">${esc(tArgs("projects.mismatch.forceSelected", typeLabel(selected), ""))}</button>
    </form>
    ${AdminTemplates.backButton("/projects", t("projects.mismatch.cancel"))}
  </div>
  <p class="hint" style="margin-top:12px;font-size:12px">${esc(t("projects.mismatch.note"))}</p>
</div>
""",
        )
    }

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
            """<tr><td colspan="5" class="dim">${esc(t("projects.list.empty"))}</td></tr>"""
        } else {
            projects.joinToString("\n") { p ->
                val href = "/projects/${esc(p.id)}#console"
                val cellLinkStyle = "display:block;color:inherit;text-decoration:none"
                // v1.53.0 — 제일 왼쪽 상태칩. data-pid 로 WS patch 대상 식별, data-state 로 색 분기.
                val state = statuses[p.id] ?: ProjectState.READY.wire
                val chip = """<span class="pstat pstat-$state" data-pid="${esc(p.id)}" data-state="$state">${esc(t("projects.status.$state"))}</span>"""
                // v1.64.0 — 앱 아이콘(없으면 placeholder vibe-coder 아이콘) + 이름 우측 버전.
                val iconSrc = if (appIcons[p.id] == true) "/projects/${p.id.encodeUrlSeg()}/app-icon" else "/static/icon.png"
                val verBadge = versions[p.id]?.takeIf { it.isNotBlank() }
                    ?.let { ver ->
                        // v1.128.7 — versionName 에 이미 v/V 접두가 있으면 중복 'v' 를 붙이지 않음.
                        val label = if (ver.startsWith("v") || ver.startsWith("V")) ver else "v$ver"
                        """ <span class="proj-ver">${esc(label)}</span>"""
                    } ?: ""
                // v1.128.1 — 패키지명 우측 프로젝트 타입 뱃지(Kotlin/Flutter). Flutter=브랜드 블루, Kotlin=퍼플.
                val typeBadge = run {
                    val isFlutter = p.projectType == "flutter"
                    val label = if (isFlutter) "Flutter" else "Kotlin"
                    val bg = if (isFlutter) "#02569B" else "#7F52FF"
                    """<span style="margin-left:8px;font-size:10px;font-weight:600;padding:2px 7px;border-radius:4px;background:$bg;color:#fff;vertical-align:middle;white-space:nowrap">$label</span>"""
                }
                """<tr class="row-link proj-row" data-pid="${esc(p.id)}">
                    <td><a href="$href" style="$cellLinkStyle">$chip</a></td>
                    <td><a href="$href" style="$cellLinkStyle;display:flex;align-items:center;gap:10px">
                        <img class="proj-icon" src="$iconSrc" alt="" loading="lazy"
                             onerror="this.onerror=null;this.src='/static/icon.png'">
                        <span style="min-width:0"><strong>${esc(p.name)}</strong>$verBadge<br><small class="dim">${esc(p.id)}</small></span>
                      </a></td>
                    <td><a href="$href" style="$cellLinkStyle"><code>${esc(p.packageName)}</code></a></td>
                    <td style="text-align:right;white-space:nowrap">$typeBadge</td>
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

      <!-- v1.127.0 — 프로젝트 타입(Kotlin/Flutter). empty/clone 공통. 기본 Kotlin. 둘 다 Android 빌드 타깃. -->
      <fieldset style="border:1px solid #333;padding:10px;border-radius:6px;margin-top:10px">
        <legend style="padding:0 6px;font-size:13px">${esc(t("projects.new.projectType"))}</legend>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer">
          <input type="radio" name="projectType" value="kotlin" checked>
          <span><strong>${esc(t("projects.new.typeKotlin"))}</strong>${esc(t("projects.new.typeKotlinDesc"))}</span>
        </label>
        <label style="display:flex;gap:8px;align-items:center;cursor:pointer;margin-top:6px">
          <input type="radio" name="projectType" value="flutter">
          <span><strong>${esc(t("projects.new.typeFlutter"))}</strong>${esc(t("projects.new.typeFlutterDesc"))}</span>
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
      <span style="flex:1"></span>
      <!-- v1.136.0 — 프롬프트 일괄 보내기 (공용 모달 vb-bcast-modal). '페이지당' 좌측. -->
      <button type="button" class="vb-bcast-open"
              style="padding:6px 12px;border-radius:7px;border:1px solid #3a3a3a;background:#222;color:var(--text);cursor:pointer;font-size:13px">
        📢 ${esc(t("console.broadcast.open"))}</button>
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

${BroadcastModalTemplate.render(lang)}

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
                    <td>${esc(fmtInstant(b.startedAt, lang))}</td>
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
      <dt>${esc(t("projects.detail.updated"))}</dt><dd>${esc(AdminTemplates.fmtTs(p.updatedAt, lang))}</dd>
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
        /** v1.129.0 — history(DB·과거) ↔ WS(ring·미래) 경계 seq. inline JS 가 WS 연결 `since` 로
         *  사용해 ring 의 과거 프레임(history 와 중복)을 받지 않게 한다. 0 = 경계 없음(전체 replay). */
        initialMaxSeq: Long = 0L,
        /**
         * v1.54.0 — ChatGPT 스타일 다중 채팅. non-null 이면 좌측에 채팅 목록 사이드바를
         * 두고 기존 콘솔 본문을 우측 메인 영역으로 감싼다 (isChat 전용).
         */
        chatSidebar: String? = null,
        /** v1.54.0 — 활성 채팅 표시 제목 (헤더). null 이면 "General Chat". */
        chatTitle: String? = null,
        /** v1.106.0 — 현재 적용 모델(빈 문자열=CLI 기본). 헤더 셀렉터 초기값. */
        model: String = "",
        /** v1.106.0 — 직전 turn 컨텍스트(cache_read) 토큰. 0=미측정. */
        contextTokens: Long = 0,
        /** v1.106.1 — 직전 turn input(비캐시) 토큰(미터 세그먼트). */
        contextInputTokens: Long = 0,
        /** v1.106.1 — 직전 turn cache_creation 토큰(미터 세그먼트). */
        contextCacheCreationTokens: Long = 0,
        /** v1.106.1 — 컨텍스트 윈도우 한도(미터 분모). 0=미측정(미터 숨김). */
        contextLimit: Long = 0,
        /** v1.106.0 — 컨텍스트 경고 임계(토큰). 0=비활성. */
        contextWarnTokens: Int = 0,
        /** v1.106.0 (P1-a) — MCP 최소화(strict) 활성 여부. */
        mcpStrict: Boolean = false,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val statusBadge = when {
            isAlive -> """<span class="ok">running</span>"""
            sessionId != null -> """<span class="dim">idle (will resume)</span>"""
            else -> """<span class="dim">no session</span>"""
        }
        // v1.106.0 — 모델 셀렉터(토큰 사용량 레버). 변경 시 즉시 submit → 유휴면 다음 prompt 부터
        // 같은 대화를 새 모델로 resume. 표준 3종 + CLI 기본. 커스텀 ID 면 별도 옵션으로 표시.
        val knownModels = listOf(
            "sonnet" to "Sonnet (권장·저비용)",
            "opus" to "Opus (고성능·고비용)",
            "fable" to "Fable 5 (최상위·Opus 2배 비용)",
            "haiku" to "Haiku (초저비용)",
        )
        val isCustomModel = model.isNotBlank() && knownModels.none { it.first.equals(model, ignoreCase = true) }
        val modelOptions = buildString {
            for ((v, label) in knownModels) {
                val selAttr = if (model.equals(v, ignoreCase = true)) " selected" else ""
                append("""<option value="$v"$selAttr>${esc(label)}</option>""")
            }
            val defSel = if (model.isBlank()) " selected" else ""
            append("""<option value="default"$defSel>CLI 기본</option>""")
            if (isCustomModel) append("""<option value="${esc(model)}" selected>${esc(model)}</option>""")
        }
        val modelSelectorHtml = """
    <form method="post" action="/projects/${esc(p.id)}/console/model" style="display:inline-flex;align-items:center;margin:0" id="model-form">
      ${CsrfTokens.hiddenInput(csrf)}
      <select name="model" title="Claude 모델 — Sonnet 가 Opus 대비 토큰 사용량 약 1/5. 변경은 다음 prompt 부터 같은 대화에 적용."
              onchange="document.getElementById('model-form').submit()"
              style="font-size:12px;line-height:1.6;padding:4px 8px;height:30px;box-sizing:border-box;vertical-align:middle;background:#1a1a1a;color:var(--text);border:1px solid #333;border-radius:8px;cursor:pointer">
        $modelOptions
      </select>
    </form>"""
        // v1.106.1 — 컨텍스트 점유율 그래픽 미터(프롬프트 히스토리 상단 상시). 직전 turn
        // usage 분해(cache_read=재사용 / cache_creation=신규캐시 / input=비캐시)를 윈도우
        // 한도 대비 스택 바로 표시. console_context_usage 프레임으로 live 갱신. Claude CLI
        // /context 와 유사한 점유/사용/남음 시각화(카테고리 분해는 stream-json 미노출).
        val ctxInitiallyHidden = if (contextTokens <= 0 && contextInputTokens <= 0 && contextCacheCreationTokens <= 0) "hidden" else ""
        val contextMeterHtml = """
<div id="ctx-meter" class="ctx-meter" $ctxInitiallyHidden
     title="대화 컨텍스트 점유율 — 윈도우 한도 대비 사용/남음. 클수록 매 turn 비용↑. '새 세션' 으로 리셋.">
  <div class="ctx-meter-row">
    <span class="ctx-meter-label">컨텍스트</span>
    <div class="ctx-meter-bar">
      <div class="ctx-seg ctx-seg-read"></div>
      <div class="ctx-seg ctx-seg-create"></div>
      <div class="ctx-seg ctx-seg-input"></div>
    </div>
    <span class="ctx-meter-text">
      <b id="ctx-used">–</b> / <span id="ctx-limit">–</span>
      (<span id="ctx-pct">0%</span>) · 남음 <span id="ctx-free">–</span>
    </span>
  </div>
  <div class="ctx-legend">
    <span><i class="ctx-dot ctx-seg-read"></i>재사용</span>
    <span><i class="ctx-dot ctx-seg-create"></i>신규캐시</span>
    <span><i class="ctx-dot ctx-seg-input"></i>입력</span>
    <span><i class="ctx-dot ctx-dot-free"></i>남음</span>
  </div>
</div>"""
        // v1.106.0 (P1-a) — MCP 최소화 토글(전역 5개 MCP 툴 스키마를 빼 캐시 프리픽스 축소).
        val mcpStrictChecked = if (mcpStrict) "checked" else ""
        val mcpStrictHtml = """
    <form method="post" action="/projects/${esc(p.id)}/console/mcp-strict" style="display:inline-flex;align-items:center;margin:0" id="mcp-strict-form">
      ${CsrfTokens.hiddenInput(csrf)}
      <input type="hidden" name="enabled" value="${if (mcpStrict) "false" else "true"}">
      <label title="전역 MCP(playwright 등 5종) 툴 스키마를 빼 매 turn 토큰을 줄입니다. 프로젝트 .mcp.json 의 서버만 사용."
             style="margin:0;font-size:12px;line-height:1;height:30px;box-sizing:border-box;padding:0 10px;color:var(--text-dim,#888);cursor:pointer;display:inline-flex;align-items:center;gap:5px;border:1px solid #333;border-radius:8px;background:var(--bg)">
        <input type="checkbox" $mcpStrictChecked onchange="document.getElementById('mcp-strict-form').submit()"
               style="cursor:pointer;margin:0;flex:0 0 auto;width:13px;height:13px">MCP 최소화
      </label>
    </form>"""
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

        // v1.109.0 — 프롬프트 자동화 UI 를 부모 오버뷰 rail(ProjectTabsTemplate, 메모 카드 위)로
        // 이동했다(사용자 요청 — 별개 기능으로 분리, 메인 프롬프트 textarea 와 무관). 콘솔 인라인
        // 패널/JS 는 제거하고, 진행 프레임(automation_progress)만 부모로 postMessage 포워딩한다(WS 핸들러).
        // REST(/claude/automation/*) 계약은 그대로 — 부모 rail 이 직접 호출 + status 폴링.
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
  /* v1.111.0 — 백그라운드 작업 패널 CSS 는 부모 rail(ProjectTabsTemplate)로 이동. */
  /* v1.106.1 — 컨텍스트 점유율 미터(상시 — 비임베드 standalone 콘솔에서만 렌더) */
  .ctx-meter { margin:0 0 8px; padding:6px 10px; border:1px solid #1f2330; border-radius:8px; background:#0d1018; }
  .ctx-meter[hidden] { display:none; }
  .ctx-meter-row { display:flex; align-items:center; gap:8px; }
  .ctx-meter-label { font-size:11px; color:var(--text-dim,#888); font-weight:600; flex:0 0 auto; }
  .ctx-meter-bar { flex:1 1 auto; height:8px; border-radius:5px; background:#1f2330; overflow:hidden; display:flex; }
  .ctx-seg { height:100%; width:0; transition:width 0.3s ease; }
  .ctx-seg-read { background:#3a82f6; }
  .ctx-seg-create { background:#2dd4bf; }
  .ctx-seg-input { background:#ffb86b; }
  .ctx-meter-text { font-size:11px; color:var(--text-dim,#888); flex:0 0 auto; font-family:ui-monospace,Menlo,monospace; white-space:nowrap; }
  .ctx-meter.warn .ctx-meter-text { color:#ffb86b; }
  .ctx-meter.warn .ctx-meter-bar { box-shadow:0 0 0 1px rgba(255,184,107,0.45) inset; }
  .ctx-legend { display:flex; gap:12px; margin-top:5px; font-size:10px; color:var(--text-dim,#888); flex-wrap:wrap; }
  .ctx-legend span { display:inline-flex; align-items:center; gap:4px; }
  .ctx-dot { width:8px; height:8px; border-radius:2px; display:inline-block; }
  .ctx-dot-free { background:#1f2330; border:1px solid #333; }
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
  /* v1.133.0 — 콘솔 이미지 썸네일 (tool_result 이미지 / 프롬프트 첨부). 클릭으로 확대 토글. */
  .log-images { display:flex; flex-wrap:wrap; gap:8px; margin-top:8px; }
  .log-images .log-img { max-height:200px; max-width:min(100%, 360px); border:1px solid #2a2a2a;
    border-radius:8px; cursor:zoom-in; background:#111; object-fit:contain; }
  .log-images .log-img.expanded { max-height:none; max-width:100%; cursor:zoom-out; }
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
    <!-- v1.110.0 — 세션 표시 + 모델 셀렉터 + MCP 최소화 토글은 콘솔 하단 바(메시지 필터 좌측)로
         이동했다(사용자 요청). 헤더 우측에는 외부 링크/중지/새 세션만 남긴다. -->
    $sideLinks
    <button type="button" id="stop-btn" class="chip chip-danger" style="display:none;height:30px;box-sizing:border-box;align-items:center"
            title="${esc(t("console.stop.title"))}">${esc(t("console.stop"))}</button>
    <form method="post" action="/projects/${esc(p.id)}/console/new" style="display:inline-flex;align-items:center;margin:0"
          onsubmit="return confirm('${esc(t("console.newSession.confirm")).replace("'", "&#39;")}')">
      ${CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="chip chip-danger" style="height:30px;box-sizing:border-box;display:inline-flex;align-items:center">${esc(t("console.newSession"))}</button>
    </form>
  </div>
</header>

$authBannerHtml

<!-- v1.108.2 — 콘솔 메시지 필터 버튼 + 자동 스크롤 토글을 콘솔 하단(응답중 스피너와
     같은 줄, 우측 정렬)으로 이동. 트리거 버튼/토글 markup 은 아래 .console-bottom-bar
     로 옮겼다. 필터 모달(#filter-modal)과 그 스타일은 위치 독립적(fixed)이라 그대로 둔다.
     필터 체크박스 로직(.filter-cb / #filter-summary / #filter-reset)도 변동 없음. -->
<style>
  /* v1.134.1 — #image-modal(이미지 첨부 다이얼로그)도 같은 모달 스타일 공유. */
  #filter-modal, #image-modal { position:fixed; inset:0; z-index:50; display:flex; align-items:center; justify-content:center; background:rgba(0,0,0,0.55); padding:16px; }
  #filter-modal[hidden], #image-modal[hidden] { display:none; }
  #filter-modal .filter-box, #image-modal .filter-box { background:#15171c; border:1px solid #2a2a2a; border-radius:10px; padding:16px 18px; width:100%; max-width:520px; max-height:85vh; overflow-y:auto; box-shadow:0 12px 40px rgba(0,0,0,0.5); box-sizing:border-box; }
  #filter-modal .filter-head, #image-modal .filter-head { display:flex; align-items:center; justify-content:space-between; margin-bottom:8px; }
  #filter-modal .filter-head strong, #image-modal .filter-head strong { font-size:14px; }
  #filter-modal .filter-x, #image-modal .filter-x { background:transparent; border:0; color:var(--text-dim); font-size:16px; cursor:pointer; line-height:1; padding:2px 6px; }
  #filter-modal .filter-x:hover, #image-modal .filter-x:hover { color:var(--text); }
</style>

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
        <label style="display:block;padding:2px 0;cursor:pointer"><input type="checkbox" class="filter-cb" data-cat="usage" checked> ${esc(t("console.filter.cat.usage"))}</label>
      </div>
    </div>
    <div style="margin-top:12px;display:flex;justify-content:flex-end;gap:8px">
      <button type="button" id="filter-reset" class="chip chip-link" style="font-size:11px;padding:3px 10px">${esc(t("console.filter.reset"))}</button>
      <button type="button" id="filter-done" class="chip" style="font-size:11px;padding:3px 12px">${esc(t("memos.close"))}</button>
    </div>
  </div>
</div>

<!-- v1.134.1 — 이미지 첨부 다이얼로그. 입력창 우측 아이콘 클릭으로 열림(인라인 파일선택
     박스/미리보기 strip 제거 → 우측 공간 컴팩트). 미리보기·개별 제거·파일 선택을 여기서. -->
<div id="image-modal" hidden>
  <div class="filter-box" role="dialog" aria-modal="true" aria-label="${esc(t("console.image.dialogTitle"))}">
    <div class="filter-head">
      <strong>📷 ${esc(t("console.image.dialogTitle"))}</strong>
      <button type="button" id="image-close" class="filter-x" aria-label="${esc(t("memos.close"))}">✕</button>
    </div>
    <p class="hint" style="margin:0 0 10px;font-size:11px">${esc(t("console.image.dialogHint"))}</p>
    <div id="image-empty" class="dim" style="font-size:12px;padding:4px 0 8px">${esc(t("console.image.empty"))}</div>
    <div id="image-preview" style="display:none;gap:8px;flex-wrap:wrap;margin-bottom:10px"></div>
    <div style="display:flex;justify-content:space-between;gap:8px">
      <button type="button" id="image-pick" class="chip" style="font-size:11px;padding:4px 12px">${esc(t("console.image.pick"))}</button>
      <button type="button" id="image-done" class="chip" style="font-size:11px;padding:4px 12px">${esc(t("memos.close"))}</button>
    </div>
  </div>
</div>

<!-- v1.106.1/.2 — 컨텍스트 점유율 미터. 임베드(ProjectTabs)에선 부모 우측 오버뷰 rail 에
     표시(postMessage)하므로 인라인은 비임베드(standalone/chat)에서만 렌더. -->
${if (embed) "" else contextMeterHtml}

<!-- v1.6.4 — 스크롤 + 우하단 jump-to-bottom 버튼 wrapper. -->
<div class="console-log-wrap">
  <div id="console-log" class="console-log" aria-live="polite">
    <!-- v1.129.1 — "더보기"를 콘솔 스크롤 영역의 첫 자식으로. 스크롤 콘텐츠의 일부라 최상단으로
         올렸을 때만 보이고(아래로 내려가면 화면 밖), 클릭 시 과거 30개를 이 버튼 다음에 prepend.
         oldestTurnIdx>0(더 과거 있음)일 때만 display. -->
    <button type="button" id="console-load-more"
            style="display:none;width:100%;padding:7px;margin:0 0 6px;font-size:12px;background:#1a1a1a;color:var(--text-dim);border:1px solid #333;border-radius:6px;cursor:pointer">
      ↑ ${esc(t("console.loadMore"))}
    </button>
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

<!-- v1.112.0 — 레이아웃 재배치(사용자 요청): 메시지 영역 바로 아래에 입력창이 오도록,
     기존에 입력창 위에 있던 빠른프롬프트(quickBar)와 도구바(console-bottom-bar)를 모두
     입력창 하단으로 이동했다. 순서: 메시지 → 입력창 → 빠른프롬프트 → 템플릿/에이전트 → 도구바. -->
<form id="prompt-form" class="prompt-form" autocomplete="off">
  <!-- maxlength 는 char 단위라 ASCII 기준 32K. 한국어 등 multi-byte 입력은
       실제 UTF-8 byte 가 32K 를 넘으면 서버에서 prompt_too_large (400) 로 거절. -->
  <!-- v1.134.1 — 첨부 미리보기 strip 은 이미지 다이얼로그(#image-modal) 안으로 이동. -->
  <!-- v1.16.1 — textarea + voice/send 버튼을 동일 row 에 가로 배치. send 가
       textarea 의 우측 (사용자 요청). 버튼들은 column flex 로 stack, 하단 정렬. -->
  <div style="display:flex;gap:8px;align-items:stretch">
    <textarea id="prompt-input" rows="${if (starterPrompt != null) 8 else 3}" maxlength="32768"
              placeholder="${esc(if (blocking) t("console.input.disabled") else t("console.input.placeholder")).replace("\n", "&#10;")}"
              style="flex:1;width:auto;min-width:0"
              ${if (blocking) "disabled" else "required"}>${esc(starterPrompt)}</textarea>
    <div style="display:flex;flex-direction:column;gap:6px;justify-content:flex-end;flex-shrink:0">
      <!-- v1.15.0 — Web Speech API 음성 입력. 미지원 브라우저는 voice-input.js 가 자동 hide. -->
      <!-- v1.108.4 — 아이콘을 이모지(🎤) → Google Material 'mic' 인라인 SVG 로 교체(사용자 요청).
           외부 CDN 미사용(§3) 위해 path 직접 인라인. 녹음 중 상태는 voice-input.js 의 .listening
           클래스(빨강+pulse)로 표시하므로 textContent 이모지 스왑은 제거. -->
      <button type="button" id="voice-btn" hidden
              data-title-start="${esc(t("console.voice.start"))}"
              data-title-stop="${esc(t("console.voice.stop"))}"
              title="${esc(t("console.voice.start"))}"
              style="width:auto;padding:8px 12px;background:#1a1a1a;color:var(--text);border:1px solid #2a2a2a;border-radius:6px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;line-height:0"
              ${if (blocking) "disabled" else ""}><svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5-3c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg></button>
      <!-- v1.133.0 — 이미지 첨부 (Lucide 'image' 아이콘 인라인 — 외부 CDN 미사용 §3).
           v1.134.1 — 클릭 시 첨부 다이얼로그(#image-modal). 첨부 수 배지(#image-count). -->
      <button type="button" id="image-btn"
              title="${esc(t("console.image.attach"))}" aria-label="${esc(t("console.image.attach"))}"
              style="position:relative;width:auto;padding:8px 12px;background:#1a1a1a;color:var(--text);border:1px solid #2a2a2a;border-radius:6px;cursor:pointer;display:inline-flex;align-items:center;justify-content:center;line-height:0"
              ${if (blocking) "disabled" else ""}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/></svg><span id="image-count" style="display:none;position:absolute;top:-6px;right:-6px;min-width:16px;height:16px;line-height:16px;padding:0 4px;font-size:10px;font-weight:600;text-align:center;border-radius:8px;background:#1e40af;color:#fff;box-sizing:border-box"></span></button>
      <!-- admin.css 의 input[type=file]{display:block}(0,1,1)이 [hidden](0,1,0)을 이겨
           파일선택 박스가 노출되던 문제 → inline display:none 으로 확실히 숨김(v1.134.1). -->
      <input type="file" id="image-file" accept="image/png,image/jpeg,image/webp,image/gif" multiple style="display:none">
      <!-- v1.112.0 — "끼어들기": 진행 중 turn 을 interrupt 로 중단하고 입력창 내용을 즉시
           새 prompt 로 보낸다(TUI Esc+입력 동형).
           v1.139.0 — 상시 노출 + 게이트 만석 시 한도 무시 강제 전송 겸용("지금 당장"). -->
      <button type="button" id="interrupt-btn" class="chip chip-danger"
              style="display:inline-flex;width:auto;padding:8px 12px;white-space:nowrap;justify-content:center"
              title="${esc(t("console.interrupt.title"))}"
              ${if (blocking) "disabled" else ""}>${esc(t("console.interrupt"))}</button>
      <button type="submit" class="primary" id="send-btn" style="width:auto;padding:8px 16px;white-space:nowrap" ${if (blocking) "disabled" else ""}>${esc(t("console.input.send"))}</button>
    </div>
  </div>
  <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px;gap:8px;flex-wrap:wrap">
    <!-- v1.7.4 — busy 뱃지 + hint 라벨 한 줄. busy 뱃지가 좌측 끝, 그 다음 hint. -->
    <div style="display:flex;align-items:center;gap:8px;min-width:0;flex:1">
      <!-- v1.108.4 — busy-badge 는 항상 숨김(사용자 요청). turn 상태는 콘솔 하단 '응답중'
           스피너 + 부모 탭 헤더(#console-busy-badge, console:busy postMessage)로 노출되므로
           힌트 라벨 좌측의 중복 칩은 제거. 단 JS(updateBusyBadge·부모 미러)가 dataset/text 를
           계속 읽으므로 element/id 는 유지하고 display 만 끈다. -->
      <span id="busy-badge" data-state="ready"
            style="display:none;font-size:12px;padding:3px 10px;border-radius:12px;font-weight:500;white-space:nowrap;flex-shrink:0">${esc(t("console.busy.idle"))}</span>
      <small class="dim" style="min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(if (blocking) t("console.input.blockedHint") else t("console.input.hint"))}</small>
    </div>
  </div>
</form>
<script src="/static/voice-input.js" defer></script>
<script src="/static/prompt-templates.js?v=1.137.4" defer></script>
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

<!-- v1.112.0 — 빠른프롬프트 바: 입력창 하단으로 이동(기존엔 입력창 위). -->
$quickBarHtml

<!-- v1.6.3 — 프롬프트 템플릿 + 관리 버튼 (한 줄). 입력창 바로 아래. -->
<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:8px">
  <select id="template-picker" style="flex:1;min-width:0;font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333">
    <option value="">${esc(t("console.template.placeholder"))}</option>
  </select>
  <button type="button" id="manage-templates-btn" class="chip chip-link" style="font-size:11px;margin-left:0;flex-shrink:0">${esc(t("console.template.manage"))}</button>
</div>

<!-- v1.6.3 — Agent dispatch + 관리 버튼 (한 줄). -->
<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:6px">
  <select id="agent-picker" style="flex:1;min-width:0;font-size:12px;padding:4px 8px;background:#1a1a1a;color:var(--text);border:1px solid #333" title="Dispatch a registered sub-agent into the prompt">
    <option value="">${esc(t("console.agent.placeholder"))}</option>
  </select>
  <a href="/agents" class="chip chip-link" style="font-size:11px;margin-left:0;flex-shrink:0">${esc(t("console.agent.manage"))}</a>
</div>

<!-- v1.108.2/v1.112.0 — 콘솔 도구 바: 좌측 응답중 스피너(setInFlight 가 hidden 토글) +
     세션/모델/MCP/필터/자동 스크롤. v1.112.0 에서 입력창 위 → 가장 하단으로 이동(사용자 요청). -->
<div class="console-bottom-bar" style="margin-top:8px">
  <div id="console-spinner" class="console-spinner" hidden aria-hidden="true">
    <span class="spinner"></span>
    <span class="spinner-label">${esc(t("console.busy.responding"))}</span>
  </div>
  <div class="console-bottom-tools">
    <!-- v1.110.0 — 세션 표시 + 모델 셀렉터 + MCP 최소화(헤더에서 이동). 메시지 필터 버튼 좌측. -->
    <span class="dim" style="font-size:11px;white-space:nowrap">${esc(t("console.session"))} $statusBadge${if (sessionId != null) """ <span class="dim">${esc(sessionId.take(12))}…</span>""" else ""}</span>
    $modelSelectorHtml
    $mcpStrictHtml
    <button type="button" id="filter-open" class="chip chip-link"
            style="font-size:11px;padding:4px 11px;display:inline-flex;align-items:center;gap:5px"
            title="${esc(t("console.filter.title"))}">
      🔍 ${esc(t("console.filter.title"))} <span id="filter-summary" class="dim" style="font-size:11px"></span>
    </button>
  </div>
</div>

<!-- v1.90.12 — 코드블록 syntax highlight (assistant 마크다운 + tool 결과). 동기 로드해
     append 시점에 window.hljs 준비. 이전엔 콘솔이 highlight.js 를 로드하지 않아 hljs 부재로
     highlight 가 항상 skip 됐다. -->
<link rel="stylesheet" href="/static/highlight-github-dark.min.css">
<script src="/static/highlight.min.js"></script>
<!-- v1.70.0 — 콘솔 친화 렌더러 (tool_use/tool_result/unknown). inline 스크립트보다 먼저 동기 로드. -->
<script src="/static/console-render.js?v=1.133.0"></script>
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
  // v1.133.0 — 'usage'(토큰 소비량 보고) 추가.
  var OPTIONAL_CATS = ['tool_use', 'tool_result', 'session', 'done', 'replay', 'ws', 'todo', 'thinking', 'usage'];
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

  // v1.129.0 — 자동스크롤 토글 제거. "최하단 근처에 있는가"(stickyWanted)만으로 자동 추적:
  //  최하단이면 새 메시지 따라 내려가고(stick), 스크롤 업 하면 해제(과거 보는 중 화면 고정).
  //  탭 숨김(display:none) 중엔 scrollHeight=0 이라 isAtBottom 이 부정확 → scroll 이벤트에서
  //  갱신한 마지막 상태(플래그)를 신뢰한다.
  var stickyWanted = true;   // 초기 진입은 최하단(최신) 보기.
  function shouldStick() { return stickyWanted; }
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
    stickyWanted = true;   // v1.129.0 — 명시적 최하단 점프 → 자동스크롤 ON.
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
    // v1.129.0 — 초기 진입은 항상 최하단(최신). reflow 구간 instant 재고정. 사용자가 그 사이
    // 직접 스크롤(wheel/touch)하면 즉시 중단 + 자동스크롤 해제(stickyWanted=false).
    pinBottomNow();
    var cancelled = false;
    function stop() { cancelled = true; stickyWanted = false; }
    logEl.addEventListener('wheel', stop, { passive: true, once: true });
    logEl.addEventListener('touchstart', stop, { passive: true, once: true });
    requestAnimationFrame(function () { if (!cancelled) pinBottomNow(); });
    [0, 50, 150, 350].forEach(function (d) {
      setTimeout(function () { if (!cancelled) pinBottomNow(); }, d);
    });
  }

  var scrollSaveTimer = null;
  logEl.addEventListener('scroll', function(){
    // v1.129.0 — 스크롤 위치로 자동스크롤 상태 갱신: 최하단 근처면 ON, 위로 올리면 OFF.
    stickyWanted = isAtBottom();
    if (stickyWanted) {
      setUnread(0);
      setJumpVisible(false);
    } else if (!jumpBtn || !jumpBtn.classList.contains('visible')) {
      setJumpVisible(true);
    }
  });

  // v1.99.1 — 탭 전환으로 이 콘솔이 다시 보일 때(display:none→block) 최하단 재고정.
  //  applyInitialView 는 최초 1회뿐이라, 다른 탭(빌드/파일 등) 갔다 돌아오면 숨겨진 동안
  //  append 된 메시지로(display:none 이라 scrollHeight 기반 stick 무효) 스크롤이 어긋난 채
  //  보였다. 부모 ProjectTabs 가 activate 시 보내는 postMessage 를 받아, 자동스크롤 ON 이면
  //  reflow 구간 instant 재고정(애니메이션 없음). OFF 면 사용자가 보던 위치 그대로 둔다.
  window.addEventListener('message', function (ev) {
    if (ev.origin !== location.origin) return;
    var d = ev.data;
    if (!d) return;
    // v1.106.3 — 부모 rail 의 /compact 버튼 등에서 프롬프트 전송 요청. 정상 제출 경로
    // (form submit → busy 면 큐, 아니면 sendPrompt)로 보내 에코/미터 갱신을 일관 처리.
    if (d.type === 'vibe:send-prompt' && typeof d.text === 'string' && d.text) {
      if (input) {
        input.value = d.text;
        if (form && form.requestSubmit) form.requestSubmit();
        else if (typeof sendPrompt === 'function') sendPrompt(d.text);
      }
      return;
    }
    // v1.108.0 — 부모 rail '자동(auto-compact)' 체크박스 → 서버 영속(fetch, 리로드 없음).
    if (d.type === 'vibe:set-autocompact') {
      var cf = document.querySelector('input[name=_csrf]');
      fetch('/projects/' + projectId + '/console/auto-compact', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: '_csrf=' + encodeURIComponent(cf ? cf.value : '') + '&enabled=' + (d.enabled ? 'true' : 'false'),
      }).catch(function () {});
      return;
    }
    if (d.type !== 'pt:tab-visible' || !stickyWanted) return;
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
    // v1.133.0 — 이미지 첨부/결과 썸네일. .log-body 의 형제로 두어 접기(clamp)와 무관하게
    // 항상 보인다. src 는 data:image base64 또는 same-origin 이력 서빙 endpoint 만 허용.
    if (opts.images && opts.images.length) {
      var contentBox = row.querySelector('.log-content');
      var metaBox = row.querySelector('.log-meta');
      if (contentBox) {
        var imgWrap = document.createElement('div');
        imgWrap.className = 'log-images';
        for (var ii = 0; ii < opts.images.length; ii++) {
          var srcv = String((opts.images[ii] && opts.images[ii].src) || '');
          var okData = /^data:image\/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=]+$/.test(srcv);
          var okUrl = srcv.indexOf('/api/projects/') === 0 && srcv.indexOf('/claude/console/image?') > 0;
          if (!okData && !okUrl) continue;
          var imEl = document.createElement('img');
          imEl.className = 'log-img';
          imEl.loading = 'lazy';
          imEl.alt = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.alt"))};
          imEl.src = srcv;
          imEl.addEventListener('click', function(e) {
            e.stopPropagation();
            this.classList.toggle('expanded');
          });
          imgWrap.appendChild(imEl);
        }
        if (imgWrap.childNodes.length) contentBox.insertBefore(imgWrap, metaBox);
      }
    }
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
    // v1.104.1 — user 프롬프트 상단고정은 "가장 최신" 1개만(.cur). 새 user 가 오면 이전
    //  .cur 를 해제하고 이 row 에 부여. append 는 항상 맨 끝(appendChild)이라 row 가 최신.
    // v1.104.1/v1.129.0 — 최신 user 1개만 .cur(sticky). 더보기로 과거를 prepend(beforeNode)할
    // 땐 .cur 를 건드리지 않는다(최신 user 의 sticky 유지).
    if (cls === 'user' && !opts.beforeNode) {
      var prevCur = logEl.querySelector('.log-line.user.cur');
      if (prevCur) prevCur.classList.remove('cur');
      row.classList.add('cur');
    }
    // v1.129.0 — opts.beforeNode 면 그 노드 앞에 삽입(더보기 과거 prepend), 아니면 맨 끝.
    if (opts.beforeNode) logEl.insertBefore(row, opts.beforeNode);
    else logEl.appendChild(row);
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
  // v1.111.0 — Todo 요약은 부모 rail 로 이동(아래 renderTodoPanel 이 vibe:todo 로 broadcast).
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

  // v1.111.0 — Todo 패널이 부모 rail 로 이동. DOM 직접 렌더 대신 스냅샷(html+summary)을
  // vibe:todo postMessage 로 부모에 전달한다(부모 project-tabs.js 가 rail 카드에 렌더).
  function renderTodoPanel() {
    var done = 0, active = 0, html = '';
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
    var summary = '';
    if (todoStore.length > 0) {
      var parts = [done + '/' + todoStore.length + ' ' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.todo.summary.done"))}];
      if (active > 0) parts.push(active + ' ' + ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.todo.summary.active"))});
      summary = '— ' + parts.join(' · ');
    }
    try { window.parent.postMessage({ type: 'vibe:todo', count: todoStore.length, html: html, summary: summary }, location.origin); } catch (e) {}
  }

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
      // v1.133.0 — 이미지 블록은 텍스트('[image]')가 아니라 실제 썸네일로 렌더(rich 추출).
      var rich = window.VibeConsole.extractToolResultRich(f.output);
      var out = rich.text;
      var resultImgs = [];
      for (var ri = 0; ri < rich.images.length; ri++) {
        resultImgs.push({ src: 'data:' + rich.images[ri].mediaType + ';base64,' + rich.images[ri].data });
      }
      var resultLabel = f.isError ? 'tool-err' : '✓ result';
      // v1.90.9 — 표시는 clip(8000)이되 복사용 원문(raw)은 전체 보존.
      append(f.isError ? 'tool-err' : 'tool-out', resultLabel, clip(out, 8000), 'tool_result',
             { raw: out, images: resultImgs });
      // v1.90.4 — tool 결과(파일 내용/명령 출력)에도 'unauthorized' 등이 정상 등장하므로 감지 제외.
    } else if (t === 'console_error') {
      // v1.108.2 — 사용량/요금 한도 종료(code=usage_limit)는 크래시성 에러가 아니라 '한도' 상태다.
      //  빨간 'error' 버블 + "success:"/"usage_limit:" prefix 대신 명료한 '🛑 한도' 시스템 카드로
      //  노출(success 오인·중복 제거). 그 외 진짜 에러는 기존대로 빨간 버블.
      if (f.code === 'usage_limit') {
        append('sys', '🛑 한도', f.message || '', 'system');
      } else {
        append('err', 'error', (f.code || '') + ': ' + (f.message || ''), 'error');
      }
      detectAuthFailure(f.message);
    } else if (t === 'console_done') {
      append('sys', 'done', f.reason || 'end_turn', 'done');
      setInFlight(false);
    } else if (t === 'console_system') {
      // 'turn_cancelled' / 'process_crashed' 등 종료 신호 — stop 버튼 숨김
      if (f.code === 'turn_cancelled' || f.code === 'process_crashed' || f.code === 'idle_terminated') {
        setInFlight(false);
      }
      // v1.133.0 — 토큰 소비량 보고(code=usage: "input N · output N · cache-read N …")는
      // 별도 'usage' 카테고리 → 필터에서 토글 가능(이전엔 mandatory 'system' 에 묶여 항상 노출).
      append('sys', f.code || 'system', f.message || '', f.code === 'usage' ? 'usage' : 'system');
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
    } else if (t === 'console_context_usage') {
      // v1.106.1 — 컨텍스트 점유율 미터 live 갱신.
      updateContextMeter(f.inputTokens, f.cacheReadTokens, f.cacheCreationTokens, f.contextLimit);
    } else if (t === 'console_replay_end') {
      append('sys', 'replay', 'history end — live frames follow', 'replay');
    } else if (t === 'automation_progress') {
      // v1.109.0 — 자동화 UI 가 부모 오버뷰 rail 로 이동 → 진행/종료 프레임을 부모로
      // postMessage 포워딩(즉시 갱신). standalone(부모 없음)이면 self 로 가고 리스너 없어 무해.
      try { window.parent.postMessage({ type: 'vibe:automation', state: f }, location.origin); } catch (e) {}
    } else if (t === 'console_unknown') {
      // v1.70.0 — 이전엔 미처리(드롭)되거나 raw 로 보이던 이벤트(thinking / system task /
      // rate_limit 등)를 친화적으로 렌더. null 이면 노이즈로 판단해 숨김.
      var u = window.VibeConsole.renderUnknown(f.raw);
      if (u) append(u.cls, u.label, u.body, u.cat);
    }
  }

  // v1.129.0 — 콘솔 페이지네이션 + WS since 경계 상태.
  var initialMaxSeq = ${initialMaxSeq};
  var lastSeq = initialMaxSeq;     // WS since: 첫 연결=history 경계, 이후 본 최대 seq.
  var oldestTurnIdx = null;        // 로드된 가장 오래된 turnIdx("더보기" before 키).
  var loadingMore = false;

  function tryParseJson(s) { try { return JSON.parse(s); } catch (e) { return null; } }
  // v1.129.0 — DB history row 1개 렌더(초기 30개 + "더보기" 과거 공용). beforeNode 있으면
  // 그 노드 앞에 prepend(과거를 위쪽에). 라이브 WS 흐름과 동일 변환 사용.
  function renderHistoryRow(r, beforeNode) {
    r = r || {};
    var role = r.role || '', text = r.text || '';
    var opts = {};
    if (r.ts) opts.ts = r.ts;
    if (beforeNode) opts.beforeNode = beforeNode;
    // v1.133.0 — 이미지 메타(r.images, base64 는 서버가 스트립) → 이력 서빙 endpoint URL.
    if (r.images && r.images.length && r.turnIdx != null) {
      var hImgs = [];
      for (var hi = 0; hi < r.images.length; hi++) {
        hImgs.push({ src: '/api/projects/' + encodeURIComponent(projectId) +
                          '/claude/console/image?turn=' + r.turnIdx + '&idx=' + hi });
      }
      opts.images = hImgs;
    }
    if (role === 'user') append('user', 'user', text, 'assistant', opts);
    else if (role === 'assistant') append('assistant', 'assistant', text, 'assistant', opts);
    else if (role === 'tool_use') {
      var inp = tryParseJson(text);
      var ru = renderToolUse(r.tool || 'tool', inp != null ? inp : text);
      var isTodo = (r.tool === 'TaskCreate' || r.tool === 'TaskUpdate' || r.tool === 'TodoWrite');
      append('tool', ru.label, ru.body, isTodo ? 'todo' : 'tool_use', opts);
    } else if (role === 'tool_result' || role === 'tool_result_error') {
      var parsed = tryParseJson(text);
      // v1.133.0 — rich 추출: 이미지 블록을 '[image]' 텍스트 대신 제외(위 opts.images 가 썸네일 담당).
      var out = window.VibeConsole.extractToolResultRich
        ? window.VibeConsole.extractToolResultRich(parsed != null ? parsed : text).text
        : window.VibeConsole.extractToolResult(parsed != null ? parsed : text);
      opts.raw = out;
      append(role === 'tool_result_error' ? 'tool-err' : 'tool-out', role === 'tool_result_error' ? 'tool-err' : '✓ result', clip(out, 8000), 'tool_result', opts);
    } else if (role === 'system') {
      var sys = tryParseJson(text) || {};
      if (sys.kind === 'session_started') append('sys', 'session', 'started' + (sys.model ? ' · ' + sys.model : ''), 'session', opts);
      else if (sys.kind === 'done') append('sys', 'done', sys.reason || 'end_turn', 'done', opts);
      else if (sys.kind === 'system') append('sys', sys.code || 'system', sys.message || '', 'system', opts);
      else append('sys', 'system', clip(text, 1000), 'system', opts);
    } else if (role === 'error') {
      var er = tryParseJson(text) || {};
      append('err', 'error', (er.code || '') + ': ' + (er.message || text), 'error', opts);
    } else if (role === 'unknown') {
      var u = window.VibeConsole.renderUnknown(text);
      if (u) append(u.cls, u.label, u.body, u.cat, opts);
    }
  }
  var loadMoreBtn = document.getElementById('console-load-more');
  function updateLoadMoreBtn() {
    if (loadMoreBtn) loadMoreBtn.style.display = (oldestTurnIdx != null && oldestTurnIdx > 0) ? 'block' : 'none';
  }
  async function loadMoreHistory() {
    if (loadingMore || oldestTurnIdx == null || oldestTurnIdx <= 0) return;
    loadingMore = true;
    if (loadMoreBtn) loadMoreBtn.disabled = true;
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/history?before=' + oldestTurnIdx + '&limit=30', { credentials: 'same-origin' });
      if (!res.ok) return;
      var arr = await res.json();
      if (!Array.isArray(arr) || arr.length === 0) { oldestTurnIdx = 0; updateLoadMoreBtn(); return; }
      // v1.129.1 — 더보기 버튼이 첫 자식이므로 그 다음 노드 앞에 prepend(버튼은 최상단 유지).
      var anchor = (loadMoreBtn && loadMoreBtn.nextSibling) ? loadMoreBtn.nextSibling : logEl.firstChild;
      var prevH = logEl.scrollHeight, prevTop = logEl.scrollTop;
      for (var i = 0; i < arr.length; i++) renderHistoryRow(arr[i], anchor);
      oldestTurnIdx = (arr[0] && arr[0].turnIdx != null) ? arr[0].turnIdx : 0;
      // v1.129.0 — 과거를 위에 추가했으니 추가된 높이만큼 scrollTop 보정 → 보던 위치 유지.
      logEl.scrollTop = prevTop + (logEl.scrollHeight - prevH);
      updateLoadMoreBtn();
    } catch (e) { /* ignore */ } finally {
      loadingMore = false;
      if (loadMoreBtn) loadMoreBtn.disabled = false;
    }
  }
  if (loadMoreBtn) loadMoreBtn.addEventListener('click', loadMoreHistory);

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
    // v1.129.0 — 가장 오래된 turnIdx 기록("더보기" 페이지네이션 before 키) + 버튼 갱신.
    oldestTurnIdx = (arr[0] && arr[0].turnIdx != null) ? arr[0].turnIdx : null;
    updateLoadMoreBtn();
    // v1.129.0 — 공용 renderHistoryRow 로 위임(초기 30개 + "더보기" 과거가 동일 변환).
    for (var i = 0; i < arr.length; i++) renderHistoryRow(arr[i]);
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
    // v1.129.0 — since=lastSeq 로 ring replay 경계 지정. 첫 연결은 initialMaxSeq(=history 경계)
    // 라 ring 의 과거 프레임(history 와 중복)을 안 받고, 재연결은 마지막으로 본 seq 이후만 받는다.
    ws = new WebSocket(proto + '//' + location.host + '/ws/projects/' + projectId + '/console/logs?since=' + lastSeq);

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
        // v1.129.0 — 본 최대 seq 추적(재연결 since). replay 마커 등 seq 없는 프레임은 무시.
        if (typeof f.seq === 'number' && f.seq > lastSeq) lastSeq = f.seq;
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
  var stopBtn = document.getElementById('stop-btn');
  var interruptBtn = document.getElementById('interrupt-btn');  // v1.112.0 — 끼어들기.
  var busyBadge = document.getElementById('busy-badge');
  var BUSY_RESPONDING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.responding"))};
  var BUSY_IDLE = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.idle"))};
  var BUSY_WAITING = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.waiting"))};
  var BUSY_STOPPED = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.stopped"))};
  var BUSY_ERROR = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.busy.error"))};
  var inFlight = false;
  // v1.113.0 — 클라이언트 인위적 큐(pendingPrompts) 제거. 응답 중 전송도 바로 서버로 보내고
  // (TUI 동형) 서버가 진행 중 turn 에 follow-up 으로 이어붙여 CLI 내부 큐가 순차 처리한다.
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
      busyBadge.textContent = BUSY_RESPONDING;
    } else {
      // v1.114.0 — 상태 토큰을 서버 wire 값(ProjectState.READY)과 통일('idle' 별칭 폐지).
      busyBadge.dataset.state = 'ready';
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
  // v1.111.0 — 백그라운드 작업 패널이 부모 rail 로 이동. taskId → { desc, meta, status } 데이터
  // 스토어만 유지하고, 변경 시 스냅샷 HTML 을 vibe:bgtasks postMessage 로 부모에 전달한다.
  // (접기/펼치기는 부모 rail 카드 헤더가 담당.)
  var bgTasks = {};  // taskId -> { desc, meta, status }
  var BG_TYPE_LABELS = { local_bash: 'shell' };
  function bgRunningCount() {
    var n = 0;
    for (var k in bgTasks) { if (bgTasks[k] && bgTasks[k].status === 'running') n++; }
    return n;
  }
  function broadcastBgTasks() {
    var ids = Object.keys(bgTasks);
    var html = '';
    for (var x = 0; x < ids.length; x++) {
      var r = bgTasks[ids[x]];
      html += '<div class="bg-task-card" data-status="' + r.status + '">' +
              '<span class="bg-task-icon">' + bgIcon(r.status) + '</span>' +
              '<span class="bg-task-desc">' + escHtml(r.desc || '') + '</span>' +
              '<span class="bg-task-meta">' + escHtml(r.meta || '') + '</span></div>';
    }
    try {
      window.parent.postMessage({ type: 'vibe:bgtasks', count: ids.length,
        running: bgRunningCount(), html: html }, location.origin);
    } catch (e) {}
  }
  // v1.106.1 — 컨텍스트 점유율 미터 갱신(상시 표시). used = input + cacheRead + cacheCreation.
  function ctxFmt(n) {
    n = Number(n) || 0;
    if (n >= 1000000) return (n / 1000000).toFixed(n >= 10000000 ? 0 : 1) + 'M';
    if (n >= 1000) return Math.round(n / 1000) + 'K';
    return String(n);
  }
  function updateContextMeter(input, cacheRead, cacheCreation, limit) {
    // v1.106.2 — 부모 ProjectTabs 우측 오버뷰 rail 미터로 전달(임베드 시). standalone/chat
    // 은 부모가 자기 자신이라 무해. 그 후 인라인 미터(비임베드)도 갱신.
    try {
      window.parent.postMessage({ type: 'vibe:context-usage',
        input: Number(input) || 0, cacheRead: Number(cacheRead) || 0,
        cacheCreation: Number(cacheCreation) || 0, limit: Number(limit) || 0 }, location.origin);
    } catch (e) {}
    var el = document.getElementById('ctx-meter'); if (!el) return;
    input = Number(input) || 0; cacheRead = Number(cacheRead) || 0;
    cacheCreation = Number(cacheCreation) || 0; limit = Number(limit) || 0;
    var used = input + cacheRead + cacheCreation;
    if (limit <= 0 || used <= 0) { el.hidden = true; return; }
    el.hidden = false;
    var pct = Math.min(100, used / limit * 100);
    function w(x) { return (Math.max(0, x) / limit * 100) + '%'; }
    var r = el.querySelector('.ctx-seg-read'); if (r) r.style.width = w(cacheRead);
    var c = el.querySelector('.ctx-seg-create'); if (c) c.style.width = w(cacheCreation);
    var i = el.querySelector('.ctx-seg-input'); if (i) i.style.width = w(input);
    var setT = function(id, v) { var n = document.getElementById(id); if (n) n.textContent = v; };
    setT('ctx-used', ctxFmt(used));
    setT('ctx-limit', ctxFmt(limit));
    setT('ctx-pct', Math.round(pct) + '%');
    setT('ctx-free', ctxFmt(Math.max(0, limit - used)));
    el.classList.toggle('warn', pct >= 70);
    el.title = '대화 컨텍스트 ' + ctxFmt(used) + ' / ' + ctxFmt(limit) + ' (' + Math.round(pct) +
      '%). 재사용 ' + ctxFmt(cacheRead) + ' · 신규캐시 ' + ctxFmt(cacheCreation) + ' · 입력 ' + ctxFmt(input) +
      '. 클수록 매 turn 비용↑ — 새 세션으로 리셋 가능.';
  }
  // 초기값(서버 직전 turn 스냅샷) 렌더.
  updateContextMeter($contextInputTokens, $contextTokens, $contextCacheCreationTokens, $contextLimit);
  function bgIcon(status) { return status === 'completed' ? '✓' : (status === 'failed' ? '✗' : '●'); }
  function bgMeta(f) {
    var parts = [BG_TYPE_LABELS[f.taskType] || f.taskType || 'task', String(f.taskId).slice(0, 8)];
    if (f.lastTool) parts.push(f.lastTool);
    if (f.toolUses) parts.push(f.toolUses + ' tools');
    return parts.join(' · ');
  }
  // v1.111.0 — DOM 카드 대신 데이터 스토어만 갱신 후 부모 rail 로 스냅샷 broadcast.
  function handleBgTask(f) {
    if (!f || !f.taskId) return;
    if (f.kind === 'started' || f.kind === 'progress') {
      // task_progress 가 task_started 없이 첫 도착(서브에이전트)해도 항목 생성.
      var rec = bgTasks[f.taskId] || (bgTasks[f.taskId] = { desc: '', meta: '', status: 'running' });
      rec.status = 'running';
      if (f.description) rec.desc = f.description;
      rec.meta = bgMeta(f);
      broadcastBgTasks();
    } else {  // 'updated' | 'notification'
      var rec2 = bgTasks[f.taskId];
      if (!rec2) return;
      var st = f.status || (f.kind === 'notification' ? 'completed' : 'running');
      var active = (st === 'running' || st === 'in_progress' || st === 'started' || st === 'pending');
      rec2.status = active ? 'running'
        : ((st === 'failed' || st === 'error' || st === 'killed') ? 'failed' : 'completed');
      broadcastBgTasks();
      if (!active) {
        var tid = f.taskId;
        setTimeout(function() {
          delete bgTasks[tid];
          broadcastBgTasks();
        }, 6000);
      }
    }
  }
  // v1.7.12 — 응답중 spinner element. console-log 하단에 표시.
  var spinnerEl = document.getElementById('console-spinner');
  function setInFlight(on) {
    inFlight = on;
    if (stopBtn) stopBtn.style.display = on ? 'inline-block' : 'none';
    // v1.139.0 — 끼어들기 버튼 상시 노출(게이트 만석 강제 전송 겸용) — display 토글 제거.
    if (spinnerEl) spinnerEl.hidden = !on;
    updateBusyBadge();
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

  // v1.112.0 — 끼어들기: 진행 중 turn 을 interrupt 로 중단하고 입력창 내용을 즉시 새 prompt 로
  // 보낸다(TUI Esc+입력 동형). 서버가 interrupt → 정리 → sendPrompt 를 한 endpoint 에서 처리.
  // ── v1.133.0 — 프롬프트 이미지 첨부 ───────────────────────────────────────
  // 첨부 경로 3종: 📷 버튼(파일 선택) / 클립보드 붙여넣기 / 드래그&드롭. 최대 4장.
  // 큰 이미지는 canvas 로 최대 변 1568px 다운스케일(비전 권장 해상도 + 토큰 절약).
  var MAX_ATTACH_IMAGES = 4;
  var pendingImages = [];  // [{mediaType, data(base64), dataUrl}]
  var imageBtn = document.getElementById('image-btn');
  var imageFile = document.getElementById('image-file');
  var imagePreview = document.getElementById('image-preview');
  // v1.134.1 — 첨부 다이얼로그 + 아이콘 배지(첨부 수). 인라인 strip 은 다이얼로그로 이동.
  var imageModal = document.getElementById('image-modal');
  var imageEmpty = document.getElementById('image-empty');
  var imageCount = document.getElementById('image-count');

  function renderImagePreview() {
    if (imageCount) {
      imageCount.textContent = String(pendingImages.length);
      imageCount.style.display = pendingImages.length ? 'inline-block' : 'none';
    }
    if (imageBtn) imageBtn.style.borderColor = pendingImages.length ? '#1e40af' : '#2a2a2a';
    if (imageEmpty) imageEmpty.style.display = pendingImages.length ? 'none' : 'block';
    if (!imagePreview) return;
    imagePreview.innerHTML = '';
    for (var i = 0; i < pendingImages.length; i++) {
      (function(idx) {
        var box = document.createElement('div');
        box.style.cssText = 'position:relative;display:inline-block';
        var im = document.createElement('img');
        im.src = pendingImages[idx].dataUrl;
        im.alt = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.alt"))};
        im.style.cssText = 'height:56px;max-width:96px;object-fit:cover;border:1px solid #2a2a2a;border-radius:6px;display:block';
        var x = document.createElement('button');
        x.type = 'button';
        x.textContent = '✕';
        x.setAttribute('aria-label', 'remove image');
        x.style.cssText = 'position:absolute;top:-6px;right:-6px;width:18px;height:18px;line-height:15px;padding:0;font-size:10px;border-radius:50%;border:1px solid #444;background:#222;color:#ddd;cursor:pointer';
        x.addEventListener('click', function() { pendingImages.splice(idx, 1); renderImagePreview(); });
        box.appendChild(im); box.appendChild(x);
        imagePreview.appendChild(box);
      })(i);
    }
    imagePreview.style.display = pendingImages.length ? 'flex' : 'none';
    // 이미지가 있으면 텍스트 없이도 제출 가능(기본 문구 자동 사용) — required 해제.
    if (input && !input.disabled) input.required = pendingImages.length === 0;
  }

  // canvas 다운스케일. gif(애니메이션 보존)는 원본 유지. PNG 는 PNG 재인코딩 우선,
  // 한도 초과 시 JPEG 폴백. 재인코딩이 원본보다 커지면 원본 유지.
  function downscaleImage(file) {
    return new Promise(function(resolve) {
      var fr = new FileReader();
      fr.onerror = function() { resolve(null); };
      fr.onload = function() {
        var dataUrl = String(fr.result || '');
        var m = dataUrl.match(/^data:(image\/[a-z0-9+.-]+);base64,(.*)$/i);
        if (!m) { resolve(null); return; }
        var origType = m[1].toLowerCase(), origData = m[2];
        var orig = { mediaType: origType, data: origData, dataUrl: dataUrl };
        if (origType === 'image/gif') { resolve(orig); return; }
        var img = new Image();
        img.onerror = function() { resolve(orig); };
        img.onload = function() {
          var MAXD = 1568;
          var w = img.naturalWidth, h = img.naturalHeight;
          if ((w <= MAXD && h <= MAXD) && dataUrl.length <= 2000000) { resolve(orig); return; }
          var scale = Math.min(1, MAXD / Math.max(w, h));
          var cw = Math.max(1, Math.round(w * scale)), ch = Math.max(1, Math.round(h * scale));
          var cv = document.createElement('canvas');
          cv.width = cw; cv.height = ch;
          try {
            cv.getContext('2d').drawImage(img, 0, 0, cw, ch);
            var keepPng = origType === 'image/png';
            var outUrl = keepPng ? cv.toDataURL('image/png') : cv.toDataURL('image/jpeg', 0.85);
            if (keepPng && outUrl.length > 7000000) outUrl = cv.toDataURL('image/jpeg', 0.85);
            var m2 = outUrl.match(/^data:(image\/[a-z0-9+.-]+);base64,(.*)$/i);
            if (!m2 || outUrl.length >= dataUrl.length) resolve(orig);
            else resolve({ mediaType: m2[1].toLowerCase(), data: m2[2], dataUrl: outUrl });
          } catch (e) { resolve(orig); }
        };
        img.src = dataUrl;
      };
      fr.readAsDataURL(file);
    });
  }

  function addImageFile(file) {
    if (!file || !/^image\//.test(file.type)) return;
    if (pendingImages.length >= MAX_ATTACH_IMAGES) {
      alert(${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.limitCount"))});
      return;
    }
    downscaleImage(file).then(function(res) {
      if (!res) { alert(${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.readFailed"))}); return; }
      if (res.data.length > 7000000) {
        alert(${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.limitSize"))});
        return;
      }
      if (pendingImages.length >= MAX_ATTACH_IMAGES) return;
      pendingImages.push(res);
      renderImagePreview();
    });
  }

  // v1.134.1 — 아이콘 클릭 → 다이얼로그 열기. 파일 선택은 다이얼로그 안 버튼(#image-pick).
  function openImageModal() { if (imageModal) { renderImagePreview(); imageModal.hidden = false; } }
  function closeImageModal() { if (imageModal) imageModal.hidden = true; }
  if (imageBtn) imageBtn.addEventListener('click', openImageModal);
  var imagePick = document.getElementById('image-pick');
  if (imagePick && imageFile) imagePick.addEventListener('click', function() { imageFile.click(); });
  var imageClose = document.getElementById('image-close');
  var imageDone = document.getElementById('image-done');
  if (imageClose) imageClose.addEventListener('click', closeImageModal);
  if (imageDone) imageDone.addEventListener('click', closeImageModal);
  if (imageModal) imageModal.addEventListener('click', function(e) { if (e.target === imageModal) closeImageModal(); });
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape' && imageModal && !imageModal.hidden) closeImageModal();
  });
  if (imageFile) {
    imageFile.addEventListener('change', function() {
      var fs = imageFile.files || [];
      for (var i = 0; i < fs.length; i++) addImageFile(fs[i]);
      imageFile.value = '';
    });
  }
  if (input) {
    input.addEventListener('paste', function(ev) {
      var items = (ev.clipboardData && ev.clipboardData.items) || [];
      var got = false;
      for (var i = 0; i < items.length; i++) {
        if (items[i].kind === 'file' && /^image\//.test(items[i].type)) {
          var f = items[i].getAsFile();
          if (f) { addImageFile(f); got = true; }
        }
      }
      if (got) ev.preventDefault();
    });
    input.addEventListener('dragover', function(ev) { ev.preventDefault(); });
    input.addEventListener('drop', function(ev) {
      var fs = (ev.dataTransfer && ev.dataTransfer.files) || [];
      var got = false;
      for (var i = 0; i < fs.length; i++) {
        if (/^image\//.test(fs[i].type)) { addImageFile(fs[i]); got = true; }
      }
      if (got) ev.preventDefault();
    });
  }

  // 전송 body / 에코 옵션 helpers — sendPrompt 와 interruptSend 공용.
  function promptBody(text, imgs) {
    if (!imgs.length) return JSON.stringify({ text: text });
    return JSON.stringify({
      text: text,
      images: imgs.map(function(p) { return { mediaType: p.mediaType, data: p.data }; }),
    });
  }
  function echoOpts(imgs) {
    if (!imgs.length) return {};
    return { images: imgs.map(function(p) { return { src: p.dataUrl }; }) };
  }
  function clearPendingImages() { pendingImages = []; renderImagePreview(); }

  async function interruptSend() {
    var text = input.value.trim();
    if (!text && pendingImages.length) text = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.defaultPrompt"))};
    if (!text) { input.focus(); return; }
    var imgs = pendingImages.slice();
    if (interruptBtn) interruptBtn.disabled = true;
    sendBtn.disabled = true;
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/interrupt', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: promptBody(text, imgs),
      });
      if (!res.ok) {
        var msg = await res.text();
        append('err', 'send', res.status + ' ' + msg, 'error');
      } else {
        append('sys', 'interrupt', ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.interrupt.sent"))}, 'system');
        append('user', 'user', text, 'assistant', echoOpts(imgs));
        input.value = '';
        clearPendingImages();
        setInFlight(true);  // 새 turn 시작(서버 console_busy_state 와 수렴).
        scrollToBottom();
        try { window.parent.postMessage({ type: 'vibe:prompt-sent', text: text }, location.origin); } catch (e) {}
      }
    } catch (e) {
      append('err', 'send', String(e), 'error');
    } finally {
      if (interruptBtn) interruptBtn.disabled = false;
      sendBtn.disabled = false;
      input.blur();
    }
  }
  if (interruptBtn) interruptBtn.addEventListener('click', interruptSend);

  async function sendPrompt(text) {
    var imgs = pendingImages.slice();
    sendBtn.disabled = true;
    setInFlight(true);
    try {
      var res = await fetch('/api/projects/' + projectId + '/claude/console/prompt', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json'},
        body: promptBody(text, imgs),
      });
      if (!res.ok) {
        var msg = await res.text();
        append('err', 'send', res.status + ' ' + msg, 'error');
        setInFlight(false);
      } else {
        append('user', 'user', text, 'assistant', echoOpts(imgs));
        input.value = '';
        clearPendingImages();
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
    // v1.133.0 — 이미지만 첨부하고 텍스트가 비면 기본 문구로 전송.
    if (!text && pendingImages.length) text = ${jsLit(com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, "console.image.defaultPrompt"))};
    if (!text) return;
    // v1.113.0 — 응답 중에도 인위적 큐 없이 바로 전송(TUI 동형). 서버가 진행 중 turn 에
    // follow-up 으로 이어붙이고 CLI 내부 큐가 순차 처리한다(대기열 메시지/상태칩 제거).
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

  // v1.109.0 — 프롬프트 자동화 UI 는 부모 오버뷰 rail(project-tabs.js)로 이동했다.
  // 콘솔은 automation_progress 프레임을 부모로 postMessage 포워딩만 한다(위 WS 핸들러).

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

  // v1.115.0 — 프롬프트 템플릿 picker 채우기 + 삽입(변수 치환) + 관리 다이얼로그는
  // /static/prompt-templates.js (window.PromptTemplates) 로 이관. 이 콘솔 페이지는
  // #template-picker / #manage-templates-btn 만 제공하면 모듈이 자동 와이어링한다.

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
                    <td>${esc(fmtInstant(b.startedAt, lang))}</td>
                    <td>${esc(fmtBuildDuration(b.startedAt, b.finishedAt))}</td>
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
      <!-- v1.107.0 — Release APK / AAB 번들 빌드(서명 주입). 키스토어 필요 → 미준비 시 비활성. -->
      <form method="post" action="/projects/${esc(p.id)}/builds/release" style="display:inline">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" style="width:auto;padding:8px 16px;background:#1f2937;color:#cbd5e1;border:1px solid #2b3648;border-radius:6px;cursor:pointer"${if (!keystoreReady) " disabled title=\"${esc(t("builds.disabled.noKeystore"))}\"" else " title=\"${esc(t("builds.queue.release.hint"))}\""}>${esc(t("builds.queue.release"))}</button>
      </form>
      <form method="post" action="/projects/${esc(p.id)}/builds/bundle" style="display:inline">
        ${CsrfTokens.hiddenInput(csrf)}
        <button type="submit" style="width:auto;padding:8px 16px;background:#1f2937;color:#cbd5e1;border:1px solid #2b3648;border-radius:6px;cursor:pointer"${if (!keystoreReady) " disabled title=\"${esc(t("builds.disabled.noKeystore"))}\"" else " title=\"${esc(t("builds.queue.bundle.hint"))}\""}>${esc(t("builds.queue.bundle"))}</button>
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
    <tr><th>${esc(t("builds.col.id"))}</th><th>${esc(t("builds.col.status"))}</th><th>${esc(t("builds.col.started"))}</th><th>${esc(t("builds.col.duration"))}</th><th>${esc(t("builds.col.apk"))}</th></tr>
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

    /**
     * v1.128.2 — 빌드 히스토리 시작 시간을 lang-aware 표시로(ko=yyyy/MM/dd, en=MM/dd/yyyy,
     * 둘 다 KST HH:mm:ss). 공통 헬퍼 AdminTemplates.fmtTs 로 위임. 파싱 실패 시 원문 그대로.
     */
    private fun fmtInstant(iso: String?, lang: String): String =
        AdminTemplates.fmtTs(iso, lang)

    /**
     * v1.107.1 — 빌드 소요 시간을 초 단위로 표시(예: 5분 → "300s"). 미완료/파싱 실패 시 "-".
     */
    private fun fmtBuildDuration(startedAt: String?, finishedAt: String?): String {
        val secs = durationSeconds(startedAt, finishedAt) ?: return "-"
        return "${Math.round(secs)}s"
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
    <dt class="dim">${esc(t("build.detail.startedAt"))}</dt><dd>${esc(AdminTemplates.fmtTs(b.startedAt, lang))}</dd>
    <dt class="dim">${esc(t("build.detail.finishedAt"))}</dt><dd>${esc(AdminTemplates.fmtTs(b.finishedAt, lang))}</dd>
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
                    <td>${esc(AdminTemplates.fmtTs(f.createdAt, lang))}</td>
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
  <!-- v1.107.3 — 툴바 버튼/폼/chip 높이 통일(30px)로 한 줄 정렬. 인라인이라 캐시 무관. -->
  <style>
    #fts-toolbar-default, #fts-toolbar-select { row-gap: 6px; }
    #fts-toolbar-default > form, #fts-toolbar-select > form { margin: 0; display: inline-flex; align-items: center; }
    #fts-toolbar-default .chip, #fts-toolbar-select .chip { height: 30px; box-sizing: border-box; display: inline-flex; align-items: center; margin: 0; }
    /* 전역 label{margin-bottom:16px} 가 업로드 <label class=chip> 를 위로 밀던 것 무력화. */
    #fts-toolbar-default label.chip, #fts-toolbar-select label.chip { margin: 0; }
  </style>
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

