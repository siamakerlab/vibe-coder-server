package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.platform.PlatformEngineRegistry
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.shouldBe
import org.junit.Test

class WebProjectTemplatesQuickPromptTest {
    @Test
    fun `iphone console renders iphone quick prompt buttons`() {
        val html = WebProjectTemplates.consolePage(
            username = "admin",
            p = project(ProjectTypes.IPHONE),
            sessionId = null,
            isAlive = false,
            agentProvider = AgentProvider.CLAUDE,
            uiCapabilities = PlatformEngineRegistry.default.forType(ProjectTypes.IPHONE).uiCapabilities(),
            lang = "en",
        )

        html.contains("SwiftUI screen") shouldBe true
        html.contains("Xcode build review") shouldBe true
        html.contains("Signing review") shouldBe true
        html.contains("Simulator review") shouldBe true
        html.contains("TestFlight review") shouldBe true
    }

    @Test
    fun `kotlin console does not render iphone quick prompt buttons`() {
        val html = WebProjectTemplates.consolePage(
            username = "admin",
            p = project(ProjectTypes.KOTLIN),
            sessionId = null,
            isAlive = false,
            agentProvider = AgentProvider.CLAUDE,
            uiCapabilities = PlatformEngineRegistry.default.forType(ProjectTypes.KOTLIN).uiCapabilities(),
            lang = "en",
        )

        html.contains("SwiftUI screen") shouldBe false
        html.contains("Xcode build review") shouldBe false
        html.contains("Signing review") shouldBe false
        html.contains("Simulator review") shouldBe false
        html.contains("TestFlight review") shouldBe false
    }

    private fun project(projectType: String): ProjectDto = ProjectDto(
        id = "demo",
        name = "Demo",
        packageName = "kr.codr.demo",
        sourcePath = "/tmp/demo",
        moduleName = "app",
        debugTask = "assembleDebug",
        updatedAt = "2026-07-22T00:00:00Z",
        projectType = projectType,
    )
}
