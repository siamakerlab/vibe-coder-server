package com.siamakerlab.vibecoder.server.projects

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class ProjectScaffolderTest {
    @Test
    fun `iphone starter creates swiftui files without overwriting`() {
        val root = Files.createTempDirectory("vibe-iphone-starter-")
        try {
            val created = ProjectScaffolder.scaffoldIPhoneStarter(root, "My iPhone App", "kr.codr.demo")
            created shouldBe 6

            val appDir = root.resolve("MyIPhoneApp")
            Files.isRegularFile(appDir.resolve("MyIPhoneAppApp.swift")) shouldBe true
            Files.isRegularFile(appDir.resolve("ContentView.swift")) shouldBe true
            Files.isRegularFile(root.resolve("MyIPhoneApp.xcodeproj/project.pbxproj")) shouldBe true
            Files.isRegularFile(root.resolve("MyIPhoneAppTests/MyIPhoneAppTests.swift")) shouldBe true
            Files.readString(root.resolve("MyIPhoneApp.xcodeproj/project.pbxproj")).contains("PBXNativeTarget") shouldBe true
            Files.readString(root.resolve("Info.plist")).contains("kr.codr.demo") shouldBe true

            ProjectScaffolder.scaffoldIPhoneStarter(root, "My iPhone App", "kr.codr.demo") shouldBe 0
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
