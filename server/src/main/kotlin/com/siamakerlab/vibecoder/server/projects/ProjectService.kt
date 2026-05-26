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

    fun register(body: RegisterProjectRequestDto): ProjectDto {
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
            if (srcRoot.exists() && Files.list(srcRoot).use { it.findFirst().isPresent }) {
                throw ApiException.localized(409, "target_not_empty",
                    messageKey = "api.project.targetNotEmpty")
            }
            log.info { "cloning $url → $srcRoot (branch=${body.cloneBranch ?: "(default)"})" }
            svc.clone(url, srcRoot, body.cloneBranch) { line ->
                log.debug { "[clone:${body.projectId}] $line" }
            }
        } else if (srcRoot.notExists()) {
            srcRoot.createDirectories()
            log.info { "created empty project folder $srcRoot" }
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
                moduleName = DEFAULT_MODULE,
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
            moduleName = DEFAULT_MODULE,
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
        return rows
            .filter { it.id != SCRATCH_ID }
            .map { row ->
                val last = buildRepo.lastForProject(row.id)
                row.toDto(false, last?.status?.name)
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
        val existing = repo.findById(SCRATCH_ID)
        if (existing != null) {
            ensureScratchDirsExist(existing.sourcePath)
            return existing.toDto(false, null)
        }
        val srcRoot = workspace.root.resolve(SCRATCH_ID)
        Files.createDirectories(srcRoot)
        ProjectScaffolder.ensureClaudeFiles(srcRoot)
        val row = repo.insert(
            id = SCRATCH_ID,
            name = "General Chat",
            packageName = "scratch",
            sourcePath = srcRoot.toString(),
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

    fun get(id: String): ProjectDto {
        val row = repo.findById(id)
            ?: throw ApiException.localized(404, "project_not_found",
                messageKey = "api.project.notFound", args = listOf(id))
        val last = buildRepo.lastForProject(id)
        return row.toDto(false, last?.status?.name)
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
     */
    fun delete(id: String): Boolean {
        // v0.13.0 — scratch ghost 프로젝트는 삭제 거부.
        if (id == SCRATCH_ID) {
            throw ApiException.localized(403, "scratch_protected", messageKey = "api.project.scratchProtected")
        }
        conversationRepo?.deleteForProject(id)
        uploadedFileRepo?.deleteForProject(id)
        artifactRepo?.deleteForProject(id)
        buildRepo.deleteForProject(id)
        return repo.delete(id) > 0
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

    private fun ProjectRow.toDto(hasGitChanges: Boolean, lastBuildStatus: String?): ProjectDto =
        ProjectDto(
            id = id, name = name, packageName = packageName,
            sourcePath = sourcePath, moduleName = moduleName, debugTask = debugTask,
            lastBuildStatus = lastBuildStatus, hasGitChanges = hasGitChanges,
            updatedAt = updatedAt,
        )

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
    }
}
