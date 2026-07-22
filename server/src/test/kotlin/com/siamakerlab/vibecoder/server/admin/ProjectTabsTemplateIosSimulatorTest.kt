package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProjectTabsTemplateIosSimulatorTest {
    @Test
    fun `iphone project renders simulator rail card with run logs and screenshot hooks`() {
        val html = ProjectTabsTemplate.page(
            username = "admin",
            project = project(ProjectTypes.IPHONE),
            flashErr = null,
            flashOk = null,
            csrf = "csrf",
            lang = "en",
            iosBuildSettings = ProjectTabsTemplate.IosBuildSettingsView(
                scheme = "Demo",
                selectedScheme = "Demo",
                inferredScheme = "Demo",
                sharedSchemes = listOf("Demo", "DemoTests"),
                debugConfiguration = "Debug",
                releaseConfiguration = "Release",
                bundleIdentifier = "kr.codr.demo",
                teamId = "ABCDE12345",
                exportMethod = "app-store-connect",
                signingStyle = "manual",
                provisioningProfileSpecifier = "Demo AppStore Profile",
                containerName = "Demo.xcodeproj",
            ),
        )

        html.contains("data-card=\"ios-build-settings\"") shouldBe true
        html.contains("/projects/demo/ios/build-settings") shouldBe true
        html.contains("<option value=\"Demo\" selected>Demo</option>") shouldBe true
        html.contains("name=\"exportMethod\"") shouldBe true
        html.contains("<option value=\"app-store-connect\" selected>app-store-connect</option>") shouldBe true
        html.contains("name=\"signingStyle\"") shouldBe true
        html.contains("<option value=\"manual\" selected>manual</option>") shouldBe true
        html.contains("value=\"kr.codr.demo\"") shouldBe true
        html.contains("value=\"ABCDE12345\"") shouldBe true
        html.contains("value=\"Demo AppStore Profile\"") shouldBe true
        html.contains("data-card=\"ios-signing\"") shouldBe true
        html.contains("/api/projects/demo/ios/signing-status") shouldBe true
        html.contains("pt-ios-sign-refresh") shouldBe true
        html.contains("pt-ios-sim-card") shouldBe true
        html.contains("/api/ios/simulators") shouldBe true
        html.contains("/api/projects/demo/ios/simulators/__UDID__/run") shouldBe true
        html.contains("/api/projects/demo/ios/simulators/__UDID__/logs") shouldBe true
        html.contains("/ws/projects/demo/ios/simulators/__UDID__/logs") shouldBe true
        html.contains("pt-ios-sim-stream") shouldBe true
        html.contains("/projects/demo/ios/simulator/screenshot") shouldBe true
    }

    @Test
    fun `kotlin project does not render simulator rail card`() {
        val html = ProjectTabsTemplate.page(
            username = "admin",
            project = project(ProjectTypes.KOTLIN),
            flashErr = null,
            flashOk = null,
            csrf = "csrf",
            lang = "en",
        )

        html.contains("data-card=\"ios-simulator\"") shouldBe false
        html.contains("data-card=\"ios-signing\"") shouldBe false
        html.contains("data-card=\"ios-build-settings\"") shouldBe false
        html.contains("/api/projects/demo/ios/signing-status") shouldBe false
        html.contains("data-screenshot-url=\"/projects/demo/ios/simulator/screenshot") shouldBe false
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
