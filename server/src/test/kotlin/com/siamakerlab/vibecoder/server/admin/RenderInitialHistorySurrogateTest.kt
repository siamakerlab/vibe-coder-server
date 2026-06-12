package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.repo.ConversationTurnRow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * v1.132.1 — 콘솔 인라인 이력 절단의 surrogate-safe 회귀 검증.
 *
 * [WebProjectTemplates.renderInitialHistoryJson] 이 maxContent(4000자) 경계에서
 * 이모지(surrogate pair)를 반토막 내면 짝 없는 high surrogate 가 남고, respondText
 * 의 UTF-8 엄격 인코딩이 MalformedInputException 으로 터져 콘솔 페이지 전체가
 * 500 이 된다 (운영 kauslim turn_idx=778 실증). 이 테스트가 깨지면 그 결함이 재발한 것.
 */
class RenderInitialHistorySurrogateTest {

    private fun row(content: String) = ConversationTurnRow(
        id = "t1",
        projectId = "p1",
        sessionId = "s1",
        turnIdx = 778,
        ts = "2026-06-12T00:00:00Z",
        role = "assistant",
        content = content,
        toolName = null,
        toolUseId = null,
        tokensIn = null,
        tokensOut = null,
        raw = null,
    )

    /** 엄격(REPORT) UTF-8 인코딩 — lone surrogate 가 있으면 MalformedInputException. */
    private fun encodeStrictUtf8(s: String): ByteArray {
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bb = encoder.encode(CharBuffer.wrap(s))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    private fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> i += 2
                c.isSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }

    @Test
    fun `4000자 경계에 걸린 이모지가 반토막 나지 않는다`() {
        // 3999자 + 🟢(U+1F7E2, surrogate pair 2자) + 채움 = 5001자.
        // 종전 substring(0, 4000) 은 high surrogate(\uD83D)만 남겨 인코딩이 터졌다.
        val content = "a".repeat(3999) + "🟢" + "b".repeat(1000)
        val json = WebProjectTemplates.renderInitialHistoryJson(listOf(row(content)))

        hasLoneSurrogate(json) shouldBe false
        // 엄격 인코딩이 예외 없이 통과해야 한다 (respondText 와 동일 조건).
        encodeStrictUtf8(json).isNotEmpty() shouldBe true
        // 한 칸 당겨 잘랐으므로 절단 마커는 +1002 (5001 - 3999).
        json shouldContain "(+1002)"
    }

    @Test
    fun `경계가 이모지 중간이 아니면 종전대로 4000자에서 자른다`() {
        val content = "a".repeat(5000)
        val json = WebProjectTemplates.renderInitialHistoryJson(listOf(row(content)))
        hasLoneSurrogate(json) shouldBe false
        json shouldContain "(+1000)"
    }
}
