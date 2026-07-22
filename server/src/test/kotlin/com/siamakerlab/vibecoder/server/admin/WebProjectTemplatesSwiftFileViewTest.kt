package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.files.ProjectFileBrowser
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.shouldBe
import org.junit.Test

class WebProjectTemplatesSwiftFileViewTest {
    @Test
    fun `swift file view loads codemirror swift mode`() {
        val html = WebProjectTemplates.fileViewPage(
            username = "admin",
            p = ProjectDto(
                id = "demo",
                name = "Demo",
                packageName = "kr.codr.demo",
                sourcePath = "/tmp/demo",
                moduleName = "app",
                debugTask = "assembleDebug",
                updatedAt = "2026-07-22T00:00:00Z",
                projectType = ProjectTypes.IPHONE,
            ),
            relPath = "Demo/ContentView.swift",
            view = ProjectFileBrowser.FileView(
                relPath = "Demo/ContentView.swift",
                sizeBytes = 42,
                content = "import SwiftUI\nstruct ContentView: View {}",
                truncated = false,
                mimeGuess = "text/x-swift",
            ),
            csrf = "csrf",
            lang = "en",
        )

        html.contains("/static/vendor/codemirror/mode/swift.js") shouldBe true
        html.contains("mode: \"text/x-swift\"") shouldBe true
    }
}
