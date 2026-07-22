package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Path

class PlatformEngineRegistry(
    private val engines: List<ProjectPlatformEngine> = defaultEngines(),
) {
    private val byType = engines.associateBy { ProjectTypes.normalize(it.projectType) }

    fun forType(projectType: String?): ProjectPlatformEngine =
        byType[ProjectTypes.normalize(projectType)] ?: byType.getValue(ProjectTypes.KOTLIN)

    fun all(): List<ProjectPlatformEngine> = byType.values.toList()

    fun detectProjectType(root: Path): String? =
        engines.firstOrNull { it.matchesProject(root) }?.projectType

    fun commonDefaultMcpIds(): Set<String> =
        engines
            .map { it.toolingProfile().defaultMcp.toSet() }
            .reduceOrNull { acc, ids -> acc intersect ids }
            .orEmpty()

    fun allToolingMcpIds(): Set<String> =
        engines.flatMap { engine ->
            engine.toolingProfile().run { defaultMcp + conditionalMcp + optInMcp }
        }.toSet()

    fun allToolingSkillIds(): Set<String> =
        engines.flatMap { engine ->
            engine.toolingProfile().run { defaultSkills + conditionalSkills + optInSkills }
        }.toSet()

    fun allToolingAgentIds(): Set<String> =
        engines.flatMap { engine ->
            engine.toolingProfile().run { defaultAgents + conditionalAgents + optInAgents }
        }.toSet()

    companion object {
        val default: PlatformEngineRegistry = PlatformEngineRegistry()

        fun defaultEngines(): List<ProjectPlatformEngine> = listOf(
            FlutterPlatformEngine,
            IPhonePlatformEngine,
            KotlinPlatformEngine,
        )
    }
}
