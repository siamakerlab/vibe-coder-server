package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.projects.ProjectArchiveService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.34.0 — `/backup` — 워크스페이스 전체 tar.gz 백업.
 *
 *   GET /backup           — 페이지 (디렉토리 별 size + 다운로드 버튼)
 *   GET /backup/download  — tar.gz stream
 *
 * 큰 트리도 메모리 폭발 없이 stream. PostgreSQL 데이터는 함께 들어가지
 * 않음 (running PG 의 raw data dir 을 tar 로 떠도 동기화 문제 발생) —
 * 대신 페이지 안에서 `pg_dump` 명령 가이드.
 *
 * 제외:
 *   - `.vibecoder/<projectId>/logs/` — 빌드 로그는 보통 거대.
 *   - `dev-tools/gradle/caches/` — 재다운로드 가능.
 *   - `dev-tools/npm-cache/`, `dev-tools/playwright/` 도 동일 이유.
 *
 * 위 정책 덕분에 일반 백업이 GB → 수십 MB 로 줄어든다.
 */
fun Routing.backupRoutes(
    authDeps: AdminRoutesDeps,
    workspace: WorkspacePath,
    /** v0.60.0 — Phase 39 BackupService (수동 download + 자동 목록 + rotation). */
    service: BackupService,
    /** v1.132.0 — 프로젝트 백업 파일(.tar.gz) 업로드 복원. */
    archive: ProjectArchiveService,
) {
    get("/backup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        // v1.137.1 — measureSubdirs 는 워크스페이스 전체 walk(운영 실측 cold ~10초)라
        // 페이지 로드를 막지 않게 캐시 + 백그라운드 측정으로 분리(stale-while-revalidate).
        val sizes = SubdirSizeCache.getAndRefresh(workspace.root)
        val autoBackups = service.listAutoBackups()
        call.respondText(
            renderPage(
                sess.username, sess.csrf, sizes, autoBackups, authDeps.config.backup, sess.language,
                ok = call.request.queryParameters["ok"], err = call.request.queryParameters["err"],
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // v1.132.0 — 프로젝트 백업(.tar.gz) 업로드 → 복원(다른 서버 이전). id/폴더/키스토어 충돌 시 거부.
    post("/backup/project-restore") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        Files.createDirectories(workspace.archivesDir())
        val tmp = Files.createTempFile(workspace.archivesDir(), ".upload-restore-", ".tar.gz")
        var received = false
        try {
            val multipart = call.receiveMultipart()
            while (true) {
                val part = multipart.readPart() ?: break
                if (part is PartData.FileItem) {
                    part.provider().toInputStream().use { ins ->
                        Files.copy(ins, tmp, StandardCopyOption.REPLACE_EXISTING)
                    }
                    received = true
                }
                part.dispose()
            }
            if (!received || Files.size(tmp) <= 0L) {
                call.respondRedirect("/backup?err=${enc(Messages.t(sess.language, "backup.restore.proj.fail"))}")
                return@post
            }
            val id = archive.restoreFromTar(tmp)
            log.info { "project restored from upload by ${sess.username}: $id" }
            call.respondRedirect("/backup?ok=${enc(Messages.t(sess.language, "backup.restore.proj.ok"))}")
        } catch (e: Throwable) {
            log.warn(e) { "project restore failed" }
            call.respondRedirect("/backup?err=${enc("${Messages.t(sess.language, "backup.restore.proj.fail")}: ${e.message ?: "error"}")}")
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    get("/backup/download") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${service.downloadFileName()}\"")
        call.respondOutputStream(ContentType.parse("application/gzip")) {
            runCatching { service.streamTarGz(this) }
                .onFailure { log.warn(it) { "backup stream failed: ${it.message}" } }
            log.info { "workspace backup downloaded by ${sess.username}" }
        }
    }

    // v0.60.0 — Phase 39 자동 backup 파일 다운로드.
    get("/backup/auto/{name}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val name = call.parameters["name"]
            ?: return@get call.respondText("missing name", status = io.ktor.http.HttpStatusCode.BadRequest)
        val path = service.resolveAutoBackupForDownload(name)
            ?: return@get call.respondText("not found", status = io.ktor.http.HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
        call.respondFile(path.toFile())
    }

    // v0.60.0 — Phase 39 자동 backup 삭제.
    post("/backup/auto/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val name = call.parameters["name"]
            ?: return@post call.respondRedirect("/backup?err=missing_name")
        val ok = service.deleteAutoBackup(name)
        call.respondRedirect("/backup?${if (ok) "ok=deleted" else "err=not_found"}")
    }

    // v0.60.0 — Phase 39 수동 즉시 백업 트리거 (스케줄 없이 한 번).
    post("/backup/auto/run-now") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        com.siamakerlab.vibecoder.server.auth.CsrfTokens.verifyCsrfFromQueryOrHeader(call)
        val ok = runCatching {
            service.createScheduled()
            service.deleteOldestOverRetention(authDeps.config.backup.retentionCount.coerceAtLeast(1))
        }.isSuccess
        call.respondRedirect("/backup?${if (ok) "ok=created" else "err=failed"}")
    }
}

// v0.60.0 — Phase 39 walk / exclusion 로직은 BackupService 로 이전됨.

private data class SubdirSize(val name: String, val bytes: Long)

/**
 * v1.137.1 — 디렉토리 용량 측정 캐시 (TTL 10분, stale-while-revalidate).
 *
 * 종전엔 `/backup` GET 마다 [measureSubdirs] 가 워크스페이스 전체를 동기 walk 해
 * cold ~10초가 걸렸고, 설정 통합 탭이 이 페이지를 iframe 으로 함께 로드해 설정 진입을
 * 끌어내렸다. 이제 캐시본을 즉시 렌더하고(없으면 "측정 중" 표시 + 자동 새로고침),
 * 부재/만료 시 백그라운드 스레드 1개만 재측정한다.
 */
private object SubdirSizeCache {
    private const val TTL_MS = 10 * 60_000L

    @Volatile
    private var snapshot: Pair<Long, List<SubdirSize>>? = null
    private val computing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 현재 캐시(없으면 null) 반환 + 부재/만료 시 백그라운드 재측정 기동(중복 방지). */
    fun getAndRefresh(root: Path): List<SubdirSize>? {
        val snap = snapshot
        val fresh = snap != null && System.currentTimeMillis() - snap.first < TTL_MS
        if (!fresh && computing.compareAndSet(false, true)) {
            Thread({
                try {
                    snapshot = System.currentTimeMillis() to measureSubdirs(root)
                } finally {
                    computing.set(false)
                }
            }, "backup-sizes-measure").apply { isDaemon = true }.start()
        }
        return snap?.second
    }
}

private fun measureSubdirs(root: Path): List<SubdirSize> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.list(root).use { stream ->
        stream.filter { Files.isDirectory(it) }.map { dir ->
            val size = runCatching {
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) }
                        .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
                }
            }.getOrDefault(0L)
            SubdirSize(dir.name, size)
        }.toList().sortedByDescending { it.bytes }
    }
}

private fun humanBytes(b: Long): String {
    if (b < 1024) return "${b}B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return "%.1f%s".format(v, units[i])
}

private fun esc(s: String?): String =
    s.orEmpty()
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

private fun renderPage(
    username: String,
    csrf: String?,
    sizes: List<SubdirSize>?,
    autoBackups: List<BackupService.AutoBackupEntry> = emptyList(),
    backupCfg: com.siamakerlab.vibecoder.server.config.BackupSection? = null,
    lang: String,
    ok: String? = null,
    err: String? = null,
    embed: Boolean = false,
): String {
    val t = { key: String -> Messages.t(lang, key) }
    // v1.137.1 — sizes == null 이면 백그라운드 측정 중(첫 방문). "측정 중" 표시 + 자동 새로고침.
    val measuring = sizes == null
    val total = (sizes ?: emptyList()).sumOf { it.bytes }
    val flash = buildString {
        if (!ok.isNullOrBlank()) append("""<div class="flash ok">${esc(ok)}</div>""")
        if (!err.isNullOrBlank()) append("""<div class="flash err">${esc(err)}</div>""")
    }
    val rowsHtml = if (measuring)
        """<tr><td colspan="2" class="dim">${esc(t("backup.sizes.measuring"))}</td></tr>"""
    else (sizes ?: emptyList()).joinToString("") { s ->
        val excluded = s.name in setOf("postgres", "dev-tools") || s.name == ".vibecoder"
        val note = when (s.name) {
            "postgres" -> " <small class=\"dim\">${esc(t("backup.row.pgExcluded"))}</small>"
            "dev-tools" -> " <small class=\"dim\">${esc(t("backup.row.toolsExcluded"))}</small>"
            else -> ""
        }
        """<tr><td><code>${esc(s.name)}/</code>$note</td><td style="text-align:right">${humanBytes(s.bytes)}</td></tr>"""
    }
    val runConfirm = t("backup.auto.runConfirm").replace("'", "\\'")
    val delConfirm = t("backup.auto.delConfirm").replace("'", "\\'")
    return AdminTemplates.shell(
        title = t("backup.title"),
        username = username,
        currentPath = "/backup",
        csrf = csrf,
        lang = lang,
        body = """
<header>
  <h1>${esc(t("backup.heading"))} <small class="dim" style="font-size:14px;font-weight:400"></small></h1>
</header>
$flash

<div class="card" style="margin-top:0;margin-bottom:14px;background:rgba(124,58,237,0.06)">
  <h2 style="margin-top:0">${esc(t("backup.restore.proj.title"))}</h2>
  <p>${esc(t("backup.restore.proj.desc"))}</p>
  <form method="post" action="/backup/project-restore?_csrf=${esc(csrf ?: "")}" enctype="multipart/form-data"
        style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
    <input type="file" name="backup" accept=".gz,.tgz,application/gzip,application/x-gzip" required>
    <button type="submit" class="primary" style="padding:8px 16px">${esc(t("backup.restore.proj.btn"))}</button>
  </form>
  <p class="hint" style="margin-top:8px">${esc(t("backup.restore.proj.hint"))}</p>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(Messages.t(lang, "backup.currentSize", if (measuring) t("backup.sizes.measuring") else humanBytes(total)))}</h2>
  ${if (measuring) """<script>setTimeout(function () { location.reload(); }, 2500);</script>""" else ""}
  <table class="devices" style="margin:0">
    <thead><tr><th>${esc(t("backup.col.directory"))}</th><th style="text-align:right">${esc(t("backup.col.size"))}</th></tr></thead>
    <tbody>$rowsHtml</tbody>
  </table>
</div>

<div class="card">
  <h2 style="margin-top:0">${esc(t("backup.download.title"))}</h2>
  <p>${esc(t("backup.download.desc"))}</p>
  <p><a href="/backup/download" class="primary" style="display:inline-block;padding:10px 18px;text-decoration:none">⬇ vibe-workspace-&lt;timestamp&gt;.tar.gz</a></p>
  <p class="hint" style="margin-top:8px">${t("backup.download.hint")}</p>
</div>

<div class="card" style="margin-top:14px">
  <h2 style="margin-top:0">${esc(t("backup.auto.title"))}</h2>
  ${if (backupCfg == null || !backupCfg.enabled) """
  <p>${t("backup.auto.disabledMsg")}</p>
  <pre class="diff-block">backup:
  enabled: true
  cron: "03:00"${esc(t("backup.auto.cronComment"))}
  retentionCount: 7${esc(t("backup.auto.retentionComment"))}</pre>
  """ else """
  <p>${Messages.t(lang, "backup.auto.enabledMsg", esc(backupCfg.cron), backupCfg.retentionCount)}</p>
  """}
  <form method="post" action="/backup/auto/run-now?_csrf=${esc(csrf ?: "")}" style="margin-bottom:10px">
    <button type="submit" class="chip chip-link" onclick="return confirm('$runConfirm')">${esc(t("backup.auto.runBtn"))}</button>
  </form>
  ${if (autoBackups.isEmpty()) """
  <p class="dim" style="font-size:12px">${esc(t("backup.auto.noFiles"))}</p>
  """ else """
  <table class="devices" style="margin:0">
    <thead><tr><th>${esc(t("backup.auto.col.file"))}</th><th style="text-align:right">${esc(t("backup.col.size"))}</th><th>${esc(t("backup.auto.col.time"))}</th><th></th></tr></thead>
    <tbody>
      ${autoBackups.joinToString("") { entry ->
        """<tr>
          <td><code>${esc(entry.fileName)}</code></td>
          <td style="text-align:right">${humanBytes(entry.sizeBytes)}</td>
          <td class="dim" style="font-size:11px">${esc(AdminTemplates.fmtTsEpochMs(entry.createdAtMs, lang))}</td>
          <td>
            <a href="/backup/auto/${esc(entry.fileName)}" class="chip chip-link" style="font-size:11px">⬇</a>
            <form method="post" action="/backup/auto/${esc(entry.fileName)}/delete?_csrf=${esc(csrf ?: "")}" style="display:inline" onsubmit="return confirm('$delConfirm')">
              <button type="submit" class="chip chip-danger" style="font-size:11px">${esc(t("backup.auto.delBtn"))}</button>
            </form>
          </td>
        </tr>"""
      }}
    </tbody>
  </table>
  """}
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.06)">
  <h2 style="margin-top:0">${esc(t("backup.pg.title"))}</h2>
  <p>${esc(t("backup.pg.desc"))}</p>
  <pre class="diff-block">docker exec vibe-coder-postgres \\
  pg_dump -U vibecoder -F c vibecoder \\
  > vibe-pg-${'$'}(date +%F).pgdump</pre>
  <p class="hint">${t("backup.pg.restoreHint")}</p>
</div>

<div class="card" style="margin-top:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0">${esc(t("backup.restore.title"))}</h2>
  <p>${esc(t("backup.restore.desc"))}</p>
  <pre class="diff-block">mkdir -p vibe-coder/vibe-coder-data
cd vibe-coder
tar xzf vibe-workspace-YYYYMMDD-HHmm.tar.gz -C vibe-coder-data/
${esc(t("backup.restore.bashComment"))}
docker compose up -d</pre>
  <p class="hint">${esc(t("backup.restore.hint"))}</p>
</div>
""",
        embed = embed,
    )
}
