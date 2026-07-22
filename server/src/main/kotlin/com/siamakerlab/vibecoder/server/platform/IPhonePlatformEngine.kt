package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.build.BuildToolchain
import com.siamakerlab.vibecoder.server.build.BuildVariant
import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import com.siamakerlab.vibecoder.server.projects.ProjectScaffolder
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Files
import java.nio.file.Path

object IPhonePlatformEngine : ProjectPlatformEngine {
    override val projectType: String = ProjectTypes.IPHONE
    override val displayName: String = "iPhone"

    override fun renderProjectMemory(info: ClaudeMdTemplate.ProjectInfo): PlatformProjectMemory =
        PlatformProjectMemory(content = ClaudeMdTemplate.renderIPhoneProjectMemory(info))

    override fun uiCapabilities(): PlatformUiCapabilities = PlatformUiCapabilities(
        projectType = projectType,
        displayName = displayName,
        badgeColor = "#111827",
        showIosBuildSettings = true,
        showIosSimulator = true,
        showIPhoneQuickPrompts = true,
    )

    override fun matchesProject(root: Path): Boolean = hasIPhoneProjectMarker(root)

    override fun scaffoldStarter(projectRoot: Path, appName: String, packageName: String): Int =
        ProjectScaffolder.scaffoldIPhoneStarter(projectRoot, appName, packageName)

    override fun buildToolchain(toolchains: PlatformBuildToolchains): BuildToolchain = toolchains.ios

    override fun supportsBuildVariant(variant: BuildVariant, projectRoot: Path?): Boolean = variant.isIos

    override fun toolingProfile(): PlatformToolingProfile = PlatformToolingProfile(
        projectType = projectType,
        defaultMcp = listOf("context7", "memory", "sequentialthinking", "time", "playwright"),
        conditionalMcp = listOf("mobile-mcp"),
        optInMcp = listOf("app-store-connect", "fastlane", "app-publish"),
        defaultSkills = listOf("swiftui-iphone-expert", "xcode-build-debugger"),
        conditionalSkills = listOf("ios-simulator-qa"),
        optInSkills = listOf("apple-signing-release", "app-store-connect-release"),
        defaultAgents = listOf("iphone-architect", "swiftui-implementer", "xcode-build-fixer"),
        conditionalAgents = listOf("simulator-qa-agent", "apple-signing-agent"),
        optInAgents = listOf("testflight-release-agent"),
        forbiddenTools = listOf("android-keystore-play-prompt", "kotlin-gradle-module-prompt", "flutter-pubspec-prompt"),
    )

    private fun hasIPhoneProjectMarker(root: Path): Boolean =
        runCatching {
            if (!Files.isDirectory(root)) return@runCatching false
            Files.walk(root, 5).use { stream ->
                for (path in stream) {
                    val name = path.fileName?.toString() ?: continue
                    if (Files.isDirectory(path) && (name.endsWith(".xcodeproj") || name.endsWith(".xcworkspace"))) {
                        return@use true
                    }
                    if (Files.isRegularFile(path) && name == "project.pbxproj") {
                        return@use true
                    }
                    if (Files.isRegularFile(path) && name == "Package.swift" && isIPhoneSwiftPackage(path)) {
                        return@use true
                    }
                    if (Files.isRegularFile(path) && name == "Info.plist" && isIPhoneInfoPlist(path)) {
                        return@use true
                    }
                }
                false
            }
        }.getOrDefault(false)

    private fun isIPhoneSwiftPackage(path: Path): Boolean {
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return false
        val lower = text.lowercase()
        return ".ios(" in lower || "platforms:" in lower && "ios" in lower || "swiftui" in lower
    }

    private fun isIPhoneInfoPlist(path: Path): Boolean {
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return false
        return "CFBundleIdentifier" in text &&
            ("UIApplicationSceneManifest" in text || "UILaunchScreen" in text || "UIDeviceFamily" in text)
    }
}
