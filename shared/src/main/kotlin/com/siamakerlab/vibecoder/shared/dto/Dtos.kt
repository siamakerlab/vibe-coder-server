package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

// region Auth

@Serializable
data class PairRequestDto(
    val deviceName: String,
    val pairingCode: String,
)

@Serializable
data class PairResponseDto(
    val token: String,
    val deviceId: String,
    val serverName: String,
)

@Serializable
data class MeDto(
    val deviceId: String,
    val deviceName: String,
)

// endregion

// region Server status / environment

@Serializable
data class ServerStatusDto(
    val serverName: String,
    val serverVersion: String,
    val osName: String,
    val javaVersion: String,
    val workspaceRoot: String,
    val projectCount: Int,
    val runningTaskCount: Int,
    val claudeAvailable: Boolean,
    val androidSdkAvailable: Boolean,
    val gitAvailable: Boolean,
    val freeDiskSpaceBytes: Long,
)

@Serializable
enum class CheckStatus { OK, WARNING, ERROR }

@Serializable
data class CheckItemDto(
    val status: CheckStatus,
    val name: String,
    val message: String,
    val detail: String? = null,
)

@Serializable
data class EnvironmentCheckDto(
    val java: CheckItemDto,
    val androidSdk: CheckItemDto,
    val git: CheckItemDto,
    val claude: CheckItemDto,
    val workspace: CheckItemDto,
)

// endregion

// region Projects

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String,
    val debugTask: String,
    val lastBuildStatus: String? = null,
    val hasGitChanges: Boolean = false,
    val updatedAt: String,
)

@Serializable
data class RegisterProjectRequestDto(
    val projectId: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String = "app",
    val debugTask: String = "assembleDebug",
)

// endregion

// region Tasks

@Serializable
enum class TaskStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELED, TIMEOUT }

@Serializable
enum class TaskType { CLAUDE_PROMPT, BUILD_DEBUG, GIT_STATUS, GIT_DIFF, GIT_LOG, FILE_UPLOAD }

@Serializable
data class TaskDto(
    val id: String,
    val projectId: String,
    val type: TaskType,
    val status: TaskStatus,
    val title: String,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ClaudeTaskRequestDto(
    val prompt: String,
    val autoBuild: Boolean = false,
)

// endregion

// region Builds + Artifacts

@Serializable
data class BuildDto(
    val id: String,
    val projectId: String,
    val variant: String,
    val status: TaskStatus,
    val startedAt: String,
    val finishedAt: String? = null,
    val artifactId: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ArtifactDto(
    val id: String,
    val projectId: String,
    val buildId: String,
    val type: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val createdAt: String,
)

// endregion

// region Git

@Serializable
data class GitStatusEntryDto(val status: String, val path: String)

@Serializable
data class GitStatusDto(
    val branch: String,
    val entries: List<GitStatusEntryDto>,
    val ahead: Int = 0,
    val behind: Int = 0,
)

@Serializable
data class GitDiffDto(val diff: String)

@Serializable
data class GitLogEntryDto(
    val sha: String,
    val message: String,
)

@Serializable
data class GitLogDto(val entries: List<GitLogEntryDto>)

// endregion

// region Files

@Serializable
data class FileEntryDto(
    val id: String,
    val originalName: String,
    val mimeType: String? = null,
    val sizeBytes: Long,
    val createdAt: String,
)

@Serializable
data class FileListDto(val entries: List<FileEntryDto>)

// endregion

// region Errors

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val detail: String? = null,
)

// endregion
