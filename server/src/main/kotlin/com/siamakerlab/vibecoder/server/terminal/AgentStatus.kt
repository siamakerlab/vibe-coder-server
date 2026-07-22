package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class AgentState(val wire: String) {
    STARTING("starting"),
    IDLE("idle"),
    RUNNING("running"),
    WAITING_INPUT("waiting_input"),
    WAITING_APPROVAL("waiting_approval"),
    COMPLETED("completed"),
    ERROR("error"),
    INTERRUPTED("interrupted"),
    DISCONNECTED("disconnected"),
}

enum class AgentActivity(val wire: String) {
    THINKING("thinking"),
    TOOL_EXECUTION("tool_execution"),
    EDITING("editing"),
    COMMAND_EXECUTION("command_execution"),
    COMPACTING("compacting"),
    RESPONDING("responding"),
}

data class AgentStatusSnapshot(
    val projectId: String,
    val provider: AgentProvider,
    val sessionId: String? = null,
    val state: AgentState,
    val activity: AgentActivity? = null,
    val currentTool: String? = null,
    val message: String? = null,
    val error: String? = null,
    val pid: Long? = null,
    val lastEventAt: Long = Instant.now().toEpochMilli(),
    val lastOutputAt: Long = 0,
    val turnStartedAt: Long? = null,
) {
    val legacyProjectState: ProjectState
        get() = when (state) {
            AgentState.STARTING -> ProjectState.WAITING
            AgentState.IDLE, AgentState.COMPLETED -> ProjectState.READY
            AgentState.RUNNING -> ProjectState.RESPONDING
            AgentState.WAITING_INPUT, AgentState.WAITING_APPROVAL -> ProjectState.WAITING
            AgentState.ERROR -> ProjectState.ERROR
            AgentState.INTERRUPTED -> ProjectState.STOPPED
            AgentState.DISCONNECTED -> ProjectState.READY
        }
}

class AgentStatusStore {
    private val statuses = ConcurrentHashMap<Key, AgentStatusSnapshot>()

    fun update(snapshot: AgentStatusSnapshot): AgentStatusSnapshot? {
        val key = Key(snapshot.projectId, snapshot.provider)
        val previous = statuses[key]
        if (previous != null && previous.lastEventAt > snapshot.lastEventAt) return null
        if (previous != null &&
            previous.sessionId != null &&
            snapshot.sessionId != null &&
            previous.sessionId != snapshot.sessionId &&
            snapshot.state != AgentState.STARTING
        ) {
            return null
        }
        statuses[key] = snapshot
        return snapshot
    }

    fun get(projectId: String, provider: AgentProvider): AgentStatusSnapshot? =
        statuses[Key(projectId, provider)]

    private data class Key(val projectId: String, val provider: AgentProvider)
}

class AgentStatusBroadcaster(
    private val store: AgentStatusStore,
    private val hub: LogHub,
) {
    suspend fun publish(raw: AgentStatusSnapshot): AgentStatusSnapshot? {
        val snapshot = store.update(raw) ?: return null
        val frame: (Long) -> WsFrame = { seq ->
            WsFrame.AgentStatusChanged(
                projectId = snapshot.projectId,
                provider = snapshot.provider.id,
                sessionId = snapshot.sessionId,
                state = snapshot.state.wire,
                activity = snapshot.activity?.wire,
                currentTool = snapshot.currentTool,
                message = snapshot.message,
                error = snapshot.error,
                pid = snapshot.pid,
                lastEventAt = snapshot.lastEventAt,
                lastOutputAt = snapshot.lastOutputAt,
                turnStartedAt = snapshot.turnStartedAt,
                seq = seq,
            )
        }
        hub.emitConsole(LogHub.consoleTopic(snapshot.projectId, snapshot.provider.id), frame)
        hub.emitConsole("__projects__", frame)
        if (snapshot.provider != AgentProvider.CLAUDE) return snapshot
        val legacy = snapshot.legacyProjectState
        hub.emitConsole(LogHub.consoleTopic(snapshot.projectId, snapshot.provider.id)) { seq ->
            WsFrame.ConsoleBusyState(legacy.busy, seq, legacy.wire)
        }
        hub.emitConsole("__projects__") { seq ->
            WsFrame.ProjectBusyChanged(snapshot.projectId, legacy.busy, seq, legacy.wire)
        }
        return snapshot
    }
}
