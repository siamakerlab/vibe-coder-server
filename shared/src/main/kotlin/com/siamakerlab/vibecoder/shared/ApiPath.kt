package com.siamakerlab.vibecoder.shared

/**
 * Single source of truth for REST + WebSocket paths.
 * Both server routes and Android repository reference these constants
 * so a path change recompiles both sides.
 */
object ApiPath {

    // Auth — 신규 통합 인증 (v0.4.0+)
    const val AUTH_LOGIN = "/api/auth/login"
    const val AUTH_SETUP = "/api/auth/setup"
    const val AUTH_SETUP_STATUS = "/api/auth/setup/status"
    const val AUTH_PASSWORD = "/api/auth/password"

    // Auth — 레거시 페어링 (v0.4.0에서 deprecated, v0.5.0에서 제거 예정)
    const val AUTH_PAIR = "/api/auth/pair"
    const val AUTH_ME = "/api/auth/me"
    const val AUTH_LOGOUT = "/api/auth/logout"

    // Public health probe (no auth — Docker HEALTHCHECK / 모니터링용)
    const val HEALTH = "/health"

    // Server
    const val SERVER_STATUS = "/api/server/status"
    const val SERVER_ENVIRONMENT = "/api/server/environment"
    const val SERVER_ENVIRONMENT_CHECK = "/api/server/environment/check"
    const val SERVER_SETTINGS = "/api/server/settings"
    const val SERVER_SETTINGS_BASIC = "/api/server/settings/basic"

    // Projects
    const val PROJECTS = "/api/projects"
    const val PROJECTS_REGISTER = "/api/projects/register"
    fun project(id: String) = "/api/projects/$id"

    // Claude console (persistent session)
    fun claudeConsolePrompt(projectId: String) =
        "/api/projects/$projectId/claude/console/prompt"
    fun claudeConsoleNew(projectId: String) =
        "/api/projects/$projectId/claude/console/new"
    /** v0.13.0 — 현재 진행 중인 Claude turn 강제 중단 (process SIGTERM + 즉시 respawn). */
    fun claudeConsoleCancel(projectId: String) =
        "/api/projects/$projectId/claude/console/cancel"
    fun claudeStatus(projectId: String) =
        "/api/projects/$projectId/claude/status"

    // Project actions (chip system)
    fun projectActions(projectId: String) = "/api/projects/$projectId/actions"
    fun projectActionsInvoke(projectId: String) =
        "/api/projects/$projectId/actions/invoke"

    // Builds
    fun buildDebug(projectId: String) = "/api/projects/$projectId/build/debug"
    fun builds(projectId: String) = "/api/projects/$projectId/builds"
    fun build(projectId: String, buildId: String) =
        "/api/projects/$projectId/builds/$buildId"
    fun buildCancel(projectId: String, buildId: String) =
        "/api/projects/$projectId/builds/$buildId/cancel"

    // Artifacts
    fun artifacts(projectId: String) = "/api/projects/$projectId/artifacts"
    fun artifact(projectId: String, artifactId: String) =
        "/api/projects/$projectId/artifacts/$artifactId"
    fun artifactDownload(projectId: String, artifactId: String) =
        "/api/projects/$projectId/artifacts/$artifactId/download"

    // Git
    fun gitStatus(projectId: String) = "/api/projects/$projectId/git/status"
    fun gitDiff(projectId: String) = "/api/projects/$projectId/git/diff"
    fun gitLog(projectId: String) = "/api/projects/$projectId/git/log"
    /** v0.18.0 — write: commit + optional push. */
    fun gitCommit(projectId: String) = "/api/projects/$projectId/git/commit"

    // Files
    fun filesUpload(projectId: String) = "/api/projects/$projectId/files/upload"
    fun files(projectId: String) = "/api/projects/$projectId/files"
    fun fileDownload(projectId: String, fileId: String) =
        "/api/projects/$projectId/files/$fileId/download"
    fun file(projectId: String, fileId: String) =
        "/api/projects/$projectId/files/$fileId"

    // WebSocket
    fun wsBuildLogs(projectId: String, buildId: String) =
        "/ws/projects/$projectId/builds/$buildId/logs"
    fun wsConsoleLogs(projectId: String) =
        "/ws/projects/$projectId/console/logs"

    // v0.10.0 — Env setup (빌드환경 컴포넌트 진단/설치)
    const val ENV_SETUP_COMPONENTS = "/api/env-setup/components"
    const val ENV_SETUP_INSTALL_ALL = "/api/env-setup/install-all"
    fun envSetupInstall(componentId: String) = "/api/env-setup/$componentId/install"
    fun wsEnvSetupLogs(taskId: String) = "/ws/env-setup/$taskId/logs"

    // v0.10.0 — Claude 자격증명 관리 (옵션 B, C)
    const val CLAUDE_AUTH_UPLOAD = "/api/env-setup/claude-auth/upload"
    const val CLAUDE_AUTH_API_KEY = "/api/env-setup/claude-auth/api-key"
    const val CLAUDE_AUTH_API_KEY_DELETE = "/api/env-setup/claude-auth/api-key/delete"

    // v0.10.0 — Claude 반자동 웹 OAuth (옵션 A)
    const val CLAUDE_LOGIN_START = "/api/env-setup/claude-login/start"
    const val CLAUDE_LOGIN_SUBMIT = "/api/env-setup/claude-login/submit"
    const val CLAUDE_LOGIN_STATUS = "/api/env-setup/claude-login/status"
    const val CLAUDE_LOGIN_CANCEL = "/api/env-setup/claude-login/cancel"

    // v0.10.0 — MCP 카탈로그
    const val MCP_CATALOG = "/api/env-setup/mcp"
    const val MCP_INSTALL = "/api/env-setup/mcp/install"
    const val MCP_UNREGISTER = "/api/env-setup/mcp/unregister"

    // v0.11.0 — MCP secret 파일 업로드 (Play Service Account JSON, App Store .p8 등)
    fun mcpUploadFile(mcpId: String, fieldKey: String) =
        "/api/env-setup/mcp/$mcpId/file/$fieldKey"

    // v0.10.0 — Git 통합 (PAT + SSH 키)
    const val GIT_INTEGRATIONS = "/api/settings/git-integrations"
    const val GIT_INTEGRATIONS_DELETE = "/api/settings/git-integrations/delete"
    const val GIT_INTEGRATIONS_SSH_KEYGEN = "/api/settings/git-integrations/ssh-keygen"

    // v0.20.0 — Prompt template library (Android client / 외부 CRUD 클라이언트용).
    // 서버는 v0.13.0 부터 노출했지만 wire 모듈엔 v0.20.0 에 정식 등록.
    const val PROMPT_TEMPLATES = "/api/prompt-templates"

    // ─────────────────────────────────────────────────────────────────────
    // v0.64.0 — Phase 43. v0.16~v0.63 의 단독 등록 endpoint 들을 SSOT 로 회수.
    //
    // 정책 (CLAUDE.md §8.A): 신규 endpoint 는 반드시 ApiPath 에 먼저 등록되고,
    // 라우터가 그 상수를 참조해야 한다 (hardcoded path 금지).
    //
    // **사용 구분**:
    //   - 정적 path (`HISTORY_SEARCH_JSON` 등 `const val`) — 라우터 등록 + client
    //     호출 모두 그대로 사용.
    //   - 동적 path (`projectHistory(projectId)` 등 `fun`) — **client 호출 전용**.
    //     pathSeg 가 `{}` 까지 URL encode 하므로 Ktor 라우터 path template
    //     (`{name}` placeholder) 에 직접 못 들어감. 라우터에서는 같은 모양의
    //     hardcoded path template 을 쓰되, 클라이언트 호출은 이 함수로 통일.
    //     SSOT 효과: path 형식/placeholder 이름이 한 곳에 명세됨 + grep 매칭 가능.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * v0.16+ — 프로젝트별 Claude 대화 turn 히스토리 (JSON variant, v0.64.0 신규).
     * 기존 SSR `/projects/{id}/history` (HTML) 는 그대로 유지.
     * Query: `limit` (default 100, max 500), `sessionId`, `before` (cursor),
     *        `agent` (HistoryAgentFilter — main/all/@name), `starred` (boolean).
     * 응답: HistoryPageDto.
     */
    fun projectHistory(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/history"

    /**
     * v0.16+ — General Chat (scratch project) 의 동일 모양 history (JSON variant, v0.64.0 신규).
     * 같은 query params + 같은 응답 schema.
     */
    const val CHAT_HISTORY = "/api/chat/history"

    /**
     * v0.13+ — General Chat 의 synthetic project id. 콘솔 WS / status / prompt
     * endpoint 는 일반 projectId 처럼 받지만, conversation history 는 [CHAT_HISTORY] 사용.
     */
    const val SCRATCH_PROJECT_ID = "__scratch__"

    /**
     * v0.29+ — 프로젝트 소스 zip 스트림 다운로드 (SSR variant — cookie 세션 인증).
     * `.git`, `build`, `.gradle`, `node_modules` 제외.
     * **주의: `/api/` prefix 없음** (SSR 라우터와 공유).
     * Content-Disposition: attachment, filename=`<projectId>-source-<yyyyMMdd-HHmm>.zip`.
     *
     * **v0.65.0+ Android / Bearer 토큰 client 는 [projectZipJson] 사용 권장.**
     * 본 path 는 Bearer 토큰을 받지 않아 cookie 가 없는 외부 client 는 redirect.
     */
    fun projectZip(projectId: String) =
        "/projects/${pathSeg(projectId)}/zip"

    /**
     * v0.65.0 신규 — 프로젝트 소스 zip JSON variant (Bearer 토큰 인증, Project ACL 검증).
     * 응답 본문은 [projectZip] 과 동일 (application/zip + Content-Disposition).
     * SSR `/projects/{id}/zip` 은 그대로 유지.
     */
    fun projectZipJson(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/zip"

    // ─────────────────────────────────────────────────────────────────────
    // v0.66.0 — Phase 45. Android client 가 호출하지 않던 기능들 JSON API 회수.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * v0.66.0 신규 — 신규 프로젝트 starter 템플릿 카탈로그.
     * 응답: ProjectTemplatesResponseDto. 정적 데이터라 캐싱 안전.
     */
    const val PROJECT_TEMPLATES = "/api/project-templates"

    /**
     * v0.66.0 신규 — sub-agent 세션 강제 새 시작 (main console 의 [claudeConsoleNew] 와 대칭).
     * 기존 SSR `/projects/{id}/agents/{agent}/new` (redirect 응답) 는 그대로 유지.
     * 응답: AgentPromptAcceptedDto.
     */
    fun agentConsoleNew(projectId: String, agent: String) =
        "/api/projects/${pathSeg(projectId)}/agents/${pathSeg(agent)}/console/new"

    // ─────────────────────────────────────────────────────────────────────
    // v0.67.0 — Phase 46. Group B: admin / 운영 JSON API.
    // 기존 SSR (cookie 세션) 만 있던 admin 기능들을 Bearer JSON 으로도 노출.
    // 모두 admin role 권한 (`requireApiAdmin`) — viewer/member 거부.
    // ─────────────────────────────────────────────────────────────────────

    // B1. Multi-user
    const val USERS = "/api/users"
    fun userRole(userId: String) = "/api/users/${pathSeg(userId)}/role"
    fun user(userId: String) = "/api/users/${pathSeg(userId)}"

    // B2. Build automation (project-scoped)
    fun automationSchedules(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/automation/schedules"
    fun automationSchedule(projectId: String, scheduleId: String) =
        "/api/projects/${pathSeg(projectId)}/automation/schedules/${pathSeg(scheduleId)}"
    fun automationScheduleToggle(projectId: String, scheduleId: String) =
        "/api/projects/${pathSeg(projectId)}/automation/schedules/${pathSeg(scheduleId)}/toggle"

    // B3. Backup
    const val BACKUP_LIST = "/api/backup"
    const val BACKUP_DOWNLOAD = "/api/backup/download"
    const val BACKUP_RUN_NOW = "/api/backup/run-now"
    fun backupAutoFile(fileName: String) = "/api/backup/auto/${pathSeg(fileName)}"

    // B4. Audit log
    const val AUDIT_LIST = "/api/audit"

    // B5. Admin info (read-only + 일부 mutation)
    const val LOG_SEARCH = "/api/logs"
    const val CODE_SEARCH = "/api/code-search"
    fun projectDeps(projectId: String) = "/api/projects/${pathSeg(projectId)}/deps"
    fun projectStats(projectId: String) = "/api/projects/${pathSeg(projectId)}/stats"
    fun projectEnvFiles(projectId: String) = "/api/projects/${pathSeg(projectId)}/env-files"
    fun projectWrapper(projectId: String) = "/api/projects/${pathSeg(projectId)}/wrapper"

    // ─────────────────────────────────────────────────────────────────────
    // v0.68.0 — Phase 47. Polling-based notification system (Group C — FCM 대체).
    // Android WorkManager 가 15분 periodic 호출, system notification 으로 표시.
    // 모든 인증 사용자 (admin/member/viewer) 노출 — viewer 도 정보용으로 받음.
    // ─────────────────────────────────────────────────────────────────────
    const val NOTIFICATIONS = "/api/notifications"
    const val NOTIFICATIONS_ACK = "/api/notifications/ack"
    /** v0.68.0 — Optional FCM token 등록 (Firebase 설정 시만 의미). */
    const val FCM_TOKEN_REGISTER = "/api/notifications/fcm-token"

    /**
     * v0.31+ — Claude 입력 자동완성.
     * Query: `prefix`, `limit`. 응답: PromptSuggestionsResponseDto.
     */
    fun promptSuggestions(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/prompt-suggestions"

    /**
     * v0.31+ — 프로젝트 history JSON export (SSR-shared, requires session cookie).
     * **주의: `/api/` prefix 없음.** v0.64.0 의 JSON variant 는 [projectHistoryExportJson].
     */
    fun projectHistoryExport(projectId: String) =
        "/projects/${pathSeg(projectId)}/history/export"

    /**
     * v0.31+ — 프로젝트 history multipart import (SSR-shared, redirect 응답).
     * Query: `dryRun=true|false`. **주의: `/api/` prefix 없음.**
     * v0.64.0 의 JSON variant (응답 JSON) 는 [projectHistoryImportJson].
     */
    fun projectHistoryImport(projectId: String) =
        "/projects/${pathSeg(projectId)}/history/import"

    /**
     * v0.30+ — cross-project conversation 검색 (SSR HTML).
     * Bearer 토큰 클라이언트는 [HISTORY_SEARCH_JSON] 을 사용할 것.
     */
    const val HISTORY_SEARCH = "/history"

    /**
     * v0.36+ — 설치된 custom agent 카탈로그. 응답: AgentsCatalogResponseDto.
     */
    const val AGENTS_CATALOG = "/api/agents"

    /**
     * v0.44+ — 프로젝트 단위로 현재 활성화된 sub-agent 이름 목록.
     * 응답: ActiveAgentsResponseDto.
     */
    fun agentsActive(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/agents/active"

    /**
     * v0.44+ — 특정 sub-agent 에게 user prompt 전달.
     * 응답: AgentPromptAcceptedDto. WebSocket [wsAgentConsoleLogs] 가 출력 스트리밍.
     */
    fun agentConsolePrompt(projectId: String, agent: String) =
        "/api/projects/${pathSeg(projectId)}/agents/${pathSeg(agent)}/console/prompt"

    /** v0.44+ — sub-agent turn 강제 중단 (SIGTERM). */
    fun agentConsoleCancel(projectId: String, agent: String) =
        "/api/projects/${pathSeg(projectId)}/agents/${pathSeg(agent)}/console/cancel"

    /**
     * v0.54+ — Kotlin/Java symbol definition lookup.
     * Query: `name` (required, regex `[A-Za-z_][A-Za-z0-9_]{0,79}`).
     * 응답: SymbolsResponseDto.
     */
    fun projectSymbols(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/symbols"

    /**
     * v0.44+ — sub-agent console log WebSocket.
     * Read-only — UserPrompt/ActionInvoke 는 서버가 drain & ignore.
     * 프롬프트 전달은 [agentConsolePrompt] REST 로.
     */
    fun wsAgentConsoleLogs(projectId: String, agent: String) =
        "/ws/projects/${pathSeg(projectId)}/agents/${pathSeg(agent)}/console/logs"

    /**
     * v0.61+ — turn 메모 set/unset (v0.64.0 부터 Bearer 토큰 인증 호환).
     * Body: HistoryMemoUpdateRequestDto (`{"memo": "..." | null}`).
     * Cookie 세션 호출 시 `?_csrf=<token>` 필수, Bearer 토큰 호출 시 CSRF skip.
     * 응답: HistoryMutationAckDto.
     */
    fun projectHistoryMemo(projectId: String, turnId: String) =
        "/api/projects/${pathSeg(projectId)}/history/${pathSeg(turnId)}/memo"

    /**
     * v0.61+ — turn ★ 토글 (v0.64.0 부터 Bearer 토큰 인증 호환).
     * Query: `?starred=true|false`. Body 없음.
     * Cookie 세션 호출 시 `?_csrf=<token>` 필수, Bearer 토큰 호출 시 CSRF skip.
     * 응답: HistoryMutationAckDto.
     */
    fun projectHistoryStar(projectId: String, turnId: String) =
        "/api/projects/${pathSeg(projectId)}/history/${pathSeg(turnId)}/star"

    /**
     * v0.64.0 신규 — cross-project conversation 검색 (JSON variant).
     * Query: `q` (required), `role?`. 응답: HistorySearchResponseDto.
     * 기존 SSR `/history` ([HISTORY_SEARCH]) 는 그대로 유지.
     */
    const val HISTORY_SEARCH_JSON = "/api/history/search"

    /**
     * v0.64.0 신규 — Anthropic 토큰/캐시 집계 (JSON variant).
     * 응답: UsageSummaryDto.
     * 기존 SSR `/usage` 는 그대로 유지.
     */
    const val USAGE_JSON = "/api/usage"

    /**
     * v0.64.0 신규 — 프로젝트 history JSON export (Bearer 토큰 인증, JSON 응답).
     * 기존 SSR `/projects/{id}/history/export` ([projectHistoryExport]) 는 그대로 유지.
     */
    fun projectHistoryExportJson(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/history/export"

    /**
     * v0.64.0 신규 — 프로젝트 history multipart import (Bearer 토큰 인증, JSON 응답).
     * Query: `dryRun=true|false`. 응답: HistoryImportResponseDto.
     */
    fun projectHistoryImportJson(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/history/import"

    /**
     * v0.64.0 — path segment URL encoding helper.
     * `agent` / `projectId` / `turnId` 등 사용자 정의 식별자가 path 에 들어갈 때 사용.
     * form encoding (`+`) 이 아니라 path encoding (`%20`) 이라
     * [java.net.URLEncoder] 결과를 추가 변환한다.
     */
    private fun pathSeg(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object ApiHeader {
    const val AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val DEVICE_NAME = "X-Device-Name"
}
