package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import java.nio.file.Files
import java.nio.file.Path

class IosWorkspaceSyncService(
    private val config: IosAgentSection,
) {
    fun plan(projectId: String, localProjectRoot: Path, dryRun: Boolean = true): IosWorkspaceSyncPlan {
        val mode = config.mode.trim().lowercase()
        return when (mode) {
            "local" -> IosWorkspaceSyncPlan(
                mode = "local",
                required = false,
                localProjectRoot = localProjectRoot.normalize().toString(),
                remoteProjectRoot = localProjectRoot.normalize().toString(),
                command = emptyList(),
                dryRun = dryRun,
            )
            "ssh", "remote" -> sshPlan(projectId, localProjectRoot, dryRun)
            else -> throw IllegalArgumentException("unsupported ios agent mode: ${config.mode}")
        }
    }

    private fun sshPlan(projectId: String, localProjectRoot: Path, dryRun: Boolean): IosWorkspaceSyncPlan {
        require(config.enabled) { "ios agent must be enabled for ssh workspace sync" }
        require(SAFE_PROJECT_ID.matches(projectId)) { "unsafe project id for ios workspace sync: $projectId" }
        val sourceRoot = localProjectRoot.normalize()
        require(Files.isDirectory(sourceRoot)) { "local project root does not exist: $sourceRoot" }

        val host = config.host.trim()
        val user = config.user.trim()
        val port = config.port
        val workspaceRoot = config.workspaceRoot.trim().trimEnd('/')
        require(host.isNotEmpty()) { "ios agent host is required for ssh workspace sync" }
        require(user.isNotEmpty()) { "ios agent user is required for ssh workspace sync" }
        require(port in 1..65535) { "ios agent port must be between 1 and 65535" }
        require(workspaceRoot.startsWith("/")) { "ios agent workspaceRoot must be an absolute path" }

        val remoteProjectRoot = "$workspaceRoot/$projectId"
        val command = buildList {
            add("rsync")
            add("-az")
            add("--delete")
            IOS_SYNC_EXCLUDES.forEach { exclude ->
                add("--exclude")
                add(exclude)
            }
            if (dryRun) add("--dry-run")
            add("-e")
            add("ssh -p $port -o BatchMode=yes -o StrictHostKeyChecking=accept-new")
            add(sourceRoot.toString().trimEnd('/') + "/")
            add("$user@$host:${remoteProjectRoot.shellSingleQuoted()}/")
        }
        return IosWorkspaceSyncPlan(
            mode = "ssh",
            required = true,
            localProjectRoot = sourceRoot.toString(),
            remoteProjectRoot = remoteProjectRoot,
            command = command,
            dryRun = dryRun,
        )
    }

    companion object {
        private val SAFE_PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val IOS_SYNC_EXCLUDES = listOf(
            ".git",
            ".gradle",
            ".vibecoder",
            "build",
            "DerivedData",
            ".build",
        )
    }
}

data class IosWorkspaceSyncPlan(
    val mode: String,
    val required: Boolean,
    val localProjectRoot: String,
    val remoteProjectRoot: String,
    val command: List<String>,
    val dryRun: Boolean,
)
