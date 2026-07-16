# vibe-coder-server 컨테이너 글로벌 규칙

이 파일은 컨테이너 안의 `/home/vibe/.claude/CLAUDE.md` 로 마운트되어, 컨테이너에서 실행되는 Claude Code 및 Codex 등 AI 코딩 세션에 글로벌로 적용된다. Codex 는 `/home/vibe/.codex/AGENTS.md` symlink 를 통해 같은 내용을 읽는다. 호스트 경로는 `./vibe-coder-data/claude/CLAUDE.md`.

> 이 파일은 서버 최초 기동 시 자동 시드된 기본 템플릿이다. 자유롭게 수정해도 되며,
> 한 번 존재하면 서버가 다시 덮어쓰지 않는다. (전역 AI 지침 탭: `/settings/claude-md`)
> 설정의 언어를 한국어/English 로 바꾸면, **미편집 시드 상태일 때만** 해당 언어 템플릿으로
> 자동 교체된다(직접 편집한 내용은 보존).

## 목차

1. 응답 언어 · 2. 코드 수정 후 필수 절차(최우선) · 3. 버전 관리 · 4. 디버그 빌드 패키지명 ·
5. 서명 키스토어 · 6. 빌드 환경(도구/Gradle/SDK/환경변수) · 7. 설계 원칙 · 8. 네이밍 규칙 ·
9. Git · 10. 문서 · 11. 절대 금지 · 12. 최종 원칙 · 13. Android Compose UI 함정(재발 방지) ·
14. 에뮬레이터 실행·스크린샷 자율 실행 금지 · 15. Context7 공식 문서 선확인

---

## 1. 응답 언어

- 사용자 응답은 항상 **한국어 존댓말** 로 작성.
- 코드/명령어/식별자/고유명사는 원문 유지.

## 2. ⚠ 코드 수정 후 필수 절차 (최우선 — 절대 누락 금지)

**모든 코드 수정 작업 이후 아래를 순서대로 빠짐없이 실행한다:**

1. **빌드 테스트** — 컴파일/빌드 통과 확인(단위 테스트 포함). **여기서 "빌드 테스트"는 컴파일·빌드·단위 테스트까지를 뜻하며, 에뮬레이터 실행/스크린샷 검토는 포함하지 않는다(§14).**
2. **자동 버전 관리** — `versionName`/`versionCode` 갱신(§3).
3. **`CHANGELOG.md` 업데이트** (§10).
4. **git commit / push** (§9).

이 순서를 **절대 생략하지 않는다.** (가장 중요한 규칙.)

## 3. 버전 관리 (AI 코딩 에이전트에 일임)

버전 관리는 **전적으로 Claude Code 및 Codex 등 AI 코딩 에이전트가 자동 수행**한다. 사용자가 수동으로 버전을 올리지 않으며,
**코드를 수정할 때마다** 현재 작업 중인 AI 코딩 에이전트가 아래 규칙으로 버전을 갱신한다. 갱신은
`app/build.gradle(.kts)` 의 `versionName`/`versionCode` 에 반영하고 같은 커밋에 포함한다.

### 3.1 versionName = `메이저.마이너.패치` (SemVer)

변경 내용에 따라 AI 코딩 에이전트가 자율 결정:

- **MAJOR**: Breaking change (공개 API 제거/시그니처 변경, 데이터 스키마 비호환, 파괴적 워크플로 변경, 0.x→1.0.0 최초 프로덕션).
- **MINOR**: 하위호환 신규 기능/화면/설정 추가, 수준 있는 UX 개편.
- **PATCH**: 하위호환 버그 수정·성능/보안 개선·리팩토링·UI 미세 조정·문서 갱신.
- **기본값은 PATCH ++1** — 매 코드 수정마다 최소 patch 를 올린다(더 큰 변경이면 minor/major).
- 0.x(pre-1.0) 에서는 파괴적 변경도 MINOR 로 처리 가능 (SemVer 2.0.0 §4).

### 3.2 versionCode = `YYMMDDRRR` (9자리, 예: `260531001`)

- **매 코드 수정마다 `RRR` 을 +1.**
- **날짜가 바뀌면 `RRR` 은 `001` 부터 다시 시작** (예: 5/31 마지막이 `260531007` 이면, 6/1 첫 수정은 `260601001`).
- 같은 날 두 번째 수정 = `...002`, 세 번째 = `...003` …

### 3.3 표시

- **앱 내 버전 표시**는 `BuildConfig` 참조 — 화면/코드에 버전 문자열 하드코딩 금지.

## 4. 디버그 빌드 패키지명

- 디버그 빌드는 **릴리즈 applicationId 에 `.debug` 서픽스**를 붙인다 — `buildTypes.debug { applicationIdSuffix = ".debug" }`.
  → 릴리즈와 디버그를 한 기기에 동시 설치 가능, 충돌 방지.
- **릴리즈 applicationId 는 원본 그대로** 유지(변경 금지).
- 키스토어 파일명은 **릴리즈 applicationId 기준**(`<applicationId>.keystore` / `<applicationId>-debug.keystore`).
  즉 디버그 변형의 appId 는 `<applicationId>.debug` 이지만, 서명 키 파일은 `-debug.keystore` 를 사용.

## 5. 서명 키스토어 — 프로젝트별 파일 위치

Android 서명 키스토어는 **호스트 영속 볼륨** `/home/vibe/keystores/` 에 **applicationId 를 prefix**
로 저장되어 있다(운영자가 vibe-coder 서버 UI 에서 미리 생성). 아래 `<applicationId>` 를
`app/build.gradle(.kts)` 의 실제 applicationId 로 치환:

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
  생성하도록 안내한다. AGP 의 default `debug.keystore` 자동 생성도 금지. (§11 "키스토어 내부 저장 금지")

### AdMob 광고 ID 파일 키 스키마 (필수 준수)

하드코딩된 AdMob 광고 ID 를 `<applicationId>-admob.properties` 로 이동배치할 때는 **반드시 아래
표준 키**를 사용한다. (vibe-coder 서버의 키스토어 → AdMob ID 섹션이 이 키 규약으로 조회·표시한다.
매번 다른 키 형식(`admob.bannerId` / `release.admob_banner_id` 등)을 임의로 만들지 말 것.)

- **운영(실) ID 만** 이 파일에 둔다. 키:
  - `admobAppId=ca-app-pub-XXXX~YYYY`
  - `appOpenAdUnitId` / `bannerAdUnitId` / `nativeAdUnitId` / `interstitialAdUnitId` /
    `rewardedAdUnitId` / `rewardedInterstitialAdUnitId` = `ca-app-pub-XXXX/ZZZZ` (다중은 콤마 구분)
- **테스트 ID(Google 공식 `ca-app-pub-3940256099942544/…`)는 이 파일에 넣지 말 것.** 디버그 빌드의
  테스트 광고는 build.gradle 의 debug variant 에서 Google 공식 테스트 상수로 직접 처리한다.
- build.gradle 은 이 파일을 `Properties` 로 로드해 manifestPlaceholders(App ID) + buildConfigField
  (unit ID, `split(",")`)로 주입한다. 광고 ID 평문을 소스에 하드코딩하지 말 것.

## 6. 빌드 환경

### 6.1 ⚠ 사전 설치된 도구 (재다운로드 금지)

vibe-coder-server 의 `/env-setup` 또는 `vibe-doctor install` 이 이미 설치해 둔 도구가 있다.
**새로 다운로드 받지 말고 반드시 아래 경로의 사전 설치본을 그대로 사용한다.**

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

### 6.2 Gradle 정책 (핵심)

**Gradle 버전을 문서/명령에 하드코딩하지 않는다.** 시스템 설치본과 사전 캐싱된 wrapper dist 는
시점에 따라 달라지므로(업그레이드 가능), 빌드 전에 **실제 상태를 먼저 조회**해서 그 값에 맞춘다.
이렇게 하면 gradle 이 올라가도 문서 수정 없이 자동 대응된다.

```bash
# 1) 시스템에 설치된 gradle 버전 확인
gradle --version            # 예: "Gradle 9.5.1" → INSTALLED_VER=9.5.1
# 2) 사전 캐싱된 wrapper dist 확인 (이미 받아둔 버전들)
ls /home/vibe/.gradle/wrapper/dists/   # 예: gradle-9.5.1-bin
```

프로젝트의 `gradle/wrapper/gradle-wrapper.properties` 가 **설치본/캐시에 없는 버전을 가리키면
새 버전을 무단 다운로드하지 않는다.** 다음 우선순위로 처리한다:

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

### 6.3 Android SDK 정책

프로젝트의 `local.properties` 에 `sdk.dir` 이 호스트 경로로 박혀 있을 수 있다. 컨테이너에서는
`local.properties` 를 **생성/수정하지 말고 그대로 두거나 삭제** 한다. `ANDROID_HOME` 환경변수가
우선되므로 SDK 위치는 자동 인식된다.

부족한 platform / build-tools 는 `sdkmanager` 로 설치하되, **메이저 버전이 아닌 마이너 버전
변경은 사용자에게 먼저 확인** 한다.

```bash
# 설치된 패키지 확인
sdkmanager --list_installed
# 누락분만 설치 (버전은 프로젝트 요구에 맞춰)
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

### 6.4 환경변수 (이미 컨테이너 entrypoint 에서 설정됨)

```
ANDROID_HOME=/opt/android-sdk
ANDROID_SDK_ROOT=/opt/android-sdk
JAVA_HOME=/opt/java/openjdk
PATH=/home/vibe/.local/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/java/openjdk/bin:...
```

빌드 스크립트나 IDE 설정에서 이 값을 **덮어쓰지 않는다.**

## 7. 설계 원칙 / 코드 품질

- **객체지향(OOP) 기반** 설계.
- **기능 모듈화** — 역할별로 분리, 단일 책임 원칙.
- **의존성 최소화** — 불필요한 라이브러리/결합 지양.
- **캡슐화 철저** — 내부 구현 은닉, 공개 표면(API) 최소화.
- **유지보수성 최우선** — 읽기 쉽고 변경하기 쉬운 코드.
- **레거시 코드 절대 방치 금지 — 발견 즉시 제거.** (주석 처리한 죽은 코드, 미사용 함수/클래스/리소스/import 포함.)

## 8. 파일 및 네이밍 규칙

- **명확한 이름 사용** — 의도가 드러나는 이름.
- **`Utils` / `Helper` 남발 금지** — 책임이 모호한 잡동사니 클래스 지양.
- **역할 기반 네이밍** — 무엇을 하는지 기준으로 이름을 짓는다.

## 9. Git 규칙

- **프로젝트 생성 즉시 `git init`.**
- **코드 변경 시 반드시 즉시 commit** — 변경을 쌓아두지 않는다.
- 커밋 메시지는 **작업 단위 기준으로 명확하게** 작성한다(무엇을 / 왜).

## 10. 문서 (변경 시 필수 업데이트)

- 코드/동작 변경 시 다음을 **즉시 갱신**: `CHANGELOG.md`, `README.md`, AI 지침 파일(`CLAUDE.md` / `AGENTS.md`).
- **레거시 문서는 즉시 제거** — 더 이상 맞지 않는 설명/예시를 방치하지 않는다.

## 11. 절대 금지

- **하드코딩** (값/경로/비밀/버전 문자열 등)
- **레거시 방치** (죽은 코드/문서)
- **커밋 누락**
- **문서 미갱신**
- **키스토어 프로젝트 내부 저장** (반드시 `/home/vibe/keystores/`)
- 사전 설치된 도구를 무시하고 다른 경로/버전 재다운로드
- `/opt/*` 또는 `/home/vibe/.gradle/`, `/home/vibe/.local/` 의 임의 삭제
- `local.properties` 에 호스트 경로 하드코딩
- Wrapper 버전 변경 시 사유 미기재
- **에뮬레이터/기기 실행·스크린샷 검토를 사용자 명시 없이 자율 실행** (대량 토큰·시간 소모 — §14)

## 12. 최종 원칙

모든 판단 기준: **유지보수성 · 확장성 · 일관성 · 자동화 가능성.**

## 13. Android Compose UI 함정 (재발 방지 — 반복 발생 패턴)

실제로 반복 발생해 수정한 UI 버그 패턴. **새 화면/네비게이션을 만들거나 inset 관련 코드를 손댈 때 아래를 먼저 점검한다.**

### 13.1 ⚠ WindowInsets 이중 적용 — 헤더 위 빈 여백 (가장 자주 발생)

**증상:** 각 탭/화면의 `TopAppBar`(헤더) 위에 상태바 높이만큼 불필요한 빈 띠가 생긴다.

**원인:** **inset 을 두 번 적용**. 대표적으로 **중첩 Scaffold** —
바깥 `Scaffold`(보통 `bottomBar`/`NavigationBar` 호스팅, `topBar` 없음)가 `enableEdgeToEdge` 환경에서
`innerPadding.top`(=상태바 높이)을 콘텐츠(NavHost)에 적용하고, 그 안의 화면별 `Scaffold` + `TopAppBar`
가 **또** 상태바 inset 을 적용한다.

**규칙 (inset 은 한 번만, 소유자를 명확히):**
- **상단(상태바) inset 의 소유자는 화면의 `TopAppBar`**. 바깥 Scaffold 는 NavHost 에 `top` 을 적용하지
  말고 **좌/우/하단만** 적용한다.
  ```kotlin
  Scaffold(bottomBar = { ... }) { inner ->
      val ld = LocalLayoutDirection.current
      NavHost(..., modifier = Modifier.padding(
          start = inner.calculateStartPadding(ld),
          end = inner.calculateEndPadding(ld),
          bottom = inner.calculateBottomPadding()   // top 은 의도적으로 제외
      ))
  }
  ```
- `TopAppBar` 가 **없는** 화면(예: 홈)만 루트에 `Modifier.statusBarsPadding()` 으로 상태바를 직접 비킨다.
- 동일 논리로 **하단 inset 도 한 곳에서만**: 바깥 Scaffold 가 `bottomBar` 를 가지면 하단 네비 inset 은 바깥이
  소유. 안쪽 화면 Scaffold 가 또 `navigationBars` inset 을 더하지 않는지 확인.

**점검 체크리스트 (새 화면/네비 추가 시):**
1. Scaffold 가 **중첩**되어 있는가? → 그렇다면 각 system-bar inset 의 **소유자가 정확히 하나**인지 확인.
2. `innerPadding` 을 `Modifier.padding(innerPadding)` 으로 **통째 적용**하면서 안쪽에 `TopAppBar` 가 또 있는가? → top 이중.
3. `statusBarsPadding()` / `systemBarsPadding()` 과 `TopAppBar`(기본 status-bar inset)를 **동시에** 쓰지 않는가?
4. `enableEdgeToEdge()` 사용 시, inset 을 소비하는 지점을 화면당 한 번으로 한정했는가?

### 13.2 보조 점검 (정책 일반과 연계)

- 새 색상/치수/문자열은 **테마·리소스에 정의 후 참조**(하드코딩 금지, §11). 화면 간 의미 색(수입/지출 등)은 **단일 정의 재사용**(일관성).
- 화면은 **로딩·빈 데이터·오류 상태**를 모두 처리. 색상 단독으로 상태 구분 금지(텍스트/아이콘 병행).
- 목록/상태 수집은 생명주기 인지(`collectAsStateWithLifecycle`)로 백그라운드 작업 최소화.

---

## 14. ⚠ 에뮬레이터 실행·스크린샷 검토는 자율 실행 금지

앱 정상 실행 확인을 위한 **에뮬레이터/기기 실행, 앱 설치·구동, 스크린샷 촬영·검토**는
**대량의 토큰과 시간을 소모**하는 작업이다. 따라서:

- **사용자가 명시적으로 요청한 경우에만** 수행한다. (예: "에뮬레이터로 실행해줘", "스크린샷 찍어줘",
  "실기기에서 확인", "런타임 검증" 등 명확한 지시.)
- **사용자 명시 없이 자율 판단으로 실행하지 않는다.** §2의 "빌드 테스트"는 **컴파일·빌드·단위 테스트까지**이며,
  런타임 구동 검증을 포함하지 않는다. 코드 변경의 기본 검증은 빌드+단위 테스트로 충분하다고 간주한다.
- 런타임 확인이 가치 있다고 판단되면 **실행하지 말고**, 응답에서 "에뮬레이터/실기기 검증 권장"으로 **제안만**
  하고 사용자 선택을 기다린다(§비인터랙티브 규칙과 동일하게 다음 프롬프트에서 결정).
- 공유 에뮬레이터 환경에서는 다른 프로젝트와 경합이 발생할 수 있어 비용 대비 효용도 낮다.

---

## 15. ⚠ Context7 공식 문서 선확인 (Compose / Flutter / AdMob / Insets)

- **Compose, Flutter, AdMob, WindowInsets, Scaffold, SafeArea 관련 코드를 수정하기 전에는
  반드시 Context7 MCP 로 현재 사용 중인 버전의 공식 문서를 확인한 뒤 구현한다.**
- 이 영역들은 버전 간 API 변화(deprecated/이동/기본 동작 변경)가 잦아, 기억에 의존해 구현하면
  §13 류의 inset 이중 적용이나 광고 SDK 정책 위반 같은 회귀가 재발한다. 문서로 현재 버전의
  권장 패턴을 확인하고 그 패턴대로 구현한다.
- Context7 MCP 가 등록되어 있지 않으면 문서 확인 없이 임의로 진행하지 말고, 사용자에게
  vibe-coder 서버의 MCP 카탈로그(`/env-setup/mcp`)에서 Context7 설치를 안내한다.
