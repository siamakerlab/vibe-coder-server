package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptSender
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * v1.116.0 — `/projects/{id}/quality` — 품질/접근성 검사 탭.
 *
 *  Phase 1 (v1.116.0): Android Lint 정적 분석 (에뮬레이터 불필요).
 *  Phase 2 (v1.117.0): connectedDebugAndroidTest 인스트루먼트 테스트(에뮬레이터) —
 *                      Compose UI Test / Espresso / ATF(동적 접근성) 결과 수집.
 *
 *  - GET  ?run=1   → lint 실행 + 결과 SSR.
 *  - GET  ?itest=1 → 인스트루먼트 테스트 실행 + 결과 SSR.
 *  - POST          → run=1 redirect (lint, write 권한).
 *  - POST /run-tests → itest=1 redirect (인스트루먼트, write 권한).
 *  - POST /fix     → 선택 항목(lint 이슈 또는 실패 테스트)을 현재 콘솔 provider 로 전송.
 *
 * 캐싱: 호출당 새로 실행(stale 결과 혼동 방지).
 */
fun Routing.qualityRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    svc: LintQualityService,
    itestSvc: InstrumentedTestService,
    promptSender: ConsolePromptSender,
) {
    get("/projects/{id}/quality") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val moduleName = safeModule(call.request.queryParameters["module"], p.moduleName)
        val runLint = call.request.queryParameters["run"] == "1"
        val runItest = call.request.queryParameters["itest"] == "1"
        // 자식 프로세스를 spawn 하는 write 성 작업 — viewer 차단.
        if ((runLint || runItest) && !requireWriteAccessOrRedirect(sess)) return@get
        val lintResult = if (runLint) svc.lint(id, moduleName) else null
        val itestResult = if (runItest) itestSvc.run(id, moduleName) else null
        val emulatorReady = runCatching { itestSvc.emulatorReady() }.getOrDefault(false)
        val prep = runCatching { itestSvc.inspectPrep(id, moduleName) }.getOrNull()
        val fixed = call.request.queryParameters["fixed"]?.toIntOrNull()
        val prepared = call.request.queryParameters["prepared"] == "1"
        val flashErr = call.request.queryParameters["err"]?.ifBlank { null }
        call.respondText(
            QualityTemplates.page(
                sess.username, p, moduleName, lintResult, itestResult, emulatorReady, prep,
                fixed, prepared, flashErr, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/quality") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val moduleName = safeModule(form["module"], "app")
        call.respondRedirect("/projects/$id/quality?module=$moduleName&run=1")
    }

    // 인스트루먼트 테스트 실행 (에뮬레이터).
    post("/projects/{id}/quality/run-tests") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val moduleName = safeModule(form["module"], "app")
        call.respondRedirect("/projects/$id/quality?module=$moduleName&itest=1")
    }

    // 테스트 사전 준비작업(androidTest 인프라 주입) → 현재 콘솔 provider 로 요청.
    post("/projects/{id}/quality/prepare") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val moduleName = safeModule(form["module"], "app")
        val prep = runCatching { itestSvc.inspectPrep(id, moduleName) }.getOrNull()
        val prompt = buildPrepPrompt(moduleName, prep)
        val ok = runCatching { promptSender.send(id, prompt, source = "quality_prepare", ownerUserId = sess.userId) }.isSuccess
        if (!ok) {
            call.respondRedirect("/projects/$id/quality?module=$moduleName&itest=1&err=${enc("콘솔 전송에 실패했습니다.")}")
            return@post
        }
        call.respondRedirect("/projects/$id/quality?module=$moduleName&itest=1&prepared=1")
    }

    // 선택 항목(lint 이슈 또는 실패 테스트) → 현재 콘솔 provider 로 수정요청 전송.
    post("/projects/{id}/quality/fix") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val moduleName = safeModule(form["module"], "app")
        val kind = form["kind"]?.trim()?.ifBlank { null } ?: "lint"
        val backFlag = if (kind == "test") "itest=1" else "run=1"
        val selected = form.getAll("sel").orEmpty().filter { it.isNotBlank() }
        if (selected.isEmpty()) {
            call.respondRedirect("/projects/$id/quality?module=$moduleName&$backFlag&err=${enc("선택된 항목이 없습니다.")}")
            return@post
        }
        val prompt = if (kind == "test") buildTestFixPrompt(moduleName, selected)
                     else buildLintFixPrompt(moduleName, selected)
        val ok = runCatching { promptSender.send(id, prompt, source = "quality_fix", ownerUserId = sess.userId) }.isSuccess
        if (!ok) {
            call.respondRedirect("/projects/$id/quality?module=$moduleName&$backFlag&err=${enc("콘솔 전송에 실패했습니다.")}")
            return@post
        }
        call.respondRedirect("/projects/$id/quality?module=$moduleName&$backFlag&fixed=${selected.size}")
    }
}

/**
 * 선택 항목을 번호 목록으로 직렬화하되, sendPrompt 의 한도(MAX_PROMPT_BYTES=100KB)를
 * 넘지 않도록 바이트 예산(24KB)에서 자른다. lint 수정 요청은 한 번에 처리 가능한 실용
 * 상한을 별도로 두는 편이 UX 상 낫기에 예산은 보수적으로 유지한다. 잘린 개수를 함께
 * 반환해 안내 문구에 쓴다.
 */
private const val FIX_PROMPT_BUDGET_BYTES = 24 * 1024
private fun numberedWithBudget(selected: List<String>): Pair<String, Int> {
    val sb = StringBuilder()
    var used = 0
    var included = 0
    for ((i, s) in selected.withIndex()) {
        val line = "${i + 1}. $s\n"
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (used + bytes > FIX_PROMPT_BUDGET_BYTES && included > 0) break
        sb.append(line); used += bytes; included++
    }
    return sb.toString().trimEnd('\n') to (selected.size - included)
}

private fun omittedNote(omitted: Int): String =
    if (omitted > 0) "\n\n(길이 제한으로 $omitted 건은 생략했습니다. 처리 후 나머지를 다시 선택해 보내주세요.)" else ""

/** Lint 이슈 → 수정요청 프롬프트. (v1.119.0 — JsonQualityRoutes 재사용 위해 internal) */
internal fun buildLintFixPrompt(moduleName: String, selected: List<String>): String {
    val (list, omitted) = numberedWithBudget(selected)
    return """
다음은 Android Lint(:$moduleName:lintDebug) 가 검출한 품질/접근성 이슈입니다. 각 항목의 파일·위치를 열어 근본 원인을 수정해 주세요.

$list

작업 지침:
- 접근성(Accessibility) 이슈는 contentDescription 추가, 터치 영역 48dp 이상 확보, 텍스트 대비 기준 충족, 색상만으로 상태 구분 금지 원칙에 따라 고쳐주세요.
- 문자열은 strings.xml 로 분리하고 하드코딩하지 마세요.
- 수정 후 동일 lint 가 통과할 것으로 보이는 근거를 간단히 설명해 주세요.
- 각 파일을 직접 열어 수정하고, 변경 요약을 마지막에 정리해 주세요.
""".trim() + omittedNote(omitted)
}

/** 실패 인스트루먼트 테스트 → 수정요청 프롬프트. (v1.119.0 — internal) */
internal fun buildTestFixPrompt(moduleName: String, selected: List<String>): String {
    val (list, omitted) = numberedWithBudget(selected)
    return """
다음은 에뮬레이터 인스트루먼트 테스트(:$moduleName:connectedDebugAndroidTest)에서 실패한 케이스입니다. 각 테스트의 실패 원인을 분석하고 수정해 주세요.

$list

작업 지침:
- 실패 메시지를 근거로 원인이 "테스트 코드"인지 "앱 구현"인지 먼저 판단해 주세요.
- Accessibility Test Framework(ATF) 위반(터치영역 48dp 미만, 대비 부족, 라벨 누락 등)으로 인한 실패라면, 테스트가 아니라 앱 UI 를 접근성 기준에 맞게 고쳐주세요.
- Compose UI Test 실패(노드 없음/상태 불일치/Navigation)라면 해당 화면 구현 또는 테스트 셀렉터를 점검해 주세요.
- 수정 후 같은 테스트가 통과할 것으로 보이는 근거를 간단히 설명하고, 변경 요약을 마지막에 정리해 주세요.
""".trim() + omittedNote(omitted)
}

/** 인스트루먼트 테스트 사전 준비작업 → 현재 콘솔 provider 에 보낼 환경 구성 프롬프트. */
private fun buildPrepPrompt(moduleName: String, prep: InstrumentedTestService.PrepStatus?): String {
    fun mark(b: Boolean?) = when (b) { true -> "이미 있음"; false -> "없음 — 추가 필요"; null -> "확인 불가" }
    val status = if (prep == null || !prep.inspectable) "현재 상태: 모듈($moduleName) 빌드 파일을 찾지 못했습니다. 모듈명을 먼저 확인해 주세요." else """
현재 상태(자동 점검):
- src/androidTest 디렉토리: ${mark(prep.hasAndroidTestDir)}
- @Test 포함 테스트 소스: ${prep.testSourceCount}개
- testInstrumentationRunner 지정: ${mark(prep.hasTestRunner)}
- Espresso/Compose UI Test 의존성: ${mark(prep.hasUiTestDep)}
- Accessibility Test Framework(ATF): ${mark(prep.hasA11yFramework)}
""".trim()

    return """
프로젝트의 에뮬레이터 인스트루먼트 테스트(:$moduleName:connectedDebugAndroidTest) 환경을 준비해 주세요. 이 작업이 끝나면 vibe-coder-server 의 "품질" 탭에서 테스트를 바로 실행할 수 있어야 합니다.

$status

다음을 빠짐없이 구성해 주세요(이미 있는 항목은 건드리지 말고 누락만 보완):

1. 의존성(모듈 build.gradle 의 androidTestImplementation):
   - androidx.test.ext:junit
   - androidx.test.espresso:espresso-core
   - androidx.compose.ui:ui-test-junit4 (Compose 프로젝트인 경우)
   - debugImplementation 으로 androidx.compose.ui:ui-test-manifest (Compose)
   - Accessibility Test Framework: androidx.test.espresso:espresso-accessibility
   버전은 프로젝트의 기존 BOM/카탈로그(libs.versions.toml)와 정합되게 맞춰주세요.

2. android.defaultConfig.testInstrumentationRunner 를 "androidx.test.runner.AndroidJUnitRunner" 로 지정.

3. src/androidTest 에 샘플 테스트 1개를 작성:
   - Compose 프로젝트면 createAndroidComposeRule 기반 UI 테스트 1개.
   - 클래스 @Before 에서 AccessibilityChecks.enable() 를 호출해 ATF(Accessibility Scanner 동급) 동적 접근성 검사가 모든 상호작용에 적용되도록 설정.
   - 메인 화면을 띄우고 핵심 노드 존재를 assert 하는 최소 테스트로 시작.

4. 구성 후 ./gradlew :$moduleName:compileDebugAndroidTestKotlin (또는 assembleDebugAndroidTest) 로 컴파일이 통과하는지 확인하고, 추가/변경한 파일과 의존성을 마지막에 요약해 주세요.

주의: 문자열 하드코딩 금지(strings.xml), 키스토어 생성 금지 등 프로젝트 규칙을 따르세요.
""".trim()
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

/**
 * Gradle 모듈명 sanitize. `module` 파라미터가 services 의 `root.resolve(moduleName)`
 * (리포트 탐색 / prep 점검)에 쓰이므로, 경로 구분자·`..` 를 막아 path-traversal 읽기를
 * 차단한다. gradle 모듈 표기에 쓰이는 문자만 허용([A-Za-z0-9_.:-]). 위반 시 fallback.
 */
internal fun safeModule(raw: String?, fallback: String): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return fallback
    if (s.contains('/') || s.contains('\\') || s.contains("..")) return fallback
    if (!s.all { it.isLetterOrDigit() || it in "_.:-" }) return fallback
    return s
}

private object QualityTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun topCategory(cat: String): String = cat.substringBefore(':').trim()

    private fun catLabel(cat: String): String = when (topCategory(cat)) {
        "Accessibility" -> "접근성"
        "Correctness" -> "정확성"
        "Security" -> "보안"
        "Performance" -> "성능"
        "Usability" -> "사용성"
        "Internationalization", "I18N" -> "국제화"
        else -> "기타"
    }

    private fun sevClass(sev: String): String = when (sev.lowercase()) {
        "fatal", "error" -> "sev-error"
        "warning" -> "sev-warn"
        else -> "sev-info"
    }

    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        lintResult: LintQualityService.Result?,
        itestResult: InstrumentedTestService.Result?,
        emulatorReady: Boolean,
        prep: InstrumentedTestService.PrepStatus?,
        fixed: Int?,
        prepared: Boolean,
        flashErr: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val flashHtml = buildString {
            if (prepared) {
                append("""<div class="ok-banner">✓ 테스트 환경 준비작업을 콘솔로 요청했습니다. <b>콘솔</b> 탭에서 provider 가 androidTest 구성을 완료하면 이 화면을 새로고침하세요.</div>""")
            }
            if (fixed != null && fixed > 0) {
                append("""<div class="ok-banner">✓ ${fixed}개 항목을 콘솔로 전송했습니다. <b>콘솔</b> 탭에서 진행 상황을 확인하세요.</div>""")
            }
            if (!flashErr.isNullOrBlank()) {
                append("""<div class="error" style="margin-bottom:10px">⚠ ${esc(flashErr)}</div>""")
            }
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Quality",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>품질 · 접근성 검사
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

$flashHtml

${lintCard(p, moduleName, lintResult, csrf)}

${itestCard(p, moduleName, itestResult, emulatorReady, prep, csrf)}

$commonStyleAndScript
""",
            lang = lang,
            embed = embed,
        )
    }

    // ── Lint 카드 (정적) ───────────────────────────────────────────────
    private fun lintCard(
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        result: LintQualityService.Result?,
        csrf: String?,
    ): String {
        val resultHtml = when {
            result == null ->
                """<p class="hint">아래 <b>Lint 실행</b> 으로 정적 분석을 시작합니다 (최초 실행은 컴파일 포함 수십 초~수 분).</p>"""
            !result.ok -> """
                <div class="error">⚠ ${esc(result.errorMessage ?: "lint 실패")} (${result.durationMs}ms)</div>
                ${if (result.rawTail != null) """<details><summary>gradle 출력 (tail)</summary><pre class="diff-block">${esc(result.rawTail)}</pre></details>""" else ""}
            """
            result.issues.isEmpty() ->
                """<div class="ok-banner">✓ lint 이슈 없음 — 깨끗합니다! (${result.durationMs}ms)</div>"""
            else -> renderLintIssues(p, moduleName, result, csrf)
        }
        return """
<section class="card" style="margin-bottom:16px">
  <h2 style="margin:0 0 4px;font-size:16px">① 정적 분석 — Android Lint <span class="dim" style="font-size:12px;font-weight:400">(에뮬레이터 불필요)</span></h2>
  <form method="post" action="/projects/${esc(p.id)}/quality" style="display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end;margin:8px 0 12px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">Module<input name="module" value="${esc(moduleName)}" placeholder="app"></label>
    <div><button type="submit" class="primary" style="padding:8px 14px">Lint 실행</button></div>
  </form>
  $resultHtml
</section>
"""
    }

    private fun renderLintIssues(
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        result: LintQualityService.Result,
        csrf: String?,
    ): String {
        val issues = result.issues
        val errorCount = issues.count { it.severity.lowercase() in setOf("error", "fatal") }
        val warnCount = issues.count { it.severity.equals("warning", true) }
        val byTop = issues.groupingBy { topCategory(it.category) }.eachCount()
        val chipOrder = listOf("Accessibility", "Correctness", "Security", "Performance", "Usability")
            .filter { byTop.containsKey(it) } + byTop.keys.filterNot {
                it in setOf("Accessibility", "Correctness", "Security", "Performance", "Usability")
            }
        val chips = buildString {
            append("""<button type="button" class="qchip active" data-cat="*">전체 (${issues.size})</button>""")
            chipOrder.forEach { top ->
                append("""<button type="button" class="qchip" data-cat="${esc(top)}">${esc(catLabel(top))} (${byTop[top]})</button>""")
            }
        }
        val rows = issues.joinToString("") { iss ->
            val top = topCategory(iss.category)
            val loc = if (iss.file != null) "${iss.file}${if (iss.line != null) ":${iss.line}" else ""}" else "(위치 없음)"
            val selVal = "[${catLabel(top)}/${iss.id}] $loc — ${iss.message}"
            """
            <label class="qrow" data-cat="${esc(top)}">
              <input type="checkbox" name="sel" value="${esc(selVal)}">
              <span class="qbadge ${sevClass(iss.severity)}">${esc(iss.severity)}</span>
              <span class="qcat">${esc(catLabel(top))}</span>
              <span class="qbody">
                <code class="qid">${esc(iss.id)}</code>
                <span class="qmsg">${esc(iss.message)}</span>
                <span class="qloc">${esc(loc)}</span>
              </span>
            </label>
            """
        }
        return """
<div class="ok-banner">✓ ${issues.size}개 이슈 검출 — <span class="sev-error">에러 $errorCount</span> · <span class="sev-warn">경고 $warnCount</span> (${result.durationMs}ms)</div>
<div class="qchips" style="margin:10px 0 6px">$chips</div>
<form method="post" action="/projects/${esc(p.id)}/quality/fix" class="qfix">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="module" value="${esc(moduleName)}">
  <input type="hidden" name="kind" value="lint">
  <div class="qtoolbar">
    <button type="button" class="ghost qselall">전체 선택</button>
    <button type="button" class="ghost qselnone">선택 해제</button>
    <span class="dim qcount" style="margin-left:auto;font-size:12px"></span>
    <button type="submit" class="primary">선택 항목 → 콘솔로 수정요청 보내기</button>
  </div>
  <div class="qlist">$rows</div>
</form>
"""
    }

    // ── 인스트루먼트 테스트 카드 (에뮬레이터) ────────────────────────────
    private fun itestCard(
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        result: InstrumentedTestService.Result?,
        emulatorReady: Boolean,
        prep: InstrumentedTestService.PrepStatus?,
        csrf: String?,
    ): String {
        val emuPill = if (emulatorReady)
            """<span class="qbadge sev-info" style="background:#16331f;color:#86efac">에뮬레이터 준비됨</span>"""
        else
            """<span class="qbadge sev-warn">에뮬레이터 미실행</span>"""
        // 실행 가능 = 에뮬레이터 부팅 + 테스트 인프라 준비.
        val testReady = prep?.runnable == true
        val runBtnDisabled = if (emulatorReady && testReady) "" else "disabled"
        val resultHtml = when {
            result == null -> {
                val warn = if (emulatorReady) "" else
                    """<div class="error" style="margin-top:8px">⚠ 빌드환경 &gt; Emulator 에서 컨테이너 에뮬레이터를 먼저 기동하세요. 부팅 완료 후 실행할 수 있습니다.</div>"""
                """<p class="hint">Compose UI Test / Espresso / ATF(동적 접근성) 테스트를 <b>빌드환경의 컨테이너 에뮬레이터</b>(serial 고정)에서 실행합니다 (최대 수 분).</p>$warn"""
            }
            !result.ok -> """
                <div class="error">⚠ ${esc(result.errorMessage ?: "테스트 실행 실패")} (${result.durationMs}ms)</div>
                ${if (result.rawTail != null) """<details><summary>gradle 출력 (tail)</summary><pre class="diff-block">${esc(result.rawTail)}</pre></details>""" else ""}
            """
            result.failed == 0 ->
                """<div class="ok-banner">✓ 전체 통과 — ${result.passed}개 통과${if (result.skipped > 0) ", ${result.skipped}개 스킵" else ""} (${result.durationMs}ms)</div>"""
            else -> renderTestFailures(p, moduleName, result, csrf)
        }
        return """
<section class="card">
  <h2 style="margin:0 0 4px;font-size:16px">② 인스트루먼트 테스트 — connectedDebugAndroidTest $emuPill</h2>
  ${prepBlock(p, moduleName, prep, csrf)}
  <form method="post" action="/projects/${esc(p.id)}/quality/run-tests" style="display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end;margin:8px 0 12px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">Module<input name="module" value="${esc(moduleName)}" placeholder="app"></label>
    <div><button type="submit" class="primary" style="padding:8px 14px" $runBtnDisabled>테스트 실행</button></div>
  </form>
  $resultHtml
</section>
"""
    }

    /** 사전 준비 상태 체크리스트 + "준비작업" 버튼. */
    private fun prepBlock(
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        prep: InstrumentedTestService.PrepStatus?,
        csrf: String?,
    ): String {
        if (prep == null || !prep.inspectable) {
            return """<div class="error" style="margin:8px 0">⚠ 모듈 <code>${esc(moduleName)}</code> 의 빌드 파일을 찾지 못했습니다. 모듈명을 확인하세요.</div>"""
        }
        fun row(ok: Boolean, label: String, extra: String = "") =
            """<li><span class="${if (ok) "pchk-ok" else "pchk-no"}">${if (ok) "✓" else "✗"}</span> $label${if (extra.isNotBlank()) " <span class=\"dim\">$extra</span>" else ""}</li>"""
        val checklist = buildString {
            append("""<ul class="qprep-list">""")
            append(row(prep.hasAndroidTestDir, "androidTest 디렉토리"))
            append(row(prep.testSourceCount > 0, "@Test 테스트 소스", "(${prep.testSourceCount}개)"))
            append(row(prep.hasTestRunner, "testInstrumentationRunner 지정"))
            append(row(prep.hasUiTestDep, "Espresso / Compose UI Test 의존성"))
            append(row(prep.hasA11yFramework, "Accessibility Test Framework (동적 접근성)"))
            append("</ul>")
        }
        val statusPill = when {
            prep.fullyReady -> """<span class="qbadge sev-info" style="background:#16331f;color:#86efac">준비 완료 (ATF 포함)</span>"""
            prep.runnable -> """<span class="qbadge sev-warn">실행 가능 (ATF 권장)</span>"""
            else -> """<span class="qbadge sev-error">준비 필요</span>"""
        }
        val btnLabel = if (prep.runnable) "환경 보완 요청" else "테스트 환경 준비작업 → 콘솔로 요청"
        return """
<div class="qprep">
  <div class="qprep-head">테스트 환경 준비 상태 $statusPill</div>
  $checklist
  <form method="post" action="/projects/${esc(p.id)}/quality/prepare" style="margin-top:6px">
    ${CsrfTokens.hiddenInput(csrf)}
    <input type="hidden" name="module" value="${esc(moduleName)}">
    <button type="submit" class="ghost">$btnLabel</button>
    <span class="dim" style="font-size:11px;margin-left:6px">현재 콘솔 provider 가 androidTest 의존성·러너·AccessibilityChecks 샘플 테스트를 구성합니다.</span>
  </form>
</div>
"""
    }

    private fun renderTestFailures(
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        moduleName: String,
        result: InstrumentedTestService.Result,
        csrf: String?,
    ): String {
        // 실패/에러만 선택 대상으로 표시(통과는 요약 배너로). 실패가 핵심이라 위로.
        val failures = result.cases.filter {
            it.outcome == InstrumentedTestService.Outcome.FAILED || it.outcome == InstrumentedTestService.Outcome.ERROR
        }
        val rows = failures.joinToString("") { tc ->
            val short = tc.className.substringAfterLast('.')
            val selVal = "[${tc.outcome}] ${tc.className}#${tc.name} — ${tc.message ?: "(메시지 없음)"}"
            """
            <label class="qrow">
              <input type="checkbox" name="sel" value="${esc(selVal)}">
              <span class="qbadge sev-error">${esc(tc.outcome.name)}</span>
              <span class="qbody">
                <code class="qid">${esc(short)}#${esc(tc.name)}</code>
                <span class="qmsg">${esc(tc.message ?: "(메시지 없음)")}</span>
              </span>
            </label>
            """
        }
        return """
<div class="ok-banner">총 ${result.total}개 — <span class="sev-error">실패 ${result.failed}</span> · 통과 ${result.passed}${if (result.skipped > 0) " · 스킵 ${result.skipped}" else ""} (${result.durationMs}ms)</div>
<form method="post" action="/projects/${esc(p.id)}/quality/fix" class="qfix" style="margin-top:8px">
  ${CsrfTokens.hiddenInput(csrf)}
  <input type="hidden" name="module" value="${esc(moduleName)}">
  <input type="hidden" name="kind" value="test">
  <div class="qtoolbar">
    <button type="button" class="ghost qselall">전체 선택</button>
    <button type="button" class="ghost qselnone">선택 해제</button>
    <span class="dim qcount" style="margin-left:auto;font-size:12px"></span>
    <button type="submit" class="primary">선택 실패 → 콘솔로 수정요청 보내기</button>
  </div>
  <div class="qlist">$rows</div>
</form>
"""
    }

    private val commonStyleAndScript = """
<p class="hint" style="margin-top:14px;font-size:12px">
  ①은 Google 공식 <b>Android Lint</b> 정적 분석(에뮬레이터 불필요), ②는 에뮬레이터에서
  <b>Compose UI Test / Espresso / ATF</b>(Accessibility Scanner 동급 동적 접근성) 를 실행합니다.
  ATF 동적 검사는 테스트에 <code>AccessibilityChecks.enable()</code> 가 포함돼야 동작합니다.
</p>
<style>
  .qchips { display:flex; flex-wrap:wrap; gap:6px; }
  .qchip { background:#2a2f3a; border:1px solid #3a4150; color:#cfd6e4; border-radius:14px; padding:4px 12px; font-size:12px; cursor:pointer; }
  .qchip.active { background:#3b82f6; border-color:#3b82f6; color:#fff; }
  .qtoolbar { display:flex; align-items:center; gap:8px; margin:8px 0; }
  .qlist { display:flex; flex-direction:column; gap:4px; }
  .qrow { display:flex; align-items:flex-start; gap:8px; padding:7px 9px; border:1px solid #2a2f3a; border-radius:7px; background:#1c2027; cursor:pointer; }
  .qrow:hover { border-color:#3a4150; }
  .qrow input { margin-top:3px; }
  .qbadge { font-size:10px; padding:1px 6px; border-radius:4px; white-space:nowrap; }
  .sev-error { color:#f87171; } .qbadge.sev-error { background:#3b1d1d; color:#fca5a5; }
  .sev-warn  { color:#fbbf24; } .qbadge.sev-warn  { background:#3a2f12; color:#fcd34d; }
  .qbadge.sev-info { background:#1e2a3a; color:#93c5fd; }
  .qcat { font-size:11px; color:#9aa4b6; white-space:nowrap; min-width:44px; }
  .qbody { display:flex; flex-direction:column; gap:1px; min-width:0; }
  .qid { font-size:11px; color:#7dd3fc; word-break:break-all; }
  .qmsg { font-size:13px; color:#e5e9f0; }
  .qloc { font-size:11px; color:#8b93a3; word-break:break-all; }
  .qprep { border:1px dashed #3a4150; border-radius:8px; padding:9px 11px; margin:6px 0 10px; background:#181c22; }
  .qprep-head { font-size:13px; color:#cfd6e4; margin-bottom:4px; }
  .qprep-list { list-style:none; margin:4px 0; padding:0; display:flex; flex-direction:column; gap:2px; }
  .qprep-list li { font-size:12px; color:#cbd2e0; }
  .pchk-ok { color:#4ade80; font-weight:700; margin-right:5px; }
  .pchk-no { color:#f87171; font-weight:700; margin-right:5px; }
</style>
<script>
(function(){
  document.querySelectorAll('form.qfix').forEach(function(root){
    var chips = root.parentNode.querySelectorAll('.qchip');
    var rows = root.querySelectorAll('.qrow');
    var count = root.querySelector('.qcount');
    function refresh(){
      var n = root.querySelectorAll('input[name=sel]:checked').length;
      if(count) count.textContent = n ? (n + '개 선택됨') : '';
    }
    chips.forEach(function(c){ c.addEventListener('click', function(){
      chips.forEach(function(x){ x.classList.remove('active'); });
      c.classList.add('active');
      var cat = c.getAttribute('data-cat');
      rows.forEach(function(r){
        r.style.display = (cat === '*' || r.getAttribute('data-cat') === cat) ? '' : 'none';
      });
    }); });
    var selAll = root.querySelector('.qselall'); if(selAll) selAll.addEventListener('click', function(){
      rows.forEach(function(r){ if(r.style.display !== 'none'){ r.querySelector('input').checked = true; } }); refresh();
    });
    var selNone = root.querySelector('.qselnone'); if(selNone) selNone.addEventListener('click', function(){
      rows.forEach(function(r){ r.querySelector('input').checked = false; }); refresh();
    });
    root.addEventListener('change', refresh);
    root.addEventListener('submit', function(e){
      if(root.querySelectorAll('input[name=sel]:checked').length === 0){
        e.preventDefault(); alert('콘솔로 보낼 항목을 1개 이상 선택하세요.');
      }
    });
  });
})();
</script>
"""
}
