package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.files.ProjectFileBrowser
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.git.GitWriter
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import java.nio.file.Files
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
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
 * 프로젝트 / 콘솔 / 빌드 SSR 라우트.
 *
 * 인증 모델: AdminRoutes 와 동일하게 `vibe_session` 쿠키 기반 (requireSessionOrRedirect).
 * REST API (`/api/projects/...`) 는 그대로 두고 폼/페이지만 새로 만든다.
 */
fun Routing.webProjectRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    builds: BuildService,
    buildRepo: BuildRepository,
    artifactRepo: ArtifactRepository,
    sessionManager: ClaudeSessionManager,
    hub: LogHub,
    uploads: UploadService,
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
) {

    // ── 목록 + 등록 폼 ────────────────────────────────────────────────
    get("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val list = projects.listForUser(sess.userId, sess.isAdmin)
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]?.let { Messages.t(sess.language, "flash.project.created") }
        call.respondText(
            WebProjectTemplates.projectsPage(
                sess.username, list, flashErr = err, flashOk = ok, csrf = sess.csrf, lang = sess.language,
            ),
            ContentType.Text.Html,
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
        authDeps.audit.projectCreate(sess.userId, created.id, sourceType, call.request.local.remoteHost)
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
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@get
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            ProjectTabsTemplate.page(
                username = sess.username,
                project = p,
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
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val removed = projects.delete(id)
        log.info { "project delete: id=$id removed=$removed by ${sess.username}" }
        authDeps.audit.projectDelete(sess.userId, id, call.request.local.remoteHost, removed)
        val ok = if (removed) Messages.t(sess.language, "flash.project.deleted") else Messages.t(sess.language, "flash.project.notExist")
        call.respondRedirect("/projects?ok=${ok.encodeUrl()}")
    }

    // ── 콘솔 ──────────────────────────────────────────────────────────
    get("/projects/{id}/console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
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
        val history = if (sid != null) {
            runCatching {
                conversationRepo.list(
                    ConversationTurnRepository.Filter(projectId = id, sessionId = sid),
                    limit = 200,
                )
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
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/console/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        runCatching { sessionManager.startNew(id) }
            .onFailure { log.warn(it) { "console reset failed for $id" } }
        log.info { "console reset: $id by ${sess.username}" }
        authDeps.audit.consoleNew(sess.userId, id, call.request.local.remoteHost)
        // v0.13.0 — scratch 는 /chat 으로 redirect (전용 페이지 유지).
        val target = if (id == ProjectService.SCRATCH_ID) "/chat" else "/projects/$id/console"
        call.respondRedirect(target)
    }

    // ── 빌드 ──────────────────────────────────────────────────────────
    get("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
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
        call.respondText(
            WebProjectTemplates.buildsPage(
                sess.username, p, buildDtos, artifacts,
                stats = stats,
                flashErr = err, flashOk = ok, csrf = sess.csrf,
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val row = runCatching { builds.enqueueDebug(id, hub) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: Messages.t(sess.language, "flash.build.queueFailed")
            log.warn(e) { "build enqueue failed: $id" }
            call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "build enqueued: ${row.id} project=$id by ${sess.username}" }
        authDeps.audit.buildEnqueue(sess.userId, id, row.id, call.request.local.remoteHost)
        // 새 빌드는 곧바로 상세 페이지로 — 실시간 로그를 바로 본다.
        call.respondRedirect("/projects/$id/builds/${row.id}")
    }

    get("/projects/{id}/builds/{buildId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
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
        requireCsrf()
        val id = call.parameters["id"]!!
        val buildId = call.parameters["buildId"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val form = call.receiveParameters()
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
            authDeps.audit.playUploadFailed(sess.userId, id, buildId, call.request.local.remoteHost, e.message)
            call.respondRedirect("/projects/$id/builds/$buildId?play_err=${Messages.t(sess.language, "flash.publish.uploadFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        log.info { "play upload prompt sent: project=$id build=$buildId track=$track aab=$aabPath by ${sess.username}" }
        authDeps.audit.playUploadTriggered(sess.userId, id, buildId, call.request.local.remoteHost, track)
        // 사용자를 콘솔로 이동 — Claude 의 진행이 라이브로 보이는 곳.
        call.respondRedirect("/projects/$id/console")
    }

    /**
     * v0.23.0 — TestFlight 업로드 트리거. PlayPublish 와 동일 패턴.
     */
    post("/projects/{id}/builds/{buildId}/testflight-upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val buildId = call.parameters["buildId"]!!
        runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${Messages.t(sess.language, "flash.project.notFound", id).encodeUrl()}")
            return@post
        }
        val form = call.receiveParameters()
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
            authDeps.audit.testFlightUploadFailed(sess.userId, id, buildId, call.request.local.remoteHost, e.message)
            call.respondRedirect("/projects/$id/builds/$buildId?tf_err=${Messages.t(sess.language, "flash.publish.uploadFailed", e.message ?: "").encodeUrl()}")
            return@post
        }
        log.info { "testflight upload prompt sent: project=$id build=$buildId ipa=$ipaPath groups=$groups by ${sess.username}" }
        authDeps.audit.testFlightUploadTriggered(sess.userId, id, buildId, call.request.local.remoteHost, groups)
        call.respondRedirect("/projects/$id/console")
    }

    post("/projects/{id}/builds/{buildId}/cancel") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val buildId = call.parameters["buildId"]!!
        try {
            builds.cancel(buildId)
        } catch (e: Throwable) {
            log.warn(e) { "build cancel failed: $buildId" }
        }
        log.info { "build cancel: $buildId project=$id by ${sess.username}" }
        authDeps.audit.buildCancel(sess.userId, id, buildId, call.request.local.remoteHost)
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
    get("/projects/{id}/files/download") {
        requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val relPath = call.request.queryParameters["path"].orEmpty()
        val raw = runCatching { fileBrowser.resolveForRawRead(id, relPath) }.getOrElse { e ->
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
        requireSessionOrRedirect(authDeps) ?: return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
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
        val multipart = call.receiveMultipart()
        var parentRel = ""
        var fileName: String? = null
        var stream: java.io.InputStream? = null
        var failMsg: String? = null
        try {
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "parent") parentRel = part.value
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
                    ),
                    ContentType.Text.Html, HttpStatusCode.BadRequest,
                )
                return@get
            }
            call.respondText(
                WebProjectTemplates.fileViewPage(
                    sess.username, p, relPath, view = null,
                    imageSizeBytes = sizeOrNull, csrf = sess.csrf, lang = sess.language,
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
                ),
                ContentType.Text.Html, HttpStatusCode.BadRequest,
            )
            return@get
        }
        call.respondText(
            WebProjectTemplates.fileViewPage(
                sess.username, p, relPath, view, csrf = sess.csrf,
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    // v1.17.0 — 이미지 / binary 파일 raw stream. <img src="..."> 가 직접 fetch.
    get("/projects/{id}/raw") {
        requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val relPath = call.request.queryParameters["path"].orEmpty()
        val raw = runCatching { fileBrowser.resolveForRawRead(id, relPath) }.getOrElse { e ->
            val sc = (e as? ApiException)?.statusCode ?: 500
            call.respondText(e.message ?: "raw read failed", ContentType.Text.Plain, HttpStatusCode.fromValue(sc))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
        call.response.header(HttpHeaders.ContentType, raw.mime)
        call.respondFile(raw.absolutePath.toFile())
    }

    post("/projects/{id}/edit") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
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

    // ── /chat — General Chat (프로젝트 무관, ghost project) v0.13.0 ───
    get("/chat") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val p = projects.ensureScratchProject()
        val alive = sessionManager.isAlive(p.id)
        val sid = sessionManager.currentSessionId(p.id)
        val env = authDeps.envDiagnostics.run(sess.language)
        // v1.7.3 — General Chat 도 동일하게 history 영속 복원.
        val history = if (sid != null) {
            runCatching {
                conversationRepo.list(
                    ConversationTurnRepository.Filter(projectId = p.id, sessionId = sid),
                    limit = 200,
                )
            }.getOrDefault(emptyList())
        } else emptyList()
        call.respondText(
            WebProjectTemplates.consolePage(
                sess.username, p, sid, alive,
                claudeCli = env.claude,
                claudeAuth = env.claudeAuth,
                csrf = sess.csrf,
                isChat = true,
                initialHistory = history,
                lang = sess.language,
            ),
            ContentType.Text.Html,
        )
    }

    // ── v0.29.0 — 프로젝트 source zip 다운로드 ─────────────────────
    get("/projects/{id}/zip") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
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
        requireCsrf()  // CSRF 검증은 유지 — 외부 트리거 방지.
        log.info { "slash chip ignored (deprecated v0.75.0): project=$id by ${sess.username}" }
        val target = if (id == ProjectService.SCRATCH_ID) "/chat" else "/projects/$id/console"
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
