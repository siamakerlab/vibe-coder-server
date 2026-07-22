package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class SymbolFinderSwiftTest {
    @Test
    fun `finds swift declarations and swiftui view conformance`() {
        val workspaceRoot = Files.createTempDirectory("swift-symbols")
        val projectRoot = workspaceRoot.resolve("demo")
        Files.createDirectories(projectRoot.resolve("Demo"))
        Files.writeString(
            projectRoot.resolve("Demo/ContentView.swift"),
            """
            import SwiftUI

            protocol HeaderViewProtocol {
                func setTitle(_ string: String)
            }

            enum AppRoute {
                case home
            }

            class SessionStore {
                var title: String = ""
            }

            struct ContentView: View {
                var body: some View { Text("Hello") }
                func refresh() {}
            }
            """.trimIndent(),
        )

        val finder = SymbolFinder(WorkspacePath(workspaceRoot))

        finder.find("demo", "HeaderViewProtocol").single().kind shouldBe "protocol"
        finder.find("demo", "AppRoute").single().kind shouldBe "enum"
        finder.find("demo", "SessionStore").single().kind shouldBe "class"
        finder.find("demo", "ContentView").single().kind shouldBe "swiftui-view"
        finder.find("demo", "refresh").single().kind shouldBe "func"
        finder.find("demo", "title").map { it.kind } shouldContain "val"
    }
}
