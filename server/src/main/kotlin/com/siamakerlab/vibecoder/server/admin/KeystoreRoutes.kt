package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRow
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptSender
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

/** flash 메시지 / path segment 를 query 에 안전하게 싣기 위한 percent-encoding. */
private fun String.enc(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

/**
 * v1.5.0 — 키스토어 관리 라우트.
 *
 * SSR:
 *   GET  /settings/keystores                              — 목록 + create form
 *   POST /settings/keystores                              — 새 키스토어 set 생성
 *   POST /settings/keystores/{pkg}/delete                 — 키스토어 set 삭제
 *   POST /settings/keystores/{pkg}/apply/{projectId}      — v1.8.0 현재 콘솔 provider prompt 로
 *                                                            프로젝트 build.gradle.kts 자동 수정
 */
fun Routing.keystoreRoutes(
    authDeps: AdminRoutesDeps,
    service: KeystoreService,
    projectRepo: ProjectRepository,
    promptSender: ConsolePromptSender,
) {
    get("/settings/keystores") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val entries = runCatching { service.list() }.getOrDefault(emptyList())
        // v1.8.0 — Apply-to-project 드롭다운에 노출할 프로젝트 목록 (scratch 제외).
        val projects = runCatching { projectRepo.list() }.getOrDefault(emptyList())
            .filter { it.id != "__scratch__" }
        val flash = call.request.queryParameters["flash"]
        call.respondText(
            KeystoreTemplates.page(
                username = sess.username,
                entries = entries,
                projects = projects,
                defaults = authDeps.config.keystore.defaults,
                flash = flash,
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/settings/keystores") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        // v1.28.1 — 폼은 csrf 를 hidden input(body)으로 보낸다. body 의 `_csrf` 를
        // 검증하고, 실패 시 JSON 403 대신 SSR flash redirect (페이지가 JSON 으로
        // 깨지지 않도록). 이전 verifyCsrfFromQueryOrHeader 는 query/header 만 봐서
        // body hidden input 과 불일치 → CSRF 항상 실패 + JSON 응답으로 UI 붕괴.
        val form = call.receiveParameters()
        if (!com.siamakerlab.vibecoder.server.auth.CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("/settings/keystores?flash=err:csrf")
            return@post
        }
        val pkg = form["packageName"]?.trim().orEmpty()
        val req = CreateKeystoreRequest(
            packageName = pkg,
            name = form["name"]?.trim().orEmpty(),
            organization = form["organization"]?.trim().orEmpty(),
            unit = form["unit"]?.trim().orEmpty(),
            country = form["country"]?.trim().orEmpty(),
            state = form["state"]?.trim().orEmpty(),
            city = form["city"]?.trim().orEmpty(),
            password = form["password"]?.trim().orEmpty(),
            validityYears = form["validityYears"]?.trim()?.toIntOrNull(),
            admob = admobFromForm(form),
        )
        val flash = runCatching { service.create(req) }
            .map { "ok:${it.packageName}" }
            .getOrElse { "err:${it.message ?: "create_failed"}" }
        call.respondRedirect("/settings/keystores?flash=$flash")
    }

    /**
     * v1.57.0 — 프로젝트 페이지/빌드 탭 안에서 바로 키스토어 생성.
     *
     * packageName 은 **프로젝트의 것으로 강제** — 폼 입력을 신뢰하지 않고
     * `project.packageName` 으로 set 을 만들어 packageName prefix 매칭으로 자동 연결.
     * 성공/실패 모두 해당 프로젝트의 빌드 페이지로 redirect 해 곧바로 빌드 가능.
     * 비밀번호는 매번 입력받고, DN 메타는 [KeystoreService.effectiveDefaults] prefill.
     */
    post("/projects/{id}/keystore") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        val projectId = call.parameters["id"].orEmpty()
        val backBuilds = "/projects/${projectId.enc()}/builds"
        val form = call.receiveParameters()
        if (!com.siamakerlab.vibecoder.server.auth.CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("$backBuilds?err=${Messages.t(sess.language, "ks.flash.csrfExpired").enc()}")
            return@post
        }
        val project = projectRepo.findById(projectId)
        if (project == null) {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", projectId).enc()}")
            return@post
        }
        // packageName 은 프로젝트 메타에서 — 폼의 hidden/readonly 값은 무시 (자동 연결 보장).
        val req = CreateKeystoreRequest(
            packageName = project.packageName,
            name = form["name"]?.trim().orEmpty(),
            organization = form["organization"]?.trim().orEmpty(),
            unit = form["unit"]?.trim().orEmpty(),
            country = form["country"]?.trim().orEmpty(),
            state = form["state"]?.trim().orEmpty(),
            city = form["city"]?.trim().orEmpty(),
            password = form["password"]?.trim().orEmpty(),
            validityYears = form["validityYears"]?.trim()?.toIntOrNull(),
            admob = admobFromForm(form),
        )
        val result = runCatching { service.create(req) }
        if (result.isSuccess) {
            val msg = Messages.t(sess.language, "ks.project.created", project.packageName)
            call.respondRedirect("$backBuilds?ok=${msg.enc()}")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "create_failed"
            log.warn(result.exceptionOrNull()) { "project keystore create failed: $projectId / ${project.packageName}" }
            val msg = Messages.t(sess.language, "ks.project.createFailed", reason)
            call.respondRedirect("$backBuilds?err=${msg.enc()}")
        }
    }

    post("/settings/keystores/{pkg}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        // v1.28.1 — body `_csrf` 검증 + 실패 시 SSR redirect (위 create 와 동일).
        val form = call.receiveParameters()
        if (!com.siamakerlab.vibecoder.server.auth.CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("/settings/keystores?flash=err:csrf")
            return@post
        }
        val pkg = call.parameters["pkg"].orEmpty()
        runCatching { service.delete(pkg) }
            .onFailure { log.warn(it) { "keystore delete failed for $pkg" } }
        call.respondRedirect("/settings/keystores?flash=deleted:$pkg")
    }

    /**
     * v1.8.0 — Phase 2: 프로젝트 build.gradle.kts 자동 수정 prompt 를 현재 콘솔 provider 에 전송.
     *
     * 키스토어 set 이 존재하고 프로젝트가 존재하면, 정형화된 한국어 prompt 를
     * [AgentRouter.sendPrompt] 으로 보내고 콘솔 페이지로 redirect.
     * 선택된 provider 가 read/edit 도구로 build.gradle.kts 의 `signingConfigs.{debug,release}` 를
     * `/home/vibe/keystores/<pkg>-keystore.properties` 기반으로 영구 적용.
     *
     * 비밀번호는 prompt 본문에 절대 포함하지 않음 — properties 파일 경로만 전달.
     * provider 가 `Properties().load(FileInputStream(...))` 로 런타임에 읽도록 가이드.
     */
    post("/settings/keystores/{pkg}/apply/{projectId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        // v1.28.1 — body `_csrf` 검증 + 실패 시 SSR redirect (위 create 와 동일).
        val form = call.receiveParameters()
        if (!com.siamakerlab.vibecoder.server.auth.CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("/settings/keystores?flash=err:csrf")
            return@post
        }
        val pkg = call.parameters["pkg"].orEmpty()
        val projectId = call.parameters["projectId"].orEmpty()

        val entry = service.get(pkg)
        if (entry == null) {
            call.respondRedirect("/settings/keystores?flash=err:keystore_not_found")
            return@post
        }
        val project = projectRepo.findById(projectId)
        if (project == null) {
            call.respondRedirect("/settings/keystores?flash=err:project_not_found")
            return@post
        }

        val prompt = buildApplySigningPrompt(project.id, project.moduleName, service, entry)
        val sent = runCatching {
            promptSender.send(projectId, prompt, source = "settings_keystore_apply_signing", ownerUserId = sess.userId)
        }.onFailure { log.warn(it) { "apply-signing prompt failed for $projectId / $pkg" } }
            .isSuccess

        if (sent) {
            // 콘솔 페이지로 이동 — 사용자가 선택 provider 응답을 즉시 볼 수 있게.
            call.respondRedirect("/projects/$projectId/console?flash=signing_applied:$pkg")
        } else {
            call.respondRedirect("/settings/keystores?flash=err:agent_send_failed")
        }
    }
}

/**
 * v1.8.0 — 선택된 console provider 가 일관된 결과를 만들도록 정형화된 prompt.
 *
 * 매개변수만 치환되고 본문은 고정 — 같은 (project, keystore) 조합엔 매번 같은
 * 지시가 전송됨. 비밀번호는 본문에 포함되지 않으며, properties 파일 경로 + 표준
 * Gradle 패턴 (Properties.load + signingConfigs) 만 안내.
 */
/**
 * v1.94.0 — 키스토어 생성 폼의 AdMob 입력 수집. App ID(단일) + 6종 유형별 다중 unit ID
 * (같은 name 의 input 여러 개 → form.getAll). App ID·unit 모두 비면 null (파일 미생성).
 */
private fun admobFromForm(form: io.ktor.http.Parameters): AdmobIds? {
    // v1.98.2 — 콤마는 직렬화 구분자 → 입력 내 콤마도 split 흡수 + trim + 빈값/중복 제거.
    fun multi(name: String) = form.getAll(name).orEmpty()
        .flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    return AdmobIds(
        appId = form["admobAppId"]?.trim().orEmpty(),
        appOpenUnitIds = multi("admobAppOpenUnitId"),
        bannerUnitIds = multi("admobBannerUnitId"),
        nativeUnitIds = multi("admobNativeUnitId"),
        interstitialUnitIds = multi("admobInterstitialUnitId"),
        rewardedUnitIds = multi("admobRewardedUnitId"),
        rewardedInterstitialUnitIds = multi("admobRewardedInterstitialUnitId"),
    ).takeIf { !it.isBlank }
}

internal fun buildApplySigningPrompt(
    projectId: String,
    moduleName: String,
    service: KeystoreService,
    entry: KeystoreEntry,
): String {
    val pkg = entry.packageName
    val propsPath = service.propertiesPath(pkg)
    val storePath = service.storeFilePath(pkg)
    val debugStorePath = storePath.parent.resolve("$pkg-debug.keystore")
    val debugVariantLine = if (entry.debugExists) {
        "- 디버그 키스토어도 같은 set 에 있음 (`$debugStorePath`). debug variant 도 같은 properties 의 password / alias 를 재사용하되 storeFile 만 `$pkg-debug.keystore` 로 지정."
    } else {
        "- 디버그 전용 키스토어 파일은 없음. 필요 시 release 와 같은 storeFile 을 debug 에도 재사용 (단일사용자 LAN 도구라 안전)."
    }
    return """
[vibe-coder-server / 키스토어 자동 적용]

프로젝트 `$projectId` 의 안드로이드 모듈 `$moduleName` 의 build.gradle.kts 를 다음 키스토어로 영구 서명되게 수정해 주세요.

대상 패키지: $pkg
키스토어 set (호스트 영속 볼륨 `/home/vibe/keystores/` 안에 위치):
- release keystore: $storePath
- properties     : $propsPath  (storeFile / storePassword / keyAlias / keyPassword 4종 평문 포함)
$debugVariantLine

수정 절차:
1. `$moduleName/build.gradle.kts` 의 `android { ... }` 블록 위에 properties 로더 추가
   (이미 있으면 중복 방지):

   ```kotlin
   import java.util.Properties
   import java.io.FileInputStream

   val signingPropsFile = file("$propsPath")
   val signingProps = Properties().apply {
       if (signingPropsFile.exists()) {
           FileInputStream(signingPropsFile).use { load(it) }
       }
   }
   ```

2. `android { ... }` 안에 `signingConfigs { ... }` 블록을 작성/갱신:

   ```kotlin
   signingConfigs {
       create("release") {
           if (signingPropsFile.exists()) {
               storeFile = file(signingProps.getProperty("storeFile"))
               storePassword = signingProps.getProperty("storePassword")
               keyAlias = signingProps.getProperty("keyAlias")
               keyPassword = signingProps.getProperty("keyPassword")
           }
       }
       getByName("debug") {
           if (signingPropsFile.exists()) {
               storeFile = file(signingProps.getProperty("storeFile"))
               storePassword = signingProps.getProperty("storePassword")
               keyAlias = signingProps.getProperty("keyAlias")
               keyPassword = signingProps.getProperty("keyPassword")
           }
       }
   }
   ```

3. `buildTypes { release { ... } }` 안에 `signingConfig = signingConfigs.getByName("release")` 가 들어가게.
4. 빈 `signingConfigs` 또는 default debug 만 남은 기존 블록은 위 패턴으로 통합. groovy DSL (`build.gradle`) 이면 같은 의미의 groovy 문법으로 작성.
5. KTS 임포트는 파일 상단의 plugins 블록 위에 모아 두기.

규칙:
- 비밀번호 / alias 평문을 build.gradle.kts 에 하드코딩하지 말 것. **반드시 properties 파일 경로만 사용**.
- 변경은 `$moduleName/build.gradle.kts` 한 파일에만. 다른 파일 수정 금지.
- 수정 후 어떤 라인이 추가됐는지 diff 형태로 알려 주세요.
- 이미 같은 properties 파일을 가리키는 signingConfigs 가 있으면 "이미 적용되어 있어 변경 없음" 으로 회신.

작업 완료 후 사용자가 빌드 (assembleDebug 또는 assembleRelease) 를 실행할 예정이라, 위 절차만 정확히 처리하고 빌드는 자동 실행하지 마세요.
""".trim()
}

/**
 * v1.102.0 — 업로드된 키스토어 staging 배치 + 서명 적용 프롬프트(한 turn 일괄).
 *
 * 서버는 파일을 [KeystoreStageResult.stagingDir] 에 규약 파일명으로만 저장했고, 이 프롬프트가
 * 선택된 콘솔 provider 에게 (1) staging → 최종 키스토어 디렉토리로 이동(기존 파일 백업 포함),
 * (2) staging 정리, (3) build.gradle.kts 서명 적용 을 한 번에 시킨다.
 */
internal fun buildKeystorePlacementPrompt(
    projectId: String,
    moduleName: String,
    packageName: String,
    service: KeystoreService,
    stage: KeystoreStageResult,
): String {
    val ksDir = service.keystoreDirPath()
    val staging = stage.stagingDir
    val finalProps = ksDir.resolve("$packageName-keystore.properties")
    val finalDebugProps = ksDir.resolve("$packageName-debug-keystore.properties")
    val moves = stage.stagedNames.joinToString("\n") { name ->
        "   - \"$staging/$name\"  →  \"$ksDir/$name\""
    }
    val hasReleaseProps = stage.propertiesFile != null || service.propertiesPath(packageName).let { java.nio.file.Files.exists(it) }
    val hasDebugProps = stage.debugPropertiesFile != null || service.debugPropertiesPath(packageName).let { java.nio.file.Files.exists(it) }
    val signingStep = if (hasReleaseProps || hasDebugProps) {
        """
3) 서명 적용: `$moduleName/build.gradle.kts` 의 signingConfigs 를 properties 파일로 적용.
   - release: `$finalProps`
   - debug: `$finalDebugProps` 가 있으면 우선 사용하고, 없으면 release properties 로 fallback.
   - properties 는 storeFile / storePassword / keyAlias / keyPassword 4종 평문 포함.

   - `android { ... }` 블록 위에 properties 로더 추가(이미 있으면 중복 방지):

     ```kotlin
     import java.util.Properties
     import java.io.FileInputStream

     val signingPropsFile = file("$finalProps")
     val signingProps = Properties().apply {
         if (signingPropsFile.exists()) { FileInputStream(signingPropsFile).use { load(it) } }
     }
     val debugSigningPropsFile = file("$finalDebugProps")
     val debugSigningProps = Properties().apply {
         if (debugSigningPropsFile.exists()) {
             FileInputStream(debugSigningPropsFile).use { load(it) }
         } else if (signingPropsFile.exists()) {
             putAll(signingProps)
         }
     }
     ```

   - `android { ... }` 안에 `signingConfigs { ... }` 작성/갱신:

     ```kotlin
     signingConfigs {
         create("release") {
             if (signingPropsFile.exists()) {
                 storeFile = file(signingProps.getProperty("storeFile"))
                 storePassword = signingProps.getProperty("storePassword")
                 keyAlias = signingProps.getProperty("keyAlias")
                 keyPassword = signingProps.getProperty("keyPassword")
             }
         }
         getByName("debug") {
             if (debugSigningProps.isNotEmpty()) {
                 storeFile = file(debugSigningProps.getProperty("storeFile"))
                 storePassword = debugSigningProps.getProperty("storePassword")
                 keyAlias = debugSigningProps.getProperty("keyAlias")
                 keyPassword = debugSigningProps.getProperty("keyPassword")
             }
         }
     }
     ```

   - `buildTypes { release { ... } }` 안에 `signingConfig = signingConfigs.getByName("release")` 포함.
   - 비밀번호/alias 평문을 build.gradle.kts 에 하드코딩 금지 — properties 경로만 사용.
   - 변경은 `$moduleName/build.gradle.kts` 한 파일에만(키스토어 이동 제외). groovy DSL 이면 동일 의미로.
""".trimEnd()
    } else {
        """
3) 서명 적용: 이번 업로드에는 properties 파일이 없으므로 build.gradle.kts 서명 수정은 생략하세요
   (필요 시 사용자가 properties 를 포함해 재업로드하거나 키스토어 탭의 '서명 적용' 을 사용).
""".trimEnd()
    }
    return """
[vibe-coder-server / 키스토어 업로드 배치 + 서명 적용]

프로젝트 `$projectId` (모듈 `$moduleName`, 패키지 `$packageName`) 의 키스토어 파일이 업로드되어
staging 디렉토리에 규약 파일명으로 준비되었습니다:
  staging: $staging

아래를 **순서대로** 정확히 한 turn 에 수행하세요 (셸 명령 + build.gradle.kts 수정).

1) 배치(이동): staging 의 각 파일을 최종 키스토어 디렉토리 `$ksDir/` 로 이동(mv)하세요.
   - 같은 이름의 기존 파일이 있으면 **덮어쓰기 전에** 타임스탬프 백업
     (`<파일명>.bak.<YYYYMMDD-HHMMSS>`) 을 먼저 만드세요.
     (키스토어 분실 = 같은 키로 앱 업데이트 영구 불가 — 반드시 백업 먼저.)
   이동 매핑:
$moves

2) 정리: 비워진 staging 디렉토리(`$staging`)를 삭제하세요.
$signingStep

4) 결과 요약: 이동/백업한 파일 목록과 build.gradle.kts 변경 diff 를 보고하세요.
   빌드(assembleDebug/Release)는 자동 실행하지 말고 사용자에게 맡기세요.
""".trim()
}

internal object KeystoreTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        entries: List<KeystoreEntry>,
        projects: List<ProjectRow>,
        defaults: KeystoreDefaults,
        flash: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val csrfHidden = csrf?.let { """<input type="hidden" name="_csrf" value="${esc(it)}">""" } ?: ""
        val flashHtml = when {
            flash == null -> ""
            flash.startsWith("ok:") -> """<div class="flash ok">✓ ${esc(t("ks.flash.created"))} <code>${esc(flash.removePrefix("ok:"))}</code></div>"""
            flash.startsWith("deleted:") -> """<div class="flash ok">✓ ${esc(t("ks.flash.deleted"))} <code>${esc(flash.removePrefix("deleted:"))}</code></div>"""
            flash.startsWith("applied:") -> """<div class="flash ok">✓ ${esc(t("ks.flash.applied"))} <code>${esc(flash.removePrefix("applied:"))}</code></div>"""
            flash == "err:csrf" -> """<div class="flash err">⚠ ${esc(t("ks.flash.csrfExpired"))}</div>"""
            flash.startsWith("err:") -> """<div class="flash err">⚠ ${esc(flash.removePrefix("err:"))}</div>"""
            else -> ""
        }

        val rows = if (entries.isEmpty()) {
            """<tr><td colspan="5" class="dim" style="text-align:center;padding:18px">${esc(t("ks.empty"))}</td></tr>"""
        } else entries.joinToString("\n") { e ->
            val files = buildList {
                if (e.releaseExists) add("release")
                if (e.debugExists) add("debug")
                if (e.propertiesExists) add(".properties")
                if (e.admobExists) add("admob")
            }.joinToString(" · ")
            // v1.8.0 — Apply to project 드롭다운. packageName 이 일치하는 프로젝트가 있으면
            // 그 옵션을 최상단으로 (recommended), 나머지는 그 뒤에. 프로젝트가 없으면 비활성 안내.
            val applyCell = if (projects.isEmpty()) {
                """<span class="dim" style="font-size:12px">${esc(t("ks.apply.noProjects"))}</span>"""
            } else {
                val matching = projects.filter { it.packageName == e.packageName }
                val others = projects.filter { it.packageName != e.packageName }
                val opts = buildString {
                    if (matching.isNotEmpty()) {
                        append("""<optgroup label="${esc(t("ks.apply.matching"))}">""")
                        matching.forEach { p ->
                            append("""<option value="${esc(p.id)}">${esc(p.name)} (${esc(p.id)})</option>""")
                        }
                        append("</optgroup>")
                    }
                    if (others.isNotEmpty()) {
                        append("""<optgroup label="${esc(t("ks.apply.others"))}">""")
                        others.forEach { p ->
                            append("""<option value="${esc(p.id)}">${esc(p.name)} (${esc(p.packageName)})</option>""")
                        }
                        append("</optgroup>")
                    }
                }
                val confirmMsg = t("ks.apply.confirm")
                """<form method="post" class="ks-apply-form" data-pkg="${esc(e.packageName)}"
                          style="display:inline-flex;gap:6px;align-items:center"
                          onsubmit="this.action = '/settings/keystores/${esc(e.packageName)}/apply/' + this.projectId.value; return confirm('${esc(confirmMsg)}')">
                      $csrfHidden
                      <select name="projectId" required style="padding:6px;font-size:12px">
                        <option value="">${esc(t("ks.apply.selectProject"))}</option>
                        $opts
                      </select>
                      <button type="submit" class="chip chip-action" style="background:#1e40af;color:#fff;font-size:12px">
                        ${esc(t("ks.apply.button"))}
                      </button>
                    </form>"""
            }
            """<tr>
              <td><code>${esc(e.packageName)}</code></td>
              <td class="dim">${esc(files)}</td>
              <td class="dim" style="font-size:12px">${esc(AdminTemplates.fmtTs(e.createdAt, lang))}</td>
              <td>$applyCell</td>
              <td style="text-align:right">
                <form method="post" action="/settings/keystores/${esc(e.packageName)}/delete"
                      style="display:inline" onsubmit="return confirm('${esc(t("ks.confirm.delete"))}')">
                  $csrfHidden
                  <button type="submit" class="chip chip-action" style="background:#7f1d1d;color:#fff">
                    ${esc(t("ks.delete"))}
                  </button>
                </form>
              </td>
            </tr>"""
        }

        val passwordPrefilled = if (defaults.defaultPassword.isNotBlank())
            """value="${esc(defaults.defaultPassword)}"""" else ""

        return AdminTemplates.shell(
            title = t("ks.title"),
            username = username,
            currentPath = "/settings/keystores",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("ks.title"))}</h1>
    <a href="/settings" class="chip chip-link">${esc(t("ks.backToSettings"))}</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("ks.intro"))}</p>
</header>

$flashHtml

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("ks.existing"))}</h2>
  <table style="width:100%;border-collapse:collapse">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">${esc(t("ks.col.package"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("ks.col.files"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("ks.col.created"))}</th>
        <th style="text-align:left;padding:8px">${esc(t("ks.col.apply"))}</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      $rows
    </tbody>
  </table>
</div>

<div class="card">
  <h2 style="margin-top:0">${esc(t("ks.create.title"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 12px">${esc(t("ks.create.hint"))}</p>
  <form method="post" action="/settings/keystores">
    $csrfHidden
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <label style="grid-column:1/3">
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.package"))}</div>
        <input name="packageName" required placeholder="com.siamakerlab.myapp"
               pattern="[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+"
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.password"))}</div>
        <input name="password" type="text" required $passwordPrefilled
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.validity"))}</div>
        <input name="validityYears" type="number" min="1" max="100"
               value="${defaults.validityYears}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.name"))}</div>
        <input name="name" value="${esc(defaults.name)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.organization"))}</div>
        <input name="organization" value="${esc(defaults.organization)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.unit"))}</div>
        <input name="unit" value="${esc(defaults.unit)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.country"))}</div>
        <input name="country" value="${esc(defaults.country)}" maxlength="2"
               style="width:100%;padding:8px;text-transform:uppercase">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.state"))}</div>
        <input name="state" value="${esc(defaults.state)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.field.city"))}</div>
        <input name="city" value="${esc(defaults.city)}" style="width:100%;padding:8px">
      </label>
    </div>

    <details style="margin-top:14px">
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

    <p class="dim" style="font-size:12px;margin:14px 0 8px">${esc(t("ks.create.warn"))}</p>
    <button type="submit" class="chip chip-action" style="background:#1e40af;color:#fff">
      ${esc(t("ks.create.button"))}
    </button>
  </form>
</div>

<div class="card" style="background:#1a1a0a;border-color:#525200;margin-top:14px">
  <h2 style="margin-top:0">${esc(t("ks.usage.title"))}</h2>
  <p>${esc(t("ks.usage.body"))}</p>
  <ol style="margin:8px 0 0 18px">
    <li>${esc(t("ks.usage.step1"))}</li>
    <li>${esc(t("ks.usage.step2"))}</li>
    <li>${esc(t("ks.usage.step3"))}</li>
  </ol>
</div>
""",
        )
    }
}
