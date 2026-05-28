package com.siamakerlab.vibecoder.server.terminal

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * v1.6.0 — Workspace bash 의 PTY 한 인스턴스.
 *
 * 컨테이너 안에서만 작동 — 호스트 shell 영향 없음. workspace 디렉토리에서 시작
 * (cwd `/workspace` 기본). pty4j 가 native PTY 생성, stdout/stderr 가 [output]
 * SharedFlow 로 흘러나오고 [write] 가 stdin 으로 들어감.
 *
 * v1.27.0 — owner 추적 + idle 추적.
 *  - [ownerUserId] : 세션 생성 사용자. WS 핸드셰이크에서 본인만 접근 가능 (ACL).
 *  - [lastActivityAt] : 마지막 input/output/connect 시각. [touch] 로 갱신.
 *    [TerminalSessionManager] 의 idle reaper 가 본 값을 기준으로 30분 무활성
 *    세션을 자동 종료.
 */
class TerminalSession(
    val id: String,
    val workdir: String,
    val ownerUserId: String?,
    private val process: PtyProcess,
    private val scope: CoroutineScope,
) {
    val createdAt: Instant = Instant.now()

    /** v1.27.0 — idle 시계. WS connect / input frame / output emit 마다 [touch]. */
    private val lastActivity = AtomicReference(Instant.now())
    val lastActivityAt: Instant get() = lastActivity.get()

    fun touch() {
        lastActivity.set(Instant.now())
    }

    /**
     * extraBufferCapacity 256 = 256 frame burst 흡수. 초과 시 [_output.emit] 이
     * suspend (back pressure) — PTY read loop 가 IO dispatcher 라 suspend 안전.
     * Hot subscriber 없는 동안 (WS 미연결) PTY 출력은 drop 되지 않고 buffer 에
     * 적체되었다 connect 후 polled.
     */
    private val _output = MutableSharedFlow<String>(
        replay = 0, extraBufferCapacity = 256,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    private val _exit = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)
    val exit: SharedFlow<Int> = _exit.asSharedFlow()

    // v1.34.0 — 출력 scrollback ring buffer. WS 재연결(다른 화면 갔다 옴) 시 화면이
    // 비어 보이던 문제(SharedFlow replay=0) 해소 — 재연결 client 에 이 버퍼를 먼저
    // replay. char(코드포인트) 기준 cap. UTF-8 멀티바이트 안전(String 단위 누적).
    private val scrollbackLock = Any()
    private val scrollback = StringBuilder()

    /** v1.34.0 — 현재 scrollback 스냅샷 (WS 연결 시 replay 용). */
    fun scrollbackSnapshot(): String = synchronized(scrollbackLock) { scrollback.toString() }

    private fun appendScrollback(s: String) {
        synchronized(scrollbackLock) {
            scrollback.append(s)
            val over = scrollback.length - MAX_SCROLLBACK_CHARS
            if (over > 0) {
                scrollback.delete(0, over)
                // v1.34.1 (19차 BUG-1) — UTF-16 char 단위 절단이 surrogate pair 중간을
                // 자르면 외톨이 low surrogate 가 맨 앞에 남아 replay 시 깨진 글자(�).
                // 외톨이 low surrogate 1개 제거(이모지/CJK 보충문자 경계 방어).
                if (scrollback.isNotEmpty() && Character.isLowSurrogate(scrollback[0])) {
                    scrollback.deleteCharAt(0)
                }
            }
        }
    }

    private var readJob: Job? = null
    private val outStream = process.outputStream
    private val inStream = process.inputStream

    fun start() {
        readJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            try {
                while (isActive) {
                    val n = runCatching { inStream.read(buf) }.getOrNull() ?: -1
                    if (n < 0) break
                    if (n > 0) {
                        val s = String(buf, 0, n, StandardCharsets.UTF_8)
                        // v1.27.0 — output 발생 = 세션 활성. idle 시계 갱신.
                        touch()
                        // v1.34.0 — scrollback 누적(재연결 replay 용) 후 emit.
                        appendScrollback(s)
                        // _output.emit 은 SharedFlow buffer 가 가득 차면 suspend.
                        // IO dispatcher 라 안전.
                        _output.emit(s)
                    }
                }
            } finally {
                val code = runCatching { process.waitFor() }.getOrDefault(-1)
                _exit.emit(code)
                log.info { "[term $id] exited code=$code" }
            }
        }
    }

    fun write(data: String) {
        // v1.27.0 — input 도 활성 신호.
        touch()
        val bytes = data.toByteArray(StandardCharsets.UTF_8)
        runCatching {
            outStream.write(bytes)
            outStream.flush()
        }.onFailure { log.debug(it) { "[term $id] write failed (process dead?)" } }
    }

    fun resize(cols: Int, rows: Int) {
        runCatching {
            process.winSize = WinSize(
                cols.coerceIn(1, MAX_TERMINAL_DIMENSION),
                rows.coerceIn(1, MAX_TERMINAL_DIMENSION),
            )
        }.onFailure { log.debug(it) { "[term $id] resize failed" } }
    }

    fun isAlive(): Boolean = process.isAlive

    /** Graceful — first SIGTERM, then destroyForcibly after 2s if still alive. */
    fun kill() {
        runCatching {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
        readJob?.cancel()
    }

    companion object {
        // v1.27.0 — 마법 상수 추출. xterm.js 가 보내는 cols/rows 가 비정상적으로
        // 큰 값일 때 native PTY 가 SEGV 날 수 있어 상한. 999 는 일반 monitor 최대치
        // (e.g. 4K + 8pt font ≈ 240 cols) 보다 훨씬 크지만 안전 마진.
        const val MAX_TERMINAL_DIMENSION = 999

        // v1.34.0 — scrollback ring buffer 최대 char. 재연결 replay 용 — 약 200KB
        // (UTF-8 기준 더 큼). 일반 터미널 수백 줄 이상 보존, 메모리 부담 적음.
        const val MAX_SCROLLBACK_CHARS = 200_000
    }
}

/**
 * v1.6.0 — 전체 활성 terminal session 의 in-memory 등록부.
 *
 *  - [create]: 신규 PTY spawn. cwd 기본 [workspaceRoot] (호스트 vibe-coder-data
 *    /workspace 마운트 위치 — 컨테이너 안에서 그대로 read/write 가능).
 *  - [get] / [list] / [close]: 호출 기본.
 *  - [shutdownAll]: graceful — JVM shutdown hook (`Runtime.addShutdownHook`,
 *    ServerMain) 에서 호출. 모든 활성 PTY 종료 + 내부 coroutine scope cancel.
 *    Ktor `ApplicationStopping` 보다 JVM shutdown hook 이 더 광범위 (`kill -TERM`
 *    docker stop 양쪽 모두 cover).
 *
 * v1.27.0 — lifecycle + 리소스 제어:
 *  - 사용자별 최대 [MAX_SESSIONS_PER_USER] 세션. 초과 시 [SessionLimitException].
 *  - [IDLE_TIMEOUT] (30분) 동안 input/output 무활성 세션은 reaper 가 자동 종료.
 *    수십개 unused PTY 가 영구히 남아 메모리/FD 누수되는 것 차단.
 */
class TerminalSessionManager(
    private val workspaceRoot: String = "/workspace",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    init {
        // v1.27.0 — idle reaper. 1분마다 lastActivityAt 검사, 30분 이상 무활성 종료.
        scope.launch {
            while (isActive) {
                delay(REAPER_INTERVAL_MS)
                runCatching { reapIdle() }
                    .onFailure { log.warn(it) { "terminal idle reaper iteration failed" } }
            }
        }
    }

    /**
     * 신규 PTY spawn.
     *
     * @param ownerUserId 세션 생성자. null 가능 (legacy / 이상 케이스) — null 인
     *   경우 WS ACL 이 owner check 를 skip 하지만 admin 가드는 그대로 유지.
     * @throws SessionLimitException 같은 [ownerUserId] 가 이미 [MAX_SESSIONS_PER_USER]
     *   개 활성 세션 보유 시.
     *
     * v1.27.1 Q-3 회수: per-user 한도 검사 + insert 가 동기화되지 않아 TOCTOU
     * 가능 (탭 두 개 동시 POST → 둘 다 count=N 통과 → 한도 +1 자리). 같은 admin 의
     * 동시성 좁아서 실 영향 작지만 SLO 명시 — `synchronized(sessions)` 블록으로
     * count + reservation 묶음. PTY spawn 자체는 lock 밖에서 진행 (외부 process
     * fork 가 lock 안에서 일어나면 다른 thread 가 한도 초과 분석 동안 무한 대기).
     */
    fun create(
        ownerUserId: String?,
        workdir: String = workspaceRoot,
        cols: Int = 80,
        rows: Int = 24,
    ): TerminalSession {
        val id = UUID.randomUUID().toString().take(12)
        // Q-3: 한도 체크 + placeholder 등록을 한 블록으로 묶어 race 차단. 같은 user
        // 의 동시 POST 가 둘 다 count 통과하는 TOCTOU 회피. sessions 는 ConcurrentHashMap
        // 이지만 "count → put" 시퀀스의 atomicity 를 위해 명시 synchronized.
        if (ownerUserId != null) {
            synchronized(sessions) {
                val active = sessions.values.count {
                    it.ownerUserId == ownerUserId && it.isAlive()
                }
                if (active >= MAX_SESSIONS_PER_USER) {
                    throw SessionLimitException(
                        "user $ownerUserId already has $active active terminal sessions (max $MAX_SESSIONS_PER_USER)",
                    )
                }
            }
        }
        // 컨테이너 내부 bash. interactive + login → ~/.bashrc 로드되어 PATH /
        // alias 정상. TERM=xterm-256color 로 vim/tmux 친화.
        val pb = PtyProcessBuilder(arrayOf("/bin/bash", "--login", "-i"))
            .setDirectory(workdir)
            .setEnvironment(
                System.getenv().toMutableMap().apply {
                    put("TERM", "xterm-256color")
                    put("LANG", "en_US.UTF-8")
                    put("LC_ALL", "en_US.UTF-8")
                    put("PS1", "vibe \\W $ ")
                },
            )
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .setConsole(false)
        val proc = pb.start()
        val sess = TerminalSession(
            id = id,
            workdir = workdir,
            ownerUserId = ownerUserId,
            process = proc,
            scope = scope,
        )
        sess.start()
        sessions[id] = sess
        // v1.27.1 Q-4 회수: `exit.collect { ... }` 가 SharedFlow 라 emit 후에도
        // suspend 유지 → 세션이 reapIdle/close 로 사라져도 collector job 이 살아남아
        // shutdownAll 까지 누적. `first()` 로 한 번만 받고 자연 종료.
        scope.launch {
            runCatching { sess.exit.first() }
            sessions.remove(id)
        }
        log.info { "[term $id] spawned bash in $workdir owner=$ownerUserId" }
        return sess
    }

    fun get(id: String): TerminalSession? = sessions[id]

    fun list(): List<TerminalSession> = sessions.values.toList()

    /**
     * v1.30.1 (CRITICAL-1) — owner 필터 목록. REST `GET /api/terminal/sessions` 가
     * caller 본인 세션만 보도록 (WS owner ACL 과 대칭). ownerUserId 가 null 인 세션
     * (legacy/이상 케이스) 은 노출하지 않는다 — 정보 누출 측면 보수적.
     */
    fun list(ownerUserId: String?): List<TerminalSession> =
        sessions.values.filter { ownerUserId != null && it.ownerUserId == ownerUserId }

    fun close(id: String) {
        sessions[id]?.let {
            it.kill()
            sessions.remove(id)
        }
    }

    /**
     * v1.27.0 — JVM shutdown hook (`Runtime.addShutdownHook`, ServerMain) 에서 호출.
     * 모든 활성 PTY 종료 (kill → destroyForcibly fallback) + 내부 scope cancel
     * (reaper 코루틴 + 세션별 exit collector 들 정리).
     */
    fun shutdownAll() {
        log.info { "terminal manager shutdown: closing ${sessions.size} sessions" }
        sessions.values.forEach { it.kill() }
        sessions.clear()
        scope.cancel()
    }

    private fun reapIdle() {
        val now = Instant.now()
        val toReap = sessions.values.filter {
            Duration.between(it.lastActivityAt, now) > IDLE_TIMEOUT
        }
        if (toReap.isEmpty()) return
        toReap.forEach { sess ->
            val idleMin = Duration.between(sess.lastActivityAt, now).toMinutes()
            log.info { "[term ${sess.id}] reaping (idle ${idleMin}m, owner=${sess.ownerUserId})" }
            sess.kill()
            sessions.remove(sess.id)
        }
    }

    class SessionLimitException(message: String) : RuntimeException(message)

    companion object {
        // v1.27.0 — 마법 상수 추출.
        const val MAX_SESSIONS_PER_USER = 4
        val IDLE_TIMEOUT: Duration = Duration.ofMinutes(30)
        const val REAPER_INTERVAL_MS = 60_000L  // 1분
    }
}
