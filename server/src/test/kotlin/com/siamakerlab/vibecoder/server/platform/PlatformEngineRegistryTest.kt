package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.build.BuildToolchain
import com.siamakerlab.vibecoder.server.build.BuildVariant
import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PlatformEngineRegistryTest {
    @Test
    fun `registry exposes separate engines for kotlin flutter and iphone`() {
        val registry = PlatformEngineRegistry.default

        registry.all().map { it.projectType }.shouldContainExactlyInAnyOrder(
            ProjectTypes.KOTLIN,
            ProjectTypes.FLUTTER,
            ProjectTypes.IPHONE,
        )
        registry.forType("Flutter").projectType shouldBe ProjectTypes.FLUTTER
        registry.forType(" iphone ").projectType shouldBe ProjectTypes.IPHONE
        registry.forType("unknown").projectType shouldBe ProjectTypes.KOTLIN
    }

    @Test
    fun `tooling profiles are platform specific`() {
        val registry = PlatformEngineRegistry.default
        val kotlin = registry.forType(ProjectTypes.KOTLIN).toolingProfile()
        val flutter = registry.forType(ProjectTypes.FLUTTER).toolingProfile()
        val iphone = registry.forType(ProjectTypes.IPHONE).toolingProfile()

        kotlin.defaultMcp shouldBe listOf("context7", "memory", "sequentialthinking", "time")
        flutter.defaultMcp shouldBe listOf("context7", "memory", "sequentialthinking", "time")
        iphone.defaultMcp shouldBe listOf("context7", "memory", "sequentialthinking", "time", "playwright")

        kotlin.defaultSkills shouldContain "kotlin-expert"
        kotlin.defaultSkills shouldNotContain "swiftui-iphone-expert"
        flutter.defaultSkills shouldContain "flutter-dart-expert"
        flutter.defaultSkills shouldNotContain "kotlin-expert"
        iphone.defaultSkills shouldContain "swiftui-iphone-expert"
        iphone.defaultSkills shouldNotContain "flutter-dart-expert"

        kotlin.defaultAgents shouldContain "gradle-build-fixer"
        flutter.defaultAgents shouldContain "flutter-build-fixer"
        iphone.defaultAgents shouldContain "xcode-build-fixer"
        iphone.conditionalMcp shouldContain "mobile-mcp"
    }

    @Test
    fun `ui capabilities are platform specific`() {
        val registry = PlatformEngineRegistry.default
        val kotlin = registry.forType(ProjectTypes.KOTLIN).uiCapabilities()
        val flutter = registry.forType(ProjectTypes.FLUTTER).uiCapabilities()
        val iphone = registry.forType(ProjectTypes.IPHONE).uiCapabilities()

        kotlin.showPlayStoreLink shouldBe true
        kotlin.showIosBuildSettings shouldBe false
        flutter.showPlayStoreLink shouldBe true
        flutter.showIPhoneQuickPrompts shouldBe false
        iphone.showPlayStoreLink shouldBe false
        iphone.showIosBuildSettings shouldBe true
        iphone.showIosSimulator shouldBe true
        iphone.showIPhoneQuickPrompts shouldBe true
    }

    @Test
    fun `platform engines own build toolchain selection`() {
        val gradle = fakeToolchain()
        val flutter = fakeToolchain()
        val ios = fakeToolchain()
        val toolchains = PlatformBuildToolchains(gradle = gradle, flutter = flutter, ios = ios)
        val registry = PlatformEngineRegistry.default

        registry.forType(ProjectTypes.KOTLIN).buildToolchain(toolchains) shouldBe gradle
        registry.forType(ProjectTypes.FLUTTER).buildToolchain(toolchains) shouldBe flutter
        registry.forType(ProjectTypes.IPHONE).buildToolchain(toolchains) shouldBe ios
        registry.forType("unknown").buildToolchain(toolchains) shouldBe gradle
    }

    @Test
    fun `platform engines own build variant support`() {
        val registry = PlatformEngineRegistry.default
        val kotlin = registry.forType(ProjectTypes.KOTLIN)
        val flutter = registry.forType(ProjectTypes.FLUTTER)
        val iphone = registry.forType(ProjectTypes.IPHONE)

        kotlin.supportsBuildVariant(BuildVariant.DEBUG) shouldBe true
        kotlin.supportsBuildVariant(BuildVariant.IOS_BUILD_DEBUG) shouldBe false
        flutter.supportsBuildVariant(BuildVariant.BUNDLE) shouldBe true
        flutter.supportsBuildVariant(BuildVariant.IOS_EXPORT_IPA) shouldBe false
        iphone.supportsBuildVariant(BuildVariant.DEBUG) shouldBe false
        iphone.supportsBuildVariant(BuildVariant.IOS_TEST) shouldBe true
        iphone.supportsBuildVariant(BuildVariant.IOS_EXPORT_IPA) shouldBe true
    }

    @Test
    fun `flutter target policy gates android and iphone build variants`() {
        val root = Files.createTempDirectory("flutter-target-policy")
        val flutter = PlatformEngineRegistry.default.forType(ProjectTypes.FLUTTER)

        flutter.supportsBuildVariant(BuildVariant.DEBUG, root) shouldBe true
        flutter.supportsBuildVariant(BuildVariant.IOS_EXPORT_IPA, root) shouldBe false

        Files.writeString(root.resolve(".vibecoder-flutter-targets.properties"), "targets=android,iphone\n")
        flutter.supportsBuildVariant(BuildVariant.DEBUG, root) shouldBe true
        flutter.supportsBuildVariant(BuildVariant.IOS_BUILD_DEBUG, root) shouldBe true
        flutter.supportsBuildVariant(BuildVariant.IOS_EXPORT_IPA, root) shouldBe true
        flutter.supportsBuildVariant(BuildVariant.IOS_TEST, root) shouldBe false

        Files.writeString(root.resolve(".vibecoder-flutter-targets.properties"), "targets=iphone\n")
        flutter.supportsBuildVariant(BuildVariant.DEBUG, root) shouldBe false
        flutter.supportsBuildVariant(BuildVariant.IOS_EXPORT_IPA, root) shouldBe true
    }

    @Test
    fun `project memories contain only selected platform rules`() {
        val base = ClaudeMdTemplate.ProjectInfo(
            appName = "Demo",
            packageName = "com.example.demo",
            projectId = "demo",
            moduleName = "app",
            debugTask = "assembleDebug",
        )
        val registry = PlatformEngineRegistry.default

        val kotlinMemory = registry.forType(ProjectTypes.KOTLIN)
            .renderProjectMemory(base.copy(projectType = ProjectTypes.KOTLIN)).content
        val flutterMemory = registry.forType(ProjectTypes.FLUTTER)
            .renderProjectMemory(base.copy(projectType = ProjectTypes.FLUTTER)).content
        val iphoneMemory = registry.forType(ProjectTypes.IPHONE)
            .renderProjectMemory(base.copy(projectType = ProjectTypes.IPHONE)).content

        kotlinMemory.contains("Kotlin Android SDK") shouldBe true
        kotlinMemory.contains("pubspec") shouldBe false
        kotlinMemory.contains("xcodebuild") shouldBe false

        flutterMemory.contains("Flutter (Dart)") shouldBe true
        flutterMemory.contains("Gradle module") shouldBe false
        flutterMemory.contains("xcodebuild") shouldBe false

        iphoneMemory.contains("Swift/SwiftUI/Xcode") shouldBe true
        iphoneMemory.contains("Android package / applicationId") shouldBe false
        iphoneMemory.contains("Project type: Flutter") shouldBe false
    }

    @Test
    fun `registry detects project types through engines in platform safe order`() {
        val root = Files.createTempDirectory("platform-detect")
        Files.writeString(root.resolve("pubspec.yaml"), "name: demo\n")
        Files.writeString(root.resolve("settings.gradle.kts"), "pluginManagement {}\n")

        PlatformEngineRegistry.default.detectProjectType(root) shouldBe ProjectTypes.FLUTTER

        val iphone = Files.createTempDirectory("platform-detect-iphone")
        Files.createDirectories(iphone.resolve("Demo.xcodeproj"))

        PlatformEngineRegistry.default.detectProjectType(iphone) shouldBe ProjectTypes.IPHONE
    }

    @Test
    fun `kotlin and flutter engines own version and icon lookup`() {
        val root = Files.createTempDirectory("platform-signals")
        val appDir = root.resolve("app")
        Files.createDirectories(appDir.resolve("src/main/res/mipmap-xxxhdpi"))
        Files.writeString(
            appDir.resolve("build.gradle.kts"),
            """
            plugins { id("com.android.application") }
            android {
              defaultConfig {
                applicationId = "kr.codr.demo"
                versionName = appVersionName
              }
            }
            val appVersionName = "2.3.4"
            """.trimIndent(),
        )
        Files.write(root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"), byteArrayOf(1, 2, 3))

        val kotlin = PlatformEngineRegistry.default.forType(ProjectTypes.KOTLIN)

        kotlin.detectPackageName(root) shouldBe "kr.codr.demo"
        kotlin.resolveVersionName(root, "app") shouldBe "2.3.4"
        kotlin.resolveAppIcon(root, "app") shouldBe root.resolve("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")

        val flutter = Files.createTempDirectory("platform-signals-flutter")
        Files.writeString(flutter.resolve("pubspec.yaml"), "name: demo\nversion: 5.6.7+8\n")

        PlatformEngineRegistry.default.forType(ProjectTypes.FLUTTER)
            .resolveVersionName(flutter, "app") shouldBe "5.6.7"
    }

    private fun fakeToolchain(): BuildToolchain = object : BuildToolchain {
        override suspend fun runBuild(
            source: Path,
            moduleName: String,
            variant: BuildVariant,
            debugTask: String,
            logger: TaskLogger,
            cancellation: Flow<Unit>,
            signing: SigningCredentials?,
        ): Int = 0

        override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? = null
    }
}
