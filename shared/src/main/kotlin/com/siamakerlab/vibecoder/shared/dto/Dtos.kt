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
    val username: String? = null,
)

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    val deviceName: String? = null,   // 클라이언트가 자신을 식별할 라벨. 미지정 시 "unknown"
)

@Serializable
data class LoginResponseDto(
    val token: String,
    val deviceId: String,
    val serverName: String,
    val username: String,
)

@Serializable
data class SetupRequestDto(
    val username: String,
    val password: String,
    val deviceName: String? = null,
)

@Serializable
data class ChangePasswordRequestDto(
    val currentPassword: String,
    val newPassword: String,
)

@Serializable
data class SetupStatusDto(
    val adminExists: Boolean,
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
    /** Folder name under workspace (= projectId). Kebab-case recommended. */
    val projectId: String,
    /** Human display name shown in lists. */
    val appName: String,
    /** Android applicationId, e.g. `com.siamakerlab.myapp`. */
    val packageName: String,
    /** Optional keystore generation request. If null, no keystore is created. */
    val keystore: KeystoreRequestDto? = null,
)

@Serializable
data class KeystoreRequestDto(
    /** Key alias inside the keystore (e.g. "myapp"). */
    val alias: String,
    /** Password used for BOTH -storepass and -keypass for simplicity. */
    val password: String,
    /** Optional override of the -dname certificate distinguished name. */
    val dname: String? = null,
    /** Validity in days. Default ≈100 years for personal projects. */
    val validityDays: Int = 36500,
)

// endregion

// region Build status

/**
 * Lifecycle states for a Build job. Named `TaskStatus` for historical reasons —
 * the deprecated Claude task pipeline shared this enum, but as of v0.2.1 it is
 * used exclusively by the build subsystem.
 */
@Serializable
enum class TaskStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELED, TIMEOUT }

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
