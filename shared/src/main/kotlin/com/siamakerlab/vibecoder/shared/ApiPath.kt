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
    // v1.2.0 — SSH 키 (vibe 사용자 ~/.ssh/id_ed25519).
    const val SERVER_SSH_KEY = "/api/server/ssh-key"
    const val SERVER_SSH_KEY_REGENERATE = "/api/server/ssh-key/regenerate"
    // v1.3.2 — 전역 (계정 단위) Claude 쿼타. 어느 프로젝트 콘솔이든 같은 계정 quota
    // 라 SSR 사이드바 / Android 탭 어디서나 동일 값 노출. ClaudeStatusService.snapshot
    // 의 scratch 프로젝트 결과 그대로 반환.
    const val SERVER_QUOTA = "/api/server/quota"
    // Codex CLI usage/status snapshot. Separate from Claude quota so providers stay isolated.
    const val SERVER_CODEX_QUOTA = "/api/server/codex-quota"

    // v1.74.0 — 홈 대시보드 "서버 상태" 카드(CPU/RAM/프로세스 점유). admin 페이지 폴링,
    // server-internal (Android client 미사용). 저민감(리소스 사용률) → quota 와 동일 무인증.
    const val SERVER_STATS = "/api/server/stats"

    // v1.6.0 — Workspace terminal (PTY bash). security.allowTerminal=true 필요.
    const val TERMINAL_SESSIONS = "/api/terminal/sessions"
    fun terminalSession(id: String) = "/api/terminal/sessions/$id"
    fun wsTerminal(id: String) = "/ws/terminal/$id"

    // v1.40.0 — 무선 ADB 기기 로그(logcat). admin 전용. server-internal (Android client 미사용).
    const val ADB_STATUS = "/api/adb/status"
    const val WS_ADB_LOGCAT = "/ws/adb/logcat"

    // v1.73.0 — 안드로이드 에뮬레이터(헤드리스, Claude Code 로그분석용) 실행 상태. admin 전용.
    // server-internal (Android client 미사용). 사이드바 pill 폴링. /emulator(SSR)·start·stop 은
    // AdbRoutes 와 동일하게 hardcoded path (SSR 액션은 ApiPath 비등록 관례).
    const val EMULATOR_STATUS = "/api/emulator/status"

    // Projects
    const val PROJECTS = "/api/projects"
    const val PROJECTS_REGISTER = "/api/projects/register"
    /** v1.60.0 — 프로젝트 목록 드래그 순서변경(body: ProjectReorderRequestDto). */
    const val PROJECTS_REORDER = "/api/projects/reorder"
    fun project(id: String) = "/api/projects/$id"
    /** v1.122.0 — 프로젝트 표시 이름 변경(body ProjectRenameRequestDto). 응답 갱신된 ProjectDto. */
    fun projectRename(id: String) = "/api/projects/$id/rename"

    // Claude console (persistent session)
    fun claudeConsolePrompt(projectId: String) =
        "/api/projects/$projectId/claude/console/prompt"
    fun claudeConsoleNew(projectId: String) =
        "/api/projects/$projectId/claude/console/new"
    /**
     * v0.13.0 — 현재 진행 중인 Claude turn 강제 중단. v1.112.0 부터 내부 구현이 SIGTERM 에서
     * control_request interrupt(같은 세션·프로세스 유지) 로 바뀜(엔드포인트/요청 형태는 동일).
     */
    fun claudeConsoleCancel(projectId: String) =
        "/api/projects/$projectId/claude/console/cancel"
    /**
     * v1.112.0 — "끼어들기": 진행 중 turn 을 interrupt 로 중단하고 곧바로 새 prompt 를 전송.
     * 요청 body 는 [claudeConsolePrompt] 와 동일(PromptRequestDto), 응답도 PromptAcceptedDto.
     * TUI 의 Esc → 새 입력과 동형. 진행 중이 아니면 일반 prompt 와 동일하게 동작.
     */
    fun claudeConsoleInterrupt(projectId: String) =
        "/api/projects/$projectId/claude/console/interrupt"
    /**
     * v1.120.0 — 콘솔 토큰/모델 설정 조회. 응답 ConsoleSettingsDto
     * (model/effectiveModel/autoCompact/mcpStrict/availableModels).
     */
    fun consoleSettings(projectId: String) =
        "/api/projects/$projectId/claude/console/settings"
    /** v1.120.0 — 모델 변경(body ConsoleModelRequestDto). 적용 시 세션 재시작(컨텍스트 유지). */
    fun consoleModel(projectId: String) =
        "/api/projects/$projectId/claude/console/model"
    /** v1.120.0 — MCP 최소화 토글(body ConsoleToggleRequestDto). */
    fun consoleMcpStrict(projectId: String) =
        "/api/projects/$projectId/claude/console/mcp-strict"
    /** v1.120.0 — 자동 /compact 토글(body ConsoleToggleRequestDto). */
    fun consoleAutoCompact(projectId: String) =
        "/api/projects/$projectId/claude/console/auto-compact"
    fun claudeStatus(projectId: String) =
        "/api/projects/$projectId/claude/status"

    // v1.59.0 — 프롬프트 자동화 (서버 백그라운드 autopilot). turn 완료마다 다음
    // 프롬프트 자동 전송. 실행 제어는 프로젝트별, 프리셋은 workspace 전역.
    fun promptAutomationStart(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/automation/start"
    fun promptAutomationStop(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/automation/stop"
    fun promptAutomationStatus(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/automation/status"
    /** workspace 전역 프리셋 목록/생성. */
    const val PROMPT_AUTOMATION_PRESETS = "/api/prompt-automations"
    /** workspace 전역 프리셋 단건 (수정/삭제). */
    fun promptAutomationPreset(presetId: String) =
        "/api/prompt-automations/${pathSeg(presetId)}"

    // v1.130.0 — 프롬프트 예약 전송. 지정 시각 / 상대 지연 / Claude 한도 해제 시점에
    // 프롬프트 1회 자동 전송(one-shot). 자동화(연쇄)와 달리 단발 예약.
    fun scheduledPrompts(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/schedule"
    fun scheduledPrompt(projectId: String, scheduleId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/schedule/${pathSeg(scheduleId)}"

    // v1.133.0 — 대화 turn 에 저장된 이미지 서빙 (user 첨부/tool_result 스크린샷).
    // query: ?turn=<turnIdx>&idx=<imageIndex>. (v1.138.0 — SSOT 등록 회수)
    fun claudeConsoleImage(projectId: String) =
        "/api/projects/${pathSeg(projectId)}/claude/console/image"

    // v1.136.0 — 프롬프트 일괄 전송. 선택한 여러 프로젝트에 같은 프롬프트를 한 번에
    // 전송(202 + accepted/rejected). 동시 turn 게이트가 순차 처리(큐)하므로 한도 초과분은
    // 대기 — v1.135.0 게이트 선확보로 대기 중 프로세스/메모리 점유 없음.
    const val CLAUDE_BROADCAST = "/api/claude/broadcast"

    // Project actions (chip system)
    fun projectActions(projectId: String) = "/api/projects/$projectId/actions"
    fun projectActionsInvoke(projectId: String) =
        "/api/projects/$projectId/actions/invoke"

    // Builds
    fun buildDebug(projectId: String) = "/api/projects/$projectId/build/debug"
    /**
     * v1.118.0 — Release(APK) 빌드 큐 등록(assembleRelease, 키스토어 서명 주입).
     * body 없음, 응답 BuildDto(202). 키스토어 미존재 시 409 keystore_required.
     */
    fun buildRelease(projectId: String) = "/api/projects/$projectId/build/release"
    /**
     * v1.118.0 — Release AAB 번들 빌드 큐 등록(bundleRelease, Play Console 업로드용).
     * body 없음, 응답 BuildDto(202). 키스토어 미존재 시 409 keystore_required.
     */
    fun buildBundle(projectId: String) = "/api/projects/$projectId/build/bundle"
    /**
     * v1.121.0 — Google Play 업로드 트리거(body PlayUploadRequestDto). Claude 콘솔에
     * "이 .aab 를 Play <track> 에 업로드" 프롬프트 전송. 응답 StoreUploadResponseDto(202).
     */
    fun playUpload(projectId: String) = "/api/projects/$projectId/play-upload"
    fun builds(projectId: String) = "/api/projects/$projectId/builds"
    fun build(projectId: String, buildId: String) =
        "/api/projects/$projectId/builds/$buildId"
    fun buildCancel(projectId: String, buildId: String) =
        "/api/projects/$projectId/builds/$buildId/cancel"

    // Project archive (v1.119.0 — JSON; SSR `/archive` 는 유지). 응답은 ArchivedProjectDto.
    /** 아카이브 레지스트리 목록 — `List<ArchivedProjectDto>`. */
    const val ARCHIVES = "/api/archives"
    /** 현재 프로젝트를 아카이브(압축 보관). idle 가드. 응답 ArchivedProjectDto(202). */
    fun projectArchive(projectId: String) = "/api/projects/${pathSeg(projectId)}/archive"
    /** 아카이브 복원(원래 projectId 로 되살림). 응답 202. */
    fun archiveRestore(archiveId: String) = "/api/archives/${pathSeg(archiveId)}/restore"
    /** 아카이브 영구 삭제(DELETE). 파일 + 레지스트리 행 제거. */
    fun archiveDelete(archiveId: String) = "/api/archives/${pathSeg(archiveId)}"
    /** 아카이브 .tar.gz 다운로드(GET, attachment). */
    fun archiveDownload(archiveId: String) = "/api/archives/${pathSeg(archiveId)}/download"

    // Quality (v1.119.0 — Android Lint JSON; SSR `/projects/{id}/quality` 는 유지).
    /** Lint(:module:lintDebug) 실행 + 결과. POST `?module=app`. 응답 LintResultDto. */
    fun qualityLint(projectId: String) = "/api/projects/${pathSeg(projectId)}/quality/lint"
    /** 선택 lint 이슈를 콘솔(Claude)로 수정요청 전송(body QualityFixRequestDto). 응답 QualityFixResponseDto. */
    fun qualityFix(projectId: String) = "/api/projects/${pathSeg(projectId)}/quality/fix"

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
    // v1.31.1 — cross-project busy state push (대시보드 / workspaces 실시간 동기).
    const val WS_PROJECTS_STATE = "/ws/projects"

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
    //   - 동적 path (`projectHistory(projectId)` 등 `fun`) — client 호출 + **라우터
    //     등록 양쪽 사용 가능**. pathSeg 가 `{name}` 을 `%7Bname%7D` 로 URL encode
    //     하지만, Ktor routing 은 등록 path 를 URL-decode 후 segment 를 파싱하므로
    //     `%7Bname%7D` 가 `{name}` placeholder 로 정상 매칭된다 (JsonAdminRoutes 의
    //     `userRole("{userId}")` / `automationSchedule(...)`, WsRoutes 의
    //     `wsAgentConsoleLogs("{projectId}","{agentName}")` 등이 그렇게 운영 중).
    //     v1.31.2 — 이전 주석은 "라우터에 못 들어감, hardcoded 써야 함" 이라 실제
    //     동작과 반대였음 (16차 점검 Q2). SSOT 효과: path 형식/placeholder 이름이
    //     한 곳에 명세 + grep 매칭. 라우터는 이 함수에 `"{name}"` 리터럴 전달.
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
    /** v1.88.0 — 모든 unread 일괄 ack (알림 미니창 "모두 삭제"). */
    const val NOTIFICATIONS_ACK_ALL = "/api/notifications/ack-all"
    /** v0.68.0 — Optional FCM token 등록 (Firebase 설정 시만 의미). */
    const val FCM_TOKEN_REGISTER = "/api/notifications/fcm-token"

    /**
     * v0.70.0 — Phase 49 #14. APK 시그너처 on-demand verify.
     * 응답: [com.siamakerlab.vibecoder.shared.dto.ApkVerifyResultDto] (v0.76.0+
     * shared SSOT, 이전엔 server-local `ApkVerifier.Result`).
     */
    fun artifactVerify(projectId: String, artifactId: String) =
        "/api/projects/${pathSeg(projectId)}/artifacts/${pathSeg(artifactId)}/verify"

    // v0.76.0 (SSOT 정합) — 기존엔 server 라우터가 hardcoded 사용. wire 호환 영향
    // 없음 (Android client 가 호출 안 함). SSOT 기록만으로 의미.
    /** v0.46.0+ — Web Push VAPID public key. */
    const val PUSH_VAPID_PUBLIC_KEY = "/api/push/vapid-public-key"
    /** v0.46.0+ — Web Push subscription register. */
    const val PUSH_SUBSCRIBE = "/api/push/subscribe"
    fun pushSubscription(id: String) = "/api/push/subscriptions/${pathSeg(id)}"

    /** v0.48.0+ — WebAuthn (passkey) — 등록 옵션 / 검증. */
    const val WEBAUTHN_REGISTER_OPTIONS = "/api/webauthn/register/options"
    const val WEBAUTHN_REGISTER_VERIFY = "/api/webauthn/register/verify"
    const val WEBAUTHN_ASSERT_OPTIONS = "/api/webauthn/assert/options"
    const val WEBAUTHN_ASSERT_VERIFY = "/api/webauthn/assert/verify"

    /** v0.27.0+ — 외부 시스템 빌드 webhook. HMAC 서명 검증. */
    fun buildWebhook(projectId: String) = "/api/webhooks/build/${pathSeg(projectId)}"

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
     * v1.91.0 신규 — 독립 메모 (전역/프로젝트별) 컬렉션.
     * GET: `?projectId=X` 면 전역 + 프로젝트 X 메모, 없으면 전체. 응답 [MemoListResponseDto].
     * POST: [MemoCreateRequestDto] body 로 생성. 응답 [MemoDto].
     * Bearer 토큰 (또는 cookie 세션) 인증. POST 는 write 권한.
     */
    const val MEMOS = "/api/memos"

    /**
     * v1.91.0 신규 — 단일 메모. PUT [MemoUpdateRequestDto] 수정 / DELETE 삭제.
     * 응답: PUT [MemoDto], DELETE [MemoMutationAckDto].
     */
    fun memo(memoId: String) = "/api/memos/${pathSeg(memoId)}"

    /**
     * v0.64.0 — path segment URL encoding helper.
     * `agent` / `projectId` / `turnId` 등 사용자 정의 식별자가 path 에 들어갈 때 사용.
     * form encoding (`+`) 이 아니라 path encoding (`%20`) 이라
     * [java.net.URLEncoder] 결과를 추가 변환한다.
     *
     * v1.145.18 — **중괄호(`{`/`}`)는 인코딩하지 않는다(절대 되돌리지 말 것).**
     * 이 ApiPath fun 들은 §8.A SSOT 규칙상 두 용도로 동시에 쓰인다:
     *   ① 클라이언트의 실제 URL 빌드 — 예: `promptAutomationStart("caldo")`.
     *   ② 서버의 Ktor 라우트 템플릿 등록 — 예: `promptAutomationStart("{projectId}")`.
     * `URLEncoder` 는 `{`→`%7B`, `}`→`%7D` 로 인코딩하는데, Ktor `RoutingPath.parse`
     * (3.1.x)는 세그먼트에 **리터럴** `{`/`}` 가 있어야만 path parameter 로 인식하고
     * 없으면 `decodeURLPart` 후 상수(Constant)로 등록한다. 즉 `%7BprojectId%7D` 는
     * 리터럴 `{projectId}` **고정 문자열** 라우트가 되어 실제 projectId 요청과 영영
     * 매칭되지 않는다(`route_not_found` 404). 이 결함이 v1.59.0~v1.145.17 동안 automation/
     * schedule/deps/stats/backup-file/build-webhook/agent-console 등 pathSeg 기반
     * 라우트를 전부 무력화했다. 중괄호를 보존하면 ②가 올바른 parameter 로 등록되고,
     * ①은 실제 식별자가 regex(`[a-z0-9][a-z0-9._-]{0,63}` 등)로 검증되어 `{`/`}` 를
     * 절대 포함하지 않으므로 영향이 없다.
     */
    private fun pathSeg(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%7B", "{")
            .replace("%7D", "}")
}

object ApiHeader {
    const val AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val DEVICE_NAME = "X-Device-Name"
}
