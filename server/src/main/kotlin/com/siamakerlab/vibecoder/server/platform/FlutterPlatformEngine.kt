package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.build.BuildToolchain
import com.siamakerlab.vibecoder.server.build.BuildVariant
import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Files
import java.nio.file.Path

object FlutterPlatformEngine : ProjectPlatformEngine {
    override val projectType: String = ProjectTypes.FLUTTER
    override val displayName: String = "Flutter"

    override fun renderProjectMemory(info: ClaudeMdTemplate.ProjectInfo): PlatformProjectMemory =
        PlatformProjectMemory(content = ClaudeMdTemplate.renderFlutterProjectMemory(info))

    override fun uiCapabilities(): PlatformUiCapabilities = PlatformUiCapabilities(
        projectType = projectType,
        displayName = displayName,
        badgeColor = "#02569B",
        showPlayStoreLink = true,
    )

    override fun matchesProject(root: Path): Boolean = Files.isRegularFile(root.resolve("pubspec.yaml"))

    override fun detectPackageName(root: Path): String? = AndroidProjectSignals.detectPackageName(root)

    override fun detectModuleName(root: Path): String? = AndroidProjectSignals.detectModuleName(root)

    override fun buildToolchain(toolchains: PlatformBuildToolchains): BuildToolchain = toolchains.flutter

    override fun supportsBuildVariant(variant: BuildVariant, projectRoot: Path?): Boolean {
        val targets = FlutterTargetPlatforms.load(projectRoot)
        return when (variant) {
            BuildVariant.DEBUG,
            BuildVariant.RELEASE,
            BuildVariant.BUNDLE,
            -> targets.android
            BuildVariant.IOS_BUILD_DEBUG,
            BuildVariant.IOS_EXPORT_IPA,
            -> targets.iphone
            BuildVariant.IOS_TEST,
            BuildVariant.IOS_ARCHIVE,
            -> false
        }
    }

    override fun resolveVersionName(projectRoot: Path, moduleName: String): String? =
        AndroidProjectSignals.resolveFlutterVersionName(projectRoot)
            ?: AndroidProjectSignals.resolveGradleVersionName(projectRoot, moduleName)

    override fun resolveAppIcon(projectRoot: Path, moduleName: String): Path? =
        AndroidProjectSignals.resolveAndroidAppIcon(projectRoot, moduleName, flutter = true)

    override fun toolingProfile(): PlatformToolingProfile = PlatformToolingProfile(
        projectType = projectType,
        defaultMcp = listOf("context7", "memory", "sequentialthinking", "time"),
        conditionalMcp = listOf("github", "gitea", "firebase"),
        optInMcp = listOf("flutter-ios-bridge", "app-publish"),
        defaultSkills = listOf("flutter-dart-expert", "flutter-ui-architect", "flutter-build-debugger"),
        conditionalSkills = listOf("firebase-flutter-integrator"),
        optInSkills = listOf("flutter-ios-bridge", "release-publish"),
        defaultAgents = listOf("flutter-architect", "flutter-implementer", "flutter-build-fixer"),
        conditionalAgents = listOf("firebase-flutter-agent"),
        optInAgents = listOf("flutter-ios-migration-agent", "store-release-agent"),
        forbiddenTools = listOf("xcode-build-debugger", "iphone-*", "swiftui-*", "kotlin-gradle-module-prompt"),
    )
}

data class FlutterTargetPlatforms(
    val android: Boolean = true,
    val iphone: Boolean = false,
) {
    val wire: String
        get() = when {
            android && iphone -> "android,iphone"
            iphone -> "iphone"
            else -> "android"
        }

    companion object {
        private const val FILE_NAME = ".vibecoder-flutter-targets.properties"

        fun load(projectRoot: Path?): FlutterTargetPlatforms {
            if (projectRoot == null) return FlutterTargetPlatforms()
            val file = projectRoot.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return FlutterTargetPlatforms()
            val text = runCatching { Files.readString(file) }.getOrDefault("")
            val raw = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("targets=") }
                ?.substringAfter("=")
                .orEmpty()
            return parse(raw)
        }

        fun parse(raw: String): FlutterTargetPlatforms {
            val parts = raw.split(',', ';', ' ')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .toSet()
            if (parts.isEmpty()) return FlutterTargetPlatforms()
            val android = parts.any { it in setOf("android", "android-only") }
            val iphone = parts.any { it in setOf("iphone", "ios", "iphone-only") }
            return FlutterTargetPlatforms(android = android, iphone = iphone)
        }
    }
}
