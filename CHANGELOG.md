# Changelog — vibe-coder-server

All notable changes to the server component will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

v0.4.0 까지는 `vibe-coder` 모노레포의 단일 CHANGELOG 였고, v0.4.1 부터
서버/안드로이드 두 리포로 분리되어 각 리포가 독립 changelog 를 갖는다.
Android 클라이언트 이력은 `vibe-coder-android` 리포의 CHANGELOG 참고.

## [Unreleased]

## [0.10.0] - 2026-05-23

대규모 wire 마일스톤 — v0.7.0~0.9.0 에서 admin SSR 전용으로 추가됐던 6개
기능군이 모두 JSON API 로 이중 노출됨. vibe-coder-android 클라이언트가 같은
기능을 모바일에서 호출 가능. Bearer token 인증 보호.

### Added — v0.9.0: Git clone (지난 minor 통합 보고)

신규 프로젝트 등록 시 git URL 에서 clone 가능. public + private (HTTPS PAT
+ SSH key) 모두 지원.

- `RegisterProjectRequestDto` 에 `sourceType / cloneUrl / cloneBranch` optional
  필드 추가 (default `empty` 라 구버전 클라이언트 호환). Wire 호환 보장.
- `GitCloneService` — `git clone --progress` 자식 spawn + URL 검증 +
  GIT_TERMINAL_PROMPT=0 (stdin hang 차단) + `accept-new` host key + 부분 파일
  자동 정리.
- `GitCredentialStore` — provider 별 PAT 보관 (`~/.config/vibe-coder/git-tokens.json`)
  + git CLI 표준 `~/.git-credentials` 동기 (`credential.helper=store`).
- SSH 키 자동 생성 (ed25519) — 사용자가 공개키를 GitHub/GitLab/Gitea/Bitbucket 에
  등록하면 SSH URL clone 즉시 동작.
- 새 설정 페이지 `/settings/git-integrations` — 토큰 목록(마스킹) +
  SSH 공개키 표시 + 등록/삭제 폼.
- 신규 프로젝트 폼 (`/projects`) 에 sourceType 라디오 + clone URL/branch 입력.

### Added — v0.10.0 핵심: API 이중 노출 (Android wire)

v0.7~0.9 의 신규 기능들이 admin SSR 전용이라 vibe-coder-android 가 호출할
경로가 없던 문제 해결. **6개 도메인의 19개 엔드포인트 추가**:

`shared/.../ApiPath.kt` (29줄 신규):
- `ENV_SETUP_COMPONENTS` (GET), `ENV_SETUP_INSTALL_ALL` (POST),
  `envSetupInstall(id)` (POST), `wsEnvSetupLogs(taskId)` (WS)
- `CLAUDE_AUTH_UPLOAD` (POST multipart), `CLAUDE_AUTH_API_KEY` (POST),
  `CLAUDE_AUTH_API_KEY_DELETE` (DELETE + POST)
- `CLAUDE_LOGIN_START / SUBMIT / STATUS / CANCEL`
- `MCP_CATALOG` (GET), `MCP_INSTALL / UNREGISTER` (POST)
- `GIT_INTEGRATIONS` (GET + POST), `GIT_INTEGRATIONS_DELETE / SSH_KEYGEN` (POST)

`shared/.../Dtos.kt` 신규 DTO 14개:
- `ComponentStateDto`, `EnvSetupComponentsResponseDto`, `EnvSetupTaskDto`
- `ClaudeApiKeyRequestDto`, `ClaudeCredentialsUploadResponseDto`,
  `ClaudeLoginStateDto`, `ClaudeLoginSubmitRequestDto`
- `McpConfigFieldDto`, `McpEntryDto`, `McpCatalogResponseDto`,
  `McpInstallRequestDto`, `McpUnregisterRequestDto`
- `GitTokenViewDto`, `GitIntegrationsResponseDto`, `GitTokenRegisterRequestDto`,
  `GitTokenDeleteRequestDto`

`server/.../env/EnvSetupApiRoutes.kt` (신규, ~220줄):
- 모든 신규 API 라우트가 한 파일에 그룹화 (`authenticate(AUTH_BEARER)` 보호).
- 같은 service 인스턴스 (EnvSetupService / ClaudeAuthService / ClaudeLoginService
  / McpService / GitCredentialStore / GitCloneService) 를 SSR 라우트와 공유.
- 도메인 객체 → DTO 매핑 helper 포함.

### Wire change

**예** — v0.10.0 이 vibe-coder-android 와 새 API 계약을 맺습니다. Android
클라이언트는 새 ApiPath 상수 + DTO 를 동기화해야 신규 기능 호출 가능. 구버전
Android (`/api/projects/register` 만 호출) 는 여전히 동작 (DTO optional 필드).

### 배포

- `siamakerlab/vibe-coder-server:0.10.0` multi-arch push 예정.

## [0.8.1] - 2026-05-23

### Changed — 베이스 이미지 Ubuntu 22.04 (jammy) → 24.04 LTS (noble)

`eclipse-temurin:17-jdk-jammy` / `17-jre-jammy` → `17-jdk-noble` / `17-jre-noble`.
**서버 코드 무변경**, base OS 메이저 업그레이드만. 4년 더 긴 보안 업데이트
수명 + 최신 glibc/libstdc++ 확보. Ubuntu 26.04 LTS 의 eclipse-temurin 매핑이
아직 확정되지 않아 24.04 로 전환 (26.04 가용 시점에 다음 마이너에서 재검토).

### Fixed — Ubuntu 24.04 base 의 default `ubuntu` 사용자 UID 1000 충돌

Ubuntu 24.04 (noble) 부터 base image 에 default `ubuntu` 사용자가 UID/GID 1000
으로 사전 생성되어 있어 `groupadd --gid 1000 vibe` 가 exit 4 로 실패. 빌드
첫 시도에서 발견 후 `userdel -r ubuntu` + `groupdel ubuntu` 를 vibe 사용자
생성 전에 수행하도록 Dockerfile 수정. 다른 base (jammy/alpine 등) 에서는
사용자 없음 → 조용히 통과 (`|| true`).

### Verification

빌드 + 런타임 도구 검증:
```
PRETTY_NAME="Ubuntu 24.04.4 LTS"
java 17.0.19, node v20.20.2, npm 10.8.2, git 2.43.0,
claude 2.1.150, script (util-linux) 2.39.3, sudo NOPASSWD ok,
npm prefix = /home/vibe/.local (v0.7.0 bind mount 정책 그대로)
```

### Wire change

**없음.** server.yml 0.8.0 → 0.8.1 (base OS patch).

### 배포

- `siamakerlab/vibe-coder-server:0.8.1` multi-arch push 예정.
- 사용자 영향: `docker compose pull && up -d --force-recreate` 하면 자동
  교체. 호스트 PUID/PGID 1000 매칭은 그대로 (vibe 사용자 UID/GID 동일).
- 기존 `:0.8.0` 사용자가 그대로 두어도 동작 — 보안 업데이트만 받으려면 업그레이드.

## [0.8.0] - 2026-05-23

### Added — MCP 카탈로그 페이지 (체크박스 다중 선택 + 50+개 + 토큰 입력)

`/env-setup/mcp` 신규 페이지. 기존 빌드환경 페이지의 "기본 MCP 묶음" 카드를
체크박스 다중 선택 + 카테고리별 그룹 + per-MCP 토큰 입력 UI 로 대체.

**파일** (모두 신규):
- `server/.../env/McpCatalog.kt` — 50+개 MCP 정적 메타데이터.
  Trust tier (VERIFIED / COMMUNITY / EXPERIMENTAL), category, recommended,
  configFields (TOKEN/URL/DSN 등) 정의.
- `server/.../env/McpService.kt` — 일괄 설치 (npm install -g + `.mcp.json`
  엔트리 등록), 제거 (엔트리만 삭제 — npm 패키지는 디스크 보존), 상태 진단
  (`npm ls -g` + `.mcp.json` 교차).
- `server/.../admin/McpRoutes.kt` — GET `/env-setup/mcp`, POST
  `/env-setup/mcp/install`, POST `/env-setup/mcp/unregister`. 진행은 기존
  `/env-setup/tasks/{taskId}` 페이지 (라이브 로그 + 경과 시간) 재사용.
- `server/.../admin/McpTemplates.kt` — 카테고리별 카드 그룹. 추천 항목 ★,
  trust chip, status chip, 체크박스 선택 시에만 토큰 입력란 노출,
  sticky bottom bar 의 "선택 항목 설치 / 제거" 버튼.

**카탈로그 (10 카테고리, 50+개)**:
- **DEV_TOOLS** — filesystem ★, fetch ★, git ★, memory ★, sequential thinking,
  time, everything
- **GIT_HOSTING** — GitHub ★ (PAT), GitLab (PAT+URL), Gitea (URL+PAT),
  Bitbucket (App PW), Azure DevOps (PAT)
- **DATABASE** — SQLite ★, Postgres (DSN), MySQL, MongoDB (URI), Redis,
  Elasticsearch, Supabase (URL+Key), Firebase (SA JSON)
- **SEARCH** — Brave ★ (API key), Tavily, Perplexity, Firecrawl, Google Maps,
  Context7 ★
- **BROWSER** — Playwright ★, Puppeteer
- **PRODUCTIVITY** — Notion ★ (Integration token), Linear, Jira, Confluence,
  Slack (Bot token), Discord, Trello, Asana, ClickUp, Airtable, Monday.com,
  Google Drive, Obsidian
- **CLOUD** — AWS KB (credentials), Cloudflare (API token), Vercel, Heroku,
  Railway, Docker Hub
- **COMMS** — SendGrid, Twilio (SID+token), Telegram Bot, Stripe (Secret),
  Sentry
- **APP_PUBLISH (Experimental)** — Google Play Publisher (SA JSON),
  App Store Connect (.p8), Fastlane
- **AI_ASSIST** — OpenAI Bridge, YouTube Transcript, Wikipedia, ArXiv,
  Everart (이미지)

**Trust tier 의미**:
- `VERIFIED` — Anthropic/1st-party 공식 — 패키지명 안정적
- `COMMUNITY` — 인기 3rd party — 패키지명 변동 가능성
- `EXPERIMENTAL` — 패키지명 미확정 / 설치 실패 가능 — 카탈로그에서 직접 수정 권장

**카탈로그에 없는 MCP 안내**: 페이지 하단에 `docker exec -it --user vibe
vibe-coder-server bash` 안내 + `npm install -g <pkg>` + `.mcp.json` 직접 편집
예문. v0.7.0 의 `/home/vibe/.local` (npm-global) + `/home/vibe/.claude` bind
mount 덕에 직접 설치한 MCP 도 이미지 업그레이드 후 영구 보존됨을 명시.

### Changed — 기존 MCP_DEFAULTS 카드 → 카탈로그 페이지 링크

빌드환경 페이지(`/env-setup`)의 "기본 MCP 서버 묶음" 카드 버튼이 단일
설치 → "MCP 카탈로그 열기 (50+)" 링크로 교체. 기존 vibe-doctor mcp
서브커맨드는 호환성을 위해 그대로 유지 (CLI 사용자용).

### Internal

- `Module.kt` / `ServerMain.kt` — `McpService` DI + `mcpRoutes` 등록.
- `McpCatalog.kt` 의 KDoc 안 `modelcontextprotocol/*` 시퀀스가 nested
  comment 로 잡히던 컴파일 결함 (v0.4.1 ApkFinder.kt 와 같은 패턴) 사전
  발견 후 회피.

### Wire change

**없음.** 신규 라우트(`/env-setup/mcp*`)는 admin SSR 전용. ApiPath / DTO /
WsFrame 무변경 → Android 클라이언트 무영향.

### 배포

- Docker 이미지 재빌드 필요 (서버 코드 변경) — `siamakerlab/vibe-coder-server:0.8.0`
  multi-arch (amd64+arm64) push 예정.
- 기존 `:0.7.0` 이미지 사용자는 그대로 동작 (catalog 라우트만 없을 뿐).
  업그레이드 시 `docker compose pull && up -d --force-recreate`.

## [0.7.1] - 2026-05-23

문서 + 운영 메타 patch. 이미지 무변경 (서버 코드 변경 없음 — `0.7.0` 이미지
재사용 가능).

### Added — AGPL-3.0 LICENSE + 공개 오픈소스 전환

- `LICENSE` 파일 추가 (GNU Affero GPL v3 전문). copyleft 강화 — SaaS 운영자가
  수정본 소스 공개 의무. 상업적 사용은 가능하나 의무 인지 필요.
- `README.md` 에 라이센스 섹션 + AGPL/Docker badge 추가.
- `Dockerfile` LABEL `licenses` 를 `UNLICENSED` → `AGPL-3.0-or-later`,
  `source` 라벨에 GitHub 리포 URL 추가.

### Changed — Git 리모트 GitHub 공개 전환

- `origin` 을 gitea 자체 호스팅 → `https://github.com/siamakerlab/vibe-coder-server.git`
  로 교체. 기존 gitea 리모트는 `gitea` 이름으로 보존 (병행 mirror 가능).
- `docker/HUB_README.md` / `docker/README.md` 의 source URL 도 신 리포 경로로 동기.

### Changed — 컨테이너명 `vibe-coder` → `vibe-coder-server` 통일

운영자가 다른 vibe 관련 컨테이너(`vibe-coder-android` 등)와 혼동하지 않도록
명시적 풀네임 사용.

- `docker/compose.yml` — `services` 이름 + `container_name` 모두 `vibe-coder-server`.
- 모든 문서/코드의 `docker exec -it vibe-coder ...` → `docker exec -it
  vibe-coder-server ...` 일괄 정정. 영향 파일:
  - `README.md`, `docker/README.md`, `docker/HUB_README.md`
  - `docker/doctor/vibe-doctor`, `docker/doctor/lib/claude-auth.sh`
  - `server/src/.../EnvDiagnostics.kt`, `EnvSetupTemplates.kt`, `AdminTemplates.kt`,
    `WebProjectTemplates.kt`, `ConsoleRoutes.kt`

### Changed — README.md docker 설치 섹션 보강

- v0.7.0 통합 구조 (`./vibe-coder-data/`) 기준 compose 예문으로 재작성.
- "자주 쓰는 운영 명령" 섹션 추가 (logs / restart / exec / pull / recreate).
- 백업/이전 한 줄 명령 (tar + scp + ssh up-d) 추가.
- 라이센스 정보 + Docker Hub badge 헤더에 노출.

### Wire change

**없음.** 서버 코드 로직 무변경 — `:server:installDist` 본체는 v0.7.0 과 동일.
새 docker image 빌드 불요 (단, 라벨 메타 최신화를 원하면 v0.7.1 태그로 재빌드 가능).

## [0.7.0] - 2026-05-23

대규모 운영 편의성 개선 마일스톤. 네 가지가 한 릴리스에 묶였습니다.

1. **Claude 웹 로그인 (터미널 접근 불가 대응)** — 자격증명 파일 업로드 + API 키 모드.
2. **Docker 볼륨 통합 구조** — `/dev-tools` 패턴, 이미지 업그레이드 시 MCP 등이 사라지던 데이터 손실 버그 fix.
3. **설치 진행 UI 개선** — 의미 없던 라인수 progress bar → 경과 시간 + 상태.
4. **신규 프로젝트 CLAUDE.md** — vibe-coder 의 비인터랙티브 환경 룰 자동 삽입.

### Added — Claude 웹 로그인 옵션 A (반자동 OAuth)

세션 도중 추가 — 브라우저만으로 `claude auth login` OAuth 흐름을 완료할 수
있는 가장 부드러운 UX. 옵션 B(파일 업로드) / C(API 키) 와 달리 다른 머신이나
사전 작업이 전혀 필요 없습니다.

- **구현** — `ClaudeLoginService.kt` (신규, ~265줄). claude CLI 2.1.150 분석
  결과 `claude auth login` 이 TUI 라 일반 ProcessBuilder spawn 으로는 stdout
  이 비어 있고 stdin 도 무시됨을 확인. **Linux 표준 도구 `script -q -c "claude
  auth login" /dev/null`** 으로 pty 를 wrap 하면 정상 동작 (JNA/pty4j dep
  불필요). stdout 에서 URL 정규식 캡처 + stdin 으로 사용자 코드 한 줄 write.
  ANSI escape sequence 제거 helper 도 포함 (ESC()-prefix 만 한정 매칭해
  URL 안의 일반 문자 손상 방지).

- **상태 머신** — IDLE → STARTING → AWAITING_CODE → VERIFYING → DONE/FAILED/CANCELED.
  watcher 가 자식 프로세스 exit + `.credentials.json` 존재 여부로 성공 판정 — 가장
  신뢰 가능한 신호. 한 번에 하나의 세션만 (mutex), 동시 시도 시 409.

- **UI** — `/env-setup/claude-login` 전용 페이지 (`EnvSetupTemplates.claudeLoginPage`).
  1초 폴링으로 상태 갱신 (XMLHttpRequest 가 아니라 fetch + JSON). 사용자에게
  보이는 것은 **단순 폼 3개**: (1) 로그인 시작 버튼, (2) 캡처된 URL + "새 탭에서
  열기" 링크 + 복사 버튼, (3) authorization code paste 입력란.
  **터미널 에뮬레이터(xterm.js)는 사용하지 않음** — pty 는 서버 내부 디테일이며
  사용자가 임의 shell 명령을 칠 수 있는 UI 가 아니라 정해진 OAuth 코드 한 줄만
  입력하는 폼이라 CLAUDE.md §3 정책 위반 아님. CLAUDE_AUTH 카드에 "옵션 0 —
  웹에서 한 번에 로그인 (v0.7.0 신규)" 강조 배너로 노출.

- **라우트** (`EnvSetupRoutes.kt`):
  - `GET  /env-setup/claude-login` — 진행 페이지 SSR
  - `POST /env-setup/claude-login/start` — 세션 spawn
  - `POST /env-setup/claude-login/submit` — 코드 제출
  - `POST /env-setup/claude-login/cancel` — 세션 취소
  - `GET  /env-setup/claude-login/status.json` — 폴링용 JSON 상태

- **Dockerfile** — `util-linux` 패키지 명시 추가 (`script` 명령 보장).
  Ubuntu jammy base 에 기본 포함되지만 운영 안정성을 위해 명시.

### Added — Claude 웹 로그인 (옵션 B + C)

`docker exec` 터미널 접근이 불가능한 환경(원격 호스팅, 모바일 운영) 에서도
Claude 인증을 완료할 수 있게 두 가지 웹 경로를 추가. 모두 `CLAUDE.md §3`
의 "raw shell UI 금지" 정책을 위반하지 않습니다.

- **옵션 B — `.credentials.json` 업로드** (`POST /env-setup/claude-auth/upload`)
  다른 머신에서 `claude login` 후 받은 파일을 그대로 멀티파트 업로드.
  JSON 파싱 + `claudeAiOauth.expiresAt` 만료 검증 + 기존 파일 자동 백업
  (`.credentials.json.bak.<ts>`) + atomic move + Posix 0600 권한.

- **옵션 C — `ANTHROPIC_API_KEY` 모드** (`POST /env-setup/claude-auth/api-key`)
  OAuth 대신 API 키. `${CLAUDE_CONFIG_DIR}/.env.api-key` 에 0600 저장.
  새 헬퍼 `ClaudeProcessEnv.applyApiKey()` 가 모든 claude 자식 프로세스
  spawn 시점(`ClaudeSessionManager` + `ClaudeStatusService`) 에서 환경변수로
  주입. 컨테이너 재기동 불필요. 진단 (`EnvDiagnostics` + `EnvSetupService`)
  은 API 키 모드면 OAuth 검사보다 우선해 OK 판정.

- **UI** — `EnvSetupTemplates.CLAUDE_AUTH` 카드를 3-옵션 `<details>` 구조로
  확장 (옵션 1 터미널 / 옵션 2 파일 업로드 / 옵션 3 API 키). flash 알림
  (`?claude=uploaded|api-key|api-key-deleted`) 추가.

- **옵션 A (반자동 웹 OAuth) 도 같은 v0.7.0 안에 추가** — 위 별도 섹션 참고.

### Fixed — Docker 볼륨 통합: 이미지 업그레이드 시 MCP 가 사라지던 버그

**증상**: `docker compose pull && up -d --force-recreate` 후 `vibe-doctor mcp`
로 설치한 MCP 서버들이 통째로 사라짐. Android SDK / Gradle 캐시는 named
volume 이라 보존됐지만, MCP 는 시스템 디렉토리(`/usr/local/lib/node_modules`) 에
설치되어 이미지 layer 안에만 존재했음.

**원인**:
- `docker/doctor/lib/mcp.sh:46` 의 `npm install -g $pkg` 가 시스템 prefix 사용.
- `/home/vibe/.npm`(npx 캐시), `/home/vibe/.cache/ms-playwright`(브라우저),
  `/home/vibe/.config` 도 마운트되지 않아 같은 위험.

**수정**:
- Dockerfile — vibe 사용자의 npm prefix 를 `/home/vibe/.local` 로 분리
  (`~/.npmrc`). `PATH` 에 `/home/vibe/.local/bin` 추가. 새 디렉토리들
  (`.npm`, `.cache/ms-playwright`, `.local/{bin,lib/node_modules}`)
  사전 생성.
- entrypoint.sh — 같은 디렉토리들을 chown 루프에 추가. `.npmrc` 가 빈
  볼륨에 가려진 경우 idempotent 재생성.
- compose.yml — **통합 데이터 디렉토리 (`./vibe-coder-data`) 패턴으로
  재작성**. named volume 선언 제거. 6개 dev-tools 디렉토리 + workspace
  + server + claude 가 모두 한 부모 아래에 들어가, `tar` 한 줄로 백업/이전
  가능.

```
./vibe-coder-data/
├── workspace/        → /workspace
├── server/           → /data
├── dev-tools/
│   ├── android-sdk/  → /opt/android-sdk
│   ├── gradle/       → /home/vibe/.gradle
│   ├── npm-global/   → /home/vibe/.local        (신규 — MCP 영구 저장)
│   ├── npm-cache/    → /home/vibe/.npm          (신규)
│   ├── playwright/   → /home/vibe/.cache/ms-playwright  (신규)
│   └── config/       → /home/vibe/.config       (신규)
└── claude/           → /home/vibe/.claude
```

**기존 사용자 마이그레이션**: `docker/README.md` 의 "v0.7.0 마이그레이션"
섹션에 두 가지 옵션 (깔끔 재시작 / 기존 named volume → bind mount 복사)
을 단계별 명령으로 제공.

### Changed — 설치 진행 페이지 UI

`/env-setup/tasks/{taskId}` 페이지의 **라인수 기반 progress bar 제거**.
설치 작업은 종료 시점을 예측할 수 없어 line/10 으로 보여주던 막대가 의미
없었고, 사용자에게 잘못된 ETA 신호를 줬음. 다음으로 교체:

- **경과 시간** (`HH:MM:SS`, 1초마다 갱신, tabular-nums 폰트).
- **상태 칩** — 진행 중(▶) / 완료(✓) / 오류(✗) / 연결 끊김(●).
- **마지막 활동 시각** ("방금" / "N초 전" / "종료됨") — 정체 감지 보조.
- 라인 수는 작은 dim 표기로 보조 정보.

### Added — 신규 프로젝트 `.claude/settings.json` 자동 생성 + vibe NOPASSWD sudo

vibe-coder 환경(stream-json, TTY 없음, turn 내 인터랙션 불가) 에 맞게 Claude
Code 동작을 사전 조정. 신규 프로젝트 생성 시 `<root>/.claude/settings.json`
이 함께 떨어집니다 (`ProjectService.kt:64-72`, `ClaudeSettingsTemplate.kt` 신규).

- `permissions.defaultMode = "bypassPermissions"` — 모든 권한 승인 자동.
  `ask` 모드면 vibe-coder 콘솔이 prompt 노출 채널이 없어 세션이 영구 hang.
- `permissions.deny[]` — 인터랙티브 / hang 가능 명령 차단 (vim/vi/nano/emacs,
  top/htop, less/more, `tail -f`, `adb logcat`, `claude login/logout`,
  `gh auth login`, `npm init` -y 없는 형태 등).
- `env` 강제 비대화형: `TERM=dumb`, `CI=1`, `NO_COLOR=1`, `BROWSER=""`,
  `PAGER=cat`, `EDITOR=true`, `VISUAL=true`, `DEBIAN_FRONTEND=noninteractive`.
- **모든 MCP allow** — vibe-doctor 가 실제 설치한 것만 존재하므로 화이트리스트
  불요. 운영자가 필요시 `.claude/settings.local.json` 으로 override.

Dockerfile 에 **vibe 사용자 NOPASSWD sudo** 추가 (`/etc/sudoers.d/vibe-nopasswd`,
`visudo -c` 검증). sudo prompt 가 vibe-coder 스트림에서 hang 을 일으키던
경우를 해소. 컨테이너가 1인 LAN 격리 도구라 도커 레벨에서 외부 신뢰 경계가
이미 분리되어 있어 가능한 정책.

### Added — 신규 프로젝트 CLAUDE.md 에 비인터랙티브 환경 룰 자동 삽입

vibe-coder 의 콘솔은 Claude 자식 프로세스를 stream-json 으로만 통신하므로
화살표 키 / TUI / stdin prompt / watch 모드를 사용자가 응답할 수 없습니다.
신규 프로젝트가 생성될 때 `ProjectService.createProject` 가 쓰는
`ClaudeMdTemplate.CONTENT` 에 **"Non-Interactive Environment" 섹션**
추가 — Claude Code 가 다음을 지키도록:

- TUI / arrow-key menu / `npm init` (without `-y`) / `claude login` 등
  stdin 대기 명령 금지.
- `tail -f`, `adb logcat` 같은 stop condition 없는 무한 명령 금지.
- 사용자 확인이 필요하면 응답 끝에 (A)(B)(C) 옵션 + 권장안으로 적고
  멈춤. 사용자는 **다음 프롬프트** 에서 선택.

영어 + 한국어 요약 병기. 기존 프로젝트의 CLAUDE.md 는 `notExists()` 가드로
덮어쓰지 않음 (마이그레이션 영향 없음).

### Wire change

**없음.** 본 릴리스는 모두 서버 내부 + 운영 환경 변경. `ApiPath` / DTO /
`WsFrame` 미변경 → Android 클라이언트는 별도 업데이트 불요. 단,
`vibe-coder-android` 의 README/docs 도 `:0.6.3` → `:0.7.0` 태그 갱신은
운영 일관성상 권장.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.7.0` (마일스톤 — `linux/amd64
  + linux/arm64` multi-arch).
- 모든 docker 파일 + README + HUB_README 의 태그 동기 (0.6.3 → 0.7.0).

### 사용자 즉시 처치 (v0.6.x → v0.7.0)

```bash
# 1) 최신 compose.yml / .env 로 교체
docker compose down
curl -fsSL .../compose.yml -o compose.yml
curl -fsSL .../.env.example -o .env

# 2) 신구조 부팅 (./vibe-coder-data/ 자동 생성)
docker pull siamakerlab/vibe-coder-server:0.7.0
docker compose up -d

# 3) 브라우저 → 빌드환경 → "모두 설치/업데이트"
#    (또는 기존 named volume 데이터를 복사하려면 docker/README.md 마이그레이션 가이드)

# 4) Claude 인증 (선택 1)
#    - 터미널: docker exec -it --user vibe vibe-coder claude login
#    - 웹:    /env-setup → "옵션 2 자격증명 업로드" 또는 "옵션 3 API 키"
```

## [0.6.3] - 2026-05-23

### Fixed — `docker exec` 가 root 로 떨어져 토큰이 vibe 홈에 안 들어가던 함정

콘솔에서 `Success: Not Logged in. Please run /login` 이 뜨는데, 사용자는
이미 `docker exec -it vibe-coder claude login` 으로 로그인했고 빌드환경
페이지도 "로그인됨" 으로 보였던 사례의 진짜 원인.

**원인**: `docker exec` 의 기본 사용자는 root (Dockerfile 의 `USER` 미설정).
entrypoint 의 `gosu vibe` 강등은 `docker run` 의 ENTRYPOINT 흐름에만 적용
되고 `docker exec` 는 그걸 거치지 않는다. 따라서:

- `docker exec -it vibe-coder claude login` → root 로 실행
- 토큰이 `/root/.claude/.credentials.json` 에 저장
- 서버는 vibe 사용자 (`CLAUDE_CONFIG_DIR=/home/vibe/.claude`) 로 동작 → 못 찾음
- 빌드환경 진단은 `/home/vibe/.claude/` 만 봐서 이전 로그인 흔적(파일은 있음)
  을 보고 OK 판정 → false positive

**수정**:

1. 모든 안내 명령에 `--user vibe` 추가 — 12군데 일괄 정정:
   - `EnvDiagnostics.checkClaudeAuth` / `buildClaudeAuthHelp`
   - `EnvSetupTemplates` (처음 사용 안내 + Claude 로그인 카드)
   - `WebProjectTemplates` (콘솔 페이지 인증 배너 + 라이브 배너)
   - `AdminTemplates` (대시보드 환경 카드 hint)
   - `ConsoleRoutes` (REST 503 응답 메시지)
   - `docker/doctor/lib/claude-auth.sh` / `docker/README.md` / `docker/HUB_README.md`

2. **잘못된 위치 감지 + 안내**: `EnvDiagnostics.checkClaudeAuth` /
   `EnvSetupService.probeClaudeAuth` 가 vibe 홈의 `.credentials.json` 이
   없을 때 `/root/.claude/.credentials.json` 도 점검. stray 토큰이 발견되면
   ERROR 메시지에 "토큰이 root 사용자 홈에 저장됨 — `--user vibe` 로 재로그인
   필요" 와 정확한 명령을 표시.

### Documentation — refresh token 자동 갱신 보증

`buildClaudeAuthHelp` 메시지에 한 줄 추가:

> "refresh token 으로 access token 은 자동 갱신되므로 한 번만 진행하면 됩니다."

이미지 pull / 컨테이너 재기동 / 시간 경과로 access token 이 만료돼도, refresh
token 이 살아있으면 claude CLI 가 자동 갱신한다는 점을 명시. 사용자는 평소
명시적 재로그인이 필요 없다.

### Wire change

**없음.** 안내 텍스트 / 진단 메시지 갱신만. ApiPath / DTO / WsFrame 무변경.

### 사용자 즉시 처치

```bash
# 1) 잘못된 위치(root)에 떨어진 토큰이 있다면, vibe 사용자로 재로그인
docker exec -it --user vibe vibe-coder claude login

# 2) 브라우저 새로고침 → 빌드환경 "Claude 로그인" 이 ✓ 로그인됨 으로 표시
#    콘솔 페이지의 첫 프롬프트도 정상 동작
```

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.3` / `:latest` (linux/amd64).
- 모든 docker 파일 + README 의 태그 동기.

### v0.7.0 로 옮긴 후보 (사용자가 명시적으로 요청 시 진행)

- **PTY 기반 웹 터미널** (`/env-setup/claude-login` 라이브) — xterm.js +
  Java PTY 로 컨테이너 안에서 `claude login` 을 브라우저에서 직접 실행.
  CLAUDE.md §3 "raw-shell UI 금지" 정책 완화 결정이 선행되어야 함.

## [0.6.2] - 2026-05-23

### Fixed — Claude 인증 진단: 토큰 만료까지 검증 (false positive 완전 해결)

v0.6.1 까지 `.credentials.json` 파일 존재만 보고 OK 로 판정했는데,
**파일은 있지만 OAuth 토큰이 만료된 상태** 가 흔하다. 사용자는 콘솔에서
`Success: Not logged in. Please run /login` 을 받는데 빌드환경 페이지
/대시보드는 ✓ 로그인됨 으로 표시되는 모순.

**진단 강화**: `.credentials.json` 의 `claudeAiOauth.expiresAt` (epoch ms)
까지 파싱해 실제 만료 여부 확인.

| 상태 | UI |
|---|---|
| `.credentials.json` 없음 | ✗ 로그인 필요 |
| 파일 있고 `expiresAt > now` (여유 6h+) | ✓ 로그인됨 (만료: yyyy-MM-dd HH:mm:ss) |
| 만료 6시간 이내 | △ 곧 만료 — 재로그인 권장 |
| `expiresAt <= now` | ✗ 토큰 만료 — 재로그인 필요 |
| 파일은 있는데 형식 파싱 실패 | △ 만료 시각 확인 실패 (WARNING) |

판정은 `EnvDiagnostics.checkClaudeAuth` 와 `EnvSetupService.probeClaudeAuth`
양쪽에서 같은 기준으로 적용. 두 곳 모두 `readOauthExpiresAt(path)` helper 가
JSON 을 안전하게 파싱하고, 어떤 실패도 null 로 떨어뜨려 페이지가 깨지지 않게.

### Added — 콘솔 페이지의 라이브 인증 실패 배너

진단이 어떤 이유로든 false positive 라도 사용자가 막막해지지 않도록,
콘솔 페이지의 WS 로그 스트림에서 다음 패턴을 감지하면 즉시 빨간 배너 +
프롬프트 폼 비활성화:

```
/(not logged in|please run \/login|invalid api key|unauthorized|authentication required)/i
```

`console_assistant` / `console_tool_result` / `console_error` /
`console_system` 모든 채널의 텍스트를 스캔.
배너 안에 `docker exec -it vibe-coder claude login` 명령을 그대로 표시,
사용자가 복사 → 재로그인 → 페이지 새로고침 흐름.

### Wire change

**없음.** `EnvironmentCheckDto.claudeAuth` 의 message/detail 텍스트만 풍부해짐.
DTO 구조 / ApiPath / WsFrame 무변경. 안드로이드 앱 영향 없음.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.2` / `:latest` (linux/amd64).
- 모든 docker 파일 + README 의 태그 동기.

### 사용자 즉시 처치

콘솔에서 `Not logged in. Please run /login` 메시지를 본 경우:

```bash
docker exec -it vibe-coder claude login
```

브라우저 새로고침 — 빌드환경/대시보드의 "Claude 로그인" 행이 ✓ 로그인됨
(만료 시각 표시) 으로 바뀌면 콘솔도 정상 동작합니다.

## [0.6.1] - 2026-05-23

### Added — 빌드환경 페이지 Phase B: 원클릭 설치 + 일괄 + 진행 페이지

v0.6.0 의 상태 진단 + 명령 안내 위에, 사용자가 **버튼 한 번**으로
설치를 시작하고 **실시간 progress + 로그** 를 볼 수 있도록 완성.

**원클릭 설치**

- 카드별 "설치 / 재설치 / 이어서 설치" 버튼 — 상태에 따라 라벨 자동 변경
  (MISSING → "설치", PARTIAL → "이어서 설치", INSTALLED → "재설치 / 업데이트").
- `POST /env-setup/{componentId}/install` → 새 task id 발급 후 즉시
  `/env-setup/tasks/{taskId}` 로 redirect. 한 클릭으로 진행 화면까지 도달.
- 자동 설치 가능한 컴포넌트: Android SDK / MCP 기본 묶음. Claude 로그인 은
  OAuth interactive 라 자동 불가 — 명령 안내만 유지.

**일괄 설치**

- 페이지 상단 우측에 **⚡ "모두 설치/업데이트" 버튼**. 자동화 가능한 모든
  컴포넌트를 단일 task 안에서 순차 실행, 같은 진행 페이지에서 통째로 본다.
- `POST /env-setup/install-all` → 단일 taskId 로 묶임.

**진행 페이지**

- `GET /env-setup/tasks/{taskId}` — 상태 라벨 / 라인 카운터 / progress
  bar (라인 수 기반 추정, 1000 라인=100% saturating) / 실시간 로그.
- WS endpoint `/ws/env-setup/{taskId}/logs` (기존 빌드용 legacy log stream
  재사용). 인증은 v0.5.5 와 동일하게 handshake cookie.
- 완료 시 progress bar 가 SUCCESS=초록 / FAILED=빨강 으로 색이 바뀌고
  하단 hint 가 "빌드환경으로 돌아가기" 또는 "원인 확인 후 재시도" 로 변경.

**백엔드 구현**

- `EnvSetupService.spawnInstall(c)` / `spawnInstallAll()` — `TaskQueue`
  에 등록, `Dispatchers.IO` 에서 `vibe-doctor <subcmd>` 자식 프로세스 spawn,
  stdout 라인 단위로 `WsFrame.Log(level=STDOUT, ...)` emit. 종료 시
  `WsFrame.Done(status)`.
- `EnvSetupService` 생성자에 `TaskQueue` / `LogHub` / `Clock` 의존성 추가.
  `ServerMain` 에서 기존 인스턴스 재사용 (ServerContext 변경 없음).
- `lastTaskId(c)` — 컴포넌트 최근 작업 id 캐시 (재시도 시 같은 진행 페이지로
  돌아가는 용도, 현재는 미사용 / 향후 확장 여지).

### Fixed — Claude 로그인 false positive

v0.5.4 ~ v0.6.0 의 `EnvDiagnostics.checkClaudeAuth` 와
`EnvSetupService.probeClaudeAuth` 가 `~/.claude/.credentials.json` 또는
`config.json` **둘 중 하나만** 있어도 OK 로 판정해 false positive 가 났다.
실제로는 `claude` CLI 가 첫 실행 시 빈 `config.json` 을 항상 만들기 때문에
"config.json 존재 = 로그인됨" 이 아니다. 콘솔에서 `Not logged in. Please
run /login` 이 뜨는데 빌드환경 페이지/대시보드는 "로그인됨" 으로 표시되던
원인.

수정: **`.credentials.json` 만** 보고 판정. `config.json` 은 무시.
vibe-doctor (`docker/doctor/lib/check.sh`) 는 동일 false positive 가 있으나
이 PR 에서는 도커 셸 스크립트는 건드리지 않고 server 측 로직만 정정.

### Changed — 컴포넌트 라벨

Claude 로그인 카드의 상태 배지가 "✓ 설치됨 / ✗ 미설치" → **"✓ 로그인됨 /
✗ 로그인 필요"** 로 표시. 다른 컴포넌트(SDK 등)는 그대로.

### Wire change

**없음.** 새 라우트는 SSR / form / cookie 기반. ApiPath / DTO / WsFrame
무변경. 안드로이드 앱 영향 없음.

### CSS

- `.progress-bar` / `.progress-fill` (`done-ok` / `done-fail`) 추가.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.1` / `:latest` (linux/amd64
  · v0.6.0 정책에 따라 일반 push 는 amd64-only).
- 모든 docker 파일 + README 의 태그 동기.

## [0.6.0] - 2026-05-23

### Added — 빌드환경 페이지 (`/env-setup`) — Phase A

좌측 nav 에 "빌드환경" 메뉴를 신설. 슬림 도커 이미지 정책 ("무거운
컴포넌트는 볼륨에") 의 사용자 진입 부담을 줄이기 위해, 어떤 컴포넌트가
설치돼 있고/없는지 한 화면에서 확인하고 설치 명령을 그대로 복사해서
실행할 수 있게 함.

**대상 컴포넌트 (7개)**

| 컴포넌트 | 출처 | 설치 |
|---|---|---|
| JDK 17 / Git CLI / Node.js / Claude CLI | 도커 이미지 내장 | 추가 작업 불필요 (✓ 자동 진단) |
| Claude 로그인 (`.credentials.json`) | OAuth | `docker exec -it vibe-coder claude login` |
| Android SDK (cmdline-tools + platform-tools + platforms;android-35 + build-tools) | sdkmanager | `docker exec -it vibe-coder vibe-doctor android` |
| Platform Tools (ADB) | Android SDK 포함 | SDK 카드로 일괄 처리 |
| 기본 MCP 서버 묶음 | `vibe-doctor mcp` | `docker exec -it vibe-coder vibe-doctor mcp` |

**핵심 구현**

- `server/env/EnvSetupService.kt` — `SetupComponent` enum + `detect()`
  per-component 진단. 도커 doctor (`docker/doctor/lib/check.sh`) 와 같은
  기준 (cmdline-tools / platform-tools / platforms / build-tools 디렉토리
  존재 여부, claude credentials 파일 여부 등).
- `server/admin/EnvSetupRoutes.kt` — `GET /env-setup` SSR 페이지.
- `server/admin/EnvSetupTemplates.kt` — 카드 7개 + "처음 사용하시나요?"
  안내 + "이미지 pull 후에도 보존됩니다" 보증 카드.
- `AdminTemplates.shell()` 의 좌측 nav 에 `링크 /env-setup "빌드환경"` 추가.

**Phase B 예고 (v0.6.1+)**

지금은 안내/명령 표시만. 다음 단계에서 카드의 "원터치 설치 버튼" + 진행
페이지 (`/env-setup/tasks/{taskId}` + `/ws/env-setup/{taskId}/logs`) +
실시간 progress bar 가 추가됩니다.

### Documentation — "빌드환경은 이미지를 갈아끼워도 보존됩니다" 보증

도커 이미지 pull 후 컨테이너를 recreate 해도 SDK/Gradle 캐시/Claude 인증/
프로젝트 소스가 살아남는다는 점을, README 와 빌드환경 페이지 양쪽에
표 형식으로 명시.

| 데이터 | 마운트 | 이미지 pull 시 |
|---|---|---|
| Android SDK (3~4GB) | `vibe-android-sdk` (named) | ✅ 보존 |
| Gradle 의존성 캐시 | `vibe-gradle-cache` (named) | ✅ 보존 |
| Claude 인증 | `~/.claude` (host bind) | ✅ 보존 |
| 프로젝트 소스 + APK | `./workspace` (host bind) | ✅ 보존 |
| DB / 로그 | `./vibe-data` (host bind) | ✅ 보존 |
| 서버 본체 | 이미지 내장 | 🔄 새 이미지로 교체 |

⚠️ `docker compose down -v` 는 named volume 까지 삭제 — 일반 업그레이드
시 사용 금지. README 에 명시.

### Documentation — README compose 예문 보강

직접 `compose.yml` 을 쓰고 싶은 사용자를 위해 최소 형태 (image + ports +
volumes + named volumes 선언) 를 그대로 복사해 쓸 수 있도록 README 본문에
포함. 기존 "curl 으로 받아서 docker compose up" 흐름도 같은 섹션에 유지.

### Wire change

**없음.** 새 SSR 페이지는 ApiPath / DTO / WsFrame 변경 없이 서버 측에서
`EnvSetupService.detect()` 호출 → HTML 렌더만 함. 안드로이드 앱 영향 없음.

### Refactored

- `ServerContext` 에 `envSetup: EnvSetupService` 추가.
- `Module.kt` 에 `envSetupRoutes(...)` 등록.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.0` / `:latest` (linux/amd64).
- **빌드 정책 변경** — 이번 릴리즈부터 일반 개발 push 는 amd64 only.
  multi-arch (amd64 + arm64) 빌드는 마일스톤 (v0.7.0, v1.0.0 등) 시점에만
  진행한다. 사유: arm64 emulation 빌드가 amd64 대비 3~5배 느려 (10~15 분 vs
  2~3 분) 잦은 commit 흐름을 지연시킴. ARM 호스트 (Apple Silicon / RPi /
  ARM 클라우드) 사용자는 마일스톤 이미지로 pull 하거나, Docker Desktop 의
  자동 emulation 으로 amd64 이미지를 실행할 수 있다.
- 모든 docker 파일 + README 의 태그 동기.

## [0.5.5] - 2026-05-23

### Fixed — 웹 콘솔/빌드 페이지의 WS `invalid_token` (httpOnly 쿠키 충돌)

v0.4.0 부터 `vibe_session` 쿠키는 XSS 방어를 위해 `httpOnly=true` 로 설정.
v0.5.0 콘솔 페이지의 JS 가 `document.cookie.match` 로 그 토큰을 읽으려
했지만, 정의상 JavaScript 는 httpOnly 쿠키에 접근하지 못한다 → 빈 token 으로
첫 `Auth` 프레임을 보냄 → `deviceRepo.findByTokenHash("")` null → 항상
`invalid_token`.

이로 인해 v0.5.0 ~ v0.5.4 사이 웹 브라우저에서 콘솔/빌드 로그가 처음부터
끊겼다. REST API 는 같은 쿠키를 서버 측에서 직접 읽기 때문에 정상 동작
했고, SSR 페이지도 멀쩡히 열려서 발견이 늦었다.

### 수정 — 서버: WS handshake cookie 인증 + JS: auth 프레임 제거

- **`WsRoutes.authenticateFirstFrame`** 에 Path 1 (cookie) 추가.
  WebSocket handshake 시 브라우저가 자동으로 동일 origin 쿠키를 첨부하므로,
  서버는 `call.request.cookies[SESSION_COOKIE]` 에서 토큰을 추출해 곧바로
  device 매칭. 성공 시 첫 프레임을 기다리지 않고 인증 완료.
  - 쿠키가 있는데 매칭 실패 → 즉시 `invalid_token` close.
  - 쿠키 없음 → 기존 Path 2 (첫 텍스트 프레임 = `WsFrame.Auth`) 로 fallback.
    안드로이드 앱은 이 경로로 그대로 동작 → wire 호환 유지.
- **콘솔 / 빌드 페이지 인라인 JS** — `ws.onopen` 에서 token 추출 + Auth
  프레임 송신 코드를 제거. handshake cookie 만으로 인증.
- `function authenticateFirstFrame` 의 expression-body 가 named-return 을
  못 잡아 block body 로 리팩토.

### Wire change

**없음.** 안드로이드 앱은 쿠키를 보내지 않으므로 Path 2 (첫 Auth 프레임)
경로를 그대로 탄다. 기존 protocol 무수정 호환. SDK 변경 없이 그대로 동작.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.5` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 영향 받은 사용자 안내

v0.5.0 ~ v0.5.4 컨테이너에서 콘솔/빌드 로그가 WS `invalid_token` 으로 끊겼던
경우, v0.5.5 이미지로 갈아끼우면 즉시 해결됩니다. 별도 재로그인 불필요.

```bash
docker pull siamakerlab/vibe-coder-server:0.5.5
docker compose up -d --force-recreate
```

## [0.5.4] - 2026-05-23

### Added — Claude CLI 인증 진단 + 콘솔 가이드

새 프로젝트를 만들고 처음 프롬프트를 보낼 때 Claude CLI 가 인증 안 된
상태였다면, 지금까지는 사용자가 `Invalid API key` / `Please run /login`
같은 자식 프로세스 stderr 만 ConsoleSystem 으로 띄엄띄엄 받아보는
상황이었다. 어떤 명령을 어디서 실행해야 풀리는지가 화면에 없었다.
이번 릴리즈는 그 흐름을 처음부터 끝까지 명확하게 만든다.

- **`EnvDiagnostics.checkClaudeAuth()`** — `CLAUDE_CONFIG_DIR` (없으면
  `~/.claude`) 의 `.credentials.json` 또는 `config.json` 존재 여부를
  검사. vibe-doctor (`docker/doctor/lib/check.sh`) 와 같은 기준.
  - CLI 미설치 → ERROR + "CLI 먼저 설치" 안내.
  - 자격증명 파일 있음 → OK.
  - 없음 → ERROR + "도커 / 호스트별 `claude login` 명령" 안내.
- **`EnvironmentCheckDto.claudeAuth`** — 새 필드. nullable + default null
  로 추가했으므로 안드로이드 앱은 무수정으로 호환 (이 필드는 그냥 안 봄).
- **대시보드 환경 카드** — "Claude 로그인" 행 추가. 로그인 안 됐을 때
  `docker exec -it vibe-coder claude login` 한 줄을 같은 카드에 노출.
- **콘솔 페이지** —
  - CLI 미설치 / 인증 누락 시 페이지 상단에 빨간 배너 (제목 / 본문 /
    복사 가능한 명령 1줄 / 자세히 펼치기) 표시.
  - 프롬프트 textarea + 전송 버튼이 자동 `disabled`. 사용자가 잘못된
    입력을 보내고 의문의 에러를 받는 흐름을 차단.
- **`POST /api/projects/{id}/claude/console/prompt`** — 서버 측 가드.
  인증 미완 / CLI 미설치 시 `503 claude_cli_missing` 또는
  `503 claude_auth_required` 에러 코드 + 한국어 메시지로 응답.
  안드로이드 앱도 곧바로 사람이 읽을 수 있는 안내를 받게 된다.

### Wire change

**최소.** `EnvironmentCheckDto.claudeAuth` 가 추가됐지만 nullable +
default null 이라 기존 안드로이드 빌드와 backward compatible.
DTO / ApiPath / WsFrame 의 명칭은 변경 없음.

새로운 에러 코드: `claude_cli_missing` (503), `claude_auth_required` (503).
안드로이드 앱은 기존 ApiException 처리 흐름 그대로 메시지만 보여주면 됨.

### Refactored

- `consoleRoutes(...)` 시그니처에 `envDiagnostics: EnvDiagnostics` 추가.
  `ServerContext.env` 그대로 주입 (ServerContext 변경 없음).
- `AdminTemplates.dashboardPage(...)` 시그니처에 `claudeAuth: CheckItemDto?`
  추가. 기존 호출부도 그대로 컴파일되도록 default null.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.4` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.5+)

- 콘솔 페이지에서 WS 로 흘러오는 `claude_unavailable` 등 ConsoleSystem
  코드를 보고 자동으로 인증 가이드 배너를 띄우는 라이브 fallback.
- i18n.

## [0.5.3] - 2026-05-23

### Added — 종료된 빌드의 디스크 로그 replay

v0.5.2 빌드 상세 페이지의 한계 — "종료된 빌드의 로그는 메모리 ring 에서
evicted 되면 더 이상 못 본다" — 를 해결. 종료 상태 빌드를 열면 서버가
워크스페이스의 `.log` 파일을 즉시 읽어 화면에 prerender 한다.

구현:

- **`loadBuildLog(workspace, projectId, buildId, storedLogPath)`** —
  `BuildRow.logPath` 를 읽어 `BuildLogReplay` 로 변환.
  - 보안: `WorkspacePath.ensureUnderWorkspace` 통과 + DB row 의 path 가
    실제로 `workspace.buildLogFile(projectId, buildId)` 와 같은지 한 번 더
    검증 (DB 변조 / path-traversal 방어).
  - 파일 미존재 / 읽기 실패 → null. UI 는 안내만 표시하고 페이지는 살아남음.
  - 너무 큰 파일은 `MAX_REPLAY_LINES = 2000` 줄로 tail-truncate.
    `truncated=true` 시 UI 가 "마지막 N / 전체 M 줄 표시" 로 알림.
- **`parseLogLine`** — `TaskLogger` 가 쓰는 `[ts] [level] message` 라인을
  분해. 포맷 외 라인(stack trace continuation 등) 은 `level=RAW` 로 fallback.
- **UI 통합** —
  - `buildDetailPage(replay = ...)` 인자 추가.
  - 종료 상태일 때 로그 카드 헤더가 "파일 replay" 로 표시.
  - 라인들은 WS 라이브 흐름과 동일한 색상 팔레트 (STDOUT=green / STDERR/ERROR=red
    / WARN=amber / INFO/sys=gray).
  - 카드 하단 caption — 파일 경로, 크기 (KB), 총 줄 수, 잘림 여부.

### Changed — 라우트 의존성

`webProjectRoutes(...)` 시그니처에 `workspace: WorkspacePath` 추가.
`ServerContext` 변경 없이 기존 `ctx.workspace` 재사용.

### Wire change

**없음.** 디스크 직접 read + 기존 `BuildRow.logPath` 활용. ApiPath / DTO /
WsFrame 무변경.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.3` / `:latest`.
  multi-arch (linux/amd64 + linux/arm64).
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.4+)

- 콘솔 메시지 검색 / 토픽 필터.
- i18n (현재 SSR 텍스트는 한국어 하드코딩).
- 빌드 로그 페이지에서도 "다운로드 (.log 파일 전체)" 링크 검토.

## [0.5.2] - 2026-05-23

### Added — 빌드 상세 페이지 + 실시간 로그 (`/projects/{id}/builds/{buildId}`)

지금까지 "빌드를 큐에 등록은 했는데 진행 상황을 어디서 보지?" 가
불명확했던 문제 해결. v0.5.0 부터 이미 존재하던 빌드 WS 라우트
(`/ws/projects/{id}/builds/{buildId}/logs`) 위에 SSR + 인라인 JS
레이어를 얹어 한 페이지에서 다음을 본다:

- **상태 카드** — `PENDING/RUNNING/SUCCESS/FAILED/CANCELED/TIMEOUT` 배지,
  variant, 시작/종료 시각, error message.
- **APK 카드** — 성공 시 다운로드 버튼 (`/api/projects/{id}/artifacts/{aid}/download`),
  파일명, sha256 prefix, 크기 (KB). 진행 중에는 placeholder.
- **로그 카드** — `console-log` 스타일 패널.
  - PENDING/RUNNING 이면 자동 WS 연결 (쿠키 토큰 인증).
  - `Log(level, message)` → 색상 분류 (`STDOUT=green`, `STDERR/ERROR=red`,
    `WARN=amber`, 그 외 sys).
  - `Done(status, errorMessage)` 수신 시 표시 + 5초 후 페이지 reload 로
    최종 상태/APK 링크 갱신.
  - 종료 상태면 WS 미연결 (메모리 ring 이미 evicted) + 파일 로그 경로 안내.
- **취소 chip** — non-terminal 상태에서만 표시. `BuildService.cancel(buildId)` →
  진행 중인 Gradle 프로세스 destroyForcibly.

### Changed — 빌드 트리거 flow 단순화

`POST /projects/{id}/builds` 의 redirect target 을
`/projects/{id}/builds?ok=...` 에서 새 빌드 상세 `/projects/{id}/builds/{newBuildId}` 로
변경. 한 번의 클릭으로 로그가 흐르는 화면까지 도달.

빌드 목록 + 프로젝트 상세 "최근 빌드" 의 build ID 셀이 상세 페이지
링크로 활성화.

### 새 라우트

- `GET /projects/{id}/builds/{buildId}` — 상세 + 로그 페이지
- `POST /projects/{id}/builds/{buildId}/cancel` — 빌드 취소 (form)

### Wire change

**없음.** 새 SSR 페이지는 기존 `BuildService.cancel` / `BuildRepository.get` /
`ArtifactRepository.get` 호출 + 기존 빌드 WS (`/ws/.../logs`) 위에 얇은 레이어.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.2` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.3+)

- 콘솔 메시지 검색 / 토픽 필터.
- i18n (현재 SSR 텍스트는 한국어 하드코딩).
- 빌드 로그를 파일에서 replay (메모리 ring evicted 후에도 종료 빌드 로그
  열람 가능하도록).

## [0.5.1] - 2026-05-23

### Added — 파일 / Git / 콘솔 chip 통합 (Standalone 도커 앱 완성도 보강)

v0.5.0 에서 깐 SSR 콘솔 위에 안드로이드 앱과 동등한 부가 기능을 마저
얹어 "웹 단독 운용 100%" 를 달성. 이제 브라우저만으로 다음이 가능:

**파일 (`/projects/{id}/files`)**
- `GET` — 업로드된 파일 목록 (이름 / MIME / 크기 / 시각 + 다운로드/삭제 chip).
- `POST /upload` — multipart form 업로드. `UploadService` 가 v0.4.1 에서
  Ktor 3.x `provider().toInputStream()` 으로 모더나이즈된 경로를 그대로 재사용.
- `GET /{fileId}/download` — `Content-Disposition: attachment` 로 응답.
  미인증 시 페이지 점프 대신 401 처럼 동작하도록 `requireSessionOrRedirect`
  호출 후에도 다운로드 흐름이 깨지지 않게 처리.
- `POST /{fileId}/delete` — 파일 + DB row 제거.
- 업로드 확장자 블랙리스트 (`exe/bat/cmd/ps1/sh`) 와 최대 크기는 기존
  `server.yml` 정책을 그대로 따른다.

**Git (`/projects/{id}/git`)**
- 한 페이지에 status (branch / ahead / behind / 변경 entry) + diff 미리보기
  (최대 20KB) + recent 10 commits 를 모두 표시. `GitReader` 호출 결과를
  카드 3개로 분리 렌더.
- diff 본문은 별도 `pre.diff-block` 모노스페이스 패널.
- 읽기 전용 — `git push` / `reset --hard` 등 쓰기 작업은 의도적으로
  노출하지 않음 (CLAUDE.md §3 보안). 필요 시 콘솔에서 Claude 에게 부탁.
- git repository 가 아니거나 git CLI 실패 시 안내 + `git init` 힌트 표시.

**콘솔 chip 통합 (`/projects/{id}/console`)**
- 슬래시 chip 7개 추가: `/status` `/cost` `/model` `/memory` `/plan`
  `/compact` `/clear` (마지막은 danger). 클릭 시
  `POST /projects/{id}/console/slash` (CONSOLE_SLASH_WHITELIST 검증) →
  `ClaudeSessionManager.sendPrompt(id, "/cmd")` 호출.
- 페이지 점프 chip 4개: 프로젝트, 빌드, 파일, git.
- "새 세션 시작" 도 chip 스타일 (danger).

**프로젝트 상세 페이지**
- "작업" 카드에 파일 / git 진입 링크 2개 추가 (콘솔 / 빌드 / 파일 / git).

### CSS / 디자인 시스템

- `.chip` / `.chip-link` / `.chip-danger` 공용 컴포넌트 추가
  (둥근 알약, 호버 효과, danger variant).
- `pre.diff-block` — 다크 모노스페이스 코드 블록.

### Refactored — 라우트 의존성

`webProjectRoutes(...)` 시그니처에 `uploads: UploadService` 와
`gitReader: GitReader` 추가. `ServerContext` 변경 없이 기존 인스턴스
재사용 (`ctx.uploads`, `ctx.git`).

### Wire change

**없음.** ApiPath / DTO / WsFrame 무변경. 새 SSR 라우트는 기존
서비스 (`UploadService` / `GitReader` / `ClaudeSessionManager`) 위에
얇은 폼 + 다운로드 응답만 추가.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.1` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.2+)

- 빌드 진행 중 실시간 로그 뷰 분리 (현재는 `ConsoleSystem` 으로 흘러감).
- 콘솔 메시지 검색 / 토픽 필터.
- 다국어 (현재 SSR 텍스트는 한국어 하드코딩 — i18n 도입 검토).

## [0.5.0] - 2026-05-23

### Added — 웹만으로 완결되는 SSR 콘솔 (Standalone 도커 앱 자아 확립)

안드로이드 앱 없이도 브라우저 하나로 **프로젝트 등록 → Claude 프롬프트 →
Gradle 빌드 → APK 다운로드** 까지 끝나도록 SSR 화면을 추가. v0.4.2 에서
선언했던 비전의 본 단계.

| 새 경로 | 기능 |
|---|---|
| `GET /projects` | 프로젝트 목록 + "새 프로젝트" 등록 폼 (`projectId` / `appName` / `packageName`) |
| `POST /projects` | `ProjectService.register` 호출. 워크스페이스에 빈 폴더 + `CLAUDE.md` 템플릿 생성. 성공 시 `/projects/{id}` 로 redirect |
| `GET /projects/{id}` | 프로젝트 상세 — 패키지/모듈/소스 경로/최근 빌드 5건 + 콘솔/빌드 진입 링크 + 메타데이터 삭제 |
| `POST /projects/{id}/delete` | 프로젝트 DB row 삭제 (워크스페이스 폴더 보존) |
| `GET /projects/{id}/console` | Claude 프롬프트 입력 + 실시간 로그 뷰. WebSocket `/ws/projects/{id}/console/logs` 자동 연결, replay + live frame 렌더. Ctrl+Enter 로 프롬프트 전송 |
| `POST /projects/{id}/console/new` | `ClaudeSessionManager.startNew` — 현 세션 종료, 다음 프롬프트가 새 대화 시작 |
| `GET /projects/{id}/builds` | 빌드 목록 + 상태 배지 + APK 다운로드 링크 (`/api/projects/{id}/artifacts/{aid}/download`) |
| `POST /projects/{id}/builds` | `BuildService.enqueueDebug` 큐 등록 |

추가 사항:

- **사이드바 nav 에 "프로젝트" 링크 추가** — 대시보드와 같은 깊이의 일급 메뉴.
- **WebSocket 인증 호환** — 콘솔 JS 는 `vibe_session` 쿠키 값을 그대로 첫
  `Auth` 프레임의 token 으로 보냄. 서버 `installAuth` 가 쿠키도 토큰 운반체로
  받아주는 v0.4.0 의 변경 덕분에 별도 토큰 발급 절차 없이 작동.
- **JS 는 인라인** (외부 빌드 파이프라인 없음, Node 의존 없음). LAN-only
  도구 철학 유지.
- **CSS** — `admin.css` 에 `.console-log` / `.prompt-form` / 액션 컬러
  팔레트(`assistant=green` / `tool=amber` / `user=blue` / `err=red` /
  `sys=gray`) 추가.

### Refactored — `AdminTemplates.shell()` internal 노출

새 페이지(`WebProjectTemplates`) 가 동일 레이아웃 셸을 사용할 수 있도록
`private fun shell` → `internal fun shell`. nav link 매칭도 새 경로
체계 (`/projects` 등) 를 인식하도록 갱신.

`requireSessionOrRedirect` / `WebSession` 타입도 `internal` 로 풀어 같은
모듈 내 다른 라우트 파일이 재사용 가능.

### Module 변경

- `server/Module.kt` — `webProjectRoutes(...)` 등록. 의존성:
  `ProjectService` / `BuildService` / `BuildRepository` / `ArtifactRepository`
  / `ClaudeSessionManager` / `LogHub`. `AdminRoutesDeps` 는 `adminRoutes` 와
  `webProjectRoutes` 가 같은 인스턴스를 공유 (세션 검증 일관성).

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작. 새 페이지들은 기존 `/api/*` REST + `/ws/*` WebSocket 위에
얇은 SSR + JS 레이어로 얹혀 있다.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.0` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md`, `README.md` 의 태그 동기.

### 다음 단계 (v0.5.1+)

- `/projects/{id}/files` — 워크스페이스 파일 업로드/다운로드 UI.
- `/projects/{id}/git` — git status/diff/log 뷰.
- 콘솔 입력에서 액션 chip (status / cost / model / clear 등) 호출 버튼.
- 빌드 로그 실시간 뷰 (현재는 콘솔에 ConsoleSystem 으로 흘러가지만 별도
  탭으로 분리 검토).

## [0.4.2] - 2026-05-23

### Changed — 정체성 선언: Standalone 도커 앱

`vibe-coder-server` 의 자아를 명시한다:

> **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만들어내는
> 외부 접근 가능한 개발머신 그 자체. 브라우저로 바로 로그인해 프로젝트
> 생성 · 프롬프트 전송 · Gradle 빌드 · APK 다운로드까지 끝낸다.
> 안드로이드 앱은 같은 서버를 가리키는 부가 클라이언트일 뿐 없어도
> 모든 기능을 쓸 수 있어야 한다.

이번 릴리즈는 그 비전의 **첫 단계**로 웹 UI 의 경로 구조를 평탄화. 실제
프로젝트 / 콘솔 / 빌드 화면 추가는 Phase 2 (v0.5.0) 에서 진행한다.

`README.md` / `CLAUDE.md` 톤도 동일한 방향으로 다듬음.

### Changed — Web URL 평탄화: `/admin/*` prefix 제거 (web-side breaking)

별도 `admin` 영역 개념을 제거하고 모든 SSR 화면을 루트 레벨로 이동.
사용자가 도메인 / 으로 접속하면 별도 추가 입력 없이 곧바로 첫 화면
(셋업 / 로그인 / 대시보드 자동 분기) 이 뜬다.

| v0.4.1 | v0.4.2+ |
|---|---|
| `/admin` | `/` |
| `/admin/login` | `/login` |
| `/admin/setup` | `/setup` |
| `/admin/settings` | `/settings` |
| `/admin/devices` | `/devices` |
| `/admin/password` | `/password` |
| `/admin/logout` | `/logout` |
| `/admin/static/admin.css` | `/static/admin.css` |

호환: `GET /admin{path...}` 핸들러가 같은 경로의 루트 버전으로
**HTTP 301 영구 리다이렉트** 한다. 북마크 · 구버전 안드로이드 앱
사용자 영향 없음. v0.6.0 에서 호환층 제거 예정.

영향 받지 않음:
- 모든 REST API (`/api/*`) — 안드로이드 앱과 wire-level 호환 그대로.
- WebSocket (`/ws/*`)
- `/health` 헬스 프로브 (Docker HEALTHCHECK)

구현:

- `server/admin/AdminRoutes.kt` — `route("/admin") { ... }` 블록 해체,
  모든 라우트를 root level 로. 함수명 `adminRoutes` 는 호출부 변경
  최소화를 위해 유지 (내부적으로 더 이상 admin 전용이 아님 → 향후
  `webRoutes` 로 rename 검토).
- `staticResources("/admin/static", ...)` → `staticResources("/static", ...)`.
- 모든 `respondRedirect("/admin/...")` 호출을 평탄 URL 로 정정.
- `requireSessionOrRedirect` 의 `next=` 파라미터 검증을 `startsWith("/admin")`
  → `startsWith("/") && !startsWith("//")` 로 강화. open-redirect 방지.
- `server/admin/AdminTemplates.kt` — `<link href=>` , 폼 `action=`, nav
  `href=`, `errorPage` 의 "대시보드로" 링크 전부 평탄 URL 로 정정.
  `navHtml` 의 `active` 클래스 매칭도 새 경로 체계로 갱신.
- `server/ServerMain.kt` 부팅 배너 — "Admin URL" 표기 제거, 단일 URL.

### Fixed — KDoc nested-comment 함정 (재발)

`AdminRoutes.kt` 의 새 KDoc 본문에 `admin/*` 표현을 그대로 적었더니
v0.4.1 의 `ApkFinder.kt` 와 동일한 K2 컴파일러 nested-comment 해석으로
`Unclosed comment` 에러 발생. 백틱 코드 표기 (`admin`) 로 회피.
앞으로 KDoc 내부에서 path glob (`*`, `/*`) 표현 사용 금지를 컨벤션화.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.4.2` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그 동기.

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작.

## [0.4.1] - 2026-05-23

### Infrastructure — 모노레포 → 2 리포 분리

`vibe-coder` 단일 저장소를 `vibe-coder-server` (본 리포) 와
`vibe-coder-android` (별도 리포) 로 분리. 본 리포는 `:server` /
`:shared` / `docker/` / `vibe-doctor` / Admin 웹 / docs 만 보유한다.

- `settings.gradle.kts`: `rootProject.name = "vibe-coder-server"`,
  `:android-app:app` 와 `skipAndroidModule` 옵션 제거. 모듈은
  `:shared` / `:server` 둘만.
- `gradle/libs.versions.toml`: Android 전용 라이브러리(Compose BOM,
  Hilt, AndroidX, Material Icons, DataStore, navigation-compose, Timber,
  espresso, Truth, Ktor client, KSP, AGP) 와 그 plugin alias 들
  (`kotlin-compose`, `android-application`, `ksp`, `hilt`, `ktor`) 제거.
- 루트 `build.gradle.kts`: `kotlin-jvm` / `kotlin-serialization` 만 남김.
- `:shared` 는 `vibe-coder-android` 리포에 **동일 사본**으로 존재하며
  wire-level 호환을 유지하기 위해 변경 시 양쪽 함께 갱신한다.

### Fixed — 베이스라인 결함 + deprecated 일괄 정리

CLAUDE.md §7 에 적혀 있던 split 시점 빌드 결함을 **실제 재현**하여 모두 회수.
이전 사이클에서는 Gradle daemon 캐시 영향으로 통과한 것처럼 보였으나,
`./gradlew clean` 후 정상 재현되었다.

- **`build/ApkFinder.kt`** — Kotlin 2.2 에서 제거된 `import kotlin.streams.toList`
  제거. JDK 16+ 의 `Stream.toList()` 멤버 메소드로 충분. 동시에 KDoc 본문
  `…/build/outputs/apk/debug/*.apk` 안의 `/*` 시퀀스가 K2 컴파일러에 의해
  nested comment 시작으로 해석되어 `Unclosed comment` 신택스 에러를 일으키던
  부분도 KDoc 표현을 재작성해 회피. import 결함이 가려져 있던 동안 함께
  숨어 있던 결함.
- **`actions/ServerActionHandler.kt:55`** — 존재하지 않는 `builds.submitDebug`
  호출을 실제 메소드 `builds.enqueueDebug` 로 정정.
- **`build/BuildService.kt`** — 위 두 결함의 파급으로 보고됐던 타입 추론
  실패는 root cause 해결과 동시에 자동 해소.

같이 발견된 비차단 deprecation 경고 2건도 동일 PR 에서 모더나이즈:

- **`auth/AuthPlugin.kt`** — Ktor 3.x 에서 `Principal` interface 가 deprecated
  (`This interface can be safely removed`). `DevicePrincipal: Principal` 의
  상위 인터페이스 제거. `principal<DevicePrincipal>()` 호출은 그대로 동작.
- **`files/FileRoutes.kt`** — `PartData.FileItem.streamProvider` (blocking
  InputStream) deprecated. `provider().toInputStream()`
  (`io.ktor.utils.io.jvm.javaio.toInputStream`) 로 교체. UploadService 시그니처
  (`InputStream` 입력) 는 그대로 유지.

이외에 split 직후 한 번 발견됐던 `ProcessRunnerTimeoutTest` 의 `@Test fun =
runBlocking { ... }` 시그니처 (Kotlin 2.2 + JUnit 4 거부) 는 0.4.0 시점에
이미 `: Unit` 명시로 정정됨. 회귀 없음.

전체 `./gradlew :server:test` 18 테스트 통과, deprecation warning 0건.

### Fixed — `.gitignore` 패턴이 source 패키지까지 무시하던 문제

`.gitignore` 의 `build/` 패턴이 Gradle output 뿐 아니라 source 트리 안의
패키지 디렉토리 `server/src/main/kotlin/.../server/build/` 까지 매칭하여,
**4개 핵심 파일이 git tracking 에서 누락된 상태였다**:

- `build/ApkFinder.kt`
- `build/BuildRoutes.kt`
- `build/BuildService.kt`
- `build/GradleBuilder.kt`

이 결함이 v0.4.0 분리 시점부터 누적되어 있었기 때문에, "main 체크아웃에선
재현되지 않는다" 고 적었던 이전 CHANGELOG 기록은 잘못된 관찰이었다
(파일 자체가 commit 되지 않아 clone 후에는 컴파일 자체가 불가능).

회수 방식:

- `.gitignore`: `build/` → `**/build/` + `!**/src/**/build/**` + `!**/src/**/build/`
  로 패턴을 좁혀, Gradle output (`server/build/`, `shared/build/`, `/build/`) 만
  무시하고 source 트리 안의 build 패키지는 보존하도록 변경.
- 누락 4개 파일을 git 에 정상 등록.

검증: `git check-ignore -v` 로 `server/build` 는 ignored, `server/src/.../build/ApkFinder.kt`
는 not-ignored 확인.

### Fixed — 로그인 / 셋업 / 에러 페이지 좌측 정렬 깨짐

Admin 웹의 `.layout` 은 `grid-template-columns: 220px 1fr` 고정이라
사이드바를 렌더하지 않는 화면(로그인 / 셋업 / 에러)에서도 좌측 220px 컬럼이
빈 채로 잡혀, `.auth-card` 가 시각적으로 좌측에 치우쳐 보였다.

- `AdminTemplates.shell()` 이 `showNav=false` 일 때 `.layout` 에 `no-nav`
  modifier 클래스를 추가하도록 변경.
- `admin.css` 에 `.layout.no-nav { grid-template-columns: 1fr }` 및
  `.content { width: 100%; justify-self: center; }` 추가. max-width:1200px
  은 유지하되 grid 셀 내에서 가운데 정렬되도록 보강.

### 배포

- Docker Hub 게시: `siamakerlab/vibe-coder-server:0.4.1` / `:latest`
  (linux/amd64 + linux/arm64 멀티아키, 2026-05-23).
  `docker pull siamakerlab/vibe-coder-server:0.4.1` 로 즉시 사용 가능.
  `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그를 `0.4.1` 로 동기.

### Wire change

없음. ApiPath / DTO / WsFrame 변경 없이 서버 내부 수정만.

## [0.4.0] - 2026-05-21

### Added — Docker 이미지 + Admin 웹 + 통합 인증

> 서버를 도커 이미지로 패키징하고, Android 앱 외에 브라우저 admin 웹을
> 통해 초기 셋업 / 비밀번호 변경 / 디바이스 관리가 가능하도록 인증
> 모델을 통합. 설계 문서: `docs/01-plan/admin-web.md`.

**Docker PoC (`docker/`)**
- **슬림 멀티스테이지 Dockerfile**: JDK17 builder + JRE runtime, 약 600MB.
  Android SDK / Gradle 캐시 / Claude 인증은 이미지에 박지 않고 컨테이너
  부팅 후 doctor가 볼륨에 다운로드.
- **vibe-doctor** (`docker/doctor/`): 인터랙티브 셋업 도우미.
  `check` / `install` / `android` / `claude` / `mcp` 서브커맨드.
  Android SDK cmdline-tools 자동 다운로드 + sdkmanager 라이선스 자동
  수락 + manifest.yml 기반 패키지 설치.
- **entrypoint.sh**: 호스트 UID/GID 매칭(`PUID`/`PGID`), 볼륨 소유권 정리,
  `VIBECODER_ADMIN_USERNAME`/`PASSWORD` env 패스스루, Android SDK 누락
  안내. `tini` PID 1 + `gosu` 권한 강등.
- **compose.yml**: 5개 볼륨(workspace/data/android-sdk/gradle-cache/claude),
  헬스체크(`/health`), `.env` 통한 모든 옵션 외부화.
- **docker/.env.example**: 한국어 주석. UID/포트/경로/JVM/admin 부트스트랩.
- **docker/README.md**: pull → compose up → admin → doctor → 앱 로그인까지
  한국어 가이드.

**Shared (`shared/`)**
- `ApiPath`: `AUTH_LOGIN`, `AUTH_SETUP`, `AUTH_SETUP_STATUS`, `AUTH_PASSWORD`,
  `HEALTH` 추가. 기존 `AUTH_PAIR` 등은 deprecated 표기로 유지.
- DTO 추가: `LoginRequestDto`, `LoginResponseDto`, `SetupRequestDto`,
  `ChangePasswordRequestDto`, `SetupStatusDto`. `MeDto`에 `username` 필드.

**Server (`server/`)**
- **`/health`**: 인증 없는 헬스 프로브. Docker HEALTHCHECK / 모니터링용.
- **AdminUsers 테이블 + DeviceRow 확장**: `user_id`/`channel` 컬럼 추가
  (nullable + default, 자동 마이그레이션).
- **`AdminUserRepository`**: 단일 admin 행 CRUD. `count()` / `findById*` /
  `insert` / `touchLogin` / `updatePassword`.
- **`PasswordHasher`**: BCrypt cost 12. `PasswordPolicy`(영문+숫자 8자
  이상), `UsernamePolicy`(3~32자 `[A-Za-z0-9._-]`).
- **`AuthService`**: `setup` / `login` / `changePassword`. 같은 username
  10회 실패 시 15분 잠금. `dummy verify`로 timing-attack 방어.
- **`AuthRoutes`**: `/api/auth/login`, `/api/auth/setup`,
  `/api/auth/setup/status`, `/api/auth/password` 신규. `/api/auth/pair`는
  admin 존재 시 410(`pairing_deprecated`) 반환.
- **`AuthPlugin`**: `Authorization` 헤더 외에 `vibe_session` 쿠키도 토큰
  운반 경로로 인정. 같은 토큰이 두 경로 어느 쪽으로 와도 인증됨.
- **Admin 웹 (`admin/`)**: 서버 사이드 렌더 HTML. `/admin/setup` /
  `/admin/login` / `/admin` (대시보드) / `/admin/settings` /
  `/admin/password` / `/admin/devices` / `/admin/logout`. 외부 CDN 의존
  없는 다크 테마 CSS (`resources/static/admin/admin.css`).
- **`ServerMain.bootstrapAdminFromEnv()`**: 부팅 시 `VIBECODER_ADMIN_*` env
  가 있고 DB에 admin이 없으면 자동 생성. Docker compose 자동화용.
- **부팅 배너**: Admin URL 표시 추가, admin 미존재 시 경고 출력.
  레거시 페어링 코드는 admin 부재 시에만 호환용으로 노출.

**Android (`android-app/app/`)**
- **`ApiService.pair()` → `login()`**: username/password 입력으로 토큰
  발급. `LoginRequestDto`/`LoginResponseDto` 사용.
- **`AuthRepository.login()`**: 시그니처 `(serverUrl, username, password,
  deviceName)`. 기존 `pair()` 제거.
- **`KtorClient.sendWithoutRequest`**: `/api/auth/login`,
  `/api/auth/setup`, `/api/auth/setup/status`, `/health`도 인증 헤더 제외
  대상에 추가.
- **`ConnectScreen`**: 페어링 코드 입력 필드를 username + password 필드로
  교체. 비밀번호는 `PasswordVisualTransformation`. admin 셋업 안내 문구
  추가.
- **`strings.xml`**: `connect_pairing_code` → `connect_username` /
  `connect_password` / `connect_setup_hint`. `connect_button` "Pair" →
  "Sign in".

**빌드 인프라**
- **`settings.gradle.kts`**: `-PskipAndroidModule=true` 옵션 신설.
  Docker 이미지 빌드 시 :android-app:app을 제외하여 AGP/Android SDK 의존성
  없이 `:server`만 빌드.

**검증 (PoC manual)**
- `./gradlew :server:installDist` 통과, BCrypt jar 정상 포함
- 서버 실제 부팅 → `/health` `/api/auth/setup/status` `/admin` 302 →
  setup → login(wrong/correct) → bearer `/me` → cookie `/admin` 대시보드
  → `/api/auth/password` (wrong/correct) → 새 비번 로그인 → 레거시
  `/api/auth/pair` 410 모든 흐름 정상
- `./gradlew :android-app:app:compileDebugKotlin` 통과

## [0.3.0] - 2026-05-21

> v0.2.0의 마지막 deferred 2건 처리: 액션 권한 게이트(FR-11-b) + MCP
> per-tool enumeration. 채팅 콘솔이 host capability 상태를 보고 비가용
> 액션을 자동 비활성화하고, MCP 도구는 `.mcp.json`에 직접 적은 만큼 즉시
> chip으로 노출된다.

### Added — capability gate (FR-11-b)
- **Shared**: `ProjectActionDto`의 모든 sealed 변형에 `requires: List<String>`
  필드 추가. `ActionTreeDto.capabilities: Map<String, Boolean>` 신설.
  `CapabilityKey` 상수 객체 — `BUILD`, `GIT`, `CLAUDE_SESSION`,
  `mcp(server)`.
- **Server**: `actions/CapabilityService` 신설. EnvDiagnostics를 30초 TTL로
  캐시하여 `git` / `claude_session` 상태 계산, `.mcp.json`의 서버 목록을
  `mcp:<name>=true`로 매핑. `build`는 등록된 프로젝트라면 true.
- **Server**: `ProjectActionRoutes.GET /actions`가 응답에 capabilities
  포함. `ProjectActionRegistry.listForProject(projectId, capabilities)`로
  시그니처 확장.
- **Server manifests**: 기본 4개 manifest 갱신 — `build:debug` →
  `requires:["build"]`, `git:*` → `requires:["git"]`, `slash:*` →
  `requires:["claude_session"]`. 정적 텍스트만 다루는 prompt/snippet은
  `requires:[]` 유지.
- **Android**: `QuickActionChips`가 `tree.capabilities` × `action.requires`를
  보고 비가용 chip을 disabled로 렌더. 비활성 chip을 탭/롱탭하면 토스트로
  사유 표시(`cap_unavailable_*` 문자열 키). `strings.xml`에 5개 capability
  사유 메시지 추가.

### Added — MCP per-tool enumeration
- **Server**: `.mcp.json`의 서버 entry에 `tools` 배열을 선언하면 per-tool
  chip 생성. 형식:
  ```json
  {"mcpServers":{"bkit":{
    "command":"...","args":[...],
    "tools":[
      {"name":"bkit_pdca_status","label":"PDCA Status","icon":"Activity"},
      {"name":"bkit_pdca_history"}
    ]}}}
  ```
  `label`/`icon` 생략 시 `name`/"Plug"로 기본화. `argsTemplate`는 JSON
  그대로 통과. `tools`가 없으면 기존 per-server fallback chip 유지.
- **Server**: 자동 생성된 InvokeMcpTool은 `requires:["mcp:<server>"]`를 갖고,
  capability map에서 해당 키가 true일 때만 enabled.
- **Server**: `ProjectActionRegistry.mcpServerNames(projectId)`를 외부에
  노출하여 CapabilityService가 활용.

### Versions
- `versionName` `0.2.2` → `0.3.0` (MINOR: 액션 시스템 신규 기능 — 권한
  게이트 + per-tool 매니페스트 확장).
- `versionCode` `260521003` → `260521004`.
- `server.yml` `server.version` `0.2.2` → `0.3.0`.

## [0.2.2] - 2026-05-21

> v0.2.0 deferred 항목 중 빌드 산출물 housekeeping 2건 (F-1, F-2) 처리.
> 사용자 가시 동작 변경 없음.

### Added
- **Server**: `ArtifactService.pruneOldArtifacts(projectId, keepCount)` —
  프로젝트당 newest-first로 정렬해 `keepCount` 초과분을 자동 삭제. 각 항목별로
  (1) artifact 디렉토리 통째 삭제 (APK + metadata.json,
  `ensureUnderWorkspace` 검증 후), (2) `Builds.artifactId` 참조 null로
  해제(build history는 보존), (3) `Artifacts` row delete. `keepCount <= 0`은
  "정리 안 함"으로 처리. 항목별 실패는 KotlinLogging WARN으로 격리.
- **Server**: `storeDebugApk` 직후 `pruneOldArtifacts(projectId,
  config.workspace.artifactKeepCount)` 자동 호출 (기본 20개 보관).
- **Repo**: `ArtifactRepository.listForProjectAll(projectId)` (limit 없음),
  `ArtifactRepository.delete(artifactId): Int`,
  `BuildRepository.detachArtifact(artifactId)` 신설.

### Changed
- **Build infra**: `gradle/wrapper/gradle-wrapper.jar`를 Gradle 9.5.1 정본
  배포본에서 ship한 wrapper로 재생성 (`./gradlew wrapper
  --gradle-version 9.5.1 --distribution-type bin`). jar 48966 → 48462 bytes.
  SHA-256 변경. `gradle-wrapper.properties`에 9.5.1 기본값 `retries=0` /
  `retryBackOffMs=500` 자동 추가. 분석 보고서 F-1 항목 해소.
- **Server**: `ArtifactService` 시그니처 확장 — 의존성에 `config: ServerConfig`,
  `buildRepo: BuildRepository` 추가. `ServerMain` 와이어링 동기.

### Versions
- `versionName` `0.2.1` → `0.2.2` (PATCH: 자동 정리/빌드 인프라).
- `versionCode` `260521002` → `260521003`.
- `server.yml` `server.version` `0.2.1` → `0.2.2`.

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
