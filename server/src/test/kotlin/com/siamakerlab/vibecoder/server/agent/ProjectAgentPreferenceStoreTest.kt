package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class ProjectAgentPreferenceStoreTest {
    private val workspace = WorkspacePath(Files.createTempDirectory("agent-pref-test"))
    private val store = ProjectAgentPreferenceStore(workspace)

    @Test fun `defaults to claude when no preference exists`() {
        store.get("p1") shouldBe AgentProvider.CLAUDE
    }

    @Test fun `stores non-default provider independently`() {
        store.set("p1", AgentProvider.CODEX)

        store.get("p1") shouldBe AgentProvider.CODEX
        store.get("p2") shouldBe AgentProvider.CLAUDE
    }

    @Test fun `setting claude removes override`() {
        store.set("p1", AgentProvider.CODEX)
        store.set("p1", AgentProvider.CLAUDE)

        store.get("p1") shouldBe AgentProvider.CLAUDE
    }
}

