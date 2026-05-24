# Vibe Coder — Server

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/siamakerlab/vibe-coder-server)](https://hub.docker.com/r/siamakerlab/vibe-coder-server)

> **Standalone Docker app.** A self-hostable Android development server that
> drives Claude Code, Gradle, and Git as child processes — accessible from any
> browser, no client install required. Spin up one container on your PC and
> log in to create projects, send prompts, build, and download APKs.

This repository contains the server body (Ktor backend) and the operations
web UI. An Android companion app (`vibe-coder-android`, separate repo) is an
optional client that points at the same server — every feature works in the
browser alone.

## Repository layout

```
vibe-coder-server/
├─ shared/              # JVM library — @Serializable DTOs / ApiPath / WsFrame
├─ server/              # Ktor server (Netty), PostgreSQL via Exposed,
│                       # Claude/Gradle/Git child processes, WS log hub,
│                       # Admin web UI (SSR HTML)
└─ docker/              # Slim Docker image + compose + vibe-doctor
```

## What's inside (v0.43.0)

### Core orchestration
- **Claude Code CLI orchestration** — one persistent child process per project,
  stream-json on stdin/stdout, console log relayed live over WS. Cancel a
  runaway turn with the ■ stop button (v0.13.0+); session-id is preserved so the
  next prompt resumes the same conversation.
- **Friendly tool rendering** — `Bash`, `Read`, `Write`, `Edit`, `Glob`, `Grep`,
  `TaskCreate/Update`, `WebSearch/Fetch` etc. each get a one-line readable
  representation in the console instead of raw JSON.
- **Four Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **Prompt template library** (v0.13.0+) — save reusable prompts at `/prompts`,
  pull them into any console via the ▼ dropdown. JSON-backed, max 500.
- **General Chat** (v0.13.0+) — `/chat` page runs a project-less Claude
  session in a synthetic `__scratch__` workspace. Full multi-turn conversation
  with `--resume`, identical UX to the project console.

### Build & deploy
- **MCP catalog** — 60+ Model Context Protocol servers in 10 categories,
  checkbox multi-select, per-MCP token form, recommended ★, trust tiers.
- **Build environment one-click installer** — Android SDK / Gradle binary &
  cache / Node + Claude CLI / MCP packages, all persisted under one host
  directory. New project `CLAUDE.md` is wired to use the installed Gradle to
  avoid redundant wrapper downloads (v0.14.1+).
- **Git clone on project register** — public / private (HTTPS PAT or SSH key)
  with auto-generated ed25519 key pair.

### Project tooling
- **In-browser file tree + editor** (v0.13.0+) — `/projects/{id}/tree` browses
  the workspace; `/projects/{id}/view` opens read-only / edit toggle with
  syntax highlighting via bundled highlight.js (Kotlin / Java / XML / JSON /
  YAML / Markdown / properties / shell). 1 MB / binary / symlink guards.
- **Settings persistence** (v0.14.0+) — `/settings` writes `server.yml` with
  atomic move + `.bak.<ts>` rotation (keeps 5). Restart required for
  host/port/name; other fields take effect on next read.

### Persistence & security
- **PostgreSQL backend** (v0.14.0+) — sidecar `postgres:17-alpine` container,
  Exposed ORM + Hikari pool, JSONB-ready for future history features.
- **Conversation history** (v0.16.0+) — every prompt, assistant message, and
  `tool_use` lands in `conversation_turns` with session/turn indices. Browse
  per-project at `/projects/{id}/history` and scratch chat at `/chat/history`.
- **CSRF protection on every SSR POST** (v0.12.4+) — HMAC-SHA256 deterministic
  derivation from the device cookie. REST API (Bearer header) is exempt.
- **IP-based brute-force throttling** (v0.12.4+) — account lock at 10 fails /
  15 min, IP block at 30 fails / 24 h. Timing-safe dummy verify on missing users.
- **Audit log** (v0.15.0+) — every operational action (login / device revoke /
  project / build / MCP / settings / git token / console new-cancel / git commit)
  lands in `audit_log` with user, IP, result, ts. `/audit` page with filter +
  paginate.
- **JSON API parity** — every admin UI feature is also exposed under `/api/*`
  with Bearer authentication for the Android companion app.

### Notifications (v0.17.0+, expanded in v0.21 / v0.27 / v0.29)
- **Email (SMTP)** alerts on build failure / first success, Claude session
  idle waiting for input, disk / quota thresholds, SSH-key / PAT expiry.
  Configure host / port / user / password / from at `/settings/email`. Jakarta
  Mail + Angus, TLS by default. Async fire-and-forget — never blocks the
  build pipeline.
- **Slack / Discord / Telegram webhooks** (v0.27.0+) — same triggers, parallel
  delivery. Configure at `/settings/webhook`. JDK 11+ `HttpClient`, SSRF
  whitelist (`hooks.slack.com`, `discord.com`/`discordapp.com`, Telegram bot
  token regex). Test message button per provider.
- **Claude usage monitoring** (v0.21.0+) — `ClaudeUsageMonitor` polls
  `claude /status` every 5 min (default), fires a one-shot email + webhook
  alert on transitioning past `warnThresholdPercent` (80%) or
  `criticalThresholdPercent` (95%). Dashboard "Claude usage" card with
  colored bar + reset time + parsed plan/model.
- **Disk usage monitoring** (v0.29.0+) — `DiskMonitor` polls
  `Files.getFileStore(workspace.root)` every 10 min, alerts on transitioning
  past `email.diskUsageWarnPercent` (85% default). Dashboard "Disk usage"
  card with total/free GB and colored bar.

### Security & sessions (v0.26.0+)
- **2FA (TOTP)** — RFC 6238 implementation with zero external dependencies
  (JDK `Mac` + custom Base32). Google Authenticator / 1Password / Authy
  compatible. Setup at `/2fa` (otpauth URI + Base32 secret + 6-digit verify),
  login flow returns `401 totp_required` after password to prompt for code.
- **Session idle timeout** — `security.sessionIdleTimeoutMinutes` (default
  30, `0` = unlimited). Bearer auth + SSR `requireSessionOrRedirect` both
  enforce: `device.lastSeenAt` older than N min → automatic `device` row
  delete + redirect to `/login?err=session_timeout`. Audit logged.

### Publishing (v0.22.0–v0.23.0)
- **Play Console upload** — build detail page has a "Play Console upload"
  card when status=SUCCESS. Precheck verifies `google-play-publisher` MCP +
  Service Account JSON. Trigger sends a structured prompt to the project's
  Claude session ("upload this AAB to internal track"); MCP-delegated
  approach keeps secrets off the server code path.
- **TestFlight upload** — same pattern with `app-store-connect` MCP. vibe-coder
  does not build iOS itself (macOS/Xcode required); user uploads externally
  produced `.ipa` to the workspace then triggers the upload.

### Quality of life (v0.28.0+)
- **APK signature inspection** — `apksigner verify --verbose --print-certs`
  parsed inline on each build detail page (active schemes v1/v2/v3/v4, Signer
  DN, SHA-256 fingerprints). Graceful when SDK / build-tools missing.
- **Build cache management** (v0.28.0+) — `/settings/cache` page shows
  current size of `~/.gradle/caches`, `~/.gradle/daemon`, `~/.android/cache`,
  `~/.npm/_cacache` with per-target "clear" buttons. CSRF + confirm dialog.
- **Source zip download** (v0.29.0+) — `GET /projects/{id}/zip` streams the
  project source as a zip (excludes `.git`, `build`, `.gradle`,
  `node_modules`, `.idea`, `*.apk`, `*.aab`). Filename auto-generated as
  `<projectId>-source-<yyyyMMdd-HHmm>.zip`.
- **Build history chart** (v0.30.0+) — inline SVG line chart on every
  `/projects/{id}/builds` page; last 30 builds with duration (success line)
  + status dots + APK size points.
- **Keyboard shortcuts** (v0.30.0+) — `g p / c / h / e / s / a / d / l` 2-key
  sequences + `?` help overlay. Disabled when an input is focused.

### Search & cross-project tools (v0.30.0–v0.32.0)
- **Global conversation search** (v0.30.0+) — `/history` greps every
  project's `conversation_turns` table. LIKE escape, role filter, ±100 char
  excerpt with `<mark>` highlight, 200 hit hard cap.
- **Build log grep** (v0.32.0+) — `/logs` walks
  `.vibecoder/<projectId>/logs/*.log` (last 2 MB per file scanned).
  Project filter optional; 200 match cap; highlights match.
- **Dependency audit** (v0.32.0+) — `/projects/{id}/deps` runs
  `./gradlew :{module}:dependencies --configuration <cfg>` and extracts
  `group:name:version` coordinates. CVE matching deferred to a later
  minor.

### Claude integration (v0.31.0+)
- **Custom agents UI** — `/agents` CRUD over `~/.claude/agents/*.md`.
  Sanitized names, 64 KB body cap, atomic write, audit logged.
- **Conversation export/import** — `GET /projects/{id}/history/export`
  downloads JSON envelope (schemaVersion 1). `POST .../history/import`
  (multipart) restores into another project; sessionId-level idempotency
  + dry-run mode. Same envelope feeds the automatic archive (next bullet).
- **Conversation auto-archive** (v0.33.0+) — `ConversationArchiver` ticks
  every 24 h: sessions inactive for ≥ 30 days are dumped to
  `<workspace>/.vibecoder/<projectId>/archive/session-<sid>.json` and
  their rows deleted from `conversation_turns`.
- **Prompt suggestions** — `GET /api/projects/{id}/claude/prompt-suggestions?prefix=…`
  returns LIKE-prefix matches from this project's `user` turns (60 s
  in-memory cache).

### Environment & build files (v0.32.0+)
- **Env files quick edit** — `/projects/{id}/env-files` exposes only a
  whitelist of 7 files (`local.properties`, `gradle.properties`, `.env`,
  `.env.local`, `app/build.gradle.kts`, `build.gradle.kts`,
  `settings.gradle.kts`). Atomic write, 256 KB cap, secret-content warning
  inline.

### Automation (v0.33.0+)
- **Cron-style build schedule** — `/projects/{id}/automation` registers
  `HH:MM` / `*:MM` / `*:*` schedules; `BuildScheduler` ticks every 60 s
  with per-minute dedupe; audit logged.
- **External build webhook** — multi-secret `POST /api/webhooks/build/{projectId}`
  authenticates via `X-Vibe-Secret-Id` + `X-Vibe-Secret` (plaintext, TLS
  expected) + optional `X-Vibe-Signature` (HMAC-SHA256 over body). 32-byte
  URL-safe random secrets, SHA-256 stored.

### Backup & CLI (v0.34.0+)
- **`/backup` SSR** — streams a tar.gz of the entire workspace (`postgres/`,
  `dev-tools/gradle/caches+daemon`, `npm-cache`, `playwright`, build logs
  excluded). PostgreSQL backup uses `pg_dump` (page-tear safe), with the
  exact command rendered inline.
- **`cli/vibe`** — single-file bash + curl (jq optional). Commands:
  `login` (auto-handles `totp_required`), `whoami`, `logout`, `projects`,
  `status`, `console <id> <prompt...>`, `build <id>`. Token in
  `~/.config/vibe-coder/config` with `0600`. Go/Rust port + WS subscribe on
  the roadmap.

### Code analysis (v0.35.0+)
- **Gradle wrapper management** — `/projects/{id}/wrapper` inspects
  `gradle/wrapper/gradle-wrapper.properties`, shows the current version,
  and lets you upgrade with a single form. Atomic write; only
  `distributionUrl` is replaced.
- **Code statistics** — `/projects/{id}/stats` walks the source tree and
  reports file count / LoC / size per language across 35+ languages.
  No external dependency (no `cloc`).
- **Workspace grep** — `/code-search` line-by-line scans every project's
  source tree (5 MB / file cap, binary skip, case-sensitive toggle, 200
  match cap). Match preview links straight to `/projects/{id}/view`.

### Multi-project & multi-agent (v0.36.0+)
- **Multi-console** — `/multi-console?projects=id1,id2,…` shows up to
  six project consoles in an iframe grid (cookie auth flows in
  automatically). Single page for parallel work across projects.
- **Agent dispatch API** — `GET /api/agents` (Bearer JSON). Console UI
  and Android client consume the same list for an "agent dropdown" so the
  user can quickly inject "Use the `<agent>` sub-agent to …" prompts.

### Users & roles (v0.37.0+ — multi-tenant first step)
- **`admin_users.role` column** — `admin` / `member` (default for new
  users is `member`; existing users auto-migrated to `admin` for safety).
- **`/users` SSR (admin only)** — create / list / role-toggle / delete
  users. Last-admin demotion & self-deletion are blocked. Audit logged
  (`user.create`, `user.role.change`, `user.delete`).
- **`requireAdminOrRedirect(sess)` helper** — used at `/users` today;
  other admin-only pages (`/audit`, `/settings`, `/backup`, `/2fa`,
  `/agents`) will be hardened in v0.37.x.

### Ubuntu 26.04 LTS rebase (v0.38.0+)
- Base image moved from `eclipse-temurin:17-{jdk,jre}-noble` (24.04 LTS)
  to `-resolute` (26.04 LTS, "Resolute Raccoon"). JDK 17.0.19 unchanged.
  Both slim and `:full` variants rebased. LTS support window now runs
  through 2031-04. No code or wire changes.

### PWA + VS Code extension (v0.39.0+)
- **PWA** — `static/admin/manifest.json` + `sw.js` (cache-first for
  `/static/*`, network-only for `/api/*` and `/ws/*`). Mobile browsers can
  "Add to Home Screen"; desktop can install as a standalone app.
- **VS Code extension** (`vscode-extension/`, v0.2.0 since server v0.43.0)
  — Projects TreeView on the activity bar (right-click → "Follow console",
  "Send prompt", "Trigger debug build"); status-bar item with
  auto-refresh; live console WebSocket subscribe streamed into an Output
  Channel; 7 palette commands (`Login` auto-handles `totp_required`).
  Marketplace-ready (`npm run package`); install with
  `code --install-extension vibe-coder-0.2.0.vsix`.

### Roles & access (extended in v0.40.0)
- **`viewer` role** — third role added next to `admin` / `member`.
  Read-only: console prompt, build queue, git commit, file upload, agent
  CRUD, project create/delete are all blocked at the SSR layer.
- **`requireWriteAccessOrRedirect(sess)`** chain helper guards every
  destructive SSR `POST` endpoint listed above; viewers get redirected
  to dashboard with "viewer 권한으로는 변경할 수 없습니다."
- **Admin-only guards extended** — `/audit`, `/settings` (GET + POST),
  `/backup`, `/backup/download` now use `requireAdminOrRedirect` on top
  of the session check. `/2fa` stays open to all roles (personal
  security).
- `/users` form gains a `viewer (read-only)` option; the role-toggle
  button cycles `admin → member → viewer → admin`.

### Agent dispatch UX (v0.41.0+)
- Console page gains an **`@ Agent dispatch`** dropdown next to the
  prompt template picker. Loads from `GET /api/agents` (registered
  `~/.claude/agents/*.md`) and on selection injects
  `Use the <agent-name> sub-agent to ` prefix into the prompt input.
- Already-prefixed prompts swap the agent name in place — no duplicate
  prefixes.
- Full sub-agent process pool (each agent → its own `claude` child) is
  tracked separately; this milestone is the UX wrapper around Claude
  Code's standard sub-agent dispatch.

### In-browser noVNC reverse proxy (v0.42.0+)
- New `/emulator/vnc/*` routes proxy `localhost:6080` (HTTP) and
  `localhost:6080/websockify` (WebSocket) through the same `vibe_session`
  cookie. Admin-only on both protocols.
- `/emulator` page embeds the noVNC client in an iframe (`autoconnect=true`,
  `resize=remote`). No more need to expose port 6080 on the host or set
  up an SSH tunnel — same-origin admin auth is sufficient.
- JDK 11+ `java.net.http.HttpClient` + `WebSocket` only; no new
  dependencies.

### Git + project scaffolding (v0.18.0+)
- **Git commit + push** — single `POST /api/projects/{id}/git/commit` (and an
  SSR form) wraps `add → commit → push` with non-interactive auth (PAT via
  `~/.git-credentials` or SSH `BatchMode=yes`). Push failure keeps the commit
  so retries are safe. Destructive ops (reset / force-push / branch delete)
  intentionally not exposed.
- **Project templates** — `templateId` on register chooses from `empty`,
  `compose-basic`, `compose-mvvm-hilt`, `compose-mvvm-room`, `wear-os`,
  `android-tv`. Each template seeds a `starterPrompt` consumed by the first
  Claude console turn.

### Android emulator (v0.19.0 scaffolding → v0.24.0 lifecycle → v0.25.0 :full)
- `/emulator` page reports KVM availability, AVD inventory, and running
  devices.
- **v0.24.0** added one-click AVD lifecycle: "+ create default" (`vibe-default`,
  API 35, Pixel 6 profile), "▶ headless start", per-device "■ stop". Each
  audited.
- **v0.25.0** ships the `:full` image (~3-4 GB, `siamakerlab/vibe-coder-server:full`)
  with `qemu-system-x86`, Xvfb, x11vnc, websockify + noVNC pre-installed.
  Use `docker/compose.full.yml` with `/dev/kvm` passthrough + `group_add KVM_GID`
  + port `6080` for browser-based noVNC mirroring. Slim image still works for
  CLI-only ADB workflows.

## Quick start (Docker, 3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# Edit .env — REQUIRED: set VIBECODER_DB_PASSWORD to a strong value (v0.14.0+).
# Also: PUID/PGID (id -u; id -g), host port — defaults work for the rest.
${EDITOR:-nano} .env

docker compose up -d            # starts postgres + vibe-coder-server

# 1. Open http://<PC IP>:17880/setup in a browser to create the admin user.
# 2. Go to "Build environment" → click "Install/update all".
#    (Android SDK download, 3-4GB, 5-15 min.)
# 3. Build environment → "Claude login" card → pick option 0/1/2/3.
```

> **v0.14.0+** ships a sidecar PostgreSQL container (`postgres:17-alpine`). The
> `VIBECODER_DB_PASSWORD` env var is mandatory — compose refuses to start with an
> empty value. Upgrading from v0.13.x is a fresh start (admin / projects
> re-created; workspace files preserved on disk). See the v0.14.0 entry in
> [CHANGELOG.md](CHANGELOG.md) for the exact steps.

## Minimum `docker-compose.yaml` (write your own)

```yaml
name: vibe-coder
services:
  postgres:
    image: postgres:17-alpine
    container_name: vibe-coder-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: vibecoder
      POSTGRES_USER: vibecoder
      POSTGRES_PASSWORD: ${VIBECODER_DB_PASSWORD:?VIBECODER_DB_PASSWORD must be set}
    volumes:
      - ./vibe-coder-data/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U vibecoder -d vibecoder"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 20s

  vibe-coder-server:
    image: siamakerlab/vibe-coder-server:latest
    container_name: vibe-coder-server
    restart: unless-stopped
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      PUID: "1000"       # id -u
      PGID: "1000"       # id -g
      TZ: "Asia/Seoul"
      JAVA_OPTS: "-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"
      # PostgreSQL connection (v0.14.0+)
      VIBECODER_DB_HOST: postgres
      VIBECODER_DB_NAME: vibecoder
      VIBECODER_DB_USER: vibecoder
      VIBECODER_DB_PASSWORD: ${VIBECODER_DB_PASSWORD:?VIBECODER_DB_PASSWORD must be set}
      # First-boot admin auto-create (optional; otherwise /setup screen)
      # VIBECODER_ADMIN_USERNAME: "admin"
      # VIBECODER_ADMIN_PASSWORD: "ChangeMe123"
    ports:
      - "17880:17880"
    volumes:
      # All persistent data lives under one host directory. tar this and
      # you've backed up everything: workspace + PG data + Android SDK + Gradle +
      # MCP packages + Playwright + Claude auth.
      - ./vibe-coder-data/workspace:/workspace
      - ./vibe-coder-data/server:/data
      - ./vibe-coder-data/dev-tools/android-sdk:/opt/android-sdk
      - ./vibe-coder-data/dev-tools/gradle:/home/vibe/.gradle
      - ./vibe-coder-data/dev-tools/npm-global:/home/vibe/.local
      - ./vibe-coder-data/dev-tools/npm-cache:/home/vibe/.npm
      - ./vibe-coder-data/dev-tools/playwright:/home/vibe/.cache/ms-playwright
      - ./vibe-coder-data/dev-tools/config:/home/vibe/.config
      - ./vibe-coder-data/claude:/home/vibe/.claude
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:17880/health"]
      interval: 30s
      timeout: 5s
      start_period: 60s
      retries: 3
```

## Common operations

```bash
docker compose logs -f vibe-coder-server          # tail server logs
docker compose restart vibe-coder-server          # restart
docker exec -it vibe-coder-server bash            # shell (root)
docker exec -it --user vibe vibe-coder-server bash   # shell (vibe user)
docker exec -it --user vibe vibe-coder-server claude --version

# Upgrade (data preserved)
docker compose pull
docker compose up -d --force-recreate
```

## Build environment persists across image upgrades ✅

Since v0.7.0 every persistent path lives under one host directory
(`./vibe-coder-data/`), so `docker compose pull && up -d` never deletes
your SDK, Gradle cache, MCP servers, Playwright browsers, or Claude auth.

| Data                              | Host path                                           | Container path                  | On recreate |
|---|---|---|---|
| Project sources + APKs            | `./vibe-coder-data/workspace/`                      | `/workspace`                    | ✅ kept |
| **PostgreSQL data (v0.14.0+)**    | `./vibe-coder-data/postgres/`                       | `/var/lib/postgresql/data` (PG container) | ✅ kept |
| Server logs + build metadata      | `./vibe-coder-data/server/`                         | `/data`                         | ✅ kept |
| Android SDK (3-4 GB)              | `./vibe-coder-data/dev-tools/android-sdk/`          | `/opt/android-sdk`              | ✅ kept |
| Gradle dependency cache (1-2 GB)  | `./vibe-coder-data/dev-tools/gradle/`               | `/home/vibe/.gradle`            | ✅ kept |
| MCP server packages (`npm -g`)    | `./vibe-coder-data/dev-tools/npm-global/`           | `/home/vibe/.local`             | ✅ kept |
| npx cache                         | `./vibe-coder-data/dev-tools/npm-cache/`            | `/home/vibe/.npm`               | ✅ kept |
| Playwright browsers (optional)    | `./vibe-coder-data/dev-tools/playwright/`           | `/home/vibe/.cache/ms-playwright` | ✅ kept |
| Other tool config                 | `./vibe-coder-data/dev-tools/config/`               | `/home/vibe/.config`            | ✅ kept |
| Claude auth (OAuth / API key / MCP registrations) | `./vibe-coder-data/claude/`         | `/home/vibe/.claude`            | ✅ kept |
| **Server body** (Ktor + Claude CLI + JDK + Node) | image layer                          | —                               | 🔄 replaced |

```bash
# Backup, one line
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/

# Move to another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

⚠️ **`docker compose down -v` removes named volumes.** v0.7.0+ uses bind
mounts only (no named volumes by default), but watch out if you mixed
in legacy state. For regular upgrades, always `up -d --force-recreate`.

## Web routes (v0.43.0)

All routes below sit at the root (no `/admin/*` prefix). Bearer auth or
session cookie required except `/setup`, `/login`, `/health`. Every SSR POST
carries a CSRF `_csrf` token (v0.12.4+).

| Path | Purpose |
|---|---|
| `/` | Dashboard (server / environment / activity summary) |
| `/projects` | Project list + register form (empty / clone) |
| `/projects/{id}` | Project detail, recent builds |
| `/projects/{id}/console` | Claude prompt input + live log (WebSocket) + slash chips + ▼ template dropdown + ■ stop button |
| `/projects/{id}/builds` | Queue debug build + APK download |
| `/projects/{id}/builds/{buildId}` | Build detail + live log + cancel |
| `/projects/{id}/tree` | **v0.13.0** Filesystem browser inside the project workspace |
| `/projects/{id}/view?path=...` | **v0.13.0** Read-only view (highlight.js) ↔ Edit mode (textarea) |
| `/projects/{id}/files` | Upload / download / delete (the upload area) |
| `/projects/{id}/git` | git status / diff / log (read-only) + **v0.18.0** commit & push form |
| `/projects/{id}/history` | **v0.16.0** Persistent prompt/response history (filter / paginate) |
| `/chat` | **v0.13.0** General Chat — project-less Claude session (`__scratch__` workspace) |
| `/chat/history` | **v0.16.0** Scratch-project persistent history |
| `/prompts` | **v0.13.0** Prompt template CRUD (used by the ▼ dropdown) |
| `/env-setup` | Build-environment status + one-click installers |
| `/env-setup/mcp` | MCP catalog (60+ entries, checkbox multi-select) |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/env-setup/tasks/{taskId}` | Live install progress (WS) |
| `/emulator` | **v0.19.0** diagnostics + **v0.24.0** AVD lifecycle (create / launch / stop) + **v0.25.0** `:full` setup guide |
| `/settings/git-integrations` | PAT tokens + SSH public key |
| `/settings/email` | **v0.17.0** SMTP configuration + trigger matrix |
| `/settings/webhook` | **v0.27.0** Slack / Discord / Telegram webhook configuration + test |
| `/settings/cache` | **v0.28.0** Gradle / Android / npm cache size + per-target cleanup |
| `/settings/cors` | Read-only CORS policy viewer |
| `/2fa` | **v0.26.0** Two-factor TOTP enable / disable |
| `/audit` | **v0.15.0** Operational audit log (filter / paginate) |
| `/projects/{id}/zip` | **v0.29.0** Streaming source-only zip download |
| `/projects/{id}/env-files` | **v0.32.0** Whitelist-edit `local.properties` / `.env` / `build.gradle.kts` |
| `/projects/{id}/deps` | **v0.32.0** Gradle dependency tree + coord extraction |
| `/projects/{id}/automation` | **v0.33.0** Cron schedule + webhook secret management |
| `/history` | **v0.30.0** Cross-project conversation search |
| `/logs` | **v0.32.0** Build log grep across all projects |
| `/agents` | **v0.31.0** Custom `.agents/*.md` CRUD |
| `/backup` | **v0.34.0** Workspace tar.gz backup + restore guide |
| `/projects/{id}/wrapper` | **v0.35.0** Gradle wrapper version + upgrade |
| `/projects/{id}/stats` | **v0.35.0** Code statistics (LoC / languages) |
| `/code-search` | **v0.35.0** Workspace-wide grep |
| `/multi-console` | **v0.36.0** N-pane multi-project console (iframe grid) |
| `/users` | **v0.37.0** Multi-user / role management (admin only); **v0.40.0** added `viewer` |
| `/emulator/vnc/*` | **v0.42.0** noVNC reverse proxy (HTTP + WebSocket; admin only) |
| `/settings`, `/devices`, `/password` | Operations |
| `/login`, `/setup`, `/logout` | Auth |

## JSON API (v0.43.0 — for clients like the Android app)

Every UI feature has a matching `/api/*` endpoint with Bearer authentication.
Wire definitions: `shared/.../ApiPath.kt` + `shared/.../Dtos.kt`. Highlights:

- `GET  /api/server/status`, `GET /api/server/environment`, `GET /api/server/environment/check`
- `GET  /api/projects`, `POST /api/projects/register` (with `sourceType=clone`
  for git clone; **v0.18.0+** `templateId` field for built-in scaffolds)
- `POST /api/projects/{id}/build/debug`, `GET /api/projects/{id}/builds`
- `POST /api/projects/{id}/builds/{buildId}/cancel`
- `POST /api/projects/{id}/claude/console/prompt | new | cancel`
  (`.../cancel` is **v0.13.0+** — Android `shared/` v0.6.11+ required)
- `GET  /api/projects/{id}/claude/status`
- `GET  /api/prompt-templates` (v0.13.0+ — prompt library)
- `GET  /api/projects/{id}/history`, `GET /api/chat/history`
  (**v0.16.0+** — persisted conversation_turns; pagination via `before`)
- `POST /api/projects/{id}/git/commit`
  (**v0.18.0+** — add → commit → optional push; non-interactive auth)
- `GET  /api/prompt-templates` (**v0.13.0+** — server) / `PROMPT_TEMPLATES` wire
  constant promoted in **v0.20.0** (`PromptTemplateDto` + list response)
- `POST /api/auth/login` now accepts optional `totpCode: String` (**v0.26.0+**);
  returns `401 totp_required` for 2FA-enabled users when missing
- `GET  /api/projects/{id}/claude/prompt-suggestions?prefix=...&limit=8`
  (**v0.31.0+** — LIKE prefix match against this project's user turns)
- `GET  /projects/{id}/history/export` / `POST .../history/import`
  (**v0.31.0+** — JSON envelope, sessionId-level idempotency)
- `POST /api/webhooks/build/{projectId}` (**v0.33.0+** — admin-auth-free
  external trigger; `X-Vibe-Secret-Id` + `X-Vibe-Secret` + optional
  `X-Vibe-Signature`)
- `GET  /api/agents` (**v0.36.0+** — list registered Claude sub-agents for
  console UI / Android dispatch dropdown)
- `GET  /api/env-setup/components`, `POST /api/env-setup/install-all`,
  `POST /api/env-setup/{componentId}/install`
- `POST /api/env-setup/claude-auth/upload` (multipart)
- `POST /api/env-setup/claude-auth/api-key`, `DELETE /api/env-setup/claude-auth/api-key/delete`
- `POST /api/env-setup/claude-login/start | submit | cancel`, `GET .../status`
- `GET  /api/env-setup/mcp`, `POST /api/env-setup/mcp/install | unregister`
- `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (multipart — Service Account JSON / Apple .p8 etc.)
- `GET  /api/settings/git-integrations`, `POST .../register | delete | ssh-keygen`
- WebSocket: `/ws/projects/{id}/console/logs`, `/ws/projects/{id}/builds/{buildId}/logs`,
  `/ws/env-setup/{taskId}/logs`

## Auth (v0.4.0+, hardened in v0.26.0)

- `POST /api/auth/setup` — first-boot admin creation (only when DB has no admin).
- `POST /api/auth/login` — `{username, password, totpCode?}` → bearer token +
  `vibe_session` cookie. `totpCode` is optional unless the user has 2FA
  enabled; in that case the server returns `401 totp_required` on the first
  call and `401 invalid_totp` on a bad code.
- `POST /api/auth/password` — change password.
- `POST /api/auth/logout` — invalidate the device row (cookie + Bearer header both work).

Passwords are stored as BCrypt cost-12 hashes only. **Brute-force protection**
(v0.12.4+):
- Account lock: 10 consecutive failures → 15 min cooldown.
- IP block: 30 failures from one IP within 24 h → 24 h block (catches
  credential-stuffing across multiple accounts).
- Timing-safe dummy verify on missing users (runtime-computed valid BCrypt
  hash so the response time matches a real verification).

**Two-factor authentication (v0.26.0+)** — RFC 6238 TOTP (HMAC-SHA1, 30 s
period, 6 digits) self-implemented (zero external deps). Enable at `/2fa`:
scan the otpauth URI with Google Authenticator / 1Password / Authy, verify a
6-digit code, secret persisted to `admin_users.totp_secret`. Login then
requires the code after password. Disable requires a current code.

**Session idle timeout (v0.26.0+)** — `security.sessionIdleTimeoutMinutes`
(default 30, `0` = unlimited). Bearer auth + SSR session both check
`device.lastSeenAt`; if older than N min, the device row is deleted and the
client is redirected to `/login?err=session_timeout`. Same policy across
cookie and `Authorization: Bearer …`.

**Multi-user + role (v0.37.0 / v0.40.0)** — `admin_users.role` column
adds `admin` / `member` / `viewer`. The first admin (from `/setup`) is
always `admin`; new users created via `/users` default to `member`.
`/users` itself is admin-only. Last-admin demotion and self-deletion are
blocked. Project-level ACLs (member who only sees a subset of projects)
are on the roadmap for v0.44+.

`requireWriteAccessOrRedirect(sess)` blocks `viewer` sessions from
destructive SSR `POST` endpoints (project create/delete, console
new/slash, build enqueue, files upload, edit, git commit, agents
save/delete). `requireAdminOrRedirect(sess)` guards `/audit`,
`/settings`, `/backup`, `/users`. JSON API and WebSocket layers do not
yet enforce role — known scope limit tracked for the next minor.

## Security boundaries

- **CSRF protection** (v0.12.4+) — every SSR POST carries a hidden `_csrf`
  HMAC-SHA256 token. REST API (Bearer header, not cookie) is exempt.
  Multipart uploads carry `_csrf` in the query string.
- **WebSocket Origin check** (v0.12.4+) — handshake rejects mismatched Origin
  to defend against cross-site WebSocket hijacking.
- **Workspace path safety** — `PathSafety.normalizeAndCheck` rejects any
  read/write outside `/workspace`. Symlinks are not followed
  (`LinkOption.NOFOLLOW_LINKS`).
- **Bearer tokens** stored hashed only; plaintext returned to the client once
  at issue.
- **WebSocket auth** — cookie automatic on same-origin handshake; Android
  clients send `{"type":"auth","token":"..."}` as the first frame.
- **Upload extension blacklist**: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- **No raw-shell UI.** No release signing. No automation prompts that wait
  on stdin (CLAUDE.md non-interactive policy templated into every new
  project's `.claude/settings.json`). `git push` is now exposed (v0.18.0+)
  but only via the non-interactive commit endpoint described below.
- **External commands** have hard timeouts; cancellation calls
  `destroyForcibly`. The `claude` child can be SIGTERM'd mid-turn via the
  ■ stop button while preserving its session-id (`--resume` later).
- **Audit log** (v0.15.0+) — `/audit` shows every operational action with
  user / IP / result / detail for post-incident review.
- **Git push policy** (v0.18.0+) — the new write endpoint adds `commit` only;
  `reset`, `force-push`, `branch -d`, and other destructive ops remain
  off-limits. Auth is non-interactive (`GIT_TERMINAL_PROMPT=0`, SSH
  `BatchMode=yes`) — no credential prompt ever blocks a request.
- **Webhook SSRF defense** (v0.27.0+) — only `hooks.slack.com`,
  `discord.com` / `discordapp.com`, and Telegram (`api.telegram.org` via a
  bot-token regex `^\d+:[A-Za-z0-9_-]+$`) are accepted. `HttpClient` follows
  no redirects to prevent cross-host hops.
- **noVNC mirroring** (`:full` image, v0.25.0+) — websockify exposes the
  emulator screen on the container's loopback only; port 6080 is meant to be
  reached either over LAN (single-operator assumption) or via an SSH tunnel.
  No vibe-coder admin auth wraps it yet — production-grade integration
  (server-side reverse proxy with cookie auth) is on the v0.26+ roadmap.

## Build matrix

| Layer | Version |
|---|---|
| Base image | eclipse-temurin:17-jdk-resolute (Ubuntu 26.04 LTS, since v0.38.0) |
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| PostgreSQL JDBC | 42.7.4 |
| PostgreSQL server | 17-alpine (sidecar container) |
| JDK toolchain | 17 |

## Build / run locally (without Docker)

You need a reachable PostgreSQL instance (host installation, separate Docker
container, or a remote PG). Point the server at it via env vars:

```bash
./gradlew :server:installDist

export VIBECODER_DB_HOST=127.0.0.1
export VIBECODER_DB_PORT=5432
export VIBECODER_DB_NAME=vibecoder
export VIBECODER_DB_USER=vibecoder
export VIBECODER_DB_PASSWORD=your-strong-password

./server/build/install/server/bin/server --workspace ./workspace
```

The bundled compose file already provides a `postgres` container — running
`docker compose up -d postgres` and using the same `.env` is the easiest path.

```
>>> Vibe Coder Server started
>>> URL         : http://192.168.0.10:17880
```

## License

[GNU Affero General Public License v3.0](LICENSE). Modifications served over
a network must release source under the same license. Commercial use allowed
under copyleft obligations.

## Companion repository

`vibe-coder-android` — mobile client that talks to the same server. Both
repos share the `shared/` module (DTOs / ApiPath / WsFrame); update them in
lockstep when wire changes occur. See `CHANGELOG.md` for the matrix.

## Bundled CLI (`cli/vibe`, v0.34.0+)

Single-file `bash` + `curl` wrapper around the REST API. Useful for shell
automation and CI without a full client.

```bash
sudo install -m 0755 cli/vibe /usr/local/bin/vibe
vibe login              # interactive — handles totp_required automatically
vibe projects
vibe console my-app "Add a settings screen with a dark mode toggle"
vibe build my-app
```

Token stored at `~/.config/vibe-coder/config` with `chmod 0600`. `jq`
optional (pretty-prints JSON). Go/Rust port with WebSocket subscribe is on
the roadmap.

## Bundled VS Code extension (`vscode-extension/`, v0.2.0)

TypeScript multi-file build (`api.ts`, `ws.ts`, `treeview.ts`,
`extension.ts`). Adds runtime dep on `ws` for WebSocket subscribe.

```bash
cd vscode-extension
npm install && npm run package    # → vibe-coder-0.2.0.vsix
code --install-extension vibe-coder-0.2.0.vsix
# or dev mode: npm run watch then F5 in VS Code
```

Features:

- **Projects sidebar** (activity bar $(rocket)) — TreeView with the last
  20 builds per project; right-click → "Follow console", "Send prompt",
  "Trigger debug build".
- **Status bar item** — `host (vX.Y.Z)`, refreshed every 60 s; click for
  full status.
- **Live console** — `Vibe Coder: Follow project console` opens an
  Output Channel and streams every Claude frame in real time. Re-run on
  the same project to toggle off.
- 7 palette commands; **Login** auto-handles `401 totp_required`.

Marketplace publish is one `vsce publish` away (PAT required) — see
`vscode-extension/README.md`. Not yet listed there.
