package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.shared.dto.ConsoleTuiPromptAcceptedDto

/**
 * Late-bound prompt injection entry point.
 *
 * Some services are constructed before the TUI manager/dispatcher. They still must not fall
 * back to provider JSON/session APIs, so they send through this adapter after ServerMain binds
 * the real [ConsolePromptDispatcher].
 */
class ConsolePromptSender {
    @Volatile
    private var dispatcher: ConsolePromptDispatcher? = null

    fun bind(dispatcher: ConsolePromptDispatcher) {
        this.dispatcher = dispatcher
    }

    suspend fun send(
        projectId: String,
        text: String,
        source: String,
        ownerUserId: String? = null,
        requestedProvider: String? = null,
        interruptFirst: Boolean = false,
    ): ConsoleTuiPromptAcceptedDto {
        val target = dispatcher ?: error("console TUI dispatcher unavailable")
        return target.send(
            ownerUserId = ownerUserId,
            projectId = projectId,
            text = text,
            requestedProvider = requestedProvider,
            source = source,
            interruptFirst = interruptFirst,
        )
    }
}
