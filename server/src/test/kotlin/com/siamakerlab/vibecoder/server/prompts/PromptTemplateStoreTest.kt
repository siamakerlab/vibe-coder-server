package com.siamakerlab.vibecoder.server.prompts

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldEndWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * v1.115.0 — PromptTemplateStore 회귀 테스트.
 * 즐겨찾기 고정 / 사용 빈도 / 복제 / 가져오기 / 카테고리(비대문자) + 구버전 JSON 하위호환.
 */
class PromptTemplateStoreTest {

    private lateinit var root: Path
    private val clock = object : Clock {
        override fun nowInstant(): Instant = Instant.parse("2026-06-07T00:00:00Z")
        override fun nowIso(): String = "2026-06-07T00:00:00"
    }

    private fun store() = PromptTemplateStore(WorkspacePath(root), clock)
    private fun jsonFile(): Path = root.resolve(".vibecoder").resolve("prompt-templates.json")

    @Before
    fun setup() { root = Files.createTempDirectory("vibe-prompts-test") }

    @After
    fun teardown() { root.toFile().deleteRecursively() }

    @Test
    fun `create 는 카테고리를 대문자로 바꾸지 않고 그대로 저장한다`() {
        val t = store().create(title = "신규 화면", category = "android", body = "Compose 화면 추가")
        t.category shouldBe "android"        // 'ANDROID' 아님
        t.pinned shouldBe false
        t.useCount shouldBe 0
    }

    @Test
    fun `category 비우면 General 폴백`() {
        store().create(title = "t", category = "   ", body = "b").category shouldBe "General"
    }

    @Test
    fun `setPinned 과 recordUse`() {
        val s = store()
        val t = s.create("t", "cat", "body")
        s.setPinned(t.id, true)!!.pinned shouldBe true
        val used = s.recordUse(t.id)!!
        used.useCount shouldBe 1
        used.lastUsedAt shouldBe "2026-06-07T00:00:00"
        // recordUse 는 updatedAt 을 건드리지 않는다(목록 수정일 안정).
        used.updatedAt shouldBe t.updatedAt
        s.recordUse(t.id)!!.useCount shouldBe 2
    }

    @Test
    fun `duplicate 는 copy 접미사 + 카운터 초기화`() {
        val s = store()
        val t = s.create("원본", "cat", "body")
        s.setPinned(t.id, true); s.recordUse(t.id)
        val dup = s.duplicate(t.id)
        dup.title shouldEndWith " (copy)"
        dup.pinned shouldBe false
        dup.useCount shouldBe 0
        dup.id shouldBe dup.id // 새 id
        (dup.id == t.id) shouldBe false
        s.listAll().size shouldBe 2
    }

    @Test
    fun `categories 는 중복 제거 + 대소문자 무시 정렬`() {
        val s = store()
        s.create("a", "Zeta", "b"); s.create("c", "alpha", "d"); s.create("e", "Zeta", "f")
        s.categories() shouldContainExactly listOf("alpha", "Zeta")
    }

    @Test
    fun `importTemplates 병합과 교체`() {
        val s = store()
        s.create("기존", "cat", "body")
        val incoming = listOf(
            PromptTemplateStore.ImportItem(title = "가져온1", category = "X", body = "b1", pinned = true, useCount = 3),
            PromptTemplateStore.ImportItem(title = "가져온2", category = "", body = "b2"),
            PromptTemplateStore.ImportItem(title = "  ", category = "Y", body = "무효"), // 빈 제목 → 스킵
        )
        // 병합
        s.importTemplates(incoming, replace = false) shouldBe 2
        s.listAll().size shouldBe 3
        s.listAll().first { it.title == "가져온1" }.let { it.pinned shouldBe true; it.useCount shouldBe 3 }
        s.listAll().first { it.title == "가져온2" }.category shouldBe "General"
        // 교체
        s.importTemplates(listOf(PromptTemplateStore.ImportItem(title = "유일", category = "Z", body = "b")), replace = true) shouldBe 1
        s.listAll().map { it.title } shouldContainExactly listOf("유일")
    }

    @Test
    fun `구버전 JSON(pinned_useCount 필드 없음) 하위호환 로드`() {
        Files.createDirectories(jsonFile().parent)
        // 신 필드가 전혀 없는 옛 스키마.
        Files.writeString(
            jsonFile(),
            """{"templates":[{"id":"old1","title":"옛템플릿","category":"Legacy","body":"본문","createdAt":"2026-01-01T00:00:00","updatedAt":"2026-01-01T00:00:00"}]}""",
        )
        val t = store().get("old1")!!
        t.title shouldBe "옛템플릿"
        t.pinned shouldBe false
        t.useCount shouldBe 0
        t.lastUsedAt shouldBe null
    }

    @Test
    fun `body 길이 제한은 문서 수준 100_000자까지 허용한다`() {
        val s = store()
        // 16_000(구 제한) 초과 ~ 100_000(신 제한) 본문은 이제 저장 가능.
        val longBody = "x".repeat(16_001)
        val saved = s.create(title = "긴 프롬프트", category = "doc", body = longBody)
        saved.body.length shouldBe 16_001
        // 경계값 100_000 도 허용.
        val max = s.create("max", "doc", "y".repeat(100_000))
        max.body.length shouldBe 100_000
    }

    @Test
    fun `body 가 100_000자 초과면 거절한다`() {
        val s = store()
        try {
            s.create("over", "doc", "z".repeat(100_001))
            error("예상치 못한 성공")
        } catch (e: com.siamakerlab.vibecoder.server.error.ApiException) {
            e.statusCode shouldBe 400
        }
    }
}
