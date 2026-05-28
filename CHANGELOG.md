# Changelog — vibe-coder-server

All notable changes to the server component will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

v0.4.0 까지는 `vibe-coder` 모노레포의 단일 CHANGELOG 였고, v0.4.1 부터
서버/안드로이드 두 리포로 분리되어 각 리포가 독립 changelog 를 갖는다.
Android 클라이언트 이력은 `vibe-coder-android` 리포의 CHANGELOG 참고.

## [Unreleased]

## [1.34.0] - 2026-05-29 — 터미널 세션 영속(scrollback replay) + 입력 깨짐/Claude Code TUI 수정

사용자 보고 3건: (1) 다른 화면 갔다 오면 터미널 세션이 날아가 보임(상시 유지 필요),
(2) 입력 중 텍스트 중복/깨짐, (3) Claude Code 실행 시 화면.

### Added

- **터미널 출력 scrollback ring buffer + 재연결 replay**: `TerminalSession` 이 출력을
  최대 200K char ring buffer 에 누적, WS (재)연결 시 먼저 replay 후 live 스트림. 이전엔
  `_output` SharedFlow 가 `replay=0` 이라 다른 사이드바 메뉴 갔다 `/terminal` 재진입 시
  **PTY 는 살아있는데 화면이 텅 비어** "세션 날아감"으로 보였음. 이제 직전 출력이 복원돼
  세션이 상시 유지되는 것처럼 동작. (`MAX_SCROLLBACK_CHARS`, `scrollbackSnapshot()`.)

### Fixed

- **입력 텍스트 중복/깨짐**: 세 원인 회수 —
  - `convertEol: true` → **false**: PTY 가 이미 CR/LF 를 제어하는데 xterm 이 `\n`→`\r\n`
    재변환해 커서 위치 제어 TUI(특히 Claude Code)의 렌더가 깨지던 문제. PTY-backed
    터미널 표준 설정으로 정정.
  - **숨김 pane fit 방지**: `display:none` pane 에서 `fit.fit()` 호출 시 clientWidth=0 →
    cols/rows 오계산 → PTY 와 xterm 폭 불일치로 bash readline 이 줄을 잘못 redraw 해
    입력 텍스트가 중복/깨져 보였음. `fitAndResize()` 가 visible 일 때만 + `requestAnimationFrame`
    으로 layout flush 후 측정하도록 변경.
  - **중복 attach 가드**: init 복원 + race 로 같은 sessionId 가 두 번 attach 되면 같은
    PTY 에 WS 2개 → echo 중복. `if (sessions[sessionId]) { activate; return }` 가드.
- **Claude Code 등 풀스크린 TUI 렌더**: 위 convertEol + 정확한 cols/rows 로 alternate
  screen / 커서 위치 제어가 정상 동작. `scrollback:5000` 으로 xterm 자체 스크롤백도 확보.
- **컨테이너 리사이즈 대응**: `ResizeObserver(#term-panes)` 추가 — 사이드바 접기/펼치기,
  레이아웃 settle 등 window resize 로 못 잡는 크기 변화에도 active 터미널 refit.

### Note

- 코드 검토 기반. 운영 배포 후 (a) 터미널에서 작업 → 다른 메뉴 갔다 복귀 시 출력 유지,
  (b) 긴 명령어 입력 시 텍스트 정상, (c) `claude` 실행 시 TUI 정상 표시 확인 권장.

## [1.33.2] - 2026-05-29 — 17차 점검 회수 (모바일 collapsed 충돌 잔존 + scratch move race + QUALITY 2)

17차 정밀점검: 직전 cycle(v1.32.0/v1.33.0/v1.33.1) 회귀검증 — A-1(scratch projectRoot
특수화) PathSafety 우회 안전 확정, A-2(logback)/A-3(categoryNav) PASS, A-4(모바일 CSS)
FAIL. CRITICAL 0, BUG 2 + QUALITY 2 회수.

### Fixed

- **BUG-1 (모바일 헤더 깨짐 잔존)**: v1.33.1 이 두 768px 블록 충돌은 고쳤으나, first-paint
  인라인 스크립트가 **viewport 폭 무관하게** `localStorage collapsed=1` 이면
  `data-sidebar-collapsed` 를 설정 → 전역 collapsed 룰(specificity (0,2,1))이 768px
  media 가로 헤더 룰((0,1,0))을 이겨, 데스크톱에서 접어둔 사용자가 모바일로 오면 가로
  헤더가 56px 로 찌그러지고 라벨이 숨겨졌음. 인라인 스크립트를 `matchMedia('(max-width:768px)')`
  인지 + resize(change) 대응으로 변경 — 모바일 폭에선 collapsed 무시(가로 헤더 우선).
- **BUG-2 (scratch 마이그레이션 move race)**: `ensureScratchProject` 가 `Files.move`
  실패(권한/cross-device)해도 `updateSourcePath` 를 무조건 실행 → 데이터는 oldP 에
  남고 DB 는 빈 newPath 를 가리켜 기존 scratch 대화가 고아화. move 성공(또는 옮길
  디렉토리 없음) 시에만 DB 갱신, 실패 시 보류하여 다음 진입 재시도. `ensureScratchDirsExist`
  도 DB 의 실제 경로(effectivePath) 기준으로 보장.

### Changed

- **Q-1**: `SettingsNav.categoryNav` 의 active 판정을 `currentPath.substringBefore('?')`
  비교로 — query string 동반 경로에서도 정확(현재 미발화 잠재 회귀 예방).
- **Q-2**: `PromptTemplateStore.read()` 파싱 실패 시 `.corrupt.<ts>` 백업 후 빈 storage
  진행 — 이전엔 빈 storage 로 진행 후 persist 가 corrupt 파일을 덮어써 영구 소실 위험.

### Note

- A-1 scratch projectRoot 특수화: `vibecoderDir` 가 내부에서 PathSafety 재검증 +
  projectId regex(first-char `[a-z0-9]`)가 `__scratch__` 주입 차단 → 우회 위험 없음 확정.
- 코드 검토 기반. 모바일 실측 권장(데스크톱 접은 채 모바일 진입 케이스).

## [1.33.1] - 2026-05-29 — 모바일 헤더 메뉴 높이 깨짐 (중복 768px 미디어쿼리 충돌)

사용자 보고: 모바일 화면에서 메뉴 사용 시 상단 헤더 메뉴 높이가 깨짐.

### Fixed

- **모바일(≤768px) 사이드바 레이아웃 깨짐**: `admin.css` 에 `@media (max-width: 768px)`
  블록이 **두 개**, 상반된 전략을 정의하며 충돌했음:
  - v1.16.0 블록: 좌측 **56px 세로 collapsed**(라벨/타이틀/quota/로그아웃 hide).
  - v1.7.6 블록: 상단 **가로 헤더 nav**(grid 1fr, flex-row).
  - CSS cascade/specificity 로 둘이 뒤섞여 — grid 는 가로(1fr)가 이기는데 라벨 hide 는
    `.layout .nav-links a .nav-label`(specificity 높음)이 이겨, **가로 헤더인데 라벨이
    숨겨지고 padding/높이가 두 규칙 혼합으로 깨졌음**.
  - 56px collapsed 블록 제거 → 가로 헤더 nav 단일 블록으로 통합(라벨 정상 표시).
    데스크톱 접기는 `[data-sidebar-collapsed]` 룰이 그대로 담당.
- **모바일 fullbleed 탭 높이 기준 붕괴(부수)**: 가로 nav 블록에 `grid-template-rows:
  auto minmax(0,1fr)` 추가. 이전엔 모바일 1칼럼에서 rows 미설정이라 `#project-tabs-root
  height:100%`(프로젝트/설정/도구 탭)의 높이 기준이 무너졌음.
- 가로 헤더에 불필요한 사이드바 접기 토글 + quota-pill `display:none`. 기본 `.nav-links`
  의 `flex:1/overflow:auto`(세로 스크롤용)를 가로용으로 override.
- `admin.css` cache-bust `?v=1.33.1` (shell + EnvSetupTemplates).

### Note

- 사용자 요청대로 코드 검토 기반 수정. 모바일 실측은 못 했으나 중복 미디어쿼리 충돌이
  확정 원인. 배포 후 모바일에서 헤더 메뉴 + 탭 페이지 확인 권장.

## [1.33.0] - 2026-05-29 — 서버 로그 / 스크래치를 워크스페이스 밖으로 분리

사용자 요청: 워크스페이스 폴더에서 서버 데이터(`vibe-coder-server-data` = 서버 로그)와
스크래치(`__scratch__`)를 다른 폴더로 이동.

### Changed

- **서버 로그 → `/data/logs` (운영) / `./logs` (dev)**: 이전엔 `logback.xml` 의 파일
  appender 가 **상대경로** `vibe-coder-server-data/logs/server.log` 였고, 서버 실행
  cwd 가 `/workspace` 라서 `/workspace/vibe-coder-server-data/logs/` 에 rolling 로그가
  쌓여 프로젝트 목록·파일 탐색에 노이즈로 섞였음(DB 미등록이지만 워크스페이스 안).
  - `logback.xml` 에 `LOG_DIR` property(`${VIBECODER_LOG_DIR:-logs}`) 도입.
  - compose 에 `VIBECODER_LOG_DIR=/data/logs` 추가 — server 볼륨(`/data`)에 영속, 워크스페이스 밖.
  - dev 는 미설정 시 `./logs`(gradle cwd = 프로젝트 루트 → `./workspace` 밖).
- **스크래치 `__scratch__` → `.vibecoder/__scratch__`**: General Chat ghost 프로젝트가
  워크스페이스 루트에 평범한 프로젝트처럼 보이던 것을 서버 메타 sidecar(`.vibecoder/`)
  안으로 이동. `WorkspacePath.projectRoot(SCRATCH_ID)` 만 `.vibecoder/__scratch__` 를
  반환하도록 매핑 — ClaudeSessionManager(cwd)·ProjectFileBrowser 등 projectRoot 사용처
  전부 일관. `BackupService.excludedSegments`(`.vibecoder/__scratch__`)와도 정합.
  - **자동 마이그레이션**: `ensureScratchProject` 가 기존 `source_path`(`/workspace/__scratch__`)
    와 새 경로가 다르면 디렉토리 `Files.move` + DB `source_path` 갱신(`ProjectRepository.
    updateSourcePath` 신규). `/chat` 첫 진입 시 1회 자동 수행.

### Migration

- **운영(컨테이너)**: `docker compose pull && up -d` 후 —
  - 서버 로그: 새 로그는 `/data/logs`(`vibe-coder-data/server/logs`)에 쌓임. 기존
    `vibe-coder-data/workspace/vibe-coder-server-data/` 는 운영자가 삭제해도 됨(과거 .gz).
  - 스크래치: `/chat` 한 번 방문 시 `vibe-coder-data/workspace/__scratch__` →
    `…/workspace/.vibecoder/__scratch__` 자동 이동 + DB 갱신.
- compose env `VIBECODER_LOG_DIR` 미지정 시 기본 `/data/logs` (compose.yml 기본값).

## [1.32.0] - 2026-05-28 — 설정 페이지 기능 재분류 (quicklinks 평면 중복 → 카테고리별 sub-nav)

사용자 요청: 설정 메뉴 항목 재검토. 일반설정에 따로 분류된 하부항목(quicklinks)
재분류.

### Changed

- **설정 sub-page 를 소속 카테고리로 분산**: 기존엔 일반설정(/settings) 페이지의
  quicklinks 카드가 11개 chip(keystores/ssh-key/cache/cors/email/webhook/push/
  git-integrations/2fa/webauthn/devices)으로 **모든 sub-page 를 평면 중복 나열**해
  8탭 카테고리 구조와 충돌했고, 정작 보안/알림/모니터링 탭의 대표 페이지엔 sub-page
  로 가는 link 가 없어 quicklinks 가 유일 진입점이었음.
  - `SettingsNav.categoryNav(currentPath, lang)` 신규 — 현재 카테고리의 sub-page
    chip sub-nav 를 생성(active 표시 포함). 각 카테고리 대표 페이지 상단에 배치:
    - **보안**(/password): 비밀번호 · 2FA · Passkey · 디바이스 · CORS
    - **알림**(/settings/email): 이메일 · Webhook · Push
    - **모니터링**(/usage): Claude 사용량 · 감사로그
    - **빌드환경**(/env-setup) · **프롬프트**(/prompts)는 기존 자체 chip 유지.
  - 일반설정(/settings)의 quicklinks 카드 제거 — 이제 language + 서버/워크스페이스/
    Claude/빌드 config 만.
  - i18n: `settings.cat.password/usage/audit` 신규(en/ko). dead key 6종
    (`settings.quicklinks.title/body/keystores/sshKey/cache/gitIntegrations`) 제거,
    categoryNav 가 재사용하는 7종(cors/email/webhook/push/twoFa/webauthn/devices)은 유지.

## [1.31.2] - 2026-05-28 — 16차 점검 회수 (SymbolFinder 연산자 우선순위 + ApiPath 주석 정정)

16차 정밀점검: 직전 대량 변경(v1.31.0 ACL 29곳 / v1.31.1 WS SSOT·CSRF) 회귀검증
전부 PASS, CRITICAL/BUG 0. QUALITY 2건만 회수.

핵심 회귀검증 결론:
- A-1 (ACL 29핸들러 일괄): 모든 `requireProjectAccessOrThrow` 가 첫 respond 이전
  위치 — double-respond/오삽입 없음.
- A-2 (StatusPages csrf/project_forbidden redirect): `Accept: text/html` 판정이라
  JSON 클라이언트(Android)는 기존 JSON 응답 유지 — redirect HTML 로 안 깨짐.
- A-4 (WS ApiPath 교체): `wsAgentConsoleLogs` 가 pathSeg 로 `{}` 를 `%7B%7D` 인코딩
  하지만 Ktor routing 이 등록 path 를 URL-decode 후 placeholder 매칭하므로 정상
  (JsonAdminRoutes 의 동일 패턴이 v0.64.0+ 운영 중인 선례로 확인).

### Fixed

- **Q1 — `SymbolFinder.isValidSymbol` 연산자 우선순위 버그**: `s.first().isLetter()
  || s.first() == '_' && s.all {...}` 가 `&&` 우선이라 `isLetter() || ('_' && all)`
  로 파싱 → 첫 글자가 letter 면 나머지 글자 검증을 건너뛰어 `foo bar`/`foo$x` 같은
  입력이 통과(매치 0 으로 새던 기능 결함, Regex.escape 안전망 덕에 인젝션은 불가).
  leading-char OR 을 괄호로 묶어 수정.

### Changed

- **Q2 — ApiPath.kt 동적 path 주석 정정**: "pathSeg 가 `{}` 를 encode 하므로 라우터
  template 에 못 들어감, 라우터는 hardcoded 써야 함" 이라는 주석이 실제 동작과
  **반대**였음 — JsonAdminRoutes/WsRoutes 가 `userRole("{userId}")` 등 pathSeg 함수를
  라우터 등록에 광범위하게 쓰고 정상 작동(Ktor 가 등록 path URL-decode 후 placeholder
  매칭). 다음 메인테이너가 주석을 믿고 hardcoded 로 되돌리면 SSOT drift 위험이라
  주석을 실제 동작에 맞게 갱신.

## [1.31.1] - 2026-05-28 — 15차 QUALITY 잔여 정리 (CSRF 일관성 + WS SSOT + lifecycle + 터미널 UX)

v1.31.0 에서 다음 cycle 로 미뤘던 QUALITY 항목 10건 일괄 정리. 단일 admin 영향은
작으나 일관성·방어선 강화.

### Changed

- **A-Q1: Sub-agent JSON cookie 분기 CSRF**: `authorizeAgentJson` 의 cookie(SSR fetch)
  분기가 mutation(prompt/cancel/new)에 CSRF 미검증이었음 — `requireWrite` 시 body
  `_csrf` 검증 추가 + 콘솔 JS 의 prompt/cancel fetch 에 `?_csrf=` 송신. Bearer 분기는
  cookie 미첨부라 무관.
- **A-Q2: WS path ApiPath SSOT (CLAUDE.md §8.A)**: build/console/env-setup/projects/
  sub-agent WS 5개 path 가 hardcoded → `ApiPath.wsBuildLogs/wsConsoleLogs/wsEnvSetupLogs/
  wsAgentConsoleLogs/WS_PROJECTS_STATE` 상수 참조로 교체(터미널 WS 는 v1.27.1 에 이미
  회수). `WS_PROJECTS_STATE` 상수 신규. **vibe-coder-android shared/ 동기 권고**(값
  동일 — wire 변경 아님, SSOT 정합용).
- **A-Q3: HistoryRoutes 비-constant-time CSRF 비교**: 평문 `!=` → `CsrfTokens.isValidCsrf`
  (constant-time) 통일. timing side-channel 제거.
- **B-Q1: TaskQueue scope shutdown 부재**: `shutdown()` 추가 + ServerMain JVM hook 등록
  (진행 중 빌드 job graceful cancel 신호). projectMutexes 무한증가는 bounded(프로젝트
  수)라 보류.
- **B-Q2: ProjectArchiver 심볼릭 링크 zip 유출**: `Files.walk` 가 링크 노드의
  `isRegularFile` 를 따라가 워크스페이스 밖 호스트 파일(/etc/passwd 등)을 가리키는
  링크를 zip 에 포함할 수 있었음 — `isSymbolicLink` skip 추가.
- **B-Q3: ProcessRunner reader join 무한대기 가능성**: `finally` 의 stdout/stderr
  `join()` 을 `withTimeoutOrNull(2_000)` 으로 감싸 외부 cancellation 경로에서 자식이
  stdout 을 계속 흘려도 2초 후 reader job 강제 cancel.
- **B-Q4: GitWriter push origin scheme 미검증**: push 전 `git remote get-url origin`
  의 scheme 검증(https/http/ssh/git 만 허용) — origin 이 file:// 등 로컬 scheme 으로
  바꿔치기된 경우 자격증명 유출 차단. commit 은 유지, push 만 skip.
- **C-Q1: dead i18n key `term.status.connectingWs` 제거** (en/ko) — v1.30.0 다중세션
  개편 후 사용처 0.
- **C-Q2: 터미널 빈 상태 안내**: 모든 탭을 닫으면 `#term-panes` 가 빈 검은 박스로
  남던 것 → "활성 세션 없음 / + 새 세션" placeholder 표시. i18n `term.empty` 신규.
- **C-Q3: 터미널 세션 라벨 안정화**: monotonic counter("세션 5")가 닫았다 열면 어긋나고
  서버 sessionId 와 무관해 혼란 → `세션 #<sessionId 6자>` 로 변경(안정 식별).

## [1.31.0] - 2026-05-28 — 15차 전체 정밀 재점검 핵심 일괄 회수 (Project ACL + CSRF + 데이터유실 + lifecycle)

3영역 병렬 전체 재점검(보안 횡단 / 빌드·도메인·lifecycle / UI·알림·백업) 결과,
CRITICAL 2 + 보안/correctness BUG 다수를 핵심 일괄 회수. QUALITY 잔여는 다음 cycle.

### Fixed

- **CRITICAL — 대화 히스토리 자동 archive 데이터 유실**: `ConversationArchiver` 가
  `session-<sid>.json` 이 이미 있으면 안 쓰면서 DB turn 은 무조건 삭제 → 같은
  sessionId 가 `--resume` 으로 재활성돼 새 turn 이 쌓인 뒤 재archive 되면 새 대화가
  영구 소실. 파일명에 archive 시각 포함(`session-<sid>-<epochMs>.json`) + 무조건
  write 로 매 archive 를 고유 파일에 보존.
- **CRITICAL — 프로젝트 SSR 전체 Project ACL 누락**: `WebProjectRoutes` 의 per-project
  SSR 핸들러 29개(console/builds/files/git/delete/edit/view/...)가 ACL 미검증 —
  history/sub-agent/JSON/console-WS 는 검증하는데 메인 SSR 만 비대칭 누락. 멀티유저
  (member role + ProjectAcl) 시 ACL 밖 프로젝트 접근·변조 가능(단일 admin 영향 0).
  throw 방식 `requireProjectAccessOrThrow(sess, projects, id)` 헬퍼를 모든 핸들러에
  일괄 적용 + StatusPages 가 `project_forbidden` + 브라우저 폼이면 `/projects` redirect.
- **BUG — `DELETE /api/projects/{id}` JSON ACL 누락**: GET 은 `canUserAccess` 검증하는데
  delete 만 빠져 ACL 제한 member 가 임의 프로젝트 삭제 가능. `requireProjectAcl` 추가.
- **BUG — build logs WS Project ACL 누락**: `/ws/projects/{id}/builds/{buildId}/logs` 가
  console WS 와 달리 ACL 미검증(buildId 만 알면 타 프로젝트 빌드 로그 스트리밍).
  `handleLegacyLogStream` 에 옵션 ACL 추가, build WS 가 projectId 전달. env-setup WS 는
  글로벌 작업이라 미적용.
- **BUG — PushRoutes 2건 + SubAgent `/new` CSRF 미검증**: 폼은 `_csrf` hidden input 을
  렌더하나 핸들러가 검증 누락 → cross-site POST 로 push 삭제/broadcast, sub-agent 세션
  재시작 가능. body `_csrf` 검증 + SSR redirect 추가.
- **BUG — ClaudeLoginService shutdown hook 미등록 (process/coroutine leak)**: 진행 중
  claude OAuth 로그인의 `script`/`claude` 자식 프로세스 + drainOutput/watchProcess job
  이 graceful 종료 경로 없이 JVM 강제 종료에만 의존. `shutdown()` 추가 + ServerMain hook.
- **BUG — webhook 빌드 enqueue 의 409→500 변질**: 키스토어 미등록(`keystore_required`
  409 ApiException)이 `runCatching` 에 잡혀 항상 500 으로 응답 → 외부 CI 가 "서버 오류"로
  오인. ApiException 은 그 status code 로 응답하도록 분기.
- **BUG — ConfigPersistence 동시쓰기 race**: `/settings` 동시 저장 시 고정 tmp 파일명
  공유로 깨진 YAML move 가능. `@Synchronized` 직렬화 + tmp 에 고유 suffix.

### Added

- `CsrfTokens` 는 v1.28.1 의 `isValidCsrf` 재사용. `requireProjectAccessOrThrow`
  (throw 기반 ACL, label 무관 일괄 적용용) 신규.

### Notes (PASS 확인 / 보류)

- 키스토어 임의 생성 금지 정책: 5개 빌드 진입 전수 `requireKeystoreOrThrow` 커버
  유지(PASS). debug.keystore 자동생성 회색지대는 운영자 보류 항목 유지.
- B-미점검 영역(GitClone URL whitelist, projectId regex, 백업 traversal, SubAgent/
  Claude/Terminal 매니저 lifecycle, 파일브라우저 PathSafety, 알림 SSRF allowlist) 전부 PASS.
- QUALITY 잔여(WS path ApiPath SSOT, HistoryRoutes 비-constant-time CSRF, SubAgent
  cookie 분기 CSRF, ProjectArchiver symlink zip, TaskQueue mutex, GitWriter push origin,
  dead i18n key `term.status.connectingWs`, 터미널 빈 상태/세션 라벨 UX)는 단일 admin
  영향 작아 다음 cycle 로.

## [1.30.1] - 2026-05-28 — 14차 점검 회수 (터미널 세션 owner ACL + CSRF query 경로 SSR redirect)

14차 정밀점검 결과: A-1/A-3/A-4 회귀검증 PASS, B 미결 4개(백업 traversal /
SubAgent leak / GitClone URL validator / projectId regex) 전부 PASS. v1.30.0
터미널 다중세션에서 신규 결함 발견 + v1.28.1 CSRF 잔존 케이스 회수.

### Fixed

- **CRITICAL — 터미널 세션 목록의 owner 필터 부재**: `GET /api/terminal/sessions` 가
  `manager.list()` 로 전체 사용자 세션을 반환(owner 필터 없음). WS 핸드셰이크는 owner
  ACL 을 강제하는데 REST list 만 비대칭이라, 멀티 admin 시 타 owner 의 sessionId/workdir
  누출 + `/terminal` init 이 타 owner 세션까지 attach 시도 → 거부되는 orphan xterm
  pane 생성. `TerminalSessionManager.list(ownerUserId)` 오버로드 추가(본인 세션만,
  null-owner 제외) + REST 핸들러가 `userIdOf()` 전달. 단일 admin 환경 실피해 0이나
  WS↔REST ACL 비대칭 구조 결함 회수.
- **BUG — 터미널 DELETE owner 미검증**: `DELETE /api/terminal/sessions/{id}` 가 owner
  검증 없이 admin 이면 타 owner 세션 강제 종료 가능. WS 와 동일하게 `ownerUserId`
  비교 후 불일치 시 403 추가.
- **BUG — CSRF query/header 경로 실패 시 JSON 페이지 붕괴 잔존**: v1.28.1 은 body 폼
  (keystore/ssh-key/webauthn-delete)만 SSR redirect 로 회수했고, `verifyCsrfFromQueryOrHeader`
  (backup/mcp/env-setup/file-upload/webauthn-passwordless)는 여전히 `ApiException(403)`
  → JSON 응답. pepper 회전(서버 재시작) 후 stale 폼 제출 시 keystore 와 동일한 페이지
  붕괴 재현 가능. `StatusPagesPlugin` 의 ApiException 핸들러에서 csrf 코드 + 브라우저
  폼 navigation(Accept: text/html)이면 referer path(open-redirect 방지 위해 host 무시)
  로 `?err=csrf` redirect — JSON API(Accept: application/json)는 기존 JSON 유지.

### Notes (PASS 확인)

- A-1 CSRF body 수정: keystore create 는 `receiveParameters()` single-call 로 csrf +
  필드 추출(이중 호출 회귀 없음). 나머지 query/multipart 경로도 정상(위 BUG 는 UX 잔존).
- A-3 탭: project-tabs.js 더보기 로직 Settings/Tools 에서 null-safe no-op, overflow
  탭 activate 재사용, cleanup race fix 유지.
- A-4 trustForwardedFor: 기본 false 시 origin==local 동작 유지.
- B 미결 4개: 백업 다운로드 `..`/절대경로 거부 + normalize startsWith 이중 가드,
  SubAgent 는 exit.collect 패턴 미사용(reader EOF 자연 종료), GitClone URL validator
  whitelist 방식(file:/gopher: 등 else 거부), projectId regex first-char anchor 로
  `..`/`.` 차단 — 전부 결함 없음.

## [1.30.0] - 2026-05-28 — 터미널 다중 세션 탭

사용자 요청: 터미널 상단에 다중 세션 탭 추가.

### Added

- **`/terminal` 다중 세션 탭**: 기존엔 페이지 1개당 단일 PTY 세션이었으나, 이제
  상단 탭바로 여러 세션을 동시에 열고 즉시 전환. 세션마다 독립 xterm 인스턴스 +
  WebSocket. "+ 새 세션" 버튼으로 추가, 각 탭의 × 로 닫기(서버 `DELETE
  /api/terminal/sessions/{id}` + WS close + xterm dispose).
  - 서버 per-user 한도(`TerminalSessionManager.MAX_SESSIONS_PER_USER=4`)와 연동 —
    4개 도달 시 "+ 새 세션" 비활성 + 안내. POST 가 429(`session_limit`) 면 동일 안내.
  - 페이지 진입 시 `GET /api/terminal/sessions` 로 기존 활성 세션을 탭으로 복원
    (WS 재연결 — 과거 출력은 SharedFlow replay=0 이라 미표시, 세션 자체는 유지),
    활성 세션이 없으면 새 세션 1개 자동 생성.
  - 활성 탭만 표시(나머지 display:none, WS/PTY 는 백그라운드 유지), 창 리사이즈
    시 활성 세션만 fit + `terminal_resize` 송신.
  - i18n: `term.newSession` / `term.sessionLabel` / `term.limitReached` /
    `term.closeSession` (en/ko).

## [1.29.0] - 2026-05-28 — 프로젝트 콘솔 탭 빈도 재구성 (저빈도 6개 → 더보기)

사용자 요청: 콘솔 상단 탭 메뉴 분석 후 사용 빈도 낮은 항목을 "더보기"로 이전.

### Changed

- **프로젝트 탭바 primary/overflow 분리**: 기존 12개 탭이 전부 상단에 노출돼 가로
  스크롤이 길었음. Android 개발 빈도 기준으로 분류:
  - **상단(primary, 6개)**: Console(기본·Claude 대화) / Builds / Files / Git /
    Agents / History — 일상 워크플로.
  - **더보기(overflow, 6개)**: Symbols / Stats / Deps / Wrapper / Automation /
    Env-files — 저빈도 분석·도구.
  - iframe prerender 는 **전체 유지** — 탭 전환 즉시성·백그라운드 WS 연결 보존.
    탭 "버튼"만 더보기 드롭다운으로 이동(`data-tab-btn` 동일 → `project-tabs.js`
    기존 탭 로직 재사용, iframe pane 공유).
  - 더보기에서 overflow 탭 선택 시 드롭다운 자동 닫힘 + summary 에 현재 탭명 표시
    ("더보기: 통계 ▾") — 상단 탭바엔 primary 만 있어 위치 파악용. 기존 global
    /usage 외부 link 는 구분선 아래 유지.
  - `project-tabs.js` cache-bust `?v=1.29.0` (Project/Settings/Tools 3개 탭 페이지
    공유 — Settings/Tools 는 더보기 없어 신규 로직 no-op).

## [1.28.1] - 2026-05-28 — 긴급: SSR 폼 CSRF body/query 불일치 (키스토어 생성 진행불가 + 페이지 JSON 붕괴)

운영 사용자 보고: 키스토어 작성 버튼 → `{"code":"csrf_token_mismatch",...}` JSON 만
보이고 사이드바 포함 UI 전부 사라짐(브라우저 JSON viewer).

### Fixed

- **SSR 폼 CSRF body/query 검증 불일치 (구조 버그)**: 일부 일반 form POST 가
  `CsrfTokens.verifyCsrfFromQueryOrHeader` (query string `?_csrf=` / `X-CSRF-Token`
  헤더만 검사)를 쓰는데, 정작 폼은 csrf 를 **hidden input(body)** 으로 보냈다 →
  서버가 body 의 `_csrf` 를 못 봐 **CSRF 항상 실패** → `ApiException(403)` 이
  JSON 으로 반환되고, 폼 navigation 이라 브라우저가 그 JSON 을 전체 페이지로
  표시(Firefox JSON viewer 의 pretty-print 체크박스)하며 SSR UI 전체가 사라짐.
  - 영향 라우트: `POST /settings/keystores` (생성), `/settings/keystores/{pkg}/delete`,
    `/settings/keystores/{pkg}/apply/{projectId}` (키스토어 — **사용자 차단**),
    `POST /settings/ssh-key/regenerate` (SSH 키 재생성 — 동일 차단).
  - 수정: 세 keystore + ssh-key 핸들러를 `call.receiveParameters()` 로 body 를 읽고
    신규 `CsrfTokens.isValidCsrf(call, body["_csrf"])` 로 검증. 실패 시 **JSON 예외
    대신 SSR flash redirect** (`?flash=err:csrf` / `?err=csrf`) — 페이지가 JSON 으로
    깨지지 않고 "보안 토큰 만료, 새로고침" 안내. multipart 폼(파일 업로드 / MCP /
    claude-auth / backup)은 원래 query 방식이라 영향 없음.
- **Webauthn passkey 삭제의 CSRF 검증 누락 (보안)**: `POST /webauthn/delete/{rowId}`
  가 CSRF 검증 자체를 안 하고 있었음(작동은 했으나 cookie 기반 SSR POST 가 무방비).
  같은 `isValidCsrf` body 검증 추가. SameSite=Lax 가 1차 방어였지만 defense-in-depth.

### Added

- `CsrfTokens.isValidCsrf(call, providedFromBody)` — 예외 없이 Boolean 반환하는
  SSR 친화 검증 헬퍼. 호출자가 JSON 예외 대신 flash redirect 로 처리 가능.
- i18n `ks.flash.csrfExpired` (en/ko) — keystore 페이지 CSRF 만료 안내.

### Note

- 사용자 요청에 따라 코드만으로 수정. CSRF mismatch 의 직접 원인(body vs query)을
  근본 회수했으므로 재시작 후 pepper 회전과 무관하게 정상 동작. 배포 후 키스토어
  생성 흐름 확인 권장.

## [1.28.0] - 2026-05-28 — 13차 점검 회수 (프록시 IP 식별 + history ACL + webhook body cap + SMTP passwordFile)

13차 정밀점검 결과: A 회귀검증 4건(v1.27.4 의 Q2/B1/Q4/Q3) 모두 PASS, CRITICAL 0,
BUG 2 + QUALITY 2 신규 발견. 사용자 승인 하에 전부 회수.

### Added

- **B-1: `security.trustForwardedFor` 플래그 + 전체 클라이언트 IP 식별 통일** — 운영은
  openresty→localhost:17880 구조라 서버 입장에서 **모든 외부 IP 가 프록시 IP 하나로
  합쳐져**, v1.27.4 의 IP 차단(30회/24h)·rate-limit per-IP 격리가 무력화되던 문제(B-1).
  - `XForwardedHeaders` 플러그인을 `trustForwardedFor=true` 일 때만 조건부 설치
    (`ktor-server-forwarded-header` 의존성 추가). 기본 false(LAN 직노출 — 스푸핑 방어).
  - IP 를 읽는 16개 라우트/플러그인의 `call.request.local.remoteHost`(직접 TCP peer,
    XFF 무시) 를 **전부 `call.request.origin.remoteHost`(XFF 반영)** 로 통일 — 차단/
    rate-limit/audit 이 일관되게 실제 클라 IP 기준. false 면 origin==local 이라 기존
    동작 유지.
  - env override `VIBECODER_TRUST_FORWARDED_FOR` 추가 — 운영(compose)에서 server.yml
    수정 없이 켤 수 있음.
  - **운영 적용**: openresty 가 `X-Forwarded-For` 를 세팅·전달하는지 확인 후
    `trustForwardedFor: true` (또는 env) 로 켜야 IP 차단/rate-limit 이 실제 클라 IP
    기준으로 복구됨. 신뢰 프록시 뒤에서만 켤 것(직노출 시 헤더 스푸핑 우회 위험).

### Fixed

- **B-2: 대화 히스토리 SSR 라우트의 Project ACL 누락** — `/projects/{id}/history`,
  `/history/export`, `/history/import` 세 SSR 라우트가 `requireSessionOrRedirect` 만
  호출하고 `requireProjectAccessOrRedirect` 를 빠뜨려, ACL 미부여 member/viewer 가
  타 프로젝트 대화 전문을 열람·export·import(덮어쓰기) 할 수 있었음. memo/star JSON
  variant 는 v0.75.1(H3)에서 ACL 보강됐으나 SSR 경로가 같은 수정에서 누락된 비대칭.
  세 라우트 모두 ACL 가드 추가.

### Changed

- **Q-1: webhook body 크기 가드** — `POST /api/webhooks/build/{id}` 가
  `receiveText().take(MAX_BODY_BYTES)` 로 char 단위 truncate(멀티바이트면 byte 한도
  초과) + 전체 body 선 적재였음. `Content-Length` 선체크(413) + byte 기준 검증으로
  교체.
- **Q-2: SMTP `passwordFile` 읽기 가드** — `EmailNotifier.resolvePassword` 가 admin
  config 경로를 정규 파일/크기 검증 없이 `readString`. 정규 파일 + 4KB 상한 가드 추가
  (디렉토리/특수파일·거대 파일 OOM 방어). Docker secret(`/run/secrets/*`) 호환 위해
  심볼릭은 따라감(NOFOLLOW 미적용).

### 미결 (다음 차수)

- 13차에서 정독 못 한 영역: 백업 다운로드 traversal, SubAgentSessionManager exit
  collector leak 패턴, GitCloneService URL validator 재우회, projectId regex. 다음
  점검 우선 대상.

## [1.27.5] - 2026-05-28 — 긴급: 탭 UI iframe cleanup race (설정 탭 2줄 중복 + 키스토어 진행불가)

운영 사용자 보고: (1) 설정 화면 탭바 2줄 중복, (2) 키스토어 생성 시 다음 화면이
제대로 안 보여 진행 불가. 두 버그는 **같은 root cause** — `project-tabs.js` 의
iframe cleanup load race.

### Fixed

- **iframe cleanup load race (탭 UI 전반)**: `project-tabs.js` 는 `defer` 로 로드
  되고 탭 iframe 은 `loading="eager"` 다. 가벼운 settings sub-page(`/settings`,
  `/password`, `/settings/keystores` 등)는 defer 스크립트가 `addEventListener('load',
  cleanup)` 를 붙이기 **전에** 이미 로드 완료될 수 있어, 지나간 load 이벤트를
  놓쳐 cleanup 이 영영 안 걸렸다. 결과: iframe 내부의 `nav.sidebar` + `.settings-tabs`
  가 숨겨지지 않아 (1) 바깥 탭바 + 내부 탭바 **2줄 중복**, (2) keystore 등 sub-page
  가 내부 sidebar 에 밀려 **레이아웃이 깨져 "다음 화면이 안 보임"**. cleanup 함수를
  추출하고 등록 시점에 `iframe.contentDocument.readyState === 'complete'` 면 즉시
  1회 실행하도록 수정. ProjectTabsTemplate / SettingsTabsTemplate / ToolsTabsTemplate
  3개 탭 페이지 모두 동일 JS 사용 → 일괄 해소.
- **캐시버스트 부재**: `project-tabs.js` 가 `?v=` 없이 참조돼 브라우저가 기존
  캐시를 쓰면 위 fix 가 반영 안 됨. 3개 참조처에 `?v=1.27.5` 추가.
- v1.27.3 의 Q1 회수(`!fullbleed` 가드로 외부 shell tabBar 생략)는 **부분적**이었음을
  정정: 외부 maybeTabs 는 껐지만 진짜 중복 원인은 iframe 내부 sub-page 의 tabBar 가
  cleanup race 로 안 숨겨진 것이었다. 본 cycle 에서 근본 원인 해소.

### Note

- 사용자 요청에 따라 "코드만으로 추정 수정" — root cause 분석은 탄탄하나 운영
  화면 실측 검증은 못 함. 배포 후 브라우저 **강력 새로고침**(캐시버스트로 자동
  해소되지만) 후 설정 탭 / 키스토어 생성 흐름 확인 권장.

## [1.27.4] - 2026-05-28 — 12차 점검 회수 (IP 차단 무력화 + 업로드 오배치 + VNC CSWSH + webhook HMAC-only)

12차 정밀점검 범위 확대: 핵심 도메인(키스토어 정책 / 빌드 파이프라인 / 인증·세션 /
파일·경로 / WS 허브). 키스토어 정책(우선순위 1)은 5개 빌드 진입 전수 가드 PASS
확정(단 debug.keystore 자동생성 회색지대 Q1 은 운영자 판단 대기로 보류). 나머지
발견 항목 일괄 회수.

### Fixed

- **Q2 (Critical성 — IP 차단 보안 무력화)**: `AuthService.recordIpFailure` 의 window
  슬라이딩 판정이 `lockedUntilMs - IP_LOCK_DURATION_MS` 역산에 의존했는데, 미잠금
  상태(`lockedUntilMs=0`)에선 음수가 되어 `nowMs - (음수) = nowMs + 24h` 가 항상
  `IP_WINDOW_MS`(24h)보다 커짐 → `takeIf` 항상 false → **IP 실패 카운터가 매 실패마다
  1로 리셋 → 30회/24h IP 차단이 영원히 발동하지 않던** 보안 기능 무력화. `FailureState`
  에 `lastFailMs` 필드를 추가하고 `nowMs - it.lastFailMs < IP_WINDOW_MS` 로 정확히
  슬라이딩. (CLAUDE.md §9.B 의 v0.12.4 "IP 기반 로그인 실패 차단" 이 실제로는
  동작 안 하고 있었음 — 회수.)
- **B1 (업로드 오배치)**: `POST /projects/{id}/files/upload` 가 multipart part 를
  순차 처리하며 file part 를 만나는 즉시 `uploadStream(parentRel, ...)` 호출하는데,
  `parentRel` 은 `parent` FormItem 에서 채워짐 → file part 가 parent 보다 먼저 도착하면
  루트에 오배치. parent 를 form action 의 query param 으로도 전달해 part 순서 의존
  제거(FormItem 은 fallback). 경로 escape 는 아니었음(PathSafety 가 projectRoot 안으로
  제한) — 위치 무결성 결함.
- **Q4 (VNC CSWSH 갭)**: `/emulator/vnc/websockify` WS 가 cookie+admin 검증은 하지만
  `WsRoutes`/`TerminalRoutes` 가 일관 적용하는 Origin↔Host 검증을 누락. 실행 중
  에뮬레이터의 live VNC 채널(화면 캡처/입력 주입)이 cross-origin WS 로 탈취 가능.
  Origin 검증 블록 추가(코드베이스 WS 표준 정렬).

### Changed

- **Q3 (webhook 평문 secret → GitHub-style HMAC-only)** — **Wire change**: 빌드
  webhook(`POST /api/webhooks/build/{id}`)의 인증을 평문 secret 전송(`X-Vibe-Secret`
  헤더, TLS 의존)에서 HMAC-only 로 격상. sender 는 `X-Vibe-Secret-Id` +
  `X-Vibe-Signature`(=hex(HMAC-SHA256(secret, body)))만 보내고, server 는 저장된
  secret 으로 HMAC 재계산·constant-time 비교. 평문 secret 전송 구간이 사라져 유출
  위험 제거.
  - DB: `build_webhook_secrets.secret_hash` 컬럼이 이제 **평문 secret** 저장(대칭
    HMAC 키라 단방향 hash 부적합 — GitHub/GitLab 동일). 컬럼명은 마이그레이션 회피로
    유지(의미는 평문). 위험 등급 낮음(단일 admin LAN + secret 권한이 "빌드 트리거"로
    한정 + 빌드는 keystore 가드 통과 필요).
  - **마이그레이션**: 운영 DB 의 기존 webhook secret 은 **0개**라 무손실. 만약 다른
    환경에 기존 secret 이 있다면 sha256 저장값이라 HMAC 검증 불가 → 재생성 필요.
  - 라우터가 hardcoded path 대신 `ApiPath.buildWebhook("{projectId}")` SSOT 참조로
    교체(CLAUDE.md §8.A 강제 룰 준수). `X-Vibe-Secret` 헤더 / `sha256Hex` 헬퍼 제거.
  - **vibe-coder-android 동기 불필요**: webhook 은 외부 CI sender 용이라 Android
    client wire 와 무관. Wiki 의 webhook 사용 예시(curl)만 갱신 대상.

### Notes (12차 점검 PASS 영역)

- **키스토어 임의 생성 금지**: 5개 빌드 진입(JSON/SSR/WS/Cron/Webhook) 전수
  `BuildService.requireKeystoreOrThrow` 단일 가드 경유 — 미통과 시 Gradle 미기동으로
  AGP default 자동생성 도달 불가. 생성 경로(`KeystoreService.create`)와 사용 경로
  분리 정상. (보강 권고 Q1: release-only 가드의 debug variant 자동생성 회색지대는
  운영자 정책 판단 대기 — 본 cycle 보류.)
- **빌드 파이프라인**: timeout/cancel(SIGTERM)/dedupe/artifact 경로 정상.
- **인증/CSRF**: BCrypt12·계정 10회/15분 잠금·dual-auth·CSRF 전수 적용 정상.
- **파일/경로 안전**: PathSafety·symlink NOFOLLOW·SVG XSS 3-layer 모두 확인.

## [1.27.3] - 2026-05-28 — 11차 점검 회수 (설정 탭바 중복 + 누적 dead i18n key)

11차 정밀점검 범위 확대: 사이드바 글로벌 이전(v1.27.0) 주변 영역 + 누적
dead code / i18n 정합 / lang 전파. Critical/Bug 0건, Quality 2건 회수.
lang 전파(compile-time enforcement)·i18n fallback·터미널 코어는 안정 수렴 재확인.

### Fixed

- **Q1 (설정 통합 페이지 탭바 2줄 중복)**: `/settings/tabs` 진입 시 상단에 옛
  8-탭 텍스트 링크 바 + iframe 내부 버튼 탭바가 동시 노출되던 결함 (v1.22.0
  선재). `SettingsTabsTemplate` 가 `shell(currentPath="/settings", fullbleed=true)`
  로 호출 → `topLevelOf("/settings")=="settings"` 라 `shell` 이 외부 `<main>`
  상단에 `SettingsNav.tabBar` 를 주입하는데, iframe 안에도 같은 탭바가 그려져
  중복. `shell` 의 maybeTabs 조건에 `!fullbleed` 가드 추가 — fullbleed 페이지
  (자체 iframe 탭 UI 보유) 는 외부 tabBar 생략. `/tools/tabs` 는 `topLevelOf=="tools"`
  라 원래 영향 없었음 (이번 fix 로 두 페이지 동작 일관). 일반 settings sub-page
  (직접 접근, fullbleed=false) 의 tabBar 는 그대로 노출.

### Changed

- **Q2 (누적 dead i18n key 6종 삭제)**: v1.27.1 의 dead-key 정리 연장. 사용처
  0건 (정의 라인 외 참조 없음) key 를 en/ko 양쪽에서 제거:
  - `term.backToSettings` — v1.27.0 사이드바 글로벌 이전으로 "← 설정" 백링크
    제거됐으나 key 잔존 (직접 회귀 잔재).
  - `settings.tab.account` / `settings.tab.network` / `settings.tab.audit` —
    `SettingsNav.tabBar` 8개 탭에 없음 (audit 은 monitoring 탭에 흡수).
  - `nav.builds` / `nav.devices` — v0.69.0 사이드바 24→6 압축 후 top-level 아님.
  `Messages.t` fallback (미정의 key → key 자체 반환) 덕에 삭제만으로 회귀 없음.

### Notes

- 11차 점검 PASS 영역: lang 전파 (`lang: String` default 재도입 / hardcoded
  "en" 우회 0건), en/ko i18n 동수 (각 1305 key, orphan 없음), `Messages.t`
  fallback, v1.27.0 사이드바 이전 주변 (topLevelOf / navIcon / redirect) 정상.

## [1.27.2] - 2026-05-28 — 10차 점검 회수 (disabledPage 코드 블록 개행)

10차 정밀점검 결과: Critical/Bug 0건, v1.27.1 회수 11항목 전부 PASS,
운영자 self-lockout 위험 없음(strict admin 안전) 확정. 신규 Quality 1건만 회수.

### Fixed

- **Q-NEW-1**: `TerminalTemplates.disabledPage` (allowTerminal=false placeholder)
  의 `<code>` 블록이 `security:\n  allowTerminal: true` 로 표시되던 문제.
  Kotlin triple-quoted raw string 안의 `\n` 은 escape 가 아니라 리터럴
  백슬래시+n → YAML 안내가 한 줄로 잘못 노출. `<code>` 에 `white-space:pre`
  추가 + raw string 안에서 실제 줄바꿈 사용으로 2줄 정상 렌더.

## [1.27.1] - 2026-05-28 — 9차 점검 회수 (사이드바 stale link + WS ApiPath SSOT + 정책 비대칭 + race / leak)

v1.27.0 직후 9차 정밀점검에서 발견된 BUG 3 + Quality 6 일괄 회수. Critical
신규 회귀 없음.

### Fixed

- **B-1 (사이드바 stale link 404)**: `allowTerminal=false` 환경에서 사이드바
  "터미널" 메뉴 클릭 시 라우터가 미등록 → 404. v1.6.1 의 "외부 노출 환경에서
  비활성화" 시나리오가 사이드바 글로벌 이전 (v1.27.0) 후 회귀. SSR `/terminal`
  만은 항상 등록 + "비활성화됨" 안내 페이지 (`TerminalTemplates.disabledPage`)
  노출. REST/WS 는 그대로 미등록 — 외부 노출 환경의 보안 차단 의도 보존.
- **B-2 (WS handshake fallback 의 unhandled exception)**: cookie 없는 클라가
  handshake 후 첫 frame 보내지 않고 즉시 disconnect 하면 `incoming.receive()`
  가 `ClosedReceiveChannelException` throw. 이전엔 `TimeoutCancellationException`
  만 catch → 정상 disconnect race 가 error log 양산. `runCatching` 으로
  모든 예외 흡수, TimeoutCancellationException 만 warn / 그 외 debug.
- **B-3 (WS 라우터의 ApiPath SSOT 우회)**: `webSocket("/ws/terminal/{id}")` raw
  string → `webSocket(ApiPath.wsTerminal("{id}"))`. CLAUDE.md §8.A 의 "신규
  REST/WS endpoint 는 반드시 `shared/ApiPath.kt` 에 먼저 등록되고 라우터는
  그 상수를 참조" 강제 룰 준수. Android client (`vibe-coder-android`) 와
  path drift 방어. REST 3개는 이미 ApiPath 참조 — WS 만 누락.

### Changed

- **Q-1 (dead import)**: `TerminalRoutes.kt:33` 의 `import io.ktor.websocket.WebSocketSession`
  미사용 제거. v1.27.0 의 BuildService `MutableSharedFlow` cleanup 과 같은
  cycle 인데 누락.
- **Q-2 (admin 가드 정책 비대칭)**: REST `requireApiAdminOrFail` 가 `DevicePrincipal.isAdmin`
  (null userRole → "admin" backward-compat) 을 사용해 legacy unbound device 가
  admin 통과 가능했음. WS `authenticateTerminalWs` 는 `userRole != "admin"` strict
  검사. 같은 PTY 리소스에 두 정책. PTY = 호스트 root 등가 (컨테이너 vibe NOPASSWD
  sudo) 사실을 감안해 **strict 정책으로 통일** — REST 도 `principal.userRole == "admin"`
  검사. 단일 admin 환경에선 영향 0 (admin device 는 항상 userRole 채워짐).
- **Q-3 (per-user 한도 race)**: `TerminalSessionManager.create` 의 `count → put`
  사이에 race 가능 (탭 두 개 동시 POST 시 둘 다 count=N 통과). `synchronized(sessions)`
  블록으로 count + reservation atomic. PTY spawn 자체는 lock 밖 (외부 process
  fork 가 lock 안이면 다른 thread 무한 대기 위험).
- **Q-4 (exit collector leak)**: `sess.exit.collect { sessions.remove(id) }` 가
  SharedFlow 라 emit 후에도 suspend 유지 → 세션 종료 후에도 collector coroutine
  이 `scope.cancel` (shutdownAll) 까지 살아남아 누적. `runCatching { sess.exit.first() }`
  로 변경 — emit 1회 받고 자연 종료.
- **Q-5 (stale 주석 + dead i18n key)**:
  - `ServerConfig.kt:122-126` KDoc: `/settings/terminal` → `/terminal` 갱신
    + v1.27.1 SSR-only mode 사실 명시.
  - `server.yml:25-28` 주석: 같은 갱신.
  - i18n: `settings.quicklinks.terminal`, `env.subsettings.terminal` (en/ko 4개)
    사용처 0건 → 삭제. v1.27.0 의 chip 제거로 dead 됐었음.
  - 신규 i18n: `term.disabled.body`, `term.disabled.hint` (B-1 placeholder 페이지용).
- **Q-6 (terminal_input size cap)**: WS `TerminalInput.data` 가 size 상한 없이
  PTY 로 flush 되어 악성 / 자동화 실수 시 메모리 spike 가능. 64KB 상한 (`MAX_INPUT_CHARS`)
  + 초과 시 drop + warn log. xterm.js 정상 keystroke 단위는 수 byte 라 paste
  시나리오까지 안전 여유.
- **Q-7 (shutdown hook KDoc 불일치)**: `TerminalSessionManager.shutdownAll` 의
  KDoc 가 "`ApplicationStopping` 후크에서 호출" 이라 표기하지만 실제 호출 site
  는 `Runtime.addShutdownHook`. KDoc 를 "JVM shutdown hook" 으로 정정 — Ktor
  graceful stop 보다 광범위 (kill -TERM / docker stop 양쪽 cover).
- **Q-8 (`/settings/terminal` 301 → 302)**: legacy alias 의 본성 (영구 이전 아님,
  호환성 도우미) 에 일관되게 임시(302). 클라이언트 브라우저 캐시 강제 무효화
  여지 보존.

### Security

- Q-2 의 strict admin 정책 통일은 PTY = 호스트 root 등가 시나리오에서의 정책
  일관성 보강. 외부 노출 (https://vibe.wody.work) 환경에서 device userRole
  미설정 (legacy / 자동화 실수) 시 admin fallback 거부 — 진정한 admin user 만
  통과.

## [1.27.0] - 2026-05-28 — 터미널 사이드바 글로벌 메뉴 + 8차 점검 보안 일괄 회수

사용자 요청: "터미널기능을 좌측 사이드바 메뉴로 이전. 글로벌로 실행되며,
workspace 폴더에서 시작."

기존 `/settings/terminal` (설정 → 빌드환경 chip 안쪽) 진입에서 사이드바
글로벌 메뉴 (/terminal) 로 승격. 동시에 사이드바 노출 = RCE-equivalent 표면이
사실상 항상 보이는 상태가 되므로 8차 정밀점검의 터미널 모듈 Critical 5건 +
Bug 3건 + 누적 Bug 2건을 함께 일괄 회수.

### Added

- **사이드바 글로벌 "터미널" 메뉴** (사용자 요청). 위치: `홈 / 프로젝트 /
  채팅 / 도구 / 터미널 / 설정`. 클릭 시 `/terminal` 진입 → 컨테이너 안
  bash PTY 가 `/workspace` 에서 시작 (server.yml `workspace.root` 가
  `./workspace` 일 때 컨테이너 안에선 `/workspace` 로 마운트). Lucide
  "terminal-square" 아이콘 신규.
- i18n: `nav.terminal` (en: "Terminal", ko: "터미널").
- `TerminalSessionManager` 의 lifecycle 관리 (이전엔 익명 `TerminalSessionManager()`
  로 Module.kt 안에서 한 번 생성되고 종료 hook 없음):
  - `ServerContext.terminalManager` 로 hoist — Module/Routes/SubAgent 등에서 공유.
  - `ServerMain` 의 `Runtime.addShutdownHook` 안에서 `terminalManager.shutdownAll()`
    호출 — 컨테이너 SIGTERM 시 활성 PTY 들 graceful 종료 (SIGTERM → 2초 후
    destroyForcibly fallback) + reaper coroutine cancel.

### Fixed (8차 정밀점검 회수)

- **Critical-1 (REST 무인증)**: `POST/GET/DELETE /api/terminal/sessions*` 가
  `authenticate(AUTH_BEARER) { ... }` 블록 안으로 이동 + `requireApiAdminOrFail`
  로 admin role 검증. 기존엔 CSRF 토큰 (페어링 코드만으로 발급 가능한 약한
  proof) 만으로 PTY 생성 가능 → 컨테이너 vibe 계정의 NOPASSWD sudo 와 결합
  시 호스트 root 등가. 사이드바 노출 후엔 외부 노출 환경에서 즉시 악용 가능.
- **Critical-2 (WS Origin / admin 검증 누락)**: `/ws/terminal/{id}` 핸드셰이크에
  `Origin` ↔ `Host` 비교 (CSWSH 방어) + 인증된 user 의 role == "admin" 검증
  추가.
- **Critical-3 (브라우저 인증 깨짐)**: `document.cookie` JS 가 httpOnly 쿠키를
  읽을 수 없어 첫 Auth frame 의 token 이 항상 빈 문자열 → 진짜 인증 가드가
  들어가면 브라우저에서 100% 실패. cookie-first 패턴으로 전환 — 핸드셰이크
  시 브라우저가 자동 첨부하는 `vibe_session` 쿠키를 서버 헬퍼 `authenticateTerminalWs`
  가 우선 사용. Android client (cookie 없음) 는 기존 Auth frame fallback.
- **Critical-4 (lifecycle)**: 위 Added 항목 참조.
- **Critical-5 (idle + per-user 한도 부재)**: `TerminalSession` 에 `lastActivityAt`
  / `touch()` 추가, `TerminalSessionManager` 의 reaper coroutine 이 60초 간격
  으로 30분 무활성 세션 자동 종료. `create(ownerUserId)` 에 `MAX_SESSIONS_PER_USER`
  (4) 한도 추가, 초과 시 `SessionLimitException` → 429 응답 + `{error:"session_limit",
  max:4}` payload.
- **Bug-1 (handshake timeout)**: Auth-frame fallback path 의 첫 frame 수신을
  `withTimeout(5_000)` 으로 감쌈. 악성 클라이언트가 handshake 만 잡고 첫 frame
  안 보내는 case 에서 코루틴 영구 점유 → FD 누수 차단.
- **Bug-2 (BuildService dead import)**: `kotlinx.coroutines.flow.MutableSharedFlow`
  import 가 v1.26.2 의 `runDebug` 제거 후 dead 였음. 한 줄 삭제.
- **Bug-3 (StatusService replaceWith)**: v1.26.2 의 `@Deprecated` 에 추가했던
  `replaceWith = ReplaceWith("snapshot(\"en\")")` 는 IDE quick-fix 가 자동으로
  hardcoded "en" 으로 치환하게 만들어 사용자 lang 가정을 깨는 회귀를 오히려
  유도. ReplaceWith 제거 — 사용자가 손으로 적절한 lang 변수 전달.

### Changed

- **Q-1**: `TerminalSession.resize()` 의 `coerceIn(1, 999)` 마법 상수 → 컴패니언
  상수 `MAX_TERMINAL_DIMENSION = 999` 로 추출. `MAX_SESSIONS_PER_USER` / `IDLE_TIMEOUT`
  / `REAPER_INTERVAL_MS` 도 동시 추출.
- **Q-2**: `TerminalSession._output.emit` 의 back-pressure 동작 (SharedFlow buffer
  가득 차면 suspend) KDoc 명시. PTY read loop 가 IO dispatcher 라 suspend 안전.
- **Q-3**: `TerminalTemplates.page` 의 JS 에서 `document.cookie` 정규식 제거 →
  cookie-first 인증으로 전환된 사실과 일관. JS 가 더 이상 httpOnly cookie 를
  parse 시도하지 않음 (어차피 실패하던 코드).
- **Q-4**: `/settings/terminal` 으로 들어오던 진입을 라우터 단에서 `respondRedirect(
  "/terminal", permanent = true)` 으로 영구 redirect — 북마크 / 외부 문서 /
  사이드바 미패치 클라이언트 호환.
- **Q-5**: `SettingsNav.topLevelOf` 에 `/terminal` top-level 분기 추가. quicklinks
  (Settings → General) + env-setup subsettings chip 에서 `/settings/terminal`
  엔트리 제거 (사이드바 글로벌 메뉴와 중복 노출 회피).

### Security

- 컨테이너 안 bash PTY 는 vibe 사용자 권한으로만 작동하지만 entrypoint 가
  vibe 에게 NOPASSWD sudo 를 부여한 상태이므로 PTY 접근 = 컨테이너 root 등가.
  따라서 본 cycle 의 가드 변경은 single-user LAN 도구라는 본 리포 전제 (CLAUDE.md
  §1) 와 무관하게 외부 노출 환경 (`https://vibe.wody.work`) 에서의 baseline
  보안 요건. 사이드바 노출 + admin 가드 + owner-only ACL + idle reaper +
  per-user 한도 4단계 방어.

### Migration

- 기존 `/settings/terminal` 북마크 / 외부 링크 → 자동 301 → `/terminal`.
- 운영 환경 (`https://vibe.wody.work`) 재배포 후 사이드바에 신규 메뉴 노출.
  사용자 별도 액션 불필요. 활성 PTY 세션 있는 사용자는 일시적 disconnect (Docker
  re-create) 후 사이드바에서 재접속.

## [1.26.2] - 2026-05-28 — 7차 점검 일괄 회수 (Accept-Language RFC + runDebug 제거 + KDoc 정정)

### Fixed

- **Bug-1**: `EnvRoutes.resolveLang` 의 Accept-Language 파싱이 RFC 7231 §5.3.5
  q-value weighting 을 무시하던 회귀. `Accept-Language: en;q=0.1, ko;q=0.9`
  같은 케이스에서 첫 토큰만 채택해 사용자 의도 (ko 우선) 와 반대로 영문 응답.
  이미 완전 구현된 `Messages.fromAcceptLanguage(header)` (q-sort + region strip
  + supported 매치) 재사용으로 한 줄 교체. AdminRoutes / StatusPagesPlugin 와
  일관성도 회복.
- **Bug-2**: `BuildService.runDebug` 함수 제거 — caller 0건 dead code (autoBuild
  기능 자체가 config flag 만 정의되고 호출 path 미구현). v1.26.1 narrative 의
  "Claude autoBuild 4개 우회 surface" 표기가 사실과 달랐던 점도 동시 정정 —
  실제 5번째 surface 는 `BuildAutomationRoutes` 의 Webhook fire 였고 그것도
  자동으로 `enqueueDebug` 경유라 SSOT 가드 발화 확인됨.

### Changed

- **Q-1**: `EnvRoutes.resolveLang` 의 FQN `io.ktor.server.application.ApplicationCall`
  → import 추가로 깔끔.
- **Q-2**: `WebProjectRoutes` 의 SSR POST `/projects/{id}/builds` 사전 keystore
  check 가 service-layer SSOT 와 이중 가드라는 점 KDoc 명시 — 실제 enforcement
  는 `BuildService.requireKeystoreOrThrow`, SSR pre-check 는 ko-localized
  flash redirect 용 UX 안내. 가드 로직 변경 시 두 곳 동시 봐야 함.
- **Q-3**: `StatusService.snapshot()` no-arg overload `@Deprecated` 표기 +
  강력 KDoc 경고. 호출자 0건 (EnvRoutes 가 v1.26.1 에서 lang 명시 호출로
  갱신) 이라 deprecation warning 발화 site 없음 — 향후 신규 caller 의 회귀
  재발 차단.
- **Q-4**: `BuildService.requireKeystoreOrThrow` KDoc 의 "Claude autoBuild" →
  "Webhook (BuildAutomationRoutes)" 정정. Bug-2 와 root cause 동일.
- **Q-5**: SSR POST `/builds` 의 keystore probe runCatching 도 `onFailure
  { log.warn }` 추가 — v1.26.1 의 GET path 회수와 일관 (silent IO 진단).

## [1.26.1] - 2026-05-28 — 6차 점검 일괄 회수 (키스토어 가드 SSOT + VNC 절충 + Accept-Language + KDoc)

### Security

- **C1 회수: 키스토어 가드 SSOT 를 `BuildService.enqueueDebug` / `runDebug` 진입부로
  이동**. v1.26.0 의 SSR POST 한 곳만 가드한 결과로 JSON API (`/api/projects/X/build/debug`) /
  WS action (`ProjectAction.RunServer("build.debug")`) / Cron schedule fire /
  Claude autoBuild (`runDebug`) 4개 우회 surface 가 잔존했음. service layer 단일
  enforcement point 로 이동 → 모든 진입점이 자동 가드. 신규 helper
  `requireKeystoreOrThrow(row)` → `ApiException.localized(409, "keystore_required",
  "api.build.keystoreRequired", args=[packageName])`. SSR 의 사전 disable / POST
  redirect 는 UX 안내용으로 유지.

### Changed

- **B1 회수: WebSocket `maxFrameSize` 8MB → 16MB 절충**. v1.25.2 의 8MB 환원이
  noVNC 풀-HD AVD 첫 framebuffer (~8.3MB RAW encoding) 를 차단할 위험이라 16MB
  로 여유. ktor 3.x 가 per-route override 미지원 → 글로벌 절충. 단일 사용자
  가정. Claude stream / 콘솔 / 빌드 로그는 그대로 8MB 이내라 영향 없음.
- **Q1 + Q4 회수: `/api/server/status` / `/api/server/environment` / .../check
  endpoint 가 `Accept-Language` 헤더 우선 사용**. 없으면 `"en"` (Android client
  영문 기대 호환). v1.26.0 의 no-arg overload 가 server default ("ko" 가능) 로
  떨어져 client 가정 깨졌던 회귀 해소. `resolveLang(call)` 헬퍼 신설.
- **Q2 회수: `buildsPage(keystoreReady)` default `true` → `false`** (fail-secure).
  현재 호출자가 명시적 전달 중이라 동작 동일, 향후 신규 호출자 추가 시 안전 마진.
- **Q3 회수: keystore probe `runCatching` 의 `onFailure` 로 `log.warn`**. 이전엔
  IO 에러 등이 silently `false` 로 fall-through → 운영자가 진단 불가. 로그로
  추적 가능.
- **Q5 회수: `StatusService.snapshot(lang)` overload 의 KDoc 명확화** — fallback
  path 임을 명시 (envSnap 있을 땐 `snapshot(envSnap)` overload 사용).

### Added

- i18n: `api.build.keystoreRequired` (Ko/En) — service-layer 가드의 i18n 메시지.

### Notes

- 우회 surface 4개가 한 helper 호출로 동시 차단된다는 점이 v1.26.1 의 핵심.
  CHANGELOG v1.26.0 의 "AGP default debug.keystore 자동 생성 차단" 의도가 이제
  실제로 모든 path 에서 enforce.

## [1.26.0] - 2026-05-28 — 키스토어 가드 (운영 정책 강화) + 5차 점검 Q1~Q4 회수

### Added

- **빌드 버튼 키스토어 readiness 가드** (사용자 요청 + 글로벌 CLAUDE.md 정책):
  - 프로젝트의 `packageName` 매칭 keystore set 이 `/home/vibe/keystores/` 에
    없으면 `/projects/{id}/builds` 페이지의 [Debug 빌드 큐] 버튼이 `disabled` +
    툴팁 안내. 버튼 하단에 yellow 경고 카드 (제목 + 본문 + "키스토어 관리 페이지"
    링크 + 기대 packageName) 표시.
  - `POST /projects/{id}/builds` 도 서버 측 가드 — UI 우회 직접 POST 시
    `flash.build.keystoreRequired` 에러 + 같은 페이지 redirect.
  - 운영 정책 (CLAUDE.md "키스토어 임의 생성 금지") 강제 — AGP 가 default
    debug.keystore 를 자동 생성하던 동작 차단.
- 신규 i18n: `builds.disabled.title/body/openKeystores/expected/noKeystore`,
  `flash.build.keystoreRequired` (Ko/En).
- **글로벌 `~/.claude/CLAUDE.md` 에 "키스토어 임의 생성 절대 금지" 조항 추가** —
  모든 프로젝트 공통. Claude Code / Gradle / build script 어느 경로에서도 새
  keystore 자동 생성 금지. 사전 등록 된 파일만 사용.

### Changed

- **5차 점검 Q1**: `EnvDiagnostics.run()` 의 중복 호출 (`/admin` dashboard) 회수.
  `StatusService.snapshot(envSnap)` overload 신설 → caller 가 이미 가진 snap
  재사용. 같은 cycle 안 process spawn 6→3 (3 가벼운 shell-out).
- **5차 점검 Q2**: `UNTRUSTED_EXTENSIONS` 의 `.mhtml` 제거 — `.mht` 가 `endsWith`
  로 cover.
- **5차 점검 Q3**: WS `maxFrameSize` 32MB → 8MB 환원. 32MB 가 필요한 곳은 VNC
  framebuffer 만 — 그 라우트 (`VncProxyRoutes`) 가 자체 override 필요 시.
  글로벌 default 의 외부 DoS surface 축소.
- **5차 점검 Q4**: `service-side "en" hardcode 6 site` → `EnvDiagnostics.run()` /
  `StatusService.snapshot()` / `EnvSetupService.detectAll()` no-arg overload
  신설. `config.i18n.defaultLanguage` 자동 fallback. JSON API entry / service-
  to-service 호출은 사용자 lang 모르는 context 라 서버 default 가 의미 있음.

## [1.25.1] - 2026-05-28 — 4차 점검 잔여 fix (StatusService lang / dead isUntrustedMime / .mhtml / VNC frame 한도)

### Security

- `UNTRUSTED_EXTENSIONS` 에 `.mhtml` / `.mht` 추가 — Chrome 의 MIME-HTML archive
  inline 렌더링 차단. writable actor 가 심을 수 있는 추가 active-content 차단.

### Changed

- **`StatusService.snapshot(lang: String)`** 의 default `"en"` 제거. `AdminRoutes.kt:70`
  의 dashboard 호출도 `sess.language` 로 갱신. 같은 cycle 안 `envDiagnostics.run(sess.language)`
  와의 중복 진단 (default fall-through) 회수.
- **WebSocket `maxFrameSize`** 8MB → 32MB. noVNC 풀-HD AVD 의 RAW encoding 첫
  framebuffer (~8.3MB) + ZRLE 큰 rectangle 회피. 외부 위협 surface 는 동일 —
  인증된 사용자만 도달.

### Removed

- `ProjectFileBrowser.isUntrustedMime()` companion (v1.24.0 ~ v1.24.1 잠시 사용,
  v1.25.0 의 `isUntrustedPathOrMime` 도입 후 dead code).

## [1.25.0] - 2026-05-28 — 3차 점검 일괄 회수 (lang default 전체 제거 / SVG attachment / WS frame 상한)

### Security

- **`/raw` SVG attachment layer 실제 발화** — v1.24.1 의 `isUntrustedMime(raw.mime)`
  가 SVG 에 false 였던 회귀 (`guessImageMime(.svg) = application/octet-stream`
  로 이미 downgrade 되어 mime 매치 안 됨). 신규 `isUntrustedPathOrMime(path, mime)`
  helper — path 확장자 (`.svg/.svgz/.html/.htm/.xhtml/.js/.mjs/.xml/.xsl/.xslt`)
  와 mime 둘 다 체크. SVG / HTML / JS / XML 모두 `Content-Disposition: attachment`
  강제 발화 → CHANGELOG 의 "3중 방어" 실제 동작.

### Changed

- **모든 page-level fn 의 `lang: String = "en"` default 일괄 제거 (34 파일)** —
  v1.24.0 의 `shell()` default 제거가 한 layer 만 해결, 그 아래 page fn 의
  default 가 silently fall-through 시키던 동일 회귀를 컴파일 단계에서 강제. 자동
  patch 후 6 service-side 호출 site (`CapabilityService` / `ConsoleRoutes` /
  `EnvRoutes` / `EnvSetupApiRoutes` / `StatusService`) 는 사용자 lang 모르는
  context 라 `"en"` 명시 전달. `StatusService.snapshot(lang)` 도 시그니처 확장.
- **`WebSocket maxFrameSize: Long.MAX_VALUE` → `8 MB`** — 외부 노출 환경에서
  단일 frame 메모리 고갈 DoS surface 축소. Claude stream / 콘솔 / 빌드 로그
  실제 사용량은 단일 frame 8 MB 이하.

### Fixed

- **lang 누락 4 site** (`WrapperTemplates.page`, `StatsTemplates.page`,
  `SearchTemplates.page`, `AutomationTemplates.page`) — `/projects/X/wrapper`,
  `/stats`, `/code-search`, `/automation` 한국어 진입 시 영어 사이드바 회귀.
  호출자 4곳에 `lang = sess.language` 명시 + page fn default 도 제거 (위 항목과 함께).

### Notes

- 3차 점검 보고서가 지목한 "page fn default 60+ site" 는 실제 34 파일 / 약 70+ fn
  시그니처. 자동 patch 로 일괄 해결.
- service-side 호출 (6 site) 의 `"en"` hardcode 는 후속 cycle 에서 `config.i18n.defaultLanguage`
  로 전환 검토. JSON API / 진단 응답이라 사용자 가시성은 낮음.

## [1.24.1] - 2026-05-28 — 재점검에서 발견된 4건 (delete path leak / isUntrustedMime wire-up / navHtml lang / partial_delete UX)

### Security

- **`/raw` 의 active-content MIME (`isUntrustedMime`) 인 경우 `Content-Disposition:
  attachment` 강제** — SVG / HTML / JS 류 응답이 octet-stream downgrade 와 CSP
  sandbox 외에도 download 강제로 이중 방어. 일부 브라우저가 헤더 무시하고 inline
  렌더 시도하는 잠재 우회 차단. v1.24.0 의 `isUntrustedMime` companion 이 이제
  실제 사용처 wire-up.

### Fixed

- **`delete` partial fail 메시지의 절대 경로 노출** — 워크스페이스 server-side path
  (`/workspace/projects/<id>/...`) 가 i18n args 로 사용자 메시지에 그대로 전달
  됐던 회귀. `projectRoot.relativize(p)` 로 상대 경로 변환 후 args 전달. 외부
  노출 도메인 (https://vibe.wody.work) 환경에서 path 누설 회피.
- **`partial_delete` i18n 메시지에 재시도 가이드 추가** — "권한/잠금 해소 후 같은
  디렉토리에서 재시도 가능 (이미 삭제된 항목은 skip)". 사용자가 부분 삭제 상태
  에서 다음 단계 모르던 UX 회수.

### Changed

- **`AdminTemplates.navHtml` 의 `lang: String = "en"` default 제거** — v1.24.0
  의 `shell()` lang required 변경과 일관성. helper 호출자 (`shell()` 단일)
  가 명시 전달하지만, 같은 회귀 재발 방지 의도로 default 잔존하면 코드 일관성
  약화. 컴파일 단계에서 강제.

## [1.24.0] - 2026-05-28 — 정밀 점검 결과 일괄 회수 (Critical 2 + Bug 6 + Quality 3)

### Security

- **`/raw` 의 SVG XSS 위험 회수**:
  - `guessImageMime(path)` 가 `.svg` 에 `image/svg+xml` → `application/octet-stream`
    으로 downgrade. 브라우저가 SVG 안의 `<script>` 를 실행 안 함.
  - `/raw` 응답에 `X-Content-Type-Options: nosniff` + `Content-Security-Policy:
    default-src 'none'; sandbox` 강제. workspace 에 쓸 수 있는 행위자
    (Claude session / git clone / uploadStream) 가 stored XSS payload 를 심어도
    vibe_session 쿠키 보호.
  - `isUntrustedMime()` companion 신규 — 추후 routes 가 위험 MIME 체크에 사용.

### Changed

- **`AdminTemplates.shell` 의 `lang: String = "en"` default 제거 → required**. 정밀
  점검 보고서의 핵심 권장 — default 가 누락 site 를 silently fall-through 시켜
  사용자 가시 사이드바 영어 회귀를 14 site 에 가까이 누적했었음. required 로
  바꿔 컴파일 단계에서 강제 노출. 컴파일 통과 시점에 모든 호출자가 lang 명시
  전달이 보장.
- `ProjectFileBrowser.skipHidden` 이 KDoc 과 동작 일치: 점(.) 으로 시작하는
  모든 entry + 빌드 산출물 (`build` / `node_modules`) 차단. 이전엔 `.git` /
  `.env` 등이 listing 노출되어 가벼운 secret 누설 + 시각적 노이즈.

### Fixed

- `/files/download` 10 MB 한도 차단 회귀 — APK / AAB 같은 큰 binary 다운로드 가능.
  `resolveForRawRead(maxBytes)` 파라미터 신규, `/files/download` 만 별도
  `MAX_DOWNLOAD_BYTES = 200 MB` 사용. `/raw` 는 기존 10 MB 유지 (이미지 viewer).
- `ProjectFileBrowser.delete` 의 재귀 walk 가 실패 path 를 `runCatching` 으로
  silent 처리하던 회귀. 실패 path 모아 `ApiException(partial_delete)` throw +
  warn 로그. 부분 삭제 상태 사용자에게 명시.
- `ProjectFileBrowser.copy` 재귀 시 `target.parent` NPE 가능성. null-safe `?.let`.
- `ProjectFileBrowser.write` 의 atomic move 실패 시 `.editing.tmp` 잔존하던
  회귀. try/catch + `Files.deleteIfExists(tmp)` cleanup.
- CodeMirror form submit handler 의 `initial = cm.getValue()` 가 서버 저장
  실패해도 dirty indicator 를 잘못 reset 하던 회귀. `initial` 갱신 제거 — redirect
  가 페이지 reload 라 fresh baseline 자동.

### Removed

- `WebProjectRoutes` 의 `uploads: UploadService` 파라미터 — v1.14.0 의 uploads
  카탈로그 라우트 제거 후 dead leg. 동시에 `UploadService` import 제거.
  Module.kt 의 `uploads = ctx.uploads` wiring 제거. `UploadService` bean 자체는
  보존 (multipart Claude credentials upload 등 다른 경로 사용).

### Notes

- 점검 보고서가 지목한 "14 site lang 누락" 은 v1.23.0 의 자동 patch 가 실제로는
  처리한 상태였음 (line number stale). `AdminTemplates.shell` lang default 제거로
  컴파일 단계 검증 강화 → 향후 재발 차단.
- 14개 페이지 본문의 한국어 raw string (i18n key 미사용) 은 본 cycle 의 범위를
  벗어남 — 후속 cycle 에서 `Messages.t(lang, ...)` 기반으로 점진 전환 예정.

## [1.23.0] - 2026-05-27 — 도구 통합 탭 + 전체 페이지 lang 전파 회수 + 사이드바 라벨 + UI 회귀

### Added

- **`/tools/tabs`** — 도구 통합 탭 페이지 (ProjectTabsTemplate / SettingsTabsTemplate
  와 일관 스타일). 6개 카테고리 iframe prerender: Overview / Multi-console /
  Emulator / Code search / Build logs / Conversation search.
- 사이드바 "도구" link 가 `/tools` → `/tools/tabs` 변경.
- 신규 `ToolsTabsTemplate.kt`.
- i18n: `tools.tabs.title`, `tools.tab.overview/multi/emulator/codeSearch/logs/history` (Ko/En).

### Fixed

- **사이드바 메뉴가 영어로 표시되던 회귀** (사용자 보고: 도구/에뮬레이터/코드검색/
  대화검색 등 진입 시 한국어 설정이어도 nav 가 영어). 22개 SSR page/template
  의 `AdminTemplates.shell()` 호출이 `lang` 인자 누락 → default `"en"`. 정밀
  검토 후 일괄 fix:
  - Route handler 직접 호출 (`/emulator`, `/webauthn`, `/usage`, `/projects/X/agents`,
    `/projects/X/symbols`, `/push`) 은 `lang = sess.language` 전달.
  - Template fn 안 호출은 fn 시그니처에 `lang: String = "en"` 추가 + 호출자도
    `lang = sess.language` 전달. 영향 fn: `AuditTemplates.page`,
    `BuildAutomationTemplates.page`, `BuildCacheTemplates.page`,
    `DependencyAuditTemplates.page`, `AgentTemplates.listPage/editPage`,
    `EmailSettingsTemplates.page`, `WebhookSettingsTemplates.page`,
    `CodeAnalysisTemplates.page` (3 sub-page), `EnvFilesTemplates.page`,
    `PromptTemplates.listPage`.
- **사이드바 "Chat" → "채팅"** (한국어). 그동안 hardcoded `"Chat"` 였음.
  `nav.chat` i18n 키 신규 + Ko `"채팅"` / En `"Chat"`.
- **파일트리 다중 선택 시 cell 사이 세로 라인** (사용자 보고). v1.19.0 의
  `tr.fts-selected td { box-shadow: inset 3px 0 0 var(--accent) }` 가 모든 td
  의 좌측에 inset bar → cell 사이 세로 라인처럼 보였음. `td:first-child` 만
  적용해서 row 전체에 좌측 accent bar 한 개만.

## [1.22.0] - 2026-05-27 — 설정 메뉴 통합 탭 페이지 (ProjectTabsTemplate 스타일 일관)

### Added

- 신규 `/settings/tabs` — 설정 8개 카테고리 (General / Security / Notifications /
  Build env / Prompts / Backup / Monitoring / Users) 를 iframe prerender 패턴으로
  통합. ProjectTabsTemplate 와 같은 layout / CSS / JS / hash sync.
- 사이드바 "설정" link 가 `/settings` → `/settings/tabs` 변경.
- iframe `data-project-id="__settings__"` 로 localStorage 마지막 탭 namespace 분리.
- 신규 `SettingsTabsTemplate.kt` (~110 LoC).
- i18n: `settings.tabs.title` (Ko/En). 기존 카테고리 라벨은 `settings.tab.*` 재사용.

### Notes

- 기존 `/settings`, `/password`, `/settings/email` 등 sub-페이지 URL 그대로
  작동. 통합 페이지의 iframe src 가 같은 URL → 0 수정.
- 각 iframe 안의 inner admin shell 의 nav.sidebar + .settings-tabs 는
  project-tabs.js 의 cleanup 이 hide (이미 v1.11.0 에 구현). 카테고리 안
  sub-page navigation 은 settings-tab bar 가 hide 됐으니 사용자가 직접 inner
  link 또는 URL 입력해야 — 후속 cycle 에서 inner sub-nav 도 visible 하게
  허용할지 검토 가능.

## [1.21.0] - 2026-05-27 — 콘솔 응답 카드를 Claude Code 터미널 스타일로

### Changed

- 콘솔 응답 카드의 90px LABEL column 제거 → 단일 column + ASCII prefix
  (`> ` user / `⏺ ` assistant / tool / `  ⎿ ` tool result / `⎯ ` system / `! ` error).
  Claude Code CLI 터미널 출력에 가깝게.
- 카드 사이 border-bottom 제거, padding `4px 0` → `1px 0` (dense terminal feel).
- prefix 만 role 별 색상 (accent / green / amber / gray-blue / dim / red).
  본문은 default mono 흰색.
- 기존 `.log-label` 은 hide (server JS / API 가 여전히 emit 하지만 시각 제거).

### Notes

- CSS-only 변경 — server SSR / WS frame payload 무수정.
- 시간/copy 버튼 등 hover meta 는 그대로 유지.

## [1.20.0] - 2026-05-27 — 콘솔 auto-scroll 모드 + sendPrompt 후 force scroll

### Added

- 콘솔 상단에 📌 **자동 스크롤** 체크박스 (default ON). ON 일 때 새 메시지
  도착 시 사용자 스크롤 위치 무시하고 항상 최하단. OFF 일 때 v1.6.4 의
  stick-to-bottom 동작 (사용자가 하단에 있을 때만 따라감).
- 사용자 선호는 `localStorage['vibe.console.autoscroll']` 영속.
- **prompt 전송 직후엔 토글 무관 항상 `scrollToBottom()`** — 사용자가 자기
  prompt + 응답을 즉시 보고 싶다는 의도가 명확.
- i18n: `console.autoscroll`, `console.autoscroll.tip` (Ko/En).

## [1.19.0] - 2026-05-27 — 파일트리 다중 선택 모드 (Copy / Cut / Paste / Delete / Download)

### Added

- **Long-press / 우클릭 → 다중 선택 모드**:
  - 데스크탑: mousedown 500ms hold 또는 우클릭으로 진입.
  - 모바일: touchstart 500ms hold.
  - 진입 후 일반 클릭 = 선택 토글 (navigation 차단), ESC / ✕ 버튼 = 모드 해제.
  - 선택 row 는 좌측 accent 색 bar + 배경 highlight.
- **신규 toolbar 액션** (`#fts-toolbar-select`):
  - ⎘ **Copy** — 선택 항목을 sessionStorage clipboard 에 저장 (action=copy)
  - ✂ **Cut** — 선택 항목을 clipboard 에 저장 (action=cut)
  - ⬇ **Download** — 단일 파일은 GET `/download` (attachment), 다중 또는 폴더
    포함 시 POST `/download-zip` (ZipOutputStream stream)
  - 🗑 **Delete** — 다중 batch delete (confirm 후)
- **Paste 버튼** — 기본 toolbar 에 clipboard 가 있으면 표시 (예: `⎘ 3 (copy)`).
  현재 보고 있는 디렉토리에 paste. action=cut 이면 `/move`, copy 면 `/copy` 호출.
- 신규 backend:
  - `ProjectFileBrowser.copy(srcRel, dstParentRel)` — 파일/디렉토리 재귀 복사.
    cycle 방지 (dst 가 src 자식 거부) + 동명 거부 + symlink skip.
  - `ProjectFileBrowser.move(srcRel, dstParentRel)` — rename 의 cross-dir 확장.
  - 5개 신규 라우트: `POST /files/copy`, `/move`, `/delete-batch`,
    `GET /files/download?path=...`, `POST /files/download-zip` (multipart paths).
- 신규 static asset: `static/admin/file-tree-select.js` (~180 LoC, zero-dep).
- i18n: `fileTree.hint.select`, `fileTree.select.*`, `fileTree.confirm.deleteN`,
  `flash.file.copied/moved/deletedN`, `api.fileBrowser.cycleMove` (Ko/En).

### Notes

- clipboard 는 `sessionStorage['vibe.fileClipboard.<projectId>']` 영속 — 다른
  프로젝트 / 새 탭 으로 옮겨가도 clipboard 독립 유지.
- zip download 의 entry path 는 src 의 fileName + 디렉토리 walk 시 상대 경로.
  여러 파일/폴더 동시 download 시 한 zip 에 묶임.
- delete-batch 는 loop 호출이라 어느 한 항목 실패 시 즉시 중단 + 그때까지 삭제된
  개수 반환. 부분 성공 케이스.

## [1.18.0] - 2026-05-27 — 텍스트 파일 통합 CodeMirror 에디터 (view/edit 분리 모드 제거)

### Changed

- 사용자 요청: "텍스트 파일도 이미지처럼 (즉시 코드 에디터 모드로)" 표시.
  기존 view (hljs read-only) ↔ edit (plain textarea) 토글 분리를 제거하고
  **CodeMirror 5** 단일 통합 에디터로 교체:
  - **line numbers gutter** + **active line 강조** + **matching/auto-close brackets**
  - **syntax highlight** (자동 mode 매핑) — 진입 즉시 표시 + 직접 편집 가능.
  - **Ctrl/Cmd+S 저장** + 변경 시 "● 변경됨" dirty indicator + 저장 버튼.
  - **`?line=N` jump** — 심볼 검색에서 jump 시 정확한 라인으로 스크롤 + 1.5초
    배경 강조 (yellow tint).
- 외부 CDN 미사용 정책 (CLAUDE.md §3) 일관 — CodeMirror 5.65.16 bundle 을
  `static/admin/vendor/codemirror/` 에 local commit.
  - 코어 (lib/codemirror.js + .css)
  - 모드 10종: xml / javascript / clike (Java/Kotlin/C-family) / yaml / markdown /
    properties / shell / css / htmlmixed / dockerfile.
  - addon: active-line / matchbrackets / closebrackets.
  - 테마: material-darker.
- 신규 helper `mapMimeToCodeMirror(mime, relPath)` — MIME + 확장자 → CodeMirror
  mode 문자열 (e.g. `text/x-kotlin`, `text/x-yaml`).

### Removed

- 기존 view ↔ edit 토글 버튼 (`#toggle-mode`) + 별도 view pane (`#file-view-pane`)
  + highlight.js 의존 (해당 페이지만 — 다른 페이지가 hljs 쓰면 그대로 유지).

### Notes

- 큰 파일도 CodeMirror 로 표시. mode 가 plain text 면 highlight 비활성 → 부담 적음.
- 이미지 / 큰 binary 는 v1.17.0 의 image viewer / `binary_file` 차단 그대로.

## [1.17.0] - 2026-05-27 — 파일트리에서 이미지 파일 클릭 시 이미지 뷰어 모드

### Added

- 파일트리에서 `.png` / `.jpg` / `.jpeg` / `.gif` / `.webp` / `.bmp` / `.ico` /
  `.avif` / `.svg` 클릭 시 `/view` 가 자동으로 **이미지 뷰어 모드**로 렌더.
  텍스트 reader (binary 차단) 대신:
  - 상단 카드에 파일경로 + 크기(KB) + naturalWidth × naturalHeight + 다운로드 link.
  - 본문은 transparent-checker 배경 위 `<img>` 표시 (max-width: 100%).
- 신규 `GET /projects/{id}/raw?path=...` — 이미지 / binary 파일 stream 응답.
  PathSafety + 심볼릭 링크 차단 + 10 MB 한도 + Content-Type 자동 (확장자
  기반). `<img src="...">` 가 직접 fetch.
- `ProjectFileBrowser`:
  - companion `isImagePath(path)` / `guessImageMime(path)` 신규 (지원 확장자 9종).
  - `resolveForRawRead(projectId, relPath)` 신규 — path 검증 후 absolute path +
    size + MIME 반환. /raw 라우트가 사용.
  - `MAX_RAW_BYTES = 10 MB` 상수 (텍스트 view 1 MB 보다 큼).
- i18n: `fileView.image.label / .download / .loadFailed` (Ko/En).

### Notes

- 이미지 다운로드 link 도 같은 `/raw?path=...` URL + `download` 속성. 별도
  endpoint 추가 안 함.
- 큰 파일은 `file_too_large` (HTTP 413) — 10 MB 초과 시. 그 외 binary 는
  여전히 `/view` 에서 텍스트 reader 의 `binary_file` 차단 메시지 (기존 동작).

## [1.16.1] - 2026-05-27 — 콘솔 prompt 전송/음성 버튼을 textarea 우측으로 이동

### Changed

- 사용자 요청. textarea 아래 한 줄로 늘어서 있던 [🎤] [전송] 버튼을 textarea
  **우측** column 으로 이동. textarea + 버튼이 같은 row 안에서 가로 배치 (채팅
  UI 패턴). 버튼 column 은 `justify-content: flex-end` 로 textarea 하단 정렬 —
  textarea 가 커져도 버튼은 가지런히 우하단.
- textarea 자체는 `flex: 1; width: auto; min-width: 0` 로 가용 폭 모두 차지
  (admin.css 의 `width: 100%` 는 inline style 가 우선).
- 자동 전송 체크박스 + busy badge + hint 는 textarea 아래 줄로 그대로.

## [1.16.0] - 2026-05-27 — viewport 동적 대응 + 사이드바 고정 + 컨텐츠 단독 스크롤 + 모바일 자동 collapsed

### Changed

- **전체 페이지를 viewport (100dvh, vh fallback) 안에 고정**. `<html>` /
  `<body>` 가로 스크롤 차단 (`overflow: hidden`). 한 페이지 = 1 화면.
- **사이드바 자체는 항상 100% 노출, 스크롤 X** (`.sidebar { overflow: hidden }`).
  nav-links 가 길어서 viewport 안에 다 안 들어오면 nav-links 안에서만 세로
  스크롤 (`.nav-links { overflow-y: auto }`) — 브랜드 / quota / user-box 는
  항상 보임.
- **우측 컨텐츠는 자체 세로 스크롤** (`.content { overflow-y: auto; overflow-x: hidden }`).
  가로 스크롤 차단.
- **`.content.fullbleed` 변형 신규** — ProjectTabsTemplate 처럼 자체적으로
  viewport 안에서 layout 을 구성하는 page 용. padding / max-width 제거 +
  overflow hidden → 자식이 직접 100% 박스 사용. `AdminTemplates.shell()` 에
  `fullbleed: Boolean = false` 옵션 추가.
- **모바일 자동 collapsed sidebar** (`@media (max-width: 768px)`):
  - 사이드바 56px 폭 강제, 라벨 / 브랜드 타이틀 / quota / 로그아웃 hide.
  - 컨텐츠 padding 32px → 16px 축소.
  - 기존 desktop collapsed (`data-sidebar-collapsed`) 와 동일한 시각 결과,
    별도 토글 없이 폭 기준 자동 적용.

### Notes

- `100dvh` (dynamic viewport height) 가 mobile 브라우저 주소창 동적 height 도
  정확히 반영. 미지원 브라우저는 `100vh` fallback (위쪽 줄 cascade).
- `min-height: 0` 추가 — grid/flex 자식이 default 의 implicit `min-content`
  때문에 overflow 정상 동작 안 하는 회귀 회피.
- ProjectTabsTemplate 의 `calc(100vh - 16px)` + `margin: -16px` 트릭 제거.
  `.content.fullbleed` 의 padding 0 + height 100% 로 깨끗 정리.

## [1.15.1] - 2026-05-27 — 음성 입력 "자동 전송" 옵션

### Added

- 🎤 버튼 좌측에 "자동 전송" 체크박스. ON 시 발화 종료 (SpeechRecognition
  onend) → form requestSubmit() 자동 호출 → 콘솔의 기존 submit handler 가
  sendPrompt() 실행. 사용자가 매번 손으로 send 누를 필요 없이 발화 → 전송
  까지 한 흐름.
- 사용자 선호는 `localStorage['vibe.voice.autoSend']` 영속 — refresh / 재진입
  후에도 같은 설정.
- i18n: `console.voice.autoSend`, `console.voice.autoSend.tip` (Ko/En).

### Notes

- 자동 전송 시 빈 prompt (공백만 발화) 는 skip — `input.value.trim().length === 0`
  검사로 거짓 양성 방지.
- 미지원 브라우저 (Firefox 등) 는 voice-input.js 가 옵션 wrapper (`#voice-auto-send-wrap`)
  도 함께 hide.

## [1.15.0] - 2026-05-27 — 콘솔 prompt 음성 입력 (Web Speech API)

### Added

- 콘솔 prompt textarea 옆 🎤 버튼 신규. 클릭 → 브라우저 `SpeechRecognition`
  (Web Speech API) 시작 → 음성 인식 결과를 textarea 에 실시간 append.
  - listening 중 버튼: 빨간 배경 + ⏺ 아이콘 + box-shadow pulse animation.
  - 한 번 더 클릭 또는 발화 종료 → 자동 중지.
  - 언어 자동: `document.documentElement.lang` 가 `ko*` 이면 `ko-KR`, 그 외 `en-US`.
  - 미지원 브라우저 (Firefox 등) 는 voice-input.js 가 버튼 자동 hide.
  - HTTPS / localhost 만 작동 (브라우저 정책). 마이크 권한 prompt 첫 사용 시.
- 신규 static asset: `static/admin/voice-input.js` (~80 LoC, zero-dep).
- i18n: `console.voice.start`, `console.voice.stop` (Ko/En).

### Notes

- 인식 결과는 사용자가 이미 입력해둔 텍스트 뒤에 append (마지막 글자가 공백/
  개행 아니면 자동 한 칸 띄움). 사용자가 prompt 일부를 음성으로, 나머지를
  키보드로 섞어 쓸 수 있게.
- 서버 측 STT (Whisper 등) 는 사용 안 함 — 브라우저 native API 만. 무료, 즉시,
  추가 token / 네트워크 없음.

## [1.14.4] - 2026-05-27 — /projects 목록 row 전체 클릭 가능 + 진입 시 항상 Console 탭

### Changed

- 사용자 요청: row 의 프로젝트 이름이 아닌 어디든 클릭해도 통합 탭 페이지로
  진입. 각 `<td>` 자식을 `display:block; color:inherit` 인 `<a>` 로 채우고
  cell padding 을 link 안으로 옮겨 클릭 영역 전체 = row 전체. tr hover 시
  background 강조 + cursor:pointer.
- **첫 진입은 항상 Console 탭** — link href 에 `#console` hash 를 명시.
  [project-tabs.js] 의 `resolveInitialTab()` 가 hash 를 localStorage 보다
  우선 사용하므로, 사용자가 마지막에 다른 탭에 머물렀더라도 list 에서 다시
  진입하면 console 로 시작.

## [1.14.3] - 2026-05-27 — /projects 목록 단순화: lastBuild / openConsole 컬럼 제거

### Changed

- 사용자 요청. 등록된 프로젝트 list 테이블에서 "최근 빌드" (status badge)
  컬럼과 "콘솔 열기" (action button) 컬럼 제거. Name + Package 두 컬럼만 남김.
  진입은 name link 가 처리 — /projects/{id} 통합 탭 페이지로 직행 (그곳에
  Builds / Console 탭 모두 있음).

## [1.14.2] - 2026-05-27 — /projects 의 "새 프로젝트" 카드 default closed

### Changed

- 사용자 보고: 신규 등록은 자주 안 일어남에 비해 카드가 크고 fields 가 많아
  매번 스크롤 필요. `<div class="card">` → `<details class="card">` (default
  closed) 로 변경 — summary 가 제목 + ▸ 마커, 클릭 시 form 펼침. native HTML
  토글 (no JS).

## [1.14.1] - 2026-05-27 — /projects 페이지 좌우 → 상하 레이아웃, 신규 등록 카드 상단으로

### Changed

- 사용자 보고: 사이드바 접힘 / 좁은 화면에서 좌우 (2fr 1fr) 배치가 답답.
  `<section class="grid">` 를 single-column 으로 변경 + 카드 순서도 신규 등록
  ("새 프로젝트") 위, 등록된 프로젝트 list 아래로.

## [1.14.0] - 2026-05-27 — Files 탭 파일 탐색기화 (mkdir/createFile/rename/delete/upload), uploads 페이지 제거

### Added

- **Files 탭 (`/tree`) 에 탐색기 액션 통합** — 상단 toolbar:
  - **Upload** — multipart upload to current directory. 단일 파일 max 50MB.
    동명 파일 있으면 거부 (overwrite=false default).
  - **새 파일** — prompt → 빈 파일 생성 → /view 편집 페이지로 자동 이동.
  - **새 폴더** — prompt → mkdir.
  - 각 row 옆 inline form: **✎ Rename** (같은 폴더 내 이름 변경), **🗑 Delete**
    (파일 또는 디렉토리 재귀 삭제, confirm 필수).
- `ProjectFileBrowser` 신규 메서드: `mkdir`, `createFile`, `rename`, `delete`,
  `uploadStream`. 모두 `PathSafety.normalizeAndCheck` 통과 + 심볼릭 링크 차단 +
  동명 / invalid name (`.`, `..`, 슬래시) 거부.
- 신규 SSR 라우트 (모두 write access + CSRF 필수):
  - `POST /projects/{id}/files/new-folder`
  - `POST /projects/{id}/files/new-file`
  - `POST /projects/{id}/files/rename`
  - `POST /projects/{id}/files/delete`
  - `POST /projects/{id}/files/upload` (multipart, `?_csrf=` 검증)
- i18n: `fileTree.upload/newFile/newFolder/rename/delete/hint.toolbar/prompt.*/confirm.*`,
  `flash.file.folderCreated/renamed`, `api.fileBrowser.alreadyExists/emptyName/invalidName`
  (Ko/En).

### Removed

- **기존 `/projects/{id}/files` 페이지 (uploads 카탈로그) 제거** —
  - `GET /projects/{id}/files` (filesPage)
  - `POST /projects/{id}/files/upload` (uploads.upload 흐름)
  - `GET /projects/{id}/files/{fileId}/download`
  - `POST /projects/{id}/files/{fileId}/delete`
  - 모두 삭제. 사용자 가시성은 0 — 같은 path 의 새 파일 탐색기 액션으로 대체.
  - Overview 페이지 / Console sideLinks 의 잔존 `/files` link 도 정리
    (overview 의 Files link 한 줄 제거, console sideLinks 의 link 는 `/tree` 로).
- `UploadService` / `uploads` 테이블 자체는 보존 — 다른 곳 (multipart Claude
  credentials upload 등) 에서 사용 가능. 단지 SSR `/files` 카탈로그 UI 만 제거.

### Notes

- 파일 탐색기 prompt 는 브라우저 `window.prompt` 기반 (no extra JS). 모달 UI
  필요하면 후속 cycle.
- 디렉토리 삭제는 재귀 — `Files.walk + reverseOrder + delete`. 큰 디렉토리는 시간 걸릴
  수 있으나 본 도구의 워크스페이스 크기 (~MB 단위) 기준 즉시 완료.

## [1.13.1] - 2026-05-27 — 메타데이터 chip row → Settings 드롭다운 안 dl 로 이동

### Changed

- 사용자 보고: 헤더의 meta-chip row 가 자리를 많이 차지함. chip 제거 후
  ⚙ Settings 드롭다운 상단의 `<dl>` 로 이동 (package / module / source /
  debugTask / lastBuild / updated 6개 그대로). 헤더는 `← Projects · 프로젝트명 ·
  ⚙ 설정` 으로 깔끔.
- 드롭다운 min-width 220px → 320px (max 480px), source path 가 길어도 word-break
  로 wrap.
- 미사용 helper `shortenPath` 제거.

## [1.13.0] - 2026-05-27 — Overview 탭 제거, 메타데이터·Delete·Zip 을 sticky 헤더로 통합

### Changed

- **Overview 탭 제거** — v1.12.0 에서 prerender 탭으로 추가한 `/overview` 는
  unique 기능 중 (a) action link 들이 모두 이미 다른 탭과 중복이었고 (b) recent
  builds 카드도 Builds 탭과 중복이었음. 남은 unique 기능 3종은 sticky 헤더로 이동:
  - **메타데이터** (`package` / `module` / `source` / `debugTask` / `lastBuild` /
    `updated`) → 헤더 인라인 `meta-chip` row. 첫 시선에 가장 자주 보고 싶은
    정보라 항상 노출.
  - **프로젝트 삭제 (Delete form)** → 헤더 우측 ⚙ Settings 드롭다운 안에 위치.
    confirm dialog + CSRF hidden input 그대로 유지.
  - **Zip 다운로드** → 같은 Settings 드롭다운 안 link.
- 결과: 탭 13개 → 12개 (Console / Builds / Files / Git / Agents / History /
  Symbols / Stats / Deps / Wrapper / Automation / Env-files).

### Notes

- `/projects/{id}/overview` URL 자체는 그대로 작동 (북마크 호환). 다만 탭 바에서
  진입 경로 제거 — 사용자가 직접 입력 / 북마크 으로만 접근.
- v1.12.0 에 추가됐던 `tabs.overview` i18n 키 제거 (Ko/En 양쪽).

## [1.12.1] - 2026-05-27 — 프로젝트 탭의 "더보기" 드롭다운 클릭 무반응 회수

### Fixed

- **`.tab-bar { overflow-x: auto }` 가 absolute positioned `.more-menu` 까지 클리핑
  → 더보기 메뉴가 펼쳐져도 화면에 안 보이던 회귀** (사용자 보고). CSS 스펙상
  `overflow-x: auto` 면 `overflow-y` 도 hidden 처리되어 부모 박스를 벗어나는
  absolute 자식이 잘림. layout 을 inner scroll wrapper 로 변경:
  - 신규 `.tab-scroll` (inner flex, `overflow-x: auto`) 가 탭 버튼들만 가로 스크롤.
  - `.tab-bar` 자체는 overflow visible — `.more-dropdown` 이 외부에 위치한 채로
    absolute child 가 자유롭게 펼쳐짐.
  - `.more-dropdown` 은 `flex-shrink: 0` 으로 항상 우측 고정 (탭 많아도 잘리지 X).

## [1.12.0] - 2026-05-27 — 프로젝트 탭에 기존 모든 SSR 페이지 prerender 통합

### Wire change

No (SSR-only — shared/ 무변경).

### Added

- **13개 탭 모두 prerender** — v1.11.0 의 5개 (Console / Builds / Files / Git /
  Agents) 에 더해 기존 More 드롭다운에 link 로 두었던 모든 페이지를 prerender
  탭에 통합:
  - History (`/history`)
  - Overview (`/overview` — 기존 metadata 카드 페이지)
  - Symbols (`/symbols`)
  - Stats (`/stats`)
  - Deps (`/deps`)
  - Wrapper (`/wrapper`)
  - Automation (`/automation`)
  - Env-files (`/env-files`)
- 모두 첫 페이지 로드 시 iframe 으로 동시 fetch → 탭 전환 즉시. WebSocket / SSE
  도 모두 백그라운드 유지.
- 신규 i18n: `tabs.history`, `tabs.overview`, `tabs.symbols`, `tabs.stats`,
  `tabs.deps`, `tabs.wrapper`, `tabs.automation`, `tabs.envFiles` (Ko/En).

### Changed

- More 드롭다운 — 8개 link 중 7개는 탭 통합. 1개만 남음: **Claude usage (global)**
  → `/usage` (프로젝트 scope 없음, 본 서버 전체의 사용량 페이지라 탭에 부적합).
- v1.11.0 의 `tabs.more.history` / `.symbols` / `.usage` / `.wrapper` / `.stats` /
  `.deps` / `.automation` / `.envFiles` i18n 키 중 (`.usage` 제외) 7개는 일반
  `tabs.<id>` 키로 이동.

### Notes

- 첫 페이지 로드 시 13개 iframe 동시 fetch — 1.7.x 시절 단일 페이지 대비 분명 더
  무거움. 사용자 명시 ("프리빌드하여 지연시간 최소화") 채택. 부담스러우면
  추후 idle-time lazy load (마우스 hover / requestIdleCallback) 로 전환 옵션 검토 가능.
- 가로 탭 바는 `overflow-x: auto` 라 스크롤 동작. 작은 화면에선 wrap 안 함.

## [1.11.0] - 2026-05-27 — 프로젝트 통합 탭 페이지 (Console 기본 + Builds/Files/Git/Agents prerender)

### Wire change

No (서버 SSR + admin static asset 한정 — shared/ 무변경).

### Added

- **단일 페이지 통합 탭 UX** — 기존엔 `/projects/{id}` 가 메타데이터 카드 페이지 +
  Console / Builds / Files / Git / Agents 가 각각 별도 SSR 페이지였다. 화면 이동
  마다 전체 reload + Claude session WS / 빌드 로그 stream 재접속 → 작업 흐름 끊김.
  v1.11.0 부터 `/projects/{id}` 진입 시:
  - sticky 상단 탭 바 (Console / Builds / Files / Git / Agents 5개) +
    "More" 드롭다운 (History / Symbols / Usage / Wrapper / Stats / Deps /
    Automation / Env-files — 외부 link, 새 탭 open).
  - 5개 핵심 페이지를 모두 `<iframe>` 으로 **prerender** — Console 만 default
    visible, 나머지 4개는 hidden DOM. 탭 클릭 시 CSS display 토글로 즉시 전환.
  - **WebSocket / SSE 항상 connect 유지** — iframe 이 항상 살아있어 백그라운드
    탭에서도 Claude turn 진행 / 빌드 로그 누적이 끊김 없이 이어진다 (사용자
    명시 선택).
  - URL hash 동기 (`#console`, `#builds`, ...) + `localStorage` 에 마지막 활성 탭
    저장 — refresh 후에도 같은 탭으로 복귀.
  - iframe `onload` 에서 inner admin shell 의 `<nav class="sidebar">` / settings
    tab bar 를 숨기고 layout grid 를 1-column 으로 압축 — 시각적으로 단일 페이지.
  - 신규 `static/admin/project-tabs.js` (zero-dep, ~80 LoC).
- **`/projects/{id}/overview`** — 기존 metadata 카드 페이지를 별도 URL 로 보존.
  More 메뉴에서 link.

### Changed

- `/projects/{id}` 의 default 반환 페이지가 `WebProjectTemplates.projectDetailPage`
  → 신규 `ProjectTabsTemplate.page`.
- 기존 `/projects/{id}/console`, `/builds`, `/tree`, `/git`, `/agents` URL 은
  **그대로 작동** (북마크 호환). iframe src 도 같은 URL 을 사용 — 기존 5개
  template 함수 0 수정.

### Notes

- prerender 대상은 핵심 5개. 자주 안 쓰는 8개 페이지는 More 메뉴 link → 직접
  진입 (별도 탭). 추후 사용 패턴 분석 후 prerender 셋 확장 가능.
- 같은 origin 이므로 iframe contentDocument 조작 안전. cross-origin sandbox
  없음 — 단일 사용자 LAN 도구 특성.

## [1.10.0] - 2026-05-27 — 안드로이드 에뮬레이터 빌드환경 다운로드

### Wire change

No (서버 내부 + admin SSR + vibe-doctor 한정 — shared/ 무변경).

### Added

- **빌드환경 페이지에 "Android 에뮬레이터" 카드 신규** —
  - `SetupComponent.EMULATOR` (id `emulator`, doctorCmd `emulator`,
    `~1.5-2 GB · 5-10 min` size hint). 기존 ANDROID_SDK 카드와 분리 — 빌드용
    코어와 에뮬레이터 패키지를 사용자가 명시적으로 선택해 받게.
  - `EnvSetupService.probeEmulator` — `$ANDROID_HOME/emulator/emulator`
    바이너리 + `$ANDROID_HOME/system-images/android-NN/VARIANT/ABI` 디렉토리
    존재 여부로 INSTALLED / PARTIAL / MISSING 판정.
  - `EnvSetupTemplates.renderAction` 의 EMULATOR 분기 — 다운로드 버튼 + KVM
    부팅은 `/emulator` 페이지 안내 hint + `docker exec ... vibe-doctor emulator`
    CLI hint.
- **vibe-doctor `emulator` 서브커맨드** —
  - `docker/doctor/manifest.yml` 에 `emulator:` 섹션 신규
    (`emulator` + `system-images;android-35;google_apis;x86_64`).
  - `docker/doctor/lib/android-sdk.sh` 에 `install_emulator_packages()` +
    `install_emulator_all()` 함수 추가. cmdline-tools / 라이선스 사전조건은
    동일 흐름 (이미 ANDROID_SDK 설치한 사용자는 skip).
  - `docker/doctor/vibe-doctor` 메인 case 에 `emulator` 추가 + `usage()` 도움말.
- i18n: `env.comp.emulator.*`, `env.size.emulator`,
  `env.action.emulatorLabel.*`, `env.action.emulatorConfirm`,
  `env.action.emulatorNote`, `probe.emulator.*` (Ko/En 동시).

### Notes

- 부팅 / KVM passthrough 는 본 카드의 책임이 아님 — 별도 (`--device /dev/kvm`
  + privileged 또는 `:full` 이미지 + `/emulator` 페이지). v0.57.0 Phase 36 의
  Helm `:full` 변형은 이미 그 path 를 cover.
- 추가 system-image (다른 API / ABI / variant) 가 필요하면 `vibe-doctor emulator`
  로 default set 받은 뒤 컨테이너 안에서 `sdkmanager 'system-images;...'`
  직접 호출. 본 카드는 default 1개 system-image 까지만.

## [1.9.0] - 2026-05-27 — Git global identity 온보딩 / 영속화

### Wire change

No (서버 내부 + admin SSR + Dockerfile/entrypoint 한정 — shared/ 무변경).

### Added

- **Git global identity 관리 (`user.name` / `user.email`)** — 컨테이너의
  자식 git CLI (clone / commit / log / push) 가 정확한 author 로 작동하도록
  필수. 미입력 시 commit 이 'Please tell me who you are' 로 실패하거나 빈
  author 로 GitHub 매칭 누락.
  - `GitConfigService` (`env/GitConfigService.kt`) — `git config --global`
    invoke 기반. get/set/clear + email 정규식 + 길이 제한.
  - `/env-setup` 페이지 상단에 **Git Identity 카드** 신규. name + email 2-field
    form + 저장 / 초기화 버튼 + 고급(터미널 명령) details. SSR + CSRF.
    `POST /env-setup/git-config` / `/env-setup/git-config/clear` 신규 라우트.
  - **Dashboard (`/`) 에 yellow banner** — git identity 미설정 시 첫 진입 화면
    상단에 "Git Identity is not configured" 카드 + "Open Build Environment →
    Git Identity" CTA. 입력 후 자동 사라짐. dashboard 한 곳에만 두어 가장 적은
    UI noise + 가장 첫 진입점에서 인지 보장.
- i18n: `git.id.*`, `api.gitConfig.*`, `dashboard.gitIdentity.*` (Ko/En 동시).

### Changed

- **Dockerfile** — `ENV GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config` 신규.
  git 2.32+ 의 명시적 global config path. 컨테이너의 모든 git 자식 프로세스가
  같은 파일을 인식. 기존 `dev-tools/config` 영속 볼륨 안에 위치하므로 별도
  mount 추가 불필요 — 이미지 업그레이드 / 컨테이너 재시작 후에도 자동 유지.
- **entrypoint.sh** — `/home/vibe/.config/git/` 디렉토리 idempotent 생성 +
  ownership/mode 정리 (700/600). 파일 자체는 자동 생성 X (사용자 입력으로만
  채워짐 — 자동 ID 추측 금지).
- `AdminRoutesDeps` 에 `gitConfig: GitConfigService` 추가.
- `ServerContext` (Module.kt) 에 `gitConfig` 필드 추가.
- `AdminTemplates.dashboardPage` 시그니처에 `gitIdentityMissing: Boolean = false`
  추가 (default false — 기존 호출자 무영향).

## [1.8.0] - 2026-05-27 — 키스토어 자동 적용 (Gradle inject + Claude prompt)

### Wire change

No (서버 내부 + admin SSR 한정 — shared/ 무변경).

### Added

- **자동 signing inject (Phase 1)** — `BuildService` 가 빌드 시작 시 프로젝트
  `packageName` 으로 `KeystoreService.loadSigning(...)` 호출. 매칭되는
  `<pkg>.keystore` + `<pkg>-keystore.properties` 가 `/home/vibe/keystores/` 에
  존재하면 Gradle CLI 에 `-Pandroid.injected.signing.store.file/key.alias/...`
  4종 inject. AGP 의 IDE-injected signing path 라 release variant 즉시 적용
  — `build.gradle.kts` 무수정.
  - 빌드 로그의 `gradle command:` 라인은 store/key password 를 `***` 로 redact
    (서버 SLF4J / WS frame 모두). `ps` 노출은 단일사용자 컨테이너 특성상 감수.
  - debug variant 는 build.gradle.kts 의 `signingConfigs.debug` 가 명시되어
    있어야 효과가 있음 → Phase 2 보완.
- **build.gradle.kts 자동 수정 (Phase 2)** — `/settings/keystores` 의 각 행에
  "Apply to project" 드롭다운 + Apply 버튼. `POST /settings/keystores/{pkg}/apply/{projectId}`
  가 정형화된 한국어 prompt 를 `ClaudeSessionManager.sendPrompt(...)` 로 콘솔에
  전송하고, 사용자는 `/projects/{id}/console` 로 자동 이동.
  - prompt 는 `<pkg>-keystore.properties` 절대경로만 전달 — 비밀번호 본문 포함 X.
    Claude 가 `Properties().load(FileInputStream(...))` 패턴으로 영구 적용.
  - 같은 (project, keystore) 조합엔 매번 같은 prompt — LLM 출력 불확정성을
    템플릿 고정으로 최소화.
  - 동일 패키지 프로젝트는 드롭다운 최상단 (`optgroup label="Same package"`).
- i18n: `ks.col.apply`, `ks.apply.button/selectProject/matching/others/noProjects/confirm`,
  `ks.flash.applied` (Ko/En). `ks.usage.step1/2` 본문도 자동 적용 두 단계
  내용으로 갱신.

### Changed

- `KeystoreService` 에 `loadSigning(packageName)`, `storeFilePath(...)`,
  `propertiesPath(...)` + `SigningCredentials` data class 추가.
- `KeystoreService` 인스턴스가 `keystoreRoutes` local 에서 → `ServerContext`
  필드로 승격. `BuildService` 와 routes 가 같은 인스턴스 공유.
- `GradleBuilder.runAssembleDebug` 시그니처에 `signing: SigningCredentials? = null`
  추가 (default null → 기존 호출 흐름 유지).
- `keystoreRoutes` 시그니처에 `ProjectRepository`, `ClaudeSessionManager` 추가
  (Phase 2 wiring).

## [1.7.25] - 2026-05-27 — `~/.android/debug.keystore` 영속 volume + INSTALL_FAILED_UPDATE_INCOMPATIBLE 해소

### Fixed

- **빌드한 디버그 APK 가 기존 디바이스 설치 앱과 cert 불일치 → 설치 실패**
  (사용자 보고). 진단:
  - `build.gradle.kts` 의 debug variant 는 별도 `signingConfig` 미설정 →
    Android Gradle plugin 의 default `~/.android/debug.keystore` 자동 사용.
  - **컨테이너 안 `/home/vibe/.android/` 가 volume 미매핑** → 매 컨테이너
    재시작마다 ephemeral → Gradle 이 새 cert (`C8:71:88:F3...`, 비번
    `android`) 로 매번 자동 재생성.
  - 사용자 PC 의 평소 debug.keystore fingerprint (`26:4B:E2:3A...`) 와
    불일치 → 같은 `<pkg>.debug` 패키지 + 다른 cert →
    `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **Fix** (`docker/compose.yml` + 운영 compose):
  - 신규 volume `${VIBE_DATA_ROOT}/dev-tools/android:/home/vibe/.android`
    추가. 컨테이너 재시작 / 이미지 업그레이드 시에도 cert 영속.
  - 호스트 PC 의 `~/.android/debug.keystore` 를 이 디렉토리에 복사해두면
    호스트 / 컨테이너 빌드가 같은 debug cert 공유 → 디바이스 충돌 없음.
- 운영자 즉시 적용 완료: `~/.android/debug.keystore` →
  `vibe-coder-data/dev-tools/android/debug.keystore` (`26:4B:E2:3A...`) 영속.

## [1.7.24] - 2026-05-27 — Multi-module 빌드의 APK find 경로 fix (BUILD SUCCESSFUL 인데 FAILED 회귀)

### Fixed

- **빌드 로그엔 `BUILD SUCCESSFUL` 인데 빌드 상태 FAILED + APK 없음** (사용자
  보고). 진단:
  - v1.7.22 의 moduleName auto-detect 가 multi-module 의 Gradle path
    `android-app:app` 를 잘 저장 → `:android-app:app:assembleDebug` 호출 성공
    → `BUILD SUCCESSFUL`.
  - 그러나 `ApkFinder.findLatestDebug` 가 `source.resolve(moduleName)` 으로
    filesystem path 만듦 → `/workspace/vibe-coder-android/android-app:app`
    (잘못된 이름) → 디렉토리 미존재 → null 반환 → BuildService 가
    `setStatus(FAILED, "apk not found")`.
- **Fix** (`ApkFinder.findLatestDebug`): `moduleName.replace(':', '/')` 로
  Gradle path 의 `:` 를 filesystem path 의 `/` 로 변환. 결과:
  `android-app/app/build/outputs/apk/debug/*.apk` 정확히 찾음.

## [1.7.23] - 2026-05-27 — Gradle .gradle/.android 캐시 root-owned 잔여물 자동 정리

### Fixed

- **`executionHistory.lock (Permission denied)` 빌드 실패** (사용자 보고).
  진단:
  - `/workspace/<project>/.gradle/9.5.1/executionHistory/` 가 root:root 소유.
  - vibe 사용자 (uid 1000) 가 쓸 수 없어 Gradle daemon 이 lock 생성 시점에 실패.
  - 원인 — 이전에 `docker exec` (default user root) 또는 다른 process 가 그
    경로에 file 생성. entrypoint 의 chown 루프가 top-level 디렉토리만 1회
    chown 해서 재귀 정리 안 됨.
- **즉시 fix**: `docker exec -u root vibe-coder-server chown -R vibe:vibe
  /workspace/vibe-coder-android/.gradle`.
- **근본 fix** (`docker/entrypoint.sh`): 부팅 시점에 `/workspace/*/.gradle`
  + `/workspace/*/.android` 디렉토리 내부에 root-owned 파일이 있으면 자동
  `chown -R vibe:vibe`. find 로 빠르게 idempotent — 매 부팅마다 자동 정리.
  로그에 `[warn] ... 안 root-owned 잔여물 발견 — chown -R vibe:vibe` 출력.

## [1.7.22] - 2026-05-27 — Multi-module clone 프로젝트 빌드 실패 fix (moduleName auto-detect)

### Fixed

- **외부 레포 clone 후 빌드 시 `Cannot locate tasks that match ':app:assembleDebug'`
  에러** (사용자 보고). 진단:
  - 모든 프로젝트의 `module_name = "app"` 으로 hardcoded.
  - BuildService 가 `:$moduleName:$debugTask` (즉 `:app:assembleDebug`) 호출.
  - vibe-coder-android 같은 multi-module 프로젝트 (실제 `:android-app:app`)
    에서 `app` 모듈 없음 → 빌드 실패.
  - 즉시 fix: vibe-coder-android 의 DB `module_name = 'android-app:app'`
    수동 update.
- **근본 fix — `ProjectService.detectAppModuleFromClonedRepo`**:
  1. clone 후 `settings.gradle.kts` (또는 `settings.gradle`) 의
     `include(":...")` 항목 모두 수집.
  2. 각 모듈의 `build.gradle.kts` (또는 `build.gradle`) 에서
     `com.android.application` plugin 적용 여부 검사.
  3. 첫 매치 모듈 path 를 `moduleName` 으로 사용 (예: `android-app:app`).
  4. 미감지 시 default `"app"` 유지 (backward compatible).
- 영향: `ProjectRow.module_name` + `CLAUDE.md`의 `## Project Info` 모두
  정확한 module path 로 자동 저장.

## [1.7.21] - 2026-05-27 — TestFlight 업로드 카드 렌더 제거 (Android 전용 도구)

### Changed

- **빌드 detail 페이지의 TestFlight 업로드 카드 렌더 제거** (사용자 요구
  "아이폰 앱은 현 프로젝트에서 지원대상이 아님"). CLAUDE.md §1 ("Android
  앱을 만드는 도구") 와 일관. ASC_KEY_ID / ASC_ISSUER_ID / .p8 등 iOS 전용
  사전조건 경고 노이즈 제거. Play Console 업로드 카드는 그대로 유지.
- POST 라우트 (`/projects/{id}/builds/{buildId}/testflight-upload`) 와
  `TestFlightPublishService` 는 그대로 남김 — API 호환 + 미래 iOS 지원
  확장 가능성에 대비. 다시 노출하려면 `WebProjectTemplates.kt` 의 한 줄
  복원 (코드 안 주석으로 안내).

## [1.7.20] - 2026-05-27 — xterm vendor cache-bust 쿼리

### Fixed

- v1.7.19 배포 후 사용자가 force-reload 안 하면 브라우저가 이전 CDN
  응답을 stale cache 로 들고 있어 여전히 검은 화면 — `?v=5.5.0` cache-bust
  쿼리 추가로 자동 무력화. xterm.min.js / xterm.min.css / addon-fit.min.js
  모두.

### Notes

- `HEAD /static/...` 는 Ktor `staticResources` 의 한계로 404 (GET 은 200
  정상). 브라우저는 GET 사용하므로 영향 없음.

## [1.7.19] - 2026-05-27 — 터미널 검은 화면 / 연결 끊김 fix (xterm.js 로컬 번들)

### Fixed

- **`/settings/terminal` 검은 화면 + "연결 끊김" 상태** (사용자 보고). 진단:
  서버 로그상 WS handshake 는 `101 Switching Protocols` 성공 + bash PTY
  정상 spawn. 클라이언트 측에서 xterm.js / FitAddon 을 jsdelivr CDN 에서
  로드하다 실패 → `Terminal is not defined` JS 에러 → `term.open()` 미실행
  → div 만 검은 배경. WS 는 onopen 안에서 throw 후 close → onclose 가 "연결
  끊김" 표시.
- **Fix**: xterm.js 5.5.0 + addon-fit 0.10.0 을 `resources/static/admin/vendor/
  xterm/` 에 로컬 번들 (3 파일, 총 ~294KB). SSR 의 `<link>` / `<script>`
  를 `/static/vendor/xterm/...` 로컬 path 로 변경. CLAUDE.md §3 의 "외부
  CDN 미사용" 정책도 같이 일관화.

## [1.7.18] - 2026-05-27 — 기존 폴더 덮어쓰기 옵션 + 명확한 에러 안내

### Fixed

- **"Target project directory is not empty" 에러 회수** (사용자 보고). 이전
  clone 이 실패했거나 사용자가 폴더만 남기고 정리하지 않은 상황에서
  같은 cloneUrl 로 재시도하면 거부됐고 정정 방법 안내가 부족했음.
  - 신규 폼 옵션 **"기존 폴더 덮어쓰기"** checkbox. 체크 시 server 가
    srcRoot 내용을 모두 삭제 후 clone 진행.
  - `api.project.targetNotEmpty` 메시지 강화 — 사용자에게 정확한 정정
    절차 안내 ("'기존 폴더 덮어쓰기' 옵션을 체크 후 다시 시도하세요").
  - 신규 i18n 키 `projects.new.overwrite` / `projects.new.overwriteHint`
    / `api.project.overwriteFailed` (ko + en).

### Wire change

- **Wire change: Yes** — `RegisterProjectRequestDto` 에 `overwrite: Boolean
  = false` 추가. default false 라 **backward-compatible**: 기존 클라이언트
  요청은 동작 변경 없음. vibe-coder-android 의 `shared/` 모듈에 같은 field
  추가 권장 (모바일에서 같은 fix 활용하려면).

## [1.7.17] - 2026-05-27 — i18n Phase 5 (buildClaudeAuthHelp + checkWorkspace)

### Changed

- **i18n Phase 5 마무리**. EnvDiagnostics 의 잔여 한국어 hardcoded 처리.
  - `buildClaudeAuthHelp(cfg, lang)` 시그니처에 lang 추가. 9줄짜리 한국어
    multi-line 가이드 (도커 환경 `--user vibe` 안내 + 호스트 환경 안내 +
    refresh token 자동 갱신 설명) 를 `diag.claudeAuth.help` 단일 키로
    multi-line 저장 (ko + en 양쪽 번역).
  - `checkWorkspace(lang)` 시그니처에 lang 추가. "read/write OK" / "cannot
    write to ..." 메시지 → `diag.workspace.ok` / `diag.workspace.fail`.
- **i18n 정밀 점검 결과**: McpTemplates / BackupRoutes / PushSettingsRoutes
  / WebhookSettingsRoutes / EmailSettingsRoutes / UsersRoutes /
  TwoFactorRoutes / MultiConsoleRoutes / ToolsRoutes / SshKeyRoutes /
  GitIntegrationsTemplates / AdminTemplates / WebProjectTemplates / AuthRoutes
  / PasswordRoutes 등 모두 이미 i18n 잘 적용된 상태로 확인. 빌드 상태 enum
  ("SUCCESS" / "FAILED" 등) 은 글로벌 convention 으로 영문 유지.

## [1.7.16] - 2026-05-27 — i18n Phase 4 (EnvSetupService + SetupComponent enum)

### Changed

- **i18n Phase 4** (사용자 요구 다음 cycle). 빌드환경 페이지의 카드 라벨 +
  진단 메시지 한국어 hardcoded 잔존을 i18n 화.
  - **`SetupComponent` enum**: 9개 컴포넌트 (JAVA / GIT / NODE / CLAUDE_CLI /
    CLAUDE_AUTH / ANDROID_SDK / PLATFORM_TOOLS / MCP_DEFAULTS / GRADLE) 의
    `displayName` / `description` / `sizeHint` 를 i18n 키 String 으로 변경.
    SSR `EnvSetupTemplates.renderCard` 가 `Messages.t(lang, key)` 로 lookup.
  - **`EnvSetupService.probe*` 6 함수** (probeCmd / probeClaudeAuth /
    probeAndroidSdk / probePlatformTools / probeMcpDefaults / probeGradle):
    각 메시지 한국어 → `probe.*` i18n 키. detectAll(lang) / detect(c, lang)
    시그니처 확장.
  - **호출자 update**: `EnvSetupRoutes.detectAll(sess.language)` 전달.
    API endpoint / internal 호출자는 default `"en"` 유지.
- **새 i18n 키** ~50개 (ko + en): `env.comp.*` (18), `env.size.*` (6),
  `probe.*` (22).

## [1.7.15] - 2026-05-27 — i18n Phase 3 (Sub-agent 본문 + EnvDiagnostics 진단 메시지)

### Changed

- **i18n 정밀 점검 Phase 3** (사용자 요구). hardcoded 한국어 → i18n 키 변환.
  - **`SubAgentRoutes` 본문**: heading `Sub-agent consoles`, intro
    (`Real multi-agent...`), lifecycle (`Idle 30분...`), empty 메시지,
    badge (`running` / `idle`), 콘솔 열기 / 메인 콘솔 / Agent 관리 link
    모두 `agents.*` 키로.
  - **`EnvDiagnostics`**: `run(lang)` 시그니처 추가, 6개 check 함수 (Java /
    Android SDK / Git / Claude CLI / Claude Auth / workspace) 가 lang 받음.
    Claude Auth 진단 메시지 11개 (cliMissing / apiKey / strayRoot /
    loginRequired / refreshOnlyOk / parseWarn / expiredHard / expiredRefresh
    / imminent / okExpiry 등) 모두 `diag.claudeAuth.*` 키로.
  - **호출자 update**: `WebProjectRoutes` 2곳 (`/projects/{id}/console`,
    `/chat`), `AdminRoutes` dashboard 의 envDiagnostics.run() 에 `sess.language`
    전달. `ConsoleRoutes` / `StatusService` / `CapabilityService` / `EnvRoutes`
    API endpoint 는 default `"en"` 유지 (session 없음).
- **새 i18n 키 ~35개** (ko + en): `agents.*` (9), `diag.*` (24).

## [1.7.14] - 2026-05-27 — i18n 정밀 점검 Phase 1 (Settings + CORS + Git + LogSearch + common keys)

### Changed

- **SSR 페이지 영어 hardcoded → i18n 키 변환 Phase 1** (사용자 요구). 한글
  사용자가 영어로만 보이던 페이지 다수를 i18n 키 기반으로.
  - **Settings General (`/settings`)**: `Workspace` / `Claude` / `Build`
    fieldset legend, `Port` / `Host` / `Max upload` / `Artifact keep count`
    / `Path` / `Timeout (min)` / `Default debug task` label, 하단 hint
    (`server.yml` atomic save 안내) 모두 i18n 화.
  - **CORS (`/settings/cors`)**: `Allowed Host` th → `table.allowedHost`.
  - **Git integrations (`/settings/git-integrations`)**: `Provider` /
    `Host` / `Username` / `Token (masked)` th.
  - **Log search (`/logs/search`)**: `Project / Build` / `Match` th.
  - **신규 i18n 키 그룹**:
    - `settings.legend.*` (4) — server / workspace / claude / build.
    - `settings.field.*` (7) — port / host / maxUploadMb / artifactKeepCount /
      path / timeoutMin / defaultDebugTask.
    - `settings.persist.hint` (1).
    - `table.*` (15) — host / allowedHost / username / tokenMasked / provider /
      match / matchPreview / projectBuild / project / timeUtc / projectRole /
      roleTool / contentClip / agent / status / actions.
    - `common.*` (10) — action / delete / edit / create / add / remove / cancel /
      close / confirm / back / empty / loading / search.
- **Phase 2 (같은 cycle 추가)**: `HistoryRoutes` / `GlobalHistorySearchRoutes`
  / `UsageRoutes` / `SubAgentRoutes` 4개 파일 시그니처 확장 + `sess.language`
  전달 + table header 라벨 (`Time (UTC)` / `Role / Tool` / `Content (clip 800)`
  / `Project / Role` / `Match preview` / `Project` / `Agent` / `상태`) 모두
  i18n 화.
- **추후 Phase 3 (다음 cycle)**: `SubAgentRoutes` 본문의 한국어 hardcoded
  안내 텍스트 (`Sub-agent consoles`, `Idle 30 분 후 자동 SIGTERM` 등),
  `EnvDiagnostics` 진단 메시지 한국어 hardcoded → i18n.

## [1.7.13] - 2026-05-27 — Settings sub-page 메뉴 노출 (키스토어 / PTY 터미널 등)

### Fixed

- **설정 sub-page 들이 어디서도 link 노출 안 됨** (사용자 보고 "설정/키스토어
  관련 메뉴가 안보임" + "pty 터미널 메뉴도 안보임"). SettingsNav 의 탭바는 8개
  (general / security / notifications / build-env / prompts / backup / monitoring
  / users) 만 노출했고 sub-page (`/settings/keystores`, `/settings/ssh-key`,
  `/settings/cache`, `/settings/terminal`, `/settings/cors`, `/settings/webhook`,
  `/settings/push`, `/settings/git-integrations`, `/2fa`, `/webauthn`, `/devices`,
  `/env-setup/mcp`) 는 link 가 어디에도 안 보였음. URL 직접 입력해야만 닿는 상태.
  - **Fix A — Settings General (`/settings`) 상단**: 12개 sub-page chip row.
  - **Fix B — Build environment (`/env-setup`) 상단**: build 관련 6개 (keystores
    / ssh-key / cache / terminal / git-integrations / mcp) chip row.
  - 새 i18n 키 22개 (ko + en).

## [1.7.12] - 2026-05-27 — 콘솔 응답중 로딩 스피너

### Added

- **콘솔창 하단 로딩 스피너** (사용자 요구). console-log-wrap 안 console-log
  직후에 `<div id="console-spinner">` 추가. CSS keyframe rotate 0.85s linear
  (외부 라이브러리 미사용) + #69db7c 컬러 + "● 응답중" 라벨. `setInFlight(true)`
  시 visible, false 시 hidden. console_busy_state WS frame 도 동일 toggle.

## [1.7.11] - 2026-05-27 — 콘솔 prompt 전송 후 포커스 해제

### Changed

- **prompt 전송 후 textarea blur** (사용자 요구). 이전엔 `sendPrompt` finally
  에서 `input.focus()` 자동 호출 → 모바일에선 키보드 다시 올라오고 PC 에선
  textarea 가 빈 상태로 활성화되어 다음 자동 입력 의도가 없을 때도 깜빡임이
  발생. `input.focus()` → `input.blur()` 로 변경. 큐에 push 한 케이스도 동일.

## [1.7.10] - 2026-05-27 — 사이드바 접기 버튼 글리프 가운데 정렬

### Fixed

- **사이드바 접기 버튼 `⇆` 글리프가 우하단 정렬** (사용자 보고). 원인:
  `line-height: 24px` + `height: 28px` mismatch + button 의 기본 inline 배치.
  `display: inline-flex` + `align-items: center` + `justify-content: center`
  + `line-height: 1` 로 정확한 가운데 정렬.

## [1.7.9] - 2026-05-27 — Claude 토큰 자동 갱신 + 응답카드 meta 위치 하단 이동

### Added

- **Claude 토큰 자동 갱신 (`ClaudeTokenRefresher`)** (사용자 요구).
  "컨테이너가 구동되는 동안 토큰을 계속해서 자동갱신하도록 구현. 사용자가 1회
  로그인 후 다시 로그인 하지 않고, 따로 신경 안써도 되게끔."
  - 10분 주기 background loop. `.credentials.json` 의 `claudeAiOauth.expiresAt`
    가 현재 +60분 이내 이면 refresh trigger.
  - Trigger: `claude --print --output-format json "ok"` 1회. Claude CLI 가
    호출 직전 access_token 만료/임박 시 `refreshToken` 으로 자동 재발급. 비용
    minimal (수 cent 미만).
  - 호출 후 `.credentials.json` 의 expiresAt 가 갱신됐는지 비교 + log. 갱신
    실패 시 warn log 만 (notifier 가 별도 임계치 메일 알림 처리).
  - ServerMain init 시점에 start() — 부팅 직후 1회 즉시 + 이후 10분 주기.

### Changed

- **응답 카드 meta (시각 + 복사 버튼) 위치** (사용자 요구). 이전엔 grid 우측
  columns 였으나 응답 카드 내부 하단으로 이동. CSS grid 를 `90px 1fr` 로 복귀
  + `.log-content` wrapper 안에 `.log-body` 위 + `.log-meta` (flex row, right
  align) 아래. opacity 0.45 → hover 시 1.0.
- **i18n 라벨**: `env.badge.partialAuth` "△ 부분 인증" → **"△ 자동 갱신 임박"**.
  `dashboard.signinExpiring` "△ 만료 임박" → **"△ 자동 갱신 임박"** (ko + en).
  실제 의미가 "만료 임박" 이 아니라 "곧 자동으로 갱신될 것" 임을 명확화.

## [1.7.8] - 2026-05-27 — Claude Auth false positive 정밀 회수

### Fixed

- **빌드환경 "△ 부분 인증" + 홈 "Claude 로그인 (비활성)" false positive**
  (사용자 보고). 실제로는 컨테이너에서 claude 정상 사용 중이었으나 두 페이지가
  부정적 라벨로 표시.
  - **근본 원인 1 — 6h 임계치가 과도하게 보수적**: `EnvSetupService.probeClaudeAuth`
    + `EnvDiagnostics.checkClaudeAuth` 모두 `expiresAt - now < 6h` 면 PARTIAL /
    WARNING 처리. Claude CLI 는 `refreshToken` 으로 자동 재발급하므로 만료 임박이라도
    정상 동작 — 우리 진단이 false positive. 임계치 **6시간 → 30분** 축소 + `refreshToken`
    존재 시 OK 로 승급. 만료 시각 파싱 실패해도 refreshToken 있으면 OK.
  - **근본 원인 2 — 홈의 WARNING 매핑 misleading**: `dashboard.disabled "(비활성)"`
    라벨이 실제 의미 ("만료 임박" / "확인 불가") 와 무관. 새 i18n 키
    `dashboard.signinExpiring` ("△ 만료 임박" / "△ Expires soon") 로 분리, WARNING
    매핑을 그쪽으로 이동. "(비활성)" 라벨은 `claude.enabled=false` 등 진짜 비활성 케이스
    전용 (현재 매핑 없음 — 미래 분기용 reserve).
  - 새 helper `readOauthRefreshToken(file)` 양 service 에 동일 형식으로 추가.

## [1.7.7] - 2026-05-27 — 콘솔 응답카드 우측 시각 + 복사 버튼

### Added

- **콘솔 메시지 우측 메타** (사용자 요구).
  - 각 `.log-line` 우측에 **HH:mm:ss 시각** + **복사 버튼** (Lucide copy SVG).
  - 라이브 흐름은 클라이언트 수신 시각, history replay (v1.7.3) 는 DB
    `ConversationTurnRow.ts` 를 parse 해서 표시.
  - 복사 버튼 hover 시 강조 + 클릭 시 `navigator.clipboard.writeText`
    (fallback: `textarea + execCommand`). 1.2초간 ✓ 색 강조.
  - grid 가 `90px 1fr` → `90px 1fr auto auto` 로 확장. auto column 은
    자식 없으면 0 너비라 다른 곳 (build-log) 영향 없음.

## [1.7.6] - 2026-05-26 — 사이드바 정밀 개편 (브랜드 세로 + 메뉴 아이콘 + 접힘 UX)

### Changed

- **사이드바 정밀 개편** (사용자 요구).
  1. **접힘 후 UI 깨짐 fix**: 이전엔 collapsed 상태에서 모든 nav 라벨까지
     숨겨 사이드바 영역이 텅 빈 채로 56px 너비만 남아 깨져 보였음. 메뉴
     아이콘은 보존, 라벨만 숨김 + 가운데 정렬로 깔끔한 좁은 사이드바.
  2. **메뉴 아이콘 도입**: home / projects / chat / tools / settings 5개에
     Lucide-스타일 inline SVG 추가 (외부 CDN 미사용 정책 일관). stroke
     currentColor 라 테마 자동 적응. expanded 상태엔 [icon + 라벨],
     collapsed 상태엔 [icon 만].
  3. **브랜드 아이콘 2배 + 세로 배치**: 32px → 64px round icon, 타이틀
     "Vibe Coder" 가 아이콘 하단에 배치 (column flex). 접힘 시 40px 로
     적당히 축소 + 타이틀 숨김.
  4. **브랜드 전체가 홈 link**: `<a href="/">` 로 wrap. 아이콘 또는 타이틀
     클릭 시 홈으로 이동.
  5. **sidebar-toggle**: brand row 우측이 아니라 brand 아래 별도 줄로 배치
     (brand 가 세로 column 이 되었으므로). 가운데 정렬.
- **CSS 영향**: `.brand` (column flex) + `.brand-icon` + `.brand-title` 신규.
  `.nav-links a` 가 flex row + gap 으로 변경, `.nav-icon` / `.nav-label`
  child 신규. Collapsed selector 가 `.brand span` 에서 `.brand-title` 로,
  추가로 `.nav-label` 숨김 selector 도. Mobile (max-width 768px) 도 brand
  horizontal + small 32px icon 으로 보정.

## [1.7.5] - 2026-05-26 — 콘솔 상단 chip 단순화 (프로젝트 chip 제거 + 화살표 제거)

### Changed

- **콘솔 상단 chip 단순화** (사용자 요구).
  - "← 프로젝트" chip 제거 — sidebar 의 "프로젝트" 메뉴와 중복.
  - 나머지 chip 의 화살표 (→, ⇢) 일괄 제거 + `@ sub-agents` 의 `@` prefix
    제거. 라벨이 깔끔해져 좁은 화면에서도 가독성 ↑.
  - 영향 키: `console.nav.history` / `console.nav.files` / `console.nav.git`
    / `console.nav.symbols` / `console.nav.agents` / `projects.detail.builds`
    (ko + en). `projects.detail.builds` 는 project detail 페이지에서도
    노출되지만 일관된 표기 — 화살표 없는 깔끔한 라벨.

## [1.7.4] - 2026-05-26 — busy 뱃지 위치 이동 (전송 라인 좌측)

### Changed

- **콘솔 busy 뱃지 위치** (사용자 요구). 이전엔 세션 카드 상단 우측 area
  (sideLinks / stop / new session 옆) 에 있어 prompt 입력 중 시선 분리.
  v1.7.4 부터 전송 버튼 라인의 hint 라벨 좌측에 배치 — 입력창 바로 아래에서
  응답중/대기중 상태가 자연스럽게 보임. CSS / JS 변경 없음 (id 동일).

## [1.7.3] - 2026-05-26 — 서버 재시작 후 conversation 복원 + sessionId file fallback

### Fixed

- **서버 재시작 후 콘솔 진입 시 "no session" + 빈 화면 회귀** (사용자 보고).
  WS ring buffer 가 in-memory 라 재시작 시 휘발 + `ClaudeSessionManager.currentSessionId()`
  도 `sessions` map 만 봐서 file 의 `claude-session.id` 영속을 무시 → 모든 프로젝트
  콘솔이 빈 상태로 보였음. 두 갈래 fix:
  1. `currentSessionId(projectId)` 가 in-memory 없으면 `readSessionId(projectId)` (file)
     fallback. 다음 prompt 시 spawnSession 이 그 id 로 `--resume` (기존 v0.13.0 path
     재사용). status badge 가 "no session" 대신 "idle (will resume)" 로 일관.
  2. SSR `/projects/{id}/console` 및 `/chat` 진입 시 `ConversationTurnRepository.list`
     로 last 200 turn (해당 sessionId 한정) 조회 → `<script id="initial-history"
     type="application/json">[...]</script>` 으로 inline embed. 페이지 load 직후
     inline JS `replayInitialHistory()` 가 parse → console-log 에 prepend.
     `role` (user/assistant/tool_use/tool_result/system/done/session) 별로 기존
     `append(cls,label,body,cat)` 형식 그대로 렌더.

### Wire change

- 없음. SSR HTML 만 확장 — JSON API / WsFrame 미변경. Android 클라이언트는
  이미 `/api/projects/{id}/history` 로 history 가져오고 있어 영향 없음.

### Notes

- `usage` role turn 은 inline embed 에서 skip (token cost report — UI 노이즈).
- 매우 큰 tool_result content (>4000 chars) 는 inline embed 시 클립 + " …(+N)"
  표시. 전체는 `/projects/{id}/history` 페이지에서 확인.
- WS ring buffer 의 replay frame 과 일부 중복 가능성 — 정보 손실보다 약간의
  중복 노이즈를 우선 선택.

## [1.7.2] - 2026-05-26 — SCRATCH startup eager bootstrap + 쿼타 pill "초기화" 라벨 제거

### Fixed

- **SCRATCH lazy bootstrap 회귀**: 이전엔 server 재시작 후 운영자가 `/chat`
  메뉴 한 번 진입해야 SCRATCH 프로젝트 디렉토리 + DB row 가 ensure 되어,
  (1) `/api/server/quota` 가 cwd 없이 CLI spawn 실패 → 사이드바 quota pill null,
  (2) 다른 프로젝트 콘솔의 conversation history 가 일부 빈 상태로 로드되던 문제.
  ServerMain 의 init block 에 `projects.ensureScratchProject()` 호출 추가 —
  컨테이너 부팅 직후 자동 ensure.

### Changed

- **사이드바 쿼타 pill 의 "초기화 / resets" prefix 라벨 제거** (사용자 요구).
  이전: `초기화 3:20am` → 이후: `3:20am` 만. `quota.resetPrefix` i18n 키는
  잔존하지만 inline JS 가 더 이상 prefix 로 사용 안 함 (다른 곳 호출처 X).

### Verified

```
$ curl https://<host>/api/server/quota   # 서버 재시작 직후 첫 호출
{
  "sessionUsagePercent": 18,  ...   # 즉시 정상 응답 (이전엔 null)
}
```

사이드바 pill 표시 — `Claude · 세션 (5h) 10% · 3:20am · 주간 (7d) 3% · Jun 2, 6pm`
(이전: `초기화 Resets 3:20am (Asia/Seoul)` 형식 노이즈).

## [1.7.1] - 2026-05-26 — 사이드바 쿼타 pill 의 "(Asia/Seoul)" 표시 회귀 fix

### Fixed

- v1.6.2 가 `stripTz` 정규식을 도입했지만 Kotlin raw string `"""..."""` 안에서
  `\\s` 가 escape 안 되고 그대로 JS 로 출력 → JS regex 가 literal `\s` (backslash
  + s) 를 찾는 패턴이 되어 whitespace 매칭 실패 → timezone 괄호 영역 strip
  되지 않고 그대로 표시 (`"Resets Jun 2, 6pm (Asia/Seoul)"`).
- `\\s` → `\s`, `\\(` → `\(`, `\\)` → `\)` 로 수정. raw string 은 escape 안
  하므로 backslash 한 개가 정답.

## [1.7.0] - 2026-05-26 — Clone path 자동 도출 (cloneUrl 만으로 프로젝트 등록)

요구: git clone 으로 프로젝트 생성 시 다른 정보 (projectId / appName / packageName)
없이 레포 주소만 받아서 자동 도출.

### Wire change

Yes (additive). `RegisterProjectRequestDto` 의 `projectId` / `appName` /
`packageName` 가 `String = ""` default 로 변경 (이전 모두 필수). 기존 호출처
(이미 모든 필드 채우는 사용자) 영향 0.

### Added

- **`ProjectService.autoFillFromCloneUrl(body)`** — sourceType=clone 이고 빈 필드
  있으면 cloneUrl 에서 도출:
  - `projectId`: cloneUrl 의 마지막 segment (`.git` strip + lowercase + sanitize).
    예: `git@gitea.wody.kr:wody/vibe-coder-android.git` → `vibe-coder-android`.
  - `appName`: 도출된 `projectId` 그대로.
  - `packageName`: 일단 placeholder `com.example.<id>` 채움.
- **`ProjectService.detectPackageFromClonedRepo(root)`** — clone 완료 후 호출.
  walk depth 5, `build.gradle.kts` / `build.gradle` 의 `applicationId` /
  `namespace` 또는 `AndroidManifest.xml` 의 `package=` 정규식 매치. 성공 시
  placeholder 를 진짜 값으로 덮어씀.
- **i18n** `projects.new.cloneAutoHint` (en + ko) — UI 의 새 안내 텍스트.

### Changed

- **`ProjectService.register`** — clone path 에선 validation 전 autoFill 적용.
  `val body` → `var body` 로 (clone 후 packageName 덮어쓰기 위함).
- **`WebProjectRoutes`** POST `/projects` — clone path 일 때 projectId / appName /
  packageName 빈 값 허용. cloneUrl 만 필수.
- **`WebProjectTemplates.projectsPage`** — register form 재구성:
  - 소스 유형 (empty / clone) 선택을 form 상단으로.
  - empty path: 기존처럼 모든 필드 명시.
  - **clone path: `cloneUrl` + `branch` (optional) 만 표시**. 다른 fields 자동
    hide + HTML5 `required` / `pattern` 일시 제거 (JS `toggleSource`).
  - clone 안내 텍스트 변경 — 자동 도출 강조.
- **`shared/dto/Dtos.kt`** `RegisterProjectRequestDto` 3 필드 `String = ""` default.

### 사용자 동작 (Clone path)

이전:
1. `/projects` → form: projectId / appName / packageName / sourceType=clone /
   cloneUrl / branch 모두 입력.
2. submit.

이후:
1. `/projects` → 라디오 "Clone existing repo" 클릭 → cloneUrl input 한 줄만 표시.
2. URL 붙여넣고 submit.
3. server 가 자동: projectId / appName / packageName 도출 → clone → 진짜 packageName
   추출 → DB 등록 + CLAUDE.md 작성.

### 호환성

- 기존 클라이언트 (Android v0.x — 모든 필드 채워서 호출) 영향 0. 자동 도출은
  필드가 빈 문자열일 때만 활성.
- 안드 클라이언트 catch-up 별도 cycle 가능 — clone path 의 form 단순화 (현재는
  안드도 모든 필드 받음).

## [1.6.4] - 2026-05-26 — 콘솔 스크롤바 + 우하단 jump-to-bottom 오버레이 (unread badge)

### Added

- **`.console-log` 명시 스크롤바** — Firefox `scrollbar-width: thin; scrollbar-color`
  + Chromium `::-webkit-scrollbar` 10px thumb (#3b3b3b) + 호버 hl.
- **`.console-jump-bottom` 우하단 floating 버튼** — 사용자가 위로 스크롤해서
  최하단이 아닐 때만 표시. 원형 36px, ↓ 아이콘 + 그림자 + 호버 시 accent 색 +
  살짝 translateY. 클릭 시 즉시 `scrollTop = scrollHeight`.
- **Unread badge** — jump 버튼 우상단. 위로 스크롤 중 새 메시지가 append 될
  때마다 카운트 증가. 사용자가 jump 또는 직접 스크롤로 최하단 도달 시 0 리셋.
  `99+` 후엔 cap.
- **i18n** `console.jumpToLatest` (en + ko).

### Changed

- `console-log` 를 `.console-log-wrap` 으로 감쌈 — `position: relative` 부모
  컨테이너 만들어서 jump 버튼이 콘솔 안 우하단에 absolute 배치.
- `append()` 의 자동 스크롤 로직 보강: 사용자가 위로 스크롤 중이면 스크롤
  유지 + unread 증가, 최하단 추적 중이면 기존처럼 즉시 따라감.

## [1.6.3] - 2026-05-26 — 콘솔 하단 입력 UI 순서 재배열 (사용자 요청)

### Changed

SSR 콘솔 페이지 (`/projects/{id}/console`) 의 본문 아래 UI 순서 변경:

| 이전 (위→아래) | 신규 (위→아래) |
|---|---|
| Agent + Template + 관리버튼 (한 줄 묶음) | **Todo 패널** |
| Todo 패널 | **입력창 + Send 버튼** |
| 입력창 | **Prompt template + 관리 (한 줄)** |
| | **Agent dispatch + 관리 (한 줄)** |

- 입력 흐름이 자연스러움 (todo 확인 → 입력 → 보조 도구 selector).
- Prompt template / Agent dispatch 가 **각각 독립 줄** 로 분리. 각 줄 끝에
  관리 버튼 (`/prompts`, `/agents` 링크) 동행 — flex:1 select + flex-shrink:0
  manage 버튼.

## [1.6.2] - 2026-05-26 — 사이드바 접기 toggle + 쿼타 pill 갱신 (타임존 제거 + refresh 아이콘)

요구 3건 묶음.

### Added

- **사이드바 접기 버튼** (`AdminTemplates.shell` + `admin.css`):
  - 사이드바 brand row 우측에 ⇆ toggle 버튼.
  - localStorage `vibe.sidebar.collapsed` 키로 영속 — **페이지 이동해도 상태
    유지** (사용자 요구: "페이지 이동에 영향 받지 않도록").
  - first paint 전 `<head>` 의 inline script 가 localStorage 보고
    `:root[data-sidebar-collapsed="1"]` 적용 → **FOUC 없음**.
  - 접힌 상태: 사이드바 폭 220px → 56px, brand 텍스트 / nav label / quota pill /
    username / logout 버튼 모두 숨김. 콘텐츠 영역 그만큼 확장.
  - i18n: `nav.collapseToggle`.
- **쿼타 pill refresh 아이콘** — pill 헤더 우측 ↻ 버튼. 클릭 즉시 fetch +
  버튼 비활성 → 응답 후 활성. (자동 60s polling 은 그대로.)
- **i18n**: `quota.refresh` (en + ko).

### Changed

- **쿼타 pill reset 시각 표시에서 타임존 제거** (`stripTz` JS helper):
  `"Resets 10:20pm (Asia/Seoul)"` → `"10:20pm"`. 사용자 요청 — 사이드바 좁은
  공간에 타임존 괄호 정보 노이즈.
- **쿼타 pill 헤더 신설** — "CLAUDE" 라벨 + refresh 아이콘. 이전엔 헤더 없이
  두 bar 만.

### CSS

- `.layout.sidebar-collapsed` + `:root[data-sidebar-collapsed="1"] .layout` —
  body class 와 root attribute 둘 다 지원 (JS toggle 이 둘 다 갱신).
- 0.18s `grid-template-columns` transition.

## [1.6.1] - 2026-05-26 — `security.allowTerminal` 기본값 true (사용자 요청)

### Changed

- `security.allowTerminal` 기본값 `false` → `true`. 운영자가 매번 명시 설정 안
  해도 즉시 사용 가능. 컨테이너 sandbox + admin 인증 두 단계 가드로 충분 안전.
  외부 노출 환경에서 비활성화하려면 `false` 명시.
- `server.yml` 의 주석 갱신 — opt-out 표현으로 정리.

## [1.6.0] - 2026-05-26 — Workspace Terminal (`/settings/terminal` xterm.js + PTY bash)

요구: 컨테이너 안 bash 를 web terminal 로 노출. vim / tmux / less / htop 같은
interactive 명령 그대로 사용. SSH 대체.

### Wire change

Yes (additive). 신규 4 `WsFrame` (TerminalOutput/Input/Resize/Exit) + 3 REST
endpoint + 1 WS endpoint. 기존 frame / endpoint 영향 0.

### Added

- **`org.jetbrains.pty4j:pty4j:0.13.4`** 의존성 (server). native PTY binary 자동
  포함 (Linux x86_64/arm64).
- **`security.allowTerminal: false`** (server.yml + SecuritySection) — opt-in
  보안 토글. 기본 비활성, true 로 명시한 환경에서만 라우트 등록.
- **`TerminalSession`** + **`TerminalSessionManager`** — `/workspace` 시작 디렉
  토리에서 `/bin/bash --login -i` spawn. TERM=xterm-256color / LANG/LC_ALL
  UTF-8 / PS1 커스텀. 컨테이너 sandbox 안에서만 작동 — 호스트 영향 없음.
  PTY 의 stdout 을 `SharedFlow<String>` 으로 broadcast, stdin 은 `write()`.
  `resize(cols, rows)` 와 graceful `kill()` 지원.
- **REST**: `POST /api/terminal/sessions` (신규), `GET /api/terminal/sessions`
  (목록), `DELETE /api/terminal/sessions/{id}` (종료).
- **WS**: `/ws/terminal/{sessionId}` — `Auth` 첫 frame + `TerminalInput` /
  `TerminalResize` 양방향, server 가 `TerminalOutput` / `TerminalExit` push.
- **SSR**: `GET /settings/terminal` — xterm.js (BSD, CDN 으로 5.5.0 + FitAddon
  0.10.0) 임베드 + WS 자동 연결 + resize 자동 동기.
- **`WsFrame.TerminalOutput/Input/Resize/Exit`** (`shared/ws/WsFrame.kt`).
- **`ApiPath.TERMINAL_SESSIONS`** + helpers (`shared/ApiPath.kt`).
- **i18n en+ko**: `term.title|backToSettings|intro|status.*` 9 키.
- **`SettingsNav`** — settings/build-env 탭 link.

### 사용자 동작

1. `server.yml` 의 `security.allowTerminal: true` 또는 env
   `VIBECODER_SECURITY_ALLOWTERMINAL=true` 설정.
2. `docker compose up -d --force-recreate`.
3. 브라우저 → 설정 → "Workspace Terminal" 진입.
4. 즉시 PTY bash spawn — `/workspace` 에서 vim, tmux, ls, git 등 자유 사용.
5. 브라우저 닫으면 PTY 가 종료 (idle 자동 정리는 후속).

### 한계 / 후속

- **idle timeout 자동 종료** 미구현 (사용자 explicit DELETE 만). 다음 cycle.
- **history replay** 없음 — 재접속 시 새 PTY (현재 session 의 화면 못 복구).
- **Android catch-up** 별도 (xterm.js WebView 임베드 또는 native Compose terminal).
- **arm64 native** pty4j 가 multi-arch 지원하나 image 가 amd64-only — milestone
  push 시 multi-arch 같이.

### 보안 — 컨테이너 sandbox

- 터미널은 **컨테이너 내부 vibe 사용자의 bash** 만 실행. 호스트 OS 영향 없음.
- `/workspace` 만 mounted (실제 사용자 파일). `/data` / `/home/vibe/.claude` 등
  도 볼륨이지만 vibe 사용자 권한 안에서만 작업 가능.
- 컨테이너 자체 권한 모델 + LAN 노출 정책 + `security.allowTerminal` opt-in 으로
  3 단계 가드.

## [1.5.2] - 2026-05-26 — Usage parser context-window + JVM timezone 명시

### Fixed

- **`parseUsageOutput` session 추출 누락**: v1.5.1 의 라인 단위 substring 매칭이
  여전히 fail. ANSI cursor positioning 이 strip 후 `"Current session"` → 일부 글자
  소실 (예: `"Curret session"`) 시키는 케이스 흔함. 라인 분리 자체를 폐기하고
  **% 매치 좌측 60-char context window 키워드 검사** 로 변경:
    - `sess` (또는 `week`) 키워드의 last-index 비교로 % 가속한 가장 가까운 헤더
      판별. 같은 줄에 헤더+%가 합쳐져도 OK.
    - `sonnet` 포함 context 는 skip (sonnet-only 변종).
    - % 매치 직후 100-char 안의 `Resets <text>` 와 짝지음 (line 무관).
  사용자 환경 (vibe.wody.work) raw text 의 "Curret session... 18% used... Resets 10:20pm"
  올바르게 18 + 10:20pm 으로 추출.

### Added

- **JVM `-Duser.timezone` 명시** (`docker/compose.yml` + 운영자
  `/home/wody/docker/vibe-coder-server/docker-compose.yml`):
  ```
  JAVA_OPTS: -Duser.timezone=${TZ:-Asia/Seoul}
  ```
  컨테이너 TZ 환경변수 (이미 양쪽 `Asia/Seoul`) 와 JVM 의 ZonedDateTime /
  LocalDateTime / Instant 모두 일관 동작. Claude `/usage` 의 reset 시각 표시
  (예: "Resets 10:20pm (Asia/Seoul)") 와 server timestamp / DB UTC 변환 / 사용자
  노출 시각 모두 동기.

## [1.5.1] - 2026-05-26 — TUI Usage capture 실제 동작 + auth 가드 + parser 보강

### Fixed

- **expect script 회귀**: v1.4.2 가 `--dangerously-skip-permissions` 의 trust
  dialog 통과한다고 가정해 무조건 `send "2\r"` 했음. 그러나 이미지에 prebuilt
  된 `.claude/.claude.json` + 영속 볼륨 mount 로 trust dialog 가 매번 안
  떠서, 강제 send 된 "2" 가 빈 prompt 에 user message 로 들어가 prompt
  망가뜨림 → `/status` 가 무효화 → screen capture 0 byte.
- **`docker/scripts/claude-usage-capture.exp`** 전면 재작성:
  - 강제 `send "2\r"` 제거. spawn → 4s sleep → 바로 `/status` 입력.
  - `log_user 1` 처음부터 켜서 전체 raw 캡처.
  - 화살표 3번 후 `w` / `d` / `w` redraw 트리거.
- **`parseUsageOutput`**: "Current session" 헤더 매칭이 strict `==` 였는데 ANSI
  cursor positioning strip 후 같은 라인에 다른 단어가 합쳐져 fail. substring
  `contains("current session")` 으로 너그럽게. sonnet-only 변종은 명시 skip.
  lookahead 시작 위치를 `i+1` → `i` 로 (헤더 줄 자체에 percent 가 합쳐진 경우 대응).
- **`stripAnsiAndBoxChars`**: 이전엔 ANSI escape 를 `""` 로 치환 → 단어 사이
  cursor positioning 위치의 단어들이 "Currentsession" 으로 붙음. `" "` 로 치환
  + 연속 공백 통합 (`[ \t]+` → 1칸) 으로 "Current session" 형태 보존.

### Added

- **사용자 요청 가드**: 클로드코드 로그인 안 된 상태에선 TUI capture skip.
  `runStatusCommand` 가 먼저 init frame 호출 → `apiKeySource` / `model` 둘 다
  null 이면 미인증 추정 → 1분+ 걸리는 expect 호출 자체 건너뜀.
  `rawSnapshots` 에 "usage capture skipped (Claude not logged in)" 명시.

### Verified

```
$ curl -sS https://<host>/api/server/quota
{
  "model": "claude-opus-4-7[1m]",
  "plan": "Subscription (Pro/Max)",
  "sessionUsagePercent": 18, "sessionResetAt": "Resets 10:20pm (Asia/Seoul)",
  "weeklyUsagePercent": 2,   "weeklyResetAt": "Resets Jun 2, 6pm (Asia/Seoul)",
  ...
}
```
사이드바 pill / Android StatusPanel 의 두 bar 정상 표시.

### Verified

`/api/server/quota` 가 정상 quota 반환 확인 (사용자 환경):
```
Current session 18% used, Resets 10:20pm (Asia/Seoul)
Current week (all models) 2% used, Resets Jun 2, 6pm (Asia/Seoul)
```
→ `sessionUsagePercent=18`, `weeklyUsagePercent=2`, 사이드바 pill / Android
StatusPanel 의 두 bar 정상 표시.

## [1.5.0] - 2026-05-26 — Android 키스토어 관리 (`/settings/keystores`)

요구: 패키지명 기반 자동 키스토어 생성. release / debug / properties / AdMob
4-file set. server.yml 의 default DN 값으로 form prefill.

### Added

- **`docker/compose.yml`** `dev-tools/keystores:/home/vibe/keystores` 볼륨.
  영속 유지 (키스토어 분실 = Play Store 업로드 영구 차단).
- **`entrypoint.sh`**: `/home/vibe/keystores` chown vibe:vibe.
- **`server.yml` `keystore.defaults`** + `KeystoreSection` config — 운영자
  본인 정보 (CN/OU/O/L/ST/C + password + validityYears) 한 번 입력해두면
  form prefill.
- **`KeystoreService`** (`admin/KeystoreService.kt`) — keytool wrapper.
  - `list()` / `get(pkg)` — 디렉토리 스캔.
  - `create(req)` — 4 파일 set 생성:
    - `<pkg>.keystore` (PKCS12, RSA 4096, validity 25년 기본)
    - `<pkg>-debug.keystore` (동일 정보 + alias suffix)
    - `<pkg>-keystore.properties` (Gradle signing config)
    - `<pkg>-admob.properties` (AdMob IDs, 선택)
  - `delete(pkg)` — set 전체 삭제.
  - 패키지명 정규식 검증 (`com.foo.bar` 표준 Android applicationId).
  - 파일 권한 600/700 strict.
- **`KeystoreRoutes`** (`admin/KeystoreRoutes.kt`) — SSR:
  - `GET /settings/keystores` — 목록 + create form + 사용법 카드.
  - `POST /settings/keystores` — 신규 생성 (CSRF 검증).
  - `POST /settings/keystores/{pkg}/delete` — confirm 후 삭제.
  - flash 메시지 (생성/삭제/오류) URL query 통한 1-회 표시.
- **`SettingsNav`** — `/settings/keystores` 를 settings/build-env 탭에 link.
- **i18n** (en + ko) — `ks.*` 36 키 (title/intro/columns/fields/admob/usage 등).

### 사용자 동작

1. server.yml 에서 한 번 keystore.defaults 의 password / DN 채우기 (운영자 본인 정보).
2. admin UI → 설정 → Keystores → "Create" form.
3. 패키지명만 입력 (`com.siamakerlab.myapp` 형식). 나머지 prefilled.
4. 즉시 4 파일 생성. 호스트의 `${VIBE_DATA_ROOT}/dev-tools/keystores/` 에서 백업 가능.
5. Android 프로젝트의 `build.gradle.kts` 가 `Properties().load(FileInputStream(
   "/home/vibe/keystores/<pkg>-keystore.properties"))` 로 signing 정보 주입.

### 글로벌 CLAUDE.md 동기

`~/.claude/CLAUDE.md` §키스토어 갱신 — server 안 위치 + 파일명 prefix 규약
+ default DN 값들 정리 (로컬 vs 컨테이너 path 분리 명시).

### 호환성

- 기존 호스트 ../keystores/ 운영 그대로 가능. server 측 관리는 추가 옵션.
- 안드 클라이언트 catch-up 별도 (v0.28.0 예정 — 설정 → Keystores entry).

## [1.4.2] - 2026-05-26 — Dashboard "Projects" 메트릭이 `__scratch__` 까지 세던 회귀 수정

### Fixed

- `StatusService.snapshot()` 의 `projectCount` 가 `projectRepo.count()` (raw DB
  row count) 를 사용해 `__scratch__` ghost 프로젝트까지 셈 → Dashboard
  의 "Projects" 메트릭이 실제 사용자 프로젝트 + 1 로 잘못 표시. `ProjectService.list()`
  는 이미 SCRATCH 필터하지만 count path 는 누락. raw row 에 직접 필터 적용.
  - 실제 프로젝트 2개 → 안드 첫 화면 "Projects: 2" (이전: 3)

## [1.4.1] - 2026-05-26 — v1.4.0 PTY capture 회귀 hotfix (빈 prompt + env shebang)

### Fixed

- **init frame** (`runInitFrame`): `claude --print ""` (빈 prompt) 가
  `"Input must be provided either through stdin or as a prompt argument"`
  에러로 거절되던 문제. dummy `"hi"` prompt 사용 + stdout 의 첫
  `system/init` JSON 줄만 추출 후 즉시 process kill — Claude 응답 frame 도착
  전에 종료해 cost / 지연 모두 최소화.
- **expect shebang** (`claude-usage-capture.exp`): `#!/usr/bin/env expect -f` 가
  `env` 의 옵션 처리 한계로 `"env: 'expect -f': No such file or directory"`
  발생. 두 가지 fix:
  - 스크립트의 `log_file -a /dev/stderr` 라인 제거 (`/dev/stderr` 가 seekable
    아니라 append-mode 가 fail 했음).
  - 호출 측에서 `expect` 바이너리 직접 호출 (`/usr/bin/expect -f script.exp`)
    로 shebang 우회. ClaudeStatusService.runUsageCapture 가 expect 존재 확인 후
    직접 spawn.

### Acceptance

- `curl -fsS https://<host>/api/server/quota` 가 model / plan 정상 반환 (init
  frame 성공).
- TUI capture 성공 시 quota 필드 4개 (`sessionUsagePercent` /
  `weeklyUsagePercent` / `sessionResetAt` / `weeklyResetAt`) 채워짐 → 사이드바
  pill + Android StatusPanel bar 표시.

## [1.4.0] - 2026-05-26 — Claude TUI PTY capture + init-frame 메타데이터 (slash command 차단 후속)

### 배경

Claude Code 2.1.x 부터 모든 slash command (`/status`, `/usage` 등) 가
`--print` 모드에서 `"... isn't available in this environment."` 메시지만
반환하고 실제 화면을 출력하지 않음. v1.3.2 의 `parseUsageOutput` 정규식은
영원히 빈 입력만 받아 quota 가 항상 null. CLI path 가 사실상 막힌 상황.

### Wire change

No — 기존 `ClaudeStatusDto` 그대로, 값이 다시 채워질 뿐.

### Added

- **`docker/scripts/claude-usage-capture.exp`** — expect TCL 스크립트.
  PTY 안에서 `claude --dangerously-skip-permissions` interactive spawn →
  `/status` 입력 → 5탭 TUI 의 Settings 탭 첫 화면 → → → 3번 화살표 (`\x1b[C`) 로
  Usage 탭 이동 → `w` 키 nudge 로 redraw → screen capture → `Esc` + `Ctrl-D` 로
  종료. 25s timeout, stderr 만 expect 자체 log, stdout 으로 raw screen.
- **Dockerfile** `expect` 패키지 + script COPY (`/usr/local/bin/claude-usage-capture.exp`).
- **`ClaudeStatusService.runInitFrame()`** — `--print --output-format=stream-json
  --verbose ""` (빈 prompt) 호출 → `system/init` frame 1줄에서 `model` /
  `apiKeySource` (→ Subscription Pro/Max vs API key) / cwd 추출. cheap (0.1s),
  항상 안정.
- **`ClaudeStatusService.runUsageCapture()`** — expect 스크립트 spawn. 미설치
  환경 (dev) 시 빈 문자열 graceful fallback.
- **`ClaudeStatusService.parseInitFrame()`** — `system/init` JSON line 파서.
  `apiKeySource == "none"` → `Subscription (Pro/Max)`, 그 외 → `API key (...)`.

### Changed

- **`ClaudeStatusService.runStatusCommand()`** — `/status` + `/usage` slash 직접
  호출 (v1.3.2 의 잘못된 path) 폐기. 대신 `runInitFrame` + `runUsageCapture`
  병행 호출 후 두 ParsedStatus 합산.
- v1.3.2 의 `runOneSlashCommand` 함수는 더 이상 호출 안 됨 (legacy 보존).
  다음 cycle 에서 dead code 정리.

### Trade-offs

- **fragile**: Claude CLI UI (탭 순서, 키 바인딩, 첫 부팅 prompt 메시지) 가
  바뀌면 즉시 깨짐. 깨졌을 때 `parseUsageOutput` 가 빈 값 반환 → quota null
  → SSR pill / Android bar 가 graceful hidden. 사용자 가시 회귀 X.
- **속도**: 호출당 ~5초 소요 (TUI init + 화살표 + redraw + exit). 60s cache
  로 흡수.
- **이미지 크기**: `expect` + dependencies (`tcl` 등) 추가로 ~7 MB 증가.

### Acceptance

- `/api/server/quota` 가 `model`, `plan` ("Subscription (Pro/Max)") 정상 반환.
- TUI capture 성공 시 `sessionUsagePercent` / `weeklyUsagePercent` /
  `sessionResetAt` / `weeklyResetAt` 모두 채워짐 → SSR 사이드바 pill +
  Android StatusPanel 의 두 bar 정상 표시.
- TUI capture 실패 시 위 4 필드 null → pill graceful hidden, bar fallback to
  legacy usagePercent (어차피 null) → 사용자에게 표시 안 됨 (시각적 변화 없음).

### Migration

`docker compose pull && up -d`. 이미지 크기 ~7 MB 증가 외 사용자 작업 없음.

## [1.3.2] - 2026-05-26 — Claude Code 2.1.x `/usage` 파싱 + `/status` 의 "Login method" 인식 + 사이드바 전역 쿼타 pill

### Fixed

- 안드/콘솔 StatusPanel 의 세션 (5h) / 주간 (7d) 사용량 값이 모두 dash 로만
  표시되던 회귀 수정. Claude Code 2.1.x 가 `/status` TUI 의 Settings 탭만 보내고
  quota 정보는 **Usage 탭에 별도 분리** 되면서 서버가 quota 자체를 못 받던 문제.

### Added

- **전역 쿼타 pill** (admin SSR 사이드바 좌하단, 유저명 위) — 모든 페이지 공통.
  60초 polling 으로 `/api/server/quota` fetch → Session (5h) + Weekly (7d) bar
  + reset 시각 표시. 색상 임계치 ≥95% error / ≥80% amber / 그 외 primary.
  쿼타 정보는 계정 단위라 프로젝트별 X — 어디서나 같은 값.
- **`GET /api/server/quota`** — `ClaudeStatusService.snapshot(SCRATCH_ID)` 결과
  그대로 반환. 60s cache 라 부담 작음. wire 호환 (기존 `ClaudeStatusDto` 그대로).
- **`ApiPath.SERVER_QUOTA`** SSOT.
- **i18n `quota.session` / `quota.weekly` / `quota.resetPrefix`** (en + ko).
- **CSS `.quota-pill`** — 사이드바 좁은 너비에 맞춘 컴팩트 bar.

### Changed

- **`ClaudeStatusService.runStatusCommand()`** — `/status` + `/usage` 두 slash
  command 를 모두 호출하고 결과 결합. `/status` 결과는 model/login-method,
  `/usage` 결과는 session/week 사용량 + reset 시각으로 사용.
- **`parseUsageOutput()` 신규** — `Current session` / `Current week (all models)`
  헤더 lookahead 3 줄 안에서 `\d+% used` + `Resets <time>` 패턴 매칭.
  `(Sonnet only)` 변종은 skip (별도 fields 없음).
- **ANSI escape + box-drawing 문자 stripping** (`stripAnsiAndBoxChars`) —
  Claude Code 2.1.x 가 `--print /<slash>` 호출에도 TUI screen 을 그대로 stdout
  으로 보내는 경우 대응. `─`/`█`/`▌`/ANSI `[…m` 등 noise 제거 후 parsing.
- **`parseOutput` plan 인식 확장** — 기존 "Plan:" 외 "Login method:" / "Subscription:"
  도 매칭. Claude Max account / Anthropic API key 등 plan 라벨 정상 노출.
- **`ParsedStatus.merge()`** — 두 출력의 non-null 우선 결합.

### Acceptance (사용자 환경)

- `/status` raw: model/login_method 라인 인식.
- `/usage` raw 의 "Current session ... 15% used / Resets 10:20pm" → sessionUsagePercent=15,
  sessionResetAt="Resets 10:20pm (Asia/Seoul)".
- "Current week (all models) ... 1% used / Resets Jun 2, 6pm" → weeklyUsagePercent=1,
  weeklyResetAt="Resets Jun 2, 6pm (Asia/Seoul)".
- 안드 StatusPanel 의 Session (5h) / Weekly (7d) 두 bar 가 정상 % + 리셋 시각 표시.

### 호환성

- 서버 wire 변경 없음 — 기존 `ClaudeStatusDto` 필드 그대로, 단지 값이 잘 채워짐.
- 안드 v0.11.0+ 모두 자동 혜택 (재빌드 불필요).
- `/usage` 호출 추가로 status snapshot 1회당 spawn 2개 → 60s cache 라 부담 작음.

## [1.3.1] - 2026-05-26 — entrypoint SSH 키 자동 생성 회귀 핫픽스

### Fixed

- entrypoint 의 SSH 키 자동 생성이 openssh-client 누락으로 실패해 컨테이너가
  무한 재시작되던 회귀 수정 (v1.2.0 도입분). `siamakerlab/vibe-coder-server:1.2.0`
  / `:1.3.0` / `:latest` 사용자는 `docker compose down && pull && up -d` 로 회복.

### Changed

- **`docker/Dockerfile`** — apt-get install 단계에 `openssh-client` 추가
  (`openssl` 직전, 알파벳 순). 단독 RUN 레이어 추가 없음. `openssh-server` 는
  키 생성에 불필요 → 포함 X.
- **`docker/entrypoint.sh`** — SSH 키 생성 블록 graceful degrade:
  - `command -v ssh-keygen` 가드 — 누락 시 warn 후 부팅 진행.
  - `ssh-keygen` 호출 자체를 `if ! ... then warn ... fi` 로 감싸 실행 실패 시에도
    부팅 진행. `set -e` 환경에서도 안전.
  - SSH 키 자동 발급은 *보조* 기능이지 서버 기동의 전제가 아님을 코드로 명시.

### Acceptance criteria 검증

- 신규 이미지 빌드 후 컨테이너 healthy 진입 확인.
- 기존 볼륨에 키 있을 때 덮어쓰지 않음 (`[[ ! -f "$SSH_KEY" ]]` 가드 유지).
- openssh-client 없는 환경에서도 서버 정상 부팅 (graceful degrade 검증).

## [1.3.0] - 2026-05-26 — Project busy 실시간 WS push (`/ws/projects` 신규 cross-project topic)

배경: v1.1.0 의 `ProjectDto.busy` 필드는 REST `/api/projects` polling 으로만 갱신 →
Android workspaces 목록이 5초 ON_RESUME 한계 (콘솔에서 작업 시작 후 즉시 다른 탭
으로 가면 stale). 본 cycle 에서 push-based 실시간 동기 도입.

### Wire change

Yes (additive). 신규 `WsFrame.ProjectBusyChanged(projectId, busy, seq)` + 신규 WS
endpoint `/ws/projects`. 기존 frame / endpoint 변경 없음.

### Added

- **`shared/ws/WsFrame.kt` `ProjectBusyChanged`** — cross-project busy 전환 frame.
  `@SerialName("project_busy_changed")`. `ConsoleBusyState` 와 의도적으로 분리
  (per-project topic 은 콘솔 자기 자신, cross-project topic 은 list view 용).
- **`/ws/projects` 신규 endpoint** (`WsRoutes.kt`) — 단방향 stream. 클라이언트는
  `WsFrame.Auth(token)` 만 보내면 됨. `?since=<seq>` 로 재연결 시 누락 slice replay.
  - replay slice → live flow 순서로 흘림 (기존 `subscribeConsole` 메커니즘 재사용).
  - 메시지 형식: `ProjectBusyChanged(projectId, busy, seq)` 만.
- **`ClaudeSessionManager.PROJECTS_TOPIC = "__projects__"`** — 전역 broadcast 토픽.

### Changed

- **`ClaudeSessionManager.setBusy(projectId, value)`** — 기존 per-project
  `ConsoleBusyState` emit 이후, 추가로 `PROJECTS_TOPIC` 에 `ProjectBusyChanged`
  emit. 두 emit 모두 값 실제 변경 시에만 (idempotent setBusy 호출엔 no-op).
  ring buffer 는 두 topic 각각 독립이라 cross-talk 없음.

### 클라이언트 사용 패턴 (예: Android workspaces 목록)

```kotlin
// 1) 초기 snapshot — 기존 REST.
val projects = api.projects()   // List<ProjectDto> (busy 필드 포함, v1.1.0+)

// 2) WS 구독 — 신규.
wsClient.streamProjects(sinceLastSeq).collect { frame ->
    when (frame) {
        is WsFrame.ProjectBusyChanged -> {
            // state.update { it.copy(projects = it.projects.map {
            //     if (it.id == frame.projectId) it.copy(busy = frame.busy) else it
            // }) }
        }
        is WsFrame.Auth, is WsFrame.Ping -> Unit
        else -> Unit  // forward-compatible
    }
}
```

### 호환성

- 기존 클라이언트: 신규 frame / endpoint 무시 — 동작 동일.
- Android v0.18.0 이하: `/ws/projects` 구독 코드 없음 → 기존 ON_RESUME polling.
- Android v0.19.0 (예정): 본 endpoint catch-up.

## [1.2.0] - 2026-05-26 — SSH 키 자동 발급 + 영속 + 설정 UI 열람 + 재생성

요구: "서버 설치 시, 깃/gitea 등에서 사용 가능한 ssh key 를 자동으로 발급 후
설정메뉴에서 열람 가능하도록 해줘. 볼륨 마운트로 영속성 유지하고, 서버 업데이트
시에는 기존 키 유지함 (키가 이미 있을 경우 덮어쓰기 금지). 그리고 설정에서 키
갱신 (재생성) 가능하도록 기능 구현."

### Wire change

Yes (additive default-value). 신규 `SshKeyDto` (publicKey/algorithm/comment/
fingerprint/createdAt) + 2 endpoints. wire 하위호환.

### Added

- **`docker/entrypoint.sh` — SSH 키 자동 발급** (idempotent):
  컨테이너 첫 부팅 시 `/home/vibe/.ssh/id_ed25519` 없으면 `ssh-keygen -t ed25519`
  실행. comment = `vibe-coder-server@<hostname>-<yyyymmdd>`. 권한 chmod 700/600/644.
  **이미 있으면 절대 덮어쓰지 않음** — 서버 이미지 교체 / 업데이트 시 동일 키 유지.
  매 부팅마다 권한만 idempotent 정정 (볼륨의 잘못된 mode 회복).
- **`docker/compose.yml` — `.ssh` 볼륨 마운트** —
  `${VIBE_DATA_ROOT}/dev-tools/ssh:/home/vibe/.ssh`. 영속.
- **`admin/SshKeyService.kt`** — 키 read (`snapshot()`) + 재생성 (`regenerate()`).
  재생성은 기존 키쌍을 `id_ed25519.bak.<yyyymmdd-HHmmss>` 로 백업한 뒤 신규 생성.
  fingerprint 는 `ssh-keygen -lf <pub>` 의 출력에서 추출.
- **`admin/SshKeyRoutes.kt`**:
  - SSR `GET /settings/ssh-key` — 공개키 textarea + Copy 버튼 + 메타 (알고리즘/
    fingerprint/comment/created) + Regenerate 카드 (confirm) + 사용법 3 단계.
  - SSR `POST /settings/ssh-key/regenerate` — CSRF 검증 후 재생성, 같은 페이지로 redirect.
  - REST `GET /api/server/ssh-key` → `SshKeyDto` (404 if not yet generated).
  - REST `POST /api/server/ssh-key/regenerate` → `SshKeyDto` (재생성 결과).
- **`shared/dto/Dtos.kt` `SshKeyDto`** — additive.
- **`shared/ApiPath.kt`** — `SERVER_SSH_KEY` / `SERVER_SSH_KEY_REGENERATE`.
- **`admin/SettingsNav.kt`** — `/settings/ssh-key` → settings top-level + build-env 탭.
- **i18n keys (en + ko)** — `ssh.*` 26 키 (title/intro/notFound/publicKey/copy/
  meta/algorithm/fingerprint/comment/created/regen/usage 등).

### Coexistence with GitCloneService.ensureSshKeyExists()

기존 `GitCloneService.ensureSshKeyExists()` 는 lazy (user trigger 시점에 생성).
v1.2.0 의 entrypoint 가 eager 로 생성 → 후속 lazy 호출은 no-op (이미 존재) 라
호환. 동일한 `/home/vibe/.ssh/id_ed25519` 파일 사용.

### 사용자 동작

1. `docker compose up` (또는 `docker pull` 후 재기동) — entrypoint 가 키 자동 발급 (첫 부팅만).
2. 웹 admin → 설정 → SSH Key 탭 → 공개키 보임.
3. Copy 버튼으로 클립보드 복사.
4. Gitea / GitHub 의 SSH Keys 메뉴에 paste.
5. 이후 vibe-coder 가 `ssh://git@host:port/owner/repo.git` 형태로 clone/push 가능.
6. 필요 시 동일 페이지 "Regenerate" 버튼 — 기존 키 .bak 백업 후 새 키 생성
   (Git 호스트에 새 공개키 재등록 필수).

### 호환성

- 기존 사용자: docker pull v1.2.0 후 재기동 시 entrypoint 가 키 발급. 기존
  `.ssh` 볼륨 마운트가 없었던 경우 호스트의 `vibe-coder-data/dev-tools/ssh`
  디렉토리가 새로 생기고 그 안에 키 생성.
- 마이그레이션: `docker compose down && docker compose pull && docker compose up -d`
  표준 절차. compose.yml 의 새 볼륨 라인 적용을 위해 .env / compose.yml 의
  최신 버전 사용.
- 외부 사용자가 `.ssh` 볼륨 없이 사용 중이었으면 (희박) 컨테이너 내부에만
  키 존재 → 재기동 시 휘발. compose.yml 업데이트 권장.

## [1.1.0] - 2026-05-26 — ProjectDto.busy 필드 추가 (Android workspace list busy 뱃지 지원)

요구: Android workspaces 목록 우측의 "빌드상태" 자리에 현재 Claude 응답중/대기중
뱃지를 표시하고 싶다. 안드로이드 단독으론 per-project busy 정보를 알 수 없음 — 서버
가 `ClaudeSessionManager.isBusy(projectId)` 를 ProjectDto 에 노출해야 함.

### Wire change

Yes (additive default-value). `ProjectDto.busy: Boolean = false` 신규 필드.
기존 클라이언트는 무시 (default false), Android v0.16.0+ 가 활용.

### Added

- **`ProjectDto.busy: Boolean = false`** (`shared/dto/Dtos.kt`) — 현재 프로젝트의
  Claude session 응답중 여부. additive default-value 라 wire 하위호환.
- **`ProjectService(isBusyOf: ((String) -> Boolean)? = null)`** — lambda 콜백 주입.
  Construction-order 의존성 회피 (sessionManager 가 ProjectService 보다 늦게
  만들어져도 OK). null 이면 항상 false (테스트).
- **ServerMain construction order swap** — `sessionManager` 를 `projects` 보다 먼저
  생성. `projects = ProjectService(..., isBusyOf = sessionManager::isBusy)` 로 묶음.

### Changed

- `ProjectService.list()` / `get(id)` 가 `isBusyOf?.invoke(id) ?: false` 결과를
  `toDto(busy = ...)` 로 전달. ACL-aware `listForUser` 는 `list()` 위에 있어
  자동 반영.
- `private fun ProjectRow.toDto(...)` 에 `busy: Boolean = false` 파라미터 추가.
  기존 호출처 4곳 중 list/get 만 busy 전달, register/cloneFrom 등 신규 row
  생성은 default false 그대로 (방금 만든 프로젝트가 busy 일 리 없음).

### 호환성

- vibe-coder-android v0.15.x 이하: `busy` 필드 무시 — 동작 동일.
- vibe-coder-android v0.16.0+: workspaces 목록에서 busy 뱃지 표시 가능.
- 서버 자체 SSR UI 는 본 cycle 에서 변경 없음 (필요 시 후속 cycle).

## [1.0.1] - 2026-05-26 — 사용량 모니터링 enhance (세션 / 주간 분리)

### Fixed

사용자 점검: Claude `/status` 의 5시간 세션 quota 와 7일 주간 quota 를 분리해
표시 못 함. `parseOutput()` 이 `quota|remaining|usage` 키워드 첫 매치 줄만
보존 → Pro/Max plan 의 두 개 quota 중 한 쪽 잃음.

### Added

- **`ClaudeStatusDto.sessionUsagePercent`** + **`weeklyUsagePercent`** +
  **`sessionResetAt`** + **`weeklyResetAt`** — 신규 4 필드. 기존
  `usagePercent` / `resetAt` 은 두 값의 max / fallback 로 채워 backward
  compatible.
- **`parseOutput()` 분리 휴리스틱**:
  - 같은 줄에 `weekly | week` → `weeklyUsagePercent` / `weeklyResetAt`
  - 같은 줄에 `session | 5-hour | 5 hour` → `sessionUsagePercent` /
    `sessionResetAt`
  - 키워드 없으면 legacy `usagePercent` (기존 동작 유지)
- **Dashboard 카드 두 % 표시** — 세션 (5시간) bar + 주간 (7일) bar 분리
  렌더. 각 bar 옆 reset 시각 inline. legacy 한 줄만 있을 땐 단일 bar fallback.
- **i18n** (en + ko): `dashboard.usage.{session, weekly, sessionReset,
  weeklyReset}`.

### Notes

- 파싱 휴리스틱은 best-effort — `claude /status` 출력 포맷 변경 시 회귀
  가능. raw 출력은 `/usage` 페이지에서 그대로 확인 (변경 없음).
- `ClaudeUsageMonitor` 의 80%/95% threshold 트리거는 그대로 legacy
  `usagePercent` (max) 사용. 향후 세션/주간 각각 threshold 설정 분리 가능.
- `:server:installDist` 통과.

## [1.0.0] - 2026-05-26 — 🎉 Pending prompts 큐 + 1.0 milestone

사용자 요청: busy 중에도 prompt 입력/제출 가능하게 + console_done 후 자동 발사.
"한 turn 끝날 때까지 기다렸다 보내야 했던" 불편 해소. 1.0 milestone (97개
릴리스 + Phase 64-67 i18n 완주 + Android cross-repo 안정화 누적).

### Added

- **Pending prompts 큐 (client-side)** — busy 중 submit → 즉시 발사 대신
  `pendingPrompts: string[]` 에 push. console 에는 "대기열 추가 (#N): preview"
  system 메시지로 가시화. input 즉시 비워 다음 prompt 작성 가능.
- **자동 큐 drain** — `setInFlight(false)` 전이 (server 측 ConsoleDone /
  busy_state(false) 도착 시 트리거) → 큐에서 1건 shift → 150ms delay 후
  자동 sendPrompt. "다음 prompt 자동 발사" system 메시지 출력.
- **Busy badge 큐 카운트** — `● 응답중 (대기 3)` 형식. 큐 비면 일반
  `● 응답중`. drain 시점에 즉시 갱신.
- **`window.vibeClearQueue()`** — 콘솔에서 호출하면 큐 전부 비움 +
  "대기열 초기화 (N개 제거)" 메시지. UI 버튼은 차후 옵션.
- **i18n**: `console.busy.responding.queued` (parametric %d) +
  `console.queue.{added(%d,%s), draining, cleared(%d), clear}` 5 keys (en+ko).

### Notes

- 큐는 client-side localStorage 영속 X — 페이지 새로고침 시 사라짐 (의도:
  fire-and-forget 큐, 사용자가 잊은 prompt 자동 발사하면 위험).
- `cancelTurn` 시점에 큐는 유지 (사용자가 현재 turn 만 중단, 다음 발사
  의도는 유지). `startNew` 는 페이지 reload 이므로 큐도 자연 클리어.
- 다중 prompt 가 한 세션에 빠르게 쌓이면 Claude session window 가 가득 차서
  context overflow 가능 — 사용자가 인지하고 사용하는 advanced UX. 안전장치
  필요 시 server 측 promptHistory 의 turn count threshold 같이 추가 가능
  (현재는 사용자 자율).
- Server v0.98.0 의 `ConsoleBusyState` frame 이 큐 drain trigger 의 핵심.
  다중 디바이스 시나리오: A 폰에서 prompt → B 폰에서 큐 push → console_done
  도착 시 두 디바이스 모두 동일 큐 동기 (큐 자체는 device-local 이지만
  drain trigger 는 server 동기 — 즉 A 가 보낸 큐는 A 만 drain).

### Why 1.0

- v0.4.0 (서버/안드로이드 리포 분리) 이후 95개 minor (~4 개월) 거쳐 1.0
  milestone. 본 cycle 의 "큐드 prompt" 가 mature mobile-grade UX 의 마지막
  관문 (이전엔 한 turn 끝날 때까지 사용자가 기다려야 했음).
- 누적 안정성: Phase 64-67 i18n (전체 SSR + API + ApiException 100%),
  cross-repo (vibe-coder-android v0.10.0 와 Bearer 인증 + Accept-Language +
  busy state sync), Docker amd64 daily push, 156 ApiException 100% migration.
- 외부 노출 OSS 로서 GitHub README + Wiki + Docker Hub 모두 latest 동기.

### Wire change

- 없음. 본 cycle 은 순수 web client JS 변경. server WS frame / DTO 변경 없음.

### Verification

- `:server:installDist` 통과.

## [0.99.0] - 2026-05-26 — 프로젝트 생성 시 입력 정보 → CLAUDE.md 자동 주입

### Fixed

사용자 보고: 프로젝트 등록 폼에서 입력한 `appName` / `packageName` 이 실제로
프로젝트의 `CLAUDE.md` 에 반영 안 되어, Claude 가 build.gradle.kts 생성 시
임의 패키지를 사용 → 콘솔에서 "패키지명을 ... 로 바꿔달라" 고 재지시해야
하는 회귀.

### Changed

- **`ClaudeMdTemplate.render(info: ProjectInfo?)`** — 기존 `const val CONTENT`
  가 generic 만 반환하던 것을, projectInfo 받으면 `## Project Info` 섹션을
  최상단에 prepend.
  - 헤더: `# CLAUDE.md — {appName}`
  - 항목: App name / Project ID / Android package (applicationId) / Default
    Gradle module / Debug build task / Source (empty | clone URL@branch)
  - guidance 문: "값을 그대로 사용해 `android { namespace = "..." }`
    + `defaultConfig { applicationId = "..." }` 설정. 임의 변경 금지."
- **`ProjectService.register()`** — `Files.writeString(claudeMd, CONTENT)`
  → `Files.writeString(claudeMd, render(ProjectInfo(appName, packageName,
  projectId, moduleName, debugTask, sourceType, cloneUrl, cloneBranch)))`.

### Notes

- 기존 프로젝트 (이미 CLAUDE.md 존재) 는 영향 없음 — register 만 새 파일
  씀, 이후엔 `ProjectScaffolder.ensureClaudeFiles()` 의 `notExists()` 가드.
- backfill (`ProjectScaffolder` 호출) 은 그대로 `CONTENT` 사용 — 호출자
  (ClaudeSessionManager) 가 projectInfo 모름. v0.99.0 이후 생성된 프로젝트만
  자동 주입 받음. 기존 프로젝트는 사용자가 직접 CLAUDE.md 편집 (Project 파일
  편집기 / 콘솔).
- scratch ghost project (`__scratch__`) 는 `appName="General Chat"` /
  `packageName="scratch"` 인데, 기존 generic 템플릿 그대로 사용 — 변경 없음.
- `:server:installDist` 통과.

## [0.98.0] - 2026-05-26 — 콘솔 응답중/대기중 배지 + server-side busy state (cross-client sync)

사용자 요청: 콘솔 prompt 보낸 후 Claude 작업 완료 여부 불명확. 응답중/대기중
배지 시각화 + Android 도 알 수 있도록 server-side 상태 노출. 프로젝트별
독립 상태 (다중 프로젝트 동시 진행 지원).

### Added

- **Web busy badge** — session header 에 `● 응답중` (녹색 pulse 애니메이션) /
  `○ 대기중` (회색) 배지. inline `<style>` 의 `@keyframes vibe-busy-pulse`
  + `data-state` 속성 토글.
- **`ClaudeSessionManager.busy` (per-project Map)** — projectId 별 독립 busy
  상태. 여러 프로젝트 동시 작업 시 각자의 콘솔 상태 분리.
- **`ClaudeSessionManager.isBusy(projectId)` + `setBusy()`** — setBusy 가
  값 변경 시점에만 `ConsoleBusyState` WS frame emit (idempotent 노이즈 방지).
- **`WsFrame.ConsoleBusyState(busy, seq)`** — busy 전환 알림 frame.
  shared 모듈에 정의. Android / 다중 web 탭 / 다중 디바이스 모두 sync.
- **`ClaudeStatusDto.busy: Boolean`** — REST 폴링 / 첫 진입 클라이언트가 즉시
  현재 상태 확인. cache hit 시에도 busy/processAlive/sessionId 는 fresh
  (60s cache 는 model/plan/quota 같은 안정 필드만).
- **i18n**: `console.busy.responding` / `console.busy.idle` (en + ko).

### Changed

- **`sendPrompt()`** — stdin 쓰기 성공 시 `setBusy(true)`.
- **`handleStdoutLine()`** — `ClaudeEvent.Done` 시 `setBusy(false)`.
- **`terminateSession()`** — cancel/startNew/idle reap 시 `setBusy(false)`.
- **`onProcessExit()`** — process crash 시 `scope.launch { setBusy(false) }`.
- **`renderFrame()` web** — `console_busy_state` 케이스 추가, server emit 받아
  `setInFlight(!!f.busy)`. 로컬 inFlight 와 server 가 항상 같은 값으로 수렴
  (다중 탭/디바이스 일관성).

### Wire change

- **Yes (additive, backward compatible).** 신규 `ConsoleBusyState` frame +
  `ClaudeStatusDto.busy` 필드. v0.97.0 이하 클라이언트는 새 frame 을 unknown
  으로 무시 → 기존 동작 (자체 inFlight 로직) 그대로.
- vibe-coder-android v0.10.0 권고 — `console_busy_state` 구독 + UI 배지 추가.

### Notes

- 프로젝트별 독립 상태 — 사용자가 명시 요청. `busy: ConcurrentHashMap` +
  `hub.emitConsole(topic(projectId))` 모두 projectId 키 기반 분리.
- web client 의 `setInFlight()` 가 양방향 동작: (1) 사용자 자체 submit 시
  로컬 즉시 true, (2) server frame 받아 sync. ClaudeEvent.Done 도착 시
  setInFlight(false) + server frame 도 setInFlight(false) — idempotent 안전.
- `:server:installDist` 통과.

## [0.97.0] - 2026-05-26 — 콘솔 메시지 필터링 UI (엑셀 스타일)

### Added — Console message filter (`consolePage`)

사용자 요청: 콘솔의 JSON 메시지 종류가 너무 많아 노이즈가 큼. 엑셀 필터처럼
원하는 카테고리만 보고 싶다 + 반드시 봐야 하는 메시지는 disabled checkbox.

**Mandatory (체크 disabled, 항상 표시) — 3 카테고리:**
- `assistant` — Claude 응답 + 사용자 자신의 prompt echo
- `error` — 모든 에러 (parse / send / WS / tool error)
- `system` — 시스템 이벤트 (turn_cancelled / process_crashed / idle_terminated / cancel ack)

**Optional (토글 on/off) — 6 카테고리:**
- `tool_use` — 도구 호출 (Read / Write / Bash 등 11 가지)
- `tool_result` — 도구 결과 (500자 truncated)
- `session` — 세션 시작 / 새 세션
- `done` — 턴 종료 (end_turn)
- `replay` — replay history begin/end 마커
- `ws` — WebSocket 연결 (connected / closed / error)

**구현 디테일:**
- `<details>` collapsible UI, `🔍` 아이콘 + 숨김 카운트 요약 (`— 3 hidden`).
- localStorage 영속: `vibe-console-filter-{projectId}` JSON `{cat: false}`
  (default true 는 키 비움 → minimal storage). 프로젝트별로 다른 필터 적용.
- `append(cls, label, body, cat)` 시그니처 확장. row 에 `data-filter-cat`
  부착, 필터 적용 시 `display:none` 토글. 새 row 도 즉시 필터 반영.
- "Show all" 버튼: 한 번에 전체 reset.
- 페이지 재방문 / 새로고침 후에도 사용자 선택 유지.

### Changed

- `WebProjectTemplates.consolePage()` JS — `append()` 시그니처에 `cat` 매개변수.
  9 callsite (renderFrame 의 9 type + ws/cancel/send 흐름의 6 사이트) 모두
  적절한 카테고리 전달.
- `MessagesEn` / `MessagesKo` — `console.filter.*` 15 keys 추가
  (title / hint / mandatory / optional / 9 cat 라벨 / reset).

### Notes

- mandatory 분류 기준: "이 메시지를 안 보면 대화 맥락이 깨짐 / 행동 결정 못함".
  assistant (대화), error (실패 신호), system (cancel/crash 알림) 이 해당.
- optional 분류 기준: "디버그성 / 반복적 / 도구 verbose 출력". 사용자가 처음엔
  켜놓고 보다가 워크플로 익숙해지면 끄는 패턴.
- 필터는 client-side 만 — 서버 WS frame emission 자체는 변경 없음 (replay /
  history endpoint 도 그대로 전체 send). localStorage 도 client-only.
- `:server:installDist` 통과.

## [0.96.0] - 2026-05-26 — Phase 67 closure (deprecated guard + metrics) + session idle timeout default 0

### Fixed

- **`security.sessionIdleTimeoutMinutes` default 30 → 0 (unlimited)** —
  android client (Bearer 토큰) 가 백그라운드에 들어갔다 돌아올 때 매번 재로그인
  요구되던 회귀 해소. LAN 단일 사용자 도구 특성상 idle 로그아웃은 UX 비용만
  크고 보안 이득 낮음. 외부 노출 환경에서는 server.yml 에서 30~60 같은 양수로
  override 권장. SSR cookie + JSON API Bearer 양쪽 모두 적용 (`AdminRoutes`
  `requireSessionOrRedirect` + `AuthPlugin` `idleTimeoutMinutesProvider`).

### Added

- **`vibe_api_errors_total{code}`** — Prometheus counter for ApiException
  분포. `/metrics` 에 노출. cardinality 안전 (`code` 는 짧은 enum-like 식별자:
  `unauthorized`, `bad_request`, `last_admin` 등).
- **`installStatusPages(serverDefaultLanguage, metrics?)`** — metrics
  매개변수 추가. Module.kt 에서 `ctx.metrics` 전달.

### Changed

- **`ApiException(statusCode, code, message, detail)` legacy 4-arg constructor**
  가 `@Deprecated(level = WARNING)` 표시 — Phase 67 (v0.92-v0.95) 의 156-site
  full migration 후 신규 throw 사이트가 실수로 legacy ctor 를 쓰면 컴파일
  경고로 즉시 가시화. ReplaceWith hint 로 `ApiException.localized(...)` 권장.
- 기존 모든 throw 사이트는 이미 v0.95.0 에서 `.localized()` 로 마이그됐으므로
  deprecation 경고 신규 발생 0건 (verified — `:server:installDist` 통과 +
  no new w: deprecated 경고).

### Notes

- 운영자가 idle timeout 을 켜고 싶다면 `server.yml` 에서
  `security.sessionIdleTimeoutMinutes: 30` 직접 설정.
- /metrics scrape 시 `# HELP vibe_api_errors_total Total ApiException by code`
  + 각 code 별 `vibe_api_errors_total{code="..."} N` 라인. Prometheus / Grafana
  에서 error rate dashboard 만들기 좋음.
- `:server:installDist` 통과.

## [0.95.0] - 2026-05-26 — Phase 67 Wave 4 FINAL: ApiException i18n 100% 완료 🎉

156 → -11 (v0.92) → -33 (v0.93) → -57 (v0.94) → **-42 (v0.95) = 0 잔여**.
**ApiException i18n migration 4-cycle 완주.** 모든 throw 사이트가 messageKey
기반 lazy localization. v0.9.0 android client 의 Accept-Language 헤더가
서버 전체 에러 응답에 사용자 언어 적용.

### Added

- **`MessagesEn` / `MessagesKo`** — `api.*` 도메인 ~30 keys 추가:
  - `api.pathSafety.{controlByte, absoluteNotAllowed, escapeWorkspace(%s),
    notUnderWorkspace(%s), notExist(%s)}` (5)
  - `api.envSetup.{unknownComponent(%s), multipartParse(%s), emptyFile,
    manualInstallOnly(%s)}` (4)
  - `api.projectAction.{bodyRequired, notFound(%s), dispatchFailed(%s)}` (3)
  - `api.gitWriter.{notARepo(%s), commitFailed(%s), spawnFail(%s),
    timeout(%s)}` (4)
  - `api.gitCredential.{emptyHost, shortToken(%d)}` (2)
  - `api.keystore.{bodyRequired, timeout, failed(%d,%s)}` (3)
  - `api.serverAction.{notAllowed(%s), notImplemented(%s), slashNotSupported}` (3)
  - `api.common.{projectIdRequired, projectNotFound(%s), artifactNotFound(%s)}` (3)

### Changed — migration (~42 throw sites, final cleanup)

- **`PathSafety`** — 5 (controlByte/absoluteNotAllowed/escapeWorkspace/
  notUnderWorkspace/notExist).
- **`EnvSetupApiRoutes`** — 5 (unknownComponent + multipartParse x2 + emptyFile x2).
- **`ProjectActionRoutes`** — 5 (bodyRequired x2 + paramsTooLarge + notFound +
  dispatchFailed). replace_all 로 projectIdRequired 통합.
- **`GitWriter`** — 4 (notARepo/commitFailed/spawnFail/timeout).
- **`ProjectRoutes`** — 3 (projectIdRequired x2 + projectForbidden).
  replace_all 로 통합.
- **`KeystoreGenerator`** — 3 (bodyRequired/timeout/failed).
- **`ServerActionHandler`** — 3 (notAllowed/notImplemented/slashNotSupported).
- **`JsonProjectZipRoutes`** — 2 (projectIdRequired/projectNotFound).
- **`GitCredentialStore`** — 2 (emptyHost/shortToken).
- **`JsonHistoryRoutes`** — 2 (projectNotFound/emptyFile).
- **`BuildRoutes`** — 2 (replace_all 로 projectIdRequired 통합).
- **`ArtifactRoutes`** — 2 (replace_all 로 artifactNotFound 통합).
- **`McpRoutes`** — 2 (multipartParse/emptyFile).
- **`EnvSetupService`** — 1 (manualInstallOnly).
- **`EnvSetupRoutes`** — 1 (unknownComponent).

### Notes

- **`grep -rn 'throw ApiException(' server/src/main/kotlin` 결과 0건.**
  완벽한 i18n coverage.
- 4 cycle 총 통계 (v0.92.0 - v0.95.0):
  - 156 throw 사이트 → 100% migration
  - ~250+ `api.*` i18n keys (en + ko)
  - 15+ 파일 영향
  - Wire change 없음 (응답 shape 동일, message 텍스트만 사용자 언어로)
- 잔여 `ApiException(...)` legacy constructor 호출은 0건. 이후 신규 throw 사이트는
  반드시 `ApiException.localized(...)` factory 사용.
- `:server:installDist` 통과.

### Phase 67 시리즈 회고 (v0.92.0 → v0.95.0, 4 cycle)

Phase 64 (SSR + flash) + Phase 66 (Accept-Language) 와 더불어 server 측
i18n 의 마지막 piece. 이제:
- SSR 페이지 (Phase 64): 사용자 언어
- HTTP 요청 헤더 우선순위 (Phase 66): Accept-Language → user.language → server default
- JSON API ApiException 응답 (Phase 67): 위 우선순위로 사용자 언어로 재렌더

cross-repo 완성도: server (en/ko 양쪽 + i18n key 1300+) + android v0.9.0
(LocaleManager + Accept-Language interceptor) = **사용자가 어떤 path 로 server
와 상호작용해도 device locale 로 응답 수신**.

## [0.94.0] - 2026-05-26 — Phase 67 Wave 3: ProjectFileBrowser + GitCloneService + McpService + PromptTemplateStore + ConsoleRoutes migration

5개 핵심 user-facing 서비스 layer 의 ~55 throw 사이트 일괄 i18n migration.

### Added

- **`MessagesEn` / `MessagesKo`** — `api.*` 도메인 ~50 keys 추가:
  - `api.fileBrowser.*` (13) — projectRootNotFound, pathNotFound(%s),
    notADirectory(%s), emptyPath, fileNotFound(%s), symlinkBlockedView,
    notAFile, fileTooLarge(%d,%d), binaryFile, contentTooLarge(%d),
    symlinkBlockedEdit, badPath, parentMissing(%s).
  - `api.gitClone.*` (11) — sshDir/sshKeygen/sshKeygenTimeout/sshKeygenFail/
    targetNotEmpty(%s)/spawnFail/timeout/cloneFailed/emptyUrl/badUrlScheme/
    unsafeUrl.
  - `api.mcp.*` (10) — noSelection/unknownMcp(%s)/comingSoon(%s)/
    missingConfig(%s)/unknownField(%s,%s)/notFileField(%s)/empty/
    tooLarge(%d,%d)/secretsDirIo/fileWriteIo.
  - `api.prompt.*` (6) — emptyTitle/emptyBody/titleTooLong(%d)/
    bodyTooLong(%d)/limitReached(%d)/notFound(%s).
  - `api.console.*` (6) — projectIdRequired/claudeCliMissing/
    claudeAuthRequired/textRequired/promptTooLarge(%d,%d)/sendFailed(%s).
  - `api.pathSafety.*` (2) — escapeAttempt/outsideWorkspace.

### Changed — migration (~55 throw sites)

- **`ProjectFileBrowser`** — 15 사이트 (file tree + read + write 모든 validation).
- **`GitCloneService`** — 11 사이트 (SSH keygen + clone process + URL validation).
- **`McpService`** — 11 사이트 (catalog selection + config validation + file
  upload).
- **`PromptTemplateStore`** — 10 사이트 (create + update + body/title length).
  replace_all 로 emptyTitle / emptyBody / titleTooLong / bodyTooLong 중복 통합.
- **`ConsoleRoutes`** — 10 사이트 (replace_all 로 projectIdRequired 5건 통합 +
  claudeCliMissing / claudeAuthRequired / textRequired / promptTooLarge /
  sendFailed).

### Notes

- 시작 156 → v0.92.0 -11 → v0.93.0 -33 → v0.94.0 -57 = **잔여 ~55** (낮은
  사용자 가시도 영역: PathSafety, EnvSetupApiRoutes, GitWriter, KeystoreGenerator,
  작은 routes 등).
- v0.9.0 android client 가 자동으로 사용자 언어로 응답 수신.
- Wire change 없음 (응답 shape 동일).
- `:server:installDist` 통과.

## [0.93.0] - 2026-05-26 — Phase 67 Wave 2: AuthRoutes + JsonAdminRoutes + ProjectService + BuildService + Claude services migration

v0.92.0 의 ApiException.localized() 인프라 위에 사용자 가시한 서비스 layer
~33 throw 사이트 일괄 migration.

### Added

- **`MessagesEn` / `MessagesKo`** — `api.*` 도메인 ~43 keys 추가:
  - `api.auth.{noUserLink, pairingDeprecated, pairBadRequest, invalidPairing}` (4)
  - `api.admin.{usernameRequired, duplicateUsername, roleInvalid, userNotFound,
    lastAdminRole, lastAdminDelete, cronRequired, scheduleNotFound,
    autoBackupNotFound, envFileNotAllowed(%s), newVersionRequired}` (11)
  - `api.project.{alreadyRegistered(%s), missingCloneUrl, cloneUnavailable,
    targetNotEmpty, notFound(%s), scratchProtected}` (6)
  - `api.build.{gradleExit(%d), apkNotFound, notFound(%s)}` (3)
  - `api.claudeAuth.{empty, tooLarge(%d), encoding(%s), jsonShape, jsonParse(%s),
    missingOauth, missingExpires, expired(%d), io(%s), dirIo(%s,%s), apiKeyEmpty,
    apiKeyPrefix, apiKeyLength(%d)}` (13)
  - `api.claudeLogin.{inProgress(%s), spawnFailed(%s), noSession, wrongState(%s),
    codeEmpty, codeIo(%s)}` (6)

### Changed — migration (~33 throw sites)

- **`AuthRoutes`** — 4 사이트 (noUserLink / pairingDeprecated / pairBadRequest /
  invalidPairing).
- **`JsonAdminRoutes`** — 12 사이트 (usernameRequired / duplicateUsername /
  roleInvalid / userNotFound x2 / lastAdminRole / lastAdminDelete /
  cronRequired / scheduleNotFound x2 / autoBackupNotFound x2 /
  envFileNotAllowed / newVersionRequired). replace_all 로 중복 통합.
- **`ProjectService`** — 7 사이트 (alreadyRegistered / missingCloneUrl /
  cloneUnavailable / targetNotEmpty / notFound x3 / scratchProtected).
  replace_all 로 project_not_found 통합.
- **`BuildService`** — 4 사이트 (gradleExit / apkNotFound / build notFound x2).
  replace_all 로 통합.
- **`ClaudeAuthService`** — 12 사이트 (empty + tooLarge + encoding + jsonShape +
  jsonParse + missingOauth + missingExpires + expired + io + dirIo + apiKeyEmpty +
  apiKeyPrefix + apiKeyLength).
- **`ClaudeLoginService`** — 6 사이트 (inProgress / spawnFailed / noSession +
  wrongState + codeEmpty + codeIo).

### Notes

- 이제 ~110 throw 사이트 잔여 (시작 156 → v0.92.0 11 처리 → v0.93.0 +33 →
  잔여 112). 비교적 user-visible 한 영역은 다 처리. 잔여는 내부 services
  (PathSafety, GitCloneService, PromptTemplateStore, etc.) 와 작은 routes.
- Wire change 없음 (응답 shape 동일, message 텍스트만 i18n).
- v0.9.0 android client 가 Accept-Language 헤더 송신 → 사용자 언어로 응답.
- `:server:installDist` 통과.

## [0.92.0] - 2026-05-26 — Phase 67: ApiException i18n infrastructure + 우선순위 migration

Phase 66 (Accept-Language end-to-end) 가 SSR 흐름의 `sess.language` 까지는
헤더 우선 적용했으나, JSON API 의 `ApiException` 메시지들은 hardcoded
English/Korean 텍스트라 android client 가 응답을 받아도 사용자 언어로
표시 불가. 본 cycle 에서 ApiException 에 i18n key 기반 lazy localization
인프라 추가 + 가장 사용자 가시한 auth/upload 에러 11 사이트 migration.

### Added

- **`ApiException.localized(statusCode, code, messageKey, args, detail)`** —
  i18n-aware factory. 예외의 `message` 필드는 영문 fallback (logs 용),
  StatusPagesPlugin 이 응답 시 사용자 언어로 re-render.
- **`MessagesEn` / `MessagesKo`** — `api.*` 도메인 10 keys:
  - `api.auth.{missingBearer / viewerReadonly / adminOnly / projectForbidden /
    csrfMissing / csrfInvalid}` (6).
  - `api.upload.{extBlocked(%s) / tooLarge(%d) / noFilePart}` (3).
  - `api.file.notFound(%s)` (1).

### Changed

- **`ApiException`** — `private constructor` + `companion object` 으로 리팩토링.
  legacy 4-arg 생성자 (`statusCode/code/message/detail`) 유지 — 기존 호출자
  영향 없음.
- **`StatusPagesPlugin.installStatusPages(serverDefaultLanguage)`** —
  `serverDefaultLanguage` 매개변수 추가. ApiException 핸들러가 `messageKey`
  있으면 `Accept-Language → serverDefault → "en"` 순서로 resolve 후
  `Messages.t(lang, messageKey, *args)` 로 localized 응답.
- **`Module.kt`** — `installStatusPages(ctx.config.i18n.defaultLanguage)` 로 호출.
- **사이트 migration (11)** — `ApiException(...)` → `ApiException.localized(...)`:
  - `AuthPlugin.kt` — `requireDevice / requireApiWrite / requireApiAdmin /
    requireProjectAcl` 4 가드.
  - `CsrfTokens.kt` — `requireCsrf / verifyCsrfFromQueryOrHeader` 의 csrf
    missing/invalid 4 사이트 (replaceAll 로 통합 2 곳).
  - `UploadService.kt` — extension blocked + file too large (2 곳) +
    file not found (2 곳) — 5 사이트.
  - `FileRoutes.kt` — no FileItem in multipart 1 사이트.

### Notes

- 잔여 145+ throw 사이트는 후속 cycle 들 (v0.93.0+) 에서 점진적 migration.
  user-visible 우선순위: AuthService login/totp/setup → JsonAdminRoutes
  validation → ProjectService → BuildService → 기타.
- Wire change 없음 — 응답 shape 동일 (`ApiErrorDto { code, message, detail }`).
  message 텍스트만 사용자 언어로 변경.
- `:server:installDist` 통과.

## [0.91.0] - 2026-05-26 — Phase 66: Accept-Language end-to-end (cross-repo with vibe-coder-android v0.9.0)

Phase 64 i18n 시리즈의 cross-repo closure. SSR cookie 흐름은 sess.language 가
이미 user.language DB 컬럼 + 서버 default 로 결정됐었으나, **mobile client
(vibe-coder-android) 가 device locale 을 서버에 전달하는 표준 경로**가
없었음. Accept-Language 헤더 우선순위 추가로 cross-repo i18n 완성.

### Added

- **`Messages.fromAcceptLanguage(header)`** — RFC 7231 §5.3.5 parse 헬퍼.
  q-value 정렬 후 [SUPPORTED] 첫 매치 반환. region tag 무시 (ko-KR → ko,
  en-US → en). 잘못된 q-value 는 1.0 fallback.
- **`Messages.resolveFromRequest(acceptLanguage, userLang, serverDefault)`** —
  통합 resolve. 우선순위: Accept-Language 헤더 → user.language → server
  default → "en".
- **`ApplicationCall.preferredLanguage(serverDefault, userLang?)`** —
  JSON API 전용 헬퍼 (Bearer 토큰 인증으로 sess.language 가 없는 경우).

### Changed

- **`requireSessionOrRedirect`** — `WebSession.language` 가 `resolve` 대신
  `resolveFromRequest` 사용. Cookie 인증 흐름에서도 같은 token 으로 호출하는
  mobile webview / vibe-coder-android 가 device locale 우선 적용 (DB 컬럼
  변경 없이). 헤더 미전송 시 기존 동작 (user.language → server default) 유지
  → backward compatible.

### Wire change

- **Yes (additive, backward compatible).** Server 가 새 헤더를 **존중** 만 함 —
  헤더 미전송 시 기존 동작 그대로. vibe-coder-android v0.9.0 부터
  Accept-Language 헤더 송신 시 더 정확한 언어 응답.
- 호환성: 기존 client (v0.8.9 이하) 는 영향 없음. v0.9.0 client 가 v0.90.0
  이하 server 에 붙어도 헤더 무시되어 기존 동작.

### Notes

- vibe-coder-android v0.9.0 동기 필요 — KtorClient Accept-Language interceptor
  추가. 짝 리포 commit 메시지 참조.
- 본 cycle 은 schema 변경 없음.
- `:server:installDist` 통과.

## [0.90.0] - 2026-05-26 — Roadmap status sync + Phase 65 닫기

§9 Roadmap "남은 단일 큰 작업 5개" (v0.63.0 시점) 의 실제 상태 회고:

| # | 항목 | 실제 상태 |
|---|---|---|
| 1 | vibe-coder-android client | 별도 리포, server 리포 scope 밖 |
| 2 | 진짜 Kotlin LSP 통합 | ✅ v0.74.0 Phase 57 #7 완료 (KotlinLspService) |
| 3 | mecab-ko 형태소 분석 | ✅ v0.75.0 Phase 58 #8 완료 |
| 4 | PR 별 빌드 비교 | ✅ v0.71.0 + v0.89.0 완료 |
| 5 | Memo/star export 통합 | ✅ v0.70.0 Phase 49 #10 완료 |

본 server 리포 단독 작업은 일단락. 사용자가 "전부 진행" 의향 표시했으나
조사 결과 #1 (별도 리포) 외 모두 이미 완성된 상태. Roadmap status 표기만
outdated 였음.

### Changed

- **`CLAUDE.md` §9** —
  - **L. 빌드 도구 강화**: "PR 별 비교" 항목 ✅ + v0.71.0 + v0.89.0 reference.
  - **M. 대화 UX / 검색**: "Memo / star export" + "mecab-ko 형태소 분석"
    두 항목 ✅ + 각 v0.70.0 / v0.75.0 reference.
  - **우선순위 항목 정리**: v0.63.0 → v0.90.0 갱신. 5 항목 모두 회고
    + 단일 미완 #1 (Android repo) 만 별도 리포 안내.

### Notes

- 코드 변경 없음 — 문서 동기화만.
- `server.version` bump 는 운영자 reference 용 (Docker tag 의 latest 동기).
- 다음 sustain cycle 은 사용자 새 요구사항 / brainstorming 으로 추가
  ☆/○/△ 등록 후 진행.

## [0.89.0] - 2026-05-26 — Phase 65 #4: PR 별 빌드 비교 (branch-aware comparison)

§9 Roadmap 의 L.○ "PR 별 비교 — 현재는 시계열 직전 SUCCESS 와 비교. PR 메타데이터
(브랜치 / commit SHA) 까지 비교 키로." 의 결정타 wire-up.

v0.71.0 Phase 51 #9 에서 schema (Builds.gitBranch + gitSha) + `BuildService.
collectGitMetadata` + `BuildRepository.previousSuccessfulInBranch` 까지 인프라
완료된 상태. 본 cycle 은 `compareWithPrevious` 가 branch-aware 가 되도록 호출
교체 + UI 노출.

### Changed

- **`BuildService.compareWithPrevious`** — 기본 동작이 `previousSuccessfulInBranch`
  (같은 branch 의 직전 SUCCESS) 로 변경. `crossBranch: Boolean = false` 매개변수
  추가 — true 면 기존 v0.58.0 동작 (브랜치 무관). git 미초기화 (gitBranch=null)
  빌드는 자동으로 cross-branch fallback.
- **`BuildService.BuildSnapshot`** — `gitBranch` / `gitSha` 필드 추가.
  `BuildSnapshot.of()` 가 row 의 값 그대로 복사.
- **`BuildService.BuildComparison`** — `scope: Scope` (SAME_BRANCH | ANY) +
  `sameBranch: Boolean` derived field 추가.
- **`WebProjectRoutes`** —
  - 3 곳의 `BuildDto(...)` 생성에 `gitBranch = row.gitBranch, gitSha = row.gitSha`
    추가 — 기존 shared DTO 필드가 null 로 흘러가던 wire 갭 해소.
  - `GET /projects/{id}/builds/{buildId}?compare=any` query 지원 — UI 의 cross-branch
    토글 link 가 사용.
- **`WebProjectTemplates.buildDetailPage`** — 빌드 정보 `<dl>` 에 branch + commit SHA
  (앞 12자 + full hash hover title) row 2 개 추가. nullable safe-call 패턴.
- **`WebProjectTemplates.renderBuildComparison`** — 시그니처에 `projectId`/`buildId`
  추가:
  - 카드 헤더에 scope badge (same-branch ok / cross-branch warn) + 토글 link.
  - branch info row (`previous: branch@sha → current: branch@sha`) 추가 —
    monospace font, 한 줄 요약.
- **`MessagesEn` / `MessagesKo`** — `build.detail.gitBranch / .gitSha` +
  `build.compare.scopeSameBranch / .scopeAny / .crossBranchLink / .sameBranchLink /
  .branchInfo` 7 keys 추가.

### Notes

- 본 cycle 은 새 schema 변경 없음. 기존 v0.71.0 의 미사용 함수 (`previousSuccessfulInBranch`)
  를 활성화하는 wire-up + UI 노출.
- §9 Roadmap 의 M.○ "Memo / star export 통합" 은 이미 v0.70.0 Phase 49 #10 에서
  완료 (TurnRecord.userMemo/starred + import 시 setMemo/setStarred 호출).
  Roadmap status 표기만 누락된 상태였음.
- 향후 cycle (v0.90.0+): mecab-ko 형태소 분석 (한국어 검색) + 진짜 Kotlin LSP 통합.
- `:server:installDist` 통과.

## [0.88.0] - 2026-05-26 — Phase 64.12: admin/ 잔여 7 파일 i18n 완료 + 한국어 0건 달성

Phase 64 (i18n) 시리즈의 마지막 cycle. admin/ 폴더 전체에서 **사용자 가시
한국어 string 리터럴 0건** 달성. 남은 한국어는 KDoc / inline / HTML comment
(운영자 가독성용, CLAUDE.md §5 정책 유지) 만.

### Changed

- **`ProjectAclRoutes`** (9 string) — `acl.*` 도메인 9 keys 추가.
  unrestricted badge / allowedCount (parametric %d) / title / titleWithUsername
  (%s) / currentState / hint / backToUsers / saveBtn / flash.removed /
  flash.updated (%d).
- **`LogSearchRoutes`** (9 string) — `logsearch.*` 도메인 9 keys.
  noMatch (%s) / empty / title / subtitle / q.label / project.label /
  searchBtn / summary (%d %d) / bottomHint.
- **`MultiConsoleRoutes`** (8 string) — `multiconsole.*` 도메인 8 keys.
  pickAndOpen (%d) / newTab / subtitle / pickLabel / maxPanesSuffix (%d) /
  openBtn / iframeHint / subagentHint.
- **`McpRoutes`** (3 string) — `flash.mcp.installRejected` + multipart parse
  / empty file part 메시지는 server-side English (JSON API 통일).
- **`GitIntegrationsRoutes`** (2 string) — `flash.git.tokenRegisterRejected` +
  `flash.git.sshKeygenRejected`.
- **`JsonAdminRoutes`** (6 string) — Korean → English **직역** (i18n 제외).
  JSON API errors 는 programmatic consumption 이며 `code` 필드 (bad_request /
  duplicate_username / last_admin 등) 가 별도로 있어서 client 가 localize.
  기존 `"this endpoint requires admin role"` 등의 English baseline 패턴과 통일.
- **`WebProjectTemplates`** (8 string) — `console.banner.*` (2) +
  `console.live.*` (3) + `console.ws.reconnect5s` (parametric %s) +
  `console.cancel.sent`.
  - `renderClaudeBanner()` 헬퍼에 `lang` 추가 — "자세히" / "로그인이 끝나면
    이 페이지를 새로고침하세요" 메시지가 i18n.
  - 콘솔 JS 안 라이브 인증 실패 banner / placeholder / WS reconnect / cancel
    메시지 4건도 `jsLit()` + sentinel (`___CODE___`) 패턴으로 render 시점 치환.
- **`MessagesEn` / `MessagesKo`** — ~38 keys 추가 (acl 9 + logsearch 9 +
  multiconsole 8 + flash extras 3 + console live 7).

### Notes

- **admin/ 폴더 grep 결과 사용자 가시 한국어 string 리터럴 0건.** 남은 한국어:
  - `WebProjectTemplates.kt:782-788` — HTML `<!-- ... -->` 안 내부 코멘트 (slash chip 제거 이력).
  - 모든 파일의 KDoc / `//` / `/* */` 코멘트 — 운영자 가독성용 (CLAUDE.md §5).
- JsonAdminRoutes 의 ApiException message 는 i18n 안 함 — JSON 응답이라
  client (Android / external) 가 자체 localize. 기존 영문 패턴 유지.
- v0.84.0 (EnvSetupTemplates) 와 같은 sentinel-replace 패턴 (`___CODE___`)
  으로 WebSocket close event 의 `ev.code` JS 변수 주입.
- `:server:installDist` 통과.

### Phase 64 시리즈 회고 (v0.77.0 - v0.88.0)

총 12 cycle 에 걸쳐 server 의 모든 SSR 라우트와 inline template 을 영문/한국어
이중 언어로 전환. 사용자별 `language` 설정 (DB column) + `Accept-Language`
fallback. `Messages.t(lang, key, ...args)` 표준 패턴 + JS 안 메시지는
`jsLit()` + sentinel-replace 패턴. 영문 baseline 으로 GitHub OSS 컨트리뷰터
저변 확대 + 한국어 운영자 가독성 유지.

총 i18n 키: 약 1000+ 개 (MessagesEn / MessagesKo 합산 2000+ 라인).
영향 받은 파일: admin/ 폴더 SSR 전체 + WebProjectRoutes / EnvSetupRoutes /
기타 route 파일.

## [0.87.0] - 2026-05-26 — Phase 64.11: TwoFactorRoutes + CorsSettingsRoutes + ToolsRoutes + BackupRoutes i18n

### Changed

- **`TwoFactorRoutes` 전체 i18n** (route + inline TwoFactorTemplates):
  - 5 flash key (`twofa.flash.*`) — sessionExpired / codeMismatch / enabled /
    currentCodeMismatch / disabled.
  - 19 template key (`twofa.title / .heading / .disabled.* / .enabled.*`) —
    disabledPage (OTP setup 가이드 + URI/secret 표시 + 활성화 폼) +
    enabledPage (활성 상태 + 비활성화 폼).
- **`CorsSettingsRoutes` 전체 i18n** (route + CorsSettingsTemplates) —
  보안 영향 큰 read-only 페이지가 통째로 번역:
  - 38 template key (`cors.*`) — 현재 상태 / allowCredentials 설명 / Host 표 /
    보안 경고 3 항목 / 변경 방법 2 옵션 (docker env + server.yml) /
    검증 / Host 패턴 예시 표 + `describeHost()` 헬퍼 4 종.
  - parametric: `cors.statusExplicit` (%d hosts).
- **`ToolsRoutes` 전체 i18n** — `/tools` hub:
  - 12 template key (`tools.heading / .intro / .card.* (codeSearch/buildLogs/
    history title + 5 desc) / .tips.title / .tips.item1 / .tips.item2`).
  - `Multi-console` / `Emulator` 카드 제목은 영문 유지 (제품명).
- **`BackupRoutes` 전체 i18n** (route + renderPage):
  - 24 template key (`backup.*`) — 현재 크기 / tar.gz 다운로드 카드 /
    자동 백업 카드 (비활성/활성 분기 + run-now / 파일 목록 / 삭제) /
    PostgreSQL 별도 백업 / 복원 절차.
  - parametric: `backup.currentSize` (%s), `backup.auto.enabledMsg`
    (%s cron + %d retention).
  - inline JS confirm 메시지 2건 (runConfirm / delConfirm) 은 `.replace("'", "\\'")`
    로 single-quote escape.
- **`MessagesEn` / `MessagesKo`** — `twofa.* / cors.* / tools.* / backup.*`
  도메인 ~98 keys 추가.

### Notes

- TwoFactorRoutes / CorsSettingsRoutes 는 `_inline_ template object` 패턴 —
  Routes 파일 안에 template object 가 같이 있어서 route+template 한 번에 변환.
- BackupRoutes 는 top-level `renderPage()` 함수 — `lang` 매개변수 추가하고
  호출자 1곳에서 `sess.language` 전달.
- ToolsRoutes 의 toolCard 헬퍼는 lang 불요 (title/desc 를 caller 에서 이미
  번역해 전달).
- 사용자 가시 한국어 string 리터럴 0건 (4 파일).
- `:server:installDist` 통과.

## [0.86.0] - 2026-05-25 — Phase 64.10: McpTemplates + GitIntegrationsTemplates + UsersRoutes i18n

### Changed

- **`UsersRoutes` 전체 i18n** (route + inline UsersTemplates):
  - 8 flash key (`users.flash.*`) — adminOnlyPage / adminOnly / exists /
    created / cantDemoteLastAdmin / cantDeleteSelf / cantDeleteLastAdmin /
    deleted (parametric).
  - 14 template key (`users.title / .subtitle.adminCount / .row.me /
    .row.acl / .row.aclTitle / .row.deleteBtn / .row.deleteConfirm /
    .col.signup / .col.lastLogin / .col.action / .newCard.title /
    .newCard.passwordLabel / .newCard.submit / .newCard.hint`).
  - role 변경 inline JS confirm 은 `escJs()` 로 정확히 escape.
- **`McpTemplates` 전체 i18n** (catalogPage + helper 4 함수):
  - 32+ template key (`mcp.*` — title / subtitle / backToEnv / howto.* /
    trust.line / selected / installBtn / unregisterBtn / unregisterConfirm /
    customCard.* / entry.* / field.required / flash.* 등).
  - 7 JS-side key (`mcp.js.*`) — render 시점 `jsLit()` 으로 single-quoted
    literal 화. parametric 2건은 `___COUNT___` / `___FNAME___` sentinel +
    JS `.replace()` 패턴 (v0.84.0 EnvSetupTemplates 와 동일).
- **`GitIntegrationsTemplates` 전체 i18n** (page + flashBlurb):
  - 30+ template key (`gitint.*` — title / backToSettings / intro /
    ssh.* (notGen / confirmGen / genBtn / genHint / pubLabel / copy /
    copied / guideLabel / cardTitle) / pat.* (cardTitle / col.createdAt /
    newSection / providerOther / hostLabel / hostPlaceholder /
    tokenPlaceholder / usernameLabel / noteLabel / notePlaceholder /
    submit / guideTitle / guide.{github,gitlab,gitea,bitbucket}) /
    token.empty / token.confirmDel / token.delBtn / flash.*).
  - SSH 키 복사 버튼 inline JS 의 `✓ 복사됨` / `복사` 토글은 `jsLit()` 사용.
- **`MessagesEn` / `MessagesKo`** — 약 ~85 keys 추가 (`users.*` 22 +
  `mcp.*` 39 + `gitint.*` 31).
- **`McpRoutes`** / **`GitIntegrationsRoutes`** — 호출자 `lang = sess.language`
  전달.

### Notes

- McpTemplates 의 JS 안 parametric 메시지 (`%d` / `%s`) 는 `Messages.t()` 가
  서버 사이드 전용이므로 render 시 sentinel (`___COUNT___` / `___FNAME___`)
  로 치환 후 클라이언트가 `.replace()` 로 최종 값 주입.
- UsersRoutes 의 "invalid role" / "user not found" 같은 시스템 코드는
  영문 유지 (사용자가 직접 마주칠 일이 거의 없는 edge case).
- 사용자 가시 한국어 string 리터럴 0건 (3 파일).
- `:server:installDist` 통과.

## [0.85.0] - 2026-05-25 — Phase 64.9: WebProjectRoutes + AdminRoutes flash 메시지 i18n

### Changed

- **`WebProjectRoutes` flash 메시지 i18n 적용** (~30 Korean 문자열 → 1
  parametric key + 14 unique key):
  - `flash.project.notFound` (parametric `%s`) — 7 곳 중복 호출 통합.
  - `flash.project.created / .deleted / .notExist / .createFailed`.
  - `flash.form.projectIdRequired / .appNameRequired / .packageNameRequired /
    .cloneUrlRequired` (등록 폼 4종 validation).
  - `flash.build.queueFailed / .notFound`.
  - `flash.publish.aabRequired / .ipaRequired / .uploadFailed` (Play +
    TestFlight 공용).
  - `flash.file.uploadFailed / .noFileSelected / .uploaded / .deleteFailed /
    .deleted / .listFailed / .openFailed / .saveFailed`.
  - `flash.git.commitFailed`.
- **`AdminRoutes` flash 메시지 i18n 적용** (~15 Korean 문자열):
  - `flash.auth.usernameRequired / .passwordRequired / .passwordMismatch /
    .setupFailed / .loginFailed` — pre-login flow 에서 `deps.config.i18n.
    defaultLanguage` 사용 (sess 없음).
  - `flash.settings.saveFailed` (parametric).
  - `flash.password.confirmMismatch / .changeFailed / .changed`.
  - `flash.device.revoked / .cantRevokeCurrent`.
  - `flash.access.adminOnly / .viewerReadonly / .projectDenied` — 3개
    auth helper 함수 (`requireAdminOrRedirect` / `requireWriteAccessOrRedirect` /
    `requireProjectAccessOrRedirect`) 가 sess.language 사용.
- **`MessagesEn` / `MessagesKo`** — `flash.*` 도메인 37 keys 추가
  (project / form / build / publish / file / git / auth / settings /
  password / device / access 11 sub-domain).

### Notes

- 중복 Korean 문자열을 parametric key 로 통합한 결과 ~45 raw strings →
  37 unique keys. `flash.project.notFound` 한 키가 7 곳 호출자 모두 처리.
- pre-login flow (`POST /setup`, `POST /login`) 에는 sess 가 없어서
  `deps.config.i18n.defaultLanguage` 사용 — 로그인 폼에 표시되는 언어와 동기.
- auth guard 함수 3개 (`requireAdminOrRedirect` 등) 에서도 sess.language 로
  redirect 시 정확한 언어로 표시. 모든 access guard redirect 가 i18n.
- 사용자 가시 한국어 string 리터럴 0건. KDoc/inline comment 만 잔존.
- `:server:installDist` 통과.

## [0.84.0] - 2026-05-25 — Phase 64.8: EnvSetupTemplates i18n

### Changed

- **`EnvSetupTemplates` 전체 i18n 적용** — 모든 사용자 가시 한국어 문자열
  (~95 strings) 을 i18n 키로 변환:
  - `envSetupPage` — 헤더 / install-all 확인 / welcome card (4 steps +
    emulator note) / preserved volume card.
  - `renderCard` + `badgeFor` — CLAUDE_AUTH 6 label vs 일반 6 label.
  - `renderAction` — JAVA/GIT/NODE/CLAUDE_CLI builtin fail / ANDROID_SDK /
    PLATFORM_TOOLS / MCP_DEFAULTS / GRADLE 카드 4 종.
  - `claudeFlashBlurb` — 3 flash 메시지 (uploaded / api-key / api-key-deleted).
  - `renderClaudeAuthActions` — 옵션 0 (웹) / 옵션 1 (터미널) / 옵션 2
    (파일 업로드) / 옵션 3 (API 키) + delete + confirm dialog 6 종.
  - `claudeLoginPage` — 상태 칩 / start / URL block (copy/copied 토글) /
    code form (placeholder/submit/cancel) / DONE / FAILED / CANCELED 패널 +
    last lines summary.
  - `stateLabel` — 7 상태 (IDLE/STARTING/AWAITING_CODE/VERIFYING/DONE/
    FAILED/CANCELED).
  - `taskProgressPage` — 상태 / 경과 / lines 카운터 / live log 카드 +
    클라이언트 JS (connecting/connected/inProgress/done/error/justNow/
    secondsAgoSuffix/ended/connectionLost + successMsg/failureMsg
    `___TOTAL___` sentinel-replace 패턴).
  - `errorBlurb` — 단순 inline 에러 페이지의 title / heading / back 링크 +
    `lang` 매개변수로 `<html lang="...">` 동기.
- **`EnvSetupRoutes`** — 6 호출자 모두 `lang = sess.language` 전달. 8 곳의
  `errorBlurb(...)` 한국어 인자도 `Messages.t(sess.language, "env.error.*")`
  로 변환 (multipartParse / noFile / uploadRejected / uploadFailed /
  keyRejected / keyFailed / startFailed / codeSubmitRejected /
  installStartFailed).
- **`MessagesEn` / `MessagesKo`** — `env.*` 도메인 ~106 keys 추가
  (badge / action / flash / auth / login / state / task / error 7 sub-domain).

### Notes

- `taskProgressPage` 의 JS 안에서 i18n 메시지는 `Messages.t()` 가 서버 사이드
  전용이므로 render 시점에 `jsLit()` 으로 single-quoted literal 화. `%s` 가
  들어가는 successMsg/failureMsg 는 `___TOTAL___` sentinel 을 `String.format`
  으로 채운 뒤 JS 가 `.replace('___TOTAL___', total)` 로 최종 치환.
- `<html lang="ko">` 하드코딩이던 errorBlurb 는 사용자 언어에 따라 ko/en 동기.
- ComponentState 모델의 `displayName`/`description`/`sizeHint`/`message` 는
  모델 레이어 책임 — 별도 cycle 에서 분리해 처리 (현재는 영문 가정).
- 사용자 가시 한국어 0건. KDoc/inline/HTML comment 만 잔존.
- `:server:installDist` 통과.

## [0.83.0] - 2026-05-25 — Phase 64.7: WebProjectTemplates 잔여 6 페이지 i18n

### Changed

- **`WebProjectTemplates`의 6 페이지 + 헬퍼 7 개 모두 `lang` 매개변수
  + i18n 키 적용** — 기존 `projectsPage` / `projectDetailPage` /
  `consolePage` 3 개와 합쳐 `WebProjectTemplates` 의 모든 SSR 페이지가
  국제화 완료:
  - **`buildsPage`** — `builds.*` (12 keys) + `bar.aria` aria-label.
  - **`buildDetailPage`** — `build.detail.*` (12 keys) + `build.compare.*`
    (9 keys) + `signer.*` (5 keys) + `publish.*` / `play.*` / `tf.*`
    (27 keys, Play+TestFlight 카드).
  - **`filesPage`** — `files.*` (11 keys) — upload card / column /
    action / nav 전체.
  - **`gitPage`** — `git.*` (20 keys) — status / diff / log card +
    commit & push 폼.
  - **`fileTreePage`** — `fileTree.*` (8 keys).
  - **`fileViewPage`** — `fileView.*` (7 keys).
  - 헬퍼 `renderBuildStatistics` / `renderBuildHistoryChart` /
    `renderPlayUploadCard` / `renderTestFlightUploadCard` /
    `renderBuildComparison` / `renderSignerInspection` / `replayCaption`
    7 개도 `lang` 전달 받아 i18n.
- **`MessagesEn` / `MessagesKo`** — 위 6 페이지 + 헬퍼용 약 117 keys 추가
  (stash 의 42 keys + 추가 ~75 keys).
- **`WebProjectRoutes`** — 6 페이지 호출자 7 곳 모두 `lang = sess.language`
  전달.

### Notes

- 사용자 가시 한국어 문자열은 6 페이지에서 모두 제거. 남은 한국어는 KDoc /
  inline / HTML comment 등 운영자 가독성용 (CLAUDE.md §5 정책에 따라 유지).
- `git.notInit` / `files.upload.hint` / `git.commit.desc` / `git.bottomHint` /
  `build.compare.dexHint` / `play.desc` / `tf.desc` / `build.compare.desc`
  등은 `<code>` HTML 태그 포함 — `esc(t(...))` 가 아닌 raw `t(...)` 출력
  (i18n 메시지 자체에 HTML 포함, XSS 위험 없음).
- 컴파일 + `:server:installDist` 정상 통과.

## [0.82.0] - 2026-05-25 — Phase 64.6: consolePage i18n (가장 큰 페이지)

### Changed

- **`WebProjectTemplates.consolePage()`** — `lang` 매개변수 + `console.*`
  (27 keys). Claude CLI/auth banner / sidebar 7 chip + title attribute /
  session label + stop button + new session confirm / agent + template
  picker + manage links / prompt textarea placeholder + send button +
  blocked hint.
- **`WebProjectRoutes`** — `GET /projects/{id}/console` + `GET /chat`
  두 호출자에 `lang = sess.language` 전달.

### Notes

- placeholder 안의 newline 은 `&#10;` HTML entity 로 변환 — textarea 가 줄바꿈
  표시.
- new session confirm 메시지는 single-quote escape (`&#39;`) 로 attribute
  안 안전 inline.

### Next (v0.83.0+)

- `buildsPage` / `buildDetailPage` / `renderBuildHistoryChart` (~9 string)
- `filesPage` / `gitPage` / `fileTreePage` / `fileViewPage` (~18 string)
- `EnvSetupTemplates.kt` (48)
- `WebProjectRoutes` flash 메시지 (33)
- 잔여 12 파일

## [0.81.0] - 2026-05-25 — Phase 64.5: projectDetailPage i18n

### Changed

- **`WebProjectTemplates.projectDetailPage()`** — `lang` 매개변수 + `projects.detail.*`
  (32 keys). 3개 카드 (Summary / Actions / Recent builds) + delete confirm
  메시지 + 11개 detail action 링크 (Console / Builds / History / Tree / Files
  / Zip / EnvFiles / Deps / Automation / Wrapper / Stats / Git) + 각 링크의
  title attribute.
- **`WebProjectRoutes` `GET /projects/{id}`** — `lang = sess.language` 전달.

### Next (v0.82.0+)

- `consolePage` (가장 큼, 약 18 string)
- `buildsPage` / `buildDetailPage` (~9 string)
- `filesPage` / `gitPage` / `fileTreePage` / `fileViewPage` (~18 string)
- `EnvSetupTemplates.kt` (48)
- `WebProjectRoutes.kt` 의 flash 메시지 (33)
- 잔여 12 파일

## [0.80.0] - 2026-05-25 — Phase 64.4: projectsPage i18n

### Changed

- **`WebProjectTemplates.projectsPage()`** — `lang` 매개변수 + `projects.*`
  (32 keys). 등록 목록 + 새 프로젝트 폼 (template / id / appName / package /
  source 라디오 / clone URL & branch / hint).
- **`WebProjectRoutes`** — 3 호출자 (`get /projects` + 2 `post /projects` error
  분기) 에 `lang = sess.language` 전달.

### Added (i18n keys)

- `projects.heading` / `projects.list.*` (5) / `projects.list.col.*` (3) /
  `projects.list.openConsole`
- `projects.new.*` (15) — template / idLabel / appName / packageName /
  source / empty / clone / cloneUrl / branch / cloneHint / submit / emptyHint
- `projects.releaseNotes.placeholder.*` (2) / `projects.precheck.*` (3)

### Next (v0.81.0+)

- `projectDetailPage` (14 string)
- `consolePage` (18 string — 가장 큼)
- `buildsPage` / `buildDetailPage` / `filesPage` / `gitPage` /
  `fileTreePage` / `fileViewPage` (~30 string)
- `EnvSetupTemplates.kt` (48)
- `McpTemplates` / `UsersRoutes` / 등 잔여 13 파일

## [0.79.0] - 2026-05-25 — Phase 64.3: AdminTemplates 잔여 페이지 i18n

`AdminTemplates.kt` 의 한국어 string 0건 (KDoc 제외) — 본 파일 i18n 완료.

### Changed

- **`renderDiskUsageCard()`** — `lang` 매개변수 + `dashboard.disk.*` (5 keys).
- **`renderClaudeUsageCard()`** — `lang` 매개변수 + `dashboard.claude.*` (6 keys).
- **`passwordPage()`** — `lang` 매개변수 + `password.*` (5 keys).
- **`devicesPage()`** — `lang` 매개변수 + `devices.*` (5 keys).
- **`errorPage()`** — `lang` 매개변수 + `error.page.*` (2 keys).
- 모든 호출자 (`AdminRoutes`) 에 `lang = sess.language` 또는 `deps.config.i18n.defaultLanguage`
  전달.

### Coverage

| AdminTemplates 페이지 | 상태 |
|---|---|
| shell / navHtml / tabBar | ✅ v0.77.0 |
| setupPage / loginPage / dashboardPage | ✅ v0.78.0 |
| settingsPage (language card) | ✅ v0.77.0 |
| renderDiskUsageCard / renderClaudeUsageCard | ✅ v0.79.0 |
| passwordPage / devicesPage / errorPage | ✅ v0.79.0 |

### Next (v0.80.0+)

- `WebProjectTemplates.kt` (60 string)
- `EnvSetupTemplates.kt` (48 string)
- `WebProjectRoutes.kt` (33) / `McpTemplates.kt` (15) / 기타 17 파일

## [0.78.0] - 2026-05-25 — Phase 64.2: 인증/대시보드 페이지 i18n 변환

v0.77.0 인프라 위에 사용자가 가장 먼저 마주치는 4개 페이지 (setup / login /
TOTP / dashboard) 의 한국어 string 을 모두 i18n key 로 변환.

### Changed

- **`AdminTemplates.setupPage()`** — `lang` 매개변수 추가. 모든 한국어 텍스트
  → `auth.setup.*` key 변환 (제목/사용자명/비밀번호 hint 등 7개).
- **`AdminTemplates.loginPage()`** — `lang` 매개변수 추가. login 폼 + TOTP
  2단계 폼 모두 변환 (auth.login.*, auth.login.totp.* 12개). passkey
  버튼/상태 JS 안의 한국어 string 도 `jsLitString(t(key))` 로 변환 (5개).
- **`AdminTemplates.dashboardPage()`** — `lang` 매개변수 추가. 3개 카드
  (Server / Environment / Activity) + Claude/SDK 배지 + auth hint + count
  suffix 모두 변환 (`dashboard.*` 23 key).

### Added (i18n keys)

- `auth.setup.*` (7 keys) — 초기 설정 폼.
- `auth.login.*` + `auth.login.totp.*` + `auth.login.passkey.*` (16 keys).
- `dashboard.*` (25 keys).
- `password.title`, `devices.title`, `devices.currentSession`,
  `error.page.title` (preallocated for v0.79.0+ 변환).

### Migration

- 로그인 전 페이지 (setup/login) 는 사용자 식별 안 됨 → server default
  (`VIBECODER_DEFAULT_LANGUAGE` env 또는 `i18n.defaultLanguage`) 만 사용.
- 로그인 후 페이지 (dashboard) 는 user 별 설정 (`Settings → General →
  Language`) 우선 적용.

### Wire change / Server requirement

- 없음 (SSR 만).

### Next (v0.79.0+)

- AdminTemplates 잔여 (password / devices / error / claude usage card / disk
  usage card)
- WebProjectTemplates (60 string)
- EnvSetupTemplates (48 string)
- 그 외 17 파일

## [0.77.0] - 2026-05-25 — Phase 64: SSR i18n 인프라 + 언어 선택

SSR 다국어 지원 시작. v0.77.0 은 **인프라 + nav + Settings dropdown** 만 — 모든
SSR 페이지의 한국어 string 일괄 변환은 v0.78.0+ 점진 진행.

### Added

- **`server/i18n/Messages.kt`** — SSR i18n 진입점. `Messages.t(lang, key, *args)`
  로 lookup, `resolve(userLang, serverDefault)` 로 fallback chain (사용자 → server
  default → "en").
- **`server/i18n/MessagesEn.kt` / `MessagesKo.kt`** — en + ko 초기 번들 (~90 키).
  Common / nav / settings / projects / env / claude / mcp / git / error 카테고리.
- **`AdminUsers.language` column** — 사용자 별 SSR 언어 (nullable, fallback).
- **`AdminUserRepository.setLanguage(userId, lang)`** — 사용자 언어 변경.
- **`I18nSection` config + `VIBECODER_DEFAULT_LANGUAGE` env override** — 서버
  default 언어 ("en"/"ko"). compose.yml + .env.example 에서 노출.
- **`/settings/language` POST endpoint** — Settings → General 의 dropdown 으로
  사용자 본인 언어 변경.

### Changed

- **`AdminTemplates.shell()`** — `lang: String` 매개변수 추가. `<html lang>`
  속성 + nav 라벨 (`nav.home`, `nav.projects` 등) i18n.
- **`AdminTemplates.settingsPage()`** — language card (dropdown) 추가. 매개변수
  `lang`, `userLanguage`, `serverDefaultLanguage` 신규.
- **`SettingsNav.tabBar()`** — `lang` 매개변수 추가. 8 탭 라벨 i18n.
- **`WebSession`** — `language: String` 필드 추가. `requireSessionOrRedirect`
  가 `Messages.resolve(user.language, config.i18n.defaultLanguage)` 로 채움.
- **`server.yml`** — `i18n.defaultLanguage: en` 섹션 추가.
- **`docker/compose.yml` + `.env.example`** — `VIBECODER_DEFAULT_LANGUAGE`
  env 노출 (default `en`).

### Migration

- 기존 사용자 = `language=null` → 서버 default ("en") 적용. Settings → General
  → Language 에서 본인 언어 선택 가능.
- 운영자는 compose 의 `VIBECODER_DEFAULT_LANGUAGE=ko` 로 한국어 default 가능.

### Wire change

- 없음 (SSR 만 영향, JSON API / Android client 무관).

### Next (v0.78.0+)

- WebProjectTemplates (60 string) / EnvSetupTemplates (48 string) /
  WebProjectRoutes (33 string) / AdminTemplates 나머지 / 기타 17 파일의 한국어
  string 일괄 변환. 키 추가 + raw string → `t(key)` 호출 변환.

## [0.76.0] - 2026-05-25 — 정밀 점검 회수: Medium/Low + wire DTO SSOT

정밀 점검 후 v0.69~v0.75 의 Medium/Low 결함 일괄 회수 + wire DTO 정렬.

### Added

- **`shared/dto/ArtifactVerifyDtos.kt`** — `ApkVerifyResultDto` 신규.
  v0.75.x 까지는 server-local inner class `ApkVerifier.Result` 만 존재. Android
  client 가 catch-up 하려면 wire shape 보증 필요. shared SSOT 로 이전.
- **`server/notify/NotificationRetentionScheduler.kt`** — 일 1회 (24h) acked +
  30일 이상된 notification_events row hard delete. `ServerMain` wire +
  shutdown hook.

### Fixed

- **M5: NotificationEvents retention prune scheduler** — 이전엔 KDoc 만 "별도
  cycle" 명시, 실 scheduler 없음. 모든 ack 된 row 영구 보존 → 빌드/usage
  알림 누적으로 DB GB 단위 증가 가능. `Clock.cutoffIso(days)` helper +
  `NotificationService.pruneAckedOlderThan(days)` + 신규 scheduler.
- **M6: EnvFiles whitelist SSOT 통합** — `JsonAdminRoutes` 의
  `WHITELISTED_ENV_FILES` (4개) 가 `EnvFilesRoutes.ENV_FILES_WHITELIST` (7개)
  와 비대칭. JSON API 의 모바일 client 가 build.gradle.kts 등 못 읽음.
  `ENV_FILES_WHITELIST` 를 `internal` 노출 + JSON 측이 직접 참조.
- **M7: EmulatorService 즉시-exit detection** — `launchAvd` 가 같은 AVD 이미
  실행 / KVM 권한 없음 / 잘못된 옵션 등으로 즉시 exit 한 프로세스도 "launched"
  로 거짓 응답하던 회귀. `proc.waitFor(1, SECONDS)` 로 짧게 기다린 후 즉시
  exit 했으면 stderr 출력을 `LaunchResult.message` 에 포함.
- **M8: LogSearchService byte/char skip 혼동** — `reader.skip(N)` 은 문자 수.
  byte 한도와 혼동되어 한글 로그 라인 섞이면 skip 오프셋 어긋남 + line 번호
  부정확. `Files.newInputStream` + byte 단위 skip 후 `BufferedReader` wrap.

### Changed

- **`ApkVerifier`** — inner `Result` 클래스 제거, shared `ApkVerifyResultDto` 직접
  사용. 7곳 호출 모두 새 타입으로 정렬. wire 호환 (필드 동일).
- **`shared/ApiPath.kt`** — `PUSH_*` / `WEBAUTHN_*` / `buildWebhook` 등 server-only
  endpoint 도 SSOT 에 명시 등록 (CLAUDE.md §8.A 정책 준수). wire 영향 없음
  (Android 가 호출 안 하지만 SSOT 기록은 의미).
- **`Clock`** — `cutoffIso(daysAgo: Int): String` default 메소드 추가.

### Removed

- **L5: `ServerActionHandler.SLASH_WHITELIST` dead constant 제거** — v0.75.0
  이후 `invokeSlash` 가 항상 410. 사용처 없음.

### Internal

- L2: `FcmSender.shutdown()` shutdown hook 등록 (이전엔 정의만, 호출 없음).
  현재 no-op 본문이라 실해 없지만 향후 connection pool 추가 시 leak 방지.
- L6 (server only): `historyRoutes` 시그니처에 `adminUserRepo` (v0.75.1 H3
  fix 의 후속) — 본 cycle 에는 추가 영향 없음.

### Wire change

- **`ApkVerifyResultDto`** — server v0.70 의 `ApkVerifier.Result` 와 wire 동일
  (필드 이름/타입/순서 정합). 기존 client (Android v0.8.5+) 와 호환.
  vibe-coder-android `shared/dto/ArtifactVerifyDtos.kt` 가 본 cycle 의 정렬과
  같은 모양 (v0.8.5 에서 catch-up 완료).

### Server requirement

- Notification retention scheduler 자동 시작 — 첫 prune 은 boot 후 5분, 이후
  24h 주기. 운영자 별도 동작 불필요.

## [0.75.1] - 2026-05-25 — Hotfix: LSP byte/race + memo/star ACL

정밀 점검 후 v0.69~v0.75 에서 발견된 High 결함 3건 일괄 회수.

### Fixed

- **H1: KotlinLspService Content-Length byte/char 혼동** — LSP 스펙은 UTF-8
  **바이트 수** 인데 기존 `BufferedReader + CharArray + reader.read` 는 문자
  단위. 한글 identifier 포함 응답 partial read → JSON parse 실패로 한글 코드
  베이스 symbol 검색이 sporadic 0건. `InputStream` 으로 raw byte 읽기 +
  `String(buf, UTF_8)` 디코드 + `readHeaderLineSafe` 도 byte 단위로 재작성.
- **H2: KotlinLspService 동시 첫 호출 시 프로세스 leak** — `instances.getOrPut`
  은 Kotlin extension 으로 ConcurrentHashMap 에서도 non-atomic (get → null
  이면 build → put 분리). 같은 projectId 의 동시 첫 호출 시 LspInstance 두 개
  spawn → 한 프로세스 leak. `computeIfAbsent` 로 java.util.concurrent atomic
  보장.
- **H3: HistoryRoutes.authorizeMemoStar Bearer 분기 Project ACL 누락** — 멀티
  사용자 환경에서 turnId 만 알면 ACL 밖 프로젝트의 turn 도 memo/star 가능
  했음 (JSON variant 와 비대칭). Bearer 통과 후 `repo.findById(turnId)` 로
  projectId 추출 → `projects.canUserAccess(uid, isAdmin, projectId)` 검증,
  실패 시 403 `project_forbidden`.

### Internal

- `historyRoutes` 시그니처에 `adminUserRepo: AdminUserRepository` 추가.
- `KotlinLspService.LspInstance` 의 `reader: BufferedReader` 제거, `input:
  InputStream` 사용.

### Wire change

- 없음. 양측 호환 유지. memo/star endpoint shape 변경 없음 — Bearer 토큰에
  연결된 user 가 해당 프로젝트 ACL 없으면 403 만 추가.

## [0.75.0] - 2026-05-25 — Fix: 사용 불가 slash command quick chip 제거

### Removed — 콘솔의 `/status` `/cost` `/model` 외 slash chip 7개

사용자 신고: 콘솔에서 `/status`, `/cost`, `/model` 등 7개 slash command 가 quick
chip 으로 등록돼 있지만 실제론 사용 불가. 검토 결과 — vibe-coder 의 콘솔은
`claude --print --output-format stream-json` **non-interactive streaming mode**
이고, Claude Code 의 interactive slash commands 가 이 모드에서 미지원. chip 클릭
시 `/status` 같은 문자열이 그냥 prompt 로 들어가 Claude 가 "그게 뭔지 모르겠다"
응답 또는 무시.

#### 제거된 chip (`WebProjectTemplates`)

- `/status` — 사용량 / 모델 정보 (interactive only).
- `/cost` — 누적 비용 (interactive only).
- `/model` — 모델 변경 (interactive only).
- `/memory` — 메모리 dump (interactive only).
- `/plan` — Plan mode toggle (interactive only).
- `/compact` — Context 압축 (interactive only).
- `/clear` — 세션 초기화 (interactive only — "새 세션" 버튼이 같은 역할).

#### 대안

- **사용량 / 모델 정보**: 콘솔 상단의 status snapshot (`ClaudeStatusService` —
  `/api/projects/{id}/claude/status` 로 별도 polling).
- **세션 reset**: 콘솔의 "새 세션" 버튼 (`/api/projects/{id}/claude/console/new` —
  child process restart).

#### 호환성

- `POST /projects/{id}/console/slash` SSR endpoint: 유지 + no-op redirect.
  외부 link / 북마크 호환. 실제로는 무음으로 console 페이지로 redirect.
- `POST /api/projects/{id}/actions/invoke` 의 `kind=slash` action: HTTP 410
  `slash_not_supported` 반환. 사용자 정의 `actions.yml` 의 prompt-기반 actions
  (kind=prompt) 는 영향 없음.
- `WebProjectTemplates.slashChip` private 함수 제거 (dead code).

### 변경 파일

- `server/admin/WebProjectTemplates.kt` — chip-row 제거, slashChip 함수 제거.
- `server/admin/WebProjectRoutes.kt` — /console/slash endpoint 무음 redirect.
- `server/actions/ServerActionHandler.kt` — invokeSlash 가 410 throw.
- `config/server.yml` — 0.75.0.

## [0.74.0] - 2026-05-25 — Phase 57+58+59: 후속 cycle 대형 작업 정밀화

### Added — Phase 57 #7 Kotlin LSP 정밀 통합 (stub → 실 동작)

`KotlinLspService` 가 v0.73.0 stub 에서 진짜 LSP client 로 진화. dependency 추가
없음 — JDK stdlib (BufferedReader / OutputStream) 만으로 JSON-RPC stdio 구현.

- **`projects/KotlinLspService.kt`** 전면 재작성:
  - `KOTLIN_LSP_PATH` 환경 변수의 binary 를 per-project 로 spawn.
  - `initialize` → `initialized` notification → `workspace/symbol` 호출.
  - Content-Length 헤더 + JSON-RPC body parsing.
  - stderr background drain (block 방지), ReentrantLock 으로 sequential I/O.
  - SymbolKind enum 일부 매핑 (class/function/var/object 등).
  - 첫 호출 60초 timeout (initialize indexing 시간).

- **`projects/SymbolRoutes.kt`** — `lookup()` helper 가 LSP 우선 + regex fallback.
  LSP 결과 비어있거나 disabled 면 SymbolFinder 사용.

- **`ServerMain.kt`** — KotlinLspService 인스턴스 + shutdown hook.

#### 활성화

```bash
# Host 에 kotlin-language-server binary (fwcd/kotlin-language-server) 빌드.
docker compose vibe-coder-server.environment 에:
  KOTLIN_LSP_PATH: /opt/kotlin-lsp/bin/kotlin-language-server
volumes:
  - /host/kotlin-lsp:/opt/kotlin-lsp:ro
```

미설정 시 SymbolFinder regex 만 사용 (변화 없음).

### Added — Phase 58 #8 mecab-ko PG image + SQL 통합

별도 PostgreSQL 이미지 + Kotlin 측 검색 SQL 분기.

- **`docker/postgres-mecab/Dockerfile`** 신규 — `postgres:17` base + mecab +
  mecab-ko-dic + python3-mecab + postgresql-plpython3-17.
- **`docker/postgres-mecab/init-mecab.sql`** 신규 — plpython3u extension +
  `mecab_kor_tokens(text) RETURNS text[]` + `mecab_kor_query(text)` SQL 함수.
  명사/동사/형용사 stem 만 추출 (어미 제거).
- **`ConversationTurnRepository`** — 환경 변수 `VIBECODER_MECAB_ENABLED=true` 시
  한국어 query 를 trigram (ILIKE) 대신 `mecab_kor_tokens(content) && mecab_kor_query(?)`
  array overlap 사용. 미설정 시 기존 trigram fallback.

#### 활성화

```bash
# Build mecab-ko PG image.
docker build -f docker/postgres-mecab/Dockerfile -t siamakerlab/postgres-mecab-ko:17 .

# docker compose .env:
VIBECODER_POSTGRES_IMAGE=siamakerlab/postgres-mecab-ko:17
VIBECODER_MECAB_ENABLED=true

docker compose down postgres && docker compose up -d postgres vibe-coder-server
```

선택 (성능 향상): generated column + GIN 인덱스 추가 — init-mecab.sql 주석 참조.

### Added — Phase 59 #13 AVD 자동 setup + KVM auto-detect

`EmulatorService` 의 createDefaultAvd 가 system-image 자동 다운로드 + boot 대기.

- **`EmulatorService.ensureSystemImage()`** — `sdkmanager --list_installed` 로
  존재 확인 → 없으면 `sdkmanager --install system-images;android-35;google_apis;x86_64`
  자동 실행 (~500MB, 1-3분 소요). License 자동 accept.
- **`EmulatorService.waitForBoot(serial, timeoutSec)`** — `adb shell getprop
  sys.boot_completed == "1"` 2초 polling, 2분 timeout.
- `createDefaultAvd` 가 ensureSystemImage 먼저 호출 — image 없어도 자동 install.
- KVM auto-detect 는 이미 `diagnose()` 에 있음 (v0.19.0 부터).

#### 활성화 (전제: `:full` 이미지 + KVM passthrough)

```yaml
# docker compose.yml
services:
  vibe-coder-server:
    image: siamakerlab/vibe-coder-server:0.74.0  # 본 cycle 부터 emulator 자동 setup 통합
    # :full variant 가 KVM 필요 (이미지 ~1GB) — 일반 사용자는 그대로 사용 가능.
    devices:
      - /dev/kvm  # Linux host KVM 필요
    privileged: true
```

미설정 시 — `diagnose()` 가 KVM unavailable 표시 + recommendation.

### 변경 파일

- `server/projects/KotlinLspService.kt` 전면 재작성 (stub → 실 LSP client).
- `server/projects/SymbolRoutes.kt` — lookup() helper.
- `server/repo/ConversationTurnRepository.kt` — mecab env var 분기.
- `server/emulator/EmulatorService.kt` — ensureSystemImage / waitForBoot.
- `server/Module.kt`, `server/ServerMain.kt` — kotlinLspService wiring + shutdown.
- `docker/postgres-mecab/{Dockerfile,init-mecab.sql}` 신규.
- `config/server.yml` — 0.74.0.

### 호환성

- 모두 환경 변수 / Docker image 활성화 → opt-in. 미설정 시 v0.73.0 동작 그대로.
- 도커 이미지 크기 변화 없음 (KOTLIN_LSP_PATH / mecab PG / KVM 모두 host 측).

## [0.73.0] - 2026-05-25 — Phase 53+55+56: grep UI + Kotlin LSP / mecab / Emulator stubs

### Added — Phase 53 #16 grep UI 강화

- `CodeAnalysisRoutes` 의 코드 검색 결과 UI 강화:
  - file viewer link 에 `&line=N` 추가 → 클릭 시 매치 행 jump (SymbolFinder 의 file viewer 가 같은 query 처리).
  - projectId monospace + primary 색, relPath dim — 시각적 grouping.
  - 코드 라인을 `<pre>` 안 dark background 박스로 → 가독성 ↑.
  - 행 번호 자체도 link.

### Skipped — Phase 53 #15 role 세분화

- 현재 `admin / member / viewer` 가 LAN 1인 도구에 적절. `viewer` (read-only) +
  `member` (write) + `admin` (전부) 3-tier 가 다른 OSS 도구 (GitHub / GitLab) 와 동일
  수준 — 추가 세분화 시 운영 복잡도 증가 vs 가치 불균형.
- 진정 fine-grained permission 필요 시 `ProjectAcl` row 의 별도 `role` 컬럼 추가 작업
  (read/write/admin 단계) — 후속 cycle.

### Added — Phase 55 stub (#7 Kotlin LSP, #8 mecab-ko)

#7 Kotlin LSP:
- `projects/KotlinLspService.kt` 신규 stub. `KOTLIN_LSP_PATH` 환경 변수로 binary detect.
- `isAvailable` flag 만 동작 — `definition()` 은 v0.73.0 에서 empty (SymbolFinder fallback).
- 정밀화는 후속 cycle (JSON-RPC stdio + textDocument/definition 통합 시 ~300MB image).

#8 mecab-ko:
- 별도 PG image build 필요. 본 cycle 은 documentation 만 — `docs/ADVANCED_FEATURES.md`.
- 후속 cycle 에서 `ConversationTurnRepository.search` SQL 통합.

### Added — Phase 56 stub (#13 Android Emulator KVM)

- `:full` Dockerfile variant (v0.57.0+) 이미 emulator binary 포함.
- KVM passthrough 활성화 안내: `/dev/kvm` device + privileged compose option.
- AVD 자동 setup script 정밀화는 후속 cycle.
- documentation: `docs/ADVANCED_FEATURES.md`.

### 신규 파일

- `server/projects/KotlinLspService.kt` (stub).
- `docs/ADVANCED_FEATURES.md` (Kotlin LSP / mecab-ko / Emulator KVM 활성화 가이드).

### 변경 파일

- `server/projects/CodeAnalysisRoutes.kt` — grep 결과 UI.
- `config/server.yml` — 0.73.0.

## [0.72.0] - 2026-05-25 — Phase 52: FCM 실제 발송 (#4)

### Added — Firebase Cloud Messaging HTTP v1 직접 통합

v0.68.0 의 FCM token register stub 을 실 활성화. Firebase Admin SDK 미사용 —
JDK stdlib (java.net.http.HttpClient + java.security.Signature RS256) 만으로 구현.
dependency 추가 없음.

`server/notify/FcmSender.kt` 신규:
  - 환경 변수 활성화: `FCM_PROJECT_ID` + `FCM_SERVICE_ACCOUNT_JSON_PATH` 모두 설정 시.
    미설정 시 `isEnabled=false` → 모든 send() no-op (polling path 가 fallback).
  - Service account JSON parse → PKCS#8 PEM → RSA private key.
  - RS256 JWT 서명 → Google OAuth `token` endpoint exchange → access token.
  - Access token 1시간 cache (만료 1분 전 부터 재발급).
  - FCM HTTP v1: `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`.
  - Token stale (404/400 UNREGISTERED/INVALID_ARGUMENT) 자동 제거.

Notifiers facade 통합:
  - `Notifiers.fcm: FcmSender?` 추가.
  - `buildResult()` 호출 시 polling notification + FCM instant push 동시 발송.

NotificationRoutes.kt:
  - `POST /api/notifications/fcm-token` (v0.68.0 stub) → 실 등록 호출.
  - 미활성 시 token 만 수집 (향후 활성화 대비) + isEnabled log.

### 활성화 방법

```bash
# Firebase Console → 프로젝트 설정 → 서비스 계정 → 비공개 키 생성 → .json 다운로드
docker compose 의 vibe-coder-server service environment 에 추가:
  FCM_PROJECT_ID: "your-firebase-project-id"
  FCM_SERVICE_ACCOUNT_JSON_PATH: "/data/firebase-service-account.json"

volumes 에 service account JSON 마운트:
  - ${HOST_PATH}/firebase-service-account.json:/data/firebase-service-account.json:ro

docker compose up -d
```

미설정 시 (대부분 사용자) — polling notification 만 사용. 15분 주기 instant 아닌
near-real-time 알림.

### 변경 파일

- `server/notify/FcmSender.kt` 신규.
- `server/notify/Notifiers.kt` — `fcm` 필드 + buildResult 통합.
- `server/notify/NotificationRoutes.kt` — fcm-token 등록 실 호출.
- `server/Module.kt`, `server/ServerMain.kt` — wiring.
- `config/server.yml` — 0.72.0.

### Token 영속화 후속 cycle

현재 in-memory tokensByUser (process restart 시 reset). DB 영속화 + 만료 token
GC 는 후속 cycle. Android 가 token rotate 시 다시 register 호출하므로 운영 상 큰
영향 없음.

## [0.71.0] - 2026-05-25 — Phase 51: Notification DB 영속화 + PR 빌드 비교 (#3, #9, #11)

### Wire change — Yes (BuildDto +2 필드, Notification 영속화)

#3 — Notification DB 영속화 (v0.68.0 in-memory queue 의 영구화)
  - `db/Schemas.kt`: `NotificationEvents` 테이블 신규 (id/userId/ts/kind/title/
    body/deepLink/projectId/ackedAt/createdAt + (userId, ackedAt) / createdAt index).
  - `NotificationService` 가 ConcurrentHashMap 에서 PG transaction 으로 전환.
    list/count/ack API 동일 — 호출처 (NotificationRoutes) 변경 X.
  - Process restart 시 unread 유지 (이전엔 reset).
  - Retention: per-user 500 unread cap (over-limit 시 가장 오래된 것 ack 처리).
    hard delete cron 은 별도 cycle (audit 보존 위해 ack 만).

#9 — PR 별 빌드 비교 git 메타데이터
  - `db/Schemas.kt` Builds: `gitBranch`, `gitSha` 컬럼 추가 (nullable).
  - `BuildRow` + `BuildRepository.create()` + `toRow()` 갱신.
  - `BuildService.collectGitMetadata()`: `git symbolic-ref --short HEAD` +
    `git rev-parse HEAD` 호출. 3초 timeout — git hung 방지.
  - graceful: .git 없는 프로젝트 / git CLI 미설치 / detached HEAD → null fallback.
  - `BuildRepository.previousSuccessfulInBranch()`: 같은 branch 직전 SUCCESS 빌드.
  - `BuildDto`: gitBranch / gitSha emit.

#11 — Slack/Discord/Telegram webhook
  - 이미 `WebhookNotifier.trySendTelegram()` 등 모두 존재 (v0.27.0+).
  - 추가 작업 불필요 — 사용자가 `/settings/webhook` 에서 URL/토큰 입력.

### 변경 파일

- `server/db/Schemas.kt` — Builds +2 컬럼, NotificationEvents 신규 + AllTables 등록.
- `server/repo/BuildRepository.kt` — create 시그너처 + previousSuccessfulInBranch.
- `server/build/BuildService.kt` — collectGitMetadata + 두 enqueue 함수 적용.
- `server/build/BuildRoutes.kt` — BuildDto gitBranch/gitSha emit.
- `server/notify/NotificationService.kt` — in-memory → PG.
- `shared/dto/Dtos.kt` BuildDto — gitBranch / gitSha 필드.
- `config/server.yml` — 0.71.0.

### 마이그레이션

- 자동 — `SchemaUtils.createMissingTablesAndColumns(*AllTables)` 가 Builds 의 신규
  컬럼과 NotificationEvents 테이블을 생성.
- 이전 in-memory notification 은 재시작 시 reset — 본 버전 부터 영속화.

### Android catch-up (v0.7.31)

- BuildDto.gitBranch / gitSha 흡수 (이미 shared/Dtos.kt 와 wire 호환).
- BuildScreen list/detail 에서 branch / sha 표시 (선택).

## [0.70.0] - 2026-05-25 — Phase 49: Server quick wins (#1, #10, #12, #14)

### Added / Fixed

#1 — **Logs JSON 실제 검색** (server v0.67.0 의 stub 해소).
  - `admin/LogSearchService.kt` 신규: SSR `LogSearchRoutes` 의 private search() 추출.
  - JSON `/api/logs` (JsonAdminRoutes) 가 실제 결과 반환.
  - Module/ServerMain wiring + ServerContext.logSearchService.

#10 — **Memo / star export 통합** (ConversationExportService.TurnRecord 확장).
  - export 의 `TurnRecord` 에 `userMemo`, `starred` 필드 추가.
  - import 시 insert 후 별도 `setMemo()` / `setStarred()` 호출.
  - sessionId 단위 idempotency 유지 (기존 세션은 skip).

#12 — **Claude 사용량 / 디스크 임계치 → Android polling 통합**.
  - `Notifiers.claudeUsageWarn()` 와 `diskUsageWarn()` 안에서
    `NotificationService.emit()` 호출 → Android polling 알림.
  - email/webhook/webPush 와 fan-out 함께 진행.

#14 — **APK 시그너처 on-demand verify**.
  - `artifacts/ApkVerifier.kt` 신규: apksigner (Android SDK build-tools) 호출.
  - 신규 endpoint `GET /api/projects/{id}/artifacts/{aid}/verify` (Bearer).
  - 응답: verified/v1/v2/v3/signers[]/warnings/errors/durationMs.
  - DB 영속 안 함 — 사용자 클릭 시점에만 실행 (1-5초).
  - `ApiPath.artifactVerify(projectId, artifactId)` 상수 추가.

### 변경 파일

- `server/admin/LogSearchService.kt` 신규.
- `server/admin/LogSearchRoutes.kt` — search() 제거 + service 호출.
- `server/admin/JsonAdminRoutes.kt` — log search stub → 실구현.
- `server/notify/Notifiers.kt` — usage/disk warn 에 NotificationService 통합.
- `server/artifacts/ApkVerifier.kt` 신규.
- `server/artifacts/ArtifactRoutes.kt` — verify endpoint 추가.
- `server/claude/ConversationExportService.kt` — userMemo/starred export+import.
- `shared/ApiPath.kt` — artifactVerify() 추가.
- `server/Module.kt`, `server/ServerMain.kt` — 신규 service wiring.
- `config/server.yml` — 0.70.0.

### Android catch-up 후속 (v0.7.27)

- `/api/logs` 가 실제 동작 — AdminScreen 의 log search 활성화 가능.
- `/api/artifacts/.../verify` — ArtifactScreen 에 "Verify signature" 버튼.
- 알림: Claude 사용량 / 디스크 임계치 도달 시 Android system notification 자동.

## [0.69.1] - 2026-05-25 — CSS baseline 강화 + 모바일 responsive

### Fixed

사용자 신고 — 일부 SSR 페이지의 input / textarea / select / button / 표 등이
크기가 깨지거나 styling 누락. admin.css 의 base style 강화로 일괄 회복.

#### Form element baseline (label 안/밖 무관, 모든 type)

- `input[type=text|password|number|email|url|search|tel|date|datetime-local|time]`,
  타입 없는 input, `textarea`, `select` 모두 동일 padding/border/색상.
  이전엔 `label input:not([type=checkbox])` 만 styled — label 밖 input 깨졌음.
- `textarea` 별도: monospace + min-height 80 + resize:vertical.
- `select`: appearance:none + 사용자 정의 chevron + cursor:pointer.
- `input[type=file]`: 자체 background + `::file-selector-button` styling.
- `input[type=checkbox|radio]`: width:auto + accent-color:var(--primary).
- placeholder 색상: `var(--text-dim)` opacity 0.6.
- disabled 상태: opacity 0.5 + cursor:not-allowed.

#### Button baseline

- 평범한 `<button>` (variant 미지정): bg-card + border + hover effect.
  이전엔 styling 전혀 없어 browser default 보임.
- `input[type=submit]`: primary button 과 동일 처리.
- `:focus-visible` 키보드 outline 추가 (접근성).

#### Heading / paragraph / pre / dl / fieldset / table 일반화

- h1/h2/h3/h4 글로벌 baseline (이전엔 `header h1` 만).
- `p`, `ul`, `ol`, `dl/dt/dd`, `hr` 일관 spacing.
- `pre`: monospace + bg + border + word-break (raw stack trace 등 안 넘침).
- `fieldset/legend`: `.settings-form` 외에서도 동일 styling.
- generic `table`: `table.devices` 와 같은 외형 (모든 데이터 표).
- 모바일 < 480px: table `display:block + overflow-x:auto` fallback.

#### Banner 확장

- `.warn-banner`, `.info-banner` 신규 (`.error` / `.ok-banner` 와 짝).

#### Responsive 강화 (3-tier breakpoint)

- 1024px↓: 사이드바 220px → 180px, content padding 32 → 24.
- 768px↓: 사이드바 → horizontal nav, logout 우측 정렬, settings-tabs
  horizontal scroll, scrollbar 숨김.
- 480px↓: grid 1 column, card/auth-card padding 축소, h1 20px, primary
  버튼 더 크게 (terminal touch 친화), table 가로 스크롤 fallback.

#### 신규 v0.69.0 settings-tabs CSS 정리

- 이전엔 inline `<style>` 로 emit. v0.69.1 부터 admin.css 의 `.settings-tabs`
  로 이동 — CSP 친화 + cache 효율 + 중복 emit 제거.

### 변경 파일

- `server/src/main/resources/static/admin/admin.css` — 약 +110 line.
- `server/admin/SettingsNav.kt` — inline style 제거.
- `config/server.yml` — version 0.69.1.

### 호환성

- 100% additive 변경 (기존 selector 모두 유지). 기존 페이지의 어떤 styling
  도 회귀 없음.
- 도커 이미지 hash 만 바뀜 — 마이그레이션 불요.

## [0.69.0] - 2026-05-25 — Phase 48: Admin SSR UI 리뉴얼 (24개 → 6개 + 8 탭)

### Changed

사용자 신고 — admin SSR 사이드바가 24개 메뉴 평탄 나열로 중구난방. 어디서 무엇을
찾아야 할지 헷갈림. 리뉴얼:

**Top-level 6개로 압축** (24 → 6):
  - 대시보드 (`/`)
  - 프로젝트 (`/projects`)
  - Chat (`/chat`)
  - 도구 (`/tools` — 신규 hub)
  - 설정 (`/settings` — 탭 통합)
  - (우측 사용자명 + 로그아웃)

**설정 페이지 8개 탭으로 통합** — 모든 sub-page 가 같은 탭바를 자동으로 받음
([AdminTemplates.shell] 가 currentPath 보고 auto-inject):
  - 일반        : `/settings`
  - 보안        : `/password`, `/2fa`, `/webauthn`, `/devices`, `/settings/cors`
  - 알림        : `/settings/email`, `/settings/webhook`, `/settings/push`
  - 빌드환경    : `/env-setup`, `/env-setup/claude-login`, `/env-setup/mcp`,
                   `/settings/git-integrations`, `/settings/cache`
  - 프롬프트 & 에이전트: `/prompts`, `/agents`
  - 백업        : `/backup`
  - 모니터링    : `/usage`, `/audit`
  - 사용자      : `/users`

**`/tools` 신규 hub** (admin/ToolsRoutes.kt) — 분산돼 있던 보조 도구들 진입점:
  - Multi-console / Emulator / 코드 검색 / 빌드 로그 검색 / 대화 검색
  - 단순 grid 카드 UI — 클릭 시 기존 페이지로 이동.

### 호환성

- **기존 모든 URL 그대로 유지** — 외부 link / 북마크 / SSR fetch 변경 없음.
- nav 표시만 정리. 깊은 URL 구조 변경 없음.
- Android client 무영향 (모두 admin SSR — Android 가 안 봄).
- 각 sub-page 의 body 도 그대로 — top 의 탭바만 자동 추가.

### 구현 메모

- `AdminTemplates.kt` 가 매우 큰 raw-string-heavy 파일이라 Kotlin K2 parser 가
  fragile (brace 매칭 에러 frequent). 신규 탭 로직을 별도 파일
  `admin/SettingsNav.kt` 로 분리해 영향 격리. AdminTemplates 의 변경은 nav 6-link
  교체 + maybeTabs inject 2 line 만.

### 변경 파일

- `server/admin/SettingsNav.kt` (신규) — topLevelOf + tabBar.
- `server/admin/ToolsRoutes.kt` (신규) — /tools hub.
- `server/admin/AdminTemplates.kt` — navHtml 6-link 교체, shell() 에 maybeTabs inject.
- `server/Module.kt` — toolsRoutes 등록.
- `config/server.yml` — version 0.69.0.

## [0.68.1] - 2026-05-25 — Hotfix: Docker runtime JRE → JDK (Android 빌드 회복)

### Fixed

사용자 신고 — `./gradlew :app:assembleDebug --no-daemon --stacktrace` 시:

```
Toolchain installation '/opt/java/openjdk' does not provide the required
capabilities: [JAVA_COMPILER]
```

**원인**: `docker/Dockerfile` 의 runtime stage 가 v0.38.0 부터
`eclipse-temurin:17-jre-resolute` (JRE only) 사용. 컨테이너 안에서 Android
프로젝트를 빌드하면 Gradle Java toolchain 이 `/opt/java/openjdk` 를 잡는데
javac 가 없어 실패.

이전 동작이 정상이었던 것은 짐작컨대:
  - Gradle toolchain auto-provisioning (foojay-resolver) fallback
  - 또는 사용자가 별도 JDK 설치
하지만 v0.68.0 컨테이너에선 일관되게 재현됨.

**수정**: runtime stage 도 `eclipse-temurin:17-jdk-resolute` (JDK) 로 통일.
이미지 약 130MB 증가하지만, 단일 사용자 LAN 도구 + Android 빌드 안정성이
이미지 크기보다 중요. builder stage 가 이미 jdk-resolute 라 BuildKit 캐시
hit — 추가 다운로드 없음.

### 마이그레이션

```bash
docker compose pull
docker compose up -d
```

이미지 hash 만 바뀜 — workspace / DB / 볼륨 무영향. 마이그레이션 불요.

## [0.68.0] - 2026-05-25 — Phase 47: Polling-based notification (Android Group C)

### Wire change — Yes (신규 3 endpoint, 모든 인증 사용자)

Android Group C (FCM push) 미구현 해소. FCM 은 Firebase 프로젝트 + Admin SDK
서버 키 + google-services.json 등 외부 인프라가 필요해 본 단일 사용자 LAN 도구에
부적합 → **polling-based notification** 으로 minimum viable 구현. FCM 통합은 stub
endpoint 만 추가 (Firebase 환경 변수 설정 시 활성).

#### 신규 endpoint (Bearer, 모든 사용자)

- `GET  /api/notifications`         → NotificationsResponseDto (events + unreadTotal)
- `POST /api/notifications/ack`     → 204 (NotificationAckRequestDto: ids[])
- `POST /api/notifications/fcm-token` → 204 (FCM stub — Firebase 미설정 시 로그만)

#### NotificationService

- `notify/NotificationService.kt` 신규 — in-memory queue per user.
  - `emit(kind, title, body, deepLink, projectId, userIds)` 가 fan-out.
  - `list(userId)` / `count(userId)` / `ack(userId, ids)` API.
  - Retention: per-user 500 events max (FIFO prune).
  - DB persistence 는 다음 cycle (v0.69.0+) — process restart 시 잃음.
  - 편의: `emitBuildSuccess()` / `emitBuildFailed()` 포함.

#### Notifiers facade 통합

- `notify/Notifiers.kt` 확장 — `notifications: NotificationService?` +
  `userIdsProvider: (() -> List<String?>)?` 추가.
- `buildResult()` 호출 시 자동으로 모든 user (admin/member/viewer) 에게 fan-out.
- ServerMain.kt 가 `adminUserRepo.listAll()` 을 provider 로 전달.

#### Notifications wire 모양

- `NotificationEventDto` — id / ts / kind / title / body / deepLink / projectId / read.
- `NotificationKind` — build.success / build.failed / claude.turn_done / usage.threshold / system.
- 알림 종류는 forward-compat — 클라이언트가 모르는 kind 도 표시 (UI 단순 fallback).

#### 변경 파일

- `shared/dto/NotificationDtos.kt` 신규.
- `shared/ApiPath.kt` — NOTIFICATIONS / NOTIFICATIONS_ACK / FCM_TOKEN_REGISTER.
- `server/notify/NotificationService.kt` 신규.
- `server/notify/NotificationRoutes.kt` 신규.
- `server/notify/Notifiers.kt` — notifications + userIdsProvider 추가.
- `server/Module.kt` — notificationRoutes 등록 + ServerContext field 추가.
- `server/ServerMain.kt` — NotificationService 생성 + Notifiers wiring.
- `server/src/main/resources/config/server.yml` — version 0.68.0.

### 호환성

- 모든 인증 사용자 (admin/member/viewer) 호출 가능. viewer 도 정보용으로 받음.
- DB 없음 — process restart 시 queue 초기화. 다음 build/turn 시 재 emit.
- FCM stub: Firebase 환경 변수 (`FCM_PROJECT_ID` + service account JSON path) 가
  설정되면 실제 push 발송 (다음 cycle 에서 구현). 현재는 로그만 + 200 OK.

### Android catch-up 요망 (v0.7.26 별도 push)

- shared dto (`NotificationDtos.kt`) + ApiPath 3개 동기.
- NotificationApi (client).
- NotificationPollingWorker (WorkManager 15분 periodic).
- System notification 표시 (NotificationManager + channel 등록).
- Manifest: POST_NOTIFICATIONS permission (Android 13+) + receiver.

## [0.67.0] - 2026-05-25 — Phase 46: Admin/운영 JSON API 묶음 (Group B)

### Wire change — Yes (admin JSON 신규 다수)

Android 정밀 매칭 분석의 Group B (admin/운영 미구현 5건) 일괄 노출. 모두
`requireApiAdmin()` 권한 — viewer/member 거부. SSR 라우터는 그대로 유지
(admin 브라우저 UI 호환).

#### B1. Multi-user JSON API
  - `GET    /api/users`               → UsersResponseDto
  - `POST   /api/users`               → AdminUserDto (201, password ≥ 6자, role
                                         admin/member/viewer)
  - `POST   /api/users/{userId}/role` → 204 (마지막 admin 강등 차단)
  - `DELETE /api/users/{userId}`      → 204 (마지막 admin 삭제 차단)

#### B2. Build automation JSON API (project-scoped)
  - `GET    /api/projects/{id}/automation/schedules` → BuildSchedulesResponseDto
  - `POST   /api/projects/{id}/automation/schedules` → BuildScheduleDto (201)
  - `POST   /api/projects/{id}/automation/schedules/{sid}/toggle` → 204
  - `DELETE /api/projects/{id}/automation/schedules/{sid}` → 204

#### B3. Backup JSON API
  - `GET    /api/backup`                    → BackupListResponseDto
                                              (manualFileName + autoBackups[])
  - `GET    /api/backup/download`           → application/gzip (manual tar.gz)
  - `GET    /api/backup/auto/{fileName}`    → application/gzip (auto file)
  - `DELETE /api/backup/auto/{fileName}`    → 204
  - `POST   /api/backup/run-now`            → BackupRunNowResponseDto

#### B4. Audit log JSON API
  - `GET    /api/audit?action=&result=&userId=&from=&to=&page=&limit=`
    → AuditLogPageDto (entries[] + nextCursor + total). cursor = page string.

#### B5. Admin info JSON API (read-only + 일부 mutation)
  - `GET    /api/logs?q=&projectId=`               → LogSearchResponseDto
    (현재 TODO — SSR 측 search() 내부 함수가 visibility 막혀있어 다음 cycle
    에서 LogSearchService 추출 후 reuse. v0.67.0 응답은 empty list.)
  - `GET    /api/code-search?q=&projectId=`        → CodeSearchResponseDto
  - `GET    /api/projects/{id}/deps`               → DependencyAuditResponseDto
    (ProjectRow.moduleName 사용 → `./gradlew :module:dependencies` 90s 타임아웃)
  - `GET    /api/projects/{id}/stats`              → CodeStatsResponseDto
  - `GET    /api/projects/{id}/env-files`          → EnvFilesResponseDto
    (화이트리스트: local.properties / gradle.properties / .env / .env.local)
  - `POST   /api/projects/{id}/env-files`          → 204 (rel 화이트리스트 검증)
  - `GET    /api/projects/{id}/wrapper`            → GradleWrapperInfoDto
  - `POST   /api/projects/{id}/wrapper`            → 204 (newVersion + dist bin/all)

#### 변경 파일

- `shared/dto/AdminBatchDtos.kt` 신규 — Group B 모든 DTO 묶음 (도메인별 분리 대신
  운영 편의 위해 single file).
- `shared/ApiPath.kt` — Group B 신규 const + fun 13개.
- `server/admin/JsonAdminRoutes.kt` 신규 — 5 그룹 모두 등록.
- `server/Module.kt` — `jsonAdminRoutes` 등록 + import.
- `server/src/main/resources/config/server.yml` — version 0.67.0.

### 호환성

- 모두 admin 권한 (`requireApiAdmin`). non-admin 토큰 호출 시 403 `admin_only`.
- SSR 라우터는 그대로 유지 — admin 브라우저 UI 무영향.
- `ApiPath` SSOT 정책 (v0.64.0+) 준수 — 모든 신규 endpoint 가 ApiPath 상수로 등록.

### Android catch-up 요망 (v0.7.25 별도 push)

- shared dto (`AdminBatchDtos.kt`) + ApiPath 13개 신규 동기.
- ApiService 메소드 (B1: usersList/create/setRole/delete, B2: schedule CRUD,
  B3: backup list/download URL/auto download/delete/runNow, B4: auditList,
  B5: 각 endpoint).
- AdminScreen — admin role 전용 single screen 에 5개 섹션 묶음.

## [0.66.0] - 2026-05-25 — Phase 45: Android 미구현 기능 회수 — JSON API 확장

### Wire change — Yes (신규 endpoint 3건 + DTO SSOT 회수)

Android v0.7.22 vs 서버 v0.65.0 정밀 매칭 분석에서 식별된 "서버에 있지만 Android
미구현" 기능들 중 우선순위 높은 5건 일괄 노출. Android v0.7.24 가 본 wire 를 흡수
하여 git commit/push, MCP 파일 업로드, sub-agent 강제 재시작, project templates
dropdown, 빌드 단건 polling 모두 사용 가능.

#### A1. Git commit (+ optional push) — DTO SSOT 회수

`server/git/GitRoutes.kt` 안에 정의돼 있던 `GitCommitRequestDto` /
`GitCommitResponseDto` 를 `shared/dto/GitDtos.kt` 로 이동. server 측은 import 만
변경, wire shape 동일. Android v0.7.24+ 가 동일 DTO 사용.

기능 자체는 v0.18.0 부터 endpoint 노출됨 (`POST /api/projects/{id}/git/commit`).
Android 가 그동안 git status/diff/log 만 read-only 호출 → commit/push UI 추가
가능.

#### A2. MCP secret 파일 업로드 — wire 노출만 필요 (기능은 v0.11.0 부터)

`POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` 는 v0.11.0 부터 endpoint
노출됐고 `ApiPath.mcpUploadFile()` 도 정의돼 있었으나, Android client 가 호출
하지 않아 `McpConfigFieldDto.isFile=true` 인 항목 (Play Service Account JSON,
TestFlight `.p8` 등) 을 모바일에서 설치할 수 없었음. Android v0.7.24 가 SAF +
multipart 흐름 추가로 해소 (서버는 변경 없음).

#### A3. 빌드 단건 polling — wire 노출만 필요

`GET /api/projects/{projectId}/builds/{buildId}` 도 v0.10.0 부터 노출됐으나
Android 가 builds list 만 호출 → WebSocket 끊김 시 빌드 진행 동기화 불완전.
Android v0.7.24 가 BuildDetail 화면에서 polling 사용. 서버 변경 없음.

#### A4. Sub-agent 강제 새 세션 (JSON variant 신규)

기존 SSR `/projects/{id}/agents/{agent}/new` (redirect 응답) 는 그대로 유지.
JSON variant `POST /api/projects/{id}/agents/{agent}/console/new` 신규 — main
console 의 `/api/projects/{projectId}/claude/console/new` 와 대칭.

`SubAgentRoutes.kt` 에 endpoint 추가. dual-auth helper `authorizeAgentJson` 재사용
(v0.65.0 추가). agent 등록 안 됨이면 404, write 권한 없으면 403, 통과 후 `startNew(id, agent)`.

응답: `{"ok": true}` (shared `AgentPromptAcceptedDto` 와 동형).

#### A5. 프로젝트 템플릿 카탈로그 — 신규 endpoint

`ProjectTemplate` (v0.18.0 도입 — `empty` / `compose-basic` / `compose-mvvm-hilt` /
`compose-mvvm-room` / `wear-os` / `android-tv`) 카탈로그가 admin SSR 신규 프로젝트
폼에서만 조회 가능했음. Android v0.7.24+ 가 신규 프로젝트 dialog 에서 dropdown
으로 노출하기 위해 JSON endpoint 추가.

신규 endpoint `GET /api/project-templates` (Bearer, viewer 도 허용).
신규 DTO `ProjectTemplateDto` / `ProjectTemplatesResponseDto` (shared/dto).
신규 라우터 `projects/ProjectTemplateRoutes.kt`.
`ApiPath.PROJECT_TEMPLATES` 상수 추가.

### 변경 파일 (서버)

- `shared/src/main/kotlin/.../shared/dto/GitDtos.kt` (신규) — A1.
- `shared/src/main/kotlin/.../shared/dto/ProjectTemplateDtos.kt` (신규) — A5.
- `shared/src/main/kotlin/.../shared/ApiPath.kt` — `PROJECT_TEMPLATES`,
  `agentConsoleNew()` 추가.
- `server/.../git/GitRoutes.kt` — DTO local 정의 제거 + shared import.
- `server/.../claude/SubAgentRoutes.kt` — `/api/.../console/new` JSON endpoint 추가.
- `server/.../projects/ProjectTemplateRoutes.kt` (신규) — A5 라우터.
- `server/.../Module.kt` — projectTemplateRoutes 등록.
- `server/src/main/resources/config/server.yml` — version 0.66.0.

### Android catch-up 요망 (v0.7.24 별도 push)

- `shared/dto/GitDtos.kt`, `shared/dto/ProjectTemplateDtos.kt` 동기.
- `ApiPath`: `PROJECT_TEMPLATES`, `agentConsoleNew()` 추가.
- `ApiService`: `gitCommit()`, `uploadMcpConfigFile()`, `getBuild()`,
  `agentNewSession()`, `projectTemplates()` 추가.
- UI: GitCommitDialog, MCP file picker (SAF + acceptMime), Build polling,
  Sub-agent restart 버튼, NewProjectDialog templates dropdown.

## [0.65.0] - 2026-05-25 — Phase 44: Sub-agent JSON dual-auth + `/api/projects/{id}/zip`

### Wire change — Yes (Bearer 호환만 확대, 응답 shape 변경 없음)

전체 API endpoint × Android v0.7.22 호출의 정밀 매칭 분석 결과 발견된 **6건의
인증 mismatch** 중 서버 측 책임 부분 (4건) 일괄 해소. 응답 shape 은 동일하므로
Android 측은 단순 경로 전환만 (v0.7.23 별도 push).

#### 인증 mismatch 분석 — Android Bearer 토큰으로 호출 시 cookie 만 받아 실패하던 endpoint

매칭 분석으로 확인된 4건:

1. `POST /api/projects/{id}/agents/{agent}/console/prompt` (sub-agent prompt)
2. `POST /api/projects/{id}/agents/{agent}/console/cancel` (sub-agent cancel)
3. `GET  /api/projects/{id}/agents/active` (active sub-agent 리스트)
4. `GET  /projects/{id}/zip` (프로젝트 소스 zip 다운로드 — SSR 경로)

(1)~(3) 은 v0.44.0 sub-agent 도입 시 admin SSR 흐름과 분리되지 않아 cookie 인증만
받았고, v0.7.21 Android catch-up 으로 사용 시작했지만 서버가 redirect 응답을
주어 JSON 파싱이 실패했음. (4) 는 SSR 전용 경로라 Bearer 토큰으로 호출 시 동작
불가.

#### 수정 (1)~(3): sub-agent JSON endpoint dual-auth

`SubAgentRoutes.kt` 에 `authorizeAgentJson` helper 추가 — `HistoryRoutes.kt` 의
`authorizeMemoStar` 와 동일 패턴:

  - `Authorization: Bearer <token>` 헤더가 있으면 token hash 로 device 검증 +
    Project ACL 확인. 통과 시 (userId, isAdmin) 반환. 토큰 invalid 면 401.
  - Bearer 없으면 기존 cookie 세션 (`requireSessionOrRedirect`) fallback —
    SSR 의 fetch 호출 (admin agent console page) 호환 유지.
  - Legacy single-user 모드 (`device.userId == null`) 는 admin 으로 간주 — ACL skip.
  - `requireWrite=true` 인 endpoint 는 cookie 경로에서 viewer 차단.

`subAgentRoutes(...)` 시그니처에 `tokens: TokenService, deviceRepo: DeviceRepository`
추가. `Module.kt` 등록 호출에 `ctx.tokens, ctx.deviceRepo` 전달.

#### 수정 (4): `/api/projects/{id}/zip` JSON variant 신규

`projects/JsonProjectZipRoutes.kt` 신규 — Bearer 토큰 인증 + Project ACL 검증 +
기존 SSR 과 동일한 zip 스트리밍 로직 (`ProjectArchiver.streamZip` 재사용) +
동일 `Content-Disposition: attachment, filename="<projectId>-source-<yyyyMMdd-HHmm>.zip"`.

기존 SSR `/projects/{id}/zip` (cookie 인증) 은 그대로 유지. admin UI 호환.

`ApiPath.projectZipJson(projectId)` 추가 — Android v0.7.23+ 가 사용.

### 정밀 매칭 분석 — 비-수정 항목 (정합 확인)

본 cycle 의 매칭 분석은 서버 174개 endpoint × Android 80개 호출 (REST 76 + WS 4)
의 1:1 매핑을 수행. 위 4건 외에는 모두 정합:

- WsFrame 직렬화: 100% 동일 (v0.64.0 분석에서 확인).
- ApiPath SSOT: Android shared/ApiPath.kt 가 모든 호출에 ApiPath 상수 사용 (hardcoded 0건).
- 인증 패턴: ConsoleRoutes / BuildRoutes / FileRoutes / GitRoutes / ProjectRoutes /
  SymbolRoutes / EnvRoutes / EnvSetupApiRoutes / ArtifactRoutes / AgentRoutes (catalog) /
  PromptRoutes / JsonHistoryRoutes / JsonUsageRoutes / ProjectActionRoutes 모두 Bearer ✅.
- `HistoryRoutes.memo/star` 는 v0.64.0 에서 이미 Bearer dual-auth ✅.
- 응답 shape: PromptAcceptedDto / ClaudeStatusDto / PromptSuggestionsResponseDto /
  HistoryPageDto / UsageSummaryDto / EnvSetupTaskDto 등 양측 동일 ✅.
- query params: symbols 의 `name`, history 의 `agent`/`starred`/`before`/`sessionId`,
  prompt-suggestions 의 `prefix`/`limit` 모두 매칭 ✅.

### Android catch-up 요망 사항 (v0.7.23 별도 push)

다음은 서버는 endpoint 준비 완료 → Android 가 `/api/` JSON variant 로 전환해야 함:

- `projectHistoryExportUrl()` → `ApiPath.projectHistoryExportJson(projectId)` 사용.
- `projectHistoryImport()` → `ApiPath.projectHistoryImportJson(projectId)` 사용.
- `projectZipUrl()` → `ApiPath.projectZipJson(projectId)` 사용 (본 cycle 신규).

### 변경 파일

- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/claude/SubAgentRoutes.kt` —
  dual-auth helper + 3 JSON endpoint 마이그.
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/projects/JsonProjectZipRoutes.kt` (신규).
- `server/src/main/kotlin/com/siamakerlab/vibecoder/server/Module.kt` —
  subAgentRoutes 인자 추가 + jsonProjectZipRoutes 등록.
- `shared/src/main/kotlin/com/siamakerlab/vibecoder/shared/ApiPath.kt` —
  `projectZipJson()` 추가.
- `server/src/main/resources/config/server.yml` — version 0.65.0.

## [0.64.0] - 2026-05-25 — Phase 43: Android client wire 정렬 + JSON API 분리

### Wire change — Yes

vibe-coder-android v0.7.18 호환성 정밀 검토 결과를 일괄 해소. shared/ 모듈에
신규 8 파일 + 기존 Dtos.kt 의 3 DTO alias 추가. **vibe-coder-android v0.7.19+
가 본 wire 를 흡수하면 그동안 baseline 에서 깨져 있던 history/chat/usage 화면이
정상 동작**.

#### Wire shape align (`shared/dto/Dtos.kt`)

- `ClaudeCredentialsUploadResponseDto` — `path: String` + `expiresAtIso: String?`
  alias 추가 (Android 가 String 기대). 기존 `targetPath` / `expiresAt: Long` 은
  default 값으로 양쪽 emit 유지 (SSR/구버전 client 호환).
- `GitTokenViewDto` — `token: String = tokenMasked` alias 추가 (Android 가
  `token` 필드명 기대).
- `EnvSetupApiRoutes.kt` — `McpEntryDto.status` 와 `ComponentStateDto.status`
  를 `lowercase()` 로 emit (Android `McpEntryStatus`/`ComponentStatus` 상수가
  모두 소문자). SSR templates 는 enum 자체로 비교하므로 영향 없음.
- `ApiErrorCode` object 신규 — TOTP_REQUIRED / INVALID_TOTP / VIEWER_READONLY /
  ADMIN_ONLY / PROJECT_FORBIDDEN / **RATE_LIMITED** / MANUAL_INSTALL_ONLY 등
  표준 코드 묶음. Android shared/ 와 동일.

#### shared/ DTO 신규 (`shared/dto/*.kt`)

기존 v0.16~v0.54 의 wire 가 Android shared/ 에만 단독 정의돼 있던 것을 정식 SSOT 회수:

- `HistoryDtos.kt` — `HistoryTurnDto` (+ `userMemo`/`starred`/`agentName`),
  `HistoryPageDto`, `HistoryMemoUpdateRequestDto`, `HistoryMutationAckDto`,
  `UsageReportDto` (v0.63.0 role="usage" content 스키마),
  `UsageSummaryDto`/`UsageDailyDto` (`/api/usage` 응답), `HistoryTurnRole`.
- `AgentDtos.kt` — `AgentInfoDto`, `AgentsCatalogResponseDto`,
  `ActiveAgentsResponseDto`, `AgentPromptAcceptedDto`.
- `SymbolDtos.kt` — `SymbolHitDto`, `SymbolsResponseDto`.
- `PromptSuggestionsDtos.kt` — `PromptSuggestionsResponseDto`.
- `HistoryAgentFilter.kt` — `MAIN`/`ALL`/`@<name>` 필터 값 상수.
- `HistorySearchDtos.kt` — `HistorySearchHitDto`, `HistorySearchResponseDto`,
  `HistoryImportResponseDto`.

> **주의 — `HistoryTurnDto.id` 타입**: Android v0.7.18 까지 `Long` 으로 추정
> 정의했으나 실제 서버 `ConversationTurnRow.id` 는 `String` (ULID-like). 본
> 신규 wire 에서 String 으로 정렬 — Android v0.7.19 catch-up 시 `Long → String`
> 변경 필수.

#### `shared/ApiPath.kt` SSOT 복원

v0.16~v0.63 의 endpoint 14개 (기존엔 라우터의 hardcoded path 로만 존재) +
v0.64.0 신규 5개를 정식 등록. `pathSeg` URL encoding helper 도 추가.

- 회수: `projectHistory`, `CHAT_HISTORY`, `SCRATCH_PROJECT_ID`, `projectZip`,
  `promptSuggestions`, `projectHistoryExport`/`Import`, `HISTORY_SEARCH`,
  `AGENTS_CATALOG`, `agentsActive`, `agentConsolePrompt`/`Cancel`,
  `projectSymbols`, `wsAgentConsoleLogs`.
- 신규 v0.64.0: `projectHistoryMemo` / `projectHistoryStar` (Phase 40 의 정식
  등록), `HISTORY_SEARCH_JSON` / `USAGE_JSON` / `projectHistoryExportJson` /
  `projectHistoryImportJson` (G1).

### Added — JSON API 신규 6개 (G1, Bearer 토큰 인증)

기존 SSR 라우트는 그대로 유지하고, Android 같은 Bearer 토큰 client 가 호출 가능한
JSON variant 를 별도 path 로 추가. 모두 `authenticate(AUTH_BEARER)` 블록 안에 등록.

| Endpoint | 메소드 | 응답 |
|---|---|---|
| `/api/projects/{id}/history` | GET | `HistoryPageDto` |
| `/api/chat/history` | GET | `HistoryPageDto` (scratch project) |
| `/api/history/search` | GET | `HistorySearchResponseDto` (admin only) |
| `/api/projects/{id}/history/export` | GET | application/json envelope |
| `/api/projects/{id}/history/import` | POST multipart | `HistoryImportResponseDto` |
| `/api/usage` | GET | `UsageSummaryDto` |

Query 파라미터: `limit` (max 500), `page`/`before` (정수, 0-base), `sessionId`,
`agent` (HistoryAgentFilter: `main`/`all`/`@<name>`), `starred=true|false`.
응답의 `nextCursor` 는 다음 page 번호 또는 null.

Project ACL (v0.49+) 통과 — 자기 ACL 에 없는 프로젝트의 history endpoint 호출
시 `403 project_forbidden`.

신규 라우터:
- `server/.../claude/JsonHistoryRoutes.kt` (history/chat/search/export/import)
- `server/.../claude/JsonUsageRoutes.kt` (usage 합산)

### Changed — memo/star endpoint dual-auth (G2)

`/api/projects/{id}/history/{turnId}/memo|star` (Phase 40, v0.61.0) 가 SSR
session + `?_csrf=` query 만 인증하던 것을 dual-auth 로 풀음:

- `Authorization: Bearer <token>` 헤더가 있으면 → token hash 로 device 검증 후
  CSRF skip 하고 진행.
- 없으면 → 기존 SSR cookie 세션 + `?_csrf=` 검증 (관리자 브라우저 UI).

같은 토큰이 두 경로 (헤더 / cookie) 어디로 와도 인증되지만, **cookie 만 있고
헤더가 없으면 CSRF 필수** (SSR fetch 흐름으로 간주).

기존 SSR `/projects/{id}/history` 페이지의 ☆ 토글 / 메모 인라인 편집은 변화 없음.

### Notes — 라우터 hardcoded path 일부 잔존

v0.64.0 에서는 신규 라우터만 `ApiPath` 참조로 작성. 기존 SSR 라우터
(`HistoryRoutes`/`ConsoleRoutes`/`SymbolRoutes`/`BackupRoutes`/`SubAgentRoutes`/
`GlobalHistorySearchRoutes`/`UsageRoutes`/`AgentRoutes`) 의 hardcoded path 치환은
별도 cycle (v0.64.x) 에서 사용성 영향 없이 진행 예정. CLAUDE.md §8.A 의
"신규 endpoint 는 반드시 ApiPath 에 먼저 등록" 룰은 본 release 부터 강제.

### Migration — vibe-coder-android

Android v0.7.18 → v0.7.19 catch-up 작업이 별도 진행되어야 본 변경의 사용자
가시 효과 (history 화면 정상 작동, memo+star UI, usage 카드) 가 노출됨. 작업 항목은
프로젝트 CLAUDE.md `§9.M` 에 정리.

## [0.63.0] - 2026-05-24 — Phase 42: Anthropic Cache 구조화

### Added

v0.47.0 의 raw `/status` 노출에서 한 단계 발전. Claude stream-json 의
`usage` 객체를 직접 파싱해서 prompt cache 통계를 영구 적재 + `/usage`
페이지에 구조화된 카드.

- **`ClaudeEvent.UsageReport`** — 새 sealed subtype. 4개 필드 모두
  nullable: `inputTokens` / `outputTokens` / `cacheReadInputTokens` /
  `cacheCreationInputTokens`. `totalInputTokens` 합계 헬퍼.
- **`ClaudeStreamParser` 확장** — assistant message 의 `message.usage`
  와 result frame 의 top-level `usage` 두 곳에서 파싱. 모든 필드가
  null 이면 emit skip.
- **`ConsoleSessionManager` / `SubAgentSessionManager`** — `UsageReport`
  를 `WsFrame.ConsoleSystem(code="usage", message="input … · output … ·
  cache-read … · cache-create …")` 로 노출. 콘솔에서 turn 종료 시 작은
  system notice 로 표시.
- **`ConversationHistoryService`** — `UsageReport` 를 `role="usage"`
  row 로 영구 적재. `content` 는 `{"input":…,"output":…,"cacheRead":…,
  "cacheCreate":…}` JSON.
- **`ConversationTurnRepository.usageSummary(projectId)`** — `role=
  "usage"` row 의 content JSON 을 regex 로 walk 해 누적 합산.
  `UsageSummary(turns, input, output, cacheRead, cacheCreate)` + 파생
  `totalInput`, `cacheHitRate` (0..100 Double).
- **`/usage` 페이지 카드**:
  - 전체 합산 6 메트릭 (turns, input, output, cache-read, cache-create,
    hit rate)
  - 프로젝트별 표 (cacheRead+input 합 기준 정렬, hit rate 컬럼)
  - 기존 raw `/status` 섹션은 그대로 (하단으로 이동)

### Wire change

No (서버-내부 model + SSR 카드; 콘솔에는 작은 system frame 만 추가
— Android client 영향 무시 가능).

## [0.62.0] - 2026-05-24 — Phase 41: 한국어 FTS (pg_trgm 통합)

### Added

v0.53.0 의 `simple` 토크나이저가 못 잡던 한국어 substring 매치 보완.
`mecab-ko` 같은 형태소 분석 extension 은 별도 PG 이미지가 필요해 부담이
큼 — 표준 PG 에 거의 모두 포함된 `pg_trgm` 으로 절충.

- **`CREATE EXTENSION IF NOT EXISTS pg_trgm`** 자동 마이그
  (`Database.init` 의 idempotent SQL block).
- **`conversation_turns_content_trgm_idx`** GIN trigram 인덱스
  (`gin_trgm_ops` on `content`). ILIKE `%q%` 가 인덱스 사용.
- **`ConversationTurnRepository.Filter.q` 자동 분기**:
  - ASCII-only query → 기존 `TsvectorMatchOp` (tsvector +
    `plainto_tsquery('simple', ?)`).
  - non-ASCII 포함 query → 신규 `TrigramIlikeOp` (`content ILIKE %q%`,
    `%/_/\` escape). 한국어 / 일본어 / 중국어 / 이모지 모두 substring
    매치.
- 둘 다 parameter binding 으로 SQL injection 방어.

### 한계 / trade-off

- **`mecab-ko` 미통합** — 진짜 형태소 분석은 별도 PG 이미지
  (`pgroonga/mecab-ko` 등) 가 필요해서 단일 사용자 dev 프로필에 부담.
  pg_trgm substring 으로 90% 케이스 커버.
- **`mecab-ko` 가 처리하는 "조사 / 어미 제거"** 는 미지원. "개발자가" 검색
  하면 "개발자" 매치 안 됨 (역은 OK — "개발자" 검색에 "개발자가" 매치).
  필요하면 사용자가 root form 으로 검색.

### Wire change

No.

## [0.61.0] - 2026-05-24 — Phase 40: Conversation memo + star

### Added

대화 turn 마다 사용자 메모 + 별표(book-mark) 기능. 긴 history 안에서
중요한 turn 을 골라 표시하고, 자기 코멘트를 남겨 두기 위함.

- **schema**: `conversation_turns.user_memo` (text, nullable),
  `conversation_turns.starred` (bool, default false) — `Database.init`
  의 `createMissingTablesAndColumns` 가 자동 마이그.
- **`ConversationTurnRepository.setMemo / setStarred / findById`** —
  memo 8000 char 캡, blank → null 정규화.
- **`Filter.starredOnly`** — true 면 `starred = true` row 만 (기본 false).
  `/history` UI 의 새 체크박스 "★ starred 만" 으로 토글, query param
  `?starred=1` round-trip.
- **`/history` SSR 갱신**:
  - 각 row 의 시각 컬럼 아래에 ☆/★ 토글 버튼 (`.star-btn`)
  - content 셀 아래에 메모 영역 — 있으면 노란 highlight, 없으면 "+ 메모"
    placeholder. 클릭 시 `prompt()` 로 인라인 편집.
  - 이벤트 위임 + `window.__VIBE_CSRF__` 사용해 fetch POST.
- **새 JSON API endpoints**:
  - `POST /api/projects/{id}/history/{turnId}/star?starred=true|false`
  - `POST /api/projects/{id}/history/{turnId}/memo` (body `{"memo":"..."}`;
    `memo: ""` 또는 null → 메모 제거)
  - CSRF 는 `?_csrf=` query 검증 (fetch 가 form-encoded body 안 보냄).

### Wire change

No (DTO 무변경; 새 컬럼은 옵션, 새 endpoint 는 SSR-bearer).

## [0.60.0] - 2026-05-24 — Phase 39: Backup 자동화

### Added

v0.34.0 의 수동 `/backup` 다운로드 + Wiki 의 cron 안내를 한 단계 발전 —
서버가 직접 cron 폴링으로 자동 백업 + rotation.

- **`BackupSection`** config (`enabled` / `cron` / `retentionCount`).
  default `enabled=false` — 기존 deployment 무영향.
- **`BackupService`** (BackupRoutes 의 walk / exclusion 로직 추출):
  - `streamTarGz(OutputStream)` — 수동 다운로드와 자동 파일 생성 양쪽이 공유
  - `createScheduled(now)` — `<workspace>/.vibecoder/backups/<ts>.tar.gz`
  - `listAutoBackups()` — most-recent-first
  - `deleteOldestOverRetention(retain)` — rotation
  - `deleteAutoBackup(name)` / `resolveAutoBackupForDownload(name)` —
    strict path-traversal 방어 (basename 만; backups 경계 밖이면 거절)
- **`BackupScheduler`** — `BuildScheduler` 와 동일 패턴 (1 분 polling +
  HH:MM cron + dedupe). `enabled` provider 가 false 면 polling 자체 skip.
  shutdown hook 등록.
- **`/backup` SSR 확장**:
  - "자동 백업 (v0.60.0+)" 카드 — 현재 enabled 상태 / cron / retention
    안내, 비활성이면 server.yml 예시 안내.
  - "지금 백업 한 번 실행" 버튼 (cron 무관하게 즉시 트리거 + rotation).
  - 자동 백업 파일 목록 (파일명 / 크기 / 시각) + 각각 다운로드 / 삭제.
- **새 endpoint**:
  - `GET /backup/auto/{name}` — 파일 다운로드
  - `POST /backup/auto/{name}/delete` — 개별 삭제
  - `POST /backup/auto/run-now` — 즉시 백업 트리거

### Exclusions

자동 백업도 manual 과 같은 exclusion (postgres data dir,
gradle/caches/daemon, npm-cache, playwright, build logs/) + **자기
자신 (`.vibecoder/backups/`)** 도 추가 제외 — 백업의 백업 무한 재귀 방지.

### Wire change

No.

## [0.59.0] - 2026-05-24 — Phase 38: 빌드 통계 대시보드

### Added

`/projects/{id}/builds` 페이지 상단에 통계 카드 추가. 한눈에 프로젝트의
빌드 건강 상태를 파악.

- **`BuildService.statistics(projectId, artifactRepo, recentLimit=30)`** —
  최근 200 row 기반 in-process aggregation:
  - total / success / failed / cancelled / running 카운트
  - successRatePercent (Int 0..100)
  - avgSuccessDurationMs (SUCCESS 만)
  - recentStatuses (most-recent-first, recentLimit개)
  - recentSuccessSizes (most-recent-first, 최근 10 SUCCESS APK 크기)
- **SSR 카드**:
  - **전체 빌드 / 성공률 (색상 배지: ≥90 ok / 70-89 warn / <70 warn) /
    평균 빌드 시간** 3-grid 메트릭.
  - **Status sparkline** (SVG bar): 최근 30 빌드 상태를 색 막대로
    (초록 success / 빨강 failed / 회색 cancel / 노랑 running). hover
    시 tooltip 으로 status 텍스트.
  - **APK 사이즈 trend** (SVG polyline): 최근 10 SUCCESS 의 사이즈 추세
    + 첫 → 마지막 Δ KB 표시 (lower-is-better 색상).
- 외부 dep 없는 inline SVG — `highlight.js` / `Chart.js` 등 추가 dep 0개.

### Wire change

No.

## [0.58.0] - 2026-05-24 — Phase 37: 빌드 결과 비교

### Added

빌드 detail 페이지 (`/projects/{id}/builds/{buildId}`) 에 **이전 성공
빌드와의 비교** 카드. 회귀를 한눈에 (APK 사이즈 증가, 빌드 시간 증가).

- **`BuildRepository.previousSuccessfulBefore(projectId, beforeCreatedAt)`** —
  같은 프로젝트의 SUCCESS 빌드 중 createdAt 이 strictly 이전인 가장
  최근 row. null = 첫 성공 빌드.
- **`BuildService.compareWithPrevious(projectId, buildId, artifactRepo)`** —
  현재 빌드와 이전 SUCCESS 빌드의 (durationMs, apkSizeBytes) 추출 +
  `BuildComparison` (current / previous + delta) 반환.
- **SSR 카드**: build detail 페이지의 APK 카드 다음에 표 형태로 노출.
  - APK 사이즈 / 빌드 시간 비교
  - Δ 컬럼이 lower-is-better 기준 색상 (빨강 = 더 커짐/느려짐, 초록 =
    개선, 회색 = 변화 없음)
- 비교는 현재 빌드가 SUCCESS 일 때만 노출 (FAILED 면 카드 숨김).

### 알려진 한계

- **메소드 수 / dex 분석 미구현.** APK 사이즈와 빌드 시간만 비교 — APK
  안의 메소드 수 / 클래스 수 비교는 `dexdump` 통합이 필요해 추후 phase.
- **이전 빌드 = 마지막 SUCCESS**. 같은 변경 묶음의 비교가 아니라 단순
  시계열 직전 SUCCESS 와 비교. PR 별 비교는 별도 메타데이터 필요.

### Wire change

No.

## [0.57.0] - 2026-05-24 — Phase 36: WebAuthn passwordless-only + Helm :full

### Added

두 가지 묶음. WebAuthn 흐름의 강화 + k8s 배포 시 emulator 지원.

**1. WebAuthn passwordless-only 모드**

- `admin_users.passwordless_only` boolean 컬럼 추가 (default `false`,
  자동 마이그).
- `AuthService.login` 의 새 옵션 인자 `hasPasskey: (userId) -> Boolean` —
  password 검증 통과 후 `user.passwordlessOnly && hasPasskey(uid)` 면
  `401 passkey_required` 로 거절. 호출처 (AuthRoutes + AdminRoutes 의
  SSR `/login`) 모두 `webauthn.hasCredentials` 전달.
- SSR `/webauthn` 페이지에 토글 카드 추가:
  - 현재 상태 표시 (활성 / 비활성)
  - 활성화 시 `confirm()` 가드 + passkey 0개 사용자는 lockout 방지로
    400 거절.
  - 비활성화는 항상 가능 (복구 경로).
- `POST /webauthn/passwordless?enabled=true|false` 토글 endpoint.
- `AdminRoutesDeps.webauthnService` 추가 — 모든 SSR/login 코드에서 사용.

**2. Helm chart `:full` 이미지 지원**

- `helm/vibe-coder-server/values.yaml` 에 `fullImage` 섹션 추가
  (`enabled` / `tag` / `novncPort`).
- `templates/deployment.yaml` 가 `fullImage.enabled=true` 일 때:
  - 이미지 태그를 `:full` 변형으로 전환
  - `securityContext.privileged: true`
  - `/dev/kvm` hostPath 디바이스 마운트
  - container 의 noVNC port (6080) 추가
- `templates/service.yaml` 가 같은 조건으로 noVNC port 노출
  (`/emulator/vnc/*` reverse proxy 가 있어서 외부 사용자는 거의 필요 X).
- `helm/vibe-coder-server/README.md` 에 `:full` 사용 playbook + 노드
  prerequisite 안내 (KVM 모듈 / PodSecurity privileged).

### Wire change

No (SSR + admin-bearer; Android client 무관).

### 한계 / 향후

- **passwordless-only recovery** 흐름 없음 — 모든 passkey 분실 + 토글
  켜져 있으면 lockout. 운영자가 DB 에서 직접 `passwordless_only = false`
  로 되돌리거나 새 admin 으로 setup 페이지를 재진입해야 함.
- Helm `:full` 은 단일 노드 / 단일 사용자 dev 환경 가정. 멀티 노드 +
  KVM 가용 노드 selection (`nodeSelector`) 은 추후.

## [0.56.0] - 2026-05-24 — Phase 35: API rate limit (per-IP)

### Added

외부 노출 / credential-stuffing 방어. Token bucket per-IP, 외부 dep 0개.

- **`RateLimiter`** — in-memory token bucket per IP. capacity / refill
  rate / lock-free refill 공식 (`tokens += elapsed × refillRate`).
- **`installRateLimit`** Ktor plugin — `ApplicationCallPipeline.Plugins`
  보다 앞 phase 에서 인터셉트. `/api/`, `/ws/`, `/login` POST 만
  throttle (정적 자원과 SSR 페이지는 skip).
- **2개 bucket**:
  - **api** — capacity 120, refill 2 tok/s (분당 약 180회 — 정상
    console 흐름은 안 걸림)
  - **auth** — capacity 10, refill 0.2 tok/s (분당 약 12회 — credential
    stuffing 방어)
- **Admin bypass** — admin Bearer / 쿠키 세션은 두 bucket 모두 무시.
- **429 응답** — `Content-Type: application/json` +
  `Retry-After: <sec>` 헤더 + `{code, message, retryAfter}` body.
- **`RateLimitSection`** config:
  - `enabled` — 기본 `true`. nginx / Cloudflare 등 외부 limiter 사용 시 끔.
  - `apiCapacity` / `apiRefillPerSecond` (기본 120 / 2.0)
  - `authCapacity` / `authRefillPerSecond` (기본 10 / 0.2)
- **Metrics** (Phase 34 통합):
  - `vibe_rate_limit_429_total{path_bucket="api|auth"}` counter
  - `vibe_rate_limit_buckets_active{bucket="api|auth"}` gauge

### Wire change

No (새 응답 코드 `rate_limited` + HTTP 429 — 정상 사용 흐름엔 보이지 않음).

## [0.55.0] - 2026-05-24 — Phase 34: Metrics endpoint (Prometheus)

### Added

운영 가시성 — Prometheus 가 직접 scrape 할 수 있는 `/metrics` endpoint.
외부 dep 0 개 (text exposition format 직접 생성).

- **`MetricsRegistry`** — counter (`LongAdder`) + gauge (callable
  supplier) 등록. 같은 metric name + labels 조합 idempotent.
- **`/metrics`** SSR admin-only. `Content-Type: text/plain;
  version=0.0.4`. label / help 텍스트 모두 Prometheus spec 준수 escape.
- **Gauges** (live sample on every scrape):
  - `vibe_jvm_memory_used_bytes` / `vibe_jvm_memory_max_bytes` /
    `vibe_jvm_threads`
  - `vibe_projects_total` (scratch 제외)
  - `vibe_users_total` (admin + member + viewer 모두)
  - `vibe_devices_total` (활성 device token 수)
  - `vibe_push_subscriptions_total`
  - `vibe_console_sessions_active` — 메인 console child 수
  - `vibe_sub_agent_sessions_active` — sub-agent child 수
- **Counters** (events; 서버 재시작 시 0 으로 리셋 — Prometheus rate()
  가 자연스럽게 처리):
  - `vibe_build_total{status="success|failed|cancelled"}`
  - `vibe_claude_usage_warn_total`
  - `vibe_disk_usage_warn_total`
- **`Notifiers.metrics`** — `var` 으로 `MetricsRegistry` set. ServerMain
  이 모든 dep 준비 후 마지막에 wire (Notifiers 자체엔 metrics 의존성
  없음 — counter inc 만 옵션).

### Scrape 예시

```yaml
# prometheus.yml
scrape_configs:
  - job_name: vibe-coder-server
    metrics_path: /metrics
    bearer_token: <admin Bearer token>
    static_configs:
      - targets: ['vibe.local:17880']
```

또는 reverse proxy 뒤에서 basic-auth 로 front + Bearer 통과.

### Wire change

No (SSR 페이지 한 개 + 텍스트 응답; Android client 무관).

## [0.54.0] - 2026-05-24 — Phase 33: 심볼 정의 검색 (best-effort regex)

### Added

CLAUDE.md §9 I.△ "AST 기반 정의 jump" 의 minimal viable 구현. 매우 큰 단독
작업이라 명시되어 있었으나, 실제 LSP (kotlin-language-server) 통합의
부담 (별도 JVM, 200-500 MB RAM, 10-30 초 cold start, Kotlin 전용) 이 단일
사용자 dev 서버 프로필 (CLAUDE.md §1) 에 비해 과함. regex 기반 best-effort
구현이 90% 케이스 충분 (top-level 선언) — zero new deps, ms 응답.

- **`SymbolFinder`** — `projectId + symbol` → declaration sites.
  - Kotlin / Java 의 `fun` / `class` / `object` / `interface` /
    `enum class` / `annotation class` / `data class` / `val` / `var` /
    `typealias` 선언 패턴 (6개 regex).
  - Identifier validation 으로 임의 regex injection 방어.
  - 100 hit hard cap, 5 MB 파일 size cap, 표준 exclusion
    (`build/`, `.gradle/`, `node_modules/`, `.idea/`).
  - 확장자 whitelist (`.kt`, `.kts`, `.java`, `.groovy`).
- **SSR `/projects/{id}/symbols?q=<name>`** — 검색 폼 + 결과 표 (kind /
  location / source line). 결과 클릭 시
  `/projects/{id}/view?path=...&line=N` 로 jump. 인접 기능 "grep으로 →"
  링크 (workspace-wide content search 로 우회).
- **JSON API `GET /api/projects/{id}/symbols?name=<name>`** —
  `{"hits":[{relPath,lineNumber,kind,line}]}`. `requireProjectAcl` 가드.
- **File viewer 통합** — `?line=N` 쿼리 파라미터 받아서 highlight.js
  렌더링 후 해당 라인 위치까지 smooth scroll + 1.5 초 노란색 outline
  강조.
- **콘솔 페이지 사이드 링크** — "⇢ 정의 검색" 칩 추가
  (`/projects/{id}/files` 옆).

### Trade-off (의식적)

- **LSP 통합 보류.** 진짜 AST jump (referenced-by, 정확한 nested type
  구분 등) 가 필요하면 `kotlin-language-server` 통합이 별도 phase 로
  올라옴. 운영 비용이 큼 — 컨테이너 추가 ~300 MB + cold start 큰 만큼
  사용자 의향이 확인된 후에만.
- **False positive 가능.** Nested 동명 클래스 / 메소드 / `val` 은 같은
  pattern 으로 잡혀 같이 표시. UI 가 jumping 용 — 결정은 사용자.
- **참조 검색 (`References to X`) 미지원.** 정의만 — `/code-search?q=`
  로 grep 가능.
- **Java 메소드 패턴 한계.** Return type 의 generic 이 매우 길거나
  주석이 끼면 매칭 실패 가능. 가장 흔한 modifier 6개 만 인식.

### Wire change

No (server-internal SSR + admin-bearer JSON; Android client 영향 없음).

## [0.53.0] - 2026-05-24 — Phase 32: PG tsvector + GIN 풀텍스트 검색

### Added

v0.16.0 이후 적혀있던 한계 ("본문 검색은 LIKE — 다음 cycle 에서 tsvector 로
교체 예정") 해소. `conversation_turns.content` 의 LIKE full-scan 이
인덱스 사용 풀텍스트 검색으로 마이그.

- **`content_tsv` generated column** (PG 12+ `GENERATED ALWAYS AS …
  STORED`) — 매 row 의 `to_tsvector('simple', content)` 가 자동 계산되어
  저장. `simple` configuration 은 language-agnostic (한국어 / 영어 모두
  토큰화 OK; stemming 없음).
- **GIN index** `conversation_turns_content_tsv_idx` — tsvector 매치를
  log-N 시간에.
- **`Database.init()` raw SQL 마이그** — `ALTER TABLE … ADD COLUMN IF
  NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`. 첫 부팅 시 한 번만 적용,
  이후 부팅은 idempotent no-op. 기존 row 는 자동으로 백필됨 (GENERATED
  STORED 의 성질).
- **`ConversationTurnRepository.Filter.q` 매칭** — LIKE 대신
  `content_tsv @@ plainto_tsquery('simple', ?)`. `plainto_tsquery` 가
  사용자 query 를 AND 토큰으로 변환 — 메타문자 / 따옴표 안전 +
  parameter binding 으로 SQL injection 방어.
- **`TsvectorMatchOp`** — 사설 `Op<Boolean>` 으로 Exposed QueryBuilder
  에 raw SQL fragment 를 안전하게 inject.

### Performance impact

수십만 row 의 `conversation_turns` 에서 content 검색이 수십 ms → ms
미만 (PG `EXPLAIN ANALYZE` 기준). insert 시 generated column 계산
비용 미미 (tsvector 생성은 매우 빠름).

### Wire change

No (REST 응답 DTO 무변경; query 시멘틱 동일 — 단어 매칭은 LIKE 와
거의 같지만 substring 매치는 안 됨 — `tsvector` 는 token 단위).

### 알려진 한계

- **`simple` 토크나이저** — 한국어 형태소 분석 안 됨. "개발자" 와
  "개발자가" 는 다른 토큰. 더 정교한 한국어 검색이 필요하면 PG 의
  `mecab-ko` extension + custom config 가 별도 phase.
- **Substring 매치 X** — `'develop'` 검색이 `'developer'` 매치 안 됨
  (token 경계). 필요하면 `:*` prefix 매치 (`to_tsquery`) 로 확장 가능
  — 추후.
- **CJK 검색은 best-effort** — 한국어 / 일본어 / 중국어 모두 'simple'
  은 공백 / 구두점 기준 토큰화. 단어 경계가 모호한 언어는 정확도가
  낮음. UI 가 부분 일치를 위해 `LIKE` 보조 검색을 함께 노출하는 것도
  옵션.

## [0.52.0] - 2026-05-24 — Phase 31: /history agent_name 필터 + 분리 보기

### Added

Phase 28 (v0.49.0) 에서 추가된 `conversation_turns.agent_name` 컬럼을
UI 에서 본격 활용. 기존 history 페이지는 메인 console + sub-agent turns
를 섞어서 보여줬는데, 이제 명시적으로 필터링 가능.

- **`ConversationTurnRepository.Filter.agentName`** — 3-mode 시멘틱:
  - `null` (기본) — `agent_name IS NULL` → **메인 console 만**.
    backward compatible: 기존 호출자 (Filter 가 비-`agentName` 가용)
    은 자동으로 메인-only.
  - `""` (빈 string) — 필터 안 함 → 메인 + 모든 sub-agent.
  - `"<name>"` — 그 sub-agent 만.
- **`distinctAgents(projectId)`** Repository 헬퍼 — UI dropdown 채움.
- **`/history` 페이지 (per-project + chat)** filter form 에
  **"Agent (v0.52.0+)"** dropdown 추가. 3 가지 옵션:
  - `(main console only)` — default
  - `(all — main + sub-agents)` — query `?agent=*`
  - `@<agent>` — 등록된 agent 별 옵션 (distinct list 에서)
- **Row 표시**에 `@<agent>` 작은 배지 추가 — sub-agent 출처가 한눈에.
- **Pagination 링크** 가 `agent` 파라미터 round-trip — 페이지 이동 시
  필터 유지.

### Query param contract

| Browser URL | Filter.agentName | Behaviour |
|---|---|---|
| `/projects/{id}/history` (no `agent=`) | `null` | 메인 console 만 |
| `?agent=*` | `""` | 메인 + 모든 sub-agent |
| `?agent=reviewer` | `"reviewer"` | `@reviewer` 만 |

### Wire change

No (DTO 무변경; 기존 호출자 자동으로 메인-only — 명시적으로 `agent=*`
를 줘야 sub-agent 가 합쳐짐).

## [0.51.0] - 2026-05-24 — Phase 30: JSON API ACL 완성

### Added

v0.49.0 Phase 28 의 한계 (SSR 만 완전 보호) 해소. 이제 mutating
per-project JSON API 와 WebSocket 모두 ACL 강제.

- **`ApplicationCall.requireProjectAcl(projects, projectId)`** —
  SSR-side `requireProjectAccessOrRedirect` 의 JSON 짝. 위반 시
  `403 project_forbidden` throw. admin bypass + 0-ACL bypass + grant
  체크 로직 캡슐화.
- **15+ JSON endpoint 에 ACL 가드 추가**:
  - `BuildRoutes`: `POST /api/projects/{id}/build/debug`,
    `GET /api/projects/{id}/builds`, `GET .../{buildId}`,
    `POST .../{buildId}/cancel`
  - `GitRoutes`: `GET /api/projects/{id}/git/{status,diff,log}`,
    `POST /api/projects/{id}/git/commit`
  - `FileRoutes`: `POST /api/projects/{id}/files/upload`,
    `GET /api/projects/{id}/files`, `GET .../files/{fileId}/download`,
    `DELETE .../files/{fileId}`
  - `ConsoleRoutes`: `POST /api/projects/{id}/claude/console/{prompt,
    new, cancel}`, `GET .../claude/status`,
    `GET .../claude/prompt-suggestions`
  - `ProjectActionRoutes`: `GET /api/projects/{id}/actions`,
    `POST .../actions/invoke`
  - `SubAgentRoutes`: `POST .../agents/{agent}/console/{prompt,
    cancel}`, `GET .../agents/active`, SSR `GET /projects/{id}/agents`,
    `GET .../agents/{agent}/console`, `POST .../new`
- **WebSocket ACL 가드** — `/ws/projects/{id}/console/logs` 와
  `/ws/projects/{id}/agents/{agent}/console/logs` 핸드셰이크에서
  device → user role + ACL 검사. 위반 시
  `WsFrame.Error("project_forbidden")` 송신 + `VIOLATED_POLICY`
  CloseReason 으로 연결 종료.
- **Signature 변경** — `BuildRoutes.buildRoutes(service, hub, projects)`
  와 `FileRoutes.fileRoutes(service, projects)` 가 `ProjectService` 를
  새로 받음. `WsRoutes.wsRoutes` 도 `projects` 인자 추가.

### Wire change

No (response DTO 무변경; 새 응답 코드 `project_forbidden` 는 ACL
밖 project 에 접근하는 viewer / member 토큰만 받음).

### Bypass 모델 (변경 없음)

- `admin` role 은 ACL 무관 — 모든 프로젝트 통과
- ACL row 가 0개인 non-admin 사용자 — 모든 프로젝트 통과 (default
  unrestricted)
- ACL row 가 1+ 개인 non-admin 사용자 — 허가된 프로젝트만 통과

## [0.50.0] - 2026-05-24 — Phase 29: Web Push payload 암호화 (RFC 8291)

### Added

CLAUDE.md §9 D.△ 의 v0.46.0 (Phase 25) 마무리. 그동안 payload-less 모드로
"Vibe Coder · 서버에서 알림이 도착했습니다" 한 줄만 표시되던 web push 가
이제 실제 title / body / url 을 담아 전달됨.

- **`Aes128GcmEncrypt`** (신규, 167 LOC) — JDK stdlib 만으로 RFC 8291
  aes128gcm 콘텐츠 인코딩 구현:
  - Ephemeral P-256 keypair (`KeyPairGenerator("EC", secp256r1)`).
  - ECDH 공유 비밀 (`KeyAgreement("ECDH")`) → 32 bytes.
  - HKDF-SHA256 (extract + 1-block expand, `Mac("HmacSHA256")`) — IKM /
    CEK (16) / NONCE (12) 도출. RFC 8291 §3.4 info strings 동일.
  - 4096-byte record: payload || `0x02` || zero padding.
  - AES-128-GCM (`Cipher("AES/GCM/NoPadding")`, 128-bit tag).
  - Final body = `salt(16) || record_size(4 BE) || keyid_len(1) ||
    as_public(65) || ciphertext`.
  - 외부 dep 0개 (BouncyCastle / web-push-java 불필요).
- **`WebPushNotifier.PushSubscription`** — `p256dh`, `auth` 필드 추가.
  서버 ↔ DB 의 기존 row (v0.46.0 부터 저장됨) 가 그대로 사용됨.
- **`WebPushNotifier.sendOne()`** — 두 path:
  - p256dh + auth **있음** → `Aes128GcmEncrypt.encrypt(...)` →
    `Content-Encoding: aes128gcm` POST.
  - p256dh / auth **없음** (legacy v0.46.0 row) → payload-less POST
    (`Content-Length: 0`). 서비스워커가 generic 알림 표시 (fallback).
- **`WebPushNotifier.broadcast(title, body, url?)`** — `url` 옵션 인자
  추가. service-worker 가 notificationclick 시 해당 경로로 focus / open.
- **`Notifiers` facade** — buildResult / claudeUsageWarn / diskUsageWarn
  각각 의미 있는 URL 전달:
  - 빌드 알림 → `/projects/{id}/builds/{buildId}`
  - Claude usage warn → `/usage`
  - Disk usage warn → `/`
- **Service worker** (`/static/sw.js` v0.50.0):
  - `event.data.json()` 으로 title / body / url 파싱 (브라우저가 자동
    복호화한 plaintext).
  - `notificationclick` 이 `data.url` 우선 — 기존 탭이 같은 path 면
    focus, 아니면 새 탭으로 open.

### Wire change

No (서버-내부 + 클라이언트 코드 자동 갱신 — `CACHE_VERSION` 변경으로
다음 페이지 진입 시 서비스워커 자동 갱신).

### 알려진 한계

- **VAPID 키와 ephemeral keypair 가 다른 객체.** ephemeral 은 매 push 마다
  새로 생성 (RFC 8291 권장). 그래서 별도 캐싱 없음 — push 당 keypair
  생성 비용 (수십 µs) 발생.
- **Padding strategy 가 고정**: 매 push 가 RECORD_SIZE=4096 으로 패딩.
  payload 크기 노출 방지 정도는 보장. 더 미세한 패딩 정책 (예: payload
  size 기반 dynamic) 은 추후.
- **Single record per push.** Web Push spec 은 multi-record 도 허용하나
  단일 알림 payload 가 4080 bytes 를 넘는 경우 거의 없어 단순화.

## [0.49.0] - 2026-05-24 — Phase 28: Project ACL + Sub-agent 영구 적재

### Added

CLAUDE.md §9 후속 minor 2건 묶음. 다중 사용자 모델 마무리 + sub-agent 영속성.

**1. Project ACL — member 가 일부 프로젝트만 보기.**

- **`ProjectAcls` 테이블** (`project_id`, `user_id`, `granted_by`, `created_at`).
  composite PK + per-user index.
- **`ProjectAclRepository`** — grant / revoke / replaceForUser / listForUser /
  listUsersForProject / hasAnyRowFor / isGranted.
- **Opt-in 제한 모델**: 사용자에게 ACL row 가 **하나도 없으면** 모든 프로젝트
  보임 (default). 하나라도 있으면 **그 프로젝트만** 보임. `admin` role 은
  ACL 무관 (lockout 방지). 기존 사용자 0-row 라 마이그레이션 무손실.
- **`ProjectService.listForUser(userId, isAdmin)`** + **`canUserAccess(userId,
  isAdmin, projectId)`** — ACL 평가 캡슐화.
- **`requireProjectAccessOrRedirect(sess, projects, projectId)`** SSR 가드
  헬퍼.
- **SSR `/users/{userId}/projects`** (admin-only) — 체크박스 list 로 ACL
  bulk-replace. 사용자 row 에 "권한" 칩 링크 추가.
- **JSON API**:
  - `GET /api/projects` — `DevicePrincipal.userRole` 기반 자동 필터링.
  - `GET /api/projects/{id}` — ACL 위반 시 `403 project_forbidden`.

**2. Sub-agent 영구 적재 — Phase 23 (v0.44.0) 후속.**

이전엔 sub-agent turn 이 LogHub 의 sliding window 안에서만 살아있었음.
이제 메인 console 과 같은 `conversation_turns` 테이블에 적재되어
재시작 후에도 보존.

- **`conversation_turns.agent_name`** 컬럼 추가 (nullable). null = 메인 console,
  non-null = sub-agent 이름. 새 인덱스 `(project_id, agent_name, ts)`.
- **`ConversationHistoryService`** API 확장 — `userPrompt`, `event`,
  `systemNotice` 모두 `agentName: String?` 옵션 인자 추가 (default null —
  기존 main-console 호출 영향 없음).
- **`SubAgentSessionManager`** — `history: ConversationHistoryService?` 의존성
  추가. 모든 user prompt / Claude event / system notice 가 `agent_name` 으로
  태깅되어 적재. session-id 별 turnIdx 는 그대로 사용.

### Wire change

No — REST 응답 DTO 무변경. ACL 의 SSR 변경, 신규 SSR 페이지 1개, 새 컬럼
+ 인덱스 마이그레이션 (`SchemaUtils.createMissingTablesAndColumns` 자동).

### 알려진 한계

- **JSON API 의 모든 project-scoped endpoint 에 ACL 가드를 일일이 안 달았음.**
  `/api/projects/{id}` 와 `/api/projects` (list) 만 적용. console prompt /
  build / git commit 등 mutating endpoint 는 `requireApiWrite()` 가드는
  거치지만 ACL 검사는 추후. SSR 측은 `requireProjectAccessOrRedirect` 으로
  완전 보호.
- **`/history` 글로벌 검색 + `/projects/{id}/history` 페이지**는 sub-agent
  turn 도 함께 표시 (agent_name 필터 UI 아직 없음). 추후 추가.

## [0.48.0] - 2026-05-24 — Phase 27: WebAuthn (passkey 2FA)

### Added

CLAUDE.md §9 B.△ "Hardware security key (WebAuthn)" 실현. TOTP 의 phishing-resistant
강화 — same-origin 정책이 보장하는 signature 가 가짜 사이트의 OTP 가로채기를 차단.

- **신규 dependency**: `com.webauthn4j:webauthn4j-core:0.29.1.RELEASE` (~600 KB,
  BouncyCastle + Jackson-CBOR transitive). 자체 구현보다 안정적 (CBOR / COSE /
  attestation 검증 모두 처리).
- **`WebauthnService`** — `WebAuthnManager` (non-strict) wrap:
  - `beginRegistration(userId, username)` — 32-byte challenge 생성, 5분 TTL 메모리
    저장. `RegistrationStart(challenge, rpId, rpName, userId, username, exclude...)`
    반환.
  - `finishRegistration(userId, clientDataJSON, attestationObject, transports, name)`
    — webauthn4j 의 `validate(RegistrationData, RegistrationParameters)` 호출 후
    `AttestationObject` 를 통째로 base64url-CBOR 로 저장 (assertion 시 재빌드용).
  - `beginAssertion(usernameHint, userId?)` — 사용자에게 등록된 credential id 만
    `allowCredentials` 로 노출. 사용자 없으면 빈 배열 (timing-safe discovery).
  - `finishAssertion(...)` — `clientDataJSON.challenge` 매칭으로 ceremony 식별 →
    `CredentialRecordImpl(AttestationObject, ...)` 4-arg 생성자 + signCount seed →
    webauthn4j 검증 → signCount 갱신 + `AssertionResult(userId, credentialId)`.
- **`WebauthnCredentials` 테이블** — userId / credentialId (unique) /
  attestationObject (CBOR base64url) / signCount / transports / attestationType /
  name / createdAt / lastUsedAt. PG schema 자동 migration.
- **`WebauthnCredentialRepository`** — insert / listForUser / findById /
  findByCredentialId / deleteById / countForUser / touchAfterAssertion.
- **`WebauthnSection`** (server.yml) — `rpId` / `rpName` / `origin`. 기본값은
  `localhost:17880` 가정. LAN/외부 노출 시 사용자가 직접 설정 (rpId 는 hostname
  only, origin 은 scheme+host+port).
- **SSR `/webauthn`** (any authenticated user) — 등록된 passkey 목록 / 이름 지정
  + 등록 버튼 / 삭제 버튼. 등록 흐름:
  1. `POST /api/webauthn/register/options` → challenge + rpId 받아옴
  2. 브라우저 `navigator.credentials.create({...})` → 인증기 (Touch ID /
     Windows Hello / 보안키) 사용
  3. `POST /api/webauthn/register/verify` → 검증 + DB 저장 + audit log
- **로그인 페이지 통합** — username 입력 직후 "🔑 Passkey 로 로그인" 버튼 활성화.
  password 입력 없이 passkey 단독으로 로그인 가능 (passkey 자체가 2FA 의 강한 형태).
  흐름:
  1. `POST /api/webauthn/assert/options { username }` → allowCredentialIds
  2. `navigator.credentials.get({...})`
  3. `POST /api/webauthn/assert/verify` → 검증 후 `vibe_session` 쿠키 발급
- **Audit** — `auth.passkey.register`, `auth.passkey.login`, `auth.passkey.delete`.
- **Nav 메뉴** — "Passkey (WebAuthn)" 링크 (`/password`, `/2fa` 옆).

### Wire change

No (서버-내부 + 새 REST endpoints `/api/webauthn/*` 는 브라우저 전용).
Android client 영향 없음.

### 알려진 한계

- **rpId 가 hostname 매칭만 됨.** 사용자가 다른 origin (예: `localhost` 등록 후
  `vibe.local` 접근) 으로 들어오면 passkey 가 동작 안 함 — WebAuthn spec 의 보안
  특성. `server.yml` 의 `webauthn` 섹션을 운영 환경에 맞게 설정.
- **Challenge 가 in-memory.** 서버 재시작 중인 ceremony 는 다시 시작해야 함
  (단일 사용자 dev 서버라 영향 적음).
- **Passwordless 흐름은 password 강제 안 함** — 실수로 보안 다운그레이드 가능.
  배포 모드별로 "passkey-only" 강제 옵션은 추후 검토.

## [0.47.0] - 2026-05-24 — Phase 26: 나머지 settings admin 가드 + Claude /usage + Helm chart

### Added

CLAUDE.md §9 후속 + △ 항목 묶음 — 3가지 작은 작업.

**1. 나머지 `/settings/*` SSR 의 admin 가드 (v0.40.0 마무리).**

v0.40.0 에서 `/settings`, `/audit`, `/backup` 만 적용했던 `requireAdminOrRedirect`
를 다음 페이지에도 확장:

- `/settings/email`, `/settings/email/test`
- `/settings/webhook`, `/settings/webhook/test`
- `/settings/cors`
- `/settings/git-integrations` (GET + 모든 POST 4개)
- `/settings/cache`, `/settings/cache/cleanup`

이제 member / viewer 가 이 페이지에 접근하면 dashboard 로 redirect.

**2. `/usage` 페이지 — Claude `/status` raw 출력 노출 (Anthropic Cache 조회 △).**

- `ClaudeStatusService.rawSnapshots` — 폴링 시점의 raw 출력을 64 KB 까지 메모리 보존.
- `/usage` SSR (admin-only) — 프로젝트별 최근 snapshot 카드, `cache` 키워드 line 자동
  bold. Anthropic 이 prompt cache 통계를 `/status` 에 추가하면 즉시 가시화 (서버 수정 0).
- nav 에 "Claude 사용량" 링크.

**3. Helm chart (Kubernetes 배포 △) — `helm/vibe-coder-server/`.**

minimal viable v1 — 운영자가 docker compose 대신 k8s 로 같은 standalone 서버 띄울 수 있게.

- `Chart.yaml` — appVersion = server.version 과 동일.
- `values.yaml` — postgres 사이드카 (선택) / workspace PVC / ingress / resource 한계 /
  env / secretEnv 키. 모든 키 인라인 문서화.
- `templates/`:
  - `_helpers.tpl` — fullname / labels / serviceAccountName.
  - `deployment.yaml` — Deployment (replicas=1, strategy=Recreate; CLAUDE.md §1
    단일 사용자 가정). readiness/liveness probe `/api/health`.
  - `service.yaml` — ClusterIP.
  - `ingress.yaml` — 옵션 (TLS + WebSocket-친화적 nginx annotation).
  - `postgres.yaml` — StatefulSet + Service (옵션 enable). 외부 PG 사용 시 끄기.
  - `pvc.yaml` — workspace + (옵션) postgres RWO PVC.
  - `secret.yaml` — PG 비밀번호 + 임의 secret env.
  - `serviceaccount.yaml`.
- `helm/vibe-coder-server/README.md` — 빠른 설치 / 외부 ingress / 외부 PG / 한계 명시.

**Helm chart 한계 (의식적):**
- Single replica (workspace RWO PVC). HA 불가능.
- `:full` 이미지 (emulator + noVNC) 미지원 — KVM 패스스루 + privileged 컨테이너 필요.

### 보류

- **AST 기반 정의 jump** (△) — Kotlin LSP 통합이 너무 큰 작업이라 별도 phase 로 분리.
- **WebAuthn** (△) — webauthn4j 추가 검토 후 별도 phase.

### Wire change

No (server-internal only).

## [0.46.0] - 2026-05-24 — Phase 25: Web Push (VAPID, payload-less)

### Added

CLAUDE.md §9 △ "Push notification (Web Push)" 항목 실현 — 외부 의존성 없이
JDK 11+ stdlib (`java.security` + `java.net.http`) 만으로 구현.

- **VAPID 키 자동 생성/영속화** — P-256 ECDSA. 워크스페이스 안
  `<workspace.root>/.vibecoder/vapid-keys.json` (atomic write). 컨테이너
  재시작 후에도 동일 키 유지.
- **`WebPushNotifier`**:
  - `publicKeyBase64Url()` — 65-byte uncompressed point (04||X||Y) → base64url
    (브라우저의 `applicationServerKey` 로 그대로 전달).
  - `buildVapidJwt(endpoint, ttlSec)` — RFC 8292 VAPID JWT.
    JOSE ES256 (ECDSA DER → R||S 64 byte raw 변환 포함).
  - `broadcast(title, body)` — 등록된 모든 subscription 에 POST.
    payload-less push (Content-Length 0 — TTL 60s). 410/404 응답 시
    subscription DB 자동 삭제.
- **`PushSubscriptions` 테이블** (PostgreSQL) — endpoint unique, userId
  ref AdminUsers. `PushSubscriptionRepository` 의 upsert / list / deleteById.
- **JSON API**:
  - `GET /api/push/vapid-public-key` — base64url public key.
  - `POST /api/push/subscribe` { endpoint, p256dh, auth, userAgent } —
    upsert subscription. `requireApiWrite` 가드.
  - `DELETE /api/push/subscriptions/{id}` — 본인 정리.
- **SSR 페이지** `/settings/push`:
  - VAPID public key 노출.
  - 현재 브라우저 구독 상태 + "이 브라우저에서 구독" / "구독 해제" 버튼.
  - 등록된 모든 subscription 목록 (사용자 / 등록 시각 / endpoint / 삭제).
  - "테스트 알림 전송" 폼.
- **Service worker 확장** (`/static/sw.js` v0.46.0):
  - `push` event → `showNotification(title, { body, icon, badge, tag })`.
  - `notificationclick` → 기존 탭 focus 또는 `/` 새 탭.
- **Notifiers facade 통합** — build 결과 / Claude usage warn / disk usage
  warn 트리거 시 email / webhook 와 함께 web push 도 자동 broadcast.

### 알려진 한계 (의식적 trade-off)

- **payload-less 모드만 지원.** 알림은 generic 제목으로만 표시.
  진짜 사용자별 메시지를 보내려면 ECDH(VAPID, sub.p256dh) → HKDF →
  AES-128-GCM 암호화 (RFC 8291) 가 필요. 이는 in-house crypto 가
  복잡해 WebAuthn (Phase 27 예정) 와 함께 외부 라이브러리 도입 검토.
- WebAuthn (passkey 2FA) 는 별도 phase 로 분리 — webauthn4j 추가 검토 필요.

### Wire change

No (server-internal — 새 endpoint 들은 모두 web 브라우저 전용; Android
client 가 사용하지 않음).

## [0.45.0] - 2026-05-24 — Phase 24: JSON API + WebSocket role 가드

### Added

CLAUDE.md §9 후속 minor (v0.40.0 의 viewer role 모델 확장) — SSR 외에
**JSON API 와 WebSocket** 에서도 role 검사가 강제되도록 보완.

- **`DevicePrincipal.userRole`** — `installAuth(... userRepo = adminUserRepo)`
  로 device → user lookup 추가. `isAdmin` / `canWrite` 헬퍼 노출.
  Legacy (사용자 미바인딩) 토큰은 안전하게 admin 으로 fallback.
- **`ApplicationCall.requireApiWrite()`** — viewer 거절 (403 `viewer_readonly`).
  적용 endpoint:
  - `POST /api/projects` (register)
  - `DELETE /api/projects/{id}`
  - `POST /api/projects/{id}/build/debug`
  - `POST /api/projects/{id}/builds/{buildId}/cancel`
  - `POST /api/projects/{id}/git/commit`
  - `POST /api/projects/{id}/files/upload`, `DELETE .../files/{fileId}`
  - `POST /api/projects/{id}/claude/console/prompt`
  - `POST /api/projects/{id}/claude/console/new`
  - `POST /api/projects/{id}/claude/console/cancel`
  - `POST /api/projects/{id}/actions/invoke`
- **`ApplicationCall.requireApiAdmin()`** — admin-only (403 `admin_only`).
  적용 endpoint (server-level 설정):
  - `POST /api/env-setup/install-all`, `POST /api/env-setup/install/{id}`
  - `POST /api/claude/auth/upload`, `POST/DELETE /api/claude/auth/api-key`
  - `POST /api/claude/login/{start,submit,cancel}`
  - `POST /api/mcp/install`, `POST /api/mcp/unregister`, `POST /api/mcp/upload/...`
  - `POST /api/git/integrations` (등록 / 삭제 / SSH 키 생성)
- **WebSocket role 가드** — `/ws/projects/{id}/console/logs` 의 `UserPrompt`
  와 `ActionInvoke` 프레임 처리 직전에 viewer role 확인. 차단 시
  `WsFrame.Error("viewer_readonly", ...)` 응답 (연결은 끊지 않고 유지 —
  read 는 계속 가능).

### Wire change

No (server-internal only; 새 응답 코드 `viewer_readonly` / `admin_only` 추가는
viewer 토큰만 받음).

### 보류 (다음 후속)

- **Project ACL** (`project_acls(project_id, user_id)` 테이블) — member 가
  허가된 프로젝트만 보기. 별도 phase 로 분리.
- 나머지 `/settings/{email,webhook,cache,cors,git-integrations}` SSR
  admin 가드 — Phase 26 에서 묶음.

## [0.44.0] - 2026-05-24 — Phase 23: Real multi-agent (sub-agent process pool)

### Added

CLAUDE.md §9 F.○ "Multi-agent orchestration" 항목의 실제 process-pool 구현
(v0.36.0 / v0.41.0 의 dispatch UX 는 단일 child 안에서 sub-agent 호출이라
**병렬 실행이 안 됐음**).

- **`SubAgentSessionManager`** — `(projectId, agentName)` 키로 별도
  Claude child process pool 을 관리. 메인 `ClaudeSessionManager` 와
  완전 독립 (상태/세션ID/topic 분리). 같은 프로젝트 워크스페이스 안에서
  reviewer / frontend / backend 등 여러 agent 가 **병렬 실행**.
  - Session id 파일: `<workspace>/<projectId>/.vibecoder/agent-sessions/<agentName>.id`
  - Idle 30 분 후 SIGTERM (resume 보존 — 다음 prompt 시 같은 sessionId)
  - 첫 prompt 에 자동으로 `Use the <agent> sub-agent to ...` prefix 주입
    → Claude Code 의 표준 sub-agent dispatch 메커니즘 활용
- **SSR 페이지**:
  - `GET /projects/{id}/agents` — 등록된 agent 목록 + 활성 sub-agent
    상태 (`running` / `idle`) + 각각 "콘솔 열기" 링크.
  - `GET /projects/{id}/agents/{agent}/console` — 개별 sub-agent 콘솔.
    메인 콘솔의 트림 버전 (slash chip / template picker 없음).
- **JSON API**:
  - `POST /api/projects/{id}/agents/{agent}/console/prompt` (body `{text}`).
  - `POST /api/projects/{id}/agents/{agent}/console/cancel` — SIGTERM,
    session-id 보존.
  - `POST /projects/{id}/agents/{agent}/new` (form) — 세션 리셋.
  - `GET /api/projects/{id}/agents/active` — 활성 agent 이름 배열.
- **WebSocket** `/ws/projects/{id}/agents/{agent}/console/logs` —
  메인 콘솔과 동일한 replay + live merge 프로토콜.
- **메인 콘솔 헤더**에 `@ sub-agents →` 링크 칩 추가
  (`/projects/{id}/agents` 로 이동).
- **`requireWriteAccessOrRedirect` 적용** — viewer role 은 prompt/cancel/new
  거부 (v0.40.0 의 role 모델 확장).

### Wire change

No (server-internal only; 기존 wire `shared/` 모듈 무변경).

### 갱신 안 한 곳

- Sub-agent 의 turn 결과는 `conversation_turns` 테이블에 적재하지 **않음**
  (history persistence 는 main console 전용으로 유지 — sub-agent 가
  rotation policy 를 흔들 가능성). 휘발성 / 60s 간격 audit log 만 남음.
- vibe-coder-android `shared/` 동기 불필요 (wire 미변경).

## [0.43.0] - 2026-05-24 — Phase 22: VS Code extension full (v0.2.0)

### Added (in `vscode-extension/`)

v0.39.0 의 단일파일 scaffold (5 commands) 에 본격 기능 추가:

- **WebSocket subscribe** — `Vibe Coder: Follow project console` 명령이
  `/ws/projects/{id}/console/logs` 구독. assistant / tool_use /
  tool_result / done / session_started 등 모든 프레임을 VS Code Output
  Channel 에 stream. 같은 프로젝트에서 명령 재실행 시 toggle off.
- **Projects TreeView** — activity-bar 의 새 "Vibe Coder" 컨테이너
  (icon $(rocket)) 안의 "Projects" view. `GET /api/projects` 결과 +
  각 프로젝트 expand 시 `GET /api/projects/{id}/builds` (최근 20개).
  Right-click 메뉴 — Send prompt / Trigger debug build. Inline action —
  Follow console ($(eye) 아이콘).
- **Status bar item** — `$(rocket) <host> (vX.Y.Z)`. 60s 간격 자동
  refresh + 설정 변경 시 즉시 refresh. 클릭하면 `vibeCoder.status` 명령.
- **`onStartupFinished` activation** — VS Code 시작 시 자동 활성화
  (status bar 표시 위해).
- **Config 추가** — `vibeCoder.statusBar` (default true). 끄려면 false.
- **Marketplace 준비** — `icon.png` (현재 서버 아이콘 재사용),
  `keywords`, `categories`, `repository` 필드. `npm run package` 스크립트
  (`@vscode/vsce package`).

### 코드 구조 변경

기존 단일 `src/extension.ts` (~150 LOC) → 4 파일로 분리:

- `src/api.ts` — REST 클라이언트, 설정 헬퍼
- `src/ws.ts` — `ws` npm 패키지로 WebSocket subscribe
- `src/treeview.ts` — `ProjectsTreeProvider`
- `src/extension.ts` — activation entry, 7 commands

새 dep: `ws` (runtime) + `@types/ws` (dev). Node 내장만 사용하던 v0.1
정책에서 한 발 양보 — VS Code WebSocket 폴리필이 없어 가장 표준적인
선택.

### 서버 측 변경 없음

`server.yml` 의 version 만 v0.42.0 → v0.43.0 으로 올림. 새 서버 endpoint
나 wire 변경 없음. VS Code extension 자체 버전은 v0.1.0 → v0.2.0.

### Wire change: No

### 알려진 한계

- **Marketplace publish 안 함** — `vsce publish` 는 PAT 필요, 메인테이너
  수동 단계.
- **Webview 미사용** — Output Channel + TreeView 만. 콘솔을 fully
  interactive 하게 만드려면 Webview API 가 필요한데 본 cycle scope 밖.
- **Icon 미최적화** — 1.6 MB. Marketplace 가 받아주지만 128×128 PNG 로
  최적화 권장.

## [0.42.0] - 2026-05-24 — Phase 21: /emulator/vnc reverse proxy (admin auth)

### Added — `/emulator/vnc/*` HTTP + WebSocket proxy

신규 `server/emulator/VncProxyRoutes.kt`. vibe-coder admin 인증 boundary
안으로 noVNC 를 끌어들임. **외부 의존성 0** — JDK 11+ 표준
`java.net.http.HttpClient` + `WebSocket` 만 사용.

- `GET /emulator/vnc/{path...}` — `http://127.0.0.1:6080/{path}` 로 HTTP
  forward. byte-array body + Content-Type 보존. `application/octet-stream`
  fallback. 15s timeout.
- `WS /emulator/vnc/websockify` — Ktor server WS 가 client 와 연결, 동시에
  JDK `HttpClient.newWebSocketBuilder()` 로 backend `ws://127.0.0.1:6080/websockify`
  열어 양방향 binary/text frame forward.
- WS subprotocol `binary` 명시 (noVNC 표준).
- 인증 가드 — 모든 endpoint 에 `requireAdminOrRedirect`. WS handshake 도
  `vibe_session` cookie + `device→user.isAdmin` 검사. viewer/member 거절.

### Added — `/emulator` 페이지에 inline noVNC iframe

`:full` 이미지 사용자가 별도 SSH 터널 없이 같은 origin 으로 emulator
화면 직접 view 가능. iframe `src="/emulator/vnc/vnc.html?path=emulator/vnc/websockify&autoconnect=true&resize=remote"`.

빈 화면일 때 디버깅 가이드 (`:full` 이미지 / emulator launch / KVM
passthrough 확인) inline hint.

### 보안 향상 (vs v0.25.0)

| | v0.25.0 | v0.42.0 |
|---|---|---|
| noVNC 접근 | 호스트 6080 직접 노출 (no auth) | `/emulator/vnc/` admin 인증 통과 후만 |
| 권장 사용 | LAN 격리 또는 SSH 터널 | reverse proxy 뒤에서 같은 origin |
| CORS / iframe | 다른 호스트라 SOP block | 같은 origin embed 가능 |

`docker/compose.full.yml` 의 `ports: ["6080:6080"]` 는 이제 **불필요**
(주석으로 안내). 컨테이너 내부에서만 6080 노출되고 admin 인증된
vibe-coder 가 proxy.

### Wire change: No (SSR + 새 endpoint family `/emulator/vnc/*`)

### 알려진 한계

- HTTP 응답 streaming 안 함 — 전체 body 를 byte-array 로 받아 한 번에
  serve. 작은 정적 자원이라 OK 지만 큰 파일 (없겠지만) 면 memory ↑.
- Backend WS connect 5s timeout. 첫 페이지 로드 후 emulator 가 booting
  중이면 다시 새로고침.
- WebSocket subprotocol negotiation 만 `binary` 지원. noVNC 의 다른
  subprotocol (`base64`) 은 미지원 — 현대 브라우저는 binary 만 써서 무관.

## [0.41.0] - 2026-05-24 — Phase 20: Multi-agent dispatch UX (1단계)

### 디자인 결정

Real multi-agent process pool ((projectId, agentId) 별 별도 child
process + 탭 UI) 은 매우 큰 변경 — ClaudeSessionManager 의 키 구조 변경,
LogHub topic 분리, conversation_turns 의 agent 컬럼 추가, UI tabbed
console 까지 모두 필요. v0.41.0 은 더 작은 단계로 **사용자가 표준
sub-agent dispatch 를 1-click 으로 사용** 할 수 있도록 콘솔 UI 만 강화.

### Added — `@ Agent dispatch` 드롭다운 (콘솔 페이지)

콘솔 페이지의 prompt 입력 영역 위 picker 줄에 새 셀렉트 박스 추가.

- 페이지 로드 시 `GET /api/agents` (v0.36.0+) 호출 → 등록된 sub-agent
  목록을 채움.
- 선택 시 prompt 입력란에 `Use the <agent-name> sub-agent to ` prefix
  자동 삽입. 기존 입력 보존 (prefix 만 prepend).
- 이미 `Use the X sub-agent to` 가 있으면 agent 이름만 교체 (중복 prefix
  방지).
- 등록된 agent 가 없으면 `(등록된 agent 없음 — /agents)` placeholder.

Claude Code 의 표준 sub-agent dispatch 메커니즘을 그대로 활용 — 서버 측
프로토콜 변경 없이 UX 만 개선.

### Wire change: No (콘솔 JS 만)

### Real multi-agent — Roadmap

진짜 process pool 은 다음 cycle 후보 (v0.41.x 또는 v0.42+):

- `SubAgentSessionManager` 가 (projectId, agentName) → 별도 `claude`
  child process spawn.
- LogHub topic 분리 (`projectId/agentName`).
- 콘솔 UI 에 agent tab (메인 + sub-agent 별).
- `conversation_turns.agent_name` 컬럼 추가.

이번 단계는 가장 가치 있는 부분 (사용자가 agent 를 쉽게 호출하는 UX) 만
선제 도입. 진짜 병렬 process pool 이 필요해질 때 위 작업 진행.

## [0.40.0] - 2026-05-24 — Phase 19: admin 가드 강화 + viewer role

### Added — `viewer` role (read-only)

CLAUDE.md §9.G 의 멀티 사용자 2단계. `admin_users.role` 의 허용 값에 `viewer`
추가:

- **admin** — 모든 권한 (관리 페이지 + write 작업)
- **member** — 작업 페이지 + 모든 write
- **viewer** — **read-only**. 콘솔 prompt / 빌드 큐 / git commit / 파일
  업로드 등 차단

### Added — `requireWriteAccessOrRedirect(sess)` helper

`requireSessionOrRedirect` 와 chain 으로 사용. viewer 세션이 write
endpoint 에 도달하면 dashboard 로 redirect (msg: "viewer 권한으로는
변경할 수 없습니다.").

`WebSession.canWrite` derived flag (`admin` 또는 `member`).

### 적용된 write 가드 (SSR)

- `POST /projects` (프로젝트 생성)
- `POST /projects/{id}/delete`
- `POST /projects/{id}/console/new`
- `POST /projects/{id}/console/slash`
- `POST /projects/{id}/builds` (debug 빌드 큐 등록)
- `POST /projects/{id}/files/upload`
- `POST /projects/{id}/edit` (파일 편집 저장)
- `POST /projects/{id}/git/commit`
- `POST /agents/save`, `POST /agents/{name}/delete`

다른 write endpoint (settings, build cancel, multi-console 등) 은 v0.40.x
에서 점진 적용.

### 적용된 admin 가드 (SSR)

`requireAdminOrRedirect` 가 다음 페이지에 추가됨:

- `/audit`
- `/settings` (GET + POST)
- `/backup` + `/backup/download`

기존부터 admin 가드가 있던 `/users` 는 변경 없음. `/2fa` 는 의도적으로
admin 가드 안 함 (개인 보안 설정이라 본인 관리 필요).

### `/users` UI 갱신

- role 옵션에 `viewer (read-only)` 추가.
- 역할 토글 버튼이 cycle (admin → member → viewer → admin) 로 동작.
- role 정책 hint 갱신.

### Wire change: No (SSR + 서버 내부 가드 만)

### 알려진 한계

- **JSON API** (`/api/*`) 는 여전히 role 가드 미적용. Bearer 토큰이 admin /
  member / viewer 어느 사용자 것이든 같은 권한으로 동작. 후속 minor 에서
  AuthPlugin 단계에서 가드 추가 검토.
- **WebSocket** 도 동일 — viewer 의 토큰으로도 console / build log
  stream 구독 가능. read-only 행동이라 의도된 대로 동작.
- **프로젝트별 ACL** (member 가 일부 프로젝트만 보기) 는 별도 minor.
  현재는 워크스페이스 전체 share.
- 일부 write endpoint (build cancel / multi-console / 의존성 audit /
  wrapper update 등) 는 가드 미적용. v0.40.x 에서 완성.

## [0.39.0] - 2026-05-24 — Phase 18: △ 묶음 (PWA + VSCode extension scaffold)

### Added — PWA (manifest + service worker)

브라우저가 admin UI 를 native-like 앱으로 install 할 수 있도록:

- 신규 `static/admin/manifest.json` — name / start_url / display=standalone /
  theme_color #0b0d12 / icon 512x512 maskable.
- 신규 `static/admin/sw.js` — minimal service worker:
  - install: STATIC_ASSETS (CSS / JS / icon / manifest) precache.
  - activate: 이전 버전 cache 정리, `clients.claim()`.
  - fetch: `/static/*` cache-first (opportunistic fill 포함), `/api/*` /
    `/ws/*` / `/admin/*` 는 network passthrough (실시간 상태 stale 방지).
  - `CACHE_VERSION` 을 매 minor 업데이트 시 invalidate.
- `AdminTemplates.shell` 의 `<head>` 에 manifest link + theme-color +
  service worker 등록 (`navigator.serviceWorker.register('/static/sw.js')`).

이제 모바일 / 데스크톱 브라우저에서 "홈 화면에 추가" 또는 "앱으로 설치"
가능. 오프라인 stale 페이지 표시는 안 함 (SSR 컨텐츠는 실시간성 우선).

### Added — VS Code extension scaffold (`vscode-extension/`)

별도 리포로 분리할 수도 있지만 본 리포 안에 같이 둠 (의존성 / wire 가
같이 진화).

- `package.json` + `tsconfig.json` + `src/extension.ts` (단일 파일, ~150 LOC).
- 외부 npm 의존성 0 — `@types/vscode` + `typescript` 만.
- HTTP via Node 내장 `http`/`https`.
- 5 commands:
  - **Vibe Coder: Login** (interactive — server URL + username + password +
    optional TOTP, `totp_required` 자동 처리)
  - **Vibe Coder: Server status**
  - **Vibe Coder: List projects** (quick-pick)
  - **Vibe Coder: Send prompt to project console**
  - **Vibe Coder: Trigger debug build**
- 설정: `vibeCoder.serverUrl` + `vibeCoder.token` (Global persist).
- WS subscribe / TreeView / status bar 는 후속 minor.

VS Code Marketplace publish 는 별도 단계 (vsce package + login). 본
cycle 은 scaffold + 로컬 dev 가능 (F5 → Extension Development Host).

### Web Push / WebAuthn — Roadmap 갱신만

CLAUDE.md §9 의 △ 항목 중 두 항목은 큰 작업이라 본 cycle 에선 미진행:

- **Web Push** — VAPID 키 + subscription endpoint + 알림 트리거 통합.
  EmailNotifier / WebhookNotifier 옆에 PushNotifier 추가 형태. v0.40+.
- **WebAuthn** — passkey 라이브러리 (예: `webauthn4j`) 의존성 추가 +
  credential register/verify 흐름. 2FA TOTP 의 phishing-resistant 강화
  버전. v0.40+.

### Wire change: No (static asset + 외부 ide 확장 만)

### 호환성

- 기존 사용자: PWA 는 progressive — 지원 안 하는 브라우저는 무시.
- VS Code extension 은 별도 install 필요 (현재는 dev mode 만).

## [0.38.0] - 2026-05-24 — Phase 17: Ubuntu 26.04 LTS rebase

### Changed — Base image: `noble` → `resolute`

eclipse-temurin 의 `17-jdk-resolute` / `17-jre-resolute` 태그가 Ubuntu
26.04 LTS (Resolute Raccoon) 매핑 확정:

```
PRETTY_NAME="Ubuntu 26.04 LTS"
VERSION_ID="26.04"
VERSION_CODENAME=resolute
```

JDK 17.0.19 동일.

`docker/Dockerfile` 의 builder + runtime 양쪽 stage 모두 rebase. `Dockerfile.full`
(emulator + noVNC) 도 동일하게 변경.

### 영향

- **API / 기능**: 동일. 코드 변경 없음.
- **이미지 크기**: noble (24.04) ↔ resolute (26.04) base 차이는 무시할 수
  있음 (수십 MB 수준).
- **빌드**: `apt-get install -y ...` 의 패키지 목록 그대로 동작 — 모든
  의존 패키지가 Resolute repo 에 존재.
- **호환성**: 사용자 입장에선 `docker compose pull && up -d` 한 줄로 완료.
  `vibe-coder-data` 의 모든 영구 데이터 유지.

### Wire change: No (Docker base image rebase 만)

### Known follow-up

- LTS 지원 기간 (Ubuntu 26.04 LTS = 2031년 4월까지) 동안 base 고정.
- Eclipse Temurin JDK 21 LTS 전환은 Ktor 4.x 출시와 묶어 별도 minor.

## [0.37.0] - 2026-05-24 — Phase 16: 멀티 사용자 / 팀 (1단계 — role 만)

### Schema — `admin_users.role` 컬럼 추가

`varchar("role", 16).default("admin")` — Exposed 의 nullable 없는 default
로 기존 row 도 안전하게 자동 마이그 (모든 기존 사용자 = admin).

값: `admin` / `member`. `viewer` (read-only) 는 후속 minor 에서 추가.

### Added — `/users` SSR (admin 만)

신규 `server/admin/UsersRoutes.kt`.

- `GET /users` — 전체 사용자 + role badge + 가입 / 마지막 로그인 + 동작 버튼.
- `POST /users` — 신규 사용자 (username + password + role 선택).
  PasswordPolicy / UsernamePolicy 그대로 적용.
- `POST /users/{id}/role` — admin ↔ member 토글. 마지막 admin 강등 차단.
- `POST /users/{id}/delete` — 사용자 + 모든 device row cascade. 자기 자신 /
  마지막 admin 삭제 차단.
- 모든 endpoint 가 admin role 만 (member 가 접근하면 dashboard redirect).
- Nav 메뉴 "사용자" + 키보드 단축키 `g u` 추가.
- Audit: `user.create` / `user.role.change` / `user.delete`.

### Added — `requireAdminOrRedirect(sess)` helper

`requireSessionOrRedirect` 와 chain 으로 사용. 본 cycle 에선 `/users` 만
가드. 다른 관리 페이지 (`/audit`, `/settings`, `/backup`, `/2fa`, `/agents`)
는 v0.37.x 에서 점진적으로 admin-only 로 강화.

### `WebSession.role` + `isAdmin` 추가

`requireSessionOrRedirect` 가 user.role 을 채워 session 에 노출. 템플릿에서
role 별 분기에 사용 가능.

### Wire change: No (SSR + DB schema 만)

### 호환성

- 기존 admin 사용자: schema migration 으로 자동 `role='admin'`. 영향 없음.
- 신규 사용자 default role: `member`. 명시적 admin 으로 만들려면 `/users`
  폼에서 role 변경 + 생성.
- 첫 admin (setup) 은 코드가 직접 `role="admin"` 으로 insert.

### 알려진 한계

- 본 cycle 의 가드는 `/users` 만. 다른 관리 페이지는 후속 minor 에서
  `requireAdminOrRedirect` chain 적용.
- 프로젝트별 ACL (member 가 일부 프로젝트만 보기) 은 v0.38+ scope.
- viewer role (read-only) 미구현 — admin / member 만.

## [0.36.0] - 2026-05-24 — Phase 15: Multi-agent orchestration (단순화)

### 디자인 결정

여러 sub-agent 가 같은 프로젝트 안에서 동시에 돌아가는 full orchestration
은 `ClaudeSessionManager` 가 (projectId, agentId) pair 별로 독립 child
process 를 spawn 해야 해서 큰 작업. **두 단계 단순화** 로 v0.36.0 의 가치
대부분을 더 작은 변경으로 확보:

1. **Agent dispatch API + UI hook** — Claude Code 의 표준 "Use the
   `<agent-name>` sub-agent to ..." prompt 패턴을 콘솔 dropdown 으로 안내.
   사용자가 등록된 agents 를 한눈에 보고 prompt 에 prefix 넣어주는 도우미.
2. **Multi-console** — N개 프로젝트의 콘솔을 iframe grid 로 동시 view.
   별도 sub-agent spawn 없이 multi-project orchestration use case 해결.

Full sub-agent process pool 은 v0.36.x 후속에서 별도 minor.

### Added — `GET /api/agents` (Bearer)

`AgentRoutes` 에 JSON endpoint 추가 — 등록된 agent 목록 (name + sizeBytes +
preview 200자) 반환. 콘솔 UI 가 ▼ 드롭다운 채우는 데 사용 (안드로이드
클라이언트도 같은 endpoint 활용 가능).

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:17880/api/agents
# → { "agents": [ {"name":"code-reviewer", "sizeBytes":1234, "preview":"..."}, ... ] }
```

### Added — `/multi-console?projects=id1,id2,...`

신규 `server/admin/MultiConsoleRoutes.kt`.

- 최대 6개 프로젝트 콘솔을 iframe grid 로 동시 노출.
- 같은 origin 라 cookie 인증이 그대로 흘러감 (추가 auth 호출 불요).
- 1개 → full-width / 2개 → 50:50 / 3+ → `auto-fit minmax(420px, 1fr)`.
- 프로젝트 id 화이트리스트 (`[a-zA-Z0-9._-]`) — URL 조작 차단.
- 각 pane 우상단에 ↗ 새 탭 링크.
- Nav 메뉴 "Multi-console" + 키보드 단축키 `g m` 추가.

### Wire change: No

### 알려진 한계

- iframe 6개가 각각 WebSocket 을 열어 reverse-proxy 의 연결 한도에 주의.
- Full multi-agent (한 프로젝트에서 여러 sub-agent 병렬 spawn) 은 v0.36.x
  후속 — `ClaudeSessionManager` 의 (projectId, agentId) 분기 + 별도 tab UI
  까지 큰 작업이라 별도 cycle.

## [0.35.0] - 2026-05-24 — Phase 14: 코드 분석 묶음

### Added — `/projects/{id}/wrapper` Gradle wrapper 관리

신규 `server/build/GradleWrapperService.kt`.

- `inspect(projectId)` — `gradle/wrapper/gradle-wrapper.properties` 파싱.
  현재 버전 + distributionType + raw URL 추출.
- `setVersion(projectId, newVersion, distributionType="bin")` — `distributionUrl`
  만 atomic write 로 교체. 다른 properties 보존.
- 버전 정규식 `^[0-9]+(\.[0-9]+)*(-rc-[0-9]+)?$`, distributionType `bin`/`all`
  화이트리스트 (path injection 차단).
- SSR `/projects/{id}/wrapper` 페이지 — 현재 상태 + 변경 폼 + gradle.org/releases 링크.
- Audit: `wrapper.update`.
- 프로젝트 detail 액션 카드에 "📦 Gradle wrapper" 링크.

### Added — `/projects/{id}/stats` 코드 통계

신규 `server/projects/CodeStatsService.kt`.

- 프로젝트 source 트리를 in-process walk + 확장자 기반 언어 분류
  (Kotlin / Java / Swift / Go / Rust / TS / ... 35+ 언어).
- 단순 line count (cloc 처럼 주석/공백 구분 안 함). 외부 도구 의존 없음.
- 제외: `.git`, `build`, `.gradle`, `node_modules`, `.idea`, 5 MB 초과 파일,
  바이너리 확장자 (30+).
- SSR 페이지 — 총 파일/라인/크기 카드 + 언어별 lines DESC 표 + 컬러 바.
- 프로젝트 detail 액션 카드에 "📊 코드 통계" 링크.

### Added — `/code-search` 워크스페이스 grep

신규 `server/projects/CodeSearchService.kt` + `/code-search` SSR.

- 모든 프로젝트의 source 트리를 line-by-line scan.
- 200 매치 hard cap, 5 MB 초과 파일 / 바이너리 skip.
- case-sensitive 토글, 프로젝트 필터.
- 매치 위치 `<mark>` 하이라이트, file:line 클릭 → 기존 `/projects/{id}/view` 파일 뷰어로 jump.
- Nav 메뉴 "코드 검색" + 키보드 단축키 `g f` 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 알려진 한계

- 코드 통계가 cloc 수준 정확도 아님 (주석/공백 구분 안 함). 외부 도구 통합은 후속.
- 워크스페이스 grep 이 in-process scan — 수만 파일 규모에선 ripgrep 대비 느림.
  ripgrep binary 가 컨테이너에 있으면 자동 fallback 검토 (다음 minor).
- Wrapper 변경 후 빌드 재실행은 사용자 책임 — 자동 invalidate 없음.

## [0.34.0] - 2026-05-24 — Phase 13: Backup/restore + CLI MVP

### Added — `/backup` SSR + tar.gz stream

신규 `server/admin/BackupRoutes.kt` + Apache Commons Compress 의존성.

- `GET /backup` — 워크스페이스 서브디렉토리 별 size + 다운로드 안내.
- `GET /backup/download` — tar.gz stream (Content-Disposition).
- 제외 패턴: `postgres/` (pg_dump 별도 권장), `dev-tools/gradle/caches`,
  `gradle/daemon`, `npm-cache`, `playwright`, 빌드 logs/. 일반 백업이 수십 MB.
- PostgreSQL 은 page-tear 위험으로 raw tar 안 함 — 페이지에 `pg_dump -F c`
  명령 가이드 inline.
- 복원 절차도 페이지 안에 inline (tar xzf + compose up -d).
- Nav 메뉴 "백업" 추가.

### Added — `cli/vibe` — bash + curl MVP

신규 `cli/vibe` 단일 파일 (no compile, ~150 LOC).

- `vibe login` — 대화형, 2FA 자동 감지 (totp_required → 코드 prompt).
- `vibe whoami` / `vibe logout` — token lifecycle.
- `vibe projects` / `vibe status` — JSON dump.
- `vibe console <id> <prompt...>` — 1회 prompt 발송 (WS log 는 별도).
- `vibe build <id>` — debug 빌드 큐.
- 토큰 저장: `$XDG_CONFIG_HOME/vibe-coder/config` (0600 권한).
- jq optional (있으면 pretty print).
- Go/Rust 정식 CLI + WS subscribe / 파일 업로드 는 후속 minor / 별도 리포.

### Ubuntu 26.04 LTS 베이스 이미지 — 검토만

`eclipse-temurin:17-jdk-resolute` 태그 확정 후 전환 예정. v0.34.0 에선
slim/full 모두 `noble` (24.04 LTS) 유지. 단순 base 변경이라 별도 minor
(v0.34.x) 에서 image 만 push.

### Dependency

- `org.apache.commons:commons-compress:1.27.1` (tar/gzip streaming).

### Wire change: No

### 알려진 한계

- tar.gz 가 streaming 이긴 하나 큰 워크스페이스 (수십 GB) 면 다운로드가 길어짐.
  진행률 표시 없음.
- CLI 가 WS 미지원 — 콘솔 stream 보려면 브라우저 사용 필요.

## [0.33.0] - 2026-05-24 — Phase 12: 자동화 (cron / webhook / archive)

### Added — Cron 빌드 schedule

신규 `build_schedules` 테이블 + `BuildScheduleRepository` + `BuildScheduler`.

- 매 60 s tick. cronExpr `HH:MM` (고정 시각) / `*:MM` (매시간 MM 분) / `*:*` (테스트용 매 분).
- 분 단위 dedupe (`lastFiredAt.startsWith(minute)`) — 같은 분 중복 발사 차단.
- Full vixie-cron expression 은 후속 minor.
- `/projects/{id}/automation` 페이지에 생성/활성/삭제 폼.
- Audit: `schedule.create` / `schedule.delete`.

### Added — Build webhook (외부 트리거)

신규 `build_webhook_secrets` 테이블 + `BuildWebhookSecretRepository`.

- 사용자가 `/projects/{id}/automation` 에서 "+ 새 secret" 클릭 → 32-byte
  URL-safe random secret 1회 노출. SHA-256 hex 만 DB 저장.
- 외부 호출: `POST /api/webhooks/build/{projectId}` (admin auth 없음).
  필수 헤더:
  - `X-Vibe-Secret-Id: <id>` — DB lookup 용.
  - `X-Vibe-Secret: <plaintext>` — TLS 의존, server 가 sha256 비교.
  - `X-Vibe-Signature: <hex>` — optional HMAC-SHA256(secret, body), body
    integrity 추가 보장.
- 본 cycle 의 단순화: GitHub-style HMAC 만으로 verify 하면 sender 가
  알고 있는 plaintext secret 을 server 가 모르므로 X-Vibe-Secret 도
  헤더로 받음. HTTPS reverse-proxy 필수.
- Audit: `webhook.secret.create`, `webhook.secret.delete`, `webhook.build.trigger`.

### Added — Conversation 자동 archive

신규 `ConversationArchiver` — 매 24h tick.

- `archiveAfterDays` (기본 30) 이상 inactive 한 (projectId, sessionId)
  pair 찾기.
- 해당 session 의 turn 들을 `ConversationExportService.ExportEnvelope` JSON
  으로 dump → `<workspace>/.vibecoder/<projectId>/archive/session-<sid>.json`.
- dump 성공 시 `conversation_turns` 에서 해당 row 삭제 (DB 부담 ↓).
- 복원: `ConversationExportService.importToProject(json)` 그대로 사용.
- 별도 archive 테이블 안 만듦 — 파일 시스템에 두면 backup / 외부 export 단순.

### Wire change: No (SSR + 외부 webhook endpoint 만)

### 알려진 한계

- Webhook 인증이 X-Vibe-Secret 헤더 plaintext 전송 — TLS 필수. GitHub-style
  HMAC-only 검증으로 강화는 후속 minor (sender 가 보낸 candidate-plaintext
  를 서버가 lookup-then-verify 하는 방식).
- Cron expression 이 HH:MM / *:MM / *:* 만 지원. 요일/월/특정 날짜는 후속.
- Archive 의 dump-then-delete 가 atomic 하지 않음 — server crash 시 dump 성공
  + 삭제 실패면 다음 tick 에서 idempotent re-run (`Files.exists(target)` skip).

## [0.32.0] - 2026-05-24 — Phase 11: 운영 도구 (deps audit / env files / log search)

### Added — `/projects/{id}/deps` — Gradle 의존성 audit

- 신규 `server/build/DependencyAudit.kt` + `DependencyAuditRoutes.kt`.
- `./gradlew :{module}:dependencies --configuration <cfg>` 실행 → raw output +
  `group:name:version` 좌표 추출 (정규식, version conflict resolution `->`
  처리).
- 모듈명 / configuration 파라미터화 (기본 `releaseRuntimeClasspath`).
- 90 s timeout, raw output 200 KB cap.
- 알려진 CVE 매칭은 후속 minor (OWASP dependencyCheckAnalyze / osv-scanner
  통합 검토).
- 프로젝트 detail 페이지에 "🧩 의존성 audit" 액션 링크.

### Added — `/projects/{id}/env-files` — Env / Build 파일 빠른 편집

- 신규 `server/projects/EnvFilesRoutes.kt`.
- 화이트리스트 7개 파일 (`local.properties`, `gradle.properties`, `.env`,
  `.env.local`, `app/build.gradle.kts`, `build.gradle.kts`,
  `settings.gradle.kts`) 만 노출 — path traversal 차단.
- Atomic move 저장 (빌드 중 race 안전), 256 KB cap.
- 비밀 파일 (`.env` / `.properties`) 에 노출 경고 hint.
- 프로젝트 detail 페이지에 "⚙ Env / Build 파일" 액션 링크.

### Added — `/logs` — 빌드 로그 가로질러 grep

- 신규 `server/admin/LogSearchRoutes.kt`.
- 워크스페이스의 모든 `.vibecoder/<projectId>/logs/<buildId>.log` 를 검색.
- 각 파일 마지막 2 MB 만 scan (큰 빌드 로그 성능 보호).
- 200 매치 hard cap, ts 자동, project 필터 옵션.
- 매치 라인 `<mark>` 하이라이트 (case-insensitive).
- Nav 메뉴 "빌드 로그 검색" + 키보드 단축키 `g l` 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 알려진 한계

- 의존성 audit 의 CVE 매칭이 빠져 있음 — 현재는 단순 트리 + 좌표 추출.
- env-files 의 화이트리스트는 hard-coded — 사용자 정의 패턴 미지원.
- log search 가 server stdout 로그는 못 봄 (Docker logs 로 확인 필요).

## [0.31.0] - 2026-05-24 — Phase 10: Claude 통합 강화

### Added — `/agents` 디렉토리 관리

신규 `server/env/AgentRegistry.kt` + `AgentRoutes.kt` — `~/.claude/agents/*.md`
custom sub-agent 파일을 UI 에서 CRUD.

- 이름 sanitize (`[A-Za-z0-9._-]{1,64}`, 숨김파일 차단).
- 본문 64 KB cap, atomic write (`tmp` → `move REPLACE_EXISTING`).
- 목록 페이지에 미리보기 (첫 600자) + 마지막 수정 시각 + 삭제 버튼.
- Edit 페이지에서 본문 수정.
- Nav 메뉴 "Agents" 추가.
- Audit: `agent.save` / `agent.delete` 액션 신규.

### Added — 대화 export / import

신규 `server/claude/ConversationExportService.kt` — 프로젝트 단위
`conversation_turns` JSON envelope export + import.

- `GET /projects/{id}/history/export` — `application/json` + Content-Disposition
  으로 즉시 다운로드. 페이지네이션 loop (1000 개씩, 100 K turn safety cap).
- `POST /projects/{id}/history/import` (multipart, `?_csrf=` query) —
  envelope 검증 + sessionId 단위 idempotency. `?dryRun=true` (기본) 으로
  미리보기, `?dryRun=false` 면 실제 INSERT.
- 5 MB 업로드 cap, schema `version 1` 명시.
- History 페이지 헤더에 📥 다운로드 + 📤 가져오기 토글 폼.

### Added — Prompt 자동완성 API

신규 `server/claude/PromptSuggestionService.kt` + JSON endpoint.

- `GET /api/projects/{projectId}/claude/prompt-suggestions?prefix=<text>` →
  `{ "suggestions": [...] }`.
- 같은 프로젝트의 `user` role turn 중 prefix 매치 (LIKE) → 최근 사용 우선
  + 짧은 prompt (<10자) 제외 + 첫 줄만 (최대 200자).
- In-memory cache 60 s — 사용자가 매 키스트로크마다 DB hit 안 함.
- 안드로이드 클라이언트 / 콘솔 JS 양쪽이 활용 가능 (Bearer 인증).

### Wire change: No (server-side only — 클라이언트가 새 endpoint 호출 시 자동 사용)

### 알려진 한계

- export/import 의 idempotency 가 sessionId 단위라, 같은 세션을 부분 import
  못 함 (전체 skip). row 단위 merge 가 필요해지면 다음 cycle.
- prompt suggestion 은 prefix only (n-gram / fuzzy 미지원). 큰 history
  에서 LIKE 가 느려질 수 있음. v0.32+ 에서 tsvector 검토.

## [0.30.0] - 2026-05-24 — Phase 9: UX 묶음 (history chart / global search / keyboard)

### Added — 빌드 history 차트

빌드 목록 페이지 (`/projects/{id}/builds`) 상단에 최근 30개 빌드의 inline
SVG line chart 표시. 외부 라이브러리 없음 (서버 사이드 SVG 생성).

- X 축: 시간 순 (oldest → newest).
- Y 축: duration (s). 초록 line = SUCCESS 연결, 빨강 점 = FAILED / TIMEOUT,
  회색 = CANCELED, 노란 사각 = APK 크기 (보조 축).
- 점 hover 시 빌드 id + 상태 + duration tooltip (`<title>`).

### Added — 글로벌 대화 검색 (`/history`)

기존 프로젝트별 `/projects/{id}/history` + `/chat/history` 외에 모든
프로젝트의 `conversation_turns` 를 가로질러 grep 하는 페이지.

- 신규 `server/claude/GlobalHistorySearchRoutes.kt` — `q` + `role` 필터.
- LIKE escape (`\` / `%` / `_`) → SQL injection 차단.
- 매치 위치 ±100자 발췌 + `<mark>` 하이라이트 (case-insensitive).
- 200개 hard cap, ts DESC 정렬.
- 빈 검색어 = 빈 결과 (전체 dump 방지).
- Nav 메뉴 "대화 검색" 항목 추가.

### Added — 키보드 단축키

신규 `static/admin/keyboard.js` — `defer` 로드, 모든 SSR 페이지 적용.
입력 필드 focus 시 / 모디파이어 키 동반 시 무시.

| 단축키 | 동작 |
|---|---|
| `g p` | /projects |
| `g c` | /chat |
| `g h` | /history (글로벌 검색) |
| `g e` | /env-setup |
| `g s` | /settings |
| `g a` | /audit |
| `g d` | / (대시보드) |
| `?` | 단축키 도움말 오버레이 토글 |
| `Esc` | 오버레이 닫기 |

2키 시퀀스는 800 ms timeout. 도움말 오버레이는 inline HTML (외부 자원 없음).

### Wire change: No (SSR + 정적 자원 만)

### 알려진 한계

- 글로벌 검색은 LIKE 기반 — 수만 turn 누적 시 느려질 수 있음. PostgreSQL
  `tsvector` 인덱스 도입은 다음 cycle (별도 minor) 검토.
- 빌드 history 차트는 최근 30개 hard-coded; 사용자가 페이지 단위 / 기간
  필터링 못 함. 큰 작업이라 v0.30.x 후속.

## [0.29.0] - 2026-05-24 — Phase 8: 프로젝트 zip 다운로드 + 디스크 사용량 모니터링

### Added — `ProjectArchiver` + `GET /projects/{id}/zip`

- 신규 `server/projects/ProjectArchiver.kt`:
  - 프로젝트 source 트리를 `java.util.zip.ZipOutputStream` 으로 직접 stream
    (메모리 폭발 없음).
  - 제외 패턴: `.git`, `build`, `.gradle`, `node_modules`, `.idea`, `*.apk`,
    `*.aab` — source 본질만 추출.
- `GET /projects/{id}/zip` SSR 라우트 — `Content-Disposition` 으로 브라우저
  zip 다운로드 트리거, 파일명 `<projectId>-source-<yyyyMMdd-HHmm>.zip`.
- 프로젝트 detail 페이지에 "Source zip 다운로드" 액션 링크.

### Added — `DiskMonitor` + 대시보드 카드

- 신규 `server/disk/DiskMonitor.kt`:
  - `measureNow()` — `Files.getFileStore(root)` 의 total/usable 로 사용 % 계산.
  - `start()` — 10분 주기 폴링. `usedPercent >= warnThresholdPercent` (기본 85%)
    로 transition 시 1회 `Notifiers.diskUsageWarn` (이메일 + webhook). 회복 시
    상태 reset → 재발송 가능. 30분 cooldown.
  - `warnThresholdPercentProvider = { config.email.diskUsageWarnPercent }` —
    기존 이메일 임계치 값 재사용 (별도 webhook 임계치 분리는 v0.30+).
- 대시보드에 디스크 사용량 카드 — 사용 % / 총 / 가용 GB + 컬러 바 (85%↑ 노랑,
  95%↑ 빨강).
- 캐시 정리 페이지 (`/settings/cache`) 로의 link 표시.

### Wiring

- ServerContext: `projectArchiver` + `diskMonitor` 추가.
- ServerMain: 두 서비스 생성 + `diskMonitor.start()` + shutdown hook 에 양쪽 등록.
- AdminRoutesDeps: `diskMonitor` 추가 → dashboard 카드 렌더링.
- WebProjectRoutes: `projectArchiver` 파라미터 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

## [0.28.0] - 2026-05-24 — Phase 7: APK 시그너처 검증 + 빌드 캐시 관리

### Added — `ApkSignerInspector` (apksigner verify wrapper)

- 신규 `server/artifacts/ApkSignerInspector.kt`:
  - `$ANDROID_HOME/build-tools/<latest>/apksigner verify --verbose --print-certs <apk>`
    실행 후 출력을 정규식으로 파싱.
  - 추출 항목: 활성 schemes (v1/v2/v3/v4), Signer #N (Subject DN + SHA-256 fingerprint),
    verified 여부 (exit 0 + scheme 1개 이상 + "DOES NOT VERIFY" 없음).
  - SDK / build-tools 미설치 시 graceful error 메시지.
  - 30s timeout, raw output 4000자 cap.
- 빌드 상세 페이지의 APK 카드 안에 검사 결과 inline 표시 — verified 배지 +
  활성 schemes + Signer 별 DN/SHA-256.

### Added — `BuildCacheService` + `/settings/cache` SSR

- 신규 `server/build/BuildCacheService.kt`:
  - `measure()` — `~/.gradle/caches`, `~/.gradle/daemon`, `~/.android/cache`,
    `~/.npm/_cacache` 디렉토리 크기 합산 (Files.walk + size).
  - `cleanup(target)` — bottom-up walk + Files.delete, 디렉토리 자체는 보존
    (graceful skip on permission errors).
  - Target enum: `GRADLE_CACHES`, `GRADLE_DAEMON`, `ANDROID_CACHE`, `NPM_CACHE`.
- 신규 `server/build/BuildCacheRoutes.kt` (`/settings/cache`) — 4개 target 의
  현재 크기 표 + per-target "정리" 버튼 (CSRF + confirm 다이얼로그). 빌드
  진행 중 cleanup race 안내.
- Nav 메뉴에 "빌드 캐시" 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 호환성

- SDK 미설치 환경: APK 카드의 서명 검사 섹션이 "ANDROID_HOME 미설정" graceful
  안내. 빌드 디테일 페이지 자체는 영향 없음.
- 빌드 캐시 cleanup 은 다음 빌드의 의존성 재다운로드를 유발 — 사용자가 명시
  click 한 경우만. confirm 다이얼로그 + 빌드 0개일 때만 권장.

## [0.27.0] - 2026-05-24 — Phase 6: Slack / Discord / Telegram webhook

### Added — `WebhookNotifier` + `/settings/webhook` SSR

기존 `EmailNotifier` 와 같은 트리거 (빌드 결과 / Claude 사용량 / 디스크 임계치)
에 병렬 발송. JDK 11+ 표준 `java.net.http.HttpClient` 만 사용 (외부 의존성 없음).

- 신규 `server/notify/WebhookNotifier.kt`:
  - Provider 별 payload 빌더: Slack (`text` + 코드블록), Discord (`content` cap 2000),
    Telegram (`text` + Markdown).
  - **SSRF 방어 화이트리스트**: Slack=`hooks.slack.com`, Discord=`discord.com` /
    `discordapp.com`, Telegram bot token 정규식 `^\d+:[A-Za-z0-9_-]+$`.
  - Fire-and-forget `send()` + 동기 `sendNow()` (테스트용, provider 별 결과 맵 반환).
  - 10s connect/request timeout, `Redirect.NEVER` (cross-origin SSRF 차단).
- 신규 `server/notify/Notifiers.kt` — Email + Webhook 통합 facade. 호출처
  (`BuildService`, `ClaudeUsageMonitor`) 는 facade 하나만 알면 됨.
- 신규 `server/notify/WebhookSettingsRoutes.kt` — `/settings/webhook` SSR.
  현재 설정 (값은 가려서 "set/empty" 만) + provider 별 setup 가이드 (Slack
  Incoming Webhook / Discord Webhook / Telegram BotFather) + 테스트 메시지
  전송 폼.

### Added — `WebhookSection` 설정

```yaml
webhook:
  enabled: false
  slackUrl: ""
  discordUrl: ""
  telegramBotToken: ""
  telegramChatId: ""
```

env override (`VIBECODER_WEBHOOK_ENABLED` / `_SLACK_URL` / `_DISCORD_URL` /
`_TELEGRAM_BOT_TOKEN` / `_TELEGRAM_CHAT_ID`) 는 server.yml 의 같은 키와 매칭.
부분 활성 가능 (예: Slack 만).

### Refactored — `BuildService.notifier` 타입 변경

- 기존 `EmailNotifier?` → `Notifiers?` 로 교체. 외부 호출 시그니처 동일.
- `ClaudeUsageMonitor.emailNotifier` 파라미터도 `notifiers: Notifiers` 로 교체.
  WebhookNotifier 가 같은 임계치 알림을 받음.

### Wire change: No (SSR + 서버 내부 wiring 만)

### Nav 메뉴

좌측 nav 에 "Webhook 알림" 항목 추가 (이메일 알림 옆).

## [0.26.0] - 2026-05-24 — Phase 5: 2FA TOTP + Session timeout

### Wire change: **Yes** (additive only)

- `LoginRequestDto.totpCode: String?` — 2FA 활성 사용자는 password 통과 후
  6자리 코드 동봉으로 같은 endpoint 재호출. 비활성 사용자는 영향 없음.
- 신규 에러 코드: `totp_required` (401), `invalid_totp` (401).

### Added — TOTP (RFC 6238) self-contained 구현

- 신규 `server/auth/Totp.kt` — `generateSecret()` / `otpauthUri()` / `verify()` +
  `Base32` encoder/decoder. JDK `javax.crypto.Mac` (HmacSHA1) 만 사용, 외부 의존성
  없음 (~150 LOC). Google Authenticator / 1Password / Authy 호환.
- `AdminUsers` 스키마: `totp_secret`, `totp_enabled_at` nullable 컬럼 추가.
- `AdminUserRow.totpEnabled` derived flag + `enableTotp(id, secret)` /
  `disableTotp(id)` 메소드.
- `AuthService.login(... totpCode: String? = null)` — totpEnabled 사용자 흐름:
  password 통과 → 코드 null 이면 `totp_required` → 클라이언트가 사용자 코드
  받아 재시도 → 검증 후 토큰 발급. window=1 (±30s drift 보정).

### Added — `/2fa` SSR 페이지

- 신규 `server/admin/TwoFactorRoutes.kt`:
  - GET `/2fa` — 비활성 시 pending secret 표시 (otpauth URI + 4글자씩 끊은
    Base32) + 6자리 코드 검증 폼. 활성 시 활성화 시각 + 비활성화 폼.
  - POST `/2fa/enable` / `/2fa/disable` — CSRF + audit.
- 좌측 nav 메뉴에 "2단계 인증" 추가.
- SSR `/login` 폼이 2단계 진입 시 username/password hidden 보존 + 코드 입력
  필드만 노출.

### Added — Session idle timeout

- `SecuritySection.sessionIdleTimeoutMinutes: Int = 30` (0 = 무제한).
- `AuthPlugin` Bearer authenticate 콜백 + SSR `requireSessionOrRedirect` 양쪽이
  같은 정책 적용 — `device.lastSeenAt` 가 N 분 이전이면 자동 폐기 (`deleteById`)
  + 401 / `/login?err=session_timeout` redirect.
- AuditLogger: `sessionTimeout(userId, deviceId, ip)` + `auth.session.timeout` 액션.

### Config (`server.yml`)

```yaml
security:
  sessionIdleTimeoutMinutes: 30
```

### Android sync 권고

- `LoginRequestDto.totpCode` 가 default null 이라 wire-compatible. 안드로이드
  클라이언트는 후속 minor (v0.7.3) 에서 `totp_required` 응답 시 별도 코드 입력
  화면을 노출하면 됨.

## [0.25.0] - 2026-05-24 — Phase 4 (2/2): `:full` 이미지 publish + compose 가이드

### Added — `docker/compose.full.yml` example

- 슬림 `compose.yml` 과 함께 (`-f compose.yml -f compose.full.yml`) 또는 단독
  override 로 사용. 차이점:
  - `image:` 가 `siamakerlab/vibe-coder-server:full` 로 교체 (~3-4GB).
  - `devices: ["/dev/kvm:/dev/kvm"]` — KVM passthrough.
  - `group_add: ["${KVM_GID:-104}"]` — host kvm 그룹 GID 매칭.
  - `ports: ["6080:6080"]` — noVNC HTTP+WS 게이트웨이 노출.
- `.env` 에 추가할 변수 3개 가이드: `VIBECODER_IMAGE_FULL`, `KVM_GID`,
  `VIBE_NOVNC_PORT`. 모두 default 값 보유.

### Added — `/emulator` 페이지의 `:full` 가이드 카드

기존 "Roadmap" placeholder 카드를 v0.25.0 의 실제 setup 가이드로 교체:
1. compose override + .env 환경 변수 셋업.
2. AVD 생성 + headless 시작 (v0.24.0 lifecycle 폼 재사용).
3. 브라우저 noVNC 접근 + SSH 터널 권장 명령어.

### Docker Hub — `siamakerlab/vibe-coder-server:full` 첫 publish

- amd64-only (KVM 은 host arch 의존이므로 cross-build 무의미).
- 본 cycle 부터 정식 publish. 이전엔 Dockerfile.full scaffold 만 존재했고
  실제 image 는 없었음.

### Security note (LAN-only)

- noVNC 6080 은 **인증 없는 raw VNC 게이트웨이**. LAN 격리 또는 SSH 터널
  (`ssh -L 6080:localhost:6080 user@host`) 가정. 외부 IP 직접 노출 시 vibe-coder
  admin 인증과 무관하게 emulator 화면이 그대로 보임 → security risk.
- 인증된 reverse-proxy 통합 (vibe-coder admin 세션 + iframe 임베드) 은 v0.26+
  scope. Ktor 서버 측 WebSocket proxy 추가 + noVNC 정적 자원 same-origin 제공
  형태로 검토 중 (의존성 부담 vs 보안 이득 trade-off).

### KVM passthrough = 컨테이너 격리 약화

`devices: [/dev/kvm]` 는 host kernel 인터페이스 직접 접근. 신뢰된 admin
단일 사용자 환경 (CLAUDE.md §1) 에서만 사용. 멀티테넌트 환경에선 권장 안 함.

### Wire change: No (Docker + SSR 가이드만)

## [0.24.0] - 2026-05-24 — Phase 4 (1/2): Emulator AVD lifecycle + :full entrypoint

### Added — `EmulatorService` 확장 + SSR lifecycle 폼

- `EmulatorService.createDefaultAvd(name, apiLevel)` — `vibe-default` 자동 생성.
  `avdmanager` 호출 + hardware-profile prompt 자동 "no" 응답 + 화이트리스트 이름
  검증 (shell injection 차단).
- `EmulatorService.launchAvd(name, noWindow=true)` — 백그라운드 emulator 실행.
  `-no-window` `-no-audio` `-no-boot-anim` 옵션 + 부모 종료와 분리되는 detached
  방식. 슬림 이미지에선 KVM 없어 software 모드로 떨어짐 (10× 느림 안내).
- `EmulatorService.stopAvd(serial)` — `adb -s <serial> emu kill` 호출 + 10s timeout.
- `/emulator` 페이지에 AVD lifecycle 카드: "디폴트 AVD 생성" / "headless 시작" /
  실행 중인 emulator 마다 "■ 종료" 버튼. flash banner (ok/err) + CSRF.
- POST 라우트 3개 — `/emulator/avd/create-default`, `/emulator/avd/launch`,
  `/emulator/avd/stop`. 각 audit log 항목 (`emulator.avd.create / .launch / .stop`).

### Added — Docker `Dockerfile.full` entrypoint 완성

- 신규 `docker/emulator-entrypoint.sh` — Xvfb (`:99`, 1080x1920x24) + fluxbox +
  x11vnc (`localhost:5900`) + websockify (`localhost:6080` → noVNC 정적 자원).
  모든 데몬은 internal-only (외부 노출은 v0.25.0 의 reverse-proxy 통해 인증
  boundary 안으로 끌어들임).
- `Dockerfile.full` 의 ENTRYPOINT 가 `emulator-entrypoint.sh` 로 전환. 슬림 본
  `entrypoint.sh` 는 위임 호출로 그대로 재사용 → 코드 중복 없음.
- Dockerfile.full TODO 주석 진행 상태 갱신 (1, 3, 4 완료 / 5, 6 v0.25.0 예정).

### Why two-step rollout

- v0.24.0: 서버 코드 + lifecycle UI + entrypoint = "Mac/Linux 호스트에서
  컨테이너 안 emulator 가 켜진다" 까지. 사용자가 VNC 클라이언트 직접 연결.
- v0.25.0: `/emulator/vnc/` SSR reverse-proxy → 브라우저에서 인증된 admin 만
  noVNC 페이지를 iframe 으로 확인. compose.yml :full 변형 docs.

### Wire change: No (SSR + Docker)

### 호환성

- 슬림 이미지 사용자: 신규 lifecycle 폼은 보이나, SDK 가 설치돼 있어야 동작.
  안 되면 graceful error (안내 메시지). 기존 진단 페이지 동작 영향 없음.
- :full 이미지 (개념적 v0.24.0 prebuild — 본 cycle 에선 Dockerfile 만 push,
  실제 image push 는 v0.25.0 마일스톤에서) 는 entrypoint 가 데몬들을 자동 부팅
  하지만 vibe-coder-server 자체는 슬림과 동일하게 시작.

## [0.23.0] - 2026-05-24 — Phase 3: TestFlight 자동 업로드

### Added — `TestFlightPublishService` + Build Detail 페이지 카드

- 신규 `server/publish/TestFlightPublishService.kt`:
  - `precheck()` — `app-store-connect` MCP 설치/등록 + `ASC_KEY_ID` /
    `ASC_ISSUER_ID` / `ASC_PRIVATE_KEY_FILE(.p8)` 존재 여부 검사.
  - `trigger(projectId, ipaPath, distributionGroups?, releaseNotes?)` —
    `app-store-connect` MCP 로 .ipa 업로드 prompt 발송. compliance 같은
    사용자 결정 단계는 자동 진행 안 함.
- 빌드 상세 페이지 (`/projects/{id}/builds/{buildId}`) 에 **TestFlight
  업로드** 카드 — Play 카드와 달리 빌드 status 무관하게 항상 노출 (vibe-coder
  는 iOS 빌드 안 함 → 빌드 SUCCESS 와 연동 무의미).
- POST 라우트 `/projects/{id}/builds/{buildId}/testflight-upload` — CSRF
  보호, 성공 시 콘솔 페이지로 redirect.
- AuditLogger: `testFlightUploadTriggered` / `testFlightUploadFailed`.

### Why iOS 빌드는 직접 안 함

- macOS + Xcode 필수 — Linux 컨테이너 범위 밖.
- 사용자가 별도 macOS 빌드 농장 (Mac mini / 본인 Mac) 에서 산출한 .ipa 를
  vibe-coder 워크스페이스에 올린 시나리오만 지원 (`scp` / git lfs / shared
  mount). `Roadmap §C.△` 의 fastlane 통합은 후속.

### Wire change: No (SSR + 서버 내부 wiring 만)

## [0.22.0] - 2026-05-24 — Phase 2: Play Console 자동 업로드

### Added — `PlayPublishService` + Build Detail 페이지 카드

빌드 성공 후 산출된 AAB 를 Google Play Console (Internal / Alpha / Beta /
Production) 로 업로드하는 워크플로. **직접 Google API 를 호출하지 않고**
MCP `google-play-publisher` 를 통해 Claude 에게 작업 위임 — vibe-coder 의
일관된 디자인 원칙 (Claude + MCP 가 모든 외부 통신 담당) 유지.

- 신규 `server/publish/PlayPublishService.kt`:
  - `precheck(packageName)` — MCP 설치/등록 상태 + Service Account JSON 존재
    여부 + packageName 일치 여부 검사. `Precheck(ready, mcpStatus, warnings)`.
  - `trigger(projectId, aabPath, track, releaseNotes?)` — 콘솔 세션에 정형
    prompt 전송 (track 화이트리스트 검증 → prompt injection 방지).
- 빌드 상세 페이지 (`/projects/{id}/builds/{buildId}`) 에 **Play Console
  업로드** 카드 — 상태가 SUCCESS 일 때만 노출. 폼: AAB 경로 (기본
  `app/build/outputs/bundle/release/app-release.aab`) + track 선택 +
  Release notes. 사전조건 부족해도 폼은 노출되어 사용자가 prompt 를 우선
  보내본 후 Claude 응답으로 부족한 점 재확인 가능.
- POST 라우트 `/projects/{id}/builds/{buildId}/play-upload` — CSRF 보호,
  성공 시 콘솔 페이지로 redirect (Claude 진행을 라이브로 확인).
- AuditLogger: `playUploadTriggered` / `playUploadFailed` + `Actions.PLAY_UPLOAD`
  (`publish.play.upload`) action constant.

### Why MCP 위임 (직접 API 호출 안 함)

- google-api-services-androidpublisher 의존성 추가 시 이미지 크기 ↑
- OAuth 토큰 / Service Account 키 lifecycle 을 서버 코드가 직접 관리하면
  보안 표면이 넓어짐 — MCP 가 표준 방식으로 처리
- 업로드 진행 / 에러는 자연어로 Claude 가 사용자에게 즉시 설명 → UX 일관성

### Wire change: No

이번 페이즈는 SSR + 서버 내부 wiring 만 추가. ApiPath / DTO 변경 없음.

### Android sync

영향 없음 — Android 클라이언트는 본 기능을 후속 minor (v0.7.x) 에서 빌드
페이지에 같은 폼을 추가하면 됨. 우선 web UI 만 release.

## [0.21.0] - 2026-05-24 — Phase 1: Claude 사용량 시각화 + 임계치 알림

### Wire change: **Yes** (additive only)

`ClaudeStatusDto` 에 두 필드 추가 (둘 다 nullable, default null → backward-compatible):

- `usagePercent: Int?` — `/status` 출력에서 추출된 사용량 0~100. "X% remaining"
  은 자동으로 `100 - X` 로 변환해 "used" 의미로 통일.
- `resetAt: String?` — quota reset 시각 (free-form, CLI 출력 그대로).

### Added — `ClaudeUsageMonitor` 백그라운드 폴링 + 이메일 트리거

- 신규 `server/claude/ClaudeUsageMonitor.kt` — `pollIntervalMinutes` 주기로
  대표 프로젝트 (`__scratch__` 기본) 의 `ClaudeStatusService.snapshot()` 호출,
  `usagePercent` 가 warn (기본 80%) / critical (기본 95%) 임계치를 처음 넘는
  transition 에서만 `EmailNotifier.claudeUsageWarn()` 발송. 재발송은 10분
  cooldown + 임계치 transition 이 다시 일어날 때만.
- 임계치 아래로 내려가면 상태 reset → 다음 cycle 에서 재발송 가능.
- `start()` 는 부팅 직후, `shutdown()` 은 JVM shutdown hook 에 연결.

### Added — `ClaudeStatusService.parseOutput` percent / resetAt 추출

- "quota|remaining|usage" 줄에 `\d+%` 패턴 있으면 `usagePercent` 로 캡쳐.
  "remaining" 단어 동반 시 자동 flip (20% remaining → 80% used).
- "reset" 단어 + "at|in" 동반 줄을 `resetAt` 로 캡쳐.

### Added — 대시보드 Claude 사용량 카드

- `AdminTemplates.dashboardPage` 에 카드 추가 — usagePercent / plan / model /
  reset 시각 + 컬러 바 (80%↑ 노랑, 95%↑ 빨강, 그 외 초록).
- snapshot 미수집 / percent 파싱 실패 시 graceful degrade (안내 메시지).

### Config — `claude.usage` 신규 섹션 (`server.yml`)

```yaml
claude:
  usage:
    enabled: true
    pollIntervalMinutes: 5
    warnThresholdPercent: 80
    criticalThresholdPercent: 95
    scratchOnly: true
```

`scratchOnly=true` (기본) 면 모든 프로젝트가 같은 Claude 계정을 공유한다는
단일-사용자 가정 하에 `__scratch__` 만 폴링해 비용 최소화. `false` 로 두면
프로젝트 전체를 돌며 max usage 채택.

### Android sync 권고

- `vibe-coder-android` 리포 `shared/` 의 `ClaudeStatusDto` 도 두 필드 추가
  필요. UI 노출은 후속 minor (v0.7.3) 에서 콘솔 헤더에 작은 percent badge.

## [0.20.0] - 2026-05-24 — Prompt template wire 정식화

### Wire change: **Yes** (additive only)

서버는 v0.13.0 부터 `/api/prompt-templates` 를 노출했고 응답 모양은 그대로
이지만, 그동안 `shared/` 모듈에 정식 wire DTO 가 없어 안드로이드 client 가
ad-hoc Json 으로 파싱해야 했다. v0.20.0 부터:

- `shared/.../ApiPath.kt`: `const val PROMPT_TEMPLATES = "/api/prompt-templates"`.
- `shared/.../dto/Dtos.kt`: `PromptTemplateDto`, `PromptTemplateListResponseDto`.
- `PromptRoutes` 의 `GET /api/prompt-templates` 가 새 wire DTO 로 응답
  (필드 동일: id / title / category / body / createdAt / updatedAt).

이전 응답 (`PromptListDto`) 과 JSON 모양이 같아 backward-compatible.
브라우저 콘솔 JS (`fetch('/api/prompt-templates')`) 도 그대로 동작.

### Android sync 권고

- `vibe-coder-android` 리포 `shared/` 도 동일 entry 동기 (v0.7.2 에서 반영).
- 신규 UI: `QuickActionSheet` 안에 `PromptTemplatesSection` 통합 — 카테고리
  탭 + 본문 칩 row → 두 번 탭으로 입력란에 paste.

### 호환성

- 신규 wire 만 추가, 기존 wire 제거 / 변경 없음.
- 구버전 (v0.7.1 이하) 안드로이드 클라이언트는 영향 없음 (해당 endpoint 를
  호출하지 않음). 신버전 (v0.7.2+) 은 새 DTO 로 안전하게 파싱.

## [0.19.0] - 2026-05-24

Phase 5 — Android Emulator (scaffolding + 진단 + ADB 통합).

### Added — `/emulator` 페이지 + `EmulatorService`

- `EmulatorService.diagnose()` — KVM (`/dev/kvm`), Android SDK, emulator binary,
  adb binary, 설치된 AVD 목록, 실행 중인 device 목록 검출. shell injection
  안전 (List<String> ProcessBuilder).
- `EmulatorService.installApk(deviceSerial, apkPath)` — 실행 중인 emulator 에
  ADB 로 APK 설치. 60초 timeout.
- `/emulator` SSR 페이지 — 진단 + 권장 사항 + AVD 생성 / emulator launch
  수동 가이드 (docker exec 명령 inline).
- nav 메뉴 "Emulator" 추가.

### Why scaffolding only (not full automation)

- KVM passthrough 는 compose 의 `devices: [/dev/kvm:/dev/kvm]` + 호스트 kvm
  그룹 설정 필요. 1인 LAN 도구라 환경마다 가용성 다름.
- 풀 자동화 + noVNC 미러는 base image 부피 (qemu/x11/websockify) 대폭 증가
  유발 — 별도 image variant (`siamakerlab/vibe-coder-server:full`) 로 분리
  예정 (v0.20+).
- 본 cycle 은 **진단 + ADB 통합** 만 — 운영자가 수동으로 emulator 띄운 후
  본 페이지에서 device 가 인식되면 그 이후엔 콘솔에서 Claude 가 ADB 로 APK
  설치 + UI 자동화 가능 (이미 동작).

### Wire change

없음. SSR only — JSON API 는 풀 자동화 cycle 에서 추가.

## [0.18.0] - 2026-05-24

Phase 4 — Git push + 프로젝트 templating.

### Added — GitWriter + commit/push UI

- `GitWriter.commitAndPush` — `git add` (전체 또는 tracked only) + commit
  (author env 주입) + optional push origin/branch. push 실패해도 commit 유지.
  shell injection 안전 (List<String> ProcessBuilder).
- `POST /api/projects/{id}/git/commit` — JSON API (Bearer 인증).
  `GitCommitRequestDto(message, push, onlyTracked)` →
  `GitCommitResponseDto(committed, pushed, branch, sha, log)`.
- `/projects/{id}/git` SSR — 기존 read-only view 에 commit/push 폼 추가
  (csrf + push checkbox + only-tracked checkbox).
- AuditLogger.gitCommit (git.commit action).

### Added — 프로젝트 시작 템플릿

- `ProjectTemplates.all` — 6 종: empty / compose-basic / compose-mvvm-hilt /
  compose-mvvm-room / wear-os / android-tv.
- `RegisterProjectRequestDto.templateId` 신규 (additive, default null).
- 신규 프로젝트 폼 dropdown 으로 노출.
- 등록 직후 첫 console 진입 시 starter prompt 가 textarea 에 자동 입력
  (한 번 소비 후 제거). 사용자는 Enter 만 누르면 Claude 가 scaffolding 시작.

### Wire change: Yes

- `ApiPath.gitCommit(projectId)` 추가.
- `RegisterProjectRequestDto.templateId` 신규 nullable 필드.
- vibe-coder-android `shared/` 동기 필요 (별도 후속 commit).

## [0.17.0] - 2026-05-24

Phase 3 — SMTP 이메일 알림 + Android Client Guide 의 v0.13.0+ 통합 가이드.

### Added — SMTP 알림 (`EmailNotifier`)

- `EmailSection` (host / port / user / password / passwordFile / from / to /
  tls / claudeUsageWarnPercent / diskUsageWarnPercent).
  env override 모두 가능 (VIBECODER_SMTP_*).
- `EmailNotifier` — Jakarta Mail + Angus implementation. send / sendNow.
  비활성 시 no-op. 발송 실패는 server log 로만.
- `/settings/email` SSR (read-only viewer + 테스트 메일 전송 버튼). nav 메뉴 추가.
- `BuildService` 가 빌드 완료 시 (SUCCESS / FAILED) 자동 알림.

### Docs — Android Client Guide 의 v0.13.0+ 통합 가이드

사용자 요청. 다음 4가지를 Android client 에 통합하는 방법:
- Turn cancel — Retrofit interface + UI rule (■ stop 버튼, console_done /
  process_crashed 등에서 자동 hide).
- Prompt templates — `/api/prompt-templates` 사용 + QuickActionSheet UX.
  CRUD UI 는 본 client v1 scope 외 (browser 에서 관리).
- General Chat — synthetic projectId `__scratch__` 로 기존 console 화면 재사용.
  BottomNav 5탭 또는 Home entry 두 옵션.
- File tree/viewer — 본 cycle 은 SSR 만. JSON API 는 다음 minor 에 예정 —
  Android 통합도 그 때.

### Wire change

없음. REST API 추가 (`/api/prompt-templates` 는 v0.13.0 부터 존재).

## [0.16.0] - 2026-05-24

Phase 2 — Prompt/Response 영구 히스토리.

### Added — conversation_turns 테이블 + `/projects/{id}/history` 페이지

- 새 PG 테이블 `conversation_turns` (id / projectId / sessionId / turnIdx /
  ts / role / content / toolName / toolUseId / tokensIn-Out / raw). Indexes
  on (projectId, ts), (projectId, sessionId, turnIdx), (toolUseId).
- `ConversationTurnRepository` — insert + filter (session / role / tool /
  ts range / content LIKE) + pagination + cascade delete.
- `ConversationHistoryService` (fire-and-forget) — ClaudeSessionManager 가
  user prompt / ClaudeEvent (assistant / tool_use / tool_result / system /
  error) 발생 시 적재. AssistantMessage 의 isPartial chunks 는 적재 안 함
  (turn 단위 final 만).
- `/projects/{id}/history` SSR — 필터 (session / role / tool / ts range /
  LIKE 검색) + 100/page pagination. 프로젝트 상세 / 콘솔 페이지에 링크.
- `/chat/history` — General Chat (`__scratch__`) 도 동일 영구화 + 전용 페이지.
- `ProjectService.delete` cascade 에 conversation_turns 추가.

### Known limits

- 본문 검색은 PostgreSQL `LIKE %query%` — 다음 cycle 에서 tsvector + GIN
  으로 교체 예정.
- 첫 user prompt 는 `sessionId=null` 로 적재 (SessionStarted 가 도착하기
  전이라). 후속 turn 부터 정상 session 묶임.
- Rotation 미구현. 1인 LAN 도구에서 수년 누적 후 수동 정리 권장 (Audit-Log
  Wiki 의 절차 참고).

## [0.15.0] - 2026-05-24

Phase 1 (계획상 ☆ 항목 묶음) — Audit log + 파일 신택스 하이라이트 + T1 wire 동기.

### Added — Audit log (B.☆)

- 신규 `audit_log` 테이블 (id / ts / userId / deviceId / ip / action /
  resourceType / resourceId / result / detail). ts / action 인덱스.
- `AuditLogRepository` — Filter (action/result/userId/from-to ts) + pagination.
- `AuditLogger` facade — 도메인 별 메서드 + kotlinx.serialization 기반 안전 JSON detail
  + 쓰기 실패가 요청 흐름을 망가뜨리지 않는 safe wrapper.
- `/audit` SSR 페이지 — 필터/검색 + 100/page pagination. nav 메뉴 "감사 로그" 추가.
- 통합 지점 (16 actions): auth.login (success/failure), auth.setup,
  auth.logout, auth.password.change, device.revoke, project.create, project.delete,
  build.enqueue, build.cancel, console.new, console.cancel (REST), mcp.install,
  mcp.unregister, settings.update, git.token.register, git.token.delete.

### Added — 파일 신택스 하이라이트 (A — T3 후속)

- `highlight.min.js` (125KB) + `github-dark` 테마 CSS 를 `/static/admin/` 에 번들
  (외부 CDN 정책 미위반).
- `fileViewPage` 가 View 모드 (read-only highlighted `<pre>`) / Edit 모드 (textarea)
  토글. 기본 View. Edit 토글 시 textarea focus + Ctrl+S 저장 + Tab indent.
- 지원 언어: kotlin / java / xml / json / yaml / markdown / properties / bash
  (ProjectFileBrowser mime guess 매핑). 200K 자 초과 파일은 highlight skip
  (브라우저 freeze 방지).

### Wire change: Yes (서버 측은 이미 v0.13.0 에 추가 — 안드로이드 동기 항목)

- v0.13.0 에서 추가한 `ApiPath.claudeConsoleCancel` 가 `vibe-coder-android`
  의 `shared/` 에도 동기됨 (v0.6.11). Android 사용자가 turn cancel 기능을
  쓰려면 v0.6.11 이상 클라이언트 필요. 구버전 클라이언트는 기능 미사용
  상태로 정상 동작.

### Changed — nav 메뉴

- "감사 로그" 추가. 순서: 대시보드 / 프로젝트 / Chat / 프롬프트 / 빌드환경 /
  설정 / 디바이스 / **감사 로그** / 비밀번호.

## [0.14.1] - 2026-05-24

### Changed — ClaudeMdTemplate 에 빌드 도구 경로 명시

**증상**: 사용자가 `/env-setup` 에서 Gradle 최신 버전을 설치한 뒤 신규 프로젝트를
만들면, Claude 가 작업 시 프로젝트의 `gradle-wrapper.properties` 가 가리키는 다른 (구)
버전을 wrapper 가 자동 다운로드. 디스크 / 빌드 시간 / API 토큰 낭비.

**Root cause**: 신규 프로젝트의 `CLAUDE.md` 에는 빌드 도구가 어디에 있는지 정보가
없어서, Claude 가 wrapper 의 distributionUrl 을 그대로 신뢰하고 새 다운로드를 트리거.

**해결**: `ClaudeMdTemplate.kt` 에 다음 섹션 추가:

- `## Installed Build Tools (USE THESE — DO NOT RE-DOWNLOAD)` — 컨테이너 안에
  이미 설치된 도구들의 경로 표.
  - Gradle: `/home/vibe/.local/gradle/` (PATH 의 `gradle`)
  - Android SDK: `$ANDROID_HOME` (`/opt/android-sdk`)
  - JDK 17 / Node 20 / Claude CLI: 이미지 번들
  - MCP packages: `/home/vibe/.local/`
- `### Gradle wrapper alignment policy` — wrapper 버전이 설치된 Gradle 과 다르면
  wrapper 를 설치 버전에 맞추라는 명시적 지시.
- `### When a wrapper is missing` — `gradle wrapper --gradle-version $(gradle --version ...)` 로
  설치 버전 기반 wrapper 생성.
- `### Cache reuse` — `~/.gradle/caches/` / SDK build-tools 캐시 정리 금지.

### 영향 범위

- **신규 프로젝트만**: `ProjectScaffolder.ensureClaudeFiles` 는 `notExists()` 가드.
  기존 프로젝트의 `CLAUDE.md` 는 보존됨. 기존 프로젝트에 적용하려면 사용자가
  파일을 수동으로 갱신하거나 삭제 후 재생성 (콘솔에서 Claude 에게 부탁).
- Wire change: 없음. 운영 정책 변경 (template) — CLAUDE.md §8.D 트리거.

## [0.14.0] - 2026-05-24

### Changed (Breaking) — 영구 저장소 SQLite → PostgreSQL

**Why**: SQLite single-writer 제약이 future-proof 하지 않다는 판단. Roadmap §9.F.☆ #1
"Prompt/Response 영구 히스토리" 가 들어오면 콘솔 stream 적재 + 검색이 동시에 일어남.
JSONB column 으로 tool_use input/output 의 가변 구조도 깔끔히 저장하려면 PG 가 적합.

**변경 사항**:

1. **JDBC driver**: `org.xerial:sqlite-jdbc` → `org.postgresql:postgresql` (42.7.4).
2. **Database.kt**: `jdbc:sqlite:...` → `jdbc:postgresql://host:port/db`. Hikari pool
   1 → 10 (PG 는 multi-connection 가능). Startup 시 30회 (60초) 재시도 — postgres
   컨테이너 ready 까지 대기.
3. **ServerConfig.DatabaseSection 신규**: host/port/name/user/password/passwordFile/maxPoolSize/sslMode.
   env override: `VIBECODER_DB_HOST/PORT/NAME/USER/PASSWORD/PASSWORD_FILE/MAX_POOL/SSLMODE`.
4. **server.yml** 에 `database:` 섹션 추가. 기본 host=postgres / port=5432 / name=vibecoder.
   비밀번호는 절대 디스크에 평문으로 두지 말 것 — env 또는 Docker secret 사용 권장.

### Changed (Breaking) — docker compose 에 postgres 컨테이너 추가

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_PASSWORD: ${VIBECODER_DB_PASSWORD:?...}
    volumes:
      - ${VIBE_DATA_ROOT:-./vibe-coder-data}/postgres:/var/lib/postgresql/data
    healthcheck: ...

  vibe-coder-server:
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      VIBECODER_DB_HOST: postgres
      VIBECODER_DB_PASSWORD: ${VIBECODER_DB_PASSWORD:?...}
```

데이터 디렉토리 구조 갱신:
```
vibe-coder-data/
  ├── workspace/
  ├── postgres/         ← v0.14.0 신규 (PG 데이터)
  ├── server/           ← 이전엔 SQLite 도 여기. v0.14.0 부터 로그/빌드 메타만
  ├── dev-tools/
  └── claude/
```

`tar czf backup.tar.gz vibe-coder-data/` 한 줄 백업 그대로 유효
(단, 일관성을 위해 `docker compose stop postgres` 후 백업 권장).

### Upgrade procedure — Fresh start

기존 v0.13.x 사용자는 **fresh start**: SQLite 데이터는 보존하지 않고 admin / 프로젝트 /
디바이스를 새로 등록. 워크스페이스 파일 (`vibe-coder-data/workspace/<projectId>/`) 은
디스크에 그대로 남아 있어 같은 ID 로 다시 등록하면 기존 소스를 이어서 사용 가능.

```bash
docker compose -f docker/compose.yml --env-file docker/.env down

# 기존 SQLite 파일 보관 (선택)
mv vibe-coder-data/server/.vibecoder/vibecoder.db ./vibecoder-v0.13-backup.sqlite 2>/dev/null || true

# .env 갱신 — VIBECODER_DB_PASSWORD 반드시 강력한 값으로
cp docker/.env.example docker/.env
${EDITOR:-nano} docker/.env

# postgres + server 같이 부팅
docker compose -f docker/compose.yml --env-file docker/.env up -d
# 첫 부팅 후 http://<IP>:17880/setup 으로 admin 재생성 + 프로젝트 재등록
```

`.env` 에 **`VIBECODER_DB_PASSWORD`** 반드시 강력한 값으로 설정 (compose 가 빈 값을 거부).

### Wire change

**없음.** REST API / WebSocket frame 무변경. 영향: 운영 (docker compose) + 내부 DB layer 만.
Android `shared/` 동기 불필요.

### Migration trigger (CLAUDE.md §8 분류)

- **C** (운영 정책 변경) — Dockerfile, compose.yml, .env.example 변경 → README / docker README /
  HUB README / 마이그레이션 문서 갱신 완료.

## [0.12.3] - 2026-05-23

### Added — 빌드환경 페이지에 Gradle 카드 + 자동 wrapper bootstrap

**증상**: 사용자가 신규 프로젝트에 Android 앱 생성 → `gradlew assembleDebug`
시도 → "이 환경에 Gradle Wrapper(gradlew)와 시스템 gradle 모두 없어 빌드
불가" 응답. 모든 신규 프로젝트의 첫 빌드가 막혔던 문제.

**Root cause**: Claude 가 생성하는 신규 프로젝트는 `build.gradle.kts` 등은
있지만 wrapper 파일 (`gradlew`, `gradle/wrapper/*.jar` 등) 은 생성 안 함.
컨테이너에 시스템 gradle 도 없어 wrapper bootstrap 불가.

**해결**: vibe-coder 의 "이미지 슬림 + 볼륨 다운로드" 패턴 통일.

1. **빌드환경 페이지에 Gradle 카드 추가** (`SetupComponent.GRADLE`).
   - "설치 (최신 stable)" / "최신 버전으로 업데이트" / "재설치" 버튼.
   - Probe 가 services.gradle.org/versions/current 조회 → 설치 버전과
     최신 비교 → UI 에 "현재 X.Y → 최신 A.B 사용가능" 표시 (업데이트 권유).
   - 설치 위치: `/home/vibe/.local/gradle/` (이미 v0.7.0 bind mount).
     `/home/vibe/.local/bin/gradle` symlink — PATH 자동 등록됨.

2. **vibe-doctor 의 `gradle` 신규 subcommand** (`docker/doctor/lib/gradle.sh`):
   - `vibe-doctor gradle` → 최신 stable 자동 조회 + 다운로드.
   - `vibe-doctor gradle 8.10.2` → 특정 버전.
   - jq 가용 시 jq, 아니면 grep fallback.
   - 같은 버전 이미 설치돼 있으면 skip (멱등).

3. **GradleBuilder 의 자동 wrapper bootstrap**: build 직전 `gradlew` 없으면
   system gradle 로 `gradle wrapper --gradle-version 8.7` 자동 실행 →
   wrapper 생성 → 그 후 정상 `./gradlew assembleDebug`. system gradle 부재면
   명확한 오류 메시지 + 빌드환경 페이지 안내.

### Wire change

**없음.** SetupComponent enum + UI + vibe-doctor 스크립트만. ApiPath / DTO
무변경 (Android 의 환경 진단 API 는 같은 list 를 그대로 반환 — 새 entry
1개 추가).

### 사용자 영향

- 업그레이드 후: 빌드환경 페이지 → Gradle 카드 → "설치 (최신 stable)" 클릭
  (~130 MB, 1~2분).
- 그 다음부터 신규 프로젝트의 첫 빌드 시 wrapper 자동 부트스트랩 → 정상.
- 기존 프로젝트는 wrapper 가 이미 있으면 그대로, 없으면 첫 빌드에서 자동
  생성.

### 배포

- `siamakerlab/vibe-coder-server:0.12.3` multi-arch push 예정.

## [0.12.2] - 2026-05-23

### Fixed — 콘솔에서 모든 파일 쓰기/AskUserQuestion 거부되어 작업 불가

**증상**: 사용자가 콘솔에서 "Hello World Android 앱 만들어줘" 같은 일반 요청
시도 → Claude 가 `settings.gradle.kts`, `build.gradle.kts` 작성 시도 → 매
파일마다 "Claude requested permissions to write to ... but you haven't
granted it yet" 응답 → 어떤 파일도 못 만들고 막힘. 추가로 AskUserQuestion
도 "Answer questions?" 만 표시되고 응답 채널이 없어 hang.

**Root cause**: v0.7.0 이전에 생성된 프로젝트 (예: `/workspace/test/`) 에
`.claude/settings.json` 이 없어서 `permissions.defaultMode: bypassPermissions`
가 적용되지 못함 → Claude Code 의 default `ask` 모드 → vibe-coder 비인터랙티브
콘솔은 권한 prompt 응답 채널 없음 → 모든 write 거부 + AskUserQuestion hang.

v0.7.0 의 `ProjectService.register` 는 신규 프로젝트에만 `.claude/settings.json`
을 생성. 기존 프로젝트는 backfill 안 됨.

**수정 — 이중 안전망**:

1. **spawn args 에 `--dangerously-skip-permissions` 명시** (`ClaudeSessionManager.kt:166`).
   `.claude/settings.json` 유무와 무관하게 권한 prompt 차단 — robust.
   `ClaudeStatusService` 의 `--print /status` 호출도 동일 추가.
2. **인터랙티브 위젯 명시 차단**: `--disallowedTools` 로
   `AskUserQuestion`, `EnterPlanMode`, `ExitPlanMode`, `NotebookEdit` 거부.
   모델이 호출 시도하면 즉시 실패 → 응답 끝에 옵션 나열 등 비인터랙티브
   경로로 자동 분기.
3. **매 spawn 직전 `.claude/settings.json` + `CLAUDE.md` 자동 backfill**
   (`ProjectScaffolder.ensureClaudeFiles`, 신규 파일). 기존 파일 있으면
   noop — 사용자 customize 보존. 신규 프로젝트는 register 가 만들고, 기존
   프로젝트는 spawn 시 보충. backfill 실패해도 prompt 차단 사유는 아님 (log
   만, args 의 플래그가 1차 안전망).

### Wire change

**없음.** server-side spawn args / 디스크 backfill 만. ApiPath / DTO 무변경.

### 사용자 영향

업그레이드 직후, 기존 프로젝트의 첫 콘솔 호출에서 자동으로
`.claude/settings.json` + `CLAUDE.md` 생성됨 (없는 경우만). 그 다음부터
모든 write/edit 가 자동 승인됨.

### 배포

- `siamakerlab/vibe-coder-server:0.12.2` multi-arch push 예정.
- 기존 사용자: `docker compose pull && up -d --force-recreate`. 별도 마이그레이션 불요.

## [0.12.1] - 2026-05-23

### Added — MCP 카탈로그 `comingSoon` 라벨

Phase 2 (Device Code OAuth wrapper) 진행 보류 결정에 따라, vibe-coder 의
비인터랙티브 환경에서 사실상 동작 불가한 MCP 를 카탈로그에 노출하되 명확히
표시. 사용자가 헛수고로 설치 시도하는 일 방지.

**McpCatalog.McpEntry**:
- 새 필드 `comingSoon: Boolean = false`.
- 카탈로그에 노출은 하되 설치 비활성 + UI 카드 흐림(opacity 0.55) + "⏳ 준비중" 배지.

**영향 항목 (1개)**:
- `google-drive` — OAuth client.json 업로드 후 첫 호출에서 브라우저 OAuth 콜백
  필수. 키 파일만 받아서는 토큰 교환 불가. 다른 모든 MCP (Slack/Notion/Linear
  Bot token, GitHub PAT, Service Account JSON 등) 는 v0.11.0 의 토큰/파일
  업로드로 충분.

**서버 검증** (`McpService.spawnBatch`):
- `comingSoon=true` MCP 가 install 요청에 포함되면 400 `coming_soon` 응답.
  웹 UI 우회 시도 차단 (Android wire 도 동일).

**UI** (`McpTemplates.renderEntry`):
- checkbox `disabled` + title 툴팁 ("브라우저 OAuth 콜백이 필수라 현재 환경
  에서 미지원").
- "준비중" 배지 — Trust 배지 옆에 표시.
- configFields 폼 숨김 (어차피 등록 불가).

### Wire change

**예** — `McpEntryDto` 에 optional `comingSoon: Boolean = false` 추가.
구버전 클라이언트는 무시하지만 catalog 응답에 새 필드 포함.

### 배포

- `siamakerlab/vibe-coder-server:0.12.1` multi-arch push 예정.

### Phase 2 (Device Code OAuth) 결정 결과

운영자가 결정한 방향: **PAT 직접 입력으로 충분**. Device Flow 구현 보류.
브라우저 OAuth 콜백이 필수인 MCP 는 위 `comingSoon` 라벨로 일관 처리.

## [0.12.0] - 2026-05-23

### Added — CORS 정책 설정 (env override + 읽기 전용 UI)

이전엔 `anyHost()` 하드코딩이라 외부 origin 노출 환경에서 CSRF 위험이 있었음.
이제 server.yml 또는 docker compose env 로 정밀 제어 가능.

**Config** (`ServerConfig.kt`):
- 새 섹션 `cors: CorsSection(allowedHosts, allowCredentials)`.
- 기본값 `["*"]` — LAN 격리 환경 (anyHost). 외부 노출 시엔 신뢰 origin 만 명시.
- Wildcard subdomain 패턴 (`*.example.com`) 지원.
- 스킴 명시 가능: `https://x.com` (https 만) / `x.com` (http+https 둘 다).

**Env override** (`ConfigLoader.applyEnvironmentOverrides`):
- `VIBECODER_CORS_ALLOWED_HOSTS` — 콤마 구분. server.yml 보다 우선.
- `VIBECODER_CORS_ALLOW_CREDENTIALS` — `true`/`false`.

**Module** (`Module.kt`):
- `install(CORS)` 가 config 참조 — `*` 포함 시 `anyHost()`, 아니면 명시 host
  list 처리. 신규 helper `parseCorsHostEntry` 가 URL 패턴 파싱.

**Compose / .env**:
- `compose.yml` environment 에 `VIBECODER_CORS_ALLOWED_HOSTS`/`VIBECODER_CORS_ALLOW_CREDENTIALS`.
- `.env.example` 의 CORS 섹션 + 보안 안내.

**UI** (신규 `/settings/cors`):
- 현재 적용된 정책 + allowedHosts 표 + describeHost 매핑 표시.
- 보안 경고 (anyHost 의 CSRF 위험, allowCredentials + anyHost 조합 금지).
- **편집은 의도적으로 제외** — env / server.yml 만 가능. UI 우발 변경 방지.
- 변경 절차 step-by-step + 검증 curl 예문.

### Added — 프로젝트 아이콘 (admin SSR header + favicon)

`vibe-coder-icon.png` 가 `server/src/main/resources/static/admin/icon.png`
으로 패키징되어 `/static/icon.png` 로 자동 노출.

- `AdminTemplates.shell()` 의 head 에 favicon `<link rel="icon">` 추가.
- nav 좌상단 brand 옆에 32x32 라운드(50%) 아이콘 배치.
- 모든 admin SSR 페이지에서 자동 표시.

### Wire change

**없음.** CORS 정책은 server-side 동작 변경, UI 는 admin SSR 전용.
ApiPath / DTO / WsFrame 무변경.

### 배포

- `siamakerlab/vibe-coder-server:0.12.0` multi-arch push 예정.
- 기존 사용자: `docker compose pull && up -d --force-recreate`. CORS 정책은
  기본 `*` 유지되므로 동작 변화 없음 (envar 설정 시에만 변경 적용).

### v0.12.0 Phase 2 (예정) — Device Code OAuth wrapper

사용자가 옵션 2 진행 요청 — GitHub OAuth Device Flow 로 PAT 발급 간소화.
구현 복잡도 (OAuth App 등록 필요 + provider 별 endpoint 차이) 때문에
별도 minor 로 분리 예정 (v0.13.0).

## [0.11.0] - 2026-05-23

### Added — MCP secret 파일 업로드 (Service Account JSON / Apple .p8 등)

기존 v0.8.0 MCP 카탈로그의 한계 해소 — Play Console / App Store Connect /
Firebase / Google Drive 같이 **OAuth 토큰이 아니라 JSON/PEM 키 파일**로
인증하는 MCP 들이 모바일·웹 UI 만으로 등록 가능해짐.

**카탈로그 변경** (`McpCatalog.ConfigField`):
- 새 필드 `isFile: Boolean` + `acceptMime: String?`.
- 영향 4개 MCP 의 path 필드를 file upload 로 전환:
  - `google-play-publisher` → `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (.json)
  - `app-store-connect` → `ASC_PRIVATE_KEY_FILE` (.p8) — 이전 `ASC_PRIVATE_KEY_PATH` 에서 이름 변경
  - `firebase` → `GOOGLE_APPLICATION_CREDENTIALS` (.json)
  - `google-drive` → `GDRIVE_CREDENTIALS_PATH` (.json)

**서버** (`McpService.uploadConfigFile`):
- 안전 경로: `${CLAUDE_CONFIG_DIR}/mcp-secrets/<mcpId>-<fieldKey><ext>`.
- 디렉토리 0700, 파일 0600 (Posix).
- 최대 128 KB (일반 Service Account / .p8 키는 수 KB 이내 — 비정상 크기 거부).
- 파일명 sanitize (영숫자+. _ - 만 허용 — path traversal 방지).
- 확장자는 원본 파일명 우선, fallback 으로 acceptMime 첫 항목.
- atomic move 로 덮어쓰기 (동시 업로드 안전).

**라우트** (SSR + JSON API 모두):
- `POST /env-setup/mcp/{mcpId}/file/{fieldKey}` (SSR) — UI 의 file input
  onChange 가 ajax 호출. 응답 `{path}` 를 hidden input 에 저장 → 일반 install
  POST 의 configValues 에 포함.
- `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (Android wire, Bearer
  auth) — 동일 multipart 형식.
- 신규 헬퍼: `ApiPath.mcpUploadFile(mcpId, fieldKey)`.
- 신규 DTO: `McpFileUploadResponseDto(path)`.

**UI** (`McpTemplates`):
- file input 분기 (`<input type="file" accept=".json,...">`).
- onChange 즉시 ajax 업로드 → 진행 상태 + path 표시.
- 업로드 미완료 상태로 install 제출 시 차단 + alert.
- 기존 등록된 path 가 있으면 표시 + 새 파일로 교체 가능.

### Wire change

**예** — `McpConfigFieldDto` 에 `isFile/acceptMime` optional 필드 추가
(default false/null 이라 구버전 클라이언트 호환). 새 endpoint
`mcpUploadFile`. 새 DTO `McpFileUploadResponseDto`. Android 클라이언트는
`shared/` 동기 + file input 흐름 구현 시 사용 가능.

### 배포

- `siamakerlab/vibe-coder-server:0.11.0` multi-arch push 예정.

## [0.10.2] - 2026-05-23

### Changed — UI 의 버전 라벨 제거 (사용자 노이즈 정리)

빌드환경 / 프로젝트 / Claude 로그인 / Git 통합 / MCP 페이지 등 곳곳에
산재해있던 "v0.x.x 부터 / v0.x.x 신규 / v0.x.x+" 같은 메인테이너 관점
라벨을 일괄 제거. 사용자는 현재 버전에서 제공되는 기능만 보면 되며,
어느 버전부터 추가됐는지는 CHANGELOG 의 관심사.

제거된 6곳:

| 파일 | 위치 | Before | After |
|---|---|---|---|
| `GitIntegrationsTemplates.kt:79` | 페이지 header | `Git 통합 v0.9.0` | `Git 통합` |
| `EnvSetupTemplates.kt:149` | MCP 카드 hint | `v0.8.0+: 체크박스 …` | `체크박스 …` |
| `EnvSetupTemplates.kt:178` | 옵션 0 강조 배너 | `… 한 번에 로그인 (v0.7.0 신규)` | `… 한 번에 로그인` |
| `EnvSetupTemplates.kt:314` | Claude 로그인 header | `Claude 웹 로그인 v0.7.0 옵션 A · 반자동 OAuth` | `Claude 웹 로그인 반자동 OAuth` |
| `WebProjectTemplates.kt:169` | 새 프로젝트 폼 legend | `소스 (v0.9.0+)` | `소스` |
| `McpTemplates.kt:121` | "직접 설치" 안내 hint | `v0.7.0+ 영구 보존` | `영구 보존` |

코드 주석의 `// v0.x.x — …` 패턴은 메인테이너용이라 유지.

### Wire change

**없음.** SSR HTML 텍스트만 변경. server.yml 0.10.1 → 0.10.2 (UI patch).

### 배포

- `siamakerlab/vibe-coder-server:0.10.2` multi-arch push 예정.

## [0.10.1] - 2026-05-23

### Fixed — Claude 웹 로그인 입력창 포커스 손실 + paste 텍스트 소실

**증상**: `/env-setup/claude-login` 의 AWAITING_CODE 단계에서 사용자가
authorization code 를 paste 한 직후, 페이지가 자동 reload 되면서 입력값이
사라지고 포커스가 풀리는 현상.

**Root cause** (`EnvSetupTemplates.kt:325-353`):
- JS 폴링이 1초마다 `/env-setup/claude-login/status.json` 호출.
- 어떤 이유로든 state 가 직전 값과 다르면 무조건 `window.location.reload()`.
- AWAITING_CODE 에서도 폴링이 계속 돌아, child process 의 stdout 미세 변동
  / watchProcess 의 race 로 state 가 흔들리면 사용자 입력 도중 reload 발생.
- input 의 값 보존 / autofocus 없어서 reload 후 처음부터 다시 paste 필요.

**수정**:

1. **AWAITING_CODE 에서 폴링 disable** — 진행상태(STARTING/VERIFYING)
   에서만 폴링. AWAITING_CODE 에선 사용자 action (제출/취소) 만이 state
   를 진행시킬 수 있어 폴링 불요.
2. **input 값 sessionStorage 자동 백업/복원** (`STORAGE_KEY=claude_login_code_buf`):
   - 페이지 로드 시 input 비어있으면 sessionStorage 에서 복원.
   - `input` + `paste` 이벤트마다 즉시 sessionStorage 에 저장.
   - 폼 submit 직전 (제출/취소 양쪽) sessionStorage clear — 다음 세션에
     stale 코드 채움 방지.
3. **input autofocus** — HTML `autofocus` 속성 + JS `setTimeout focus`
   이중 보장 (일부 환경에서 HTML autofocus 가 안 먹는 케이스 대비).
4. **사용자 안내문 추가** — "입력 중에는 페이지가 자동 갱신되지 않으니
   제출 버튼을 직접 누르세요" 힌트 표시.

**Trade-off**: AWAITING_CODE 에서 폴링이 멈춰 있어, child process 가 그
사이 죽어도 사용자는 즉시 알 수 없음. submit 시 `wrong_state` (409)
에러로 안내 (기존 errorBlurb 표시 흐름 그대로).

### Verification

`./gradlew clean :server:installDist` 통과. 빌드환경 페이지 → "옵션 0 웹
로그인" → "로그인 시작" → AWAITING_CODE 화면에서 input 에 long string
paste → 5초 대기 → 페이지 자동 reload 없음 + 입력값 그대로 → 제출 시 정상
전송.

### Wire change

**없음.** SSR HTML/JS 수정만. ApiPath / DTO / WsFrame 무변경.

### 배포

- `siamakerlab/vibe-coder-server:0.10.1` multi-arch push 예정.
- 사용자 영향: `docker compose pull && up -d --force-recreate` 후 즉시 fix
  적용. AWAITING_CODE 상태에서 paste 한 코드가 영구 안정.

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
