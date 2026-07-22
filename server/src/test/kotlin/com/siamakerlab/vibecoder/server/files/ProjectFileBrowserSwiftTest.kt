package com.siamakerlab.vibecoder.server.files

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class ProjectFileBrowserSwiftTest {
    @Test
    fun `swift files are readable text with swift mime`() {
        val workspaceRoot = Files.createTempDirectory("swift-file-browser")
        val projectRoot = workspaceRoot.resolve("demo")
        Files.createDirectories(projectRoot.resolve("Demo"))
        Files.writeString(
            projectRoot.resolve("Demo/ContentView.swift"),
            """
            import SwiftUI

            struct ContentView: View {
                var body: some View { Text("Hello") }
            }
            """.trimIndent(),
        )

        val view = ProjectFileBrowser(WorkspacePath(workspaceRoot)).read("demo", "Demo/ContentView.swift")

        view.mimeGuess shouldBe "text/x-swift"
        view.content.contains("struct ContentView") shouldBe true
    }
}
