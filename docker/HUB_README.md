# Vibe Coder Server

> **Self-hostable Android development server orchestrating Claude Code,
> Gradle, and Git as child processes.** Single Docker container, browser-only
> operation, optional Android companion client. Spin up on your PC and log in
> to create projects, send prompts, build, and download APKs — no local SDK,
> JDK, or Gradle install on the operator side.

## Why this exists

Built by and for a solo Android developer shipping a lot of small apps with
Claude Code, who wanted to keep working without lugging the dev laptop around.
Standard remote tools all fell short:

- **SSH from a phone** drops constantly and is painful for long Claude turns.
- **Debug APK install** over USB / `adb` is too many manual steps.
- **RDP / VNC** make phone-side input miserable.

So the server is Claude Code-dedicated — Docker on your home dev PC for a
consistent, reproducible environment; an optional Android companion gives a
touch-first UI for prompting, watching build output, and one-tap installing
the debug APK on the same device. Run several projects in parallel, jump
between them, save common prompts as templates, and pick up exactly where you
left off after any disconnect.

**The Android client is optional** — every feature works in the browser too,
so the server alone is a complete solution.

Built entirely by vibe-coding with Claude Code, updated continuously as the
maintainer's own workflow evolves. Features rarely used by the maintainer may
have stale bugs — please open a GitHub issue and they'll be fixed quickly.

## Quick reference

- **Source**: <https://github.com/siamakerlab/vibe-coder-server>
- **Wiki (Android client guide, REST API, MCP catalog)**:
  <https://github.com/siamakerlab/vibe-coder-server/wiki>
- **Issues**: <https://github.com/siamakerlab/vibe-coder-server/issues>
- **Architectures**: `linux/amd64` (multi-arch builds reserved for milestones).
- **Latest tags**: `1.135.0`, `latest`
- **Base OS**: Ubuntu 26.04 LTS (Resolute Raccoon) since v0.38.0
- **Image size**: ~750 MB (Android SDK / Gradle / MCP packages live in
  bind-mounted volumes — see below). v0.14.0+ runs alongside a small
  `postgres:17-alpine` sidecar container.
- **License**: AGPL-3.0

## Quick start (3 minutes)

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# v0.14.0+: set VIBECODER_DB_PASSWORD in .env — required (compose refuses empty).
# Also tweak PUID/PGID (id -u; id -g) and host port; defaults work otherwise.
${EDITOR:-nano} .env

docker compose up -d            # boots postgres + vibe-coder-server

# 1. Browser → http://<PC IP>:17880/setup  (create the first admin user)
# 2. Build environment → "Install/update all" (Android SDK, ~5-15 min)
# 3. Build environment → Claude login card → pick one of 4 options
# 4. Projects → New project (empty or git clone) → console / build / download
```

> **Upgrading from v0.13.x?** v0.14.0 swaps SQLite for PostgreSQL — fresh
> start required (admin / projects re-created; workspace files preserved).
> See the v0.14.0 entry in
> [CHANGELOG.md](https://github.com/siamakerlab/vibe-coder-server/blob/main/CHANGELOG.md)
> for the exact steps.

## What's in the box (v1.135.0)

**Claude orchestration**
- One persistent Claude Code child per project — stream-json IO, live console
  over WebSocket, friendly one-line rendering for every tool event, ■ stop /
  interrupt-and-send, session-id preserved across restarts (`--resume`).
- Four auth options: terminal, credential file upload, API key, and a
  semi-automatic web OAuth (`script -q` PTY wrap).
- General Chat (project-less console), prompt template library, prompt
  suggestions, custom agents (`~/.claude/agents` CRUD), and a real
  multi-agent **sub-agent pool** with per-agent consoles.
- **Prompt automation** (repeat / sequence autopilot) + **one-shot scheduled
  prompts** (fire at a set time or when the Claude quota resets).
- **Concurrency & memory guards** — concurrent-turn cap (default 3) and
  resident-session cap (default 6, LRU pause + resume); queued prompts don't
  spawn Claude processes while waiting.
- Conversation history persisted in PostgreSQL — cross-project search
  (Korean trigram, optional mecab-ko), memos / stars, export / import,
  token-usage & prompt-cache stats, console image attachments (vision).

**Projects**
- Register empty / git clone (HTTPS PAT or auto-generated ed25519 SSH key) /
  built-in Compose templates; **Kotlin or Flutter (Android-only)** project
  types with `pubspec.yaml` auto-detection on clone.
- In-browser file tree + editor (highlight.js), workspace code search,
  per-language stats, dependency audit, env-file whitelist editing, source
  zip download.
- Rename display name / package / folder; portable **project backup /
  restore** (single tar.gz: source + keystores + docs + settings); project
  archive.

**Build & deploy**
- Gradle / `flutter build apk` toolchains — debug & release APK / AAB, live
  build logs, history charts + statistics, build comparison, APK signature
  inspection, per-package keystore management.
- Cron build schedules + external HMAC-signed build webhooks; Play Console
  and TestFlight upload via MCP delegation.
- Build environment one-click installer — Android SDK, Gradle, Node + Claude
  CLI, MCP packages, optional Flutter SDK; everything in bind-mounted
  volumes.
- MCP catalog with 60+ servers (trust tiers, per-MCP token forms); headless
  Android emulator (KVM) with wireless ADB.

**Operations & security**
- Single-admin model (multi-user roles were removed in v1.45.0). BCrypt +
  Bearer/cookie auth, CSRF on every SSR POST, IP brute-force throttling,
  TOTP 2FA, WebAuthn passkeys, audit log, per-IP API rate limit.
- Notifications: SMTP email, Slack / Discord / Telegram webhooks, encrypted
  Web Push; Claude usage & disk monitors.
- Prometheus `/metrics`, workspace backups (`/backup` + scheduler), PWA,
  VS Code extension, bundled CLI (`cli/vibe`), i18n (en / ko), Kubernetes
  Helm chart.
- **JSON API parity** — every browser feature is also at `/api/*` with
  Bearer auth, for the Android companion client or third-party automation.

## Image layout (~750 MB)

| Layer | Contents | Size |
|---|---|---|
| Ubuntu 26.04 LTS (Resolute Raccoon) | base | ~30 MB |
| OpenJDK 17 (JRE) | runs the vibe-coder server | ~200 MB |
| Node 20 LTS + Claude Code CLI | Claude child process | ~250 MB |
| git, curl, unzip, jq, tini, gosu, util-linux, sudo | minimal tooling | ~80 MB |
| ImageMagick · Pillow (python3) · rsvg · webp · poppler · ghostscript | image tooling (v1.92.0) | ~150 MB |
| vibe-coder server (Ktor installDist) | app body | ~50 MB |

> **Image tools + sudo** (v1.92.0): ImageMagick, Pillow (`python3-pil` +
> NumPy), `rsvg-convert`, `cwebp`/`dwebp`, `poppler-utils`, Ghostscript, and
> `optipng`/`pngquant`/`jpegoptim` are pre-installed so Claude can handle
> screenshots, mockups, icons, and APK resources out of the box. Anything else
> installs through the `vibe` user's passwordless sudo
> (`sudo apt-get update && sudo apt-get install <pkg>`).

**Not bundled** (operator installs into volumes on first run via the
`/env-setup` page):

- Android SDK (~3-4 GB)
- Gradle dependency cache (~1-2 GB on first build)
- Claude OAuth credentials / API key
- MCP servers from the catalog (`npm install -g <pkg>`)
- Playwright browsers (optional, ~300 MB)

## Configuration (`.env`)

| Variable | Default | Description |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:latest` | Image tag to pull (pin to a specific `0.x.y` for reproducibility) |
| `VIBECODER_POSTGRES_IMAGE` | `postgres:17-alpine` | PG sidecar image (v0.14.0+) |
| **`VIBECODER_DB_PASSWORD`** | (required) | **Must be set.** compose refuses to start with empty value |
| `VIBECODER_DB_HOST` | `postgres` | DB host. Use `host:port` for an external PG |
| `VIBECODER_DB_NAME` / `_USER` | `vibecoder` / `vibecoder` | DB name & user |
| `VIBECODER_DB_SSLMODE` | `disable` | `prefer`/`require`/`verify-ca`/`verify-full` |
| `PUID` / `PGID` | `1000` / `1000` | Match host UID/GID (`id -u` / `id -g`) |
| `VIBE_PORT` | `17880` | Host port to expose |
| `VIBE_DATA_ROOT` | `./vibe-coder-data` | **Unified host directory** holding everything persistent |
| `VIBE_CLAUDE_DIR` | `${VIBE_DATA_ROOT}/claude` | Override to `~/.claude` to share host's Claude auth |
| `VIBECODER_ADMIN_USERNAME` | (unset) | Auto-create admin on first boot |
| `VIBECODER_ADMIN_PASSWORD` | (unset) | Pair with above. Change via `/password` immediately |
| `JAVA_OPTS` | `-Xmx2g …` | JVM heap — tune to host RAM |

### Volume layout (v0.7.0+ — unified)

All persistent data lives in **one host directory** (`./vibe-coder-data/`).
`tar` it and you've backed up everything: workspace + DB + Android SDK +
Gradle + MCP + Playwright + Claude auth.

```
${VIBE_DATA_ROOT}/                          container
─────────────────                           ─────────
├── workspace/                  →  /workspace                       (sources + APKs)
├── postgres/                   →  vibe-coder-postgres : /var/lib/postgresql/data  (v0.14.0+)
├── server/                     →  /data                            (logs + build metadata)
├── dev-tools/
│   ├── android-sdk/            →  /opt/android-sdk                 (3-4 GB)
│   ├── gradle/                 →  /home/vibe/.gradle               (1-2 GB)
│   ├── npm-global/             →  /home/vibe/.local                (MCP packages)
│   ├── npm-cache/              →  /home/vibe/.npm                  (npx cache)
│   ├── playwright/             →  /home/vibe/.cache/ms-playwright  (optional)
│   ├── config/                 →  /home/vibe/.config               (tool config)
│   ├── ssh/                    →  /home/vibe/.ssh                  (v1.2.0+ SSH key — auto-generated on first boot)
│   └── keystores/              →  /home/vibe/keystores             (v1.5.0+ Android signing keys ⚠️ back up!)
└── claude/                     →  /home/vibe/.claude               (OAuth / API key / MCP registrations)
```

> **v1.2.0+ SSH key auto-provisioning.** On first boot the entrypoint generates
> an ED25519 keypair at `dev-tools/ssh/id_ed25519`. Once present, it is
> **never overwritten** on subsequent boots — your key survives image
> upgrades. View / copy / regenerate it under Settings → SSH Key in the admin
> UI. Requires `openssh-client` in the image (included since v1.3.1; v1.2.0
> and v1.3.0 had a missing-package regression — upgrade to v1.3.1 or later).

> **v1.5.0+ Android keystore management** ⚠️. Settings → Keystores creates a
> 4-file set per package (release / debug / Gradle properties / optional
> AdMob IDs) under `dev-tools/keystores/`. Pre-fill the form by editing
> `keystore.defaults` in `server.yml` (CN / O / OU / country / city +
> default password) — then only the package name has to be typed for each
> new app. **Losing the release key blocks Play Store updates forever**, so
> back up `dev-tools/keystores/` immediately after creation.

> **v0.7.0 fixed a data-loss bug.** Pre-0.7.0 stored MCP servers in the
> image's system directory (`/usr/local/lib/node_modules`), so they vanished
> on `docker compose pull && up -d`. v0.7.0+ routes them to a bind mount.
> See the [Upgrade Guide](https://github.com/siamakerlab/vibe-coder-server/wiki/Upgrade-Guide) on the wiki.

### Backup / migrate

Stop the containers (especially `postgres`) before snapshotting to guarantee
file consistency.

```bash
# Snapshot everything (workspace + PostgreSQL data + dev-tools + Claude auth)
docker compose stop
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/
docker compose start

# Or: PostgreSQL logical dump while the server keeps running
docker exec vibe-coder-postgres pg_dump -U vibecoder -F c vibecoder > vibe-pg-$(date +%F).pgdump

# On another machine
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost 'cd ~/vibe-coder && tar xzf vibe-coder-data-*.tar.gz && docker compose up -d'
```

`vibe-coder-data/postgres/` is owned by the `postgres` user inside the
container (UID 70 in alpine images). On the host you may need `sudo` to read
files directly. Either use `tar` with sudo, or do logical `pg_dump` against
the running container.

## Web UI routes (v1.135.0)

All routes sit at the root (no `/admin/*` prefix). Bearer token or session
cookie required except `/setup`, `/login`, `/health`.

| Path | Purpose |
|---|---|
| `/` | Dashboard (server / environment / activity summary) |
| `/projects` · `/projects/{id}` | List + register; project tabs (console / builds / files / git / agents / history …) |
| `/projects/{id}/console` | Live Claude chat (WebSocket) + templates + automation rail |
| `/projects/{id}/builds` | Queue builds, APK/AAB download, charts, comparison |
| `/chat` · `/multi-console` | Project-less chat; up to 6 consoles in a grid |
| `/history` · `/usage` · `/audit` · `/logs` | Cross-project search / usage stats / audit / build-log grep |
| `/env-setup` (+ `/mcp`, `/claude-login`) | Build environment installer, MCP catalog, Claude OAuth |
| `/backup` · `/archive` | Workspace backup + project restore; archived projects |
| `/emulator` · `/adb` | Headless AVD lifecycle; wireless ADB pairing |
| `/settings…` · `/devices` · `/password` · `/2fa` · `/webauthn` | Operations & security |
| `/metrics` | Prometheus exposition |

Full route table: [GitHub README](https://github.com/siamakerlab/vibe-coder-server#web-routes).


## JSON API (v1.135.0 — for clients)

Full reference + curl examples in the
[REST API Reference](https://github.com/siamakerlab/vibe-coder-server/wiki/REST-API-Reference)
wiki; Retrofit interfaces in the
[Android Client Guide](https://github.com/siamakerlab/vibe-coder-server/wiki/Android-Client-Guide).

Highlights:

- `POST /api/auth/login` → Bearer token (2FA-aware)
- `GET/POST /api/projects…` — register (clone / template / `projectType`), rename, reorder, archive
- `POST /api/projects/{id}/build/{debug|release|bundle}`, builds list / cancel, artifact download, `play-upload`
- `POST /api/projects/{id}/claude/console/{prompt|new|cancel|interrupt}` (+ image attachments), `GET …/claude/status`
- `…/claude/automation/{start|stop}` + presets; `…/claude/schedule` (one-shot scheduled prompts)
- `GET /api/projects/{id}/history`, `/api/history/search`, `/api/usage`, quality lint/fix, prompt suggestions
- `GET /api/env-setup/components`, MCP install / unregister, Claude auth upload / api-key / web OAuth
- WebSocket: `/ws/projects/{id}/console/logs`, `/ws/builds/{buildId}/logs`, `/ws/env-setup/{taskId}/logs`


## Common operations

```bash
docker compose logs -f vibe-coder-server                # tail server logs
docker compose restart vibe-coder-server                # restart
docker exec -it vibe-coder-server bash                  # shell (root)
docker exec -it --user vibe vibe-coder-server bash      # shell (vibe user)
docker exec -it --user vibe vibe-coder-server claude --version

# Upgrade (data preserved)
docker compose pull
docker compose up -d --force-recreate
```

## vibe-doctor (CLI alternative to the web UI)

```bash
docker exec -it vibe-coder-server vibe-doctor                # interactive (recommended)
docker exec -it vibe-coder-server vibe-doctor check          # diagnostics only
docker exec    vibe-coder-server vibe-doctor install         # non-interactive bulk install
docker exec -it vibe-coder-server vibe-doctor android        # Android SDK only
docker exec -it vibe-coder-server vibe-doctor claude         # Claude auth helper
docker exec -it vibe-coder-server vibe-doctor mcp            # prompt-based MCP picker
```

## Troubleshooting

### "Permission denied" — volume permission error
`PUID` / `PGID` don't match host. `id -u; id -g`, update `.env`, then
`docker compose up -d --force-recreate`.

### "Build failed: SDK location not found"
Run the build environment installer (web UI) or `vibe-doctor android`.

### Claude says "Not logged in" but UI shows ✓
Either you ran `claude login` as root (need `--user vibe`) or your token
expired (≥30 days unused — re-login). Diagnostic detects both from v0.6.2+.

### MCP installed but Claude doesn't see it
Make sure `~/.claude/.mcp.json` has the entry. The MCP catalog UI writes
this automatically; manual installations need a hand-edit.

### Windows / WSL2 builds are slow
Keep project sources on the **Linux side of WSL2** (`/home/...`), not
under `/mnt/c/...` (5-20× slower I/O).

Full troubleshooting catalog:
<https://github.com/siamakerlab/vibe-coder-server/wiki/Troubleshooting>

## Security notes

- **LAN-internal only.** Do not expose this port on a public IP. Use VPN
  (Tailscale / WireGuard) or a reverse proxy with HTTPS + auth for remote
  access.
- Admin password policy: ≥ 8 chars, mixed letters + digits.
- Passwords stored as BCrypt cost-12 hashes. Bearer tokens stored as
  hashes too; plaintext returned only at issue.
- 10 failed logins → 15-min account lock (timing-safe).
- All disk operations validated by `PathSafety` (no `..` escape from
  workspace).
- No raw shell endpoint; no web terminal emulator.

Full model: <https://github.com/siamakerlab/vibe-coder-server/wiki/Security-Model>

## Build instructions (maintainer)

```bash
# Regular development push — slim (amd64-only, fast 2-3 min)
docker buildx build --platform linux/amd64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<ver> \
    -t siamakerlab/vibe-coder-server:latest \
    --push .


# Milestone multi-arch slim (slow 10-15 min via arm64 emulation)
docker buildx build --platform linux/amd64,linux/arm64 ...
```

Full guide: <https://github.com/siamakerlab/vibe-coder-server/blob/main/docker/README.md>

## Links

- Source code: <https://github.com/siamakerlab/vibe-coder-server>
- Wiki: <https://github.com/siamakerlab/vibe-coder-server/wiki>
- Changelog: <https://github.com/siamakerlab/vibe-coder-server/blob/main/CHANGELOG.md>
- License (AGPL-3.0): <https://github.com/siamakerlab/vibe-coder-server/blob/main/LICENSE>
