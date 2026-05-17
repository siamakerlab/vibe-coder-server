package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.core.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

data class PairingCodeRecord(
    val code: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    fun isValid(now: Instant, candidate: String): Boolean =
        candidate == code && now.isBefore(expiresAt)
}

/**
 * Holds a single rotating 6-digit pairing code. The code is consumed after
 * a successful `POST /api/auth/pair` and a new one is issued.
 */
class PairingCodeStore(
    private val clock: Clock,
    private val ttl: Duration,
    private val random: () -> String = { generateNumeric(6) },
) {
    private val current = AtomicReference<PairingCodeRecord?>(null)

    fun rotate(): PairingCodeRecord {
        val now = clock.nowInstant()
        val rec = PairingCodeRecord(random(), now, now.plus(ttl))
        current.set(rec)
        return rec
    }

    fun peek(): PairingCodeRecord? = current.get()

    /** Try to consume the code. Returns true on success. */
    fun tryConsume(code: String): Boolean {
        val rec = current.get() ?: return false
        if (!rec.isValid(clock.nowInstant(), code)) return false
        return current.compareAndSet(rec, null)
    }

    companion object {
        private val digits = ('0'..'9').toList()
        fun generateNumeric(length: Int): String {
            val sr = java.security.SecureRandom()
            return (1..length).map { digits[sr.nextInt(digits.size)] }.joinToString("")
        }
    }
}
