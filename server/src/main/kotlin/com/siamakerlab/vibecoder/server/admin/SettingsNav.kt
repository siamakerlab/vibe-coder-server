package com.siamakerlab.vibecoder.server.admin

/**
 * v0.69.0 — Phase 48 UI 리뉴얼.
 *
 * 사이드바를 24개 평탄 메뉴에서 6개 top-level 로 압축한 후, 설정 페이지 안에
 * 8개 탭으로 묶기 위한 helper. AdminTemplates.kt 와 별도 파일로 분리한 이유:
 * AdminTemplates 가 매우 큰 raw-string-heavy 파일이라 Kotlin K2 parser 가
 * fragile — 신규 코드 추가 시 brace 매칭 에러 frequent. 별도 파일은 영향 없음.
 *
 * 사용처:
 *  - [AdminTemplates.shell] 가 currentPath 보고 settings 카테고리면 자동 inject.
 *  - 각 sub-page 의 body 는 그대로 — top 의 탭바만 추가.
 */
internal object SettingsNav {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    /**
     * currentPath -> top-level nav key 매핑.
     *
     * - dashboard: /
     * - projects:  /projects*
     * - chat:      /chat*
     * - tools:     /tools, /multi-console, /emulator, /logs, /code-search, /history
     * - terminal:  /terminal  (v1.27.0 — 글로벌 사이드바 메뉴로 분리, workspace 시작)
     * - settings:  나머지 admin sub-page 모두 (settings/password/2fa/webauthn/devices/
     *              env-setup/backup/usage/audit/users/prompts/agents)
     */
    fun topLevelOf(currentPath: String): String {
        val p = currentPath.ifBlank { "/" }
        return when {
            p == "/" -> "dashboard"
            p.startsWith("/projects") -> "projects"
            p.startsWith("/chat") -> "chat"
            p == "/tools" || p.startsWith("/tools/") -> "tools"
            p.startsWith("/multi-console") -> "tools"
            p.startsWith("/emulator") -> "tools"
            p == "/logs" || p.startsWith("/logs/") -> "tools"
            p.startsWith("/code-search") -> "tools"
            p == "/history" || p.startsWith("/history/") -> "tools"
            // v1.27.0 — 글로벌 사이드바 메뉴. 기존 /settings/terminal 진입은 라우터
            // 단에서 301 → /terminal 로 redirect (호환). 여기선 두 경로 모두 active.
            p == "/terminal" || p.startsWith("/terminal/") -> "terminal"
            p.startsWith("/settings/terminal") -> "terminal"
            p.startsWith("/settings/ssh-key") -> "settings"
            p.startsWith("/settings/keystores") -> "settings"
            p.startsWith("/settings") -> "settings"
            p.startsWith("/password") -> "settings"
            p.startsWith("/2fa") -> "settings"
            p.startsWith("/webauthn") -> "settings"
            p.startsWith("/devices") -> "settings"
            p.startsWith("/env-setup") -> "settings"
            p.startsWith("/backup") -> "settings"
            p.startsWith("/usage") -> "settings"
            p.startsWith("/audit") -> "settings"
            p.startsWith("/users") -> "settings"
            p.startsWith("/prompts") -> "settings"
            p.startsWith("/agents") -> "settings"
            else -> "dashboard"
        }
    }

    /**
     * 설정 페이지 8개 탭 — 각 sub-page 상단에 inject.
     *
     * v0.77.0 — i18n. [lang] 으로 라벨 분기. 호출자가 WebSession.language 전달.
     */
    fun tabBar(currentPath: String, lang: String): String {
        val tab = tabOf(currentPath)
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val tabs = listOf(
            Triple("general", t("settings.tab.general"), "/settings"),
            Triple("security", t("settings.tab.security"), "/password"),
            Triple("notifications", t("settings.tab.notifications"), "/settings/email"),
            Triple("build-env", t("settings.tab.buildEnv"), "/env-setup"),
            Triple("prompts", t("settings.tab.prompts"), "/prompts"),
            Triple("backup", t("settings.tab.backup"), "/backup"),
            Triple("monitoring", t("settings.tab.monitoring"), "/usage"),
            Triple("users", t("settings.tab.users"), "/users"),
        )
        val items = tabs.joinToString("\n") { (key, label, href) ->
            val cls = if (key == tab) "tab active" else "tab"
            "<a href=\"" + esc(href) + "\" class=\"" + esc(cls) + "\">" + esc(label) + "</a>"
        }
        return TAB_BAR_PREFIX + items + TAB_BAR_SUFFIX
    }

    /** currentPath -> settings 탭 key. */
    private fun tabOf(currentPath: String): String {
        val p = currentPath
        return when {
            p == "/settings" -> "general"
            p.startsWith("/password") -> "security"
            p.startsWith("/2fa") -> "security"
            p.startsWith("/webauthn") -> "security"
            p.startsWith("/devices") -> "security"
            p == "/settings/cors" -> "security"
            p == "/settings/email" -> "notifications"
            p == "/settings/webhook" -> "notifications"
            p == "/settings/push" -> "notifications"
            p.startsWith("/env-setup") -> "build-env"
            p == "/settings/git-integrations" -> "build-env"
            p == "/settings/cache" -> "build-env"
            p == "/settings/ssh-key" -> "build-env"
            p.startsWith("/settings/keystores") -> "build-env"
            // v1.27.0 — /settings/terminal 은 /terminal 로 redirect. settings 탭바
            // 안에선 더 이상 active 가 아니지만 legacy 진입자가 도달할 수 있으므로
            // 매핑은 보존 (어차피 redirect 가 먼저 일어남).
            p.startsWith("/settings/terminal") -> "build-env"
            p.startsWith("/prompts") -> "prompts"
            p.startsWith("/agents") -> "prompts"
            p.startsWith("/backup") -> "backup"
            p.startsWith("/usage") -> "monitoring"
            p.startsWith("/audit") -> "monitoring"
            p.startsWith("/users") -> "users"
            else -> "general"
        }
    }

    // v0.69.1 — CSS 는 admin.css 의 .settings-tabs 로 이동. HTML wrapper 만 emit.
    private const val TAB_BAR_PREFIX = "<div class=\"settings-tabs\">"
    private const val TAB_BAR_SUFFIX = "</div>"

    /**
     * v1.31.3 — 카테고리별 sub-page chip sub-nav. 각 카테고리 대표 페이지(보안 /password,
     * 알림 /settings/email, 모니터링 /usage) 상단에 배치해 같은 카테고리 sub-page 로
     * 이동. 이전엔 일반설정(/settings)의 quicklinks 가 모든 sub-page 를 평면 중복
     * 나열했으나(8탭 카테고리 구조와 충돌), 카테고리별로 분산. 빌드환경(env-setup)/
     * 프롬프트(prompts)는 각 페이지가 자체 chip 을 이미 보유.
     */
    fun categoryNav(currentPath: String, lang: String): String {
        val t = { key: String -> com.siamakerlab.vibecoder.server.i18n.Messages.t(lang, key) }
        val subs: List<Pair<String, String>> = when (tabOf(currentPath)) {
            "security" -> listOf(
                "/password" to "settings.cat.password",
                "/2fa" to "settings.quicklinks.twoFa",
                "/webauthn" to "settings.quicklinks.webauthn",
                "/devices" to "settings.quicklinks.devices",
                "/settings/cors" to "settings.quicklinks.cors",
            )
            "notifications" -> listOf(
                "/settings/email" to "settings.quicklinks.email",
                "/settings/webhook" to "settings.quicklinks.webhook",
                "/settings/push" to "settings.quicklinks.push",
            )
            "monitoring" -> listOf(
                "/usage" to "settings.cat.usage",
                "/audit" to "settings.cat.audit",
            )
            else -> emptyList()
        }
        if (subs.isEmpty()) return ""
        val pathNoQuery = currentPath.substringBefore('?')
        val items = subs.joinToString("") { (href, key) ->
            // v1.33.2 (17차 Q-1) — query string 동반 경로(`/usage?range=7d`)에서도 active.
            val active = if (href == pathNoQuery) " active" else ""
            "<a href=\"" + esc(href) + "\" class=\"chip chip-link" + active + "\">" + esc(t(key)) + "</a>"
        }
        return "<div class=\"settings-subnav\" style=\"display:flex;flex-wrap:wrap;gap:8px;margin-bottom:14px\">" + items + "</div>"
    }
}
