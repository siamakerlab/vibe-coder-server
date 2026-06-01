package com.siamakerlab.vibecoder.server.claude

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * v1.69.0 — Claude turn 동시 in-flight 제한 게이트.
 *
 * 여러 프로젝트 / sub-agent 콘솔에서 동시에 prompt 를 던지면 같은 Anthropic 계정 + IP 로
 * 동시 요청이 burst 로 몰려 서버측 throttle(HTTP 429 "Server is temporarily limiting
 * requests") 을 유발한다. 이 게이트는 "동시에 진행 중인 turn 수" 를 [limit] 으로 제한해
 * burst 를 흡수한다. 상한 도달 시 새 turn 은 거부(reject) 가 아니라 permit 이 빌 때까지
 * **대기(queue)** 하므로 사용자의 prompt 는 유실되지 않고 순서대로 처리된다.
 *
 * - [ClaudeSessionManager] (프로젝트 메인 콘솔) 와
 *   [SubAgentSessionManager] (멀티 에이전트) 가 **같은 인스턴스를 공유**한다 —
 *   둘 다 동일 계정으로 나가므로 하나의 풀에서 함께 세야 의미가 있다.
 *
 * 정확성:
 * - key 별로 permit 을 최대 1개만 보유한다 (한 프로젝트/에이전트는 한 번에 turn 1개).
 *   같은 key 의 중복 acquire 는 무시 → permit 누수 방지.
 * - [release] 는 idempotent. 호출 지점이 여러 곳(Done / cancel / crash / idle / write 실패)
 *   이라도 실제 보유 중일 때만 1회 반환한다.
 *
 * @param limit 동시 허용 turn 수. **0 이하면 비활성(무제한)** — 기존 동작 그대로.
 */
class ClaudeConcurrencyGate(val limit: Int) {

    private val semaphore: Semaphore? = if (limit > 0) Semaphore(limit) else null
    private val heldKeys = ConcurrentHashMap.newKeySet<String>()

    val enabled: Boolean get() = semaphore != null

    /** 현재 진행 중(permit 보유) turn 수. */
    fun inFlight(): Int = heldKeys.size

    /**
     * permit 1개를 확보한다. 상한 도달 시 빌 때까지 suspend. 같은 [key] 가 이미 보유 중이면
     * 즉시 반환(추가 확보 안 함). 코루틴 취소 시 permit 을 잡지 않고 예외 전파(누수 없음).
     */
    suspend fun acquire(key: String) {
        val s = semaphore ?: return
        // v1.71.0 (정밀점검) — add 를 먼저(atomic) 해서 같은 key 의 동시 acquire 가
        // 둘 다 contains=false 를 통과해 permit 2개를 소비하는 race 를 차단. 이미 보유/대기
        // 중이면 즉시 반환(중복 확보 안 함). 대기 중 취소(CancellationException)면 등록 해제.
        if (!heldKeys.add(key)) return
        try {
            s.acquire()
        } catch (t: Throwable) {
            heldKeys.remove(key)
            throw t
        }
    }

    /** [key] 가 보유한 permit 을 반환한다. 보유하고 있지 않으면 no-op (idempotent). */
    fun release(key: String) {
        val s = semaphore ?: return
        if (heldKeys.remove(key)) {
            s.release()
            log.debug { "gate release [$key] → inFlight=${heldKeys.size}/$limit" }
        }
    }
}
