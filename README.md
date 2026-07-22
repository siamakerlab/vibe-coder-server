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
- **Resource guard** — memory-heavy child processes are admitted by the
  `resources.*` memory policy instead of per-provider session caps. Under
  pressure the server first closes disconnected idle TUI sessions, then blocks
  new AI/build work before the container reaches OOM.
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
- **Scheduled one-shot prompts** — the console rail's automation card ("⏰")
  schedules a single prompt that fires **when the project is idle**: at an
  absolute/relative time, or when the Claude session / weekly quota resets
  (`GET/POST /api/projects/{id}/claude/schedule`, `DELETE .../schedule/{sid}`).
- **Broadcast prompt** — send one prompt to many projects at once ("📢" next to
  the schedule button, and on the project-list header): checkbox project picker
  + prompt, `POST /api/claude/broadcast`. Excess sends queue behind the
  concurrent-turn gate and proceed in order without holding memory.
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

- **One-click build environment installer** — mobile build tools, Node +
  provider CLIs, and MCP packages are persisted under one host directory.
  New projects receive a project-local `CLAUDE.md` from their selected platform
  engine so platform-specific rules do not live in global AI memory.
- **Flutter (Android-only) SDK installer** — the `/env-setup` "Flutter" card
  clones the stable channel to `/home/vibe/.local/flutter` and precaches
  **Android artifacts only** (`flutter precache --android --no-ios --no-web …`),
  intentionally skipping iOS/web/desktop engine artifacts to save several GB of
  disk. Optional component — excluded from "Install all".
- **Codex CLI installer (optional)** — the `/env-setup` "Codex CLI" card installs
  OpenAI's `@openai/codex` via npm into `/home/vibe/.local` (the npm-global volume),
  so it survives image updates. Login/config is kept under `CODEX_HOME=/home/vibe/.codex`
  (the `dev-tools/codex` volume), so `codex login` persists across redeploys. Optional —
  excluded from "Install all"; install via the card or `vibe-doctor codex`.
- **SSH server installer (optional)** — the `/env-setup` "SSH server" card installs
  OpenSSH server, applies the selected container port, and starts `sshd` for
  `ssh -p <port> vibe@<host>`. Compose exposes `${VIBE_SSH_PORT:-2222}` to
  `${VIBECODER_SSH_PORT:-2222}`; usually set only `VIBE_SSH_PORT`, and set
  `VIBECODER_SSH_PORT` too only when changing the container port from the card.
  `sshd` is key-only (`PasswordAuthentication no`) since the `vibe` account has no
  password, so the card provisions the login key two ways: **register your public
  key** (pasted into `~/.ssh/authorized_keys`, private key never leaves your machine)
  or **issue an access key** (server generates a dedicated `access_ed25519` keypair,
  registers its public key, and offers the private key for download —
  `ssh -i vibe-access -p <port> vibe@<host>`). The card shows the ready `ssh` command
  and the fingerprints of authorized keys.
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
- **Keystore management** — Settings → Keystores generates release / debug
  keystores, release / debug `.properties`, and optional AdMob files per package.
  Auto-applied to builds in two layers:
  `BuildService` injects `-Pandroid.injected.signing.*` for the release variant,
  and "Apply to project" sends a prompt to the selected project console provider that wires
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
  TestFlight (via `app-store-connect` MCP). App Store Connect key metadata and
  `.p8` files can be kept in the server-owned secret store; private key content
  is never returned by the API.
- **Headless Android emulator pool** — `/emulator` manages up to five
  KVM-accelerated AVD slots (`/dev/kvm`) across phone, tablet, and foldable
  profiles. Project leases prevent two projects from targeting the same serial.

### Project tooling

- **Register a project** — empty, git clone (public, or private via HTTPS PAT
  or auto-generated ed25519 SSH key), or a built-in template (`empty`,
  `compose-basic`, `compose-mvvm-hilt`, `compose-mvvm-room`, `wear-os`,
  `android-tv`; each seeds a starter prompt). Project type is **Kotlin,
  Flutter, or iPhone**. Each type has its own platform engine for project-local
  `CLAUDE.md`, MCP/skill/agent recommendations, and build/tooling boundaries.
  Clones auto-detect `pubspec.yaml` as Flutter and root `.xcodeproj` /
  `.xcworkspace` as iPhone with a mismatch-confirmation dialog.
- **iPhone macOS agent requirements** — see
  [docs/iphone-macos-agent-requirements.md](docs/iphone-macos-agent-requirements.md)
  for MacBook local mode, remote macOS SSH agent, Xcode/Simulator, signing, and
  Apple Developer account prerequisites.
- **Rename name / package / folder** — `/projects/{id}/overview` edits the
  display name (anytime), the `applicationId`, and the folder/project-id (the
  last two require the project to be idle). Renaming the package updates the DB,
  renames keystore files, and prompts Claude to refactor code/manifest/signing;
  renaming the folder moves workspace dirs and migrates the DB primary key
  across all child tables in one transaction.
- **In-browser file tree + editor** — `/projects/{id}/tree` browses the
  workspace; `/projects/{id}/view` opens a CodeMirror editor with Kotlin / Java /
  Swift / XML / JSON / YAML / Markdown / properties / shell modes. 1 MB /
  binary / symlink guards.
- **Env files quick edit** — `/projects/{id}/env-files` edits a whitelist of 7
  files (`local.properties`, `gradle.properties`, `.env`, `.env.local`, the
  three `*.gradle.kts`). Atomic write, 256 KB cap, secret-content warning.
- **Code statistics** — `/projects/{id}/stats` reports file count / LoC / size
  per language across 35+ languages (no external `cloc`).
- **Dependency audit** — `/projects/{id}/deps` runs
  `gradlew :{module}:dependencies` and extracts `group:name:version`.
- **Symbol lookup** — `/projects/{id}/symbols` finds Kotlin/Java/Swift
  declarations, including Swift `struct`, `class`, `protocol`, `enum`, `func`,
  and SwiftUI `View` conformers, then links directly into the file editor line.
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
  delete, and a one-click "apply signing to build.gradle.kts" console-provider prompt.
  You can also **upload an existing keystore set** (release keystore / debug
  keystore / release properties / debug properties): the server stages them under
  the canonical `<pkg>.*` names (properties `storeFile` normalized to the host
  path), then a **single console prompt** has the selected provider move them into
  place (backing up any existing file as `.bak.<ts>`), apply signing to
  `build.gradle.kts`, and clean up the staging dir — all in one turn. Allowed
  **only when the console is idle** (it fires a prompt).
  Shares the same `KeystoreService` as the global `/settings/keystores` page.

### MCP integration

- **Catalog** — 60+ Model Context Protocol servers in 10 categories, checkbox
  multi-select, per-MCP token form, recommended ★, trust tiers. The marketplace
  view (`/env-setup/mcp`) has per-card Install/Remove + a status pill.
- **Platform tooling profiles** — global default MCP installation stays
  platform-neutral (`context7`, `memory`, `sequentialthinking`, `time`).
  Kotlin, Flutter, and iPhone projects each expose different default,
  conditional, and opt-in MCP/skill/agent bundles through their platform engine.
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

`latest` and version tags are published for both `linux/amd64` and `linux/arm64`.
The same compose file works on Intel/AMD Linux hosts and Apple Silicon
(M1/M2/M3/M4) Macs; Docker pulls the matching image automatically.

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
      # Optional: enabled after the Build environment → "SSH server" card installs sshd.
      - "2222:2222"
    volumes:
      # All persistent data lives under one host directory — tar it and you've
      # backed up everything (workspace + PG + SDK + Gradle + MCP + AI provider auth/tools).
      - ./vibe-coder-data/workspace:/workspace
      - ./vibe-coder-data/server:/data
      - ./vibe-coder-data/dev-tools/android-sdk:/opt/android-sdk
      - ./vibe-coder-data/dev-tools/gradle:/home/vibe/.gradle
      - ./vibe-coder-data/dev-tools/npm-global:/home/vibe/.local
      - ./vibe-coder-data/dev-tools/npm-cache:/home/vibe/.npm
      - ./vibe-coder-data/dev-tools/playwright:/home/vibe/.cache/ms-playwright
      - ./vibe-coder-data/dev-tools/config:/home/vibe/.config
      - ./vibe-coder-data/dev-tools/codex:/home/vibe/.codex
      # SSH key for git over SSH — entrypoint auto-generates an ED25519 keypair
      # on first boot and never overwrites it. View/regenerate at Settings → SSH Key.
      - ./vibe-coder-data/dev-tools/ssh:/home/vibe/.ssh
      # Android signing keystores — managed at Settings → Keystores.
      # BACK THIS UP: losing the release key blocks Play Store updates forever.
      - ./vibe-coder-data/dev-tools/keystores:/home/vibe/keystores
      # Android Gradle plugin default debug.keystore — keeps debug fingerprints stable.
      - ./vibe-coder-data/dev-tools/android:/home/vibe/.android
      - ./vibe-coder-data/dev-tools/opencode:/home/vibe/.opencode
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
servers, Playwright browsers, Codex/OpenCode state, or Claude auth.

| Data                              | Host path                                           | Container path                  | On recreate |
|---|---|---|---|
| Project sources + APKs            | `./vibe-coder-data/workspace/`                      | `/workspace`                    | ✅ kept |
| PostgreSQL data                   | `./vibe-coder-data/postgres/`                       | `/var/lib/postgresql/data` (PG container) | ✅ kept |
| Server logs + build metadata      | `./vibe-coder-data/server/`                         | `/data`                         | ✅ kept |
| Android SDK (3-4 GB)              | `./vibe-coder-data/dev-tools/android-sdk/`          | `/opt/android-sdk`              | ✅ kept |
| Gradle dependency cache (1-2 GB)  | `./vibe-coder-data/dev-tools/gradle/`               | `/home/vibe/.gradle`            | ✅ kept |
| MCP server packages (`npm -g`)    | `./vibe-coder-data/dev-tools/npm-global/`           | `/home/vibe/.local`             | ✅ kept |
| Flutter SDK (Android-only, ~2.5 GB) | `./vibe-coder-data/dev-tools/npm-global/flutter/` | `/home/vibe/.local/flutter`     | ✅ kept (shares `.local` volume) |
| npx cache                         | `./vibe-coder-data/dev-tools/npm-cache/`            | `/home/vibe/.npm`               | ✅ kept |
| Playwright browsers (optional)    | `./vibe-coder-data/dev-tools/playwright/`           | `/home/vibe/.cache/ms-playwright` | ✅ kept |
| Other tool config                 | `./vibe-coder-data/dev-tools/config/`               | `/home/vibe/.config`            | ✅ kept |
| Codex login/config/logs           | `./vibe-coder-data/dev-tools/codex/`                | `/home/vibe/.codex`             | ✅ kept |
| SSH key                           | `./vibe-coder-data/dev-tools/ssh/`                  | `/home/vibe/.ssh`               | ✅ kept |
| Android keystores ⚠️              | `./vibe-coder-data/dev-tools/keystores/`            | `/home/vibe/keystores`          | ✅ kept (back up!) |
| Android debug keystore            | `./vibe-coder-data/dev-tools/android/`              | `/home/vibe/.android`           | ✅ kept |
| OpenCode CLI install              | `./vibe-coder-data/dev-tools/opencode/`             | `/home/vibe/.opencode`          | ✅ kept |
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
| `/projects` | Project list + register form; drag-reorder (☰), page size 20/50/100, provider status chips, Kotlin/Flutter/iPhone type badge |
| `/projects/{id}` | Project tabs (console / builds / files / git / agents / history / …) |
| `/projects/{id}/console` | Provider TUI console + live log (WS) + quick-prompt template dropdown + stop controls |
| `/projects/{id}/builds` | Queue debug build + APK download + history chart + statistics; inline keystore-create form when none is linked |
| `/projects/{id}/builds/{buildId}` | Build detail + live log + cancel + signature + comparison card |
| `POST /projects/{id}/ios/build-settings` | Save per-project iPhone/Xcode scheme, Debug/Release configuration, bundle id, and export/signing choices |
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
| `/emulator` | Headless Android emulator pool; start/stop up to five phone/tablet/foldable AVD slots |
| `/agents` | Custom `.agents/*.md` CRUD |
| `/multi-console` | N-pane multi-project console (iframe grid) |
| `/env-setup` | Build-environment status + one-click installers |
| `/env-setup/mcp` | MCP catalog (60+) — marketplace cards with Install/Remove + status |
| `/env-setup/claude-login` | Semi-automatic web OAuth |
| `/env-setup/tasks/{taskId}` | Live install progress (WS) |
| `/usage` | Claude `/status` + prompt-cache stats card (admin) |
| `/metrics` | Prometheus exposition (admin) |
| `/backup`, `/backup/auto/{name}` | Workspace tar.gz backup + scheduled-file download/delete + **project-restore upload** (`POST /backup/project-restore`) (admin) |
| `/projects/{id}/backup` | Portable per-project export — source + keystores + docs + settings as one tar.gz (`…/backup/download`); restore it on any server via the upload form on `/backup` |
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
- `GET /api/ios/preflight` → local Linux/Mac/Xcode/simctl capability snapshot for iPhone features
- `GET /api/ios/simulators` → iPhone/iPad Simulator inventory via local MacBook install or SSH macOS agent
- `POST /api/ios/simulators/{udid}/boot`, `POST /api/ios/simulators/{udid}/shutdown` → boot or stop an iPhone/iPad Simulator on the Mac agent
- `POST /api/projects/{id}/ios/simulators/{udid}/run` → install the latest iOS debug `.app`, launch it, and capture a screenshot
- `GET /api/projects/{id}/ios/simulators/{udid}/logs` → fetch recent app logs from the iPhone/iPad Simulator with a bundle-id predicate
- `GET /ws/projects/{id}/ios/simulators/{udid}/logs` → stream live Simulator app logs to the iPhone project rail
- `GET /projects/{id}/ios/simulator/screenshot` → cookie-authenticated latest Simulator screenshot preview for the web project rail
- `ios-test` build jobs keep the `.xcresult` bundle and up to five screenshot/image attachments as downloadable build artifacts
- Flutter projects default to Android-only. Add `.vibecoder-flutter-targets.properties`
  with `targets=iphone` or `targets=android,iphone` to opt into the macOS-agent iPhone build path
  (`flutter create --platforms=ios .`, `flutter build ios --debug --simulator --no-codesign`,
  `flutter build ipa --release`).
- `GET|POST /api/ios/agent-config` → iPhone local/SSH macOS agent settings
- `GET|POST /api/ios/app-store-connect-key` → App Store Connect key metadata and `.p8` private-key registration; responses never include private-key content
- `GET /api/ios/app-store-connect/diagnostics?bundleId=<id>` → read-only App Store Connect JWT/authentication and app lookup diagnostics
- `POST /api/ios/keychain/import` → admin-only macOS keychain `.p12` certificate import/unlock for iPhone signing; responses never include passwords
- `POST /api/ios/swift-tools/install` → admin-only optional SwiftLint/SwiftFormat installation on the local or SSH macOS agent
- `GET /api/projects/{id}/ios/signing-status` → Xcode signing snapshot: identities, provisioning profile metadata, bundle/team match, and expiration warnings
- `POST /api/projects/{id}/builds/{buildId}/testflight-upload` → create a TestFlight upload job and delegate the upload prompt to the selected console provider
- `GET /api/projects/{id}/testflight/uploads` → recent TestFlight upload jobs with `queued`, `uploading`, `processing`, `accepted`, or `failed` status; active uploads are also reconciled by App Store Connect build polling
- `POST /api/projects/{id}/testflight/uploads/{jobId}/status` → update TestFlight upload status and normalize known failure messages into stable `errorCode` values
- `GET /api/projects`, `POST /api/projects/register` (`sourceType=clone`, `templateId`)
- `GET /api/projects/{id}/tooling-profile` → projectType-specific MCP/skill/agent profile
- `POST /api/projects/{id}/rename` (body `{name}` — display-name rename), `DELETE /api/projects/{id}`
- `POST /api/projects/reorder` (body `{offset, order:[id…]}` — persists custom order)
**Build**
- `POST /api/projects/{id}/build/debug`, `GET /api/projects/{id}/builds`
- `POST /api/projects/{id}/build/release` (assembleRelease, APK), `POST /api/projects/{id}/build/bundle` (bundleRelease, AAB) — keystore-signed; `409 keystore_required` if no matching keystore
- `POST /api/projects/{id}/ios/build/{debug|test|archive|export-ipa}` — iPhone/Xcode jobs via local MacBook install or SSH macOS agent; logs use the same build log WebSocket
- `POST /api/projects/{id}/builds/{buildId}/cancel`
- Build JSON responses include nullable `failureKind` for classified failures such as `swift_compile_failed`, `scheme_missing`, or `profile_missing`.
- `POST /api/projects/{id}/play-upload` (body `{aabPath?, track?, releaseNotes?}` — triggers a Claude console prompt to upload the AAB to Google Play)
- `GET /api/projects/{id}/artifacts/{artifactId}/download`
- `POST /api/webhooks/build/{projectId}` (external trigger — `X-Vibe-Secret-Id` + `X-Vibe-Secret` + optional `X-Vibe-Signature`)

**Quality (Android Lint)**
- `POST /api/projects/{id}/quality/lint?module=app` → `LintResultDto` (runs `:module:lintDebug`; emulator not required)
- `POST /api/projects/{id}/quality/fix` (body `{module, kind, selected:[…]}`) → sends selected issues to the console (Claude) as a fix request

**Project archive**
- `GET /api/archives` → `[ArchivedProjectDto]`
- `POST /api/projects/{id}/archive` (idle-guarded; `409 project_busy` while running) → `ArchivedProjectDto`
- `POST /api/archives/{aid}/restore`, `DELETE /api/archives/{aid}`, `GET /api/archives/{aid}/download` (.tar.gz)

**Claude console & automation**
- `POST /api/projects/{id}/claude/console/{prompt|new|cancel|interrupt}`, `GET .../claude/status`
  (`interrupt` = stop the running turn and immediately send a new prompt — "interrupt & send")
  - v1.162.5: `prompt`/`interrupt` are TUI-only text injection paths. Non-empty
    `images` in `PromptRequestDto` are rejected with `images_unsupported`.
- `GET|POST /api/projects/{id}/console/tui/session`,
  `POST /api/projects/{id}/console/tui/prompt`,
  `POST /api/projects/{id}/console/tui/interrupt`,
  `POST /api/projects/{id}/console/tui/compact`,
  `POST /api/projects/{id}/console/tui/mode`,
  `DELETE /api/projects/{id}/console/tui/session/{sessionId}`,
  `WS /ws/projects/{id}/console/tui/{sessionId}` — project-scoped provider CLI TUI
  session over PTY/xterm.js. The prompt/compact endpoints record a user turn, then inject
  text into the provider TUI via bracketed paste. The legacy `.../claude/console/prompt`
  endpoint now routes to the same TUI runtime. `mode` is retained as a compatibility
  endpoint and keeps TUI enabled.
  Raw `terminal_input.data` is treated only as PTY stdin; clients that want a direct terminal
  submission to appear in prompt history must set nullable `TerminalInput.recordPrompt` to the
  user-visible prompt text.
  Assistant history is ingested from provider-native stores where available: Claude JSONL,
  OpenCode/GLM `opencode.db`, and Codex rollout JSONL paths referenced by `~/.codex/state_*.sqlite`.
- `GET /api/projects/{id}/claude/console/image?turn=N&idx=M` — serves an image stored in a
  conversation turn (tool-result screenshots / user attachments) for console history restore
- `POST /api/projects/{id}/claude/automation/{start|stop}`, `GET .../status`
- `GET/POST /api/projects/{id}/claude/schedule`, `DELETE .../schedule/{scheduleId}` —
  one-shot scheduled prompt (`triggerType`: `time` | `session_reset` | `weekly_reset`;
  `atEpochMs` or `delayMinutes` for `time`; fires only when the console is idle)
- `POST /api/claude/broadcast` — send one prompt to many projects (body
  `{prompt, projectIds}` → 202 `{accepted, rejected}`; max 100, queued by the
  concurrent-turn gate)
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
- `POST /api/env-setup/claude-login/{start|submit|cancel}`, `GET .../status`
- `GET /api/env-setup/mcp`, `POST /api/env-setup/mcp/{install|unregister}`, `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (multipart)
- Provider status/quota: `GET /api/server/quota` (Claude), `GET /api/server/codex-quota`,
  `GET /api/server/opencode-quota`, `GET /api/server/glm-quota` (z.ai coding plan monitor)

**Notifications, push, terminal & emulator**
- `GET /api/notifications`, `POST /api/notifications/{ack|ack-all}`
- `GET /api/push/vapid-public-key`, `POST /api/push/subscribe`, `DELETE /api/push/subscriptions/{id}`
- `GET /api/terminal/sessions`, `POST /api/terminal/sessions`, `DELETE /api/terminal/sessions/{id}`,
  `WS /ws/terminal/{id}` (workspace PTY terminal; gated by `security.allowTerminal`)
- `GET /api/emulator/status`, `GET /api/emulators`, `POST /api/emulators/{id}/{start|stop}`
- `GET|POST|DELETE /api/projects/{id}/emulator/lease` (project-level emulator allocation)
- `POST /emulator/{start|stop}` and `/emulator/{start|stop}/{id}` (SSR controls)
- `GET /api/settings/git-integrations`, `POST .../{register|delete|ssh-keygen}`

**WebSocket**
- `/ws/projects/{id}/console/logs`, `/ws/projects/{id}/builds/{buildId}/logs`,
  `/ws/env-setup/{taskId}/logs`, `/ws/projects/{id}/agents/{agent}/console/logs`,
  `/ws/projects`, `/ws/terminal/{id}`, `/ws/adb/logcat`

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

**Session idle timeout** — `security.sessionIdleTimeoutMinutes` (default 0,
`0` = unlimited); cookie and Bearer both delete the device row past the limit
and redirect to `/login?err=session_timeout`.

**Terminal session persistence** — workspace terminal (PTY) sessions stay alive
on the server when you navigate away; returning re-attaches and replays scrollback
so you can continue where you left off (multiple tabs included). A session is only
reaped after `security.terminalIdleTimeoutMinutes` (default `1440` = 24h, `0` =
unlimited) of inactivity **with no live WebSocket** — a connected (visible) terminal
is never reaped. The browser also auto-reconnects (exponential backoff plus on tab
foreground / network resume).

**Project console TUI sessions** — provider-native Claude/Codex/OpenCode PTYs are
heavier than the workspace bash terminal. Disconnected idle project console TUI
sessions are reaped after `security.consoleTuiIdleTimeoutMinutes` (default `120`;
`0` = unlimited). Starting new TUI sessions is gated only by the ResourceGuard
memory policy; under pressure it closes disconnected idle TUI sessions before
blocking new heavy work.

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
