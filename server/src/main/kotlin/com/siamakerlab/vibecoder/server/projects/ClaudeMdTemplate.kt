package com.siamakerlab.vibecoder.server.projects

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

    const val CONTENT = """# CLAUDE.md — Vibe Coder Android Project Rules

## Project Rules

- This is an Android project managed through Vibe Coder.
- Use Kotlin Android SDK.
- Prefer Jetpack Compose and Material 3 when UI changes are required.
- Keep architecture clean and maintainable.
- Use MVVM + Repository pattern when adding new features.
- Do not place business logic directly in Activity or Composable.
- Avoid unnecessary dependencies.
- Preserve existing package structure unless explicitly requested.
- Before finishing coding tasks, check for obvious build errors.

## Build Rules

- Use Gradle Wrapper only.
- On Windows, use gradlew.bat.
- On Linux/macOS, use ./gradlew.
- Debug build task is assembleDebug unless the project config says otherwise.

## Installed Build Tools (USE THESE — DO NOT RE-DOWNLOAD)

The vibe-coder host has already downloaded the following tools into bind-mounted
volumes via the **Build Environment** page (`/env-setup`) or `vibe-doctor`.
**Use these versions/paths. Do NOT trigger a fresh download of a different
toolchain version.**

| Tool | Container path | Notes |
|---|---|---|
| Gradle (host install) | `/home/vibe/.local/gradle/` (binary on PATH as `gradle`) | Latest stable. Use this for wrapper bootstrap. |
| Android SDK | `${'$'}ANDROID_HOME` (typically `/opt/android-sdk`) | Includes cmdline-tools, platform-tools (adb), platforms;android-35, build-tools. |
| JDK | bundled in the server image | OpenJDK 17, on PATH as `java`. |
| Node.js + Claude CLI | bundled in the server image | Node 20 LTS, `claude` on PATH. |
| MCP packages | `/home/vibe/.local/` (npm global prefix) | Whatever the user installed via `/env-setup/mcp`. |

### Gradle wrapper alignment policy

When a project's `gradle/wrapper/gradle-wrapper.properties` references a
**Gradle version different from the one already installed at
`/home/vibe/.local/gradle/`**, prefer to align the wrapper to the installed
version rather than letting Gradle download a second copy. Procedure:

1. Check installed version: `gradle --version | grep '^Gradle '`.
2. Either:
   - Update `distributionUrl` in `gradle-wrapper.properties` to that version, or
   - Re-generate the wrapper with the installed gradle:
     `gradle wrapper --gradle-version <installed-version> --distribution-type bin`.

Reasoning: downloading a second Gradle distribution wastes disk + minutes per
project + Claude API tokens spent waiting on the download log. The host
already has the right binary. Stick to it unless the project genuinely
requires a specific older Gradle for API reasons — in that case state the
reason in the response.

### When a wrapper is missing

If `gradlew` is absent (e.g., a freshly scaffolded project), use the host
gradle to generate one with the installed version:

```bash
gradle wrapper --gradle-version "${'$'}(gradle --version | awk '/^Gradle /{print ${'$'}2; exit}')" --distribution-type bin
```

`BuildService` also runs this automatically on the first build attempt, but
generating up front is faster.

### Cache reuse

Don't remove `~/.gradle/caches/` or `${'$'}ANDROID_HOME/build-tools/*` to "clean
up" — those caches are bind-mounted volumes shared across projects and
re-downloading them is expensive. If `gradle --refresh-dependencies` is
truly needed, mention it in the response.

## Response Rules

- Summarize modified files.
- Summarize important implementation decisions.
- Mention whether build was executed.
- If build failed, explain the likely cause and next step.

## Non-Interactive Environment (CRITICAL)

Vibe Coder runs Claude as a **non-interactive child process** behind a web/mobile
UI. The user CANNOT respond to TUI prompts, arrow-key menus, stdin reads, or any
in-stream interactive widget. Treat every turn as one-shot.

- DO NOT use AskUserQuestion, interactive selection menus, or any tool/affordance
  that requires the user to press a key inside an active session.
- DO NOT call CLI commands that wait on stdin (e.g. `npm init` without `-y`,
  `gh auth login` interactive flow, `claude login`).
- DO NOT enter watch / REPL / TUI modes (`gradle --console=plain` is fine,
  `./gradlew --watch-fs` interactive is not).
- DO NOT pause and ask "should I continue?". Either proceed with a sensible
  default, or list the question(s) at the end of your response so the user
  answers in the **next prompt**.
- Long-running commands must complete and return (no `tail -f`, no `adb logcat`
  without a clear stop condition).

When you need user input or a decision:

1. State the question(s) inline at the end of the response.
2. Show 2~3 concrete options labeled (A), (B), (C) with one-line trade-offs.
3. Suggest a default (marked "권장 / Recommended").
4. Stop. The user replies in the next prompt with their choice.

### 한국어 요약

vibe-coder 환경은 인터랙티브 입력이 불가능합니다. 화살표 키 선택, stdin
응답, TUI/REPL 진입은 모두 작동하지 않습니다.

- 사용자 확인이 필요하면 응답 마지막에 (A)(B)(C) 옵션과 권장안을 적어
  두세요. 사용자는 **다음 프롬프트**에서 선택해 보냅니다.
- 대기하는 명령(`adb logcat`, `gradle --watch-fs`, 인터랙티브 `claude login`
  등)은 절대 호출하지 마세요.
- 한 턴은 항상 자기완결적이어야 합니다.
"""
}
