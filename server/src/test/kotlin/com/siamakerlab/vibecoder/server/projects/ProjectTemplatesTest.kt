package com.siamakerlab.vibecoder.server.projects

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProjectTemplatesTest {
    @Test
    fun `iphone swiftui starter template is available`() {
        val template = ProjectTemplates.byId("iphone-swiftui-basic")

        template?.title shouldBe "iPhone - SwiftUI 기본"
        template?.starterPrompt.orEmpty().contains("SwiftUI") shouldBe true
        template?.starterPrompt.orEmpty().contains("xcodebuild") shouldBe true
        ProjectTemplates.all.map { it.id } shouldContain "iphone-swiftui-basic"
    }
}
