package com.siamakerlab.vibecoder.console.ui.nav

object Routes {
    const val CONNECT = "connect"
    const val DASHBOARD = "dashboard"
    const val ENVIRONMENT = "environment"
    const val PROJECT_LIST = "projects"
    const val PROJECT_REGISTER = "projects/register"

    // path templates
    const val CONSOLE = "projects/{projectId}/console"
    const val BUILD_LOG = "projects/{projectId}/builds/{buildId}/logs"
    const val BUILDS = "projects/{projectId}/builds"
    const val ARTIFACTS = "projects/{projectId}/artifacts"
    const val GIT = "projects/{projectId}/git"
    const val FILES = "projects/{projectId}/files"

    fun console(id: String) = "projects/$id/console"
    fun buildLog(id: String, buildId: String) = "projects/$id/builds/$buildId/logs"
    fun builds(id: String) = "projects/$id/builds"
    fun artifacts(id: String) = "projects/$id/artifacts"
    fun git(id: String) = "projects/$id/git"
    fun files(id: String) = "projects/$id/files"

    const val ARG_PROJECT_ID = "projectId"
    const val ARG_BUILD_ID = "buildId"
}
