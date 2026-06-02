package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.SettingsNav
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * v1.89.0 — `/settings/notifications` — 알림 종류(kind)별 수신 on/off.
 *
 * 끈 kind 는 [NotificationService.emit] 이 적재를 skip ([NotificationPrefsStore]).
 * 저장은 즉시 반영(재시작 불요). 알림 카테고리(이메일/Webhook 옆)에 배치.
 */
fun Routing.notificationSettingsRoutes(authDeps: AdminRoutesDeps, prefs: NotificationPrefsStore) {
    get("/settings/notifications") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val ok = call.request.queryParameters["ok"] != null
        call.respondText(
            NotificationSettingsTemplates.page(
                sess.username, prefs.currentEnabled(), ok, sess.csrf, sess.language, call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }
    post("/settings/notifications") {
        requireSessionOrRedirect(authDeps) ?: return@post
        val params = requireCsrf()
        // 체크된 kind 만 전송됨 → 그 집합을 enabled 로 저장(나머지 known kind 는 off).
        val checked = params.getAll("kind")?.toSet() ?: emptySet()
        prefs.saveEnabled(checked)
        call.respondRedirect("/settings/notifications?ok=1")
    }
}

private object NotificationSettingsTemplates {
    private fun esc(s: String?): String =
        s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** kind → (라벨 ko/en, 설명 ko/en). */
    private fun labels(lang: String): Map<String, Pair<String, String>> {
        val ko = lang == "ko"
        return linkedMapOf(
            "build.success" to ((if (ko) "빌드 완료" else "Build succeeded") to (if (ko) "Gradle 빌드가 성공했을 때" else "When a Gradle build succeeds")),
            "build.failed" to ((if (ko) "빌드 오류" else "Build failed") to (if (ko) "빌드가 실패했을 때" else "When a build fails")),
            "claude.turn_done" to ((if (ko) "작업 완료" else "Task done") to (if (ko) "콘솔 작업(turn)이 끝났을 때" else "When a console turn completes")),
            "claude.stopped" to ((if (ko) "작업 중지됨" else "Task stopped") to (if (ko) "작업을 사용자가 중지했을 때" else "When a task is cancelled")),
            "claude.error" to ((if (ko) "오류" else "Error") to (if (ko) "Claude 프로세스 비정상 종료" else "When the Claude process crashes")),
            "usage.threshold" to ((if (ko) "사용량 임계치" else "Usage threshold") to (if (ko) "Claude 사용량이 임계치에 도달" else "When Claude usage hits the threshold")),
            "system" to ((if (ko) "시스템" else "System") to (if (ko) "일반/시스템 알림" else "General / system notices")),
        )
    }

    fun page(
        username: String,
        enabled: Map<String, Boolean>,
        ok: Boolean,
        csrf: String?,
        lang: String,
        embed: Boolean,
    ): String {
        val ko = lang == "ko"
        val title = if (ko) "알림 설정" else "Notifications"
        val intro = if (ko) "받을 알림 종류를 선택하세요. 끈 항목은 벨/목록에 더 이상 쌓이지 않습니다 (즉시 적용)."
        else "Choose which notifications to receive. Unchecked kinds stop accruing in the bell (applies immediately)."
        val saveLabel = if (ko) "저장" else "Save"
        val okHtml = if (ok) """<div class="ok-banner">✓ ${if (ko) "저장되었습니다." else "Saved."}</div>""" else ""

        val rows = labels(lang).entries.joinToString("\n") { (kind, lbl) ->
            val on = enabled[kind] != false
            """
    <label class="card" style="display:flex;align-items:flex-start;gap:10px;padding:12px 14px;margin-bottom:8px;cursor:pointer">
      <input type="checkbox" name="kind" value="${esc(kind)}" ${if (on) "checked" else ""} style="margin-top:3px">
      <span>
        <span style="font-weight:600">${esc(lbl.first)}</span>
        <span class="dim" style="display:block;font-size:12px">${esc(lbl.second)}</span>
      </span>
    </label>"""
        }

        return AdminTemplates.shell(
            title = title,
            username = username,
            currentPath = "/settings/notifications",
            csrf = csrf,
            body = """
${SettingsNav.categoryNav("/settings/notifications", lang)}
<header><h1>$title</h1></header>
$okHtml
<p class="hint" style="margin-bottom:14px">${esc(intro)}</p>
<form method="post" action="/settings/notifications">
  ${CsrfTokens.hiddenInput(csrf)}
$rows
  <div style="margin-top:14px"><button type="submit" class="primary">${esc(saveLabel)}</button></div>
</form>
""",
            lang = lang,
            embed = embed,
        )
    }
}
