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
     * v1.100.0 — projectId → 마지막 상태칩 문자열(responding/ready/waiting/stopped/error).
     * busy(boolean) 만으로는 백그라운드 대기(waiting, busy=true 유지)·에러(error) 같은
     * 같은-boolean 다른-state 전이를 구분 못 해 뱃지가 안 바뀌었다. setBusy 가 value+state
     * 중 하나라도 바뀌면 emit 하도록 함께 추적한다.
     */
    private val busyState = ConcurrentHashMap<String, String>()

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
                // v1.99.2 — 새 사용자 prompt 는 새 turn 시작 → 이전 turn 의 백그라운드 작업
                // 잔재(완료 통지 누락 등)를 무효화한다. 안 그러면 stale 한 outstanding 때문에
                // 이번 turn 의 Done 이 영구 suspended 로 묶일 수 있다. 자동 재개(isAutoResume)는
                // 같은 turn 의 연속이라 건드리지 않는다.
                if (!isAutoResume) session.outstandingBgTasks.clear()
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
        // v1.106.0/.1 — 컨텍스트 리셋 → 누적 토큰/경고 플래그도 초기화.
        runCatching { contextTokensFile(projectId).deleteIfExists() }
        sessions[projectId]?.let {
            it.lastContextTokens = 0; it.lastInputTokens = 0; it.lastCacheCreationTokens = 0
            it.contextWarned = false
        }
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
                // v1.106.0 (P1-b) — 컨텍스트가 임계 초과면 자동 재개 보류(매 turn 거대한 맥락을
                // 사용자 부재중 자동 과금하는 것을 방지). 사용자가 직접 이어가거나 리셋하도록 안내.
                if (autoResumeBlockedByContext(pid)) {
                    clearTurnActive(pid)
                    runCatching {
                        emitSystem(pid, "turn_resume_skipped_large_context",
                            "컨텍스트가 커서(약 ${lastContextTokens(pid) / 1000}K) 자동 재개를 보류했습니다 — 직접 이어가거나 '새 세션(컨텍스트 리셋)' 하세요.")
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
        // v1.60.0 — 상태칩 명시 상태. 미지정 시 busy → responding / ready.
        //   ready(유휴)=그레이 / responding(응답중)=초록 / waiting(대기중,백그라운드)=노랑 /
        //   stopped(중단됨, cancel·crash·idle·rate-limit 소진)=보라 / error(API 에러)=빨강.
        val st = state ?: if (value) "responding" else "ready"
        // v1.99.2 → v1.100.0 — busy(boolean) 뿐 아니라 state 문자열 변화도 감지해 emit.
        // 백그라운드 대기(waiting)는 busy=true 를 유지한 채 responding→waiting 으로만 바뀌므로,
        // 예전처럼 busy 값만 비교하면(prev==value) 전이가 묻혀 뱃지가 갱신되지 않았다.
        val prev = busy.put(projectId, value)
        val prevState = busyState.put(projectId, st)
        if (prev == value && prevState == st) return
        // v1.71.0 (정밀점검) — permit release 를 busy 전이에 묶지 않는다. busy=true 는
        // sendPrompt 의 stdin write 직후에야 set 되는데, 그 전에 Done/exit 이 먼저
        // 도달하면 false-전이가 안 일어나 permit 이 영구 leak (풀 wedge) 됐다. release 는
        // 종료 sink(Done / onProcessExit / terminateSession / write 실패)에서 직접 호출
        // (gate.release 는 heldKeys 기반 idempotent — SubAgentSessionManager 와 동일 방식).
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
            // v1.106.0 — 모델 명시(토큰 사용량 최대 레버). 프로젝트 override → 전역 config.
            // 빈 문자열/"default" 면 --model 미전달(CLI 기본값). 운영 기본은 Sonnet.
            val model = effectiveModel(projectId)
            if (model.isNotBlank()) { add("--model"); add(model) }
            // v1.106.0 (P1-a) — MCP 최소화. strict 면 전역 ~/.claude/.mcp.json 무시하고
            // 프로젝트 .mcp.json(있으면)만, 없으면 빈 설정 → MCP 툴 스키마 프리픽스 축소.
            if (isMcpStrict(projectId)) {
                val projMcp = projectRoot.resolve(".mcp.json")
                val cfg = if (projMcp.exists()) projMcp else emptyMcpConfigFile(projectId)
                add("--strict-mcp-config")
                add("--mcp-config"); add(cfg.toString())
            }
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
                    it.model = event.model  // v1.106.1 — 윈도우 한도 추정용 실제 모델 id
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
            // v1.99.2 — 백그라운드 작업 lifecycle 추적. task_started → 집합 추가,
            // 완료/실패 통지 → 제거. 진행 중 프레임이 올 때 lastActivity 도 갱신해 idle reap
            // (기본 30분) 이 백그라운드 대기 turn 을 죽이지 않게 한다. 이 집합이 비어있지
            // 않은 동안 들어온 Done 은 아래에서 "재개 대기" 로 처리된다.
            if (event is ClaudeEvent.BackgroundTask) {
                sessions[projectId]?.let { s ->
                    when {
                        event.kind == "started" -> s.outstandingBgTasks.add(event.taskId)
                        isBackgroundTaskTerminal(event) -> s.outstandingBgTasks.remove(event.taskId)
                    }
                    s.lastActivity = Instant.now()
                }
            }
            // v1.106.0/.1 — usage 토큰 분해로 컨텍스트 점유율 미터 갱신(상시 표시) +
            // 자동재개 가드(P1-b) + 임계 경고. used = input + cache_read + cache_creation.
            // v1.107.1 — result 프레임의 누적치(cumulative)는 제외. assistant 메시지의 이번
            // turn 단일 값만 현재 컨텍스트 크기로 사용(누적치는 윈도우 초과해 1.7M 등 오표시).
            if (event is ClaudeEvent.UsageReport && !event.cumulative) {
                val cacheRead = event.cacheReadInputTokens ?: 0L
                val input = event.inputTokens ?: 0L
                val cacheCreate = event.cacheCreationInputTokens ?: 0L
                if (cacheRead > 0 || input > 0 || cacheCreate > 0) {
                    val s = sessions[projectId]
                    s?.let {
                        it.lastContextTokens = cacheRead
                        it.lastInputTokens = input
                        it.lastCacheCreationTokens = cacheCreate
                    }
                    val used = input + cacheRead + cacheCreate
                    val limit = contextLimitFor(s?.model, used)
                    runCatching { writeContext(projectId, input, cacheRead, cacheCreate, limit) }
                    // 컨텍스트 미터 프레임(상시 표시 바 live 갱신).
                    hub.emitConsole(topic(projectId)) { seq ->
                        WsFrame.ConsoleContextUsage(input, cacheRead, cacheCreate, limit, seq)
                    }
                    val warn = config.claude.contextWarnTokens
                    if (warn > 0 && cacheRead >= warn && s != null && !s.contextWarned) {
                        s.contextWarned = true
                        emitSystem(
                            projectId, "context_large",
                            "⚠ 컨텍스트가 약 ${cacheRead / 1000}K 토큰입니다 — 이어가는 동안 매 turn 전체 맥락이 과금됩니다. " +
                                "비용을 크게 줄이려면 '새 세션(컨텍스트 리셋)' 을 권장합니다.",
                        )
                    }
                }
            }
            // v1.83.0 — claude 가 host stdin 없이 자발적으로 turn 을 재개(background task
            // 완료 후 자동 속행 등)하면 sendPrompt 를 안 거쳐 busy 가 false 인 채로 프레임만
            // 흐른다 → 뱃지가 "대기중" 인데 실제론 작업이 진행되는 고착 상태. 활동 프레임
            // (assistant/tool)이 오면 busy=true(responding) + 미완 마크로 동기화한다.
            // (Done/ErrorEvent 는 아래에서 종료 처리하므로 제외.)
            // v1.100.0 — waiting(백그라운드 대기, busy=true 유지) 중 재개 활동이 오면
            //  responding(초록) 으로 복귀시켜야 한다. 예전 조건(busy != true)만으론 busy 가
            //  이미 true 라 노랑(waiting)에 고착됐다. setBusy 는 idempotent(이미
            //  busy=true·responding 이면 no-op)이므로 매 활동 프레임 호출해도 안전.
            if (event is ClaudeEvent.AssistantMessage ||
                event is ClaudeEvent.ToolUse ||
                event is ClaudeEvent.ToolResult
            ) {
                if (busy[projectId] != true) markTurnActive(projectId)
                setBusy(projectId, true, "responding")
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
                // v1.99.0 — rate-limit error 직후 같은 turn 이 CLI 자체 복구로 Done 까지 도달한
                //  경우, 예약돼 살아남은 재개(retryJob)가 백오프 후 또 발사하지 않도록 먼저 취소.
                //  (정상 turn 에선 retryJob 이 항상 null 이라 무해.) rate-limit 카운터도 리셋.
                sessions[projectId]?.let { it.retryJob?.cancel(); it.retryJob = null; it.rateLimitRetry = 0 }
                // v1.99.2 — 백그라운드 작업(Bash run_in_background / Task)이 아직 진행 중인데
                //  claude 가 turn 을 끝낸(= 완료를 기다리려 turn 종료) 경우. 이건 "종료"가 아니라
                //  "재개 대기"다 — 백그라운드 작업이 끝나면 claude 가 같은 turn 을 자동 재개
                //  (across turns)한다. rate-limit(v1.99.0)과 동형으로 처리:
                //   ① gate.release 안 함 → permit 유지. 안 그러면 재개 turn 이 sendPrompt 를
                //      안 거쳐 permit 없이 진행돼 동시 한도가 무력화됐다(사용자 보고: 실행중 4개).
                //   ② setBusy(false)/clearTurnActive 안 함 → "대기중" 으로 안 떨어지고 미완 마크
                //      유지(busy 는 이미 true 라 그대로). 뱃지가 응답중을 유지.
                //   ③ fireTurnDone 안 함 → 자동화가 미완 turn 을 완료로 오인해 다음 프롬프트를
                //      쏴 turn 이 중첩되던 근본 원인 차단.
                //  재개 turn 이 진짜 Done(outstandingBgTasks 비어있음) 될 때 아래 else 로 정상 종료.
                val bgOutstanding = sessions[projectId]?.outstandingBgTasks?.isNotEmpty() == true
                if (bgOutstanding) {
                    // v1.100.0 — busy 는 true 로 유지하되 상태칩을 "대기중"(waiting, 노랑) 으로
                    // 전이. 재개 turn 의 활동 프레임이 오면 위(L484)에서 responding 으로 자연 복귀.
                    setBusy(projectId, true, "waiting")
                    emitSystem(
                        projectId, "bg_task_suspended",
                        "백그라운드 작업이 진행 중이라 turn 을 유지합니다 — 완료되면 자동으로 이어집니다.",
                    )
                } else {
                    gate.release(projectId)  // v1.71.0 — turn 정상 완료 → permit 반환(idempotent).
                    clearTurnActive(projectId)  // v1.82.0 — 정상 완료 → 미완 마크 OFF.
                    setBusy(projectId, false)
                    // v1.59.0 — 자동화 리스너에 turn 완료 통지 (fire-and-forget, stdout 파싱 비blocking).
                    fireTurnDone(projectId, event.reason)
                }
            } else if (event is ClaudeEvent.ErrorEvent) {
                if (isRateLimitError(event)) {
                    // v1.99.0 — rate-limit 은 turn "종료"가 아니라 "일시중단 → 재개 대기"다.
                    //  ① gate.release 를 **하지 않는다** — permit 을 유지해 그 슬롯이 rate-limit
                    //     동안 다른 turn 에 넘어가지 않게 한다(동시 in-flight 가 실제로 줄어 burst 완화).
                    //  ② fireTurnDone 도 **하지 않는다** — 자동화가 rate-limit 을 완료로 오인해
                    //     백오프 0 으로 다음 프롬프트를 쏘고(+재개와 이중발사) 폭주하던 문제 차단.
                    //  재개 turn 이 진짜 Done 될 때 정상 종료 경로(위 Done 분기)에서 release+통지된다.
                    //  (이전 v1.80.0 은 여기서 release+fireTurnDone 을 호출 → permit 빠른 회전 +
                    //   자동화 폭주 → "동시 한도 초과 + rate limit 악순환" 의 원인이었다.)
                    scheduleRateLimitRetry(projectId)
                } else {
                    // 진짜 에러 turn 종료(rate-limit 아님) — permit 반환 + 자동화 통지 + busy 해제.
                    // v1.100.0 — 상태칩을 "에러"(error, 빨강) 로. cancel/crash/idle 의 "중단됨"
                    // (stopped, 보라) 과 구분 — API/turn 에러임을 색으로 즉시 식별.
                    gate.release(projectId)
                    fireTurnDone(projectId, "error:${event.code}")
                    sessions[projectId]?.rateLimitRetry = 0
                    clearTurnActive(projectId)
                    setBusy(projectId, false, "error")
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
        val session = sessions[projectId] ?: run {
            // v1.99.0 — permit 반환 + 자동화 중단(다른 종료 경로와 짝 맞춤 — 좀비 방지).
            gate.release(projectId); fireInterrupt(projectId, "rate_limit_session_gone")
            setBusy(projectId, false, "stopped"); return
        }
        // v1.106.0 (P1-b) — 컨텍스트가 임계 초과면 rate-limit 자동 재개도 보류.
        // 거대한 맥락을 백오프 후 반복 자동 과금하는 것을 막는다(사용자가 직접 재개/리셋).
        if (autoResumeBlockedByContext(projectId)) {
            session.rateLimitRetry = 0
            session.retryJob?.cancel(); session.retryJob = null
            gate.release(projectId)
            clearTurnActive(projectId)
            setBusy(projectId, false, "stopped")
            fireInterrupt(projectId, "rate_limit_skipped_large_context")
            emitSystem(projectId, "rate_limit_skipped_large_context",
                "rate limit 발생 — 컨텍스트가 커서(약 ${lastContextTokens(projectId) / 1000}K) 자동 재개를 보류했습니다. " +
                "직접 이어가거나 '새 세션(컨텍스트 리셋)' 하세요.")
            return
        }
        val attempt = session.rateLimitRetry + 1
        if (attempt > MAX_RATE_LIMIT_RETRIES) {
            session.rateLimitRetry = 0
            session.retryJob?.cancel(); session.retryJob = null
            // v1.99.0 — rate-limit error 에서 유지하던 permit 을 이제 반환 + 자동화 중단(좀비 방지).
            gate.release(projectId)
            clearTurnActive(projectId)  // v1.82.0 — rate-limit 자동 재개 포기 → 미완 마크 OFF.
            setBusy(projectId, false, "stopped")
            fireInterrupt(projectId, "rate_limit_giveup")
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
                // v1.99.0 — 재개 전 세션 교체/종료 → 유지하던 permit 반환 + 자동화 중단.
                gate.release(projectId)
                fireInterrupt(projectId, "rate_limit_session_gone")
                setBusy(projectId, false, "stopped"); return@launch
            }
            runCatching { sendPrompt(projectId, RATE_LIMIT_RESUME_PROMPT, isAutoResume = true) }
                .onFailure {
                    // v1.99.0 — 재개 프롬프트 전송 실패 → permit 반환 + 자동화 중단.
                    log.warn(it) { "[$projectId] rate-limit 자동 재개 실패" }
                    gate.release(projectId)
                    fireInterrupt(projectId, "rate_limit_resume_failed")
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
        // v1.60.0 — busy 중 프로세스 종료 = 미완 turn 중단 → "stopped". 정상 완료 후 종료는 유휴.
        // v1.102.1 — 두 가지 가드로 복원:
        //  ① intentionalKill(cancel/idle/shutdown)은 terminateSession 이 busy 전이를 전담하므로
        //     여기선 skip — 둘이 race 하며 stopped↔ready 로 서로 덮어쓰던 문제 차단.
        //  ② 자발적 종료(claude clean exit / crash)는 종료 직전 busy 여부로 분기 — busy 였으면
        //     "stopped"(미완 중단), 아니면 "ready"(정상 완료 후 clean exit). 이전엔 setBusy 의
        //     boolean idempotency 에 기대 무조건 "stopped" 를 넘겨도 무시됐지만, v1.100.0
        //     state-aware 전환 후엔 ready→stopped 가 emit 돼 *정상 완료 세션에 '중단됨'* 이
        //     뜨던 회귀(특히 clean exit code 0)를 명시 가드로 해소.
        if (!session.intentionalKill) {
            val wasBusy = busy[projectId] == true
            scope.launch { setBusy(projectId, false, if (wasBusy) "stopped" else "ready") }
        }
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
        // v0.98.0 — process 종료 (cancel / startNew / idle reap / shutdown) 시 busy 항상 false.
        // v1.60.0 / v1.102.1 — busy 중 종료(사용자 cancel 등)면 "stopped"(중단됨), 정상 완료
        // 후 종료(idle reap 등)면 "ready"(유휴) 유지. 이전엔 무조건 "stopped" 를 넘기고 setBusy
        // boolean idempotency 에 기댔지만, v1.100.0 state-aware 전환 후 정상 완료(ready) 세션이
        // idle reap 될 때 ready→stopped 가 emit 되던 회귀를 명시 분기로 해소.
        val wasBusy = busy[projectId] == true
        setBusy(projectId, false, if (wasBusy) "stopped" else "ready")
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

    // ── v1.106.0 — 프로젝트별 모델 override (.vibecoder/claude-model) ──
    private fun modelFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("claude-model")

    /** 프로젝트별 override 모델(없으면 null = 전역 기본 사용). */
    fun readProjectModel(projectId: String): String? {
        val f = modelFile(projectId)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    /**
     * 프로젝트별 모델 설정. 공백/"default" = override 해제(전역 기본 사용).
     * 살아있는 프로세스는 건드리지 않으므로 **다음 세션 spawn(유휴 재개·새 세션)부터 적용**.
     */
    fun setProjectModel(projectId: String, model: String?) {
        val f = modelFile(projectId)
        val v = model?.trim().orEmpty()
        runCatching {
            if (v.isBlank() || v.equals("default", ignoreCase = true)) {
                f.deleteIfExists()
            } else {
                Files.createDirectories(f.parent)
                f.writeText(v)
            }
        }.onFailure { log.warn(it) { "[$projectId] claude-model 저장 실패" } }
    }

    /**
     * v1.106.0 — 모델 설정 후, 유휴(살아있지만 비-busy)면 프로세스를 종료(session-id 보존)해
     * 다음 prompt 가 같은 대화를 **새 모델로** resume 하게 한다. busy 면 파일만 기록(현재 turn
     * 종료 후 다음 spawn 부터 적용).
     */
    suspend fun setProjectModelAndRestart(projectId: String, model: String?) {
        setProjectModel(projectId, model)
        if (isAlive(projectId) && !isBusy(projectId)) {
            runCatching { terminateSession(projectId) }
                .onFailure { log.warn(it) { "[$projectId] 모델 변경 후 재시작 실패" } }
        }
    }

    /**
     * 실제 적용될 모델: 프로젝트 override → 전역 [ServerConfig.claude].model.
     * 공백/"default" 면 빈 문자열 반환(= --model 미전달, CLI 기본값).
     */
    fun effectiveModel(projectId: String): String {
        val raw = readProjectModel(projectId) ?: config.claude.model
        return if (raw.isBlank() || raw.equals("default", ignoreCase = true)) "" else raw.trim()
    }

    // ── v1.106.0/.1 — 컨텍스트 점유(직전 turn 토큰 분해) 영속 ──
    // 파일 포맷(CSV): input,cacheRead,cacheCreation,limit  (구버전 단일 숫자도 호환)
    private fun contextTokensFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("claude-context-tokens")

    private fun writeContext(projectId: String, input: Long, cacheRead: Long, cacheCreation: Long, limit: Long) {
        val f = contextTokensFile(projectId)
        Files.createDirectories(f.parent)
        f.writeText("$input,$cacheRead,$cacheCreation,$limit")
    }

    /** v1.106.1 — 컨텍스트 점유 스냅샷. used = input+cacheRead+cacheCreation. */
    data class ContextSnapshot(
        val input: Long, val cacheRead: Long, val cacheCreation: Long, val limit: Long,
    ) { val used: Long get() = input + cacheRead + cacheCreation }

    fun contextSnapshot(projectId: String): ContextSnapshot {
        sessions[projectId]?.let { s ->
            if (s.lastContextTokens > 0 || s.lastInputTokens > 0 || s.lastCacheCreationTokens > 0) {
                val used = s.lastInputTokens + s.lastContextTokens + s.lastCacheCreationTokens
                return sanitizeContext(ContextSnapshot(s.lastInputTokens, s.lastContextTokens, s.lastCacheCreationTokens, contextLimitFor(s.model, used)))
            }
        }
        val f = contextTokensFile(projectId)
        if (f.exists()) {
            val parts = f.readText().trim().split(",")
            when {
                parts.size >= 4 -> return sanitizeContext(ContextSnapshot(
                    parts[0].toLongOrNull() ?: 0, parts[1].toLongOrNull() ?: 0,
                    parts[2].toLongOrNull() ?: 0, parts[3].toLongOrNull() ?: 0,
                ))
                parts.size == 1 -> {  // 구버전: cacheRead 단일 숫자
                    val cr = parts[0].toLongOrNull() ?: 0
                    return sanitizeContext(ContextSnapshot(0, cr, 0, contextLimitFor(null, cr)))
                }
            }
        }
        return ContextSnapshot(0, 0, 0, 0)
    }

    /**
     * v1.107.1 — 누적치 오염 방어. cacheRead 가 윈도우 한도를 초과하면(과거 버그로 저장된
     * result 누적치) 단일 turn 값일 수 없으므로 빈 스냅샷 반환 → 미터 숨김(다음 turn 의
     * 정상 per-turn 값이 올 때까지). limit=0(미측정)이면 그대로.
     */
    private fun sanitizeContext(s: ContextSnapshot): ContextSnapshot =
        if (s.limit > 0 && s.cacheRead > s.limit) ContextSnapshot(0, 0, 0, 0) else s

    /** 메모리 세션값 → 없으면 파일(서버 재시작 후) → 둘 다 없으면 0. (P0-b/P1-b 호환용) */
    fun lastContextTokens(projectId: String): Long {
        sessions[projectId]?.lastContextTokens?.takeIf { it > 0 }?.let { return it }
        return contextSnapshot(projectId).cacheRead
    }

    /**
     * v1.106.1 — 모델별 컨텍스트 윈도우 한도(토큰) 추정. 이 계정은 opus/sonnet 1M 활성(관측치).
     * haiku 만 200K. used 가 base 를 넘으면 1M(상위 윈도우)로 보정. 미터 분모로 사용.
     */
    private fun contextLimitFor(model: String?, used: Long): Long {
        val m = model?.lowercase().orEmpty()
        val base = if (m.contains("haiku")) 200_000L else 1_000_000L
        return if (used > base) 1_000_000L else base
    }

    /** v1.106.0 (P1-b) — 컨텍스트가 경고 임계 이상이면 자동 재개 보류 대상. */
    private fun autoResumeBlockedByContext(projectId: String): Boolean {
        val warn = config.claude.contextWarnTokens
        return warn > 0 && lastContextTokens(projectId) >= warn
    }

    /** v1.106.0 — 콘솔 표시용 컨텍스트 경고 임계(토큰). 0=비활성. */
    fun contextWarnTokens(): Int = config.claude.contextWarnTokens

    // ── v1.106.0 (P1-a) — MCP 최소화(strict). 전역 ~/.claude/.mcp.json 의 5개 서버
    //    툴 스키마가 매 세션 캐시 프리픽스에 포함되어 토큰을 늘린다. strict 면 전역을
    //    무시하고 프로젝트 .mcp.json(있으면)만, 없으면 빈 설정 → MCP 0개로 프리픽스 축소.
    private fun mcpStrictFile(projectId: String): Path =
        workspace.vibecoderDir(projectId).resolve("mcp-strict")

    fun isMcpStrict(projectId: String): Boolean = mcpStrictFile(projectId).exists()

    fun setMcpStrict(projectId: String, enabled: Boolean) {
        val f = mcpStrictFile(projectId)
        runCatching {
            if (enabled) { Files.createDirectories(f.parent); f.writeText("1") }
            else f.deleteIfExists()
        }.onFailure { log.warn(it) { "[$projectId] mcp-strict 저장 실패" } }
    }

    /** 설정 후 유휴면 재시작(세션 유지) → 다음 prompt 부터 적용. */
    suspend fun setMcpStrictAndRestart(projectId: String, enabled: Boolean) {
        setMcpStrict(projectId, enabled)
        if (isAlive(projectId) && !isBusy(projectId)) {
            runCatching { terminateSession(projectId) }
                .onFailure { log.warn(it) { "[$projectId] MCP 설정 후 재시작 실패" } }
        }
    }

    private fun emptyMcpConfigFile(projectId: String): Path {
        val f = workspace.vibecoderDir(projectId).resolve("mcp-empty.json")
        runCatching {
            Files.createDirectories(f.parent)
            if (!f.exists()) f.writeText("{\"mcpServers\":{}}")
        }
        return f
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
        /**
         * v1.99.2 — 진행 중인 백그라운드 작업(Bash run_in_background / Task) 의 taskId 집합.
         * `task_started` 시 추가, 완료/실패 통지(terminal status / notification) 시 제거.
         * 비어있지 않은 동안 들어온 `result`(Done)은 "turn 종료"가 아니라 "재개 대기"로
         * 처리한다 — 백그라운드 작업 완료 시 claude 가 같은 turn 을 재개(across turns)하므로
         * permit/자동화/busy 를 보류해야 동시 한도 무력화 + 자동화 프롬프트 중첩을 막는다.
         * 단일 reader 코루틴이 갱신하지만 sendPrompt clear 와 교차하므로 thread-safe set.
         */
        val outstandingBgTasks: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
        /** v1.106.0 — 직전 turn 의 cache_read 토큰(≈현재 컨텍스트 크기). 0=미측정. */
        @Volatile var lastContextTokens: Long = 0,
        /** v1.106.0 — 컨텍스트 임계 경고를 이 세션에서 이미 1회 emit 했는지(노이즈 방지). */
        @Volatile var contextWarned: Boolean = false,
        /** v1.106.1 — 컨텍스트 미터용 토큰 분해(직전 turn). */
        @Volatile var lastInputTokens: Long = 0,
        @Volatile var lastCacheCreationTokens: Long = 0,
        /** v1.106.1 — init frame 의 실제 모델 id(윈도우 한도 추정용, 예: claude-opus-4-8[1m]). */
        @Volatile var model: String? = null,
    )

    /**
     * v1.99.2 — 백그라운드 작업 lifecycle 이벤트가 "종료(완료/실패)" 를 뜻하는지 판정.
     * - 명시적 status 가 있으면 terminal 화이트리스트로 판정(running/in_progress 등 진행형은 false).
     * - status 가 없는 `notification` (kind="notification") 은 운영 데이터상 항상 완료 통지
     *   ({"subtype":"task_notification",...,"status":"completed"} 또는 status 생략) 이므로 terminal.
     * - started / progress (진행형) 은 false → 집합에 유지.
     */
    private fun isBackgroundTaskTerminal(event: ClaudeEvent.BackgroundTask): Boolean {
        val s = event.status?.lowercase()
        if (s != null) return s in BG_TERMINAL_STATUSES
        return event.kind == "notification"
    }

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val IDLE_CHECK_INTERVAL_MS = 60_000L

        /**
         * v1.99.2 — 백그라운드 작업이 끝났음을 뜻하는 status 값(소문자). 진행형
         * (running/in_progress/started/pending/queued) 은 의도적으로 제외 — 그 동안엔
         * 집합에 유지돼 turn 이 suspended 로 묶인다.
         */
        val BG_TERMINAL_STATUSES = setOf(
            "completed", "complete", "done", "success", "succeeded",
            "failed", "fail", "error", "errored",
            "cancelled", "canceled", "killed", "timeout", "timed_out", "aborted",
        )
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
