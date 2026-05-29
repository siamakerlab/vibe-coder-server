package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.ClaudeMdTemplates
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.i18n.Messages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/** 프로젝트 CLAUDE.md 최대 256KB (전역과 동일). */
private const val MAX_BYTES = 256 * 1024

/**
 * v1.35.0 — 프로젝트별 CLAUDE.md 관리 (프로젝트 탭). 파일명 고정("CLAUDE.md") 이라
 * path traversal 위험 없음. 저장은 env-files 와 동일한 atomic move.
 *
 *   GET  /projects/{id}/claude-md       — 편집기
 *   POST /projects/{id}/claude-md/save  — 저장
 */
fun Routing.projectClaudeMdRoutes(authDeps: AdminRoutesDeps, projects: ProjectService, workspace: WorkspacePath) {
    get("/projects/{id}/claude-md") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val path = workspace.projectRoot(id).resolve("CLAUDE.md")
        val exists = path.isRegularFile()
        val content = if (exists) runCatching { Files.readString(path) }.getOrDefault("") else ""
        val size = if (exists) runCatching { Files.size(path) }.getOrDefault(0L) else 0L
        val t = { key: String -> Messages.t(sess.language, key) }
        call.respondText(
            ClaudeMdTemplates.page(
                username = sess.username,
                currentPath = "/projects",
                heading = t("claudeMd.project.title"),
                intro = t("claudeMd.project.intro"),
                pathDisplay = "$id/CLAUDE.md",
                content = content,
                exists = exists,
                sizeBytes = size,
                saveAction = "/projects/$id/claude-md/save",
                csrf = sess.csrf,
                lang = sess.language,
                flashOk = if (call.request.queryParameters["ok"] == "saved") t("claudeMd.flash.saved") else null,
                flashErr = call.request.queryParameters["err"],
            ),
            ContentType.Text.Html,
        )
    }

    post("/projects/{id}/claude-md/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val content = call.receiveParameters()["content"].orEmpty()
        val t = { key: String -> Messages.t(sess.language, key) }
        if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
            call.respondRedirect("/projects/$id/claude-md?err=${enc(t("claudeMd.tooLarge"))}")
            return@post
        }
        runCatching {
            val root = workspace.projectRoot(id)
            val path = root.resolve("CLAUDE.md")
            Files.createDirectories(root)
            val tmp = root.resolve("CLAUDE.md.tmp")
            Files.writeString(tmp, content)
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            log.info { "project CLAUDE.md saved: $id (${content.toByteArray(StandardCharsets.UTF_8).size}B) by ${sess.username}" }
        }.onFailure { e ->
            log.warn(e) { "project CLAUDE.md save failed: $id" }
            call.respondRedirect("/projects/$id/claude-md?err=${enc("save failed: ${e.message}")}")
            return@post
        }
        call.respondRedirect("/projects/$id/claude-md?ok=saved")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
