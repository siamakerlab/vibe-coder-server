package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.agent.AgentContextSnapshot
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.agent.ModelCatalogService
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.build.XcodeBuildSettings
import com.siamakerlab.vibecoder.server.build.XcodeProjectInspector
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.files.ProjectFileBrowser
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.git.GitWriter
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptDispatcher
import com.siamakerlab.vibecoder.server.terminal.AgentStatusStore
import com.siamakerlab.vibecoder.server.terminal.ConsoleTuiSessionManager
import com.siamakerlab.vibecoder.server.terminal.ConsoleTuiPreferenceStore
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.ProjectReorderRequestDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.nio.file.Files
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * v1.71.0 — 프로젝트에 진행 중(RUNNING/PENDING) 빌드가 있는지. 폴더/패키지 변경 idle 가드용.
 * v1.98.1 — `internal` 승격: ArchiveRoutes 의 아카이브 idle 가드도 같은 판정을 공유한다.
 */
internal fun isBuildRunning(buildRepo: BuildRepository, id: String): Boolean =
    buildRepo.listForProject(id, limit = 5).any {
        it.status == com.siamakerlab.vibecoder.shared.dto.TaskStatus.RUNNING ||
            it.status == com.siamakerlab.vibecoder.shared.dto.TaskStatus.PENDING
    }

/**
 * v1.114.0 — 프로젝트가 **완전 유휴**인지 단일 판정. turn 응답중(busy) / 빌드중(RUNNING·PENDING)
 * / 자동화중(active) 중 하나라도 진행 중이면 false.
 *
 * 직후 콘솔 프롬프트를 쏘거나 파일/구조를 바꾸는 동작(키스토어·AdMob 저장/삭제, 폴더·패키지
 * 변경, 아카이브)의 idle 가드와 그 **표시**(structuralEnabled 등)가 모두 이 함수를 공유한다.
 * 이전엔 가드 식이 `consoleIdle()`/인라인 OR/부분식(structuralEnabled 는 isActive 누락)으로
 * 3가지로 흩어져, 자동화 진행 중일 때 "폼은 활성인데 제출은 차단" 되는 표시=동작 불일치가
 * 있었다 → 단일 헬퍼로 해소.
 */
internal fun isProjectIdle(
    sessionManager: ClaudeSessionManager,
    buildRepo: BuildRepository,
    promptAutomationManager: com.siamakerlab.vibecoder.server.automation.PromptAutomationManager,
    id: String,
): Boolean = !sessionManager.isBusy(id) &&
    !isBuildRunning(buildRepo, id) &&
    !promptAutomationManager.isActive(id)

/**
 * 프로젝트 / 콘솔 / 빌드 SSR 라우트.
 *
 * 인증 모델: AdminRoutes 와 동일하게 `vibe_session` 쿠키 기반 (requireSessionOrRedirect).
 * REST API (`/api/projects/...`) 는 그대로 두고 폼/페이지만 새로 만든다.
 */
/**
 * v1.71.0 — 패키지명 변경 시 콘솔로 보내는 Claude 작업 지시 프롬프트.
 * vibe-coder 서버는 이미 (1) DB packageName 갱신 (2) /home/vibe/keystores 의
 * 키스토어 파일명(`<pkg>.keystore` 등)을 새 패키지로 rename 해 두었다. 나머지
 * 코드/디렉토리/매니페스트/signing 참조 갱신을 현재 콘솔 provider 가 수행한다.
 */
private fun packageRenamePrompt(oldPkg: String?, newPkg: String): String {
    val from = oldPkg ?: "(현재 패키지)"
    return """
        이 Android 프로젝트의 패키지명(applicationId)을 `$from` 에서 `$newPkg` 로 완전히 변경해 주세요.
        vibe-coder 서버가 이미 처리한 것: ① 등록 DB 의 패키지명 갱신, ② /home/vibe/keystores 의
        키스토어 파일명(`$from.keystore` 등 4종)을 `$newPkg.*` 로 rename. 나머지를 모두 수행하세요:

        1. `app/build.gradle.kts` 의 `namespace` 와 `applicationId` 를 `$newPkg` 로 변경.
        2. 소스 디렉토리 구조 이동: `src/main/java`(및 `kotlin`)/`src/test`/`src/androidTest` 아래
           `${from.replace('.', '/')}` 경로의 모든 파일을 `${newPkg.replace('.', '/')}` 로 이동(폴더 구조 변경).
        3. 모든 `.kt`/`.java` 의 `package` 선언과 `import` 를 새 패키지로 갱신.
        4. `AndroidManifest.xml` 및 XML 리소스의 fully-qualified 클래스 참조(`$from.*`)를 `$newPkg.*` 로 갱신.
        5. signing config 가 키스토어 파일명/properties 를 `$from` 기준으로 참조하면 `$newPkg` 로 갱신
           (서버가 파일은 이미 rename 함 — 코드 내 참조만 맞추면 됨).
        6. 빈 옛 패키지 디렉토리 정리, 그리고 `assembleDebug` 로 빌드가 통과하는지 확인.

        주의: vibe-coder 프로젝트 폴더명 자체는 바꾸지 마세요(별도 절차). 위 작업만 정확히 수행 후 요약 보고해 주세요.
    """.trimIndent()
}

private fun iosBuildFailureFixPrompt(
    projectName: String,
    projectId: String,
    buildId: String,
    variant: String,
    failureKind: String?,
    errorMessage: String,
    logPath: String?,
): String = """
    iPhone 프로젝트 `$projectName` 의 Xcode 빌드 실패를 정밀 분석하고 수정해 주세요.

    - projectId: `$projectId`
    - buildId: `$buildId`
    - variant: `$variant`
    - failureKind: `${failureKind ?: "unknown"}`
    - classified failure:
    ```
    $errorMessage
    ```
    - build log: `${logPath ?: ".vibecoder/<projectId>/logs/<buildId>.log"}`

    요청:
    1. 빌드 로그와 Xcode 프로젝트 설정을 확인해 실제 원인을 특정하세요.
    2. Swift/SwiftUI 코드, scheme, signing, simulator destination, export 설정 중 필요한 부분만 수정하세요.
    3. 가능한 경우 동일 variant 빌드를 다시 실행해 검증하고, 실패가 남으면 다음 조치까지 요약하세요.
""".trimIndent()

fun Routing.webProjectRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    builds: BuildService,
    buildRepo: BuildRepository,
    artifactRepo: ArtifactRepository,
    sessionManager: ClaudeSessionManager,
    hub: LogHub,
    gitReader: GitReader,
    gitWriter: GitWriter,
    workspace: WorkspacePath,
    fileBrowser: ProjectFileBrowser,
    /** v0.22.0 — Play Console 업로드 트리거 (MCP google-play-publisher 위임). */
    playPublishService: com.siamakerlab.vibecoder.server.publish.PlayPublishService,
    /** v0.23.0 — TestFlight 업로드 트리거 (MCP app-store-connect 위임). */
    testFlightPublishService: com.siamakerlab.vibecoder.server.publish.TestFlightPublishService,
    testFlightUploadJobRepo: com.siamakerlab.vibecoder.server.repo.TestFlightUploadJobRepository? = null,
    /** v0.28.0 — APK 서명 검사 (apksigner verify). */
    apkSignerInspector: com.siamakerlab.vibecoder.server.artifacts.ApkSignerInspector,
    /** v0.29.0 — 프로젝트 source zip stream. */
    projectArchiver: com.siamakerlab.vibecoder.server.projects.ProjectArchiver,
    /**
     * v1.7.3 — 콘솔 진입 시 DB 의 conversation history 를 inline embed 해서 서버
     * 재시작 후에도 기존 대화 가시. WS ring buffer 가 휘발성인 한계 보완.
     */
    conversationRepo: ConversationTurnRepository,
    /** v1.26.0 — 빌드 시작 전 keystore readiness 검사 (운영 정책: 임의 생성 금지). */
    keystoreService: com.siamakerlab.vibecoder.server.admin.KeystoreService,
    /** v1.71.0 (정밀점검 H4) — 폴더/패키지 변경 idle 가드 + 폴더 이동 전 sub-agent 종료. */
    subAgentManager: com.siamakerlab.vibecoder.server.claude.SubAgentSessionManager,
    /** v1.71.0 (정밀점검 H4) — 폴더/패키지 변경 시 자동화 진행 여부 가드. */
    promptAutomationManager: com.siamakerlab.vibecoder.server.automation.PromptAutomationManager,
    agentRouter: AgentRouter? = null,
    modelCatalog: ModelCatalogService? = null,
    consoleTuiManager: ConsoleTuiSessionManager? = null,
    consolePromptDispatcher: ConsolePromptDispatcher? = null,
    consoleTuiPrefs: ConsoleTuiPreferenceStore? = null,
    agentStatusStore: AgentStatusStore? = null,
) {

    // ── 목록 + 등록 폼 ────────────────────────────────────────────────
    get("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val full = projects.listForUser(sess.userId, sess.isAdmin)
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]?.let { projectListOkFlash(sess.language, it) }
        // v1.60.0 — 페이지네이션: size 화이트리스트(20/50/100, 기본 20), page 1-base.
        val total = full.size
        val size = call.request.queryParameters["size"]?.toIntOrNull()?.takeIf { it in setOf(20, 50, 100) } ?: 20
        val pageCount = ((total + size - 1) / size).coerceAtLeast(1)
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, pageCount)
        val offset = (page - 1) * size
        val list = full.drop(offset).take(size)
        val statuses = list.associate { it.id to projectStatus(it.id, sessionManager, agentStatusStore) }
        // v1.64.0 — 행별 앱 버전(versionName) + 런처 아이콘 존재 여부(없으면 placeholder).
        val versions = list.associate { it.id to runCatching { projects.appVersionName(it.id, it.moduleName) }.getOrNull() }
        val appIcons = list.associate { it.id to runCatching { projects.resolveAppIcon(it.id, it.moduleName) != null }.getOrDefault(false) }
        val uiCapabilities = list.associate { it.id to projects.uiCapabilities(it.projectType) }
        call.respondText(
            WebProjectTemplates.projectsPage(
                sess.username, list, flashErr = err, flashOk = ok, csrf = sess.csrf, lang = sess.language,
                statuses = statuses, page = page, size = size, total = total,
                versions = versions, appIcons = appIcons, uiCapabilities = uiCapabilities,
            ),
            ContentType.Text.Html,
        )
    }

    // v1.60.0 — 드래그 순서변경 (JSON, Bearer/쿠키). 목록 페이지 JS 가 fetch.
    authenticate(AUTH_BEARER) {
        post(ApiPath.PROJECTS_REORDER) {
            call.requireApiWrite()
            val req = call.receive<ProjectReorderRequestDto>()
            projects.reorder(req.offset, req.order)
            call.respond(HttpStatusCode.OK)
        }
    }

    // v1.64.0 — 프로젝트 런처 아이콘(raster png/webp). 없으면 404 → 목록이 placeholder 사용.
    // v1.137.3 — 원본(최대 density / 업로드 원본 수 MB 가능)을 매번 그대로 보내던 것을
    // 64px 다운스케일 + in-memory 캐시(AppIconCache)로 교체. 원본 변경은 mtime/size 로 감지.
    // v1.144.1 — Cache-Control 을 max-age=3600 → no-cache 로. max-age 동안 브라우저가
    //   재검증 없이 캐시본을 써, 아이콘을 바꿔도 ETag 가 바뀐들 최대 1시간 stale 이 남던 문제.
    //   no-cache = 저장하되 매 사용 전 ETag(If-None-Match) 재검증 → 변경 없으면 304(거의
    //   0바이트, 64px 라 본문 자체도 수 KB), 바뀌면 즉시 200 신규본. 즉시성 ↔ 대역폭 균형.
    get("/projects/{id}/app-icon") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val moduleName = runCatching { projects.get(id).moduleName }.getOrNull() ?: "app"
        val icon = runCatching { projects.resolveAppIcon(id, moduleName) }.getOrNull()
        if (icon == null || !Files.isRegularFile(icon)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val entry = com.siamakerlab.vibecoder.server.projects.AppIconCache.get(id, icon)
        if (entry == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.response.header(HttpHeaders.ETag, entry.etag)
        if (call.request.headers[HttpHeaders.IfNoneMatch] == entry.etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(entry.bytes, ContentType.parse(entry.contentType))
    }

    get("/projects/{id}/ios/simulator/screenshot") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrNull()
        if (p == null || !projects.uiCapabilities(p.projectType).showIosSimulator) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val root = Path.of(p.sourcePath).normalize()
        val screenshot = root.resolve(".vibecoder-ios-build")
            .resolve("simulator")
            .resolve("latest-screenshot.png")
            .normalize()
        if (!screenshot.startsWith(root) || !Files.isRegularFile(screenshot)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.respondFile(screenshot.toFile())
    }

    get(ApiPath.PROJECT_CONSOLE_CONTEXT_PATTERN) {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val provider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        val model = agentRouter?.effectiveModel(id) ?: sessionManager.effectiveModel(id)
        val sessionId = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val snap = effectiveConsoleContextSnapshot(id, provider, model, sessionId, agentRouter, sessionManager, conversationRepo)
        call.respondText(
            """{"provider":"${provider.id}","input":${snap.input},"cacheRead":${snap.cacheRead},"cacheCreation":${snap.cacheCreation},"limit":${snap.limit}}""",
            ContentType.Application.Json,
        )
    }

    post("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val projectId = params["projectId"]?.trim().orEmpty()
        val appName = params["appName"]?.trim().orEmpty()
        val packageName = params["packageName"]?.trim().orEmpty()
        // v0.9.0 — sourceType ('empty' | 'clone') + optional cloneUrl/branch
        val sourceType = params["sourceType"]?.trim()?.ifBlank { null } ?: "empty"
        val cloneUrl = params["cloneUrl"]?.trim()?.ifBlank { null }
        val cloneBranch = params["cloneBranch"]?.trim()?.ifBlank { null }
        val templateId = params["templateId"]?.trim()?.ifBlank { null }
        // v1.127.0 — 프로젝트 타입(kotlin/flutter). 기본 kotlin. clone 시에도 사용자 선택 우선
        // (register 의 ProjectTypes.normalize 가 최종 검증). 폼 미전송 시 kotlin.
        val projectType = params["projectType"]?.trim()?.ifBlank { null } ?: "kotlin"
        // v1.128.0 — clone 타입 mismatch 확인 페이지에서 "진행" 시 true (mismatch 검사 skip).
        val projectTypeAck = params["projectTypeAck"]?.let { it == "true" || it == "on" || it == "1" } == true
        // v1.7.18 — clone path 의 "기존 폴더 덮어쓰기" 체크박스.
        val overwrite = params["overwrite"]?.let { it == "true" || it == "on" || it == "1" } == true

        // v1.7.0 — clone path 에선 cloneUrl 만 필수. projectId/appName/packageName
        // 은 ProjectService.register 가 cloneUrl + clone 후 build.gradle.kts 에서 자동 도출.
        val isClone = sourceType == "clone"
        val basicErr = when {
            isClone && cloneUrl.isNullOrBlank() ->
                Messages.t(sess.language, "flash.form.cloneUrlRequired")
            !isClone && projectId.isBlank() -> Messages.t(sess.language, "flash.form.projectIdRequired")
            !isClone && appName.isBlank() -> Messages.t(sess.language, "flash.form.appNameRequired")
            !isClone && packageName.isBlank() -> Messages.t(sess.language, "flash.form.packageNameRequired")
            else -> null
        }
        if (basicErr != null) {
            val list = projects.listForUser(sess.userId, sess.isAdmin)
            call.respondText(
                WebProjectTemplates.projectsPage(
                    sess.username, list, flashErr = basicErr, csrf = sess.csrf, lang = sess.language,
                ),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        val result = runCatching {
            projects.register(
                RegisterProjectRequestDto(
                    projectId = projectId,
                    appName = appName,
                    packageName = packageName,
                    keystore = null,
                    sourceType = sourceType,
                    cloneUrl = cloneUrl,
                    cloneBranch = cloneBranch,
                    templateId = templateId,
                    overwrite = overwrite,
                    projectType = projectType,
                    projectTypeAck = projectTypeAck,
                )
            )
        }

        val created = result.getOrElse { e ->
            val apiEx = e as? ApiException
            // v1.128.0 — clone 타입 mismatch → 확인 페이지(감지값 수용 / 선택값 강제 진행 두 버튼).
            if (apiEx?.code == "project_type_mismatch") {
                val selected = apiEx.messageArgs.getOrNull(0)?.toString() ?: projectType
                val detected = apiEx.messageArgs.getOrNull(1)?.toString().orEmpty()
                call.respondText(
                    WebProjectTemplates.projectTypeMismatchPage(
                        username = sess.username, projectId = projectId, appName = appName,
                        packageName = packageName, cloneUrl = cloneUrl, cloneBranch = cloneBranch,
                        selected = selected, detected = detected, csrf = sess.csrf, lang = sess.language,
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.Conflict,
                )
                return@post
            }
            val msg = apiEx?.message ?: e.message ?: Messages.t(sess.language, "flash.project.createFailed")
            log.warn(e) { "project register failed: $projectId by ${sess.username}" }
            val list = projects.listForUser(sess.userId, sess.isAdmin)
            call.respondText(
                WebProjectTemplates.projectsPage(
                    sess.username, list, flashErr = msg, csrf = sess.csrf, lang = sess.language,
                ),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        log.info { "project registered: ${created.id} by ${sess.username}" }
        authDeps.audit.projectCreate(sess.userId, created.id, sourceType, call.request.origin.remoteHost)
        call.respondRedirect("/projects/${created.id}")
    }

    // ── 상세 ──────────────────────────────────────────────────────────
    //
    // v1.11.0 — 단일 페이지 + 5개 iframe 탭 (Console / Builds / Files / Git / Agents)
    // 으로 교체. 기존 `projectDetailPage` (메타데이터 카드 위주) 는
    // `/projects/{id}/overview` 로 이동해서 More 메뉴에서 접근 가능 + 직접 URL 진입
    // 보존. 첫 진입은 console 탭 자동 활성 — 사용자가 다른 페이지로 이동해도
    // iframe 들이 항상 살아 있어 Claude WS / 빌드 로그 stream 이 끊기지 않는다.
    get("/projects/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        // v1.49.0 — 헤더 프로젝트명 콤보박스(빠른 프로젝트 전환)용 전체 목록.
        val allProjects = runCatching { projects.listForUser(sess.userId, sess.isAdmin) }
            .getOrDefault(listOf(p))
        // 콤보박스 각 항목 좌측 상태칩. TUI/AgentStatusStore live snapshot 기준이며,
        // 과거 대화 이력으로 stopped 를 되살리지 않는다.
        val projectStatuses = allProjects.associate { pr ->
            pr.id to projectStatus(pr.id, sessionManager, agentStatusStore)
        }
        // v1.157.2 — 콤보박스 정렬은 세션 출력 활동이 아니라 사용자가 프롬프트를 송신한 시각 기준.
        val lastPromptTs = runCatching { conversationRepo.lastUserPromptTsByProject() }.getOrDefault(emptyMap())
        val agentProvider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        val availableAgentProviders = agentRouter?.availableProviders() ?: listOf(AgentProvider.CLAUDE)
        val selectedModel = if (agentRouter != null) {
            agentRouter.readProjectModel(id) ?: agentRouter.effectiveModel(id) ?: "default"
        } else {
            sessionManager.readProjectModel(id) ?: sessionManager.effectiveModel(id)
        }
        val modelOptions = modelCatalog?.modelsFor(
            provider = agentProvider,
            currentModel = selectedModel,
            effectiveModel = agentRouter?.effectiveModel(id) ?: sessionManager.effectiveModel(id),
        ).orEmpty()
        val modelOptionsByProvider = availableAgentProviders.associateWith { provider ->
            modelCatalog?.modelsFor(
                provider = provider,
                currentModel = if (provider == agentProvider) selectedModel else null,
                effectiveModel = if (provider == agentProvider) {
                    agentRouter?.effectiveModel(id) ?: sessionManager.effectiveModel(id)
                } else {
                    null
                },
            ).orEmpty()
        }
        // v1.156.0 — opencode reasoning effort(--variant) 조회.
        val selectedVariant = (agentRouter?.managerFor(AgentProvider.OPENCODE) as? com.siamakerlab.vibecoder.server.agent.opencode.OpenCodeSessionManager)
            ?.effectiveVariant(id) ?: ""
        // v1.50.0 — 우측 overview rail 데이터.
        // v1.108.4 — keystore/admob 준비 상태를 한 번의 get() 으로 함께 계산(개요카드 행).
        val ksEntry = runCatching { keystoreService.get(p.packageName) }.getOrNull()
        val keystoreReady = ksEntry != null
        val admobReady = ksEntry?.admobExists == true
        val usage = runCatching { conversationRepo.usageSummary(id) }.getOrNull()
        val uiCapabilities = projects.uiCapabilities(p.projectType)
        // v1.172.0 — 기존(pre-v1.169.0) iPhone 프로젝트에 iOS 빌드 지침(스킬 + CLAUDE.md 파이프라인
        // 섹션)이 없으면 열람 시점에 idempotent 보강(프로세스당 1회). 페이지 렌더를 막지 않도록 격리.
        if (uiCapabilities.showIosBuildSettings) {
            runCatching { projects.ensureIphoneProjectGuidance(id, p.projectType, p.sourcePath) }
        }
        val iosBuildSettings = if (uiCapabilities.showIosBuildSettings) {
            runCatching {
                val source = Path.of(p.sourcePath)
                val settings = XcodeBuildSettings.load(source)
                val info = XcodeProjectInspector.inspect(source)
                ProjectTabsTemplate.IosBuildSettingsView(
                    scheme = settings.scheme,
                    selectedScheme = info.selectedScheme,
                    inferredScheme = info.inferredScheme,
                    sharedSchemes = info.sharedSchemes,
                    debugConfiguration = settings.debugConfiguration,
                    releaseConfiguration = settings.releaseConfiguration,
                    bundleIdentifier = settings.bundleIdentifier.ifBlank { p.packageName },
                    teamId = settings.teamId,
                    exportMethod = settings.exportMethod,
                    signingStyle = settings.signingStyle,
                    provisioningProfileSpecifier = settings.provisioningProfileSpecifier,
                    containerName = info.containerName,
                )
            }.getOrNull()
        } else null
        val currentSessionId = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val ctxSnap = effectiveConsoleContextSnapshot(id, agentProvider, selectedModel, currentSessionId, agentRouter, sessionManager, conversationRepo)
        // v1.158.5 — 프롬프트 히스토리를 provider 무관하게 통합 조회.
        val promptFilter = ConversationTurnRepository.Filter(projectId = id, role = "user")
        val promptCount = runCatching { conversationRepo.count(promptFilter) }.getOrDefault(0L)
        // v1.134.0 — rail 프롬프트 히스토리: 최근 7개 → 전체(스크롤 목록). 페이지 비대 방지
        // 안전 상한 1000(repo list 상한과 동일) — 사실상 전체.
        val recentPrompts = runCatching {
            val limit = 1000
            val off = (promptCount - limit).coerceAtLeast(0)
            conversationRepo.list(promptFilter, limit = limit, offset = off).asReversed().map { it.content }
        }.getOrDefault(emptyList())
        call.respondText(
            ProjectTabsTemplate.page(
                username = sess.username,
                project = p,
                allProjects = allProjects,
                projectStatuses = projectStatuses,
                lastPromptTs = lastPromptTs,
                agentProvider = agentProvider,
                availableAgentProviders = availableAgentProviders,
                model = selectedModel,
                availableModelOptions = modelOptions,
                modelOptionsByProvider = modelOptionsByProvider,
                variant = selectedVariant,
                keystoreReady = keystoreReady,
                admobReady = admobReady,
                tokensTotal = (usage?.let { it.inputTokens + it.outputTokens }) ?: 0L,
                cacheHitRate = usage?.cacheHitRate,
                contextInputTokens = ctxSnap.input,
                contextCacheReadTokens = ctxSnap.cacheRead,
                contextCacheCreationTokens = ctxSnap.cacheCreation,
                contextLimit = ctxSnap.limit,
                promptCount = promptCount,
                recentPrompts = recentPrompts,
                autoCompact = sessionManager.isAutoCompact(id),
                iosBuildSettings = iosBuildSettings,
                uiCapabilities = uiCapabilities,
                flashErr = err, flashOk = ok,
                csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/ios/build-settings") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        if (!projects.uiCapabilities(p.projectType).showIosBuildSettings) {
            call.respondRedirect("/projects/$id?err=${Messages.t(sess.language, "flash.iosBuildSettings.iphoneRequired").encodeUrl()}")
            return@post
        }
        val source = Path.of(p.sourcePath).normalize()
        val scheme = form["scheme"]?.trim().orEmpty()
        val debugConfiguration = form["debugConfiguration"]?.trim().orEmpty().ifBlank { "Debug" }
        val releaseConfiguration = form["releaseConfiguration"]?.trim().orEmpty().ifBlank { "Release" }
        val bundleIdentifier = form["bundleIdentifier"]?.trim().orEmpty().ifBlank { p.packageName }
        val teamId = form["teamId"]?.trim().orEmpty()
        val exportMethod = form["exportMethod"]?.trim().orEmpty()
        val signingStyle = form["signingStyle"]?.trim().orEmpty()
        val provisioningProfileSpecifier = form["provisioningProfileSpecifier"]?.trim().orEmpty()
        val info = runCatching { XcodeProjectInspector.inspect(source) }.getOrElse { e ->
            call.respondRedirect("/projects/$id?err=${Messages.t(sess.language, "flash.iosBuildSettings.inspectFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        if (scheme.isNotBlank() && info.sharedSchemes.isNotEmpty() && scheme !in info.sharedSchemes) {
            call.respondRedirect("/projects/$id?err=${Messages.t(sess.language, "flash.iosBuildSettings.invalidScheme", scheme).encodeUrl()}")
            return@post
        }
        runCatching {
            XcodeBuildSettings.save(
                source,
                XcodeBuildSettings(
                    scheme = scheme,
                    debugConfiguration = debugConfiguration,
                    releaseConfiguration = releaseConfiguration,
                    bundleIdentifier = bundleIdentifier,
                    teamId = teamId,
                    exportMethod = exportMethod,
                    signingStyle = signingStyle,
                    provisioningProfileSpecifier = provisioningProfileSpecifier,
                ),
            )
        }.onFailure { e ->
            log.warn(e) { "iOS build settings save failed: project=$id" }
            call.respondRedirect("/projects/$id?err=${Messages.t(sess.language, "flash.iosBuildSettings.saveFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        call.respondRedirect("/projects/$id?ok=${Messages.t(sess.language, "flash.iosBuildSettings.saved").encodeUrl()}")
    }

    // v1.11.0 — 기존 detail (메타데이터 카드) 페이지 보존 — More 메뉴에서 link.
    get("/projects/{id}/overview") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val recent = buildRepo.listForProject(id, limit = 5).map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage, failureKind = row.failureKind,
                gitBranch = row.gitBranch, gitSha = row.gitSha,
            )
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.projectDetailPage(
                sess.username, p, recent, flashErr = err, flashOk = ok, csrf = sess.csrf,
                lang = sess.language,
                // v1.71.0 — 폴더/패키지 변경은 완전 유휴(turn·빌드·자동화 미진행)일 때만.
                // v1.114.0 — 제출 가드와 동일한 isProjectIdle 공유(이전엔 isActive 누락 → 자동화
                //   진행 중일 때 폼은 활성인데 제출은 차단되던 표시=동작 불일치).
                structuralEnabled = isProjectIdle(sessionManager, buildRepo, promptAutomationManager, id),
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val removed = projects.delete(id)
        log.info { "project delete: id=$id removed=$removed by ${sess.username}" }
        authDeps.audit.projectDelete(sess.userId, id, call.request.origin.remoteHost, removed)
        val ok = if (removed) Messages.t(sess.language, "flash.project.deleted") else Messages.t(sess.language, "flash.project.notExist")
        call.respondRedirect("/projects?ok=${ok.encodeUrl()}")
    }

    // ── v1.71.0 프로젝트 설정: 표시명 / 패키지명 / 폴더명 변경 ─────────────
    // 폴더명·패키지명은 프로젝트가 "대기중"(turn 미진행 + 빌드 미진행)일 때만 허용.
    // (buildRunning 판정은 top-level isBuildRunning 헬퍼.)
    post("/projects/{id}/rename-name") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val newName = params["name"]?.trim().orEmpty()
        try {
            projects.rename(id, newName)
            call.respondRedirect("/projects/$id/overview?ok=${Messages.t(sess.language, "flash.project.renamed").encodeUrl()}")
        } catch (e: Exception) {
            call.respondRedirect("/projects/$id/overview?err=${(e.message ?: "error").encodeUrl()}")
        }
    }

    post("/projects/{id}/rename-package") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val newPkg = params["packageName"]?.trim().orEmpty()
        // 대기중 가드: turn 진행 중 / 빌드 중 / 자동화 진행 중이면 거부.
        // (alive 메인 프로세스엔 프롬프트를 보낼 것이므로 허용. 자동화는 sendPrompt 와 충돌.)
        val consoleBusy = agentRouter?.isBusy(id) ?: sessionManager.isBusy(id)
        if (consoleBusy || isBuildRunning(buildRepo, id) || promptAutomationManager.isActive(id)) {
            call.respondRedirect("/projects/$id/overview?err=${Messages.t(sess.language, "flash.project.rename.notIdle").encodeUrl()}")
            return@post
        }
        val oldPkg = runCatching { projects.get(id).packageName }.getOrNull()
        try {
            projects.updatePackageName(id, newPkg)
        } catch (e: Exception) {
            call.respondRedirect("/projects/$id/overview?err=${(e.message ?: "invalid package").encodeUrl()}")
            return@post
        }
        // 키스토어 파일명은 서버가 처리 (KeystoreService).
        if (oldPkg != null && oldPkg != newPkg) {
            runCatching { keystoreService.renamePackage(oldPkg, newPkg) }
                .onFailure { log.warn(it) { "keystore rename failed $oldPkg → $newPkg" } }
        }
        // 코드/파일/디렉토리 구조 리네임은 현재 콘솔 provider 에 위임.
        val prompt = packageRenamePrompt(oldPkg, newPkg)
        val dispatcher = consolePromptDispatcher
        if (dispatcher == null) {
            log.warn { "package-rename prompt rejected for $id: console TUI dispatcher unavailable" }
        } else {
            runCatching {
                dispatcher.send(
                    ownerUserId = sess.userId,
                    projectId = id,
                    text = prompt,
                    requestedProvider = null,
                    source = "package_rename",
                )
            }.onFailure { log.warn(it) { "package-rename prompt send failed for $id" } }
        }
        call.respondRedirect("/projects/$id/overview?ok=${Messages.t(sess.language, "flash.project.package.changed").encodeUrl()}")
    }

    post("/projects/{id}/rename-folder") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val newId = params["newId"]?.trim().orEmpty()
        // 폴더 이동은 프로세스 cwd 를 옮기므로 완전 idle 필요: turn/빌드/자동화 미진행.
        // v1.71.0 (정밀점검 H4) — 자동화는 rename 직후에도 옛 id 로 sendPrompt 를 시도하므로 거부.
        if (!isProjectIdle(sessionManager, buildRepo, promptAutomationManager, id)) {
            call.respondRedirect("/projects/$id/overview?err=${Messages.t(sess.language, "flash.project.rename.notIdle").encodeUrl()}")
            return@post
        }
        try {
            // live claude 프로세스가 옛 폴더를 cwd 로 잡고 있으므로 먼저 종료(세션 재설정).
            // DB 대화 이력은 마이그레이션으로 보존되어 새 콘솔에서 그대로 보임.
            if (sessionManager.isAlive(id)) sessionManager.startNew(id)
            // v1.71.0 (정밀점검 H4) — sub-agent 프로세스도 옛 폴더를 cwd 로 잡으므로 종료.
            runCatching {
                subAgentManager.activeAgentsFor(id).forEach { agent -> subAgentManager.startNew(id, agent) }
            }.onFailure { log.warn(it) { "sub-agent terminate before folder rename failed for $id" } }
            projects.renameFolder(id, newId)
            log.info { "project folder renamed $id → $newId by ${sess.username}" }
            call.respondRedirect("/projects/$newId/overview?ok=${Messages.t(sess.language, "flash.project.folder.renamed").encodeUrl()}")
        } catch (e: Exception) {
            log.warn(e) { "folder rename failed $id → $newId" }
            call.respondRedirect("/projects/$id/overview?err=${(e.message ?: "rename failed").encodeUrl()}")
        }
    }

    // ── 콘솔 ──────────────────────────────────────────────────────────
    get("/projects/{id}/console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val agentProvider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        val alive = agentRouter?.isAlive(id) ?: sessionManager.isAlive(id)
        val sid = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val tuiMode = true
        val tuiSessionId = consoleTuiManager?.find(sess.userId, id, agentProvider)?.id
        val historySessionId = if (tuiMode) tuiSessionId else sid
        val availableAgentProviders = agentRouter?.availableProviders() ?: listOf(AgentProvider.CLAUDE)
        // v0.18.0 — 등록 직후 첫 console 진입이면 starter prompt 를 자동 입력 (소비).
        val starterPrompt = projects.consumeStarterPrompt(id)
        // Claude CLI 인증 상태 진단. CLI 자체와 자격증명 파일 둘 다 검사.
        val env = authDeps.envDiagnostics.run(sess.language)
        // v1.7.3 — DB conversation history (last 200 turn, ASC). sessionId 가 있을 때만
        // 해당 세션 turn 만 조회. 없으면 — 새 프로젝트 또는 last id 없는 케이스 — 빈 list.
        // v1.104.2 — 진짜 "마지막 200개" 로 회수. 이전엔 offset=0 ASC 라 turn 많은 세션
        // (tally-counter 1000+) 에서 *가장 오래된* 200개만 와, 최근 대화(방금 보낸 프롬프트
        // 포함)가 콘솔 재진입 시 통째로 누락됐다(상단 고정도 당연히 안 됨). offset=total-200.
        // v1.129.0 — 초기 30개만 로드(과거는 콘솔 최상단 "더보기" 로 페이지네이션). offset=total-30.
        // 30개 안에 user(현재/마지막 프롬프트)가 없으면(매우 긴 turn) 마지막 user 1개를 맨 앞에
        // 붙여, 현재 작업 중 프롬프트의 상단 고정(.cur sticky)을 30개 밖이어도 유지한다.
        val history = if (historySessionId != null || tuiMode) {
            runCatching {
                val f = ConversationTurnRepository.Filter(projectId = id, provider = agentProvider.id, sessionId = historySessionId)
                val rows = conversationRepo.listLatest(f, limit = 30)
                if (rows.none { it.role == "user" }) {
                    val uf = ConversationTurnRepository.Filter(projectId = id, provider = agentProvider.id, sessionId = historySessionId, role = "user")
                    conversationRepo.listLatest(uf, limit = 1) + rows
                } else rows
            }.getOrDefault(emptyList())
        } else emptyList()
        // v1.129.0 — history(DB·과거) ↔ WS replay(ring·미래) 경계 seq. 클라가 WS 연결 시
        // since=이 값으로 보내, ring 의 과거 프레임(history 와 중복)을 replay 하지 않게 한다.
        val initialMaxSeq = hub.consoleCurrentSeq(LogHub.consoleTopic(id, agentProvider.id))
        val selectedModel = if (agentRouter != null) {
            agentRouter.readProjectModel(id) ?: agentRouter.effectiveModel(id) ?: "default"
        } else {
            sessionManager.effectiveModel(id)
        }
        val currentSessionId = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val ctxSnap = effectiveConsoleContextSnapshot(id, agentProvider, selectedModel, currentSessionId, agentRouter, sessionManager, conversationRepo)
        val modelOptions = modelCatalog?.modelsFor(
            provider = agentProvider,
            currentModel = selectedModel,
            effectiveModel = agentRouter?.effectiveModel(id) ?: sessionManager.effectiveModel(id),
        ).orEmpty()
        call.respondText(
            WebProjectTemplates.consolePage(
                sess.username, p, sid, alive,
                claudeCli = env.claude,
                claudeAuth = env.claudeAuth,
                csrf = sess.csrf,
                starterPrompt = starterPrompt,
                initialHistory = history,
                initialMaxSeq = initialMaxSeq,
                model = selectedModel,
                variant = (agentRouter?.managerFor(AgentProvider.OPENCODE) as? com.siamakerlab.vibecoder.server.agent.opencode.OpenCodeSessionManager)?.effectiveVariant(id) ?: "",
                contextTokens = ctxSnap.cacheRead,
                contextInputTokens = ctxSnap.input,
                contextCacheCreationTokens = ctxSnap.cacheCreation,
                contextLimit = ctxSnap.limit,
                contextWarnTokens = sessionManager.contextWarnTokens(),
                mcpStrict = sessionManager.isMcpStrict(id),
                tuiMode = tuiMode,
                agentProvider = agentProvider,
                availableAgentProviders = availableAgentProviders,
                availableModelOptions = modelOptions,
                uiCapabilities = projects.uiCapabilities(p.projectType),
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
                // v1.175.0 — 수동 모드에선 이미 살아있는 세션이 있을 때만 자동 attach. 없으면 "세션
                // 열기" 버튼으로만 시작. 자동 모드면 기존대로 항상 자동 실행.
                autoOpenConsole = com.siamakerlab.vibecoder.server.config.ConfigHolder.current.security.autoManageSessions ||
                    tuiSessionId != null,
            ),
            ContentType.Text.Html,
        )
    }

    // v1.129.0 — 콘솔 "더보기": before turnIdx 이전 과거 limit 개(ASC) JSON. 초기 30개 위로 prepend.
    get("/api/projects/{id}/claude/console/history") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val before = call.request.queryParameters["before"]?.toIntOrNull()
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
        val agentProvider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        val sid = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val tuiMode = true
        val tuiSessionId = consoleTuiManager?.find(sess.userId, id, agentProvider)?.id
        val historySessionId = if (tuiMode) tuiSessionId else sid
        val rows = if ((historySessionId != null || tuiMode) && before != null) {
            runCatching {
                val f = ConversationTurnRepository.Filter(projectId = id, provider = agentProvider.id, sessionId = historySessionId, beforeTurnIdx = before)
                conversationRepo.listLatest(f, limit = limit)
            }.getOrDefault(emptyList())
        } else emptyList()
        call.respondText(WebProjectTemplates.renderInitialHistoryJson(rows), ContentType.Application.Json)
    }

    // v1.133.0 — 콘솔 이력 복원용 이미지 서빙. DB row(tool_result 의 base64 이미지 블록 /
    // user 첨부 raw)에서 idx 번째 이미지를 실제 bytes 로 응답. inline history JSON 에는
    // base64 를 싣지 않고(페이지 비대 방지) <img src="...console/image?turn=N&idx=M"> 가
    // 세션 쿠키로 이 endpoint 를 부른다. 현재 세션의 turn 만 (이력 복원 범위와 동일).
    // TUI-only console uses the active TUI PTY session. If the PTY session disappeared,
    // fall back provider-wide like the console history view.
    get("/api/projects/{id}/claude/console/image") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val turn = call.request.queryParameters["turn"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val idx = (call.request.queryParameters["idx"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val agentProvider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        val sid = agentRouter?.currentSessionId(id) ?: sessionManager.currentSessionId(id)
        val tuiMode = true
        val tuiSessionId = consoleTuiManager?.find(sess.userId, id, agentProvider)?.id
        val historySessionId = if (tuiMode) {
            tuiSessionId
        } else {
            sid ?: return@get call.respond(HttpStatusCode.NotFound)
        }
        val rows = runCatching { conversationRepo.byTurnIdx(id, historySessionId, turn, provider = agentProvider.id) }.getOrDefault(emptyList())
        for (row in rows) {
            val images = when {
                row.role == "user" ->
                    com.siamakerlab.vibecoder.server.claude.ConsoleImages.fromUserRaw(row.raw)
                row.role == "tool_result" || row.role == "tool_result_error" ->
                    com.siamakerlab.vibecoder.server.claude.ConsoleImages.fromToolResultContent(row.content)
                else -> emptyList()
            }
            val img = images.getOrNull(idx) ?: continue
            // 화이트리스트 밖 mediaType 은 응답 헤더 주입 방지 차원에서 거절.
            if (img.mediaType !in ClaudeSessionManager.ALLOWED_IMAGE_MEDIA_TYPES) continue
            val bytes = runCatching {
                java.util.Base64.getMimeDecoder().decode(img.data)
            }.getOrNull() ?: continue
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
            // /projects/{id}/raw 와 동일한 이중 방어 — Claude tool 출력 유래 콘텐츠라
            // sniffing/동적 실행을 차단(mediaType 화이트리스트에 SVG 없음 + nosniff + sandbox).
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
            return@get call.respondBytes(bytes, ContentType.parse(img.mediaType))
        }
        call.respond(HttpStatusCode.NotFound)
    }

    post("/projects/{id}/console/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        runCatching {
            if (agentRouter != null) agentRouter.startNew(id) else sessionManager.startNew(id)
            consoleTuiManager?.startNew(sess.userId, id)
        }
            .onFailure { log.warn(it) { "console reset failed for $id" } }
        log.info { "console reset: $id by ${sess.username}" }
        authDeps.audit.consoleNew(sess.userId, id, call.request.origin.remoteHost)
        // v0.13.0 — scratch 는 /chat. v1.54.0 — chat 세션은 /chat?c=<id> (사이드바 유지).
        val target = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            else -> "/projects/$id/console"
        }
        call.respondRedirect(target)
    }

    // v1.175.0 — 수동 "세션 종료". 현재 provider 의 콘솔 세션을 두 시스템(stream-json + PTY)에서
    // 종료하되 **session-id 는 보존**(다음 "세션 열기"에서 resume). 종료 후 이동 타겟을 JSON 으로
    // 돌려준다: 같은 프로젝트에 다른 provider 세션이 살아있으면 그 provider 콘솔, 없으면 프로젝트 목록.
    // ("새 세션"과 달리 대화를 버리지 않는다.) SSR 전용(브라우저) — Android wire 영향 없음.
    post("/projects/{id}/console/close") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val current = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        runCatching {
            if (agentRouter != null) agentRouter.closeSession(id) else sessionManager.closeSession(id)
            consoleTuiManager?.closeProvider(sess.userId, id, current)
        }
            .onFailure { log.warn(it) { "console close failed for $id" } }
        log.info { "console close: $id/${current.id} by ${sess.username}" }
        // 다른 provider 세션 생존 판정: PTY alive 또는 stream-json alive.
        val ptyAlive = consoleTuiManager?.aliveProviders(id, sess.userId).orEmpty()
        val nextProvider = (agentRouter?.availableProviders() ?: listOf(AgentProvider.CLAUDE))
            .filter { it != current }
            .firstOrNull { it in ptyAlive || agentRouter?.managerFor(it)?.isAlive(id) == true }
        val navigate = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            nextProvider != null -> {
                agentRouter?.setProvider(id, nextProvider)
                "/projects/$id#console"
            }
            else -> "/projects"
        }
        call.respondText("{\"navigate\":\"$navigate\"}", ContentType.Application.Json)
    }

    // v1.106.0 — 프로젝트별 모델 설정(토큰 사용량 레버). 유휴면 즉시 재시작(세션 유지),
    // busy 면 다음 turn 부터 적용. v1.x — 선택된 provider(Claude/Codex)별 파일에 독립 저장.
    post("/projects/{id}/console/model") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val model = form["model"]?.trim().orEmpty()
        val variant = form["variant"]?.trim().orEmpty()
        val provider = agentRouter?.providerFor(id) ?: AgentProvider.CLAUDE
        runCatching {
            if (agentRouter != null) {
                agentRouter.setProjectModelAndRestart(id, model)
                // v1.156.0 — opencode reasoning effort(--variant) 별도 저장.
                if (provider == AgentProvider.OPENCODE) {
                    (agentRouter.managerFor(AgentProvider.OPENCODE) as? com.siamakerlab.vibecoder.server.agent.opencode.OpenCodeSessionManager)
                        ?.setProjectVariant(id, variant.ifBlank { null })
                }
            } else {
                sessionManager.setProjectModelAndRestart(id, model)
            }
            // v1.175.0 — 수동 모드: 모델 적용 위해 현재 provider PTY 만 재시작(세션-id 보존).
            // 자동 모드: 기존대로 프로젝트의 모든 provider PTY 일괄 종료.
            if (com.siamakerlab.vibecoder.server.config.ConfigHolder.current.security.autoManageSessions) {
                consoleTuiManager?.closeProject(sess.userId, id)
            } else {
                consoleTuiManager?.closeProvider(sess.userId, id, provider)
            }
        }
            .onFailure { log.warn(it) { "set model failed for $id" } }
        log.info { "console model set: $id/${provider.id} -> '${model.ifBlank { "default" }}'${if (provider == AgentProvider.OPENCODE) " variant='${variant.ifBlank { "default" }}'" else ""} by ${sess.username}" }
        val target = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            else -> "/projects/$id#console"
        }
        call.respondRedirect(target)
    }

    // v1.106.0 (P1-a) — 프로젝트 MCP 최소화 토글. on = 전역 MCP 무시(프로젝트 .mcp.json 만/없으면 0개)
    // → 매 세션 캐시 프리픽스 축소. 유휴면 즉시 재시작(세션 유지), busy 면 다음 turn 부터.
    post("/projects/{id}/console/mcp-strict") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val enabled = form["enabled"]?.equals("true", ignoreCase = true) == true ||
            form["enabled"]?.equals("on", ignoreCase = true) == true
        runCatching { sessionManager.setMcpStrictAndRestart(id, enabled) }
            .onFailure { log.warn(it) { "set mcp-strict failed for $id" } }
        log.info { "console mcp-strict set: $id -> $enabled by ${sess.username}" }
        val target = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            else -> "/projects/$id/console"
        }
        call.respondRedirect(target)
    }

    // v1.108.0 — 프로젝트별 자동 /compact 토글. ON(기본) 이면 컨텍스트 임계 초과 시 turn 종료 후
    // 서버가 자동으로 /compact 실행. 우측 오버뷰 컨텍스트 카드의 '자동' 체크박스가 호출(fetch).
    post("/projects/{id}/console/auto-compact") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val enabled = form["enabled"]?.equals("true", ignoreCase = true) == true ||
            form["enabled"]?.equals("on", ignoreCase = true) == true
        runCatching { sessionManager.setAutoCompact(id, enabled) }
            .onFailure { log.warn(it) { "set auto-compact failed for $id" } }
        log.info { "auto-compact set: $id -> $enabled by ${sess.username}" }
        call.respondText("ok", ContentType.Text.Plain)
    }

    // TUI-only migration compatibility endpoint. Older pages may still post here, but console mode
    // can no longer be disabled.
    post("/projects/{id}/console/tui-mode") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        runCatching { consoleTuiPrefs?.setTuiMode(id, true) }
            .onFailure { log.warn(it) { "set console tui mode failed for $id" } }
        log.info { "console tui mode forced on: $id by ${sess.username}" }
        call.respondText("ok", ContentType.Text.Plain)
    }

    post("/projects/{id}/console/provider") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val router = agentRouter
        if (router == null) {
            call.respondRedirect("/projects/$id/console?err=${"agent provider router unavailable".encodeUrl()}")
            return@post
        }
        val provider = AgentProvider.parse(form["provider"])
        if (provider == null || provider !in router.availableProviders()) {
            call.respondRedirect("/projects/$id/console?err=${"invalid agent provider".encodeUrl()}")
            return@post
        }
        router.setProvider(id, provider)
        // v1.175.0 — 수동 모드: provider 전환 시 이전 provider 세션을 살려둔다(자동 종료 금지 →
        // "세션 종료 시 다른 provider 로 이동" 성립). 자동 모드: 기존대로 일괄 종료.
        if (com.siamakerlab.vibecoder.server.config.ConfigHolder.current.security.autoManageSessions) {
            consoleTuiManager?.closeProject(sess.userId, id)
        }
        log.info { "console provider set: $id -> ${provider.id} by ${sess.username}" }
        call.respondRedirect("/projects/$id#console")
    }

    // ── 빌드 ──────────────────────────────────────────────────────────
    get("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val rows = buildRepo.listForProject(id, limit = 100)
        val buildDtos = rows.map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage, failureKind = row.failureKind,
                gitBranch = row.gitBranch, gitSha = row.gitSha,
            )
        }
        val artifacts = artifactRepo.listForProject(id).associateBy { it.buildId }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        // v0.59.0 — Phase 38 통계 카드.
        val stats = runCatching { builds.statistics(id, artifactRepo) }.getOrNull()
        // v1.26.0 — keystore readiness (운영 정책: 임의 생성 금지). false 면 빌드 버튼 disabled.
        // v1.26.1 — onFailure 로 silent failure 진단 가능하게 (이전엔 IO 에러 silently false 반환).
        val keystoreReady = runCatching { keystoreService.get(p.packageName) != null }
            .onFailure { log.warn(it) { "keystore probe failed for package=${p.packageName}" } }
            .getOrDefault(false)
        // v1.57.0 — 키스토어 미준비 시 인라인 생성 폼 prefill (DN 메타 + 마지막 입력 캐시).
        val keystorePrefill = if (keystoreReady) null
        else runCatching { keystoreService.effectiveDefaults() }.getOrNull()
        call.respondText(
            WebProjectTemplates.buildsPage(
                sess.username, p, buildDtos, artifacts,
                stats = stats,
                keystoreReady = keystoreReady,
                keystorePrefill = keystorePrefill,
                flashErr = err, flashOk = ok, csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        // v1.26.0 — keystore 가드 (운영 정책: 키스토어 임의 생성 금지). UI 가 이미 비활성화
        // 했지만 직접 POST 우회 차단. project.packageName 매칭 keystore 가 있어야만 진행.
        // v1.26.2 — Q-2 명시: 실제 enforcement 는 v1.26.1 의 `BuildService.requireKeystoreOrThrow`
        // SSOT 가 담당. 본 SSR pre-check 는 사용자 친화 flash redirect 위함 — service
        // layer 가 던지는 409 ApiException 보다 ko-localized 메시지로 자연. 가드 로직
        // 변경 시 두 곳 (이 블록 + BuildService.requireKeystoreOrThrow) 같이 봐야 함.
        // Q-5: onFailure log.warn — GET path 와 일관 (silent IO failure 진단).
        val pkgOk = runCatching {
            val proj = projects.get(id)
            keystoreService.get(proj.packageName) != null
        }.onFailure { log.warn(it) { "keystore probe failed (POST /builds): project=$id" } }
            .getOrDefault(false)
        if (!pkgOk) {
            val msg = Messages.t(sess.language, "flash.build.keystoreRequired")
            call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
            return@post
        }
        val row = runCatching { builds.enqueueDebug(id, hub) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.build.queueFailed")
            log.warn(e) { "build enqueue failed: $id" }
            call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "build enqueued: ${row.id} project=$id by ${sess.username}" }
        authDeps.audit.buildEnqueue(sess.userId, id, row.id, call.request.origin.remoteHost)
        // 새 빌드는 곧바로 상세 페이지로 — 실시간 로그를 바로 본다.
        call.respondRedirect("/projects/$id/builds/${row.id}")
    }

    // v1.107.0 — Release APK / AAB 번들 빌드 큐 등록. /builds(debug) 와 동일 가드.
    // variant: "release" → assembleRelease, "bundle" → bundleRelease. 둘 다 키스토어 서명 주입.
    for (variant in listOf("release", "bundle")) {
        post("/projects/{id}/builds/$variant") {
            val sess = requireSessionOrRedirect(authDeps) ?: return@post
            if (!requireWriteAccessOrRedirect(sess)) return@post
            requireCsrf()
            val id = call.parameters["id"]!!
            requireProjectAccessOrThrow(sess, projects, id)
            val pkgOk = runCatching {
                keystoreService.get(projects.get(id).packageName) != null
            }.onFailure { log.warn(it) { "keystore probe failed (POST /builds/$variant): project=$id" } }
                .getOrDefault(false)
            if (!pkgOk) {
                val msg = Messages.t(sess.language, "flash.build.keystoreRequired")
                call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
                return@post
            }
            val row = runCatching {
                if (variant == "bundle") builds.enqueueBundle(id, hub) else builds.enqueueRelease(id, hub)
            }.getOrElse { e ->
                val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.build.queueFailed")
                log.warn(e) { "$variant build enqueue failed: $id" }
                call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
                return@post
            }
            log.info { "$variant build enqueued: ${row.id} project=$id by ${sess.username}" }
            authDeps.audit.buildEnqueue(sess.userId, id, row.id, call.request.origin.remoteHost)
            call.respondRedirect("/projects/$id/builds/${row.id}")
        }
    }

    get("/projects/{id}/builds/{buildId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val buildId = call.parameters["buildId"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val row = buildRepo.get(buildId)
        if (row == null || row.projectId != id) {
            call.respondRedirect("/projects/$id/builds?err=${Messages.t(sess.language, "flash.build.notFound", buildId).encodeUrl()}")
            return@get
        }
        val dto = BuildDto(
            id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
            startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
            artifactId = row.artifactId, errorMessage = row.errorMessage, failureKind = row.failureKind,
            gitBranch = row.gitBranch, gitSha = row.gitSha,
        )
        val artifact = row.artifactId?.let { artifactRepo.get(id, it) }
        val testFlightUploads = testFlightUploadJobRepo?.listForProject(id, limit = 8).orEmpty()

        // 종료된 빌드면 디스크 로그 파일을 읽어 prerender. (WS ring 은 evicted 일 수 있음)
        val isTerminal = row.status.name in setOf("SUCCESS", "FAILED", "CANCELED", "TIMEOUT")
        val replay = if (isTerminal) loadBuildLog(workspace, id, buildId, row.logPath) else null

        // v0.22.0 — Play 업로드 카드용 precheck. 빌드 성공일 때만 의미 있음.
        val playPrecheck = if (row.status.name == "SUCCESS") {
            runCatching { playPublishService.precheck(p.packageName) }.getOrNull()
        } else null
        // v0.23.0 — TestFlight precheck 는 빌드 상태와 무관 (vibe-coder 는 iOS 빌드 안 함).
        val testFlightPrecheck = runCatching { testFlightPublishService.precheck() }.getOrNull()

        // v0.28.0 — APK 서명 검사. SUCCESS + artifact 가 .apk 일 때만.
        val signerInspection = if (artifact != null && artifact.fileName.endsWith(".apk", ignoreCase = true)) {
            runCatching { apkSignerInspector.inspect(java.nio.file.Path.of(artifact.filePath)) }.getOrNull()
        } else null

        // v0.58.0 — Phase 37 이전 성공 빌드와의 비교 (size / duration delta).
        // v0.89.0 — Phase 65 PR 별 비교 (기본=same branch). ?compare=any 면 cross-branch
        //   fallback (main 머지 직후 PR vs main 비교 use case).
        val crossBranch = call.request.queryParameters["compare"] == "any"
        val comparison = if (row.status.name == "SUCCESS") {
            runCatching { builds.compareWithPrevious(id, buildId, artifactRepo, crossBranch = crossBranch) }.getOrNull()
        } else null

        call.respondText(
            WebProjectTemplates.buildDetailPage(
                sess.username, p, dto, artifact, replay,
                playPrecheck = playPrecheck,
                playFlashOk = call.request.queryParameters["play_ok"],
                playFlashErr = call.request.queryParameters["play_err"],
                testFlightPrecheck = testFlightPrecheck,
                testFlightUploads = testFlightUploads,
                tfFlashOk = call.request.queryParameters["tf_ok"],
                tfFlashErr = call.request.queryParameters["tf_err"],
                signerInspection = signerInspection,
                comparison = comparison,
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds/{buildId}/ios-fix-prompt") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val buildId = call.parameters["buildId"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val row = buildRepo.get(buildId)
        if (row == null || row.projectId != id) {
            call.respondRedirect("/projects/$id/builds?err=${Messages.t(sess.language, "flash.build.notFound", buildId).encodeUrl()}")
            return@post
        }
        if (row.status.name != "FAILED" || !row.variant.startsWith("ios-") || row.errorMessage.isNullOrBlank()) {
            call.respondRedirect("/projects/$id/builds/$buildId")
            return@post
        }
        val dispatcher = consolePromptDispatcher
        if (dispatcher == null) {
            call.respondRedirect("/projects/$id/builds?err=${Messages.t(sess.language, "flash.console.dispatcherUnavailable").encodeUrl()}")
            return@post
        }
        val prompt = iosBuildFailureFixPrompt(
            projectName = p.name,
            projectId = id,
            buildId = buildId,
            variant = row.variant,
            failureKind = row.failureKind,
            errorMessage = row.errorMessage,
            logPath = row.logPath,
        )
        runCatching {
            dispatcher.send(
                ownerUserId = sess.userId,
                projectId = id,
                text = prompt,
                requestedProvider = null,
                source = "ios_build_failure_fix",
            )
        }.onFailure { e ->
            log.warn(e) { "iOS build failure fix prompt send failed: project=$id build=$buildId" }
            call.respondRedirect("/projects/$id/builds?err=${Messages.t(sess.language, "flash.console.promptSendFailed").encodeUrl()}")
            return@post
        }
        log.info { "iOS build failure fix prompt sent: project=$id build=$buildId by ${sess.username}" }
        call.respondRedirect("/projects/$id/console")
    }

    /**
     * v0.22.0 — Play Console (Internal/Alpha/Beta/Production) 업로드 트리거.
     *
     * 사전조건이 충족되지 않아도 (예: MCP 미설치) 그대로 prompt 전송 — 콘솔 provider 가
     * 첫 응답에서 즉시 오류를 보고하므로 사용자가 어디서 막혔는지 명확.
     * 단 ProjectService.get(id) 통과 + AAB 경로 입력 필수.
     */
    post("/projects/{id}/builds/{buildId}/play-upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        // requireCsrf() 가 receiveParameters() 로 본문을 1회 읽고 검증 후 반환한다.
        // Ktor 3.x 는 본문 채널을 1회만 읽을 수 있어 별도 receiveParameters() 재호출 금지.
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val buildId = call.parameters["buildId"]!!
        // 프로젝트 존재 검증만 필요 — 반환값은 사용하지 않음(testflight-upload 와 동일 패턴).
        runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val aabPath = form["aabPath"]?.trim().orEmpty()
        val track = form["track"]?.trim().orEmpty().ifBlank { "internal" }
        val notes = form["releaseNotes"]?.trim()
        if (aabPath.isBlank()) {
            call.respondRedirect("/projects/$id/builds/$buildId?play_err=${Messages.t(sess.language, "flash.publish.aabRequired").encodeUrl()}")
            return@post
        }
        runCatching {
            playPublishService.trigger(
                projectId = id,
                aabRelativePath = aabPath,
                track = track,
                releaseNotes = notes,
            )
        }.onFailure { e ->
            log.warn(e) { "play upload trigger failed: $id $buildId" }
            authDeps.audit.playUploadFailed(sess.userId, id, buildId, call.request.origin.remoteHost, e.message)
            call.respondRedirect("/projects/$id/builds/$buildId?play_err=${Messages.t(sess.language, "flash.publish.uploadFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        log.info { "play upload prompt sent: project=$id build=$buildId track=$track aab=$aabPath by ${sess.username}" }
        authDeps.audit.playUploadTriggered(sess.userId, id, buildId, call.request.origin.remoteHost, track)
        // 사용자를 콘솔로 이동 — Claude 의 진행이 라이브로 보이는 곳.
        call.respondRedirect("/projects/$id/console")
    }

    /**
     * v0.23.0 — TestFlight 업로드 트리거. PlayPublish 와 동일 패턴.
     */
    post("/projects/{id}/builds/{buildId}/testflight-upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        // requireCsrf() 가 본문을 1회 읽고 검증 후 반환 — receiveParameters() 재호출 금지(Ktor 1회 제한).
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val buildId = call.parameters["buildId"]!!
        val project = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val row = buildRepo.get(buildId)
        val artifact = row?.artifactId?.let { artifactRepo.get(id, it) }
        val ipaPath = form["ipaPath"]?.trim().orEmpty()
        val groups = form["distributionGroups"]?.trim()?.takeIf { it.isNotBlank() }
        val notes = form["releaseNotes"]?.trim()
        if (ipaPath.isBlank()) {
            call.respondRedirect("/projects/$id/builds/$buildId?tf_err=${Messages.t(sess.language, "flash.publish.ipaRequired").encodeUrl()}")
            return@post
        }
        runCatching {
            val asc = testFlightPublishService.diagnoseApp(project.packageName)
            val buildNumber = testFlightPublishService.nextBuildNumber(project.sourcePath)
            testFlightPublishService.trigger(
                com.siamakerlab.vibecoder.server.publish.TestFlightPublishService.UploadRequest(
                    projectId = id,
                    buildId = buildId,
                    artifactId = artifact?.id,
                    ipaRelativePath = ipaPath,
                    bundleId = project.packageName,
                    appId = asc?.matchingAppId,
                    appName = asc?.matchingAppName,
                    buildNumber = buildNumber,
                    distributionGroups = groups,
                    releaseNotes = notes,
                )
            )
        }.onFailure { e ->
            log.warn(e) { "testflight upload trigger failed: $id $buildId" }
            authDeps.audit.testFlightUploadFailed(sess.userId, id, buildId, call.request.origin.remoteHost, e.message)
            call.respondRedirect("/projects/$id/builds/$buildId?tf_err=${Messages.t(sess.language, "flash.publish.uploadFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        log.info { "testflight upload prompt sent: project=$id build=$buildId ipa=$ipaPath groups=$groups by ${sess.username}" }
        authDeps.audit.testFlightUploadTriggered(sess.userId, id, buildId, call.request.origin.remoteHost, groups)
        call.respondRedirect("/projects/$id/console")
    }

    post("/projects/{id}/builds/{buildId}/cancel") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val buildId = call.parameters["buildId"]!!
        try {
            builds.cancel(buildId)
        } catch (e: Throwable) {
            log.warn(e) { "build cancel failed: $buildId" }
        }
        log.info { "build cancel: $buildId project=$id by ${sess.username}" }
        authDeps.audit.buildCancel(sess.userId, id, buildId, call.request.origin.remoteHost)
        call.respondRedirect("/projects/$id/builds/$buildId")
    }

    // ── 파일 트리 / 보기 / 편집 (v0.13.0)
    // v1.14.0 — 기존 /projects/{id}/files (uploads 카탈로그) UI/라우트 제거. 그 자리에
    // 파일 탐색기 (mkdir / createFile / rename / delete / upload) 액션을 /tree 페이지에
    // 직접 통합. uploads 테이블 자체는 보존 — 다른 곳에서 사용 가능.
    // ─────────────────────────────────────────────────────────────────
    get("/projects/{id}/tree") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val subPath = call.request.queryParameters["path"].orEmpty()
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        val entries = runCatching { fileBrowser.list(id, subPath) }.getOrElse {
            val msg = (it as? ApiException)?.message ?: it.message ?: Messages.t(sess.language, "flash.file.listFailed")
            call.respondText(
                WebProjectTemplates.fileTreePage(
                    sess.username, p, subPath, emptyList(),
                    flashErr = msg, csrf = sess.csrf,
                    lang = sess.language,
                    embed = call.isEmbeddedRequest(),
                ),
                ContentType.Text.Html, HttpStatusCode.BadRequest,
            )
            return@get
        }
        call.respondText(
            WebProjectTemplates.fileTreePage(
                sess.username, p, subPath, entries,
                flashErr = err, flashOk = ok, csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // v1.14.0 — 파일 탐색기 액션 (mkdir / createFile / rename / delete / upload).
    // 모두 form POST + redirect → /tree?path=<현재 디렉토리>. parent 는 form 의 hidden
    // input "parent" (현재 사용자가 보고 있던 subPath) 로부터.

    post("/projects/{id}/files/new-folder") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parent = params["parent"].orEmpty()
        val name = params["name"].orEmpty().trim()
        val relPath = if (parent.isBlank()) name else "$parent/$name"
        val result = runCatching { fileBrowser.mkdir(id, relPath) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()!!
            val msg = (e as? ApiException)?.message ?: e.message ?: "mkdir failed"
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&err=${msg.encodeUrl()}")
            return@post
        }
        call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.folderCreated", name).encodeUrl()}")
    }

    post("/projects/{id}/files/new-file") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parent = params["parent"].orEmpty()
        val name = params["name"].orEmpty().trim()
        val relPath = if (parent.isBlank()) name else "$parent/$name"
        val result = runCatching { fileBrowser.createFile(id, relPath) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()!!
            val msg = (e as? ApiException)?.message ?: e.message ?: "create failed"
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&err=${msg.encodeUrl()}")
            return@post
        }
        // 신규 파일은 바로 편집 페이지로 — 사용자가 즉시 내용 입력.
        call.respondRedirect("/projects/$id/view?path=${relPath.encodeUrl()}&ok=created")
    }

    post("/projects/{id}/files/rename") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = params["path"].orEmpty()
        val newName = params["newName"].orEmpty().trim()
        // parent 추출 — relPath 의 마지막 / 앞.
        val parent = relPath.substringBeforeLast('/', "")
        val result = runCatching { fileBrowser.rename(id, relPath, newName) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()!!
            val msg = (e as? ApiException)?.message ?: e.message ?: "rename failed"
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&err=${msg.encodeUrl()}")
            return@post
        }
        call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.renamed", newName).encodeUrl()}")
    }

    post("/projects/{id}/files/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = params["path"].orEmpty()
        val parent = relPath.substringBeforeLast('/', "")
        val result = runCatching { fileBrowser.delete(id, relPath) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()!!
            val msg = (e as? ApiException)?.message ?: e.message ?: "delete failed"
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&err=${msg.encodeUrl()}")
            return@post
        }
        call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.deleted").encodeUrl()}")
    }

    // v1.19.0 — 다중 선택 모드 액션. paths form param 은 newline-separated relPath 목록.

    post("/projects/{id}/files/copy") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val dstParent = params["dstParent"].orEmpty()
        val paths = (params["paths"].orEmpty()).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var failMsg: String? = null
        for (src in paths) {
            try {
                fileBrowser.copy(id, src, dstParent)
            } catch (e: Throwable) {
                failMsg = (e as? ApiException)?.message ?: e.message ?: "copy failed"
                break
            }
        }
        if (failMsg != null) {
            call.respondRedirect("/projects/$id/tree?path=${dstParent.encodeUrl()}&err=${failMsg.encodeUrl()}")
        } else {
            call.respondRedirect("/projects/$id/tree?path=${dstParent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.copied", paths.size).encodeUrl()}")
        }
    }

    post("/projects/{id}/files/move") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val dstParent = params["dstParent"].orEmpty()
        val paths = (params["paths"].orEmpty()).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var failMsg: String? = null
        for (src in paths) {
            try {
                fileBrowser.move(id, src, dstParent)
            } catch (e: Throwable) {
                failMsg = (e as? ApiException)?.message ?: e.message ?: "move failed"
                break
            }
        }
        if (failMsg != null) {
            call.respondRedirect("/projects/$id/tree?path=${dstParent.encodeUrl()}&err=${failMsg.encodeUrl()}")
        } else {
            call.respondRedirect("/projects/$id/tree?path=${dstParent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.moved", paths.size).encodeUrl()}")
        }
    }

    post("/projects/{id}/files/delete-batch") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parent = params["parent"].orEmpty()
        val paths = (params["paths"].orEmpty()).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var deleted = 0
        var failMsg: String? = null
        for (p in paths) {
            try {
                fileBrowser.delete(id, p)
                deleted++
            } catch (e: Throwable) {
                failMsg = (e as? ApiException)?.message ?: e.message ?: "delete failed"
                break
            }
        }
        if (failMsg != null) {
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&err=${failMsg.encodeUrl()}")
        } else {
            call.respondRedirect("/projects/$id/tree?path=${parent.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.deletedN", deleted).encodeUrl()}")
        }
    }

    // 단일 파일 다운로드 — 이미지 외 일반 binary/text 모두. attachment disposition.
    // v1.24.0 — MAX_DOWNLOAD_BYTES (200 MB) 사용. 기존 /raw 의 10 MB 한도가
    // APK / AAB 다운로드 차단하던 회귀 회수.
    get("/projects/{id}/files/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = call.request.queryParameters["path"].orEmpty()
        val raw = runCatching {
            fileBrowser.resolveForRawRead(id, relPath,
                maxBytes = com.siamakerlab.vibecoder.server.files.ProjectFileBrowser.MAX_DOWNLOAD_BYTES)
        }.getOrElse { e ->
            val sc = (e as? ApiException)?.statusCode ?: 500
            call.respondText(e.message ?: "download failed", ContentType.Text.Plain, HttpStatusCode.fromValue(sc))
            return@get
        }
        val name = relPath.substringAfterLast('/')
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, name,
            ).toString(),
        )
        call.response.header(HttpHeaders.ContentType, raw.mime)
        call.respondFile(raw.absolutePath.toFile())
    }

    // 다중 파일 다운로드 → zip stream. paths 는 form post (newline-separated) 또는
    // GET query 의 `paths` (\n encoded). UI 는 hidden form POST 로 호출.
    post("/projects/{id}/files/download-zip") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val paths = (params["paths"].orEmpty()).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) {
            call.respondText("no paths", ContentType.Text.Plain, HttpStatusCode.BadRequest)
            return@post
        }
        // 각 path 를 resolveForRawRead 로 검증 — 디렉토리도 허용 (raw 는 file 만이라 우회).
        // 디렉토리는 workspace projectRoot 기준 직접 walk. 안전: PathSafety.normalizeAndCheck.
        val projectRoot = workspace.projectRoot(id)
        if (!projectRoot.toFile().exists()) {
            call.respondText("project not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@post
        }
        val resolved = paths.map { rel ->
            try {
                com.siamakerlab.vibecoder.server.core.PathSafety.normalizeAndCheck(projectRoot, rel) to rel
            } catch (e: Throwable) {
                call.respondText(e.message ?: "bad path", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return@post
            }
        }
        val ts = java.time.Instant.now().toString().take(10)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, "vibe-coder-$id-$ts.zip",
            ).toString(),
        )
        call.respondOutputStream(ContentType.Application.Zip) {
            java.util.zip.ZipOutputStream(this).use { zip ->
                for ((abs, rel) in resolved) {
                    if (!java.nio.file.Files.exists(abs)) continue
                    if (java.nio.file.Files.isSymbolicLink(abs)) continue
                    // v1.51.0 — 25차: 중간 디렉토리 symlink 로 워크스페이스 밖 파일이 zip 에 담기는
                    // read-escape 차단(lexical normalizeAndCheck 만으론 부족). 위반 시 해당 entry skip.
                    if (runCatching { com.siamakerlab.vibecoder.server.core.PathSafety.assertRealInside(projectRoot, abs) }.isFailure) continue
                    if (java.nio.file.Files.isDirectory(abs)) {
                        // 재귀 walk — 디렉토리 안의 모든 정규 파일 entry. zip 안 path 는
                        // rel 기준 상대.
                        java.nio.file.Files.walk(abs).use { stream ->
                            stream.forEach { p ->
                                if (java.nio.file.Files.isSymbolicLink(p)) return@forEach
                                if (java.nio.file.Files.isRegularFile(p)) {
                                    val sub = abs.parent.relativize(p).toString().replace('\\', '/')
                                    zip.putNextEntry(java.util.zip.ZipEntry(sub))
                                    java.nio.file.Files.copy(p, zip)
                                    zip.closeEntry()
                                }
                            }
                        }
                    } else if (java.nio.file.Files.isRegularFile(abs)) {
                        val name = rel.substringAfterLast('/').ifEmpty { rel }
                        zip.putNextEntry(java.util.zip.ZipEntry(name))
                        java.nio.file.Files.copy(abs, zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    post("/projects/{id}/files/upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        // multipart 라 requireCsrf 못 씀 → query string `?_csrf=...` 또는 헤더 검증.
        CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        // v1.27.4 (B1 회수) — parent 를 query param 에서 먼저 확정. multipart part 순서
        // 의존 제거: file part 가 parent FormItem 보다 먼저 와도 정확한 디렉토리에 업로드.
        // FormItem "parent" 는 query 가 없을 때만 fallback (구형 form 호환).
        val multipart = call.receiveMultipart()
        var parentRel = call.request.queryParameters["parent"].orEmpty()
        var fileName: String? = null
        var stream: java.io.InputStream? = null
        var failMsg: String? = null
        try {
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "parent" && parentRel.isBlank()) parentRel = part.value
                        }
                        is PartData.FileItem -> {
                            if (fileName == null) {
                                fileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: "upload"
                                // stream 을 즉시 소비 — provider 가 close 후엔 사용 불가.
                                part.provider().toInputStream().use { input ->
                                    fileBrowser.uploadStream(id, parentRel, fileName!!, input, overwrite = false)
                                }
                            }
                        }
                        else -> {}
                    }
                } catch (e: Throwable) {
                    failMsg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.file.uploadFailed")
                } finally {
                    part.dispose()
                }
            }
        } catch (e: Throwable) {
            failMsg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.file.uploadFailed")
        }
        if (failMsg != null) {
            call.respondRedirect("/projects/$id/tree?path=${parentRel.encodeUrl()}&err=${failMsg.encodeUrl()}")
            return@post
        }
        if (fileName == null) {
            call.respondRedirect("/projects/$id/tree?path=${parentRel.encodeUrl()}&err=${Messages.t(sess.language, "flash.file.noFileSelected").encodeUrl()}")
            return@post
        }
        call.respondRedirect("/projects/$id/tree?path=${parentRel.encodeUrl()}&ok=${Messages.t(sess.language, "flash.file.uploaded", fileName!!).encodeUrl()}")
    }

    get("/projects/{id}/view") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = call.request.queryParameters["path"].orEmpty()
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        // v1.17.0 — 이미지 파일은 별도 image 모드로 분기. fileBrowser.read() 가
        // binary 차단하기 전에 확장자 기준 판정 — viewer 가 <img src="/raw?path=..."> 사용.
        if (com.siamakerlab.vibecoder.server.files.ProjectFileBrowser.isImagePath(relPath)) {
            // path traversal / 파일 존재 검증만 미리 시도. 실패 시 error banner 렌더.
            val sizeOrNull = runCatching {
                fileBrowser.resolveForRawRead(id, relPath).sizeBytes
            }.getOrNull()
            if (sizeOrNull == null) {
                val msg = Messages.t(sess.language, "flash.file.openFailed")
                call.respondText(
                    WebProjectTemplates.fileViewPage(
                        sess.username, p, relPath, null,
                        flashErr = msg, csrf = sess.csrf, lang = sess.language,
                        embed = call.isEmbeddedRequest(),
                    ),
                    ContentType.Text.Html, HttpStatusCode.BadRequest,
                )
                return@get
            }
            call.respondText(
                WebProjectTemplates.fileViewPage(
                    sess.username, p, relPath, view = null,
                    imageSizeBytes = sizeOrNull, csrf = sess.csrf, lang = sess.language,
                    embed = call.isEmbeddedRequest(),
                ),
                ContentType.Text.Html,
            )
            return@get
        }
        val view = runCatching { fileBrowser.read(id, relPath) }.getOrElse {
            val msg = (it as? ApiException)?.message ?: it.message ?: Messages.t(sess.language, "flash.file.openFailed")
            call.respondText(
                WebProjectTemplates.fileViewPage(
                    sess.username, p, relPath, null,
                    flashErr = msg, csrf = sess.csrf,
                    lang = sess.language,
                    embed = call.isEmbeddedRequest(),
                ),
                ContentType.Text.Html, HttpStatusCode.BadRequest,
            )
            return@get
        }
        call.respondText(
            WebProjectTemplates.fileViewPage(
                sess.username, p, relPath, view, csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // v1.17.0 — 이미지 / binary 파일 raw stream. <img src="..."> 가 직접 fetch.
    // v1.24.0 — 보안 강화: X-Content-Type-Options: nosniff + CSP sandbox 헤더. SVG 같은
    // active-content MIME 은 guessImageMime 에서 octet-stream 으로 downgrade (XSS 방어).
    get("/projects/{id}/raw") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = call.request.queryParameters["path"].orEmpty()
        val raw = runCatching { fileBrowser.resolveForRawRead(id, relPath) }.getOrElse { e ->
            val sc = (e as? ApiException)?.statusCode ?: 500
            call.respondText(e.message ?: "raw read failed", ContentType.Text.Plain, HttpStatusCode.fromValue(sc))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
        call.response.header(HttpHeaders.ContentType, raw.mime)
        call.response.header("X-Content-Type-Options", "nosniff")
        // CSP sandbox: 응답이 어떤 동적 코드도 실행 못 함 (이미지/바이너리 의도된 용도).
        // 같은 origin 이라도 stored-XSS 차단.
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        // v1.24.1 — Active-content MIME (svg / html / js) 인 경우 attachment 강제 →
        // 일부 브라우저가 nosniff+CSP 우회해 inline 렌더 시도하는 케이스까지 차단.
        // octet-stream downgrade 와 이중 방어.
        // v1.25.0 — path 확장자 기반 판정도 OR. v1.24.0 의 guessImageMime(.svg) 가
        // 이미 octet-stream 으로 downgrade 한 후라 mime 만 보면 SVG 가 untrusted 로
        // 잡히지 않던 회귀 회수.
        if (com.siamakerlab.vibecoder.server.files.ProjectFileBrowser.isUntrustedPathOrMime(relPath, raw.mime)) {
            val name = relPath.substringAfterLast('/')
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, name,
                ).toString(),
            )
        }
        call.respondFile(raw.absolutePath.toFile())
    }

    post("/projects/{id}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val relPath = params["path"].orEmpty()
        val content = params["content"].orEmpty()
        try {
            fileBrowser.write(id, relPath, content)
        } catch (e: Throwable) {
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.file.saveFailed")
            call.respondRedirect("/projects/$id/view?path=${relPath.encodeUrl()}&err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "file saved by ${sess.username}: $id :: $relPath" }
        call.respondRedirect("/projects/$id/view?path=${relPath.encodeUrl()}&ok=saved")
    }

    // ── Git ───────────────────────────────────────────────────────────
    get("/projects/{id}/git") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val source = Path.of(p.sourcePath)
        // git이 초기화 안 됐거나 외부 명령 자체가 실패해도 페이지는 살아야 한다.
        val status = runCatching { gitReader.status(source) }.getOrElse { null }
        val diff = runCatching { gitReader.diff(source) }.getOrElse { null }
        val gitLog = runCatching { gitReader.log(source, count = 10) }.getOrElse { null }
        val unavailable = status == null && diff == null && gitLog == null

        val flash = call.request.queryParameters["flash"]
        call.respondText(
            WebProjectTemplates.gitPage(
                sess.username, p,
                status = status, diff = diff, log = gitLog,
                unavailable = unavailable,
                csrf = sess.csrf,
                commitFlash = flash,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // v0.18.0 — commit / push from the git page form
    post("/projects/{id}/git/commit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val message = params["message"].orEmpty()
        val push = params["push"] != null
        val onlyTracked = params["onlyTracked"] != null
        val source = projects.sourcePathOrThrow(id)
        val result = try {
            gitWriter.commitAndPush(source, message, push = push, onlyTracked = onlyTracked)
        } catch (e: Throwable) {
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.git.commitFailed")
            authDeps.audit.let { /* audit via REST API path only — SSR ignores for noise */ }
            call.respondRedirect("/projects/$id/git?flash=${msg.take(200).encodeUrl()}")
            return@post
        }
        val flashMsg = buildString {
            if (result.committed) append("✓ committed ${result.sha?.take(8) ?: ""} on ${result.branch}")
            else append("(no changes)")
            if (push) {
                appendLine()
                if (result.pushed) append("✓ pushed to origin/${result.branch}")
                else append("✗ push failed — see log on the page (try again)")
            }
        }
        log.info { "git commit by ${sess.username} on $id: committed=${result.committed} pushed=${result.pushed}" }
        call.respondRedirect("/projects/$id/git?flash=${flashMsg.encodeUrl()}")
    }

    // ── /chat — General Chat (ChatGPT 스타일 다중 세션) v1.54.0 ───
    // 각 채팅 = `__chat_<id>__` ghost 프로젝트. 좌측 사이드바에 채팅 목록, 우측에 콘솔.
    // 기존 단일 /chat (v0.13.0 __scratch__) 에서 확장 — prompt/cancel/new API 는 그대로
    // 재사용(projectId = 활성 chat ghost id). 채팅 없으면 자동으로 1개 생성.
    get("/chat") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        var chats = projects.listChats()
        if (chats.isEmpty()) {
            projects.createChat()
            chats = projects.listChats()
        }
        val requested = call.request.queryParameters["c"]?.trim()?.ifBlank { null }
        val activeId = requested?.takeIf { reqId -> chats.any { it.id == reqId } }
            ?: chats.first().id
        val p = projects.ensureChat(activeId)
        val alive = sessionManager.isAlive(p.id)
        val sid = sessionManager.currentSessionId(p.id)
        val ctxSnap = sessionManager.contextSnapshot(p.id)  // v1.106.1 — 컨텍스트 미터 초기값
        val env = authDeps.envDiagnostics.run(sess.language)
        // v1.7.3 — Chat 도 동일하게 history 영속 복원.
        // v1.104.2 — console 과 동일: 최근 200 turn(offset=total-200). 이전엔 oldest 200.
        val history = if (sid != null) {
            runCatching {
                val f = ConversationTurnRepository.Filter(projectId = p.id, sessionId = sid)
                val off = (conversationRepo.count(f) - 200).coerceAtLeast(0)
                conversationRepo.list(f, limit = 200, offset = off)
            }.getOrDefault(emptyList())
        } else emptyList()
        val activeTitle = chats.firstOrNull { it.id == activeId }?.title
        val sidebar = WebProjectTemplates.chatSidebar(chats, activeId, sess.csrf, sess.language)
        val chatModel = sessionManager.effectiveModel(p.id)
        val chatModelOptions = modelCatalog?.modelsFor(
            provider = AgentProvider.CLAUDE,
            currentModel = chatModel,
            effectiveModel = chatModel,
        ).orEmpty()
        call.respondText(
            WebProjectTemplates.consolePage(
                sess.username, p, sid, alive,
                claudeCli = env.claude,
                claudeAuth = env.claudeAuth,
                csrf = sess.csrf,
                isChat = true,
                initialHistory = history,
                chatSidebar = sidebar,
                chatTitle = activeTitle,
                model = chatModel,
                variant = (agentRouter?.managerFor(AgentProvider.OPENCODE) as? com.siamakerlab.vibecoder.server.agent.opencode.OpenCodeSessionManager)?.effectiveVariant(p.id) ?: "",
                contextTokens = ctxSnap.cacheRead,
                contextInputTokens = ctxSnap.input,
                contextCacheCreationTokens = ctxSnap.cacheCreation,
                contextLimit = ctxSnap.limit,
                contextWarnTokens = sessionManager.contextWarnTokens(),
                mcpStrict = sessionManager.isMcpStrict(p.id),
                tuiMode = true,
                availableModelOptions = chatModelOptions,
                uiCapabilities = projects.uiCapabilities(p.projectType),
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    // ── 새 채팅 생성 → 새 채팅 콘솔로 이동 ──────────────────────────
    post("/chat/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = projects.createChat()
        call.respondRedirect("/chat?c=${id.encodeUrl()}")
    }

    // ── 채팅 제목 변경 ─────────────────────────────────────────────
    post("/chat/{id}/rename") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"].orEmpty()
        if (!ProjectService.isChatGhost(id)) {
            call.respondRedirect("/chat"); return@post
        }
        val title = params["title"]?.trim().orEmpty()
        runCatching { projects.renameChat(id, title) }
            .onFailure { log.warn(it) { "chat rename failed: $id" } }
        call.respondRedirect("/chat?c=${id.encodeUrl()}")
    }

    // ── 채팅 삭제 (살아있는 세션 종료 + cascade 정리) ──────────────
    post("/chat/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"].orEmpty()
        if (ProjectService.isChatGhost(id)) {
            // 진행 중인 Claude 자식 프로세스 정리 + session-id 파일 제거.
            runCatching { sessionManager.startNew(id) }
            runCatching { projects.deleteChat(id) }
                .onFailure { log.warn(it) { "chat delete failed: $id" } }
        }
        call.respondRedirect("/chat")
    }

    // ── v0.29.0 — 프로젝트 source zip 다운로드 ─────────────────────
    get("/projects/{id}/zip") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        // ProjectService.get 으로 존재 확인 (잘못된 id → 404).
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        log.info { "project zip download: $id by ${sess.username}" }
        val safeName = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())
        call.response.header(
            io.ktor.http.HttpHeaders.ContentDisposition,
            "attachment; filename=\"${safeName}-source-${ts}.zip\"",
        )
        call.respondOutputStream(ContentType.parse("application/zip")) {
            runCatching { projectArchiver.streamZip(id, this) }
                .onFailure { log.warn(it) { "zip stream failed for $id: ${it.message}" } }
            Unit
            // p 는 nullability 검증/log 용. 더 이상 참조 불요.
            @Suppress("UNUSED_EXPRESSION") p
        }
    }

    // ── 콘솔: 슬래시 커맨드 endpoint (v0.75.0 deprecated, no-op) ──────
    // v0.75.0: slash chip UI 제거. endpoint 자체는 유지 — 외부 link / 북마크 호환.
    // 사용자가 직접 POST 해도 무음으로 console 페이지로 redirect (실제 prompt 발송 X).
    // 이유: claude --print --output-format stream-json non-interactive 모드는
    // Claude Code 의 slash commands (/status, /cost, /model 등) 미지원 → 그냥
    // prompt 텍스트로 처리되어 Claude 가 못 알아들음. UI 에서 chip 도 제거.
    post("/projects/{id}/console/slash") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        requireCsrf()  // CSRF 검증은 유지 — 외부 트리거 방지.
        log.info { "slash chip ignored (deprecated v0.75.0): project=$id by ${sess.username}" }
        val target = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            else -> "/projects/$id/console"
        }
        call.respondRedirect(target)
    }
}

private fun projectListOkFlash(lang: String, ok: String): String = when (ok) {
    "created" -> Messages.t(lang, "flash.project.created")
    "deleted" -> Messages.t(lang, "flash.project.deleted")
    else -> ok
}

/**
 * 폼 flash 메시지를 query string 으로 옮길 때 쓰는 단순 URL encoder.
 * java.net.URLEncoder 가 form-encoding 이라 공백을 `+` 로 바꿔서 SSR에 보이기 어색.
 * 표준 percent-encoding 으로 통일한다.
 */
private fun String.encodeUrl(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

/**
 * 프로젝트 상태칩의 SSR 초기 렌더 값([ProjectState] wire).
 *
 * TUI-only 상태 판정은 [AgentStatusStore] 를 우선한다. 상태 store 에 아직 live snapshot 이
 * 없으면 구 Claude manager 의 in-memory 상태만 보조로 보고, 과거 대화 이력 기반 interrupted
 * 추론은 사용하지 않는다. 종료된 세션은 idle 로 수렴해야 하며, 이전 user turn 이 미완료라는
 * 이유만으로 SSR 초기 렌더가 stopped 를 되살리면 stale 보라색 상태가 재발한다.
 */
private fun projectStatus(
    id: String,
    sessionManager: ClaudeSessionManager,
    agentStatusStore: AgentStatusStore?,
): String {
    agentStatusStore?.get(id, AgentProvider.CLAUDE)?.legacyProjectState?.let { return it.wire }
    sessionManager.busyStateOrNull(id)?.let { return it.wire }
    return ProjectState.READY.wire
}

private fun effectiveConsoleContextSnapshot(
    projectId: String,
    provider: AgentProvider,
    model: String?,
    sessionId: String?,
    agentRouter: AgentRouter?,
    sessionManager: ClaudeSessionManager,
    conversationRepo: ConversationTurnRepository,
): AgentContextSnapshot {
    val live = agentRouter?.contextSnapshot(projectId)
        ?: sessionManager.contextSnapshot(projectId).let {
            AgentContextSnapshot(
                input = it.input,
                cacheRead = it.cacheRead,
                cacheCreation = it.cacheCreation,
                limit = it.limit,
            )
    }
    if (live.used > 0 && live.limit > 0) return live
    val latest = sessionId?.let { conversationRepo.latestUsageContext(projectId, provider.id, it) } ?: return live
    val used = latest.usedInput
    if (used <= 0) return live
    return AgentContextSnapshot(
        input = latest.inputTokens,
        cacheRead = latest.cacheReadTokens,
        cacheCreation = latest.cacheCreationTokens,
        limit = contextLimitForProvider(provider, model, used + latest.outputTokens),
    )
}

private fun contextLimitForProvider(provider: AgentProvider, model: String?, used: Long): Long =
    when (provider) {
        AgentProvider.CLAUDE -> {
            val m = model?.lowercase().orEmpty()
            if (m.contains("haiku")) 200_000L else 1_000_000L
        }
        AgentProvider.CODEX,
        AgentProvider.OPENCODE -> when {
            used <= 0L -> 0L
            used <= 128_000L -> 128_000L
            used <= 200_000L -> 200_000L
            else -> used
        }
    }

/** 종료된 빌드의 디스크 로그를 읽어 화면에 prerender 할 수 있게 가공한 결과. */
data class BuildLogReplay(
    val lines: List<BuildLogLine>,
    val truncated: Boolean,
    val totalLines: Int,
    val sizeBytes: Long,
    val sourcePath: String,
)

data class BuildLogLine(
    val ts: String,
    val level: String,
    val message: String,
)

/**
 * TaskLogger 가 쓴 `[ts] [level] message` 라인 파일을 읽어 [BuildLogReplay] 로 변환.
 *
 * - 너무 큰 파일에서 페이지를 OOM 시키지 않도록 마지막 [MAX_REPLAY_LINES] 줄만
 *   유지. 잘렸으면 `truncated=true` 로 UI 가 안내.
 * - 보안: 항상 `WorkspacePath.ensureUnderWorkspace` 로 외부 경로 거부.
 *   `logPath` 가 DB row 에서 왔어도 신뢰하지 않는다.
 * - 파일 미존재 / IO 실패 → null. 페이지는 정상 렌더되고 안내만 표시.
 */
private fun loadBuildLog(
    workspace: WorkspacePath,
    projectId: String,
    buildId: String,
    storedLogPath: String?,
): BuildLogReplay? {
    val pathStr = storedLogPath?.takeIf { it.isNotBlank() } ?: return null
    val path = runCatching { java.nio.file.Paths.get(pathStr) }.getOrNull() ?: return null
    val safe = runCatching { workspace.ensureUnderWorkspace(path) }.getOrNull() ?: return null
    if (!Files.exists(safe) || !Files.isRegularFile(safe)) return null

    // DB row 가 가리키는 path 가 빌드 id 별 logsDir 안에 있는지 한 번 더 검증.
    val expected = workspace.buildLogFile(projectId, buildId).normalize()
    if (safe.normalize() != expected) return null

    val sizeBytes = runCatching { Files.size(safe) }.getOrDefault(0L)
    val all = runCatching { Files.readAllLines(safe, Charsets.UTF_8) }.getOrElse { emptyList() }
    val truncated = all.size > MAX_REPLAY_LINES
    val tail = if (truncated) all.subList(all.size - MAX_REPLAY_LINES, all.size) else all
    val lines = tail.map(::parseLogLine)
    return BuildLogReplay(
        lines = lines,
        truncated = truncated,
        totalLines = all.size,
        sizeBytes = sizeBytes,
        sourcePath = safe.toString(),
    )
}

private const val MAX_REPLAY_LINES = 2_000

private val LOG_LINE_REGEX = Regex("""^\[([^]]+)] \[([^]]+)] (.*)$""", RegexOption.DOT_MATCHES_ALL)

/** TaskLogger 가 쓰는 `[ts] [level] message` 포맷을 분해. 포맷 외 라인은 level=RAW. */
private fun parseLogLine(raw: String): BuildLogLine {
    val m = LOG_LINE_REGEX.matchEntire(raw)
    return if (m != null) {
        BuildLogLine(ts = m.groupValues[1], level = m.groupValues[2], message = m.groupValues[3])
    } else {
        BuildLogLine(ts = "", level = "RAW", message = raw)
    }
}
