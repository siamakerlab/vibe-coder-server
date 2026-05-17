# Vibe Coder MVP Planning Document

> **Summary**: Android 폰에서 PC의 Claude Code · Gradle · Git 환경을 원격 제어하여 프롬프트 → 코드수정 → debug build → APK 다운로드/설치까지 이어주는 모바일 개발 콘솔(MVP).
>
> **Project**: vibe-coder
> **Version**: 0.1.0
> **Author**: sia@siamakerlab.com
> **Date**: 2026-05-17
> **Status**: Draft

---

## Executive Summary

| Perspective | Content |
|-------------|---------|
| **Problem** | 외출·이동 중에도 진행 중인 Android 프로젝트의 변경·검증·체험 사이클을 닫고 싶지만, 폰만으로는 Claude Code·Gradle·Git 도구를 직접 실행할 수 없다. |
| **Solution** | PC에서 실행되는 Ktor 서버(Vibe Coder Server)가 모든 무거운 작업을 수행하고, Android 앱(Vibe Coder Console)은 페어링·프롬프트 전송·실시간 로그 시청·APK 다운로드/설치만 담당하는 콘솔형 클라이언트 아키텍처. |
| **Function/UX Effect** | 모바일 화면 한 곳에서 "프롬프트 입력 → Claude 로그 실시간 시청 → debug build → APK 설치"를 5분 안에 완료. 서버 환경 진단, Git status/diff/log 조회, 스크린샷·로그 업로드까지 단일 흐름. |
| **Core Value** | 데스크톱을 떠나지 않아도 되는 시간을, 데스크톱을 떠나서도 동일하게 만들어 주는 "주머니 속 개발 서버 리모컨". |

---

## Context Anchor

> Auto-generated from Executive Summary. Propagated to Design/Do documents for context continuity.

| Key | Value |
|-----|-------|
| **WHY** | 폰만 가진 상태에서도 Android 프로젝트의 코드 수정 → debug build → 설치 검증 사이클을 닫기 위해. PC를 켜 두기만 하면 어디서든 동일한 dev loop을 돌릴 수 있어야 한다. |
| **WHO** | 자기 자신이 Android 앱을 만드는 1인 개발자(sia@siamakerlab.com). PC 서버를 켜 두고 외부에서 Android 폰으로만 작업 흐름을 이어가는 시나리오. 다중 사용자/조직은 MVP 범위 외. |
| **RISK** | (1) 무인 서버에서의 Claude/Gradle 무한 루프·timeout 누락 → 리소스 점유 (2) workspace 외부 경로 접근 / path traversal로 임의 파일 노출 (3) APK 설치 시 잘못된 SHA-256 검증으로 변조 파일 설치 (4) 토큰 평문 저장. |
| **SUCCESS** | (1) "페어링 → 프롬프트 → Claude 로그 → debug build → APK 다운로드 → 설치 Intent" 16-step 시나리오가 단일 세션에서 완주된다. (2) 모든 외부 명령에 timeout이 적용되어 무한 점유가 발생하지 않는다. (3) workspace 외부 경로/path traversal 차단 단위 테스트 통과. (4) Linux + Windows 양쪽에서 debug APK가 동일하게 생성된다. |
| **SCOPE** | 9-phase 구현 순서: ①서버 골격/상태/환경 → ②페어링 인증 → ③프로젝트 등록/CLAUDE.md → ④작업 큐 + WebSocket 로그 → ⑤Claude Code 실행 → ⑥Debug Build/artifact → ⑦Android UI → ⑧APK 다운로드/설치 → ⑨Git 조회/파일 업로드. |

---

## 1. Overview

### 1.1 Purpose

Android 폰을 들고 있는 1인 개발자가, 데스크톱(또는 노트북)에서 켜 둔 Vibe Coder Server에 접속해서 다음을 모바일에서 끝까지 수행할 수 있게 한다.

1. 진행 중인 Android 프로젝트에 Claude Code 프롬프트를 보내고 그 로그를 실시간으로 본다.
2. 변경된 코드로 debug APK를 빌드하고 그 로그를 본다.
3. 빌드된 APK를 폰으로 다운로드해서 "알 수 없는 앱 설치" 권한 흐름으로 설치 화면까지 연다.
4. Git status / diff / log를 본다 (수정·push는 하지 않는다).
5. 화면 캡처·로그 파일 등을 서버 프로젝트로 업로드한다.

### 1.2 Background

기존에는 PC 앞에 있어야만 위 dev loop을 돌릴 수 있었다. 외출·이동·소파 등에서 "한 줄만 고치고 깔아서 확인하고 싶다"는 작은 흐름이 매번 막혔다. SSH 클라이언트로 우회하는 것은 가능하지만 (1) 모바일 환경에서 빌드 로그를 안정적으로 보기 어렵고 (2) APK를 폰으로 받아 설치 Intent를 여는 흐름이 끊긴다. Vibe Coder는 이 두 가지를 모바일 UX로 직접 처리한다.

### 1.3 Related Documents

- 명세: 사용자 제공 "Vibe Coder — Claude Code용 최종 MVP 구현 프롬프트" (대화 컨텍스트)
- 전역 개발 규칙: `C:/Users/wody/.claude/CLAUDE.md` (Gradle 9.5.1 / AGP 9.2.0 / Kotlin 2.2.20 / Compose BOM 2026.05 / Hilt 2.59.2 매트릭스, 버전 정책, CHANGELOG 정책)
- Design 문서: `docs/02-design/features/vibe-coder-mvp.design.md` (다음 단계)

---

## 2. Scope

### 2.1 In Scope

#### 서버 (vibe-coder-server)
- [ ] Ktor Server (Netty engine, kotlinx.serialization, WebSocket)
- [ ] SQLite + Exposed ORM (devices, projects, tasks, builds, artifacts, uploaded_files)
- [ ] YAML 설정 로딩 (`server.yml`, `workspace.yml`, `claude.yml`, `android.yml`, `git.yml`, `security.yml`)
- [ ] 페어링 코드 발급 (서버 시작 시 콘솔 출력, 10분 만료)
- [ ] Bearer token 인증 (`Authorization: Bearer {token}`, DB에는 hash 저장)
- [ ] WebSocket 인증 (연결 후 첫 메시지 `{"type":"auth","token":"..."}`)
- [ ] 환경 진단 (JDK / Android SDK / Git / Claude Code / workspace 권한)
- [ ] 프로젝트 등록 (기존 Android 프로젝트 sourcePath 기반)
- [ ] `.vibecoder/` 메타 폴더 + `CLAUDE.md` 자동 생성
- [ ] 작업 큐 (프로젝트당 동시 1개, project-level mutex)
- [ ] 외부 프로세스 실행 래퍼 (ProcessBuilder + timeout + stdout/stderr stream)
- [ ] Claude Code 실행 (`claude -p "{wrappedPrompt}"`) + wrapped prompt 생성
- [ ] Gradle Debug Build (`gradlew assembleDebug` / `gradlew.bat assembleDebug` OS 분기)
- [ ] APK 탐색 → `.vibecoder/artifacts/debug/{buildId}/` 복사 + SHA-256 + metadata.json
- [ ] Git 조회 (`git status --short`, `git diff`, `git log --oneline -20`)
- [ ] 파일 업로드/다운로드 (`.vibecoder/uploads/{yyyyMMdd}/...`, 100MB, 확장자 화이트리스트)
- [ ] WebSocket 로그 스트리밍 (`/ws/projects/{projectId}/tasks/{taskId}/logs`)

#### Android 앱 (vibe-coder-console)
- [ ] Jetpack Compose + Material 3 + Navigation Compose
- [ ] MVVM + Repository pattern (+ Hilt DI)
- [ ] DataStore (Preferences flavor) — 서버 주소·토큰·deviceName 저장
- [ ] Ktor Client (Android engine) — REST + WebSocket
- [ ] 12개 화면: Connect / Dashboard / Environment / ProjectList / ProjectRegister / ProjectDetail / ClaudePrompt / Log / Build / Artifact / Git / FileTransfer
- [ ] APK 다운로드 → `context.cacheDir/apks/{artifactId}.apk` → SHA-256 검증 → FileProvider URI + ACTION_VIEW Intent
- [ ] "알 수 없는 앱 설치" 권한 가이드 (Android 8+)

#### Shared (vibe-coder-shared, JVM-only Gradle 모듈)
- [ ] `@Serializable` DTO: ProjectDto / TaskDto / BuildDto / ArtifactDto / ServerStatusDto / EnvironmentCheckDto / CheckItemDto / LogMessageDto
- [ ] API path / WebSocket 프레임 type 상수

#### Repository / DevX
- [ ] Gradle 9.5.1 multi-module, version catalog (`gradle/libs.versions.toml`)
- [ ] `.gitignore` (`.bkit/`, `*.db`, `vibe-coder-server-data/`, `.gradle/`, `build/`, `local.properties`)
- [ ] CHANGELOG.md (모노레포 통합 — 글로벌 CLAUDE.md 정책)
- [ ] README.md (모노레포 루트 + 모듈별 1줄 설명)
- [ ] 프로젝트별 README.md (server, android-app, shared)

### 2.2 Out of Scope (MVP)

명세 §2 "MVP 제외 범위" 전체. 특히 다음은 절대 구현하지 않는다.

- 신규 Android 프로젝트 자동 생성 / 템플릿 앱 생성
- Release 서명 / AAB / Play Console 업로드
- ADB 연결 / Logcat 수집 / 디바이스 미러링
- Git push / reset --hard / clean -fd / force push / 원격 저장소 설정 변경
- 다중 사용자 권한 / OAuth / 조직 관리
- Cloudflare Tunnel · Tailscale 자동 설정
- 앱 아이콘 / 스토어 이미지 자동 생성
- 작업 예약(cron) / 자동 테스트 실행
- 서버에서 임의 shell 명령 실행 UI (raw shell 절대 금지)

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-01 | 페어링 코드 발급: 서버 부팅 시 6자리 코드를 콘솔에 출력하고 10분 만료. `POST /api/auth/pair`로 token 발급, hash만 DB 저장. | High | Pending |
| FR-02 | 인증 미들웨어: `Authorization: Bearer {token}` 검증, WebSocket은 연결 후 첫 메시지 `{type:"auth",token}`로 인증. | High | Pending |
| FR-03 | 서버 상태 조회: `GET /api/server/status`에서 OS·JDK·workspace·project count·running task count·claude/sdk/git 가용성·여유 디스크 반환. | High | Pending |
| FR-04 | 환경 진단: `GET /api/server/environment` — JDK / Android SDK / Git / Claude Code / workspace 권한 5종 OK/WARNING/ERROR. | High | Pending |
| FR-05 | 프로젝트 등록: 기존 sourcePath 기반. workspace 정책 검증, gradlew/settings.gradle 존재 확인, `.vibecoder/` 생성, `CLAUDE.md` 미존재 시 생성. | High | Pending |
| FR-06 | 프로젝트 목록/상세 조회. | High | Pending |
| FR-07 | Claude Code 작업 실행: 작업 큐 등록 → wrapped prompt 생성 → sourcePath에서 `claude -p` 실행 → stdout/stderr → 로그 파일 + WebSocket 스트림. | High | Pending |
| FR-08 | Claude 작업 종료 시 `git diff`를 `.vibecoder/patches/{taskId}.patch`로 저장. | Medium | Pending |
| FR-09 | Debug build 실행: OS 분기로 `gradlew.bat` 또는 `./gradlew` + `assembleDebug` 실행. 로그 파일 + WebSocket 스트림. | High | Pending |
| FR-10 | APK 산출물 관리: `source/{moduleName}/build/outputs/apk/debug/*.apk` 중 최신 → `.vibecoder/artifacts/debug/{buildId}/`로 복사 + SHA-256 + metadata.json. | High | Pending |
| FR-11 | APK 다운로드 API: 인증 + workspace 경계 검증 + path traversal 차단. | High | Pending |
| FR-12 | Git 조회: status / diff / log 3개 API. | Medium | Pending |
| FR-13 | 파일 업로드: 100MB 이하, 확장자 블랙리스트(exe/bat/cmd/ps1/sh) 차단, `.vibecoder/uploads/{yyyyMMdd}/{fileId}_{name}` 저장. | Medium | Pending |
| FR-14 | 파일 목록/다운로드/삭제. | Medium | Pending |
| FR-15 | 작업 취소 API: 실행 중 프로세스를 `destroyForcibly()`로 종료, 상태 CANCELED. | Medium | Pending |
| FR-16 | Android 앱: 12개 화면 + WebSocket 로그 실시간 표시 + APK 다운로드/SHA-256 검증/설치 Intent + "알 수 없는 앱 설치" 권한 가이드. | High | Pending |

### 3.2 Non-Functional Requirements

| Category | Criteria | Measurement Method |
|----------|----------|-------------------|
| **Cross-platform** | Linux/Windows/macOS 모두 동작. OS 분기 코드는 `OsType` 추상화 한 곳에서만. | 수동: Linux + Windows 양쪽에서 16-step 시나리오 완주 |
| **Security** | path traversal 차단 / workspace 외부 파일 접근 차단 / 업로드 확장자 차단 / 토큰 hash 저장 / raw shell UI 없음. | 단위 테스트 + 코드 리뷰 체크리스트 |
| **Reliability** | 모든 외부 명령에 timeout(기본 Claude 60분 / Build 30분). 취소 시 프로세스 강제 종료. | 단위 테스트 + ProcessRunner.kt 리뷰 |
| **Realtime** | WebSocket 로그 종단간 지연 < 1초 (LAN 기준). | 수동 측정 |
| **APK Integrity** | 서버 SHA-256 = 앱 다운로드 후 재계산 SHA-256. 불일치 시 설치 차단. | 단위 테스트 + 수동 |
| **Concurrency** | 동일 프로젝트에서 Claude/Build 동시 불가. 다른 프로젝트는 병렬 가능(MVP 구조만). | 단위 테스트 (project-level Mutex) |
| **Dependency Currency** | 글로벌 CLAUDE.md 매트릭스(Gradle 9.5.1 / AGP 9.2.0 / Kotlin 2.2.20 / Compose BOM 2026.05 / Hilt 2.59.2) 그대로 적용. | `./gradlew dependencies` + `versions.toml` 리뷰 |
| **i18n Structure** | strings.xml 영어 단일이지만 하드코딩 금지. 향후 언어 추가 가능 구조. | 코드 리뷰 (`getString()` 사용률) |
| **Logging** | 공통 Logger 사용. release 빌드에서 로그 비활성. 토큰·PII 로그 금지. | 코드 리뷰 + ProGuard 규칙 |

---

## 4. Success Criteria

### 4.1 Definition of Done

명세 §20 "완료 기준" 18개 항목을 그대로 채택한다.

- [ ] **SC-01** 서버를 실행할 수 있다 (`./gradlew :server:run` 또는 distribution).
- [ ] **SC-02** Android 앱에서 서버에 페어링할 수 있다.
- [ ] **SC-03** Android 앱에서 서버 상태를 볼 수 있다.
- [ ] **SC-04** Android 앱에서 서버 환경 진단 결과를 볼 수 있다.
- [ ] **SC-05** Android 앱에서 기존 Android 프로젝트를 등록할 수 있다.
- [ ] **SC-06** Android 앱에서 프로젝트 목록과 상세 정보를 볼 수 있다.
- [ ] **SC-07** Android 앱에서 Claude 프롬프트를 보낼 수 있다.
- [ ] **SC-08** 서버가 프로젝트 경로에서 Claude Code를 실행한다.
- [ ] **SC-09** Android 앱에서 Claude Code 실행 로그를 실시간으로 볼 수 있다.
- [ ] **SC-10** Android 앱에서 debug build를 요청할 수 있다.
- [ ] **SC-11** 서버가 Gradle Wrapper로 debug APK를 생성한다.
- [ ] **SC-12** Android 앱에서 빌드 로그를 실시간으로 볼 수 있다.
- [ ] **SC-13** Android 앱에서 APK를 다운로드할 수 있다.
- [ ] **SC-14** Android 앱에서 APK 설치 화면을 열 수 있다.
- [ ] **SC-15** Android 앱에서 Git status, diff, log를 볼 수 있다.
- [ ] **SC-16** Android 앱에서 파일을 서버 프로젝트에 업로드할 수 있다.
- [ ] **SC-17** 서버는 workspace 밖 파일 접근을 차단한다.
- [ ] **SC-18** Windows/Linux/macOS에서 빌드 명령 분기가 가능하다 (Linux + Windows는 실측 검증).

### 4.2 Quality Criteria

- [ ] **QC-01** `./gradlew build` 전체 모듈 성공, deprecation warning 0.
- [ ] **QC-02** 핵심 로직 단위 테스트 — `PathSafetyTest`, `ProcessRunnerTimeoutTest`, `Sha256Test`, `PairingCodeTest`, `OsTypeBuilderSelectorTest` — 모두 통과.
- [ ] **QC-03** 서버 lint(detekt) / Android lint 0 error.
- [ ] **QC-04** 글로벌 CLAUDE.md §2-2-1 매트릭스 그대로 적용 (downgrade 없음).
- [ ] **QC-05** 글로벌 CLAUDE.md §1 절차 준수: 빌드 → 버전 갱신 → CHANGELOG → commit.

---

## 5. Risks and Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **R-01** Claude Code CLI가 무한 대기 / 표준 입력 요구 | High | Medium | `claude -p` non-interactive 모드 강제. ProcessRunner에서 60분 hard timeout, 1분마다 stdout idle 체크. |
| **R-02** Gradle 빌드가 너무 길거나 데몬 누수 | High | Medium | `--no-daemon` 옵션, 30분 hard timeout, `destroyForcibly` 종료 시 자식 프로세스도 함께 정리. |
| **R-03** path traversal로 workspace 외부 파일 노출 (`../../etc/passwd`) | Critical | High (공격이 시도된다고 가정) | 모든 경로는 `Path.normalize()` + `startsWith(workspaceRoot)` 검사. 단위 테스트 필수. 파일명에 `..` / 절대경로 / 드라이브 letter 차단. |
| **R-04** APK 변조 (네트워크 중간에 변조된 파일) | High | Low | 서버에서 SHA-256 계산 → metadata에 저장 → 앱에서 다운로드 후 재계산 → 불일치 시 즉시 삭제, 설치 차단. |
| **R-05** WebSocket 첫 메시지 인증 누락 | High | Medium | 핸들러 진입 시 `withTimeout(5s)` 안에서 auth 메시지를 받지 못하면 연결 종료. 인증 전에는 어떤 로그도 송신하지 않음. |
| **R-06** Windows에서 `./gradlew` 또는 Linux에서 `gradlew.bat` 잘못 호출 | Medium | Medium | `OsType` enum + `BuilderSelector` 단일 책임. 단위 테스트로 OS 분기 검증. |
| **R-07** Hilt 2.58 이하 사용 시 AGP 9 + `BaseExtension` 제거로 빌드 실패 | High | Low (매트릭스에서 2.59.2 명시) | `libs.versions.toml`에 Hilt 2.59.2 고정. CHANGELOG에 사유 기록. |
| **R-08** AGP 9 + Kotlin 2.2 환경에서 KSP source set 충돌 | Medium | Medium | `gradle.properties`에 `android.disallowKotlinSourceSets=false` 적용. CHANGELOG 기록. |
| **R-09** 토큰 평문 저장으로 DB 유출 시 도용 | High | Low | 토큰은 SHA-256(또는 bcrypt) hash만 저장. 발급 직후 1회만 평문 반환. 비교는 hash 대조. |
| **R-10** "알 수 없는 앱 설치" 권한 미부여로 설치 Intent 실패 | Medium | High (Android 8+) | `packageManager.canRequestPackageInstalls()` 사전 체크, 미부여 시 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 설정 화면 안내. |
| **R-11** 동일 프로젝트에서 Claude + Build 동시 실행 | Medium | Medium | Project-level `Mutex`로 직렬화. 큐 dispatcher가 lock 대기. |
| **R-12** 로그 파일 무한 증가로 디스크 점유 | Medium | High (장기 사용 시) | `artifactKeepCount` 정책으로 오래된 build/log 자동 정리. MVP는 수동 정리 옵션만 — DELETE artifact API. |

---

## 6. Impact Analysis

> **Purpose**: 기존 자원에 대한 변경이 아니라 **신규 프로젝트 신설**이지만, 글로벌 정책과 사용자 머신 상태에 영향을 주는 부분을 명시한다.

### 6.1 Changed Resources

| Resource | Type | Change Description |
|----------|------|--------------------|
| `D:/dev/vibe-coder/` 디렉토리 | Filesystem | 모노레포 신규 생성 (server/, android-app/, shared/, docs/, gradle/, README.md, CHANGELOG.md, .gitignore) |
| 사용자 PC의 8개 포트(기본 17880) | Network | 서버 listen. `0.0.0.0:17880` (LAN에서 폰이 접근). 방화벽 설정 필요. |
| 사용자 PC의 workspace 디렉토리 (`vibe-coder-server-data/workspace/projects/*`) | Filesystem | 등록된 Android 프로젝트의 sourcePath. 서버는 read/write. **Claude/Gradle이 임의 파일 수정 가능**. |
| Android 폰의 `cacheDir/apks/` | Filesystem (앱 격리) | 다운로드된 APK 캐시. 사용자 데이터 삭제 시 함께 삭제됨. |
| Android 폰의 "알 수 없는 앱 설치" 권한 | OS Permission | Vibe Coder 앱에 부여 필요 (Android 8+). |
| Claude Code CLI / Android SDK / Git / JDK | External Tool | 서버가 호출만 함. 설치/업데이트는 사용자 책임. |

### 6.2 Current Consumers

| Resource | Operation | Code Path | Impact |
|----------|-----------|-----------|--------|
| Workspace 프로젝트 sourcePath | READ/WRITE | server: ClaudeRunner, GradleBuilder, GitReader / **사용자가 직접 IDE에서 편집** | 동시 편집 시 Claude diff와 충돌 가능 → 작업 시작 시 git status 스냅샷 저장 (FR-08) |
| Android SDK (`ANDROID_HOME`) | READ | GradleBuilder가 sub-process 환경변수로 전달 | OS 환경변수 의존. 환경 진단 API에서 사전 경고. |
| Claude Code CLI 인증 토큰 | READ | `claude` 명령이 자체적으로 사용 | 서버는 토큰 관리하지 않음. CLI가 인증 안 되어 있으면 stdout으로 안내 메시지가 들어옴 → 로그에 그대로 전달. |
| 포트 17880 | LISTEN | Ktor server | 다른 프로세스 점유 시 시작 실패. 설정 파일에서 override 가능. |

### 6.3 Verification

- [ ] 같은 프로젝트에 동시 작업 요청 시 두 번째 요청이 PENDING으로 남아 대기 (단위 테스트)
- [ ] workspace 외부 sourcePath로 프로젝트 등록 시도 시 400 응답 (단위 테스트)
- [ ] `.gitignore`에 `.bkit/`, `vibe-coder-server-data/`, `*.db`, `build/`, `.gradle/`, `local.properties`, `.idea/`, `*.apk` 포함
- [ ] Claude CLI 미설치 환경에서 환경 진단 API가 ERROR로 명확히 표시
- [ ] 포트 충돌 시 서버 startup이 명확한 에러로 종료

---

## 7. Architecture Considerations

### 7.1 Project Level Selection

| Level | Characteristics | Recommended For | Selected |
|-------|-----------------|-----------------|:--------:|
| Starter | 단일 모듈 / 정적 사이트 | 포트폴리오, 랜딩페이지 | ☐ |
| Dynamic | 기능별 모듈 + BaaS | 웹앱 + 인증 + DB | ☐ |
| **Enterprise** | **Strict layer separation, DI, multi-module** | **모노레포 / 서버+클라이언트 / 외부 프로세스 통합** | **☑** |

→ **Enterprise** 선택 이유: server(Ktor) + android-app(Compose) + shared(JVM library) 3개 Gradle 모듈, DI(Hilt), 외부 CLI 통합(Claude/Gradle/Git), Real-time WebSocket, 보안 경계가 명확해야 함.

### 7.2 Key Architectural Decisions

| Decision | Options | Selected | Rationale |
|----------|---------|----------|-----------|
| **Repository layout** | Multi-repo / Monorepo | **Monorepo (settings.gradle.kts에 3개 include)** | DTO 공유, 단일 버전 정책, CHANGELOG 통합 (글로벌 CLAUDE.md §5) |
| **Build tool** | Maven / Gradle | **Gradle 9.5.1 + Kotlin DSL** | 글로벌 CLAUDE.md §2-2-1 매트릭스 |
| **JDK** | 17 / 21 | **21 (toolchain)** | AGP 9.2.0 + Kotlin 2.2.20 권장. server/shared/android-app 모두 동일 |
| **Server framework** | Spring Boot / Ktor / Micronaut | **Ktor 3.x (Netty + WebSocket + content-negotiation)** | 명세 지정. Coroutine 친화적, 가벼움 |
| **Server ORM** | Exposed / SQLDelight / JDBC | **Exposed (DAO + DSL)** | 사용자 선택. SQLite + 단일 JVM 모듈 |
| **DB** | SQLite / H2 / Postgres | **SQLite (file-based, WAL 모드)** | MVP, 단일 사용자, 백업 단순 |
| **Serialization** | kotlinx.serialization / Jackson | **kotlinx.serialization** | Kotlin native, multiplatform 친화 |
| **Shared module** | KMP / JVM-only | **JVM-only (`kotlin("jvm")` library)** | 사용자 선택. MVP 단순화 |
| **Server logger** | log4j2 / logback / kotlin-logging | **logback + kotlin-logging** | Ktor 기본 호환 |
| **Android UI** | XML / Compose | **Jetpack Compose (Material 3)** | 명세 지정 + 글로벌 매트릭스 |
| **Android DI** | Hilt / Koin / 수동 | **Hilt 2.59.2** | 글로벌 매트릭스, AGP 9 호환 |
| **Android networking** | OkHttp+Retrofit / Ktor Client | **Ktor Client (Android engine) + WebSocket** | DTO 공유 시 직렬화 일관성, server와 동일 stack |
| **Android storage** | DataStore / SharedPreferences | **DataStore (Preferences flavor 1.2.1)** | 글로벌 매트릭스 |
| **Android nav** | NavHost / Compose Destinations | **Navigation Compose 2.8.9** | 글로벌 매트릭스 |
| **Server config** | properties / YAML / HOCON | **YAML (`kaml` lib)** | 명세 §16 YAML 예시 그대로 |
| **Concurrency** | Project-level / Server-level | **Project-level Mutex** | 사용자 선택. 다른 프로젝트는 병렬 가능 (구조만 열어둠) |
| **WebSocket auth** | URL query / Subprotocol / **First message** | **First message** | 사용자 선택. 로그 노출 위험 최소화 |
| **Pairing code issuance** | Static config / CLI / **Startup console** | **Startup console** (6자리, 10분 만료) | 사용자 선택 |
| **OS priority** | Windows-first / Linux-first / Equal | **Linux-first + Windows 동등 지원** | 사용자 선택. macOS는 best-effort |
| **Android i18n** | 한국어 우선 / 영어 단일 | **영어 단일 strings.xml** | 사용자 선택 + 글로벌 CLAUDE.md §6 |

### 7.3 Clean Architecture Approach

```
Selected Level: Enterprise (Monorepo)

vibe-coder/
├─ settings.gradle.kts
├─ build.gradle.kts            ← root (plugins block, version catalog 적용)
├─ gradle/
│  └─ libs.versions.toml       ← 버전 카탈로그 (단일 진실 원천)
├─ gradle.properties           ← android.disallowKotlinSourceSets=false 등
├─ .gitignore
├─ CHANGELOG.md
├─ README.md
│
├─ server/                     ← :server (Kotlin/JVM, Ktor)
│  ├─ build.gradle.kts
│  └─ src/main/kotlin/com/siamakerlab/vibecoder/server/
│     ├─ ServerMain.kt
│     ├─ config/               ← YAML 로딩, 설정 모델
│     ├─ db/                   ← Exposed schemas, DAO
│     ├─ auth/                 ← 페어링 코드, Bearer middleware, WS auth
│     ├─ workspace/            ← workspace 경로 검증, .vibecoder 폴더 관리
│     ├─ projects/             ← 등록/조회 라우트
│     ├─ tasks/                ← 작업 큐, ProcessRunner, project Mutex
│     ├─ claude/               ← Claude Code 실행 + wrapped prompt
│     ├─ build/                ← Gradle Wrapper 실행 + APK 탐색
│     ├─ artifacts/            ← SHA-256 + metadata + 다운로드
│     ├─ git/                  ← status / diff / log
│     ├─ files/                ← 업로드/다운로드
│     ├─ env/                  ← 환경 진단
│     └─ ws/                   ← WebSocket hub (로그 broadcast)
│
├─ android-app/                ← :android-app
│  └─ app/
│     ├─ build.gradle.kts
│     ├─ src/main/kotlin/com/siamakerlab/vibecoder/console/
│     │  ├─ MainActivity.kt
│     │  ├─ VibeCoderApp.kt    ← Application (Hilt)
│     │  ├─ data/              ← Repository + Ktor Client + DataStore
│     │  ├─ domain/            ← 도메인 모델 (DTO → UI 변환)
│     │  ├─ ui/                ← 12 screens (Connect / Dashboard / ...)
│     │  ├─ di/                ← Hilt modules
│     │  ├─ install/           ← FileProvider + APK install Intent
│     │  └─ ws/                ← WebSocket client + LogStream Flow
│     └─ src/main/res/         ← strings.xml (en) + values-night
│
└─ shared/                     ← :shared (JVM library)
   └─ src/main/kotlin/com/siamakerlab/vibecoder/shared/
      ├─ dto/                  ← @Serializable DTO 8개
      ├─ api/                  ← API path / WS type 상수
      └─ ws/                   ← WS 프레임 sealed class
```

---

## 8. Convention Prerequisites

### 8.1 Existing Project Conventions

- [x] 글로벌 CLAUDE.md (`C:/Users/wody/.claude/CLAUDE.md`): 적용
- [ ] 모노레포 루트 `CLAUDE.md`: Design phase에서 작성
- [ ] 등록 대상 Android 프로젝트별 `CLAUDE.md`: 프로젝트 등록 시 자동 생성 (명세 §8 템플릿)
- [ ] `detekt.yml` (server 모듈 lint): Design phase에서 정의
- [ ] Android lint baseline: Design phase에서 정의

### 8.2 Conventions to Define/Verify

| Category | Current State | To Define | Priority |
|----------|---------------|-----------|:--------:|
| **Package naming** | missing | `com.siamakerlab.vibecoder.{server,console,shared}` | High |
| **Module naming** | missing | `:server`, `:android-app`, `:shared` | High |
| **Version policy** | global CLAUDE.md §5 | 모노레포 통합 `version.txt` 또는 `libs.versions.toml`의 project.version | High |
| **CHANGELOG** | global CLAUDE.md §1 | 루트 CHANGELOG.md 통합, Keep a Changelog 형식 | High |
| **Logging** | missing | `logback.xml` (server), `Timber` (android) | High |
| **DTO 위치** | missing | shared 모듈 only, server·android 양쪽에서 import | High |
| **API path** | missing | `shared.api.ApiPath` 상수 — server route와 android repository가 같은 상수 참조 | High |
| **Error model** | missing | server: `ApiError(code, message)` JSON, android: sealed class `Result<T>` | High |
| **i18n** | missing | `strings.xml` (en) 단일, 하드코딩 금지, Compose에서는 `stringResource()` 강제 | High |
| **Filesystem 안전 API** | missing | `WorkspacePath.from(rootDir, relative)` 단일 entry point | Critical |

### 8.3 Environment Variables Needed

서버는 환경변수보다는 **YAML 설정**을 1차로 사용한다. 환경변수는 보조.

| Variable | Purpose | Scope | To Be Created |
|----------|---------|-------|:-------------:|
| `VIBECODER_CONFIG_DIR` | 설정 파일 위치 override (기본 `./config`) | Server | ☑ |
| `VIBECODER_WORKSPACE_ROOT` | workspace.yml의 root override | Server | ☑ |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK 경로 (Gradle 빌드용) | Server (subprocess) | OS-level (기존) |
| `CLAUDE_CMD` | Claude CLI 명령 경로 override (`auto` 자동 탐색 실패 시) | Server | ☑ |

Android 앱 환경변수는 사용하지 않는다 (DataStore).

### 8.4 Pipeline Integration

9-phase Development Pipeline은 적용하지 않는다 (이미 MVP §19 자체 9단계 보유).
PDCA 사이클(plan → design → do → analyze → iterate → report)만 적용한다.

---

## 9. Next Steps

1. [ ] **`/pdca design vibe-coder-mvp`** — Design 문서 작성
   - 3가지 아키텍처 옵션 비교 (Minimal / Clean / Pragmatic) 후 선택
   - 11개 module별 클래스/함수 시그니처 수준까지 정의
   - Session Guide 생성 (multi-session 구현 대비)
2. [ ] **`/pdca do vibe-coder-mvp`** — 구현 시작
   - Module 1 (root build infra + libs.versions.toml + shared DTO)부터 순차
   - 명세 §19 9단계 순서를 따르되 module별 Session으로 분할
3. [ ] 각 단계 종료 시 `/pdca analyze` → Match Rate < 90% 시 `/pdca iterate`
4. [ ] 전체 종료 후 `/pdca report`로 통합 보고서

---

## Version History

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 0.1 | 2026-05-17 | Initial draft. 사용자 명세 + Checkpoint 1/2 답변 반영 (Linux-first, JVM-only shared, Exposed, 첫-메시지 WS auth, 콘솔 페어링, minSdk 26, project-level mutex, en-only). | sia@siamakerlab.com |
