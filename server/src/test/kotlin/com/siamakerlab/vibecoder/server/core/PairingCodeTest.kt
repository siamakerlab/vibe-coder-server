package com.siamakerlab.vibecoder.server.core

import com.siamakerlab.vibecoder.server.auth.PairingCodeStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.time.Duration
import java.time.Instant

class PairingCodeTest {

    private class TestClock(var now: Instant) : Clock {
        override fun nowInstant() = now
        override fun nowIso() = now.toString()
    }

    @Test fun `code can be consumed exactly once`() {
        val clock = TestClock(Instant.parse("2026-05-17T10:00:00Z"))
        val store = PairingCodeStore(clock, Duration.ofMinutes(10), random = { "123456" })
        store.rotate()
        store.tryConsume("123456") shouldBe true
        store.tryConsume("123456") shouldBe false
    }

    @Test fun `wrong code is rejected`() {
        val clock = TestClock(Instant.parse("2026-05-17T10:00:00Z"))
        val store = PairingCodeStore(clock, Duration.ofMinutes(10), random = { "111111" })
        store.rotate()
        store.tryConsume("999999") shouldBe false
    }

    @Test fun `expired code is rejected`() {
        val clock = TestClock(Instant.parse("2026-05-17T10:00:00Z"))
        val store = PairingCodeStore(clock, Duration.ofMinutes(10), random = { "222222" })
        store.rotate()
        clock.now = Instant.parse("2026-05-17T10:11:00Z")
        store.tryConsume("222222") shouldBe false
    }

    @Test fun `rotate replaces existing code`() {
        val clock = TestClock(Instant.parse("2026-05-17T10:00:00Z"))
        val store = PairingCodeStore(clock, Duration.ofMinutes(10),
            random = object : () -> String {
                var i = 0
                override fun invoke(): String = listOf("aaa", "bbb")[i++]
            })
        val a = store.rotate()
        val b = store.rotate()
        a shouldNotBe b
    }
}
