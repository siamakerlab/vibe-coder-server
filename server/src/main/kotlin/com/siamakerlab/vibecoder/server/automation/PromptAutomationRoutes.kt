package com.siamakerlab.vibecoder.server.automation

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.auth.requireApiWrite
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.claude.ClaudeStatusService
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRepository
import com.siamakerlab.vibecoder.server.repo.ScheduledPromptRepository
import com.siamakerlab.vibecoder.server.repo.ScheduledPromptRow
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationMode
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetUpsertDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetsResponseDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStartRequestDto
import com.siamakerlab.vibecoder.shared.dto.ScheduleSendRequestDto
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptDto
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptsResponseDto
import com.siamakerlab.vibecoder.shared.dto.ScheduledPromptTriggers
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private val log = KotlinLogging.logger {}

/**
 * v1.59.0 — 프롬프트 자동화 라우트.
 *
 * JSON (Bearer) — 콘솔 패널 / Android 가 사용:
 *   POST  promptAutomationStart(projectId)   — 시작 (preset 또는 inline)
 *   POST  promptAutomationStop(projectId)    — 중지
 *   GET   promptAutomationStatus(projectId)  — 현재/마지막 진행
 *   GET   PROMPT_AUTOMATION_PRESETS          — 프리셋 목록 (workspace 전역)
 *   POST  PROMPT_AUTOMATION_PRESETS          — 프리셋 생성
 *   PUT   promptAutomationPreset(presetId)   — 프리셋 수정
 *   DELETE promptAutomationPreset(presetId)  — 프리셋 삭제
 *
 * SSR (cookie 세션 + CSRF) — `/projects/{id}/automation/prompts`:
 *   프리셋 관리 + 즉석/프리셋 시작 + 중지 + 최근 실행 이력.
 */
fun Routing.promptAutomationRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    manager: PromptAutomationManager,
    presetStore: PromptAutomationPresetStore,
    runRepo: PromptAutomationRunRepository,
    schedRepo: ScheduledPromptRepository,
    statusService: ClaudeStatusService,
    clock: Clock,
) {
    // ── JSON API ──────────────────────────────────────────────────────
    authenticate(AUTH_BEARER) {
        post(ApiPath.promptAutomationStart("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val spec = resolveSpec(call.receive<PromptAutomationStartRequestDto>(), presetStore)
            val status = manager.start(projectId, spec)
            log.info { "automation started: $projectId mode=${spec.mode} total≈${spec.prompts.size}" }
            call.respond(HttpStatusCode.Accepted, status)
        }

        post(ApiPath.promptAutomationStop("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            manager.stop(projectId)
            call.respond(manager.statusOf(projectId))
        }

        get(ApiPath.promptAutomationStatus("{projectId}")) {
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            call.respond(manager.statusOf(projectId))
        }

        get(ApiPath.PROMPT_AUTOMATION_PRESETS) {
            call.respond(PromptAutomationPresetsResponseDto(presetStore.listAll()))
        }

        post(ApiPath.PROMPT_AUTOMATION_PRESETS) {
            call.requireApiWrite()
            call.respond(HttpStatusCode.Created, presetStore.create(call.receive<PromptAutomationPresetUpsertDto>()))
        }

        put(ApiPath.promptAutomationPreset("{presetId}")) {
            call.requireApiWrite()
            val presetId = call.parameters["presetId"].orEmpty()
            call.respond(presetStore.update(presetId, call.receive<PromptAutomationPresetUpsertDto>()))
        }

        delete(ApiPath.promptAutomationPreset("{presetId}")) {
            call.requireApiWrite()
            val presetId = call.parameters["presetId"].orEmpty()
            val ok = presetStore.delete(presetId)
            call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }

        // ── 프롬프트 예약 전송 (one-shot) ──────────────────────────────
        get(ApiPath.scheduledPrompts("{projectId}")) {
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            call.respond(ScheduledPromptsResponseDto(schedRepo.listForProject(projectId).map { it.toDto() }))
        }

        post(ApiPath.scheduledPrompts("{projectId}")) {
            call.requireApiWrite()
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val req = call.receive<ScheduleSendRequestDto>()
            val row = createSchedule(projectId, req, schedRepo, statusService, clock, lang = "ko")
            call.respond(HttpStatusCode.Created, row.toDto())
        }

        delete(ApiPath.scheduledPrompt("{projectId}", "{scheduleId}")) {
            call.requireApiWrite()
            val projectId = projectId()
            call.requireProjectAcl(projects, projectId)
            projects.rowOrThrow(projectId)
            val scheduleId = call.parameters["scheduleId"].orEmpty()
            val n = schedRepo.cancel(scheduleId, projectId)
            call.respond(if (n > 0) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }

    // ── SSR (cookie 세션 + CSRF) ──────────────────────────────────────
    get("/projects/{id}/automation/prompts") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        call.respondText(
            PromptAutomationTemplates.page(
                username = sess.username, p = p,
                presets = presetStore.listAll(),
                status = manager.statusOf(id),
                runs = runRepo.listForProject(id, limit = 15),
                ok = call.request.queryParameters["ok"],
                err = call.request.queryParameters["err"],
                csrf = sess.csrf, lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/automation/prompts/start") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val spec = runCatching {
            val presetId = form["presetId"]?.trim()?.ifBlank { null }
            if (presetId != null) {
                resolveSpec(PromptAutomationStartRequestDto(presetId = presetId), presetStore)
            } else {
                val mode = form["mode"]?.trim().orEmpty().ifBlank { PromptAutomationMode.REPEAT }
                val prompts = form["prompts"].orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                resolveSpec(
                    PromptAutomationStartRequestDto(
                        mode = mode, prompts = prompts,
                        repeatCount = form["repeatCount"]?.trim()?.toIntOrNull() ?: 1,
                        loops = form["loops"]?.trim()?.toIntOrNull() ?: 1,
                        stopOnError = form["stopOnError"]?.equals("on", true) ?: false || form["stopOnError"]?.equals("true", true) ?: false,
                    ),
                    presetStore,
                )
            }
        }.getOrElse {
            call.respondRedirect("/projects/$id/automation/prompts?err=${enc(it.message ?: "invalid")}")
            return@post
        }
        runCatching { manager.start(id, spec) }
            .onSuccess { log.info { "automation started (SSR): $id by ${sess.username}" } }
            .onFailure {
                val msg = (it as? ApiException)?.message ?: it.message ?: "start failed"
                call.respondRedirect("/projects/$id/automation/prompts?err=${enc(msg)}")
                return@post
            }
        call.respondRedirect("/projects/$id/automation/prompts?ok=${enc("자동화를 시작했습니다.")}")
    }

    post("/projects/{id}/automation/prompts/stop") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        manager.stop(id)
        call.respondRedirect("/projects/$id/automation/prompts?ok=${enc("자동화를 중지했습니다.")}")
    }

    post("/projects/{id}/automation/prompts/presets") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val req = PromptAutomationPresetUpsertDto(
            name = form["name"].orEmpty(),
            mode = form["mode"]?.trim().orEmpty().ifBlank { PromptAutomationMode.REPEAT },
            prompts = form["prompts"].orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            repeatCount = form["repeatCount"]?.trim()?.toIntOrNull() ?: 1,
            loops = form["loops"]?.trim()?.toIntOrNull() ?: 1,
            stopOnError = form["stopOnError"]?.equals("on", true) == true || form["stopOnError"]?.equals("true", true) == true,
        )
        runCatching { presetStore.create(req) }
            .onFailure {
                val msg = (it as? ApiException)?.message ?: it.message ?: "create failed"
                call.respondRedirect("/projects/$id/automation/prompts?err=${enc(msg)}")
                return@post
            }
        call.respondRedirect("/projects/$id/automation/prompts?ok=${enc("프리셋을 저장했습니다.")}")
    }

    post("/projects/{id}/automation/prompts/presets/{presetId}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        presetStore.delete(call.parameters["presetId"].orEmpty())
        call.respondRedirect("/projects/$id/automation/prompts?ok=${enc("프리셋을 삭제했습니다.")}")
    }
}

private fun io.ktor.server.routing.RoutingContext.projectId(): String =
    call.parameters["projectId"]
        ?: throw ApiException.localized(400, "bad_request", messageKey = "api.console.projectIdRequired")

/** 시작 요청을 [PromptAutomationManager.StartSpec] 으로 정규화. preset 우선. */
private fun resolveSpec(
    req: PromptAutomationStartRequestDto,
    presetStore: PromptAutomationPresetStore,
): PromptAutomationManager.StartSpec {
    val pid = req.presetId
    if (!pid.isNullOrBlank()) {
        val p = presetStore.get(pid)
            ?: throw ApiException.localized(404, "preset_not_found", messageKey = "api.automation.notFound", args = listOf(pid))
        return PromptAutomationManager.StartSpec(p.name, p.mode, p.prompts, p.repeatCount, p.loops, p.stopOnError)
    }
    if (req.mode !in PromptAutomationMode.ALL)
        throw ApiException.localized(400, "invalid_mode", messageKey = "api.automation.invalidMode")
    val prompts = req.prompts.map { it.trim() }.filter { it.isNotEmpty() }
    if (prompts.isEmpty())
        throw ApiException.localized(400, "empty_prompts", messageKey = "api.automation.emptyPrompts")
    return PromptAutomationManager.StartSpec(
        name = "ad-hoc", mode = req.mode, prompts = prompts,
        repeatCount = req.repeatCount, loops = req.loops, stopOnError = req.stopOnError,
    )
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

/** 최대 예약 프롬프트 크기(UTF-8 byte). 콘솔 textarea 한도와 동급. */
private const val MAX_SCHEDULE_PROMPT_BYTES = 32 * 1024

/**
 * v1.130.0 — 예약 생성 공통 로직. trigger 별 fireAtEpochMs / baselinePercent / 표시 라벨을
 * 계산해 [ScheduledPromptRepository.create] 로 저장한다. (JSON · SSR 양쪽에서 호출)
 */
private fun createSchedule(
    projectId: String,
    req: ScheduleSendRequestDto,
    schedRepo: ScheduledPromptRepository,
    statusService: ClaudeStatusService,
    clock: Clock,
    lang: String,
): ScheduledPromptRow {
    val prompt = req.prompt.trim()
    if (prompt.isBlank()) throw ApiException(400, "empty_prompt", "프롬프트를 입력하세요.")
    if (prompt.toByteArray(Charsets.UTF_8).size > MAX_SCHEDULE_PROMPT_BYTES) {
        throw ApiException(400, "prompt_too_large", "프롬프트가 너무 깁니다.")
    }
    val triggerType = ScheduledPromptTriggers.normalize(req.triggerType)
    var fireAt: Long? = null
    var baseline: Int? = null
    val label: String
    when (triggerType) {
        ScheduledPromptTriggers.TIME -> {
            val nowMs = clock.nowInstant().toEpochMilli()
            fireAt = req.atEpochMs
                ?: req.delayMinutes?.takeIf { it > 0 }?.let { nowMs + it * 60_000L }
                ?: throw ApiException(400, "no_time", "예약 시각(분/시간 또는 정확한 시각)을 지정하세요.")
            if (fireAt < nowMs - 60_000L) throw ApiException(400, "past_time", "과거 시각으로는 예약할 수 없습니다.")
            label = if (req.atEpochMs != null) AdminTemplates.fmtTsEpochMs(fireAt, lang)
            else delayLabel(req.delayMinutes ?: 0L)
        }
        ScheduledPromptTriggers.SESSION_RESET -> {
            baseline = statusService.cachedSnapshot(projectId).sessionUsagePercent
            label = "세션 한도 해제 후"
        }
        ScheduledPromptTriggers.WEEKLY_RESET -> {
            baseline = statusService.cachedSnapshot(projectId).weeklyUsagePercent
            label = "주간 한도 해제 후"
        }
        else -> throw ApiException(400, "invalid_trigger", "알 수 없는 트리거입니다.")
    }
    return schedRepo.create(projectId, prompt, triggerType, fireAt, label, baseline)
}

private fun delayLabel(minutes: Long): String = when {
    minutes <= 0 -> "곧"
    minutes < 60 -> "${minutes}분 뒤"
    minutes % 60 == 0L -> "${minutes / 60}시간 뒤"
    else -> "${minutes / 60}시간 ${minutes % 60}분 뒤"
}

internal fun ScheduledPromptRow.toDto() = ScheduledPromptDto(
    id = id, projectId = projectId, prompt = prompt, triggerType = triggerType,
    fireAtEpochMs = fireAtEpochMs, triggerLabel = triggerLabel, status = status,
    createdAt = createdAt, sentAt = sentAt, lastError = lastError,
)
