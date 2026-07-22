package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.shared.dto.BuildDto
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import com.siamakerlab.vibecoder.shared.dto.TaskStatus
import io.kotest.matchers.shouldBe
import org.junit.Test

class WebProjectTemplatesIosBuildFailureTest {
    @Test
    fun `ios failed build renders console fix prompt action`() {
        val html = WebProjectTemplates.buildDetailPage(
            username = "admin",
            p = project(ProjectTypes.IPHONE),
            b = build(variant = "ios-debug"),
            artifact = null,
            csrf = "csrf",
            lang = "en",
        )

        html.contains("/projects/demo/builds/build-1/ios-fix-prompt") shouldBe true
        html.contains("Ask console to fix iOS failure") shouldBe true
        html.contains("failureKind: <code>swift_compile_failed</code>") shouldBe true
    }

    @Test
    fun `non ios failed build does not render ios fix prompt action`() {
        val html = WebProjectTemplates.buildDetailPage(
            username = "admin",
            p = project(ProjectTypes.KOTLIN),
            b = build(variant = "debug"),
            artifact = null,
            csrf = "csrf",
            lang = "en",
        )

        html.contains("ios-fix-prompt") shouldBe false
        html.contains("Ask console to fix iOS failure") shouldBe false
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

    private fun build(variant: String): BuildDto = BuildDto(
        id = "build-1",
        projectId = "demo",
        variant = variant,
        status = TaskStatus.FAILED,
        startedAt = "2026-07-22T00:00:00Z",
        finishedAt = "2026-07-22T00:01:00Z",
        errorMessage = "Build failed (swift_compile_failed): Swift compilation failed.",
        failureKind = "swift_compile_failed",
    )
}
