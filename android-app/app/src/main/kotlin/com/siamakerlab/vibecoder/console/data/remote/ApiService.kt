package com.siamakerlab.vibecoder.console.data.remote

import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeTaskRequestDto
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import com.siamakerlab.vibecoder.shared.dto.FileListDto
import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import com.siamakerlab.vibecoder.shared.dto.PairRequestDto
import com.siamakerlab.vibecoder.shared.dto.PairResponseDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto
import com.siamakerlab.vibecoder.shared.dto.TaskDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiService @Inject constructor(
    private val client: HttpClient,
    private val prefs: AppPreferences,
) {
    private suspend fun base(): String = (prefs.session.first().serverUrl ?: error("server not paired")).trimEnd('/')
    private fun u(path: String, base: String) = "$base$path"

    suspend fun pair(serverUrl: String, deviceName: String, code: String): PairResponseDto {
        val resp = client.post(u(ApiPath.AUTH_PAIR, serverUrl.trimEnd('/'))) {
            contentType(ContentType.Application.Json)
            setBody(PairRequestDto(deviceName = deviceName, pairingCode = code))
        }
        return resp.body()
    }

    suspend fun status(): ServerStatusDto = client.get(u(ApiPath.SERVER_STATUS, base())).body()
    suspend fun environment(): EnvironmentCheckDto = client.get(u(ApiPath.SERVER_ENVIRONMENT, base())).body()
    suspend fun environmentCheck(): EnvironmentCheckDto = client.post(u(ApiPath.SERVER_ENVIRONMENT_CHECK, base())).body()

    suspend fun projects(): List<ProjectDto> = client.get(u(ApiPath.PROJECTS, base())).body()
    suspend fun registerProject(body: RegisterProjectRequestDto): ProjectDto =
        client.post(u(ApiPath.PROJECTS_REGISTER, base())) {
            contentType(ContentType.Application.Json); setBody(body)
        }.body()
    suspend fun project(id: String): ProjectDto = client.get(u(ApiPath.project(id), base())).body()

    suspend fun submitClaudeTask(projectId: String, body: ClaudeTaskRequestDto): TaskDto =
        client.post(u(ApiPath.claudeTasks(projectId), base())) {
            contentType(ContentType.Application.Json); setBody(body)
        }.body()
    suspend fun cancelTask(projectId: String, taskId: String) {
        client.post(u(ApiPath.claudeTaskCancel(projectId, taskId), base()))
    }
    suspend fun listClaudeTasks(projectId: String): List<TaskDto> =
        client.get(u(ApiPath.claudeTasks(projectId), base())).body()

    suspend fun buildDebug(projectId: String): BuildDto =
        client.post(u(ApiPath.buildDebug(projectId), base())).body()
    suspend fun builds(projectId: String): List<BuildDto> =
        client.get(u(ApiPath.builds(projectId), base())).body()
    suspend fun cancelBuild(projectId: String, buildId: String) {
        client.post(u(ApiPath.buildCancel(projectId, buildId), base()))
    }

    suspend fun artifacts(projectId: String): List<ArtifactDto> =
        client.get(u(ApiPath.artifacts(projectId), base())).body()
    suspend fun artifact(projectId: String, artifactId: String): ArtifactDto =
        client.get(u(ApiPath.artifact(projectId, artifactId), base())).body()

    suspend fun gitStatus(projectId: String): GitStatusDto =
        client.get(u(ApiPath.gitStatus(projectId), base())).body()
    suspend fun gitDiff(projectId: String): GitDiffDto =
        client.get(u(ApiPath.gitDiff(projectId), base())).body()
    suspend fun gitLog(projectId: String): GitLogDto =
        client.get(u(ApiPath.gitLog(projectId), base())).body()

    suspend fun listFiles(projectId: String): FileListDto =
        client.get(u(ApiPath.files(projectId), base())).body()
    suspend fun deleteFile(projectId: String, fileId: String) {
        client.delete(u(ApiPath.file(projectId, fileId), base()))
    }
    suspend fun uploadFile(projectId: String, fileName: String, mimeType: String, bytes: ByteArray) {
        client.submitFormWithBinaryData(
            url = u(ApiPath.filesUpload(projectId), base()),
            formData = formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }
        )
    }

    /** Build the URL for direct GET of an artifact APK. */
    suspend fun artifactDownloadUrl(projectId: String, artifactId: String): String =
        u(ApiPath.artifactDownload(projectId, artifactId), base())
}
