# Vibe Coder

> Mobile development console that lets an Android phone drive a PC-side Claude Code + Gradle build environment over LAN.

Vibe Coder is **not** "Android-as-a-dev-machine". It is a **remote console**: the PC runs the heavy work (Claude Code CLI, Gradle Wrapper, Git CLI, file management) and the Android app simply pairs with the server, sends prompts, streams logs, downloads the resulting debug APK, and opens the install screen.

## Repository layout

```
vibe-coder/
├─ shared/              # JVM library — @Serializable DTOs, API constants, WS frames
├─ server/              # Kotlin/JVM — Ktor server, SQLite (Exposed), workspace manager,
│                       #   Claude/Gradle/Git process runner, WebSocket log hub
└─ android-app/app/     # Android — Jetpack Compose + Hilt console UI
```

## Build matrix (per global `CLAUDE.md` §2-2-1)

| Layer | Version |
|---|---|
| Gradle wrapper | 9.5.1 |
| AGP | 9.2.0 |
| Kotlin | 2.2.20 |
| KSP | 2.2.20-2.0.3 |
| Compose BOM | 2026.05.00 |
| Hilt | 2.59.2 |
| JDK toolchain | 21 |
| minSdk / targetSdk | 26 / 35 |
| Server: Ktor | 3.1.2 |
| Server: Exposed | 0.55.0 |
| Server: SQLite JDBC | 3.46.1.3 |

## Quick start

### Server

```bash
./gradlew :server:run
```

On startup, the pairing code is printed to the console:

```
>>> Vibe Coder Server started
>>> Pairing code: 472913   (expires at 18:42:11)
>>> Server URL  : http://192.168.0.10:17880
```

### Android app

Open the project in Android Studio, run the `app` configuration on a phone in the same LAN, enter the server URL and pairing code on the Connect screen.

## 16-step end-to-end scenario

1. Start `vibe-coder-server` on PC.
2. Launch the Android app.
3. Pair with the server (URL + 6-digit code).
4. View server status / environment diagnostics.
5. Register an existing Android project on the server.
6. Pick a project.
7. Enter a Claude prompt.
8. Server runs `claude -p` in the project directory.
9. Watch the Claude log stream live.
10. Request a debug build.
11. Server runs `gradlew assembleDebug` (OS-aware: `gradlew.bat` on Windows).
12. Watch the build log stream live.
13. Download the produced APK.
14. Open the install screen via FileProvider + ACTION_VIEW.
15. View Git status / diff / log.
16. Upload screenshots / logs back to the project.

## Documentation

- Plan: `docs/01-plan/features/vibe-coder-mvp.plan.md`
- Design: `docs/02-design/features/vibe-coder-mvp.design.md`
- Analysis: `docs/03-analysis/vibe-coder-mvp.analysis.md`
- Report: `docs/04-report/vibe-coder-mvp.report.md`

## Security boundaries (MVP)

- Workspace-rooted path resolution (`PathSafety.normalizeAndCheck`) — every filesystem-touching API rejects paths outside the configured workspace root.
- Bearer token authentication with **hash-only** storage; tokens are returned to the client exactly once at pair time.
- WebSocket auth is performed by the **first message** (`{"type":"auth","token":"..."}`); the URL never carries the token.
- Upload extension blacklist: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- No raw-shell UI. No `git push`. No `git reset --hard`. No release signing.
- Every external command runs under a hard timeout and `destroyForcibly` on cancellation.
