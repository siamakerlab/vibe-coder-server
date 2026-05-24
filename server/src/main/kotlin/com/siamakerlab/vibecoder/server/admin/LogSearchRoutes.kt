package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.32.0 — `/logs` — 워크스페이스 안의 빌드 로그 파일을 가로질러 grep.
 *
 * Source:
 *   - `<workspace>/.vibecoder/<projectId>/logs/<buildId>.log` (BuildService 가 저장)
 *
 * 단순 in-process grep — 각 파일을 line by line 읽어 매치 라인만 수집.
 * tail (마지막 N MB) 만 읽어 큰 로그에서 성능 ↓ 방지.
 *
 * 안전 정책:
 *   - workspace 외부 경로 접근 불가 (WorkspacePath 안에서만).
 *   - q 빈 문자열 = 빈 결과 (대량 dump 방지).
 *   - matches 200 hard cap.
 */
fun Routing.logSearchRoutes(authDeps: AdminRoutesDeps, workspace: WorkspacePath) {
    get("/logs") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val q = call.request.queryParameters["q"]?.trim()?.ifBlank { null }
        val projectFilter = call.request.queryParameters["project"]?.trim()?.ifBlank { null }
        val matches = if (q == null) emptyList() else search(workspace, q, projectFilter)
        call.respondText(
            renderPage(sess.username, sess.csrf, q, projectFilter, matches),
            ContentType.Text.Html,
        )
    }
}

data class LogMatch(
    val projectId: String,
    val buildId: String,
    val lineNumber: Int,
    val line: String,
)

private const val MAX_MATCHES = 200
private const val MAX_BYTES_PER_FILE = 2 * 1024 * 1024  // 마지막 2 MB

private fun search(workspace: WorkspacePath, q: String, projectFilter: String?): List<LogMatch> {
    val sidecar = workspace.root.resolve(".vibecoder")
    if (!Files.isDirectory(sidecar)) return emptyList()
    val results = mutableListOf<LogMatch>()
    val qLower = q.lowercase()

    Files.list(sidecar).use { topStream ->
        topStream.toList().forEach { projectDir ->
            if (results.size >= MAX_MATCHES) return@forEach
            if (!Files.isDirectory(projectDir)) return@forEach
            val pid = projectDir.name
            if (projectFilter != null && pid != projectFilter) return@forEach
            val logsDir = projectDir.resolve("logs")
            if (!Files.isDirectory(logsDir)) return@forEach
            Files.list(logsDir).use { logStream ->
                logStream.toList().forEach inner@{ logFile ->
                    if (results.size >= MAX_MATCHES) return@inner
                    if (!logFile.isRegularFile() || !logFile.name.endsWith(".log")) return@inner
                    val buildId = logFile.name.removeSuffix(".log")
                    grepFile(logFile, qLower, pid, buildId, results)
                }
            }
        }
    }
    return results.take(MAX_MATCHES)
}

/** 파일 끝에서 MAX_BYTES_PER_FILE 만 읽어 grep (큰 빌드 로그 성능). */
private fun grepFile(path: Path, qLower: String, pid: String, buildId: String, out: MutableList<LogMatch>) {
    val size = runCatching { Files.size(path) }.getOrDefault(0L)
    val skip = (size - MAX_BYTES_PER_FILE).coerceAtLeast(0)
    runCatching {
        Files.newBufferedReader(path).use { reader ->
            if (skip > 0) reader.skip(skip)
            var lineNo = 0
            // skip 으로 라인 잘림 첫 줄은 drop
            if (skip > 0) { reader.readLine(); lineNo++ }
            while (true) {
                val line = reader.readLine() ?: break
                lineNo++
                if (out.size >= MAX_MATCHES) return@use
                if (line.lowercase().contains(qLower)) {
                    out += LogMatch(pid, buildId, lineNo, line.take(400))
                }
            }
        }
    }.onFailure { log.debug(it) { "grep failed $path" } }
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun highlight(line: String, q: String): String {
    val safe = esc(line)
    if (q.isBlank()) return safe
    val rx = Regex(Regex.escape(esc(q)), RegexOption.IGNORE_CASE)
    return rx.replace(safe) { """<mark style="background:#facc15;color:#111">${it.value}</mark>""" }
}

private fun renderPage(
    username: String,
    csrf: String?,
    q: String?,
    projectFilter: String?,
    matches: List<LogMatch>,
): String {
    val rowsHtml = if (matches.isEmpty() && q != null) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">"${esc(q)}" 에 매치되는 줄이 없습니다 (각 빌드 로그 마지막 2 MB 안에서).</td></tr>"""
    } else if (matches.isEmpty()) {
        """<tr><td colspan="4" class="dim" style="text-align:center;padding:14px">검색어를 입력하면 모든 빌드 로그를 가로질러 grep 합니다.</td></tr>"""
    } else {
        matches.joinToString("") { m ->
            """<tr>
              <td><a href="/projects/${esc(m.projectId)}/builds/${esc(m.buildId)}"><code style="font-size:11px">${esc(m.projectId.take(20))}/${esc(m.buildId.take(8))}</code></a></td>
              <td class="dim" style="text-align:right;font-size:11px">L${m.lineNumber}</td>
              <td><pre style="margin:0;font-size:12px;white-space:pre-wrap;word-break:break-word;max-width:900px">${highlight(m.line, q ?: "")}</pre></td>
            </tr>"""
        }
    }

    return AdminTemplates.shell(
        title = "로그 검색",
        username = username,
        currentPath = "/logs",
        csrf = csrf,
        body = """
<header>
  <h1>로그 검색 <small class="dim" style="font-size:14px;font-weight:400">v0.32.0 — 모든 빌드 로그</small></h1>
</header>

<form method="get" action="/logs" class="card" style="margin-bottom:14px;display:grid;grid-template-columns:1fr 200px auto;gap:8px;align-items:end">
  <label style="margin:0">검색어 (대소문자 무시)
    <input type="text" name="q" value="${esc(q)}" placeholder="FAILED / OutOfMemory / lint ..." autofocus>
  </label>
  <label style="margin:0">프로젝트 필터 (선택)
    <input type="text" name="project" value="${esc(projectFilter)}" placeholder="my-app">
  </label>
  <div>
    <button type="submit" class="primary" style="padding:8px 14px">검색</button>
  </div>
</form>

<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
  <small class="dim">${if (q == null) "" else "Top ${matches.size} 매치 (cap=$MAX_MATCHES). 각 빌드 로그 마지막 2 MB 만 scan."}</small>
</div>

<table class="devices">
  <thead><tr>
    <th style="width:280px">Project / Build</th>
    <th style="width:50px">L#</th>
    <th>Match</th>
  </tr></thead>
  <tbody>$rowsHtml</tbody>
</table>

<p class="hint" style="margin-top:14px;font-size:12px">
  Server stdout 로그는 <code>docker logs vibe-coder-server</code> 로 확인. 본 페이지는 빌드 로그
  (`.vibecoder/&lt;projectId&gt;/logs/&lt;buildId&gt;.log`) 만. 대화 검색은 <a href="/history">/history</a>.
</p>
"""
    )
}
