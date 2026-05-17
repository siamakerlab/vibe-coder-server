package com.siamakerlab.vibecoder.server.tasks

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import com.siamakerlab.vibecoder.shared.ws.WsLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Writes log lines to disk and broadcasts them on WebSocket simultaneously.
 *
 * Each Logger instance owns one open file handle and one topic key — pass it
 * to ClaudeRunner / GradleBuilder as the line sink.
 */
class TaskLogger(
    private val taskId: String,
    private val logFile: Path,
    private val hub: LogHub,
    private val clock: Clock,
) : AutoCloseable {

    private val fileLock = Mutex()
    private val writer: BufferedWriter

    init {
        Files.createDirectories(logFile.parent)
        writer = Files.newBufferedWriter(
            logFile,
            Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    suspend fun line(level: String, message: String) {
        val ts = clock.nowIso()
        fileLock.withLock {
            writer.write("[$ts] [$level] $message")
            writer.newLine()
            writer.flush()
        }
        hub.publisher(taskId).emit(WsFrame.Log(taskId, level, message, ts))
    }

    suspend fun info(message: String) = line(WsLevel.INFO, message)
    suspend fun warn(message: String) = line(WsLevel.WARN, message)
    suspend fun error(message: String) = line(WsLevel.ERROR, message)

    suspend fun done(status: String, errorMessage: String? = null) {
        hub.publisher(taskId).emit(WsFrame.Done(taskId, status, errorMessage))
    }

    override fun close() {
        try { writer.close() } catch (_: Throwable) {}
    }
}
