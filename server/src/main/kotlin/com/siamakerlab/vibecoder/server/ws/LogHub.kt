package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-topic broadcast bus for WebSocket subscribers.
 *
 * Topics are stable keys:
 *  - `task-<id>` / `build-<id>` — legacy log streams, no seq, no replay.
 *  - `console-<projectId>`     — console stream with monotonic seq + 200-frame ring buffer.
 *
 * For task/build streams, use [publisher] and [subscribe] (replay 64 in the underlying
 * SharedFlow handles "client connects right after producer started" cases).
 *
 * For console streams, use [emitConsole] and [subscribeConsole]. Each emitted frame is
 * tagged with a per-topic monotonically increasing `seq`. Reconnecting clients pass
 * the last `seq` they saw; [subscribeConsole] returns the missing slice + the live flow.
 */
class LogHub(
    /** Console ring buffer depth. Plan §3 mandates 200. */
    private val consoleRingCapacity: Int = DEFAULT_RING_CAPACITY,
) {

    // region Legacy task/build streams

    private val legacyTopics = ConcurrentHashMap<String, MutableSharedFlow<WsFrame>>()
    // v1.34.2 (20차 BUG-2) — 레거시 토픽(build/task/mcp/env-setup id 별)은 한 번 만들면
    // computeIfAbsent 로 영구 잔존했고 close() 호출자가 0건이라 서버 수명 동안 단조 증가
    // (토픽당 replay 64 frame retain). publisher 호출마다 활동 시각을 기록하고, idle
    // reaper 가 구독자 0 + IDLE 초과 토픽만 정리 — 진행 중 빌드(구독자>0)는 안전 보존.
    private val legacyLastActivity = ConcurrentHashMap<String, Long>()
    private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        reaperScope.launch {
            while (isActive) {
                delay(LEGACY_REAP_INTERVAL_MS)
                runCatching { reapIdleLegacyTopics() }
            }
        }
    }

    fun publisher(topic: String): MutableSharedFlow<WsFrame> {
        legacyLastActivity[topic] = System.currentTimeMillis()
        return legacyTopics.computeIfAbsent(topic) {
            MutableSharedFlow(
                replay = 64,
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
    }

    fun subscribe(topic: String): SharedFlow<WsFrame> = publisher(topic).asSharedFlow()

    fun close(topic: String) {
        legacyTopics.remove(topic)
        legacyLastActivity.remove(topic)
        consoleTopics.remove(topic)
    }

    private fun reapIdleLegacyTopics() {
        val now = System.currentTimeMillis()
        legacyTopics.forEach { (topic, flow) ->
            val idleMs = now - (legacyLastActivity[topic] ?: now)
            if (idleMs > LEGACY_IDLE_TIMEOUT_MS && flow.subscriptionCount.value == 0) {
                legacyTopics.remove(topic)
                legacyLastActivity.remove(topic)
            }
        }
    }

    /** v1.34.2 — JVM shutdown hook 에서 reaper coroutine 정리. */
    fun shutdown() {
        reaperScope.cancel()
    }

    // endregion

    // region Console (seq + ring buffer)

    data class SeqFrame(val seq: Long, val frame: WsFrame)

    /**
     * Snapshot returned by [subscribeConsole].
     *
     * - [replay] : frames whose seq > since AND still resident in the ring buffer.
     * - [ringFloor] : lowest seq still in the ring; if `since` < [ringFloor], the
     *   gap between `since+1` and [ringFloor]-1 is permanently lost — the client
     *   should surface a "history may be partial" notice.
     * - [highWaterMark] : highest seq emitted at snapshot time.
     * - [live] : SharedFlow of every subsequent emit (already-seen seq are NOT replayed).
     */
    data class ConsoleView(
        val replay: List<SeqFrame>,
        val ringFloor: Long,
        val highWaterMark: Long,
        val live: SharedFlow<SeqFrame>,
    )

    private data class ConsoleTopic(
        // v1.34.2 (20차 BUG-1) — replay 0 → CONSOLE_PUBLISHER_REPLAY. subscribeConsole 의
        // ring snapshot 과 view.live.collect 구독 등록 사이 microsecond gap 에 emit 된
        // frame 이 replay=0 이라 영구 유실되던 race 완화. publisher replay 버퍼가 그
        // 직전 frame 들을 보유해 collect 시작 시 전달 → gap 복구. 핸들러의 기존 dedup
        // (`sf.seq <= since` + `view.replay.any{ it.seq==sf.seq }`)이 ring-replay 와의
        // 중복을 완전 제거하므로 중복 전송 없음.
        val publisher: MutableSharedFlow<SeqFrame> = MutableSharedFlow(
            replay = CONSOLE_PUBLISHER_REPLAY,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        ),
        val ring: ArrayDeque<SeqFrame> = ArrayDeque(),
        val seq: AtomicLong = AtomicLong(0L),
        val ringLock: Any = Any(),
    )

    private val consoleTopics = ConcurrentHashMap<String, ConsoleTopic>()

    private fun stateOf(topic: String): ConsoleTopic =
        consoleTopics.computeIfAbsent(topic) { ConsoleTopic() }

    /**
     * Emit a frame on a console topic. The supplied [factory] is called with the freshly
     * assigned seq so the frame itself can carry the same value. Returns the assigned seq.
     */
    suspend fun emitConsole(topic: String, factory: (seq: Long) -> WsFrame): Long {
        val state = stateOf(topic)
        // B2 (21차 점검) — seq 할당과 ring 삽입을 단일 임계영역으로 원자화.
        // 이전엔 incrementAndGet() 이 락 밖에 있어, 같은 topic(특히 multi-producer
        // 인 PROJECTS_TOPIC)에 두 코루틴이 동시 emit 하면 (A:seq=1 → B:seq=2 →
        // B 가 먼저 락 잡아 addLast → A addLast) ring 이 seq 내림차순으로 들어가
        // subscribeConsole 의 ringFloor(firstOrNull)·eviction(pollFirst)·replay 순서가
        // 깨질 수 있었다. publisher.emit 만 락 밖에서 수행(suspend·구독자 호출 격리).
        val next: Long
        val sf: SeqFrame
        synchronized(state.ringLock) {
            next = state.seq.incrementAndGet()
            sf = SeqFrame(next, factory(next))
            state.ring.addLast(sf)
            while (state.ring.size > consoleRingCapacity) state.ring.pollFirst()
        }
        state.publisher.emit(sf)
        return next
    }

    fun subscribeConsole(topic: String, since: Long): ConsoleView {
        val state = stateOf(topic)
        val replay: List<SeqFrame>
        val ringFloor: Long
        val highWater: Long
        synchronized(state.ringLock) {
            replay = state.ring.filter { it.seq > since }.toList()
            ringFloor = state.ring.firstOrNull()?.seq ?: (state.seq.get() + 1L)
            highWater = state.seq.get()
        }
        return ConsoleView(replay, ringFloor, highWater, state.publisher.asSharedFlow())
    }

    fun consoleCurrentSeq(topic: String): Long = consoleTopics[topic]?.seq?.get() ?: 0L

    /** Drop all replay history for a topic (used by "Start new session"). */
    fun resetConsole(topic: String) {
        consoleTopics.remove(topic)
    }

    // endregion

    companion object {
        const val DEFAULT_RING_CAPACITY = 200

        // v1.34.2 (20차 BUG-1) — console publisher replay 버퍼 깊이. snapshot~collect
        // gap 복구용. 32 면 일반 재연결 race window 를 충분히 덮음(중복은 핸들러 dedup).
        const val CONSOLE_PUBLISHER_REPLAY = 32

        // v1.34.2 (20차 BUG-2) — 레거시 토픽 idle 정리 주기/임계. 구독자 0 + 10분 무활동
        // 토픽만 제거(끝난 빌드/설치). 진행 중(구독자>0)은 보존.
        const val LEGACY_REAP_INTERVAL_MS = 5 * 60 * 1000L
        const val LEGACY_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** Build a provider-scoped console topic key for a given project id. */
        fun consoleTopic(projectId: String, provider: String = "claude"): String =
            "console-$projectId-${provider.trim().lowercase().ifBlank { "claude" }}"

        /**
         * v0.44.0 — sub-agent console topic. Lives parallel to the main project console so
         * Phase 23's per-agent child processes broadcast on their own ring buffer.
         */
        fun subAgentConsoleTopic(projectId: String, agentName: String): String =
            "console-agent-$projectId-$agentName"
    }
}
