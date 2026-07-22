package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.platform.PlatformEngineRegistry
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * v1.159.1 — 프로젝트 목록의 Google Play 스토어 링크 렌더 검증.
 * packageName 으로 play.google.com 앱 페이지 URL 을 만들고, 새 탭으로 열리며,
 * 패키지명과 개발언어 배지 사이에 위치하는지 확인.
 */
class ProjectsListPlayLinkTest {

    private fun project(pkg: String) = ProjectDto(
        id = "myapp",
        name = "My App",
        packageName = pkg,
        sourcePath = "/ws/myapp",
        moduleName = "app",
        debugTask = "assembleDebug",
        updatedAt = "2026-07-08T00:00:00Z",
    )

    private fun render(pkg: String) = WebProjectTemplates.projectsPage(
        username = "admin",
        projects = listOf(project(pkg)),
        lang = "ko",
        csrf = "csrf",
        total = 1,
        uiCapabilities = mapOf("myapp" to PlatformEngineRegistry.default.forType(ProjectTypes.KOTLIN).uiCapabilities()),
    )

    @Test
    fun `play link opens store page in new tab`() {
        val html = render("com.siamakerlab.myapp")
        html shouldContain "https://play.google.com/store/apps/details?id=com.siamakerlab.myapp"
        html shouldContain "target=\"_blank\""
        html shouldContain "rel=\"noopener noreferrer\""
        // 행 네비와 겹치지 않도록 stopPropagation.
        html shouldContain "event.stopPropagation()"
        html shouldContain "Google Play 스토어에서 열기"
    }

    @Test
    fun `play link sits between package and language badge`() {
        val html = render("com.siamakerlab.myapp")
        val pkgIdx = html.indexOf("<code>com.siamakerlab.myapp</code>")
        val playIdx = html.indexOf("play.google.com/store/apps/details")
        val kotlinBadgeIdx = html.indexOf(">Kotlin<")
        pkgIdx shouldBeGreaterThan -1
        playIdx shouldBeGreaterThan pkgIdx        // 패키지 뒤
        playIdx shouldBeLessThan kotlinBadgeIdx   // 언어 배지 앞
    }

    @Test
    fun `play link visibility follows engine ui capability`() {
        val iphone = project("kr.codr.demo").copy(projectType = ProjectTypes.IPHONE)
        val html = WebProjectTemplates.projectsPage(
            username = "admin",
            projects = listOf(iphone),
            lang = "ko",
            csrf = "csrf",
            total = 1,
            uiCapabilities = mapOf("myapp" to PlatformEngineRegistry.default.forType(ProjectTypes.IPHONE).uiCapabilities()),
        )

        html shouldContain ">iPhone<"
        html shouldNotContain "play.google.com/store/apps/details"
    }
}
