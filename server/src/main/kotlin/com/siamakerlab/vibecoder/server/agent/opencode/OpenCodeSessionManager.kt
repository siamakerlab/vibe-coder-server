package com.siamakerlab.vibecoder.server.agent.opencode

import com.siamakerlab.vibecoder.server.agent.AgentContextSnapshot
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.AgentSessionManager
import com.siamakerlab.vibecoder.server.claude.ClaudeEvent
import com.siamakerlab.vibecoder.server.claude.ConversationHistoryService
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

private fun defaultOpenCodeCmd(): String =
    System.getenv("OPENCODE_CMD")?.takeIf { it.isNotBlank() }
        ?: if (OsType.detect() == OsType.WINDOWS) "opencode.cmd" else "opencode"

/**
 * v1.150.0 — Phase 2 OpenCode provider 세션 매니저. [AgentSessionManager] 를 직접 구현
 * (CodexSessionManager 구조 차용, 독립 구현 — AGENTS.md "AI Provider 구조").
 *
 * opencode CLI 를 `run --format json` 1회성 exec 모드로 spawn 한다. Codex 와 동일하게 turn
 * 단위 프로세스 — 매 prompt 마다 새 spawn, stdout NDJSON 스트림을 읽어 [handleEvent] 로 변환.
 * thread id(= opencode sessionID `ses_...`) 파일로 resume (`-s <id>`).
 *
 * Phase 0 turn 관찰 hook([turnDoneListener]/[turnInterruptListener]) + Phase 1 부팅 reconcile
 * ([reconcileInterruptedTurnsAsync]) 를 동일 패턴으로 지원해 자동화/예약/알림이 Claude/Codex 와
 * 동일하게 동작한다. rate-limit 자동 재개는 opencode rate-limit 이벤트 형식이 확정되면 별도 추가.
 */
class OpenCodeSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val parser: OpenCodeJsonParser = OpenCodeJsonParser(),
    private val history: ConversationHistoryService? = null,
    private val residentCapProvider: () -> Int = { config.opencode.maxResidentSessions },
    private val opencodeCmdProvider: () -> String = { defaultOpenCodeCmd() },
) : AgentSessionManager {
    override val provider: AgentProvider = AgentProvider.OPENCODE

    @Volatile
    override var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    @Volatile
    override var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    private fun fireTurnDone(projectId: String, reason: String) {
        val l = turnDoneListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] opencode turnDoneListener failed" } } }
    }

    private fun fireInterrupt(projectId: String, reason: String) {
        val l = turnInterruptListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] opencode turnInterruptListener failed" } } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val busy = ConcurrentHashMap<String, Boolean>()
    private val lastTouched = ConcurrentHashMap<String, Instant>()
    private val pendingPrompts = ConcurrentHashMap<String, ArrayDeque<String>>()
    /** 이미 thread 로 저장한 sessionID 캐시 — 첫 이벤트에서 1회 thread.started 처리. */
    private val knownSessions = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override suspend fun sendPrompt(projectId: String, text: String, images: List<PromptImageDto>) {
        require(text.isNotBlank()) { "prompt text is required" }
        if (images.isNotEmpty()) {
            emitSystem(projectId, "opencode_images_unsupported", "OpenCode provider does not support inline image DTOs yet.")
            throw IllegalArgumentException("OpenCode provider does not support images yet")
        }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PROMPT_BYTES) { "prompt too large ($bytes bytes UTF-8 > $MAX_PROMPT_BYTES)" }

        val lock = locks.computeIfAbsent(projectId) { Mutex() }
        lock.withLock {
            if (busy[projectId] == true) {
                val size = enqueuePrompt(projectId, text)
                emitSystem(projectId, "opencode_prompt_queued", "OpenCode turn is already running. Queued prompt #$size.")
                return
            }
            startPromptLocked(projectId, text)
        }
    }

    override suspend fun startNew(projectId: String) {
        cancelTurn(projectId)
        clearQueuedPrompts(projectId)
        runCatching { sessionIdFile(projectId).deleteIfExists() }
        runCatching { contextFile(projectId).deleteIfExists() }
        knownSessions.remove(projectId)
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "opencode_new_session", "OpenCode session reset. The next prompt starts a fresh session.")
        fireInterrupt(projectId, "new_session")
    }

    override suspend fun cancelTurn(projectId: String) {
        clearTurnActive(projectId)
        clearQueuedPrompts(projectId)
        val proc = processes[projectId]
        if (proc?.isAlive == true) {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            emitSystem(projectId, "opencode_cancelled", "OpenCode turn was cancelled.")
        }
        jobs[projectId]?.cancel()
        setBusy(projectId, ProjectState.STOPPED)
    }

    override suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto>) {
        emitSystem(projectId, "opencode_interrupt_send", "진행 중 OpenCode turn을 중단하고 새 프롬프트를 시작합니다.")
        cancelTurn(projectId)
        sendPrompt(projectId, text, images)
    }

    override fun isAlive(projectId: String): Boolean = processes[projectId]?.isAlive == true

    override fun isBusy(projectId: String): Boolean = busy[projectId] == true

    override fun currentSessionId(projectId: String): String? = readSessionId(projectId)

    override fun contextSnapshot(projectId: String): AgentContextSnapshot = readContext(projectId)

    override fun readProjectModel(projectId: String): String? {
        val f = modelFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    override fun effectiveModel(projectId: String): String? {
        val raw = readProjectModel(projectId) ?: config.opencode.model
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
        }.onFailure { log.warn(it) { "[$projectId] opencode-model 저장 실패" } }
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
                }.onFailure { log.warn(it) { "[$projectId] OpenCode 모델 변경 후 프로세스 종료 실패" } }
            }
        }
    }

    fun shutdown() {
        processes.values.forEach { runCatching { it.destroyForcibly() } }
        scope.cancel()
    }

    private fun buildArgs(projectId: String, text: String, sessionId: String?): List<String> =
        buildOpenCodeExecArgs(
            cmd = opencodeCmdProvider(),
            text = text,
            sessionId = sessionId,
            model = effectiveModel(projectId),
        )

    private fun enqueuePrompt(projectId: String, text: String): Int {
        val queue = pendingPrompts.computeIfAbsent(projectId) { ArrayDeque() }
        queue.addLast(text)
        return queue.size
    }

    private fun clearQueuedPrompts(projectId: String) {
        pendingPrompts.remove(projectId)
    }

    private suspend fun startPromptLocked(projectId: String, text: String) {
        markTurnActive(projectId)
        val sid = readSessionId(projectId)
        history?.userPrompt(projectId, sid, text, provider = provider.id)
        val root = workspace.projectRoot(projectId)
        if (!root.exists()) throw IllegalStateException("project root not found: $root")
        val args = buildArgs(projectId, text, sid)
        log.info { "[$projectId] spawning OpenCode: ${args.joinToString(" ")} (cwd=$root)" }
        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(args).also { pb ->
                pb.directory(root.toFile())
                pb.redirectErrorStream(false)
                applyOpenCodeProcessEnv(pb)
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
                        log.debug { "[$projectId][opencode stderr] $line" }
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
                        clearTurnActive(projectId)
                        val detail = synchronized(stderrTail) { stderrTail.joinToString("\n").trim() }
                        val message = buildString {
                            append("OpenCode exited with status ${proc.exitValue()}.")
                            if (detail.isNotBlank()) append("\n").append(detail)
                        }
                        log.warn { "[$projectId] $message" }
                        emitSystem(projectId, "opencode_exit", message)
                        fireInterrupt(projectId, "crashed")
                    }
                }
            } catch (t: Throwable) {
                log.warn(t) { "[$projectId] OpenCode reader failed" }
                emitSystem(projectId, "opencode_error", t.message ?: "OpenCode reader failed")
                clearTurnActive(projectId)
                setBusy(projectId, ProjectState.ERROR)
                fireInterrupt(projectId, "crashed")
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
            emitSystem(projectId, "opencode_prompt_dequeued", "Running queued OpenCode prompt. Remaining: $remaining.")
            startPromptLocked(projectId, next)
        }
    }

    private suspend fun handleEvent(projectId: String, event: OpenCodeEvent) {
        // 첫 이벤트에서 sessionID 를 thread id 로 채택 (opencode 는 별도 thread.started 가 없음).
        if (event.sessionId.isNotBlank() && knownSessions.add(event.sessionId)) {
            writeSessionId(projectId, event.sessionId)
            history?.adoptNullSession(projectId, event.sessionId, provider = provider.id)
            history?.event(
                projectId,
                event.sessionId,
                ClaudeEvent.SessionStarted(
                    sessionId = event.sessionId,
                    model = effectiveModel(projectId),
                    cwd = workspace.projectRoot(projectId).toString(),
                ),
                provider = provider.id,
            )
            hub.emitConsole(topic(projectId)) { seq ->
                WsFrame.ConsoleSessionStarted(event.sessionId, model = effectiveModel(projectId), cwd = workspace.projectRoot(projectId).toString(), seq = seq)
            }
        }
        when (event) {
            is OpenCodeEvent.StepStart -> setBusy(projectId, ProjectState.RESPONDING)
            is OpenCodeEvent.Text -> {
                if (event.text.isNotBlank()) {
                    hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleAssistant(event.text, isPartial = false, seq = seq) }
                    history?.event(
                        projectId,
                        readSessionId(projectId),
                        ClaudeEvent.AssistantMessage(event.text, isPartial = false),
                        provider = provider.id,
                    )
                }
            }
            is OpenCodeEvent.ToolStarted -> {
                val input = event.input ?: buildJsonObject { put("tool", event.tool) }
                history?.event(
                    projectId,
                    readSessionId(projectId),
                    ClaudeEvent.ToolUse(event.tool, input, event.callId),
                    provider = provider.id,
                )
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleToolUse(event.tool, input, event.callId, seq) }
            }
            is OpenCodeEvent.ToolCompleted -> {
                val input = event.input ?: buildJsonObject { put("tool", event.tool) }
                history?.event(
                    projectId,
                    readSessionId(projectId),
                    ClaudeEvent.ToolResult(event.callId, input, isError = false),
                    provider = provider.id,
                )
                hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleToolResult(event.callId, input, isError = false, seq) }
            }
            is OpenCodeEvent.StepFinish -> {
                event.tokens?.let {
                    val ctx = contextFromTokens(it)
                    writeContext(projectId, ctx)
                    hub.emitConsole(topic(projectId)) { seq ->
                        WsFrame.ConsoleContextUsage(ctx.input, ctx.cacheRead, ctx.cacheCreation, ctx.limit, seq)
                    }
                }
                // reason == "stop" → turn 전체 완료. 그 외(tool-calls 등)는 중간 step.
                if (event.reason == "stop") {
                    hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleDone(event.reason, seq) }
                    history?.event(projectId, readSessionId(projectId), ClaudeEvent.Done(event.reason), provider = provider.id)
                    clearTurnActive(projectId)
                    setBusy(projectId, ProjectState.READY)
                    fireTurnDone(projectId, event.reason)
                }
            }
            is OpenCodeEvent.Unknown -> {
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

    private fun sessionIdFile(projectId: String) = workspace.vibecoderDir(projectId).resolve("opencode-session.id")

    private fun contextFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("opencode-context-tokens")

    private fun modelFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("opencode-model")

    private fun turnActiveFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("opencode-turn-active")

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

    private fun contextFromTokens(tokens: OpenCodeTokens): AgentContextSnapshot {
        val uncachedInput = (tokens.input - tokens.cacheRead).coerceAtLeast(0)
        return AgentContextSnapshot(
            input = uncachedInput,
            cacheRead = tokens.cacheRead,
            cacheCreation = tokens.cacheWrite,
            limit = opencodeContextLimit(tokens.total.coerceAtLeast(0)),
        )
    }

    private fun opencodeContextLimit(used: Long): Long = when {
        used <= 0L -> 0L
        used <= 128_000L -> 128_000L
        used <= 200_000L -> 200_000L
        else -> used
    }

    private fun readSessionId(projectId: String): String? =
        runCatching { sessionIdFile(projectId).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null } }.getOrNull()

    private fun writeSessionId(projectId: String, sessionId: String) {
        val f = sessionIdFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText(sessionId)
    }

    // ── v1.150.0 — turn-active 영속 마크 (부팅 reconcile 용, Codex 와 동일 패턴) ────────
    private fun markTurnActive(projectId: String) {
        runCatching {
            val f = turnActiveFile(projectId)
            Files.createDirectories(f.parent)
            f.writeText("0")
        }.onFailure { log.warn(it) { "[$projectId] opencode turn-active 마크 실패" } }
    }

    private fun clearTurnActive(projectId: String) {
        runCatching { turnActiveFile(projectId).deleteIfExists() }
    }

    private fun readTurnActiveRetries(projectId: String): Int? {
        val f = turnActiveFile(projectId)
        return if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else null
    }

    /**
     * v1.150.0 — 서버 부팅 시 1회 호출(비동기). 재시작으로 끊긴 미완 OpenCode turn
     * (`opencode-turn-active` 마크 잔존) 을 같은 sessionID 로 resume (`-s <id>`).
     * [com.siamakerlab.vibecoder.server.agent.codex.CodexSessionManager.reconcileInterruptedTurnsAsync]
     * 와 동일 패턴.
     */
    fun reconcileInterruptedTurnsAsync() {
        scope.launch {
            val ids = projectIdsWithTurnMark()
            if (ids.isEmpty()) return@launch
            log.info { "재시작으로 끊긴 미완 OpenCode turn ${ids.size}개 자동 재개 시도: $ids" }
            for (pid in ids) {
                val retries = readTurnActiveRetries(pid) ?: continue
                if (retries >= MAX_BOOT_RESUME_RETRIES) {
                    clearTurnActive(pid)
                    log.warn { "[$pid] OpenCode 재시작 자동 재개 ${MAX_BOOT_RESUME_RETRIES}회 초과 — 포기" }
                    runCatching {
                        emitSystem(pid, "opencode_turn_resume_giveup",
                            "서버 재시작 후 OpenCode 자동 재개가 ${MAX_BOOT_RESUME_RETRIES}회를 초과했습니다. 직접 이어서 진행해 주세요.")
                    }
                    continue
                }
                runCatching {
                    turnActiveFile(pid).writeText((retries + 1).toString())
                    emitSystem(pid, "opencode_turn_auto_resume",
                        "서버 재시작으로 중단된 OpenCode 작업을 자동으로 이어서 진행합니다 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES).")
                    val lock = locks.computeIfAbsent(pid) { Mutex() }
                    lock.withLock {
                        if (busy[pid] != true) startPromptLocked(pid, BOOT_RESUME_PROMPT)
                    }
                    log.info { "[$pid] 재시작 끊긴 OpenCode turn 자동 재개 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES)" }
                }.onFailure { log.warn(it) { "[$pid] OpenCode boot resume 실패" } }
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    private fun projectIdsWithTurnMark(): List<String> {
        val base = turnActiveFile("__probe__").parent?.parent ?: return emptyList()
        return runCatching {
            Files.list(base).use { stream ->
                stream.filter { Files.isDirectory(it) && Files.exists(it.resolve("opencode-turn-active")) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.getOrElse { emptyList() }
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
                    log.info { "[$projectId] reaping idle OpenCode process due to maxResidentSessions=$cap" }
                    proc.destroy()
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
                    processes.remove(projectId, proc)
                    lastTouched.remove(projectId)
                    busy.remove(projectId)
                }.onFailure { log.warn(it) { "[$projectId] OpenCode resident process reap failed" } }
            }
    }

    private fun applyOpenCodeProcessEnv(pb: ProcessBuilder) {
        pb.environment().putIfAbsent("HOME", "/home/vibe")
        pb.environment().putIfAbsent("XDG_CONFIG_HOME", "/home/vibe/.config")
        val ch = config.opencode.configHome
        if (ch != "default" && ch.isNotBlank()) {
            pb.environment()["OPENCODE_CONFIG_HOME"] = ch
        }
    }

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        private const val MAX_STDERR_TAIL_LINES = 12
        const val MAX_BOOT_RESUME_RETRIES = 2
        const val BOOT_RESUME_PROMPT =
            "Continue from where you left off — the server restarted and the previous OpenCode turn was interrupted. " +
                "Resume the in-progress work; do not restart from scratch."
    }
}

/** v1.150.0 — opencode CLI exec 인자 빌더 (`opencode run --format json --auto ...`). */
internal fun buildOpenCodeExecArgs(
    cmd: String,
    text: String,
    sessionId: String?,
    model: String?,
): List<String> = buildList {
    add(cmd)
    add("run")
    add("--format"); add("json")
    add("--auto")
    model?.let { add("-m"); add(it) }
    if (sessionId != null) {
        add("-s"); add(sessionId)
    }
    add(text)
}
