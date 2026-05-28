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
    }
}

/**
 * v1.6.0 — 전체 활성 terminal session 의 in-memory 등록부.
 *
 *  - [create]: 신규 PTY spawn. cwd 기본 [workspaceRoot] (호스트 vibe-coder-data
 *    /workspace 마운트 위치 — 컨테이너 안에서 그대로 read/write 가능).
 *  - [get] / [list] / [close]: 호출 기본.
 *  - [shutdownAll]: graceful — `ApplicationStopping` 후크에서 호출. 모든 활성
 *    PTY 종료 + 내부 coroutine scope cancel.
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
     */
    fun create(
        ownerUserId: String?,
        workdir: String = workspaceRoot,
        cols: Int = 80,
        rows: Int = 24,
    ): TerminalSession {
        if (ownerUserId != null) {
            val active = sessions.values.count { it.ownerUserId == ownerUserId && it.isAlive() }
            if (active >= MAX_SESSIONS_PER_USER) {
                throw SessionLimitException(
                    "user $ownerUserId already has $active active terminal sessions (max $MAX_SESSIONS_PER_USER)",
                )
            }
        }
        val id = UUID.randomUUID().toString().take(12)
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
        scope.launch {
            sess.exit.collect { sessions.remove(id) }
        }
        log.info { "[term $id] spawned bash in $workdir owner=$ownerUserId" }
        return sess
    }

    fun get(id: String): TerminalSession? = sessions[id]

    fun list(): List<TerminalSession> = sessions.values.toList()

    fun close(id: String) {
        sessions[id]?.let {
            it.kill()
            sessions.remove(id)
        }
    }

    /**
     * v1.27.0 — `ApplicationStopping` 후크에서 호출. 모든 활성 PTY 종료 (kill →
     * destroyForcibly fallback) + 내부 scope cancel (reaper 코루틴 정리).
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
