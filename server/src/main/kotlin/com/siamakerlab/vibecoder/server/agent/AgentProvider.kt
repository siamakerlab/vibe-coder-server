package com.siamakerlab.vibecoder.server.agent

import kotlinx.serialization.Serializable

@Serializable
enum class AgentProvider(val id: String, val displayName: String) {
    CLAUDE("claude", "Claude Code"),
    CODEX("codex", "Codex"),
    OPENCODE("opencode", "OpenCode"),
    ;

    companion object {
        fun parse(raw: String?): AgentProvider? {
            val v = raw?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.id == v || it.name.lowercase() == v }
        }
    }
}

