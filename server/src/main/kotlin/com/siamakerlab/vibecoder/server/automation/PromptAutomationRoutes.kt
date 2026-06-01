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
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.PromptAutomationRunRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationMode
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetUpsertDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationPresetsResponseDto
import com.siamakerlab.vibecoder.shared.dto.PromptAutomationStartRequestDto
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
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val form = call.receiveParameters()
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
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val form = call.receiveParameters()
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
