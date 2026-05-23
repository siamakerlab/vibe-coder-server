# Vibe Coder Server

> **Slim server image for single-developer LAN-paired Android development.**
> A Ktor backend manages Claude Code, Gradle, and Git as child processes;
> the Android console app drives it remotely over the same LAN.

## Quick Reference

- **Source repository**: <https://github.com/siamakerlab/vibe-coder>
- **Issue tracker**: <https://github.com/siamakerlab/vibe-coder/issues>
- **Supported architectures**: `linux/amd64`, `linux/arm64`
- **Supported tags**: `0.4.0`, `latest`
- **Image size**: ~600MB (Android SDK and Gradle cache are downloaded into volumes separately)
- **License**: see LICENSE in the source repository

## Quick Start (3 minutes)

```bash
# 1) Pull the image
docker pull siamakerlab/vibe-coder-server:0.4.0

# 2) Grab the compose file and .env template
mkdir -p ~/vibe-coder && cd ~/vibe-coder
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/.env.example -o .env
# Edit .env to set PUID/PGID, host port, and volume paths

# 3) Boot
docker compose up -d

# 4) Admin web setup (in a browser)
#    http://<PC IP>:17880/admin → create the first password

# 5) Download the build environment (Android SDK, etc.)
docker exec -it vibe-coder vibe-doctor
```

Then sign in from the Android console app using the same server URL and username/password.

---

## Image Layout

| Layer | Contents | Size |
|---|---|---|
| Ubuntu 22.04 (slim) | base | ~30MB |
| OpenJDK 17 (JRE) | runs the vibe-coder server | ~200MB |
| Node 20 LTS + Claude Code CLI | Claude child process | ~250MB |
| git, curl, unzip, jq, tini, gosu, etc. | minimal build tooling | ~80MB |
| vibe-coder server (installDist) | Ktor app body | ~50MB |
| **Total** | | **~600MB** |

**Not bundled in the image** (doctor downloads into volumes on first run):

- Android SDK (~3–4GB)
- Gradle dependency cache (~1–2GB on the first build)
- Claude authentication credentials (mounting the host `~/.claude` is recommended)
- Optional MCP servers (e.g. Playwright Chromium)

---

## Configuration (`.env`)

Copy `.env.example` to `.env`. Key variables:

| Variable | Default | Description |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:0.4.0` | Image tag to pull |
| `PUID` / `PGID` | `1000` / `1000` | Match the host UID/GID. Use `id -u` / `id -g` to find them |
| `VIBE_PORT` | `17880` | Host port to expose |
| `VIBE_WORKSPACE` | `./workspace` | Source / build-artifact directory |
| `VIBE_DATA` | `./vibe-data` | Server metadata (SQLite, etc.) |
| `VIBE_CLAUDE_DIR` | `~/.claude` | Claude auth directory (sharing the host copy is recommended) |
| `VIBECODER_ADMIN_USERNAME` | (unset) | Auto-creates an admin on first boot |
| `VIBECODER_ADMIN_PASSWORD` | (unset) | Paired with the above. Change immediately after boot |
| `JAVA_OPTS` | `-Xmx2g …` | JVM heap. Tune to your host RAM |

### Volume Mount Layout

```
host                              container
────                              ─────────
${VIBE_WORKSPACE}              →  /workspace          (source / APKs)
${VIBE_DATA}                   →  /data               (DB / logs)
${VIBE_CLAUDE_DIR}             →  /home/vibe/.claude  (auth)
named: vibe-android-sdk        →  /opt/android-sdk    (SDK)
named: vibe-gradle-cache       →  /home/vibe/.gradle  (dependency cache)
```

Host bind mounts let your IDE/editor reach the files directly; named volumes are managed
by Docker and survive container removal.

---

## doctor

```bash
docker exec -it vibe-coder vibe-doctor              # interactive (recommended)
docker exec -it vibe-coder vibe-doctor check        # diagnostics only
docker exec    vibe-coder vibe-doctor install       # non-interactive bulk install
docker exec -it vibe-coder vibe-doctor android      # Android SDK only
docker exec -it vibe-coder vibe-doctor claude       # Claude auth only
docker exec -it vibe-coder vibe-doctor mcp          # optional MCP servers only
```

On the first run it walks through:

1. **Environment diagnostics** — JDK / Node / git / Claude CLI / workspace permissions
2. **Android SDK install** — cmdline-tools (130MB) → automatic license acceptance → platform-tools + platforms;android-35 + build-tools;35.0.0
3. **Claude auth** — preferably by mounting the host `~/.claude`, or by `claude login` inside the container
4. **Optional MCP** — filesystem, sqlite, fetch, playwright, etc. (each opt-in)
5. **Final check** — every component reports ✓

---

## Admin Web

`http://<PC IP>:17880/admin`

| Page | Purpose |
|---|---|
| `/admin/setup` | Create the admin account on first boot |
| `/admin/login` | Sign in |
| `/admin` | Dashboard (server status, environment diagnostics, recent builds) |
| `/admin/settings` | GUI editor for `server.yml` |
| `/admin/password` | Change password |
| `/admin/devices` | Paired-device list / revoke |

The Android app signs in with the same username/password.

---

## Troubleshooting

### "Permission denied" — volume permission error

Caused by `PUID` / `PGID` not matching the host user.

```bash
id -u; id -g                   # check host UID/GID
# Update PUID/PGID in .env, then
docker compose up -d --force-recreate
```

### "Build failed: SDK location not found"

doctor has not been run. Execute `docker exec -it vibe-coder vibe-doctor android`.

### Claude is not authenticated

Prefer mounting the host `~/.claude`. Otherwise run `docker exec -it vibe-coder claude login`.

### Builds are slow

The `vibe-gradle-cache` volume is filled on the first build; subsequent builds are much faster.
If you have RAM headroom, raise `JAVA_OPTS=-Xmx8g` (or similar) in `.env`.

### Windows / WSL2

Keep your project sources on the **Linux side of WSL2** (`/home/...`).
Mounting a Windows-side path (`/mnt/c/...`) makes build I/O 5–20× slower.

---

## Security Notes

- This image is **LAN-internal only.** Do not expose it on a public IP.
- Admin password policy: minimum length 8, letters and digits mixed.
- Pairing tokens / passwords are stored **as hashes only** (BCrypt cost 12, SHA-256).
- If you put the admin password in `.env` as plaintext, change it via `/admin/password` right after boot.

---

## Further Information / Build Instructions

- Full documentation + maintainer multi-arch build guide: <https://github.com/siamakerlab/vibe-coder/blob/main/docker/README.md>
- Changelog: <https://github.com/siamakerlab/vibe-coder/blob/main/CHANGELOG.md>
- Android console app and server source: in the same repository under `android-app/` and `server/`
