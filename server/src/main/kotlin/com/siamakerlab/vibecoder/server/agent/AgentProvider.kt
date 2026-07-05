package com.siamakerlab.vibecoder.server.agent

import kotlinx.serialization.Serializable

@Serializable
enum class AgentProvider(val id: String, val displayName: String) {
    CLAUDE("claude", "Claude Code"),
    CODEX("codex", "Codex"),
    // v1.156.0 — z.ai coding plan 강제(GLM) 정책 반영: 표시명을 "GLM" 으로. id 는 "opencode" 유지
    // (wire/내부 식별자 — AgentRouter 의존, provider preference store, 파일명 등).
    OPENCODE("opencode", "GLM"),
    ;

    companion object {
        fun parse(raw: String?): AgentProvider? {
            val v = raw?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.id == v || it.name.lowercase() == v }
        }
    }
}

