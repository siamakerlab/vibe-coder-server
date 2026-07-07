package com.siamakerlab.vibecoder.server.prompts

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * 프롬프트 템플릿 관리 — v0.13.0.
 *
 * 단일 admin 환경이라 사용자 분리 없이 한 곳에 저장.
 *   - 위치: `<workspace>/.vibecoder/prompt-templates.json`
 *   - 단일 JSON 배열 (id/category/title/body/createdAt).
 *   - 콘솔 페이지의 "Templates" 드롭다운에서 즉시 prompt 영역에 paste.
 *   - 카테고리는 자유 텍스트 (사용자가 입력). 비우면 "General".
 *
 * 동시성: 한 admin 만 변경 → ReentrantReadWriteLock 으로 충분.
 */
class PromptTemplateStore(
    private val workspace: WorkspacePath,
    private val clock: Clock,
) {

    @Serializable
    data class Template(
        val id: String,
        val title: String,
        val category: String,
        val body: String,
        val createdAt: String,
        val updatedAt: String,
        // v1.115.0 — 즐겨찾기 고정 + 사용 빈도. 기존 JSON 에 필드가 없어도 default 로 역직렬화.
        val pinned: Boolean = false,
        val useCount: Int = 0,
        val lastUsedAt: String? = null,
    )

    @Serializable
    private data class Storage(
        val templates: MutableList<Template> = mutableListOf(),
    )

    private val lock = ReentrantReadWriteLock()

    private fun path(): Path =
        workspace.root.resolve(".vibecoder").resolve("prompt-templates.json")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }

    fun listAll(): List<Template> = lock.read { read().templates.toList() }

    fun get(id: String): Template? = lock.read {
        read().templates.firstOrNull { it.id == id }
    }

    /** 카테고리 → 알파벳 정렬된 템플릿 리스트. UI 의 drop-down grouping 에 사용. */
    fun grouped(): Map<String, List<Template>> = lock.read {
        read().templates
            .sortedWith(compareBy({ it.category.lowercase() }, { it.title.lowercase() }))
            .groupBy { it.category }
    }

    fun create(title: String, category: String, body: String): Template {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty()) throw ApiException.localized(400, "empty_title", messageKey = "api.prompt.emptyTitle")
        if (cleanBody.isEmpty()) throw ApiException.localized(400, "empty_body", messageKey = "api.prompt.emptyBody")
        if (cleanTitle.length > MAX_TITLE_LEN)
            throw ApiException.localized(400, "title_too_long", messageKey = "api.prompt.titleTooLong", args = listOf(MAX_TITLE_LEN))
        if (cleanBody.length > MAX_BODY_LEN)
            throw ApiException.localized(400, "body_too_long", messageKey = "api.prompt.bodyTooLong", args = listOf(MAX_BODY_LEN))
        val cleanCat = category.trim().ifBlank { "General" }
        val now = clock.nowIso()
        val t = Template(Ids.taskId(), cleanTitle, cleanCat, cleanBody, now, now)
        lock.write {
            val s = read()
            if (s.templates.size >= MAX_TOTAL)
                throw ApiException.localized(400, "limit_reached", messageKey = "api.prompt.limitReached", args = listOf(MAX_TOTAL))
            s.templates.add(t)
            persist(s)
        }
        log.info { "prompt template created: ${t.id} title='${t.title}'" }
        return t
    }

    fun update(id: String, title: String, category: String, body: String): Template {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isEmpty()) throw ApiException.localized(400, "empty_title", messageKey = "api.prompt.emptyTitle")
        if (cleanBody.isEmpty()) throw ApiException.localized(400, "empty_body", messageKey = "api.prompt.emptyBody")
        if (cleanTitle.length > MAX_TITLE_LEN)
            throw ApiException.localized(400, "title_too_long", messageKey = "api.prompt.titleTooLong", args = listOf(MAX_TITLE_LEN))
        if (cleanBody.length > MAX_BODY_LEN)
            throw ApiException.localized(400, "body_too_long", messageKey = "api.prompt.bodyTooLong", args = listOf(MAX_BODY_LEN))
        val cleanCat = category.trim().ifBlank { "General" }
        return lock.write {
            val s = read()
            val idx = s.templates.indexOfFirst { it.id == id }
            if (idx < 0) throw ApiException.localized(404, "template_not_found", messageKey = "api.prompt.notFound", args = listOf(id))
            val updated = s.templates[idx].copy(
                title = cleanTitle, category = cleanCat, body = cleanBody,
                updatedAt = clock.nowIso(),
            )
            s.templates[idx] = updated
            persist(s)
            updated
        }
    }

    fun delete(id: String): Boolean = lock.write {
        val s = read()
        val removed = s.templates.removeAll { it.id == id }
        if (removed) persist(s)
        removed
    }

    /** v1.115.0 — 즐겨찾기 고정 토글. 없는 id 면 null. */
    fun setPinned(id: String, pinned: Boolean): Template? = lock.write {
        val s = read()
        val idx = s.templates.indexOfFirst { it.id == id }
        if (idx < 0) return@write null
        val updated = s.templates[idx].copy(pinned = pinned, updatedAt = clock.nowIso())
        s.templates[idx] = updated
        persist(s)
        updated
    }

    /** v1.115.0 — 삽입 시 사용 빈도 +1 + lastUsedAt 갱신(정렬·추천용). 없는 id 면 null. */
    fun recordUse(id: String): Template? = lock.write {
        val s = read()
        val idx = s.templates.indexOfFirst { it.id == id }
        if (idx < 0) return@write null
        val now = clock.nowIso()
        // updatedAt 은 건드리지 않는다(목록 "수정일" 이 사용으로 흔들리지 않게).
        val updated = s.templates[idx].copy(useCount = s.templates[idx].useCount + 1, lastUsedAt = now)
        s.templates[idx] = updated
        persist(s)
        updated
    }

    /** v1.115.0 — 템플릿 복제. 제목 뒤 " (copy)" 부여, 사용횟수·고정은 초기화. */
    fun duplicate(id: String): Template = lock.write {
        val s = read()
        val src = s.templates.firstOrNull { it.id == id }
            ?: throw ApiException.localized(404, "template_not_found", messageKey = "api.prompt.notFound", args = listOf(id))
        if (s.templates.size >= MAX_TOTAL)
            throw ApiException.localized(400, "limit_reached", messageKey = "api.prompt.limitReached", args = listOf(MAX_TOTAL))
        val now = clock.nowIso()
        val copy = src.copy(
            id = Ids.taskId(),
            title = (src.title + " (copy)").take(MAX_TITLE_LEN),
            pinned = false, useCount = 0, lastUsedAt = null,
            createdAt = now, updatedAt = now,
        )
        s.templates.add(copy)
        persist(s)
        copy
    }

    /** v1.115.0 — 카테고리 목록(중복 제거, 대소문자 무시 정렬). 콤보박스 datalist 용. */
    fun categories(): List<String> = lock.read {
        read().templates.map { it.category }.filter { it.isNotBlank() }
            .distinct().sortedBy { it.lowercase() }
    }

    /**
     * v1.115.0 — 가져오기(import). [replace]=true 면 기존 전체 교체, false 면 병합(append).
     * 들어온 항목은 새 id 발급 + 검증/clamp(제목·본문 길이, 카테고리 General 폴백). MAX_TOTAL 초과분은 버림.
     * 반환값 = 실제 적재된 개수.
     */
    fun importTemplates(incoming: List<ImportItem>, replace: Boolean): Int = lock.write {
        val s = read()
        if (replace) s.templates.clear()
        val now = clock.nowIso()
        var added = 0
        for (item in incoming) {
            if (s.templates.size >= MAX_TOTAL) break
            val title = item.title.trim().take(MAX_TITLE_LEN)
            val body = item.body.trim().take(MAX_BODY_LEN)
            if (title.isEmpty() || body.isEmpty()) continue
            s.templates.add(
                Template(
                    id = Ids.taskId(), title = title,
                    category = item.category.trim().ifBlank { "General" }.take(100),
                    body = body, createdAt = now, updatedAt = now,
                    pinned = item.pinned, useCount = item.useCount.coerceAtLeast(0),
                ),
            )
            added++
        }
        persist(s)
        added
    }

    /** import 입력 항목(외부 JSON). id/타임스탬프는 무시하고 서버가 새로 발급. */
    @Serializable
    data class ImportItem(
        val title: String = "",
        val category: String = "",
        val body: String = "",
        val pinned: Boolean = false,
        val useCount: Int = 0,
    )

    private fun read(): Storage {
        val p = path()
        if (!p.exists()) return Storage()
        return try {
            val text = Files.readString(p, Charsets.UTF_8)
            json.decodeFromString(Storage.serializer(), text)
        } catch (e: Throwable) {
            // v1.33.2 (17차 Q-2) — 파싱 실패 시 빈 storage 로 진행하면 이후 create/update
            // 의 persist 가 corrupt 파일을 덮어써 기존 템플릿이 영구 소실. 백업 후 진행.
            val bak = p.resolveSibling("${p.fileName}.corrupt.${System.currentTimeMillis()}")
            runCatching { Files.copy(p, bak) }
                .onSuccess { log.warn(e) { "prompt-templates.json 파싱 실패 → $bak 로 백업 후 빈 storage" } }
                .onFailure { log.warn(e) { "prompt-templates.json 파싱 실패 + 백업 실패 → 빈 storage" } }
            Storage()
        }
    }

    private fun persist(s: Storage) {
        val p = path()
        Files.createDirectories(p.parent)
        val tmp = p.resolveSibling("${p.fileName}.tmp")
        Files.writeString(
            tmp, json.encodeToString(Storage.serializer(), s), Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val MAX_TITLE_LEN = 200
        const val MAX_BODY_LEN = 100_000
        const val MAX_TOTAL = 500
    }
}
