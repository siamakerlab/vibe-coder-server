package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val consoleTuiIngestLog = KotlinLogging.logger {}

/**
 * Best-effort native transcript ingestor for PTY-backed provider TUI sessions.
 *
 * The terminal screen is not parsed. For Claude, we read appended JSONL records from the
 * Claude project transcript directory and persist assistant text messages into the
 * existing conversation DB. Existing files are baselined before TUI prompt injection so old
 * history is not imported wholesale.
 */
class ConsoleTuiHistoryIngestService(
    private val workspace: WorkspacePath,
    private val conversationRepo: ConversationTurnRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val ingestJobs = ConcurrentHashMap<IngestKey, Job>()

    fun prepare(projectId: String, provider: AgentProvider, workdir: Path) {
        val state = readState(projectId, provider)
        var changed = false
        when (provider) {
            AgentProvider.CLAUDE -> {
                for (file in claudeTranscriptFiles(workdir)) {
                    val key = file.toString()
                    if (!state.offsets.containsKey(key)) {
                        state.offsets[key] = runCatching { file.fileSize() }.getOrDefault(0L)
                        changed = true
                    }
                }
            }
            AgentProvider.OPENCODE -> {
                val key = openCodePartOffsetKey()
                if (!state.offsets.containsKey(key)) {
                    val baselineCreatedAt = openCodeMaxAssistantCreatedAt(workdir)
                    state.offsets[key] = baselineCreatedAt
                    for (turn in openCodeAssistantTurns(workdir, atOrAfterCreatedAt = baselineCreatedAt, alreadyImported = state.importedKeys)) {
                        state.importedKeys.add(turn.importKey)
                    }
                    changed = true
                }
                openCodeLogFile().takeIf { it.isRegularFile() }?.let { file ->
                    val key = file.toString()
                    if (!state.offsets.containsKey(key)) {
                        state.offsets[key] = runCatching { file.fileSize() }.getOrDefault(0L)
                        changed = true
                    }
                }
                if (changed) state.trimImportedKeys()
            }
            AgentProvider.CODEX -> {
                for (rollout in codexRolloutFiles(workdir)) {
                    val key = rollout.path.toString()
                    if (!state.offsets.containsKey(key)) {
                        state.offsets[key] = runCatching { rollout.path.fileSize() }.getOrDefault(0L)
                        changed = true
                    }
                }
            }
        }
        if (changed) writeState(projectId, provider, state)
    }

    fun scheduleIngest(
        projectId: String,
        provider: AgentProvider,
        workdir: Path,
        sessionId: String?,
        onAssistantImported: () -> Unit = {},
    ) {
        val key = IngestKey(projectId, provider)
        ingestJobs.remove(key)?.cancel()
        val job = scope.launch {
            repeat(60) {
                delay(2_000)
                runCatching {
                    when (provider) {
                        AgentProvider.CLAUDE -> ingestClaudeNow(projectId, provider, workdir, sessionId, onAssistantImported)
                        AgentProvider.OPENCODE -> ingestOpenCodeNow(projectId, provider, workdir, sessionId, onAssistantImported)
                        AgentProvider.CODEX -> ingestCodexNow(projectId, provider, workdir, sessionId, onAssistantImported)
                    }
                }
                    .onFailure { consoleTuiIngestLog.debug(it) { "console TUI history ingest failed: ${it.message}" } }
            }
        }
        job.invokeOnCompletion { ingestJobs.remove(key, job) }
        ingestJobs[key] = job
    }

    fun shutdown() {
        ingestJobs.values.forEach { it.cancel() }
        ingestJobs.clear()
        scope.cancel()
    }

    private fun ingestClaudeNow(
        projectId: String,
        provider: AgentProvider,
        workdir: Path,
        fallbackSessionId: String?,
        onAssistantImported: () -> Unit,
    ) {
        val state = readState(projectId, provider)
        var changed = false
        for (file in claudeTranscriptFiles(workdir)) {
            val key = file.toString()
            val size = runCatching { file.fileSize() }.getOrDefault(0L)
            val offset = state.offsets[key] ?: 0L
            if (size <= offset) continue
            val lines = readLinesFrom(file, offset)
            for (line in lines) {
                val parsed = parseClaudeAssistantTranscriptLine(line, fallbackSessionId) ?: continue
                if (!state.importedKeys.add(parsed.importKey)) continue
                importClaudeTurn(projectId, parsed)
                onAssistantImported()
                changed = true
            }
            state.trimImportedKeys()
            state.offsets[key] = size
            changed = true
        }
        if (changed) writeState(projectId, provider, state)
    }

    private fun ingestOpenCodeNow(
        projectId: String,
        provider: AgentProvider,
        workdir: Path,
        fallbackSessionId: String?,
        onAssistantImported: () -> Unit,
    ) {
        val state = readState(projectId, provider)
        var changed = false
        val partOffsetKey = openCodePartOffsetKey()
        var maxCreatedAt = state.offsets[partOffsetKey] ?: 0L
        for (turn in openCodeAssistantTurns(workdir, atOrAfterCreatedAt = maxCreatedAt, alreadyImported = state.importedKeys)) {
            if (!state.importedKeys.add(turn.importKey)) continue
            importOpenCodeTurn(projectId, turn, fallbackSessionId)
            onAssistantImported()
            maxCreatedAt = maxOf(maxCreatedAt, turn.createdAt)
            changed = true
        }
        if (maxCreatedAt > (state.offsets[partOffsetKey] ?: 0L)) {
            state.offsets[partOffsetKey] = maxCreatedAt
            changed = true
        }
        val logFile = openCodeLogFile()
        val logKey = logFile.toString()
        val logSize = runCatching { logFile.fileSize() }.getOrDefault(0L)
        val logOffset = state.offsets[logKey] ?: logSize
        if (logFile.isRegularFile() && logSize > logOffset) {
            val sessionIds = openCodeSessionIds(workdir)
            for (line in readLinesFrom(logFile, logOffset)) {
                val parsed = parseOpenCodeLogErrorLine(line, sessionIds) ?: continue
                if (!state.importedKeys.add(parsed.importKey)) continue
                importOpenCodeTurn(projectId, parsed, fallbackSessionId)
                onAssistantImported()
                changed = true
            }
            state.offsets[logKey] = logSize
            changed = true
        }
        if (changed) {
            state.trimImportedKeys()
            writeState(projectId, provider, state)
        }
    }

    private fun parseClaudeAssistantTranscriptLine(line: String, fallbackSessionId: String?): ClaudeAssistantTranscriptTurn? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        return parseClaudeAssistantTranscriptLine(obj, line, fallbackSessionId)
    }

    private fun importClaudeTurn(projectId: String, turn: ClaudeAssistantTranscriptTurn) {
        conversationRepo.insert(
            projectId = projectId,
            provider = AgentProvider.CLAUDE.id,
            sessionId = turn.sessionId,
            role = "assistant",
            content = turn.text,
            tokensIn = turn.tokensIn,
            tokensOut = turn.tokensOut,
            raw = turn.raw.take(20_000),
        )
        if (turn.hasUsage) {
            conversationRepo.insert(
                projectId = projectId,
                provider = AgentProvider.CLAUDE.id,
                sessionId = turn.sessionId,
                role = "usage",
                content = """{"input":${turn.inputTokens},"output":${turn.outputTokens},"""" +
                    """"cacheRead":${turn.cacheReadTokens},"cacheCreate":${turn.cacheCreationTokens}}""",
                tokensIn = (turn.inputTokens + turn.cacheReadTokens + turn.cacheCreationTokens).toIntSafe(),
                tokensOut = turn.outputTokens.toIntSafe(),
            )
        }
    }

    private fun importOpenCodeTurn(
        projectId: String,
        turn: OpenCodeAssistantTranscriptTurn,
        fallbackSessionId: String?,
    ) {
        conversationRepo.insert(
            projectId = projectId,
            provider = AgentProvider.OPENCODE.id,
            sessionId = fallbackSessionId ?: turn.sessionId,
            role = "assistant",
            content = turn.text,
            tokensIn = turn.tokensIn,
            tokensOut = turn.tokensOut,
            raw = turn.raw.take(20_000),
        )
        if (turn.tokensIn != null || turn.tokensOut != null) {
            conversationRepo.insert(
                projectId = projectId,
                provider = AgentProvider.OPENCODE.id,
                sessionId = fallbackSessionId ?: turn.sessionId,
                role = "usage",
                content = """{"input":${turn.tokensIn ?: 0},"output":${turn.tokensOut ?: 0},"cacheRead":0,"cacheCreate":0}""",
                tokensIn = turn.tokensIn,
                tokensOut = turn.tokensOut,
            )
        }
    }

    private fun ingestCodexNow(
        projectId: String,
        provider: AgentProvider,
        workdir: Path,
        fallbackSessionId: String?,
        onAssistantImported: () -> Unit,
    ) {
        val state = readState(projectId, provider)
        var changed = false
        for (rollout in codexRolloutFiles(workdir)) {
            val key = rollout.path.toString()
            val size = runCatching { rollout.path.fileSize() }.getOrDefault(0L)
            val offset = state.offsets[key] ?: 0L
            if (size <= offset) continue
            val sessionId = fallbackSessionId ?: rollout.threadId
            for (line in readLinesFrom(rollout.path, offset)) {
                val parsed = parseCodexAssistantRolloutLine(
                    line = line,
                    fallbackSessionId = sessionId,
                    nativeSessionKey = rollout.threadId,
                ) ?: continue
                if (!state.importedKeys.add(parsed.importKey)) continue
                importCodexTurn(projectId, parsed)
                onAssistantImported()
                changed = true
            }
            state.trimImportedKeys()
            state.offsets[key] = size
            changed = true
        }
        if (changed) writeState(projectId, provider, state)
    }

    private fun importCodexTurn(projectId: String, turn: CodexAssistantTranscriptTurn) {
        conversationRepo.insert(
            projectId = projectId,
            provider = AgentProvider.CODEX.id,
            sessionId = turn.sessionId,
            role = "assistant",
            content = turn.text,
            raw = turn.raw.take(20_000),
        )
    }

    private fun claudeTranscriptFiles(workdir: Path): List<Path> {
        val home = System.getenv("CLAUDE_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".claude") }
            ?: Path.of("/home/vibe/.claude")
        val dir = home.resolve("projects").resolve(encodeClaudeProjectPath(workdir))
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.name.endsWith(".jsonl") }
                .sorted(Comparator.comparing<Path, Long> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .toList()
        }
    }

    private fun readLinesFrom(file: Path, offset: Long): List<String> {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(offset.coerceAtLeast(0))
            val out = mutableListOf<String>()
            while (true) {
                val raw = raf.readLine() ?: break
                out += raw.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
            }
            return out
        }
    }

    private fun openCodeAssistantTurns(
        workdir: Path,
        atOrAfterCreatedAt: Long,
        alreadyImported: Set<String>,
    ): List<OpenCodeAssistantTranscriptTurn> {
        val db = openCodeDbFile()
        if (!db.isRegularFile()) return emptyList()
        val dir = workdir.toAbsolutePath().normalize().toString()
        val importedOpenCodeKeys = alreadyImported
            .asSequence()
            .filter { it.startsWith("opencode:") }
            .take(MAX_IMPORTED_KEYS)
            .toList()
        val importedClause = if (importedOpenCodeKeys.isEmpty()) "" else {
            importedOpenCodeKeys.joinToString(
                prefix = " AND ('opencode:' || p.session_id || ':' || p.id) NOT IN (",
                postfix = ")",
            ) { sqliteLiteral(it) }
        }
        val sql = """
            SELECT json_object(
              'partId', p.id,
              'sessionId', p.session_id,
              'messageId', p.message_id,
              'createdAt', COALESCE(p.time_created, 0),
              'text', json_extract(p.data, '$.text'),
              'tokensIn', json_extract(m.data, '$.tokens.input'),
              'tokensOut', json_extract(m.data, '$.tokens.output'),
              'raw', p.data
            )
            FROM part p
            JOIN message m ON m.id = p.message_id
            JOIN session s ON s.id = p.session_id
            WHERE s.directory = ${sqliteLiteral(dir)}
              AND json_extract(m.data, '$.role') = 'assistant'
              AND json_extract(p.data, '$.type') = 'text'
              AND COALESCE(p.time_created, 0) >= $atOrAfterCreatedAt
              $importedClause
            ORDER BY p.time_created ASC, p.id ASC
            LIMIT 200
        """.trimIndent()
        val output = runSqliteReadonly(db, sql)
        if (output.isBlank()) return emptyList()
        return output.lineSequence()
            .mapNotNull { line -> parseOpenCodeAssistantRow(line, json) }
            .filter { it.text.isNotBlank() }
            .toList()
    }

    private fun openCodeMaxAssistantCreatedAt(workdir: Path): Long {
        val db = openCodeDbFile()
        if (!db.isRegularFile()) return 0L
        val dir = workdir.toAbsolutePath().normalize().toString()
        val sql = """
            SELECT COALESCE(MAX(p.time_created), 0)
            FROM part p
            JOIN message m ON m.id = p.message_id
            JOIN session s ON s.id = p.session_id
            WHERE s.directory = ${sqliteLiteral(dir)}
              AND json_extract(m.data, '$.role') = 'assistant'
              AND json_extract(p.data, '$.type') = 'text'
        """.trimIndent()
        return runSqliteReadonly(openCodeDbFile(), sql)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.toLongOrNull()
            ?: 0L
    }

    private fun openCodeDbFile(): Path {
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".local/share") }
            ?: Path.of("/home/vibe/.local/share")
        return dataHome.resolve("opencode").resolve("opencode.db")
    }

    private fun openCodeLogFile(): Path {
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".local/share") }
            ?: Path.of("/home/vibe/.local/share")
        return dataHome.resolve("opencode").resolve("log").resolve("opencode.log")
    }

    private fun openCodePartOffsetKey(): String =
        "${openCodeDbFile()}:assistantPartTime"

    private fun openCodeSessionIds(workdir: Path): Set<String> {
        val db = openCodeDbFile()
        if (!db.isRegularFile()) return emptySet()
        val dir = workdir.toAbsolutePath().normalize().toString()
        val sql = """
            SELECT id
            FROM session
            WHERE directory = ${sqliteLiteral(dir)}
            ORDER BY time_created ASC, id ASC
        """.trimIndent()
        val output = runSqliteReadonly(db, sql)
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun codexRolloutFiles(workdir: Path): List<CodexRolloutFile> {
        val db = codexStateDbFile() ?: return emptyList()
        val dir = workdir.toAbsolutePath().normalize().toString()
        val sql = """
            SELECT id, rollout_path
            FROM threads
            WHERE cwd = ${sqliteLiteral(dir)}
              AND rollout_path <> ''
            ORDER BY updated_at_ms ASC, id ASC
        """.trimIndent()
        val output = runSqliteReadonly(db, sql, separator = "\t")
        if (output.isBlank()) return emptyList()
        return output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                val pathText = parts.getOrNull(1)?.trim().orEmpty()
                if (pathText.isBlank()) return@mapNotNull null
                val path = Path.of(pathText)
                if (!path.isRegularFile()) return@mapNotNull null
                CodexRolloutFile(path = path, threadId = parts.getOrNull(0)?.trim()?.ifBlank { null })
            }
            .toList()
    }

    private fun codexStateDbFile(): Path? {
        val home = codexHome()
        if (!home.isDirectory()) return null
        return Files.list(home).use { stream ->
            stream
                .filter { it.isRegularFile() && it.name.startsWith("state_") && it.name.endsWith(".sqlite") }
                .sorted(Comparator.comparing<Path, Long> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }.reversed())
                .findFirst()
                .orElse(null)
        } ?: home.resolve("state.sqlite").takeIf { it.isRegularFile() }
    }

    private fun codexHome(): Path =
        System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".codex") }
            ?: Path.of("/home/vibe/.codex")

    private fun runSqliteReadonly(db: Path, sql: String, separator: String? = null): String {
        if (!db.isRegularFile()) return ""
        return runCatching {
            val args = buildList {
                add("sqlite3")
                add("-readonly")
                add("-batch")
                separator?.let { add("-separator"); add(it) }
                add("-noheader")
                add(db.toString())
                add(sql)
            }
            ProcessBuilder(args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .let { process ->
                    val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        ""
                    } else if (process.exitValue() == 0) {
                        text
                    } else {
                        ""
                    }
                }
        }.getOrDefault("")
    }

    private fun stateFile(projectId: String, provider: AgentProvider): Path =
        workspace.vibecoderDir(projectId)
            .resolve("console-tui-ingest")
            .also { it.createDirectories() }
            .resolve("${provider.id}.json")

    private fun readState(projectId: String, provider: AgentProvider): IngestState {
        val file = stateFile(projectId, provider)
        if (!file.exists()) return IngestState()
        val obj = runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return IngestState()
        val offsetsObj = obj["offsets"] as? JsonObject ?: return IngestState()
        val imported = (obj["importedKeys"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toMutableSet()
            ?: linkedSetOf()
        return IngestState(
            offsets = offsetsObj.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            }.toMutableMap(),
            importedKeys = imported,
        )
    }

    private fun writeState(projectId: String, provider: AgentProvider, state: IngestState) {
        val file = stateFile(projectId, provider)
        val body = buildJsonObject {
            put("offsets", buildJsonObject {
                for ((path, offset) in state.offsets) put(path, offset)
            })
            put("importedKeys", buildJsonArray {
                state.importedKeys.forEach { add(it) }
            })
        }.toString()
        file.writeText(body)
    }

    private data class IngestState(
        val offsets: MutableMap<String, Long> = linkedMapOf(),
        val importedKeys: MutableSet<String> = linkedSetOf(),
    ) {
        fun trimImportedKeys() {
            if (importedKeys.size <= MAX_IMPORTED_KEYS) return
            val keep = importedKeys.toList().takeLast(MAX_IMPORTED_KEYS)
            importedKeys.clear()
            importedKeys.addAll(keep)
        }
    }

    companion object {
        private const val MAX_IMPORTED_KEYS = 2_000
    }

    private data class IngestKey(
        val projectId: String,
        val provider: AgentProvider,
    )
}

internal data class ClaudeAssistantTranscriptTurn(
    val importKey: String,
    val sessionId: String?,
    val text: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val raw: String,
) {
    val hasUsage: Boolean get() =
        inputTokens > 0 || outputTokens > 0 || cacheReadTokens > 0 || cacheCreationTokens > 0
}

internal data class OpenCodeAssistantTranscriptTurn(
    val importKey: String,
    val sessionId: String?,
    val text: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val raw: String,
    val createdAt: Long = 0L,
)

internal data class CodexAssistantTranscriptTurn(
    val importKey: String,
    val sessionId: String?,
    val text: String,
    val raw: String,
)

private data class CodexRolloutFile(
    val path: Path,
    val threadId: String?,
)

internal fun parseOpenCodeAssistantRow(
    line: String,
    json: Json = Json { ignoreUnknownKeys = true },
): OpenCodeAssistantTranscriptTurn? {
    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    val partId = obj.string("partId") ?: return null
    val sessionId = obj.string("sessionId")
    val text = obj.string("text")?.trim().orEmpty()
    if (text.isBlank()) return null
    return OpenCodeAssistantTranscriptTurn(
        importKey = "opencode:$sessionId:$partId",
        sessionId = sessionId,
        text = text,
        tokensIn = obj.long("tokensIn")?.toIntSafe(),
        tokensOut = obj.long("tokensOut")?.toIntSafe(),
        raw = obj.string("raw") ?: line,
        createdAt = obj.long("createdAt") ?: 0L,
    )
}

internal fun parseOpenCodeLogErrorLine(
    line: String,
    allowedSessionIds: Set<String>,
): OpenCodeAssistantTranscriptTurn? {
    if (!line.contains("message=\"stream error\"")) return null
    val sessionId = Regex("""session\.id=([^ ]+)""").find(line)?.groupValues?.getOrNull(1) ?: return null
    if (allowedSessionIds.isNotEmpty() && sessionId !in allowedSessionIds) return null
    val rawError = Regex("error\\.error=\"((?:\\\\.|[^\"])*)\"").find(line)?.groupValues?.getOrNull(1)
        ?: return null
    val error = rawError
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .trim()
    if (error.isBlank()) return null
    val providerId = Regex("""providerID=([^ ]+)""").find(line)?.groupValues?.getOrNull(1)
    val modelId = Regex("""modelID=([^ ]+)""").find(line)?.groupValues?.getOrNull(1)
    val prefix = buildString {
        append("OpenCode provider error")
        if (!providerId.isNullOrBlank() || !modelId.isNullOrBlank()) {
            append(" (")
            append(listOfNotNull(providerId, modelId).joinToString("/"))
            append(")")
        }
        append(": ")
    }
    return OpenCodeAssistantTranscriptTurn(
        importKey = "opencode-log:$sessionId:${line.hashCode()}:${error.hashCode()}",
        sessionId = sessionId,
        text = prefix + error,
        tokensIn = null,
        tokensOut = null,
        raw = line,
    )
}

internal fun parseCodexAssistantRolloutLine(
    line: String,
    fallbackSessionId: String?,
    nativeSessionKey: String? = null,
    json: Json = Json { ignoreUnknownKeys = true },
): CodexAssistantTranscriptTurn? {
    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    if (obj.string("type") != "response_item") return null
    val payload = obj["payload"]?.jsonObject ?: return null
    if (payload.string("type") != "message" || payload.string("role") != "assistant") return null
    val phase = payload.string("phase")
    if (phase != null && phase != "final_answer") return null
    val text = codexAssistantText(payload["content"]).trim()
    if (text.isBlank()) return null
    val sessionId = fallbackSessionId
    val nativeId = payload.string("id")
        ?: payload["internal_chat_message_metadata_passthrough"]?.jsonObject?.string("turn_id")
        ?: obj.string("timestamp")
    val importSessionKey = nativeSessionKey ?: sessionId
    val importKey = nativeId
        ?.let { "codex:$importSessionKey:$it" }
        ?: "codex:$importSessionKey:${line.hashCode()}:${text.hashCode()}"
    return CodexAssistantTranscriptTurn(
        importKey = importKey,
        sessionId = sessionId,
        text = text,
        raw = line,
    )
}

internal fun parseClaudeAssistantTranscriptLine(
    obj: JsonObject,
    raw: String,
    fallbackSessionId: String?,
): ClaudeAssistantTranscriptTurn? {
    if (obj.string("type") != "assistant") return null
    val message = obj["message"]?.jsonObject ?: return null
    if (message.string("role") != "assistant") return null
    val text = claudeAssistantText(message["content"]).trim()
    if (text.isBlank()) return null
    val nativeSessionId = obj.string("sessionId")
    val sessionId = fallbackSessionId ?: nativeSessionId
    val usage = message["usage"]?.jsonObject
    val input = usage?.long("input_tokens") ?: 0L
    val output = usage?.long("output_tokens") ?: 0L
    val cacheRead = usage?.long("cache_read_input_tokens") ?: 0L
    val cacheCreation = usage?.long("cache_creation_input_tokens") ?: 0L
    val nativeId = obj.string("uuid") ?: message.string("id")
    val importSessionKey = nativeSessionId ?: sessionId
    val importKey = nativeId
        ?.let { "claude:$importSessionKey:$it" }
        ?: "claude:$importSessionKey:${raw.hashCode()}:${text.hashCode()}"
    return ClaudeAssistantTranscriptTurn(
        importKey = importKey,
        sessionId = sessionId,
        text = text,
        tokensIn = (input + cacheRead + cacheCreation).toIntSafe(),
        tokensOut = output.toIntSafe(),
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = cacheRead,
        cacheCreationTokens = cacheCreation,
        raw = raw,
    )
}

private fun claudeAssistantText(content: kotlinx.serialization.json.JsonElement?): String {
    val arr = content as? JsonArray ?: return ""
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        if (obj.string("type") == "text") obj.string("text") else null
    }.joinToString("\n\n")
}

private fun codexAssistantText(content: kotlinx.serialization.json.JsonElement?): String {
    val arr = content as? JsonArray ?: return ""
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        when (obj.string("type")) {
            "output_text", "text" -> obj.string("text")
            else -> null
        }
    }.joinToString("\n\n")
}

private fun encodeClaudeProjectPath(workdir: Path): String =
    workdir.toAbsolutePath().normalize().toString().replace('/', '-')

private fun sqliteLiteral(value: String): String =
    "'" + value.replace("'", "''") + "'"

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun Long.toIntSafe(): Int =
    coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
