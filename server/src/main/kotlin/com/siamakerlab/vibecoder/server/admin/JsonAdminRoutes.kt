package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.PasswordHasher
import com.siamakerlab.vibecoder.server.auth.requireApiAdmin
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.build.DependencyAudit
import com.siamakerlab.vibecoder.server.build.GradleWrapperService
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.projects.CodeSearchService
import com.siamakerlab.vibecoder.server.projects.CodeStatsService
import com.siamakerlab.vibecoder.server.projects.ProjectService
import com.siamakerlab.vibecoder.server.repo.AdminUserRepository
import com.siamakerlab.vibecoder.server.repo.AuditLogRepository
import com.siamakerlab.vibecoder.server.repo.BuildScheduleRepository
import com.siamakerlab.vibecoder.server.repo.DeviceRepository
import com.siamakerlab.vibecoder.shared.ApiPath
import com.siamakerlab.vibecoder.shared.dto.AdminUserDto
import com.siamakerlab.vibecoder.shared.dto.AuditLogEntryDto
import com.siamakerlab.vibecoder.shared.dto.AuditLogPageDto
import com.siamakerlab.vibecoder.shared.dto.AutoBackupEntryDto
import com.siamakerlab.vibecoder.shared.dto.BackupListResponseDto
import com.siamakerlab.vibecoder.shared.dto.BackupRunNowResponseDto
import com.siamakerlab.vibecoder.shared.dto.BuildScheduleCreateRequestDto
import com.siamakerlab.vibecoder.shared.dto.BuildScheduleDto
import com.siamakerlab.vibecoder.shared.dto.BuildScheduleToggleRequestDto
import com.siamakerlab.vibecoder.shared.dto.BuildSchedulesResponseDto
import com.siamakerlab.vibecoder.shared.dto.CodeLanguageStatDto
import com.siamakerlab.vibecoder.shared.dto.CodeSearchHitDto
import com.siamakerlab.vibecoder.shared.dto.CodeSearchResponseDto
import com.siamakerlab.vibecoder.shared.dto.CodeStatsResponseDto
import com.siamakerlab.vibecoder.shared.dto.DependencyAuditResponseDto
import com.siamakerlab.vibecoder.shared.dto.DependencyCoordinateDto
import com.siamakerlab.vibecoder.shared.dto.EnvFileDto
import com.siamakerlab.vibecoder.shared.dto.EnvFileSaveRequestDto
import com.siamakerlab.vibecoder.shared.dto.EnvFilesResponseDto
import com.siamakerlab.vibecoder.shared.dto.GradleWrapperInfoDto
import com.siamakerlab.vibecoder.shared.dto.GradleWrapperUpdateRequestDto
import com.siamakerlab.vibecoder.shared.dto.LogSearchHitDto
import com.siamakerlab.vibecoder.shared.dto.LogSearchResponseDto
import com.siamakerlab.vibecoder.shared.dto.UserCreateRequestDto
import com.siamakerlab.vibecoder.shared.dto.UserRoleUpdateRequestDto
import com.siamakerlab.vibecoder.shared.dto.UsersResponseDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Files

private val log = KotlinLogging.logger {}

/**
 * v0.67.0 — Phase 46. Group B (admin / 운영) JSON API 라우터 묶음.
 *
 * 기존 SSR 만 있던 admin 기능들을 Bearer 인증 JSON 으로도 노출. SSR 라우터는 그대로 유지
 * (admin 브라우저 UI 호환). 본 라우터들은 Android admin 화면 + 외부 도구가 호출.
 *
 * 모두 `requireApiAdmin()` — admin role 만 호출 가능. viewer/member 는 403 admin_only.
 *
 * 라우터를 다섯 카테고리로 묶지만 모두 같은 파일에 정의 — wiring 단순화. 각 그룹 함수가
 * 개별 라우터 등록을 담당, [jsonAdminRoutes] top-level 함수가 모두 호출.
 */
fun Routing.jsonAdminRoutes(
    // B1
    users: AdminUserRepository,
    deviceRepo: DeviceRepository,
    hasher: PasswordHasher,
    // B2
    schedules: BuildScheduleRepository,
    projects: ProjectService,
    // B3
    backup: com.siamakerlab.vibecoder.server.admin.BackupService,
    workspace: WorkspacePath,
    // B4
    audit: AuditLogRepository,
    // B5
    codeSearch: CodeSearchService,
    codeStats: CodeStatsService,
    deps: DependencyAudit,
    wrapper: GradleWrapperService,
    /** v0.70.0 — Phase 49 #1 실제 log search 활성화. */
    logSearch: LogSearchService,
) {
    authenticate(AUTH_BEARER) {

        // v1.45.0 — 단일 admin 화: 멀티유저 JSON API(users CRUD / role) 제거.
        // 인증/토큰/2FA/passkey 는 유지. user 관리 UI 와 함께 삭제.

        // ────────── B2. Build automation (project-scoped) ───────────
        get(ApiPath.automationSchedules("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val items = schedules.listAll()
                .filter { it.projectId == pid }
                .map { it.toDto() }
            call.respond(BuildSchedulesResponseDto(items))
        }
        post(ApiPath.automationSchedules("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val req = call.receive<BuildScheduleCreateRequestDto>()
            if (req.cronExpr.isBlank())
                throw ApiException.localized(400, "bad_request", messageKey = "api.admin.cronRequired")
            val row = schedules.create(pid, req.cronExpr.trim(), req.variant.ifBlank { "debug" }, req.description)
            call.respond(HttpStatusCode.Created, row.toDto())
        }
        post(ApiPath.automationScheduleToggle("{projectId}", "{scheduleId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val sid = call.parameters["scheduleId"]!!
            val req = call.receive<BuildScheduleToggleRequestDto>()
            val n = schedules.toggleEnabled(sid, req.enabled)
            if (n == 0) throw ApiException.localized(404, "not_found", messageKey = "api.admin.scheduleNotFound")
            call.respond(HttpStatusCode.NoContent)
        }
        delete(ApiPath.automationSchedule("{projectId}", "{scheduleId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val sid = call.parameters["scheduleId"]!!
            val n = schedules.delete(sid)
            if (n == 0) throw ApiException.localized(404, "not_found", messageKey = "api.admin.scheduleNotFound")
            call.respond(HttpStatusCode.NoContent)
        }

        // ────────── B3. Backup ──────────────────────────────────────
        get(ApiPath.BACKUP_LIST) {
            call.requireApiAdmin()
            val list = backup.listAutoBackups().map {
                AutoBackupEntryDto(it.fileName, it.sizeBytes, it.createdAtMs)
            }
            call.respond(BackupListResponseDto(
                manualFileName = backup.downloadFileName(),
                autoBackups = list,
            ))
        }
        get(ApiPath.BACKUP_DOWNLOAD) {
            call.requireApiAdmin()
            val name = backup.downloadFileName()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
            call.respondOutputStream(ContentType.parse("application/gzip")) {
                runCatching { backup.streamTarGz(this) }
                    .onFailure { log.warn(it) { "backup stream failed: ${it.message}" } }
            }
        }
        get(ApiPath.backupAutoFile("{fileName}")) {
            call.requireApiAdmin()
            val name = call.parameters["fileName"]!!
            val p = backup.resolveAutoBackupForDownload(name)
                ?: throw ApiException.localized(404, "not_found", messageKey = "api.admin.autoBackupNotFound")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${p.fileName}\"")
            call.respondOutputStream(ContentType.parse("application/gzip")) {
                Files.copy(p, this)
            }
        }
        delete(ApiPath.backupAutoFile("{fileName}")) {
            call.requireApiAdmin()
            val name = call.parameters["fileName"]!!
            val ok = backup.deleteAutoBackup(name)
            if (!ok) throw ApiException.localized(404, "not_found", messageKey = "api.admin.autoBackupNotFound")
            call.respond(HttpStatusCode.NoContent)
        }
        post(ApiPath.BACKUP_RUN_NOW) {
            call.requireApiAdmin()
            val p = backup.createScheduled()
            call.respond(BackupRunNowResponseDto(
                fileName = p.fileName.toString(),
                sizeBytes = runCatching { Files.size(p) }.getOrDefault(0L),
            ))
        }

        // ────────── B4. Audit log ───────────────────────────────────
        get(ApiPath.AUDIT_LIST) {
            call.requireApiAdmin()
            val params = call.request.queryParameters
            val limit = (params["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
            val page = (params["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val filter = AuditLogRepository.Filter(
                action = params["action"]?.ifBlank { null },
                result = params["result"]?.ifBlank { null },
                userId = params["userId"]?.ifBlank { null },
                fromTs = params["from"]?.ifBlank { null },
                toTs = params["to"]?.ifBlank { null },
            )
            val rows = audit.list(filter, limit = limit, offset = page.toLong() * limit.toLong())
            val total = audit.count(filter)
            val hasNext = (page + 1).toLong() * limit.toLong() < total
            call.respond(AuditLogPageDto(
                entries = rows.map {
                    AuditLogEntryDto(
                        id = it.id, ts = it.ts, userId = it.userId, deviceId = it.deviceId,
                        ip = it.ip, action = it.action, resourceType = it.resourceType,
                        resourceId = it.resourceId, result = it.result, detail = it.detail,
                    )
                },
                nextCursor = if (hasNext) (page + 1).toString() else null,
                total = total,
            ))
        }

        // ────────── B5. Admin info (read-only + 일부 mutation) ──────
        get(ApiPath.LOG_SEARCH) {
            call.requireApiAdmin()
            val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
                ?: return@get call.respond(LogSearchResponseDto(emptyList()))
            val projectFilter = call.request.queryParameters["projectId"]?.ifBlank { null }
            // v0.70.0 — Phase 49 #1 실제 활성화. LogSearchService 가 SSR 과 같은 로직 reuse.
            val hits = logSearch.search(q, projectFilter).map {
                LogSearchHitDto(it.projectId, it.buildId, it.lineNumber, it.line)
            }
            call.respond(LogSearchResponseDto(hits))
        }
        get(ApiPath.CODE_SEARCH) {
            call.requireApiAdmin()
            val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
                ?: return@get call.respond(CodeSearchResponseDto(emptyList()))
            val pid = call.request.queryParameters["projectId"]?.ifBlank { null }
            val matches = codeSearch.search(q, projectFilter = pid)
            call.respond(CodeSearchResponseDto(
                hits = matches.map { CodeSearchHitDto(it.projectId, it.relPath, it.lineNumber, it.line) },
            ))
        }
        get(ApiPath.projectDeps("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val module = runCatching { projects.rowOrThrow(pid).moduleName }.getOrElse { "app" }
            val r = runCatching { deps.audit(pid, moduleName = module) }.getOrElse {
                return@get call.respond(DependencyAuditResponseDto(
                    ok = false, errorMessage = it.message,
                ))
            }
            call.respond(DependencyAuditResponseDto(
                ok = r.ok,
                moduleName = r.moduleName, configuration = r.configuration,
                durationMs = r.durationMs,
                coordinates = r.coordinates.map {
                    DependencyCoordinateDto(it.group, it.name, it.version)
                },
                rawOutput = r.rawOutput,
                errorMessage = r.errorMessage,
            ))
        }
        get(ApiPath.projectStats("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val r = codeStats.analyze(pid)
            call.respond(CodeStatsResponseDto(
                projectId = r.projectId,
                totalFiles = r.totalFiles, totalLines = r.totalLines, totalBytes = r.totalBytes,
                durationMs = r.durationMs,
                byLanguage = r.byLanguage.map {
                    CodeLanguageStatDto(it.language, it.files, it.lines, it.bytes)
                },
                errorMessage = r.errorMessage,
            ))
        }
        get(ApiPath.projectEnvFiles("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            // EnvFile 검색 — `EnvFilesRoutes` 의 내부 함수가 internal 로 정의됐다고 가정.
            // 안전한 길: 화이트리스트 파일 list 직접 — local.properties, gradle.properties, .env, .env.local 등.
            val items = WHITELISTED_ENV_FILES.map { rel ->
                val abs = workspace.projectRoot(pid).resolve(rel)
                val exists = Files.exists(abs)
                val body = if (exists) runCatching { Files.readString(abs) }.getOrDefault("") else ""
                EnvFileDto(
                    rel = rel, exists = exists,
                    sizeBytes = if (exists) runCatching { Files.size(abs) }.getOrDefault(0L) else 0L,
                    body = body,
                )
            }
            call.respond(EnvFilesResponseDto(items))
        }
        post(ApiPath.projectEnvFiles("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val req = call.receive<EnvFileSaveRequestDto>()
            if (req.rel !in WHITELISTED_ENV_FILES)
                throw ApiException.localized(400, "bad_request",
                    messageKey = "api.admin.envFileNotAllowed", args = listOf(req.rel))
            val abs = workspace.projectRoot(pid).resolve(req.rel)
            Files.createDirectories(abs.parent)
            Files.writeString(abs, req.body)
            call.respond(HttpStatusCode.NoContent)
        }
        get(ApiPath.projectWrapper("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val info = wrapper.inspect(pid)
            call.respond(GradleWrapperInfoDto(
                present = info.present,
                currentVersion = info.currentVersion,
                distributionType = info.distributionType,
                distributionUrl = info.distributionUrl,
                propertiesPath = info.propertiesPath,
            ))
        }
        post(ApiPath.projectWrapper("{projectId}")) {
            call.requireApiAdmin()
            val pid = call.parameters["projectId"]!!
            call.requireProjectAcl(projects, pid)
            val req = call.receive<GradleWrapperUpdateRequestDto>()
            if (req.newVersion.isBlank())
                throw ApiException.localized(400, "bad_request", messageKey = "api.admin.newVersionRequired")
            val dist = if (req.distributionType in setOf("bin", "all")) req.distributionType else "bin"
            wrapper.setVersion(pid, req.newVersion.trim(), dist)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// ── DTO 매핑 헬퍼 ────────────────────────────────────────────────────

private fun com.siamakerlab.vibecoder.server.repo.AdminUserRow.toDto() = AdminUserDto(
    id = id, username = username, role = role,
    createdAt = createdAt, lastLoginAt = lastLoginAt,
    passwordChangedAt = passwordChangedAt,
    totpEnabled = totpEnabled,
    passwordlessOnly = passwordlessOnly,
)

private fun com.siamakerlab.vibecoder.server.repo.BuildScheduleRow.toDto() = BuildScheduleDto(
    id = id, projectId = projectId, cronExpr = cronExpr, variant = variant,
    enabled = enabled, createdAt = createdAt, lastFiredAt = lastFiredAt,
    description = description,
)

/**
 * v0.76.0 (M6 fix) — `EnvFilesRoutes.ENV_FILES_WHITELIST` 를 직접 참조하여 SSOT
 * 정합. 이전엔 자체 4개 list 보유 (gradle 파일 3개 누락) → SSR/JSON 비대칭.
 * 이제 두 라우터가 같은 상수.
 */
private val WHITELISTED_ENV_FILES = com.siamakerlab.vibecoder.server.projects.ENV_FILES_WHITELIST
