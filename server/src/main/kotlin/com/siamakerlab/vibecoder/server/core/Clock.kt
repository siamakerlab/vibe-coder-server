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
    /**
     * v0.76.0 — N 일 이전 시점의 ISO 문자열. NotificationRetentionScheduler 등 일/주
     * 단위 retention 작업에서 cutoff 비교용. `<` 비교는 ISO 8601 lexicographic 으로
     * 안전.
     */
    fun cutoffIso(daysAgo: Int): String = OffsetDateTime
        .ofInstant(nowInstant().minusSeconds(daysAgo * 86_400L), ZoneId.systemDefault())
        .toString()
}

class SystemClock : Clock {
    override fun nowInstant(): Instant = Instant.now()
    override fun nowIso(): String =
        OffsetDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).toString()
}
