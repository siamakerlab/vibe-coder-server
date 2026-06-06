# vibe-coder-server Container Global Rules

This file is mounted as `/home/vibe/.claude/CLAUDE.md` inside the container and applies globally to every Claude Code session that runs there. The host path is `./vibe-coder-data/claude/CLAUDE.md`.

> This is the default template auto-seeded on first server start. Feel free to edit it;
> once it exists the server never overwrites it. (Global CLAUDE.md tab: `/settings/claude-md`)
> Switching the UI language between English/Korean re-seeds this file in that language
> **only while it is still the unmodified seed** — your own edits are always preserved.

## Table of contents

1. Response language · 2. Required steps after every code change (top priority) · 3. Versioning ·
4. Debug build package name · 5. Signing keystore · 6. Build environment (tools/Gradle/SDK/env) ·
7. Design principles · 8. Naming rules · 9. Git · 10. Documentation · 11. Never do · 12. Guiding principles ·
13. Android Compose UI pitfalls · 14. Emulator run / screenshots — no autonomous execution

---

## 1. Response language

- Always respond to the user in **English**.
- Keep code, commands, identifiers, and proper nouns verbatim (do not translate them).

## 2. ⚠ Required steps after every code change (top priority — never skip)

**After every code change, run these in order, with nothing omitted:**

1. **Build test** — confirm compile/build passes (incl. unit tests). **Here "build test" means compile + build + unit tests; it does NOT include running an emulator or reviewing screenshots (§14).**
2. **Automatic versioning** — bump `versionName`/`versionCode` (§3).
3. **Update `CHANGELOG.md`** (§10).
4. **git commit / push** (§9).

**Never skip this sequence.** (The single most important rule.)

## 3. Versioning (delegated to Claude Code)

Versioning is **fully automated by Claude Code**. The user does not bump versions manually;
**on every code change** Claude Code updates the version per the rules below. Apply the update to
`versionName`/`versionCode` in `app/build.gradle(.kts)` and include it in the same commit.

### 3.1 versionName = `major.minor.patch` (SemVer)

Claude Code decides the level by change type:

- **MAJOR**: breaking change (public API removal/signature change, incompatible data schema, destructive workflow change, first 0.x→1.0.0 production).
- **MINOR**: backward-compatible new feature/screen/setting, a meaningful UX revamp.
- **PATCH**: backward-compatible bug fix, perf/security improvement, refactor, minor UI tweak, docs.
- **Default is PATCH ++1** — bump at least the patch on every code change (minor/major for larger changes).
- Under 0.x (pre-1.0) a breaking change may be handled as MINOR (SemVer 2.0.0 §4).

### 3.2 versionCode = `YYMMDDRRR` (9 digits, e.g. `260531001`)

- **Increment `RRR` by 1 on every code change.**
- **When the date changes, `RRR` restarts at `001`** (e.g. if the last on 5/31 was `260531007`, the first on 6/1 is `260601001`).
- Second change the same day = `...002`, third = `...003`, …

### 3.3 Display

- **In-app version display** uses `BuildConfig` — never hardcode the version string in UI/code.

## 4. Debug build package name

- Debug builds append a **`.debug` suffix to the release applicationId** — `buildTypes.debug { applicationIdSuffix = ".debug" }`,
  so release and debug can be installed side by side on one device without conflict.
- The **release applicationId stays unchanged** (do not modify it).
- Keystore filenames are keyed by the **release applicationId** (`<applicationId>.keystore` / `<applicationId>-debug.keystore`).
  So the debug variant's appId is `<applicationId>.debug`, but its signing key file is `-debug.keystore`.

## 5. Signing keystore — per-project file locations

Android signing keystores live on the **host-persistent volume** `/home/vibe/keystores/`,
prefixed by **applicationId** (the operator creates them in the vibe-coder server UI).
Replace `<applicationId>` below with the real applicationId from `app/build.gradle(.kts)`:

| File | Purpose |
|---|---|
| `/home/vibe/keystores/<applicationId>.keystore` | Release signing key (PKCS12) |
| `/home/vibe/keystores/<applicationId>-debug.keystore` | Debug signing key |
| `/home/vibe/keystores/<applicationId>-keystore.properties` | Gradle signing config (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) |
| `/home/vibe/keystores/<applicationId>-admob.properties` | (optional) AdMob IDs |

- Load the `.properties` file in `signingConfigs` via `Properties().load(FileInputStream(...))`.
  **Never hardcode passwords/alias in build.gradle** — reference only the properties file path.
- **Never create a new keystore yourself** (operator policy). If a file is missing, stop the build
  and tell the operator to create it for that applicationId under **Settings → Keystores
  (`/settings/keystores`)** in the vibe-coder server. AGP's default `debug.keystore` auto-generation
  is also forbidden. (§11 "storing keystores inside the project is forbidden".)

### AdMob ad-ID file key schema (must follow)

When relocating hardcoded AdMob ad IDs into `<applicationId>-admob.properties`, **always use the
standard keys below.** (The vibe-coder server's Keystore → AdMob ID section reads/displays by this key
convention. Do not invent a different key format each time, e.g. `admob.bannerId` / `release.admob_banner_id`.)

- Put **only production (real) IDs** in this file. Keys:
  - `admobAppId=ca-app-pub-XXXX~YYYY`
  - `appOpenAdUnitId` / `bannerAdUnitId` / `nativeAdUnitId` / `interstitialAdUnitId` /
    `rewardedAdUnitId` / `rewardedInterstitialAdUnitId` = `ca-app-pub-XXXX/ZZZZ` (comma-separate multiples)
- **Do NOT put test IDs (Google official `ca-app-pub-3940256099942544/…`) in this file.** Handle debug-build
  test ads directly in the build.gradle debug variant using Google's official test constants.
- build.gradle loads this file via `Properties` and injects manifestPlaceholders (App ID) + buildConfigField
  (unit IDs, `split(",")`). Never hardcode ad IDs in source.

## 6. Build environment

### 6.1 ⚠ Pre-installed tools (do NOT re-download)

vibe-coder-server's `/env-setup` (or `vibe-doctor install`) has already installed the tools below.
**Do not download new copies — always use the pre-installed binaries at these paths.**

| Tool | Path | Env var | Notes |
|---|---|---|---|
| **Gradle (system install)** | `/home/vibe/.local/gradle/` | on PATH (`gradle` = `/home/vibe/.local/bin/gradle`) | Persistent volume. **Version not pinned — run `gradle --version` before building** |
| **Gradle wrapper cache** | `/home/vibe/.gradle/wrapper/dists/` | — | Pre-cached dists; `ls` to confirm and reuse (never hardcode a version) |
| **Gradle dependency cache** | `/home/vibe/.gradle/caches/` | — | Maven Central / Google dependencies |
| **Android SDK** | `/opt/android-sdk` | `ANDROID_HOME`, `ANDROID_SDK_ROOT` | |
| **Android cmdline-tools** | `/opt/android-sdk/cmdline-tools/latest/bin/` | on PATH (`sdkmanager`, `avdmanager`) | |
| **Android platform-tools** | `/opt/android-sdk/platform-tools/` | on PATH (`adb`) | |
| **JDK 17** | `/opt/java/openjdk` | `JAVA_HOME` | OpenJDK 17 |
| **Node 20 LTS** | `/usr/bin/node`, `/usr/bin/npm`, `/usr/bin/npx` | — | |
| **npm global prefix** | `/home/vibe/.local/` (`bin/`, `lib/node_modules/`) | — | MCP `npm install -g` lands here |
| **Playwright browsers** | `/home/vibe/.cache/ms-playwright/` | — | When using the Playwright MCP |

### 6.2 Gradle policy (important)

**Never hardcode a Gradle version in docs or commands.** The system install and the pre-cached
wrapper dists change over time (upgrades), so **query the actual state first** and match it before
building. This way an upgraded Gradle is handled automatically with no doc edits.

```bash
# 1) Check the installed gradle version
gradle --version            # e.g. "Gradle 9.5.1" -> INSTALLED_VER=9.5.1
# 2) Check pre-cached wrapper dists (versions already downloaded)
ls /home/vibe/.gradle/wrapper/dists/   # e.g. gradle-9.5.1-bin
```

If a project's `gradle/wrapper/gradle-wrapper.properties` points at **a version not present in the
install/cache, do not silently download it.** Resolve in this priority order:

1. **Reuse the installed/cached version (preferred)** — set `distributionUrl` to the version (`<VER>`) you found above (replace `<VER>` with the actual queried value):
   ```properties
   distributionUrl=https\://services.gradle.org/distributions/gradle-<VER>-bin.zip
   ```
   Leave a one-line comment explaining the change. (If it already matches, leave it as is.)

2. **Bypass the wrapper** — invoke the system `gradle` instead of `./gradlew`.
   ```bash
   gradle --no-daemon assembleDebug
   ```
   Check compatibility first (e.g. `org.gradle.java.home` in `gradle.properties`).

3. **If a different version is truly required** — state the reason (a specific plugin requirement, etc.), confirm with the user, then download. No silent downloads.

### 6.3 Android SDK policy

A project's `local.properties` may have `sdk.dir` hardcoded to a host path. Inside the container,
**do not create or edit `local.properties`** — leave it or delete it. The `ANDROID_HOME` env var
takes precedence, so the SDK location is detected automatically.

Install missing platforms / build-tools with `sdkmanager`, but **confirm with the user before any
non-major (minor) version change.**

```bash
# List installed packages
sdkmanager --list_installed
# Install only what's missing (match the project's required versions)
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

### 6.4 Environment variables (already set by the container entrypoint)

```
ANDROID_HOME=/opt/android-sdk
ANDROID_SDK_ROOT=/opt/android-sdk
JAVA_HOME=/opt/java/openjdk
PATH=/home/vibe/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/java/openjdk/bin:...
```

**Do not override these** in build scripts or IDE settings.

## 7. Design principles / code quality

- **Object-oriented (OOP)** design.
- **Modular features** — separate by responsibility, single-responsibility principle.
- **Minimal dependencies** — avoid unnecessary libraries/coupling.
- **Strict encapsulation** — hide internals, minimize the public (API) surface.
- **Maintainability first** — code that is easy to read and change.
- **Never leave legacy code lying around — remove it the moment you find it.** (Includes commented-out dead code, unused functions/classes/resources/imports.)

## 8. File & naming rules

- **Use clear names** — names that reveal intent.
- **Don't overuse `Utils` / `Helper`** — avoid junk-drawer classes with vague responsibility.
- **Role-based naming** — name things by what they do.

## 9. Git rules

- **Run `git init` immediately when creating a project.**
- **Commit immediately on every code change** — don't let changes pile up.
- Write commit messages **clearly, per unit of work** (what / why).

## 10. Documentation (must update on change)

- On any code/behavior change, **update immediately**: `CHANGELOG.md`, `README.md`, `CLAUDE.md`.
- **Remove legacy docs immediately** — never leave outdated descriptions/examples behind.

## 11. Never do

- **Hardcoding** (values/paths/secrets/version strings, etc.)
- **Leaving legacy code/docs** lying around
- **Missing commits**
- **Out-of-date docs**
- **Storing keystores inside the project** (always `/home/vibe/keystores/`)
- Ignore the pre-installed tools and re-download a different path/version
- Arbitrarily delete `/opt/*` or `/home/vibe/.gradle/`, `/home/vibe/.local/`
- Hardcode a host path in `local.properties`
- Change the wrapper version without recording the reason
- **Run an emulator/device or review screenshots autonomously without explicit user request** (heavy token/time cost — §14)

## 12. Guiding principles

Every decision is judged by: **maintainability · extensibility · consistency · automatability.**

## 13. Android Compose UI pitfalls (regression prevention — recurring patterns)

UI bug patterns that actually recurred and were fixed. **Check these first when adding a new
screen/navigation or touching inset-related code.**

### 13.1 ⚠ Double-applied WindowInsets — empty band above the header (most common)

**Symptom:** an unnecessary empty strip the height of the status bar appears above each tab/screen's
`TopAppBar` (header).

**Cause:** **inset applied twice.** Typically a **nested Scaffold** — the outer `Scaffold` (usually
hosting `bottomBar`/`NavigationBar`, no `topBar`) applies `innerPadding.top` (= status-bar height) to
the content (NavHost) under `enableEdgeToEdge`, and the per-screen inner `Scaffold` + `TopAppBar`
applies the status-bar inset **again**.

**Rule (apply each inset once, with a clear owner):**
- **The top (status-bar) inset owner is the screen's `TopAppBar`.** The outer Scaffold must NOT apply
  `top` to the NavHost — only **start/end/bottom**.
  ```kotlin
  Scaffold(bottomBar = { ... }) { inner ->
      val ld = LocalLayoutDirection.current
      NavHost(..., modifier = Modifier.padding(
          start = inner.calculateStartPadding(ld),
          end = inner.calculateEndPadding(ld),
          bottom = inner.calculateBottomPadding()   // top intentionally excluded
      ))
  }
  ```
- Only screens **without** a `TopAppBar` (e.g. Home) offset the status bar directly at the root via
  `Modifier.statusBarsPadding()`.
- Same logic for the **bottom inset — apply it in one place only**: if the outer Scaffold has a
  `bottomBar`, it owns the bottom-nav inset. Make sure the inner screen Scaffold doesn't add a
  `navigationBars` inset again.

**Checklist (when adding a screen/nav):**
1. Are Scaffolds **nested**? → if so, ensure each system-bar inset has **exactly one owner**.
2. Are you applying `innerPadding` wholesale via `Modifier.padding(innerPadding)` while there's also a
   `TopAppBar` inside? → top double-applied.
3. You aren't using `statusBarsPadding()` / `systemBarsPadding()` **together with** a `TopAppBar`
   (which already applies the status-bar inset)?
4. With `enableEdgeToEdge()`, did you limit inset consumption to once per screen?

### 13.2 Secondary checks (tied to general policy)

- Define new colors/dimens/strings in **theme/resources and reference them** (no hardcoding, §11).
  Semantic colors shared across screens (income/expense, etc.) use a **single shared definition** (consistency).
- Each screen handles **loading / empty / error** states. Don't distinguish state by color alone
  (pair with text/icon).
- Collect lists/state lifecycle-aware (`collectAsStateWithLifecycle`) to minimize background work.

---

## 14. ⚠ Running an emulator / reviewing screenshots — no autonomous execution

Verifying the app actually runs — **launching an emulator/device, installing & running the app,
taking & reviewing screenshots** — consumes **a lot of tokens and time.** Therefore:

- Do it **only when the user explicitly asks** (e.g. "run it on the emulator", "take a screenshot",
  "check on a real device", "runtime verification", and similar clear instructions).
- **Do not run it on your own judgment without explicit user request.** §2's "build test" means
  **compile + build + unit tests** and does not include runtime execution. Build + unit tests are
  considered sufficient default verification for a code change.
- If runtime verification seems valuable, **do not run it** — instead **only suggest** "emulator/device
  verification recommended" in your response and wait for the user's choice (decided in the next prompt,
  per the non-interactive rule).
- In a shared emulator environment, contention with other projects makes the cost/benefit low.
