package com.siamakerlab.vibecoder.server.core

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds

class ProcessRunnerTimeoutTest {

    @Test fun `command exceeding timeout is killed`(): Unit = runBlocking {
        // sleep 5 seconds, but cap at 300ms — must be killed.
        val cmd = if (OsType.detect() == OsType.WINDOWS)
            listOf("cmd.exe", "/c", "ping", "127.0.0.1", "-n", "10")
        else
            listOf("/bin/sh", "-c", "sleep 5")
        val runner = ProcessRunner(Paths.get("."))
        val result = runner.run(cmd, timeout = 300.milliseconds)
        result.timedOut shouldBe true
    }

    @Test fun `successful command returns exit 0`(): Unit = runBlocking {
        val cmd = if (OsType.detect() == OsType.WINDOWS)
            listOf("cmd.exe", "/c", "echo hi")
        else
            listOf("/bin/sh", "-c", "echo hi")
        val runner = ProcessRunner(Paths.get("."))
        val result = runner.run(cmd, timeout = kotlin.time.Duration.parse("PT10S"))
        result.exitCode shouldBe 0
    }

    @Test fun `cancellation flow kills command and marks result cancelled`(): Unit = runBlocking {
        val cmd = if (OsType.detect() == OsType.WINDOWS)
            listOf("cmd.exe", "/c", "ping", "127.0.0.1", "-n", "10")
        else
            listOf("/bin/sh", "-c", "sleep 5")
        val cancellation = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val runner = ProcessRunner(Paths.get("."))

        val result = async {
            runner.run(cmd, timeout = kotlin.time.Duration.parse("PT10S"), cancellation = cancellation)
        }
        launch {
            delay(150)
            cancellation.emit(Unit)
        }

        result.await().cancelled shouldBe true
    }
}
