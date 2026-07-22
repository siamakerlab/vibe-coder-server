package com.siamakerlab.vibecoder.server.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class ProcessResult(
    val exitCode: Int,
    val durationMs: Long,
    val cancelled: Boolean,
    val timedOut: Boolean,
)

/**
 * Executes an external OS command with:
 *   - a hard wall-clock [timeout]
 *   - on cancellation OR timeout, [Process.destroyForcibly]
 *   - line-streamed stdout/stderr via [onLine]
 *
 * Caller may signal asynchronous cancellation by emitting [Unit] on [cancellation].
 */
class ProcessRunner(
    private val workdir: Path,
    private val env: Map<String, String> = emptyMap(),
) {

    suspend fun run(
        command: List<String>,
        timeout: Duration = 30.minutes,
        cancellation: Flow<Unit> = emptyFlow(),
        onLine: suspend (level: String, line: String) -> Unit = { _, _ -> },
    ): ProcessResult = coroutineScope {
        val started = System.currentTimeMillis()
        val pb = ProcessBuilder(command)
            .directory(workdir.toFile())
            .redirectErrorStream(false)
        pb.environment().putAll(env)

        val process: Process = withContext(Dispatchers.IO) { pb.start() }

        // Stream stdout / stderr line-by-line.
        val stdoutJob = launch(Dispatchers.IO) { streamLines(process.inputStream, "STDOUT", onLine) }
        val stderrJob = launch(Dispatchers.IO) { streamLines(process.errorStream, "STDERR", onLine) }

        // External cancellation watcher.
        val cancellationSignaled = AtomicBoolean(false)
        val cancelJob = launch {
            val signaled = cancellation.firstOrNull()
            if (signaled != null && process.isAlive) {
                cancellationSignaled.set(true)
                process.destroyForcibly()
            }
        }

        val (exit, timedOut, cancelled) = try {
            val exitCode = withTimeout(timeout.toJavaDuration().toMillis()) {
                withContext(Dispatchers.IO) { process.waitFor() }
            }
            Triple(exitCode, false, false)
        } catch (e: TimeoutCancellationException) {
            if (process.isAlive) process.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Triple(-1, true, false)
        } catch (e: CancellationException) {
            if (process.isAlive) process.destroyForcibly().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            throw e
        } finally {
            // v1.31.1 (B-Q3) — reader join 에 timeout. 정상(EOF)/timeout(파이프 close)
            // 경로는 즉시 unblock 되지만, 외부 cancellation 으로 process 가 아직
            // destroyForcibly 되기 전 자식이 stdout 을 계속 흘리면 join 이 길어질 수
            // 있어 2초 상한. 초과 시 reader job 강제 cancel.
            withTimeoutOrNull(2_000) {
                stdoutJob.join()
                stderrJob.join()
            } ?: run {
                stdoutJob.cancel()
                stderrJob.cancel()
            }
            cancelJob.cancel()
        }

        val effectiveCancelled = !isActive
        ProcessResult(
            exitCode = exit,
            durationMs = System.currentTimeMillis() - started,
            cancelled = cancelled || cancellationSignaled.get() || effectiveCancelled,
            timedOut = timedOut,
        )
    }

    private suspend fun streamLines(
        stream: InputStream,
        level: String,
        onLine: suspend (level: String, line: String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: Throwable) {
                    null
                } ?: return
                onLine(level, line)
            }
        }
    }

    companion object {
        val DEFAULT_AUTH_TIMEOUT: Duration = 5.seconds
    }
}
