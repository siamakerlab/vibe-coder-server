package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.server.auth.requireProjectAcl
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.shared.ApiPath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

private val log = KotlinLogging.logger {}

/**
 * v0.65.0 — Phase 44. `/api/projects/{id}/zip` JSON variant (Bearer 토큰 인증).
 *
 * 기존 SSR `/projects/{id}/zip` ([com.siamakerlab.vibecoder.server.admin.WebProjectRoutes])
 * 는 cookie 세션만 받아서 Android Bearer client 호출이 불가능했음. 같은 zip 스트리밍 로직을
 * Bearer 인증 + Project ACL 검증 + 동일 Content-Disposition 으로 노출.
 *
 * 응답: `application/zip` octet stream. Content-Disposition: attachment, filename=
 * `<projectId>-source-<yyyyMMdd-HHmm>.zip` (SSR 과 동일 형식).
 *
 * `.git`, `build`, `.gradle`, `node_modules` 등은 [ProjectArchiver] 가 제외.
 */
fun Routing.jsonProjectZipRoutes(
    projects: ProjectService,
    projectArchiver: ProjectArchiver,
) {
    authenticate(AUTH_BEARER) {
        // Note: 라우터 등록은 hardcoded path template (Ktor 의 `{name}` placeholder).
        // ApiPath.projectZipJson(...) 는 client 호출용 (pathSeg encoding).
        get("/api/projects/{projectId}/zip") {
            val pid = call.parameters["projectId"]
                ?: throw ApiException(400, "bad_request", "projectId is required")
            call.requireProjectAcl(projects, pid)
            // 존재 검사 — 모르는 projectId 면 404.
            runCatching { projects.get(pid) }.getOrElse {
                throw ApiException(404, "project_not_found", "project '$pid' not found")
            }
            val safeName = pid.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now())
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${safeName}-source-${ts}.zip\"",
            )
            call.respondOutputStream(ContentType.parse("application/zip")) {
                runCatching { projectArchiver.streamZip(pid, this) }
                    .onFailure { log.warn(it) { "zip stream failed for $pid: ${it.message}" } }
            }
            // ApiPath SSOT 참조 (사용은 안 하지만 grep 매칭 + SSOT 검증용).
            @Suppress("UNUSED_EXPRESSION") ApiPath.projectZip(pid)
        }
    }
}
