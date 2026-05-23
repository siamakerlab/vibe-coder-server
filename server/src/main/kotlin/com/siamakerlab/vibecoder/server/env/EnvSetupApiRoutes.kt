package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.git.GitCloneService
import com.siamakerlab.vibecoder.server.git.GitCredentialStore
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.ClaudeApiKeyRequestDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeCredentialsUploadResponseDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeLoginStateDto
import com.siamakerlab.vibecoder.shared.dto.ClaudeLoginSubmitRequestDto
import com.siamakerlab.vibecoder.shared.dto.ComponentStateDto
import com.siamakerlab.vibecoder.shared.dto.EnvSetupComponentsResponseDto
import com.siamakerlab.vibecoder.shared.dto.EnvSetupTaskDto
import com.siamakerlab.vibecoder.shared.dto.GitIntegrationsResponseDto
import com.siamakerlab.vibecoder.shared.dto.GitTokenDeleteRequestDto
import com.siamakerlab.vibecoder.shared.dto.GitTokenRegisterRequestDto
import com.siamakerlab.vibecoder.shared.dto.GitTokenViewDto
import com.siamakerlab.vibecoder.shared.dto.McpCatalogResponseDto
import com.siamakerlab.vibecoder.shared.dto.McpConfigFieldDto
import com.siamakerlab.vibecoder.shared.dto.McpEntryDto
import com.siamakerlab.vibecoder.shared.dto.McpInstallRequestDto
import com.siamakerlab.vibecoder.shared.dto.McpUnregisterRequestDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream

private val log = KotlinLogging.logger {}

/**
 * v0.10.0 — Admin SSR 전용이던 모든 신규 기능을 JSON API 로도 이중 노출.
 *
 * 같은 service 인스턴스 (EnvSetupService / ClaudeAuthService / ClaudeLoginService /
 * McpService / GitCredentialStore / GitCloneService) 를 SSR 라우트와 공유.
 * 모든 엔드포인트는 Bearer token 인증 (`installAuth` 의 [AUTH_BEARER]) 보호.
 *
 * Wire — `ApiPath` 의 v0.10.0 섹션과 [com.siamakerlab.vibecoder.shared.dto] 의
 * 신규 DTO 들이 클라이언트(vibe-coder-android) 와 1:1 매칭.
 */
fun Routing.envSetupApiRoutes(
    envSetup: EnvSetupService,
    claudeAuth: ClaudeAuthService,
    claudeLogin: ClaudeLoginService,
    mcp: McpService,
    credentials: GitCredentialStore,
    cloneSvc: GitCloneService,
    clock: Clock,
) {
    authenticate(AUTH_BEARER) {

        // ── Env setup 컴포넌트 ─────────────────────────────────
        get(ApiPath.ENV_SETUP_COMPONENTS) {
            val items = envSetup.detectAll().map { it.toDto() }
            call.respond(EnvSetupComponentsResponseDto(items))
        }

        post(ApiPath.ENV_SETUP_INSTALL_ALL) {
            val taskId = envSetup.spawnInstallAll()
            call.respond(EnvSetupTaskDto(taskId))
        }

        post(ApiPath.envSetupInstall("{componentId}")) {
            val id = call.parameters["componentId"]!!
            val comp = SetupComponent.byId(id)
                ?: throw ApiException(404, "unknown_component", "Unknown component: $id")
            val taskId = envSetup.spawnInstall(comp)
            call.respond(EnvSetupTaskDto(taskId))
        }

        // ── Claude 자격증명 (옵션 B, C) ─────────────────────────
        post(ApiPath.CLAUDE_AUTH_UPLOAD) {
            val multipart = call.receiveMultipart()
            var bytes: ByteArray? = null
            try {
                while (true) {
                    val part = multipart.readPart() ?: break
                    try {
                        if (part is PartData.FileItem && bytes == null) {
                            bytes = part.provider().toInputStream().use { it.readBytes() }
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (e: Throwable) {
                throw ApiException(400, "multipart", "multipart 파싱 실패: ${e.message}")
            }
            val data = bytes ?: throw ApiException(400, "empty",
                "파일이 선택되지 않았습니다 (multipart field 무엇이든 허용).")
            val result = claudeAuth.uploadCredentials(data)
            call.respond(ClaudeCredentialsUploadResponseDto(
                targetPath = result.targetPath,
                backup = result.backup,
                expiresAt = result.expiresAt,
            ))
        }

        post(ApiPath.CLAUDE_AUTH_API_KEY) {
            val req = call.receive<ClaudeApiKeyRequestDto>()
            claudeAuth.registerApiKey(req.apiKey)
            call.respond(HttpStatusCode.NoContent)
        }

        // DELETE 메서드 + POST 둘 다 허용 — 모바일 SDK 호환성.
        delete(ApiPath.CLAUDE_AUTH_API_KEY_DELETE) {
            claudeAuth.deleteApiKey()
            call.respond(HttpStatusCode.NoContent)
        }
        post(ApiPath.CLAUDE_AUTH_API_KEY_DELETE) {
            claudeAuth.deleteApiKey()
            call.respond(HttpStatusCode.NoContent)
        }

        // ── Claude 반자동 OAuth (옵션 A) ────────────────────────
        post(ApiPath.CLAUDE_LOGIN_START) {
            val s = claudeLogin.start()
            call.respond(s.toApiDto())
        }
        post(ApiPath.CLAUDE_LOGIN_SUBMIT) {
            val req = call.receive<ClaudeLoginSubmitRequestDto>()
            val s = claudeLogin.submitCode(req.code)
            call.respond(s.toApiDto())
        }
        get(ApiPath.CLAUDE_LOGIN_STATUS) {
            val s = claudeLogin.status()
            if (s == null) call.respond(HttpStatusCode.NoContent)
            else call.respond(s.toApiDto())
        }
        post(ApiPath.CLAUDE_LOGIN_CANCEL) {
            val s = claudeLogin.cancel()
            if (s == null) call.respond(HttpStatusCode.NoContent)
            else call.respond(s.toApiDto())
        }

        // ── MCP 카탈로그 ────────────────────────────────────────
        get(ApiPath.MCP_CATALOG) {
            val states = mcp.detectAll().associateBy { it.id }
            val entries = McpCatalog.all.map { e ->
                val st = states[e.id]
                McpEntryDto(
                    id = e.id,
                    displayName = e.displayName,
                    pkg = e.pkg,
                    description = e.description,
                    category = e.category.label,
                    trust = e.trust.name,
                    recommended = e.recommended,
                    homepage = e.homepage,
                    configFields = e.configFields.map { f ->
                        McpConfigFieldDto(f.key, f.label, f.placeholder, f.isSecret, f.required, f.help)
                    },
                    status = st?.status?.name ?: "UNKNOWN",
                    configValues = st?.configValues.orEmpty(),
                )
            }
            call.respond(McpCatalogResponseDto(entries))
        }
        post(ApiPath.MCP_INSTALL) {
            val req = call.receive<McpInstallRequestDto>()
            val taskId = mcp.spawnBatch(req.selections)
            call.respond(EnvSetupTaskDto(taskId))
        }
        post(ApiPath.MCP_UNREGISTER) {
            val req = call.receive<McpUnregisterRequestDto>()
            mcp.unregister(req.ids)
            call.respond(HttpStatusCode.NoContent)
        }

        // ── Git 통합 ────────────────────────────────────────────
        get(ApiPath.GIT_INTEGRATIONS) {
            val tokens = credentials.list().map {
                GitTokenViewDto(it.provider, it.host, it.username, it.tokenMasked, it.createdAt, it.note)
            }
            call.respond(GitIntegrationsResponseDto(tokens, cloneSvc.getPublicKeyOrNull()))
        }
        post(ApiPath.GIT_INTEGRATIONS) {
            val req = call.receive<GitTokenRegisterRequestDto>()
            credentials.register(
                provider = req.provider, host = req.host, username = req.username,
                token = req.token, note = req.note, nowIso = clock.nowIso(),
            )
            call.respond(HttpStatusCode.NoContent)
        }
        post(ApiPath.GIT_INTEGRATIONS_DELETE) {
            val req = call.receive<GitTokenDeleteRequestDto>()
            val removed = credentials.delete(req.host)
            if (removed) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound)
        }
        post(ApiPath.GIT_INTEGRATIONS_SSH_KEYGEN) {
            val pub = cloneSvc.ensureSshKeyExists()
            call.respond(GitIntegrationsResponseDto(
                tokens = credentials.list().map {
                    GitTokenViewDto(it.provider, it.host, it.username, it.tokenMasked, it.createdAt, it.note)
                },
                sshPublicKey = pub,
            ))
        }
    }
}

// 도메인 객체 → DTO 매핑 헬퍼

private fun ComponentState.toDto() = ComponentStateDto(
    id = component.id,
    displayName = component.displayName,
    description = component.description,
    sizeHint = component.sizeHint,
    status = status.name,
    message = message,
    installable = component.doctorCmd != null,
)

private fun ClaudeLoginService.SessionDto.toApiDto() = ClaudeLoginStateDto(
    id = id,
    state = state,
    url = url,
    startedAt = startedAt,
    updatedAt = updatedAt,
    errorMessage = errorMessage,
    lastLines = lastLines,
)
