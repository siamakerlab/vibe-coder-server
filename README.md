# Vibe Coder — Server

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Docker Pulls](https://img.shields.io/docker/pulls/siamakerlab/vibe-coder-server)](https://hub.docker.com/r/siamakerlab/vibe-coder-server)

> **Standalone Docker app.** A self-hostable Android development server that
> drives Claude Code, Gradle, and Git as child processes — accessible from any
> browser, no client install required. Spin up one container on your PC and
> log in to create projects, send prompts, build, and download APKs.

This repository contains the server body (Ktor backend) and the operations
web UI. An Android companion app (`vibe-coder-android`, separate repo) is an
optional client that points at the same server — **every feature works in the
browser alone.**

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Repository layout](#repository-layout)
- [Features](#features) — orchestration · history · build · tooling · MCP ·
  notifications · security · persistence · operations · clients
- [Quick start](#quick-start-docker-3-minutes)
- [Running it](#running-it) — compose reference · common ops · data volumes
- [Reference](#reference) — web routes · JSON API · auth · security boundaries ·
  build matrix · local build
- [License & companion repo](#license)

---

## Why this exists

I'm a solo Android developer shipping a lot of small apps with Claude Code,
and I wanted to keep working without lugging the dev laptop around — from the
train, the café, the couch. Standard remote tools all fell short:

- **SSH from a phone** drops constantly and is painful for long Claude turns.
- **Debug APK install** over USB / `adb` is too many manual steps.
- **RDP / VNC** make phone-side input miserable.

So I built a Claude Code-dedicated server plus a thin Android client. The
server runs in Docker on my home dev PC for a consistent, reproducible
environment; the Android app gives me a real touch-first UI for prompting,
watching build output, and one-tap installing the debug APK on the same
device. I can run several projects in parallel, jump between them, save
common prompts as templates, and pick up exactly where I left off after any
disconnect.

**The Android client is optional** — every feature works in the browser too,
so the server alone is a complete solution. If you're another solo Android
developer in a similar workflow, you'll probably find it just as useful.

Built entirely by vibe-coding with Claude Code, and updated continuously as
my own workflow needs change. Features I rarely use may have stale bugs —
please open an issue and I'll fix them quickly.

## Repository layout

```
vibe-coder-server/
├─ shared/              # JVM library — @Serializable DTOs / ApiPath / WsFrame
├─ server/              # Ktor server (Netty), PostgreSQL via Exposed,
│                       # Claude/Gradle/Git child processes, WS log hub,
│                       # Admin web UI (SSR HTML)
├─ docker/              # Slim Docker image + compose + vibe-doctor
├─ helm/                # Minimal Kubernetes Helm chart
├─ cli/                 # Single-file bash REST client (`vibe`)
└─ vscode-extension/    # VS Code client
```

---

## Features

Everything below is reachable from the browser UI; most also have a matching
`/api/*` JSON endpoint for the Android client (see [JSON API](#json-api)).
For per-release history, see [CHANGELOG.md](CHANGELOG.md).

### Claude Code orchestration

- **Persistent session per project** — one long-lived `claude` child process,
  stream-json over stdin/stdout, console relayed live over WebSocket. The ■
  stop button SIGTERMs a runaway turn while preserving the session-id, so the
  next prompt resumes the same conversation (`--resume`).
- **Concurrent turn cap** — `claude.maxConcurrentTurns` (default 3) bounds how
  many turns run at once across **all** project and sub-agent consoles, sharing
  a single coroutine `Semaphore`. Excess prompts **queue** (never rejected),
  avoiding Anthropic's server-side 429 throttle from bursting one account/IP.
  Set `0` for unlimited.
- **Friendly console rendering** — every stream-json event renders as a
  human-readable line instead of raw JSON: tool results extract their text,
  every tool (including `mcp__*`, `Task`, `ToolSearch`, …) gets a one-line
  summary, `thinking` blocks collapse to a toggleable `💭 thinking`, and
  sub-agent / rate-limit events are labelled. One shared
  `static/admin/console-render.js` powers the main console, `/chat`, and
  sub-agent consoles identically.
- **Console input** — Enter sends; Shift+Enter / Ctrl+Cmd+Enter inserts a
  newline; IME composition Enter (Korean etc.) never sends.
- **Four Claude auth options** — terminal, file upload, API key, **plus** a
  semi-automatic web OAuth (`script -q` PTY wrap, no xterm.js).
- **Prompt template library** — save reusable prompts at `/prompts`, pull them
  into any console via the ▼ dropdown. JSON-backed, max 500.
- **General Chat** — `/chat` hosts multiple independent ChatGPT-style sessions
  (left sidebar lists every chat, auto-titled from the first prompt, rename /
  delete per item). Each chat is a `__chat_<id>__` ghost project with its own
  Claude session and persistent history, reusing the project console UX.
- **Prompt suggestions** — prefix autocomplete from this project's past `user`
  turns.
- **Prompt automation (server-side autopilot)** — `/projects/{id}/automation/prompts`
  fires the next prompt on every turn completion in **repeat** (same prompt × N)
  or **sequence** (list) mode. Runs even with the browser closed; presets are
  workspace-global; run history is persisted and reconciled on boot.
- **Custom agents** — `/agents` CRUD over `~/.claude/agents/*.md` (sanitized
  names, 64 KB body cap, atomic write, audit logged).
- **Real multi-agent (sub-agent process pool)** — a **separate** Claude child
  per `(projectId, agentName)` runs in parallel against the same workspace
  (e.g. a `reviewer` reading while a `frontend` writes Compose). Each agent has
  its own SSR console (`/projects/{id}/agents/{agent}/console`), WebSocket
  topic, and session-id file; first prompt auto-prefixes
  `Use the <agent> sub-agent to …`. Persistent history, idle-30-min SIGTERM
  with resume.
- **Agent dispatch dropdown** — `@ Agent dispatch` next to the template picker
  injects the dispatch prefix into the prompt input (swaps in place, no
  duplicates).
- **Multi-console** — `/multi-console?projects=id1,id2,…` shows up to six
  project consoles in an iframe grid for parallel work.

### Conversation history & search

- **Persistent history** — every prompt, assistant message, and `tool_use`
  lands in `conversation_turns` with session/turn indices. Browse per-project
  at `/projects/{id}/history` and scratch chat at `/chat/history`, with
  pagination and filters.
- **Agent filter** — history filters to `(main only)` / `(all)` / a specific
  `@<agent>`; each row carries an `@agent` badge.
- **Per-turn memo + star** — ☆/★ bookmark toggle and an inline memo editor on
  each turn; filter `?starred=1` for bookmarked-only.
- **Memos (global / per-project)** — free-form notes separate from per-turn
  memos. The sidebar **Memos** page lists all of them as a card grid with a
  mini dialog for view/edit; the project console rail (below the prompt
  history) shows global + this-project memos with quick add. A memo with no
  project is **global** (shown on every project screen); otherwise it's scoped
  to one project.
- **Export / import** — `GET /projects/{id}/history/export` downloads a JSON
  envelope; import restores into another project with session-level idempotency
  and a dry-run mode. The same envelope feeds the auto-archive.
- **Auto-archive** — sessions inactive ≥ 30 days are dumped to
  `<workspace>/.vibecoder/<projectId>/archive/` and their rows pruned.
- **Full-text search** — per-project history search plus cross-project
  `/history`. ASCII queries use a PostgreSQL `tsvector` GIN index;
  Korean / non-ASCII queries route to a `pg_trgm` trigram index. Parameterized
  binding throughout (no SQL injection).
- **Prompt cache statistics** — Anthropic `usage` (input / output /
  cache-read / cache-create) is parsed from the stream and stored; `/usage`
  shows totals + per-project cache hit-rate, with the raw `/status` text kept
  below for forward compatibility.
- **Symbol definition lookup** — `/projects/{id}/symbols` does a best-effort
  regex scan for Kotlin / Java declarations (`fun`, `class`/`interface`/`object`,
  `val`/`var`, `typealias`, Java methods/types); hits link to
  `/projects/{id}/view?path=…&line=N` which smooth-scrolls and flashes the row.
- **Workspace grep / build-log grep** — `/code-search` scans every project's
  source tree; `/logs` greps `.vibecoder/<projectId>/logs/*.log`. Both cap
  matches and link previews back to the file viewer.

### Build & deploy

- **One-click build environment installer** — Android SDK, Gradle binary &
  cache, Node + Claude CLI, and MCP packages, all persisted under one host
  directory. New projects' `CLAUDE.md` is wired to use the installed Gradle to
  avoid redundant wrapper downloads.
- **Pre-installed image tooling** — the runtime image ships ImageMagick,
  Pillow (`python3-pil` + NumPy), `rsvg-convert`, `cwebp`/`dwebp`,
  `poppler-utils`, Ghostscript, and `optipng`/`pngquant`/`jpegoptim` so Claude
  can manipulate screenshots, mockups, icons, and APK resources out of the box.
  Anything else installs via the `vibe` user's passwordless sudo
  (`sudo apt-get install <pkg>`).
- **Debug build + APK download** — queued per project; live build log over WS;
  cancellable. Outputs are stored with meaningful names
  (`<packageName>-<variant>-v<versionName>.apk`, version read via `aapt`/`aapt2`
  best-effort), reflected in the download `Content-Disposition`.
- **Keystore management** — Settings → Keystores generates release / debug /
  `.properties` / AdMob files per package. Auto-applied to builds in two layers:
  `BuildService` injects `-Pandroid.injected.signing.*` for the release variant,
  and "Apply to project" sends a Claude-console prompt that wires
  `signingConfigs` in `build.gradle.kts`. **Back up the release key — losing it
  blocks Play Store updates forever.**
- **APK signature inspection** — `apksigner verify --print-certs` parsed inline
  on each build detail page (schemes v1–v4, signer DN, SHA-256 fingerprints).
- **Build insights** — inline SVG history chart (last 30 builds), a statistics
  card (total / success-rate / avg duration + sparkline + APK-size trend), and a
  comparison card vs. the previous SUCCESS build (size / duration delta).
- **Gradle wrapper management** — `/projects/{id}/wrapper` shows the current
  version and upgrades `distributionUrl` with one form.
- **Build cache management** — `/settings/cache` shows Gradle / Android / npm
  cache sizes with per-target clear buttons.
- **Scheduling & triggers** — cron-style build schedules
  (`HH:MM` / `*:MM` / `*:*`), plus an external `POST /api/webhooks/build/{id}`
  authenticated by secret-id + secret (+ optional HMAC-SHA256 signature).
- **Publishing** — Play Console upload (via `google-play-publisher` MCP) and
  TestFlight (via `app-store-connect` MCP). MCP-delegated, so signing secrets
  stay off the server code path.
- **Headless Android emulator** — `/emulator` starts/stops a KVM-accelerated
  AVD (`/dev/kvm`) so Claude can run `adb -s emulator-5554 install/logcat`
  directly for log analysis (no screen).

### Project tooling

- **Register a project** — empty, git clone (public, or private via HTTPS PAT
  or auto-generated ed25519 SSH key), or a built-in template (`empty`,
  `compose-basic`, `compose-mvvm-hilt`, `compose-mvvm-room`, `wear-os`,
  `android-tv`; each seeds a starter prompt).
- **Rename name / package / folder** — `/projects/{id}/overview` edits the
  display name (anytime), the `applicationId`, and the folder/project-id (the
  last two require the project to be idle). Renaming the package updates the DB,
  renames keystore files, and prompts Claude to refactor code/manifest/signing;
  renaming the folder moves workspace dirs and migrates the DB primary key
  across all child tables in one transaction.
- **In-browser file tree + editor** — `/projects/{id}/tree` browses the
  workspace; `/projects/{id}/view` toggles read-only (highlight.js: Kotlin /
  Java / XML / JSON / YAML / Markdown / properties / shell) and edit. 1 MB /
  binary / symlink guards.
- **Env files quick edit** — `/projects/{id}/env-files` edits a whitelist of 7
  files (`local.properties`, `gradle.properties`, `.env`, `.env.local`, the
  three `*.gradle.kts`). Atomic write, 256 KB cap, secret-content warning.
- **Code statistics** — `/projects/{id}/stats` reports file count / LoC / size
  per language across 35+ languages (no external `cloc`).
- **Dependency audit** — `/projects/{id}/deps` runs
  `gradlew :{module}:dependencies` and extracts `group:name:version`.
- **Source zip download** — `/projects/{id}/zip` streams source only (excludes
  `.git`, `build`, `.gradle`, `node_modules`, `.idea`, APK/AAB).
- **Settings persistence** — `/settings` writes `server.yml` with atomic move +
  `.bak.<ts>` rotation (keeps 5). Host/port/name need a restart; other fields
  apply on next read.
- **Per-project Keystore tab** — `/projects/{id}/keystore` manages that project's
  signing keystore and AdMob unit IDs scoped to its `applicationId`: status,
  collapsible **SHA-1 / SHA-256 / MD5 fingerprints** (lazy `keytool -list`, for
  Firebase / Google Sign-In / Maps registration), AdMob ID editing
  (independent of the keystore — App ID + **6 ad types**: banner / app-open /
  native advanced / interstitial / rewarded / rewarded-interstitial, each holding
  **multiple unit IDs** via comma-separated `<pkg>-admob.properties`), create /
  delete, and a one-click "apply signing to build.gradle.kts" Claude prompt.
  You can also **upload an existing keystore set** (release / debug / properties)
  — files are stored under the canonical `<pkg>.*` names, the properties `storeFile`
  is normalized to the host path, any existing file is backed up as `.bak.<ts>`
  before overwrite, and a signing-refresh prompt is sent to the console right after.
  Upload is allowed **only when the console is idle** (it fires a prompt).
  Shares the same `KeystoreService` as the global `/settings/keystores` page.

### MCP integration

- **Catalog** — 60+ Model Context Protocol servers in 10 categories, checkbox
  multi-select, per-MCP token form, recommended ★, trust tiers. The marketplace
  view (`/env-setup/mcp`) has per-card Install/Remove + a status pill.
- **User-scope registration** — `claude mcp add-json -s user` so the console,
  sub-agents, and `claude mcp list` all see them.
- **Bundled Gitea MCP** — `gitea-mcp` ships as a Go binary.
- **Per-project MCP tab** — `/projects/{id}/mcp` edits `.mcp.json` and shows a
  live connection-status card.

### Notifications

- **Channels** — Email (SMTP, `/settings/email`), Slack / Discord / Telegram
  webhooks (`/settings/webhook`, SSRF-whitelisted), browser **Web Push** (VAPID,
  RFC 8291 AES-128-GCM encrypted payloads, pure JDK, `/settings/push`), and an
  in-app **notification bell** on every page (unread badge, mini-panel, 30s
  polling, ack / clear-all).
- **Triggers** — build success/failure, Claude turn done/stopped/error, Claude
  usage thresholds, disk thresholds, Claude idle waiting for input, SSH-key /
  PAT expiry.
- **Monitors** — `ClaudeUsageMonitor` polls `claude /status` (one-shot alert
  past warn/critical %, dashboard card); `DiskMonitor` polls the workspace file
  store (alert past warn %, dashboard card).

### Authentication & security

Single-operator tool: **exactly one admin**, created at `/setup`. Every
authenticated session has full access — authentication itself is the only
boundary. See [Auth](#auth) and [Security boundaries](#security-boundaries) for
detail.

- **Login** — username + password (BCrypt cost-12), Bearer token + `vibe_session`
  cookie.
- **2FA (TOTP)** — RFC 6238, zero deps, Google Authenticator / 1Password / Authy
  compatible (`/2fa`).
- **WebAuthn / passkey** — `webauthn4j`, phishing-resistant 2FA alternative
  (`/webauthn`), with an optional passwordless-only mode per user.
- **Session idle timeout**, **CSRF on every SSR POST**, **WebSocket Origin
  check**, **per-IP rate limit**, **brute-force lockout** (account + IP), and an
  **audit log** (`/audit`) of every operational action.

### Persistence

- **PostgreSQL 17** sidecar container, Exposed ORM + Hikari pool. Schema is
  created/migrated idempotently on boot (`createMissingTablesAndColumns` + raw
  `IF NOT EXISTS` migrations), so upgrades need no manual DB steps.
- **All persistent data under one host directory** (`./vibe-coder-data/`) —
  workspace, PG data, Android SDK, Gradle cache, MCP packages, Claude auth,
  SSH key, keystores — so `docker compose pull && up -d` never deletes it (see
  [Data volumes](#data-persists-across-image-upgrades)).
- **Backup** — manual one-line tar.gz, a `pg_dump` guide rendered inline, and an
  optional scheduled backup (`BackupScheduler`, cron syntax, retention rotation)
  with download/delete from `/backup`.

### Operations & observability

- **Prometheus** — `/metrics` text exposition (zero deps): 11 gauges + 5
  counters (JVM, projects, sessions, push subs, builds, rate-limit, usage/disk
  warns).
- **Per-IP rate limit** — token bucket, separate `api` and `auth` buckets, admin
  sessions bypass, `429 + Retry-After`. Disable if a reverse proxy already
  throttles.
- **Server stats card** — CPU / RAM / process usage + load + uptime on the home
  dashboard.
- **Kubernetes** — minimal Helm chart at `helm/vibe-coder-server/`
  (single-replica Deployment + RWO PVC + optional PG StatefulSet + optional
  WebSocket-friendly Ingress).
- **Base image** — `eclipse-temurin:17-jdk-resolute` (Ubuntu 26.04 LTS).

### Clients & integrations

- **PWA** — installable from mobile ("Add to Home Screen") and desktop;
  service worker caches `/static/*`, network-only for `/api/*` & `/ws/*`.
- **VS Code extension** (`vscode-extension/`) — Projects TreeView, status-bar
  item, live console Output Channel, 7 palette commands.
- **CLI** (`cli/vibe`) — single-file bash + curl REST client for shell
  automation / CI.
- **Android companion** (`vibe-coder-android`, separate repo) — shares the
  `shared/` wire module; optional.

---

## Quick start (Docker, 3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# Edit .env — REQUIRED: set VIBECODER_DB_PASSWORD to a strong value.
# Also: PUID/PGID (id -u; id -g), host port — defaults work for the rest.
${EDITOR:-nano} .env

docker compose up -d            # starts postgres + vibe-coder-server

# 1. Open http://<PC IP>:17880/setup in a browser to create the admin user.
# 2. Go to "Build environment" → click "Install/update all".
#    (Android SDK download, 3-4GB, 5-15 min.)
# 3. Build environment → "Claude login" card → pick option 0/1/2/3.
# 4. Build environment → "Git Identity" card → enter user.name / user.email
#    (persisted at /home/vibe/.config/git/config; without it commits abort or
#    are recorded with a blank author).
```

> Ships a sidecar PostgreSQL container (`postgres:17-alpine`).
> `VIBECODER_DB_PASSWORD` is mandatory — compose refuses to start with an empty
> value. See [CHANGELOG.md](CHANGELOG.md) for migration notes between major
> versions.

---

## Running it

### Minimum `docker-compose.yaml` (write your own)

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
      VIBECODER_DB_HOST: postgres
      VIBECODER_DB_NAME: vibecoder
      VIBECODER_DB_USER: vibecoder
      VIBECODER_DB_PASSWORD: ${VIBECODER_DB_PASSWORD:?VIBECODER_DB_PASSWORD must be set}
      # First-boot admin auto-create (optional; otherwise use the /setup screen)
      # VIBECODER_ADMIN_USERNAME: "admin"
      # VIBECODER_ADMIN_PASSWORD: "ChangeMe123"
    ports:
      - "17880:17880"
    volumes:
      # All persistent data lives under one host directory — tar it and you've
      # backed up everything (workspace + PG + SDK + Gradle + MCP + Claude auth).
      - ./vibe-coder-data/workspace:/workspace
      - ./vibe-coder-data/server:/data
      - ./vibe-coder-data/dev-tools/android-sdk:/opt/android-sdk
      - ./vibe-coder-data/dev-tools/gradle:/home/vibe/.gradle
      - ./vibe-coder-data/dev-tools/npm-global:/home/vibe/.local
      - ./vibe-coder-data/dev-tools/npm-cache:/home/vibe/.npm
      - ./vibe-coder-data/dev-tools/playwright:/home/vibe/.cache/ms-playwright
      - ./vibe-coder-data/dev-tools/config:/home/vibe/.config
      # SSH key for git over SSH — entrypoint auto-generates an ED25519 keypair
      # on first boot and never overwrites it. View/regenerate at Settings → SSH Key.
      - ./vibe-coder-data/dev-tools/ssh:/home/vibe/.ssh
      # Android signing keystores — managed at Settings → Keystores.
      # BACK THIS UP: losing the release key blocks Play Store updates forever.
      - ./vibe-coder-data/dev-tools/keystores:/home/vibe/keystores
      - ./vibe-coder-data/claude:/home/vibe/.claude
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:17880/health"]
      interval: 30s
      timeout: 5s
      start_period: 60s
      retries: 3
```

### Common operations

```bash
docker compose logs -f vibe-coder-server             # tail server logs
docker compose restart vibe-coder-server             # restart
docker exec -it vibe-coder-server bash               # shell (root)
docker exec -it --user vibe vibe-coder-server bash   # shell (vibe user)
docker exec -it --user vibe vibe-coder-server claude --version

# Upgrade (data preserved)
docker compose pull
docker compose up -d --force-recreate
```

### Data persists across image upgrades

Every persistent path lives under one host directory (`./vibe-coder-data/`), so
`docker compose pull && up -d` never deletes your SDK, Gradle cache, MCP
servers, Playwright browsers, or Claude auth.

| Data                              | Host path                                           | Container path                  | On recreate |
|---|---|---|---|
| Project sources + APKs            | `./vibe-coder-data/workspace/`                      | `/workspace`                    | ✅ kept |
| PostgreSQL data                   | `./vibe-coder-data/postgres/`                       | `/var/lib/postgresql/data` (PG container) | ✅ kept |
| Server logs + build metadata      | `./vibe-coder-data/server/`                         | `/data`                         | ✅ kept |
| Android SDK (3-4 GB)              | `./vibe-coder-data/dev-tools/android-sdk/`          | `/opt/android-sdk`              | ✅ kept |
| Gradle dependency cache (1-2 GB)  | `./vibe-coder-data/dev-tools/gradle/`               | `/home/vibe/.gradle`            | ✅ kept |
| MCP server packages (`npm -g`)    | `./vibe-coder-data/dev-tools/npm-global/`           | `/home/vibe/.local`             | ✅ kept |
| npx cache                         | `./vibe-coder-data/dev-tools/npm-cache/`            | `/home/vibe/.npm`               | ✅ kept |
| Playwright browsers (optional)    | `./vibe-coder-data/dev-tools/playwright/`           | `/home/vibe/.cache/ms-playwright` | ✅ kept |
| Other tool config                 | `./vibe-coder-data/dev-tools/config/`               | `/home/vibe/.config`            | ✅ kept |
| SSH key                           | `./vibe-coder-data/dev-tools/ssh/`                  | `/home/vibe/.ssh`               | ✅ kept |
| Android keystores ⚠️              | `./vibe-coder-data/dev-tools/keystores/`            | `/home/vibe/keystores`          | ✅ kept (back up!) |
| Claude auth (OAuth / API key / MCP) | `./vibe-coder-data/claude/`                       | `/home/vibe/.claude`            | ✅ kept |
| Server body (Ktor + Claude CLI + JDK + Node) | image layer                              | —                               | 🔄 replaced |

```bash
# Backup, one line
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/

# Move to another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

⚠️ **`docker compose down -v` removes named volumes.** The bundled compose uses
bind mounts only (no named volumes), but watch out if you mixed in legacy state.
For regular upgrades, always `up -d --force-recreate`.

### Build / run locally (without Docker)

You need a reachable PostgreSQL instance (host install, separate Docker
container, or remote PG). Point the server at it via env vars:

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
`docker compose up -d postgres` and reusing the same `.env` is the easiest path.

---

## Reference

### Web routes

All routes sit at the root (no `/admin/*` prefix). Bearer auth or session cookie
required except `/setup`, `/login`, `/health`. Every SSR POST carries a CSRF
`_csrf` token.

| Path | Purpose |
|---|---|
| `/` | Dashboard (server / environment / activity summary + server-stats card) |
| `/projects` | Project list + register form; drag-reorder (☰), page size 20/50/100, 3-state status chips |
| `/projects/{id}` | Project tabs (console / builds / files / git / agents / history / …) |
| `/projects/{id}/console` | Claude prompt input + live log (WS) + quick-prompt buttons + ▼ template dropdown + ■ stop |
| `/projects/{id}/builds` | Queue debug build + APK download + history chart + statistics; inline keystore-create form when none is linked |
| `/projects/{id}/builds/{buildId}` | Build detail + live log + cancel + signature + comparison card |
| `POST /projects/{id}/keystore` | Create a keystore for this project (package locked → auto-linked) |
| `/projects/{id}/overview` | Edit display name / package / folder |
| `/projects/{id}/tree` | Filesystem browser inside the project workspace |
| `/projects/{id}/view?path=…&line=N` | Read-only view (highlight.js) ↔ edit mode |
| `/projects/{id}/files` | Upload / download / delete |
| `/projects/{id}/mcp` | Per-project `.mcp.json` editor + live connection-status card |
| `/projects/{id}/git` | git status / diff / log (read-only) + commit & push form |
| `/projects/{id}/history` | Persistent prompt/response history (filter / paginate / agent filter) |
| `/projects/{id}/env-files` | Whitelist-edit `local.properties` / `.env` / `build.gradle.kts` |
| `/projects/{id}/deps` | Gradle dependency tree + coord extraction |
| `/projects/{id}/automation` | Cron build schedule + webhook secret management |
| `/projects/{id}/automation/prompts` | Prompt automation — repeat/sequence presets, start/stop, run history |
| `/projects/{id}/wrapper` | Gradle wrapper version + upgrade |
| `/projects/{id}/stats` | Code statistics (LoC / languages) |
| `/projects/{id}/symbols` | Symbol definition lookup (regex; Kotlin/Java) |
| `/projects/{id}/zip` | Streaming source-only zip download |
| `/projects/{id}/agents` | Sub-agent index — registered agents + live status + open-console |
| `/projects/{id}/agents/{agent}/console` | Per-agent console (independent Claude child) |
| `/memos` | Memos (global / per-project) — card grid + mini dialog; "New memo" picks scope |
| `/chat`, `/chat?c=<id>` | General Chat; multi-session sidebar (each chat = `__chat_<id>__` ghost) |
| `POST /chat/new`, `/chat/{id}/rename`, `/chat/{id}/delete` | Create / rename / delete a chat session |
| `/chat/history` | Scratch-project persistent history |
| `/prompts` | Prompt template CRUD (used by the ▼ dropdown) |
| `/history` | Cross-project conversation search |
| `/logs` | Build log grep across all projects |
| `/code-search` | Workspace-wide grep |
| `/agents` | Custom `.agents/*.md` CRUD |
| `/multi-console` | N-pane multi-project console (iframe grid) |
| `/emulator` | Headless Android emulator — start/stop + status (KVM-accelerated) |
| `/env-setup` | Build-environment status + one-click installers |
| `/env-setup/mcp` | MCP catalog (60+) — marketplace cards with Install/Remove + status |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/env-setup/tasks/{taskId}` | Live install progress (WS) |
| `/usage` | Claude `/status` + prompt-cache stats card (admin) |
| `/metrics` | Prometheus exposition (admin) |
| `/backup`, `/backup/auto/{name}` | Workspace tar.gz backup + scheduled-file download/delete (admin) |
| `/archive` | Archived projects (Tools tab) — compress a project (source + keystore) to tar.gz, remove from the list, restore/download/delete (admin) |
| `/audit` | Operational audit log (filter / paginate) |
| `/2fa`, `/webauthn` | Two-factor TOTP / passkey enrollment |
| `/settings`, `/settings/email`, `/settings/webhook`, `/settings/cache`, `/settings/cors`, `/settings/push`, `/settings/git-integrations` | Configuration (admin) |
| `/devices`, `/password` | Operations |
| `/login`, `/setup`, `/logout` | Auth |

### JSON API

Every UI feature has a matching `/api/*` endpoint with Bearer authentication.
Wire definitions live in `shared/.../ApiPath.kt` + `shared/.../dto/*.kt`.
Highlights:

**Server & projects**
- `GET /api/server/status`, `GET /api/server/stats`, `GET /api/server/environment[/check]`
- `GET /api/projects`, `POST /api/projects/register` (`sourceType=clone`, `templateId`)
- `POST /api/projects/reorder` (body `{offset, order:[id…]}` — persists custom order)

**Build**
- `POST /api/projects/{id}/build/debug`, `GET /api/projects/{id}/builds`
- `POST /api/projects/{id}/builds/{buildId}/cancel`
- `GET /api/projects/{id}/artifacts/{artifactId}/download`
- `POST /api/webhooks/build/{projectId}` (external trigger — `X-Vibe-Secret-Id` + `X-Vibe-Secret` + optional `X-Vibe-Signature`)

**Claude console & automation**
- `POST /api/projects/{id}/claude/console/{prompt|new|cancel}`, `GET .../claude/status`
- `POST /api/projects/{id}/claude/automation/{start|stop}`, `GET .../status`
- `GET/POST /api/prompt-automations`, `PUT/DELETE /api/prompt-automations/{presetId}`
- `GET /api/prompt-templates`
- `GET /api/projects/{id}/claude/prompt-suggestions?prefix=…`
- `GET /api/agents`; `POST /api/projects/{id}/agents/{agent}/console/{prompt|cancel}`, `GET /api/projects/{id}/agents/active`

**History, memos & usage**
- `GET /api/projects/{id}/history`, `GET /api/chat/history` (pagination `page`/`before`; filters `sessionId`/`agent`/`starred`)
- `GET /api/history/search?q=…&role=…` (cross-project; admin)
- `GET /api/projects/{id}/history/export`, `POST /api/projects/{id}/history/import` (multipart)
- `POST /api/projects/{id}/history/{turnId}/star?starred=…`, `POST .../history/{turnId}/memo`
- `GET /api/memos[?projectId=X]`, `POST /api/memos`, `PUT/DELETE /api/memos/{memoId}` (global / per-project memos)
- `GET /api/usage` (Anthropic token + prompt-cache totals)
- `GET /api/projects/{id}/symbols?name=…`

**Git**
- `POST /api/projects/{id}/git/commit` (add → commit → optional push; non-interactive auth)

**Auth & security**
- `POST /api/auth/{setup|login|password|logout}` (`login` accepts optional `totpCode`; returns `401 totp_required`)
- `POST /api/webauthn/register/{options|verify}`, `POST /api/webauthn/assert/{options|verify}` (assert mints a session)

**Environment setup**
- `GET /api/env-setup/components`, `POST /api/env-setup/install-all`, `POST /api/env-setup/{componentId}/install`
- `POST /api/env-setup/claude-auth/upload` (multipart), `POST /api/env-setup/claude-auth/api-key`, `DELETE .../api-key/delete`
- `POST /api/env-setup/claude-login/{start|submit|cancel}`, `GET .../status`
- `GET /api/env-setup/mcp`, `POST /api/env-setup/mcp/{install|unregister}`, `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (multipart)

**Notifications, push & emulator**
- `GET /api/notifications`, `POST /api/notifications/{ack|ack-all}`
- `GET /api/push/vapid-public-key`, `POST /api/push/subscribe`, `DELETE /api/push/subscriptions/{id}`
- `GET /api/emulator/status`, `POST /emulator/{start|stop}`
- `GET /api/settings/git-integrations`, `POST .../{register|delete|ssh-keygen}`

**WebSocket**
- `/ws/projects/{id}/console/logs`, `/ws/projects/{id}/builds/{buildId}/logs`,
  `/ws/env-setup/{taskId}/logs`, `/ws/projects/{id}/agents/{agent}/console/logs`

### Auth

- `POST /api/auth/setup` — first-boot admin creation (only when DB has no admin).
- `POST /api/auth/login` — `{username, password, totpCode?}` → Bearer token +
  `vibe_session` cookie. `totpCode` is required only for 2FA users; the server
  returns `401 totp_required` then `401 invalid_totp` on a bad code.
- `POST /api/auth/password` — change password.
- `POST /api/auth/logout` — invalidate the device row (cookie + Bearer both work).

Passwords are stored as **BCrypt cost-12** hashes only. **Brute-force
protection:** account lock at 10 consecutive failures (15 min cooldown); IP
block at 30 failures / 24 h (catches credential-stuffing); timing-safe dummy
verify on missing users.

**Two-factor (TOTP)** — RFC 6238 (HMAC-SHA1, 30 s, 6 digits), zero deps. Enable
at `/2fa`; login then requires the code after the password.

**WebAuthn / Passkey** — `webauthn4j`, phishing-resistant. Enable at `/webauthn`
(Touch ID / Windows Hello / FIDO2); the login page exposes a passkey button.
Configure `server.webauthn.{rpId, rpName, origin}` to match the user-facing
hostname (LAN e.g. `rpId: vibe.local`, `origin: http://vibe.local:17880`). An
optional passwordless-only mode rejects password/TOTP for users with a passkey.

**Session idle timeout** — `security.sessionIdleTimeoutMinutes` (default 30,
`0` = unlimited); cookie and Bearer both delete the device row past the limit
and redirect to `/login?err=session_timeout`.

**Single-admin model** — this is a single-operator tool, so multi-user, the
`admin`/`member`/`viewer` roles, the `/users` UI, the `/api/users*` endpoints
and per-user project ACLs do not exist. There is exactly one admin; every
authenticated session has full access. (The `admin_users.role` column and an
empty `project_acls` table remain for schema compatibility.)

### Security boundaries

- **CSRF** — every SSR POST carries an HMAC-SHA256 `_csrf` token (multipart in
  the query string). The Bearer-header REST API is exempt.
- **WebSocket Origin check** — the handshake rejects mismatched Origins
  (cross-site WebSocket hijacking defense).
- **Workspace path safety** — `PathSafety` rejects any read/write outside
  `/workspace`; symlinks are not followed (`NOFOLLOW_LINKS`).
- **Tokens** — Bearer tokens stored hashed; plaintext returned once at issue.
  WebSocket auth: cookie automatic on same-origin; Android sends an
  `{"type":"auth","token":"…"}` first frame.
- **No raw-shell UI.** No release signing. No automation prompts that wait on
  stdin (non-interactive policy templated into every new project's
  `.claude/settings.json`).
- **External commands** have hard timeouts; cancellation calls
  `destroyForcibly`; the `claude` child can be SIGTERM'd mid-turn while keeping
  its session-id.
- **Git push policy** — only `commit` (+ optional push) is exposed; `reset`,
  force-push, branch delete and other destructive ops are not. Auth is
  non-interactive (`GIT_TERMINAL_PROMPT=0`, SSH `BatchMode=yes`).
- **Webhook SSRF defense** — only `hooks.slack.com`, `discord.com` /
  `discordapp.com`, and Telegram (`api.telegram.org`, bot-token regex) are
  accepted; no redirects followed.
- **Upload extension blacklist** — `exe`, `bat`, `cmd`, `ps1`, `sh`.
- **Audit log** — `/audit` records every operational action with user / IP /
  result / detail.

### Build matrix

| Layer | Version |
|---|---|
| Base image | `eclipse-temurin:17-jdk-resolute` (Ubuntu 26.04 LTS) |
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| PostgreSQL JDBC | 42.7.4 |
| PostgreSQL server | 17-alpine (sidecar container) |
| JDK toolchain | 17 |

---

## License

[GNU Affero General Public License v3.0](LICENSE). Modifications served over a
network must release source under the same license. Commercial use is allowed
under the copyleft obligations.

## Companion repository

`vibe-coder-android` — mobile client that talks to the same server. Both repos
share the `shared/` module (DTOs / ApiPath / WsFrame); update them in lockstep
when wire changes occur. See [CHANGELOG.md](CHANGELOG.md) for the matrix.

## Bundled tools

**CLI** (`cli/vibe`) — single-file `bash` + `curl` wrapper around the REST API:

```bash
sudo install -m 0755 cli/vibe /usr/local/bin/vibe
vibe login              # interactive — handles totp_required automatically
vibe projects
vibe console my-app "Add a settings screen with a dark mode toggle"
vibe build my-app
```

Token stored at `~/.config/vibe-coder/config` (`chmod 0600`). `jq` optional.

**VS Code extension** (`vscode-extension/`) — Projects TreeView (right-click →
follow console / send prompt / trigger build), status-bar item, live console
Output Channel, 7 palette commands.

```bash
cd vscode-extension
npm install && npm run package    # → vibe-coder-*.vsix
code --install-extension vibe-coder-*.vsix
```
