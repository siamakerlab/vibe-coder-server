# Vibe Coder — Server

> **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만들어내는
> "외부 접근 가능한 개발머신" 그 자체. 서버 PC 에 도커 컨테이너 하나
> 띄우면 브라우저로 바로 로그인해서 프로젝트 생성 · 프롬프트 전송 ·
> Gradle 빌드 · APK 다운로드까지 끝낸다.
>
> 안드로이드 앱(`vibe-coder-android`, 별도 리포) 은 같은 서버를 가리키는
> **부가 클라이언트** 일 뿐, 없어도 모든 기능을 사용할 수 있다.

이 리포는 그 도커 앱의 본체(Ktor 서버) 와 운영용 웹 UI 를 보유한다.
Claude Code CLI / Gradle Wrapper / Git CLI / 파일 관리 자식 프로세스를
모두 서버가 소유하고, 로그는 WebSocket 으로 모든 클라이언트(브라우저 ·
Android 앱) 에 스트림한다.

## 리포 구성

```
vibe-coder-server/
├─ shared/              # JVM 라이브러리 — @Serializable DTO / ApiPath / WsFrame
├─ server/              # Ktor 서버 (Netty), SQLite(Exposed),
│                       #   Claude/Gradle/Git 자식 프로세스, WS 로그 허브,
│                       #   Admin 웹 (SSR HTML)
└─ docker/              # 슬림 Docker 이미지 + compose + vibe-doctor
```

**짝 리포**: `vibe-coder-android` (별도 git 저장소).
주소: `ssh://git@gitea.wody.kr:2929/wody/vibe-coder-android.git`
두 리포는 `:shared` 모듈의 동일 사본을 통해 wire-level 호환을 유지합니다
(`ApiPath` / DTO / `WsFrame` 변경 시 양쪽 함께 갱신).

## 빌드 매트릭스

| Layer | Version |
|---|---|
| Gradle wrapper | 9.5.1 |
| Kotlin | 2.2.20 |
| Ktor | 3.1.2 |
| Exposed | 0.55.0 |
| SQLite JDBC | 3.46.1.3 |
| JDK toolchain | 17 |

## 빌드 / 실행

### 로컬 실행

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server --workspace ./workspace
```

부팅 후 콘솔에 다음이 표시됩니다:

```
>>> Vibe Coder Server started
>>> URL         : http://192.168.0.10:17880
```

브라우저로 그 URL 에 접속하면 곧바로 셋업 화면(첫 부팅) 또는 로그인
화면(이미 설정됨) 이 뜬다. 안드로이드 앱(`vibe-coder-android`) 도
같은 URL + username/password 로 로그인할 수 있지만 필수는 아니다.

### 웹만으로 끝낼 수 있는 작업 (v0.6.0+)

| 경로 | 용도 |
|---|---|
| `/` | 대시보드 (서버/환경/활동 요약) |
| `/projects` | 프로젝트 목록 + 새 프로젝트 등록 |
| `/projects/{id}` | 프로젝트 상세, 최근 빌드 |
| `/projects/{id}/console` | Claude 프롬프트 입력 + 실시간 로그 (WebSocket) + 슬래시 chip |
| `/projects/{id}/builds` | Debug 빌드 큐 등록 + APK 다운로드 |
| `/projects/{id}/builds/{buildId}` | 빌드 상세 + 실시간 로그 (WebSocket) + 취소 |
| `/projects/{id}/files` | 파일 업로드 / 다운로드 / 삭제 |
| `/projects/{id}/git` | git status / diff / log (읽기 전용) |
| `/env-setup` | 빌드환경 (JDK / SDK / Claude 로그인) 상태 + 설치 안내 |
| `/settings` · `/devices` · `/password` | 운영 설정 / 디바이스 / 비밀번호 |
| `/login` · `/setup` · `/logout` | 인증 |

v0.4.2 부터 별도 `/admin/*` prefix 없이 모두 루트 바로 아래에 평탄화되어
있다. 구버전 `/admin/...` 경로는 영구 리다이렉트 호환층으로 유지된다
(v0.6.0 에서 제거 예정).

### Docker 실행

#### compose.yml 한 줄 quick-start

```bash
mkdir -p ~/vibe-coder && cd ~/vibe-coder

curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/.env.example -o .env

# .env 에서 PUID/PGID (id -u; id -g) / 포트 / 볼륨 경로 조정 후
docker compose up -d

# 첫 부팅 후 admin 셋업: 브라우저 → http://<PC IP>:17880/setup
# 그 다음 좌측 nav → 빌드환경 → 안내대로 Android SDK / Claude 로그인.
```

#### 최소 compose 예시 (직접 작성하고 싶다면)

```yaml
name: vibe-coder
services:
  vibe-coder:
    image: siamakerlab/vibe-coder-server:0.6.0
    container_name: vibe-coder
    restart: unless-stopped
    environment:
      PUID: "1000"
      PGID: "1000"
      JAVA_OPTS: "-Xmx2g -XX:+UseG1GC"
    ports:
      - "17880:17880"
    volumes:
      - ./workspace:/workspace                # 프로젝트 소스 + APK 산출물
      - ./vibe-data:/data                     # SQLite DB / 서버 로그
      - vibe-android-sdk:/opt/android-sdk     # Android SDK (named volume)
      - vibe-gradle-cache:/home/vibe/.gradle  # Gradle 캐시 (named volume)
      - ~/.claude:/home/vibe/.claude          # Claude 인증 (호스트 공유)
volumes:
  vibe-android-sdk:
  vibe-gradle-cache:
```

자세한 가이드는 `docker/README.md` 를 참고하세요.

### 빌드환경은 이미지를 갈아끼워도 보존됩니다 ✅

브라우저의 **빌드환경 페이지** (`/env-setup`) 또는 `vibe-doctor` 로 설치하는
**Android SDK / Gradle 캐시 / Claude 인증** 은 모두 **Docker named volume 또는
호스트 bind mount** 에 저장됩니다.

| 데이터 | 마운트 위치 | 이미지 pull / 컨테이너 recreate 시 |
|---|---|---|
| Android SDK (3~4 GB) | `vibe-android-sdk` (named) | ✅ 보존 |
| Gradle 의존성 캐시 | `vibe-gradle-cache` (named) | ✅ 보존 |
| Claude 인증 (`.credentials.json`) | `~/.claude` (호스트 bind) | ✅ 보존 |
| 프로젝트 소스 + APK | `./workspace` (호스트 bind) | ✅ 보존 |
| SQLite DB + 빌드 로그 | `./vibe-data` (호스트 bind) | ✅ 보존 |
| **서버 본체** (Ktor 앱 + Claude CLI + JDK) | 이미지 내장 | 🔄 새 이미지로 교체 |

데이터를 잃지 않고 서버만 업그레이드:

```bash
docker pull siamakerlab/vibe-coder-server:<새 버전>
docker compose up -d --force-recreate
```

⚠️ **`docker compose down -v` 는 named volume 까지 삭제** 합니다 (SDK 3~4GB
다시 다운로드). 일반 업그레이드 시에는 `down -v` 를 쓰지 말고 위의
`up -d --force-recreate` 만 사용하세요.

## 인증 (v0.4.0+)

- `/api/auth/setup` — 첫 부팅 시 admin 계정 생성 (DB 에 admin 이 없을 때만).
- `/api/auth/login` — username + password → bearer token + `vibe_session` 쿠키.
- `/api/auth/password` — 현재 비밀번호 확인 후 변경.
- `/api/auth/pair` — **deprecated** (admin 존재 시 410 응답).

토큰은 BCrypt cost 12 hash 만 DB 에 저장됩니다. 10 회 연속 실패 시
15 분 잠금. timing-attack 대응으로 dummy verify 도 수행.

## 보안 경계 (MVP)

- 워크스페이스 외부 경로 거부 (`PathSafety.normalizeAndCheck`).
- 토큰 hash-only 저장, 클라이언트에는 발급 시점에 1 회만 평문 노출.
- WebSocket 인증은 **첫 메시지** (`{"type":"auth","token":"..."}`) 로 수행 —
  URL 에 토큰을 싣지 않음.
- 업로드 확장자 blacklist: `exe`, `bat`, `cmd`, `ps1`, `sh`.
- raw-shell UI 없음. `git push` / `git reset --hard` / release 서명 없음.
- 모든 외부 명령은 하드 타임아웃 + 취소 시 `destroyForcibly`.

## 베이스라인 결함 회수 이력

분리 시점(v0.4.0 main) 에 보고됐던 컴파일 결함 3건 + 비차단
deprecation 2건 + `.gitignore` 패턴 결함 1건은 모두 v0.4.1 에서 회수.
상세 내용은 `CHANGELOG.md` 의 v0.4.1 섹션 참고.

- `ApkFinder.kt` — Kotlin 2.2 에서 제거된 `kotlin.streams.toList` import +
  KDoc 안의 `/*` 시퀀스가 nested comment 로 해석되는 신택스 에러.
- `ServerActionHandler.kt` — 존재하지 않는 `builds.submitDebug` 호출을
  실제 메소드 `builds.enqueueDebug` 로 정정.
- `auth/AuthPlugin.kt` — Ktor 3.x deprecated `Principal` 인터페이스 제거.
- `files/FileRoutes.kt` — `PartData.streamProvider` → `provider()` 마이그.
- `.gitignore` — `build/` 패턴이 `server/src/.../server/build/` 패키지까지
  무시하여 4 개 핵심 소스 파일이 untracked 상태였음. 패턴을 좁히고 누락
  파일을 정상 등록.
