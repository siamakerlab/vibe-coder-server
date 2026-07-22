package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.build.BuildToolchain
import com.siamakerlab.vibecoder.server.build.BuildVariant
import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Path

object KotlinPlatformEngine : ProjectPlatformEngine {
    override val projectType: String = ProjectTypes.KOTLIN
    override val displayName: String = "Kotlin"

    override fun renderProjectMemory(info: ClaudeMdTemplate.ProjectInfo): PlatformProjectMemory =
        PlatformProjectMemory(content = ClaudeMdTemplate.renderKotlinProjectMemory(info))

    override fun uiCapabilities(): PlatformUiCapabilities = PlatformUiCapabilities(
        projectType = projectType,
        displayName = displayName,
        badgeColor = "#7F52FF",
        showPlayStoreLink = true,
    )

    override fun matchesProject(root: Path): Boolean = AndroidProjectSignals.matchesGradleProject(root)

    override fun detectPackageName(root: Path): String? = AndroidProjectSignals.detectPackageName(root)

    override fun detectModuleName(root: Path): String? = AndroidProjectSignals.detectModuleName(root)

    override fun buildToolchain(toolchains: PlatformBuildToolchains): BuildToolchain = toolchains.gradle

    override fun supportsBuildVariant(variant: BuildVariant, projectRoot: Path?): Boolean = !variant.isIos

    override fun resolveVersionName(projectRoot: Path, moduleName: String): String? =
        AndroidProjectSignals.resolveGradleVersionName(projectRoot, moduleName)

    override fun resolveAppIcon(projectRoot: Path, moduleName: String): Path? =
        AndroidProjectSignals.resolveAndroidAppIcon(projectRoot, moduleName, flutter = false)

    override fun toolingProfile(): PlatformToolingProfile = PlatformToolingProfile(
        projectType = projectType,
        defaultMcp = listOf("context7", "memory", "sequentialthinking", "time"),
        conditionalMcp = listOf("github", "gitea"),
        optInMcp = listOf("app-publish"),
        defaultSkills = listOf(
            "kotlin-expert",
            "jetpack-compose-expert",
            "compose-architecture-expert",
            "material3-expert",
        ),
        conditionalSkills = listOf("qa-scenario-writer"),
        optInSkills = listOf("release-publish"),
        defaultAgents = listOf("kotlin-architect", "compose-implementer", "gradle-build-fixer"),
        conditionalAgents = listOf("kotlin-qa-agent"),
        optInAgents = listOf("play-release-agent"),
        forbiddenTools = listOf("mobile-mcp", "xcode-build-debugger", "iphone-*", "swiftui-*", "flutter-*"),
    )
}
