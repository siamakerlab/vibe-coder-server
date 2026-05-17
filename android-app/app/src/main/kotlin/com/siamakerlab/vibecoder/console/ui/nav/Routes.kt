package com.siamakerlab.vibecoder.console.ui.nav

object Routes {
    const val CONNECT = "connect"
    const val DASHBOARD = "dashboard"
    const val ENVIRONMENT = "environment"
    const val PROJECT_LIST = "projects"
    const val PROJECT_REGISTER = "projects/register"

    // path templates
    const val PROJECT_DETAIL = "projects/{projectId}"
    const val CLAUDE_PROMPT = "projects/{projectId}/claude"
    const val LOG = "projects/{projectId}/logs/{kind}/{taskId}"
    const val BUILDS = "projects/{projectId}/builds"
    const val ARTIFACTS = "projects/{projectId}/artifacts"
    const val GIT = "projects/{projectId}/git"
    const val FILES = "projects/{projectId}/files"

    fun projectDetail(id: String) = "projects/$id"
    fun claudePrompt(id: String) = "projects/$id/claude"
    fun log(id: String, kind: String, taskId: String) = "projects/$id/logs/$kind/$taskId"
    fun builds(id: String) = "projects/$id/builds"
    fun artifacts(id: String) = "projects/$id/artifacts"
    fun git(id: String) = "projects/$id/git"
    fun files(id: String) = "projects/$id/files"

    const val ARG_PROJECT_ID = "projectId"
    const val ARG_TASK_ID = "taskId"
    const val ARG_KIND = "kind"   // "task" | "build"
}
