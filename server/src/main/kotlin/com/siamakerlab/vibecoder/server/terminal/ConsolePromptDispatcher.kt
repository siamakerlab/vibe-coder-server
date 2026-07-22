package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.shared.dto.ConsoleTuiPromptAcceptedDto
import com.siamakerlab.vibecoder.shared.dto.PromptRequestDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAX_CONSOLE_TUI_PROMPT_BYTES = 100_000

class ConsolePromptDispatcher(
    private val projects: ProjectService,
    private val agentRouter: AgentRouter,
    private val manager: ConsoleTuiSessionManager,
    private val conversationRepo: ConversationTurnRepository,
    private val historyIngest: ConsoleTuiHistoryIngestService,
) {
    suspend fun send(
        ownerUserId: String?,
        projectId: String,
        text: String,
        requestedProvider: String? = null,
        source: String,
        interruptFirst: Boolean = false,
    ): ConsoleTuiPromptAcceptedDto {
        val provider = selectedProvider(projectId, requestedProvider)
        val session = manager.ensure(
            ownerUserId = ownerUserId,
            projectId = projectId,
            provider = provider,
            workdir = projects.sourcePathOrThrow(projectId),
        )
        historyIngest.prepare(projectId, provider, session.workdir)
        acceptProviderStartupPrompts(provider, session)
        if (interruptFirst) session.interrupt()
        val turn = conversationRepo.insert(
            projectId = projectId,
            provider = provider.id,
            sessionId = session.id,
            role = "user",
            content = text,
            raw = """{"source":"$source","sessionId":"${session.id}","interruptFirst":$interruptFirst}""",
        )
        session.markPromptSent(turn.id)
        manager.publishState(projectId, provider, ProjectState.RESPONDING)
        if (!session.pastePrompt(text)) {
            manager.publishState(projectId, provider, ProjectState.ERROR)
            throw ApiException.localized(
                500,
                "console_tui_write_failed",
                messageKey = "api.consoleTui.writeFailed",
            )
        }
        historyIngest.scheduleIngest(projectId, provider, session.workdir, session.id) {
            manager.markAssistantImported(session.id)
        }
        return ConsoleTuiPromptAcceptedDto(
            sessionId = session.id,
            provider = provider.id,
            turnId = turn.id,
            turnIdx = turn.turnIdx,
        )
    }

    suspend fun compact(
        ownerUserId: String?,
        projectId: String,
        requestedProvider: String? = null,
    ): ConsoleTuiPromptAcceptedDto =
        send(ownerUserId, projectId, "/compact", requestedProvider, source = "console_tui_compact")

    fun cancel(ownerUserId: String?, projectId: String, requestedProvider: String? = null): Boolean {
        val provider = selectedProvider(projectId, requestedProvider)
        val session = manager.find(ownerUserId, projectId, provider) ?: return false
        if (!session.interrupt()) return false
        manager.publishState(projectId, provider, ProjectState.READY)
        return true
    }

    fun recordDirectTerminalPrompt(
        projectId: String,
        provider: AgentProvider,
        sessionId: String,
        text: String,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        val turn = conversationRepo.insert(
            projectId = projectId,
            provider = provider.id,
            sessionId = sessionId,
            role = "user",
            content = normalized,
            raw = """{"source":"console_tui_direct","sessionId":"$sessionId"}""",
        )
        manager.get(sessionId)?.markPromptSent(turn.id)
        manager.publishState(projectId, provider, ProjectState.RESPONDING)
    }

    fun selectedProvider(projectId: String, requested: String? = null): AgentProvider {
        val parsed = AgentProvider.parse(requested)
        return if (parsed != null && agentRouter.availableProviders().contains(parsed)) {
            parsed
        } else {
            agentRouter.providerFor(projectId)
        }
    }

    private suspend fun acceptProviderStartupPrompts(provider: AgentProvider, session: ConsoleTuiSession) {
        if (provider != AgentProvider.CODEX) return
        val trustScrollbackLength = session.scrollbackLength()
        if (session.acceptCodexTrustPromptIfVisible()) {
            withTimeoutOrNull(2_000) {
                while (session.isAlive() && session.scrollbackLength() <= trustScrollbackLength + 16) {
                    delay(100)
                }
            }
        }
    }
}

internal fun validatedConsoleTuiPromptText(body: PromptRequestDto): String {
    val text = body.text.trim()
    if (text.isEmpty()) {
        throw ApiException.localized(400, "bad_request", messageKey = "api.console.textRequired")
    }
    val byteSize = text.toByteArray(Charsets.UTF_8).size
    if (byteSize > MAX_CONSOLE_TUI_PROMPT_BYTES) {
        throw ApiException.localized(
            400,
            "prompt_too_large",
            messageKey = "api.console.promptTooLarge",
            args = listOf(MAX_CONSOLE_TUI_PROMPT_BYTES, byteSize),
        )
    }
    if (!body.images.isNullOrEmpty()) {
        throw ApiException.localized(
            400,
            "images_unsupported",
            messageKey = "api.consoleTui.imagesUnsupported",
        )
    }
    return text
}
