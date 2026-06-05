package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.build.BuildService
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
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.ProjectReorderRequestDto
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
 * 프로젝트 / 콘솔 / 빌드 SSR 라우트.
 *
 * 인증 모델: AdminRoutes 와 동일하게 `vibe_session` 쿠키 기반 (requireSessionOrRedirect).
 * REST API (`/api/projects/...`) 는 그대로 두고 폼/페이지만 새로 만든다.
 */
/**
 * v1.71.0 — 패키지명 변경 시 콘솔로 보내는 Claude 작업 지시 프롬프트.
 * vibe-coder 서버는 이미 (1) DB packageName 갱신 (2) /home/vibe/keystores 의
 * 키스토어 파일명(`<pkg>.keystore` 등)을 새 패키지로 rename 해 두었다. 나머지
 * 코드/디렉토리/매니페스트/signing 참조 갱신을 Claude 가 수행한다.
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
) {

    // ── 목록 + 등록 폼 ────────────────────────────────────────────────
    get("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val full = projects.listForUser(sess.userId, sess.isAdmin)
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]?.let { Messages.t(sess.language, "flash.project.created") }
        // v1.60.0 — 페이지네이션: size 화이트리스트(20/50/100, 기본 20), page 1-base.
        val total = full.size
        val size = call.request.queryParameters["size"]?.toIntOrNull()?.takeIf { it in setOf(20, 50, 100) } ?: 20
        val pageCount = ((total + size - 1) / size).coerceAtLeast(1)
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, pageCount)
        val offset = (page - 1) * size
        val list = full.drop(offset).take(size)
        // v1.60.0 — 상태칩 3-state (응답중/대기중/중지됨). 프로세스 생존이 아닌 대화 이력 기반.
        val statuses = list.associate { it.id to projectStatus(it.id, sessionManager, conversationRepo) }
        // v1.64.0 — 행별 앱 버전(versionName) + 런처 아이콘 존재 여부(없으면 placeholder).
        val versions = list.associate { it.id to runCatching { projects.appVersionName(it.id, it.moduleName) }.getOrNull() }
        val appIcons = list.associate { it.id to runCatching { projects.resolveAppIcon(it.id, it.moduleName) != null }.getOrDefault(false) }
        call.respondText(
            WebProjectTemplates.projectsPage(
                sess.username, list, flashErr = err, flashOk = ok, csrf = sess.csrf, lang = sess.language,
                statuses = statuses, page = page, size = size, total = total,
                versions = versions, appIcons = appIcons,
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
        call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
        call.respondFile(icon.toFile())
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
                )
            )
        }

        val created = result.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.project.createFailed")
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
        // v1.56.0 — 콤보박스 각 항목 좌측 상태칩 (목록 페이지와 동일 체계).
        //  responding(응답중) > ready(대기·세션활성) > idle(유휴). 진입 시점 snapshot.
        // v1.60.0 — 3-state 상태칩 (대화 이력 기반). switcher 콤보의 각 항목 좌측 칩.
        val projectStatuses = allProjects.associate { pr ->
            pr.id to projectStatus(pr.id, sessionManager, conversationRepo)
        }
        // v1.50.0 — 우측 overview rail 데이터.
        val keystoreReady = runCatching { keystoreService.get(p.packageName) != null }.getOrDefault(false)
        val usage = runCatching { conversationRepo.usageSummary(id) }.getOrNull()
        val promptFilter = ConversationTurnRepository.Filter(projectId = id, role = "user")
        val promptCount = runCatching { conversationRepo.count(promptFilter) }.getOrDefault(0L)
        val recentPrompts = runCatching {
            val off = (promptCount - 7).coerceAtLeast(0)
            conversationRepo.list(promptFilter, limit = 7, offset = off).asReversed().map { it.content }
        }.getOrDefault(emptyList())
        call.respondText(
            ProjectTabsTemplate.page(
                username = sess.username,
                project = p,
                allProjects = allProjects,
                projectStatuses = projectStatuses,
                keystoreReady = keystoreReady,
                tokensTotal = (usage?.let { it.inputTokens + it.outputTokens }) ?: 0L,
                cacheHitRate = usage?.cacheHitRate,
                promptCount = promptCount,
                recentPrompts = recentPrompts,
                flashErr = err, flashOk = ok,
                csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
        )
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
                artifactId = row.artifactId, errorMessage = row.errorMessage,
                gitBranch = row.gitBranch, gitSha = row.gitSha,
            )
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.projectDetailPage(
                sess.username, p, recent, flashErr = err, flashOk = ok, csrf = sess.csrf,
                lang = sess.language,
                // v1.71.0 — 폴더/패키지 변경은 대기중(turn·빌드 미진행)일 때만.
                structuralEnabled = !sessionManager.isBusy(id) && !isBuildRunning(buildRepo, id),
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
        if (sessionManager.isBusy(id) || isBuildRunning(buildRepo, id) || promptAutomationManager.isActive(id)) {
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
        // 코드/파일/디렉토리 구조 리네임은 콘솔 프롬프트로 Claude 에게 위임.
        val prompt = packageRenamePrompt(oldPkg, newPkg)
        runCatching { sessionManager.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "package-rename prompt send failed for $id" } }
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
        if (sessionManager.isBusy(id) || isBuildRunning(buildRepo, id) || promptAutomationManager.isActive(id)) {
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
        val alive = sessionManager.isAlive(id)
        val sid = sessionManager.currentSessionId(id)
        // v0.18.0 — 등록 직후 첫 console 진입이면 starter prompt 를 자동 입력 (소비).
        val starterPrompt = projects.consumeStarterPrompt(id)
        // Claude CLI 인증 상태 진단. CLI 자체와 자격증명 파일 둘 다 검사.
        val env = authDeps.envDiagnostics.run(sess.language)
        // v1.7.3 — DB conversation history (last 200 turn, ASC). sessionId 가 있을 때만
        // 해당 세션 turn 만 조회. 없으면 — 새 프로젝트 또는 last id 없는 케이스 — 빈 list.
        // v1.104.2 — 진짜 "마지막 200개" 로 회수. 이전엔 offset=0 ASC 라 turn 많은 세션
        // (tally-counter 1000+) 에서 *가장 오래된* 200개만 와, 최근 대화(방금 보낸 프롬프트
        // 포함)가 콘솔 재진입 시 통째로 누락됐다(상단 고정도 당연히 안 됨). offset=total-200.
        val history = if (sid != null) {
            runCatching {
                val f = ConversationTurnRepository.Filter(projectId = id, sessionId = sid)
                val off = (conversationRepo.count(f) - 200).coerceAtLeast(0)
                conversationRepo.list(f, limit = 200, offset = off)
            }.getOrDefault(emptyList())
        } else emptyList()
        call.respondText(
            WebProjectTemplates.consolePage(
                sess.username, p, sid, alive,
                claudeCli = env.claude,
                claudeAuth = env.claudeAuth,
                csrf = sess.csrf,
                starterPrompt = starterPrompt,
                initialHistory = history,
                model = sessionManager.effectiveModel(id),
                contextTokens = sessionManager.lastContextTokens(id),
                contextWarnTokens = sessionManager.contextWarnTokens(),
                mcpStrict = sessionManager.isMcpStrict(id),
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/console/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        runCatching { sessionManager.startNew(id) }
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

    // v1.106.0 — 프로젝트별 Claude 모델 설정(토큰 사용량 레버). 유휴면 즉시 재시작(세션 유지),
    // busy 면 다음 turn 부터 적용. 값: sonnet / opus / haiku / default / 전체 모델 ID.
    post("/projects/{id}/console/model") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val model = form["model"]?.trim().orEmpty()
        runCatching { sessionManager.setProjectModelAndRestart(id, model) }
            .onFailure { log.warn(it) { "set model failed for $id" } }
        log.info { "console model set: $id -> '${model.ifBlank { "default" }}' by ${sess.username}" }
        val target = when {
            id == ProjectService.SCRATCH_ID -> "/chat"
            ProjectService.isChatGhost(id) -> "/chat?c=${id.encodeUrl()}"
            else -> "/projects/$id/console"
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
                artifactId = row.artifactId, errorMessage = row.errorMessage,
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
            artifactId = row.artifactId, errorMessage = row.errorMessage,
            gitBranch = row.gitBranch, gitSha = row.gitSha,
        )
        val artifact = row.artifactId?.let { artifactRepo.get(id, it) }

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

    /**
     * v0.22.0 — Play Console (Internal/Alpha/Beta/Production) 업로드 트리거.
     *
     * 사전조건이 충족되지 않아도 (예: MCP 미설치) 그대로 prompt 전송 — Claude 가
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
        runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val ipaPath = form["ipaPath"]?.trim().orEmpty()
        val groups = form["distributionGroups"]?.trim()?.takeIf { it.isNotBlank() }
        val notes = form["releaseNotes"]?.trim()
        if (ipaPath.isBlank()) {
            call.respondRedirect("/projects/$id/builds/$buildId?tf_err=${Messages.t(sess.language, "flash.publish.ipaRequired").encodeUrl()}")
            return@post
        }
        runCatching {
            testFlightPublishService.trigger(
                projectId = id,
                ipaRelativePath = ipaPath,
                distributionGroups = groups,
                releaseNotes = notes,
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
                model = sessionManager.effectiveModel(p.id),
                contextTokens = sessionManager.lastContextTokens(p.id),
                contextWarnTokens = sessionManager.contextWarnTokens(),
                mcpStrict = sessionManager.isMcpStrict(p.id),
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

/**
 * 폼 flash 메시지를 query string 으로 옮길 때 쓰는 단순 URL encoder.
 * java.net.URLEncoder 가 form-encoding 이라 공백을 `+` 로 바꿔서 SSR에 보이기 어색.
 * 표준 percent-encoding 으로 통일한다.
 */
private fun String.encodeUrl(): String =
    java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

/**
 * v1.60.0 — 프로젝트 상태칩 3-state. 프로세스 생존이 아닌 **대화 이력 기반**이라
 * 서버 재시작 후에도 정확하다.
 *  - responding(응답중) : 현재 응답 stream 중(in-memory busy).
 *  - stopped(중지됨)    : 최신 user 프롬프트 이후 완료(assistant/usage)가 없음
 *                         (cancel / crash / 서버중단으로 끊김).
 *  - ready(대기중)      : 완료됐거나 대화가 없는(fresh) 상태 — 프롬프트 대기.
 */
private fun projectStatus(
    id: String,
    sessionManager: ClaudeSessionManager,
    conversationRepo: ConversationTurnRepository,
): String = when {
    sessionManager.isBusy(id) -> "responding"
    runCatching { conversationRepo.lastPromptInterrupted(id) }.getOrDefault(false) -> "stopped"
    else -> "ready"
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
