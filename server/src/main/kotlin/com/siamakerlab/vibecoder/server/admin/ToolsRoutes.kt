package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.i18n.Messages
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * v0.69.0 — Phase 48. `/tools` hub — UI 리뉴얼의 일부.
 *
 * 기존 사이드바에 평탄하게 나열돼 있던 "보조 도구" 들을 한 페이지에 묶음:
 *   - Multi-console (여러 프로젝트 콘솔 동시 보기)
 *   - 코드 검색 (cross-project file content search)
 *   - 빌드 로그 검색 (cross-project build logs grep)
 *   - 대화 검색 (cross-project conversation history search)
 *
 * 각 도구의 기능 자체는 그대로 유지 — 이 hub 는 단순 카드 grid 진입점.
 *
 * 보안: cookie 세션 — `requireSessionOrRedirect` 통과 필요.
 *
 * v0.87.0 Phase 64.11 — 모든 사용자 가시 한국어 i18n 키화 (tools.*).
 */
fun Routing.toolsRoutes(authDeps: AdminRoutesDeps) {

    // v1.23.0 — 도구 통합 탭 페이지. 사이드바 "도구" link 가 가리키는 진입점.
    // /tools, /multi-console, /code-search, /logs, /history 를 iframe
    // prerender. SettingsTabsTemplate / ProjectTabsTemplate 와 일관 디자인.
    get("/tools/tabs") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        call.respondText(
            ToolsTabsTemplate.page(sess.username, csrf = sess.csrf, lang = sess.language),
            ContentType.Text.Html,
        )
    }

    get("/tools") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val lang = sess.language
        val t = { key: String -> Messages.t(lang, key) }
        val body = """
<h1 style="margin-bottom:16px">${t("tools.heading")} <small class="dim" style="font-size:14px;font-weight:400"></small></h1>
<p class="dim" style="margin-bottom:24px">
  ${t("tools.intro")}
</p>

<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px">
  ${toolCard("Multi-console", t("tools.card.multiConsole.desc"), "/multi-console", "📺")}
  ${toolCard(t("tools.card.adb.title"), t("tools.card.adb.desc"), "/adb", "📱")}
  ${toolCard(t("tools.card.codeSearch.title"), t("tools.card.codeSearch.desc"), "/code-search", "🔎")}
  ${toolCard(t("tools.card.buildLogs.title"), t("tools.card.buildLogs.desc"), "/logs", "📜")}
  ${toolCard(t("tools.card.history.title"), t("tools.card.history.desc"), "/history", "💬")}
</div>

<hr style="margin:28px 0;border:none;border-top:1px solid var(--border, #2a2f3a)">

<h2 style="font-size:16px;margin-bottom:12px">${t("tools.tips.title")}</h2>
<ul class="dim" style="font-size:13px;line-height:1.6">
  <li>${t("tools.tips.item1")}</li>
  <li>${t("tools.tips.item2")}</li>
</ul>
""".trimIndent()
        call.respondText(
            AdminTemplates.shell(
                title = t("tools.heading"),
                username = sess.username,
                currentPath = "/tools",
                csrf = sess.csrf,
                lang = lang,
                body = body,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }
}

private fun toolCard(title: String, desc: String, href: String, icon: String): String =
    """
<a href="$href" class="card" style="text-decoration:none;color:inherit;display:block;
   padding:18px;border-radius:10px;border:1px solid var(--border, #2a2f3a);
   transition:border-color 0.15s,background 0.15s;cursor:pointer"
   onmouseover="this.style.borderColor='var(--primary, #facc15)'"
   onmouseout="this.style.borderColor='var(--border, #2a2f3a)'">
  <div style="font-size:24px;margin-bottom:8px">$icon</div>
  <div style="font-weight:600;margin-bottom:6px">$title</div>
  <div class="dim" style="font-size:12px;line-height:1.5">$desc</div>
</a>
""".trimIndent()
