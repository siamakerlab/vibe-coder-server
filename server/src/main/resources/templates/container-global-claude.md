# vibe-coder-server 컨테이너 글로벌 규칙

이 파일은 컨테이너 안의 `/home/vibe/.claude/CLAUDE.md` 로 마운트되어, 컨테이너에서 실행되는 모든 Claude Code 세션에 글로벌로 적용된다. 호스트 경로는 `./vibe-coder-data/claude/CLAUDE.md`.

> 이 파일은 서버 최초 기동 시 자동 시드된 기본 템플릿이다. 자유롭게 수정해도 되며,
> 한 번 존재하면 서버가 다시 덮어쓰지 않는다. (전역 CLAUDE.md 탭: `/settings/claude-md`)

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

## 절대 금지

- 사전 설치된 도구를 무시하고 다른 경로/버전 재다운로드
- `/opt/*` 또는 `/home/vibe/.gradle/`, `/home/vibe/.local/` 의 임의 삭제
- `local.properties` 에 호스트 경로 하드코딩
- Wrapper 버전 변경 시 사유 미기재
