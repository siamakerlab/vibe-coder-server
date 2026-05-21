# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Version codes follow the global convention `yymmddrrr` (date + run counter).

## [Unreleased]

## [0.2.1] - 2026-05-21

> v0.2.0의 deferred 항목 중 deprecated 엔드포인트 제거. one-shot Claude task
> 파이프라인 잔재를 모두 정리하고 콘솔 단일 경로로 통합.

### Removed
- **Server**: `POST /api/projects/{id}/claude/tasks` (deprecated 핸들러),
  `GET /api/projects/{id}/claude/tasks`, `GET .../claude/tasks/{taskId}`,
  `POST .../claude/tasks/{taskId}/cancel` 4개 엔드포인트.
- **Server**: WebSocket `/ws/projects/{id}/tasks/{taskId}/logs` 엔드포인트
  (콘솔 WS 및 빌드 WS만 남김).
- **Server 파일**: `claude/ClaudeRoutes.kt`, `claude/ClaudeRunner.kt`,
  `claude/ClaudePromptBuilder.kt`, `tasks/TaskRoutes.kt`,
  `repo/TaskRepository.kt`.
- **DB**: `Tasks` 테이블 정의 삭제 (`db/Schemas.kt`). 신규 서버는 이 테이블을
  더 이상 생성하지 않음. 기존 DB 파일은 그대로 두면 됨 (테이블만 unused
  상태로 남음).
- **Shared**: `ClaudeTaskRequestDto`, `TaskDto`, `TaskType` 제거. `TaskStatus`
  enum은 BuildRow가 사용하므로 보존하되 KDoc에 build 전용임을 명시.
- **Shared**: `ApiPath.claudeTasks/claudeTask/claudeTaskCancel/wsTaskLogs`
  4개 path 상수 제거.
- **Android**: `ApiService.submitClaudeTask/listClaudeTasks/cancelTask` 함수
  제거. `Repositories.kt` `TaskRepository` 클래스 통째로 제거.
  `WsClient.streamTaskLogs` 제거.
- **Android Nav**: `Routes.LOG` (`projects/{id}/logs/{kind}/{taskId}`)
  → `Routes.BUILD_LOG` (`projects/{id}/builds/{buildId}/logs`)로 단순화.
  `ARG_KIND`/`ARG_TASK_ID` 제거, `ARG_BUILD_ID` 신설.
  `Routes.log(id, kind, taskId)` → `Routes.buildLog(id, buildId)`.

### Changed
- **Android `LogScreen`**: build-only로 단순화. ViewModel은 `WsClient` +
  `BuildRepository`만 주입받고 `kind` 분기 제거.
- **Server `StatusService`**: `taskRepo` → `buildRepo` 의존성으로 교체.
  `runningTaskCount`는 이제 `Builds` 테이블의 RUNNING+PENDING 개수.
  `BuildRepository.countRunning()` 메서드 신설.
- **Server `ServerContext`**: `taskRepo`, `claude: ClaudeRunner` 필드 제거.
- **Server `tasks/LogWriter.kt`**: KDoc에서 ClaudeRunner 언급 제거.

### Versions
- `versionName` `0.2.0` → `0.2.1` (PATCH: deprecated 코드 정리, 동작 변경
  없음).
- `versionCode` `260521001` → `260521002`.
- `server.yml` `server.version` `0.2.0` → `0.2.1`.

## [0.2.0] - 2026-05-21

> project-claude-console — 채팅형 Claude Console + 영속 세션 + 액션 레지스트리.
> PDCA 사이클 종료 Match Rate 98% (archived at `docs/archive/2026-05/project-claude-console/`).

### Added — Server (persistent Claude console)
- `claude/ClaudeSessionManager`: 프로젝트당 1개 영속 `claude --print --output-format stream-json --input-format stream-json [--resume <id>]` 자식 프로세스. stdin/stdout 파이프 + per-project stdin mutex + idle 30분 reaper + crash/resume-failure 감지.
- `claude/ClaudeStreamParser` + `claude/ClaudeEvent`: stdout 라인 → sealed `ClaudeEvent`(SessionStarted / AssistantMessage / ToolUse / ToolResult / Error / Done / Unknown).
- `claude/ConsoleRoutes`: `POST /api/projects/{id}/claude/console/prompt`, `POST .../console/new`, `GET .../claude/status`.
- `claude/ClaudeStatusService`: `claude /status` 60s 캐시 (slash command sidecar fallback 포함).
- `ws/LogHub` 확장: 콘솔 토픽 ring buffer 200건 + 단조 증가 seq + `?since=<seq>` replay.
- `ws/WsRoutes` 확장: `/ws/projects/{id}/console/logs?since=` 양방향 (client→server: auth/user_prompt/action_invoke, server→client: console_* sealed frames).
- `actions/` 패키지: sealed `ProjectAction` (SendPrompt / InvokeMcpTool / RunServerAction / OpenPalette / SnippetInsert / InvokeClaudeSlashCommand) + `ProjectActionRegistry` (resources + workspace `actions.user.json` 병합, MCP `.mcp.json` 자동 발견, mtime 10s 핫리로드) + `ServerActionHandler` (whitelist: `build.debug`, `git.{status,diff,log}`, slash `{status,cost,model,clear,memory,plan,compact}`) + routes (`GET /actions`, `POST /actions/invoke`, 4KB params cap).
- `resources/actions/`: 기본 manifest `build.json`, `git.json`, `claude.json`, `snippets.json`.
- `projects/KeystoreGenerator`: 프로젝트 등록 시 디버그/릴리즈 동일 키스토어 자동 생성.
- `error/StatusPagesPlugin`: 표준 `ApiErrorDto` 응답 코드 확장 (`action_not_allowed`, `claude_send_failed`, `params_too_large`, `prompt_too_large` 등).

### Added — Android (chat console)
- `ui/console/`: `ProjectConsoleScreen` (TopAppBar + LazyColumn 대화 + Surface 입력바), `ConsoleViewModel`, `messages/` 카드 6종 (AssistantBubble/ToolUse/ToolResult/Error/System/Unknown), `input/PromptInputBar` + `VoiceButton` (SpeechRecognizer 한/영) + `QuickActionChips` (카테고리 탭 + LazyRow), `scroll/AutoScrollState` (스크롤 잠금 + "↓ Jump to latest"), `status/StatusPanel` (collapsible).
- `data/remote/ConsoleWsClient` + `data/repository/ConsoleRepository`: WS 양방향 + `?since` 재접속 replay.
- `AndroidManifest.xml`: `RECORD_AUDIO` 권한 선언 + `<queries>` SpeechRecognizer.
- `strings.xml`: 콘솔 UI 약 30개 키 신규 (하드코딩 제거).

### Added — Shared
- `dto/ConsoleDtos.kt`: `PromptRequestDto`, `PromptAcceptedDto`, `ClaudeStatusDto`.
- `dto/ProjectActionDto.kt`: 액션 트리 wire DTO (sealed `ProjectActionDto` 6 변형 + `ActionCategoryDto` + `ActionTreeDto` + `ActionInvokeRequestDto`).
- `ws/WsFrame`: `Console*` 서브타입 10종 (`SessionStarted`/`Assistant`/`ToolUse`/`ToolResult`/`Error`/`Done`/`Unknown`/`System`/`ReplayBegin`/`ReplayEnd`) + client→server `UserPrompt`/`ActionInvoke`.
- `ApiPath`: console + actions 엔드포인트 상수.

### Changed
- `claude/ClaudeRoutes.POST /api/projects/{id}/claude/tasks` → **deprecated** (one-shot 모드, 1 사이클 호환 유지). 신규 클라이언트는 console 엔드포인트 사용.
- `WorkspacePath` + `PathSafety`: `.vibecoder` 메타 경로를 `<root>/.vibecoder/<projectId>/`로 통일 (이전: `<root>/<projectId>/.vibecoder/`).
- `server.yml`: `workspace.root` 기본값 `./vibe-coder-server-data/workspace` → `./workspace`; `security.restrictToWorkspace` 옵션 제거 (`PathSafety`가 항상 강제하므로 잉여).
- Repositories(6개): `Clock` 주입으로 결정성 향상; `ProjectService`는 키스토어 생성 흐름 통합.
- Android nav `Routes`: `ProjectDetail` 라우트가 `ProjectConsoleScreen`을 가리키도록 변경; `ClaudePrompt` 라우트는 console로 흡수.
- `ProjectRegisterScreen`: 키스토어 자동 생성 안내 + 폼 확장.
- `MainActivity`: 음성 권한 launcher + 콘솔 진입 흐름.

### Removed
- `ui/claude/ClaudePromptScreen.kt`, `ui/projects/ProjectDetailScreen.kt` (콘솔로 흡수). 라우트도 함께 제거.

### Versions
- `versionName` `0.1.0` → `0.2.0` (MINOR: 영속 콘솔/액션 레지스트리/음성 입력 — 하위호환 신규 기능, 사용자 워크플로 확장).
- `versionCode` `260517001` → `260521001` (yymmddrrr).
- `server.yml` `server.version` `0.1.0` → `0.2.0` (Plan 문서와 동기).

### Deferred (다음 사이클 후보)
- `ProjectActionDto.requires` 권한 게이트 + Android 비활성 chip + 사유 tooltip (FR-11-b).
- MCP per-tool enumeration (현재 per-server 1 chip만; JSON Schema 기반 폼 후속).
- Deprecated `/api/projects/{id}/claude/tasks` 제거 (1 사이클 유예 후).
- `gradle-wrapper.jar` 바이너리 생성 (`gradle wrapper --gradle-version 9.5.1`).
- `artifactKeepCount` 자동 정리 (현재 수동 DELETE artifact API만).

## [0.1.0] - 2026-05-17

### Added
- Initial monorepo skeleton: `:shared`, `:server`, `:android-app:app`.
- Gradle 9.5.1 wrapper + version catalog (`gradle/libs.versions.toml`).
- Build matrix per global `CLAUDE.md` §2-2-1: Gradle 9.5.1 / AGP 9.2.0 / Kotlin 2.2.20 / Compose BOM 2026.05.00 / Hilt 2.59.2 / JDK 21.
- PDCA Plan and Design documents under `docs/01-plan/` and `docs/02-design/`.
- `shared` module: 13 `@Serializable` DTOs, API path constants, WebSocket frame sealed class.
- `server` module: Ktor 3.x + Exposed + SQLite + YAML config + pairing-code auth + WebSocket log streaming + Claude/Gradle/Git process execution + APK artifact management with SHA-256.
- `android-app` module: Jetpack Compose + Material 3 + Hilt + Ktor Client + DataStore + 12 screens (Connect/Dashboard/Environment/ProjectList/Register/Detail/ClaudePrompt/Log/Build/Artifact/Git/Files) + APK installer via FileProvider.

### Notes
- `android.disallowKotlinSourceSets=false` is required for AGP 9 + KSP2 (workaround until KSP migrates to `android.sourceSets`).
- `-Xannotation-default-target=param-property` is applied to Android module (Kotlin 2.2 KT-73255 forward-compat for Hilt).

### Changed during first build (2026-05-17)

Adjustments made while producing the first runnable debug APK:

- **Compose Compiler plugin**: Kotlin 2.0+ moved Compose Compiler into a separate Gradle plugin. Added `org.jetbrains.kotlin.plugin.compose` (alias `libs.plugins.kotlin.compose`) to root `build.gradle.kts` and to `android-app/app/build.gradle.kts`.
- **JDK toolchain 21 → 17**: Local environment ships JDK 17 only; Foojay auto-download did not transparently honour `jvmToolchain(21)` for the AGP `hiltJavaCompileDebug` task. Downgraded `jvmToolchain` and `sourceCompatibility` / `targetCompatibility` in `shared/`, `server/`, and `android-app/app/` to 17. AGP 9 + Kotlin 2.2 remain fully supported on JDK 17.
- **Ktor 3.1.2 API alignment**:
  - `KtorClient.kt` — `sendWithoutRequest` block now reads the URL via `request.url.pathSegments` (URLBuilder `encodedPath` was not resolvable in the lambda's inference scope).
  - `DownloadService.kt` — replaced `ByteReadChannel.readAvailable` (whose ext-fn location moved between Ktor minors) with the stable `bodyAsChannel().toInputStream()` JVM helper.
  - `WsClient.kt` — added explicit `io.ktor.websocket.close` import for `DefaultClientWebSocketSession.close()`.
- **Foojay toolchain resolver**: Added `org.gradle.toolchains.foojay-resolver-convention 0.10.0` to `settings.gradle.kts` and `org.gradle.java.installations.auto-download=true` to `gradle.properties` so a future move back to JDK 21 will auto-provision the toolchain.
- **First APK**: `android-app/app/build/outputs/apk/debug/app-debug.apk` (~21 MB, versionCode `260517001`, versionName `0.1.0`, applicationId `com.siamakerlab.vibecoder.console.debug`).
