package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.admin.WebProjectTemplates
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * v1.133.0 — 콘솔 이미지 블록 추출/분리([ConsoleImages]) + 이력 JSON 의 이미지 메타
 * ([WebProjectTemplates.renderInitialHistoryJson]) 검증.
 */
class ConsoleImagesTest {

    private val b64 = "iVBORw0KGgoAAAANSUhEUg" + "A".repeat(100) // png 헤더 흉내 base64

    private val toolResultContent =
        """[{"type":"text","text":"screenshot taken"},""" +
            """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"$b64"}}]"""

    @Test
    fun `tool_result content 에서 이미지 블록을 추출한다`() {
        val imgs = ConsoleImages.fromToolResultContent(toolResultContent)
        imgs.size shouldBe 1
        imgs[0].mediaType shouldBe "image/png"
        imgs[0].data shouldBe b64
    }

    @Test
    fun `strip 은 base64 를 제거하고 mediaType 목록을 돌려준다`() {
        val (cleaned, types) = ConsoleImages.stripToolResultImages(toolResultContent)
        types shouldBe listOf("image/png")
        cleaned shouldNotContain b64
        cleaned shouldContain "\"omitted\":true"
        cleaned shouldContain "screenshot taken"
        // 정리된 JSON 에서 더 이상 이미지 데이터가 추출되지 않는다.
        ConsoleImages.fromToolResultContent(cleaned).isEmpty() shouldBe true
    }

    @Test
    fun `이미지 없는 content 는 원문 그대로`() {
        val plain = """[{"type":"text","text":"hello"}]"""
        val (cleaned, types) = ConsoleImages.stripToolResultImages(plain)
        types.isEmpty() shouldBe true
        cleaned shouldBe plain
    }

    @Test
    fun `user raw 의 첨부 이미지 JSON 을 파싱한다`() {
        val raw = """[{"mediaType":"image/jpeg","data":"$b64"}]"""
        val imgs = ConsoleImages.fromUserRaw(raw)
        imgs.size shouldBe 1
        imgs[0].mediaType shouldBe "image/jpeg"
        ConsoleImages.fromUserRaw(null).isEmpty() shouldBe true
        ConsoleImages.fromUserRaw("not-json").isEmpty() shouldBe true
    }

    private fun row(role: String, content: String, raw: String? = null) = ConversationTurnRow(
        id = "t1", projectId = "p1", sessionId = "s1", turnIdx = 7,
        ts = "2026-06-12T00:00:00Z", role = role, content = content,
        toolName = null, toolUseId = null, tokensIn = null, tokensOut = null, raw = raw,
    )

    @Test
    fun `이력 JSON 은 tool_result 의 base64 대신 images 메타를 싣는다`() {
        val json = WebProjectTemplates.renderInitialHistoryJson(
            listOf(row("tool_result", toolResultContent)),
        )
        json shouldNotContain b64
        json shouldContain "\"images\":[{\"mediaType\":\"image/png\"}]"
        json shouldContain "\"turnIdx\":7"
    }

    @Test
    fun `이력 JSON 은 user 첨부(raw)를 images 메타로 노출한다`() {
        val raw = """[{"mediaType":"image/webp","data":"$b64"}]"""
        val json = WebProjectTemplates.renderInitialHistoryJson(
            listOf(row("user", "이 화면 고쳐줘", raw)),
        )
        json shouldNotContain b64
        json shouldContain "\"images\":[{\"mediaType\":\"image/webp\"}]"
    }
}
