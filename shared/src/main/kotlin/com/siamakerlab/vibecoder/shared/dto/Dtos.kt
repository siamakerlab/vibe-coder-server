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
    /**
     * Claude CLI 인증(`claude login`) 진단. v0.5.4+ 추가.
     * `claude` 는 CLI 설치 자체를 보고, 이 필드는 자격증명 파일 존재 여부를 본다.
     * 기존 클라이언트 호환을 위해 nullable + default null.
     */
    val claudeAuth: CheckItemDto? = null,
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
    /**
     * v0.9.0 — 프로젝트 소스 유형.
     *  - `empty` (default): 빈 폴더 + CLAUDE.md 템플릿 (기존 동작, 호환).
     *  - `clone`: [cloneUrl] 로 git clone. private 인증은 환경설정의
     *    git provider 토큰 또는 vibe 사용자 SSH 키 사용.
     */
    val sourceType: String = "empty",
    /** sourceType=="clone" 일 때 필수. https:// 또는 git@ 형식. */
    val cloneUrl: String? = null,
    /** Optional — 비우면 repo 의 default branch. */
    val cloneBranch: String? = null,
    /**
     * v0.18.0 — 사전 정의 템플릿 id. sourceType=="empty" 일 때만 의미.
     * 비어 있거나 "empty" 면 starter prompt 없음 (기존 동작).
     * 그 외엔 ProjectTemplates.byId(templateId) 의 starterPrompt 를 후속 console
     * 입력에 자동 주입 — Claude 가 그 가이드대로 scaffolding 시작.
     */
    val templateId: String? = null,
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

// region v0.10.0 — Env setup (빌드환경 컴포넌트)

@Serializable
data class ComponentStateDto(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeHint: String,
    /** INSTALLED / MISSING / PARTIAL / UNKNOWN */
    val status: String,
    val message: String,
    /** 자동 설치 가능 여부 (CLAUDE_AUTH 처럼 인터랙티브한 항목은 false). */
    val installable: Boolean,
)

@Serializable
data class EnvSetupComponentsResponseDto(val components: List<ComponentStateDto>)

/** install / install-all 응답 — taskId 로 WS 로그 구독. */
@Serializable
data class EnvSetupTaskDto(val taskId: String)

// endregion

// region v0.10.0 — Claude 자격증명 / 로그인

@Serializable
data class ClaudeApiKeyRequestDto(val apiKey: String)

@Serializable
data class ClaudeCredentialsUploadResponseDto(
    val targetPath: String,
    val backup: String?,
    val expiresAt: Long,
)

@Serializable
data class ClaudeLoginStateDto(
    val id: String,
    /** IDLE / STARTING / AWAITING_CODE / VERIFYING / DONE / FAILED / CANCELED */
    val state: String,
    val url: String?,
    val startedAt: String,
    val updatedAt: String,
    val errorMessage: String?,
    val lastLines: List<String>,
)

@Serializable
data class ClaudeLoginSubmitRequestDto(val code: String)

// endregion

// region v0.10.0 — MCP 카탈로그

@Serializable
data class McpConfigFieldDto(
    val key: String,
    val label: String,
    val placeholder: String? = null,
    val isSecret: Boolean = false,
    val required: Boolean = true,
    val help: String? = null,
    /** v0.11.0 — true 면 파일 업로드 UI 필요. POST {mcpUploadFile} multipart 호출. */
    val isFile: Boolean = false,
    /** UI 의 accept 속성 (예: ".json,application/json"). */
    val acceptMime: String? = null,
)

/** v0.11.0 — MCP secret 파일 업로드 응답. 응답의 path 를 install request 의 configValues 값으로 사용. */
@Serializable
data class McpFileUploadResponseDto(val path: String)

@Serializable
data class McpEntryDto(
    val id: String,
    val displayName: String,
    val pkg: String,
    val description: String,
    /** Category enum 의 label (한국어). 클라이언트 그룹 표시용. */
    val category: String,
    /** VERIFIED / COMMUNITY / EXPERIMENTAL */
    val trust: String,
    val recommended: Boolean,
    val homepage: String? = null,
    val configFields: List<McpConfigFieldDto> = emptyList(),
    /** INSTALLED / REGISTERED_ONLY / NOT_INSTALLED / UNKNOWN */
    val status: String,
    /** 현재 등록된 config 값 (보안: secret 도 마스킹 없이 그대로 — 운영자가 자기 토큰 확인용). */
    val configValues: Map<String, String> = emptyMap(),
    /** v0.12.1 — 브라우저 OAuth 콜백 필수라 비인터랙티브 환경 미지원. UI 가 '준비중' 표시 + 설치 비활성. */
    val comingSoon: Boolean = false,
)

@Serializable
data class McpCatalogResponseDto(val entries: List<McpEntryDto>)

@Serializable
data class McpInstallRequestDto(
    /** id → (configKey → value) 맵. 예: {"github": {"GITHUB_PERSONAL_ACCESS_TOKEN":"ghp_..."}} */
    val selections: Map<String, Map<String, String>>,
)

@Serializable
data class McpUnregisterRequestDto(val ids: List<String>)

// endregion

// region v0.10.0 — Git 통합 (PAT + SSH)

@Serializable
data class GitTokenViewDto(
    val provider: String,
    val host: String,
    val username: String,
    val tokenMasked: String,
    val createdAt: String,
    val note: String? = null,
)

@Serializable
data class GitIntegrationsResponseDto(
    val tokens: List<GitTokenViewDto>,
    val sshPublicKey: String?,
)

@Serializable
data class GitTokenRegisterRequestDto(
    val provider: String,
    val host: String,
    val username: String? = null,
    val token: String,
    val note: String? = null,
)

@Serializable
data class GitTokenDeleteRequestDto(val host: String)

// endregion

// region Prompt templates (v0.20.0 — wire 정식화. 서버는 v0.13.0 부터 동일 shape 노출)

@Serializable
data class PromptTemplateDto(
    val id: String,
    val title: String,
    val category: String,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PromptTemplateListResponseDto(
    val templates: List<PromptTemplateDto>,
)

// endregion

// region Errors

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val detail: String? = null,
)

// endregion
