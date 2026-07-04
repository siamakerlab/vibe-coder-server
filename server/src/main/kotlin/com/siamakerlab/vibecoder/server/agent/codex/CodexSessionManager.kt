package com.siamakerlab.vibecoder.server.agent.codex

import com.siamakerlab.vibecoder.server.agent.AgentContextSnapshot
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentSessionManager
import com.siamakerlab.vibecoder.server.claude.ConversationHistoryService
import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.PromptImageDto
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

private fun defaultCodexCmd(): String =
    System.getenv("CODEX_CMD")?.takeIf { it.isNotBlank() }
        ?: if (OsType.detect() == OsType.WINDOWS) "codex.cmd" else "codex"

class CodexSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val parser: CodexJsonParser = CodexJsonParser(),
    private val history: ConversationHistoryService? = null,
    private val usageRecorder: CodexUsageRecorder? = null,
    private val residentCapProvider: () -> Int = { config.codex.maxResidentSessions },
    private val codexCmdProvider: () -> String = { defaultCodexCmd() },
) : AgentSessionManager {
    override val provider: AgentProvider = AgentProvider.CODEX

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val busy = ConcurrentHashMap<String, Boolean>()
    private val lastTouched = ConcurrentHashMap<String, Instant>()
    private val pendingPrompts = ConcurrentHashMap<String, ArrayDeque<String>>()

    override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) {
        require(text.isNotBlank()) { "prompt text is required" }
        if (images.isNotEmpty()) {
            emitSystem(projectId, "codex_images_unsupported", "Codex provider does not support inline image DTOs yet.")
            throw IllegalArgumentException("Codex provider does not support images yet")
        }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PROMPT_BYTES) { "prompt too large ($bytes bytes UTF-8 > $MAX_PROMPT_BYTES)" }

        val lock = locks.computeIfAbsent(projectId) { Mutex() }
        lock.withLock {
            if (busy[projectId] == true) {
                val size = enqueuePrompt(projectId, text)
                emitSystem(projectId, "codex_prompt_queued", "Codex turn is already running. Queued prompt #$size.")
                return
            }
            startPromptLocked(projectId, text)
        }
    }

    override suspend fun startNew(projectId: String) {
        cancelTurn(projectId)
        clearQueuedPrompts(projectId)
        runCatching { threadIdFile(projectId).deleteIfExists() }
        runCatching { contextFile(projectId).deleteIfExists() }
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "codex_new_session", "Codex thread reset. The next prompt starts a fresh thread.")
    }

    override suspend fun cancelTurn(projectId: String) {
        clearQueuedPrompts(projectId)
        val proc = processes[projectId]
        if (proc?.isAlive == true) {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            emitSystem(projectId, "codex_cancelled", "Codex turn was cancelled.")
        }
        jobs[projectId]?.cancel()
        setBusy(projectId, ProjectState.STOPPED)
    }

    override fun isAlive(projectId: String): Boolean = processes[projectId]?.isAlive == true

    override fun isBusy(projectId: String): Boolean = busy[projectId] == true

    override fun currentSessionId(projectId: String): String? = readThreadId(projectId)

    override fun contextSnapshot(projectId: String): AgentContextSnapshot = readContext(projectId)

    override fun readProjectModel(projectId: String): String? {
        val f = modelFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    override fun effectiveModel(projectId: String): String? {
        val raw = readProjectModel(projectId) ?: config.codex.model
        return raw.trim().takeIf { it.isNotBlank() && !it.equals("default", ignoreCase = true) }
    }

    fun setProjectModel(projectId: String, model: String?) {
        val f = modelFile(projectId)
        val v = model?.trim().orEmpty()
        runCatching {
            if (v.isBlank()) {
                f.deleteIfExists()
            } else {
                Files.createDirectories(f.parent)
                f.writeText(v)
            }
        }.onFailure { log.warn(it) { "[$projectId] codex-model 저장 실패" } }
    }

    override suspend fun setProjectModelAndRestart(projectId: String, model: String?) {
        setProjectModel(projectId, model)
        if (isAlive(projectId) && !isBusy(projectId)) {
            processes[projectId]?.let { proc ->
                runCatching {
                    proc.destroy()
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                    processes.remove(projectId, proc)
                    lastTouched.remove(projectId)
                    busy.remove(projectId)
                }.onFailure { log.warn(it) { "[$projectId] Codex 모델 변경 후 프로세스 종료 실패" } }
            }
        }
    }

    fun shutdown() {
        processes.values.forEach { runCatching { it.destroyForcibly() } }
        scope.cancel()
    }

    private fun buildArgs(projectId: String, text: String, threadId: String?): List<String> {
        return buildCodexExecArgs(
            cmd = codexCmdProvider(),
            text = text,
            threadId = threadId,
            model = effectiveModel(projectId),
        )
    }

    private fun enqueuePrompt(projectId: String, text: String): Int {
        val queue = pendingPrompts.computeIfAbsent(projectId) { ArrayDeque() }
        queue.addLast(text)
        return queue.size
    }

    private fun clearQueuedPrompts(projectId: String) {
        pendingPrompts.remove(projectId)
    }

    private suspend fun startPromptLocked(projectId: String, text: String) {
        val sid = readThreadId(projectId)
        history?.userPrompt(projectId, sid, text, provider = provider.id)
        val root = workspace.projectRoot(projectId)
        if (!root.exists()) throw IllegalStateException("project root not found: $root")
        val args = buildArgs(projectId, text, sid)
        log.info { "[$projectId] spawning Codex: ${args.joinToString(" ")} (cwd=$root)" }
        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(args).also { pb ->
                pb.directory(root.toFile())
                pb.redirectErrorStream(false)
                applyCodexProcessEnv(pb)
            }.start()
        }
        runCatching { proc.outputStream.close() }
        processes[projectId] = proc
        setBusy(projectId, ProjectState.RESPONDING)
        reapResidentProcesses(excludeProjectId = projectId)
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
        val stderrTail = Collections.synchronizedList(mutableListOf<String>())
        jobs[projectId] = scope.launch {
            val stderrJob = launch {
                while (true) {
                    val line = withContext(Dispatchers.IO) { stderr.readLine() } ?: break
                    if (line.isNotBlank()) {
                        stderrTail.add(line)
                        while (stderrTail.size > MAX_STDERR_TAIL_LINES) stderrTail.removeAt(0)
                        log.debug { "[$projectId][codex stderr] $line" }
                    }
                }
            }
            try {
                while (true) {
                    val line = withContext(Dispatchers.IO) { stdout.readLine() } ?: break
                    handleEvent(projectId, parser.parseLine(line) ?: continue)
                }
                val ok = withContext(Dispatchers.IO) { proc.waitFor(1, TimeUnit.SECONDS); proc.exitValue() == 0 }
                if (busy[projectId] == true) {
                    setBusy(projectId, if (ok) ProjectState.READY else ProjectState.ERROR)
                    if (!ok) {
                        val detail = synchronized(stderrTail) { stderrTail.joinToString("\n").trim() }
                        val message = buildString {
                            append("Codex exited with status ${proc.exitValue()}.")
                            if (detail.isNotBlank()) append("\n").append(detail)
                        }
                        log.warn { "[$projectId] $message" }
                        emitSystem(projectId, "codex_exit", message)
                    }
                }
            } catch (t: Throwable) {
                log.warn(t) { "[$projectId] Codex reader failed" }
                emitSystem(projectId, "codex_error", t.message ?: "Codex reader failed")
                setBusy(projectId, ProjectState.ERROR)
            } finally {
                stderrJob.cancel()
                processes.remove(projectId, proc)
                lastTouched.remove(projectId)
                jobs.remove(projectId)
                startNextQueuedPrompt(projectId)
            }
        }
    }

    private suspend fun startNextQueuedPrompt(projectId: String) {
        val lock = locks.computeIfAbsent(projectId) { Mutex() }
        lock.withLock {
            if (busy[projectId] == true || processes[projectId]?.isAlive == true) return
            val queue = pendingPrompts[projectId] ?: return
            if (queue.isEmpty()) {
                pendingPrompts.remove(projectId)
                return
            }
            val next = queue.removeFirst()
            val remaining = queue.size
            if (queue.isEmpty()) pendingPrompts.remove(projectId)
            emitSystem(projectId, "codex_prompt_dequeued", "Running queued Codex prompt. Remaining: $remaining.")
            startPromptLocked(projectId, next)
        }
    }

    private suspend fun handleEvent(projectId: String, event: CodexEvent) {
        when (event) {
            is CodexEvent.ThreadStarted -> {
                writeThreadId(projectId, event.threadId)
                history?.adoptNullSession(projectId, event.threadId, provider = provider.id)
                history?.event(
                    projectId,
                    event.threadId,
                    ClaudeEvent.SessionStarted(
                        sessionId = event.threadId,
                        model = effectiveModel(projectId),
                        cwd = workspace.projectRoot(projectId).toString(),
                    ),
                    provider = provider.id,
                )
                hub.emitConsole(topic(projectId)) { seq ->
                    WsFrame.ConsoleSessionStarted(event.threadId, model = effectiveModel(projectId), cwd = workspace.projectRoot(projectId).toString(), seq = seq)
                }
            }
            CodexEvent.TurnStarted -> setBusy(projectId, ProjectState.RESPONDING)
            is CodexEvent.AgentMessage -> {
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleAssistant(event.text, isPartial = false, seq = seq) }
                history?.event(
                    projectId,
                    readThreadId(projectId),
                    ClaudeEvent.AssistantMessage(event.text, isPartial = false),
                    provider = provider.id,
                )
            }
            is CodexEvent.CommandStarted -> {
                val toolUseId = event.id ?: "codex_command_${System.nanoTime()}"
                val input = buildJsonObject { put("command", event.command) }
                history?.event(
                    projectId,
                    readThreadId(projectId),
                    ClaudeEvent.ToolUse("command_execution", input, toolUseId),
                    provider = provider.id,
                )
                hub.emitConsole(topic(projectId)) { seq ->
                    WsFrame.ConsoleToolUse(
                        "command_execution",
                        input,
                        toolUseId,
                        seq,
                    )
                }
            }
            is CodexEvent.CommandCompleted -> {
                val toolUseId = event.id ?: "codex_command_${System.nanoTime()}"
                val output = event.output ?: buildJsonObject {
                    event.command?.let { put("command", it) }
                }
                history?.event(
                    projectId,
                    readThreadId(projectId),
                    ClaudeEvent.ToolResult(toolUseId, output, isError = false),
                    provider = provider.id,
                )
                hub.emitConsole(topic(projectId)) { seq ->
                    WsFrame.ConsoleToolResult(
                        toolUseId,
                        output,
                        isError = false,
                        seq,
                    )
                }
            }
            is CodexEvent.TurnCompleted -> {
                event.usage?.let {
                    usageRecorder?.record(it)
                    val ctx = contextFromUsage(it)
                    writeContext(projectId, ctx)
                    hub.emitConsole(topic(projectId)) { seq ->
                        WsFrame.ConsoleContextUsage(ctx.input, ctx.cacheRead, ctx.cacheCreation, ctx.limit, seq)
                    }
                }
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleDone(event.reason, seq) }
                history?.event(projectId, readThreadId(projectId), ClaudeEvent.Done(event.reason), provider = provider.id)
                setBusy(projectId, ProjectState.READY)
            }
            is CodexEvent.TurnFailed -> {
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleError("codex_turn_failed", event.message, seq) }
                history?.event(
                    projectId,
                    readThreadId(projectId),
                    ClaudeEvent.ErrorEvent("codex_turn_failed", event.message),
                    provider = provider.id,
                )
                setBusy(projectId, ProjectState.ERROR)
            }
            is CodexEvent.Error -> {
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleError("codex_error", event.message, seq) }
                history?.event(
                    projectId,
                    readThreadId(projectId),
                    ClaudeEvent.ErrorEvent("codex_error", event.message),
                    provider = provider.id,
                )
                setBusy(projectId, ProjectState.ERROR)
            }
            is CodexEvent.Unknown -> {
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleUnknown(event.raw, seq) }
            }
        }
    }

    private suspend fun setBusy(projectId: String, state: ProjectState) {
        busy[projectId] = state.busy
        lastTouched[projectId] = Instant.now()
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleBusyState(state.busy, seq, state.wire) }
        hub.emitConsole("__projects__") { seq -> WsFrame.ProjectBusyChanged(projectId, state.busy, seq, state.wire) }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleSystem(code, message, seq) }
    }

    private fun topic(projectId: String): String = LogHub.consoleTopic(projectId, provider.id)

    private fun threadIdFile(projectId: String) = workspace.vibecoderDir(projectId).resolve("codex-thread.id")

    private fun contextFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("codex-context-tokens")

    private fun modelFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("codex-model")

    private fun writeContext(projectId: String, snapshot: AgentContextSnapshot) {
        val f = contextFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText("${snapshot.input},${snapshot.cacheRead},${snapshot.cacheCreation},${snapshot.limit}")
    }

    private fun readContext(projectId: String): AgentContextSnapshot =
        runCatching {
            val f = contextFile(projectId)
            if (!f.exists()) return@runCatching AgentContextSnapshot()
            val parts = f.readText().trim().split(",")
            AgentContextSnapshot(
                input = parts.getOrNull(0)?.toLongOrNull() ?: 0,
                cacheRead = parts.getOrNull(1)?.toLongOrNull() ?: 0,
                cacheCreation = parts.getOrNull(2)?.toLongOrNull() ?: 0,
                limit = parts.getOrNull(3)?.toLongOrNull() ?: 0,
            )
        }.getOrDefault(AgentContextSnapshot())

    private fun contextFromUsage(usage: JsonObject): AgentContextSnapshot {
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val cacheRead = usage["cached_input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val uncachedInput = (input - cacheRead).coerceAtLeast(0)
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = usage["reasoning_output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val used = input + output + reasoning
        return AgentContextSnapshot(
            input = uncachedInput,
            cacheRead = cacheRead,
            cacheCreation = 0,
            limit = codexContextLimit(used),
        )
    }

    private fun codexContextLimit(used: Long): Long = when {
        used <= 0L -> 0L
        used <= 128_000L -> 128_000L
        used <= 200_000L -> 200_000L
        else -> used
    }

    private fun readThreadId(projectId: String): String? =
        runCatching { threadIdFile(projectId).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()

    private fun writeThreadId(projectId: String, threadId: String) {
        val f = threadIdFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText(threadId)
    }

    private fun reapResidentProcesses(excludeProjectId: String) {
        val cap = residentCapProvider().coerceAtLeast(0)
        if (cap <= 0) return
        val alive = processes.filterValues { it.isAlive }
        val excess = alive.size - cap
        if (excess <= 0) return
        alive.entries
            .asSequence()
            .filter { (projectId, _) -> projectId != excludeProjectId && busy[projectId] != true }
            .sortedBy { (projectId, _) -> lastTouched[projectId] ?: Instant.EPOCH }
            .take(excess)
            .forEach { (projectId, proc) ->
                runCatching {
                    log.info { "[$projectId] reaping idle Codex process due to maxResidentSessions=$cap" }
                    proc.destroy()
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                    processes.remove(projectId, proc)
                    lastTouched.remove(projectId)
                    busy.remove(projectId)
                }.onFailure { log.warn(it) { "[$projectId] Codex resident process reap failed" } }
            }
    }

    private fun applyCodexProcessEnv(pb: ProcessBuilder) {
        pb.environment().putIfAbsent("HOME", "/home/vibe")
        pb.environment().putIfAbsent("XDG_CONFIG_HOME", "/home/vibe/.config")
        pb.environment().putIfAbsent("CODEX_HOME", "/home/vibe/.config/codex")
    }

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        private const val MAX_STDERR_TAIL_LINES = 12
    }
}

internal fun buildCodexExecArgs(
    cmd: String,
    text: String,
    threadId: String?,
    model: String?,
): List<String> =
    buildList {
        add(cmd)
        // `--ask-for-approval` is a top-level Codex option in v0.142.x. If it is
        // placed after `exec`, Codex exits with status 2 before the turn starts.
        add("--ask-for-approval"); add("never")
        add("exec")
        add("--json")
        add("--sandbox"); add("danger-full-access")
        add("--skip-git-repo-check")
        model?.let { add("--model"); add(it) }
        if (threadId != null) {
            add("resume"); add(threadId)
        }
        add(text)
    }
