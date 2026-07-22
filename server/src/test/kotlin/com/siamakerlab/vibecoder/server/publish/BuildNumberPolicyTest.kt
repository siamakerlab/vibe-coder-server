package com.siamakerlab.vibecoder.server.publish

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BuildNumberPolicyTest {
    @Test
    fun `uses highest xcode or plist build number plus one`() {
        val root = Files.createTempDirectory("vibe-build-number-")
        try {
            val xcode = root.resolve("Demo.xcodeproj")
            xcode.createDirectories()
            xcode.resolve("project.pbxproj").writeText(
                """
                CURRENT_PROJECT_VERSION = 42;
                CURRENT_PROJECT_VERSION = 44;
                """.trimIndent()
            )
            val app = root.resolve("Demo")
            app.createDirectories()
            app.resolve("Info.plist").writeText(
                """
                <plist><dict>
                  <key>CFBundleVersion</key>
                  <string>43</string>
                </dict></plist>
                """.trimIndent()
            )

            BuildNumberPolicy.next(root) shouldBe "45"
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `can derive flutter pubspec build metadata`() {
        val root = Files.createTempDirectory("vibe-build-number-flutter-")
        try {
            root.resolve("pubspec.yaml").writeText("name: demo\nversion: 1.2.3+77\n")

            BuildNumberPolicy.next(root) shouldBe "78"
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
