package com.siamakerlab.vibecoder.console.data.repository

import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.console.data.remote.ApiService
import com.siamakerlab.vibecoder.shared.dto.ArtifactDto
import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import com.siamakerlab.vibecoder.shared.dto.FileEntryDto
import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import com.siamakerlab.vibecoder.shared.dto.ServerStatusDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val prefs: AppPreferences,
) {
    /**
     * Username/password 로그인. 성공 시 토큰을 prefs에 저장.
     * v0.4.0부터 페어링 코드 방식은 deprecated.
     */
    suspend fun login(serverUrl: String, username: String, password: String, deviceName: String) {
        val resp = api.login(serverUrl, username, password, deviceName)
        prefs.saveSession(serverUrl, resp.token, deviceName, resp.deviceId)
    }
    suspend fun logout() = prefs.clear()
    suspend fun current() = prefs.current()
}

@Singleton
class ServerRepository @Inject constructor(private val api: ApiService) {
    suspend fun status(): ServerStatusDto = api.status()
    suspend fun environment(): EnvironmentCheckDto = api.environment()
}

@Singleton
class ProjectRepository @Inject constructor(private val api: ApiService) {
    suspend fun list(): List<ProjectDto> = api.projects()
    suspend fun get(id: String): ProjectDto = api.project(id)
    suspend fun register(req: RegisterProjectRequestDto): ProjectDto = api.registerProject(req)
}

@Singleton
class BuildRepository @Inject constructor(private val api: ApiService) {
    suspend fun runDebug(projectId: String): BuildDto = api.buildDebug(projectId)
    suspend fun list(projectId: String): List<BuildDto> = api.builds(projectId)
    suspend fun cancel(projectId: String, buildId: String) = api.cancelBuild(projectId, buildId)
}

@Singleton
class ArtifactRepository @Inject constructor(private val api: ApiService) {
    suspend fun list(projectId: String): List<ArtifactDto> = api.artifacts(projectId)
    suspend fun get(projectId: String, artifactId: String): ArtifactDto = api.artifact(projectId, artifactId)
    suspend fun downloadUrl(projectId: String, artifactId: String): String = api.artifactDownloadUrl(projectId, artifactId)
}

@Singleton
class GitRepository @Inject constructor(private val api: ApiService) {
    suspend fun status(projectId: String): GitStatusDto = api.gitStatus(projectId)
    suspend fun diff(projectId: String): GitDiffDto = api.gitDiff(projectId)
    suspend fun log(projectId: String): GitLogDto = api.gitLog(projectId)
}

@Singleton
class FileRepository @Inject constructor(private val api: ApiService) {
    suspend fun list(projectId: String): List<FileEntryDto> = api.listFiles(projectId).entries
    suspend fun delete(projectId: String, fileId: String) = api.deleteFile(projectId, fileId)
    suspend fun upload(projectId: String, fileName: String, mimeType: String, bytes: ByteArray) =
        api.uploadFile(projectId, fileName, mimeType, bytes)
}
