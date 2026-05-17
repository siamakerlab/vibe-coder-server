package com.siamakerlab.vibecoder.server.projects

object ClaudeMdTemplate {
    const val CONTENT = """# CLAUDE.md — Vibe Coder Android Project Rules

## Project Rules

- This is an Android project managed through Vibe Coder.
- Use Kotlin Android SDK.
- Prefer Jetpack Compose and Material 3 when UI changes are required.
- Keep architecture clean and maintainable.
- Use MVVM + Repository pattern when adding new features.
- Do not place business logic directly in Activity or Composable.
- Avoid unnecessary dependencies.
- Preserve existing package structure unless explicitly requested.
- Before finishing coding tasks, check for obvious build errors.

## Build Rules

- Use Gradle Wrapper only.
- On Windows, use gradlew.bat.
- On Linux/macOS, use ./gradlew.
- Debug build task is assembleDebug unless the project config says otherwise.

## Response Rules

- Summarize modified files.
- Summarize important implementation decisions.
- Mention whether build was executed.
- If build failed, explain the likely cause and next step.
"""
}
