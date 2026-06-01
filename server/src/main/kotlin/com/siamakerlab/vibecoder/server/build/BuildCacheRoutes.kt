package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireAdminOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
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

private val log = KotlinLogging.logger {}

/**
 * v0.28.0 — `/settings/cache` — Gradle / Android / npm 캐시 크기 + 정리.
 *
 * 빌드 진행 중에는 cleanup 가 위험 — UI 가 `runningBuilds > 0` 이면 정리 버튼
 * disabled. 실제 cleanup 은 사용자가 명시 클릭 + CSRF 검증.
 */
fun Routing.buildCacheRoutes(authDeps: AdminRoutesDeps, svc: BuildCacheService) {
    get("/settings/cache") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        if (!requireAdminOrRedirect(sess)) return@get
        val sizes = svc.measure()
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(
            BuildCacheTemplates.page(sess.username, sizes, sess.csrf, ok, err, lang = sess.language, embed = call.isEmbeddedRequest()),
            ContentType.Text.Html,
        )
    }

    post("/settings/cache/cleanup") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireAdminOrRedirect(sess)) return@post
        requireCsrf()
        val params = call.receiveParameters()
        val raw = params["target"]?.uppercase()?.replace("-", "_") ?: ""
        val target = runCatching { BuildCacheService.Target.valueOf(raw) }.getOrNull()
            ?: run {
                call.respondRedirect("/settings/cache?err=${enc("알 수 없는 target: $raw")}")
                return@post
            }
        val r = svc.cleanup(target)
        log.info { "cache cleanup $target by ${sess.username}: files=${r.deletedFiles} freed=${r.freedBytes}" }
        val msg = if (r.errorMessage != null) {
            "err=${enc("[$target] cleanup 실패: ${r.errorMessage}")}"
        } else {
            "ok=${enc("[$target] 파일 ${r.deletedFiles}개 / ${humanBytes(r.freedBytes)} 정리")}"
        }
        call.respondRedirect("/settings/cache?$msg")
    }
}

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

internal fun humanBytes(b: Long): String {
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

private object BuildCacheTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        sizes: BuildCacheService.CacheSize,
        csrf: String?,
        ok: String?,
        err: String?,

        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">${esc(it)}</div>""" } ?: ""

        fun row(label: String, bytes: Long, target: BuildCacheService.Target, hint: String): String {
            val disabled = if (bytes == 0L) "disabled" else ""
            return """
            <tr>
              <td><strong>${esc(label)}</strong><br><small class="dim">${esc(hint)}</small></td>
              <td style="text-align:right"><code>${humanBytes(bytes)}</code></td>
              <td style="text-align:right">
                <form method="post" action="/settings/cache/cleanup" style="display:inline">
                  ${CsrfTokens.hiddenInput(csrf)}
                  <input type="hidden" name="target" value="${target.name}">
                  <button type="submit" class="chip chip-danger" $disabled
                    onclick="return confirm('${esc(label)} 캐시를 비우시겠습니까? 다음 빌드에서 재다운로드 발생합니다.')">정리</button>
                </form>
              </td>
            </tr>"""
        }

        return AdminTemplates.shell(
            title = "빌드 캐시 관리",
            username = username,
            currentPath = "/settings/cache",
            csrf = csrf,
            embed = embed,
            body = """
<header>
  <h1>빌드 캐시 관리</h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    Gradle / Android SDK / npm 의 캐시 디렉토리 크기 + 비우기. 빌드 진행 중에는
    cleanup 금지 (race risk) — 빌드 페이지에서 모든 빌드 종료 확인 후 사용.
  </p>
</header>

$okHtml
$errHtml

<div class="card">
  <table style="width:100%">
    <thead>
      <tr><th>디렉토리</th><th style="text-align:right">크기</th><th style="text-align:right">동작</th></tr>
    </thead>
    <tbody>
      ${row("Gradle caches", sizes.gradleCachesBytes, BuildCacheService.Target.GRADLE_CACHES, "~/.gradle/caches — 의존성 jar / Maven local")}
      ${row("Gradle daemon", sizes.gradleDaemonBytes, BuildCacheService.Target.GRADLE_DAEMON, "~/.gradle/daemon — JVM warm pool / 로그")}
      ${row("Android cache", sizes.androidCacheBytes, BuildCacheService.Target.ANDROID_CACHE, "~/.android/cache — SDK manager / AVD 캐시")}
      ${row("npm cache", sizes.npmCacheBytes, BuildCacheService.Target.NPM_CACHE, "~/.npm/_cacache — MCP 등 npm 패키지 캐시")}
    </tbody>
    <tfoot>
      <tr><td><strong>총합</strong></td><td style="text-align:right"><code>${humanBytes(sizes.totalBytes)}</code></td><td></td></tr>
    </tfoot>
  </table>
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">주의 사항</h2>
  <ul style="font-size:13px;line-height:1.7">
    <li>Cleanup 은 디렉토리 안의 모든 파일을 삭제합니다 (디렉토리 자체는 유지).
      다음 빌드가 의존성을 재다운로드해 한참 느려질 수 있음.</li>
    <li>빌드 진행 중 cleanup → Gradle 데몬이 file lock 을 잡고 있어 일부 파일은
      skip 됨 (graceful).</li>
    <li>측정은 walk + size 합산이라 큰 트리에서 수초 소요될 수 있음.</li>
    <li>이 페이지는 호스트 디스크 임계치 알림 (이메일 / webhook) 과 별개. 임계치
      알림은 자동, 본 페이지는 수동 정리.</li>
  </ul>
</div>
""",
            lang = lang,
        )
    }
}
