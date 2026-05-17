# Vibe Coder MVP Design Document

> **Project**: vibe-coder
> **Version**: 0.1.0
> **Author**: sia@siamakerlab.com
> **Date**: 2026-05-17
> **Status**: Draft

---

## Context Anchor (from Plan)

| Key | Value |
|-----|-------|
| **WHY** | 폰만 가진 상태에서도 Android 코드 수정 → debug build → 설치 검증 사이클을 닫기 위해. |
| **WHO** | 1인 Android 개발자(sia@siamakerlab.com). |
| **RISK** | timeout 누락, path traversal, APK 미검증, 토큰 평문 저장. |
| **SUCCESS** | 16-step 시나리오 완주 / timeout 적용 / workspace 격리 / Linux+Windows 동일 결과. |
| **SCOPE** | 9-phase 구현 (server 골격→인증→프로젝트→큐→Claude→Build→AndroidUI→APK→Git/Files). |

---

## 1. Overview

본 문서는 `vibe-coder-mvp` Plan 문서의 16개 FR / 18개 SC / 12개 Risk를 **실제 코드 구조**로 매핑한다. 모노레포 3개 모듈(`:shared`, `:server`, `:android-app`)의 패키지/클래스/함수 시그니처를 정의하고, 9단계 구현 순서를 multi-session-friendly한 11개 Module Map으로 분해한다.

---

## 2. Architecture Selection (3-Option Comparison)

| Option | 핵심 아이디어 | 장점 | 단점 | 선택 |
|--------|--------------|------|------|:----:|
| **A. Minimal** | Ktor route handler 안에 비즈니스 로직 직접 작성, DTO·Repository 분리 없이 Exposed DAO 직접 사용 | 코드량 최소, 빠른 1차 구현 | path-traversal 방어/동시성 정책이 핸들러마다 분산되어 회귀 위험 ↑, 단위 테스트 곤란 | ☐ |
| **B. Clean Architecture (4-layer)** | presentation / application(use-case) / domain / infrastructure 4계층 분리, 각 계층 인터페이스로 격리 | 최고 수준 테스트성·유지보수성 | 16개 FR 대비 추상화 4-tier는 과잉. 1인 개발 MVP에는 비용 ↑ | ☐ |
| **C. Pragmatic Balance** ⭐ | Ktor route → Service → Repository(Exposed) 3-layer + 횡단(WorkspacePath, ProcessRunner, Mutex)는 별도 util 모듈 | path-safety/timeout/큐는 단일 책임 util로 격리하여 테스트 용이. 비즈니스 로직은 서비스에 응집. | application/domain 분리는 없음 → 도메인 모델이 Exposed DAO에 약하게 결합 | **☑** |

**선택**: **Option C (Pragmatic Balance)**

**Trade-off table**:

| 항목 | A | B | C |
|------|:-:|:-:|:-:|
| 코드량 | 1.0× | 2.2× | 1.4× |
| 단위 테스트 가능도 | 낮음 | 매우 높음 | 높음 |
| 1인 개발자 유지비 | 낮음 (단기) | 높음 | 중간 |
| 회귀 위험 (path/timeout) | 높음 | 낮음 | 낮음 |
| 확장성 (멀티 사용자 등) | 낮음 | 매우 높음 | 중간 (서비스 인터페이스화로 확장 가능) |

**선택 사유**: path traversal·timeout·process kill은 **단일 책임 util**로 격리하는 것이 R-01/R-03/R-09를 동시에 차단하면서도 4-tier의 과잉 비용을 피한다.

---

## 3. Module Map (Top-Level)

| # | Module | 책임 | 의존 |
|---|--------|------|------|
| M01 | `:shared` (`com.siamakerlab.vibecoder.shared`) | DTO, API 상수, WS 프레임 | kotlinx.serialization only |
| M02 | `:server` config / db / repo | YAML 로딩, Exposed 스키마, Repository | :shared, Exposed, kaml |
| M03 | `:server` core util | WorkspacePath, ProcessRunner, Sha256, OsType, PathSafety | (server-only) |
| M04 | `:server` auth | 페어링 코드, Bearer plugin, WS auth | M02, M03 |
| M05 | `:server` env / status | 환경 진단, 서버 상태 | M03 |
| M06 | `:server` projects | 프로젝트 등록/조회, .vibecoder 생성, CLAUDE.md | M02, M03 |
| M07 | `:server` tasks / ws-hub | 작업 큐(project Mutex), LogStream, WebSocket 브로드캐스트 | M02, M03 |
| M08 | `:server` claude / build / artifact | Claude wrapped prompt, Gradle build, APK 탐색, SHA-256 | M03, M07 |
| M09 | `:server` git / files | git status/diff/log, 업로드/다운로드 | M03, M06 |
| M10 | `:android-app` core | App + Hilt + Ktor Client + DataStore + Repository + WS | :shared |
| M11 | `:android-app` ui (12 screens) + install | Compose 화면 12종, ViewModel, APK 설치 Intent | M10 |

---

## 4. Repository Layout (확정)

```
vibe-coder/
├─ settings.gradle.kts                        ← rootProject.name = "vibe-coder", include(":shared",":server",":android-app")
├─ build.gradle.kts                            ← plugins{} only (apply false)
├─ gradle.properties
├─ gradle/
│  ├─ libs.versions.toml                       ← 버전 카탈로그 (단일 진실 원천)
│  └─ wrapper/gradle-wrapper.properties        ← 9.5.1
├─ .gitignore
├─ CHANGELOG.md
├─ README.md
│
├─ shared/                                     ← :shared (JVM library, kotlin("jvm"))
│  ├─ build.gradle.kts
│  └─ src/main/kotlin/com/siamakerlab/vibecoder/shared/
│     ├─ ApiPath.kt
│     ├─ dto/
│     │  ├─ ProjectDto.kt
│     │  ├─ TaskDto.kt
│     │  ├─ BuildDto.kt
│     │  ├─ ArtifactDto.kt
│     │  ├─ ServerStatusDto.kt
│     │  ├─ EnvironmentCheckDto.kt
│     │  ├─ CheckItemDto.kt
│     │  ├─ LogMessageDto.kt
│     │  ├─ PairRequestDto.kt / PairResponseDto.kt
│     │  ├─ RegisterProjectRequestDto.kt
│     │  ├─ ClaudeTaskRequestDto.kt
│     │  ├─ FileEntryDto.kt
│     │  ├─ GitStatusDto.kt / GitDiffDto.kt / GitLogDto.kt
│     │  └─ ApiErrorDto.kt
│     └─ ws/
│        ├─ WsFrame.kt                          ← sealed class (AuthFrame / LogFrame / DoneFrame / ErrorFrame / PingFrame)
│        └─ WsType.kt                           ← const
│
├─ server/                                     ← :server (Kotlin/JVM, Ktor)
│  ├─ build.gradle.kts
│  ├─ src/main/kotlin/com/siamakerlab/vibecoder/server/
│  │  ├─ ServerMain.kt                          ← main(args), 페어링 코드 출력, embeddedServer
│  │  ├─ Module.kt                              ← Application.module() — install plugins, register routes
│  │  ├─ config/
│  │  │  ├─ ServerConfig.kt                     ← @Serializable data classes
│  │  │  └─ ConfigLoader.kt
│  │  ├─ db/
│  │  │  ├─ Database.kt                         ← Database.connect(SQLite WAL)
│  │  │  ├─ Schemas.kt                          ← Exposed Table objects
│  │  │  └─ Migrations.kt                       ← SchemaUtils.createMissingTablesAndColumns
│  │  ├─ repo/
│  │  │  ├─ DeviceRepository.kt
│  │  │  ├─ ProjectRepository.kt
│  │  │  ├─ TaskRepository.kt
│  │  │  ├─ BuildRepository.kt
│  │  │  ├─ ArtifactRepository.kt
│  │  │  └─ UploadedFileRepository.kt
│  │  ├─ core/
│  │  │  ├─ WorkspacePath.kt                    ← 안전 경로 빌더
│  │  │  ├─ PathSafety.kt                       ← isInside, normalize, traversal check
│  │  │  ├─ OsType.kt                           ← WINDOWS / LINUX / MAC + gradleWrapperName
│  │  │  ├─ Sha256.kt
│  │  │  ├─ ProcessRunner.kt                    ← timeout + destroyForcibly + stdout/stderr stream
│  │  │  ├─ Ids.kt                              ← TaskId / BuildId / ArtifactId 생성
│  │  │  └─ Clock.kt                            ← Instant.now wrapper for testing
│  │  ├─ auth/
│  │  │  ├─ PairingCode.kt                      ← 6-digit, 10-min TTL
│  │  │  ├─ TokenService.kt                     ← issue / hash / verify
│  │  │  ├─ AuthPlugin.kt                       ← Ktor plugin (Bearer)
│  │  │  └─ WsAuth.kt                           ← 첫 메시지 auth + 5초 timeout
│  │  ├─ env/
│  │  │  ├─ EnvDiagnostics.kt                   ← java, sdk, git, claude, workspace 5종 진단
│  │  │  └─ StatusService.kt                    ← /api/server/status 응답 빌더
│  │  ├─ projects/
│  │  │  ├─ ProjectService.kt                   ← 등록 시 검증/.vibecoder/CLAUDE.md 생성
│  │  │  ├─ ClaudeMdTemplate.kt
│  │  │  └─ ProjectRoutes.kt
│  │  ├─ tasks/
│  │  │  ├─ TaskQueue.kt                        ← project-level Mutex 큐
│  │  │  ├─ TaskExecutor.kt                     ← interface — execute(taskId): Flow<LogLine>
│  │  │  ├─ TaskService.kt                      ← 큐 등록 + WS 연결
│  │  │  ├─ TaskRoutes.kt
│  │  │  └─ TaskCancel.kt
│  │  ├─ claude/
│  │  │  ├─ ClaudeRunner.kt                     ← claude -p 실행, wrappedPrompt 생성, autoBuild 옵션
│  │  │  ├─ ClaudePromptBuilder.kt
│  │  │  └─ ClaudeRoutes.kt
│  │  ├─ build/
│  │  │  ├─ GradleBuilder.kt                    ← OS 분기, --no-daemon, timeout
│  │  │  ├─ ApkFinder.kt                        ← outputs/apk/debug/*.apk 중 최신
│  │  │  ├─ BuildService.kt
│  │  │  └─ BuildRoutes.kt
│  │  ├─ artifacts/
│  │  │  ├─ ArtifactService.kt                  ← 복사 + sha256 + metadata.json
│  │  │  └─ ArtifactRoutes.kt
│  │  ├─ git/
│  │  │  ├─ GitReader.kt                        ← status/diff/log 화이트리스트만
│  │  │  └─ GitRoutes.kt
│  │  ├─ files/
│  │  │  ├─ UploadService.kt                    ← 확장자 차단, path normalize
│  │  │  └─ FileRoutes.kt
│  │  ├─ ws/
│  │  │  ├─ LogHub.kt                           ← taskId/buildId별 broadcast (SharedFlow)
│  │  │  └─ WsRoutes.kt
│  │  ├─ error/
│  │  │  ├─ ApiException.kt
│  │  │  └─ StatusPagesPlugin.kt
│  │  └─ Logging.kt
│  └─ src/main/resources/
│     ├─ logback.xml
│     └─ config/                                ← 기본 YAML 6종
│        ├─ server.yml
│        ├─ workspace.yml
│        ├─ claude.yml
│        ├─ android.yml
│        ├─ git.yml
│        └─ security.yml
│
└─ android-app/                                ← :android-app
   └─ app/
      ├─ build.gradle.kts
      ├─ src/main/AndroidManifest.xml
      ├─ src/main/kotlin/com/siamakerlab/vibecoder/console/
      │  ├─ VibeCoderApp.kt                     ← @HiltAndroidApp
      │  ├─ MainActivity.kt                     ← Compose entry + Navigation
      │  ├─ ui/
      │  │  ├─ theme/
      │  │  │  ├─ Color.kt
      │  │  │  ├─ Theme.kt
      │  │  │  └─ Type.kt
      │  │  ├─ nav/
      │  │  │  ├─ Routes.kt
      │  │  │  └─ AppNavHost.kt
      │  │  ├─ connect/                         ← 14.1 Server Connect Screen
      │  │  ├─ dashboard/                       ← 14.2
      │  │  ├─ environment/                     ← 14.3
      │  │  ├─ projects/                        ← 14.4 list + 14.5 register + 14.6 detail
      │  │  ├─ claude/                          ← 14.7 prompt
      │  │  ├─ log/                             ← 14.8 (Claude+Build 공용)
      │  │  ├─ build/                           ← 14.9
      │  │  ├─ artifact/                        ← 14.10
      │  │  ├─ git/                             ← 14.11
      │  │  ├─ files/                           ← 14.12
      │  │  └─ common/                          ← StatusChip, LogLineRow 등
      │  ├─ data/
      │  │  ├─ remote/
      │  │  │  ├─ KtorClient.kt                 ← Bearer + timeout
      │  │  │  ├─ ApiService.kt                 ← 모든 REST API
      │  │  │  ├─ WsClient.kt                   ← 첫 메시지 auth, LogFrame Flow
      │  │  │  └─ DownloadService.kt            ← APK 다운로드 + 진행률
      │  │  ├─ local/
      │  │  │  └─ AppPreferences.kt             ← DataStore (serverUrl/token/deviceName)
      │  │  └─ repository/
      │  │     ├─ AuthRepository.kt
      │  │     ├─ ServerRepository.kt
      │  │     ├─ ProjectRepository.kt
      │  │     ├─ TaskRepository.kt
      │  │     ├─ BuildRepository.kt
      │  │     ├─ ArtifactRepository.kt
      │  │     ├─ GitRepository.kt
      │  │     └─ FileRepository.kt
      │  ├─ domain/
      │  │  └─ model/                           ← DTO → UI 변환 결과
      │  ├─ install/
      │  │  ├─ ApkInstaller.kt                  ← FileProvider + ACTION_VIEW
      │  │  ├─ UnknownSourcesGuide.kt
      │  │  └─ Sha256Verifier.kt
      │  └─ di/
      │     ├─ NetworkModule.kt
      │     ├─ StorageModule.kt
      │     └─ RepositoryModule.kt
      └─ src/main/res/
         ├─ values/strings.xml                  ← en only
         ├─ values/themes.xml
         ├─ xml/file_paths.xml                  ← FileProvider 설정
         └─ drawable/ ic_launcher_*.xml
```

---

## 5. Data Model (Exposed Tables)

```kotlin
object Devices : Table("devices") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)
    val tokenHash = varchar("token_hash", 128)
    val createdAt = varchar("created_at", 64)
    val lastSeenAt = varchar("last_seen_at", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}
object Projects : Table("projects") { ... }   // id PK, name, package_name, source_path, module_name, debug_task, created_at, updated_at
object Tasks    : Table("tasks")    { ... }   // id PK, project_id, type, status, title, prompt, log_path, error_message, started_at, finished_at, created_at
object Builds   : Table("builds")   { ... }   // id PK, project_id, variant, status, log_path, artifact_id?, error_message, started_at, finished_at, created_at
object Artifacts: Table("artifacts"){ ... }   // id PK, project_id, build_id, type, file_name, file_path, size_bytes, sha256, created_at
object UploadedFiles : Table("uploaded_files") { ... } // id PK, project_id, original_name, file_path, mime_type, size_bytes, created_at
```

(명세 §13 DDL 그대로. Exposed `SchemaUtils.createMissingTablesAndColumns(...)` 적용.)

---

## 6. API Surface (Ktor Routing)

```
authenticate("bearer-or-pair") {
  // pair는 open
  POST   /api/auth/pair
}
authenticate("bearer") {
  GET    /api/auth/me
  POST   /api/auth/logout

  GET    /api/server/status
  GET    /api/server/environment
  POST   /api/server/environment/check
  GET    /api/server/settings
  PUT    /api/server/settings/basic

  GET    /api/projects
  POST   /api/projects/register
  GET    /api/projects/{projectId}
  DELETE /api/projects/{projectId}

  POST   /api/projects/{projectId}/claude/tasks
  GET    /api/projects/{projectId}/claude/tasks
  GET    /api/projects/{projectId}/claude/tasks/{taskId}
  POST   /api/projects/{projectId}/claude/tasks/{taskId}/cancel

  POST   /api/projects/{projectId}/build/debug
  GET    /api/projects/{projectId}/builds
  GET    /api/projects/{projectId}/builds/{buildId}
  POST   /api/projects/{projectId}/builds/{buildId}/cancel

  GET    /api/projects/{projectId}/artifacts
  GET    /api/projects/{projectId}/artifacts/{artifactId}
  GET    /api/projects/{projectId}/artifacts/{artifactId}/download

  GET    /api/projects/{projectId}/git/status
  GET    /api/projects/{projectId}/git/diff
  GET    /api/projects/{projectId}/git/log

  POST   /api/projects/{projectId}/files/upload
  GET    /api/projects/{projectId}/files
  GET    /api/projects/{projectId}/files/{fileId}/download
  DELETE /api/projects/{projectId}/files/{fileId}
}

webSocket {
  /ws/projects/{projectId}/tasks/{taskId}/logs
  /ws/projects/{projectId}/builds/{buildId}/logs
}
```

---

## 7. WebSocket Frame Spec

```kotlin
@Serializable
sealed class WsFrame {
    @Serializable @SerialName("auth")  data class Auth(val token: String) : WsFrame()
    @Serializable @SerialName("log")   data class Log(val taskId: String, val level: String, val message: String, val ts: String) : WsFrame()
    @Serializable @SerialName("done")  data class Done(val taskId: String, val status: String, val errorMessage: String? = null) : WsFrame()
    @Serializable @SerialName("error") data class Error(val code: String, val message: String) : WsFrame()
    @Serializable @SerialName("ping")  data object Ping : WsFrame()
}
```

**Flow**:
1. 클라이언트 연결 → 서버 `withTimeout(5_000) { receiveFrame() as Auth }` 검증
2. 인증 통과 시 `LogHub.subscribe(taskId)` SharedFlow를 cold collector로 변환해 send
3. 작업 종료 시 Done 프레임 → 연결 close

---

## 8. Core Util Specs (`server.core.*`)

### 8.1 PathSafety
```kotlin
object PathSafety {
    fun isInside(root: Path, candidate: Path): Boolean
    fun normalizeAndCheck(root: Path, raw: String): Path  // throws ApiException(403, "path_traversal")
}
```

### 8.2 ProcessRunner
```kotlin
data class ProcessResult(val exitCode: Int, val durationMs: Long, val cancelled: Boolean)

class ProcessRunner(
    private val workdir: Path,
    private val env: Map<String, String> = emptyMap(),
) {
    suspend fun run(
        command: List<String>,
        timeout: Duration,
        onLine: suspend (level: String, line: String) -> Unit,
        cancellation: Flow<Unit> = emptyFlow(),
    ): ProcessResult
}
```
- `ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(false)`
- stdout/stderr 각각 별도 코루틴으로 라인 읽기
- `withTimeout(timeout)` 안에서 `process.waitFor`, 초과 시 `destroyForcibly`
- `cancellation.first()` 수신 시 즉시 `destroyForcibly`

### 8.3 OsType
```kotlin
enum class OsType { WINDOWS, LINUX, MAC;
  companion object { fun detect(): OsType }
  fun gradleWrapper(): String = if (this == WINDOWS) "gradlew.bat" else "./gradlew"
  fun shell(): List<String> = if (this == WINDOWS) listOf("cmd.exe","/c") else listOf("/bin/sh","-c")
}
```

### 8.4 Sha256
```kotlin
object Sha256 {
    fun hashFile(path: Path): String     // SHA-256 hex, 8KB 버퍼 스트리밍
    fun hashString(s: String): String
}
```

---

## 9. Task Queue Design

```kotlin
class TaskQueue(
    private val taskRepo: TaskRepository,
    private val buildRepo: BuildRepository,
) {
    private val projectMutexes = ConcurrentHashMap<String, Mutex>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellations = ConcurrentHashMap<String, MutableSharedFlow<Unit>>()

    fun submit(
        projectId: String,
        taskId: String,
        executor: suspend (cancel: Flow<Unit>) -> Unit,
    ): Job {
        val mutex = projectMutexes.computeIfAbsent(projectId) { Mutex() }
        val cancelFlow = cancellations.computeIfAbsent(taskId) { MutableSharedFlow(1) }
        return scope.launch {
            taskRepo.markPending(taskId)
            mutex.withLock {
                taskRepo.markRunning(taskId)
                try { executor(cancelFlow); taskRepo.markSuccess(taskId) }
                catch (e: CancellationException) { taskRepo.markCanceled(taskId); throw e }
                catch (e: Throwable) { taskRepo.markFailed(taskId, e.message); }
            }
        }
    }

    suspend fun cancel(taskId: String) { cancellations[taskId]?.emit(Unit) }
}
```

---

## 10. Android-side Design

### 10.1 Navigation Graph (sealed class Route)
```kotlin
sealed class Route(val path: String) {
    data object Connect : Route("connect")
    data object Dashboard : Route("dashboard")
    data object Environment : Route("environment")
    data object ProjectList : Route("projects")
    data object ProjectRegister : Route("projects/register")
    data class  ProjectDetail(val projectId: String) : Route("projects/$projectId")
    data class  ClaudePrompt(val projectId: String) : Route("projects/$projectId/claude")
    data class  Log(val projectId: String, val taskId: String, val kind: String) : Route("projects/$projectId/logs/$kind/$taskId")
    data class  Builds(val projectId: String) : Route("projects/$projectId/builds")
    data class  Artifacts(val projectId: String) : Route("projects/$projectId/artifacts")
    data class  Git(val projectId: String) : Route("projects/$projectId/git")
    data class  Files(val projectId: String) : Route("projects/$projectId/files")
}
```

### 10.2 Repository → ViewModel → UI flow

```
ApiService (Ktor + interceptor + Bearer)
   │
   ▼
*Repository (Flow<Result<T>>)
   │
   ▼
*ViewModel (Hilt) — StateFlow<UiState>
   │
   ▼
*Screen Composable — collectAsStateWithLifecycle()
```

### 10.3 LogStream Flow (WebSocket)
```kotlin
class WsClient(...) {
    fun stream(projectId: String, kind: String, taskId: String): Flow<WsFrame> = flow {
        client.webSocket(/* url */) {
            send(json.encodeToString(WsFrame.Auth(token = bearerToken)))
            for (frame in incoming) when (frame) {
                is Frame.Text -> emit(json.decodeFromString<WsFrame>(frame.readText()))
                else -> {}
            }
        }
    }.flowOn(Dispatchers.IO)
}
```

### 10.4 ApkInstaller
```kotlin
class ApkInstaller(private val context: Context) {
    fun verifyAndOpen(apkFile: File, expectedSha256: String) {
        val actual = Sha256Verifier.hex(apkFile)
        require(actual.equals(expectedSha256, ignoreCase = true)) { "sha256_mismatch" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            UnknownSourcesGuide.launch(context); return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
```

---

## 11. Implementation Guide

### 11.1 Implementation Order (Plan §19 9-phase → 11-session)

| Session | Module(s) | Deliverable |
|---------|-----------|-------------|
| **S1** | M01 + 루트 빌드 인프라 | `:shared` 컴파일 + `:server`,`:android-app` skeleton 인식 |
| **S2** | M02 (config/db/repo) + M03 (core util) | SQLite 부팅 + Exposed 테이블 + WorkspacePath/ProcessRunner 단위테스트 |
| **S3** | M04 + M05 | 페어링·Bearer·WS-auth + server status/environment API |
| **S4** | M06 | 프로젝트 등록/조회 + .vibecoder/CLAUDE.md 생성 |
| **S5** | M07 | 작업 큐 + LogHub + /ws/.../logs |
| **S6** | M08 | Claude 실행 + Gradle build + APK 탐색/SHA-256 |
| **S7** | M09 | Git 조회 + 파일 업로드/다운로드 |
| **S8** | M10 | android-app 골격: Hilt + Ktor Client + WsClient + Repository |
| **S9** | M11 (1/3): Connect / Dashboard / Environment / ProjectList / ProjectRegister | 인증부터 프로젝트 목록까지 작동 |
| **S10** | M11 (2/3): ProjectDetail / ClaudePrompt / Log / Build | Claude+Build 흐름 작동 |
| **S11** | M11 (3/3): Artifact + APK install + Git + FileTransfer | 16-step 전체 시나리오 |

### 11.2 Decision Record Chain

```
📋 Decision Record Chain
[Plan] Repository layout: Monorepo (settings include 3 modules)
       — DTO 공유 + 단일 버전 + 단일 CHANGELOG
[Plan] Server stack: Ktor + Exposed + SQLite + kotlinx.serialization
       — Coroutine 친화, 가벼움, JVM-only shared와 정합
[Plan] Android stack: Compose + Hilt + Ktor Client + DataStore
       — Material 3 + Hilt DI + 서버와 동일 직렬화
[Plan] OS priority: Linux-first + Windows 동등
       — 서버 다운 환경 고려, OS 분기 단일 책임(OsType)
[Plan] Pairing/WS auth: console output + first-message auth
       — URL 토큰 노출 회피
[Plan] Concurrency: project-level Mutex
       — 다른 프로젝트 병렬 가능, 동일 프로젝트는 직렬화
[Design] Architecture: Pragmatic Balance (3-layer + util)
       — Clean 4-tier는 1인 MVP에 과잉, Minimal은 회귀 위험 ↑
[Design] WS frame: sealed class WsFrame
       — auth/log/done/error/ping 명시적 type
[Design] Path safety: PathSafety.normalizeAndCheck() 단일 entry
       — R-03 path traversal 방어 단일 책임
[Design] DI: Hilt 2.59.2 (AGP 9 호환)
       — 글로벌 매트릭스 §2-2-1 그대로
```

### 11.3 Session Guide (`/pdca do feature --scope SX` 지원)

각 Session은 다음을 만족해야 다음 Session으로 넘어간다:

- **S1 DONE**: `./gradlew help` 성공, `:shared:compileKotlin` 성공
- **S2 DONE**: `:server:test PathSafetyTest` 통과, SQLite 파일 생성됨
- **S3 DONE**: 서버 부팅 시 페어링 코드 콘솔 출력, `curl /api/server/status` 200
- **S4 DONE**: 프로젝트 등록 → `.vibecoder/project.yml` 및 `CLAUDE.md` 생성됨
- **S5 DONE**: 가짜 sleep 10초 task를 큐에 등록 → WS로 로그 수신
- **S6 DONE**: 등록된 프로젝트에 `claude -p "echo hello"` 실행 + debug build → APK 파일 + sha256
- **S7 DONE**: git status JSON 반환, 1MB 파일 업로드/다운로드 라운드트립
- **S8 DONE**: android `:app:compileDebugKotlin` 성공, Hilt 그래프 컴파일
- **S9 DONE**: 앱에서 페어링→프로젝트 등록 흐름 mock 작동
- **S10 DONE**: Claude/Build 로그가 앱에 흐른다 (서버↔앱 통합)
- **S11 DONE**: APK install Intent까지 열림 / Git 화면 표시 / 파일 업로드 OK

---

## 12. Test Strategy (MVP)

### 12.1 Server 단위 테스트 (`:server:test`)

| Test class | 검증 항목 |
|------------|----------|
| `PathSafetyTest` | `../etc/passwd`, 절대경로, 드라이브 letter (`C:\`), null byte 차단 |
| `OsTypeBuilderSelectorTest` | OsType별 gradlew/.bat 선택 |
| `Sha256Test` | 알려진 입력의 hex 값 일치 |
| `ProcessRunnerTimeoutTest` | sleep 10s + timeout 1s → cancelled=true |
| `PairingCodeTest` | 10분 만료, 1회 사용 후 invalid |
| `TaskQueueProjectMutexTest` | 동일 projectId 2건 동시 submit → 직렬화 |

### 12.2 Android 수동 검증

- 페어링 흐름 (콘솔 코드 → 입력 → 토큰 저장)
- Claude 프롬프트 → 로그 흐름
- 빌드 → APK 다운로드 → SHA-256 검증 → 설치 Intent

---

## 13. Next Steps

1. **`/pdca do vibe-coder-mvp`** → S1부터 순서대로 구현
2. 각 Session 종료 시 SC 체크리스트 갱신
3. S11 완료 후 `/pdca analyze`

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-05-17 | Initial draft. Pragmatic Balance 선택, 11-session 분할 정의. | sia@siamakerlab.com |
