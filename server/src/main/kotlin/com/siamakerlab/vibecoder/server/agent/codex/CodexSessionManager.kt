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
import kotlinx.coroutines.delay
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

    /**
     * v1.146.0 — turn 관찰 hook (provider 무관화). [AgentRouter.installTurnListeners] 가
     * Claude/Codex/OpenCode 모든 manager 에 동일 리스너를 주입한다. Codex 는 turn 단위
     * exec 프로세스라 [handleEvent] 의 TurnCompleted/TurnFailed 시점에 [fireTurnDone] 호출.
     */
    @Volatile
    override var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    @Volatile
    override var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    private fun fireTurnDone(projectId: String, reason: String) {
        val l = turnDoneListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] codex turnDoneListener failed" } } }
    }

    private fun fireInterrupt(projectId: String, reason: String) {
        val l = turnInterruptListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] codex turnInterruptListener failed" } } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val busy = ConcurrentHashMap<String, Boolean>()
    private val lastTouched = ConcurrentHashMap<String, Instant>()
    private val pendingPrompts = ConcurrentHashMap<String, ArrayDeque<String>>()
    /**
     * v1.148.0 — rate-limit 자동 재개 카운터. 같은 thread-id 에서 연속 rate-limit 시
     * [MAX_RATE_LIMIT_RETRIES] 까지 지수 백오프로 "이어서 진행" 프롬프트를 재전송.
     * 성공 turn / 사용자 prompt / cancel / startNew 시 0 으로 리셋.
     */
    private val rateLimitRetries = ConcurrentHashMap<String, Int>()
    /** v1.148.0 — rate-limit 자동 재개 대기 코루틴. cancel/startNew/사용자 prompt 시 취소. */
    private val retryJobs = ConcurrentHashMap<String, Job>()

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
        cancelRateLimitRetry(projectId)
        clearTurnActive(projectId)  // v1.149.0 — 사용자 취소 → 미완 마크 해제
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

    /**
     * v1.148.0 — 진행 중 Codex turn 을 끊고 새 프롬프트를 시작. Codex exec 는 turn 단위
     * 프로세스라 stdin interrupt 가 의미 없다 — destroy 후 새 turn 시작이 자연스럽다.
     * rate-limit 자동 재개 대기 중이면 취소하고 사용자 의도(새 프롬프트)를 우선.
     */
    override suspend fun interruptAndSend(projectId: String, text: String, images: List<PromptImageDto>) {
        cancelRateLimitRetry(projectId)
        emitSystem(projectId, "codex_interrupt_send", "진행 중 Codex turn을 중단하고 새 프롬프트를 시작합니다.")
        cancelTurn(projectId)
        sendPrompt(projectId, text, images)
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

    /**
     * v1.149.0 — 서버 부팅 시 1회 호출(비동기). 재시작/크래시로 끊긴 미완 Codex turn
     * (codex-turn-active 마크 잔존) 프로젝트마다 "이어서 진행" 프롬프트를 자동 전송
     * (같은 thread-id resume → 멈춘 곳부터). [com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager.reconcileInterruptedTurnsAsync]
     * 와 동일 패턴. 무거운 codex spawn 이라 부팅을 블로킹하지 않도록 scope 에서 순차 실행(2초 간격).
     */
    fun reconcileInterruptedTurnsAsync() {
        scope.launch {
            val ids = projectIdsWithTurnMark()
            if (ids.isEmpty()) return@launch
            log.info { "재시작으로 끊긴 미완 Codex turn ${ids.size}개 자동 재개 시도: $ids" }
            for (pid in ids) {
                val retries = readTurnActiveRetries(pid) ?: continue
                if (retries >= MAX_BOOT_RESUME_RETRIES) {
                    clearTurnActive(pid)
                    log.warn { "[$pid] Codex 재시작 자동 재개 ${MAX_BOOT_RESUME_RETRIES}회 초과 — 포기" }
                    runCatching {
                        emitSystem(pid, "codex_turn_resume_giveup",
                            "서버 재시작 후 Codex 자동 재개가 ${MAX_BOOT_RESUME_RETRIES}회를 초과했습니다. 직접 이어서 진행해 주세요.")
                    }
                    continue
                }
                runCatching {
                    // 마커를 (retries+1) 로 갱신 후 isAutoResume=true 로 spawn → startPromptLocked 가
                    // 마커를 덮어쓰지 않는다 (부팅 재개 횟수 추적 보존).
                    turnActiveFile(pid).writeText((retries + 1).toString())
                    emitSystem(pid, "codex_turn_auto_resume",
                        "서버 재시작으로 중단된 Codex 작업을 자동으로 이어서 진행합니다 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES).")
                    val lock = locks.computeIfAbsent(pid) { Mutex() }
                    lock.withLock {
                        if (busy[pid] != true) startPromptLocked(pid, BOOT_RESUME_PROMPT, isAutoResume = true)
                    }
                    log.info { "[$pid] 재시작 끊긴 Codex turn 자동 재개 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES)" }
                }.onFailure { log.warn(it) { "[$pid] Codex boot resume 실패" } }
                delay(2_000)  // 순차 spawn 부하 분산
            }
        }
    }

    /** codex-turn-active 마크가 있는 프로젝트 id 목록 (workspace `.vibecoder/<id>/` 스캔). */
    private fun projectIdsWithTurnMark(): List<String> {
        val base = turnActiveFile("__probe__").parent?.parent ?: return emptyList()  // .vibecoder 루트
        return runCatching {
            Files.list(base).use { stream ->
                stream.filter { Files.isDirectory(it) && Files.exists(it.resolve("codex-turn-active")) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.getOrElse { emptyList() }
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

    /**
     * v1.148.0/v1.149.0 — [isAutoResume]=true 면 rate-limit 자동 재개 / 부팅 자동 재개 경로.
     * ① rate-limit 카운터를 유지 ([scheduleRateLimitRetry] 가 증감 관리).
     * ② turn-active 마커도 유지 (재개 대기/부팅 재개 중엔 미완 상태를 남겨야 reconcile 이 잡음).
     * 일반 사용자 prompt (sendPrompt / 큐 드레인) 는 isAutoResume=false 로 둘 다 초기화.
     */
    private suspend fun startPromptLocked(projectId: String, text: String, isAutoResume: Boolean = false) {
        if (!isAutoResume) {
            rateLimitRetries.remove(projectId)
            markTurnActive(projectId)
        }
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
                // v1.149.0 — TurnCompleted/TurnFailed 이벤트를 받았는지 추적. Codex exec 는
                // turn 단위 프로세스라 TurnFailed(rate-limit 포함) 후에도 프로세스가 exit 하며
                // 이 while 루프를 빠져나온다. 이때 "이벤트 없이 exit = crash" 분기가 rate-limit
                // 재개/usage-limit/진짜에러 처리를 덮어쓰는 것을 막는다 (M1.2 회귀 수정).
                var sawTurnEnd = false
                while (true) {
                    val line = withContext(Dispatchers.IO) { stdout.readLine() } ?: break
                    val event = parser.parseLine(line) ?: continue
                    handleEvent(projectId, event)
                    if (event is CodexEvent.TurnCompleted || event is CodexEvent.TurnFailed) sawTurnEnd = true
                }
                val ok = withContext(Dispatchers.IO) { proc.waitFor(1, TimeUnit.SECONDS); proc.exitValue() == 0 }
                // TurnCompleted/TurnFailed 없이 exit 한 경우만 crash (이벤트로 종료 처리됐으면 스킵).
                if (!sawTurnEnd && busy[projectId] == true) {
                    setBusy(projectId, if (ok) ProjectState.READY else ProjectState.ERROR)
                    if (!ok) {
                        clearTurnActive(projectId)  // v1.149.0 — crash → 미완 마크 해제
                        val detail = synchronized(stderrTail) { stderrTail.joinToString("\n").trim() }
                        val message = buildString {
                            append("Codex exited with status ${proc.exitValue()}.")
                            if (detail.isNotBlank()) append("\n").append(detail)
                        }
                        log.warn { "[$projectId] $message" }
                        emitSystem(projectId, "codex_exit", message)
                        // 프로세스가 TurnCompleted/TurnFailed 이벤트 없이 죽음 → crash 로 간주해
                        // 진행 중 자동화를 중단시킨다.
                        fireInterrupt(projectId, "crashed")
                    }
                }
            } catch (t: Throwable) {
                log.warn(t) { "[$projectId] Codex reader failed" }
                emitSystem(projectId, "codex_error", t.message ?: "Codex reader failed")
                clearTurnActive(projectId)  // v1.149.0 — reader exception(crash) → 미완 마크 해제
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
            emitSystem(projectId, "codex_prompt_dequeued", "Running queued Codex prompt. Remaining: $remaining.")
            startPromptLocked(projectId, next)
        }
    }

    /**
     * v1.148.0 — 일시 rate-limit 시 지수 백오프(30/60/120/240/300초)로 같은 thread 에
     * "이어서 진행" 프롬프트를 재전송. 최대 [MAX_RATE_LIMIT_RETRIES] 회. 초과 시 STOPPED.
     *
     * 재개 대기 동안 busy(RESPONDING) 를 유지해 자동화가 빈 슬롯에 폭주하는 것을 막는다
     * (fireTurnDone 을 호출하지 않음 — Claude v1.99.0 동일 정책: rate-limit 은 turn "종료"가
     * 아니라 "일시중단 → 재개 대기"다).
     */
    private suspend fun scheduleRateLimitRetry(projectId: String) {
        val attempt = (rateLimitRetries[projectId] ?: 0) + 1
        if (attempt > MAX_RATE_LIMIT_RETRIES) {
            cancelRateLimitRetry(projectId)
            setBusy(projectId, ProjectState.STOPPED)
            fireInterrupt(projectId, "rate_limit_giveup")
            emitSystem(
                projectId, "rate_limit_giveup",
                "Codex rate limit 이 ${MAX_RATE_LIMIT_RETRIES}회 자동 재개 후에도 지속됩니다. " +
                    "자동 재개를 중지합니다 — 잠시 후 직접 이어서 진행해 주세요.",
            )
            return
        }
        rateLimitRetries[projectId] = attempt
        val delayMs = RATE_LIMIT_BASE_BACKOFF_MS shl (attempt - 1)  // 30/60/120/240/300 초
        retryJobs[projectId]?.cancel()
        // 재개 대기 동안 busy 유지(응답 중 표시) — 사용자에게 진행 예정 안내.
        setBusy(projectId, ProjectState.RESPONDING)
        emitSystem(
            projectId, "rate_limit_retry",
            "Codex 일시 rate limit (사용량 한도 아님). ${delayMs / 1000}초 후 자동으로 이어서 진행합니다 " +
                "($attempt/$MAX_RATE_LIMIT_RETRIES).",
        )
        retryJobs[projectId] = scope.launch {
            try {
                delay(delayMs)
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            retryJobs.remove(projectId)
            // 같은 thread-id 로 resume — startPromptLocked(resetRateLimitCounter=false) 로
            // 재시도 카운터를 보존한 채 새 exec 프로세스를 spawn. busy 는 이미 RESPONDING 이라
            // sendPrompt 를 쓰면 큐잉 분기로 빠지므로 직접 startPromptLocked 호출.
            val lock = locks.computeIfAbsent(projectId) { Mutex() }
            lock.withLock {
                if (busy[projectId] != true) return@withLock  // 도중 cancel/stop 됨.
                runCatching { startPromptLocked(projectId, RATE_LIMIT_RESUME_PROMPT, isAutoResume = true) }
                    .onFailure {
                        log.warn(it) { "[$projectId] Codex rate-limit 자동 재개 실패" }
                        cancelRateLimitRetry(projectId)
                        scope.launch {
                            setBusy(projectId, ProjectState.STOPPED)
                            fireInterrupt(projectId, "rate_limit_resume_failed")
                        }
                    }
            }
        }
    }

    /** v1.148.0 — rate-limit 자동 재개 상태(카운터 + 대기 코루틴) 초기화. */
    private fun cancelRateLimitRetry(projectId: String) {
        rateLimitRetries.remove(projectId)
        retryJobs[projectId]?.cancel()
        retryJobs.remove(projectId)
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
                clearTurnActive(projectId)  // v1.149.0 — 정상 완료 → 미완 마크 해제
                setBusy(projectId, ProjectState.READY)
                fireTurnDone(projectId, event.reason)
            }
            is CodexEvent.TurnFailed -> {
                // v1.148.0 — TurnFailed 메시지를 3가지 종료 경로로 분기.
                //   ① rate-limit   → 일시중단 → 백오프 후 같은 thread 자동 재개 (fireTurnDone 안 함)
                //   ② usage-limit  → 재시도 无효 → STOPPED + interrupt (자동화 다음 프롬프트 차단)
                //   ③ 진짜 에러     → ERROR + fireTurnDone("error") (자동화 stopOnError 분기)
                val msg = event.message
                when {
                    isCodexRateLimitMessage(msg) -> {
                        history?.event(
                            projectId,
                            readThreadId(projectId),
                            ClaudeEvent.ErrorEvent("codex_rate_limit", msg),
                            provider = provider.id,
                        )
                        scheduleRateLimitRetry(projectId)
                    }
                    isCodexUsageLimitMessage(msg) -> {
                        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleError("codex_usage_limit", msg, seq) }
                        history?.event(
                            projectId,
                            readThreadId(projectId),
                            ClaudeEvent.ErrorEvent("codex_usage_limit", msg),
                            provider = provider.id,
                        )
                        cancelRateLimitRetry(projectId)
                        clearTurnActive(projectId)  // v1.149.0 — 종료 → 미완 마크 해제
                        setBusy(projectId, ProjectState.STOPPED)
                        fireInterrupt(projectId, "usage_limit")
                    }
                    else -> {
                        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleError("codex_turn_failed", msg, seq) }
                        history?.event(
                            projectId,
                            readThreadId(projectId),
                            ClaudeEvent.ErrorEvent("codex_turn_failed", msg),
                            provider = provider.id,
                        )
                        cancelRateLimitRetry(projectId)
                        clearTurnActive(projectId)  // v1.149.0 — 종료 → 미완 마크 해제
                        setBusy(projectId, ProjectState.ERROR)
                        // turn 실패는 자동화 관점에서 error 성 종료 — [PromptAutomationManager.onTurnDone]
                        // 의 stopOnError(reason.startsWith("error")) 분기가 잡도록 "error" reason 으로 통지.
                        fireTurnDone(projectId, "error")
                    }
                }
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

    /**
     * v1.149.0 — Codex turn-active 영속 마크 (서버 재시작으로 끊긴 미완 turn 자동 재개용).
     * [com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager] 의 `turn-active` 와 대칭이나
     * 파일명이 다르다 (`codex-turn-active`) — 같은 프로젝트의 Claude/Codex 마커가 섞이지 않게.
     * 존재 = "이 프로젝트에 정상 완료되지 않은 Codex turn 이 있음". 파일 내용 = 부팅 자동 재개 횟수.
     */
    private fun turnActiveFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("codex-turn-active")

    /** 새 사용자 prompt 전송 시 마크 ON (자동 재개 횟수 0 으로 리셋). */
    private fun markTurnActive(projectId: String) {
        runCatching {
            val f = turnActiveFile(projectId)
            Files.createDirectories(f.parent)
            f.writeText("0")
        }.onFailure { log.warn(it) { "[$projectId] codex turn-active 마크 실패" } }
    }

    /** turn 정상 완료 / 사용자 취소 / 새 세션 / 진짜 에러 시 마크 OFF. rate-limit 재개 대기는 유지. */
    private fun clearTurnActive(projectId: String) {
        runCatching { turnActiveFile(projectId).deleteIfExists() }
    }

    /** 마크가 있으면 자동 재개 횟수, 없으면 null. */
    private fun readTurnActiveRetries(projectId: String): Int? {
        val f = turnActiveFile(projectId)
        return if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else null
    }

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
        // v1.156.1 — putIfAbsent → put 강제 설정 (컨테이너 HOME=/root 회피).
        pb.environment()["HOME"] = "/home/vibe"
        pb.environment()["XDG_CONFIG_HOME"] = "/home/vibe/.config"
        pb.environment()["CODEX_HOME"] = "/home/vibe/.config/codex"
    }

    companion object {
        // v1.158.8 — 문서 수준 상향(32KB → 100_000 byte). ClaudeSessionManager 와 정렬.
        const val MAX_PROMPT_BYTES = 100_000
        private const val MAX_STDERR_TAIL_LINES = 12
        /**
         * v1.148.0 — Codex 일시 rate-limit 자동 재개. [RATE_LIMIT_BASE_BACKOFF_MS] 지수 백오프로
         * 최대 [MAX_RATE_LIMIT_RETRIES] 회 같은 thread 에 "이어서 진행" 프롬프트를 재전송.
         * ClaudeSessionManager 와 동일 정책/상숫값이나 독립 상수로 provider 별 조정 허용.
         */
        const val MAX_RATE_LIMIT_RETRIES = 5
        const val RATE_LIMIT_BASE_BACKOFF_MS = 30_000L
        const val RATE_LIMIT_RESUME_PROMPT =
            "Continue from where you left off — the previous turn was interrupted by a temporary " +
                "rate limit (not a usage limit). Resume the in-progress work; do not restart from scratch."
        /**
         * v1.149.0 — 서버 재시작으로 끊긴 미완 Codex turn 자동 재개. 무한 재개 방지로 최대
         * [MAX_BOOT_RESUME_RETRIES] 회 (재시작이 반복돼도). 초과 시 마크 제거 + 수동 안내.
         * ClaudeSessionManager 와 동일값.
         */
        const val MAX_BOOT_RESUME_RETRIES = 2
        const val BOOT_RESUME_PROMPT =
            "Continue from where you left off — the server restarted and the previous Codex turn was interrupted. " +
                "Resume the in-progress work; do not restart from scratch."
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
