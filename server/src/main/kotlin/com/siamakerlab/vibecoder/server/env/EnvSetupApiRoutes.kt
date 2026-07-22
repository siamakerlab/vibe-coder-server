package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiAdmin
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.git.GitCloneService
import com.siamakerlab.vibecoder.server.git.GitCredentialStore
import com.siamakerlab.vibecoder.shared.ApiPath
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
import com.siamakerlab.vibecoder.shared.dto.McpFileUploadResponseDto
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream

private val log = KotlinLogging.logger {}

/**
 * v0.10.0 — Admin SSR 전용이던 모든 신규 기능을 JSON API 로도 이중 노출.
 *
 * 같은 service 인스턴스 (EnvSetupService / ClaudeLoginService /
 * McpService / GitCredentialStore / GitCloneService) 를 SSR 라우트와 공유.
 * 모든 엔드포인트는 Bearer token 인증 (`installAuth` 의 [AUTH_BEARER]) 보호.
 *
 * Wire — `ApiPath` 의 v0.10.0 섹션과 [com.siamakerlab.vibecoder.shared.dto] 의
 * 신규 DTO 들이 클라이언트(vibe-coder-android) 와 1:1 매칭.
 */
fun Routing.envSetupApiRoutes(
    envSetup: EnvSetupService,
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
            call.requireApiAdmin()
            val taskId = envSetup.spawnInstallAll()
            call.respond(EnvSetupTaskDto(taskId))
        }

        post(ApiPath.envSetupInstall("{componentId}")) {
            call.requireApiAdmin()
            val id = call.parameters["componentId"]!!
            val comp = SetupComponent.byId(id)
                ?: throw ApiException.localized(404, "unknown_component", messageKey = "api.envSetup.unknownComponent", args = listOf(id))
            val taskId = envSetup.spawnInstall(comp)
            call.respond(EnvSetupTaskDto(taskId))
        }

        // ── Claude 웹 OAuth ────────────────────────────────────
        post(ApiPath.CLAUDE_LOGIN_START) {
            call.requireApiAdmin()
            val s = claudeLogin.start()
            call.respond(s.toApiDto())
        }
        post(ApiPath.CLAUDE_LOGIN_SUBMIT) {
            call.requireApiAdmin()
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
            call.requireApiAdmin()
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
                        McpConfigFieldDto(
                            key = f.key, label = f.label, placeholder = f.placeholder,
                            isSecret = f.isSecret, required = f.required, help = f.help,
                            isFile = f.isFile, acceptMime = f.acceptMime,
                        )
                    },
                    // v0.64.0 — Android v0.7.x 가 소문자 상수와 비교. SSR templates 는
                    // McpService.Status enum 자체로 비교하므로 wire emit 만 lowercase.
                    status = (st?.status?.name ?: "UNKNOWN").lowercase(),
                    configValues = st?.configValues.orEmpty(),
                    comingSoon = e.comingSoon,
                )
            }
            call.respond(McpCatalogResponseDto(entries))
        }
        post(ApiPath.MCP_INSTALL) {
            call.requireApiAdmin()
            val req = call.receive<McpInstallRequestDto>()
            val taskId = mcp.spawnBatch(req.selections)
            call.respond(EnvSetupTaskDto(taskId))
        }
        post(ApiPath.MCP_UNREGISTER) {
            call.requireApiAdmin()
            val req = call.receive<McpUnregisterRequestDto>()
            mcp.unregister(req.ids)
            call.respond(HttpStatusCode.NoContent)
        }

        // v0.11.0 — MCP secret 파일 업로드 (Android wire)
        post(ApiPath.mcpUploadFile("{mcpId}", "{fieldKey}")) {
            call.requireApiAdmin()
            val mcpId = call.parameters["mcpId"]!!
            val fieldKey = call.parameters["fieldKey"]!!
            val multipart = call.receiveMultipart()
            var bytes: ByteArray? = null
            var fileName: String? = null
            try {
                while (true) {
                    val part = multipart.readPart() ?: break
                    try {
                        if (part is PartData.FileItem && bytes == null) {
                            fileName = part.originalFileName
                            bytes = part.provider().toInputStream().use { it.readBytes() }
                        }
                    } finally {
                        part.dispose()
                    }
                }
            } catch (e: Throwable) {
                throw ApiException.localized(400, "multipart", messageKey = "api.envSetup.multipartParse", args = listOf(e.message ?: ""))
            }
            val data = bytes ?: throw ApiException.localized(400, "empty", messageKey = "api.envSetup.emptyFile")
            val path = mcp.uploadConfigFile(mcpId, fieldKey, data, fileName)
            call.respond(McpFileUploadResponseDto(path))
        }

        // ── Git 통합 ────────────────────────────────────────────
        get(ApiPath.GIT_INTEGRATIONS) {
            call.requireApiAdmin() // v1.43.0 — 같은 라우터의 mutation 들과 정렬(admin 전용 메타데이터).
            val tokens = credentials.list().map {
                GitTokenViewDto(it.provider, it.host, it.username, it.tokenMasked, it.createdAt, it.note)
            }
            call.respond(GitIntegrationsResponseDto(tokens, cloneSvc.getPublicKeyOrNull()))
        }
        post(ApiPath.GIT_INTEGRATIONS) {
            call.requireApiAdmin()
            val req = call.receive<GitTokenRegisterRequestDto>()
            credentials.register(
                provider = req.provider, host = req.host, username = req.username,
                token = req.token, note = req.note, nowIso = clock.nowIso(),
            )
            call.respond(HttpStatusCode.NoContent)
        }
        post(ApiPath.GIT_INTEGRATIONS_DELETE) {
            call.requireApiAdmin()
            val req = call.receive<GitTokenDeleteRequestDto>()
            val removed = credentials.delete(req.host)
            if (removed) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound)
        }
        post(ApiPath.GIT_INTEGRATIONS_SSH_KEYGEN) {
            call.requireApiAdmin()
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
    // v0.64.0 — Android shared/ 의 ComponentStatus 상수는 모두 소문자. SSR 측은
    // ComponentStatus enum 자체로 비교하므로 wire emit 만 소문자.
    status = status.name.lowercase(),
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
