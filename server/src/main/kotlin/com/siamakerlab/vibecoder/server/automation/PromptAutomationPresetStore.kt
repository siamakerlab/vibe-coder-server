package com.siamakerlab.vibecoder.server.automation

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationMode
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetUpsertDto
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
 * v1.59.0 — 프롬프트 자동화 프리셋 저장소 (workspace 전역).
 *
 * `PromptTemplateStore` 와 같은 패턴: 단일 admin 환경이라 한 JSON 파일에 저장하고
 * ReentrantReadWriteLock + atomic write 로 보호.
 *   - 위치: `<workspace>/.vibecoder/prompt-automations.json`
 *   - 어느 프로젝트/세션에서나 같은 프리셋 재사용.
 */
class PromptAutomationPresetStore(
    private val workspace: WorkspacePath,
    private val clock: Clock,
) {

    @Serializable
    private data class Storage(
        val presets: MutableList<PromptAutomationPresetDto> = mutableListOf(),
    )

    private val lock = ReentrantReadWriteLock()

    private fun path(): Path =
        workspace.root.resolve(".vibecoder").resolve("prompt-automations.json")

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }

    fun listAll(): List<PromptAutomationPresetDto> = lock.read {
        read().presets.sortedBy { it.name.lowercase() }
    }

    fun get(id: String): PromptAutomationPresetDto? = lock.read {
        read().presets.firstOrNull { it.id == id }
    }

    fun create(req: PromptAutomationPresetUpsertDto): PromptAutomationPresetDto {
        val clean = validate(req)
        val now = clock.nowIso()
        val preset = clean.copy(id = Ids.taskId(), createdAt = now, updatedAt = now)
        lock.write {
            val s = read()
            if (s.presets.size >= MAX_TOTAL)
                throw ApiException.localized(400, "limit_reached", messageKey = "api.automation.limitReached", args = listOf(MAX_TOTAL))
            s.presets.add(preset)
            persist(s)
        }
        log.info { "prompt-automation preset created: ${preset.id} name='${preset.name}'" }
        return preset
    }

    fun update(id: String, req: PromptAutomationPresetUpsertDto): PromptAutomationPresetDto {
        val clean = validate(req)
        return lock.write {
            val s = read()
            val idx = s.presets.indexOfFirst { it.id == id }
            if (idx < 0) throw ApiException.localized(404, "preset_not_found", messageKey = "api.automation.notFound", args = listOf(id))
            val updated = clean.copy(
                id = id,
                createdAt = s.presets[idx].createdAt,
                updatedAt = clock.nowIso(),
            )
            s.presets[idx] = updated
            persist(s)
            updated
        }
    }

    fun delete(id: String): Boolean = lock.write {
        val s = read()
        val removed = s.presets.removeAll { it.id == id }
        if (removed) persist(s)
        removed
    }

    /**
     * 입력 검증 + 정규화. id/createdAt/updatedAt 은 placeholder("") 로 채워 반환 —
     * 호출자가 copy 로 실제 값 주입.
     */
    private fun validate(req: PromptAutomationPresetUpsertDto): PromptAutomationPresetDto {
        val name = req.name.trim()
        if (name.isEmpty()) throw ApiException.localized(400, "empty_name", messageKey = "api.automation.emptyName")
        if (name.length > MAX_NAME_LEN)
            throw ApiException.localized(400, "name_too_long", messageKey = "api.automation.nameTooLong", args = listOf(MAX_NAME_LEN))
        if (req.mode !in PromptAutomationMode.ALL)
            throw ApiException.localized(400, "invalid_mode", messageKey = "api.automation.invalidMode")
        val prompts = req.prompts.map { it.trim() }.filter { it.isNotEmpty() }
        if (prompts.isEmpty()) throw ApiException.localized(400, "empty_prompts", messageKey = "api.automation.emptyPrompts")
        if (prompts.size > MAX_PROMPTS)
            throw ApiException.localized(400, "too_many_prompts", messageKey = "api.automation.tooManyPrompts", args = listOf(MAX_PROMPTS))
        prompts.firstOrNull { it.length > MAX_PROMPT_LEN }?.let {
            throw ApiException.localized(400, "prompt_too_long", messageKey = "api.automation.promptTooLong", args = listOf(MAX_PROMPT_LEN))
        }
        val repeatCount = req.repeatCount.coerceIn(1, MAX_REPEAT)
        val loops = req.loops.coerceIn(1, MAX_LOOPS)
        return PromptAutomationPresetDto(
            id = "", name = name, mode = req.mode, prompts = prompts,
            repeatCount = repeatCount, loops = loops, stopOnError = req.stopOnError,
            createdAt = "", updatedAt = "",
        )
    }

    private fun read(): Storage {
        val p = path()
        if (!p.exists()) return Storage()
        return try {
            json.decodeFromString(Storage.serializer(), Files.readString(p, Charsets.UTF_8))
        } catch (e: Throwable) {
            // PromptTemplateStore 와 동일 — 파싱 실패 시 corrupt 백업 후 빈 storage.
            val bak = p.resolveSibling("${p.fileName}.corrupt.${System.currentTimeMillis()}")
            runCatching { Files.copy(p, bak) }
                .onSuccess { log.warn(e) { "prompt-automations.json 파싱 실패 → $bak 백업 후 빈 storage" } }
                .onFailure { log.warn(e) { "prompt-automations.json 파싱 실패 + 백업 실패 → 빈 storage" } }
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
        const val MAX_NAME_LEN = 120
        const val MAX_PROMPT_LEN = 8_000
        const val MAX_PROMPTS = 100      // sequence 리스트 상한
        const val MAX_REPEAT = 200       // repeat 횟수 상한
        const val MAX_LOOPS = 50         // sequence 전체 반복 상한
        const val MAX_TOTAL = 200        // 프리셋 개수 상한
    }
}
