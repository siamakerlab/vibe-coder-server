package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.build.BuildService
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.files.UploadService
import com.siamakerlab.vibecoder.server.git.GitReader
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
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
    workspace: WorkspacePath,
) {

    // ── 목록 + 등록 폼 ────────────────────────────────────────────────
    get("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val list = projects.list()
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]?.let { "프로젝트가 생성되었습니다." }
        call.respondText(
            WebProjectTemplates.projectsPage(sess.username, list, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/projects") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val params = call.receiveParameters()
        val projectId = params["projectId"]?.trim().orEmpty()
        val appName = params["appName"]?.trim().orEmpty()
        val packageName = params["packageName"]?.trim().orEmpty()
        // v0.9.0 — sourceType ('empty' | 'clone') + optional cloneUrl/branch
        val sourceType = params["sourceType"]?.trim()?.ifBlank { null } ?: "empty"
        val cloneUrl = params["cloneUrl"]?.trim()?.ifBlank { null }
        val cloneBranch = params["cloneBranch"]?.trim()?.ifBlank { null }

        val basicErr = when {
            projectId.isBlank() -> "프로젝트 ID 를 입력하세요."
            appName.isBlank() -> "앱 이름을 입력하세요."
            packageName.isBlank() -> "패키지명을 입력하세요."
            sourceType == "clone" && cloneUrl.isNullOrBlank() ->
                "Clone URL 을 입력하세요 (https:// 또는 git@host:owner/repo)."
            else -> null
        }
        if (basicErr != null) {
            val list = projects.list()
            call.respondText(
                WebProjectTemplates.projectsPage(sess.username, list, flashErr = basicErr),
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
                )
            )
        }

        val created = result.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: "프로젝트 생성 실패"
            log.warn(e) { "project register failed: $projectId by ${sess.username}" }
            val list = projects.list()
            call.respondText(
                WebProjectTemplates.projectsPage(sess.username, list, flashErr = msg),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )
            return@post
        }

        log.info { "project registered: ${created.id} by ${sess.username}" }
        call.respondRedirect("/projects/${created.id}")
    }

    // ── 상세 ──────────────────────────────────────────────────────────
    get("/projects/{id}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val recent = buildRepo.listForProject(id, limit = 5).map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage,
            )
        }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.projectDetailPage(sess.username, p, recent, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val removed = projects.delete(id)
        log.info { "project delete: id=$id removed=$removed by ${sess.username}" }
        val ok = if (removed) "프로젝트가 삭제되었습니다." else "프로젝트가 존재하지 않습니다."
        call.respondRedirect("/projects?ok=${ok.encodeUrl()}")
    }

    // ── 콘솔 ──────────────────────────────────────────────────────────
    get("/projects/{id}/console") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val alive = sessionManager.isAlive(id)
        val sid = sessionManager.currentSessionId(id)
        // Claude CLI 인증 상태 진단. CLI 자체와 자격증명 파일 둘 다 검사.
        val env = authDeps.envDiagnostics.run()
        call.respondText(
            WebProjectTemplates.consolePage(
                sess.username, p, sid, alive,
                claudeCli = env.claude,
                claudeAuth = env.claudeAuth,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/console/new") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        runCatching { sessionManager.startNew(id) }
            .onFailure { log.warn(it) { "console reset failed for $id" } }
        log.info { "console reset: $id by ${sess.username}" }
        call.respondRedirect("/projects/$id/console")
    }

    // ── 빌드 ──────────────────────────────────────────────────────────
    get("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val rows = buildRepo.listForProject(id, limit = 100)
        val buildDtos = rows.map { row ->
            BuildDto(
                id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
                startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
                artifactId = row.artifactId, errorMessage = row.errorMessage,
            )
        }
        val artifacts = artifactRepo.listForProject(id).associateBy { it.buildId }
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.buildsPage(
                sess.username, p, buildDtos, artifacts,
                flashErr = err, flashOk = ok,
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val row = runCatching { builds.enqueueDebug(id, hub) }.getOrElse { e ->
            val msg = (e as? ApiException)?.message ?: e.message ?: "빌드 큐 등록 실패"
            log.warn(e) { "build enqueue failed: $id" }
            call.respondRedirect("/projects/$id/builds?err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "build enqueued: ${row.id} project=$id by ${sess.username}" }
        // 새 빌드는 곧바로 상세 페이지로 — 실시간 로그를 바로 본다.
        call.respondRedirect("/projects/$id/builds/${row.id}")
    }

    get("/projects/{id}/builds/{buildId}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val buildId = call.parameters["buildId"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val row = buildRepo.get(buildId)
        if (row == null || row.projectId != id) {
            call.respondRedirect("/projects/$id/builds?err=${"빌드 '$buildId' 를 찾을 수 없습니다.".encodeUrl()}")
            return@get
        }
        val dto = BuildDto(
            id = row.id, projectId = row.projectId, variant = row.variant, status = row.status,
            startedAt = row.startedAt ?: row.createdAt, finishedAt = row.finishedAt,
            artifactId = row.artifactId, errorMessage = row.errorMessage,
        )
        val artifact = row.artifactId?.let { artifactRepo.get(id, it) }

        // 종료된 빌드면 디스크 로그 파일을 읽어 prerender. (WS ring 은 evicted 일 수 있음)
        val isTerminal = row.status.name in setOf("SUCCESS", "FAILED", "CANCELED", "TIMEOUT")
        val replay = if (isTerminal) loadBuildLog(workspace, id, buildId, row.logPath) else null

        call.respondText(
            WebProjectTemplates.buildDetailPage(sess.username, p, dto, artifact, replay),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/builds/{buildId}/cancel") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val buildId = call.parameters["buildId"]!!
        runCatching { builds.cancel(buildId) }
            .onFailure { log.warn(it) { "build cancel failed: $buildId" } }
        log.info { "build cancel: $buildId project=$id by ${sess.username}" }
        call.respondRedirect("/projects/$id/builds/$buildId")
    }

    // ── 파일 ──────────────────────────────────────────────────────────
    get("/projects/{id}/files") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val files = uploads.list(id)
        val err = call.request.queryParameters["err"]
        val ok = call.request.queryParameters["ok"]
        call.respondText(
            WebProjectTemplates.filesPage(sess.username, p, files, flashErr = err, flashOk = ok),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/files/upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val multipart = call.receiveMultipart()
        var saved: String? = null
        var fail: String? = null

        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part is PartData.FileItem) {
                    val name = part.originalFileName ?: "upload"
                    val mime = part.contentType?.toString()
                    val row = runCatching {
                        part.provider().toInputStream().use { stream ->
                            uploads.upload(id, name, mime, stream, sizeHint = null)
                        }
                    }
                    if (row.isFailure) {
                        val e = row.exceptionOrNull()!!
                        fail = (e as? ApiException)?.message ?: e.message ?: "업로드 실패"
                        log.warn(e) { "upload failed: project=$id file=$name" }
                    } else {
                        saved = row.getOrNull()?.originalName
                    }
                }
            } finally {
                part.dispose()
            }
        }

        if (fail != null) {
            call.respondRedirect("/projects/$id/files?err=${fail.encodeUrl()}")
            return@post
        }
        if (saved == null) {
            call.respondRedirect("/projects/$id/files?err=${"파일이 선택되지 않았습니다.".encodeUrl()}")
            return@post
        }
        log.info { "upload ok: project=$id file=$saved by ${sess.username}" }
        call.respondRedirect("/projects/$id/files?ok=${"'${saved}' 업로드 완료.".encodeUrl()}")
    }

    get("/projects/{id}/files/{fileId}/download") {
        // 세션 확인은 하지만 redirect 가 아닌 401 로 응답해야 다운로드 도중 페이지 점프가 안 일어남.
        requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val fileId = call.parameters["fileId"]!!
        val row = runCatching { uploads.resolveForDownload(id, fileId) }.getOrElse {
            call.respondText("file not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(
                ContentDisposition.Parameters.FileName, row.originalName,
            ).toString(),
        )
        call.respondFile(Path.of(row.filePath).toFile())
    }

    post("/projects/{id}/files/{fileId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val fileId = call.parameters["fileId"]!!
        val result = runCatching { uploads.delete(id, fileId) }
        if (result.isFailure) {
            val e = result.exceptionOrNull()!!
            val msg = (e as? ApiException)?.message ?: e.message ?: "파일 삭제 실패"
            call.respondRedirect("/projects/$id/files?err=${msg.encodeUrl()}")
            return@post
        }
        log.info { "file deleted: $fileId project=$id by ${sess.username}" }
        call.respondRedirect("/projects/$id/files?ok=${"파일이 삭제되었습니다.".encodeUrl()}")
    }

    // ── Git ───────────────────────────────────────────────────────────
    get("/projects/{id}/git") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${("프로젝트 '$id' 를 찾을 수 없습니다.").encodeUrl()}")
            return@get
        }
        val source = Path.of(p.sourcePath)
        // git이 초기화 안 됐거나 외부 명령 자체가 실패해도 페이지는 살아야 한다.
        val status = runCatching { gitReader.status(source) }.getOrElse { null }
        val diff = runCatching { gitReader.diff(source) }.getOrElse { null }
        val gitLog = runCatching { gitReader.log(source, count = 10) }.getOrElse { null }
        val unavailable = status == null && diff == null && gitLog == null

        call.respondText(
            WebProjectTemplates.gitPage(
                sess.username, p,
                status = status, diff = diff, log = gitLog,
                unavailable = unavailable,
            ),
            ContentType.Text.Html,
        )
    }

    // ── 콘솔: 액션 chip 전송 (form action) ─────────────────────────────
    post("/projects/{id}/console/slash") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        val id = call.parameters["id"]!!
        val params = call.receiveParameters()
        val cmd = params["command"]?.trim().orEmpty()

        if (cmd.isBlank() || cmd !in CONSOLE_SLASH_WHITELIST) {
            call.respondRedirect("/projects/$id/console")
            return@post
        }
        runCatching { sessionManager.sendPrompt(id, "/$cmd") }
            .onFailure { log.warn(it) { "slash chip failed: $cmd project=$id" } }
        log.info { "slash chip: /$cmd project=$id by ${sess.username}" }
        call.respondRedirect("/projects/$id/console")
    }
}

/** 콘솔 액션 chip 으로 노출할 슬래시 커맨드 화이트리스트. ServerActionHandler 와 동일. */
private val CONSOLE_SLASH_WHITELIST: Set<String> =
    setOf("status", "cost", "model", "clear", "memory", "plan", "compact")

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
