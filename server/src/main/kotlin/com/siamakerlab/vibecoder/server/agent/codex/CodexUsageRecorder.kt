package com.siamakerlab.vibecoder.server.agent.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

data class CodexTurnUsage(
    val inputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningOutputTokens: Int? = null,
    val updatedAt: Instant = Instant.now(),
)

class CodexUsageRecorder {
    private val latest = AtomicReference<CodexTurnUsage?>(null)

    fun record(usage: JsonObject) {
        latest.set(
            CodexTurnUsage(
                inputTokens = usage.int("input_tokens"),
                cachedInputTokens = usage.int("cached_input_tokens"),
                outputTokens = usage.int("output_tokens"),
                reasoningOutputTokens = usage.int("reasoning_output_tokens"),
            )
        )
    }

    fun snapshot(): CodexTurnUsage? = latest.get()

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull
}
