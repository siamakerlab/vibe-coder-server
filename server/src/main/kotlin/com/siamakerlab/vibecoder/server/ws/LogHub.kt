package com.siamakerlab.vibecoder.server.ws

import com.siamakerlab.vibecoder.shared.ws.WsFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-task / per-build broadcast bus for WebSocket subscribers.
 * Each topic is identified by a stable key (e.g., "task-...", "build-...").
 *
 * - Buffer 256 lines with DROP_OLDEST policy so a slow consumer cannot
 *   stall the producer (which is a live process).
 * - Replay 64 lines so a freshly-connected client sees recent history.
 */
class LogHub {

    private val topics = ConcurrentHashMap<String, MutableSharedFlow<WsFrame>>()

    fun publisher(topic: String): MutableSharedFlow<WsFrame> =
        topics.computeIfAbsent(topic) {
            MutableSharedFlow(
                replay = 64,
                extraBufferCapacity = 256,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

    fun subscribe(topic: String): SharedFlow<WsFrame> = publisher(topic).asSharedFlow()

    fun close(topic: String) {
        topics.remove(topic)
    }
}
