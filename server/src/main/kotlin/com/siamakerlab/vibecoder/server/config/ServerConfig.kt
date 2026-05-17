package com.siamakerlab.vibecoder.server.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val server: ServerSection,
    val workspace: WorkspaceSection,
    val security: SecuritySection,
    val claude: ClaudeSection,
    val build: BuildSection,
    val git: GitSection,
)

@Serializable
data class ServerSection(
    val name: String = "Vibe Coder Server",
    val host: String = "0.0.0.0",
    val port: Int = 17880,
    val version: String = "0.1.0",
)

@Serializable
data class WorkspaceSection(
    val root: String = "./vibe-coder-server-data/workspace",
    val maxUploadSizeMb: Long = 100,
    val artifactKeepCount: Int = 20,
    val uploadDeniedExtensions: List<String> = listOf("exe", "bat", "cmd", "ps1", "sh"),
)

@Serializable
data class SecuritySection(
    val pairingEnabled: Boolean = true,
    val pairingCodeExpireMinutes: Int = 10,
    val restrictToWorkspace: Boolean = true,
    val allowRawShell: Boolean = false,
)

@Serializable
data class ClaudeSection(
    val enabled: Boolean = true,
    val path: String = "auto",
    val timeoutMinutes: Int = 60,
    val autoBuildAfterTask: Boolean = false,
)

@Serializable
data class BuildSection(
    val timeoutMinutes: Int = 30,
    val defaultDebugTask: String = "assembleDebug",
)

@Serializable
data class GitSection(
    val enabled: Boolean = true,
    val path: String = "auto",
)
