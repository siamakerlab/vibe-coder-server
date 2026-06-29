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
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
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
    /** v1.130.0 — 프롬프트 예약(one-shot) (Projects.id FK). delete cascade 정리용. */
    private val scheduledPromptRepo: com.siamakerlab.vibecoder.server.repo.ScheduledPromptRepository? = null,
    /** v1.91.0 — 독립 메모 (Projects.id nullable FK). delete cascade 시 프로젝트 전용 메모 정리(전역 보존). */
    private val memoRepo: com.siamakerlab.vibecoder.server.repo.MemoRepository? = null,
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
            // v1.128.0 — 타입 mismatch 확인 후 ack 재제출이면 직전 clone 을 재사용(재clone skip).
            val reuseCloned = body.projectTypeAck && Files.isDirectory(srcRoot.resolve(".git"))
            if (srcRoot.exists() && Files.list(srcRoot).use { it.findFirst().isPresent }) {
                when {
                    reuseCloned ->
                        log.info { "[clone:${body.projectId}] reusing previously cloned repo (project-type ack)" }
                    body.overwrite -> {
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
                    }
                    else -> throw ApiException.localized(409, "target_not_empty",
                        messageKey = "api.project.targetNotEmpty")
                }
            }
            if (!reuseCloned) {
                log.info { "cloning $url → $srcRoot (branch=${body.cloneBranch ?: "(default)"})" }
                svc.clone(url, srcRoot, body.cloneBranch) { line ->
                    log.debug { "[clone:${body.projectId}] $line" }
                }
            }
            // v1.7.0 — clone 완료 후 build.gradle.kts / AndroidManifest.xml 에서
            // 진짜 packageName 추출 시도. 성공 시 placeholder (com.example.<projectId>)
            // 를 덮어씀. 실패 시 placeholder 그대로 (사용자가 후속 콘솔에서 수정 가능).
            if (body.packageName.startsWith("com.example.")) {
                PackageNameDetector.detectApplicationId(srcRoot)?.let { detected ->
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
            runCatching { PackageNameDetector.detectAppModule(srcRoot) }.getOrNull()
        } else null
        val moduleNameFinal = detectedModule ?: DEFAULT_MODULE
        if (detectedModule != null && detectedModule != DEFAULT_MODULE) {
            log.info { "[clone:${body.projectId}] auto-detected app moduleName: $detectedModule" }
        }
        // v1.128.0 — clone 한 repo 의 실제 타입(pubspec.yaml/gradle)과 사용자 선택이 불일치하면
        // 확인 요구(ack 전까지 중단). clone 폴더는 보존 — ack 재제출 시 위 reuseCloned 로 재사용.
        if (isClone && !body.projectTypeAck) {
            val detectedType = PackageNameDetector.detectProjectType(srcRoot)
            val selected = ProjectTypes.normalize(body.projectType)
            if (detectedType != null && detectedType != selected) {
                log.info { "[clone:${body.projectId}] project-type mismatch: selected=$selected detected=$detectedType" }
                throw ApiException.localized(409, "project_type_mismatch",
                    messageKey = "api.project.typeMismatch", args = listOf(selected, detectedType))
            }
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
                projectType = ProjectTypes.normalize(body.projectType),
            )
            Files.writeString(claudeMd, ClaudeMdTemplate.render(info))
        }
        ProjectScaffolder.ensureAgentsLink(srcRoot)

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
            // v1.125.0 — 사용자 선택 타입 영속(SSOT). 알 수 없는 값은 kotlin 으로 흡수.
            projectType = ProjectTypes.normalize(body.projectType),
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
     * v1.60.0 — 드래그 순서변경. [pageIds] 는 현재 페이지(글로벌 [offset] 부터)의 새
     * 순서. 전체 비-ghost 정렬 id 의 `[offset, offset+size)` slice 를 pageIds 로 교체한
     * 뒤 전 행 sort_order 를 0..N-1 로 정규화한다. slice 와 pageIds 가 **같은 집합이
     * 아니면 거부**(다른 페이지/위조 차단).
     */
    fun reorder(offset: Int, pageIds: List<String>) {
        if (pageIds.isEmpty()) return
        val all = repo.listIdsInOrder().filter { !isGhost(it) }
        val from = offset.coerceIn(0, all.size)
        val to = (offset + pageIds.size).coerceAtMost(all.size)
        val slice = all.subList(from, to)
        if (slice.size != pageIds.size || slice.toSet() != pageIds.toSet()) {
            throw ApiException.localized(400, "reorder_mismatch", messageKey = "api.project.reorderMismatch")
        }
        val newAll = ArrayList<String>(all.size)
        newAll.addAll(all.subList(0, from))
        newAll.addAll(pageIds)
        newAll.addAll(all.subList(to, all.size))
        repo.applyOrder(newAll)
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
            scheduledPromptRepo?.deleteForProject(id)
            // v1.91.0 — 프로젝트 전용 메모만 정리. 전역 메모(projectId NULL)는 보존.
            memoRepo?.deleteForProject(id)
            projectAclRepo?.deleteAllForProject(id)
            buildRepo.deleteForProject(id)
            // v1.86.5 (정밀점검) — NotificationEvents 정리. FK 가 없어(projectId nullable)
            // 미정리 시 FK violation 은 안 났지만 삭제된 프로젝트의 알림이 고아로 잔존해
            // 알림함에 깨진 deepLink 로 남고, 같은 projectId 재사용 시 옛 알림이 섞였다.
            // renameId 는 이미 NotificationEvents 를 repoint 하므로 delete↔renameId 대칭 복원.
            // projectId == id 인 행만 삭제 — projectId NULL(글로벌 알림)은 보존.
            com.siamakerlab.vibecoder.server.db.NotificationEvents.deleteWhere {
                com.siamakerlab.vibecoder.server.db.NotificationEvents.projectId eq id
            }
            repo.delete(id) > 0
        }
    }

    /** v1.71.0 — 표시명(name) 변경. 언제든 가능. */
    fun rename(id: String, newName: String): Boolean {
        val name = newName.trim()
        require(name.isNotBlank()) { "name required" }
        require(name.length <= 256) { "name too long (max 256)" }
        if (isGhost(id)) {
            throw ApiException.localized(403, "scratch_protected", messageKey = "api.project.scratchProtected")
        }
        return repo.updateName(id, name) > 0
    }

    /**
     * v1.71.0 — DB 패키지명(applicationId) 갱신. 실제 코드/파일/keystore 리네임은
     * 라우트가 콘솔 프롬프트로 Claude 에게 위임 + keystore 파일은 서버(KeystoreService)가 처리.
     */
    fun updatePackageName(id: String, newPackage: String): Boolean {
        val pkg = newPackage.trim()
        require(PACKAGE_NAME_PATTERN.matches(pkg)) {
            "invalid package name (expected like com.example.app): $pkg"
        }
        if (isGhost(id)) {
            throw ApiException.localized(403, "scratch_protected", messageKey = "api.project.scratchProtected")
        }
        return repo.updatePackageName(id, pkg) > 0
    }

    /**
     * v1.71.0 — 폴더명(=projectId=PK) 변경. 파일시스템 이동 + DB PK 마이그레이션.
     *
     * 호출 전 라우트가 idle 가드(세션 미진행/미실행 + 빌드 미진행)를 검사해야 한다.
     * 순서: FS 이동(루트 먼저) → DB 마이그레이션. DB 실패 시 FS 롤백 후 throw.
     */
    fun renameFolder(oldId: String, newId: String) {
        val target = newId.trim()
        if (isGhost(oldId)) {
            throw ApiException.localized(403, "scratch_protected", messageKey = "api.project.scratchProtected")
        }
        require(PROJECT_ID_PATTERN.matches(target)) {
            "projectId must match $PROJECT_ID_PATTERN (소문자/숫자로 시작, [a-z0-9._-] 만 허용, 1~64자)"
        }
        if (target == oldId) return
        repo.findById(oldId) ?: throw ApiException.localized(
            404, "project_not_found", messageKey = "api.project.notFound", args = listOf(oldId))
        if (repo.findById(target) != null) throw ApiException.localized(
            409, "already_exists", messageKey = "api.project.alreadyRegistered", args = listOf(target))

        val oldRoot = workspace.projectRoot(oldId)
        val newRoot = workspace.projectRoot(target)
        val oldVibe = workspace.vibecoderDir(oldId)
        val newVibe = workspace.vibecoderDir(target)
        val oldKs = runCatching { workspace.keystoresDir(oldId) }.getOrNull()
        val newKs = runCatching { workspace.keystoresDir(target) }.getOrNull()

        if (newRoot.exists()) throw ApiException.localized(
            409, "already_exists", messageKey = "api.project.alreadyRegistered", args = listOf(target))

        // 1) FS 루트 이동 (가장 중요 — 실패하면 아무것도 안 바뀐 상태로 중단).
        if (oldRoot.exists()) {
            runCatching { Files.move(oldRoot, newRoot) }.getOrElse {
                log.error(it) { "[rename] project root move failed $oldRoot → $newRoot" }
                throw ApiException.localized(500, "fs_move_failed",
                    messageKey = "api.project.renameFailed", args = listOf(oldId))
            }
        }
        // 2) 메타(.vibecoder/<id>) 디렉토리 이동 — **필수**(빌드/아티팩트/로그/업로드 보관).
        //    v1.71.0 (정밀점검 H2): best-effort 였으면 root 만 옮겨지고 DB commit 후 메타가
        //    옛 경로에 고아로 남았다. 실패 시 root 롤백 + 중단(DB 미변경).
        if (oldVibe.exists()) {
            runCatching { if (newVibe.notExists()) Files.move(oldVibe, newVibe) }.getOrElse {
                log.error(it) { "[rename] vibecoder dir move failed — rolling back root" }
                runCatching { if (newRoot.exists() && oldRoot.notExists()) Files.move(newRoot, oldRoot) }
                throw ApiException.localized(500, "fs_move_failed",
                    messageKey = "api.project.renameFailed", args = listOf(oldId))
            }
        }
        // keystore 디렉토리는 best-effort (없거나 운영자 외부 keystore 사용 케이스 허용).
        if (oldKs != null && newKs != null) runCatching {
            if (oldKs.exists() && newKs.notExists()) { newKs.parent?.createDirectories(); Files.move(oldKs, newKs) }
        }.onFailure { log.warn(it) { "[rename] keystores dir move failed" } }

        // 3) DB PK 마이그레이션. 실패 시 FS 롤백.
        val ok = runCatching { repo.renameId(oldId, target) }.getOrDefault(false)
        if (!ok) {
            log.error { "[rename] DB migration failed — rolling back FS $newRoot → $oldRoot" }
            runCatching { if (newRoot.exists() && oldRoot.notExists()) Files.move(newRoot, oldRoot) }
            runCatching { if (newVibe.exists() && oldVibe.notExists()) Files.move(newVibe, oldVibe) }
            if (oldKs != null && newKs != null) runCatching { if (newKs.exists() && oldKs.notExists()) Files.move(newKs, oldKs) }
            throw ApiException.localized(500, "db_migration_failed",
                messageKey = "api.project.renameFailed", args = listOf(oldId))
        }
        // 4) source_path 를 새 루트로 갱신.
        runCatching { repo.updateSourcePath(target, newRoot.toString()) }
        log.info { "project folder rename: $oldId → $target" }
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
            projectType = projectType,
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

    // v1.128.0 — clone 패키지명(applicationId) / app 모듈 감지는 [PackageNameDetector] 로 이관.
    // 멀티모듈에서 라이브러리 서브모듈 namespace 를 applicationId 로 오인하던 버그 회수
    // (siashell: com.siashell.app 인데 com.siashell.service 채택 / Calculator). 규칙·테스트는
    // PackageNameDetector.kt 참조.

    /**
     * v1.64.0 — 모듈 build.gradle(.kts) 의 versionName. 없으면 null (목록 행 "v1.2.3" 표시용).
     * 파일 IO 실패는 graceful null. "동적" — 매 호출 시 현재 gradle 을 읽음.
     */
    fun appVersionName(projectId: String, moduleName: String): String? {
        if (isGhost(projectId)) return null
        val projectRoot = runCatching { workspace.projectRoot(projectId) }.getOrNull() ?: return null
        // Kotlin: <module>/build.gradle(.kts). v1.128.7 — 리터럴뿐 아니라 변수 참조 / 문자열 보간 /
        // version.properties 참조까지 정적 해석([VersionNameResolver]).
        val moduleDir = projectRoot.resolve(moduleName.replace(':', '/'))
        val props = loadVersionProps(projectRoot)
        for (g in listOf("build.gradle.kts", "build.gradle")) {
            val gf = moduleDir.resolve(g)
            if (!Files.isRegularFile(gf)) continue
            val text = runCatching { Files.readString(gf) }.getOrNull() ?: continue
            VersionNameResolver.resolve(text, props)?.let { return it }
        }
        // v1.128.6 — Flutter: pubspec.yaml 의 `version: 1.2.3+4` → 1.2.3 (+ 뒤 build number 제외).
        // Flutter 의 android/app/build.gradle 은 versionName 이 변수(flutterVersionName)라 위
        // 정규식에 안 잡혀 자연히 여기로 폴백한다.
        val pubspec = projectRoot.resolve("pubspec.yaml")
        if (Files.isRegularFile(pubspec)) {
            runCatching { Files.readString(pubspec) }.getOrNull()?.let { text ->
                Regex("""(?m)^version:\s*([^\s#]+)""").find(text)?.let {
                    return it.groupValues[1].substringBefore('+').take(32)
                }
            }
        }
        return null
    }

    /** v1.128.7 — version.properties(rootProject) 의 KEY=VALUE 파싱. versionName 의
     *  `versionProps["KEY"]` 보간 해석에 사용. 파일 없거나 실패 시 빈 맵. */
    private fun loadVersionProps(root: java.nio.file.Path): Map<String, String> {
        val f = root.resolve("version.properties")
        if (!Files.isRegularFile(f)) return emptyMap()
        return runCatching {
            Files.readAllLines(f).mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
                val i = t.indexOf('=')
                if (i < 0) null else t.substring(0, i).trim() to t.substring(i + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * v1.64.0 — 런처 아이콘 raster(png/webp) 파일. adaptive-icon(xml)만 있으면 null →
     * 목록은 placeholder(vibe-coder 아이콘) 사용. 큰 density 우선. 반환 경로는
     * workspace 내부 보장(symlink escape 방어).
     */
    fun resolveAppIcon(projectId: String, moduleName: String): Path? {
        if (isGhost(projectId)) return null
        val projectRoot = runCatching { workspace.projectRoot(projectId) }.getOrNull() ?: return null
        // v1.128.5 — Kotlin(app/src/main/res) + Flutter(android/app/src/main/res) 양쪽 탐색.
        // Flutter 는 ic_launcher 가 android/app 하위에 있어 기존 moduleName 경로로는 미인식이었다.
        val resCandidates = listOf(
            moduleName.replace(':', '/'),               // Kotlin: app (또는 nested gradle 모듈)
            "android/app",                              // Flutter 표준
            "android/" + moduleName.replace(':', '/'),  // Flutter nested 모듈 대비
        ).distinct()
        val densities = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi", "")
        val bases = listOf("mipmap", "drawable")
        val names = listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground")
        for (modPath in resCandidates) {
            val resRoot = projectRoot.resolve(modPath).resolve("src/main/res")
            if (!Files.isDirectory(resRoot)) continue
            for (d in densities) for (b in bases) {
                val dir = resRoot.resolve(if (d.isEmpty()) b else "$b-$d")
                if (!Files.isDirectory(dir)) continue
                for (n in names) for (ext in listOf("png", "webp")) {
                    val f = dir.resolve("$n.$ext")
                    if (Files.isRegularFile(f)) {
                        return runCatching { workspace.ensureUnderWorkspace(f.toRealPath()) }.getOrNull()
                    }
                }
            }
        }
        // v1.65.0 — res 에 raster 가 없으면 업로드된 프로젝트 루트 icon.png fallback
        // (App Icon 탭 업로드 직후, Claude 가 res 에 적용하기 전에도 미리보기/목록 반영).
        val rootIcon = projectRoot.resolve("icon.png")
        if (Files.isRegularFile(rootIcon)) {
            return runCatching { workspace.ensureUnderWorkspace(rootIcon.toRealPath()) }.getOrNull()
        }
        return null
    }

    companion object {
        const val DEFAULT_MODULE = "app"
        const val DEFAULT_DEBUG_TASK = "assembleDebug"

        /**
         * v0.12.4 — 폼/REST 모두에서 받는 projectId 의 유효 패턴.
         * 클라이언트 regex `[a-z0-9][a-z0-9._-]*{,63}` 와 서버 검증을 일원화.
         */
        val PROJECT_ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /** v1.71.0 — Android applicationId(패키지명) 유효 패턴. 각 세그먼트는 소문자로 시작. */
        val PACKAGE_NAME_PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

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
