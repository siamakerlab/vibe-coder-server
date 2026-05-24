package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun publisher(topic: String): MutableSharedFlow<WsFrame> =
        legacyTopics.computeIfAbsent(topic) {
            MutableSharedFlow(
                replay = 64,
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    fun subscribe(topic: String): SharedFlow<WsFrame> = publisher(topic).asSharedFlow()

    fun close(topic: String) {
        legacyTopics.remove(topic)
        consoleTopics.remove(topic)
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
        val publisher: MutableSharedFlow<SeqFrame> = MutableSharedFlow(
            replay = 0,
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
        val next = state.seq.incrementAndGet()
        val frame = factory(next)
        val sf = SeqFrame(next, frame)
        synchronized(state.ringLock) {
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

        /** Build a console topic key for a given project id. */
        fun consoleTopic(projectId: String): String = "console-$projectId"

        /**
         * v0.44.0 — sub-agent console topic. Lives parallel to the main project console so
         * Phase 23's per-agent child processes broadcast on their own ring buffer.
         */
        fun subAgentConsoleTopic(projectId: String, agentName: String): String =
            "console-agent-$projectId-$agentName"
    }
}
