package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.core.WorkspacePath
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

/**
 * v0.32.0 — `/projects/{id}/env-files` — Environment / build property 파일 빠른 편집.
 *
 * 화이트리스트 파일만 노출 (path traversal 방어). 큰 트리 탐색은 기존
 * `/projects/{id}/tree` (ProjectFileBrowser) 사용.
 */
fun Routing.envFilesRoutes(authDeps: AdminRoutesDeps, projects: ProjectService, workspace: WorkspacePath) {
    get("/projects/{id}/env-files") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val root = workspace.projectRoot(id)
        val files = ENV_FILES_WHITELIST.map { rel ->
            val path = root.resolve(rel)
            val exists = path.isRegularFile()
            val size = if (exists) runCatching { Files.size(path) }.getOrDefault(0L) else 0L
            val body = if (exists) runCatching { Files.readString(path) }.getOrDefault("(read failed)") else ""
            EnvFile(rel, exists, size, body)
        }
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(EnvFilesTemplates.page(sess.username, p, files, ok, err, sess.csrf, lang = sess.language, embed = call.isEmbeddedRequest()), ContentType.Text.Html)
    }

    post("/projects/{id}/env-files/save") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val params = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val rel = params["rel"]?.trim().orEmpty()
        val body = params["body"].orEmpty()
        if (rel !in ENV_FILES_WHITELIST) {
            call.respondRedirect("/projects/$id/env-files?err=${enc("invalid file: $rel")}")
            return@post
        }
        // v1.44.0 — char/byte 혼동 회수: String.length(UTF-16 char) 가 아니라 UTF-8 byte 로 검증.
        // (동종 핸들러 ProjectFileBrowser.write / AgentRegistry / SkillRegistry 와 정렬.)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            call.respondRedirect("/projects/$id/env-files?err=${enc("file too large (max $MAX_BODY_BYTES bytes)")}")
            return@post
        }
        runCatching {
            val root = workspace.projectRoot(id)
            val path = root.resolve(rel)
            Files.createDirectories(path.parent)
            val tmp = root.resolve("$rel.tmp")
            Files.writeString(tmp, body)
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            log.info { "env-file save: $id/$rel (${body.length}B) by ${sess.username}" }
        }.onFailure { e ->
            log.warn(e) { "env-file save failed: $id/$rel" }
            call.respondRedirect("/projects/$id/env-files?err=${enc("save failed: ${e.message}")}")
            return@post
        }
        call.respondRedirect("/projects/$id/env-files?ok=${enc("$rel 저장됨")}")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

internal data class EnvFile(val rel: String, val exists: Boolean, val sizeBytes: Long, val body: String)

/**
 * 화이트리스트 — Android 프로젝트 기준 환경/설정 파일.
 * 다른 파일은 기존 file browser (`/projects/{id}/tree`) 에서 편집.
 *
 * v0.76.0 (M6 fix) — `internal` 로 노출해서 [JsonAdminRoutes] 와 같은 SSOT.
 * 이전엔 JSON variant 가 4개만 보유 (gradle 파일 3개 누락) → SSR/JSON 비대칭.
 */
internal val ENV_FILES_WHITELIST = listOf(
    "local.properties",
    "gradle.properties",
    ".env",
    ".env.local",
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
)

private const val MAX_BODY_BYTES = 256 * 1024

internal object EnvFilesTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        p: com.siamakerlab.vibecoder.shared.dto.ProjectDto,
        files: List<EnvFile>,
        ok: String?,
        err: String?,
        csrf: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        val cards = files.joinToString("\n") { f ->
            val existsBadge = if (f.exists) """<span class="ok">✓ ${(f.sizeBytes / 1024.0).let { "%.1fKB".format(it) }}</span>"""
            else """<span class="dim">(없음 — 저장 시 생성됨)</span>"""
            // Plain textarea — syntax highlight 는 /projects/{id}/view 의 highlight.js
            val isSecret = f.rel.contains(".env") || f.rel.endsWith(".properties")
            val warn = if (isSecret) """<p class="hint" style="font-size:11px;color:#facc15">⚠ 비밀번호/API key 가 포함될 수 있습니다. workspace 외부 (호스트) 로 노출되지 않도록 주의.</p>""" else ""
            """
<div class="card" style="margin-bottom:16px">
  <details ${if (f.exists) "open" else ""}>
    <summary><strong>${esc(f.rel)}</strong> $existsBadge</summary>
    <form method="post" action="/projects/${esc(p.id)}/env-files/save" style="display:grid;gap:8px;margin-top:10px">
      ${CsrfTokens.hiddenInput(csrf)}
      <input type="hidden" name="rel" value="${esc(f.rel)}">
      <textarea name="body" rows="14" spellcheck="false" style="font-family:ui-monospace,Menlo,monospace;font-size:12px">${esc(f.body)}</textarea>
      <div>
        <button type="submit" class="primary">저장</button>
      </div>
      $warn
    </form>
  </details>
</div>"""
        }

        return AdminTemplates.shell(
            title = "${esc(p.name)} · Env files",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>Env / Build 파일
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

$okHtml
$errHtml

<p class="hint" style="margin-bottom:14px">
  화이트리스트된 환경/빌드 설정 파일만 빠른 편집. 기타 파일은 <a href="/projects/${esc(p.id)}/tree">파일 트리</a>.
  저장은 atomic move (`.tmp` → `move REPLACE_EXISTING`) — 빌드 중 race 안전.
</p>

$cards
""",
            lang = lang,
            embed = embed,
        )
    }
}
