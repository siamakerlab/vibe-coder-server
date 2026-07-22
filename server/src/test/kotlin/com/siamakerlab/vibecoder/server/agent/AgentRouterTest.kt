package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.PromptImageDto
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AgentRouterTest {
    private val root: Path = Files.createTempDirectory("agent-router-test")

    @After
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `installTurnListeners fans out to Claude Codex and OpenCode managers`() {
        val managers = AgentProvider.entries.map { RecordingManager(it) }
        val router = AgentRouter(ProjectAgentPreferenceStore(WorkspacePath(root)), managers)
        val doneEvents = mutableListOf<String>()
        val interruptEvents = mutableListOf<String>()

        router.installTurnListeners(
            done = { projectId, reason -> doneEvents += "$projectId:$reason" },
            interrupt = { projectId, reason -> interruptEvents += "$projectId:$reason" },
        )

        runBlocking {
            managers.forEach { manager ->
                manager.turnDoneListener?.invoke("p-${manager.provider.id}", "done")
                manager.turnInterruptListener?.invoke("p-${manager.provider.id}", "cancelled")
            }
        }

        doneEvents shouldContainExactlyInAnyOrder listOf("p-claude:done", "p-codex:done", "p-opencode:done")
        interruptEvents shouldContainExactlyInAnyOrder listOf("p-claude:cancelled", "p-codex:cancelled", "p-opencode:cancelled")
    }

    private class RecordingManager(
        override val provider: AgentProvider,
    ) : AgentSessionManager {
        override var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null
        override var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null

        override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) = Unit
        override suspend fun startNew(projectId: String) = Unit
        override suspend fun cancelTurn(projectId: String) = Unit
        override fun isAlive(projectId: String): Boolean = true
        override fun isBusy(projectId: String): Boolean = false
        override fun currentSessionId(projectId: String): String? = null
    }
}
