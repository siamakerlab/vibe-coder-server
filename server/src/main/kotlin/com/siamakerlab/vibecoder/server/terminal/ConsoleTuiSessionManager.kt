package com.siamakerlab.vibecoder.server.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.shared.dto.ProjectState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val consoleTuiLog = KotlinLogging.logger {}

enum class ConsoleTuiTurnState {
    IDLE,
    PROMPT_SENT,
    RUNNING,
    ASSISTANT_OUTPUT_DETECTED,
    TURN_COMPLETE,
    STALLED,
    EXITED,
}

data class ConsoleTuiTurnSnapshot(
    val state: ConsoleTuiTurnState = ConsoleTuiTurnState.IDLE,
    val lastPromptTurnId: String? = null,
    val lastPromptAt: String? = null,
)

data class ConsoleTuiCommand(
    val argv: List<String>,
    val displayName: String,
)

private fun defaultClaudeHome(): Path? =
    System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".claude") }
        ?: Path.of("/home/vibe/.claude")

class ConsoleTuiCommandBuilder(
    private val claudeHookUrl: (projectId: String, sessionId: String?) -> String? = { _, _ -> null },
    private val claudeHome: () -> Path? = ::defaultClaudeHome,
    private val effectiveModel: (projectId: String) -> String? = { null },
) {
    fun build(
        projectId: String,
        provider: AgentProvider,
        workdir: Path? = null,
        resumePrevious: Boolean = true,
        runtimeSessionId: String? = null,
    ): ConsoleTuiCommand {
        val model = effectiveModel(projectId)?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("default", ignoreCase = true) }
        val argv = when (provider) {
            AgentProvider.CLAUDE -> buildList {
                add("claude")
                add("--settings")
                add(claudeSettings(projectId, runtimeSessionId))
                if (model != null) {
                    add("--model")
                    add(model)
                }
                val sessionId = if (resumePrevious) workdir?.let { latestClaudeSessionId(it) } else null
                if (sessionId != null) {
                    add("--resume")
                    add(sessionId)
                }
            }
            AgentProvider.CODEX -> buildList {
                add("codex")
                add("--dangerously-bypass-approvals-and-sandbox")
                add("--no-alt-screen")
                if (model != null) {
                    add("-m")
                    add(model)
                }
                val threadId = if (resumePrevious) workdir?.let { latestCodexThreadId(it) } else null
                if (threadId != null) {
                    add("resume")
                    add(threadId)
                }
            }
            AgentProvider.OPENCODE -> buildList {
                add("opencode")
                add("--auto")
                add("--mini")
                if (model != null) {
                    add("-m")
                    add(model)
                }
                val sessionId = if (resumePrevious) workdir?.let { latestOpenCodeSessionId(it) } else null
                if (sessionId != null) {
                    add("--session")
                    add(sessionId)
                }
            }
        }
        return ConsoleTuiCommand(argv = argv, displayName = displayName(argv))
    }

    private fun displayName(argv: List<String>): String =
        argv.mapIndexed { index, value ->
            if (index > 0 && argv[index - 1] == "--settings") "{settings}" else value
        }.joinToString(" ")

    private fun latestClaudeSessionId(workdir: Path): String? {
        val home = claudeHome() ?: return null
        val projectDir = home.resolve("projects").resolve(claudeProjectKey(workdir))
        if (!Files.isDirectory(projectDir)) return null
        return runCatching { Files.list(projectDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .max(Comparator.comparingLong<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .map { it.fileName.toString().removeSuffix(".jsonl") }
                .orElse(null)
                ?.takeIf { it.isNotBlank() }
        } }.getOrNull()
    }

    private fun claudeProjectKey(workdir: Path): String =
        workdir.toAbsolutePath().normalize().toString()
            .map { ch -> if (ch.isLetterOrDigit()) ch else '-' }
            .joinToString("")

    private fun latestCodexThreadId(workdir: Path): String? {
        val db = codexStateDbFile() ?: return null
        val dir = workdir.toAbsolutePath().normalize().toString()
        val sql = """
            SELECT id
            FROM threads
            WHERE cwd = ${sqliteLiteral(dir)}
            ORDER BY updated_at_ms DESC, id DESC
            LIMIT 1
        """.trimIndent()
        return runSqliteScalar(db, sql)
    }

    private fun latestOpenCodeSessionId(workdir: Path): String? {
        val home = System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home", "/home/vibe"))
        val db = home.resolve(".local/share/opencode/opencode.db")
        if (!Files.isRegularFile(db)) return null
        val dir = workdir.toAbsolutePath().normalize().toString()
        val sql = """
            SELECT id
            FROM session
            WHERE directory = ${sqliteLiteral(dir)}
            ORDER BY time_updated DESC, id DESC
            LIMIT 1
        """.trimIndent()
        return runSqliteScalar(db, sql)
    }

    private fun codexStateDbFile(): Path? {
        val home = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(".codex") }
            ?: Path.of("/home/vibe/.codex")
        if (!Files.isDirectory(home)) return null
        return Files.list(home).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("state_") && it.fileName.toString().endsWith(".sqlite") }
                .max(Comparator.comparingLong<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .orElse(null)
        } ?: home.resolve("state.sqlite").takeIf { Files.isRegularFile(it) }
    }

    private fun runSqliteScalar(db: Path, sql: String): String? =
        runCatching {
            val process = ProcessBuilder("sqlite3", "-readonly", "-batch", "-noheader", db.toString(), sql)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            } else {
                null
            }
        }.getOrNull()

    private fun sqliteLiteral(value: String): String =
        "'" + value.replace("'", "''") + "'"

    private fun claudeSettings(projectId: String, sessionId: String?): String {
        val url = claudeHookUrl(projectId, sessionId)?.trim()?.takeIf { it.isNotBlank() }
            ?: return """{"tui":"default"}"""
        val hookUrl = jsonString(url)
        val toolMatcherEvents = setOf(
            "PreToolUse",
            "PostToolUse",
            "PostToolUseFailure",
            "PermissionRequest",
            "PermissionDenied",
        )
        val hookEvents = listOf(
            "SessionStart",
            "UserPromptSubmit",
            "PreToolUse",
            "PostToolUse",
            "PostToolUseFailure",
            "PermissionRequest",
            "PermissionDenied",
            "Notification",
            "Stop",
            "StopFailure",
            "SessionEnd",
        )
        val hooks = hookEvents.joinToString(",") { event ->
            val matcher = if (event in toolMatcherEvents) """"matcher":".*",""" else ""
            """"$event":[{$matcher"hooks":[{"type":"http","url":$hookUrl}]}]"""
        }
        return """{"tui":"default","hooks":{$hooks}}"""
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}

class ConsoleTuiSession(
    val id: String,
    val projectId: String,
    val provider: AgentProvider,
    val workdir: Path,
    val ownerUserId: String?,
    val commandDisplay: String,
    private val process: PtyProcess,
    private val scope: CoroutineScope,
) {
    val createdAt: Instant = Instant.now()
    private val lastActivity = AtomicReference(Instant.now())
    val lastActivityAt: Instant get() = lastActivity.get()
    private val lastOutput = AtomicReference(Instant.EPOCH)
    val lastOutputAt: Instant get() = lastOutput.get()
    private val turnStarted = AtomicReference<Instant?>(null)
    val turnStartedAt: Instant? get() = turnStarted.get()
    val pid: Long? = runCatching { process.pid() }.getOrNull()
    private val turnState = AtomicReference(ConsoleTuiTurnSnapshot())
    private val connections = AtomicInteger(0)
    private val outStream = process.outputStream
    private val inStream = process.inputStream
    private var readJob: Job? = null
    private val scrollbackLock = Any()
    private val scrollback = StringBuilder()
    private var turnCompleteJob: Job? = null
    private val codexTrustPromptAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val notifyTurnDoneOnExit = java.util.concurrent.atomic.AtomicBoolean(false)
    private val notifyTurnDoneOnStall = java.util.concurrent.atomic.AtomicBoolean(false)
    private val intentionalClose = java.util.concurrent.atomic.AtomicBoolean(false)
    private val userInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private val _exit = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    val exit: SharedFlow<Int> = _exit.asSharedFlow()

    fun start() {
        readJob = scope.launch(Dispatchers.IO) {
            val reader = java.io.InputStreamReader(inStream, StandardCharsets.UTF_8)
            val cbuf = CharArray(8192)
            try {
                while (isActive) {
                    val n = runCatching { reader.read(cbuf) }.getOrNull() ?: -1
                    if (n < 0) break
                    if (n > 0) {
                        val data = String(cbuf, 0, n)
                        touch()
                        lastOutput.set(Instant.now())
                        markRunningFromOutput()
                        appendScrollback(data)
                        _output.emit(data)
                    }
                }
            } finally {
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                val previous = turnState.get()
                notifyTurnDoneOnExit.set(previous.lastPromptTurnId != null && previous.state !in TERMINAL_OR_INACTIVE_STATES)
                turnState.updateAndGet {
                    it.copy(state = ConsoleTuiTurnState.EXITED)
                }
                _exit.tryEmit(code)
                consoleTuiLog.info { "[console-tui $id] exited code=$code project=$projectId provider=${provider.id}" }
            }
        }
    }

    fun turnSnapshot(): ConsoleTuiTurnSnapshot = turnState.get()

    fun consumeTurnDoneOnExitSignal(): Boolean =
        notifyTurnDoneOnExit.getAndSet(false)

    fun consumeTurnDoneOnStallSignal(): Boolean =
        notifyTurnDoneOnStall.getAndSet(false)

    fun markPromptSent(turnId: String) {
        turnCompleteJob?.cancel()
        turnStarted.set(Instant.now())
        turnState.set(
            ConsoleTuiTurnSnapshot(
                state = ConsoleTuiTurnState.PROMPT_SENT,
                lastPromptTurnId = turnId,
                lastPromptAt = Instant.now().toString(),
            ),
        )
    }

    private fun markRunningFromOutput() {
        turnState.updateAndGet { current ->
            when (current.state) {
                ConsoleTuiTurnState.PROMPT_SENT -> current.copy(state = ConsoleTuiTurnState.RUNNING)
                else -> current
            }
        }
    }

    fun markAssistantOutputDetected() {
        turnState.updateAndGet { current ->
            when (current.state) {
                ConsoleTuiTurnState.PROMPT_SENT,
                ConsoleTuiTurnState.RUNNING,
                ConsoleTuiTurnState.STALLED,
                ConsoleTuiTurnState.ASSISTANT_OUTPUT_DETECTED -> current.copy(state = ConsoleTuiTurnState.ASSISTANT_OUTPUT_DETECTED)
                else -> current
            }
        }
    }

    fun markStalledIfNeeded(now: Instant = Instant.now(), timeout: Duration = DEFAULT_STALLED_TIMEOUT) {
        if (timeout.isZero || timeout.isNegative) return
        turnState.updateAndGet { current ->
            when (current.state) {
                ConsoleTuiTurnState.PROMPT_SENT,
                ConsoleTuiTurnState.RUNNING -> {
                    val sentAt = current.lastPromptAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    val lastSignalAt = listOfNotNull(sentAt, lastActivityAt).maxOrNull()
                    if (lastSignalAt != null && Duration.between(lastSignalAt, now) > timeout) {
                        notifyTurnDoneOnStall.set(true)
                        current.copy(state = ConsoleTuiTurnState.STALLED)
                    } else {
                        current
                    }
                }
                else -> current
            }
        }
    }

    fun scheduleTurnComplete(
        debounce: Duration = DEFAULT_TURN_COMPLETE_DEBOUNCE,
        onDone: suspend (projectId: String, reason: String) -> Unit,
    ) {
        val promptTurnId = turnState.get().lastPromptTurnId ?: return
        turnCompleteJob?.cancel()
        turnCompleteJob = scope.launch {
            delay(debounce.toMillis().coerceAtLeast(0))
            val completed = turnState.updateAndGet { current ->
                if (current.lastPromptTurnId == promptTurnId &&
                    current.state == ConsoleTuiTurnState.ASSISTANT_OUTPUT_DETECTED
                ) {
                    current.copy(state = ConsoleTuiTurnState.TURN_COMPLETE)
                } else {
                    current
                }
            }
            if (completed.lastPromptTurnId == promptTurnId &&
                completed.state == ConsoleTuiTurnState.TURN_COMPLETE
            ) {
                turnStarted.set(null)
                runCatching { onDone(projectId, "console_tui_transcript") }
                    .onFailure { consoleTuiLog.warn(it) { "[console-tui $id] turn done listener failed" } }
            }
        }
    }

    fun touch() {
        lastActivity.set(Instant.now())
    }

    fun attach() {
        connections.incrementAndGet()
        touch()
    }

    fun detach() {
        connections.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun hasActiveConnection(): Boolean = connections.get() > 0

    fun scrollbackSnapshot(): String = synchronized(scrollbackLock) { scrollback.toString() }

    fun scrollbackLength(): Int = synchronized(scrollbackLock) { scrollback.length }

    fun hasQuotaLimitOutput(): Boolean =
        QUOTA_LIMIT_PATTERNS.any { it.containsMatchIn(scrollbackSnapshot()) }

    fun hasCodexTrustPrompt(): Boolean {
        if (provider != AgentProvider.CODEX) return false
        if (codexTrustPromptAccepted.get()) return false
        val text = scrollbackSnapshot()
        return text.contains("Do you trust the contents of this directory?") &&
            text.contains("1. Yes, continue")
    }

    fun acceptCodexTrustPromptIfVisible(): Boolean {
        if (!hasCodexTrustPrompt()) return false
        if (!codexTrustPromptAccepted.compareAndSet(false, true)) return false
        return write("\r")
    }

    fun write(data: String): Boolean {
        touch()
        return runCatching {
            outStream.write(data.toByteArray(StandardCharsets.UTF_8))
            outStream.flush()
            true
        }.getOrElse {
            consoleTuiLog.debug(it) { "[console-tui $id] write failed" }
            false
        }
    }

    fun pastePrompt(prompt: String): Boolean {
        val normalized = normalizeConsoleTuiPromptInput(prompt)
        return write("\u001b[200~$normalized\u001b[201~\r")
    }

    fun interrupt(): Boolean {
        turnCompleteJob?.cancel()
        turnStarted.set(null)
        markUserInterrupted()
        return write("\u001b")
    }

    fun markUserInterrupted() {
        userInterrupted.set(true)
    }

    fun resize(cols: Int, rows: Int) {
        runCatching {
            process.winSize = WinSize(
                cols.coerceIn(1, MAX_TERMINAL_DIMENSION),
                rows.coerceIn(1, MAX_TERMINAL_DIMENSION),
            )
        }.onFailure { consoleTuiLog.debug(it) { "[console-tui $id] resize failed" } }
    }

    fun isAlive(): Boolean = process.isAlive

    fun kill(intentional: Boolean = true) {
        if (intentional) intentionalClose.set(true)
        runCatching {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        readJob?.cancel()
        turnCompleteJob?.cancel()
    }

    fun consumeIntentionalCloseSignal(): Boolean =
        intentionalClose.getAndSet(false)

    fun consumeUserInterruptedSignal(): Boolean =
        userInterrupted.getAndSet(false)

    private fun appendScrollback(data: String) {
        synchronized(scrollbackLock) {
            scrollback.append(data)
            val over = scrollback.length - MAX_SCROLLBACK_CHARS
            if (over > 0) {
                scrollback.delete(0, over)
                if (scrollback.isNotEmpty() && Character.isLowSurrogate(scrollback[0])) {
                    scrollback.deleteCharAt(0)
                }
            }
        }
    }

    companion object {
        const val MAX_TERMINAL_DIMENSION = 999
        const val MAX_SCROLLBACK_CHARS = 200_000
        val DEFAULT_TURN_COMPLETE_DEBOUNCE: Duration = Duration.ofSeconds(4)
        val DEFAULT_STALLED_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val TERMINAL_OR_INACTIVE_STATES = setOf(
            ConsoleTuiTurnState.IDLE,
            ConsoleTuiTurnState.TURN_COMPLETE,
            ConsoleTuiTurnState.EXITED,
        )
        private val QUOTA_LIMIT_PATTERNS = listOf(
            Regex("""(?i)\bquota\b.*\b(exceeded|exhausted|limit|reached)\b"""),
            Regex("""(?i)\b(rate|usage|weekly|monthly)\s+limit\b.*\b(exceeded|exhausted|reached|reset)\b"""),
            Regex("""(?i)\bRESOURCE_EXHAUSTED\b|\bHTTP\s*429\b|\bstatus\s*429\b"""),
        )
    }
}

internal fun ConsoleTuiSession.isReapableForResourcePressure(): Boolean =
    isAlive() && !hasActiveConnection() && turnSnapshot().state in REAPABLE_INACTIVE_STATES

internal fun ConsoleTuiSession.isReapableForIdleTimeout(now: Instant, idleTimeout: Duration): Boolean =
    isAlive() &&
        !hasActiveConnection() &&
        turnSnapshot().state in REAPABLE_INACTIVE_STATES &&
        Duration.between(lastActivityAt, now) > idleTimeout

private val REAPABLE_INACTIVE_STATES = setOf(
    ConsoleTuiTurnState.IDLE,
    ConsoleTuiTurnState.TURN_COMPLETE,
)

internal fun classifyConsoleTuiExit(
    exitCode: Int,
    intentionalClose: Boolean,
    userInterrupted: Boolean,
    quotaLimited: Boolean,
): AgentState = when {
    quotaLimited -> AgentState.ERROR
    userInterrupted -> AgentState.INTERRUPTED
    exitCode == 0 || intentionalClose -> AgentState.IDLE
    else -> AgentState.ERROR
}

internal fun normalizeConsoleTuiPromptInput(prompt: String): String =
    prompt
        .replace("\u001b[200~", "")
        .replace("\u001b[201~", "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { ch -> ch == '\n' || ch == '\t' || ch.code >= 0x20 }

class ConsoleTuiSessionManager(
    private val commandBuilder: ConsoleTuiCommandBuilder,
    private val idleTimeout: Duration = Duration.ofHours(24),
    private val resourceGuard: com.siamakerlab.vibecoder.server.resources.ResourceGuard? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ConsoleTuiSession>()
    private val byKey = ConcurrentHashMap<SessionKey, String>()
    private val freshStarts = ConcurrentHashMap<SessionKey, Boolean>()
    @Volatile
    var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null
    @Volatile
    var agentStatusChangedListener: (suspend (AgentStatusSnapshot) -> Unit)? = null

    fun publishState(projectId: String, provider: AgentProvider, state: ProjectState) {
        publishAgentStatus(projectId, provider, state)
    }

    private fun publishAgentStatus(
        projectId: String,
        provider: AgentProvider,
        state: ProjectState,
        message: String? = null,
        error: String? = null,
        sessionOverride: ConsoleTuiSession? = null,
    ) {
        val listener = agentStatusChangedListener ?: return
        val session = sessionOverride ?: findAny(projectId, provider)
        val snapshot = AgentStatusSnapshot(
            projectId = projectId,
            provider = provider,
            sessionId = session?.id,
            state = when (state) {
                ProjectState.READY -> AgentState.IDLE
                ProjectState.RESPONDING -> AgentState.RUNNING
                ProjectState.WAITING -> AgentState.WAITING_INPUT
                ProjectState.STOPPED -> AgentState.INTERRUPTED
                ProjectState.ERROR -> AgentState.ERROR
            },
            activity = when (state) {
                ProjectState.RESPONDING -> AgentActivity.THINKING
                ProjectState.WAITING -> AgentActivity.THINKING
                else -> null
            },
            message = message,
            error = error,
            pid = session?.pid,
            lastOutputAt = session?.lastOutputAt?.toEpochMilli() ?: 0L,
            turnStartedAt = session?.turnStartedAt?.toEpochMilli(),
        )
        scope.launch {
            runCatching { listener(snapshot) }
                .onFailure { consoleTuiLog.warn(it) { "[console-tui] agent status listener failed for $projectId/${provider.id}" } }
        }
    }

    init {
        scope.launch {
            while (isActive) {
                delay(REAPER_INTERVAL_MS)
                runCatching {
                    reapIdleForResourcePressure()
                    markStalledSessions()
                    reapIdle()
                }
                    .onFailure { consoleTuiLog.warn(it) { "console TUI idle reaper failed" } }
            }
        }
    }

    // Keep check/spawn/register atomic. Concurrent open/prompt calls for the same key must not
    // leave duplicate provider CLI PTYs running.
    @Synchronized
    fun ensure(
        ownerUserId: String?,
        projectId: String,
        provider: AgentProvider,
        workdir: Path,
        cols: Int = 100,
        rows: Int = 30,
    ): ConsoleTuiSession {
        if (ownerUserId == null) {
            findAny(projectId, provider)?.let { return it }
        }
        val key = SessionKey(ownerUserId, projectId, provider)
        byKey[key]?.let { existingId ->
            val existing = sessions[existingId]
            if (existing != null && existing.isAlive()) return existing
            byKey.remove(key, existingId)
        }

        resourceGuard?.ensureCanStart("console ${provider.id}") {
            closeIdleSessionsForResourcePressure()
        }
        val id = UUID.randomUUID().toString().take(12)
        val resumePrevious = freshStarts.remove(key) == null
        val command = commandBuilder.build(
            projectId,
            provider,
            workdir,
            resumePrevious = resumePrevious,
            runtimeSessionId = id,
        )
        val proc = PtyProcessBuilder(command.argv.toTypedArray())
            .setDirectory(workdir.toString())
            .setEnvironment(
                System.getenv().toMutableMap().apply {
                    put("TERM", "xterm-256color")
                    put("LANG", "en_US.UTF-8")
                    put("LC_ALL", "en_US.UTF-8")
                    remove("CLAUDE_CODE_NO_FLICKER")
                },
            )
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
            .start()
        val session = ConsoleTuiSession(
            id = id,
            projectId = projectId,
            provider = provider,
            workdir = workdir,
            ownerUserId = ownerUserId,
            commandDisplay = command.displayName,
            process = proc,
            scope = scope,
        )
        sessions[id] = session
        byKey[key] = id
        session.start()
        agentStatusChangedListener?.let { listener ->
            scope.launch {
                runCatching {
                    listener(
                        AgentStatusSnapshot(
                            projectId = projectId,
                            provider = provider,
                            sessionId = id,
                            state = AgentState.STARTING,
                            message = "PTY spawned",
                            pid = session.pid,
                        ),
                    )
                }
            }
        }
        scope.launch {
            val exitCode = runCatching { session.exit.first() }.getOrDefault(-1)
            val intentionalClose = session.consumeIntentionalCloseSignal()
            val userInterrupted = session.consumeUserInterruptedSignal()
            val quotaLimited = session.hasQuotaLimitOutput()
            val exitState = classifyConsoleTuiExit(exitCode, intentionalClose, userInterrupted, quotaLimited)
            agentStatusChangedListener?.let { listener ->
                runCatching {
                    listener(
                        AgentStatusSnapshot(
                            projectId = projectId,
                            provider = provider,
                            sessionId = id,
                            state = exitState,
                            error = when {
                                quotaLimited -> "Usage quota limit reached"
                                exitState == AgentState.ERROR -> "Process exited with code $exitCode"
                                else -> null
                            },
                            message = when {
                                userInterrupted -> "Interrupted by user"
                                intentionalClose -> "Process closed by server"
                                exitCode == 0 -> "Process exited"
                                else -> null
                            },
                            pid = session.pid,
                            lastOutputAt = session.lastOutputAt.toEpochMilli(),
                            turnStartedAt = session.turnStartedAt?.toEpochMilli(),
                        ),
                    )
                }
            }
            if (session.consumeTurnDoneOnExitSignal()) {
                runCatching { turnDoneListener?.invoke(projectId, "console_tui_exit") }
                    .onFailure { consoleTuiLog.warn(it) { "[console-tui $id] turn done listener failed after exit" } }
            }
            sessions.remove(id)
            byKey.remove(key, id)
        }
        consoleTuiLog.info { "[console-tui $id] spawned project=$projectId provider=${provider.id} cwd=$workdir command=${command.displayName}" }
        return session
    }

    @Synchronized
    fun find(ownerUserId: String?, projectId: String, provider: AgentProvider): ConsoleTuiSession? {
        if (ownerUserId == null) return findAny(projectId, provider)
        val key = SessionKey(ownerUserId, projectId, provider)
        val id = byKey[key] ?: return null
        val session = sessions[id]
        if (session != null && session.isAlive()) return session
        byKey.remove(key, id)
        if (session != null) {
            sessions.remove(id, session)
            clearState(session)
        }
        return null
    }

    @Synchronized
    private fun findAny(projectId: String, provider: AgentProvider): ConsoleTuiSession? {
        val id = byKey.entries.firstOrNull { (key, _) -> key.projectId == projectId && key.provider == provider }?.value
            ?: return null
        return sessions[id]?.takeIf { it.isAlive() }
    }

    @Synchronized
    fun get(id: String): ConsoleTuiSession? {
        val session = sessions[id] ?: return null
        if (session.isAlive()) return session
        sessions.remove(id)
        byKey.entries.removeIf { it.value == id }
        clearState(session)
        return null
    }

    @Synchronized
    fun close(id: String) {
        sessions[id]?.let { session ->
            session.kill()
            sessions.remove(id)
            byKey.entries.removeIf { it.value == id }
            clearState(session)
        }
    }

    // ownerUserId == null is an internal "close all owners for this project" path.
    @Synchronized
    fun closeProject(ownerUserId: String?, projectId: String) {
        val ids = byKey.entries
            .filter { (key, _) -> (ownerUserId == null || key.ownerUserId == ownerUserId) && key.projectId == projectId }
            .map { it.value }
            .toSet()
        ids.forEach(::close)
    }

    @Synchronized
    fun startNew(ownerUserId: String?, projectId: String) {
        AgentProvider.entries.forEach { provider ->
            freshStarts[SessionKey(ownerUserId, projectId, provider)] = true
        }
        closeProject(ownerUserId, projectId)
    }

    @Synchronized
    fun startNewProject(ownerUserId: String?, projectId: String) {
        AgentProvider.entries.forEach { provider ->
            freshStarts[SessionKey(ownerUserId, projectId, provider)] = true
            freshStarts[SessionKey(null, projectId, provider)] = true
        }
        closeProject(null, projectId)
    }

    fun markAssistantImported(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.markAssistantOutputDetected()
        session.scheduleTurnComplete { projectId, reason ->
            publishState(projectId, session.provider, ProjectState.READY)
            turnDoneListener?.invoke(projectId, reason)
        }
    }

    @Synchronized
    fun shutdownAll() {
        consoleTuiLog.info { "console TUI manager shutdown: closing ${sessions.size} sessions" }
        sessions.keys.toList().forEach(::close)
        scope.cancel()
    }

    private fun reapIdle() {
        if (idleTimeout.isZero || idleTimeout.isNegative) return
        val now = Instant.now()
        sessions.values.forEach { session ->
            if (session.isReapableForIdleTimeout(now, idleTimeout)) {
                consoleTuiLog.info { "[console-tui ${session.id}] idle timeout; closing" }
                close(session.id)
            }
        }
    }

    @Synchronized
    private fun reapIdleForResourcePressure() {
        val guard = resourceGuard ?: return
        guard.decision {
            closeIdleSessionsForResourcePressure()
        }
    }

    @Synchronized
    private fun closeIdleSessionsForResourcePressure(): Int {
        val ids = sessions.values
            .filter { it.isReapableForResourcePressure() }
            .sortedBy { it.lastActivityAt }
            .map { it.id }
        ids.forEach { id ->
            consoleTuiLog.warn { "[console-tui $id] closing disconnected idle session due to memory pressure" }
            close(id)
        }
        return ids.size
    }

    private fun markStalledSessions() {
        val now = Instant.now()
        sessions.values.forEach { session ->
            session.markStalledIfNeeded(now)
            if (session.consumeTurnDoneOnStallSignal()) {
                publishState(session.projectId, session.provider, ProjectState.WAITING)
                scope.launch {
                    runCatching { turnDoneListener?.invoke(session.projectId, "console_tui_stalled") }
                        .onFailure { consoleTuiLog.warn(it) { "[console-tui ${session.id}] turn done listener failed after stall" } }
                }
            }
        }
    }

    private fun clearState(session: ConsoleTuiSession) {
        publishAgentStatus(session.projectId, session.provider, ProjectState.READY, sessionOverride = session)
    }

    private data class SessionKey(
        val ownerUserId: String?,
        val projectId: String,
        val provider: AgentProvider,
    )

    companion object {
        const val REAPER_INTERVAL_MS = 60_000L
    }
}
