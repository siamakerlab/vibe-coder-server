package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.git.GitCloneService
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRow
import com.siamakerlab.vibecoder.server.repo.UploadedFileRepository
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class ProjectService(
    private val workspace: WorkspacePath,
    private val repo: ProjectRepository,
    private val buildRepo: BuildRepository,
    private val keystoreGen: KeystoreGenerator,
    /** v0.9.0 — null 이면 clone 기능 비활성 (테스트 등). 운영에선 항상 주입. */
    private val gitClone: GitCloneService? = null,
    /**
     * v0.12.4 — 프로젝트 삭제 시 자식 row cascade 정리용. SQLite 의 foreign_keys
     * 가 활성화되면서 명시적 정리가 없으면 FK violation 으로 삭제 자체가 실패한다.
     * 테스트 컨텍스트에서는 null 로 두고 cascade 없이 호출 (자식 row 없는 환경).
     */
    private val artifactRepo: ArtifactRepository? = null,
    private val uploadedFileRepo: UploadedFileRepository? = null,
    /** v0.16.0 — conversation_turns cascade. null 이면 history persistence 비활성. */
    private val conversationRepo: com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository? = null,
    /**
     * v0.49.0 — Project ACL filter. null 이면 ACL 비활성 (모든 사용자가 모든 프로젝트 보기 — legacy).
     * 운영 환경에선 항상 주입.
     */
    private val projectAclRepo: com.siamakerlab.vibecoder.server.repo.ProjectAclRepository? = null,
    /**
     * v1.43.0 — 프로젝트 삭제 시 FK cascade 정리. BuildSchedules/BuildWebhookSecrets 가
     * Projects.id 를 참조(onDelete CASCADE 미설정)하므로 명시적 정리 없으면 repo.delete 가
     * FK violation 으로 실패. 테스트에선 null 허용(자식 row 없는 환경).
     */
    private val buildScheduleRepo: com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository? = null,
    private val buildWebhookSecretRepo: com.siamakerlab.vibecoder.server.repo.BuildWebhookSecretRepository? = null,
    /** v1.59.0 — 프롬프트 자동화 실행 이력 (Projects.id FK). delete cascade 정리용. */
    private val promptAutomationRunRepo: com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRepository? = null,
    /**
     * v1.1.0 — `ClaudeSessionManager.isBusy(projectId)` 콜백. ProjectDto.busy 필드 채움.
     * Construction-order 문제 회피 위해 lambda. null 이면 항상 false (테스트 / dev 환경).
     * 운영에선 ServerMain 에서 sessionManager::isBusy 전달.
     */
    private val isBusyOf: ((String) -> Boolean)? = null,
) {

    /**
     * Project creation (v0.3):
     *   - Client supplies projectId / appName / packageName / optional keystore.
     *   - Server creates `<workspace>/<projectId>/` (empty is fine — Claude scaffolds later).
     *   - Server writes `CLAUDE.md` template inside the project folder.
     *   - If a keystore is requested, server generates it under
     *     `<workspace>/.vibecoder/keystores/<projectId>/` (OUTSIDE the project folder).
     *   - moduleName defaults to "app", debugTask to "assembleDebug".
     */
    /**
     * v0.18.0 — register 후 콘솔에 자동 입력될 starter prompt 가 있으면 반환.
     * 같은 turn 에서 사용자가 "엔터" 만 누르면 Claude 가 scaffolding 시작.
     * register 결과와 별개라 ProjectDto 자체엔 안 넣고 별도 API 로 노출 (Phase4).
     */
    private val starterPromptByProject = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun starterPromptFor(projectId: String): String? = starterPromptByProject[projectId]

    fun consumeStarterPrompt(projectId: String): String? = starterPromptByProject.remove(projectId)

    fun register(originalBody: RegisterProjectRequestDto): ProjectDto {
        // v1.7.0 — clone path 자동 채움. cloneUrl 만으로 등록 가능.
        // empty path (사용자 직접 입력 프로젝트) 는 기존처럼 모든 필드 필수.
        var body = if (originalBody.sourceType.equals("clone", ignoreCase = true)) {
            autoFillFromCloneUrl(originalBody)
        } else originalBody

        require(body.projectId.isNotBlank()) { "projectId required" }
        // v0.12.4 — 클라이언트 폼 regex 와 동일한 패턴을 서버에서도 강제.
        // 이전엔 path separator 만 차단해 공백/대문자/특수문자 입력이 통과돼서
        // 폴더 이름이 OS 마다 다르게 처리되거나 UI 표기와 불일치하는 문제가 있었다.
        require(PROJECT_ID_PATTERN.matches(body.projectId)) {
            "projectId must match $PROJECT_ID_PATTERN (소문자/숫자로 시작, [a-z0-9._-] 만 허용, 1~64자)"
        }
        require(body.appName.isNotBlank()) { "appName required" }
        require(body.packageName.isNotBlank()) { "packageName required" }

        if (repo.findById(body.projectId) != null) {
            throw ApiException.localized(409, "project_already_registered",
                messageKey = "api.project.alreadyRegistered", args = listOf(body.projectId))
        }

        // Keystore generation runs FIRST so a validation failure (weak password etc.)
        // doesn't leave behind an orphaned project folder on disk.
        val keystoreSummary = body.keystore?.let { ksReq ->
            val res = keystoreGen.generate(body.projectId, body.appName, ksReq)
            "alias=${res.alias} file=${res.keystoreFile.fileName}"
        }

        val srcRoot = workspace.projectRoot(body.projectId)

        // v0.9.0 — sourceType 분기. 'clone' 이면 git clone 먼저 실행 후 템플릿 보강.
        val isClone = body.sourceType.equals("clone", ignoreCase = true)
        if (isClone) {
            val url = body.cloneUrl?.trim().orEmpty()
            if (url.isEmpty()) {
                throw ApiException.localized(400, "missing_clone_url",
                    messageKey = "api.project.missingCloneUrl")
            }
            val svc = gitClone ?: throw ApiException.localized(500, "clone_unavailable",
                messageKey = "api.project.cloneUnavailable")
            // clone 은 빈 디렉토리에만 — 이미 존재하면 거부.
            // v1.7.18 — body.overwrite=true 면 기존 폴더 내용 모두 삭제 후 clone.
            // 사용자가 이전 clone 실패 후 orphan 폴더 정리 없이 재시도하는 흔한 케이스.
            if (srcRoot.exists() && Files.list(srcRoot).use { it.findFirst().isPresent }) {
                if (body.overwrite) {
                    log.info { "[clone:${body.projectId}] overwrite=true — wiping existing $srcRoot" }
                    runCatching {
                        Files.walk(srcRoot)
                            .sorted(Comparator.reverseOrder())
                            .forEach { Files.deleteIfExists(it) }
                        if (!srcRoot.exists()) Files.createDirectories(srcRoot)
                    }.onFailure { e ->
                        throw ApiException.localized(500, "overwrite_failed",
                            messageKey = "api.project.overwriteFailed", args = listOf(e.message ?: ""))
                    }
                } else {
                    throw ApiException.localized(409, "target_not_empty",
                        messageKey = "api.project.targetNotEmpty")
                }
            }
            log.info { "cloning $url → $srcRoot (branch=${body.cloneBranch ?: "(default)"})" }
            svc.clone(url, srcRoot, body.cloneBranch) { line ->
                log.debug { "[clone:${body.projectId}] $line" }
            }
            // v1.7.0 — clone 완료 후 build.gradle.kts / AndroidManifest.xml 에서
            // 진짜 packageName 추출 시도. 성공 시 placeholder (com.example.<projectId>)
            // 를 덮어씀. 실패 시 placeholder 그대로 (사용자가 후속 콘솔에서 수정 가능).
            if (body.packageName.startsWith("com.example.")) {
                detectPackageFromClonedRepo(srcRoot)?.let { detected ->
                    log.info { "[clone:${body.projectId}] auto-detected packageName: $detected" }
                    body = body.copy(packageName = detected)
                }
            }
        } else if (srcRoot.notExists()) {
            srcRoot.createDirectories()
            log.info { "created empty project folder $srcRoot" }
        }
        // v1.7.22 — moduleName auto-detect (clone / empty 모두). 기본값 "app" 가
        // multi-module 프로젝트에서 빌드 실패하던 회귀 해소. clone 한 경우 settings
        // 파싱 + com.android.application plugin 모듈 찾기. empty 신규 프로젝트는
        // 디폴트 "app" 그대로.
        val detectedModule = if (isClone) {
            runCatching { detectAppModuleFromClonedRepo(srcRoot) }.getOrNull()
        } else null
        val moduleNameFinal = detectedModule ?: DEFAULT_MODULE
        if (detectedModule != null && detectedModule != DEFAULT_MODULE) {
            log.info { "[clone:${body.projectId}] auto-detected app moduleName: $detectedModule" }
        }
        // CLAUDE.md / .claude/settings.json 은 clone 후에도 동일하게 보강 — 기존 파일 보존.
        // v0.99.0 — 프로젝트 입력 정보 (appName / packageName / projectId / moduleName /
        // debugTask / cloneUrl) 를 CLAUDE.md 최상단 ## Project Info 섹션에 자동 주입.
        // Claude 가 첫 turn 부터 정확한 applicationId / namespace 사용 → 수동 재지시 불요.
        val claudeMd = srcRoot.resolve("CLAUDE.md")
        if (claudeMd.notExists()) {
            val info = ClaudeMdTemplate.ProjectInfo(
                appName = body.appName,
                packageName = body.packageName,
                projectId = body.projectId,
                moduleName = moduleNameFinal,
                debugTask = DEFAULT_DEBUG_TASK,
                sourceType = body.sourceType,
                cloneUrl = body.cloneUrl,
                cloneBranch = body.cloneBranch,
            )
            Files.writeString(claudeMd, ClaudeMdTemplate.render(info))
        }

        // v0.7.0 — .claude/settings.json: vibe-coder 비인터랙티브 환경 권장 정책.
        // bypassPermissions + 인터랙티브 도구 deny + 비대화형 env 강제.
        val claudeDir = srcRoot.resolve(".claude")
        if (claudeDir.notExists()) claudeDir.createDirectories()
        val settingsJson = claudeDir.resolve("settings.json")
        if (settingsJson.notExists()) {
            Files.writeString(settingsJson, ClaudeSettingsTemplate.CONTENT)
        }

        val vibeDir = workspace.vibecoderDir(body.projectId)
        val projectYml = vibeDir.resolve("project.yml")
        if (projectYml.notExists()) {
            projectYml.writeText(buildProjectYml(body, srcRoot, keystoreSummary))
        }

        val row = repo.insert(
            id = body.projectId,
            name = body.appName,
            packageName = body.packageName,
            sourcePath = srcRoot.toString(),
            moduleName = moduleNameFinal,
            debugTask = DEFAULT_DEBUG_TASK,
        )

        // v0.18.0 — 템플릿 starter prompt 기록. 같은 turn 에서 사용자가 콘솔로 가면
        // 입력란이 자동 채워짐. 한 번 소비되면 제거 (consumeStarterPrompt).
        val tplId = body.templateId
        if (tplId != null && tplId != "empty") {
            val tpl = ProjectTemplates.byId(tplId)
            if (tpl != null && tpl.starterPrompt.isNotBlank()) {
                // 앱 이름 같은 메타데이터를 prompt 안에 삽입 (단순 placeholder 치환).
                val expanded = tpl.starterPrompt.replace("\${앱이름}", body.appName)
                starterPromptByProject[body.projectId] = expanded
                log.info { "starter prompt queued for ${body.projectId}: template=${tpl.id}" }
            }
        }

        return row.toDto(hasGitChanges = false, lastBuildStatus = null)
    }

    fun list(): List<ProjectDto> {
        val rows = repo.list()
        // v0.13.0 — scratch "ghost" project (/chat 페이지용) 는 일반 목록에서 숨김.
        // v1.54.0 — chat 세션 ghost(`__chat_*`)도 동일하게 제외.
        return rows
            .filter { !isGhost(it.id) }
            .map { row ->
                val last = buildRepo.lastForProject(row.id)
                // v1.1.0 — Claude busy 상태 주입 (manager 미주입 = false).
                row.toDto(false, last?.status?.name, isBusyOf?.invoke(row.id) ?: false)
            }
    }

    /**
     * v0.49.0 — ACL-aware listing. Admin sees everything (legacy behaviour). Non-admin users
     * see the full list **unless** they have one or more ACL rows, in which case the list is
     * narrowed to those projects (opt-in restriction).
     */
    fun listForUser(userId: String, isAdmin: Boolean): List<ProjectDto> {
        val all = list()
        if (isAdmin || projectAclRepo == null) return all
        if (!projectAclRepo.hasAnyRowFor(userId)) return all
        val allowed = projectAclRepo.listForUser(userId).toSet()
        return all.filter { it.id in allowed }
    }

    /**
     * v0.49.0 — Single-project access check. Admin always allowed; non-admin allowed if the
     * user has no ACL rows OR has an explicit grant for [projectId].
     */
    fun canUserAccess(userId: String, isAdmin: Boolean, projectId: String): Boolean {
        if (isAdmin || projectAclRepo == null) return true
        if (!projectAclRepo.hasAnyRowFor(userId)) return true
        return projectAclRepo.isGranted(projectId, userId)
    }

    /**
     * v0.13.0 — General Chat 용 ghost 프로젝트.
     *
     * `/chat` 진입 시 호출되어 워크스페이스 폴더 + CLAUDE.md + .claude/settings.json
     * + DB row 가 모두 존재함을 보장. 사용자가 직접 만들 수 없는 ID (`__scratch__` 는
     * PROJECT_ID_PATTERN 의 first-char `[a-z0-9]` 검증을 통과 못 함).
     *
     * 일반 list() / register() / delete() 에서는 차단/필터링.
     */
    fun ensureScratchProject(): ProjectDto {
        // v1.33.0 — scratch 를 워크스페이스 루트(`/workspace/__scratch__`)에서 서버 메타
        // sidecar(`/workspace/.vibecoder/__scratch__`)로 이동. 워크스페이스 프로젝트
        // 목록/파일 탐색에 ghost 프로젝트가 섞이지 않게. BackupService 의 excludedSegments
        // (`.vibecoder/__scratch__`)와도 정합. Claude 작업 접근성은 동일(같은 볼륨).
        // projectRoot(SCRATCH_ID) 가 v1.33.0 부터 .vibecoder/__scratch__ 를 반환 — 모든
        // 서비스(ClaudeSessionManager cwd 등)와 동일 경로로 일관.
        val srcRoot = workspace.projectRoot(SCRATCH_ID)
        val newPath = srcRoot.toString()
        val existing = repo.findById(SCRATCH_ID)
        if (existing != null) {
            // 옛 경로(`/workspace/__scratch__`)에서 자동 마이그레이션.
            if (existing.sourcePath != newPath) {
                val oldP = Path.of(existing.sourcePath)
                Files.createDirectories(srcRoot.parent)
                // v1.33.2 (17차 BUG-2) — move 성공(또는 옮길 디렉토리 없음) 시에만 DB 갱신.
                // 이전엔 move 실패해도 무조건 updateSourcePath 라, 실패 시 실제 데이터는
                // oldP 에 남고 DB 는 newPath(빈 디렉토리)를 가리켜 기존 scratch 대화가
                // 고아화됐음. 실패 시 DB 보류 → 다음 /chat 진입에서 재시도.
                val migrated = if (Files.exists(oldP) && srcRoot.notExists()) {
                    runCatching { Files.move(oldP, srcRoot); true }
                        .onFailure { log.warn(it) { "scratch dir move failed: $oldP → $srcRoot (DB 갱신 보류)" } }
                        .getOrDefault(false)
                } else true  // 옮길 옛 디렉토리 없음 / 새 경로 이미 존재 → 경로만 갱신.
                if (migrated) {
                    repo.updateSourcePath(SCRATCH_ID, newPath)
                    log.info { "scratch migrated: ${existing.sourcePath} → $newPath" }
                }
            }
            // migrated 실패 시 DB 는 아직 oldP — 그 경로의 dirs 보장(데이터 보존).
            val effectivePath = (repo.findById(SCRATCH_ID) ?: existing).sourcePath
            ensureScratchDirsExist(effectivePath)
            return (repo.findById(SCRATCH_ID) ?: existing).toDto(false, null)
        }
        Files.createDirectories(srcRoot)
        ProjectScaffolder.ensureClaudeFiles(srcRoot)
        val row = repo.insert(
            id = SCRATCH_ID,
            name = "General Chat",
            packageName = "scratch",
            sourcePath = newPath,
            moduleName = DEFAULT_MODULE,
            debugTask = DEFAULT_DEBUG_TASK,
        )
        log.info { "scratch project bootstrapped at $srcRoot" }
        return row.toDto(false, null)
    }

    private fun ensureScratchDirsExist(sourcePath: String) {
        val p = java.nio.file.Path.of(sourcePath)
        if (p.notExists()) Files.createDirectories(p)
        ProjectScaffolder.ensureClaudeFiles(p)
    }

    // ── v1.54.0 — ChatGPT 스타일 다중 채팅 (각 채팅 = `__chat_<id>__` ghost) ──────────

    /** Chat 목록 한 줄 요약 (사이드바용). */
    data class ChatSummary(
        val id: String,
        /** 표시 제목 — 사용자 지정명 또는 첫 프롬프트 자동 요약. */
        val title: String,
        /** 마지막 대화 시각 ISO (대화 없으면 생성 시각). 정렬/표시용. */
        val lastActivity: String,
        val busy: Boolean,
        val alive: Boolean,
    )

    /**
     * 새 채팅 ghost 프로젝트 생성. 워크스페이스 sidecar 폴더 + CLAUDE.md + settings.json
     * + DB row 를 모두 만든다. 반환은 새 ghost projectId.
     */
    fun createChat(): String {
        val id = CHAT_PREFIX + com.siamakerlab.vibecoder.server.core.Ids.taskId() + "_"
        val srcRoot = workspace.projectRoot(id)
        Files.createDirectories(srcRoot)
        ProjectScaffolder.ensureClaudeFiles(srcRoot)
        repo.insert(
            id = id,
            name = DEFAULT_CHAT_TITLE,
            packageName = "chat",
            sourcePath = srcRoot.toString(),
            moduleName = DEFAULT_MODULE,
            debugTask = DEFAULT_DEBUG_TASK,
        )
        log.info { "chat created: $id at $srcRoot" }
        return id
    }

    /** 단일 채팅 존재 보장 (디렉토리/scaffold 복구). 없는 id 면 404. */
    fun ensureChat(id: String): ProjectDto {
        require(isChatGhost(id)) { "not a chat id: $id" }
        val row = repo.findById(id)
            ?: throw ApiException.localized(404, "chat_not_found", messageKey = "api.project.notFound", args = listOf(id))
        ensureScratchDirsExist(row.sourcePath)
        return row.toDto(false, null)
    }

    /**
     * 전체 채팅 목록 — 최근 활동순(desc). 제목은 사용자 지정명 우선, "New chat" 이면
     * 첫 사용자 프롬프트를 잘라 자동 제목으로 표시.
     */
    fun listChats(): List<ChatSummary> {
        return repo.list()
            .filter { isChatGhost(it.id) }
            .map { row ->
                val lastTs = conversationRepo?.lastTs(row.id)
                val title = if (row.name == DEFAULT_CHAT_TITLE) {
                    conversationRepo?.firstUserContent(row.id)?.let { summarizeTitle(it) } ?: DEFAULT_CHAT_TITLE
                } else row.name
                ChatSummary(
                    id = row.id,
                    title = title,
                    lastActivity = lastTs ?: row.updatedAt,
                    busy = isBusyOf?.invoke(row.id) ?: false,
                    alive = false,
                )
            }
            .sortedByDescending { it.lastActivity }
    }

    /** 채팅 제목 변경 (사용자 지정). 빈 문자열이면 자동 제목으로 되돌림. */
    fun renameChat(id: String, title: String) {
        require(isChatGhost(id)) { "not a chat id: $id" }
        val clean = title.trim().take(120).ifBlank { DEFAULT_CHAT_TITLE }
        repo.updateName(id, clean)
    }

    /** 채팅 삭제 — conversation_turns 등 cascade 정리 후 DB row 제거 (디스크는 보존). */
    fun deleteChat(id: String): Boolean {
        require(isChatGhost(id)) { "not a chat id: $id" }
        // delete() 는 scratch 만 거부 — chat ghost 는 통과해 정상 cascade.
        return delete(id)
    }

    /** 첫 프롬프트 → 채팅 제목 자동 요약 (한 줄, 최대 40자). */
    private fun summarizeTitle(prompt: String): String {
        val firstLine = prompt.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.isEmpty()) return DEFAULT_CHAT_TITLE
        return if (firstLine.length <= 40) firstLine else firstLine.take(40).trimEnd() + "…"
    }

    fun get(id: String): ProjectDto {
        val row = repo.findById(id)
            ?: throw ApiException.localized(404, "project_not_found",
                messageKey = "api.project.notFound", args = listOf(id))
        val last = buildRepo.lastForProject(id)
        return row.toDto(false, last?.status?.name, isBusyOf?.invoke(id) ?: false)
    }

    fun sourcePathOrThrow(id: String): Path {
        val row = repo.findById(id) ?: throw ApiException.localized(404, "project_not_found", messageKey = "api.project.notFound", args = listOf(id))
        return Path.of(row.sourcePath)
    }

    fun rowOrThrow(id: String): ProjectRow =
        repo.findById(id) ?: throw ApiException.localized(404, "project_not_found", messageKey = "api.project.notFound", args = listOf(id))

    /**
     * 프로젝트 + 의존 row 모두 삭제. 디스크 파일은 보존 (UI 약속).
     *
     * v0.12.4 — PostgreSQL foreign_keys 가 cascade 정리 필수.
     * v0.16.0 — conversation_turns 도 같이 정리.
     * v1.43.0 — (1) BuildSchedules/BuildWebhookSecrets/ProjectAcls 정리 누락 회수
     *   (이전엔 해당 row 가 있으면 repo.delete 가 FK violation 으로 실패).
     *   (2) 전체를 단일 transaction 으로 묶어 원자성 확보 (부분 실패 시 데이터 유실 방지).
     *   Exposed 의 중첩 transaction 은 같은 스레드의 바깥 transaction 을 재사용한다.
     */
    fun delete(id: String): Boolean {
        // v0.13.0 — scratch ghost 프로젝트는 삭제 거부.
        if (id == SCRATCH_ID) {
            throw ApiException.localized(403, "scratch_protected", messageKey = "api.project.scratchProtected")
        }
        return transaction {
            // 자식(참조) 테이블을 부모(projects) 보다 먼저 정리.
            conversationRepo?.deleteForProject(id)
            uploadedFileRepo?.deleteForProject(id)
            artifactRepo?.deleteForProject(id)
            buildScheduleRepo?.deleteForProject(id)
            buildWebhookSecretRepo?.deleteForProject(id)
            promptAutomationRunRepo?.deleteForProject(id)
            projectAclRepo?.deleteAllForProject(id)
            buildRepo.deleteForProject(id)
            repo.delete(id) > 0
        }
    }

    private fun buildProjectYml(
        req: RegisterProjectRequestDto,
        absSource: Path,
        keystoreSummary: String?,
    ): String = """
        |# Vibe Coder project metadata
        |id: ${req.projectId}
        |appName: ${req.appName}
        |packageName: ${req.packageName}
        |sourcePath: $absSource
        |moduleName: $DEFAULT_MODULE
        |debugTask: $DEFAULT_DEBUG_TASK
        |keystore: ${keystoreSummary ?: "none"}
    """.trimMargin()

    private fun ProjectRow.toDto(
        hasGitChanges: Boolean,
        lastBuildStatus: String?,
        busy: Boolean = false,
    ): ProjectDto =
        ProjectDto(
            id = id, name = name, packageName = packageName,
            sourcePath = sourcePath, moduleName = moduleName, debugTask = debugTask,
            lastBuildStatus = lastBuildStatus, hasGitChanges = hasGitChanges,
            updatedAt = updatedAt,
            busy = busy,
        )

    /**
     * v1.7.0 — clone path 에서 cloneUrl 만 받은 경우 빈 필드 자동 채움.
     *  - projectId: cloneUrl 의 마지막 segment 의 `.git` strip + sanitize.
     *    예: `git@gitea.wody.kr:wody/vibe-coder-android.git` → `vibe-coder-android`
     *  - appName: projectId 첫 글자 대문자 (또는 그대로) — 사용자 후속 수정 가능.
     *  - packageName: clone 전엔 placeholder `com.example.<projectId>`. clone
     *    완료 후 build.gradle.kts / AndroidManifest.xml 에서 진짜 값 추출 시도 →
     *    성공 시 [register] 가 body.copy(packageName = ...) 로 덮어씀.
     */
    private fun autoFillFromCloneUrl(body: RegisterProjectRequestDto): RegisterProjectRequestDto {
        val url = body.cloneUrl?.trim().orEmpty()
        if (url.isEmpty()) return body
        val derivedId = projectIdFromCloneUrl(url)
        val pid = body.projectId.ifBlank { derivedId }
        val name = body.appName.ifBlank { derivedId }
        val pkg = body.packageName.ifBlank {
            // sanitize for package name segment — 알파벳/숫자만, 다른 문자 → 제거.
            val segment = derivedId.lowercase().replace(Regex("[^a-z0-9]"), "")
                .ifBlank { "app" }
            "com.example.$segment"
        }
        return body.copy(projectId = pid, appName = name, packageName = pkg)
    }

    /**
     * v1.7.0 — cloneUrl 의 마지막 segment 에서 projectId 도출.
     * HTTPS / SSH / git+ssh / gitea 등 다양한 형식 지원.
     */
    private fun projectIdFromCloneUrl(url: String): String {
        var s = url.trim().removeSuffix(".git").removeSuffix("/")
        val lastSlash = s.lastIndexOf('/')
        val lastColon = s.lastIndexOf(':')
        val cut = maxOf(lastSlash, lastColon)
        if (cut >= 0) s = s.substring(cut + 1)
        // PROJECT_ID_PATTERN 에 맞게 sanitize.
        var sanitized = s.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("^[._-]+"), "")
            .replace(Regex("[._-]+$"), "")
            .take(64)
        if (sanitized.isEmpty() || !sanitized[0].isLetterOrDigit()) {
            sanitized = "repo-${System.currentTimeMillis() % 100000}"
        }
        return sanitized
    }

    /**
     * v1.7.0 — clone 된 repo 에서 Android packageName 추출 시도.
     *
     * 검색 순서 (재귀 walk depth ≤ 5):
     *  1. build.gradle.kts 의 `applicationId = "..."` 또는 `namespace = "..."`
     *  2. build.gradle (groovy) 의 `applicationId "..."` 또는 `namespace "..."`
     *  3. AndroidManifest.xml 의 `package="..."` (legacy)
     *
     * 첫 매치 반환. 모두 실패 시 null.
     */
    /**
     * v1.7.22 — clone 후 settings.gradle.kts 의 `include(":...")` 항목 중
     * `com.android.application` plugin 이 적용된 모듈을 찾아 server 의
     * `moduleName` 으로 사용. 기본값 `"app"` 가 multi-module 프로젝트
     * (예: vibe-coder-android 의 `:android-app:app`) 에서 작동 안 하던
     * "project 'app' not found in root project" 빌드 실패 해소.
     *
     * 반환 형식 — Gradle path 의 leading `:` 제거. BuildService 가
     * `":$moduleName:$debugTask"` 로 조립하므로 nested 모듈은 콜론 그대로
     * (예: `"android-app:app"`).
     */
    private fun detectAppModuleFromClonedRepo(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        val includeRegex = Regex("""include\s*\(?\s*["':]([a-zA-Z0-9_.:\-]+)["']""")
        val appPluginRegex = Regex("""com\.android\.application""")
        // 1) settings.gradle(.kts) 파싱 → include 된 module path 모두 수집.
        val settingsCandidates = listOf("settings.gradle.kts", "settings.gradle")
        val includes = mutableListOf<String>()
        for (name in settingsCandidates) {
            val f = root.resolve(name)
            if (!f.toFile().isFile) continue
            val text = runCatching { Files.readString(f) }.getOrNull() ?: continue
            includeRegex.findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim().trimStart(':')
                if (raw.isNotEmpty()) includes.add(raw)
            }
            break
        }
        if (includes.isEmpty()) return null
        // 2) 각 module 의 build.gradle(.kts) 에서 com.android.application 적용 여부 확인.
        for (mod in includes) {
            val moduleDir = root.resolve(mod.replace(':', '/'))
            val candidates = listOf("build.gradle.kts", "build.gradle")
            for (g in candidates) {
                val gf = moduleDir.resolve(g)
                if (!gf.toFile().isFile) continue
                val text = runCatching { Files.readString(gf) }.getOrNull() ?: continue
                if (appPluginRegex.containsMatchIn(text)) return mod
            }
        }
        return null
    }

    private fun detectPackageFromClonedRepo(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        val gradleRegex = Regex("""(?:applicationId|namespace)\s*[=]?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")
        val manifestRegex = Regex("""package\s*=\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")
        return runCatching {
            Files.walk(root, 5).use { stream ->
                for (path in stream) {
                    if (Files.isDirectory(path)) continue
                    val name = path.fileName?.toString() ?: continue
                    val isGradle = name == "build.gradle.kts" || name == "build.gradle"
                    val isManifest = name == "AndroidManifest.xml"
                    if (!isGradle && !isManifest) continue
                    val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
                    val match = if (isGradle) {
                        gradleRegex.find(text)
                    } else {
                        manifestRegex.find(text)
                    }
                    if (match != null) {
                        val pkg = match.groupValues[1]
                        // Android applicationId 규칙: 최소 1개 `.` 필요.
                        if (pkg.contains('.')) return@use pkg
                    }
                }
                null
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_MODULE = "app"
        const val DEFAULT_DEBUG_TASK = "assembleDebug"

        /**
         * v0.12.4 — 폼/REST 모두에서 받는 projectId 의 유효 패턴.
         * 클라이언트 regex `[a-z0-9][a-z0-9._-]*{,63}` 와 서버 검증을 일원화.
         */
        val PROJECT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /** v0.13.0 — General Chat ghost 프로젝트 ID. 사용자 입력으로는 만들 수 없는 형태. */
        const val SCRATCH_ID = "__scratch__"

        /** v1.54.0 — ChatGPT 스타일 다중 채팅. 각 채팅 = `__chat_<id>__` ghost 프로젝트. */
        const val CHAT_PREFIX = "__chat_"

        /** v1.54.0 — 새 채팅 기본 제목. 이 값이면 첫 프롬프트로 자동 제목 대체. */
        const val DEFAULT_CHAT_TITLE = "New chat"

        /** v1.54.0 — chat 세션 ghost 프로젝트인지. */
        fun isChatGhost(id: String): Boolean = id.startsWith(CHAT_PREFIX)

        /**
         * v1.54.0 — 일반 워크스페이스 프로젝트가 아닌 ghost(scratch + chat). list() /
         * metrics / status 등에서 일괄 제외하기 위한 단일 판정. [WorkspacePath.isGhostId]
         * 와 같은 규칙 (모듈 경계 때문에 양쪽에 존재).
         */
        fun isGhost(id: String): Boolean = id == SCRATCH_ID || isChatGhost(id)
    }
}
