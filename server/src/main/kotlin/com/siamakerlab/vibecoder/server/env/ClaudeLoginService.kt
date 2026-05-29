package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Collections

private val log = KotlinLogging.logger {}

/**
 * 반자동 웹 OAuth 로그인 — v0.7.0 옵션 A.
 *
 * `claude auth login` 은 TUI(curses) 라 일반 ProcessBuilder spawn 으로는
 * stdout 이 비어 있고 stdin write 도 무시된다. Linux 표준 도구 `script -q`
 * 로 pty 를 wrap 하면 정상 동작한다는 사실을 확인했다 (claude CLI 2.1.150 기준).
 *
 * 흐름:
 *  1. start() — `script -q -c "claude auth login" /dev/null` 자식 프로세스 spawn.
 *  2. stdout 에서 OAuth URL 캡처
 *     (`https://claude.com/cai/oauth/authorize?...&code=true...`).
 *  3. 사용자가 그 URL 을 브라우저에서 열어 인증 → Anthropic 페이지가 발급한
 *     authorization code (PKCE) 를 받는다.
 *  4. submitCode(code) — 코드를 자식 stdin 에 write → CLI 가 토큰 교환 →
 *     `~/.claude/.credentials.json` 생성 → 프로세스 종료.
 *  5. watcher 가 exit code + 자격증명 파일 존재로 성공/실패 판정.
 *
 * 한 번에 하나의 세션만 (mutex). 동시 진행 시 409 응답.
 *
 * **CLAUDE.md §3 정책**: raw shell UI 가 아님. 단일 OAuth 코드 한 줄 입력만
 * 받는 정해진 폼이므로 정책 위반 아님 (사용자가 임의 shell 명령을 칠 수 없음).
 */
class ClaudeLoginService(
    private val clock: Clock,
    private val claudeAuth: ClaudeAuthService,
) {
    private val mutex = Mutex()
    @Volatile private var session: Session? = null

    /** 세션이 ClaudeLoginService 의 lifecycle 따라가도록 자체 scope 보유. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class State { IDLE, STARTING, AWAITING_CODE, VERIFYING, DONE, FAILED, CANCELED }

    data class SessionDto(
        val id: String,
        val state: String,
        val url: String?,
        val startedAt: String,
        val updatedAt: String,
        val errorMessage: String?,
        val lastLines: List<String>,
    )

    /** 진행 중 세션의 현재 상태. 없으면 null. */
    fun status(): SessionDto? = session?.toDto()

    suspend fun start(): SessionDto = mutex.withLock {
        session?.let { existing ->
            if (existing.state in ACTIVE_STATES) {
                throw ApiException.localized(409, "in_progress",
                    messageKey = "api.claudeLogin.inProgress", args = listOf(existing.id))
            }
        }
        val cmd = listOf("script", "-q", "-c", "claude auth login", "/dev/null")
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["BROWSER"] = ""
        pb.environment()["TERM"] = "dumb"
        pb.environment()["NO_COLOR"] = "1"

        val proc = try {
            pb.start()
        } catch (e: IOException) {
            throw ApiException.localized(500, "spawn_failed",
                messageKey = "api.claudeLogin.spawnFailed", args = listOf(e.message ?: ""))
        }
        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
        val s = Session(
            id = Ids.taskId(),
            startedAt = clock.nowIso(),
            updatedAt = clock.nowIso(),
            process = proc,
            stdin = stdin,
        )
        session = s
        log.info { "claude login session started: ${s.id}" }

        scope.launch { drainOutput(s) }
        scope.launch { watchProcess(s) }
        s.toDto()
    }

    suspend fun submitCode(code: String): SessionDto = mutex.withLock {
        val s = session
            ?: throw ApiException.localized(404, "no_session", messageKey = "api.claudeLogin.noSession")
        if (s.state != State.AWAITING_CODE) {
            throw ApiException.localized(409, "wrong_state",
                messageKey = "api.claudeLogin.wrongState", args = listOf(s.state.name))
        }
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            throw ApiException.localized(400, "empty", messageKey = "api.claudeLogin.codeEmpty")
        }
        try {
            s.stdin.write(trimmed)
            s.stdin.newLine()
            s.stdin.flush()
        } catch (e: IOException) {
            s.state = State.FAILED
            s.errorMessage = "코드 전송 실패: ${e.message}"
            s.updatedAt = clock.nowIso()
            throw ApiException.localized(500, "io",
                messageKey = "api.claudeLogin.codeIo", args = listOf(e.message ?: ""))
        }
        s.state = State.VERIFYING
        s.updatedAt = clock.nowIso()
        log.info { "claude login code submitted: ${s.id}" }
        s.toDto()
    }

    /**
     * v0.12.4 — start / submitCode 와 동일하게 mutex 로 직렬화 (이전엔 lock 밖에서
     * session 상태 변경 → start 와 race 가능). suspend 로 시그니처 변경.
     */
    suspend fun cancel(): SessionDto? = mutex.withLock {
        val s = session ?: return null
        runCatching { s.process.destroy() }
        runCatching { s.stdin.close() }
        if (s.state in ACTIVE_STATES) {
            s.state = State.CANCELED
            s.updatedAt = clock.nowIso()
        }
        log.info { "claude login session canceled: ${s.id}" }
        s.toDto()
    }

    /**
     * v1.31.0 (B-BUG1 회수) — JVM shutdown hook 용. 진행 중인 `script claude auth login`
     * 자식 프로세스를 강제 종료하고 내부 scope 를 cancel (drainOutput/watchProcess 정리).
     * 이전엔 shutdown 경로가 없어 컨테이너 재시작 시 orphan script/claude 프로세스 +
     * 두 블로킹 job 이 JVM 강제 종료에만 의존했음.
     */
    fun shutdown() {
        runCatching { session?.process?.destroyForcibly() }
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────
    // 내부
    // ─────────────────────────────────────────────────────────────────

    private fun drainOutput(s: Session) {
        try {
            // pty 출력은 라인 경계가 흐트러질 수 있어 char 단위로 읽고 ANSI 제거 후 누적.
            // 21차 점검(minor) — 이전엔 4096-byte 청크를 독립 UTF-8 디코딩해 청크 경계의
            // 멀티바이트 문자가 깨질 수 있었다(비-ASCII 진단/오류 메시지 한정). InputStreamReader
            // 가 미완성 멀티바이트 tail 을 다음 read 로 carry-over.
            val reader = java.io.InputStreamReader(s.process.inputStream, StandardCharsets.UTF_8)
            val buf = CharArray(4096)
            val sb = StringBuilder()
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = String(buf, 0, n)
                val clean = stripAnsi(chunk)
                if (clean.isEmpty()) continue
                sb.append(clean)
                // 짧은 줄들로 분할해 last-N 보관
                clean.split('\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        s.lastLines.add(line)
                        while (s.lastLines.size > MAX_KEEP_LINES) s.lastLines.removeAt(0)
                    }
                // URL 캡처 (한 번만)
                if (s.url == null) {
                    URL_PATTERN.find(sb)?.let { match ->
                        s.url = match.value
                        s.state = State.AWAITING_CODE
                        s.updatedAt = clock.nowIso()
                        log.info { "claude login captured URL for ${s.id}" }
                    }
                }
                // 'Paste code' 프롬프트 감지 — URL 못 잡았어도 보조 신호
                if (s.state == State.STARTING && CODE_PROMPT_PATTERN.containsMatchIn(sb)) {
                    s.state = State.AWAITING_CODE
                    s.updatedAt = clock.nowIso()
                }
                // 버퍼가 너무 커지면 trim (URL/prompt 캡처 이후엔 줄여도 됨)
                if (sb.length > 16_384) sb.delete(0, sb.length - 4_096)
            }
        } catch (e: Throwable) {
            log.debug(e) { "drain output ended for ${s.id}" }
        }
    }

    private fun watchProcess(s: Session) {
        try {
            val exit = s.process.waitFor()
            // exit 시점에 자격증명 파일 존재 여부로 성공/실패 판정 — 가장 신뢰 가능한 신호.
            val credsExists = claudeAuth.credentialsPath().toFile().exists()
            if (s.state == State.CANCELED) {
                // 이미 사용자가 cancel — 상태 유지.
            } else if (exit == 0 && credsExists) {
                s.state = State.DONE
                s.updatedAt = clock.nowIso()
                log.info { "claude login DONE: ${s.id} (exit=$exit, creds=$credsExists)" }
            } else {
                s.state = State.FAILED
                s.errorMessage = "exit=$exit, credentials=${if (credsExists) "exists" else "missing"} · " +
                    s.lastLines.takeLast(3).joinToString(" | ")
                s.updatedAt = clock.nowIso()
                log.warn { "claude login FAILED: ${s.id} → ${s.errorMessage}" }
            }
        } catch (_: InterruptedException) {
            // shutdown 중
        }
    }

    private data class Session(
        val id: String,
        val startedAt: String,
        @Volatile var updatedAt: String,
        @Volatile var state: State = State.STARTING,
        @Volatile var url: String? = null,
        @Volatile var errorMessage: String? = null,
        val lastLines: MutableList<String> = Collections.synchronizedList(mutableListOf()),
        val process: Process,
        val stdin: BufferedWriter,
    )

    private fun Session.toDto(): SessionDto = SessionDto(
        id = id,
        state = state.name,
        url = url,
        startedAt = startedAt,
        updatedAt = updatedAt,
        errorMessage = errorMessage,
        lastLines = ArrayList(lastLines),
    )

    companion object {
        private val ACTIVE_STATES = setOf(State.STARTING, State.AWAITING_CODE, State.VERIFYING)
        private const val MAX_KEEP_LINES = 30

        // Anthropic 의 OAuth authorize URL 패턴 — claude.com/cai/oauth/authorize?...
        // CLI 2.1.150 기준 캡처본 매칭. host 변경 가능성 대비해 약간 헐겁게.
        private val URL_PATTERN = Regex(
            """https://(?:claude\.com|console\.anthropic\.com)/[^\s'"]+oauth/authorize[^\s'"]+"""
        )

        // 'Paste code here if prompted >' 같은 프롬프트 감지
        private val CODE_PROMPT_PATTERN = Regex(
            """(?i)paste\s+code|enter\s+code|authorization\s+code"""
        )

        /**
         * ANSI escape sequence + 잔여 제어문자 제거.
         *
         * pty(`script -q`) 로 wrap 한 TUI 출력은 ANSI 가 잔뜩 포함되어 있어,
         * URL 캡처 / 코드 프롬프트 감지 / lastLines 표시 전에 정리해야 한다.
         * 매칭 범위를 ESC(\u001B) 로 시작하는 시퀀스로 한정해 URL 안의
         * 일반 문자(`?`, `&`, `=`, `[`)가 손상되지 않게 보호한다.
         */
        fun stripAnsi(text: String): String =
            text.replace(ANSI_CSI, "")
                .replace(ANSI_OSC, "")
                .replace(ANSI_SINGLE_BYTE, "")
                .replace(OTHER_CONTROL_NO_TAB_NL_CR, "")

        // CSI: ESC [ <params> <intermediate> <final>
        private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        // OSC: ESC ] ... (BEL | ESC \)
        private val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
        // ESC + single byte (7, 8, =, >, N, O, M)
        private val ANSI_SINGLE_BYTE = Regex("\u001B[78=>NOM]")
        // 기타 비프린트 제어문자 — Tab(0x09), LF(0x0A), CR(0x0D) 만 보존하고 제거.
        private val OTHER_CONTROL_NO_TAB_NL_CR =
            Regex("[\u0000-\u0008\u000B-\u000C\u000E-\u001F\u007F]")
    }
}
