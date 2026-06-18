package com.siamakerlab.vibecoder.server.claude

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ClaudeConcurrencyGateTest {

    @Test fun `limit 0 disables gating`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(0)
        gate.enabled shouldBe false
        // 어떤 acquire 도 inFlight 를 늘리지 않고 즉시 통과.
        gate.acquire("a"); gate.acquire("b"); gate.acquire("c")
        gate.inFlight() shouldBe 0
        gate.release("a") // no-op, 예외 없음
    }

    @Test fun `acquire up to limit then queue until release`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(2)
        gate.acquire("p1")
        gate.acquire("p2")
        gate.inFlight() shouldBe 2

        // 3번째는 permit 이 없어 대기해야 한다.
        var thirdAcquired = false
        val job = launch {
            gate.acquire("p3")
            thirdAcquired = true
        }
        delay(100)
        thirdAcquired shouldBe false // 아직 대기 중

        gate.release("p1") // permit 1개 반환 → p3 진행
        job.join()
        thirdAcquired shouldBe true
        gate.inFlight() shouldBe 2 // p2, p3
    }

    @Test fun `release is idempotent`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(1)
        gate.acquire("x")
        gate.inFlight() shouldBe 1
        gate.release("x")
        gate.release("x") // 중복 release 는 permit 을 과다 반환하지 않음
        gate.inFlight() shouldBe 0

        // permit 이 정확히 1개만 살아있어야 한다 — 두 acquire 가 동시에 통과하면 안 됨.
        gate.acquire("y")
        var zAcquired = false
        val job = async {
            gate.acquire("z")
            zAcquired = true
        }
        delay(100)
        zAcquired shouldBe false
        gate.release("y")
        job.await()
        zAcquired shouldBe true
    }

    @Test fun `holds reflects permit ownership and waiting registration`(): Unit = runBlocking {
        // v1.135.0 — 상주 세션 LRU 회수가 "turn 진행 중" 세션을 제외하는 데 쓰는 helper.
        val gate = ClaudeConcurrencyGate(1)
        gate.holds("a") shouldBe false
        gate.acquire("a")
        gate.holds("a") shouldBe true
        // 대기 등록 중인 key 도 holds=true (회수 금지 — permit 확보 직후 spawn 할 세션).
        val job = launch { gate.acquire("b") }
        delay(100)
        gate.holds("b") shouldBe true
        gate.release("a")
        job.join()
        gate.holds("a") shouldBe false
        gate.holds("b") shouldBe true
        gate.release("b")
        gate.holds("b") shouldBe false
    }

    @Test fun `same key does not consume two permits`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(1)
        gate.acquire("dup")
        gate.acquire("dup") // 같은 key 중복 → 추가 확보 안 함
        gate.inFlight() shouldBe 1
        // 다른 key 는 여전히 대기해야 한다 (permit 1개를 dup 이 보유).
        var otherAcquired = false
        val job = launch {
            gate.acquire("other")
            otherAcquired = true
        }
        delay(100)
        otherAcquired shouldBe false
        gate.release("dup")
        job.join()
        otherAcquired shouldBe true
    }

    // v1.144.4 — 자발적 재개 turn 흡수(adopt) 검증.

    @Test fun `adopt registers in-flight and takes a permit when available`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(2)
        gate.acquire("p1")
        gate.adopt("p2") // host stdin 없이 재개된 turn 흡수 — 남은 permit 확보
        gate.inFlight() shouldBe 2
        gate.holds("p2") shouldBe true
        // 이제 만석 → 새 key 는 대기.
        var thirdAcquired = false
        val job = launch { gate.acquire("p3"); thirdAcquired = true }
        delay(100)
        thirdAcquired shouldBe false
        gate.release("p2")
        job.join()
        thirdAcquired shouldBe true
    }

    @Test fun `adopt over-subscribes when full and release does not leak a permit`(): Unit = runBlocking {
        // 핵심 정합성: 만석에서 자발적 재개(유령 turn)를 흡수하면 한도를 일시 초과(over-subscribe)
        // 하지만, 그 turn 의 release 는 permit 을 풀에 되돌려주면 안 된다(잡지 않았으므로).
        val gate = ClaudeConcurrencyGate(1)
        gate.acquire("p1") // 만석 (permit 1/1)
        gate.adopt("ghost") // permit 없지만 heldKeys 등록 → inFlight 카운트 포함
        gate.inFlight() shouldBe 2
        gate.holds("ghost") shouldBe true

        gate.release("ghost") // pendingReduce 로 흡수 — semaphore.release() 안 함
        gate.inFlight() shouldBe 1

        // permit 이 잘못 반환됐다면 available 이 1로 늘어 새 key 가 즉시 통과해버린다.
        // 정합성이 맞으면 p1 이 유일 permit 보유자라 새 key 는 대기해야 한다.
        var otherAcquired = false
        val job = launch { gate.acquire("other"); otherAcquired = true }
        delay(100)
        otherAcquired shouldBe false
        gate.release("p1")
        job.join()
        otherAcquired shouldBe true
        gate.inFlight() shouldBe 1 // other 만
    }

    @Test fun `adopt is a no-op for already-held key`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(2)
        gate.acquire("a")
        gate.adopt("a") // 이미 보유 → 중복 등록·확보 안 함 (acquire 와 race 해도 안전)
        gate.inFlight() shouldBe 1
    }

    @Test fun `adopt is a no-op when gating disabled`(): Unit = runBlocking {
        val gate = ClaudeConcurrencyGate(0)
        gate.adopt("a")
        gate.inFlight() shouldBe 0
        gate.release("a") // 예외 없음
    }
}
