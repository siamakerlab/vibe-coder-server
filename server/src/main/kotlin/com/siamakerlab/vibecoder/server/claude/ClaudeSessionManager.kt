package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val log = KotlinLogging.logger {}

/**
 * Owns the lifecycle of one persistent `claude` child process per project.
 *
 * - First [sendPrompt] for a project spawns the process (resuming if a saved session-id exists).
 * - Subsequent prompts re-use the same stdin/stdout — no cold start.
 * - [startNew] tears down the current process and deletes the saved session-id.
 * - [shutdown] sends SIGTERM (then SIGKILL after 5 s) to every alive session.
 *
 * All disk reads/writes funnel through [workspace] so [WorkspacePath]'s path-safety rules apply.
 */
class ClaudeSessionManager(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val hub: LogHub,
    private val parser: ClaudeStreamParser = ClaudeStreamParser(),
    /** Idle SIGTERM after this duration. session-id file is preserved. */
    private val idleTimeout: Duration = Duration.ofMinutes(30),
    /** v0.16.0 — turn 영구 적재. null 이면 history persistence 비활성 (테스트). */
    private val history: ConversationHistoryService? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ProjectSession>()

    /** Synchronizes spawn — prevents two simultaneous "first prompt" arrivals racing to start a process. */
    private val spawnLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * v0.98.0 — projectId → busy flag (사용자 prompt 보낸 후 Done/cancel/crash/idle 까지 true).
     * Web 클라이언트는 자체 inFlight 로 동기화하지만, Android / REST 폴링 / 첫 진입
     * 클라이언트가 server-side 상태를 즉시 알 수 있도록 노출.
     * setBusy() 가 변경 시점에 ConsoleBusyState WS frame 도 emit (live 클라이언트 sync).
     */
    private val busy = ConcurrentHashMap<String, Boolean>()

    /**
     * v1.59.0 — turn 완료(정상 Done) 리스너. 프롬프트 자동화
     * ([com.siamakerlab.vibecoder.server.automation.PromptAutomationManager])가 등록해
     * "작업 완료마다 다음 프롬프트"를 구현. 순환의존 방지를 위해 setter 주입.
     * reason = `ClaudeEvent.Done.subtype` (예: "success", "error_max_turns").
     */
    @Volatile
    var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    /**
     * v1.59.0 — turn 비정상 중단(cancel / new session / crash) 리스너. 진행 중인
     * 자동화를 멈추기 위함. reason = "cancelled" | "new_session" | "crashed".
     */
    @Volatile
    var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null

    private fun fireTurnDone(projectId: String, reason: String) {
        val l = turnDoneListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] turnDoneListener failed" } } }
    }

    private fun fireInterrupt(projectId: String, reason: String) {
        val l = turnInterruptListener ?: return
        scope.launch { runCatching { l(projectId, reason) }.onFailure { log.warn(it) { "[$projectId] turnInterruptListener failed" } } }
    }

    init {
        // Idle reaper
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                reapIdleSessions()
            }
        }
    }

    /** Send [text] as a user turn. Spawns the session if necessary. */
    suspend fun sendPrompt(projectId: String, text: String) {
        require(text.isNotBlank()) { "prompt text is required" }
        // 실제 stdin 으로 흘러갈 UTF-8 byte size 기준으로 검증. v0.12.3 까지는
        // text.length (char count) 였는데 한국어 등 multi-byte 문자에서는 의도와
        // 다르게 작은 입력이 통과되거나 큰 입력이 거부될 수 있었다.
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PROMPT_BYTES) {
            "prompt too large ($bytes bytes UTF-8 > $MAX_PROMPT_BYTES)"
        }

        val session = ensureSession(projectId)
        // v0.16.0 — user prompt 영구 적재 (sendPrompt 시점의 sessionId 사용).
        history?.userPrompt(projectId, session.sessionId, text)
        val envelope = buildJsonObject {
            put("type", "user")
            put("message", buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                })
            })
        }.toString()

        session.stdinMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    session.stdin.write(envelope)
                    session.stdin.newLine()
                    session.stdin.flush()
                }
                session.lastActivity = Instant.now()
                // v0.98.0 — prompt 전송 성공 → busy=true. ConsoleEvent.Done 시 false 로 전이.
                setBusy(projectId, true)
            } catch (e: IOException) {
                log.warn(e) { "[$projectId] stdin write failed; will respawn on next prompt" }
                emitSystem(projectId, "process_crashed", "Claude process is no longer accepting input (${e.message}). Retrying on next prompt.")
                terminateSession(projectId)
                fireInterrupt(projectId, "crashed")
                throw e
            }
        }
    }

    /** Stop the current process (if any), forget its session-id, clear replay ring. */
    suspend fun startNew(projectId: String) {
        terminateSession(projectId)
        runCatching { sessionIdFile(projectId).deleteIfExists() }
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "new_session_requested", "Session reset. The next prompt starts a fresh conversation.")
        fireInterrupt(projectId, "new_session")
    }

    /**
     * v0.13.0 — 진행 중인 turn 강제 중단.
     *
     * Claude CLI stdin 으로 인터럽트 envelope 를 보내는 방법이 없어, 현재 자식 프로세스를
     * SIGTERM 으로 죽이고 session-id 는 보존 (다음 prompt 시 --resume 으로 같은 대화 이어감).
     * 사용자가 답변 도중 잘못된 방향이라고 판단하면 즉시 stop → 새 prompt 로 방향 전환 가능.
     *
     * startNew 와 다른 점: startNew 는 session-id 삭제 → 완전 새 대화. cancel 은 그대로 이어감.
     */
    suspend fun cancelTurn(projectId: String) {
        val existed = sessions[projectId]?.process?.isAlive == true
        if (!existed) {
            emitSystem(projectId, "cancel_noop", "진행 중인 Claude turn 이 없습니다.")
            return
        }
        terminateSession(projectId)
        emitSystem(
            projectId, "turn_cancelled",
            "사용자가 turn 을 중단했습니다. 다음 prompt 는 같은 세션 (--resume) 으로 이어집니다.",
        )
        fireInterrupt(projectId, "cancelled")
    }

    fun isAlive(projectId: String): Boolean =
        sessions[projectId]?.process?.isAlive == true

    /**
     * v1.7.3 — in-memory session 없으면 file (claude-session.id) 의 last id 로 fallback.
     * 서버 재시작 후엔 sessions map 이 비어 있어 이전엔 null 반환 → 콘솔이 "no session"
     * 표시 + status pill empty. 실제로는 file 에 last sessionId 가 영속되어 있고 다음
     * prompt 시점에 `--resume` 으로 spawn 되므로, 라벨도 "idle (will resume)" 로 일관되게.
     */
    fun currentSessionId(projectId: String): String? =
        sessions[projectId]?.sessionId ?: readSessionId(projectId)

    /** v0.98.0 — 해당 프로젝트가 현재 응답 중인지. 프로젝트별 독립 상태. */
    fun isBusy(projectId: String): Boolean = busy[projectId] == true

    /**
     * v0.98.0 — busy 상태 전이. 값이 실제 변경됐을 때만 ConsoleBusyState WS frame emit
     * (idempotent 호출 시 노이즈 방지). projectId 별로 독립 — 여러 프로젝트 동시 작업
     * 시 각 프로젝트 콘솔이 자기 상태만 받음 (hub.topic 이 프로젝트별 분리).
     */
    private suspend fun setBusy(projectId: String, value: Boolean) {
        val prev = busy.put(projectId, value)
        if (prev == value) return
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleBusyState(busy = value, seq = seq) }
        // v1.3.0 — cross-project topic 으로도 broadcast. /ws/projects 구독자
        // (workspaces 목록 / 대시보드) 가 실시간으로 busy 뱃지 갱신.
        hub.emitConsole(PROJECTS_TOPIC) { seq ->
            WsFrame.ProjectBusyChanged(projectId = projectId, busy = value, seq = seq)
        }
    }

    suspend fun shutdown() {
        log.info { "shutting down ${sessions.size} Claude session(s)" }
        sessions.keys.toList().forEach { terminateSession(it) }
        scope.cancel()
    }

    // region internals

    private suspend fun ensureSession(projectId: String): ProjectSession {
        sessions[projectId]?.let { existing ->
            if (existing.process.isAlive) return existing
            log.info { "[$projectId] stale session detected (process exited); respawning" }
            terminateSession(projectId)
        }
        val lock = spawnLocks.computeIfAbsent(projectId) { Mutex() }
        return lock.withLock {
            sessions[projectId]?.takeIf { it.process.isAlive } ?: spawnSession(projectId)
        }
    }

    private suspend fun spawnSession(projectId: String): ProjectSession {
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw IllegalStateException("project root not found: $projectRoot")
        }
        // v0.12.2 — 기존 프로젝트 (v0.7.0 이전 생성) 도 권한 정책이 적용되도록
        // .claude/settings.json + CLAUDE.md 가 없으면 매 spawn 전에 자동 backfill.
        com.siamakerlab.vibecoder.server.projects.ProjectScaffolder.ensureClaudeFiles(projectRoot)

        val savedId = readSessionId(projectId)
        val cmd = resolveClaudeCmd()
        val args = buildList {
            add(cmd)
            add("--output-format"); add("stream-json")
            add("--input-format"); add("stream-json")
            add("--verbose")
            // v0.12.2 — vibe-coder 의 비인터랙티브 환경은 권한 prompt 응답 불가.
            // bypassPermissions 를 spawn 인자로 강제 (.claude/settings.json 누락
            // 케이스에서도 안전). CLAUDE.md §3 의 sandbox 정책과 일관.
            add("--dangerously-skip-permissions")
            // 인터랙티브 위젯 (AskUserQuestion / EnterPlanMode / ExitPlanMode) 명시 차단 —
            // 모델이 호출하면 즉시 거부되어 다른 경로 (응답 끝에 옵션 나열) 로 진행.
            // v1.55.0 — General Chat ghost(__scratch__ / __chat_*)는 "대화 전용":
            // 파일 생성·수정·실행(빌드)·하위에이전트 도구를 추가 차단해 실제 프로젝트
            // 작업이 일어나지 않게 한다. 읽기(Read/Glob/Grep)·웹검색(WebSearch/WebFetch)
            // 은 허용해 대화 품질 유지. 일반 프로젝트는 영향 없음.
            add("--disallowedTools")
            val disallowed = buildString {
                append("AskUserQuestion ExitPlanMode EnterPlanMode NotebookEdit")
                if (WorkspacePath.isGhostId(projectId)) {
                    append(" Bash Write Edit Task")
                }
            }
            add(disallowed)
            if (savedId != null) {
                add("--resume"); add(savedId)
            }
        }
        log.info { "[$projectId] spawning: ${args.joinToString(" ")} (cwd=$projectRoot)" }

        val proc = try {
            val pb = ProcessBuilder(args)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
            // v0.7.0 — API 키 모드(.env.api-key 등록 시) 면 ANTHROPIC_API_KEY 주입.
            com.siamakerlab.vibecoder.server.env.ClaudeProcessEnv.applyApiKey(pb.environment())
            pb.start()
        } catch (e: IOException) {
            emitSystem(projectId, "claude_unavailable", "Failed to spawn Claude: ${e.message}")
            throw e
        }

        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val stdout = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        val stderr = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))

        val session = ProjectSession(
            projectId = projectId,
            process = proc,
            stdin = stdin,
            sessionId = savedId,
            lastActivity = Instant.now(),
            wasResuming = savedId != null,
            startedAt = Instant.now(),
        )
        sessions[projectId] = session

        // stdout reader
        session.readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { stdout.readLine() } ?: break
                    if (line.isBlank()) continue
                    handleStdoutLine(projectId, line)
                }
            } catch (e: IOException) {
                log.debug(e) { "[$projectId] stdout reader ended" }
            } finally {
                onProcessExit(projectId, proc, session)
            }
        }
        // stderr reader (informational, but we sample the last few lines for resume-failure detection)
        session.stderrJob = scope.launch {
            try {
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { stderr.readLine() } ?: break
                    if (line.isBlank()) continue
                    log.debug { "[$projectId][stderr] $line" }
                    synchronized(session.stderrTail) {
                        session.stderrTail.addLast(line)
                        while (session.stderrTail.size > STDERR_TAIL_LIMIT) session.stderrTail.pollFirst()
                    }
                }
            } catch (e: IOException) {
                log.debug(e) { "[$projectId] stderr reader ended" }
            }
        }
        return session
    }

    /**
     * Returns true when the just-exited process appears to have died because `--resume <id>`
     * referenced a session the CLI no longer accepts. Heuristic:
     *  - The session was launched with `--resume`.
     *  - It exited within [RESUME_FAILURE_WINDOW_MS] (real session work takes longer).
     *  - Stderr contains one of the [RESUME_FAILURE_PATTERNS] phrases OR no SessionStarted
     *    frame was ever observed (so the CLI never accepted the resume).
     */
    private fun looksLikeResumeFailure(session: ProjectSession): Boolean {
        if (!session.wasResuming) return false
        val elapsed = java.time.Duration.between(session.startedAt, Instant.now()).toMillis()
        if (elapsed > RESUME_FAILURE_WINDOW_MS) return false
        // If a SessionStarted frame was observed, the resume succeeded — the crash is something else.
        if (session.sawSessionStarted) return false
        val stderrText = synchronized(session.stderrTail) { session.stderrTail.joinToString("\n") }.lowercase()
        return RESUME_FAILURE_PATTERNS.any { stderrText.contains(it) }
            || stderrText.isEmpty()    // silent fast exit on resume is treated as failure too
    }

    private suspend fun handleStdoutLine(projectId: String, line: String) {
        val events = parser.parseLine(line)
        if (events.isEmpty()) return
        for (event in events) {
            // capture session-id from the system/init line
            if (event is ClaudeEvent.SessionStarted) {
                sessions[projectId]?.let {
                    it.sessionId = event.sessionId
                    it.sawSessionStarted = true
                }
                runCatching { writeSessionId(projectId, event.sessionId) }
                    .onFailure { log.warn(it) { "[$projectId] failed to persist session-id" } }
            }
            hub.emitConsole(topic(projectId)) { seq -> toWsFrame(event, seq) }
            // v0.16.0 — turn 영구 적재. SessionStarted 는 자체 sessionId 사용 (위에서
            // session.sessionId 가 갱신되기 전이라). 그 외엔 현재 session 의 id.
            val sidForRow = when (event) {
                is ClaudeEvent.SessionStarted -> event.sessionId
                else -> sessions[projectId]?.sessionId
            }
            history?.event(projectId, sidForRow, event)
            // v0.98.0 — Done 이벤트 시 busy=false. ConsoleBusyState 자동 emit.
            if (event is ClaudeEvent.Done) {
                setBusy(projectId, false)
                // v1.59.0 — 자동화 리스너에 turn 완료 통지 (fire-and-forget, stdout 파싱 비blocking).
                fireTurnDone(projectId, event.reason)
            }
        }
    }

    private fun toWsFrame(event: ClaudeEvent, seq: Long): WsFrame = when (event) {
        is ClaudeEvent.SessionStarted -> WsFrame.ConsoleSessionStarted(
            sessionId = event.sessionId, model = event.model, cwd = event.cwd, seq = seq,
        )
        is ClaudeEvent.AssistantMessage -> WsFrame.ConsoleAssistant(
            text = event.text, isPartial = event.isPartial, seq = seq,
        )
        is ClaudeEvent.ToolUse -> WsFrame.ConsoleToolUse(
            toolName = event.toolName, input = event.input, toolUseId = event.toolUseId, seq = seq,
        )
        is ClaudeEvent.ToolResult -> WsFrame.ConsoleToolResult(
            toolUseId = event.toolUseId, output = event.output, isError = event.isError, seq = seq,
        )
        is ClaudeEvent.ErrorEvent -> WsFrame.ConsoleError(
            code = event.code, message = event.message, seq = seq,
        )
        is ClaudeEvent.Done -> WsFrame.ConsoleDone(reason = event.reason, seq = seq)
        is ClaudeEvent.UsageReport -> {
            // v0.63.0 — Phase 42 usage 정보는 콘솔 직접 표시 X (turn 종료 시 작은 system
            // notice 로만). 영구 적재는 ConversationHistoryService 가 별도 처리.
            val parts = mutableListOf<String>()
            event.inputTokens?.let { parts += "input ${it}" }
            event.outputTokens?.let { parts += "output ${it}" }
            event.cacheReadInputTokens?.let { parts += "cache-read ${it}" }
            event.cacheCreationInputTokens?.let { parts += "cache-create ${it}" }
            WsFrame.ConsoleSystem(code = "usage", message = parts.joinToString(" · "), seq = seq)
        }
        is ClaudeEvent.Unknown -> WsFrame.ConsoleUnknown(raw = event.raw, seq = seq)
    }

    private fun onProcessExit(projectId: String, proc: Process, session: ProjectSession) {
        val exit = runCatching { proc.exitValue() }.getOrNull()
        val crashed = exit != null && exit != 0
        // v0.98.0 — process exit 시 항상 busy 해제. setBusy 가 suspend 라
        // launch 안에서 호출 (onProcessExit 자체는 비-suspend).
        scope.launch { setBusy(projectId, false) }
        if (session.intentionalKill) {
            // B1 (21차 점검) — 의도된 종료(cancel/startNew/idle reap/shutdown). SIGTERM 의
            // 비정상 종료코드를 crash 로 보지 않음 → session-id 보존, 오메시지 미emit.
            log.info { "[$projectId] claude terminated intentionally (code=$exit)" }
        } else if (crashed) {
            log.warn { "[$projectId] claude exited with code $exit" }
            val resumeFailed = looksLikeResumeFailure(session)
            scope.launch {
                if (resumeFailed) {
                    runCatching { sessionIdFile(projectId).deleteIfExists() }
                    emitSystem(
                        projectId,
                        "resume_failed_starting_new",
                        "Previous Claude session could not be resumed (CLI rejected --resume). " +
                            "Cleared session id; the next prompt will start a new session.",
                    )
                } else {
                    emitSystem(
                        projectId,
                        "process_crashed",
                        "Claude exited with code $exit. Next prompt will attempt to resume the session.",
                    )
                }
            }
        } else {
            log.info { "[$projectId] claude exited cleanly (code=$exit)" }
        }
        runCatching { session.stdin.close() }
        sessions.remove(projectId, session)
    }

    private suspend fun terminateSession(projectId: String) {
        val session = sessions.remove(projectId) ?: return
        // B1 (21차 점검) — SIGTERM 전에 의도된 종료임을 표식. onProcessExit(readerJob
        // finally) 이 이 session 참조를 그대로 보므로 resume-failure 오판을 차단.
        session.intentionalKill = true
        runCatching { session.stdin.close() }
        if (session.process.isAlive) {
            session.process.destroy()
            withContext(Dispatchers.IO) {
                if (!session.process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn { "[$projectId] SIGTERM grace expired; SIGKILL" }
                    session.process.destroyForcibly()
                }
            }
        }
        session.readerJob?.cancel()
        session.stderrJob?.cancel()
        // v0.98.0 — process 종료 (cancel / startNew / idle reap / crash) 시 busy 항상 false.
        setBusy(projectId, false)
    }

    private suspend fun reapIdleSessions() {
        val now = Instant.now()
        val cutoff = now.minus(idleTimeout)
        sessions.values.toList().forEach { s ->
            if (s.lastActivity.isBefore(cutoff)) {
                log.info { "[${s.projectId}] idle for ${Duration.between(s.lastActivity, now).toMinutes()}m; SIGTERM" }
                emitSystem(s.projectId, "idle_terminated", "Session went idle and was paused. Send a prompt to resume.")
                terminateSession(s.projectId)
            }
        }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(topic(projectId)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
        // v0.16.0 — system notice 도 history 에 적재 (process_crashed / turn_cancelled 등).
        history?.systemNotice(projectId, sessions[projectId]?.sessionId, code, message)
    }

    private fun topic(projectId: String) = LogHub.consoleTopic(projectId)

    private fun sessionIdFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("claude-session.id")

    private fun readSessionId(projectId: String): String? {
        val f = sessionIdFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    private fun writeSessionId(projectId: String, id: String) {
        val f = sessionIdFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText(id)
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private data class ProjectSession(
        val projectId: String,
        val process: Process,
        val stdin: BufferedWriter,
        @Volatile var sessionId: String?,
        @Volatile var lastActivity: Instant,
        val stdinMutex: Mutex = Mutex(),
        @Volatile var readerJob: Job? = null,
        @Volatile var stderrJob: Job? = null,
        /** True iff this process was launched with `--resume <savedId>`. */
        val wasResuming: Boolean = false,
        /** Wall-clock time the process started — used for resume-failure detection. */
        val startedAt: Instant = Instant.now(),
        /** Flips true once a `system/init` frame arrives, proving the CLI accepted the resume. */
        @Volatile var sawSessionStarted: Boolean = false,
        /**
         * B1 (21차 점검) — 의도된 종료(cancelTurn / startNew / idle reap / shutdown) 표식.
         * terminateSession 이 SIGTERM 전에 true 로 세팅 → onProcessExit 이 SIGTERM 의
         * 비정상 종료코드(143)를 resume-failure 로 오판해 보존돼야 할 session-id 를
         * 삭제하거나 process_crashed 오메시지를 emit 하지 않도록 한다.
         */
        @Volatile var intentionalKill: Boolean = false,
        /** Last N stderr lines for resume-failure heuristics. */
        val stderrTail: java.util.ArrayDeque<String> = java.util.ArrayDeque(),
    )

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val IDLE_CHECK_INTERVAL_MS = 60_000L
        /**
         * v1.3.0 — Cross-project busy state broadcast topic. workspaces 목록 /
         * 대시보드가 `/ws/projects` 로 구독.
         */
        const val PROJECTS_TOPIC = "__projects__"
        /** Sessions that die within this window with `--resume` are treated as resume failures. */
        const val RESUME_FAILURE_WINDOW_MS = 5_000L
        const val STDERR_TAIL_LIMIT = 20

        /** Substrings (lowercase) in stderr that mark a resume rejection by the CLI. */
        val RESUME_FAILURE_PATTERNS = listOf(
            "session not found",
            "invalid session",
            "no such session",
            "could not resume",
            "session id not recognized",
            "unknown session",
        )
    }
}
