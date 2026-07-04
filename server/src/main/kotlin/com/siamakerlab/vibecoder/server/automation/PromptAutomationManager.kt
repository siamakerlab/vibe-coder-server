package com.siamakerlab.vibecoder.server.automation

import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRepository
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationMode
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatus
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStatusDto
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * v1.59.0 — 프롬프트 자동화 오케스트레이터 (서버 백그라운드 autopilot).
 *
 * 프로젝트당 active run 1개를 in-memory 로 관리한다. 시작 시 프롬프트 큐를 만들고
 * 첫 프롬프트를 보낸 뒤, [AgentRouter] 가 선택한 provider 의 manager 가 turn 완료(Done)를
 * 통지할 때마다 ([onTurnDone]) 큐에서 다음 프롬프트를 꺼내 보낸다. 큐가 비면 완료.
 *
 * v1.146.0 — Claude 전용([ClaudeSessionManager])에서 provider 무관([AgentRouter])으로
 * 전환. Codex/OpenCode provider 로 자동화가 동작한다 (provider 가 turn 완료 리스너를
 * [AgentSessionManager.turnDoneListener] 에서 구현한 경우).
 *
 * 브라우저가 닫혀도 서버 프로세스 안에서 계속 진행된다(사용자 요구). 진행 상태는
 * [WsFrame.AutomationProgress] 로 console topic 에 broadcast → 웹/Android 콘솔이 뱃지 갱신.
 *
 * 안전장치: 프로젝트당 1개, 총 발사 수 상한([MAX_TOTAL_SENDS]), 에러/cancel/crash 중단.
 */
class PromptAutomationManager(
    private val router: AgentRouter,
    private val runRepo: PromptAutomationRunRepository,
    private val hub: LogHub,
) {

    /** 시작 명세 — 라우트가 preset 또는 inline 요청을 정규화해 전달. */
    data class StartSpec(
        val name: String,
        val mode: String,            // repeat | sequence
        val prompts: List<String>,
        val repeatCount: Int,
        val loops: Int,
        val stopOnError: Boolean,
    )

    private class ActiveRun(
        val runId: String,
        val projectId: String,
        val name: String,
        val mode: String,
        val queue: ArrayDeque<String>,
        val total: Int,
        var sent: Int,
        val stopOnError: Boolean,
    )

    private val active = ConcurrentHashMap<String, ActiveRun>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(projectId: String) = locks.computeIfAbsent(projectId) { Mutex() }

    fun isActive(projectId: String): Boolean = active.containsKey(projectId)

    /** 자동화 시작. 이미 진행 중이면 409. 첫 프롬프트를 즉시 전송. */
    suspend fun start(projectId: String, spec: StartSpec): PromptAutomationStatusDto = lockFor(projectId).withLock {
        if (active.containsKey(projectId)) {
            throw ApiException.localized(409, "automation_already_running", messageKey = "api.automation.alreadyRunning")
        }
        val queue = buildQueue(spec)
        if (queue.isEmpty()) {
            throw ApiException.localized(400, "empty_prompts", messageKey = "api.automation.emptyPrompts")
        }
        if (queue.size > MAX_TOTAL_SENDS) {
            throw ApiException.localized(400, "too_many_sends", messageKey = "api.automation.tooManySends", args = listOf(MAX_TOTAL_SENDS))
        }
        val row = runRepo.create(projectId, spec.name, spec.mode, queue.size)
        val run = ActiveRun(row.id, projectId, spec.name, spec.mode, ArrayDeque(queue), queue.size, 0, spec.stopOnError)
        active[projectId] = run

        val first = run.queue.removeFirst()
        try {
            router.sendPrompt(projectId, first)
            run.sent = 1
            runRepo.updateSent(run.runId, 1)
            emitSystem(projectId, "automation_started", systemMsg("자동화 시작", spec.name, 1, run.total))
            emitProgress(run, PromptAutomationStatus.RUNNING, active = true, lastPrompt = first)
        } catch (e: Throwable) {
            active.remove(projectId)
            runRepo.finish(run.runId, PromptAutomationStatus.FAILED, 0, e.message ?: "send_failed")
            emitProgress(run, PromptAutomationStatus.FAILED, active = false, lastPrompt = null)
            throw ApiException.localized(500, "automation_start_failed", messageKey = "api.automation.startFailed", args = listOf(e.message ?: "send_failed"))
        }
        statusOf(projectId)
    }

    /** turn 정상 완료 통지 — 다음 프롬프트 발사 또는 완료. */
    suspend fun onTurnDone(projectId: String, reason: String) = lockFor(projectId).withLock {
        val run = active[projectId] ?: return@withLock
        // 에러성 종료 + stopOnError → FAILED 중단.
        if (run.stopOnError && reason.startsWith("error")) {
            active.remove(projectId)
            runRepo.finish(run.runId, PromptAutomationStatus.FAILED, run.sent, reason)
            emitSystem(projectId, "automation_failed", systemMsg("자동화 중단(에러)", run.name, run.sent, run.total))
            emitProgress(run, PromptAutomationStatus.FAILED, active = false, lastPrompt = null)
            return@withLock
        }
        val next = run.queue.removeFirstOrNull()
        if (next == null) {
            active.remove(projectId)
            runRepo.finish(run.runId, PromptAutomationStatus.DONE, run.sent)
            emitSystem(projectId, "automation_done", systemMsg("자동화 완료", run.name, run.sent, run.total))
            emitProgress(run, PromptAutomationStatus.DONE, active = false, lastPrompt = null)
            return@withLock
        }
        try {
            router.sendPrompt(projectId, next)
            run.sent += 1
            runRepo.updateSent(run.runId, run.sent)
            emitProgress(run, PromptAutomationStatus.RUNNING, active = true, lastPrompt = next)
        } catch (e: Throwable) {
            active.remove(projectId)
            runRepo.finish(run.runId, PromptAutomationStatus.FAILED, run.sent, e.message ?: "send_failed")
            emitProgress(run, PromptAutomationStatus.FAILED, active = false, lastPrompt = null)
            log.warn(e) { "[$projectId] automation next-prompt send failed" }
        }
    }

    /** cancel / new session / crash → 진행 중 자동화 중단. */
    suspend fun onInterrupt(projectId: String, reason: String) = lockFor(projectId).withLock {
        val run = active.remove(projectId) ?: return@withLock
        runRepo.finish(run.runId, PromptAutomationStatus.STOPPED, run.sent, "interrupted:$reason")
        emitSystem(projectId, "automation_stopped", systemMsg("자동화 중단", run.name, run.sent, run.total))
        emitProgress(run, PromptAutomationStatus.STOPPED, active = false, lastPrompt = null)
    }

    /** 사용자 명시적 중지. */
    suspend fun stop(projectId: String): Boolean = lockFor(projectId).withLock {
        val run = active.remove(projectId) ?: return@withLock false
        runRepo.finish(run.runId, PromptAutomationStatus.STOPPED, run.sent)
        emitSystem(projectId, "automation_stopped", systemMsg("자동화 중지", run.name, run.sent, run.total))
        emitProgress(run, PromptAutomationStatus.STOPPED, active = false, lastPrompt = null)
        true
    }

    /** 현재(active) 또는 마지막 run 스냅샷. */
    fun statusOf(projectId: String): PromptAutomationStatusDto {
        active[projectId]?.let { r ->
            return PromptAutomationStatusDto(
                active = true, runId = r.runId, name = r.name, mode = r.mode,
                status = PromptAutomationStatus.RUNNING, sent = r.sent, total = r.total,
            )
        }
        val last = runRepo.lastForProject(projectId) ?: return PromptAutomationStatusDto(active = false)
        return PromptAutomationStatusDto(
            active = false, runId = last.id, name = last.name, mode = last.mode,
            status = last.status, sent = last.sent, total = last.total,
            startedAt = last.startedAt, finishedAt = last.finishedAt, lastError = last.lastError,
        )
    }

    private fun buildQueue(spec: StartSpec): List<String> {
        val prompts = spec.prompts.map { it.trim() }.filter { it.isNotEmpty() }
        if (prompts.isEmpty()) return emptyList()
        return when (spec.mode) {
            PromptAutomationMode.REPEAT -> List(spec.repeatCount.coerceIn(1, 1000)) { prompts.first() }
            PromptAutomationMode.SEQUENCE -> buildList {
                repeat(spec.loops.coerceIn(1, 1000)) { addAll(prompts) }
            }
            else -> emptyList()
        }
    }

    private suspend fun emitProgress(run: ActiveRun, status: String, active: Boolean, lastPrompt: String?) {
        hub.emitConsole(LogHub.consoleTopic(run.projectId)) { seq ->
            WsFrame.AutomationProgress(
                projectId = run.projectId, runId = run.runId, status = status, mode = run.mode,
                sent = run.sent, total = run.total, active = active, lastPrompt = lastPrompt, seq = seq,
            )
        }
    }

    private suspend fun emitSystem(projectId: String, code: String, message: String) {
        hub.emitConsole(LogHub.consoleTopic(projectId)) { seq ->
            WsFrame.ConsoleSystem(code = code, message = message, seq = seq)
        }
    }

    private fun systemMsg(label: String, name: String, sent: Int, total: Int): String =
        "🤖 $label — $name ($sent/$total)"

    companion object {
        /** repeat×count / sequence×loops 로 만들어진 큐의 절대 상한. 비용 폭주 방지. */
        const val MAX_TOTAL_SENDS = 500
    }
}
