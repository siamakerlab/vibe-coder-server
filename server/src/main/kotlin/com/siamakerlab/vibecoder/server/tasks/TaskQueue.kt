package com.siamakerlab.vibecoder.server.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * Project-level task queue. Two tasks for the **same projectId** are serialized
 * via per-project [Mutex]; tasks for different projects can run concurrently.
 *
 * The [executor] receives a cold cancellation [Flow] — emit a single value
 * via [cancel] to ask the running process to stop.
 */
class TaskQueue {

    private val projectMutexes = ConcurrentHashMap<String, Mutex>()
    private val cancellations = ConcurrentHashMap<String, MutableSharedFlow<Unit>>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param onStart   called once the project lock is acquired
     * @param executor  receives a cancellation Flow; throw to fail, return to succeed
     * @param onSuccess / [onFailure] / [onCancel] mutually exclusive completion callbacks
     */
    fun submit(
        projectId: String,
        taskId: String,
        onStart: suspend () -> Unit = {},
        executor: suspend (Flow<Unit>) -> Unit,
        onSuccess: suspend () -> Unit = {},
        onFailure: suspend (Throwable) -> Unit = {},
        onCancel: suspend () -> Unit = {},
    ): Job {
        val mutex = projectMutexes.computeIfAbsent(projectId) { Mutex() }
        val cancel = cancellations.computeIfAbsent(taskId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 1)
        }
        val job = scope.launch {
            try {
                mutex.withLock {
                    onStart()
                    try {
                        executor(cancel)
                        onSuccess()
                    } catch (e: CancellationException) {
                        log.info { "task $taskId cancelled" }
                        onCancel()
                        throw e
                    } catch (e: Throwable) {
                        log.warn(e) { "task $taskId failed: ${e.message}" }
                        onFailure(e)
                    }
                }
            } finally {
                activeJobs.remove(taskId)
                cancellations.remove(taskId)
            }
        }
        activeJobs[taskId] = job
        return job
    }

    suspend fun cancel(taskId: String) {
        val signal = cancellations[taskId] ?: return
        signal.emit(Unit)
        activeJobs[taskId]?.cancel(CancellationException("cancelled by API"))
    }

    fun activeCount(): Int = activeJobs.size
}
