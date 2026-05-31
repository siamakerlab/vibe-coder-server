# vibe-coder-server 컨테이너 글로벌 규칙

이 파일은 컨테이너 안의 `/home/vibe/.claude/CLAUDE.md` 로 마운트되어, 컨테이너에서 실행되는 모든 Claude Code 세션에 글로벌로 적용된다. 호스트 경로는 `./vibe-coder-data/claude/CLAUDE.md`.

> 이 파일은 서버 최초 기동 시 자동 시드된 기본 템플릿이다. 자유롭게 수정해도 되며,
> 한 번 존재하면 서버가 다시 덮어쓰지 않는다. (전역 CLAUDE.md 탭: `/settings/claude-md`)
> 설정의 언어를 한국어/English 로 바꾸면, **미편집 시드 상태일 때만** 해당 언어 템플릿으로
> 자동 교체된다(직접 편집한 내용은 보존).

## 응답 언어

- 사용자 응답은 항상 **한국어 존댓말** 로 작성.
- 코드/명령어/식별자/고유명사는 원문 유지.

## ⚠ 빌드 환경 — 사전 설치된 도구 (재다운로드 금지)

vibe-coder-server 의 `/env-setup` 또는 `vibe-doctor install` 이 이미 설치해 둔 도구가 있다. **새로 다운로드 받지 말고 반드시 아래 경로의 사전 설치본을 그대로 사용한다.**

| 도구 | 경로 | 환경변수 | 비고 |
|---|---|---|---|
| **Gradle (시스템 설치본)** | `/home/vibe/.local/gradle/` | PATH 노출 (`gradle` = `/home/vibe/.local/bin/gradle`) | 영속 볼륨. **버전 고정 아님 — 빌드 전 `gradle --version` 으로 확인** |
| **Gradle Wrapper 캐시** | `/home/vibe/.gradle/wrapper/dists/` | — | 사전 캐싱된 dist 는 `ls` 로 확인 후 재사용 (버전 하드코딩 금지) |
| **Gradle 의존성 캐시** | `/home/vibe/.gradle/caches/` | — | Maven Central / Google 의존성 |
| **Android SDK** | `/opt/android-sdk` | `ANDROID_HOME`, `ANDROID_SDK_ROOT` | |
| **Android cmdline-tools** | `/opt/android-sdk/cmdline-tools/latest/bin/` | PATH 노출 (`sdkmanager`, `avdmanager`) | |
| **Android platform-tools** | `/opt/android-sdk/platform-tools/` | PATH 노출 (`adb`) | |
| **JDK 17** | `/opt/java/openjdk` | `JAVA_HOME` | OpenJDK 17 |
| **Node 20 LTS** | `/usr/bin/node`, `/usr/bin/npm`, `/usr/bin/npx` | — | |
| **npm 글로벌 prefix** | `/home/vibe/.local/` (`bin/`, `lib/node_modules/`) | — | MCP `npm install -g` 가 여기 떨어짐 |
| **Playwright 브라우저** | `/home/vibe/.cache/ms-playwright/` | — | Playwright MCP 사용 시 |

## Gradle 정책 (핵심)

**Gradle 버전을 문서/명령에 하드코딩하지 않는다.** 시스템 설치본과 사전 캐싱된 wrapper dist 는 시점에 따라 달라지므로(업그레이드 가능), 빌드 전에 **실제 상태를 먼저 조회**해서 그 값에 맞춘다. 이렇게 하면 gradle 이 올라가도 문서 수정 없이 자동 대응된다.

```bash
# 1) 시스템에 설치된 gradle 버전 확인
gradle --version            # 예: "Gradle 9.5.1" → INSTALLED_VER=9.5.1
# 2) 사전 캐싱된 wrapper dist 확인 (이미 받아둔 버전들)
ls /home/vibe/.gradle/wrapper/dists/   # 예: gradle-9.5.1-bin
```

프로젝트의 `gradle/wrapper/gradle-wrapper.properties` 가 **설치본/캐시에 없는 버전을 가리키면 새 버전을 무단 다운로드하지 않는다.** 다음 우선순위로 처리한다:

1. **설치본/캐시 버전 재활용 (권장)** — 위에서 확인한 캐싱·설치된 버전(`<VER>`)으로 `distributionUrl` 을 맞춘다(아래 `<VER>` 는 실제 조회값으로 치환):
   ```properties
   distributionUrl=https\://services.gradle.org/distributions/gradle-<VER>-bin.zip
   ```
   수정 후 변경 사유를 한 줄 주석으로 남긴다. (이미 일치하면 그대로 둔다.)

2. **Wrapper 우회** — `./gradlew` 대신 시스템 `gradle` 로 호출한다.
   ```bash
   gradle --no-daemon assembleDebug
   ```
   `gradle.properties` 의 `org.gradle.java.home` 등 호환성을 미리 검토한다.

3. **다른 버전이 정말 필요한 경우** — 그 사유(특정 플러그인 요구사항 등)를 명시한 뒤, 사용자에게 확인을 받고 다운로드한다. 무단 다운로드 금지.

## Android SDK 정책

프로젝트의 `local.properties` 에 `sdk.dir` 이 호스트 경로로 박혀 있을 수 있다. 컨테이너에서는 `local.properties` 를 **생성/수정하지 말고 그대로 두거나 삭제** 한다. `ANDROID_HOME` 환경변수가 우선되므로 SDK 위치는 자동 인식된다.

부족한 platform / build-tools 는 `sdkmanager` 로 설치하되, **메이저 버전이 아닌 마이너 버전 변경은 사용자에게 먼저 확인** 한다.

```bash
# 설치된 패키지 확인
sdkmanager --list_installed
# 누락분만 설치 (버전은 프로젝트 요구에 맞춰)
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

## 환경변수 (이미 컨테이너 entrypoint 에서 설정됨)

```
ANDROID_HOME=/opt/android-sdk
ANDROID_SDK_ROOT=/opt/android-sdk
JAVA_HOME=/opt/java/openjdk
PATH=/home/vibe/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/java/openjdk/bin:...
```

빌드 스크립트나 IDE 설정에서 이 값을 **덮어쓰지 않는다.**

## 버전 정책 (Claude Code 에 일임)

버전 관리는 **전적으로 Claude Code 가 자동 수행**한다. 사용자가 수동으로 버전을
올리지 않으며, **코드를 수정할 때마다** Claude Code 가 아래 규칙으로 버전을 갱신한다.

- **versionName** = `메이저.마이너.패치` (SemVer). 변경 내용에 따라 Claude Code 가 자율 결정:
  - **MAJOR**: Breaking change (공개 API 제거/시그니처 변경, 데이터 스키마 비호환, 파괴적 워크플로 변경, 0.x→1.0.0 최초 프로덕션).
  - **MINOR**: 하위호환 신규 기능/화면/설정 추가, 수준 있는 UX 개편.
  - **PATCH**: 하위호환 버그 수정·성능/보안 개선·리팩토링·UI 미세 조정·문서 갱신.
  - **기본값은 PATCH ++1** — 매 코드 수정마다 최소 patch 를 올린다(더 큰 변경이면 minor/major).
  - 0.x(pre-1.0) 에서는 파괴적 변경도 MINOR 로 처리 가능 (SemVer 2.0.0 §4).
- **versionCode** = `YYMMDDRRR` (9자리, 예: `260531001`). **매 코드 수정마다 `RRR` 을 +1**.
  - **날짜가 바뀌면 `RRR` 은 `001` 부터 다시 시작** (예: 5/31 마지막이 `260531007` 이면, 6/1 첫 수정은 `260601001`).
  - 같은 날 두 번째 수정 = `...002`, 세 번째 = `...003` …
- **앱 내 버전 표시**는 `BuildConfig` 참조 — 화면/코드에 버전 문자열 하드코딩 금지.
- 위 갱신은 `app/build.gradle(.kts)` 의 `versionName`/`versionCode` 에 반영하고, 같은 커밋에 포함한다.

## 키스토어 (서명) — 프로젝트별 파일 위치

Android 서명 키스토어는 **호스트 영속 볼륨** `/home/vibe/keystores/` 에 **applicationId 를
prefix** 로 저장되어 있다(운영자가 vibe-coder 서버 UI 에서 미리 생성). 아래 `<applicationId>`
를 `app/build.gradle(.kts)` 의 실제 applicationId 로 치환:

| 파일 | 용도 |
|---|---|
| `/home/vibe/keystores/<applicationId>.keystore` | 릴리즈 서명 키 (PKCS12) |
| `/home/vibe/keystores/<applicationId>-debug.keystore` | 디버그 서명 키 |
| `/home/vibe/keystores/<applicationId>-keystore.properties` | Gradle signing config (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) |
| `/home/vibe/keystores/<applicationId>-admob.properties` | (선택) AdMob IDs |

- `signingConfigs` 는 위 `.properties` 를 `Properties().load(FileInputStream(...))` 로 읽어 적용한다.
  **비밀번호/alias 평문을 build.gradle 에 하드코딩 금지** — properties 파일 경로만 참조.
- **키스토어를 임의로 새로 만들지 말 것** (운영자 정책). 파일이 없으면 빌드를 멈추고, 운영자에게
  vibe-coder 서버의 **설정 → 키스토어(`/settings/keystores`)** 에서 해당 applicationId 로
  생성하도록 안내한다. AGP 의 default `debug.keystore` 자동 생성도 금지.

## 설계 원칙 / 코드 품질

- **객체지향(OOP) 기반** 설계.
- **기능 모듈화** — 역할별로 분리, 단일 책임 원칙.
- **의존성 최소화** — 불필요한 라이브러리/결합 지양.
- **캡슐화 철저** — 내부 구현 은닉, 공개 표면(API) 최소화.
- **유지보수성 최우선** — 읽기 쉽고 변경하기 쉬운 코드.
- **레거시 코드 절대 방치 금지 — 발견 즉시 제거.** (주석 처리한 죽은 코드, 미사용 함수/클래스/리소스/import 포함.)

## Git 규칙

- **프로젝트 생성 즉시 `git init`.**
- **코드 변경 시 반드시 즉시 commit** — 변경을 쌓아두지 않는다.
- 커밋 메시지는 **작업 단위 기준으로 명확하게** 작성한다(무엇을 / 왜).

## 절대 금지

- 사전 설치된 도구를 무시하고 다른 경로/버전 재다운로드
- `/opt/*` 또는 `/home/vibe/.gradle/`, `/home/vibe/.local/` 의 임의 삭제
- `local.properties` 에 호스트 경로 하드코딩
- Wrapper 버전 변경 시 사유 미기재
