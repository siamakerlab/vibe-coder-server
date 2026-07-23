package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireApiAdmin
import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.ConfigPersistence
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.IosAgentConfigDto
import com.siamakerlab.vibecoder.shared.dto.IosAgentConnectRequestDto
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectKeySaveRequestDto
import com.siamakerlab.vibecoder.shared.dto.IosKeychainImportRequestDto
import com.siamakerlab.vibecoder.shared.dto.IosSwiftToolsInstallRequestDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.iosRoutes(
    service: IosPreflightService = IosPreflightService(),
    simulatorInventory: IosSimulatorInventoryService = IosSimulatorInventoryService(),
    simulatorControl: IosSimulatorControlService = IosSimulatorControlService(),
    simulatorRun: IosSimulatorRunService = IosSimulatorRunService(),
    simulatorLogs: IosSimulatorLogService = IosSimulatorLogService(),
    signingStatus: IosSigningStatusService = IosSigningStatusService(),
    keychainImport: IosKeychainImportService = IosKeychainImportService(),
    swiftToolsInstall: IosSwiftToolsInstallService = IosSwiftToolsInstallService(),
    appStoreConnectKeys: AppStoreConnectKeyStore? = null,
    appStoreConnectDiagnostics: AppStoreConnectDiagnosticService? =
        appStoreConnectKeys?.let { AppStoreConnectDiagnosticService(it) },
    projects: ProjectService? = null,
    onConfigSaved: ((ServerConfig) -> Unit)? = null,
    bootstrap: IosAgentBootstrapService = IosAgentBootstrapService(onConfigSaved = onConfigSaved),
) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.IOS_PREFLIGHT) {
            call.respond(service.check())
        }

        get(ApiPath.IOS_SIMULATORS) {
            call.respond(simulatorInventory.list())
        }

        post(ApiPath.IOS_SIMULATOR_BOOT_ROUTE) {
            call.respond(simulatorControl.boot(call.parameters["udid"].orEmpty()))
        }

        post(ApiPath.IOS_SIMULATOR_SHUTDOWN_ROUTE) {
            call.respond(simulatorControl.shutdown(call.parameters["udid"].orEmpty()))
        }

        post(ApiPath.IOS_PROJECT_SIMULATOR_RUN_ROUTE) {
            val projectService = projects ?: error("projects service unavailable")
            val row = projectService.rowOrThrow(call.parameters["projectId"].orEmpty())
            if (ProjectTypes.normalize(row.projectType) != ProjectTypes.IPHONE) {
                throw ApiException.localized(409, "iphone_project_required", messageKey = "api.build.iphoneTargetRequired")
            }
            call.respond(
                simulatorRun.run(
                    projectId = row.id,
                    projectRoot = java.nio.file.Path.of(row.sourcePath),
                    bundleId = row.packageName,
                    udid = call.parameters["udid"].orEmpty(),
                )
            )
        }

        // v1.167.0 — 부팅된 시뮬레이터 현재 화면만 재캡처.
        post(ApiPath.IOS_PROJECT_SIMULATOR_SCREENSHOT_ROUTE) {
            val projectService = projects ?: error("projects service unavailable")
            val row = projectService.rowOrThrow(call.parameters["projectId"].orEmpty())
            if (ProjectTypes.normalize(row.projectType) != ProjectTypes.IPHONE) {
                throw ApiException.localized(409, "iphone_project_required", messageKey = "api.build.iphoneTargetRequired")
            }
            call.respond(
                simulatorRun.capture(
                    projectId = row.id,
                    projectRoot = java.nio.file.Path.of(row.sourcePath),
                    udid = call.parameters["udid"].orEmpty(),
                )
            )
        }

        get(ApiPath.IOS_PROJECT_SIMULATOR_LOGS_ROUTE) {
            val projectService = projects ?: error("projects service unavailable")
            val row = projectService.rowOrThrow(call.parameters["projectId"].orEmpty())
            if (ProjectTypes.normalize(row.projectType) != ProjectTypes.IPHONE) {
                throw ApiException.localized(409, "iphone_project_required", messageKey = "api.build.iphoneTargetRequired")
            }
            call.respond(
                simulatorLogs.recent(
                    projectId = row.id,
                    bundleId = row.packageName,
                    udid = call.parameters["udid"].orEmpty(),
                )
            )
        }

        get(ApiPath.IOS_PROJECT_SIGNING_STATUS_ROUTE) {
            val projectService = projects ?: error("projects service unavailable")
            val row = projectService.rowOrThrow(call.parameters["projectId"].orEmpty())
            if (ProjectTypes.normalize(row.projectType) != ProjectTypes.IPHONE) {
                throw ApiException.localized(409, "iphone_project_required", messageKey = "api.build.iphoneTargetRequired")
            }
            call.respond(
                signingStatus.check(
                    projectId = row.id,
                    projectRoot = java.nio.file.Path.of(row.sourcePath),
                    packageName = row.packageName,
                )
            )
        }

        get(ApiPath.IOS_AGENT_CONFIG) {
            call.requireApiAdmin()
            call.respond(ConfigHolder.current.ios.agent.toDto())
        }

        post(ApiPath.IOS_AGENT_CONFIG) {
            call.requireApiAdmin()
            val req = call.receive<IosAgentConfigDto>().normalized()
            validate(req)
            val cur = ConfigHolder.current
            val newConfig = cur.copy(ios = cur.ios.copy(agent = req.toConfig()))
            runCatching { ConfigPersistence.save(newConfig) }
                .getOrElse { e ->
                    throw ApiException.localized(500, "config_save_failed",
                        messageKey = "flash.settings.saveFailed", args = listOf(e.message ?: ""))
                }
            onConfigSaved?.invoke(newConfig) ?: ConfigHolder.update(newConfig)
            call.respond(req)
        }

        // v1.167.0 — 비밀번호 원클릭 부트스트랩(sshpass). 비번은 1회 사용 후 미저장.
        post(ApiPath.IOS_AGENT_CONNECT) {
            call.requireApiAdmin()
            call.respond(bootstrap.connect(call.receive<IosAgentConnectRequestDto>()))
        }

        get(ApiPath.IOS_APP_STORE_CONNECT_KEY) {
            call.requireApiAdmin()
            val store = appStoreConnectKeys ?: error("app store connect key store unavailable")
            call.respond(store.get())
        }

        get(ApiPath.IOS_APP_STORE_CONNECT_DIAGNOSTICS) {
            call.requireApiAdmin()
            val diagnostics = appStoreConnectDiagnostics ?: error("app store connect diagnostics unavailable")
            call.respond(diagnostics.diagnose(call.request.queryParameters["bundleId"]))
        }

        post(ApiPath.IOS_APP_STORE_CONNECT_KEY) {
            call.requireApiAdmin()
            val store = appStoreConnectKeys ?: error("app store connect key store unavailable")
            val req = call.receive<IosAppStoreConnectKeySaveRequestDto>()
            call.respond(store.save(req))
        }

        post(ApiPath.IOS_KEYCHAIN_IMPORT) {
            call.requireApiAdmin()
            val req = call.receive<IosKeychainImportRequestDto>()
            call.respond(keychainImport.importCertificate(req))
        }

        post(ApiPath.IOS_SWIFT_TOOLS_INSTALL) {
            call.requireApiAdmin()
            val req = call.receive<IosSwiftToolsInstallRequestDto>()
            call.respond(swiftToolsInstall.install(req))
        }
    }
}

private fun com.siamakerlab.vibecoder.server.config.IosAgentSection.toDto(): IosAgentConfigDto =
    IosAgentConfigDto(
        enabled = enabled,
        mode = mode,
        host = host,
        port = port,
        user = user,
        workspaceRoot = workspaceRoot,
        xcodePath = xcodePath,
    )

private fun IosAgentConfigDto.normalized(): IosAgentConfigDto =
    copy(
        mode = when (mode.trim().lowercase()) {
            "ssh", "remote" -> "ssh"
            else -> "local"
        },
        host = host.trim(),
        port = port.coerceIn(1, 65535),
        user = user.trim(),
        workspaceRoot = workspaceRoot.trim(),
        xcodePath = xcodePath.trim().ifBlank { "auto" },
    )

private fun IosAgentConfigDto.toConfig(): com.siamakerlab.vibecoder.server.config.IosAgentSection =
    com.siamakerlab.vibecoder.server.config.IosAgentSection(
        enabled = enabled,
        mode = mode,
        host = host,
        port = port,
        user = user,
        workspaceRoot = workspaceRoot,
        xcodePath = xcodePath,
    )

private fun validate(req: IosAgentConfigDto) {
    if (req.mode !in setOf("local", "ssh")) {
        throw ApiException.localized(400, "invalid_ios_agent_mode", messageKey = "api.ios.invalidAgentMode")
    }
    if (req.enabled && req.mode == "ssh") {
        if (req.host.isBlank()) {
            throw ApiException.localized(400, "ios_agent_host_required", messageKey = "api.ios.agentHostRequired")
        }
        if (req.user.isBlank()) {
            throw ApiException.localized(400, "ios_agent_user_required", messageKey = "api.ios.agentUserRequired")
        }
        if (req.workspaceRoot.isBlank()) {
            throw ApiException.localized(400, "ios_agent_workspace_required", messageKey = "api.ios.agentWorkspaceRequired")
        }
    }
}
