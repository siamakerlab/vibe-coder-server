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
    /** v1.69.0 — 동시 in-flight turn 제한 게이트. 기본값 = 무제한(비활성). */
    private val gate: ClaudeConcurrencyGate = ClaudeConcurrencyGate(0),
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

    /**
     * Send [text] as a user turn. Spawns the session if necessary.
     * v1.80.0 — [isAutoResume]=true 면 rate-limit 자동 재개(내부 호출): 재시도 카운터를
     * 리셋하지 않고 history 적재도 생략. 사용자 prompt(false)는 진행 중인 자동 재개를 취소.
     */
    suspend fun sendPrompt(projectId: String, text: String, isAutoResume: Boolean = false) {
        require(text.isNotBlank()) { "prompt text is required" }
        // 실제 stdin 으로 흘러갈 UTF-8 byte size 기준으로 검증. v0.12.3 까지는
        // text.length (char count) 였는데 한국어 등 multi-byte 문자에서는 의도와
        // 다르게 작은 입력이 통과되거나 큰 입력이 거부될 수 있었다.
        val bytes = text.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_PROMPT_BYTES) {
            "prompt too large ($bytes bytes UTF-8 > $MAX_PROMPT_BYTES)"
        }

        val session = ensureSession(projectId)
        // v1.80.0 — 사용자 prompt 면 진행 중인 rate-limit 자동 재개를 취소하고 카운터 리셋.
        if (!isAutoResume) {
            session.retryJob?.cancel()
            session.retryJob = null
            session.rateLimitRetry = 0
        }
        // v0.16.0 — user prompt 영구 적재 (sendPrompt 시점의 sessionId 사용).
        // 자동 재개 프롬프트는 사용자 입력이 아니므로 history 미적재.
        if (!isAutoResume) history?.userPrompt(projectId, session.sessionId, text)
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

        // v1.69.0 — 동시 in-flight 상한 도달 시 permit 이 빌 때까지 대기(queue). 무제한이면 즉시 통과.
        // release 는 setBusy(true→false) 전이 단일 지점(아래 catch 의 write 실패 포함)에서 idempotent 하게.
        // v1.90.0 — 상한 도달로 대기에 들어가면 콘솔에 안내(다른 프로젝트 turn 종료 시 자동 순차 진행).
        gate.acquire(projectId) {
            emitSystem(
                projectId, "rate_limit_waiting",
                "동시 작업 한도(${gate.limit}개)에 도달해 대기 중입니다. 다른 작업이 끝나면 순서대로 자동 진행됩니다.",
            )
        }
        session.stdinMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    session.stdin.write(envelope)
                    session.stdin.newLine()
                    session.stdin.flush()
                }
                session.lastActivity = Instant.now()
                // v1.82.0 — 사용자 prompt → 영속 "미완 turn" 마크 ON(재개 횟수 리셋). 정상 완료 /
                // 취소 / 새 세션 시 OFF. 서버가 비정상 종료(재시작)되면 마크가 남아 부팅 reconcile
                // 이 자동 재개. 자동 재개(isAutoResume) 자체는 마크를 건드리지 않는다.
                if (!isAutoResume) markTurnActive(projectId)
                // v0.98.0 — prompt 전송 성공 → busy=true. ConsoleEvent.Done 시 false 로 전이.
                setBusy(projectId, true)
            } catch (e: IOException) {
                log.warn(e) { "[$projectId] stdin write failed; will respawn on next prompt" }
                // busy 가 true 로 전이되기 전 실패 → setBusy(false) 전이가 안 일어나므로 여기서 명시 release.
                gate.release(projectId)
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
        clearTurnActive(projectId)  // v1.82.0 — 새 세션 시작 → 이전 미완 turn 마크 버림.
        runCatching { sessionIdFile(projectId).deleteIfExists() }
        hub.resetConsole(topic(projectId))
        emitSystem(projectId, "new_session_requested", "Session reset. The next prompt starts a fresh conversation.")
        fireInterrupt(projectId, "new_session")
    }

    /**
     * v1.82.0 — 서버 부팅 시 1회 호출(비동기). 재시작/크래시로 끊긴 미완 turn(turn-active 마크
     * 잔존) 프로젝트마다 "이어서 진행" 프롬프트를 자동 전송(--resume → 멈춘 곳부터). 무거운
     * claude spawn 이라 부팅을 블로킹하지 않도록 내부 scope 에서 순차 실행(2초 간격, 부하 분산).
     */
    fun reconcileInterruptedTurnsAsync() {
        scope.launch {
            val ids = projectIdsWithTurnMark()
            if (ids.isEmpty()) return@launch
            log.info { "재시작으로 끊긴 미완 turn ${ids.size}개 자동 재개 시도: $ids" }
            for (pid in ids) {
                val retries = readTurnActiveRetries(pid) ?: continue
                if (retries >= MAX_BOOT_RESUME_RETRIES) {
                    clearTurnActive(pid)
                    log.warn { "[$pid] 재시작 자동 재개 ${MAX_BOOT_RESUME_RETRIES}회 초과 — 포기" }
                    runCatching {
                        emitSystem(pid, "turn_resume_giveup",
                            "서버 재시작 후 자동 재개가 ${MAX_BOOT_RESUME_RETRIES}회를 초과했습니다. 직접 이어서 진행해 주세요.")
                    }
                    continue
                }
                runCatching {
                    turnActiveFile(pid).writeText((retries + 1).toString())  // 부팅 재개 횟수 증가
                    emitSystem(pid, "turn_auto_resume",
                        "서버 재시작으로 중단된 작업을 자동으로 이어서 진행합니다 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES).")
                    sendPrompt(pid, BOOT_RESUME_PROMPT, isAutoResume = true)
                    log.info { "[$pid] 재시작 끊긴 turn 자동 재개 (${retries + 1}/$MAX_BOOT_RESUME_RETRIES)" }
                }.onFailure { log.warn(it) { "[$pid] boot resume 실패" } }
                delay(2_000)  // 순차 spawn 부하 분산
            }
        }
    }

    /** turn-active 마크가 있는 프로젝트 id 목록 (workspace `.vibecoder/<id>/turn-active` 스캔). */
    private fun projectIdsWithTurnMark(): List<String> {
        val base = turnActiveFile("__probe__").parent?.parent ?: return emptyList()  // .vibecoder 루트
        return runCatching {
            Files.list(base).use { stream ->
                stream.filter { Files.isDirectory(it) && Files.exists(it.resolve("turn-active")) }
                    .map { it.fileName.toString() }
                    .toList()
            }
        }.getOrElse { emptyList() }
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
        clearTurnActive(projectId)  // v1.82.0 — 사용자 명시 중단 → 부팅 자동 재개 대상 아님.
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
    private suspend fun setBusy(projectId: String, value: Boolean, state: String? = null) {
        val prev = busy.put(projectId, value)
        if (prev == value) return
        // v1.71.0 (정밀점검) — permit release 를 busy 전이에 묶지 않는다. busy=true 는
        // sendPrompt 의 stdin write 직후에야 set 되는데, 그 전에 Done/exit 이 먼저
        // 도달하면 false-전이가 안 일어나 permit 이 영구 leak (풀 wedge) 됐다. release 는
        // 종료 sink(Done / onProcessExit / terminateSession / write 실패)에서 직접 호출
        // (gate.release 는 heldKeys 기반 idempotent — SubAgentSessionManager 와 동일 방식).
        // v1.60.0 — 상태칩 명시 상태. 미지정 시 busy → responding / ready.
        // value 가 실제로 바뀐 경우(prev != value)만 도달하므로, false 전이는
        // "직전까지 응답중이었다" 를 뜻함 → Done 은 "ready", 프로세스 종료(cancel/
        // crash/idle-during-busy)는 호출부가 "stopped" 전달.
        val st = state ?: if (value) "responding" else "ready"
        // v1.83.0 — 콘솔 페이지도 state 전달(이전엔 busy boolean 만 → "stopped/중단됨"
        // 구분 불가). rate-limit 재시도 소진 등 비정상 종료를 콘솔 뱃지에 정확 반영.
        hub.emitConsole(topic(projectId)) { seq -> WsFrame.ConsoleBusyState(busy = value, seq = seq, state = st) }
        // v1.3.0 — cross-project topic 으로도 broadcast. /ws/projects 구독자
        // (workspaces 목록 / 대시보드) 가 실시간으로 busy 뱃지 갱신.
        hub.emitConsole(PROJECTS_TOPIC) { seq ->
            WsFrame.ProjectBusyChanged(projectId = projectId, busy = value, seq = seq, state = st)
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
            // v1.80.0 — stream-json input 모드 제약 회수: 백그라운드 작업(Bash run_in_background)
            // 후 turn 을 끝내면 작업이 완료돼도 **자동 재개되지 않는다**(호스트가 stdin 입력을
            // 제어하는 모드라 CLI 가 스스로 새 turn 을 못 만듦). 사용자가 매번 수동으로 진행
            // 메시지를 보내야 하던 현상 → 시스템 프롬프트로 "turn 을 끝내지 말고 같은 turn 안에서
            // 완료까지 기다리라" 강제. ghost(chat)는 Bash 차단이라 무관 → 제외.
            if (!WorkspacePath.isGhostId(projectId)) {
                add("--append-system-prompt"); add(BACKGROUND_TASK_GUIDE)
            }
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
                    val prev = it.sessionId
                    it.sessionId = event.sessionId
                    it.sawSessionStarted = true
                    // v1.91.5 — 새 세션 첫 턴: sendPrompt 가 아직 session_id 미발급(null)
                    // 상태로 저장한 user 프롬프트를, 방금 확정된 실제 id 로 backfill.
                    // 콘솔 복원(initialHistory)은 현재 session_id 로 필터하므로, 이 backfill
                    // 이 없으면 새 세션 첫 프롬프트만 재방문 시 누락된다(assistant 응답은 보임).
                    if (prev == null && event.sessionId.isNotBlank()) {
                        history?.adoptNullSession(projectId, event.sessionId)
                    }
                }
                runCatching { writeSessionId(projectId, event.sessionId) }
                    .onFailure { log.warn(it) { "[$projectId] failed to persist session-id" } }
            }
            // v1.83.0 — claude 가 host stdin 없이 자발적으로 turn 을 재개(background task
            // 완료 후 자동 속행 등)하면 sendPrompt 를 안 거쳐 busy 가 false 인 채로 프레임만
            // 흐른다 → 뱃지가 "대기중" 인데 실제론 작업이 진행되는 고착 상태. 활동 프레임
            // (assistant/tool)이 오는데 busy 가 아니면 busy=true + 미완 마크로 동기화한다.
            // (Done/ErrorEvent 는 아래에서 종료 처리하므로 제외.)
            if (busy[projectId] != true &&
                (event is ClaudeEvent.AssistantMessage ||
                    event is ClaudeEvent.ToolUse ||
                    event is ClaudeEvent.ToolResult)
            ) {
                markTurnActive(projectId)
                setBusy(projectId, true)
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
                gate.release(projectId)  // v1.71.0 — turn 정상 완료 → permit 반환(idempotent).
                sessions[projectId]?.rateLimitRetry = 0  // v1.80.0 — 정상 완료 → rate-limit 카운터 리셋.
                clearTurnActive(projectId)  // v1.82.0 — 정상 완료 → 미완 마크 OFF.
                setBusy(projectId, false)
                // v1.59.0 — 자동화 리스너에 turn 완료 통지 (fire-and-forget, stdout 파싱 비blocking).
                fireTurnDone(projectId, event.reason)
            } else if (event is ClaudeEvent.ErrorEvent) {
                // v1.80.0 — result(is_error=true) 도 turn 종료. 이전엔 busy 해제가 누락돼
                // 에러 turn 후 콘솔이 "응답 중"에 멈춰 있었다. permit 반환 + 자동화 통지.
                gate.release(projectId)
                fireTurnDone(projectId, "error:${event.code}")
                if (isRateLimitError(event)) {
                    // 서버측 일시 rate limit → 지수 백오프로 "이어서 진행" 자동 재개(busy·마크 내부 관리).
                    scheduleRateLimitRetry(projectId)
                } else {
                    // v1.82.0 — 에러로 turn 종료(rate-limit 아님) → 미완 마크 OFF(재개 대상 아님).
                    sessions[projectId]?.rateLimitRetry = 0
                    clearTurnActive(projectId)
                    setBusy(projectId, false, "stopped")
                }
            }
        }
    }

    // v1.80.0 — 서버측 일시 rate limit("Server is temporarily limiting requests", 사용량 한도
    // 아님) 판정. result(is_error) 의 message/subtype 패턴 매칭.
    private fun isRateLimitError(event: ClaudeEvent.ErrorEvent): Boolean {
        val m = (event.message ?: "").lowercase()
        val c = event.code.lowercase()
        return m.contains("temporarily limiting") || m.contains("rate limit") ||
            m.contains("rate-limit") || m.contains("rate_limit") || m.contains("429") ||
            c.contains("rate") || c.contains("overloaded")
    }

    /**
     * v1.80.0 — rate-limit error turn 자동 재개. 지수 백오프(30/60/120초)로 최대
     * [MAX_RATE_LIMIT_RETRIES] 회 "이어서 진행" 프롬프트를 같은 --resume 세션에 자동 전송
     * (멈춘 곳부터 재개). 초과 시 자동 재개를 중지하고 상태를 "중지됨" 으로 표시.
     */
    private suspend fun scheduleRateLimitRetry(projectId: String) {
        val session = sessions[projectId] ?: run { setBusy(projectId, false, "stopped"); return }
        val attempt = session.rateLimitRetry + 1
        if (attempt > MAX_RATE_LIMIT_RETRIES) {
            session.rateLimitRetry = 0
            session.retryJob?.cancel(); session.retryJob = null
            clearTurnActive(projectId)  // v1.82.0 — rate-limit 자동 재개 포기 → 미완 마크 OFF.
            setBusy(projectId, false, "stopped")
            emitSystem(projectId, "rate_limit_giveup",
                "서버측 rate limit 이 ${MAX_RATE_LIMIT_RETRIES}회 자동 재개 후에도 지속됩니다. " +
                "자동 재개를 중지합니다 — 잠시 후 직접 이어서 진행해 주세요.")
            return
        }
        session.rateLimitRetry = attempt
        val delayMs = RATE_LIMIT_BASE_BACKOFF_MS shl (attempt - 1)  // 30 / 60 / 120 s
        session.retryJob?.cancel()
        // 재개 대기 동안 busy 유지(응답 중 표시) — 사용자에게 진행 예정 안내.
        setBusy(projectId, true, "responding")
        emitSystem(projectId, "rate_limit_retry",
            "서버측 일시 rate limit (사용량 한도 아님). ${delayMs / 1000}초 후 자동으로 이어서 진행합니다 " +
            "($attempt/$MAX_RATE_LIMIT_RETRIES).")
        session.retryJob = scope.launch {
            delay(delayMs)  // 취소(사용자 prompt / cancel / 종료) 시 CancellationException 으로 정상 종료
            val cur = sessions[projectId]
            if (cur !== session || cur.process.isAlive != true) {
                setBusy(projectId, false, "stopped"); return@launch
            }
            runCatching { sendPrompt(projectId, RATE_LIMIT_RESUME_PROMPT, isAutoResume = true) }
                .onFailure {
                    log.warn(it) { "[$projectId] rate-limit 자동 재개 실패" }
                    setBusy(projectId, false, "stopped")
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
        is ClaudeEvent.SystemNote -> WsFrame.ConsoleSystem(code = event.code, message = event.message, seq = seq)
        is ClaudeEvent.BackgroundTask -> WsFrame.ConsoleBackgroundTask(
            kind = event.kind, taskId = event.taskId, description = event.description,
            taskType = event.taskType, status = event.status,
            lastTool = event.lastTool, toolUses = event.toolUses, seq = seq,
        )
        is ClaudeEvent.Unknown -> WsFrame.ConsoleUnknown(raw = event.raw, seq = seq)
    }

    private fun onProcessExit(projectId: String, proc: Process, session: ProjectSession) {
        val exit = runCatching { proc.exitValue() }.getOrNull()
        val crashed = exit != null && exit != 0
        // v1.71.0 — 프로세스 종료(crash/clean/intentional) 시 permit 반환(idempotent).
        gate.release(projectId)
        // v0.98.0 — process exit 시 항상 busy 해제. setBusy 가 suspend 라
        // launch 안에서 호출 (onProcessExit 자체는 비-suspend).
        // v1.60.0 — busy 중 프로세스 종료 = 미완 turn 중단 → "stopped". busy 아니었으면
        // setBusy 가 idempotent 로 무시(=정상 완료 후 종료엔 영향 없음).
        scope.launch { setBusy(projectId, false, "stopped") }
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
        // v1.80.0 — 예약된 rate-limit 자동 재개가 있으면 취소(cancel / startNew / idle / shutdown).
        session.retryJob?.cancel()
        session.retryJob = null
        // v1.71.0 — cancel / startNew / idle reap / shutdown / crash 종료 시 permit 반환(idempotent).
        gate.release(projectId)
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
        // v1.60.0 — busy 중 종료면 "stopped"(중지됨), 아니면 idempotent 무시.
        setBusy(projectId, false, "stopped")
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

    // ── v1.82.0 — turn-active 영속 마크 (서버 재시작으로 끊긴 미완 turn 자동 재개용) ──
    // 존재 = "이 프로젝트에 정상 완료되지 않은 turn 이 있음". 파일 내용 = 부팅 자동 재개 횟수.
    // busy 는 메모리라 재시작 시 사라지므로, 영속 파일로 미완 여부를 남긴다.
    private fun turnActiveFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("turn-active")

    /** 사용자 prompt 전송 시 마크 ON (자동 재개 횟수 0 으로 리셋). */
    private fun markTurnActive(projectId: String) {
        runCatching {
            val f = turnActiveFile(projectId)
            Files.createDirectories(f.parent)
            f.writeText("0")
        }.onFailure { log.warn(it) { "[$projectId] turn-active 마크 실패" } }
    }

    /** turn 정상 완료 / 사용자 취소 / 새 세션 시 마크 OFF. */
    private fun clearTurnActive(projectId: String) {
        runCatching { turnActiveFile(projectId).deleteIfExists() }
    }

    /** 마크가 있으면 자동 재개 횟수, 없으면 null. */
    private fun readTurnActiveRetries(projectId: String): Int? {
        val f = turnActiveFile(projectId)
        return if (f.exists()) f.readText().trim().toIntOrNull() ?: 0 else null
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
        /** v1.80.0 — 연속 rate-limit 자동 재개 횟수(성공 turn / 사용자 prompt 시 0 으로 리셋). */
        @Volatile var rateLimitRetry: Int = 0,
        /** v1.80.0 — 예약된 자동 재개 Job (사용자 개입 / cancel / 종료 시 취소). */
        @Volatile var retryJob: Job? = null,
    )

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val IDLE_CHECK_INTERVAL_MS = 60_000L
        /**
         * v1.80.0 — `--append-system-prompt` 로 주입. stream-json input 모드에선 백그라운드
         * 작업 완료 시 CLI 가 자동 재개되지 않으므로(호스트가 입력 제어), turn 을 끝내지 말고
         * 같은 turn 안에서 완료까지 기다리도록 유도. 영어로 작성(시스템 프롬프트 준수율).
         */
        const val BACKGROUND_TASK_GUIDE =
            "[vibe-coder console environment] You are running under Claude Code in stream-json " +
            "mode where the HOST controls stdin. CRITICAL: if you start a background task " +
            "(e.g. Bash run_in_background:true) and then END your turn to 'wait for it', you will " +
            "NOT be auto-resumed when it finishes — the user would have to manually send another " +
            "message every time. So: (1) prefer running long commands synchronously with a larger " +
            "timeout and finish within a single turn; (2) if you must run something asynchronously, " +
            "do NOT end the turn — keep polling in the same turn (sleep, then check status/output, " +
            "repeat) until it completes, then report the result; (3) never conclude a turn with " +
            "'I'll do this in the background and wait for completion' — you will not be resumed."
        /**
         * v1.80.0 — 서버측 일시 rate limit("Server is temporarily limiting requests", 사용량
         * 한도 아님) 으로 turn 이 error 종료되면, [RATE_LIMIT_BASE_BACKOFF_MS] 지수 백오프
         * (30/60/120초) 로 최대 [MAX_RATE_LIMIT_RETRIES] 회 "이어서 진행" 프롬프트를 자동
         * 전송(같은 --resume 세션이라 멈춘 곳부터 재개). 초과 시 상태 "중지됨".
         */
        const val MAX_RATE_LIMIT_RETRIES = 5
        const val RATE_LIMIT_BASE_BACKOFF_MS = 30_000L
        const val RATE_LIMIT_RESUME_PROMPT =
            "Continue from where you left off — the previous turn was interrupted by a temporary " +
            "server-side rate limit (not a usage limit). Resume the in-progress work; do not restart from scratch."
        /**
         * v1.82.0 — 서버 재시작으로 끊긴 미완 turn 의 부팅 자동 재개. 무한 재개 방지로 최대
         * [MAX_BOOT_RESUME_RETRIES] 회(재시작이 반복돼도). 초과 시 마크 제거 + 수동 안내.
         */
        const val MAX_BOOT_RESUME_RETRIES = 2
        const val BOOT_RESUME_PROMPT =
            "The vibe-coder server was restarted while you were mid-task, so the previous turn was " +
            "interrupted. Continue from where you left off — resume the in-progress work; do not restart from scratch."
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
