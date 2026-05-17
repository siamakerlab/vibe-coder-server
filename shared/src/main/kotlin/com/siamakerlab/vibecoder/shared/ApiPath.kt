package com.siamakerlab.vibecoder.shared

/**
 * Single source of truth for REST + WebSocket paths.
 * Both server routes and Android repository reference these constants
 * so a path change recompiles both sides.
 */
object ApiPath {

    // Auth
    const val AUTH_PAIR = "/api/auth/pair"
    const val AUTH_ME = "/api/auth/me"
    const val AUTH_LOGOUT = "/api/auth/logout"

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

    // Claude tasks
    fun claudeTasks(projectId: String) = "/api/projects/$projectId/claude/tasks"
    fun claudeTask(projectId: String, taskId: String) =
        "/api/projects/$projectId/claude/tasks/$taskId"
    fun claudeTaskCancel(projectId: String, taskId: String) =
        "/api/projects/$projectId/claude/tasks/$taskId/cancel"

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
    fun wsTaskLogs(projectId: String, taskId: String) =
        "/ws/projects/$projectId/tasks/$taskId/logs"
    fun wsBuildLogs(projectId: String, buildId: String) =
        "/ws/projects/$projectId/builds/$buildId/logs"
}

object ApiHeader {
    const val AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "
    const val DEVICE_NAME = "X-Device-Name"
}
