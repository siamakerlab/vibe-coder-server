package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

@Serializable
data class IosPreflightDto(
    val checkedAt: String,
    val mode: String,
    val macAvailable: Boolean,
    val xcodeAvailable: Boolean,
    val simctlAvailable: Boolean,
    val simulatorUiEnabled: Boolean,
    val xcodeSelectPath: String? = null,
    val xcodebuildPath: String? = null,
    val xcodeVersion: String? = null,
    val iphoneOsSdkVersion: String? = null,
    val iphoneSimulatorSdkVersion: String? = null,
    val iphoneDeviceTypes: List<String> = emptyList(),
    val ipadDeviceTypes: List<String> = emptyList(),
    val codesigningIdentities: List<String> = emptyList(),
    val blockedReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosAgentConfigDto(
    val enabled: Boolean = false,
    val mode: String = "local",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val workspaceRoot: String = "",
    val xcodePath: String = "auto",
)

@Serializable
data class IosAppStoreConnectKeyDto(
    val configured: Boolean = false,
    val keyId: String = "",
    val issuerId: String = "",
    val privateKeyPresent: Boolean = false,
    val privateKeyPath: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class IosAppStoreConnectKeySaveRequestDto(
    val keyId: String,
    val issuerId: String,
    val privateKeyPem: String? = null,
)

@Serializable
data class IosAppStoreConnectDiagnosticDto(
    val checkedAt: String,
    val configured: Boolean = false,
    val authenticated: Boolean = false,
    val appsReachable: Boolean = false,
    val appCount: Int = 0,
    val bundleId: String? = null,
    val matchingAppId: String? = null,
    val matchingAppName: String? = null,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosKeychainImportRequestDto(
    val p12Path: String,
    val p12Password: String,
    val keychainName: String = "vibe-coder",
    val keychainPassword: String,
    val setAsDefaultSearchKeychain: Boolean = true,
)

@Serializable
data class IosKeychainImportDto(
    val checkedAt: String,
    val mode: String,
    val ok: Boolean,
    val keychainPath: String,
    val p12Path: String,
    val created: Boolean = false,
    val unlocked: Boolean = false,
    val imported: Boolean = false,
    val partitionListUpdated: Boolean = false,
    val searchListUpdated: Boolean = false,
    val codesigningIdentities: List<String> = emptyList(),
    val blockedReason: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosSwiftToolsInstallRequestDto(
    val installSwiftLint: Boolean = true,
    val installSwiftFormat: Boolean = true,
)

@Serializable
data class IosSwiftToolsInstallDto(
    val checkedAt: String,
    val mode: String,
    val ok: Boolean,
    val swiftLintInstalled: Boolean = false,
    val swiftLintVersion: String? = null,
    val swiftFormatInstalled: Boolean = false,
    val swiftFormatVersion: String? = null,
    val blockedReason: String? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosSigningProfileDto(
    val uuid: String,
    val name: String,
    val teamIds: List<String> = emptyList(),
    val bundleId: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    val matchingBundleId: Boolean = false,
    val matchingTeamId: Boolean = false,
)

@Serializable
data class IosSigningStatusDto(
    val checkedAt: String,
    val mode: String,
    val projectId: String,
    val bundleId: String,
    val teamId: String,
    val signingStyle: String,
    val codesigningIdentities: List<String> = emptyList(),
    val profiles: List<IosSigningProfileDto> = emptyList(),
    val ready: Boolean = false,
    val blockedReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosSimulatorDto(
    val udid: String,
    val name: String,
    val runtime: String,
    val deviceTypeIdentifier: String? = null,
    val state: String,
    val available: Boolean = true,
    val kind: String = "unknown",
)

@Serializable
data class IosSimulatorListDto(
    val checkedAt: String,
    val mode: String,
    val simulatorUiEnabled: Boolean,
    val devices: List<IosSimulatorDto> = emptyList(),
    val blockedReason: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class IosSimulatorActionDto(
    val checkedAt: String,
    val mode: String,
    val udid: String,
    val action: String,
    val ok: Boolean,
    val exitCode: Int,
    val message: String? = null,
    val blockedReason: String? = null,
)

@Serializable
data class IosSimulatorRunDto(
    val checkedAt: String,
    val mode: String,
    val projectId: String,
    val udid: String,
    val bundleId: String,
    val appPath: String? = null,
    val screenshotPath: String? = null,
    val installed: Boolean = false,
    val launched: Boolean = false,
    val screenshotCaptured: Boolean = false,
    val ok: Boolean = false,
    val blockedReason: String? = null,
    val message: String? = null,
)

@Serializable
data class IosSimulatorLogsDto(
    val checkedAt: String,
    val mode: String,
    val projectId: String,
    val udid: String,
    val bundleId: String,
    val lines: List<String> = emptyList(),
    val ok: Boolean = false,
    val blockedReason: String? = null,
    val message: String? = null,
)
