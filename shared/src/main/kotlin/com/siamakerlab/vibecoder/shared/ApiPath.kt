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

    // v0.10.0 — Git 통합 (PAT + SSH 키)
    const val GIT_INTEGRATIONS = "/api/settings/git-integrations"
    const val GIT_INTEGRATIONS_DELETE = "/api/settings/git-integrations/delete"
    const val GIT_INTEGRATIONS_SSH_KEYGEN = "/api/settings/git-integrations/ssh-keygen"
}

object ApiHeader {
    const val AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val DEVICE_NAME = "X-Device-Name"
}
