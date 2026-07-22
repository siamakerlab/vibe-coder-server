package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.shared.dto.ProjectTypes

object ClaudeMdTemplate {

    /**
     * v0.99.0 — 프로젝트 등록 시 입력 정보를 CLAUDE.md 최상단에 자동 주입.
     *
     * 그동안 [CONTENT] 는 const 라 모든 프로젝트가 같은 generic 파일을 받았다.
     * 사용자가 register 시 입력한 `appName` / `packageName` 등이 CLAUDE.md 에
     * 반영 안 돼 콘솔에서 Claude 에게 다시 "패키지명을 ... 으로 바꿔줘" 라고
     * 수동 지시해야 했음. [render] 가 projectInfo 받으면 ## Project Info 섹션
     * 을 prepend 해 Claude 가 첫 turn 부터 정확한 정보 사용.
     *
     * projectInfo null (backfill / scratch) 시 generic 버전 ([CONTENT]) 반환.
     */
    data class ProjectInfo(
        val appName: String,
        val packageName: String,
        val projectId: String,
        val moduleName: String,
        val debugTask: String,
        val sourceType: String? = null,  // "empty" | "clone"
        val cloneUrl: String? = null,
        val cloneBranch: String? = null,
        /** v1.127.0 — "kotlin" | "flutter". Flutter 면 Android 전용 Flutter body 를 생성. */
        val projectType: String = ProjectTypes.KOTLIN,
    )

    fun render(info: ProjectInfo? = null): String {
        if (info == null) return CONTENT
        val cloneLine = when {
            info.sourceType == "clone" && !info.cloneUrl.isNullOrBlank() -> {
                val branch = info.cloneBranch?.takeIf { it.isNotBlank() }?.let { " (branch: `$it`)" } ?: ""
                "- **Source**: cloned from `${info.cloneUrl}`$branch"
            }
            else -> "- **Source**: empty scaffold (no upstream)"
        }
        return when (ProjectTypes.normalize(info.projectType)) {
            ProjectTypes.FLUTTER -> renderFlutterProjectMemory(info, cloneLine)
            ProjectTypes.IPHONE -> renderIPhoneProjectMemory(info, cloneLine)
            else -> renderKotlinProjectMemory(info, cloneLine)
        }
    }

    fun renderKotlinProjectMemory(info: ProjectInfo): String = renderKotlinProjectMemory(info, cloneLine(info))

    fun renderFlutterProjectMemory(info: ProjectInfo): String = renderFlutterProjectMemory(info, cloneLine(info))

    fun renderIPhoneProjectMemory(info: ProjectInfo): String = renderIPhoneProjectMemory(info, cloneLine(info))

    private fun cloneLine(info: ProjectInfo): String = when {
        info.sourceType == "clone" && !info.cloneUrl.isNullOrBlank() -> {
            val branch = info.cloneBranch?.takeIf { it.isNotBlank() }?.let { " (branch: `$it`)" } ?: ""
            "- **Source**: cloned from `${info.cloneUrl}`$branch"
        }
        else -> "- **Source**: empty scaffold (no upstream)"
    }

    private fun renderKotlinProjectMemory(info: ProjectInfo, cloneLine: String): String {
        val infoBlock = """# CLAUDE.md — ${info.appName}

## Project Info (auto-populated on project creation)

- **App name (display)**: ${info.appName}
- **Project ID (workspace folder)**: `${info.projectId}`
- **Android package / applicationId**: `${info.packageName}`
- **Default Gradle module**: `${info.moduleName}`
- **Debug build task**: `${info.debugTask}`
$cloneLine

> When the user asks to "make this an Android app" or to scaffold build files,
> use the values above instead of inventing new ones. In particular set
> `android { namespace = "${info.packageName}" }` and `defaultConfig {
> applicationId = "${info.packageName}" }`. The package name is canonical —
> do not change it without explicit user request.

"""
        // CONTENT 의 첫 `# CLAUDE.md` 라인을 위 헤더로 치환. 본문 (Project Rules 이하) 은 그대로.
        val body = CONTENT.substringAfter("# CLAUDE.md — Vibe Coder Android Project Rules\n\n")
        return infoBlock + body
    }

    // v1.127.0 — Flutter(Android 앱 빌드 전용) 프로젝트 body. applicationId 는 android/app
    // 모듈의 defaultConfig.applicationId 이고, 플랫폼은 android 로만 제한(iOS/web/desktop 금지).
    private fun renderFlutterProjectMemory(info: ProjectInfo, cloneLine: String): String {
        val infoBlock = """# CLAUDE.md — ${info.appName}

## Project Info (auto-populated on project creation)

- **App name (display)**: ${info.appName}
- **Project ID (workspace folder)**: `${info.projectId}`
- **Android applicationId**: `${info.packageName}` (in `android/app/build.gradle(.kts)` defaultConfig.applicationId)
- **Project type**: Flutter — **Android build target only**
$cloneLine

> Flutter (Dart) project, **Android only**. When scaffolding a new app run
> `flutter create --platforms=android .` (never create ios/web/linux/windows/macos).
> Set `android/app/build.gradle(.kts)` defaultConfig.applicationId = "${info.packageName}".
> The applicationId is canonical — do not change it without explicit user request.

"""
        val body = CONTENT_FLUTTER.substringAfter("# CLAUDE.md — Vibe Coder Flutter (Android-only) Project Rules\n\n")
        return infoBlock + body
    }

    private fun renderIPhoneProjectMemory(info: ProjectInfo, cloneLine: String): String {
        val infoBlock = """# CLAUDE.md — ${info.appName}

## Project Info (auto-populated on project creation)

- **App name (display)**: ${info.appName}
- **Project ID (workspace folder)**: `${info.projectId}`
- **Bundle ID**: `${info.packageName}`
- **Project type**: iPhone — SwiftUI/Xcode project
$cloneLine

> iPhone project. Use Swift/SwiftUI/Xcode rules only. The bundle id above is canonical —
> do not change it without explicit user request. Simulator actions require MacBook local
> install or a connected macOS agent with `xcrun simctl` preflight passing.

"""
        val body = CONTENT_IPHONE.substringAfter("# CLAUDE.md — Vibe Coder iPhone Project Rules\n\n")
        return infoBlock + body
    }

    // v1.106.0 — 토큰 절감(P2): 매 turn 캐시 프리픽스에 들어가는 프로젝트 CLAUDE.md 를
    // 슬림화. 모든 실질 규칙(비인터랙티브·gradle 재사용·캐시 보존·응답 규칙)은 보존하되
    // 장황한 산문/중복(영문+국문 중복 블록)을 bullet 로 압축. (신규 프로젝트에 적용)
    const val CONTENT = """# CLAUDE.md — Vibe Coder Android Project Rules

## Project Rules
- Android project managed through Vibe Coder. Kotlin Android SDK.
- Prefer Jetpack Compose + Material 3 for UI. Clean, maintainable architecture.
- MVVM + Repository for new features; no business logic in Activity/Composable.
- Avoid unnecessary dependencies. Preserve existing package structure unless asked.
- Check for obvious build errors before finishing a coding task.

## Build Rules
- Gradle Wrapper only: `./gradlew` (Linux/macOS), `gradlew.bat` (Windows).
- Debug build task = `assembleDebug` unless the project config says otherwise.

## Installed Build Tools — USE THESE, DO NOT RE-DOWNLOAD
Host already provisioned these into bind-mounted volumes (via `/env-setup`).
Use these exact paths/versions; do NOT fetch a different toolchain.
- Gradle (host): `/home/vibe/.local/gradle/` (on PATH as `gradle`, latest stable).
- Android SDK: `${'$'}ANDROID_HOME` (≈`/opt/android-sdk`; cmdline-tools, platform-tools/adb, platforms;android-35, build-tools).
- JDK 17 (`java`) + Node 20 + Claude CLI (`claude`): bundled in image.
- MCP packages: `/home/vibe/.local/` (npm global prefix).

### Gradle policy (saves disk + minutes + tokens)
- If `gradle-wrapper.properties` pins a Gradle version ≠ installed one, align the
  wrapper to the installed version (edit `distributionUrl`, or
  `gradle wrapper --gradle-version <installed> --distribution-type bin`) instead
  of letting Gradle download a 2nd copy. Deviate only if the project truly needs a
  specific older Gradle — state why.
- Missing `gradlew`? Generate with host gradle:
  `gradle wrapper --gradle-version "${'$'}(gradle --version | awk '/^Gradle /{print ${'$'}2; exit}')" --distribution-type bin`
  (BuildService also does this on first build).
- Do NOT delete `~/.gradle/caches/` or `${'$'}ANDROID_HOME/build-tools/*` — shared
  bind-mounted caches; re-downloading is expensive. Mention if `--refresh-dependencies` is truly needed.

## Response Rules
- Summarize modified files + key decisions; state whether build ran; if it failed, give likely cause + next step.

## Non-Interactive Environment (CRITICAL)
Claude runs as a non-interactive child process behind a web/mobile UI. The user
CANNOT answer TUI prompts, menus, or stdin. Every turn is one-shot.
- No AskUserQuestion / interactive menus / key-press affordances.
- No stdin-waiting commands (`npm init` without `-y`, interactive `gh auth login`, `claude login`).
- No watch/REPL/TUI or unbounded commands (`tail -f`, `adb logcat` without a stop condition). `gradle --console=plain` is fine.
- Never pause to ask "should I continue?". Proceed with a sensible default, OR list questions at the END as (A)(B)(C) with a "권장/Recommended" default — the user replies in the NEXT prompt.
- 한국어: 인터랙티브 입력 불가. 확인이 필요하면 응답 끝에 (A)(B)(C) + 권장안을 적고 멈추세요(다음 프롬프트에서 선택). 대기성 명령 금지, 한 턴은 자기완결.
"""

    // v1.127.0 — Flutter 프로젝트 기본 CLAUDE.md. 기본 target 은 Android-only 이고,
    // `.vibecoder-flutter-targets.properties` 가 iPhone 을 명시한 경우에만 iOS 빌드를 허용한다.
    const val CONTENT_FLUTTER = """# CLAUDE.md — Vibe Coder Flutter Project Rules

## Project Rules
- Flutter (Dart) project managed through Vibe Coder.
- Default target is Android only. iPhone target is opt-in through `.vibecoder-flutter-targets.properties`
  (`targets=iphone` or `targets=android,iphone`).
- Prefer Material 3. Clean architecture; keep business logic out of widgets.
- Avoid unnecessary packages. Preserve existing structure unless asked.
- Run `flutter analyze` and fix obvious build errors before finishing a task.

## Platform Target Policy
- If the target file is absent, do not add or build iOS/web/desktop.
- For Android-only projects, `flutter create` MUST pass `--platforms=android`.
- If iPhone target is enabled, iOS work must run through the Vibe Coder macOS agent path:
  `flutter create --platforms=ios .`, `flutter build ios --debug --simulator --no-codesign`,
  or `flutter build ipa --release`.
- web/linux/windows/macos remain out of scope.
- applicationId lives in `android/app/build.gradle(.kts)` defaultConfig.applicationId.

## Build Rules
- Build with `flutter build apk --debug` / `--release`, or `flutter build appbundle --release`.
- The server build pipeline runs these; artifacts land in `build/app/outputs/flutter-apk/`
  (APK) and `build/app/outputs/bundle/release/` (AAB).
- iPhone debug builds produce `build/ios/iphonesimulator/*.app`; release exports produce
  `build/ios/ipa/*.ipa`.
- Release signing: `android/key.properties` + `signingConfigs.release` in app/build.gradle
  (flutter build does NOT use Gradle injected signing). iPhone signing uses Flutter/Xcode
  project signing settings on the Mac agent.

## Installed Build Tools — USE THESE, DO NOT RE-DOWNLOAD
Host provisioned these into bind-mounted volumes (via `/env-setup`). Use these exact paths.
- Flutter SDK: `/home/vibe/.local/flutter` (on PATH as `flutter` / `dart`; Docker precache is Android-only).
- Android SDK: `${'$'}ANDROID_HOME` (cmdline-tools, platform-tools/adb, platforms;android-35, build-tools).
- JDK 17 (`java`) + Node 20 + Claude CLI (`claude`): bundled in image.
- Flutter invokes `android/gradlew` internally — do not bypass it.

### Cache policy (saves disk + minutes + tokens)
- Do NOT delete `~/.gradle/caches/`, `${'$'}ANDROID_HOME/build-tools/*`, `${'$'}HOME/.pub-cache`,
  or `/home/vibe/.local/flutter` — shared bind-mounted caches; re-downloading is expensive.
- `flutter build` runs `pub get` automatically; run `flutter pub get` after editing `pubspec.yaml`.

## Response Rules
- Summarize modified files + key decisions; state whether build ran; if it failed, give likely cause + next step.

## Non-Interactive Environment (CRITICAL)
Claude runs as a non-interactive child process behind a web/mobile UI. The user
CANNOT answer TUI prompts, menus, or stdin. Every turn is one-shot.
- No AskUserQuestion / interactive menus / key-press affordances.
- No stdin-waiting commands (interactive `gh auth login`, `claude login`, `flutter create` prompts).
- No watch/REPL/TUI or unbounded commands (`tail -f`, `adb logcat` without a stop condition,
  `flutter run` without an exit). One-shot `flutter build` is fine.
- Never pause to ask "should I continue?". Proceed with a sensible default, OR list questions at the END as (A)(B)(C) with a "권장/Recommended" default — the user replies in the NEXT prompt.
- 한국어: 인터랙티브 입력 불가. 확인이 필요하면 응답 끝에 (A)(B)(C) + 권장안을 적고 멈추세요(다음 프롬프트에서 선택). 대기성 명령 금지, 한 턴은 자기완결.
"""

    const val CONTENT_IPHONE = """# CLAUDE.md — Vibe Coder iPhone Project Rules

## Project Rules
- iPhone app project managed through Vibe Coder. Swift/SwiftUI/Xcode only.
- Prefer SwiftUI, clear state ownership, accessibility labels, and predictable navigation.
- Limit Simulator support to iPhone/iPad. Do not create watchOS/tvOS/visionOS/macOS app targets.
- Avoid unnecessary packages. Preserve existing Xcode project structure unless asked.
- Check obvious Swift compile/test errors before finishing a coding task.

## Tooling Rules
- Use `xcodebuild` for build/test verification when a MacBook local install or macOS agent is available.
- Use `xcrun simctl` only after Mac preflight passes. Linux Docker alone cannot run Simulator.
- Do not run Gradle, Play, Android keystore, or Flutter pubspec workflows for this project.
- App Store Connect, fastlane, signing certificates, provisioning profiles, and `.p8` keys are release-only.
  Use them only when the user explicitly asks for release/TestFlight work.

## Secret Rules
- Never print, commit, or log signing certificates, provisioning profiles, ASC private keys, issuer IDs,
  key IDs, passwords, or session tokens.
- Keep signing assets in the configured secret store/keychain path, not inside the app source tree.

## Response Rules
- Summarize modified files + key decisions; state whether Xcode build/test or Simulator verification ran.
- If Mac preflight is unavailable, say which iPhone actions were skipped and why.

## Non-Interactive Environment (CRITICAL)
Claude runs as a non-interactive child process behind a web/mobile UI. The user
CANNOT answer TUI prompts, menus, or stdin. Every turn is one-shot.
- No AskUserQuestion / interactive menus / key-press affordances.
- No stdin-waiting commands (`xcodebuild -allowProvisioningUpdates` that opens login prompts,
  interactive `gh auth login`, `claude login`).
- No watch/REPL/TUI or unbounded commands. Simulator commands must have a clear exit condition.
- Never pause to ask "should I continue?". Proceed with a sensible default, OR list questions at the END as (A)(B)(C) with a "권장/Recommended" default — the user replies in the NEXT prompt.
- 한국어: 인터랙티브 입력 불가. 확인이 필요하면 응답 끝에 (A)(B)(C) + 권장안을 적고 멈추세요(다음 프롬프트에서 선택). 대기성 명령 금지, 한 턴은 자기완결.
"""
}
