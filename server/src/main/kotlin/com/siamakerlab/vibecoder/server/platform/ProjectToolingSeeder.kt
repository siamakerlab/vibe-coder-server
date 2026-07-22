package com.siamakerlab.vibecoder.server.platform

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists

private val toolingSeedLog = KotlinLogging.logger {}

/**
 * Seeds project-local Claude Code skills and agents from the selected platform engine profile.
 *
 * Global registries remain user-managed. Project creation gets only the selected engine defaults,
 * and existing files are never overwritten.
 */
object ProjectToolingSeeder {
    data class Result(
        val skillsCreated: Int,
        val agentsCreated: Int,
    ) {
        val totalCreated: Int get() = skillsCreated + agentsCreated
    }

    fun seedProjectDefaults(projectRoot: Path, profile: PlatformToolingProfile): Result {
        if (projectRoot.notExists()) return Result(0, 0)

        val skillsCreated = profile.defaultSkills.count { id ->
            val safe = sanitizeId(id) ?: return@count false
            val target = projectRoot.resolve(".claude").resolve("skills").resolve(safe).resolve("SKILL.md")
            writeIfMissing(target, skillBody(profile.projectType, safe))
        }
        val agentsCreated = profile.defaultAgents.count { id ->
            val safe = sanitizeId(id) ?: return@count false
            val target = projectRoot.resolve(".claude").resolve("agents").resolve("$safe.md")
            writeIfMissing(target, agentBody(profile.projectType, safe))
        }

        if (skillsCreated + agentsCreated > 0) {
            toolingSeedLog.info {
                "project tooling seeds created for ${profile.projectType}: skills=$skillsCreated agents=$agentsCreated root=$projectRoot"
            }
        }
        return Result(skillsCreated, agentsCreated)
    }

    private fun writeIfMissing(target: Path, body: String): Boolean {
        if (Files.exists(target)) return false
        target.parent?.createDirectories()
        return runCatching {
            Files.writeString(
                target,
                body,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            true
        }.getOrElse {
            if (Files.exists(target)) false else throw it
        }
    }

    private fun sanitizeId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        if (trimmed.startsWith(".")) return null
        if (!trimmed.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        return trimmed
    }

    private fun skillBody(projectType: String, id: String): String {
        val purpose = skillPurpose(id)
        return """
            # $id

            Use this skill for `$projectType` projects when work needs: $purpose.

            Stay inside this project's platform rules from `CLAUDE.md`. Do not pull in tooling,
            build commands, or release assumptions from another platform engine.
        """.trimIndent() + "\n"
    }

    private fun agentBody(projectType: String, id: String): String {
        val purpose = agentPurpose(id)
        return """
            ---
            name: $id
            description: Use for `$projectType` projects when the task needs $purpose.
            ---

            You are the `$id` agent for `$projectType` projects.

            Follow this project's local `CLAUDE.md` as the authority. Keep changes scoped to
            the selected platform engine, verify with the platform's native build/test tools,
            and avoid adding cross-platform assumptions unless the user explicitly asks.
        """.trimIndent() + "\n"
    }

    private fun skillPurpose(id: String): String = when (id) {
        "kotlin-expert" -> "idiomatic Kotlin, nullability, coroutines, and JVM API design"
        "jetpack-compose-expert" -> "Jetpack Compose implementation, recomposition safety, and state-driven UI"
        "compose-architecture-expert" -> "Compose screen architecture, state hoisting, and navigation structure"
        "material3-expert" -> "Material 3 component, density, color, and interaction decisions"
        "flutter-dart-expert" -> "Dart and Flutter implementation, package structure, and runtime behavior"
        "flutter-ui-architect" -> "Flutter screen composition, navigation, adaptive layout, and theming"
        "flutter-build-debugger" -> "Flutter analyze, test, Gradle bridge, and build failure diagnosis"
        "swiftui-iphone-expert" -> "SwiftUI, iPhone/iPad layout, state, navigation, and Apple platform conventions"
        "xcode-build-debugger" -> "Xcode project, scheme, signing, simulator, archive, and export failures"
        else -> id.replace('-', ' ')
    }

    private fun agentPurpose(id: String): String = when (id) {
        "kotlin-architect" -> "Kotlin/Android architecture planning and boundary decisions"
        "compose-implementer" -> "Jetpack Compose feature implementation and UI polish"
        "gradle-build-fixer" -> "Gradle, AGP, Kotlin plugin, and Android build failure repair"
        "flutter-architect" -> "Flutter app architecture and package/module decisions"
        "flutter-implementer" -> "Flutter feature implementation and widget/state updates"
        "flutter-build-fixer" -> "Flutter, Dart, Android bridge, and dependency build repair"
        "iphone-architect" -> "SwiftUI/iOS architecture, target layout, and signing boundaries"
        "swiftui-implementer" -> "SwiftUI feature implementation and iPhone/iPad UI refinement"
        "xcode-build-fixer" -> "Xcode, simulator, scheme, archive, export, and signing repair"
        else -> id.replace('-', ' ')
    }
}
