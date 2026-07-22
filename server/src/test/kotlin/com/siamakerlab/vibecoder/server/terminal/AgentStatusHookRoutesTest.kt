package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class AgentStatusHookRoutesTest {
    @Test
    fun `maps claude prompt submit to running thinking status`() {
        val status = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"UserPromptSubmit"}"""),
            nowMs = 10L,
        )

        status?.projectId shouldBe "app"
        status?.provider shouldBe AgentProvider.CLAUDE
        status?.sessionId shouldBe "s1"
        status?.state shouldBe AgentState.RUNNING
        status?.activity shouldBe AgentActivity.THINKING
        status?.turnStartedAt shouldBe 10L
    }

    @Test
    fun `maps claude tool and permission events to detailed status`() {
        val tool = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"PreToolUse","tool_name":"Bash"}"""),
            nowMs = 20L,
        )
        val permission = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"PermissionRequest","tool_name":"Edit","message":"Approve edit"}"""),
            nowMs = 30L,
        )

        tool?.state shouldBe AgentState.RUNNING
        tool?.activity shouldBe AgentActivity.TOOL_EXECUTION
        tool?.currentTool shouldBe "Bash"
        permission?.state shouldBe AgentState.WAITING_APPROVAL
        permission?.currentTool shouldBe "Edit"
        permission?.message shouldBe "Approve edit"
    }

    @Test
    fun `maps claude stop events to idle or error`() {
        val stop = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"Stop"}"""),
            nowMs = 40L,
        )
        val failure = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"StopFailure","error":"quota exceeded"}"""),
            nowMs = 50L,
        )

        stop?.state shouldBe AgentState.IDLE
        failure?.state shouldBe AgentState.ERROR
        failure?.error shouldBe "quota exceeded"
    }

    @Test
    fun `maps notification types without sticky generic waiting state`() {
        val permission = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"Notification","notification_type":"permission_prompt","message":"Approve"}"""),
            nowMs = 55L,
        )
        val idle = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"Notification","notification_type":"idle_prompt","message":"Done"}"""),
            nowMs = 56L,
        )
        val generic = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"Notification","notification_type":"auth_success","message":"Signed in"}"""),
            nowMs = 57L,
        )
        val agentWaiting = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"Notification","notification_type":"agent_needs_input","message":"Agent is waiting"}"""),
            nowMs = 58L,
        )

        permission?.state shouldBe AgentState.WAITING_APPROVAL
        idle?.state shouldBe AgentState.IDLE
        generic shouldBe null
        agentWaiting?.state shouldBe AgentState.RUNNING
        agentWaiting?.activity shouldBe AgentActivity.THINKING
    }

    @Test
    fun `maps tool failure interrupt to stopped`() {
        val interrupted = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"PostToolUseFailure","tool_name":"Bash","error":"Interrupted","is_interrupt":true}"""),
            nowMs = 59L,
        )
        val failed = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"PostToolUseFailure","tool_name":"Bash","error":"exit 1","is_interrupt":false}"""),
            nowMs = 60L,
        )

        interrupted?.state shouldBe AgentState.INTERRUPTED
        interrupted?.currentTool shouldBe "Bash"
        failed?.state shouldBe AgentState.RUNNING
        failed?.activity shouldBe AgentActivity.THINKING
    }

    @Test
    fun `store rejects late events from a previous session`() {
        val store = AgentStatusStore()

        store.update(
            AgentStatusSnapshot(
                projectId = "app",
                provider = AgentProvider.CLAUDE,
                sessionId = "old",
                state = AgentState.RUNNING,
                lastEventAt = 1L,
            ),
        )
        store.update(
            AgentStatusSnapshot(
                projectId = "app",
                provider = AgentProvider.CLAUDE,
                sessionId = "new",
                state = AgentState.STARTING,
                lastEventAt = 2L,
            ),
        )
        val rejected = store.update(
            AgentStatusSnapshot(
                projectId = "app",
                provider = AgentProvider.CLAUDE,
                sessionId = "old",
                state = AgentState.ERROR,
                lastEventAt = 3L,
            ),
        )

        rejected shouldBe null
        store.get("app", AgentProvider.CLAUDE)?.sessionId shouldBe "new"
        store.get("app", AgentProvider.CLAUDE)?.state shouldBe AgentState.STARTING
    }

    @Test
    fun `session end is idle and interrupted maps to stopped legacy state`() {
        val ended = mapClaudeHookEvent(
            projectId = "app",
            sessionId = "s1",
            event = json("""{"hook_event_name":"SessionEnd"}"""),
            nowMs = 61L,
        )

        ended?.state shouldBe AgentState.IDLE
        ended?.legacyProjectState shouldBe ProjectState.READY
        AgentStatusSnapshot(
            projectId = "app",
            provider = AgentProvider.CLAUDE,
            state = AgentState.INTERRUPTED,
        ).legacyProjectState shouldBe ProjectState.STOPPED
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
