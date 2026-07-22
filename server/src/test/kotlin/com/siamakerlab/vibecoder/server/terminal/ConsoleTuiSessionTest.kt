package com.siamakerlab.vibecoder.server.terminal

import com.pty4j.PtyProcess
import com.pty4j.WinSize
import com.siamakerlab.vibecoder.server.agent.AgentProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class ConsoleTuiSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun `normalizeConsoleTuiPromptInput removes pasted control markers before stripping control chars`() {
        val normalized = normalizeConsoleTuiPromptInput("a\r\n\u001b[200~b\u0000c\u001b[201~\rd\t")

        normalized shouldBe "a\nbc\nd\t"
        normalized shouldNotContain "[200~"
        normalized shouldNotContain "[201~"
    }

    @Test
    fun `pastePrompt wraps sanitized prompt in bracketed paste envelope`() {
        val process = FakePtyProcess()
        val session = session(process)

        session.pastePrompt("hello\r\n\u001b[201~world") shouldBe true

        process.writtenText() shouldBe "\u001b[200~hello\nworld\u001b[201~\r"
    }

    @Test
    fun `pastePrompt reports closed input stream instead of silently succeeding`() {
        val session = session(FailingOutputPtyProcess())

        session.pastePrompt("hello") shouldBe false
    }

    @Test
    fun `command builder starts Codex and OpenCode in yolo mode`() {
        val builder = ConsoleTuiCommandBuilder { projectId ->
            if (projectId == "app1") "test-model" else null
        }

        builder.build("app1", AgentProvider.CODEX).argv shouldBe listOf(
            "codex",
            "--dangerously-bypass-approvals-and-sandbox",
            "--no-alt-screen",
            "-m",
            "test-model",
        )
        builder.build("app1", AgentProvider.OPENCODE).argv shouldBe listOf(
            "opencode",
            "--auto",
            "--mini",
            "-m",
            "test-model",
        )
    }

    @Test
    fun `interrupt writes escape and cancels prompt state only through following prompt`() {
        val process = FakePtyProcess()
        val session = session(process)

        session.interrupt() shouldBe true
        session.pastePrompt("next") shouldBe true

        process.writtenText() shouldBe "\u001b\u001b[200~next\u001b[201~\r"
    }

    @Test
    fun `hasCodexTrustPrompt detects Codex directory trust screen only for Codex sessions`(): Unit = runBlocking {
        val process = FakePtyProcess()
        val codex = session(process, provider = AgentProvider.CODEX)
        codex.start()
        process.emit("Do you trust the contents of this directory?\n1. Yes, continue\n")
        delay(50)

        codex.scrollbackLength() shouldBe "Do you trust the contents of this directory?\n1. Yes, continue\n".length
        codex.hasCodexTrustPrompt() shouldBe true
        codex.acceptCodexTrustPromptIfVisible() shouldBe true
        codex.hasCodexTrustPrompt() shouldBe false
        codex.acceptCodexTrustPromptIfVisible() shouldBe false
        process.writtenText() shouldBe "\r"

        val claude = session(FakePtyProcess(), provider = AgentProvider.CLAUDE)
        claude.hasCodexTrustPrompt() shouldBe false
    }

    @Test
    fun `assistant import schedules turn complete callback`(): Unit = runBlocking {
        val process = FakePtyProcess()
        val session = session(process)
        val events = mutableListOf<String>()

        session.markPromptSent("turn-1")
        session.markAssistantOutputDetected()
        session.scheduleTurnComplete(debounce = Duration.ZERO) { projectId, reason ->
            events += "$projectId:$reason"
        }
        delay(50)

        session.turnSnapshot().state shouldBe ConsoleTuiTurnState.TURN_COMPLETE
        events shouldBe listOf("app1:console_tui_transcript")
    }

    @Test
    fun `session exit after active prompt exposes one turn done signal`(): Unit = runBlocking {
        val session = session(EofPtyProcess())

        session.markPromptSent("turn-1")
        session.start()
        delay(50)

        session.turnSnapshot().state shouldBe ConsoleTuiTurnState.EXITED
        session.consumeTurnDoneOnExitSignal() shouldBe true
        session.consumeTurnDoneOnExitSignal() shouldBe false
    }

    @Test
    fun `session exit without active prompt does not expose turn done signal`(): Unit = runBlocking {
        val session = session(EofPtyProcess())

        session.start()
        delay(50)

        session.turnSnapshot().state shouldBe ConsoleTuiTurnState.EXITED
        session.consumeTurnDoneOnExitSignal() shouldBe false
    }

    @Test
    fun `stalled active prompt exposes one turn done signal`() {
        val session = session(FakePtyProcess())

        session.markPromptSent("turn-1")
        session.markStalledIfNeeded(
            now = Instant.now().plus(Duration.ofMinutes(11)),
            timeout = Duration.ofMinutes(10),
        )

        session.turnSnapshot().state shouldBe ConsoleTuiTurnState.STALLED
        session.consumeTurnDoneOnStallSignal() shouldBe true
        session.consumeTurnDoneOnStallSignal() shouldBe false
    }

    @Test
    fun `detach never makes active connection count negative`() {
        val session = session(FakePtyProcess())

        session.attach()
        session.hasActiveConnection() shouldBe true
        session.detach()
        session.detach()

        session.hasActiveConnection() shouldBe false
    }

    @Test
    fun `active prompt does not stall while recent terminal output exists`(): Unit = runBlocking {
        val process = FakePtyProcess()
        val session = session(process)
        session.start()

        session.markPromptSent("turn-1")
        process.emit("still working\n")
        delay(50)
        session.markStalledIfNeeded(
            now = session.lastActivityAt.plus(Duration.ofMinutes(9)),
            timeout = Duration.ofMinutes(10),
        )

        session.turnSnapshot().state shouldBe ConsoleTuiTurnState.RUNNING
        session.consumeTurnDoneOnStallSignal() shouldBe false
    }

    @Test
    fun `resource pressure reaps only disconnected inactive sessions`(): Unit = runBlocking {
        val idle = session(FakePtyProcess())
        idle.isReapableForResourcePressure() shouldBe true

        val connectedIdle = session(FakePtyProcess())
        connectedIdle.attach()
        connectedIdle.isReapableForResourcePressure() shouldBe false

        val running = session(FakePtyProcess())
        running.markPromptSent("turn-running")
        running.isReapableForResourcePressure() shouldBe false

        val stalled = session(FakePtyProcess())
        stalled.markPromptSent("turn-stalled")
        stalled.markStalledIfNeeded(
            now = Instant.now().plus(Duration.ofMinutes(11)),
            timeout = Duration.ofMinutes(10),
        )
        stalled.isReapableForResourcePressure() shouldBe false

        val complete = session(FakePtyProcess())
        complete.markPromptSent("turn-complete")
        complete.markAssistantOutputDetected()
        complete.scheduleTurnComplete(debounce = Duration.ZERO) { _, _ -> }
        delay(50)
        complete.turnSnapshot().state shouldBe ConsoleTuiTurnState.TURN_COMPLETE
        complete.isReapableForResourcePressure() shouldBe true
    }

    @Test
    fun `idle timeout never reaps disconnected active work`(): Unit = runBlocking {
        val now = Instant.now().plus(Duration.ofHours(3))

        val idle = session(FakePtyProcess())
        idle.isReapableForIdleTimeout(now, Duration.ofHours(2)) shouldBe true

        val connectedIdle = session(FakePtyProcess())
        connectedIdle.attach()
        connectedIdle.isReapableForIdleTimeout(now, Duration.ofHours(2)) shouldBe false

        val running = session(FakePtyProcess())
        running.markPromptSent("turn-running")
        running.isReapableForIdleTimeout(now, Duration.ofHours(2)) shouldBe false

        val stalled = session(FakePtyProcess())
        stalled.markPromptSent("turn-stalled")
        stalled.markStalledIfNeeded(
            now = stalled.lastActivityAt.plus(Duration.ofMinutes(11)),
            timeout = Duration.ofMinutes(10),
        )
        stalled.turnSnapshot().state shouldBe ConsoleTuiTurnState.STALLED
        stalled.isReapableForIdleTimeout(now, Duration.ofHours(2)) shouldBe false

        val complete = session(FakePtyProcess())
        complete.markPromptSent("turn-complete")
        complete.markAssistantOutputDetected()
        complete.scheduleTurnComplete(debounce = Duration.ZERO) { _, _ -> }
        delay(50)
        complete.turnSnapshot().state shouldBe ConsoleTuiTurnState.TURN_COMPLETE
        complete.isReapableForIdleTimeout(now, Duration.ofHours(2)) shouldBe true
    }

    @Test
    fun `exit classification treats quota as error user interrupt as stopped and normal exit as idle`() {
        classifyConsoleTuiExit(
            exitCode = 0,
            intentionalClose = false,
            userInterrupted = false,
            quotaLimited = false,
        ) shouldBe AgentState.IDLE
        classifyConsoleTuiExit(
            exitCode = 0,
            intentionalClose = false,
            userInterrupted = true,
            quotaLimited = false,
        ) shouldBe AgentState.INTERRUPTED
        classifyConsoleTuiExit(
            exitCode = 0,
            intentionalClose = false,
            userInterrupted = false,
            quotaLimited = true,
        ) shouldBe AgentState.ERROR
        classifyConsoleTuiExit(
            exitCode = 1,
            intentionalClose = false,
            userInterrupted = false,
            quotaLimited = false,
        ) shouldBe AgentState.ERROR
    }

    @Test
    fun `session detects quota limit output in scrollback`(): Unit = runBlocking {
        val process = FakePtyProcess()
        val session = session(process)
        session.start()

        process.emit("Weekly/Monthly Limit Exhausted. Your limit will reset at 21:49\n")
        delay(50)

        session.hasQuotaLimitOutput() shouldBe true
    }

    private fun session(
        process: PtyProcess,
        provider: AgentProvider = AgentProvider.CLAUDE,
    ): ConsoleTuiSession =
        ConsoleTuiSession(
            id = "sess1",
            projectId = "app1",
            provider = provider,
            workdir = Path.of("."),
            ownerUserId = "user1",
            commandDisplay = "fake",
            process = process,
            scope = scope,
        )

    private class FakePtyProcess : PtyProcess() {
        private val output = ByteArrayOutputStream()
        private val input = java.io.PipedInputStream()
        private val inputWriter = java.io.PipedOutputStream(input)
        private var size = WinSize(100, 30)
        private var alive = true

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException("running") else 0
        override fun destroy() {
            alive = false
        }
        override fun isAlive(): Boolean = alive
        override fun setWinSize(winSize: WinSize) {
            size = winSize
        }
        override fun getWinSize(): WinSize = size

        fun writtenText(): String = output.toString(Charsets.UTF_8)

        fun emit(text: String) {
            inputWriter.write(text.toByteArray(Charsets.UTF_8))
            inputWriter.flush()
        }
    }

    private class EofPtyProcess : PtyProcess() {
        private val output = ByteArrayOutputStream()
        private var size = WinSize(100, 30)

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
        override fun setWinSize(winSize: WinSize) {
            size = winSize
        }
        override fun getWinSize(): WinSize = size
    }

    private class FailingOutputPtyProcess : PtyProcess() {
        private var size = WinSize(100, 30)

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                throw java.io.IOException("closed")
            }
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 1
        override fun exitValue(): Int = 1
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
        override fun setWinSize(winSize: WinSize) {
            size = winSize
        }
        override fun getWinSize(): WinSize = size
    }
}
