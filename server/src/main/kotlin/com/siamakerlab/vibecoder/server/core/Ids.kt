package com.siamakerlab.vibecoder.server.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

object Ids {

    private val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val counter = AtomicInteger(0)

    fun taskId(): String = "task-${stamp()}-${tail()}"
    fun buildId(): String = "build-${stamp()}-${tail()}"
    fun artifactId(): String = "art-${stamp()}-${tail()}"
    fun deviceId(): String = "dev-${stamp()}-${tail()}"
    fun fileId(): String = "file-${stamp()}-${tail()}"

    private fun stamp(): String = ZonedDateTime.now(ZoneId.systemDefault()).format(ts)
    private fun tail(): String = counter.incrementAndGet().toString().padStart(3, '0')
}
