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
    /**
     * v0.26.0 — 2FA TOTP 코드 (6자리). 사용자가 TOTP 활성화 안 했으면 null.
     * 활성 + 누락 → 서버는 401 `totp_required`. 클라이언트가 사용자에게 코드 입력
     * 요청 후 같은 endpoint 에 totpCode 동봉으로 재시도.
     */
    val totpCode: String? = null,
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

/**
 * v1.2.0 — 서버 SSH 공개 키 스냅샷.
 *  - `publicKey`: `ssh-ed25519 AAAA... comment` 형식 그대로 (Git host 에 paste).
 *  - `algorithm`: 보통 `ssh-ed25519`.
 *  - `fingerprint`: `SHA256:xxxxxxxx==` 형태 (Git host UI 와 매칭).
 *  - `createdAt`: 키 파일의 mtime (ISO-8601).
 */
@Serializable
data class SshKeyDto(
    val publicKey: String,
    val algorithm: String,
    val comment: String? = null,
    val fingerprint: String? = null,
    val createdAt: String? = null,
)

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
    /**
     * v1.1.0 — 현재 프로젝트의 Claude 응답중 여부 (ClaudeSessionManager.isBusy(id)).
     * UI 가 list 에 응답중/대기중 뱃지를 표시할 때 사용. default false (process
     * 안 떠있거나 미감지). additive default-value 라 wire 호환.
     */
    val busy: Boolean = false,
)

/**
 * v1.60.0 — 프로젝트 목록 드래그 순서변경 요청.
 * [order] 는 현재 페이지(글로벌 [offset] 부터)의 새 id 순서. 서버가 전체 정렬의
 * 해당 slice 를 교체 후 sort_order 정규화. order 가 그 slice 와 같은 집합이 아니면 거부.
 */
@Serializable
data class ProjectReorderRequestDto(
    val offset: Int = 0,
    val order: List<String> = emptyList(),
)

@Serializable
data class RegisterProjectRequestDto(
    /**
     * Folder name under workspace (= projectId). Kebab-case recommended.
     * v1.7.0 — sourceType=clone 일 때 빈 문자열이면 cloneUrl 의 마지막 segment
     * (`.git` strip + sanitize) 에서 자동 도출.
     */
    val projectId: String = "",
    /**
     * Human display name shown in lists.
     * v1.7.0 — clone path 에선 비워두면 자동 (projectId 첫 글자 capitalized).
     */
    val appName: String = "",
    /**
     * Android applicationId, e.g. `com.siamakerlab.myapp`.
     * v1.7.0 — clone path 에선 비워두면 자동 (clone 후 build.gradle.kts /
     * AndroidManifest.xml 에서 추출 시도, 실패 시 `com.example.<projectId>` placeholder).
     */
    val packageName: String = "",
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
    /**
     * v1.7.18 — clone path 에서 기존 워크스페이스 폴더 (orphan, DB row 없음)
     * 가 존재할 때 강제 덮어쓰기. true 면 server 가 srcRoot 내용을 모두 삭제
     * 후 clone. false (default) 면 기존 동작 (`target_not_empty` 409 에러).
     *
     * **Wire change**: Android shared/ 의 RegisterProjectRequestDto 에도 같은
     * field 추가 필요 (default false 라 backward-compatible).
     */
    val overwrite: Boolean = false,
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
    /** v0.71.0 — Phase 51 #9: git branch / sha (PR 단위 비교용). null = 미수집. */
    val gitBranch: String? = null,
    val gitSha: String? = null,
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

/**
 * `POST /api/env-setup/claude-auth/upload` 응답.
 *
 * v0.64.0 — vibe-coder-android v0.7.x 호환을 위해 dual emit:
 * - `targetPath` / `expiresAt: Long` : 기존 (v0.5.4+) SSR/외부 client 가 사용.
 * - `path`       / `expiresAtIso: String?` : Android v0.7.x 가 사용 (필드명/타입 정렬).
 *
 * 다음 wire change 사이클에서 `targetPath` / `expiresAt` 을 deprecate 하고
 * `path` / `expiresAtIso` 로 단일화 예정.
 */
@Serializable
data class ClaudeCredentialsUploadResponseDto(
    val targetPath: String,
    val backup: String?,
    val expiresAt: Long,
    /** v0.64.0 — Android v0.7.x alias (= [targetPath]). */
    val path: String = targetPath,
    /** v0.64.0 — [expiresAt] 의 ISO-8601 String 표현 (Android v0.7.x 가 String 기대). */
    val expiresAtIso: String? = null,
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

/**
 * Git Integrations 목록 한 행.
 *
 * v0.64.0 — vibe-coder-android v0.7.x 호환을 위해 `token` alias 추가
 * (Android shared/ 가 `GitTokenDto.token` 으로 정의). 다음 wire change 에서
 * `tokenMasked` 를 deprecate 하고 `token` 으로 단일화 예정.
 */
@Serializable
data class GitTokenViewDto(
    val provider: String,
    val host: String,
    val username: String,
    val tokenMasked: String,
    val createdAt: String,
    val note: String? = null,
    /** v0.64.0 — Android v0.7.x alias (= [tokenMasked]). */
    val token: String = tokenMasked,
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

/**
 * v0.64.0 — 서버가 [ApiErrorDto.code] 로 보내는 표준 에러 코드.
 * REST 응답(주로 401/403/429) 과 WebSocket [com.siamakerlab.vibecoder.shared.ws.WsFrame.Error]
 * 양쪽에서 공유. Android shared/ 와 동일한 상수 묶음 (SSOT).
 */
object ApiErrorCode {
    // 2FA (v0.26+)
    const val TOTP_REQUIRED = "totp_required"
    const val INVALID_TOTP = "invalid_totp"

    // Role (v0.45+)
    /** 403 — viewer 토큰이 mutating endpoint 를 호출. */
    const val VIEWER_READONLY = "viewer_readonly"
    /** 403 — admin 전용 endpoint 를 admin 이 아닌 토큰이 호출. */
    const val ADMIN_ONLY = "admin_only"

    // Project ACL (v0.49+)
    /** 403 — 사용자 ACL 에 포함되지 않은 프로젝트의 endpoint 호출. */
    const val PROJECT_FORBIDDEN = "project_forbidden"

    // Rate limit (v0.56+)
    /** 429 — per-IP token bucket 한도 초과. 응답 헤더 `Retry-After: <seconds>`. */
    const val RATE_LIMITED = "rate_limited"

    // 기존 (v0.10+)
    const val MANUAL_INSTALL_ONLY = "manual_install_only"
    const val MISSING_CLONE_URL = "missing_clone_url"
    const val BAD_URL_SCHEME = "bad_url_scheme"
    const val IN_PROGRESS = "in_progress"
    const val WRONG_STATE = "wrong_state"
    const val TOO_LARGE = "too_large"
    const val EXPIRED = "expired"
}

// endregion
