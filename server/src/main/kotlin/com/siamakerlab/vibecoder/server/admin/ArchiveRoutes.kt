package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.projects.ProjectArchiveService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v1.98.0 — 프로젝트 아카이브 열람/복원. Tools 탭의 'Archive' inner 페이지(embed 대응).
 *
 *   GET  /archive                  — 아카이브 목록 (Tools 탭 iframe inner)
 *   POST /projects/{id}/archive    — 프로젝트 아카이브 실행(목록/프로젝트 페이지에서)
 *   POST /archive/{aid}/restore    — 복원(언아카이브)
 *   POST /archive/{aid}/delete     — 아카이브 영구 삭제
 *   GET  /archive/{aid}/download   — tar.gz 다운로드
 */
fun Routing.archiveRoutes(
    authDeps: AdminRoutesDeps,
    archive: ProjectArchiveService,
) {
    get("/archive") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val rows = runCatching { archive.list() }.getOrDefault(emptyList())
        call.respondText(
            ArchiveTemplates.page(
                username = sess.username,
                archives = rows,
                ok = call.request.queryParameters["ok"],
                err = call.request.queryParameters["err"],
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/archive") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        val r = runCatching { archive.archive(id) }
        if (r.isSuccess) {
            call.respondRedirect("/projects?ok=${enc("아카이브됨: $id")}")
        } else {
            log.warn(r.exceptionOrNull()) { "archive failed: $id" }
            call.respondRedirect("/projects?err=${enc("아카이브 실패: ${r.exceptionOrNull()?.message ?: "error"}")}")
        }
    }

    post("/archive/{aid}/restore") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val aid = call.parameters["aid"]!!
        val r = runCatching { archive.unarchive(aid) }
        if (r.isSuccess) {
            call.respondRedirect("/archive?ok=${enc("복원되었습니다 — 프로젝트 목록에서 확인하세요.")}")
        } else {
            log.warn(r.exceptionOrNull()) { "restore failed: $aid" }
            call.respondRedirect("/archive?err=${enc("복원 실패: ${r.exceptionOrNull()?.message ?: "error"}")}")
        }
    }

    post("/archive/{aid}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val aid = call.parameters["aid"]!!
        runCatching { archive.deleteArchive(aid) }
            .onFailure { log.warn(it) { "archive delete failed: $aid" } }
        call.respondRedirect("/archive?ok=${enc("아카이브를 삭제했습니다.")}")
    }

    get("/archive/{aid}/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val aid = call.parameters["aid"]!!
        val f = archive.archiveFile(aid)
        if (f == null) {
            call.respondRedirect("/archive?err=${enc("아카이브 파일을 찾을 수 없습니다.")}")
            return@get
        }
        call.response.headers.append("Content-Disposition", "attachment; filename=\"$aid.tar.gz\"")
        call.respondFile(f.toFile())
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
