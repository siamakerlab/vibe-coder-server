package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.build.BuildToolchain
import com.siamakerlab.vibecoder.server.build.BuildVariant
import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import java.nio.file.Path

interface ProjectPlatformEngine {
    val projectType: String
    val displayName: String

    fun matchesProject(root: Path): Boolean = false

    fun detectPackageName(root: Path): String? = null

    fun detectModuleName(root: Path): String? = null

    fun scaffoldStarter(projectRoot: Path, appName: String, packageName: String): Int = 0

    fun renderProjectMemory(info: ClaudeMdTemplate.ProjectInfo): PlatformProjectMemory

    fun uiCapabilities(): PlatformUiCapabilities

    fun toolingProfile(): PlatformToolingProfile

    fun buildToolchain(toolchains: PlatformBuildToolchains): BuildToolchain

    fun supportsBuildVariant(variant: BuildVariant, projectRoot: Path? = null): Boolean

    fun resolveVersionName(projectRoot: Path, moduleName: String): String? = null

    fun resolveAppIcon(projectRoot: Path, moduleName: String): Path? = null
}
