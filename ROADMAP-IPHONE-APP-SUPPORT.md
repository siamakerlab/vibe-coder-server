# iPhone App Support Roadmap

> 내부 문서 (한국어). Kotlin/Flutter 프로젝트 지원 이후, Vibe Coder Server 가
> iPhone 앱 개발을 지원하기 위한 단계별 로드맵.
>
> 기준일: 2026-07-22. 현재 서버의 기본 실행 환경은 Linux Docker 이므로, iPhone 앱의
> 실제 빌드/시뮬레이터/서명/배포는 macOS + Xcode 가 있는 빌드 에이전트가 담당한다.
> 단, Vibe Coder Server 를 MacBook 에 직접 설치한 경우에는 로컬 Xcode Simulator 를
> 실행할 수 있다.

## 0. 목표

Vibe Coder Server 의 웹 UI/TUI 중심 개발 경험은 유지하면서, iPhone 앱 프로젝트도 생성,
AI 수정, 빌드, 테스트, 아카이브, TestFlight 업로드까지 같은 운영 흐름에서 다룰 수 있게 한다.

핵심 목표:

- `projectType` 을 Kotlin/Flutter 중심에서 iPhone 네이티브까지 확장한다.
- Linux 서버는 프로젝트/콘솔/히스토리/빌드 요청/아티팩트 관리를 담당한다.
- macOS 빌드 에이전트는 Xcode, `xcodebuild`, Simulator, codesign, notarization 성격의
  Apple 전용 작업을 담당한다.
- Simulator 실행은 MacBook/macOS 설치 환경에서만 허용한다. Linux Docker 단독 환경에서는
  Simulator 관련 버튼을 비활성화하고 원격 macOS agent 연결 안내만 표시한다.
- 단일 사용자 운영 모델을 유지한다. 팀 권한, 멀티테넌트, 복잡한 CI SaaS 기능은 넣지 않는다.

## 1. 제약과 설계 원칙

### 1.1 반드시 인정해야 하는 제약

- iPhone 앱 빌드는 Xcode가 필요하다. Linux Docker 컨테이너 안에서 완결할 수 없다.
- iOS/iPadOS Simulator 실행도 macOS가 필요하다. Android식 emulator가 아니라 Xcode
  Simulator를 사용한다.
- 실기기 설치/디버깅은 Apple Developer 계정, signing certificate, provisioning profile,
  연결된 기기 신뢰 상태에 영향을 받는다.
- App Store Connect/TestFlight 업로드는 인증 키, bundle id, team id, profile 관리가 필요하다.

### 1.2 원칙

- 서버는 Apple 비밀키를 평문 로그에 남기지 않는다.
- signing 자산은 프로젝트 파일과 분리해서 관리한다.
- 빌드 에이전트 연결이 없어도 프로젝트 생성, 파일 편집, AI 콘솔 작업은 가능해야 한다.
- iPhone 지원은 네이티브 Swift/SwiftUI를 1차 대상으로 한다.
- Simulator 실행 범위는 iPhone/iPad Simulator로 제한한다. watchOS, tvOS, visionOS,
  macOS destination은 초기 UI에서 숨긴다.
- Flutter iOS는 2차 대상으로 한다. 현재 Flutter 지원은 Android 전용으로 제한되어 있으므로,
  iPhone 지원과 함께 Flutter 플랫폼 정책을 분리해야 한다.
- Kotlin Multiplatform/iOS는 3차 이후 검토한다. 초기 범위에 넣으면 빌드/템플릿/IDE 모델이
  과도하게 넓어진다.

## 2. 목표 아키텍처

### 2.0 Platform Engine 캡슐화 원칙

Kotlin, Flutter, iPhone은 `projectType` 문자열 분기만 추가하는 방식으로 확장하지 않는다.
각 플랫폼은 독립된 “Platform Engine” 캡슐을 가진다.

목표:

- 플랫폼별 빌드, 템플릿, 타입 감지, 환경 진단, 코드 분석, 아이콘/버전 파싱, 실행/배포 정책을
  한 엔진 안에 모은다.
- 공통 서버 계층은 `ProjectPlatformEngine` 인터페이스만 호출한다.
- 새 플랫폼 추가 시 `when(projectType)` 분기가 라우트/서비스 전역으로 퍼지지 않게 한다.
- Kotlin/Flutter 기존 동작을 iPhone 지원 때문에 흔들지 않는다.
- Kotlin과 Flutter도 서로 독립 엔진이다. 둘 다 현재 Android 산출물을 만들 수 있다는 이유로
  `AndroidPlatformEngine` 같은 공통 엔진 아래에 묶지 않는다.
- Kotlin은 Gradle/Kotlin/Compose 계열 책임을 갖고, Flutter는 Flutter/Dart/pubspec 계열 책임을
  갖는다. 공유 가능한 것은 하위 유틸리티뿐이며, 정책 결정은 각 엔진이 한다.
- 전역 `CLAUDE.md`/`AGENTS.md`는 모바일 개발 공통 운영 규칙만 담는다. Kotlin, Flutter,
  iPhone 전용 규칙은 프로젝트 생성/등록 시 선택된 engine이 지역 프로젝트 `CLAUDE.md`로 주입한다.

초기 엔진:

| Engine | projectType | 책임 | 외부 도구 |
|---|---|---|---|
| `KotlinPlatformEngine` | `kotlin` | Gradle/Kotlin 앱 템플릿, Gradle build, Android icon/version 파싱 | JDK, Gradle |
| `FlutterPlatformEngine` | `flutter` | Flutter 템플릿/감지, Flutter Android build, pubspec version/icon 파싱 | Flutter, Android SDK |
| `IPhonePlatformEngine` | `iphone` | SwiftUI 템플릿, Xcode build, Simulator, signing, TestFlight | macOS, Xcode |

Kotlin과 Flutter 분리 기준:

| 책임 | KotlinPlatformEngine | FlutterPlatformEngine |
|---|---|---|
| 언어/프레임워크 | Kotlin, Gradle, Compose | Dart, Flutter |
| 생성 템플릿 | Gradle project/module, Compose starter | `flutter create`/Flutter starter, platform folders policy |
| 타입 감지 | Gradle settings/build files, Kotlin/Java sources | root `pubspec.yaml`, Flutter metadata, `android/`/future `ios/` |
| 버전 파싱 | Gradle `versionName`/`versionCode` | `pubspec.yaml` `version:` |
| 아이콘 파싱 | `<module>/src/main/res` | `android/app/src/main/res`, future `ios/Runner/Assets.xcassets` |
| 빌드 | Gradle tasks | `flutter build ...` |
| 품질/분석 | Gradle/Lint/Kotlin symbol | `flutter analyze`, Dart/Flutter symbol |
| 서명/배포 | Android keystore/Play path | Flutter target별 signing: Android key.properties, future iOS signing |
| Skill/Agent | Kotlin/Compose 계열 | Flutter/Dart 계열 |

### 2.0.1 Platform Engine 인터페이스 초안

```kotlin
interface ProjectPlatformEngine {
    val type: String
    val displayNameKey: String

    fun detect(root: Path): PlatformDetection?
    fun scaffold(request: ProjectScaffoldRequest): PlatformScaffoldResult
    fun renderProjectMemory(info: ProjectInfo): PlatformProjectMemory
    fun environmentProbe(projectId: String): PlatformEnvironmentSnapshot
    fun build(request: PlatformBuildRequest): PlatformBuildJob
    fun artifacts(projectId: String): List<PlatformArtifact>
    fun appVersion(projectId: String): String?
    fun appIcon(projectId: String): PlatformIcon?
    fun symbols(projectId: String, query: String): List<PlatformSymbol>
    fun quickPrompts(projectId: String): List<QuickPrompt>
    fun capabilities(projectId: String): PlatformCapabilities
}
```

`PlatformProjectMemory`는 지역 `CLAUDE.md` 생성 결과다.

```kotlin
data class PlatformProjectMemory(
    val fileName: String = "CLAUDE.md",
    val body: String,
    val overwritePolicy: ProjectMemoryOverwritePolicy = ProjectMemoryOverwritePolicy.CREATE_ONLY,
)
```

기본 인터페이스는 작게 시작하고, iPhone처럼 실행/배포가 필요한 플랫폼은 보조 인터페이스를
추가한다.

```kotlin
interface RunnablePlatformEngine {
    fun devices(projectId: String): List<PlatformDevice>
    fun run(request: PlatformRunRequest): PlatformRunJob
}

interface ReleasablePlatformEngine {
    fun signingStatus(projectId: String): SigningSnapshot
    fun packageForRelease(request: ReleasePackageRequest): PlatformBuildJob
    fun upload(request: ReleaseUploadRequest): ReleaseUploadJob
}
```

### 2.0.2 Engine Registry

공통 서비스는 registry를 통해 엔진을 찾는다.

```kotlin
class PlatformEngineRegistry(
    engines: List<ProjectPlatformEngine>,
) {
    fun forType(projectType: String): ProjectPlatformEngine
    fun detect(root: Path): List<PlatformDetection>
}
```

Registry 규칙:

- 알 수 없는 타입은 자동으로 Kotlin으로 흡수하지 않는다. 생성/clone 시점에는 명확한 fallback UI를
  띄우고, DB에 저장된 구버전 값만 migration fallback을 허용한다.
- detection이 복수 후보를 반환하면 confidence와 근거를 UI에 표시한다.
- platform-specific 설정은 각 엔진 store가 소유한다.

### 2.0.3 모듈/패키지 배치안

```text
server/platform/
  core/
    ProjectPlatformEngine.kt
    PlatformEngineRegistry.kt
    PlatformDetection.kt
    PlatformCapabilities.kt
  kotlin/
    KotlinPlatformEngine.kt
    KotlinTemplate.kt
    KotlinBuildAdapter.kt
    KotlinProjectInspector.kt
  flutter/
    FlutterPlatformEngine.kt
    FlutterTemplate.kt
    FlutterBuildAdapter.kt
    FlutterProjectInspector.kt
  iphone/
    IPhonePlatformEngine.kt
    IPhoneTemplate.kt
    XcodeToolchain.kt
    IosAgent.kt
    IosSigningStore.kt
    IosSimulatorService.kt
```

현재 `server/build/BuildToolchain`은 `platform/*/*BuildAdapter`로 감싸거나 점진적으로 이동한다.
초기 단계에서는 기존 클래스를 유지하고 `KotlinPlatformEngine`/`FlutterPlatformEngine`이
어댑터로 호출한다.

### 2.0.4 캡슐화 금지/허용

금지:

- `WebProjectRoutes`, `ProjectService`, `BuildService` 등에 `if (projectType == "iphone")`
  분기 추가
- Kotlin과 Flutter를 `AndroidPlatformEngine` 같은 단일 엔진으로 합치기
- Flutter 프로젝트를 Kotlin/Gradle 프로젝트의 특수 케이스로 처리하기
- Kotlin 프로젝트를 Flutter target의 특수 케이스로 처리하기
- iPhone signing/Simulator 상태를 Kotlin/Flutter UI 모델에 끼워 넣기
- Flutter iOS 정책을 기존 Flutter Android build path에 암묵적으로 추가
- 공통 DTO에 플랫폼별 optional 필드를 계속 덧붙이기
- 전역 `container-global-claude.*.md`에 Kotlin/Flutter/iPhone 세부 규칙을 계속 추가하기
- 프로젝트 생성 시 선택한 플랫폼과 다른 지역 `CLAUDE.md` 템플릿을 주입하기

허용:

- 공통 route가 engine capability를 보고 버튼 노출
- 공통 build queue가 engine build job을 실행
- 공통 artifact repository가 platform artifact type만 받아 저장
- platform-specific settings route를 engine별 namespace로 분리
- 전역 `CLAUDE.md`에는 secret/log/workspace/MCP/공식문서 확인 같은 모바일 공통 규칙만 유지
- 지역 `CLAUDE.md`는 engine별 template renderer가 소유

### 2.0.5 Engine Capability Matrix

| Capability | Kotlin | Flutter | iPhone |
|---|:---:|:---:|:---:|
| Scaffold | 예 | 예 | 예 |
| Clone detection | 예 | 예 | 예 |
| Build debug | 예 | 예 | 예, Mac 필요 |
| Build release package | APK/AAB | APK/AAB, iOS는 후속 | IPA |
| Run in device/simulator | 기존 Android 기능 | Android/후속 iOS | iPhone/iPad Simulator |
| Signing management | keystore | Android key.properties, iOS 후속 | certificate/profile/keychain |
| Store upload | Play | Play, App Store 후속 | TestFlight/App Store Connect |
| Symbol scan | Kotlin/Java | Dart/Flutter 후속 | Swift/SwiftUI |
| Platform-specific skills/agents | Kotlin/Compose 계열 | Flutter/Dart 계열 | iPhone/SwiftUI 계열 |

Kotlin과 Flutter의 capability가 같은 이름을 가져도 구현은 공유하지 않는다. 예를 들어
`build debug`는 공통 build queue에 올라갈 수 있지만, Kotlin은 Gradle task를 만들고 Flutter는
Flutter CLI command를 만든다.

### 2.0.6 전역/지역 AI 지침 분리

전역 지침(`container-global-claude.ko.md`, `container-global-claude.en.md`,
`~/.claude/CLAUDE.md`, `~/.codex/AGENTS.md`)은 모든 모바일 프로젝트에 공통으로 적용되는
운영 규칙만 담는다.

전역에 남길 것:

| 영역 | 내용 |
|---|---|
| 보안 | secret 로그/커밋 금지, workspace boundary, raw shell 금지 |
| 운영 | long-running 작업, non-interactive 명령, build/test 후 요약 |
| 도구 | MCP 연결 확인, 공식 문서 확인 원칙, skill/agent 사용 규칙 |
| 모바일 공통 | 접근성, 터치 타깃, 네트워크/권한/스토어 secret 주의 |
| 서버 공통 | Vibe Coder 경로, artifact/log/history 사용 방식 |

전역에서 제거할 것:

| 제거 대상 | 이동 위치 |
|---|---|
| Kotlin/Compose 세부 UI/Gradle 규칙 | `KotlinPlatformEngine.renderProjectMemory()` |
| Flutter/Dart/pubspec/build 규칙 | `FlutterPlatformEngine.renderProjectMemory()` |
| iPhone/SwiftUI/Xcode/signing 규칙 | `IPhonePlatformEngine.renderProjectMemory()` |
| Android 전용 emulator/keystore/Play 지침 | Kotlin 또는 Flutter Android target 지역 지침 |
| 플랫폼별 quick prompt/agent 추천 | 각 engine capability |

지역 프로젝트 `CLAUDE.md`는 프로젝트 생성/등록 시 선택된 engine이 생성한다.

| projectType | 생성 주체 | 지역 지침 내용 |
|---|---|---|
| `kotlin` | `KotlinPlatformEngine` | Kotlin/Compose/Gradle, module, lint/build, keystore/Play |
| `flutter` | `FlutterPlatformEngine` | Dart/Flutter/pubspec, target platform policy, flutter analyze/build |
| `iphone` | `IPhonePlatformEngine` | Swift/SwiftUI/Xcode, Simulator, signing, TestFlight |

Overwrite 정책:

- 신규 프로젝트: 지역 `CLAUDE.md`를 생성한다.
- clone 프로젝트: 기존 `CLAUDE.md`가 있으면 덮어쓰지 않고 “엔진 권장 지침 삽입” 액션만 제공한다.
- 프로젝트 타입 변경: 기존 파일을 보존하고 새 플랫폼 지침 preview/diff를 제공한다.
- archive/restore: 지역 `CLAUDE.md`는 프로젝트 파일로 보존한다.

### 2.0.7 플랫폼별 지역 `CLAUDE.md` 최소 내용

프로젝트 생성 UI에서 `kotlin`, `flutter`, `iphone` 중 하나를 선택하면 해당 engine이 아래
내용을 지역 `CLAUDE.md`에 주입한다. 전역 `CLAUDE.md`는 이 내용을 포함하지 않는다.

| projectType | 반드시 포함할 내용 | 포함하면 안 되는 내용 |
|---|---|---|
| `kotlin` | Gradle module/task, Kotlin/Java symbol, Compose/Kotlin 스타일, lint/test/build 명령, keystore/Play 정책 | Dart/pubspec, Xcode/simctl, App Store Connect |
| `flutter` | Dart/Flutter/pubspec, `flutter analyze/test/build`, target platform policy, Flutter asset/icon/version 정책 | Kotlin module 내부 구조를 직접 가정하는 규칙, Xcode native 수정 규칙 |
| `iphone` | Swift/SwiftUI/Xcode, `xcodebuild`/`simctl` 검증, iPhone/iPad Simulator 제한, signing/TestFlight secret 정책 | Gradle/Play 정책, Flutter pubspec 규칙, watchOS/tvOS/visionOS/macOS 대상 |

지역 지침 생성은 다음 경로로만 허용한다.

```kotlin
PlatformEngineRegistry.forType(projectType)
    .renderProjectMemory(projectInfo)
```

금지:

- project creation route에서 `if (projectType == "flutter")` 같은 분기문으로 `CLAUDE.md`를 직접 쓰기
- 전역 template에 플랫폼별 instruction을 남겨 놓고 생성 프로젝트가 이를 상속하도록 만들기
- Kotlin과 Flutter가 같은 “Android 계열” template을 공유하기
- iPhone 프로젝트에 Mac preflight 없이 Simulator 실행 가능하다고 안내하기

지역 `CLAUDE.md` 품질 기준:

- 첫 화면에서 현재 프로젝트 타입과 사용 가능한 명령이 명확해야 한다.
- AI provider가 다른 플랫폼의 빌드/배포 도구를 호출하지 않도록 금지 규칙을 포함한다.
- secret, signing, store upload는 전역 보안 규칙을 반복하지 않고 프로젝트별 구체 경로만 보강한다.
- engine snapshot test로 생성 결과를 고정한다.

### 2.1 Server

- 프로젝트 등록, 타입 감지, 템플릿 생성
- AI provider TUI 세션 관리
- macOS agent 로 빌드/테스트/시뮬레이터 작업 요청
- 빌드 로그 WebSocket 중계
- `.ipa`, `.xcarchive`, dSYM, test result bundle 다운로드 관리
- App Store Connect 업로드 요청/상태 표시

서버가 직접 하지 않는 일:

- Linux 컨테이너 안에서 Xcode build/simulator 실행 시도
- Apple signing secret 을 프로젝트 소스 폴더에 저장
- 사용자가 명시하지 않은 App Store Connect 쓰기 작업
- watchOS/tvOS/visionOS/macOS destination 자동 선택

### 2.2 macOS Build Agent

작은 독립 실행 프로세스로 둔다.

- workspace checkout/sync
- Xcode 버전/SDK/provisioning 상태 진단
- `xcodebuild build/test/archive/exportArchive`
- iPhone/iPad Simulator boot/install/launch/screenshot/log stream
- signing certificate/profile 조회
- TestFlight 업로드 (`xcrun altool` 또는 `notarytool`/Transporter 계열 중 실제 사용 가능한 경로)

Agent 작업은 모두 job 단위로 기록한다.

| Job | 입력 | 출력 | 실패 분류 |
|---|---|---|---|
| `IOS_PREFLIGHT` | agent config | Xcode/SDK/Simulator/signing snapshot | `agent_unreachable`, `xcode_missing`, `simctl_missing` |
| `IOS_SYNC` | project id, revision | synced path, changed file count | `rsync_failed`, `workspace_unsafe` |
| `IOS_BUILD_DEBUG` | scheme, destination | `.app`, `.xcresult`, log | `compile_failed`, `scheme_missing`, `simulator_unavailable` |
| `IOS_TEST` | scheme, destination, test plan | `.xcresult`, screenshots | `test_failed`, `test_host_failed` |
| `IOS_ARCHIVE` | scheme, configuration | `.xcarchive`, dSYM | `archive_failed`, `signing_failed` |
| `IOS_EXPORT_IPA` | archive, export options | `.ipa`, dSYM | `export_failed`, `profile_mismatch` |
| `IOS_SIM_RUN` | `.app`, device id | screenshot, app log | `install_failed`, `launch_failed`, `screenshot_failed` |
| `IOS_TESTFLIGHT_UPLOAD` | `.ipa`, ASC key ref | upload id, processing state | `asc_auth_failed`, `duplicate_build`, `upload_failed` |

### 2.3 실행 모드 제한

| 서버 설치 위치 | Xcode build | iPhone/iPad Simulator | signing/export | 비고 |
|---|---:|---:|---:|---|
| Linux Docker 단독 | 불가 | 불가 | 불가 | 프로젝트/AI/파일 관리만 가능 |
| Linux Docker + 원격 Mac agent | 가능 | 가능 | 가능 | 서버가 Mac agent에 작업 위임 |
| MacBook 로컬 설치 | 가능 | 가능 | 가능 | 로컬 Xcode/Simulator 직접 사용 |

UI 제한:

- `xcrun simctl` 진단이 통과한 경우에만 Simulator 카드와 실행 버튼을 표시한다.
- 대상 device family는 iPhone/iPad Simulator만 노출한다.
- watchOS/tvOS/visionOS/macOS destination은 감지되더라도 초기 UI에서 숨긴다.
- MacBook 로컬 설치 여부는 `uname`, `xcode-select -p`, `xcrun simctl list` 진단으로
  판정한다.

초기 연결 방식:

- Phase 1: SSH command runner
- Phase 2: 전용 agent HTTP/WebSocket API
- Phase 3: persistent job queue + artifact streaming

### 2.4 공식 문서 기준선

구현 시 최신 공식 문서를 확인해야 하는 영역:

- Xcode command-line tool reference: `xcodebuild`, `xcrun simctl`, result bundle 옵션.
- Simulator guide: screenshot, video, app install/launch, booted device 대상 명령.
- App Store Connect API: API key 생성, role, TestFlight build/status API.
- Xcode archive/export: `archive`, `-exportArchive`, `exportOptions.plist`, signing 옵션.

규칙:

- 명령 옵션은 기억으로 고정하지 않고 구현 시점의 `xcodebuild -help`, `xcrun simctl help`,
  Apple 문서를 함께 확인한다.
- `xcrun altool`/Transporter/ASC API 중 업로드 경로는 구현 시점의 Apple 권장 경로로 확정한다.
- 문서 확인 결과가 바뀌면 이 로드맵의 Phase 4/6을 먼저 갱신한다.

### 2.5 Server 데이터 모델 초안

추가/확장 후보:

| 모델 | 필드 | 목적 |
|---|---|---|
| `ProjectTypes.IPHONE` | `iphone` | Swift/SwiftUI iPhone 프로젝트 식별 |
| `IosProjectSettings` | projectId, scheme, workspacePath, projectPath, bundleId, teamId, deploymentTarget | Xcode build 기본값 |
| `IosAgentConfig` | mode, host, port, user, workspaceRoot, xcodePath, enabled | MacBook 로컬/원격 Mac agent 연결 |
| `IosSigningAsset` | teamId, certFingerprint, profileUuid, profileName, expiresAt, keychainRef | signing preflight 표시 |
| `IosBuildJob` | id, projectId, jobType, status, startedAt, finishedAt, failureKind, artifactIds | 빌드/테스트/배포 이력 |
| `IosSimulatorDevice` | udid, name, runtime, deviceType, state, lastSeenAt | Simulator inventory/cache |
| `IosReleaseUpload` | buildJobId, ascBuildId, version, buildNumber, processingState | TestFlight 추적 |

초기에는 기존 build job/artifact 테이블을 최대한 재사용하고, iOS 전용 설정/agent/signing만 별도
store로 분리한다.

### 2.6 API / Route 초안

신규 endpoint는 구현 시 `ApiPath`에 먼저 등록한다.

| Method | Path | 용도 |
|---|---|---|
| `GET` | `/api/ios/preflight` | MacBook 로컬/원격 agent 진단 |
| `GET/POST` | `/api/ios/agent-config` | iOS agent 설정 조회/저장 |
| `GET/POST` | `/api/projects/{id}/ios/settings` | scheme/workspace/bundle/team 설정 |
| `POST` | `/api/projects/{id}/ios/sync` | Mac workspace 동기화 |
| `POST` | `/api/projects/{id}/ios/build/debug` | simulator debug build |
| `POST` | `/api/projects/{id}/ios/build/test` | simulator test |
| `POST` | `/api/projects/{id}/ios/build/archive` | generic iOS archive |
| `POST` | `/api/projects/{id}/ios/build/export-ipa` | IPA export job |
| `POST` | `/api/projects/{id}/ios/test` | XCTest/UI test |
| `POST` | `/api/projects/{id}/ios/archive` | `.xcarchive` 생성 |
| `POST` | `/api/projects/{id}/ios/export-ipa` | `.ipa` export |
| `GET` | `/api/ios/simulators` | iPhone/iPad Simulator 목록 |
| `POST` | `/api/projects/{id}/ios/simulators/{udid}/run` | install/launch/screenshot |
| `GET` | `/api/projects/{id}/ios/simulators/{udid}/logs` | 최근 앱 로그 |
| `POST` | `/api/projects/{id}/ios/testflight/upload` | TestFlight 업로드 |

### 2.7 Job 상태머신

모든 iOS job은 같은 상태를 쓴다.

| 상태 | 의미 |
|---|---|
| `queued` | 서버가 요청을 수락했고 실행 대기 |
| `syncing` | Mac workspace 동기화 중 |
| `running` | agent 명령 실행 중 |
| `collecting_artifacts` | `.app`/`.ipa`/`.xcresult`/dSYM 회수 중 |
| `completed` | 정상 완료 |
| `failed` | 재시도해도 자동 복구 어려운 실패 |
| `cancelled` | 사용자가 중단 |
| `blocked` | Mac/secret/signing 등 선행조건 미충족 |

UI는 `blocked`와 `failed`를 구분한다. 예를 들어 Xcode 미설치, agent 미연결, ASC key 누락은
실패가 아니라 사용자가 조치해야 하는 blocked 상태로 표시한다.

### 2.8 보안 경계

- agent 명령은 allowlist builder로만 생성한다. raw shell string 입력은 금지한다.
- source sync는 workspace root 내부 프로젝트만 허용한다.
- `rsync --delete` 대상 경로는 agent workspace root 아래 project slug로 제한한다.
- signing private key, `.p12`, `.mobileprovision`, `.p8`은 artifact/history/log에 포함하지 않는다.
- build log sanitizer는 다음 패턴을 마스킹한다:
  - App Store Connect private key block
  - certificate password
  - keychain password
  - provisioning profile raw XML 중 민감 필드
  - Apple ID/session token
- release/upload MCP는 사용자가 명시적으로 요청한 turn에서만 호출한다.

### 2.9 프로젝트 타입

기존 `projectType` 확장 후보:

| 값 | 의미 | 1차 지원 |
|---|---|---|
| `kotlin` | 기존 Kotlin 앱 프로젝트 | 기존 유지 |
| `flutter` | Flutter 프로젝트 | 기존 유지, iOS 빌드 정책은 Phase 5에서 분리 |
| `iphone` | Swift/SwiftUI iPhone 앱 | 신규 1차 목표 |
| `ios-universal` | iPhone/iPad universal 앱 | 후속 |
| `kmp-ios` | Kotlin Multiplatform iOS target | 후속 검토 |

초기 프로젝트 타입은 `iphone` 하나만 추가한다. 다만 MacBook/macOS agent 환경에서는
iPad Simulator 실행을 허용해 레이아웃 확인까지 가능하게 한다. iPad 전용 앱 타입,
macOS/watchOS/tvOS/visionOS 앱 타입은 제외한다.

## 3. Phase 0 — 조사와 경계 확정

목표: 실제 구현 전에 macOS 의존성과 서버 책임 범위를 확정한다.

### 3.1 조사 티켓

- [x] 기존 Kotlin/Flutter 분기 위치 조사:
  - `BuildService.toolchainFor`
  - `ProjectService` 타입 감지/버전/아이콘
  - `ClaudeMdTemplate`
  - `container-global-claude.ko.md` / `container-global-claude.en.md` 플랫폼별 규칙
  - 프로젝트 생성 폼/배지/i18n
  - 품질/배포/환경 진단 route
- [x] `ProjectPlatformEngine` 최소 인터페이스 확정.
- [x] Kotlin/Flutter 기존 구현을 engine adapter로 감싸는 migration 계획 확정.
- [x] 전역 CLAUDE.md 축소 범위 확정:
  - 모바일 공통 규칙만 유지
  - Kotlin/Compose 규칙은 Kotlin 지역 템플릿으로 이동
  - Flutter/Dart 규칙은 Flutter 지역 템플릿으로 이동
  - iPhone/SwiftUI 규칙은 iPhone 지역 템플릿으로 신규 작성
- [x] macOS agent 최소 요구사항 정리: macOS 버전, Xcode 버전, command line tools, Ruby/fastlane 필요 여부.
  - `docs/iphone-macos-agent-requirements.md`
- [x] MacBook 로컬 설치 모드 요구사항 정리: JDK, PostgreSQL, Xcode, command line tools,
  `xcode-select`, workspace path 권한.
  - `docs/iphone-macos-agent-requirements.md`
- [x] Apple Developer 계정 작업 목록 정리: team id, bundle id, certificate, profile, ASC API key.
  - `docs/iphone-macos-agent-requirements.md`
- [x] `xcodebuild` 명령 표준화:
  - debug simulator build
  - release archive
  - export ipa
  - test without UI
  - UI test with Simulator
- [x] Flutter iOS를 이번 로드맵에서 어디까지 다룰지 확정.
  - 이번 본선 로드맵은 Kotlin/Flutter/iPhone engine 경계 분리와 Android-only Flutter 기본 동작
    보존까지만 포함한다.
  - Flutter iOS 실제 빌드(`flutter-ios`, `flutter build ios`, `flutter build ipa`)는 Phase 7
    후속 확장으로 유지한다.
- [x] “서버 로컬 빌드 불가” 상태의 UI/에러 메시지 정책 확정.
- [x] Simulator 버튼 표시 조건 확정: iPhone 프로젝트에서만 카드 노출, MacBook 로컬 설치 또는
  원격 Mac agent 연결 확인 전에는 조작 버튼 숨김.

### 3.2 결정해야 할 정책

| 결정 | 기본안 | 대안/보류 |
|---|---|---|
| iPhone MVP project type | `iphone` 단일 | `ios-universal`은 후속 |
| Simulator 대상 | iPhone/iPad only | watch/tv/vision/mac 숨김 |
| Mac 연결 1차 | SSH bridge | 전용 agent는 Phase 1 안정화 후 |
| workspace sync | 서버 → Mac 단방향 rsync | bidirectional sync 금지 |
| signing 1차 | manual signing 자산 등록 | automatic signing은 후속 |
| release 업로드 | ASC API key 기반 | Apple ID interactive login 금지 |
| 플랫폼 분기 방식 | `PlatformEngineRegistry` | 전역 `when(projectType)` 금지 |
| 기존 Kotlin/Flutter 처리 | adapter로 감싸기 | 즉시 대규모 이동은 보류 |
| AI 지침 분리 | 전역 공통 + 지역 플랫폼별 | 전역에 플랫폼 세부 규칙 누적 금지 |

### 3.3 산출물

- `docs/ios/Architecture.md`
- `docs/ios/Mac-Agent-Setup.md`
- `docs/ios/Signing-Security.md`
- `docs/platform/Platform-Engine-Architecture.md`
- `docs/platform/Global-vs-Project-Claude-Memory.md`
- iPhone 기능 on/off preflight spec

완료 조건:

- 로컬 서버만 있을 때 가능한 기능과 macOS agent 필요 기능이 명확히 분리된다.
- iPhone 지원 MVP 범위가 Swift/SwiftUI iPhone 앱으로 고정된다.
- phase별 blocked/failed 메시지 분류표가 작성된다.
- Kotlin/Flutter/iPhone 엔진 경계가 문서화된다.

## 3.5 Phase 0.5 — Platform Engine 기반 정리

목표: iPhone 기능 구현 전에 Kotlin/Flutter/iPhone이 독립 엔진으로 들어갈 수 있는 슬롯을 만든다.

### 3.5.1 구현 티켓

- [x] `ProjectPlatformEngine` core 인터페이스 추가.
- [x] `PlatformEngineRegistry` 추가.
- [x] `PlatformToolingProfile` 추가:
  - platform별 기본 MCP/Skill/Agent
  - 조건부 MCP/Skill/Agent
  - opt-in MCP/Skill/Agent
  - forbidden tool list
- [x] `KotlinPlatformEngine` 추가:
  - 기존 Gradle toolchain adapter 호출
  - Kotlin 지역 `CLAUDE.md` template/memory 호출
  - Kotlin 전용 `toolingProfile()` 제공
  - 기존 icon/version/symbol 구현 adapter 호출
- [x] `KotlinPlatformEngine` 전용 설정/기능 경계 확정:
  - Gradle task/module
  - Kotlin/Java symbol
  - Android keystore/Play upload 경로
  - Compose/Kotlin quick prompt
- [x] `FlutterPlatformEngine` 추가:
  - 기존 Flutter toolchain adapter 호출
  - Flutter 지역 `CLAUDE.md` template/memory 호출
  - Flutter 전용 `toolingProfile()` 제공
  - pubspec version/icon adapter 호출
- [x] `FlutterPlatformEngine` 전용 설정/기능 경계 확정:
  - Flutter SDK/pub cache
  - `pubspec.yaml` dependency/version
  - Flutter target platform policy
  - Dart/Flutter analyze/test/build
  - Flutter/Dart quick prompt
- [x] `BuildService`의 `toolchainFor(projectType)`를 registry 기반으로 변경.
- [x] `ProjectService`의 타입 감지/CLAUDE.md 생성/icon/version 조회를 engine 경유로 변경.
- [x] 전역 `container-global-claude.*.md`를 모바일 공통 지침 중심으로 축소.
- [x] 지역 `CLAUDE.md` 생성은 `engine.renderProjectMemory()`만 사용하도록 변경.
- [x] 플랫폼별 지역 `CLAUDE.md` snapshot test 추가:
  - Kotlin: Gradle/Kotlin/Compose 규칙만 포함
  - Flutter: Dart/Flutter/pubspec 규칙만 포함
  - iPhone: SwiftUI/Xcode/Simulator/signing 규칙만 포함
- [x] 전역 `container-global-claude.*.md` regression test 추가:
  - Kotlin/Compose heading 없음
  - Flutter/Dart heading 없음
  - SwiftUI/Xcode/signing heading 없음
  - Android emulator/Play/keystore 세부 절차 없음
- [x] 프로젝트 UI는 engine capability로 버튼/배지를 렌더.
- [x] MCP catalog/env setup/agent registry는 `engine.toolingProfile()`을 SSOT로 사용.
- [x] 기존 Kotlin/Flutter 회귀 테스트 추가:
  - Kotlin 생성/clone/build
  - Flutter 생성/clone/build
  - icon/version 표시
  - project archive/restore projectType 보존

### 3.5.2 캡슐화 완료 기준

- 공통 서비스에 `ProjectTypes.KOTLIN`/`ProjectTypes.FLUTTER` 직접 비교가 새로 늘지 않는다.
- Kotlin과 Flutter가 별도 engine class, 별도 template/memory renderer, 별도 inspector를 가진다.
- Kotlin/Flutter/iPhone이 서로 다른 `PlatformToolingProfile`을 가진다.
- MCP/Skill/Agent 자동 추천 UI가 projectType별 profile만 사용한다.
- 전역 CLAUDE.md에 Kotlin/Flutter/iPhone 세부 지침이 남지 않는다.
- 프로젝트 생성 시 선택한 engine의 지역 CLAUDE.md만 생성된다.
- clone 프로젝트의 기존 지역 CLAUDE.md는 자동 overwrite되지 않는다.
- 프로젝트 타입 변경 시 새 지역 CLAUDE.md를 바로 쓰지 않고 preview/diff를 먼저 제공한다.
- Kotlin engine은 Flutter 파일(`pubspec.yaml`, `android/app`)을 직접 해석하지 않는다.
- Flutter engine은 Kotlin/Gradle module 정책을 직접 재사용하지 않는다.
- iPhone 추가 전에도 Kotlin/Flutter 동작이 동일하다.
- 각 엔진의 설정/진단/템플릿/빌드 책임이 한 패키지 아래에서 추적된다.
- 신규 플랫폼 추가 시 registry 등록과 engine 구현만으로 최소 기능이 붙는다.

## 4. Phase 1 — macOS Agent SSH Bridge

목표: 가장 단순한 방식으로 원격 Mac에서 Xcode 빌드를 실행한다.

### 4.1 구현 티켓

- [x] 설정 추가:
  - `ios.agent.enabled`
  - `ios.agent.host`
  - `ios.agent.port`
  - `ios.agent.user`
  - `ios.agent.workspaceRoot`
  - `ios.agent.xcodePath`
- [x] 실행 backend 1차 추가:
  - `IosAgentCommandRunner`
  - local / SSH mode command builder
  - Xcode preflight command allowlist
  - timeout
- [x] 실행 backend 보강 1차:
  - stdout/stderr 병렬 drain
  - large `xcodebuild` output pipe backpressure deadlock 방지
  - timeout 시 process destroy + partial output 반환
- [x] 실행 backend 보강 후속:
  - job 단위 cancel
- [x] build log hub 연결:
  - iOS build job 도 기존 `TaskLogger` + `LogHub` + `BuildRepository` 흐름 사용
- [x] SSH 접속 진단 1차:
  - `xcodebuild -version`
  - `xcrun simctl list devicetypes -j`
  - `security find-identity -v -p codesigning`
  - SSH exit 255 → `agent_unreachable`
- [x] 서버 workspace와 Mac workspace 동기화 정책 1차:
  - 1차: `rsync --delete` 기반 push
  - 제외: `.git`, build output, derived data, secrets
  - dry-run plan 우선 생성
- [x] 빌드 작업 모델 추가:
  - `IOS_BUILD_DEBUG`
  - `IOS_TEST`
  - `IOS_ARCHIVE`
  - `IOS_EXPORT_IPA`
- [x] 빌드 로그를 기존 build log hub에 연결.

### 4.2 Preflight 결과 필드

| 필드 | 예 |
|---|---|
| `mode` | `mac_local`, `mac_ssh`, `linux` |
| `xcodeVersion` | `Xcode 18.x / Build version 18A...` |
| `xcodeSelectPath` | `/Applications/Xcode.app/Contents/Developer` |
| `xcodebuildPath` | `/usr/bin/xcodebuild` |
| `iphoneOsSdkVersion` | `26.0` |
| `iphoneSimulatorSdkVersion` | `26.0` |
| `simctlAvailable` | true/false |
| `iphoneDeviceTypes` | iPhone 모델 목록 |
| `ipadDeviceTypes` | iPad 모델 목록 |
| `codesigningIdentities` | fingerprint + label |
| `blockedReason` | `mac_required`, `agent_unreachable`, `xcode_missing`, `simctl_missing` |

### 4.3 테스트

- SSH host unreachable이면 job이 `blocked(agent_unreachable)`로 종료.
- Linux Docker 단독이면 Simulator UI가 숨김.
- MacBook 로컬에서 `xcrun simctl list -j` 파싱 테스트.
- stdout/stderr가 큰 `xcodebuild` 로그에서도 deadlock 없이 drain.

완료 조건:

- 웹 UI에서 iPhone 프로젝트의 “환경 진단”을 눌러 원격 Mac 상태를 확인할 수 있다.
- 서버에서 원격 Mac으로 소스 동기화 후 `xcodebuild` debug build를 실행할 수 있다.
- agent 미연결 상태가 빌드 실패가 아니라 blocked 상태로 표시된다.

## 5. Phase 2 — iPhone 프로젝트 생성과 타입 감지

목표: Swift/SwiftUI iPhone 프로젝트를 Vibe Coder에서 1등 프로젝트 타입으로 다룬다.

### 5.1 구현 티켓

- [x] `ProjectTypes.IPHONE` 추가.
- [x] `IPhonePlatformEngine` 추가:
  - detect
  - scaffold
  - project memory render
  - capabilities
  - quick prompts
- [x] clone 타입 감지:
  - `*.xcodeproj`
  - `*.xcworkspace`
  - `Package.swift`
  - `project.pbxproj`
  - `Info.plist`
- [x] 신규 프로젝트 폼에 `iPhone (SwiftUI)` 추가.
- [x] SwiftUI starter template 1차 추가:
  - iPhone only target
  - SwiftUI App lifecycle
  - 기본 bundle id
  - 최소 iOS deployment target
  - XCTest scaffold
- [x] `CLAUDE.md` 템플릿 추가:
  - Swift/SwiftUI 규칙
  - Xcode project 수정 주의사항
  - signing 파일 직접 생성/로그 노출 금지
  - `xcodebuild` 검증 명령
  - Simulator 조작은 Mac preflight 통과 시에만 수행
  - App Store Connect/Release Pack MCP는 명시적 사용자 요청 시에만 사용
- [x] iPhone 지역 `CLAUDE.md`는 `IPhonePlatformEngine.renderProjectMemory()`에서만 생성.
- [x] 프로젝트 목록/상세/개요의 타입 배지 정리.
- [x] iPhone 프로젝트 기본 quick prompt 추가:
  - “SwiftUI 화면 구현”
  - “Xcode build 실패 수정”
  - “Simulator screenshot 기반 UI 점검”
  - “Signing 오류 분석”

### 5.2 SwiftUI starter 최소 구조

```text
<ProjectName>.xcodeproj/
<ProjectName>/
  <ProjectName>App.swift
  ContentView.swift
  Assets.xcassets/
  Preview Content/
<ProjectName>Tests/
<ProjectName>UITests/
CLAUDE.md
```

초기 template은 Xcode project 파일 생성 방식이 핵심 리스크다. 직접 `project.pbxproj`를 문자열로
조립하기보다 다음 순서로 검토한다.

1. Mac agent가 있으면 Xcode template 또는 Swift Package 기반 생성 가능성 검토.
2. 서버 단독 생성은 검증된 최소 `.xcodeproj` fixture를 사용하고 snapshot test로 고정.
3. 장기적으로는 XcodeGen/Tuist 같은 선언형 project generator 도입을 별도 검토.

### 5.3 타입 감지 우선순위

| 조건 | 판정 |
|---|---|
| `.xcodeproj/project.pbxproj` + Swift sources | `iphone` 후보 |
| `.xcworkspace` + `ios/Runner.xcworkspace` + `pubspec.yaml` | `flutter` 후보 |
| `Package.swift`만 있음 | Swift package, iPhone 여부 추가 확인 |
| `project.pbxproj`에 `TARGETED_DEVICE_FAMILY = 1` | iPhone 우선 |
| `TARGETED_DEVICE_FAMILY = 1,2` | `iphone`으로 등록하되 iPad Simulator 허용 |

완료 조건:

- 새 iPhone 프로젝트를 생성하면 SwiftUI 앱 골격과 iPhone 전용 `CLAUDE.md`가 만들어진다.
- clone한 Xcode 프로젝트는 `iphone` 타입으로 감지된다.
- Kotlin/Flutter 기존 프로젝트 생성/clone 동작이 변하지 않는다.
- iPhone 관련 분기는 `IPhonePlatformEngine` 내부에만 존재한다.
- 전역 CLAUDE.md를 수정하지 않아도 iPhone 프로젝트 지역 지침이 완결된다.

## 6. Phase 3 — Xcode Build Toolchain

목표: 기존 Gradle/Flutter 빌드 추상화 옆에 Xcode toolchain을 추가한다.

### 6.1 구현 티켓

- [x] `BuildToolchain` 1차 구현 추가:
  - `IosBuildToolchain`
  - `IosXcodeCommandBuilder`
  - 기존 build queue/log hub 연결
- [x] `BuildToolchain` 후속 구현 1차:
  - `XcodeBuildRequest`
  - `XcodeArtifactFinder`
- [x] `IPhonePlatformEngine` 이 Xcode toolchain 선택을 소유하도록 연결.
- [x] 공통 build route는 engine registry만 통해 build job을 생성.
- [x] scheme/configuration 자동 감지:
  - [x] `.xcodeproj` shared `.xcscheme` 단일 scheme 우선 감지
  - [x] `.xcworkspace` + CocoaPods/SPM 구조에서 project shared scheme 감지
  - [x] 다중 scheme 이고 추론 불가하면 명확한 오류 제공
  - [x] scheme 미지정/다중 scheme 선택 UI 제공
- [x] Debug build 1차:
  - simulator destination
- [x] Debug build 후속 1차:
  - derived data path 격리
  - log parser
- [x] Archive/export 1차:
  - `archivePath`
  - `exportOptions.plist`
  - `.ipa` 수집
- [x] Archive/export 후속:
  - [x] `.xcarchive`, dSYM 수집
  - [x] signing method/team/export option UI
  - [x] remote Mac 산출물 보존/정리 정책 고도화
- [x] 실패 로그 요약 1차:
  - compile error
  - signing error
  - missing scheme
  - simulator unavailable
- [x] 실패 로그 요약 후속:
  - [x] failureKind 를 build row/API 응답에 구조화
  - [x] `.xcresult` 기반 상세 summary 추출
- [x] `.xcresult` 보존:
  - [x] test/build result bundle artifact 등록
  - [x] `.xcarchive` bundle zip artifact 등록
  - [x] 실패 summary 추출
  - screenshot attachment 추출은 Phase 5와 연결

### 6.2 표준 명령 템플릿

구현 시점의 `xcodebuild -help`로 옵션을 재확인한다.

```bash
xcodebuild \
  -project <App>.xcodeproj \
  -scheme <Scheme> \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=<Device>,OS=<Runtime>' \
  -derivedDataPath <DerivedData> \
  -resultBundlePath <Result.xcresult> \
  build
```

```bash
xcodebuild \
  -project <App>.xcodeproj \
  -scheme <Scheme> \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath <Archive.xcarchive> \
  archive
```

```bash
xcodebuild \
  -exportArchive \
  -archivePath <Archive.xcarchive> \
  -exportPath <ExportDir> \
  -exportOptionsPlist <exportOptions.plist>
```

### 6.3 로그 파서 분류

| 패턴 | failureKind | 사용자 메시지 |
|---|---|---|
| `xcodebuild: error: The project named` | `project_missing` | Xcode project 경로 확인 필요 |
| `The workspace named` | `workspace_missing` | workspace 경로 확인 필요 |
| `Scheme .* is not currently configured` | `scheme_missing` | scheme 선택 필요 |
| `No profiles for .* were found` | `profile_missing` | provisioning profile 등록 필요 |
| `Provisioning profile .* has expired` | `profile_expired` | provisioning profile 갱신/재설치 필요 |
| `doesn't support the .* identifier` | `profile_bundle_mismatch` | bundle id와 profile 매칭 확인 |
| `does not match team` | `profile_team_mismatch` | Team ID와 profile team 확인 |
| `Signing certificate .* not found` | `certificate_missing` | Mac keychain certificate 확인 |
| `User interaction is not allowed` | `keychain_locked` | Mac keychain unlock 필요 |
| `Command CompileSwift failed` | `swift_compile_failed` | Swift compile error 요약 |
| `Testing failed` | `test_failed` | `.xcresult` 확인 |

완료 조건:

- `iphone` 프로젝트에서 Debug build와 Release archive/export가 기존 빌드 화면으로 실행된다.
- `.ipa`와 dSYM을 웹에서 다운로드할 수 있다.
- `xcodebuild` exit 65/70 같은 포괄 오류가 사용자 조치 가능한 failureKind로 정규화된다.
- `BuildService`에 iPhone 전용 hardcoded 분기가 남지 않는다.

## 7. Phase 4 — Signing과 Apple 자산 관리

목표: iPhone 빌드에서 가장 사고가 많은 signing을 프로젝트와 분리된 관리 기능으로 제공한다.

### 7.1 구현 티켓

- [x] 설정/Signing 화면 추가:
  - iPhone 프로젝트 우측 rail 에 Xcode build/signing 설정 카드와 Signing 상태 카드를 둔다.
  - Signing 상태 카드는 `GET /api/projects/{id}/ios/signing-status` 하나를 SSOT 로 사용해
    certificate identity, profile uuid/name/expiration, bundle/team match, blocked reason 을 표시한다.
  - team id
  - bundle id
  - signing style
  - certificate fingerprint
  - provisioning profile uuid/name/expiration
- [x] App Store Connect API key 저장:
  - key id
  - issuer id
  - private key file
- [x] secret 저장 위치와 권한 정책 확정.
- [x] 원격 Mac keychain 연동:
  - [x] 전용 keychain 생성
  - [x] certificate import
  - [x] keychain unlock
  - [x] codesign/xcodebuild 접근용 key partition list 설정
- [x] profile 설치/검증:
  - profile bundle id 매칭
  - team id 매칭
  - 만료일 경고
- [x] signing 오류를 사용자 조치 가능한 메시지로 정규화.

### 7.2 Secret 저장 정책

| 자산 | 저장 위치 | 로그/아티팩트 포함 | 비고 |
|---|---|---:|---|
| `.p8` ASC key | server secret store | 금지 | upload job에서만 참조 |
| `.p12` certificate | Mac keychain 또는 encrypted secret | 금지 | import 후 원본 삭제 권장 |
| keychain password | server secret store | 금지 | command env에도 직접 노출 최소화 |
| `.mobileprovision` | signing asset store | 원문 금지 | uuid/name/team/expiry만 UI 표시 |
| `exportOptions.plist` | generated temp file | 민감값 마스킹 | job 종료 후 정리 |

### 7.3 Preflight 게이트

- Debug simulator build: signing 없이 가능해야 한다.
- Release archive: team id + signing identity 필요.
- Export IPA: provisioning profile 또는 automatic signing 설정 필요.
- TestFlight upload: `.ipa` + ASC key + bundle id 매칭 필요.

완료 조건:

- release archive/export가 수동 Xcode UI 없이 재현 가능하다.
- 만료/불일치 signing 상태가 빌드 전에 감지된다.
- signing 자산 누락은 build failure가 아니라 blocked 상태로 표시된다.

## 8. Phase 5 — Simulator와 실기기 실행

목표: AI가 만든 iPhone 앱을 브라우저에서 실행/검증할 수 있게 한다.

### 8.1 구현 티켓

- [x] Simulator inventory 1차:
  - 설치된 runtime
  - 사용 가능한 iPhone/iPad device type
  - booted 상태
- [x] 실행 가능 환경 제한 1차:
  - MacBook 로컬 설치: 로컬 `xcrun simctl` 직접 사용
  - Linux + 원격 Mac agent: agent를 통해 `xcrun simctl` 사용
  - Linux 단독: Simulator 기능 비활성
- [x] Simulator 작업 1차:
  - boot
  - shutdown
- [x] Simulator 작업 2차:
  - install app
  - launch app
  - screenshot
- [x] Simulator 로그 조회 1차:
  - `simctl spawn <UDID> log show --last 5m`
  - 앱 bundle id predicate
  - 최근 200줄 bounded response
- [x] Simulator 작업 후속:
  - realtime log stream
- [x] 웹 UI 1차:
  - iPhone/iPad Simulator device 카드
  - screenshot preview
  - 최근 앱 로그
- [x] 웹 UI 후속:
  - realtime log stream 연결
  - recent app logs 자동 갱신/필터는 realtime stream으로 대체
- [x] UI 테스트:
  - `xcodebuild test`
  - test result bundle 다운로드
  - 실패 스크린샷 수집
- [x] 실기기 연결은 후순위로 둔다. 초기에는 iPhone/iPad Simulator만 안정화한다.

### 8.2 Simulator 명령 범위

구현 시점의 `xcrun simctl help`로 옵션을 재확인한다.

```bash
xcrun simctl list -j devices available
xcrun simctl boot <UDID>
xcrun simctl install <UDID> <App.app>
xcrun simctl launch <UDID> <bundle.id>
xcrun simctl io <UDID> screenshot <file.png>
xcrun simctl spawn <UDID> log stream --style compact --predicate <predicate>
```

### 8.3 UI/리소스 제한

- 동시 boot Simulator는 기본 1개, 최대 2개.
- idle 20분 후 shutdown 후보.
- Mac agent 메모리/CPU pressure가 높으면 새 Simulator boot를 blocked 처리.
- iPad Simulator는 레이아웃 확인용으로만 노출하고 iPad 전용 배포 설정은 숨긴다.

완료 조건:

- 빌드된 앱을 MacBook 로컬 또는 원격 Mac Simulator에 설치/실행하고 screenshot을 웹에서 확인할 수 있다.
- 테스트 결과가 빌드 화면과 연결된다.
- Simulator가 없는 환경에서는 관련 UI가 명확히 비활성 상태로 보인다.

## 9. Phase 6 — TestFlight 배포

목표: 생성/수정한 iPhone 앱을 TestFlight까지 업로드한다.

### 9.1 구현 티켓

- [x] App Store Connect 연결 진단.
  - `GET /api/ios/app-store-connect/diagnostics` 가 저장된 `.p8` 로 ES256 JWT 를 생성하고
    App Store Connect `/v1/apps` 를 read-only 조회한다.
- [x] bundle id/app id 조회.
  - 선택 `bundleId` query 로 `/v1/apps?filter[bundleId]=...` 를 조회해 app id/name 매칭을 반환한다.
- [x] build number 자동 증가 정책.
  - Xcode `CURRENT_PROJECT_VERSION`, `Info.plist` `CFBundleVersion`, Flutter `pubspec.yaml`
    build metadata 중 가장 큰 정수 + 1 을 upload prompt 에 제안한다.
- [x] archive/export 후 upload job 추가.
  - iOS build detail 의 TestFlight 카드와 JSON API가 `testflight_upload_jobs` row 를 생성한다.
- [x] upload 상태 추적:
  - [x] queued
  - [x] uploading
  - [x] processing
  - [x] accepted
  - [x] failed
  - ASC build polling 이 App Store Connect `processingState` 를 조회해
    `PROCESSING` → `processing`, `VALID` → `accepted`, `FAILED`/`INVALID` → `failed` 로 전이한다.
- [x] 실패 메시지 정규화:
  - [x] invalid provisioning → `invalid_provisioning`
  - [x] duplicate build number → `duplicate_build_number`
  - [x] missing compliance → `missing_compliance`
  - [x] invalid icon/screenshot metadata → `invalid_icon_screenshot_metadata`
  - [x] ASC auth / invalid IPA 보조 분류 → `authentication_failed`, `invalid_ipa`
- [x] TestFlight 업로드 이력 카드 추가.

### 9.2 Upload 상태 모델

| 상태 | 의미 |
|---|---|
| `ready` | `.ipa`와 ASC key 검증 완료 |
| `uploading` | 업로드 진행 중 |
| `uploaded` | ASC가 파일을 수락 |
| `processing` | App Store Connect 처리 대기 |
| `accepted` | TestFlight build로 사용 가능 |
| `failed` | 업로드/처리 실패 |
| `blocked` | ASC key/bundle/build number 등 선행조건 누락 |

### 9.3 Release Pack 안전장치

- 업로드 버튼은 기본 숨김. Release Pack 설치/설정 완료 후 표시.
- upload 전 확인 모달에 bundle id, version, build number, team id, ASC issuer를 표시.
- 같은 version/buildNumber 재업로드는 사전 차단한다.
- 업로드 job은 prompt automation이 자동으로 실행하지 못하게 별도 user action만 허용한다.

완료 조건:

- 웹 UI에서 iPhone 앱을 archive/export/upload까지 한 흐름으로 실행할 수 있다.
- 업로드 결과와 실패 원인이 프로젝트 이력에 남는다.

## 10. Phase 7 — Flutter iOS 확장

목표: 기존 Flutter 프로젝트가 iPhone 빌드도 선택할 수 있게 한다.

전제:

- macOS agent가 안정화되어 있어야 한다.
- 현재 Flutter 설치/템플릿은 Android 전용 정책이므로, 플랫폼 정책을 프로젝트별로 분리해야 한다.
- `FlutterPlatformEngine`은 Android package와 iOS package를 별도 capability로 분리해야 한다.

작업:

- [x] Flutter 프로젝트의 target platform 설정 추가:
  - Android only
  - iPhone only
  - Android + iPhone
- [x] `FlutterPlatformEngine` 내부 build target 분리:
  - `flutter-android`
  - `flutter-ios`
  - 공통 Dart 분석
- [x] `flutter precache --ios`는 macOS agent에서만 수행.
- [x] `flutter create --platforms=ios` 또는 기존 프로젝트의 `ios/` 생성 정책 확정.
- [x] `flutter build ios --no-codesign` debug 검증.
- [x] `flutter build ipa` release/export 연동.
- [x] Flutter iOS signing 가이드 추가.

완료 조건:

- Flutter 프로젝트에서 iPhone `.ipa`를 만들 수 있다.
- Android 전용 Flutter 프로젝트는 기존처럼 불필요한 iOS artifact를 받지 않는다.

## 11. Phase 8 — 코드 분석과 AI 작업 품질

목표: Swift/iPhone 프로젝트에서 AI 수정 품질을 Kotlin/Flutter 수준으로 끌어올린다.

- [x] Swift 파일 하이라이트/미리보기.
  - `.swift` MIME 추정 `text/x-swift`
  - CodeMirror 5 Swift mode vendoring 및 file view 연결
- [x] symbol scan:
  - `struct`
  - `class`
  - `protocol`
  - `enum`
  - `func`
  - SwiftUI `View`
  - 기존 `/symbols` regex scanner 에 `.swift` 확장자와 Swift declaration pattern 추가
- [x] SwiftFormat/SwiftLint 선택 설치.
  - admin 전용 `POST /api/ios/swift-tools/install` 이 Mac local 또는 SSH macOS agent 에서
    Homebrew 기반으로 `swiftlint`, `swiftformat` 을 선택 설치한다.
- [x] Xcode build error parser를 prompt feedback에 연결.
  - iOS build 실패 시 분류된 `failureKind`/요약/매칭 로그를 build row `errorMessage` 로 보존
  - iOS 실패 상세 화면에 “콘솔에 iOS 실패 수정 요청” 버튼 추가
  - 버튼은 현재 TUI console provider 에 buildId, variant, 분류 요약, build log 경로를 전달
- [x] iPhone 전용 quick prompt:
  - SwiftUI 레이아웃 점검
  - signing 오류 분석
  - Simulator 실패 분석
  - TestFlight 업로드 오류 분석
- [x] `mobile-mcp`/Simulator 조작과 AI 콘솔 연결 검토.
  - 현재 세션의 `mobile_list_available_devices` 결과 사용 가능한 장치 없음.
  - iPhone `PlatformToolingProfile.conditionalMcp` 에 `mobile-mcp` 를 유지하되, 자동 조작은
    Mac preflight + 실제 Simulator device 확인 후에만 허용한다.

완료 조건:

- Swift compile/test 실패를 AI가 바로 이해할 수 있는 요약으로 제공한다.
- 프로젝트 화면에서 Swift/iPhone 작업에 필요한 파일, 빌드, 실행, 배포 루프가 끊기지 않는다.

## 12. 플랫폼별 Skill / Agent / MCP 분류

목표: Kotlin, Flutter, iPhone 프로젝트가 각자 다른 MCP, Skill, Agent 구성을 가지도록
engine 단위로 도구 캡슐을 분리한다. iPhone 개발은 Xcode 빌드만 붙이는 수준으로 끝내지 않고,
AI 작업 품질, Simulator 검증, signing/release 운영까지 역할별 도구 묶음으로 정리한다.

### 12.1 기본 설치 원칙

- API key, Apple private key, 외부 쓰기 권한이 필요한 도구는 기본 자동 설치하지 않는다.
- 전역 기본 설치는 플랫폼 중립 도구만 포함한다.
- 플랫폼별 MCP/Skill/Agent 추천 목록은 `ProjectPlatformEngine.toolingProfile()`이 결정한다.
- Kotlin, Flutter, iPhone은 서로 다른 tooling profile을 가지며, 공통 “모바일 기본 bundle”에
  플랫폼 세부 도구를 섞지 않는다.
- MacBook 로컬 설치 또는 원격 Mac agent preflight가 통과한 경우에만 iPhone 실행/검증 도구를
  기본 선택 대상으로 올린다.
- `xcrun simctl`이 없는 Linux Docker 단독 환경에서는 iPhone Simulator 도구를 설치해도
  실행할 수 없으므로, UI에서 “Mac 필요”로 분류한다.
- 기본 설치는 zero-config, read-heavy, 로컬 검증 중심 도구만 허용한다.
- 배포/서명/스토어 업로드 도구는 “iPhone Release Pack”으로 별도 opt-in 설치한다.

### 12.2 Platform Tooling Profile

각 engine은 아래 구조를 반환한다.

```kotlin
data class PlatformToolingProfile(
    val projectType: String,
    val defaultMcp: List<ToolRef>,
    val conditionalMcp: List<ConditionalToolRef>,
    val optInMcp: List<ToolRef>,
    val defaultSkills: List<ToolRef>,
    val conditionalSkills: List<ConditionalToolRef>,
    val optInSkills: List<ToolRef>,
    val defaultAgents: List<ToolRef>,
    val conditionalAgents: List<ConditionalToolRef>,
    val optInAgents: List<ToolRef>,
    val forbiddenTools: List<ToolRef>,
)
```

플랫폼별 기본 profile:

| projectType | 기본 MCP | 조건부 MCP | opt-in MCP |
|---|---|---|---|
| `kotlin` | `context7`, `memory`, `sequentialthinking`, `time` | `github`/`gitea` | Play/store publish 도구 |
| `flutter` | `context7`, `memory`, `sequentialthinking`, `time` | `github`/`gitea`, `firebase` | Flutter iOS bridge, store publish 도구 |
| `iphone` | `context7`, `memory`, `sequentialthinking`, `time`, `playwright` | `mobile-mcp` | `app-store-connect`, `fastlane`, `app-publish` |

| projectType | 기본 Skill | 조건부 Skill | opt-in Skill |
|---|---|---|---|
| `kotlin` | `kotlin-expert`, `jetpack-compose-expert`, `compose-architecture-expert`, `material3-expert` | `qa-scenario-writer` | release/publish skill |
| `flutter` | `flutter-dart-expert`, `flutter-ui-architect`, `flutter-build-debugger` | `firebase-flutter-integrator` | `flutter-ios-bridge`, release/publish skill |
| `iphone` | `swiftui-iphone-expert`, `xcode-build-debugger` | `ios-simulator-qa` | `apple-signing-release`, `app-store-connect-release` |

| projectType | 기본 Agent | 조건부 Agent | opt-in Agent |
|---|---|---|---|
| `kotlin` | `kotlin-architect`, `compose-implementer`, `gradle-build-fixer` | `kotlin-qa-agent` | `play-release-agent` |
| `flutter` | `flutter-architect`, `flutter-implementer`, `flutter-build-fixer` | `firebase-flutter-agent` | `flutter-ios-migration-agent`, `store-release-agent` |
| `iphone` | `iphone-architect`, `swiftui-implementer`, `xcode-build-fixer` | `simulator-qa-agent`, `apple-signing-agent` | `testflight-release-agent` |

금지 profile:

| projectType | 기본/자동 노출 금지 |
|---|---|
| `kotlin` | `mobile-mcp`, `xcode-build-debugger`, `iphone-*`, `swiftui-*`, `flutter-*` |
| `flutter` | `xcode-build-debugger`, `iphone-*`, `swiftui-*`, Kotlin Gradle module 전용 prompt |
| `iphone` | Android keystore/Play prompt, Kotlin Gradle module prompt, Flutter pubspec prompt |

조건부 노출 조건:

- `mobile-mcp`: MacBook 로컬 또는 원격 Mac agent에서 `xcrun simctl` preflight 통과.
- `app-store-connect`/`fastlane`/`app-publish`: 사용자가 release pack 설치를 명시적으로 요청하고
  secret 등록 UI를 통과.
- `firebase`: 프로젝트 파일에서 Firebase dependency가 감지되거나 사용자가 선택.
- `github`/`gitea`: repo remote가 감지되거나 사용자가 선택.

### 12.3 Skills

| Skill | 기본 설치 | 용도 | 비고 |
|---|---:|---|---|
| `swiftui-iphone-expert` | 예 | SwiftUI 화면 구조, state, navigation, accessibility, iPhone form factor 구현 | iPhone MVP 기본 |
| `xcode-build-debugger` | 예 | `xcodebuild` 로그, scheme/configuration, Swift compile error 분석 | Mac agent 없어도 로그 분석 가능 |
| `ios-simulator-qa` | 조건부 | iPhone/iPad Simulator 실행, screenshot, UI test 실패 분석 | MacBook/원격 Mac preflight 통과 시 |
| `apple-signing-release` | 아니오 | certificate/profile/exportOptions/TestFlight signing 문제 분석 | secret/계정 작업 동반 |
| `app-store-connect-release` | 아니오 | ASC API, TestFlight 업로드, build number, 심사 상태 | ASC key 필요 |
| `flutter-ios-bridge` | 아니오 | 기존 Flutter 프로젝트의 iOS target 전환/빌드 | Phase 7 이후 |
| `kmp-ios-reviewer` | 아니오 | Kotlin Multiplatform iOS target 검토 | 후속 검토 범위 |

1차 구현 시 기본 생성할 skill:

- `swiftui-iphone-expert`
- `xcode-build-debugger`
- `ios-simulator-qa`는 Mac preflight 통과 시 추천/기본 선택

### 12.4 Agents

Agents는 긴 작업을 역할별로 분리하기 위한 Claude Code custom agent로 둔다. 기본 agent는
프로젝트 생성 시 `~/.claude/agents/*.md` 또는 서버 agent registry를 통해 제공한다.

| Agent | 기본 설치 | 역할 | 호출 예 |
|---|---:|---|---|
| `iphone-architect` | 예 | SwiftUI 앱 구조, folder/module, state/navigation 설계 | “앱 구조 잡아줘” |
| `swiftui-implementer` | 예 | 화면/기능 구현, preview, accessibility 반영 | “설정 화면 추가” |
| `xcode-build-fixer` | 예 | build/test 실패 로그 분석 후 최소 수정 | “빌드 실패 수정” |
| `simulator-qa-agent` | 조건부 | Simulator 실행, screenshot 판독, UI test 재현 | “시뮬레이터에서 확인” |
| `apple-signing-agent` | 아니오 | signing/profile/exportOptions 문제 진단 | “배포 서명 오류 분석” |
| `testflight-release-agent` | 아니오 | archive/export/upload, ASC 상태 추적 | “TestFlight 업로드” |
| `flutter-ios-migration-agent` | 아니오 | Flutter Android-only 프로젝트를 iOS 포함 정책으로 전환 | Phase 7 이후 |

기본 agent bundle:

- `iphone-architect`
- `swiftui-implementer`
- `xcode-build-fixer`
- `simulator-qa-agent`는 Mac preflight 통과 시만 기본 선택

Release agent bundle:

- `apple-signing-agent`
- `testflight-release-agent`

### 12.5 MCP

#### MCP 후보

아래 MCP는 catalog 후보이며, 실제 기본/조건부/opt-in 노출은 `PlatformToolingProfile`이 최종
결정한다.

| MCP | 기본 분류 | 조건 | 용도 |
|---|---:|---|---|
| `context7` | 공통 기본 | 모든 환경 | Kotlin/Flutter/SwiftUI/Swift Package/Firebase 등 최신 문서 확인 |
| `memory` | 공통 기본 | 모든 환경 | 프로젝트별 결정/제약 장기 기억 |
| `sequentialthinking` | 공통 기본 | 모든 환경 | 복잡한 빌드/signing 문제 단계적 분석 |
| `time` | 공통 기본 | 모든 환경 | 로그 시각, 예약 작업, TestFlight 처리 시간 해석 |
| `playwright` | 플랫폼별 | 웹 UI 회귀 또는 iPhone release 검증 필요 시 | 웹 UI 회귀, App Store Connect 웹 화면 수동 확인 보조 |
| `mobile-mcp` | 조건부 | MacBook 로컬 또는 원격 Mac agent에서 `xcrun simctl` 통과 | iPhone/iPad Simulator 조작, screenshot, accessibility tree |

#### iPhone Release Pack

| MCP | 기본 설치 | 조건 | 용도 | 기본 제외 이유 |
|---|---:|---|---|---|
| `app-store-connect` | 아니오 | ASC API key 필요 | TestFlight, app metadata, build status | Apple private key 필요 |
| `fastlane` | 아니오 | Ruby/fastlane + Apple auth 필요 | match/gym/pilot 등 release 자동화 | 로컬 Ruby 환경과 계정 상태 의존 |
| `app-publish` | 아니오 | Apple/Google 자격증명 선택 | 통합 publish 작업 | 외부 쓰기 권한 범위가 넓음 |

#### 선택 설치

| MCP | 기본 설치 | 용도 |
|---|---:|---|
| `github` / `gitea` | 선택 | iPhone 프로젝트 issue/PR/release 관리 |
| `firecrawl` / `tavily` / `perplexity` | 선택 | Apple 문서 외부 자료 조사, 오류 검색 |
| `sentry` | 선택 | iPhone 앱 crash/error 운영 연동 |
| `firebase` | 선택 | Firebase Auth/Crashlytics/FCM 연동 프로젝트 |
| `notion` / `linear` / `jira` | 선택 | 작업 관리 연동 |

### 12.6 MCP 카탈로그 변경안

- `mobile-mcp`는 현재 zero-config라 기본 설치 대상이 될 수 있지만, iPhone 기능에서는
  `xcrun simctl` preflight 결과를 함께 보여준다.
- `app-store-connect`, `fastlane`, `app-publish`는 recommended는 가능하지만 defaultInstall은
  금지한다.
- iPhone 관련 MCP 카드에는 platform badge를 추가한다:
  - `Mac required`
  - `Secret required`
  - `External write`
  - `Simulator`
  - `Release`
- “iPhone 기본 도구 설치” 버튼은 다음만 선택한다:
  - `context7`
  - `memory`
  - `sequentialthinking`
  - `time`
  - `playwright`
  - `mobile-mcp`는 Mac preflight 통과 시만 포함
- “iPhone Release Pack 설치” 버튼은 별도 확인을 거쳐 다음을 선택한다:
  - `app-store-connect`
  - `fastlane`
  - `app-publish`

### 12.7 프로젝트 생성 시 기본 프롬프트/도구 노출

iPhone 프로젝트의 `CLAUDE.md`에는 다음 도구 사용 정책을 포함한다.

- SwiftUI/Swift/Xcode 관련 문법·API는 기억으로 단정하지 말고 `context7` 또는 공식 문서를
  확인한다.
- Simulator 조작은 `mobile-mcp`가 사용 가능하고 Mac preflight가 통과한 경우에만 수행한다.
- signing, profile, ASC private key는 출력/로그/커밋에 남기지 않는다.
- 배포 관련 MCP는 사용자가 명시적으로 요청한 경우에만 호출한다.
- UI 수정 후 가능한 경우 `xcodebuild test` 또는 Simulator screenshot으로 확인한다.

### 12.8 Skill/Agent 산출물

Skill과 Agent는 플랫폼 engine이 추천/기본 노출 목록을 결정한다. 전역 기본 설치는 공통 도구만
담고, 플랫폼별 작업 능력은 지역 프로젝트 memory와 engine capability에서 노출한다.

| 산출물 | 위치 예시 | 생성 주체 | 비고 |
|---|---|---|---|
| 공통 skill | `~/.claude/skills/mobile-common/SKILL.md`, `~/.codex/skills/mobile-common/SKILL.md` | server env setup | 공식 문서 확인, secret 금지, 모바일 UX 공통 |
| Kotlin skill bundle | `~/.claude/skills/kotlin-compose-*` | `KotlinPlatformEngine` 추천 | Kotlin 프로젝트에서만 기본 추천 |
| Flutter skill bundle | `~/.claude/skills/flutter-dart-*` | `FlutterPlatformEngine` 추천 | Flutter 프로젝트에서만 기본 추천 |
| iPhone skill bundle | `~/.claude/skills/swiftui-iphone-*` | `IPhonePlatformEngine` 추천 | iPhone 프로젝트에서만 기본 추천 |
| 공통 agents | `~/.claude/agents/mobile-reviewer.md` | server env setup | 플랫폼 중립 리뷰/QA |
| Kotlin agents | `~/.claude/agents/kotlin-*.md`, `~/.claude/agents/compose-*.md` | `KotlinPlatformEngine` 추천 | Kotlin 프로젝트에서만 기본 추천 |
| Flutter agents | `~/.claude/agents/flutter-*.md` | `FlutterPlatformEngine` 추천 | Flutter 프로젝트에서만 기본 추천 |
| iPhone agents | `~/.claude/agents/iphone-*.md` | `IPhonePlatformEngine` 추천 | Mac preflight 결과에 따라 Simulator agent 노출 |

기본 bundle 원칙:

| projectType | 기본 추천 | 조건부 추천 | opt-in |
|---|---|---|---|
| `kotlin` | Kotlin/Compose review, Gradle build fix | Play upload helper | store upload/release agent |
| `flutter` | Flutter/Dart review, Flutter build fix | Flutter iOS bridge | store upload/release agent |
| `iphone` | SwiftUI review, Xcode build debugger | Simulator QA agent, signing preflight agent | TestFlight/App Store Connect release agent |

검증:

- `ProjectPlatformEngine.toolingProfile()` snapshot test가 Kotlin/Flutter/iPhone 3종 모두 존재한다.
- Kotlin/Flutter/iPhone profile의 기본 MCP/Skill/Agent 목록이 서로 동일하지 않다.
- Kotlin 프로젝트에서는 iPhone release agent가 기본 노출되지 않는다.
- Flutter 프로젝트에서는 Kotlin Gradle module prompt가 기본 노출되지 않는다.
- iPhone 프로젝트에서는 Android keystore/Play prompt가 기본 노출되지 않는다.
- 같은 프로젝트에서도 preflight/secret 상태에 따라 조건부 도구만 추가되고 기본 bundle은 변하지 않는다.
- Release 관련 skill/agent/MCP는 사용자가 명시적으로 요청한 경우에만 설치 또는 실행된다.

## 13. 제외 범위

초기 iPhone MVP에서 제외한다.

- iPad 전용/Universal UI 세밀 지원
- iPad Simulator 실행은 허용하지만, iPad 전용 앱 생성/배포 타입은 제외
- watchOS/tvOS/visionOS/macOS 앱
- Xcode 프로젝트 구조의 모든 edge case 자동 수정
- 멀티 사용자 signing 자산 분리
- App Store metadata 전체 관리
- 실기기 farm
- Kotlin Multiplatform iOS target

## 14. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| macOS agent 부재 | 빌드/실행 불가 | UI에서 명확히 진단하고 생성/AI 작업은 허용 |
| MacBook 로컬 설치 감지 오류 | Simulator 버튼 오노출 | `xcode-select` + `xcrun simctl` preflight 둘 다 통과해야 노출 |
| Xcode 버전 차이 | 빌드 재현성 저하 | agent 진단에 Xcode/SDK/runtime snapshot 저장 |
| signing 복잡도 | release 실패 다발 | signing preflight와 오류 정규화 우선 구현 |
| workspace sync 오류 | Mac 빌드 대상 불일치 | rsync dry-run/log, 제외 규칙 테스트 |
| Apple 인증키 노출 | 보안 사고 | secret store/keychain 사용, 로그 마스킹 |
| Simulator 리소스 사용 | MacBook/Mac agent 성능 저하 | 동시 실행 제한, idle shutdown |
| Flutter iOS 범위 확장 | 플랫폼 정책 혼선 | Swift/iPhone MVP 이후 별도 phase로 격리 |
| 기본 설치 도구 과다 | 초기 설정/메모리 부담 증가 | zero-config 기본 설치와 Release Pack을 분리 |
| 배포 MCP 오작동 | 외부 상태 변경 사고 | App Store Connect/fastlane/app-publish는 opt-in + 확인 절차 |
| 전역/지역 지침 drift | 생성 프로젝트별 AI 행동 불일치 | 전역 template regression test + engine별 지역 CLAUDE.md snapshot test |
| Kotlin/Flutter engine bleed | Flutter 프로젝트에서 Gradle 규칙 실행 또는 반대 상황 | Kotlin/Flutter engine class, inspector, memory renderer를 분리하고 공통 서비스 직접 분기 금지 |
| clone 프로젝트 지침 overwrite | 기존 프로젝트 작업 규칙 손실 | 기존 `CLAUDE.md`는 보존하고 권장 지침 preview/diff만 제공 |

## 15. 검증 체크리스트

- 전역 `CLAUDE.md`/`AGENTS.md`에는 모바일 공통사항만 남는다.
- Kotlin 프로젝트 생성 시 Kotlin 전용 지역 `CLAUDE.md`만 생성된다.
- Flutter 프로젝트 생성 시 Flutter 전용 지역 `CLAUDE.md`만 생성된다.
- iPhone 프로젝트 생성 시 iPhone 전용 지역 `CLAUDE.md`만 생성된다.
- clone 프로젝트의 기존 `CLAUDE.md`는 자동 overwrite되지 않는다.
- 프로젝트 타입 변경 시 지역 지침 preview/diff가 먼저 표시된다.
- Kotlin/Flutter/iPhone engine이 각각 별도 class, template, inspector를 가진다.
- 공통 서비스에 플랫폼별 `when(projectType)` 분기가 새로 확산되지 않는다.
- iPhone 프로젝트 생성 후 SwiftUI 앱이 만들어진다.
- clone된 Xcode 프로젝트가 `iphone` 타입으로 감지된다.
- iPhone 기본 skill/agent bundle이 생성되거나 추천 목록에 표시된다.
- Mac preflight 통과 시 `mobile-mcp`와 `simulator-qa-agent`가 추천/기본 선택된다.
- Linux Docker 단독 환경에서는 Simulator/Mobile MCP 실행 기능이 제한 표시된다.
- macOS agent 진단이 Xcode, Simulator, signing 상태를 보여준다.
- MacBook 로컬 설치에서는 iPhone/iPad Simulator 카드가 표시된다.
- Linux Docker 단독 설치에서는 Simulator 실행 버튼이 표시되지 않는다.
- Debug simulator build가 성공/실패 로그를 웹 UI에 스트리밍한다.
- Simulator에 앱을 설치하고 screenshot을 회수한다.
- Release archive/export가 `.ipa`와 dSYM을 artifact로 등록한다.
- signing 만료/불일치가 빌드 전에 감지된다.
- TestFlight 업로드 성공/실패 상태가 프로젝트 이력에 남는다.
- macOS agent가 없어도 AI 콘솔/파일 편집/프로젝트 관리는 깨지지 않는다.

## 16. MVP 실행 순서

### 16.1 MVP-0: 문서/진단만

목표: 기능 버튼을 노출하기 전에 환경 판정이 정확한지 확인한다.

- `GET /api/ios/preflight`
- MacBook 로컬/원격 Mac/Linux 단독 판정
- Xcode/Simulator/signing snapshot 표시
- Simulator UI 노출 조건 검증

출시 기준:

- Linux Docker 단독에서 Xcode/Simulator 기능이 절대 실행되지 않는다.
- MacBook 로컬에서 `xcrun simctl list -j` 결과를 안정적으로 파싱한다.

### 16.2 MVP-1: iPhone 프로젝트 타입 + AI 작업

목표: 빌드가 없어도 iPhone 프로젝트를 만들고 AI가 수정할 수 있게 한다.

- `ProjectTypes.IPHONE`
- SwiftUI starter template
- iPhone `CLAUDE.md`
- 기본 skill/agent bundle
- 파일 브라우저 Swift highlighting/symbol scan 1차

출시 기준:

- 신규 SwiftUI 프로젝트가 생성된다.
- clone Xcode 프로젝트가 `iphone`으로 감지된다.
- AI 콘솔/quick prompt가 iPhone 규칙을 따른다.

### 16.3 MVP-2: Debug build

목표: Mac 환경에서 simulator debug build를 완성한다.

- workspace sync
- scheme/destination 선택
- `xcodebuild build`
- `.xcresult` artifact 저장
- compile/signing/scheme 오류 정규화

출시 기준:

- SwiftUI starter가 MacBook 로컬 또는 원격 Mac에서 debug build 성공.
- 실패 시 사용자 조치 가능한 failureKind가 표시된다.

### 16.4 MVP-3: Simulator 실행

목표: 브라우저에서 iPhone/iPad Simulator 실행 결과를 확인한다.

- Simulator inventory
- boot/install/launch/screenshot
- `mobile-mcp` 조건부 추천
- idle shutdown

출시 기준:

- Debug `.app`을 Simulator에 설치/실행하고 screenshot을 회수한다.
- Linux 단독 환경에서는 동일 버튼이 비활성으로 남는다.

### 16.5 MVP-4: Archive/export

목표: `.ipa`를 만들 수 있게 한다.

- signing preflight
- archive
- exportOptions 생성
- `.ipa`, dSYM artifact 등록

출시 기준:

- signing 자산이 준비된 프로젝트에서 release export가 성공한다.
- signing 누락은 blocked로 표시된다.

### 16.6 MVP-5: TestFlight

목표: 명시적 사용자 액션으로 TestFlight 업로드를 수행한다.

- ASC key 등록
- upload job
- processing status polling
- upload history

출시 기준:

- version/buildNumber 중복을 사전 차단한다.
- 업로드 성공/실패 상태가 프로젝트 이력에 남는다.

## 17. 구현 전 체크리스트

- 공식 Apple 문서와 로컬 `xcodebuild -help`, `xcrun simctl help` 확인.
- MacBook 로컬 설치와 원격 Mac agent 중 1차 타깃 확정.
- `ApiPath`/DTO/wire 변경 범위 확정.
- secret 저장 위치와 마스킹 테스트 작성.
- `ProjectPlatformEngine`/`PlatformEngineRegistry` 인터페이스 확정.
- Kotlin/Flutter engine 분리 구현 후 기존 프로젝트 생성/빌드 회귀 확인.
- 전역 `CLAUDE.md` 공통화 regression test 작성.
- Kotlin/Flutter/iPhone 지역 `CLAUDE.md` snapshot test 작성.
- iPhone 기본 skill/agent 템플릿 작성.
- `mobile-mcp` 기본 추천 조건을 preflight와 연결.
- Release Pack은 opt-in으로 분리.
