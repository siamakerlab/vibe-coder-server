package com.siamakerlab.vibecoder.server.i18n

/**
 * v0.77.0 — Phase 64 i18n. English bundle (default).
 *
 * Key 컨벤션:
 *   common.*           재사용 단어 (save, cancel, delete, back, …)
 *   nav.*              상단 nav + Settings 8 탭
 *   home.*             dashboard
 *   projects.*         project list / register / detail
 *   console.*          console (prompt / messages)
 *   builds.*           build list / detail
 *   git.*              git status / commit / log
 *   files.*            file browser
 *   env.*              environment setup (SDK / Claude / MCP / Git)
 *   claude.*           Claude auth (oauth / file / api key)
 *   mcp.*              MCP catalog
 *   gitint.*           Git integrations (PAT / SSH)
 *   settings.*         settings (account / password / language / cors / 2fa / backup)
 *   users.*            user management
 *   backup.*           backup
 *   audit.*            audit log
 *   logs.*             log search
 *   notif.*            notifications
 *   webauthn.*         passkeys
 *   error.*            error messages
 *
 * 모든 key 는 본 파일에 등록 + [MessagesKo] 에 동일 key 가 있어야 함 (linter 부재라 수동
 * 동기화). 비어있으면 [Messages.t] 가 key 자체를 반환 — 운영 중 깨진 string 즉시 발견.
 */
internal object MessagesEn {
    val MAP: Map<String, String> = mapOf(
        // ─────────────────────────────────────────────── common
        "common.save" to "Save",
        "common.cancel" to "Cancel",
        "common.delete" to "Delete",
        "common.back" to "Back",
        "common.edit" to "Edit",
        "common.create" to "Create",
        "common.update" to "Update",
        "common.confirm" to "Confirm",
        "common.close" to "Close",
        "common.next" to "Next",
        "common.previous" to "Previous",
        "common.search" to "Search",
        "common.refresh" to "Refresh",
        "common.download" to "Download",
        "common.upload" to "Upload",
        "common.copy" to "Copy",
        "common.copied" to "Copied",
        "common.loading" to "Loading…",
        "common.empty" to "Nothing here yet",
        "common.error" to "Error",
        "common.success" to "Success",
        "common.warning" to "Warning",
        "common.info" to "Info",
        "common.yes" to "Yes",
        "common.no" to "No",
        "common.ok" to "OK",
        "common.disabled" to "Disabled",
        "common.enabled" to "Enabled",
        "common.required" to "Required",
        "common.optional" to "Optional",
        "common.unknown" to "Unknown",
        "common.never" to "Never",
        "common.now" to "Now",
        "common.signOut" to "Sign out",
        "common.signIn" to "Sign in",
        "common.username" to "Username",
        "common.password" to "Password",
        "common.email" to "Email",
        "common.name" to "Name",
        "common.id" to "ID",
        "common.status" to "Status",
        "common.actions" to "Actions",
        "common.type" to "Type",
        "common.date" to "Date",
        "common.time" to "Time",
        "common.size" to "Size",
        "common.count" to "Count",
        "common.role" to "Role",
        "common.you" to "You",

        // ─────────────────────────────────────────────── nav (top-level)
        "nav.home" to "Home",
        "nav.projects" to "Projects",
        "nav.tools" to "Tools",
        "nav.builds" to "Builds",
        "nav.devices" to "Devices",
        "nav.settings" to "Settings",
        "nav.logout" to "Sign out",

        // ─────────────────────────────────────────────── settings tabs
        "settings.tab.general" to "General",
        "settings.tab.account" to "Account",
        "settings.tab.security" to "Security",
        "settings.tab.network" to "Network",
        "settings.tab.notifications" to "Notifications",
        "settings.tab.backup" to "Backup",
        "settings.tab.users" to "Users",
        "settings.tab.audit" to "Audit",
        "settings.tab.buildEnv" to "Build environment",
        "settings.tab.prompts" to "Prompts & Agents",
        "settings.tab.monitoring" to "Monitoring",

        // ─────────────────────────────────────────────── settings page
        "settings.title" to "Settings",
        "settings.general.language.title" to "Language",
        "settings.general.language.body" to "Choose the SSR language for your session. Server default applies if not set.",
        "settings.general.language.option.system" to "Use server default (%s)",
        "settings.general.language.option.en" to "English",
        "settings.general.language.option.ko" to "한국어 (Korean)",
        "settings.general.language.save" to "Save language",
        "settings.general.language.saved" to "Language saved — please refresh the page.",

        // ─────────────────────────────────────────────── home / dashboard
        "home.greeting" to "Welcome to %s",
        "home.metric.projects" to "Projects",
        "home.metric.runningTasks" to "Running",
        "home.metric.diskFree" to "Free disk",
        "home.quickActions" to "Quick actions",

        // ─────────────────────────────────────────────── projects
        "projects.title" to "Projects",
        "projects.register" to "Register project",
        "projects.empty.title" to "No projects yet",
        "projects.empty.body" to "Register your first Android project to get started.",
        "projects.delete.confirm" to "Delete project %s?",
        "projects.lastBuild" to "Last build",

        // ─────────────────────────────────────────────── env setup
        "env.title" to "Environment setup",
        "env.installAll" to "Install all",
        "env.installOne" to "Install %s",
        "env.refresh" to "Refresh",
        "env.status.installed" to "Installed",
        "env.status.missing" to "Missing",
        "env.status.installing" to "Installing…",

        // ─────────────────────────────────────────────── claude
        "claude.title" to "Claude authentication",
        "claude.option.oauth" to "OAuth (recommended)",
        "claude.option.file" to "Credentials file",
        "claude.option.apiKey" to "API key",

        // ─────────────────────────────────────────────── mcp
        "mcp.title" to "MCP catalog",
        "mcp.install" to "Install selected",

        // ─────────────────────────────────────────────── git integrations
        "gitint.title" to "Git integrations",
        "gitint.token.register" to "Register token",
        "gitint.ssh.keygen" to "Generate SSH key",

        // ─────────────────────────────────────────────── error / form
        "error.required" to "%s is required",
        "error.invalid" to "Invalid %s",
        "error.notFound" to "%s not found",
        "error.forbidden" to "Forbidden",
        "error.unauthorized" to "Unauthorized",
        "error.serverError" to "Server error",
        "error.csrf" to "CSRF check failed",
    )
}
