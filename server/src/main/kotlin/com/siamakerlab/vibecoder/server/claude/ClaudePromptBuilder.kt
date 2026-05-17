package com.siamakerlab.vibecoder.server.claude

object ClaudePromptBuilder {
    fun wrap(userPrompt: String): String = """
        You are working inside an Android project managed by Vibe Coder.
        Follow the project's CLAUDE.md rules.

        User request:
        $userPrompt

        Requirements:
        - Preserve the existing architecture.
        - Avoid unnecessary dependencies.
        - Keep changes focused and minimal.
        - Summarize modified files.
        - If you run a build, summarize the result.
        - If the build fails, explain the likely cause and next step.
    """.trimIndent()
}
