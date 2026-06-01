package com.siamakerlab.vibecoder.server.admin

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * v1.72.0 — iframe 임베드 렌더링 검증. [AdminTemplates.shell] 의 `embed=true` 가
 * 사이드바 nav 와 설정 탭바를 **HTML 에 아예 넣지 않는지**(JS 로 숨기는 게 아니라
 * 서버 미렌더) 를 출력 문자열 수준에서 확증한다. 이 불변식이 깨지면 통합 탭 iframe
 * 안에서 "페이지 in 페이지"(전체 shell 이중 노출)가 재발한다.
 */
class EmbedShellTest {

    private fun render(embed: Boolean): String = AdminTemplates.shell(
        title = "T",
        body = "<p data-marker=\"vibe-body\">body</p>",
        username = "tester",
        currentPath = "/settings",
        csrf = null,
        lang = "en",
        embed = embed,
    )

    @Test
    fun `direct (non-embed) request renders sidebar nav and settings tabs`() {
        val html = render(embed = false)
        // 직접 접근(북마크 등): 크롬 정상 노출 — 회귀 방지.
        html shouldContain "nav class=\"sidebar\""
        html shouldContain "settings-tabs"
        html shouldContain "<p data-marker=\"vibe-body\">body</p>"
    }

    @Test
    fun `embed request omits sidebar nav and settings tabs entirely`() {
        val html = render(embed = true)
        // iframe 임베드: nav / 탭바를 처음부터 미렌더(문자열에 존재하지 않아야 함).
        html shouldNotContain "nav class=\"sidebar\""
        html shouldNotContain "settings-tabs"
        // layout 은 사이드바 슬롯 없는 단일 칼럼.
        html shouldContain "layout no-nav"
        // 본문은 그대로 렌더.
        html shouldContain "<p data-marker=\"vibe-body\">body</p>"
    }

    @Test
    fun `embed has no effect when showNav already false`() {
        // login/setup 류(showNav=false)는 embed 와 무관하게 nav 가 원래 없다.
        val plain = AdminTemplates.shell(
            title = "T", body = "x", showNav = false, currentPath = "/settings",
            lang = "en", embed = false,
        )
        val embedded = AdminTemplates.shell(
            title = "T", body = "x", showNav = false, currentPath = "/settings",
            lang = "en", embed = true,
        )
        plain shouldNotContain "nav class=\"sidebar\""
        embedded shouldNotContain "nav class=\"sidebar\""
    }
}
