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
 * v1.90.0 — **동적 limit**([setLimit]). `/settings` 에서 maxConcurrentTurns 를 바꾸면
 * 재시작 없이 즉시 반영된다. 내부적으로는 고정 크기 [MAX_LIMIT] Semaphore 를 두고
 * "available permit 수 = 현재 limit" 가 되도록 조정한다:
 *   - 늘리기: 부족한 만큼 `release()` (대기 중이던 turn 이 즉시 진행).
 *   - 줄이기: 즉시 회수 가능한 permit 은 `tryAcquire()` 로 회수, 진행 중이라 회수 못한
 *     만큼은 [pendingReduce] 로 적어두고 다음 [release] 때 흡수(permit 을 안 돌려줌).
 *     → 진행 중 turn 을 강제 중단하지 않고 신규 turn 부터 새 한도가 적용된다(eventual).
 *
 * @param initialLimit 동시 허용 turn 수. **0 이하면 비활성(무제한)**.
 */
class ClaudeConcurrencyGate(initialLimit: Int) {

    private companion object {
        /** Semaphore 물리 상한. 운영상 동시성은 한 자릿수라 64 면 충분. */
        const val MAX_LIMIT = 64
    }

    /** 현재 유효 동시 한도. 0 이하 = 무제한. */
    @Volatile
    var limit: Int = initialLimit.coerceAtMost(MAX_LIMIT)
        private set

    // available permit 0 으로 시작 → init 에서 limit 만큼 release 해 available=limit 로 맞춘다.
    private val semaphore = Semaphore(MAX_LIMIT, MAX_LIMIT)
    private val heldKeys = ConcurrentHashMap.newKeySet<String>()

    /** limit 조정(setLimit) 과 release 의 permit 회계를 직렬화. */
    private val adjustLock = Any()
    /** 줄이기 시 즉시 회수 못한 permit 수 — 다음 release 들이 이만큼 permit 을 안 돌려줘 흡수. */
    private var pendingReduce = 0

    init {
        if (limit > 0) repeat(limit) { semaphore.release() }
    }

    val enabled: Boolean get() = limit > 0

    /** 현재 진행 중(permit 보유) turn 수. */
    fun inFlight(): Int = heldKeys.size

    /**
     * permit 1개를 확보한다. 상한 도달 시 빌 때까지 suspend. 같은 [key] 가 이미 보유 중이면
     * 즉시 반환(추가 확보 안 함). 코루틴 취소 시 permit 을 잡지 않고 예외 전파(누수 없음).
     *
     * v1.90.0 — [onWait]: 즉시 확보 실패(상한 도달로 대기 진입)일 때만 1회 호출. 호출자가
     * 콘솔에 "rate limit 대기 중" 안내를 emit 하는 용도. 즉시 확보되는 정상 경로에선 미호출.
     */
    suspend fun acquire(key: String, onWait: suspend () -> Unit = {}) {
        if (limit <= 0) return  // 무제한
        // v1.71.0 (정밀점검) — add 를 먼저(atomic) 해서 같은 key 의 동시 acquire 가
        // 둘 다 contains=false 를 통과해 permit 2개를 소비하는 race 를 차단. 이미 보유/대기
        // 중이면 즉시 반환(중복 확보 안 함). 대기 중 취소(CancellationException)면 등록 해제.
        if (!heldKeys.add(key)) return
        try {
            // v1.90.0 — 즉시 확보 시도. 실패(상한 도달)면 대기 안내 후 suspend.
            if (!semaphore.tryAcquire()) {
                runCatching { onWait() }
                semaphore.acquire()
            }
        } catch (t: Throwable) {
            heldKeys.remove(key)
            throw t
        }
    }

    /** [key] 가 보유한 permit 을 반환한다. 보유하고 있지 않으면 no-op (idempotent). */
    fun release(key: String) {
        if (heldKeys.remove(key)) {
            synchronized(adjustLock) {
                if (pendingReduce > 0) {
                    pendingReduce--   // 줄이기 흡수 — permit 을 풀에 돌려주지 않는다.
                } else {
                    semaphore.release()
                }
            }
            log.debug { "gate release [$key] → inFlight=${heldKeys.size}/$limit (pendingReduce=$pendingReduce)" }
        }
    }

    /**
     * v1.90.0 — 동시 한도를 런타임에 변경. `/settings` 저장 직후 호출. 진행 중 turn 은
     * 강제 중단하지 않으며, 줄이는 경우 신규 turn 부터 새 한도가 적용된다(eventual).
     */
    fun setLimit(newLimit: Int) {
        val n = newLimit.coerceIn(0, MAX_LIMIT)
        synchronized(adjustLock) {
            val diff = n - limit
            limit = n
            when {
                diff > 0 -> {
                    // 늘리기: 미반영 줄이기(pendingReduce) 를 먼저 상쇄, 나머지는 permit 추가.
                    var add = diff
                    val cancel = minOf(add, pendingReduce)
                    pendingReduce -= cancel
                    add -= cancel
                    repeat(add) { semaphore.release() }
                }
                diff < 0 -> {
                    // 줄이기: 비어있는 permit 은 즉시 회수, 진행 중이라 못 회수한 만큼은 적어둔다.
                    var reduce = -diff
                    while (reduce > 0 && semaphore.tryAcquire()) reduce--
                    pendingReduce += reduce
                }
            }
            log.info { "gate limit → $n (inFlight=${heldKeys.size}, pendingReduce=$pendingReduce)" }
        }
    }
}
