package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

class ProjectAgentPreferenceStore(
    private val workspace: WorkspacePath,
) {
    fun get(projectId: String): AgentProvider {
        val f = file(projectId)
        if (!f.exists()) return AgentProvider.CLAUDE
        return AgentProvider.parse(runCatching { f.readText() }.getOrNull()) ?: AgentProvider.CLAUDE
    }

    fun set(projectId: String, provider: AgentProvider) {
        val f = file(projectId)
        runCatching {
            if (provider == AgentProvider.CLAUDE) {
                f.deleteIfExists()
            } else {
                Files.createDirectories(f.parent)
                f.writeText(provider.id)
            }
        }.onFailure { log.warn(it) { "[$projectId] agent provider 저장 실패" } }
    }

    private fun file(projectId: String) =
        workspace.vibecoderDir(projectId).resolve("agent-provider")
}

