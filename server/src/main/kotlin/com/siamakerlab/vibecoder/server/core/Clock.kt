package com.siamakerlab.vibecoder.server.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Wraps wall clock so tests can substitute. Production = system default zone ISO_OFFSET_DATE_TIME.
 */
interface Clock {
    fun nowInstant(): Instant
    fun nowIso(): String
}

class SystemClock : Clock {
    override fun nowInstant(): Instant = Instant.now()
    override fun nowIso(): String =
        OffsetDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).toString()
}
