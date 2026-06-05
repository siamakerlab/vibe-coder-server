# Changelog — vibe-coder-server

All notable changes to the server component will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

v0.4.0 까지는 `vibe-coder` 모노레포의 단일 CHANGELOG 였고, v0.4.1 부터
서버/안드로이드 두 리포로 분리되어 각 리포가 독립 changelog 를 갖는다.
Android 클라이언트 이력은 `vibe-coder-android` 리포의 CHANGELOG 참고.

## [Unreleased]

## [1.105.1] - 2026-06-05 — 콘솔 백그라운드 작업 패널 접기/펼치기 (접으면 진행 중 개수만 표시)

### Added

- **콘솔 하단 백그라운드 작업 패널(`#bg-tasks`)을 헤더 클릭으로 접기/펼치기.** 접으면 카드 목록을
  숨기고 **진행 중(running)인 작업 개수만** 헤더 우측에 배지로 표시(예: "진행 중 2개"). 작업이
  시작/종료될 때 개수가 즉시 갱신된다.
- 접힘 상태는 `localStorage`(`vibeBgTasksCollapsed`)에 저장되어 새로고침/재진입 후에도 유지.
  키보드 접근성(Enter/Space 토글, `aria-expanded`) 포함.
- i18n: `console.bgtasks.collapsedCount`("진행 중 {n}개" / "{n} running"),
  `console.bgtasks.toggleHint` (ko/en).

## [1.105.0] - 2026-06-05 — AdMob 저장 시 앱 적용(광고+광고제거 구매) 콘솔 프롬프트 자동 발사 + 콘솔 busy 시 저장 버튼 비활성

### Added

- **프로젝트 키스토어 탭에서 AdMob 광고 ID 저장 시, 앱에 적용하도록 Claude 콘솔로 프롬프트를
  자동 발사.** 광고 ID 원본(`<pkg>-admob.properties`)을 단일 진실로 두고, 빌드 스크립트가 이를
  로드해 주입하도록 안내한다. 프롬프트는 버전을 고정하지 않고 **공식문서 확인 후 최신 라이브러리·
  권장 코드로 적용**하도록 지시(시간이 지나도 최신 유지):
  - **하단 배너**: 공식 권장 **적응형 배너(anchored adaptive)** 로 디바이스 최하단 고정, 전역 1개 관리.
  - **전면(Interstitial)**: 폴더블/멀티윈도우 사이즈 오류 방지를 위해 **표시 직전 디바이스/창 사이즈
    확인** 후 송출.
  - **그 외(앱오프닝/네이티브/보상형/보상형전면)**: 배치가 애매하면 **광고 ID 만 기록(BuildConfig)**
    하고 앱 내 노출은 사용자가 직접.
  - **광고 제거 인앱결제(필수)**: 광고 적용 시 Play Billing 최신 버전·권장 흐름으로 **비소모성 "광고
    제거"** 상품 추가(상품 ID 는 `remove_ads` 로 통일), 구매 시 광고 제거 + 시작 시 복원, 진입점은
    **앱 설정(Settings) 화면**.
  - 디버그는 Google 공식 테스트 광고 ID, GDPR/UMP 동의 수집 후 광고 초기화.
- **저장 버튼 좌측에 "↗ 광고 적용 프롬프트 보내기" 버튼 추가.** 재저장 없이, 이미 저장된 광고 ID 로
  적용 프롬프트만 콘솔에 재전송한다(저장 시 콘솔이 busy 였거나 코드 변경 후 재적용 시 유용).
  전용 라우트 `POST /projects/{id}/keystore/admob/apply`. 콘솔 유휴 + 저장된 광고 ID 가 있을 때만 활성.
- **콘솔이 작업 중(응답/빌드/자동화)일 때 AdMob 저장/적용 버튼 비활성화 + 안내.** 저장·적용 모두 콘솔
  프롬프트 발사를 동반하므로 키스토어 업로드와 동일하게 **콘솔 유휴일 때만** 허용(렌더~제출 사이
  race 는 서버측 idle 가드로 방어).

### Notes

- 광고 ID 를 모두 비우고 저장하면 기존처럼 `<pkg>-admob.properties` 삭제만 수행(프롬프트 미발사).

## [1.104.3] - 2026-06-05 — SSR 폼 POST 본문 이중 수신(double-receive) 으로 인한 500 일괄 회수 (AdMob 저장 등)

### Fixed

- **프로젝트 키스토어 탭에서 AdMob 광고 ID 입력 후 저장 시 `{"code":"internal_error",
  "message":"Request body has already been consumed (received)."}` 500 에러.**
  운영 보고(starreading 프로젝트)로 확인. UI 가 JSON 으로 깨지며 저장 불가.
- **근본 원인**: `CsrfTokens.requireCsrf()` 가 이미 `call.receiveParameters()` 로 요청
  본문을 1회 소비하고 검증된 `Parameters` 를 반환하는데, 다수 SSR 폼 POST 핸들러가 그
  반환값을 버리고 `call.receiveParameters()` 를 한 번 더 호출했다. Ktor 3.1.2 는
  `DoubleReceive` 플러그인 미설치 시 본문 채널을 1회만 읽을 수 있어 두 번째 수신에서
  타입과 무관하게 `RequestAlreadyConsumedException` 을 던진다.
- **영향 범위**: AdMob 저장 외에도 동일 안티패턴을 가진 SSR 폼 핸들러 다수가 잠재적으로
  동일 500 을 일으켰다(1인 운영 환경이라 AdMob 만 표면화). 일괄 수정:
  - `ProjectKeystoreRoutes` (keystore create / admob save)
  - `WebProjectRoutes` (Play 업로드 / TestFlight 업로드)
  - `TwoFactorRoutes` (2FA enable / disable)
  - `PromptAutomationRoutes` (automation start / presets)
  - `BuildAutomationRoutes` (schedule create / toggle / secrets)
  - `BuildCacheRoutes`, `DependencyAuditRoutes`, `AgentRoutes`, `SkillRoutes`,
    `CodeAnalysisRoutes`, `EnvFilesRoutes`, `ProjectSkillRoutes`, `ProjectAgentRoutes`,
    `ProjectMcpRoutes`, `ProjectClaudeMdRoutes`
- **수정 방식**: `requireCsrf()` 의 반환값(검증된 폼 `Parameters`)을 그대로 사용하고
  중복 `call.receiveParameters()` 호출을 제거. 반환값이 동일하므로 동작은 완전히 보존된다.
  (multipart 업로드 경로인 `HistoryRoutes` 의 import 는 `isValidCsrf` query 검증 방식이라
  영향 없음.)

### Fixed

- **턴이 많은 프로젝트에서 콘솔/채팅에 다시 들어오면(reload) 방금 보낸 프롬프트를 포함한
  최근 대화가 통째로 안 보이던 문제.** (운영 tally-counter — 한 세션 1067 turn — 에서
  재현. 프롬프트 전송 직후엔 보이지만 다른 페이지 갔다 오면 사라지고 상단 고정도 안 됨)
  - **근본원인**: 콘솔 history 복원 쿼리가 `orderBy(ts ASC).limit(200).offset(0)` —
    주석은 "last 200 turn" 이지만 실제로는 **가장 오래된 200개**를 가져왔다. 세션 turn 이
    200을 넘으면 최근 대화가 200 밖으로 밀려 복원에서 누락됐다. (우측 rail 의 recentPrompts
    는 `offset=count-7` 로 최근을 올바르게 가져와 거기엔 보이는 불일치도 있었음.)
  - **수정**: `offset = count(filter) - 200` 로 **진짜 최근 200 turn**을 ASC 로 복원.
    console(`/projects/{id}/console`) + chat(`/chat`) 두 라우트 동일 수정.
  - 라이브(WS) 흐름은 정상이라 전송 직후엔 보였고, reload 시에만 누락 → "프롬프트 보낸 후
    페이지 이동" 조건에서만 드러났다.
  - 파일: `WebProjectRoutes.kt`. Wire 변경 없음. (JSON history API/Android 복원이 같은
    패턴을 쓰면 별도 점검 필요 — 본 수정은 웹 SSR 콘솔/채팅 한정.)

### Fixed

- **콘솔에서 상단에 sticky 고정되는 user 프롬프트가 가장 최신이 아니라 이전 프롬프트였던
  문제.** (운영 tally-counter 콘솔에서 마지막 프롬프트가 '네 권장사항대로 진행해줘'인데
  상단엔 그 이전 프롬프트가 고정돼 있던 사례)
  - **근본원인**: `.console-log .log-line.user` **모든** user 메시지에 `position:sticky;
    top:0` 이 걸려 있어, 여러 프롬프트가 stacking 됐다. 마지막 프롬프트의 응답이 아직 짧아
    그 프롬프트가 `top:0` 까지 밀려 올라가지 않으면, 화면 상단을 지나는 *이전* 프롬프트가
    `top:0` 에 고정됐다. (탭 재진입 깨짐이 아니라 sticky 다중 stacking 이 원인)
  - **수정**: 상단 고정을 "가장 최신 프롬프트 1개"(`.log-line.user.cur`)로 한정.
    `append()` 가 새 user 메시지마다 이전 `.cur` 를 해제하고 최신 row 에만 부여한다
    (append 는 항상 맨 끝이라 DOM 마지막 = 최신). 이전 프롬프트들은 일반 흐름으로 위로
    스크롤돼 사라지고, 최신 프롬프트만 상단에 고정된다.
  - 파일: `admin.css`(`.log-line.user` 에서 sticky 분리 → `.user.cur`),
    `WebProjectTemplates.kt`(append 의 `.cur` 토글). 캐시: `admin.css?v` 1.104.0 → 1.104.1.
    Wire 변경 없음.

### Changed

- **홈·프로젝트 목록·채팅·메모·터미널 페이지가 넓은 모니터에서 브라우저 폭을 효율적으로
  쓰도록 개선.** 이전엔 `.content` 의 `max-width: 1200px` 중앙정렬로 양옆 여백이 과했는데,
  도구/설정 페이지처럼 전체 폭을 활용한다.
  - 새 레이아웃 변형 `.content.wide` 추가 — `fullbleed`(iframe 탭 전용, padding 0)와 달리
    **max-width 만 해제하고 padding(여백)은 유지**해 일반 콘텐츠가 가장자리에 붙지 않는다.
  - `AdminTemplates.shell(wide = true)` 파라미터 신설(`fullbleed` 우선). 5개 페이지가 호출:
    홈(`/`), 프로젝트 목록(`/projects`), 채팅(`/chat`, `consolePage` 의 `isChat` 분기 —
    콘솔은 통합 탭 inner 라 제외), 메모(`/memos`), 터미널(`/terminal`).
  - 파일: `admin.css`(`.content.wide`), `AdminTemplates.kt`(shell `wide` 파라미터 + 대시보드),
    `WebProjectTemplates.kt`(프로젝트 목록 + 채팅), `MemosRoutes.kt`, `TerminalRoutes.kt`.
    캐시: `admin.css?v` 1.100.0 → 1.104.0. Wire 변경 없음.

### Changed

- **콘솔 하단의 turn 상태칩(busy-badge)을 통합 탭 헤더의 프로젝트 전환 콤보박스 좌측으로
  이동.** 입력창 아래에 있어 스크롤·탭 전환 시 가려지던 상태칩을, 항상 보이는 sticky 헤더로
  올려 어느 탭(빌드/파일/Git 등)에 있어도 콘솔 진행 상태(유휴·응답중·대기중·중단됨·에러 +
  큐 카운트)를 확인할 수 있다.
  - **구조**: 콘솔은 iframe inner, 헤더는 outer shell 로 document 가 분리돼 있어, 콘솔의
    busy-badge 갱신(`updateBusyBadge`/`showStopped`/`showWaiting`/`showError`)이
    `postMessage({type:'console:busy', state, text})` 로 부모에 미러링하고, `project-tabs.js`
    가 헤더의 `#console-busy-badge` 를 갱신한다. 5-state 색 팔레트는 콘솔과 동일.
  - 통합 탭 뷰(`/projects/{id}`, embed)에서는 콘솔 하단 busy-badge 를 숨겨 중복을 없앴고,
    콘솔 단독 페이지(`/projects/{id}/console` 직접 진입, 헤더 없음)에서는 기존처럼 하단에 유지.
  - 파일: `ProjectTabsTemplate.kt`(헤더 칩 + CSS), `WebProjectTemplates.kt`(postMessage
    미러링 + embed 시 하단 숨김), `project-tabs.js`(수신 핸들러). 캐시: `project-tabs.js?v`
    1.99.1 → 1.103.0. Wire 변경 없음.

### Fixed

- **콘솔에서 정상적으로 끝난 turn 의 상태칩이 '중단됨'(stopped, 보라)으로 표시되던 문제.**
  - **근본원인**: v1.100.0(5-state 색상)에서 `setBusy` 를 busy(boolean) 뿐 아니라 state
    문자열까지 비교해 emit 하도록 바꿨는데, 종료 처리 두 지점(`onProcessExit` /
    `terminateSession`)은 v1.60.0 이래 **무조건** `setBusy(false, "stopped")` 를 호출하면서
    "정상 완료(이미 ready)면 boolean idempotency 로 무시된다"는 가정에 의존하고 있었다.
    state-aware 전환 후엔 그 가정이 깨져 `ready → stopped` 전이가 emit → 정상 완료 세션에
    '중단됨'. 두 경로에서 재현: ① claude 프로세스가 turn 정상 완료 후 clean exit(code 0)할 때,
    ② 정상 완료 후 방치된 세션이 idle reaper(30분)로 정리될 때.
  - **수정**: v1.60.0 의 원래 의도(busy 중 종료만 '중단됨', 정상 완료 후 종료는 '유휴' 유지)를
    명시 분기로 복원. 종료 직전 busy 여부로 `stopped`/`ready` 를 선택하고,
    `intentionalKill`(cancel/idle/shutdown) 경로는 `terminateSession` 이 busy 전이를 전담하게
    해 `onProcessExit` 와 race 하며 서로 덮어쓰던 문제도 차단. 사용자 cancel·크래시는 그대로
    '중단됨', clean exit·idle reap 은 '유휴'.
  - 파일: `ClaudeSessionManager.kt`(`onProcessExit` / `terminateSession`). Wire 변경 없음.

## [1.102.0] - 2026-06-04 — 키스토어 업로드를 "서버 staging → Claude 콘솔 이동배치" 방식으로 전환

### Changed

- **키스토어 업로드 처리 방식 변경(v1.101.0 개선).** 이전엔 서버가 업로드 파일을 최종
  위치(`/home/vibe/keystores/`)에 **직접 배치**한 뒤 서명 적용 프롬프트만 보냈는데, 이제는
  서버가 **staging 디렉토리(`/home/vibe/keystores/_staging/<pkg>/`)에 규약 파일명으로만
  저장**하고, **한 번의 콘솔 프롬프트**로 Claude 가 (1) staging → 최종 위치로 이동배치
  (기존 파일은 `.bak.<ts>` 백업), (2) staging 정리, (3) build.gradle.kts 서명 적용 을
  한 turn 에 일괄 수행한다(사용자 요청 — 이동배치를 Claude 에 위임).
  - 3개 업로드 박스(release / debug / properties)는 그대로. 서버는 파일명을 규약명
    (`<pkg>.keystore` / `<pkg>-debug.keystore` / `<pkg>-keystore.properties`)으로 바꿔
    staging 에 저장하고, properties 의 storeFile 은 서버 표준 경로로 정규화한다.
  - **콘솔 유휴 가드 유지**: 업로드는 콘솔이 유휴일 때만(직후 프롬프트 발사).
  - `KeystoreService.saveUploaded()` → `stageUploaded()` + `KeystoreUploadResult` →
    `KeystoreStageResult` + `stagingDir()`. 신규 프롬프트 빌더
    `buildKeystorePlacementPrompt()`(이동+백업+정리+서명을 한 turn 으로). 라우트는
    동일(`POST /projects/{id}/keystore/upload`). 테스트 `KeystoreServiceUploadTest`
    갱신. Wire 변경 없음(SSR).

## [1.101.0] - 2026-06-04 — 프로젝트 키스토어 탭에 업로드 기능 추가(외부 키 가져오기 + 콘솔 자동 갱신)

> ⚠️ v1.101.0 의 "서버 직접 배치" 방식은 **v1.102.0 에서 staging + Claude 콘솔 이동배치로
> 대체**되었습니다(아래 설명은 초기 구현 이력). 최종 동작은 v1.102.0 항목 참고.

### Added

- **프로젝트 키스토어 탭(`/projects/{id}/keystore`)에서 외부에서 만든 키스토어를
  업로드.** 이미 가지고 있는 release / debug 키스토어와 signing properties 파일을
  올리면, 각각 표준 위치(`/home/vibe/keystores/`)에 프로젝트 패키지 규약 파일명
  (`<pkg>.keystore` / `<pkg>-debug.keystore` / `<pkg>-keystore.properties`)으로 저장하고,
  업로드 직후 콘솔에 build.gradle.kts 서명 정보를 갱신하는 프롬프트를 자동 전송한다.
  - **신규 라우트**: `POST /projects/{id}/keystore/upload` (SSR, multipart). 패키지명은
    프로젝트 메타로 강제 — 업로드 파일명은 무시하고 서버가 규약 파일명으로 저장.
  - **콘솔 유휴 가드**: 업로드 직후 프롬프트를 쏘므로, 콘솔이 유휴
    (`isBusy` / `isBuildRunning` / `automation.isActive` 모두 아님)일 때만 허용
    — 다른 파괴적/이동 라우트(delete/rename/archive)와 동일 가드 체계. 작업 중이면
    업로드 폼이 비활성화되고 안내 메시지를 표시.
  - **안전망**: 기존 키스토어가 있으면 덮어쓰기 전에 `<name>.bak.<yyyyMMdd-HHmmss>` 로
    백업(키 분실 = 같은 키로 앱 업데이트 영구 불가). PKCS12(DER 0x30) / JKS(0xFEEDFEED)
    매직 + 256KB 크기 1차 검증. properties 는 storePassword/keyAlias/keyPassword 를
    추출하고 `storeFile` 을 서버 표준 경로로 강제 정규화(업로드된 로컬 경로
    `D:/dev/keystores/...` 가 서버에서 무효이므로).
  - 파일: `KeystoreService.saveUploaded()` + `KeystoreUploadResult`,
    `ProjectKeystoreRoutes.kt`(upload 라우트 + 업로드 카드 UI + idle 판정),
    `Module.kt`(buildRepo / promptAutomationManager 주입). 테스트
    `KeystoreServiceUploadTest`. Wire 변경 없음(SSR — ApiPath 대상 아님).

### Changed

- **모든 페이지의 turn 상태 뱃지를 5가지 상태 + 일관 색상으로 통일.** 기존엔
  콘솔(`#busy-badge`)·프로젝트 목록(`.pstat`)·통합 탭 전환 콤보박스가 라벨·색이
  제각각이었다 — 예: 같은 "대기중" 단어가 콘솔에선 유휴(idle), 목록에선 ready(초록)
  를 가리켰고, 응답중이 목록에선 주황·콘솔에선 초록이었다. 이제 한 체계로 정렬:
  - **유휴**(`ready`/`idle`) — 그레이. 진행 중인 turn 없음.
  - **응답중**(`responding`) — 초록(pulse). claude 가 작업 중.
  - **대기중**(`waiting`) — 노랑. 백그라운드 작업(Bash `run_in_background` / `Task`)이
    진행 중이라 turn 이 재개 대기(v1.99.2 의 suspended 상태를 색으로 가시화).
  - **중단됨**(`stopped`) — 보라(이전 빨강). 사용자 취소 / 크래시 / idle 종료 /
    rate-limit 자동재개 소진.
  - **에러**(`error`) — 빨강. API/turn 에러로 종료(중단됨과 색 구분).
  - 서버 `setBusy` 가 busy(boolean) 뿐 아니라 상태 문자열 변화도 감지해 emit하도록
    개선 — `waiting` 은 busy=true 를 유지한 채 전이하므로 이전엔 뱃지가 안 바뀌었다.
  - 파일: `ClaudeSessionManager.kt`(busyState 추적 + waiting/error emit),
    `WebProjectTemplates.kt`(콘솔 badge CSS/JS + 목록 LABELS), `ProjectTabsTemplate.kt`
    (탭 전환 콤보박스 칩), `admin.css`(`--wait`/`--halt` 변수 + `.pstat-*` 5색 +
    MCP pill 색 보존 override), i18n(ko/en) `projects.status.*` / `console.busy.*`.
  - 캐시: `admin.css?v` 1.86.4 → 1.100.0(AdminTemplates / EnvSetupTemplates).
  - **Android 참고**: `WsFrame.ConsoleBusyState.state` / `ProjectBusyChanged.state` 에
    새 값 `"waiting"` / `"error"` 가 추가됐다(필드 구조 불변 — wire-compatible).
    Android 클라가 상태칩을 그린다면 두 값을 노랑/빨강으로 매핑 권장. 미인식 시
    기존처럼 busy 폴백(responding/ready)으로 안전 동작.

## [1.99.2] - 2026-06-04 — 백그라운드 작업 중 turn 이 "대기중" 으로 떨어져 동시 한도 무력화 + 자동화 중첩되던 근본원인 회수

### Fixed

- **콘솔에서 작업 중 claude 가 백그라운드 작업(Bash `run_in_background` / `Task`)을
  띄우고 turn 을 끝내면, 상태가 "대기중" 으로 바뀌며 (1) 대기 중이던 다른 프로젝트
  turn 이 실행되고 (2) 진행 중인 자동화가 다음 프롬프트를 발사한 뒤, 백그라운드 작업이
  끝나 turn 이 재개되면 다시 "응답중" 으로 돌아와 — 결과적으로 동시 실행 turn 이 한도를
  넘어 4개까지 누적되던 문제.** (사용자 보고)
  - **근본원인**: 백그라운드 작업이 살아있는데도 들어온 `result`(Done)을 *진짜 turn
    종료* 로 처리했다. 그 Done 에서 `gate.release()`(동시 한도 permit 반환) +
    `setBusy(false)`("대기중") + `fireTurnDone()`(자동화 다음 프롬프트)이 모두 실행됐다.
    그런데 claude 2.x 는 백그라운드 작업 완료 시 **같은 turn 을 자동 재개(across turns)**
    하는데, 이 재개 turn 은 `sendPrompt` 를 거치지 않아 **permit 없이** 진행 → 동시 한도
    (`maxConcurrentTurns`)가 무력화됐다. v1.80.0 의 `BACKGROUND_TASK_GUIDE` 프롬프트가
    "turn 을 끝내지 말라" 고 유도하지만 claude 가 항상 따르진 않아 서버가 무방비였다.
  - **수정**: rate-limit 회수(v1.99.0)와 **동형** 처리. `ProjectSession.outstandingBgTasks`
    집합으로 백그라운드 작업 lifecycle(`task_started`→추가, 완료/실패 통지→제거)을
    추적하고, 이 집합이 비어있지 않은 동안 들어온 Done 은 *종료가 아니라 재개 대기* 로
    간주 — `gate.release` / `setBusy(false)` / `clearTurnActive` / `fireTurnDone` 을 **모두
    보류**하고 "백그라운드 작업 진행 중" 시스템 알림만 노출한다. 백그라운드 작업이 모두
    끝나고 재개 turn 이 진짜 Done 될 때 permit 반환 + 자동화 통지가 정확히 1회 일어난다.
  - **부수 수정**: 백그라운드 작업 진행 프레임 수신 시 `lastActivity` 를 갱신해, suspended
    turn 이 idle reaper(기본 30분)에 죽지 않게 한다. 새 사용자 prompt 전송 시 이전 turn 의
    백그라운드 잔재(완료 통지 누락 등)를 `clear()` 해 다음 turn 이 영구 suspended 로 묶이는
    edge 를 차단한다.
  - 파일: `ClaudeSessionManager.kt`(outstandingBgTasks 집합 + Done 분기 + 추적 블록 +
    `isBackgroundTaskTerminal` / `BG_TERMINAL_STATUSES`). Wire 변경 없음 — `bg_task_suspended`
    는 기존 `ConsoleSystem` 프레임 재사용.

## [1.99.1] - 2026-06-04 — 콘솔 탭 재진입 시 스크롤이 최하단에서 어긋나던 문제 회수

### Fixed

- **다른 탭(빌드/파일 등)에 갔다가 콘솔로 돌아오면 스크롤이 최하단이 아닌 위치에서
  보이거나 "주르륵" 내려가던 현상.** v1.93.1/2 의 `applyInitialView()`(최하단 고정)는
  콘솔 iframe **최초 1회만** 호출돼, 탭 전환으로 `display:none→block` 되어 재진입할 때는
  재고정이 없었다. 콘솔이 숨겨진 동안 WS 메시지가 append 되면 `display:none` 이라
  `scrollHeight` 기반 stick 이 무효 → 다시 보일 때 어긋난 위치.
  - 수정: `project-tabs.js` 의 `activate()` 가 탭 전환 시 해당 frame 에
    `postMessage({type:'pt:tab-visible'})` 를 보내고, 콘솔이 이를 받아 자동스크롤 ON 이면
    reflow 구간 instant 재고정(애니메이션 없음), OFF 면 보던 위치 유지.
  - 캐시: `project-tabs.js?v` 1.93.3 → 1.99.1 (3개 탭 템플릿 동기). SW 는 `?v` 자산을
    network passthrough 라 CACHE_VERSION 불필요.

## [1.99.0] - 2026-06-04 — rate-limit 폭주 회수: 동시 한도(maxConcurrentTurns)가 무력화되던 근본원인

### Fixed

- **`maxConcurrentTurns`(동시 turn 한도)가 rate-limit 상황에서 무력화돼 동시 작업이 한도를
  넘고 API rate limit("Server is temporarily limiting requests")이 악순환하던 문제.**
  근본 원인은 `ClaudeSessionManager` 가 **rate-limit error 를 turn "종료"로 처리**한 것:
  `gate.release`(permit 반환) + `fireTurnDone`(자동화에 완료 통지)을 호출 → ① rate-limited
  슬롯이 즉시 다른 turn 에 넘어가며 permit 이 빠르게 회전(동시 in-flight 가 실제로 안 줄고
  burst 가속), ② 자동화가 rate-limit 을 완료로 오인해 **백오프 0 으로 다음 프롬프트 발사 +
  rate-limit 자동재개와 이중발사** → 폭주.
  - 수정: rate-limit error 는 turn "일시중단 → 재개 대기"로 처리. `gate.release`·`fireTurnDone`
    을 **호출하지 않고** permit 을 유지(슬롯 점유 → 동시 요청 실제 감소) + 자동화 미통지(이중
    발사 차단). 재개 turn 이 진짜 `Done` 될 때만 정상 종료 경로에서 release + 통지.
  - 재개의 모든 종료 경로(MAX 초과 포기 / 세션 없음·교체·종료 / 재개 전송 실패)에서
    `gate.release` + `fireInterrupt`(자동화 STOPPED) 로 permit 누수·자동화 좀비 방지.
  - 정밀 재검(code-analyzer) 보강 2건: ① 세션-없음 포기 경로의 `fireInterrupt` 누락(자동화
    좀비) 회수, ② `Done` 분기에서 살아남은 재개(retryJob) 취소 — rate-limit 직후 CLI 자체
    복구로 같은 turn 이 Done 까지 도달할 때 이중발사가 재발하던 경로 차단.
  - 정상 흐름(prompt→Done, 진짜 에러→stopped)은 무변경. gate 의 heldKeys idempotency 가
    다중 release 호출 지점을 안전하게 흡수함을 검증.

## [1.98.2] - 2026-06-03 — 세션 전체 코드 정밀 재검 회수 (AdMob 2 + 에뮬레이터 1)

이번 세션 변경분(아카이브 제외) 전체를 정밀 재검(code-analyzer 2개 영역 + 직접)하여
실 결함 3건 회수. XSS·직렬화·i18n·gosu·voice-input·wire 호환은 견고함을 확인.

### Fixed

- **[AdMob, Medium] unit ID 직렬화 라운드트립 손상** — `multi()`(ProjectKeystoreRoutes /
  KeystoreRoutes)가 콤마(=properties 직렬화 구분자)를 입력단에서 막지 않아, 한 입력칸에
  `id1,id2` 가 들어가면 저장 시 1개 값이지만 재로드(`split(",")`) 시 2개로 분리되는 비가역
  손상. `flatMap { it.split(",") }` 로 흡수 + `distinct()` 로 중복 제거.
- **[에뮬레이터, Important] offline 좀비 인스턴스를 present 로 인식** — `serialPresentCached()`
  가 `adb devices` 의 **모든 state**(offline 포함)를 인정해, 죽어가는 좀비가 `status.external`/
  `isRunning` 을 true 로 묶어 **start 를 영구 거부**하고 `stop`(adb emu kill 은 offline 에 무효)
  은 "종료 신호 보냄" 거짓 성공이 되던 모순. `state != "offline"` 필터로 live 상태만 present.

## [1.98.1] - 2026-06-03 — 아카이브 정밀점검 회수 (9개 결함: 3 High / 4 Medium / 2 Low)

v1.98.0 아카이브 기능을 배포 전 정밀 재검(code-analyzer)하여 데이터/서명키 손실 직결
결함을 일괄 회수. 모두 `ProjectArchiveService` / `ArchiveRoutes` 한정.

### Fixed

- **H1 idle 가드 부재** — 빌드/세션/자동화 진행 중에도 archive 가 `<root>/<id>` 를 통째
  삭제해 동작 중 빌드·세션이 깨졌다. archive 라우트에 폴더 rename 과 동일한
  `isBusy || isBuildRunning || isActive` 가드 추가(거부 시 `flash.project.rename.notIdle`).
  `isBuildRunning` 을 `internal` 승격해 공유.
- **H2 DB 정리 실패 시 폴더 삭제 강행** — `deleteProject` 가 실패해도 `runCatching` 이
  삼키고 폴더/키스토어를 삭제 → **Projects row 는 살아있는데 폴더 증발**(목록엔 보이나 전부
  깨짐) + 복원도 `project_exists` 가드에 막혀 데드 상태. 이제 **삭제 성공을 확인한 뒤에만**
  원본 제거, 실패 시 레지스트리 등록 롤백 + tar 제거 + 원본 보존.
- **H3 / M1 키스토어 덮어쓰기** — 복원이 같은 packageName 의 **현역 키스토어**를
  `REPLACE_EXISTING` 으로 덮어써 릴리즈 서명키 손실 위험. archive 시 `existsOriginalOrPackage`
  로 중복 아카이브 거부 + unarchive 시 현역 키스토어 존재하면 `keystore_conflict` 로 거부 +
  복원은 미존재 파일만 기록.
- **M2 동시성 race / TOCTOU** — archive/unarchive/deleteArchive 를 단일 lock 으로 직렬화.
- **M3 tar 해제 hardening** — `--no-absolute-names --no-same-owner` 추가(절대경로 엔트리 방어).
- **M4 프로세스 출력 처리** — `run()` 이 stdout 을 **별도 데몬 스레드**로 drain → readText
  블로킹이 waitFor 타임아웃을 무력화하던 문제 + 스트림 미닫힘(use) 회수.
- **L1 staging 누수** — `try/finally` 로 부분 실패 시에도 `.staging-*` 정리.
- **L2 archivePath 검증** — DB 의 archive 경로를 `ensureUnderWorkspace` 로 검증 후 사용.

## [1.98.0] - 2026-06-03 — 프로젝트 아카이브(압축 보관) / 복원

### Added

- **프로젝트 아카이브 / 복원.** 프로젝트를 `tar.gz` 로 압축 보관하고 목록에서 제거,
  필요 시 복원하는 기능. (사용자 요청)
  - **아카이브**: 소스 폴더(`<root>/<id>`) + 메타(`<root>/.vibecoder/<id>`) + **키스토어**
    (`/home/vibe/keystores/<pkg>.*`, 원본 이동) + manifest 를 `<root>/.vibecoder/archives/
    <id>-<ts>.tar.gz` 로 압축. 검증 성공 후에만 DB 자식+row 정리(`delete` 재사용) + 원본
    제거 → **목록에서 완전히 사라짐**. (원자성: tar 완성 전엔 아무것도 안 지움)
  - **복원(언아카이브)**: tar 해제로 폴더·키스토어 되돌림 + Projects row 재삽입. id/폴더
    충돌 시 거부.
  - **열람 메뉴**: Tools 탭에 **'Archive'** 신설(`/archive`) — 카드별 복원/`.tar.gz`
    다운로드/영구삭제. 프로젝트 페이지(더보기)에 **🗄 아카이브** 버튼.
  - 신규: `ArchivedProjects`(독립 테이블 — Projects FK 아님) + `ArchivedProjectRepository`
    + `ProjectArchiveService` + `ArchiveRoutes`/`ArchiveTemplates`.
  - 라우트: `GET /archive`, `POST /projects/{id}/archive`, `POST /archive/{aid}/restore`,
    `POST /archive/{aid}/delete`, `GET /archive/{aid}/download` (admin + CSRF, SSR).
  - **범위 메모**: 복원은 소스·메타(빌드 아티팩트/로그 파일 포함)·키스토어·프로젝트 등록을
    되살린다. 대화/빌드 **DB 이력**(conversation_turns 등)은 아카이브 시 정리되며 이번
    범위에서 자동 복원 대상이 아니다(파일 아티팩트는 메타 폴더로 보존). Wire change 없음.

## [1.97.0] - 2026-06-03 — MCP 카탈로그에 AdMob 조회/리포팅 추가

### Added

- **AdMob (조회/리포팅) MCP** — `kunny/admob-mcp-server`. 내 앱·광고 단위 목록(ID/유형) +
  network/mediation 수익 리포트, 상위 수익 앱·광고 단위 조회. **읽기 전용**(광고 단위 생성은
  AdMob API 미지원 — 콘솔에서만). npm 미배포(git clone+build) + OAuth Desktop 브라우저
  인증이라 `comingSoon`(수동 설치 안내)으로 노출. (`McpCatalog`, id `admob`, APP_PUBLISH)
- 참고: 광고 단위 ID 자동 수집·정리는 서버 측 AdMob OAuth 연동(사용자 브라우저 OAuth +
  `adUnits.list`)이 더 매끄러운 경로 — 별도 기능으로 검토 예정.

## [1.96.2] - 2026-06-03 — 진짜 원인: JVM 이 kvm 그룹을 못 받던 entrypoint gosu 버그

### Fixed

- **에뮬레이터 KVM 가속이 실제로 동작하지 못하던 근본 원인 — entrypoint 의 `gosu vibe:vibe`.**
  v1.96.1 의 `/dev/kvm` R/W 가드는 정확했지만 계속 false 였던 이유는, **서버 JVM 프로세스의
  supplementary groups 가 비어 있어(`Groups:` 공백) `kvm`(gid 993)을 못 받았기** 때문.
  entrypoint.sh 가 `usermod -aG kvm vibe` 로 vibe 를 kvm 멤버로 만든 뒤 **`gosu vibe:vibe`**
  (그룹 명시)로 서버를 띄웠는데, gosu 는 그룹을 명시하면 supplementary groups 를 버린다
  (setgroups 단일) → JVM 이 /dev/kvm(`crw-rw---- root:kvm`, 0660)에 접근 불가 → `-accel on`
  실패. (콘솔에서 `-accel off` 로만 에뮬레이터가 떴던 것도 이 때문.)
  → entrypoint 의 모든 `gosu vibe:vibe` 를 **`gosu vibe`** (그룹 생략)로 교체. gosu 가
  `initgroups` 로 kvm 을 포함한 전체 supplementary groups 를 적용한다. 회귀 방지 주석 추가.
- `docker exec -u vibe` 는 docker 가 /etc/group 을 다시 읽어 그룹을 부여하므로 `test -w` 가
  OK 로 보여(진단 함정), 실제 JVM 프로세스 권한과 달랐다.

### Changed (운영 정책)

- entrypoint 유저 전환 방식 변경(`gosu vibe:vibe` → `gosu vibe`). 재빌드된 이미지부터 서버
  JVM 및 그 자식(콘솔/빌드/에뮬레이터)이 kvm 등 supplementary groups 를 정상 상속한다.

## [1.96.1] - 2026-06-03 — KVM 가드 오탐 수정 (정상 KVM 환경에서 에뮬레이터 시작 막힘 회귀)

### Fixed

- **v1.96.0 의 accel-check 가드가 정상 KVM 환경에서도 에뮬레이터 시작을 막던 회귀.**
  `accelCheckUsable()` 이 `emulator -accel-check` 바이너리를 `ProcessBuilder` 로 호출했는데,
  같은 명령이 **셸에선 어떤 조건(cwd `/`, stdin `/dev/null`, 비-TTY 포함)에서도 exit 0 +
  "KVM is installed and usable"** 인데 **JVM 자식프로세스 컨텍스트에서만 오탐(false)** 을
  반환했다(emulator 런처가 JVM 하위에서 종료코드를 전파하지 못한 것으로 추정). 그 결과
  `/dev/kvm` 가 정상인데도 "KVM 가속을 쓸 수 없어 시작을 막았습니다" 로 거부됐다.
  → 바이너리 호출을 버리고 **`/dev/kvm` 의 R/W 접근성**(KVM 가속의 실제 전제, `-accel on`
  이 여는 것)을 fork 없이 직접 검사하도록 교체. access(2) 가 그룹/ACL 을 놓치는 경우 대비
  실제 R/W open 폴백 포함.

## [1.96.0] - 2026-06-03 — 에뮬레이터 안정화: KVM accel-check 가드 + 외부 인스턴스 인식/회수

### Fixed / Changed

- **에뮬레이터가 KVM 가속 없이(`-accel off`/TCG) 떠 불안정해지는 문제 차단.** 운영
  점검에서 콘솔로 수동 실행된 `-accel off` 에뮬레이터가 부팅 4분 51초 + CPU 100% +
  ANR 37개 폭주 상태로 방치됐고, 서버는 자기가 spawn 한 프로세스만 추적해 이를 인식·회수
  하지 못했다. 세 가지 가드 추가:
  1. **accel-check 가드** — `start()` 가 `emulator -accel-check` 로 KVM 가용성을 먼저
     확인하고, 불가하면 시작을 **거부**(TCG 폭주 예방) + `/dev/kvm`·kvm 그룹 안내.
  2. **외부 인스턴스 인식** — `status()`/`isRunning()` 이 `adb devices` 로 `emulator-5554`
     점유를 인식(짧은 TTL 캐시). 콘솔/수동으로 띄운 인스턴스도 running 으로 표시되고,
     `Status.external` 플래그 + `/api/emulator/status` JSON 의 `external` 필드로 노출.
  3. **중복 spawn 차단 + 회수** — 외부 인스턴스가 같은 serial 을 점유 중이면 `start()` 가
     위에 또 띄우지 않고 거부하며, `stop()` 은 서버 프로세스가 없어도 `adb emu kill` 로
     외부 좀비를 회수한다.
- **UI 경고** — `/emulator` 페이지가 외부 실행 인스턴스에 "KVM 가속 미보장 — [중지] 후
  [시작]으로 재기동" 경고 배너(`emulator.external.warn`, Ko/En) 노출.
- 참고: GPU(예: NVIDIA)는 이 문제와 무관 — 불안정의 원인은 **CPU 가속(KVM)** 부재이며,
  헤드리스 로그분석 용도라 GPU 렌더링 가속은 불필요하다.

### Wire change

- `/api/emulator/status` JSON 에 `external` 필드 추가(부가 필드 — 구 클라이언트는 무시,
  호환). ApiPath 경로 불변. vibe-coder-android `shared/` 동기 권고(선택).

## [1.95.0] - 2026-06-03 — MCP 카탈로그에 mobile-next/mobile-mcp(모바일 UI 자동화) 추가

### Added

- **Mobile (Mobile-Next) MCP** — MCP 카탈로그에 `@mobilenext/mobile-mcp` 추가
  (빌드환경 > MCP 페이지에서 체크 후 설치). adb(Android)·xcrun(iOS) 위에서 동작하는
  zero-config 모바일 UI 자동화 — accessibility tree 기반 탭/스와이프/타이핑/스크린샷.
  vibe-coder 의 헤드리스 에뮬레이터(빌드환경 > Emulator, v1.73.0)와 연계하면 Claude 가
  빌드한 앱을 직접 조작·검증할 수 있다. `recommended` 노출, COMMUNITY tier, API 키 불필요.
  (`McpCatalog`, id `mobile-mcp`, homepage mobile-next/mobile-mcp)

### Changed

- MCP 카탈로그 BROWSER 카테고리 라벨을 "브라우저 / 모바일 자동화 (Playwright /
  Puppeteer / Mobile)" 로 확장.

## [1.94.0] - 2026-06-03 — AdMob 광고 6종 + 유형별 다중 unit ID + 음성인식 중복 누적 수정

### Added

- **AdMob 광고 유형 6종 전체 지원.** 기존 3종(배너 / 앱 오프닝 / 네이티브 광고 고급형)에
  **전면 광고 / 보상형 광고 / 보상형 전면 광고** 3종을 추가. App ID 1개 +
  6개 유형으로 구성. (`KeystoreService.AdmobIds`)
- **유형별 다중 unit ID.** 한 광고 유형에 unit ID 를 여러 개 등록 가능 (A/B 테스트,
  배치별 분리 등). 프로젝트 키스토어 탭의 각 유형 행에 **"+ 단위 추가" / "삭제"**
  버튼(JS 동적 행). 서버는 같은 name 의 input 을 `form.getAll()` 로 수집.
- **AdMob 라벨 i18n 한글화.** 프로젝트 키스토어 탭(`ProjectKeystoreRoutes`)이 영어
  하드코딩이던 라벨/제목/버튼을 `Messages` i18n 으로 전환 — 한국어 UI 에서 "배너",
  "앱 오프닝", "네이티브 광고 고급형", "전면 광고", "보상형 광고", "보상형 전면 광고"
  로 표시. 전역 `/settings/keystores` · 빌드탭 생성폼도 6종으로 확장.

### Changed

- **`<pkg>-admob.properties` 다중값 포맷.** 각 unit 키(`bannerAdUnitId` 등)를 콤마
  구분으로 직렬화. AdMob unit ID(`ca-app-pub-…/…`)에는 콤마가 없어 안전하고,
  v1.93.0 까지의 단일값 파일은 split 시 1개 리스트로 그대로 호환(마이그레이션 불필요).
  신규 키: `interstitialAdUnitId` / `rewardedAdUnitId` / `rewardedInterstitialAdUnitId`.
  빌드 스크립트는 `split(",")` 로 읽으면 됨(`ks.usage.step3` 안내 갱신).

### Fixed

- **음성 입력이 같은 조각을 계속 덧붙이던 버그**("뭔가 하다가 만 것 같은데" →
  "뭔가뭔가뭔가뭔가 하다가뭔가 하다가 만…"). `voice-input.js` 의 `onresult` 가
  `e.resultIndex` 부터 `finalText` 에 누적(`+=`)했는데, 일부 엔진(특히 ko-KR /
  모바일 Chrome)이 이미 final 처리된 result 를 `resultIndex=0` 으로 재전송하면 같은
  조각이 반복 누적됐다. 매 콜백마다 `e.results` 전체를 처음부터 재구성(idempotent)
  하도록 변경 — 재전송·인덱스 리셋에도 중복이 생기지 않는다.
- 캐시: `voice-input.js` 는 `?v` 없이 `/static/` cache-first 라 SW 에 박제됨 →
  `sw.js` `CACHE_VERSION` v1.90.15 → v1.94.0 bump 로 구 SW 캐시 무효화(activate 시 삭제).

### Wire change

- 없음. 키스토어/AdMob 은 SSR 라우트(ApiPath 대상 아님) — vibe-coder-android `shared/`
  동기 불필요.

## [1.93.3] - 2026-06-03 — 프로젝트 화면 렌더링 정돈 (요소가 따로 그려졌다 재조합되는 현상 제거)

### Fixed

- **프로젝트 화면 진입 시 여러 UI 요소가 따로 그려진 뒤 재조합되며 "정신 없이"
  완성되던 현상.** 세 가지 원인을 각각 해소:
  1. **활성 탭이 서버에서 안 정해짐** — 모든 `.tab-pane` 이 `display:none` 으로
     시작해 JS `activate()` 전까지 빈 화면 → 콘솔이 늦게 튀어나오고 탭 버튼
     하이라이트도 사후에 붙었다. 기본 탭(console) pane/button 을 **서버에서 미리
     `active`** 로 마킹 → 첫 페인트부터 올바른 탭.
  2. **rail 여백을 JS 가 iframe 로드 후 주입** — inner `.content` 를 가운데→왼쪽 +
     우측 패딩으로 재배치(reflow)했다. `embed` 이면서 프로젝트 탭(`/projects`)인
     페이지에 한해 **CSS 로 rail 폭만큼 우측 여백을 선반영**(`AdminTemplates.shell`
     의 `body.pt-embed .content`) → 첫 페인트부터 최종 위치(JS 가 같은 값을 재적용해도
     변동 없음).
  3. **셸이 조각조각 조립되는 모습** — `#project-tabs-root` 를 **reveal gate**(처음
     `opacity:0`)로 가린 뒤, `project-tabs.js` 가 활성 탭/레이아웃을 정리하고 다음
     프레임에 `.pt-ready` 로 **한 번에 페이드인**. JS 실패 시에도 CSS 애니메이션
     폴백(2.5s)이 결국 표시하므로 영구 숨김 위험 없음. `prefers-reduced-motion` 존중.
- 캐시: `project-tabs.js?v` 1.90.7 → 1.93.3 (3개 탭 템플릿 동기). SW 는 `?v` 자산을
  network passthrough 라 CACHE_VERSION 불필요.

## [1.93.2] - 2026-06-03 — 콘솔 초기 진입 스크롤 위치 개선

### Fixed

- **콘솔 탭에 처음 들어가면 상단 어딘가에서 시작해 하단으로 "주르륵" 내려가던
  현상.** 초기 히스토리 렌더 끝에서 `scrollTop=scrollHeight` 를 **1회만** 호출해서,
  그 뒤에 일어나는 비동기 reflow(부모 ProjectTabs 의 rail padding 주입 / hljs
  코드 하이라이트 / iframe 레이아웃 확정)로 최하단이 어긋나고 화면이 위→아래로
  이동해 보였다. 초기 진입 뷰 결정을 `applyInitialView()` 로 분리:
  - **자동 스크롤 ON**: 즉시 최하단 고정 + 비동기 reflow 구간(`requestAnimationFrame`
    + 0/50/150/350ms) 동안 instant(애니메이션 없음)로 재고정 → **첫 페인트부터
    최하단**, 시각적 이동 없음. 그 사이 사용자가 직접 스크롤(wheel/touch)하면 즉시 중단.
  - **자동 스크롤 OFF**: 직전에 보던 스크롤 위치를 그대로 복원(없으면 최하단).
- **콘솔 스크롤 위치 영속 추가.** 스크롤 시 마지막 위치를 `localStorage`
  (`vibe.console.scroll.<projectId>`, 프로젝트별, 250ms 디바운스)에 저장 →
  OFF 모드 재진입 시 복원에 사용.

## [1.93.1] - 2026-06-03 — 콘솔 대기열 뱃지/메시지의 `%d` 미치환 버그 수정

### Fixed

- **콘솔 큐 i18n 의 `%d` 가 그대로 노출되던 버그** — busy 뱃지/큐 메시지가
  "● 응답중 (대기 %d)", "대기열 추가 (#%d): …", "대기열 초기화 (%d개 제거)"
  처럼 `%d` 가 숫자로 치환되지 않고 문자 그대로 표시됐다.
  - 원인: 이 문자열들은 JS 측 치환을 위해 `Messages.t(lang, key, "___N___")` 로
    **문자열 토큰**을 채워 템플릿을 만든다(`BUSY_QUEUED_TPL` 등). 그런데 값이
    `%d` 라서 `String.format("…%d…", "___N___")` 가 `IllegalFormatConversionException`
    을 던지고, `Messages.t` 의 `runCatching{…}.getOrDefault(raw)` 가 **원본 템플릿
    (`%d` 포함)** 을 반환 → JS 의 `.replace('___N___', n)` 이 매칭 실패 → `%d` 노출.
  - 수정: 해당 3개 키(`console.busy.responding.queued` / `console.queue.added` /
    `console.queue.cleared`)의 `%d` → `%s` (ko/en 모두). 토큰이 문자열이라 `%s` 로
    포맷해야 보존되고, 최종 숫자는 기존대로 JS 가 삽입한다. `%s` 는 Int 인자에도
    안전하므로 회귀 없음.
  - 같은 패턴의 나머지 토큰 채움 키(`env.task.successMsg/failureMsg`,
    `mcp.js.alertPendingFile`)는 이미 `%s` 라 영향 없음.

## [1.93.0] - 2026-06-02 — 프로젝트 키스토어 탭 (키스토어 / AdMob / SHA 지문)

### Added

- **프로젝트 페이지에 "키스토어" 탭 신규** (`/projects/{id}/keystore`, 더보기 메뉴).
  전역 `/settings/keystores` 페이지 재활용이 아닌, 해당 프로젝트의 `applicationId`
  한 건만 다루는 **프로젝트 스코프 신규 페이지**(사용자 명시 요청). 같은
  `KeystoreService` 인스턴스를 공유하므로 빌드 서명 inject 와 SSOT.
  - **상태** — release / debug / `.properties` / admob 파일 존재 여부 + 생성일.
  - **SHA 지문 열람** (접어두기) — SHA-1 / SHA-256 / MD5 + 만료일을 release/debug
    각각 표시. Firebase / Google Sign-In / Maps API 등록용. `<details>` 펼칠 때만
    `keytool -list -v` 를 1회 lazy 호출(탭 프리로드 시 프로세스 spawn 회피).
    값 클릭 시 전체 선택(복사 편의). 비밀번호를 모르면 안내 문구.
  - **AdMob 광고 ID 관리** (접어두기) — App / App-Open / Banner / Native Unit ID
    를 키스토어 유무와 **독립적으로** 저장/수정/삭제(`<pkg>-admob.properties`).
    4개 모두 비우면 파일 삭제.
  - **키스토어 생성** (미존재 시, 접어두기) — 패키지는 프로젝트 값으로 고정,
    DN/비밀번호/유효기간은 마지막 입력값 prefill. 생성 시 AdMob 동시 저장 가능.
  - **build.gradle.kts 서명 적용** — 기존 정형화 prompt 를 Claude 콘솔로 전송
    (전역 페이지의 apply 와 동일 로직 재사용).
  - **삭제** — release/debug/properties/admob set 일괄 삭제.
- 새 라우트(모두 SSR, admin 인증 + CSRF): `GET /projects/{id}/keystore`,
  `GET /projects/{id}/keystore/fingerprints`(HTML 조각),
  `POST /projects/{id}/keystore/{create,admob,apply,delete}`.
  JSON wire 아님 → `shared/ApiPath` 대상 아님(symbols/env-files 와 동일 컨벤션),
  Android 동기 불필요.

### Changed

- `KeystoreService` 에 `fingerprints()` / `readAdmob()` / `saveAdmob()` 추가.
  `buildApplySigningPrompt` 시그니처를 `ProjectRow` → `(projectId, moduleName)` 로
  바꿔 `internal` 노출(전역/프로젝트 양쪽 라우트 공유).

## [1.92.0] - 2026-06-02 — 이미지 처리 도구 기본 내장 (ImageMagick / Pillow 등)

### Added

- **이미지 도구 기본 내장.** Claude Code 가 스크린샷·디자인 mockup·앱 아이콘·APK
  리소스 등을 별도 설치 없이 바로 다룰 수 있도록 런타임 이미지에 핵심 image 도구를
  포함했다. 추가된 패키지:
  - `imagemagick` — `convert` / `mogrify` / `identify` (변환·리사이즈·합성)
  - `python3` + `python3-pip` + `python3-pil`(Pillow) + `python3-numpy` — PIL 기반
    스크립트. PEP 668(externally-managed) 때문에 `pip install` 대신 apt 패키지 사용.
  - `librsvg2-bin` — `rsvg-convert` (SVG → PNG, Android 벡터 아이콘)
  - `webp` — `cwebp` / `dwebp`
  - `poppler-utils` — `pdftoppm` / `pdfinfo` (PDF → 이미지)
  - `ghostscript` — ImageMagick 의 PDF/PS delegate
  - `optipng` / `pngquant` / `jpegoptim` — 이미지 최적화
- **ImageMagick PDF/PS 정책 완화(방어적).** 일부 배포판의 `policy.xml` 은
  Ghostscript CVE-2016-3714 완화를 위해 PDF/PS/EPS coder 를 차단해
  `convert x.pdf y.png` 이 "not authorized" 로 실패한다. 현재 resolute 기본
  이미지(ImageMagick 7.1.2)엔 차단이 없어 사실상 no-op 이지만, 베이스 교체/IM6
  회귀 시에도 PDF→이미지 변환이 보장되도록 정책 줄을 방어적으로 제거(IM6/IM7
  경로 모두). 1인 신뢰 LAN 도구·입력이 사용자 본인 자산뿐이라는 전제.

### Changed

- **`vibe` 유저 NOPASSWD sudo 재확인.** 이미 v0.7.0 부터 `/etc/sudoers.d/vibe-nopasswd`
  로 패스워드 없는 sudo 가 구성돼 있어, 위 기본셋 외에 필요한 도구는
  `sudo apt-get update && sudo apt-get install <pkg>` 로 언제든 추가 설치 가능.
  (별도 변경 없음 — 본 릴리스에서 동작을 명시적으로 문서화.)

### 마이그레이션

- 신규 이미지로 `docker compose pull && docker compose up -d --force-recreate` 하면
  자동 적용. 볼륨/DB 영향 없음. 이미지 크기는 image 도구 + python3 스택으로 약
  120~180MB 증가.

## [1.91.5] - 2026-06-02 — 콘솔 새 세션 첫 프롬프트 누락 + 메모 다이얼로그 버튼 깨짐 수정

### Fixed

- **콘솔에서 프롬프트 전송 후 다른 페이지 갔다 돌아오면 직전 프롬프트가 "가끔" 사라지던
  현상.** 새 세션의 첫 프롬프트는 `sendPrompt` 시점에 아직 Claude session_id 가 발급되기
  전(null)이라 `conversation_turns` 에 `session_id=NULL` 로 저장됐는데, 콘솔 복원
  (initialHistory)은 현재 session_id 로 필터해서 그 user 턴만 누락됐다(assistant 응답은
  실제 id 로 저장돼 보임). Claude init(SessionStarted) 이벤트로 session_id 가 확정될 때,
  직전에 NULL 로 저장된 메인 콘솔 턴(`agent_name IS NULL`)을 실제 id 로 backfill 하도록
  수정. `ConversationTurnRepository.adoptNullSession` + `ClaudeSessionManager` SessionStarted
  처리(prev==null 일 때만).
- **메모 다이얼로그(`/memos`) 하단 버튼이 두 줄로 깨지고 저장 버튼이 과하게 길게 보이던
  현상.** admin.css 의 `button:not(.primary)...` 전역 규칙(specificity 0,5,1)이 모달의
  `.memo-btn`(0,1,0)을 이겨 큰 padding·테두리를 강제했다. 버튼 규칙을 `#memo-modal` ID
  prefix(1,1,0)로 올리고 `width:auto` / `white-space:nowrap` / `flex:0 0 auto` 명시.
  (프로젝트 rail 메모 모달은 `#project-tabs-root` ID 로 이미 보호됨.)

## [1.91.4] - 2026-06-02 — 콘솔 메시지 필터를 다이얼로그로 + 자동 스크롤과 한 줄 배치

### Changed

- **콘솔 "메시지 필터"를 접기(`<details>`) 대신 버튼 → 다이얼로그(모달) 로 변경.**
  버튼은 "자동 스크롤" 토글과 **같은 라인의 좌측**에 배치(자동 스크롤은 우측 끝).
  필터 체크박스 로직(`.filter-cb` / `#filter-summary` / `#filter-reset` /
  localStorage)은 그대로 — 모달 안으로 이동만. 모달은 배경 클릭 / ✕ / 닫기 / Esc
  로 닫힘. 파일: `admin/WebProjectTemplates.kt`.

## [1.91.3] - 2026-06-02 — 콘솔 퀵 프롬프트: "중단된 작업 재시작" 추가

### Added

- **콘솔 빠른 프롬프트 버튼에 "중단된 작업 재시작" 추가.** 중단·크래시·세션 끊김으로
  멈춘 작업을 점검하고 미완료 부분을 이어서 완료하도록 지시하는 한 클릭 버튼. `continue`
  옆에 노출되며, 코드 작업 전용이라 대화 전용 General Chat 콘솔에선 제외. 파일:
  `admin/WebProjectTemplates.kt`(textKeys) + i18n `console.quick.restart.{label,prompt}`.

## [1.91.2] - 2026-06-02 — MCP 카탈로그에서 Fetch 제거

### Removed

- **MCP 카탈로그에서 `Fetch` (`@modelcontextprotocol/server-fetch`) 항목 제거.**
  카탈로그(설치 옵션 목록)에서만 빠지며, 이미 설치해 `.mcp.json` 에 등록된
  기존 사용자에겐 영향 없음. 파일: `env/McpCatalog.kt`. wire 변경 없음.

## [1.91.1] - 2026-06-02 — MCP 카탈로그: App Publish (Play + App Store) 통합 추가

### Added

- **MCP 카탈로그에 `App Publish (Play + App Store)` 추가** (`mikusnuz/app-publish-mcp`,
  npm `app-publish-mcp`). Google Play Console + Apple App Store Connect 를 한 MCP 로
  다루는 통합 서버 (Play 35 + Apple 56 도구 — 릴리스/트랙 업로드/스크린샷/리뷰/구독 등).
  - 기존 `Google Play Publisher` / `App Store Connect` 항목은 **그대로 유지** (교체 아님).
  - `npx -y app-publish-mcp` 로 실행 (bin 제공). category `APP_PUBLISH`, trust
    `EXPERIMENTAL`.
  - configFields (모두 optional — 쓰는 플랫폼 것만 입력): `GOOGLE_SERVICE_ACCOUNT_PATH`
    (파일), `APPLE_KEY_ID`, `APPLE_ISSUER_ID`, `APPLE_P8_PATH`(파일). 빈 값은
    `.mcp.json` env 에 주입되지 않음.
  - 파일: `env/McpCatalog.kt`. wire 변경 없음(카탈로그 데이터 추가).

## [1.91.0] - 2026-06-02 — 메모 기능 (전역/프로젝트별)

전역 메모 + 프로젝트별 메모. 좌측 사이드바 "Memos" 메뉴에서 모든 메모를 카드형으로
나열하고 미니창(다이얼로그)으로 열람·편집한다. 프로젝트 콘솔 화면 우측 rail 의
프롬프트 히스토리 하단에도 메모 위젯(전역 + 이 프로젝트)을 노출하고, 추가 시
scope(전역/이 프로젝트)를 선택한다. 전역 메모는 모든 프로젝트 화면에서 보이고,
프로젝트별 메모는 해당 프로젝트 화면에서만 보인다.

### Added

- **독립 메모 (전역/프로젝트별).** `conversation_turns.user_memo`(turn 인라인 메모,
  v0.61.0) 와 별개의 free-form 메모.
  - DB: `memos` 테이블 (`id`, `project_id` nullable FK, `content`, `created_at`,
    `updated_at`). `project_id` NULL = 전역, non-null = 프로젝트 전용.
  - 좌측 사이드바 **Memos** 메뉴 + `/memos` SSR 카드형 목록 페이지. 카드 클릭 →
    미니창(다이얼로그) 보기/편집, "새 메모" → scope 선택 + 본문 입력 다이얼로그.
  - 프로젝트 콘솔 화면 우측 rail (프롬프트 히스토리 하단) **메모 위젯** — 전역 +
    이 프로젝트 메모 노출, ＋ 버튼으로 추가, 항목 클릭으로 미니창 편집.
  - 파일: `MemoDtos.kt`(shared), `db/Schemas.kt`(Memos), `repo/MemoRepository.kt`,
    `memo/JsonMemoRoutes.kt`, `admin/MemosRoutes.kt`, `ProjectTabsTemplate.kt`(rail
    위젯), `AdminTemplates.kt`(nav + notebook 아이콘), `SettingsNav.kt`.
  - 프로젝트 삭제 cascade(`ProjectService.delete`) 에 `MemoRepository.deleteForProject`
    추가 — 프로젝트 전용 메모만 정리, 전역 메모는 보존. 프로젝트 id rename
    (`ProjectRepository.renameId`) 에도 `memos.project_id` repoint 추가.

### Wire change

**Yes** — vibe-coder-android `shared/` 동기 필요.

- 신규 DTO `shared/dto/MemoDtos.kt`: `MemoDto`, `MemoCreateRequestDto`,
  `MemoUpdateRequestDto`, `MemoListResponseDto`, `MemoMutationAckDto`.
- 신규 `ApiPath` 상수: `MEMOS = "/api/memos"`, `memo(memoId)` 함수.
- 신규 endpoint (Bearer 토큰 또는 cookie 세션 인증, POST/PUT/DELETE 는 write 권한):
  - `GET /api/memos[?projectId=X]` — 전체 또는 (전역 + 프로젝트 X) 목록.
  - `POST /api/memos` — 생성 (body `MemoCreateRequestDto`).
  - `PUT /api/memos/{memoId}` — 본문/scope 수정 (body `MemoUpdateRequestDto`).
  - `DELETE /api/memos/{memoId}` — 삭제.
- 호환성: 기존 클라이언트 영향 없음(신규 테이블·endpoint). `memos` 테이블은
  `createMissingTablesAndColumns` 로 기존 DB 에 자동 추가.

## [1.90.17] - 2026-06-02 — 콘솔 접기 높이 50% 축소(360→180px)

### Changed

- **콘솔 메시지 접힘 상태 높이를 360px → 180px(50%)로 축소.** 긴 메시지/도구 결과가 접혔을 때
  차지하는 높이를 절반으로 줄여 한 화면에 더 많은 메시지를 본다. 메인·sub-agent 콘솔 모두
  적용(임계 높이 + clamp max-height).

## [1.90.16] - 2026-06-02 — replay clip JSON 도 unescape 경유

### Fixed

- **replay(history) 경로에서 clip 된 이중 인코딩 tool 결과가 여전히 `\t`/`\n` literal 로 남던
  잔여 케이스.** `tryParse` 가 clip 으로 불완전해진 JSON 파싱에 실패하면 raw text 를
  `extractToolResult` 없이 직접 써서 unescape 가 적용되지 않았다. parsed 가 null 이어도
  `extractToolResult` 를 거치도록 변경(clip-tolerant unescape 적용).

## [1.90.15] - 2026-06-02 — clip 된 이중 인코딩 tool 결과 unescape

### Fixed

- **v1.90.14 후에도 일부 tool 결과에 `\t`/`\n` 가 남던 문제.** 해당 출력은 JSON 이중 인코딩
  문자열인데 표시용 clip(`…(+N)`)으로 **닫는 따옴표가 잘려** 양끝 따옴표 검사에 걸리지 않아
  unescape 가 skip 됐다. `unescapeJsonString` 을 clip-tolerant 로 보강 — 시작 따옴표 + escape
  패턴이면 닫는 따옴표 유무와 무관하게 best-effort 수동 unescape. `?v`/CACHE 1.90.14 → 1.90.15.

## [1.90.14] - 2026-06-02 — 이중 인코딩 tool 결과의 `\t`/`\n` literal 회수

### Fixed

- **일부 tool 결과에 `\t`/`\n` 가 실제 탭/줄바꿈이 아니라 literal 문자로 보이던 문제.**
  일부 tool_result output 이 JSON 으로 한 번 더 인코딩된 문자열(양끝 따옴표 + escape)로 와서
  `extractToolResult` 가 string 을 그대로 반환 → 콘솔에 `1\timport…\n2\t…` 가 raw 로 노출됐다
  (운영 실측 33개 중 5개). `unescapeJsonString` 추가 — 양끝이 따옴표인 유효 JSON 문자열이면
  1회 `JSON.parse` 로 unescape, 아니면 원문 유지(백슬래시 손상 방지).
  - console-render.js?v 1.90.10 → 1.90.14, sw.js CACHE_VERSION v1.90.10 → v1.90.14.

### Fixed

- **콘솔에서 syntax highlight 가 전혀 안 되던 문제.** highlight.js 자산은 번들돼 있었지만 어느
  페이지도 `<script>` 로 로드하지 않아 `window.hljs` 가 없었고, assistant 코드블록·tool 결과
  highlight 가 항상 조용히 skip 됐다(코드블록 박스만 적용). 콘솔 페이지에 `highlight.min.js`
  + `highlight-github-dark.min.css` 를 console-render.js 앞에 동기 로드해 활성화.

## [1.90.12] - 2026-06-02 — 콘솔 tool 결과 코드블록 + syntax highlight

### Changed

- **tool 결과(파일 내용·명령 출력)를 코드블록으로 렌더 + syntax highlight.** 이전엔 monospace
  평문(escHtml)이라 일반 텍스트와 구분이 약하고 코드 색이 없었다. 이제 `tool-out`/`tool-err`
  메시지를 `<pre class="md-pre"><code>` 박스(배경+가로스크롤+monospace)로 감싸고 highlight.js
  `highlightElement`(auto-detect)로 Kotlin/XML/JSON 등 색을 입힌다. assistant 코드블록과 동일
  톤이라 일관적이며, `cat -n`(`1\t…`) 줄번호도 가로스크롤 박스로 정렬이 유지된다.
  - `.md-pre` CSS 스코프를 `.log-body.md`(assistant 전용) → `.log-body`(tool 결과 포함)로 확장.

### Fixed

- **알림 벨 버튼 스타일이 그동안 무엇을 바꿔도 화면에 반영되지 않던 근본 원인.** admin.css 의
  `button:not(.primary):not(.danger):not(.chip):not(.logout):not(.tab)` 규칙은 specificity 가
  **(0,5,1)**(:not 5개)인데, 알림 벨 CSS `.vibe-notif-btn` 은 **(0,1,0)** 이라 매번 졌다.
  그래서 사각 테두리/투명 배경(v1.90.6) 등 의도한 스타일이 admin.css 의 일반 button 스타일
  (radius `--radius`, padding 8px16px, `--bg-card`)에 덮여 왔다(운영 브라우저 실측으로 확인).
  벨 버튼 셀렉터를 **id(`#vibe-notif-btn`, specificity (1,0,0))**로 올려 확실히 이기게 회수.
  - 이전 v1.90.1~v1.90.6 의 벨 스타일 시도들이 화면에 안 보였던 것은 전부 이 specificity 패배가
    원인이었다(코드는 정상 배포됐으나 적용이 안 됨).

### Changed

- **`task_progress` 콘솔 이벤트 처리 추가.** 그간 `task_started`/`task_notification`/`task_updated`
  는 깔끔한 todo 카드였지만 `task_progress` 만 누락돼 `system·task_progress` + generic 요약으로
  노출됐다(운영 DB 기준 177건). `task_updated` 와 동일한 "… task" todo 카드로 렌더(+description fallback).
- **`summarizeInput` 중첩 객체 키 노출.** 이전엔 중첩 객체를 `{…}` 로만 표시해 내용이 전무했다.
  이제 키 일부(`{a, b, c, …}`)를 노출해 MCP/커스텀 도구 입력의 가독성↑.
- console-render.js 수정 → `?v` 1.86.5 → 1.90.10, sw.js `CACHE_VERSION` v1.86.5 → v1.90.10.

### 검토 메모

- 콘솔 렌더는 전반적으로 견고함을 재확인(운영 DB role 분포 분석): raw JSON 노출 경로 거의 없음.
  `usage`/`thinking_tokens`/`thinking` 은 의도적 드롭·뱃지, `tool_use` 는 `tool_name` 보존으로
  도구명 표시, `rate_limit_event` 는 "⏳ rate limit" 처리됨.

### Changed

- **콘솔 복사 버튼은 항상 원문(clip 전 전체)을 복사.** 이전엔 화면에 표시된 clip 본문을 그대로
  복사해, 잘린 tool 결과는 잘린 채 복사됐다. `append` 의 복사 핸들러가 `opts.raw`(있으면)를
  우선 복사하도록 하고, tool 결과(live/replay) append 시 clip 전 원문을 `raw` 로 전달.
- **tool 결과 표시 clip 한도 4000 → 8000자.** 파일 내용 등 긴 결과의 표시 잘림 완화(복사는
  위 변경으로 한도와 무관하게 전체).

### Fixed

- **assistant 외 메시지(tool 사용/결과·system·error 등)가 접힌 채 펼쳐지지 않던 문제.**
  v1.90.5 에서 접기/펼치기를 모든 메시지로 확장할 때 CSS·clamp 판정은 바꿨지만 **펼치기 토글
  click 핸들러만 `.log-line.assistant .log-content` 한정**으로 남아, 파일 내용 같은 tool 결과가
  접히기만 하고 클릭해도 안 펼쳐졌다(불완전 수정). 셀렉터를 모든 `.log-content` 로 일치.

### Fixed

- **알림 미니창이 콘솔(iframe) 영역 클릭으로 안 닫히던 문제.** 통합 탭의 콘솔은 iframe(별도
  document)이라, 부모 shell 의 알림 벨이 등록한 `document` click 이 iframe 내부 클릭에는
  발생하지 않아 미니창이 열린 채 유지됐다. 부모 `window` 의 `blur`(=iframe/다른 영역으로
  포커스 이동) 시 닫도록 추가하고, `Esc` 닫기도 함께 추가. 부모 영역 외부 클릭은 기존
  document click 으로 계속 닫힌다.
- **프로젝트 전환 콤보박스(+설정/더보기 팝업)도 동일하게 iframe 영역 클릭 시 닫기.** 같은
  원인(콘솔 iframe 클릭이 부모 document click 미발생)으로 `pt-switcher`/`pt-settings`/
  `more-dropdown` 이 안 닫혔다. `project-tabs.js` 에 부모 `window` blur + `Esc` 시 모든 팝업
  닫기 추가. `project-tabs.js?v` 1.89.0 → 1.90.7.

### Changed

- **알림 벨을 콘솔 우상단 "프로젝트 설정 버튼"(`pt-settings`)과 동일 스타일로.** 그간 원형 +
  그림자 + 채운 배경이라 사각 테두리형 설정 버튼과 형태가 달라 "같은 스타일"로 안 보였다.
  이제 `pt-settings summary` 와 동일하게 **사각(radius 4px) + 1px 테두리(#1f2330, hover #2a3145)
  + 투명 배경 + dim 색(--text-dim)**, 아이콘 18px. unread 시 danger 강조만 유지.
- **sub-agent 콘솔에도 접기/펼치기 적용**(메인 콘솔 v1.90.5 와 일관). `SubAgentRoutes` 의
  append 에 `.log-content` wrapper + 길이(360px) 기반 clamp + click 토글 + `.md`/clamp CSS 추가.
  user 프롬프트(별도 2줄 클램프)는 제외. `renderSubAgentConsole` 에 lang 파라미터 추가(i18n).

### Changed

- **콘솔의 모든 메시지에 길이 기반 접기/펼치기 적용.** 이전엔 assistant(Claude 응답)만
  일정 높이(360px) 초과 시 접혔는데, tool 사용/결과·system·error·user 메시지도 동일 임계로
  접고 탭하면 펼치도록 일관 적용. CSS 셀렉터를 `.log-line.assistant .log-content` → `.log-content`
  로 확장하고, clamp 판정을 assistant 전용 블록 밖으로 이동(코드블록 syntax highlight 는
  마크다운인 assistant 만 유지). 긴 도구 출력/파일 결과로 콘솔이 길어지던 문제 완화.

### Fixed

- **"Claude CLI 로그인 필요" 라이브 배너 오탐.** 콘솔의 `detectAuthFailure` 가 **assistant
  응답 본문**과 **tool 결과(파일/명령 출력)**에도 `AUTH_FAIL_RE` 를 돌려, 결제·인증을 다루는
  프로젝트에서 Claude 가 정상 출력한 "unauthorized" / "authentication required" /
  "invalid api key" 등을 로그인 실패로 오인해 배너를 띄우고 입력창을 잠갔다.
  - assistant·tool_result 경로에서 감지를 제거(서버 진단성 error/system 프레임에서만 수행).
  - 정규식을 CLI 특유 문구(`not logged in` / `please run /login` / `claude login` /
    `invalid api key … /login`)로 축소해 일반 단어 오탐 차단.
  - 참고: 서버는 로그인 실패를 콘솔로 emit하지 않으므로(진짜 진단은 `/env-setup` 의
    EnvDiagnostics) 이 배너는 어디까지나 보조 fallback 이다.
- **sub-agent 콘솔에서 마크다운이 raw 로 보이던 문제.** 메인 콘솔은 v1.85.0 에서 assistant
  응답을 `renderMarkdown` 으로 렌더하지만 sub-agent 콘솔(`SubAgentRoutes`)은 `escHtml` 만
  써서, code-reviewer 같은 sub-agent 의 마크다운 응답(헤딩/표/코드블록)이 raw 로 노출됐다.
  메인 콘솔과 동일하게 assistant 에 `renderMarkdown` 적용 + `.md` 마크다운 스타일을 sub-agent
  페이지에도 추가.
  - 메인 콘솔에서 **Task 도구로** sub-agent 를 호출한 경우의 결과는 `console_tool_result`
    (코드/파일과 구분 불가)라 여전히 raw 다 — sub-agent 전용 콘솔(`/agents/...`)에서 보면
    마크다운으로 렌더된다.

### Fixed

- **알림 벨이 다른 UI 와 색이 미묘하게 다르던 근본 원인 회수.** `NotificationBell` 의 CSS 가
  `--card`/`--fg`/`--card-hover`/`--muted` 등 **admin.css 에 존재하지 않는 변수명**을 써서,
  항상 fallback 하드코딩 색이 적용되어 사이드바 nav 등과 톤이 어긋났다. 실제 변수
  (`--bg-card`/`--text`/`--bg-elev`/`--text-dim`/`--border`/`--danger`/`--shadow`/`--radius`)로
  전면 통일 → 라이트/다크 테마에도 정상 적응.
- **벨 아이콘을 사이드바 nav 아이콘과 동일 톤으로.** 20px + `stroke-width` 2 + `opacity` 0.85
  (hover 시 1) — `.nav-links a .nav-icon` 와 일치. (이전 24px/2.2 → nav 기준 재적용.)
- 알림 종류별 색(`kindColor`)도 admin 팔레트 hex(`--ok`/`--primary`/`--danger`/`--warn`/
  `--text-dim`)와 동일 값으로 정렬.

## [1.90.2] - 2026-06-02 — 알림 벨 아이콘 비율 재조정

### Fixed

- **알림 벨 아이콘이 여전히 작던 문제 재조정.** v1.90.1 에서 버튼을 줄이는 방향(40→36)은
  오히려 전체가 작아 보였다. 버튼을 적당한 크기(38px)로 두고 아이콘을 키워(20→24px,
  stroke 2→2.2) 버튼을 채우도록 비율을 개선(아이콘/버튼 ≈ 0.63).

## [1.90.1] - 2026-06-02 — 알림 벨 아이콘/버튼 비율 정리

### Fixed

- **알림 벨 버튼이 크고 아이콘이 작던 비율 문제.** 버튼 40px + 아이콘 20px → 버튼 36px +
  아이콘 22px 로 조정해 사이드바 nav 아이콘(20px)과 시각 균형을 맞췄다. inline CSS 라
  즉시 반영(`?v`/SW 무관).

## [1.90.0] - 2026-06-02 — 동시성 한도 런타임 반영 + rate limit 대기 안내

### Added

- **rate limit 대기 콘솔 안내 + 순차 진행.** 동시 작업 한도 도달 시 새 prompt 는 거부되지
  않고 대기(queue)했다가 다른 작업이 끝나면 순차 진행되는데, 그동안 콘솔에 "동시 작업
  한도(N개)에 도달해 대기 중" system 메시지를 표시한다(`ClaudeConcurrencyGate.acquire` 의
  `onWait` 콜백 — 즉시 확보되는 정상 경로엔 안내하지 않음). 메인 콘솔 + sub-agent 모두 적용.
- **동시성 한도 동적 변경.** `ClaudeConcurrencyGate.setLimit` — `/settings` 에서
  maxConcurrentTurns 를 바꾸면 **재시작 없이 즉시** 반영된다. 늘리면 대기 중이던 turn 이
  즉시 진행, 줄이면 진행 중 turn 은 그대로 두고 신규 turn 부터 새 한도 적용(eventual).

### Fixed

- **설정 저장이 적용/표시되지 않던 문제.** `/settings` 저장은 파일엔 정상 기록됐지만,
  폼과 런타임이 startup 시점의 immutable snapshot(`deps.config`)을 참조해 저장값이 보이지도
  반영되지도 않았다(재시작 전까지). 런타임 SSOT `ConfigHolder` 도입 — 저장 직후 갱신해
  **폼이 저장값을 즉시 표시**하고, 동시성 한도는 `setLimit` 으로 **즉시 반영**한다.
  (host/port 등 startup 에 바인딩되는 값은 여전히 재시작 후 적용 — 폼 표시는 즉시.)

### Changed

- 기본 `claude.maxConcurrentTurns` 5 → 3 (번들 default 는 이미 3, 운영 외부 config 도 3으로 정렬).
  과도한 동시 요청에 의한 서버측 throttle(429) 여유 확보.

### Fixed

- **알림 미니창이 상시 열려 다른 UI 를 가리던 버그.** `NotificationBell` 의 CSS
  `.vibe-notif-panel { display: flex }` (author 스타일)이 `hidden` 속성(`display:none`,
  UA 스타일)을 이겨, JS 의 토글/외부클릭 닫기 로직이 정상인데도 패널이 항상 보였다.
  `.vibe-notif-panel[hidden] { display: none }` 을 더 높은 specificity 로 명시해 회수.
  이제 벨 재클릭 / 바깥 클릭(포커스 해제) 시 정상적으로 닫힌다.
- **알림 벨 아이콘이 프로젝트 화면의 ⚙ 설정 버튼과 겹치던 문제.** 우상단 fixed 벨
  (~56px)과 `.pt-header` 우측 끝 컨트롤이 오버레이됐다. `.pt-header` 에 우측 패딩
  (64px)을 줘 ⚙ 버튼을 벨 왼쪽으로 비켜나게 했다.

## [1.89.0] - 2026-06-02 — 알림 수신 설정 + 프로젝트 팝업 UX

### Added

- **알림 종류별 수신 on/off** (`/settings/notifications`). 빌드 완료/오류, 작업 완료/중지/
  오류, 사용량 임계치, 시스템 7종을 체크박스로 켜고 끈다. 끈 kind 는 `NotificationService.emit`
  이 적재 자체를 skip(벨/목록에 쌓이지 않음). 설정은 `<workspace>/.vibecoder/notification-prefs.json`
  에 영속하며 **즉시 반영**(재시작 불요, opt-out 방식이라 새 kind 는 기본 on). 설정 → 알림
  카테고리(이메일/Webhook 옆)에 "앱 내 알림" 링크 추가.

### Changed

- **프로젝트 화면 팝업의 바깥 클릭 자동 닫기.** 콤보박스(프로젝트 전환)·설정(⚙)·더보기(▾)
  드롭다운(`<details>`)이 바깥 영역 클릭 시 자동으로 닫힌다(이전엔 콤보박스만 닫혔고 설정/
  더보기는 열린 채 유지). switcher 없는 페이지에서도 동작하도록 공통 핸들러로 일반화.
- **프로젝트 전환 콤보박스 — 모바일에서 검색창 자동 포커스 제거.** 콤보박스를 열 때 검색
  입력에 자동 포커스하던 동작을 fine pointer(데스크톱 마우스)에서만 적용. 터치 기기(폰)에선
  소프트 키보드가 계속 떠 불편했던 문제 해소. `project-tabs.js?v` bump(1.72.0 → 1.89.0).

## [1.88.0] - 2026-06-02 — 우상단 알림 벨 + 미니창

### Added

- **화면 우상단 알림 벨 UI.** 모든 admin SSR 페이지(chrome 표시 페이지) 우상단에 벨 아이콘을
  고정 배치. unread 가 있으면 빨강 배지로 개수 표시(99+ 상한). 클릭하면 미니창(드롭다운)이
  열리며 **최신 알림을 위부터** 나열하고, 우하단 "모두 삭제" 버튼으로 전체 비움.
  - 표시 종류: 빌드 완료(초록)/오류(빨강), 작업 완료(파랑)/중지(노랑)/오류(빨강), 사용량
    임계치(노랑), 시스템(회색). kind 별 좌측 색 띠 + 제목 이모지로 구분.
  - 항목 클릭 시 `deepLink`(콘솔/빌드 로그/사용량 등)로 이동하며 해당 알림을 읽음 처리.
  - 30초 polling(`GET /api/notifications`) 으로 배지/목록 자동 갱신. 미니창이 열려 있을 때만
    목록을 다시 그림.
  - CSS/JS 는 `AdminTemplates.shell` 에 inline(`NotificationBell`) — 별도 정적 자산
    미생성이라 `?v`/SW `CACHE_VERSION` 영향 없음. embed(iframe inner)·비-chrome 페이지엔
    미주입(중첩 노출 방지).
- **Claude 콘솔 작업(turn) 알림 emit.** 이전엔 `NotificationKind.CLAUDE_TURN_DONE` 가 정의만
  되고 실제 발생하지 않았다. 이제 turn 완료 → "작업 완료", 사용자 취소 → "작업 중지됨",
  프로세스 크래시 → "오류" 알림을 생성. `ServerMain` 에서 자동화 hook 과 **합성**(단일 var
  listener)하며, 자동화 진행 중 turn(연속이라 과다) 과 ghost(scratch/chat) 프로젝트는 제외.

### Wire change

- **Yes — vibe-coder-android `shared/` 동기 필요.**
  - `shared/dto/NotificationDtos.kt` `NotificationKind` 에 `CLAUDE_STOPPED="claude.stopped"`,
    `CLAUDE_ERROR="claude.error"` **추가**(additive const — 기존 클라이언트는 모르는 kind 를
    generic 표시하면 됨, 호환 깨짐 없음).
  - `shared/ApiPath.kt` 에 `NOTIFICATIONS_ACK_ALL="/api/notifications/ack-all"` 추가
    (POST, 모든 unread 일괄 ack). 기존 `NOTIFICATIONS`/`NOTIFICATIONS_ACK` 불변.
  - `NotificationService.ackAll(userId)` + 라우트 추가. ack 는 기존과 동일한 soft(ackedAt set,
    audit 보존) 정책.

### Fixed

- `NotificationService` 의 `deleteWhere` 중복 import 정리.

### Changed

- **빌드 산출물 APK 파일명을 `<packageName>-<variant>-v<versionName>.apk` 로 저장.**
  이전엔 Gradle 기본 이름(`app-debug.apk`)을 그대로 저장해, 여러 프로젝트의 APK 를
  내려받으면 전부 `app-debug.apk` 라 식별이 어려웠다. 이제 예: `com.example.app-debug-v1.2.3.apk`.
  - `versionName` 은 신규 `ApkBadgingReader` 가 Android SDK build-tools 의 `aapt`(없으면
    `aapt2`) `dump badging` 출력에서 추출(best-effort). 도구 부재/타임아웃/파싱 실패 시
    버전 부분을 생략(`com.example.app-debug.apk`)해 graceful fallback.
  - `packageName` 은 프로젝트 메타(DB), `variant` 는 현재 `debug` 고정(release 빌드 경로
    추가 시 `storeDebugApk(variant=...)` 로 확장 가능). 파일시스템 안전 문자만 남기고 치환.
  - 다운로드 시 `Content-Disposition` 의 filename 도 동일하게 반영(`row.fileName` 사용).
  - 기존 빌드 산출물엔 영향 없음(새 빌드부터 적용). `artifacts/metadata.json` 의 `fileName`/
    `variant` 도 새 값으로 기록.
  - 빌드환경에 build-tools 미설치 시 버전 없는 이름으로 동작 — 빌드 자체는 영향 없음.

## [1.86.5] - 2026-06-02 — 정밀점검: 마크다운 링크 href XSS 회수

### Security

- **콘솔 마크다운 링크 href 속성 주입 XSS 회수** (`static/admin/console-render.js`
  `renderMarkdown`). `mdEsc` 는 `& < >` 만 escape 하고 `"` 는 그대로 두는데, 링크
  URL 캡처 패턴이 `[^\s)]+` 로 `"` 를 허용해, `[x](https://a"onmouseover="alert(1))`
  형태의 마크다운이 `href="…"` 속성을 탈출해 이벤트 핸들러를 주입할 수 있었다. assistant
  메시지(=Claude 응답)는 `renderMarkdown` → `innerHTML` 경로로 렌더되고, Claude 출력은
  웹검색 결과 / clone 한 repo README / 파일 내용 등 **외부 콘텐츠**를 포함할 수 있어
  stored XSS 경로가 된다(외부 노출 `vibe.wody.work` 환경에서 실재 위험). URL 캡처에서
  `" ' < >` 를 제외(`[^\s)"'<>]+`)해 차단. 정상 URL 의 이 문자는 `%22` 등으로 인코딩되므로
  raw 등장은 공격 시그널.
  - tool/sys/err/user 메시지 경로는 `escHtml(body)` → innerHTML 이라 영향 없음(이미 안전).
  - SW 캐시 정책에 따라 `console-render.js?v=` (WebProjectTemplates / SubAgentRoutes) +
    `sw.js` `CACHE_VERSION` 동반 bump(v1.86.0 → v1.86.5).

### Fixed

- **`ProjectService.delete` 의 `NotificationEvents` 고아 row 정리.** `projectId` 가 FK
  없는 nullable 이라 FK violation 은 없었지만, 미정리 시 삭제된 프로젝트의 알림이 알림함에
  깨진 deepLink 로 잔존하고 같은 projectId 재사용 시 옛 알림이 섞였다. `renameId` 는 이미
  `NotificationEvents` 를 repoint 하므로 **delete↔renameId 대칭**을 복원(`projectId == id`
  행만 삭제, 글로벌 알림 `projectId NULL` 은 보존). 같은 transaction 안에서 처리해 원자성 유지.

### 정밀점검 결과 (회수 불요 — 기록)

- 1차(보안·동시성 경계) + 2차(미점검 영역) 정밀점검 — PathSafety(lexical + `assertRealInside`
  symlink) / 인증(BCrypt timing 방어 · 계정·IP brute-force · TOTP · passwordless) /
  CSRF(HMAC deterministic · constant-time · SSR=requireCsrf · `/api/`=Bearer+SameSite=Lax) /
  ProcessRunner·GradleBuilder·AdbService(stdout·stderr 병렬 소비 또는 DISCARD, strict regex,
  셸 미경유) / git URL 검증(case-insensitive scheme 화이트리스트) / 파일 IO
  (normalizeAndCheck+assertRealInside 쌍) / FK cascade(delete↔renameId 7개 FK 테이블 일치) /
  claude·automation 동시성(spawnLock·stdinMutex·gate·per-project Mutex) / WS Origin
  (fail-closed) / Web Push RFC 8291(HKDF·ECDH·GCM·RFC 8188 body) / McpService(카탈로그 기반·
  셸 미경유) / repo raw SQL(registerArgument 파라미터 바인딩) / SSR XSS(onclick escJs ·
  highlight esc-first) 전부 견고함 재확인.

## [1.86.4] - 2026-06-01 — 마크다운 테이블 렌더링

### Added

- **마크다운 테이블 렌더링.** `| 헤더 | … |` + `|---|---|` 구분행 + 본문 행을 `<table>`
  (`thead`/`tbody`)로 변환. escape 우선이라 XSS 안전. 가로 스크롤(`overflow-x:auto`) +
  짝수 행 stripe + 헤더 강조. `<br>` 정리 대상에 table/thead/tbody/tr/th/td 추가.

## [1.86.3] - 2026-06-01 — 구 SW 강제 회수 (마크다운/접기 미동작 확정 수정)

### Fixed

- **구 Service Worker 가 console-render.js 를 구버전/null 로 박제해 마크다운/접기가 끝까지
  안 되던 문제 강제 회수.** 컴파일 클래스 검증 결과 서버 코드(append 마크다운·clamp·
  renderMarkdown)는 정상 배포 확인 → 원인은 100% 클라이언트 구 SW. v1.86.1 의 controllerchange
  reload 로도 안 풀리는 끈질긴 케이스(구 HTML/구 SW 동시 잔존)를 위해, 콘솔 페이지가
  로드 직후 **`window.VibeConsole.renderMarkdown` 부재를 감지하면 SW·캐시를 전부 제거하고
  1회 reload**(sessionStorage 가드)한다. SW 없는 상태로 최신 자산을 직접 받고, reload 후
  register 가 새 SW(?v 우회)를 재설치. console-render.js?v / admin.css?v → 1.86.3.

## [1.86.2] - 2026-06-01 — 응답 버블 좌측 정렬 통일

### Changed

- **모든 응답 메시지 버블을 좌측 정렬.** 시스템/완료/replay pill 만 중앙(`justify-content:center`)
  이던 것을 좌측(`flex-start`)으로 통일(assistant/tool/thinking 은 이미 좌측). 내 프롬프트는
  과거 sticky 터미널 스타일 유지(v1.86.1).

## [1.86.1] - 2026-06-01 — user 프롬프트 sticky 복원 + 구 SW 자동 회수

### Fixed

- **내 프롬프트 버블화로 상단 고정(sticky)이 깨지던 문제.** v1.86.0 에서 user 메시지에
  `justify-content:flex-end`(우측 버블)를 줬는데, user 카드는 원래 `position:sticky; top:0`
  으로 스크롤 상단 고정(v1.70.1)이라 flex 정렬과 충돌해 UI 가 깨졌다. user 프롬프트는 버블
  대신 **과거 sticky 상단고정 + 터미널('> ') 스타일**로 복원(assistant/tool/sys/thinking 은
  버블 유지).
- **구 Service Worker 자동 회수.** v1.86.0 의 SW ?v 우회 수정이 적용되려면 새 SW 가 활성화돼야
  하는데, 구 SW(v0.50.0)가 살아있으면 마크다운/접기가 계속 구 자산(null 혼입본)으로 안 보였다.
  SW 등록 시 `reg.update()` + 새 SW 가 control 을 잡으면(기존 SW 가 있던 경우만) **1회 자동
  reload** 하도록 추가 → 사용자가 페이지를 열면 자동으로 최신 자산 반영(수동 Unregister 불요).

## [1.86.0] - 2026-06-01 — 채팅 버블 UI + Service Worker 캐시 버그 + thinking 뱃지

### Fixed

- **마크다운이 여전히 raw 로 보이던 진짜 원인 — Service Worker 캐시.** sw.js 가 `/static/`
  을 **cache-first** 로 잡고 `CACHE_VERSION` 이 `v0.50.0` 에 머물러 있어, ?v 캐시버스트로
  새 `console-render.js` 를 배포해도 SW 가 구버전(v1.85.0 null 혼입본)을 박제 서빙했다.
  서버는 ?v 무관 최신 파일을 주지만 SW 가 앞에서 가로챈 것. **버전 쿼리(?v=)가 붙은 자산은
  SW 캐시를 우회(network passthrough)** 하도록 수정 + `CACHE_VERSION` → v1.86.0(구 캐시 무효).

### Added

- **콘솔 채팅 버블 UI.** 터미널 스타일(`>`/`⏺` prefix)에서 **말풍선 레이아웃**으로 전환:
  내 프롬프트=우측 강조 버블, claude 응답=좌측 중립 버블(마크다운), 도구 호출/결과=좌측
  인셋 버블, 시스템/완료=중앙 pill, thinking=좌측 흐린 작은 뱃지.

### Changed

- **thinking / 파싱 불가 메시지 = 이름 뱃지.** 빈 thinking(signature-only)을 숨기지 않고
  "💭 Thinking…" 뱃지로 표시(사용자 요청 — 흔적 보존). JSON 이 절단·깨져 파싱 불가한 경우도
  raw 노출 대신 `"type"` 정규식 추출로 타입 이름 뱃지("💭 Thinking…" / "· event ·")를 표시.
  inline replay 에선 빈 thinking 의 긴 signature 를 버려 경량 마커로 단축(절단 깨짐 원천 차단).

## [1.85.1] - 2026-06-01 — console-render.js null 바이트 긴급 수정

### Fixed

- **v1.85.0 마크다운/접기/renderUnknown 전체 무력화 버그.** `renderMarkdown` 의 코드블록/
  inline code placeholder 구분자에 **null 바이트(` `) 8개**가 혼입돼 있었다. null 이
  포함된 `console-render.js` 는 브라우저가 정상 실행하지 못해 `window.VibeConsole` 객체가
  통째로 로드되지 않았고, 그 결과 마크다운 렌더·긴 메시지 접기뿐 아니라 기존 renderToolUse/
  renderUnknown 까지 전부 죽어 콘솔에 raw 텍스트(`##`, JSON)가 그대로 노출됐다. null 제거 +
  `console-render.js?v` → 1.85.1 캐시버스트(이전 null 버전을 받은 브라우저 강제 갱신).

## [1.85.0] - 2026-06-01 — 콘솔 assistant 마크다운 렌더 + 긴 메시지 접기

### Added

- **assistant 응답 마크다운 렌더링.** 이전엔 콘솔에 마크다운 raw(`## 제목`, `**굵게**`,
  ```` ``` ```` 코드블록)가 그대로 보였다. `console-render.js` 에 경량 마크다운 렌더러
  (`renderMarkdown`)를 추가 — 헤딩/볼드/이탤릭/취소선/inline code/코드블록/순서·비순서
  리스트/인용/링크/수평선 지원. **escape 를 먼저 적용**하므로 응답 안의 raw HTML 은 항상
  무력화 → XSS 안전(외부 CDN/라이브러리 미사용, §3 준수). 코드블록은 highlight.js 가
  로드돼 있으면 syntax highlight.
- **긴 assistant 메시지 접기.** 360px 초과 메시지는 접어서 표시하고(하단 그라데이션 +
  "⌄ 더보기"), 카드를 탭하면 전문 펼침("⌃ 접기"). 마크다운 링크 클릭/텍스트 드래그
  선택 시엔 토글하지 않는다(기존 user 프롬프트 카드 클램프 패턴 확장).

### Fixed

- **서브에이전트 위임 prompt 노이즈 숨김.** `tool_result` 없는 user 메시지(Task 서브에이전트
  내부 prompt / 이미지 좌표 메타, role='unknown' 11행)가 메인 콘솔에 "user {…}" 요약으로
  떴다. 해당 작업은 이미 Task tool_use 카드로 노출되므로 `renderUnknown` 에서 숨김 처리.
  - **노이즈 전수 검토 결과**: 운영 `conversation_turns` 의 `role='unknown'` 8종을 모두 분류
    완료. 중요 기능 내포 메시지(task_* 백그라운드·서브에이전트 / thinking 사고 / rate_limit)는
    v1.83.0~v1.84.0 에서 처리됨. 새로 놓친 중요 메시지는 없음.
- **빈 thinking 블록이 콘솔에 raw JSON 으로 노출되던 버그.** 과거 DB 에 적재된 빈 thinking
  (`{"type":"thinking","thinking":"","signature":"…3700자…"}`)이 새로고침 시 `renderInitialHistoryJson`
  의 4000자 절단으로 **JSON 이 깨져** replay 의 `renderUnknown(JSON.parse)` 가 실패 → raw
  텍스트가 그대로 보였다. inline history 생성 시 노이즈 unknown(빈 thinking / thinking_tokens /
  task_* / user prompt)을 `isNoiseUnknownContent` 로 제외(live 경로는 v1.84.0 파서가 이미 드롭).

### Changed

- **컨텍스트 auto-compaction 알림 노출.** claude 가 컨텍스트 한도 근처에서 이전 대화를 요약
  압축할 때 보내는 `compact_boundary` system 메시지를 "🗜 컨텍스트가 자동으로 압축되었습니다"
  시스템 메시지로 표시(이전엔 "system·compact_boundary" 요약). compaction 은 claude 가 자동
  수행하며 **session-id 가 유지**되므로 `--resume`/미완 마크/재시작 재개가 모두 호환된다
  (수동 `/compact` 는 stream-json input 모드 제약으로 미지원 — Claude Code 자체 한계).

## [1.84.0] - 2026-06-01 — 콘솔 백그라운드 작업 진행 카드

### Added

- **콘솔에 백그라운드 작업(Bash `run_in_background` 등) 진행 카드 표시.** Claude Code TUI
  하단 Shell 카드와 동형 — claude 가 백그라운드 작업을 띄우고 turn 을 끝내도 진행 상황을
  알 수 있다. CLI 가 stream-json 으로 보내는 system 메시지(`task_started` / `task_updated` /
  `task_notification`)를 파싱해 콘솔 입력창 위 패널(`#bg-tasks`)에 카드로 그린다:
  - `task_started` → "● 실행 중"(초록 pulse) 카드 + description + task_type/id.
  - `task_progress`(주로 Task 서브에이전트) → description 실시간 갱신 + last_tool · N tools
    진행 메타(예: "Reading Theme.kt · Read · 14 tools"). started 없이 와도 카드 생성.
  - `task_updated`(patch.status=completed/failed 등) → "✓ 완료" / "✗ 실패" 후 6초 뒤 제거.
  - 여러 작업 동시 진행 시 목록으로 누적. 작업이 없으면 패널 자동 숨김.
  - 이전엔 이 system 메시지들이 `Unknown`(노이즈)으로 흘러 숨겨졌다. DB 영구 적재는 안 함
    (휘발성 — live + LogHub replay 로 단기 복원).

### Fixed

- **노이즈 메시지 DB 적재 정리.** 운영 `conversation_turns` 의 `role='unknown'` 8858행을
  enumerate 한 결과 대부분이 노이즈였다:
  - **빈 thinking 블록**(`{"type":"thinking","thinking":"","signature":...}` — signature-only
    redacted, 1650행) → 파서가 드롭. 클라이언트(`renderUnknown`)는 이미 숨겼지만 서버 history
    엔 Unknown 으로 쌓였다. 내용이 있는 thinking 만 통과(💭 collapsible 렌더 유지).
  - `task_progress`(177행)는 위 백그라운드 카드로 흡수. (thinking_tokens 5951 / rate_limit_event
    201 / task_started 등은 v1.70.0 / v1.83.0 / v1.84.0 에서 이미 처리된 과거 잔재.)

### Wire change

- **Yes** — `WsFrame.ConsoleBackgroundTask`(`console_background_task`) 신규 프레임
  (kind/taskId/description/taskType/status). `ClaudeEvent.BackgroundTask` 서버 모델 +
  파서가 system task_* subtype 을 라우팅. vibe-coder-android `shared/` 동기 권고
  (콘솔 백그라운드 작업 카드 — Android 콘솔에서도 동일 표현 가능).

## [1.83.0] - 2026-06-01 — 콘솔 "대기중" 고착 회수 + rate-limit 가시화

### Fixed

- **백그라운드 작업 후 turn 자발적 재개 시 뱃지가 "대기중"에 고착되던 현상 회수(핵심).**
  claude 가 background task(빌드 등)를 시작하고 "완료를 기다리겠다"며 turn 을 종료하면
  busy=false → "대기중"이 되는데, 작업이 끝나 **claude CLI 가 host stdin 없이 자발적으로
  turn 을 재개**(assistant/tool 프레임 재개)하면 `sendPrompt` 를 거치지 않아 `setBusy(true)`
  가 안 불려 **작업은 진행되는데 뱃지는 "대기중"** 으로 남았다. `handleStdoutLine` 이 활동
  프레임(assistant/tool_use/tool_result)을 받는데 busy 가 아니면 `setBusy(true)` + 미완 마크로
  동기화하도록 수정 → 자발적 재개도 "응답중"으로 정확 표시(+ 재시작 자동재개 v1.82.0 대상 포함).
- **`rate_limit_event` 가 콘솔에 JSON 노이즈(Unknown)로만 보이던 문제.** claude 2.x 가
  서버측 rate limit 시 내보내는 `rate_limit_event` 를 파서가 모델링하지 않아, 사용자가
  "thinking 후 멈춤"으로 오해했다. `ClaudeEvent.SystemNote` 로 모델링해 **"rate limit —
  자동 재시도 중"** 시스템 메시지로 노출(/history 에도 system role 적재).
- **rate-limit 재시도 소진/취소/크래시 시 콘솔 뱃지가 "중단됨"으로 표시되지 않던 문제.**
  `setBusy(state="stopped")` 가 대시보드(`ProjectBusyChanged.state`)로만 전달되고 콘솔이
  구독하는 `ConsoleBusyState` 에는 busy boolean 만 있어 "중단됨"을 구분 못 했다.
  `ConsoleBusyState.state` 필드 추가로 콘솔에서도 빨강 "■ 중단됨" 뱃지 표시.

### Changed

- **rate-limit 자동 재시도 횟수 3 → 5회**(`MAX_RATE_LIMIT_RETRIES`). 백오프(30/60/120…) 후
  5회까지 "이어서 진행" 자동 재개, 모두 실패하면 "중단됨".

### Wire change

- **Yes** — `WsFrame.ConsoleBusyState` 에 `state: String? = null` 추가
  ("responding" | "ready" | "stopped"). null 기본값이라 구버전 클라이언트 호환(무시 가능).
  vibe-coder-android `shared/` 동기 권고(콘솔 busy 뱃지 3-state 정확성). `ClaudeEvent.SystemNote`
  는 서버 내부 모델 — wire 는 기존 `console_system` 프레임 재사용(신규 프레임 없음).

## [1.82.0] - 2026-06-01 — 서버 재시작 시 미완 turn 자동 재개

### Added

- **콘솔 작업 중 서버 재시작/크래시로 끊긴 미완 turn 을 부팅 시 자동 재개.** busy 상태는
  메모리라 재시작 시 사라져 "대기중" 으로 돌아가던 현상 → **영속 `turn-active` 마크**
  (`.vibecoder/<projectId>/turn-active`)로 미완 여부를 남긴다.
  - 사용자 prompt 전송 시 마크 ON, **정상 완료(Done) / 사용자 취소 / 새 세션 / 비-rate
    에러** 시 OFF. 비정상 종료(재시작·크래시)면 마크가 남는다.
  - 부팅 시 `reconcileInterruptedTurnsAsync()` 가 마크 남은 프로젝트마다 "Continue from
    where you left off" 프롬프트를 같은 `--resume` 세션에 자동 전송(멈춘 곳부터 재개,
    2초 간격 순차로 spawn 부하 분산).
  - **무한 재개 방지**: 부팅 자동 재개 최대 2회(재시작이 반복돼도). 초과 시 마크 제거 +
    "직접 이어가세요" 안내. 콘솔에 `turn_auto_resume` / `turn_resume_giveup` 시스템 메시지.
  - rate-limit 자동 재시도(v1.80.0)와 일관: 사용자 개입 시 취소, 정상 완료 시 카운터 리셋.

## [1.81.0] - 2026-06-01 — 프로젝트 설정 동선 회수

### Fixed

- **프로젝트 이름/패키지명/폴더명 변경 폼으로 가는 동선 누락 회수.** rename 폼은
  `/projects/{id}/overview`(`projectDetailPage`)에 정상 구현돼 있었으나, 통합 탭
  (`/projects/{id}`) 헤더의 ⚙ 설정 드롭다운에는 패키지명 등이 **읽기 전용 표시**만 있고
  그 폼 페이지로 가는 링크가 없어 사용자가 수정 UI에 도달할 수 없었다. ⚙ 드롭다운에
  "이름·패키지·폴더 변경…"(`/overview`) 링크 추가. (패키지명·폴더명 input 은 기존대로
  대기중—turn·빌드 미진행—일 때만 활성.)

## [1.80.0] - 2026-06-01 — 콘솔 안정성: 백그라운드 가이드 + rate-limit 자동 재개

### Fixed

- **rate-limit error turn 후 콘솔이 "응답 중"에 멈추던 버그.** `handleStdoutLine` 이
  `ClaudeEvent.Done` 에서만 `busy=false` 했고 `ClaudeEvent.ErrorEvent`(result `is_error=true`,
  rate-limit 포함)는 turn 종료 처리를 안 해 permit·busy 가 안 풀렸다. ErrorEvent 도 turn
  종료로 처리(permit 반환 + busy 해제 + 자동화 통지).
- **백그라운드 작업 후 자동 재개 안 되던 현상 완화.** stream-json input 모드에선 Claude 가
  `run_in_background` 작업을 띄우고 turn 을 끝내면 완료돼도 자동 재개되지 않아(호스트가 입력
  제어) 매번 수동 메시지가 필요했다. `--append-system-prompt` 로 "turn 을 끝내지 말고 같은
  turn 안에서 동기 실행/폴링으로 완료까지 기다리라" 가이드 주입(ghost/chat 제외). 구조적
  제약의 실효적 완화.

### Added

- **서버측 rate limit 자동 재개.** turn 이 "Server is temporarily limiting requests"(사용량
  한도 아님) 로 종료되면, 지수 백오프(30 → 60 → 120초)로 **최대 3회** "Continue from where
  you left off" 프롬프트를 같은 `--resume` 세션에 자동 전송 → **멈춘 곳부터 재개**(원래
  프롬프트 재전송 아님, 중복 작업 없음). 3회 후에도 지속되면 자동 재개를 중지하고 상태를
  **"중지됨"** 으로 표시. 재개 대기 중 사용자가 새 prompt/cancel 하면 즉시 취소. 정상 완료 시
  카운터 리셋.

## [1.79.0] - 2026-06-01 — 터미널 403 회수 + 게이지 2색 + 사이드바 정리

### Fixed

- **워크스페이스 터미널 HTTP 403 회수.** v1.45.0 단일 admin 화로 `AuthPlugin` 이
  `DevicePrincipal.userRole` 을 더 이상 채우지 않는데(`userRole=null`, 대신 `isAdmin`
  항상 true), 터미널의 REST(`requireApiAdminOrFail`)·WS(`authenticateTerminalWs`)가
  폐기된 `userRole=="admin"` / role 컬럼 조회를 strict 체크해 **항상 403** 이었다.
  단일 admin 모델(인증된 device = admin)로 정정 — `isAdmin`/인증된 device 기준.

### Changed

- **서버 상태 도넛 게이지 2색화.** 한 도넛에 **파랑=서버 전체 사용량 / 초록=vibe-coder
  점유분**(프로세스, 전체의 부분집합이라 위에 겹쳐 표시)을 동시에 표현. 게이지를 CPU·메모리
  2개로 정리(기존 프로세스 게이지는 초록 호로 흡수). 범례 추가. 임계 색상(주황/빨강) 대신
  의미 기반 고정 색. `admin.css?v=1.79.0`, i18n `dashboard.sys.legendTotal/legendVibe`.
- **사이드바 유저명 표시 제거**(운영자 요청). 로그아웃 버튼 위 username `<div class="user">`
  미렌더 — 단일 admin 이라 정보가치 낮음.

## [1.78.0] - 2026-06-01 — 서버 상태 원형 게이지 시각화

### Changed

- **홈 대시보드 "서버 상태" 카드를 원형 게이지(도넛)로 시각화** — CPU·메모리·프로세스 CPU
  를 3개 도넛 게이지로 한눈에 표시(중앙 % + 75%/90% 임계 색상: accent→주황→빨강).
  inline SVG(`r=15.915` → 둘레≈100, `stroke-dasharray="pct 100"`)로 외부 라이브러리·CDN
  없이 구현. 게이지 아래에 메모리(used/total)·프로세스(RSS+heap)·load·uptime 상세 텍스트.
  `/api/server/stats` 5초 폴링 시 `stroke-dasharray` 트랜지션으로 부드럽게 갱신.
- `admin.css` `.gauge*` 추가, 캐시버스트 `?v=1.78.0`. i18n `dashboard.sys.procGauge`.

## [1.77.0] - 2026-06-01 — status 엔드포인트 인증 게이트 일괄

### Security

- **사이드바/대시보드 status 폴링 엔드포인트를 인증 게이트로 일괄 통일.**
  `/api/server/stats`(v1.74.0)·`/api/adb/status`(v1.40.0)·`/api/emulator/status`(v1.73.0)를
  `authenticate(AUTH_BEARER)` + `requireDevice()` 로 전환 — `/api/server/quota`(v1.52.0에서
  이미 인증)와 동일 패턴. 이전엔 **미인증**이라 외부 노출 환경에서 인프라 정보(호스트
  RAM/CPU/코어/프로세스 RSS/heap/uptime), adb 연결 기기 수, 에뮬레이터 설치/실행 상태가
  익명에게 노출됐다.
  - 사이드바 pill·대시보드 카드는 `fetch(credentials:'same-origin')` 로 `vibe_session`
    쿠키를 전송하므로 로그인 사용자는 그대로 통과. 익명은 401 → JS 가 `r.ok` 체크로 pill
    을 숨김(graceful, 회귀 없음).
  - v1.74.0 `SystemStatsRoutes`·AdbRoutes 의 잘못된 주석("quota 와 동일 무인증" — quota 는
    v1.52.0부터 인증됨) 정정.

## [1.76.0] - 2026-06-01 — 뒤로가기 아이콘 버튼 통일

### Changed

- **프로젝트/설정 탭의 모든 "뒤로가기/복귀" 링크를 일관된 아이콘 버튼으로 통일**
  (운영자 선호). `AdminTemplates.backButton(href, label, topTarget)` 공통 헬퍼 —
  arrow-left(Lucide) SVG 아이콘 + 라벨, `.back-btn` 스타일. 이전엔 `chip chip-link` +
  "← " 텍스트 / `primary-link` / 인라인 style 등 페이지마다 제각각이었다.
  - **프로젝트**: 빌드상세→프로젝트(`builds.back`)·빌드상세→빌드목록
    (`build.detail.backToBuilds`), 파일뷰어→프로젝트(`files.back`)·Git→프로젝트
    (`git.back`), 심볼→파일(`SymbolRoutes`), 자동화 프롬프트→자동화
    (`PromptAutomationTemplates`), sub-agent 콘솔→메인콘솔/목록(`SubAgentRoutes`),
    히스토리→콘솔/Chat(`HistoryRoutes`), 탭 헤더→프로젝트 목록(`tabs.backToList`).
  - **설정**: editPage(skills/plugins/agent-defs)→목록(`ScopedManagerTemplates`),
    전역 agents editPage(`AgentRoutes`), MCP→빌드환경(`mcp.backToEnv`),
    git-integrations→설정(`gitint.backToSettings`), CORS→설정(`cors.backToSettings`),
    env-setup login/task/error→빌드환경(`env.*.backToEnv`/`done.btn`).
  - i18n: 위 라벨들의 "← " 텍스트 prefix 제거(아이콘이 화살표 제공).
  - pagination(← Prev / Next →)·breadcrumb(상위 디렉토리)은 의미가 달라 그대로 유지.
- `admin.css` `.back-btn` 추가, 캐시버스트 `?v=1.76.0`.

## [1.75.0] - 2026-06-01 — 탭 sub-page 복귀 네비 + 프로젝트 전환 콘솔 우선

### Fixed

- **설정 통합 탭 iframe 의 sub-page 갇힘 회수.** v1.72.0(iframe inner page 의 nav 미렌더)
  이후, 보안/알림/모니터링 카테고리의 sub-page 에서 같은 카테고리 다른 페이지로 이동하는
  네비게이션이 없어 사용자가 갇혔다(부모 탭 재클릭해도 iframe src 가 리셋되지 않음).
  - `SettingsNav.categoryNav()` 호출을 누락 페이지에 추가(`/password` 패턴):
    보안 `/2fa`·`/webauthn`·`/devices`·`/settings/cors`, 알림 `/settings/push`,
    모니터링 `/audit`. (`/password`·`/usage`·`/settings/email`·`/settings/webhook` 은 기존 보유.)
  - `ScopedManagerTemplates`(skills/plugins/agent-defs) · `AgentRoutes` 의 editPage 상단에
    "← 목록" 복귀 칩 추가(iframe 안 편집 화면 갇힘 방지).
  - 참고: 프로젝트 탭의 `/tree`·`/git`·`/builds/{id}` 등은 이미 `target="_top"` back 링크 +
    부모 탭바가 있어 정상(전수검사에서 재확인).

### Changed

- **프로젝트 간 전환 시 콘솔 우선.** 헤더 프로젝트 switcher 링크에 `#console` 추가 —
  이전에 그 프로젝트에서 다른 탭(빌드/파일 등)을 보고 있었더라도, 다른 프로젝트로
  이동하면 콘솔 탭을 먼저 보여준다(hash 가 localStorage 복원보다 우선).

## [1.74.0] - 2026-06-01 — 홈 대시보드 서버 상태 카드

### Added

- **홈 대시보드(`/`)에 "서버 상태" 카드** — CPU / 메모리 / 프로세스(vibe-coder 컨테이너)
  점유 + load average + uptime 을 5초 폴링으로 실시간 표시.
  - `SystemStatsService` — JDK 17 cgroup-aware `com.sun.management.OperatingSystemMXBean`
    (시스템 CPU `getCpuLoad`, 프로세스 CPU `getProcessCpuLoad`, 메모리), cgroup v2
    `memory.current`/`memory.max`(컨테이너 한도), `/proc/self/status` VmRSS(프로세스 RSS),
    JVM 힙(`MemoryMXBean`). 75%/90% 임계 색상.
  - `GET /api/server/stats` — `{cpuPercent, processCpuPercent, ramUsedMb/TotalMb/Percent,
    processRssMb, heapUsedMb/MaxMb, loadAvg, cores, uptimeSec}`. /proc·cgroup 읽기는
    Dispatchers.IO 격리. 저민감 → `/api/server/quota` 와 동일 무인증.

### Wire change

- **Yes (경미)** — `ApiPath.SERVER_STATS` 신규. admin 대시보드 server-internal,
  vibe-coder-android 실사용 없음.

## [1.73.0] - 2026-06-01 — 안드로이드 에뮬레이터(헤드리스, Claude Code 로그분석용)

### Added

- **빌드환경에 "Android Emulator" 설치 항목 + 헤드리스 에뮬레이터 lifecycle.**
  화면 미러링(noVNC) 없이 **Claude Code 가 콘솔에서 `adb install` / `adb logcat` 으로
  빌드 결과를 즉시 검증·로그분석**하기 위한 인프라. 서버는 에뮬레이터를 띄워 adb 로
  잡히게 + 실행 상태 표시까지만 책임지고, 설치/로그수집은 Claude 가 직접 한다.
  - **빌드환경 컴포넌트** (`SetupComponent.ANDROID_EMULATOR`): `/env-setup` 카드에
    자동 노출. 설치는 SDK 와 동일한 `vibe-doctor android` 흐름 — manifest.yml 에
    `emulator` + `system-images;android-35;google_apis;x86_64` 추가(google_apis = adb root,
    logcat 유리). probe 는 emulator 바이너리 + system-image 존재로 판정.
  - **`/emulator` 페이지 + 사이드바 pill**: 무선디버깅 pill 바로 아래에 에뮬레이터
    실행 상태(중지/부팅중/실행중) pill. `/emulator` 에서 수동 시작/중지 + AVD 정보 +
    Claude adb 사용 안내. (실행은 수동 — 자동 시작/idle reaper 없음.)
  - **`EmulatorService`**: AVD(`vibe_pixel_api35`, pixel_6) 멱등 생성(avdmanager) +
    헤드리스 시작(`emulator -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
    -accel on`) + 부팅 상태(getprop sys.boot_completed) + graceful 종료(adb emu kill →
    SIGTERM → SIGKILL, ClaudeSessionManager 패턴). 단일 인스턴스(`emulator-5554`).
  - 로그분석 흐름: 부팅 후 프로젝트 콘솔에서 Claude 가
    `adb -s emulator-5554 install -r <apk>` / `adb -s emulator-5554 logcat` 직접 실행.

### Changed (운영 정책 — 재구성 필요)

- **Docker 이미지**: 헤드리스 에뮬레이터 런타임 라이브러리 apt 추가
  (`libgl1-mesa-glx libpulse0 libnss3 libxcursor1 libxrender1 libxrandr2 libuuid1 libexpat1`).
  system-image 자체는 여전히 볼륨(doctor).
- **compose.yml**: `vibe-coder-server` 에 `devices: [/dev/kvm:/dev/kvm]` 추가(KVM 가속).
  `entrypoint.sh` 가 `/dev/kvm` 의 실제 GID 로 kvm 그룹을 동적 생성해 `vibe` 를 추가
  (호스트별 GID 차이 대응, group_add 하드코딩 회피). KVM 없으면 에뮬레이터 미가속 →
  매우 느려 부팅 실패 가능(페이지가 안내).
- **운영 갱신 절차**: `docker-compose.yml` 에 `/dev/kvm` devices 수동 sync 후
  `docker compose up -d --force-recreate`. 에뮬레이터 미사용 시 devices 생략 가능.

### Wire change

- **Yes (경미)** — `ApiPath.EMULATOR_STATUS` (`/api/emulator/status`) 신규. admin 전용
  server-internal 이라 vibe-coder-android 실사용 없음. shared/ 동기 권고(메모 수준).

## [1.72.0] - 2026-06-01 — 통합 탭 iframe "페이지 in 페이지" 근본 회수

### Fixed

- **설정/프로젝트/도구 통합 탭의 iframe inner page 에서 사이드바·탭바·전체
  레이아웃이 통째로 이중 노출되던 "페이지 in 페이지" 현상 회수.** (제보: 설정
  화면이 iframe 안에 전체 shell 째로 중첩, 다른 페이지 이동 시 사이드바가
  떴다 사라지거나 안 사라짐.)
  - **원인**: inner page 가 전체 admin shell(nav 포함)을 그대로 iframe 에
    로드한 뒤, `project-tabs.js` 가 **load 후 JS 로** 내부 `nav.sidebar` /
    `.settings-tabs` 를 숨기던(cleanup) 방식. iframe load 이벤트와 스크립트
    타이밍이 어긋나면 숨김이 늦거나 누락되어 전체 레이아웃이 노출됐다.
    `visibility:hidden` 초기화 잔재는 cleanup 미실행 race 시 iframe 이 영구
    invisible 이 되는 별도 위험도 있었다.
  - **해법**: **서버가 iframe 요청에 nav/탭바 크롬을 처음부터 미렌더**한다
    (JS 로 숨기는 방식 전면 폐기). `AdminTemplates.shell` 에 `embed` 파라미터
    추가 — embed=true 면 사이드바 nav + 설정 탭바를 HTML 에 넣지 않고 layout 을
    `no-nav` 로. `ApplicationCall.isEmbeddedRequest()` 가 브라우저 표준
    `Sec-Fetch-Dest: iframe` 헤더(iframe 내부 sub-navigation 도 자동 커버) +
    탭 iframe src 의 `?_embed=1` 폴백으로 자동 판정. 직접 접근(북마크)은
    `document` 라 크롬이 정상 노출되어 회귀 없음.
  - 모든 iframe-target SSR 페이지 + iframe 내부에서 링크로 도달하는 2차 페이지
    (보안: 2fa/webauthn/devices/cors, 도구: keystore/ssh/git/logs/code-search/
    multi-console/adb, env-setup: claude-login/task-progress 등)에 embed 전파.
  - `/password` **POST 실패**(비밀번호 확인 불일치/변경 실패) 시 페이지 재렌더
    에도 embed 적용 — iframe 안 비밀번호 변경 실패 케이스의 이중 노출까지 회수.
  - `project-tabs.js` 의 cleanup / `visibility:hidden` 제거(rail 패딩 보정만
    유지), `?v=1.72.0` 캐시버스트.

### Internal

- `EmbedShellTest` 추가 — `shell(embed=true)` 가 nav/탭바를 출력 문자열에
  넣지 않음을 단위 검증(직접 접근 시 노출은 보존).
- **Wire change 없음** (`shared/` 무변경, `?_embed=1` 은 내부 쿼리) —
  vibe-coder-android 동기 불필요.

## [1.71.0] - 2026-06-01 — 프로젝트 설정: 이름/패키지/폴더명 변경

### Added

- **프로젝트 설정에서 표시명 · 패키지명 · 폴더명(프로젝트 ID) 변경** —
  `/projects/{id}/overview` 에 "프로젝트 설정" 카드 추가.
  - **표시명(name)**: 언제든 변경. `POST /projects/{id}/rename-name`.
  - **패키지명(applicationId)**: **대기중(turn·빌드 미진행)** 일 때만.
    `POST /projects/{id}/rename-package` — 서버가 ① DB `package_name` 갱신
    ② `/home/vibe/keystores` 의 키스토어 파일 4종(`<pkg>.keystore` 등)을 새
    패키지로 rename(`KeystoreService.renamePackage`) ③ **콘솔에 리네임 작업
    프롬프트를 전송**해 Claude 가 package 선언/`src/main/java` 디렉토리 구조/
    AndroidManifest/`namespace`·`applicationId`/signing 참조를 코드 레벨에서 갱신.
  - **폴더명(projectId=PK)**: **대기중** 일 때만. `POST /projects/{id}/rename-folder` —
    파일시스템 이동(`<root>/<id>`, `.vibecoder/<id>`, keystores 디렉토리) +
    **DB PK 마이그레이션**(`ProjectRepository.renameId`: 새 PK 삽입 → 8개 자식
    테이블 `project_id` repoint → 옛 PK 삭제, 단일 트랜잭션). live 콘솔 세션은
    이동 전 종료. DB 실패 시 FS 롤백.
  - 파일: `ProjectService`(rename/updatePackageName/renameFolder),
    `ProjectRepository`(updatePackageName/renameId), `KeystoreService.renamePackage`,
    `WebProjectRoutes`(3 routes + 프롬프트), `WebProjectTemplates`(설정 카드), i18n.
  - 기존 프로젝트 영향 없음(새 기능). scratch/chat ghost 프로젝트는 변경 거부.

## [1.70.7] - 2026-05-31 — 버전 표시 박제 회수 (외부 config shadow)

### Fixed

- **이미지 업그레이드 후에도 대시보드/health 가 옛 버전을 표시하던 문제** —
  사용자가 `/settings` 에서 설정을 저장하면 `ConfigPersistence` 가 외부 config
  (`./config/server.yml`)에 **`version` 까지 포함한 전체 설정**을 기록한다. 이후
  ConfigLoader 는 외부 config 를 우선 로드하므로 version 이 저장 시점 값으로 박제돼,
  새 이미지를 배포해도 옛 버전이 표시됐다(예: 1.70.6 이미지인데 1.70.5 표시).
  - `ConfigLoader` 가 외부 config 를 쓸 때도 **`server.version` 만은 번들(classpath)
    리소스 값으로 덮어쓰도록** 수정. 사용자 설정(port / timeout / maxConcurrentTurns
    / trustForwardedFor 등)은 외부 config 그대로 유지, version 만 코드 기준.

## [1.70.6] - 2026-05-31 — Claude 사용량 미표시 회수 (2.1.158 TUI 대응)

### Fixed

- **Claude 사용량(quota) 이 표시되지 않던 문제** — Claude Code 2.1.158 에서
  대시보드/`/usage` 의 사용량이 비어 있었다. 원인 두 가지:
  1. **온보딩 차단** — 2.1.x interactive TUI 는 `~/.claude/.claude.json` 의
     `hasCompletedOnboarding` 플래그가 없으면 매 실행마다 "Select login method"
     온보딩을 띄운다. quota 는 TUI 를 PTY 로 캡처해 얻는데 이 화면에서 막혀
     아무것도 못 읽었다(이미 oauthAccount 로 로그인된 상태인데도).
  2. **네비게이션 변경** — 기존 캡처는 `/status` 입력 후 →→→ 화살표로 Usage 탭
     이동이었는데 2.1.158 에서 깨졌다. 2.1.158 은 **`/usage` 슬래시**가 Usage
     화면("Current session N% used / Current week N% used / Resets …")을 바로 연다.
  - **entrypoint** 가 부팅 시 `~/.claude/.claude.json` 에 `hasCompletedOnboarding=true`
    + 캡처 cwd(`/workspace`, `/workspace/__scratch__`) `hasTrustDialogAccepted=true`
    를 idempotent 하게 기록(로그인된 config 있을 때만) → 온보딩/trust 다이얼로그
    건너뜀.
  - **`claude-usage-capture.exp`** 를 `/status`+화살표 → **`/usage` 직접 호출**로
    교체(탭 네비게이션 제거 = 견고). trust 다이얼로그 잔존 대비 `/usage` 전 Enter 1회.
  - 서버측 파서(`parseUsageOutput`)는 기존 `N% used` 정규식으로 신포맷도 그대로
    파싱 — 변경 불필요. 검증: 세션/주간 % + Resets 정상 추출 확인.
  - 파일: `docker/scripts/claude-usage-capture.exp`, `docker/entrypoint.sh`.

## [1.70.5] - 2026-05-31 — UI 기능별 버전 라벨 제거

### Changed

- **UI 전반의 기능별 버전 표기 제거** — "Claude 사용량 (v0.21.0)", "디스크 사용량
  (v0.29.0)", "대화 검색 v0.30.0 — …" 처럼 헤딩/툴팁/힌트에 박혀 있던 *"이 기능은
  vN 에 추가됨"* 류 라벨이 단일 사용자에게 의미가 없어 전부 제거.
  - i18n(`MessagesKo`/`MessagesEn`) 엔트리 + SSR 라우트(history/usage/backup/tools/
    webauthn/push/agents/symbols/code-analysis/dependency-audit/build-cache/env-files/
    global-search) 헤딩·툴팁·힌트 일괄 정리.
  - 대시보드의 **"서버 버전" 카드(현재 실행 중인 빌드 표시)는 유지** — 운영상 의미가
    있어 제외.

## [1.70.4] - 2026-05-31 — 콘솔 WebSocket 재연결 UX 개선

### Changed

- **콘솔 WS 끊김 메시지/재연결 개선** — 탭을 백그라운드로 보내거나 다른 앱에
  갔다 오면 브라우저가 소켓을 얼려 서버 timeout(45s)으로 1006 close 가 발생하는데
  (정상 동작), 매번 `closed (code 1006); 재연결 5초 후` 가 떠서 끊긴 것처럼 보였다.
  - 끊김 시 **한 번만** `연결 끊김 — 재연결 중…` 으로 부드럽게 안내, 재연결되면
    `재연결됨` 표시(매 close 마다 코드 노출 안 함).
  - **탭 복귀(visibilitychange) · 포커스 · 네트워크 복구(online) 시 즉시 재연결** —
    5초 기다리지 않음. 포그라운드 끊김은 2초 후 자동 재시도.
  - 정상 종료(code 1000)는 알림/재연결 생략. 중복 소켓/타이머 가드 추가.
  - 메인 콘솔 · `/chat` · sub-agent 콘솔 동일 적용.
  - 참고: 서버 keepalive(`pingPeriodMillis=20s` / `timeoutMillis=45s`)는 정상이며,
    1006 은 서버/프록시 결함이 아니라 브라우저의 백그라운드 소켓 동결이 원인.

## [1.70.3] - 2026-05-31 — SSR 히스토리 친화 렌더링 + 복사 아이콘 추가 축소

### Fixed

- **`/history` · `/chat/history` · 전역 대화 검색(SSR)의 raw JSON 노출 제거** —
  v1.70.0 은 콘솔(JS)만 고쳤고 이들 **서버 렌더링 히스토리 페이지**는
  `conversation_turns.content` 를 `<pre>` 에 raw 로 그대로 출력하고 있었다.
  `tool_use` input(`{"file_path":…,"content":…}`), `unknown`(`{"type":"thinking"…}`),
  `tool_result` 배열, `system {kind:…}` 등이 전부 raw 로 보임.
  - 신규 `HistoryContentFormatter` (콘솔 `console-render.js` 의 **서버측 짝**)로
    tool_use→`key=value` 요약, tool_result→평문, thinking→`💭`, system/rate_limit→
    라벨 변환. 파싱 실패 시 원본 폴백.
  - 적용: `HistoryRoutes`(/history·/chat/history), `GlobalHistorySearchRoutes`(전역 검색).

### Changed

- **콘솔 복사 아이콘 추가 축소** — `.log-copy` SVG 11px → 9px (원본 14px 의 ~2/3).

## [1.70.2] - 2026-05-31 — 콘솔 복사 아이콘 축소

### Changed

- **콘솔 메시지 우하단 복사 아이콘 축소** — `.log-copy` SVG 14px → 11px 로
  줄여 카드 본문 대비 덜 튀게 조정.

## [1.70.1] - 2026-05-31 — 콘솔 고정 프롬프트 카드 개선

### Fixed

- **상단 고정(sticky) 프롬프트 위로 콘솔 내용이 비치던 문제** — `.console-log`
  의 `padding-top: 12px` 때문에 `top:0` sticky 카드가 그만큼 내려가 그 위 12px
  로 스크롤 내용이 보였다. 컨테이너 top padding 을 제거해 카드가 최상단까지 밀착.

### Changed

- **긴 프롬프트는 앞 2줄만 표시** — 이전엔 `max-height:40vh` 로 긴 프롬프트가
  콘솔 절반 이상을 덮었다. `-webkit-line-clamp: 2` 로 2줄 클램프 + **카드 클릭
  시 펼침/접힘**(`.expanded`, 펼치면 최대 40vh 자체 스크롤). 복사 버튼/텍스트
  드래그 선택은 토글에서 제외.
- **사용자 송신 프롬프트 카드 배경 차별화** — 불투명 파랑 틴트 배경(`#121a28`)
  + 좌측 강조선(inset box-shadow)으로 응답/시스템 카드와 시각적으로 구분.
  불투명이라 고정 시 아래 내용 비침도 함께 차단.
- 메인 콘솔 · `/chat` · sub-agent 콘솔 모두 동일 적용(공유 `admin.css`
  `.console-log` + 각 콘솔에 클릭-펼침 핸들러).

## [1.70.0] - 2026-05-31 — 콘솔 응답 친화 렌더링 (raw JSON 제거)

### Changed

- **콘솔의 raw JSON 노출 전면 제거** — 운영 DB(`conversation_turns`) 정밀
  분석 결과, 다음 이벤트들이 사용자에게 raw JSON 으로 보이거나 의미 없이
  드롭되고 있었다. 모두 친화적 메시지로 변환.
  - **tool_result** — content 가 배열(`[{"type":"text","text":"…"}]`)일 때
    `JSON.stringify` 로 그대로 노출되던 것을 평문 추출(text 블록 join,
    image 는 `[image]`)로 변경. 라이브 + history replay 양쪽.
  - **tool_use (11개 외 도구)** — `Task`/`ToolSearch`/`Monitor`/`ScheduleWakeup`/
    `BashOutput`/`KillShell`/`SlashCommand`/`ExitPlanMode`/`AskUserQuestion`/
    `NotebookEdit`/`MultiEdit`/`Skill`/`Workflow`/`Task*` + **모든 `mcp__server__tool`**
    (→ `🔌 server·tool`) 인식. 미지원 도구도 raw dump 대신 `key=value` 요약.
  - **thinking 블록** — 이전엔 raw 로 보이거나(드롭) → `💭 thinking` 흐릿한
    이탤릭. signature-only(빈 본문) 는 숨김. 콘솔 필터에 "사고 과정" 토글 추가.
  - **system 서브타입** — `task_started`(🟢 task) / `task_notification`(✓ task) /
    `task_updated`(… task) 를 sub-agent 작업 진행으로 표시(기존엔 드롭).
  - **rate_limit_event** — `⏳ rate limit` + 상태/리셋시각으로 표시(기존 드롭).
  - **history replay** — 저장된 `system {kind:…}` / `error {code,message}` /
    `tool_use` input / `unknown` 도 라이브와 동일 변환(기존엔 raw JSON).
  - 렌더 로직을 공용 **`/static/admin/console-render.js`** (`window.VibeConsole`)
    로 추출 → 메인 콘솔 · `/chat` · **sub-agent 콘솔**이 동일 표현 공유
    (이전 sub-agent 는 항상 raw dump 였음).
  - 파일: `static/admin/console-render.js`(신규) + `admin.css`(thinking 스타일),
    `WebProjectTemplates.kt`, `SubAgentRoutes.kt`, i18n(filter label).

### Fixed

- **`thinking_tokens` 노이즈 적재/emit 제거** — `{type:system,subtype:thinking_tokens}`
  (추정 토큰 카운터)는 UI/이력 가치가 없는데 운영 DB 에 5792행 누적돼 있었다.
  `ClaudeStreamParser` 가 emit/적재 모두 생략하도록 드롭. 기존 행은 영향 없음
  (신규 turn 부터 미적재).

## [1.69.0] - 2026-05-31 — 동시 in-flight turn 제한 + 콘솔 Enter 전송

### Added

- **동시 진행 Claude turn 상한 (`claude.maxConcurrentTurns`, 기본 3)** — 여러
  프로젝트/sub-agent 콘솔을 오가며 동시에 prompt 를 던지면 같은 Anthropic
  계정+IP 로 요청이 burst 로 몰려 서버측 throttle(HTTP 429 "Server is temporarily
  limiting requests · Rate limited")이 발생한다. 이를 막기 위해 동시에 진행 중인
  turn 수를 상한으로 제한하는 게이트를 추가.
  - **거부가 아니라 대기(queue)**: 상한 도달 시 새 turn 은 permit 이 빌 때까지
    suspend 후 순서대로 처리 → 프롬프트 유실 없음.
  - `ClaudeConcurrencyGate` (coroutine `Semaphore` 기반) 를 **메인 콘솔
    (`ClaudeSessionManager`) + 멀티 에이전트 (`SubAgentSessionManager`) 가 공유** —
    같은 계정으로 나가므로 하나의 풀에서 함께 카운트.
  - acquire 는 `sendPrompt` 의 stdin write 직전, release 는 turn 종료
    (`busy` true→false: Done/cancel/crash/idle, sub-agent 는 Done/exit/terminate)
    + write 실패 지점에서 idempotent 하게. key 당 permit 최대 1개 (중복 acquire 무시).
  - 기본값 **3** (1M 컨텍스트 + throttle 회피를 위한 보수값). `0` = 무제한(기존 동작).
  - 설정: `/settings` → Claude 섹션에 "동시 진행 turn 상한" number 입력(0~20).
    `server.yml` 의 `claude.maxConcurrentTurns`. **변경은 컨테이너 재시작 후 적용.**
  - 파일: `claude/ClaudeConcurrencyGate.kt`(신규) + 단위 테스트, `ServerConfig.kt`,
    `server.yml`, `ServerMain.kt`, `ClaudeSessionManager.kt`,
    `SubAgentSessionManager.kt`, `AdminRoutes.kt`, `AdminTemplates.kt`, i18n.

### Changed

- **콘솔 프롬프트 전송/줄바꿈 키 변경** — **Enter = 전송**, **Ctrl/Cmd+Enter
  (또는 Shift+Enter) = 줄바꿈** 으로 맞바꿈(이전: Ctrl+Enter 전송). 메인 콘솔 +
  sub-agent 콘솔 양쪽 적용. 한글 등 IME 조합 확정 Enter 는 `ev.isComposing`
  가드로 전송하지 않아 오발송 방지. placeholder/hint 문구도 갱신.
- **새 프로젝트 화면 "프로젝트 ID" 라벨** → **"프로젝트 ID • Folder (kebab-case)"** —
  입력한 ID 가 워크스페이스 내 프로젝트 폴더명과 동일함을 명시.

## [1.68.1] - 2026-05-31 — Gitea MCP command 절대경로 고정 (구 npm shadow 회피)

### Fixed

- **공식 `gitea-mcp` 바이너리가 구 npm 패키지에 가려지던 문제** — 이전에
  설치됐던 community 패키지(`@boringstudio_org/gitea-mcp`)가 npm 글로벌
  prefix(`/home/vibe/.local/bin/gitea-mcp` → `index.js`)에 심볼릭을 남겼고,
  PATH 가 `/home/vibe/.local/bin` 을 `/usr/local/bin` 보다 먼저 보므로
  `command = "gitea-mcp"` 가 **구 JS 스크립트로 잘못 해석**됐다(공식 Go 바이너리
  가려짐).
  - 카탈로그 `command` 를 절대경로 **`/usr/local/bin/gitea-mcp`** 로 고정 →
    PATH shadow 와 무관하게 이미지의 공식 바이너리를 결정적으로 실행.
  - 운영 볼륨의 구 npm 패키지(`@boringstudio_org/gitea-mcp`)는 `npm uninstall -g`
    로 제거(shadow 심볼릭 제거). 신규 설치 볼륨엔 애초에 없음.

## [1.68.0] - 2026-05-31 — Gitea MCP를 공식 서버(gitea/gitea-mcp)로 교체

### Changed

- **Gitea MCP 를 공식 `gitea.com/gitea/gitea-mcp` (v1.0.1) 로 교체** — 기존
  community npm 패키지(`@boringstudio_org/gitea-mcp`)가 오작동(콘솔에서 동작 안 함).
  공식 서버는 Go 정적 바이너리라 npm 이 아닌 **이미지 번들 바이너리**로 동작.
  - **Dockerfile** — 빌드 시 공식 릴리스 tar.gz 를 받아 `/usr/local/bin/gitea-mcp`
    설치(`TARGETARCH` 기반 amd64/arm64 자동 선택, 정적 링크라 의존성 없음).
    `/usr/local/bin` 은 볼륨 밖이라 이미지 업그레이드 후에도 유지.
  - **카탈로그** — `command = "gitea-mcp"`, `args = ["-t","stdio"]`, trust VERIFIED.
    env 규약이 공식 기준으로 변경: `GITEA_API_URL`/`GITEA_TOKEN` →
    **`GITEA_HOST`(인스턴스 루트 URL, `/api/v1` 아님) + `GITEA_ACCESS_TOKEN`**
    (+ 선택 `GITEA_INSECURE`). 재설치 시 새 필드 입력 필요.
  - **`McpEntry.binaryInstall` 플래그 신규** — true 면 `npm install -g` 건너뛰고
    user-scope 등록만, "설치됨" 판정도 npm 대신 PATH 의 바이너리 존재(`command -v`)로.
  - **stale 등록 자동 정리** — startup 의 `ensureUserScopeRegistration()` 가
    binaryInstall 항목 중 등록된 `command` 가 카탈로그와 다른 것(= 구 npx gitea)을
    user-scope 에서 제거 → 사용자가 올바른 env 로 재설치하도록 NOT_INSTALLED 환원.
  - 변경 파일: `docker/Dockerfile`, `env/McpCatalog.kt`, `env/McpService.kt`.
  - **마이그레이션**: 배포 후 MCP 페이지에서 Gitea 를 다시 [설치] —
    GITEA_HOST(예: `https://gitea.wody.kr`) + Access Token 입력. 콘솔에서
    `claude mcp list` 또는 프로젝트 MCP 탭의 상태 카드(v1.67.0)로 Connected 확인.

## [1.67.0] - 2026-05-31 — 프로젝트 MCP 탭에 "인식된 MCP + 연결 상태" 라이브 카드

### Added

- **프로젝트 MCP 탭(`/projects/{id}/mcp`) 상단에 라이브 상태 카드** — 해당
  프로젝트에서 Claude 가 실제로 인식하는 MCP 서버 목록과 **연결 상태**를
  보여준다. `claude mcp list` 를 프로젝트 cwd 에서 실행해 파싱하며, 상태를
  연결됨(✓ 초록) / 연결 실패(✗ 빨강) / 인증 필요(! 주황) / 승인 대기(⏸ 흐림)
  로 색상 배지 표시. ↻ 새로고침 버튼.
  - health check 라 수 초 걸릴 수 있어 **페이지와 분리된 ajax fragment** 로 로드
    (`GET /projects/{id}/mcp/status`, HTML fragment 응답). 페이지 로드 시
    "상태 확인 중…" placeholder → 결과 주입.
  - 기존의 디스크 파싱 기반 칩 목록(전역/프로젝트 `.mcp.json`)은 "등록되어 있는가"
    를 보여줬지만, 이 카드는 "Claude 가 실제로 로드해 **연결됐는가**"를 보여줘
    v1.66.5 의 user-scope 전환이 콘솔에 제대로 반영됐는지 한눈에 확인 가능.
  - 변경 파일: `env/McpService.kt`(`LiveServer` data class + `liveStatus(cwd)` +
    `runClaudeMcp` 의 cwd/timeout 파라미터), `projects/ProjectMcpRoutes.kt`
    (status 라우트 + `renderMcpLive` helper + 로더 JS), i18n `mcpProject.live.*`
    11개 키(Ko/En).

## [1.66.5] - 2026-05-31 — MCP를 표준 글로벌(user-scope) 위치에 등록 (콘솔 인식 수정)

### Fixed

- **설치한 MCP가 콘솔에서 안 보이던 근본 문제** — vibe-coder 는 MCP 를
  `${CLAUDE_CONFIG_DIR}/.mcp.json` 에 직접 기록했지만, **Claude Code 는 그 경로를 MCP
  설정으로 읽지 않는다**(프로젝트 `.mcp.json` 또는 user scope 만 읽음). 그래서 콘솔
  세션에 등록 MCP(fetch/memory/gitea 등)가 전혀 로드되지 않았다(`claude mcp list` 에도
  미표시).
  - 등록/제거를 **표준 메커니즘으로 전환**: `claude mcp add-json <name> '<json>' -s user`
    / `claude mcp remove <name> -s user` → `.claude.json` 의 user-scope `mcpServers`.
    이제 콘솔·서브에이전트·터미널·`claude mcp list` 모두에서 일관 인식.
  - **기존 설치 자동 이관** — 서버 startup 시 `ensureUserScopeRegistration()` 가 레거시
    `.mcp.json` 의 server 들을 user-scope 로 멱등 이관(없는 것만). 진짜 첫 실행이면 기본
    MCP(fetch/memory/sequential-thinking/context7/playwright) user-scope 등록.
  - `detect`/`registeredServerNames` 가 user-scope(`.claude.json`)를 source-of-truth 로 읽음.

## [1.66.4] - 2026-05-31 — MCP 설치 버튼 폭 / 상태 판정 / Gitea 패키지 수정

### Fixed

- **Gitea MCP 설치 실패** (`npm install -g mcp-server-gitea → exit 1`) — 카탈로그의
  `mcp-server-gitea` 가 npm 에 존재하지 않음(404). 실제 게시 패키지
  **`@boringstudio_org/gitea-mcp`**(env `GITEA_API_URL`/`GITEA_TOKEN` 일치)로 교체.
  `GITEA_API_URL` 안내를 `<root>/api/v1` 로 보정 + homepage 추가.
- **기본 설치 MCP 가 "등록만" 으로 오표시** — `npx -y <pkg>` 기반 MCP 는 글로벌 설치
  없이 on-demand 실행되는데, `npm ls -g` 미검출로 `REGISTERED_ONLY`(등록만)로 보였다.
  `McpService.detect` 에서 **npx 기반은 등록되어 있으면 `INSTALLED`(설치됨)** 로 판정
  (글로벌 설치 필요한 command != npx 항목만 "등록만" 구분). Play 업로드 precheck 도
  동일 영향(npx + SA JSON 시 ready).

### Changed

- **MCP 설치 버튼이 카드 전체폭을 쓰던 문제** — `button.primary` 의 `width:100%` 를
  inline `width:auto` 로 덮어 **라벨(설치/Install) 너비만큼만 우측 정렬**. 아이콘(⤓/↻)
  대신 텍스트 라벨로.

## [1.66.3] - 2026-05-31 — MCP 카드 UI 정리 + 스피너 상시 노출 버그 수정

### Fixed

- **MCP 카드의 스피너가 항상 도는 버그** — 진행영역 `<div class="mcp-progress" hidden
  style="display:flex">` 에서 인라인 `display:flex` 가 `hidden` 속성(`[hidden]{display:none}`,
  낮은 우선순위)을 덮어써 설치 중이 아니어도 모든 카드에서 스피너가 노출됐다. 초기
  `display:none` + JS 가 설치 시 `flex` 로 전환하도록 수정.

### Changed

- **MCP 카드 UI 정리** — 설치 버튼을 **아이콘(⤓/↻)으로 카드 우하단** 배치(제거 버튼은
  그 왼쪽, 설치/등록 시만). 설정 필요 항목의 입력 필드는 **접이식(`<details>`, 기본 접힘)**
  으로 표시(`⚙ 설정 필요 (N)`). 설치 클릭 시 접힌 설정을 자동 펼쳐 required 필드 validation
  포커스 보장. i18n `mcp.entry.configToggle`.

## [1.66.2] - 2026-05-31 — 이미지 내 전역 CLAUDE.md 정책 보강

### Changed

- 컨테이너 전역 CLAUDE.md 템플릿(`templates/container-global-claude.{ko,en}.md`,
  새 프로젝트/컨테이너에 적용)에 정책 섹션 추가:
  - **코드 수정 후 필수 절차(최우선)** — 빌드 테스트 → 자동 버전 관리 → `CHANGELOG.md`
    업데이트 → git commit/push 순서, 절대 누락 금지.
  - **버전 정책 (Claude Code 일임)** — versionName=SemVer(기본 patch++), versionCode=
    `YYMMDDRRR`(매 코드 수정 RRR++, **날짜 바뀌면 001 재시작**), 매 코드 변경 시 자동 갱신.
  - **디버그 빌드 패키지명** — 디버그는 릴리즈 applicationId 에 `.debug` 서픽스
    (`applicationIdSuffix = ".debug"`), 릴리즈 appId 원본 유지. 키스토어 파일명은
    릴리즈 applicationId 기준(`-debug.keystore`).
  - **키스토어 (서명) 프로젝트별 파일 위치** — `/home/vibe/keystores/<applicationId>.keystore`
    (+ `-debug` / `-keystore.properties` / `-admob.properties`). properties 로더 사용,
    비번 하드코딩·키스토어 임의 생성 금지.
  - **설계 원칙 / 코드 품질** — OOP / 모듈화 / 의존성 최소화 / 캡슐화 / 유지보수성,
    레거시 코드 발견 즉시 제거.
  - **Git 규칙** — 생성 즉시 `git init`, 코드 변경 시 즉시 commit, 작업 단위 명확.
  - **문서 필수 업데이트** — `CHANGELOG.md` / `README.md` / `CLAUDE.md`, 레거시 문서 즉시 제거.
  - **파일 및 네이밍 규칙** — 명확한 이름, `Utils`/`Helper` 남발 금지, 역할 기반 네이밍.
  - **절대 금지 보강** — 하드코딩 / 레거시 방치 / 커밋 누락 / 문서 미갱신 / 키스토어 내부 저장.
  - **최종 원칙** — 유지보수성 · 확장성 · 일관성 · 자동화 가능성.
- 전체를 **번호 체계(1~12)로 재정리** + 목차 추가 — Claude Code 참조 용이, 내용 보존.
- 기존 프로젝트엔 영향 없음(notExists 가드) — 적용은 다음 컨테이너 부팅/새 프로젝트부터.

## [1.66.0] - 2026-05-31 — 스토어 자산 탭에 Play Console 업로드 연결

### Added

- **Play Console 업로드** (스토어 자산 탭) — `google-play-publisher` MCP 가 설치 +
  Service Account JSON 설정돼 있으면(precheck) 탭에 업로드 카드가 활성화된다.
  - 트랙(internal/alpha/beta/production) 선택 + 릴리스 노트 + "스토어 자산(그래픽/
    스크린샷)도 함께 반영" 체크 → release AAB 업로드 + (옵션) 그래픽·언어별 스크린샷
    listing 반영을 **한 prompt 로 Claude 콘솔에 위임**(`PlayPublishService.triggerStoreUpload`).
    콘솔 탭에서 진행/결과 확인, publish 는 review 상태 유지.
  - MCP 미설치/미설정 시: 카드가 상태·경고 + `/env-setup/mcp` 링크로 안내(비활성).
  - 라우트 `POST /projects/{id}/assets/play-upload`(CSRF), precheck 재검증.
- `PlayPublishService.triggerStoreUpload(track, releaseNotes, includeListing,
  hasGraphic, screenshotsByLang, …)` 신규 — 기존 AAB-only `trigger` 와 별개로
  listing 자산까지 포함.
- i18n `assets.play.*` (Ko/En).

## [1.65.0] - 2026-05-31 — 프로젝트 스토어 자산 탭 (앱 아이콘/그래픽/스크린샷)

### Added

- **스토어 자산 탭** — 프로젝트 페이지 상단에 `스토어 자산` 탭 신설
  (`/projects/{id}/assets`). 앱 아이콘 · 피처 그래픽 · 스크린샷을 프로젝트 루트에
  업로드.
  - **앱 아이콘**: 업로드 → 루트 `icon.png` 저장. 업로드 시 **다이얼로그로 컨펌** —
    [확인]이면 Claude 콘솔에 "런처 아이콘 적용" 정형 prompt 를 전송해 Claude Code 가
    `res/mipmap-*` 에 자동 적용(빌드는 미실행). [취소]면 `icon.png` 복사만.
    선택 시 즉시 미리보기(`URL.createObjectURL`).
  - **피처 그래픽**: 업로드 → 루트 `graphic.png` (1024×500 권장).
  - **스크린샷**: 언어 코드별로 루트 `screenshot-<lang>-<n>.png` (자동 번호, 다중
    업로드). 목록 미리보기 + 개별 삭제.
  - 미리보기 serve `GET /projects/{id}/assets/raw/{name}` (허용된 파일명만,
    workspace 내부 보장). 업로드는 multipart + part `_csrf` 검증, 고정/검증 파일명만
    기록(traversal 차단).
- `ProjectService.resolveAppIcon` 가 res raster 가 없으면 루트 `icon.png` 를
  fallback 으로 사용 → 목록/탭이 업로드 직후(Claude 적용 전)에도 새 아이콘 반영.
- i18n `assets.*` / `tabs.assets` (Ko/En).

## [1.64.0] - 2026-05-31 — 프로젝트 목록 앱 아이콘·버전 + 사이드바 무선디버깅 pill

### Added

- **프로젝트 목록 행에 앱 아이콘 + 버전** (`/projects`) — 프로젝트명 좌측(상태칩 우측)에
  런처 아이콘, 이름 우측에 `vX.Y.Z` 버전 배지.
  - 아이콘: `GET /projects/{id}/app-icon` 이 모듈 `src/main/res/mipmap-*|drawable-*` 에서
    `ic_launcher(.png/.webp)` 등 raster 를 큰 density 우선으로 찾아 스트림(workspace 내부
    보장). adaptive-icon(xml)만 있으면 404 → **placeholder(vibe-coder 아이콘)** 사용.
    `<img onerror>` 로도 placeholder fallback.
  - 버전: 모듈 `build.gradle(.kts)` 의 `versionName` 을 **매 요청 시 동적으로** 읽음
    (`ProjectService.appVersionName`). 없으면 미표시.
- **사이드바 무선디버깅 pill** — 기존 ADB 뱃지를 **Claude 사용량 위로 이동**하고
  `무선디버깅` 타이틀 + 상태(연결됨/대기 중) + 연결 기기 수 + pulse dot 으로 UI 개편.
  adb 미설치 시 hidden, 사이드바 접힘 시 hidden. i18n `nav.adb.*`.

### Changed

- `admin.css?v=1.64.0`. 신규 i18n `projects.*`(아이콘/버전은 키 불필요) — `nav.adb.*` 추가.

## [1.63.0] - 2026-05-31 — 대시보드 무선 ADB 기기 상태 카드

### Added

- **무선 ADB 기기 상태 카드** — 홈 대시보드(`/`)에 무선 ADB 카드 추가. ADB 사용
  가능 여부 + 연결된 기기 수를 표시하고 `/adb`(기기 로그/연결 관리)로 링크. 기존
  `/api/adb/status` JSON 을 30초 주기로 폴링하는 클라이언트 카드라 서버 의존성 주입
  변경 없음. i18n `dashboard.adb.*` (Ko/En).

## [1.62.0] - 2026-05-31 — MCP 카드 인라인 설치 (페이지 이동 없이 스피너)

### Changed

- **MCP 설치를 인라인으로** — 카드의 설치 버튼이 더 이상 라이브 로그 전체 페이지로
  이동하지 않고, **카드 안에서 스피너 + 진행 텍스트**를 표시한 뒤 완료 시 상태 pill·
  버튼을 그 자리에서 갱신한다.
  - 신규 JSON endpoint `POST /env-setup/mcp/install.json` — 기존 install 과 동일
    로직이지만 redirect 대신 `{taskId}` 반환. 카드 JS 가 기존
    `/ws/env-setup/{taskId}/logs`(쿠키 인증, `done` 프레임) 를 구독해 진행/완료 감지.
  - 설치 성공 → pill `설치됨`, 버튼 `재설치`, `제거` 버튼 노출. 실패 → 에러 + 재시도 가능.
  - **제거도 인라인** — `unregister` 를 ajax 로 호출 후 pill/버튼을 즉시 되돌림.
  - 카드 1폼·2버튼(설치/제거 `formaction` 분기)으로 중첩 폼 제거. JS 끄면 기존
    redirect 방식으로 graceful degrade.
  - 신규 i18n `mcp.js.installing/installDone/installFailed/removing/removed` (Ko/En),
    `.mcp-spinner` CSS(기존 키프레임 재사용). `admin.css?v=1.62.0`.

## [1.61.0] - 2026-05-31 — MCP 카탈로그 마켓플레이스형 (카드별 설치/제거)

### Changed

- **MCP 설치 UX 개편** (`/env-setup/mcp`) — 체크박스 다중선택 + 하단 일괄 설치/제거
  바 방식을 **카드마다 설치/제거 버튼 + 상태 pill** 방식(마켓플레이스형)으로 교체.
  - 각 카드 우상단에 상태 pill: `설치됨`(초록) / `등록만`(주황) / `미설치`(회색).
  - 카드 하단에 자체 설치 폼(필요한 토큰/URL 입력란 항상 노출) + **설치/재설치**
    버튼. 설치/등록된 항목은 **제거** 버튼 추가(확인 다이얼로그).
  - 라우터/서비스(`McpRoutes`/`McpService`)는 그대로 — 기존 `select` 단건 install/
    unregister 를 카드별 폼이 그대로 사용. 설치는 기존 라이브 진행 페이지로 이동.
  - 파일 업로드(Service Account JSON/.p8) ajax 는 카드 폼 단위로 동작 유지.
  - 신규 i18n `mcp.entry.notInstalled` / `install` / `reinstall` / `remove` /
    `removeConfirm` (Ko/En). `admin.css` 의 `.pstat-*` pill 재사용.

## [1.60.0] - 2026-05-31 — 프로젝트 목록 순서변경/페이지네이션 + 상태칩 3-state

### Added

- **프로젝트 드래그 순서변경** — `/projects` 목록 우측 핸들(☰)을 잡고 위/아래로
  끌어 순서를 바꾼다. `Projects.sort_order` 컬럼 신규(부팅 시 `createMissingTablesAndColumns`
  자동 추가). 드롭 시 현재 페이지의 새 순서 + offset 을 `POST /api/projects/reorder`
  로 전송 → 서버가 전체 정렬의 해당 slice 를 교체하고 sort_order 정규화(집합 불일치
  시 거부). **새 프로젝트는 맨 위**(insert 시 `min(sort_order)-1`).
- **페이지네이션** — 페이지당 개수 콤보(20/50/100, `localStorage` 기억) + 이전/다음
  네비. `GET /projects?page=&size=`.
- **switcher 순서 반영** — 콘솔 상단 프로젝트 전환 콤보(`ProjectTabsTemplate`)가
  이름 가나다순 → **사용자 정의 순서**(sort_order)로.

### Changed

- **상태칩 3-state 재설계 (응답중 / 대기중 / 중지됨)** — 기존
  `responding`/`ready`/`idle` 은 자식 **프로세스 생존**(isBusy/isAlive) 기준이라
  부정확했다(재시작 후 완료된 대화가 `유휴`, 응답 중 서버중단이 미반영). 이제
  **대화 이력(conversation_turns) 기반**:
  - 응답 stream 중 → `응답중`.
  - 최신 user 프롬프트 이후 완료(assistant/usage) 없음 → `중지됨`(cancel/crash/서버중단).
  - 그 외(완료 또는 fresh) → `대기중`. (`유휴` 제거.)
  - `ConversationTurnRepository.lastPromptInterrupted`, `WebProjectRoutes.projectStatus` 헬퍼.
  - 실시간: `ProjectBusyChanged.state`(신규 필드)로 cancel/crash 시 `중지됨` live 반영,
    서버 재시작은 snapshot(이력)으로 정확 산출.
- `admin.css?v` 캐시 버전 1.60.0.

### Wire change

- **Yes** — Android `shared/` 동기 필요.
  - `ApiPath.PROJECTS_REORDER` 신규 + `ProjectReorderRequestDto(offset, order)` 신규.
  - `WsFrame.ProjectBusyChanged.state: String?` 추가(additive — 구버전 클라 무시 호환).
  - **vibe-coder-android shared/ 동기 필요**: 위 3건.

## [1.59.2] - 2026-05-31 — 콘솔 프롬프트 sticky 고정 + 우측 히스토리 즉시 반영

### Added

- **내가 보낸 프롬프트 카드 상단 고정(sticky)** — 콘솔에서 프롬프트를 보내면 그
  user 카드가 새 메시지에 밀려 올라가다 최상단에 닿으면 **스크롤 상단에 걸려 고정**
  된다. 지금 스트리밍되는 응답이 어떤 프롬프트에 대한 것인지 한눈에 보인다. CSS
  `position:sticky` 만 사용(JS 무관). sticky 형제 특성상 다음 user 카드가 올라오면
  이전 카드를 교체 → 항상 "직전 프롬프트" 1장 고정. 긴 프롬프트는 40vh 캡 + 자체
  스크롤. `/chat` 에도 동일 적용.

### Fixed

- **우측 오버뷰 프롬프트 히스토리 즉시 미반영** — 콘솔은 iframe 이고 우측 rail
  (`ProjectTabsTemplate`)은 서버 렌더 시점의 snapshot 만 갖고 있어, 프롬프트를
  보내도 페이지 reload 전엔 히스토리가 갱신되지 않았다. 콘솔 `sendPrompt` 성공 시
  부모로 `postMessage('vibe:prompt-sent')` 통지 → `project-tabs.js` 가 rail
  `.pt-hist-list` 맨 위에 즉시 prepend(최대 7개 + opacity ramp, 직전 동일 prompt
  중복 skip). same-origin 검증.
- `admin.css?v` / `project-tabs.js?v` 캐시 버스팅 버전 갱신(1.59.2).

## [1.59.1] - 2026-05-31 — 무선 ADB 연결 실패 진단/안내 개선

### Fixed

- **무선 ADB "failed to connect" 안내** — 자동 탐지 목록에 뜬 기기에 "연결" 을
  눌렀을 때 `failed to connect to <ip:port>` 로 실패하던 현상의 원인은
  **Android 11+ 페어링 미완료**였다. connect 서비스(`_adb-tls-connect`)는 페어링
  여부와 무관하게 mDNS 로 광고되어 목록·"연결" 버튼이 노출되지만, 미페어링 키는
  TLS 단계에서 거부된다(포트는 열려 있어 `Connection refused` 없이 실패).
  - `AdbService.isLikelyUnpaired(output)` — `failed to connect` + `refused`/`timeout`
    suffix 없음 → 미페어링 추정. `/adb/connect` 실패 시 "먼저 페어링하세요" 안내로 치환.
  - `AdbService.connectPortFor(ip)` — 같은 IP 의 mDNS connect 포트 탐색.
    `/adb/pair` 성공 시 그 포트로 **자동 connect 1회 시도**(이전엔 주석만 있고
    동작 안 함 — 주석/동작 불일치 회수). 자동 연결 실패 시 수동 연결 안내.
  - 신규 i18n `adb.connect.needPair` / `adb.pair.autoConnected` / `adb.pair.okConnectHint` (Ko/En).
  - 참고: adb 키(`~/.android`)는 compose 볼륨으로 영속되므로 **페어링은 1회만** 하면 유지됨.

## [1.59.0] - 2026-05-31 — 프롬프트 자동화 (서버 백그라운드 autopilot)

### Added

- **프롬프트 자동화** — 콘솔 작업(turn)이 완료될 때마다 서버가 다음 프롬프트를
  자동 전송한다. **브라우저를 닫아도 서버 프로세스 안에서 계속 진행**된다.
  - **반복(repeat)**: 같은 프롬프트(예: "앱 전체 코드 정밀 점검/리뷰/수정")를 N회.
  - **순차(sequence)**: 미리 작성한 프롬프트 리스트를 순서대로(전체를 loops회).
  - 핵심 hook: `ClaudeSessionManager` 의 turn 완료(`Done`) 신호에 리스너
    (`turnDoneListener`)를 달아 `PromptAutomationManager` 가 큐에서 다음 프롬프트를
    `sendPrompt`. cancel / new session / crash 시 자동 중단(`turnInterruptListener`).
  - 콘솔에 접이식 **자동화 패널**(시작/중지 + 실시간 진행 뱃지, `AutomationProgress`
    WS 구독). 대화 전용 General Chat 에선 숨김.
  - `/projects/{id}/automation/prompts` SSR 페이지 — 즉석 시작 + 프리셋 관리 +
    최근 실행 이력. 빌드 자동화 페이지에서 링크 진입.
- **프롬프트 프리셋 (서버 저장)** — 자주 쓰는 프롬프트 리스트를 workspace 전역
  (`<workspace>/.vibecoder/prompt-automations.json`)에 저장해 어느 프로젝트/세션에서나
  재사용. `PromptAutomationPresetStore`(JSON CRUD, `PromptTemplateStore` 패턴).
- **JSON API** (Bearer / 쿠키): `POST /api/projects/{id}/claude/automation/start` ·
  `.../stop` · `GET .../status`, 그리고 전역 프리셋 `GET/POST /api/prompt-automations` ·
  `PUT/DELETE /api/prompt-automations/{presetId}`.

### Changed

- 실행 이력 DB 테이블 `prompt_automation_runs` 신규(+ `PromptAutomationRunRepository`).
  서버 재시작 시 끊긴 `running` run 은 부팅 시 `stopped(orphaned)` 로 정리(reconcile).
  `ProjectService.delete` cascade 에 자동화 run 삭제 추가(FK 정합).
- 안전장치: 프로젝트당 active run 1개, 총 발사 수 상한 500, 에러/cancel 시 중단.

### Wire change

- **Yes** — Android `shared/` 동기 필요.
  - `ApiPath`: `promptAutomationStart/Stop/Status(projectId)`, `PROMPT_AUTOMATION_PRESETS`,
    `promptAutomationPreset(presetId)` 신규.
  - `WsFrame.AutomationProgress` 신규(`@SerialName("automation_progress")`) — console
    topic 으로 진행/종료 broadcast. 구버전 클라이언트는 미지원 type 으로 무시(호환).
  - DTO 신규 `AutomationDtos.kt`: `PromptAutomationPresetDto` / `…UpsertDto` /
    `…PresetsResponseDto` / `…StartRequestDto` / `…StatusDto` + `PromptAutomationMode` /
    `PromptAutomationStatus` 상수.
  - **vibe-coder-android shared/ 동기 필요**: 위 ApiPath 5건 + WsFrame 1건 + DTO 1파일.

## [1.58.0] - 2026-05-31 — 콘솔 입력창 상단 빠른 프롬프트 버블 버튼

### Added

- **빠른 프롬프트 버블 버튼** — 프로젝트 콘솔(및 General Chat)의 프롬프트 입력창
  바로 위에 자주 쓰는 짧은 프롬프트를 알약형 버튼으로 배치. **클릭하면 즉시
  전송**된다. 기본 세트: 선택지 답변 `A` / `B` / `C` / `D`, 그리고 `계속` /
  `모두 수정` / `정밀 리뷰` / `권장대로 진행`.
  - 클릭 시 textarea 에 해당 프롬프트를 채우고 `form.requestSubmit()` 으로 기존
    송신 경로를 그대로 거치므로, **응답 중이면 자동으로 큐에 적재**(v0.99.0 큐
    로직 재사용)되고 전송 후 입력창 정리/blur 도 동일하게 처리된다.
  - 인증 미비(`blocking`) 상태에선 버튼이 비활성화된다.
  - 대화 전용 General Chat(`__scratch__`/`__chat_*`)에선 코드 작업 버튼
    (`모두 수정` / `정밀 리뷰`)을 숨겨 도구 차단(v1.55.0) 정책과 일관 유지.

### Changed

- `WebProjectTemplates.consolePage` 에 `quickBarHtml`(버튼 + 인라인 스타일) 추가,
  IIFE 에 `#quick-prompts` 클릭 위임 핸들러 추가. 신규 i18n 키 `console.quick.*`
  (Ko/En). 신규 라우트/엔드포인트 없음 — 기존 `POST /api/projects/{id}/claude/console/prompt`
  재사용이라 Wire/Android 영향 없음.

## [1.57.0] - 2026-05-31 — 프로젝트/빌드 페이지 내 인라인 키스토어 생성

### Added

- **빌드 페이지에서 바로 키스토어 생성** — 키스토어가 준비되지 않은 프로젝트의
  빌드 페이지(`/projects/{id}/builds`, 프로젝트 탭의 Builds 탭 포함)에서 `설정 →
  키스토어`로 떠나지 않고 그 자리에서 키스토어 set 을 생성한다. "키스토어 미준비"
  경고 박스 안에 접이식 인라인 생성 폼(`renderInlineKeystoreForm`)이 추가됐고,
  생성 즉시 같은 빌드 페이지로 돌아와 곧바로 빌드 가능.
- **패키지명 자동 고정 + 자동 연결** — 인라인 폼의 패키지명은 해당 프로젝트의
  `packageName` 으로 readonly 고정되며, POST 핸들러(`POST /projects/{id}/keystore`)가
  폼 입력을 신뢰하지 않고 `project.packageName` 으로 set 을 만든다. 키스토어는
  packageName prefix 매칭으로 별도 설정 없이 그 프로젝트 빌드에 자동 연결.
- **과거 입력값 prefill (비밀번호 제외)** — 직전 생성 폼의 DN 메타(이름/조직/부서/
  국가/주·도/도시) + 유효기간을 `/home/vibe/keystores/.last-input.properties` 에
  캐시(`KeystoreService.recordLastInput`)하고, 다음 생성 폼에서 `effectiveDefaults()`
  로 server.yml `keystore.defaults` 위에 덮어 prefill. **비밀번호·alias 등 비밀값은
  절대 저장하지 않으며** 매번 입력받는다. 캐시 파일은 `.keystore` suffix 가 아니라
  키스토어 목록 스캔에도 잡히지 않고, `rw-------` 권한.

### Changed

- `KeystoreService` 에 `effectiveDefaults()` / `recordLastInput()` 추가, `create()`
  성공 직후 비밀번호 제외 DN 메타를 캐시. 기존 `/settings/keystores` 동작은 불변.
- `WebProjectTemplates.buildsPage` 에 `keystorePrefill: KeystoreDefaults?` 파라미터
  추가, `WebProjectRoutes` GET `/projects/{id}/builds` 가 미준비 시 `effectiveDefaults()`
  전달.

### Wire change

- 없음. 신규 라우트 `POST /projects/{id}/keystore` 는 SSR 폼 전용(쿠키 세션 +
  body `_csrf`)이라 `shared/ApiPath` / JSON API 비대상. Android 클라이언트 동기 불필요.

## [1.56.0] - 2026-05-31 — 프로젝트 퀵 이동 콤보박스에 상태칩 추가

### Added

- **프로젝트 탭 헤더의 퀵 이동 콤보박스에 상태칩** — 프로젝트명을 눌러 펼치는
  프로젝트 전환 드롭다운(`ProjectTabsTemplate` switcher)의 각 항목 좌측에 현재
  Claude 세션 상태를 색 점 + 라벨로 표시. 목록 페이지(`/projects`, v1.53.0)와
  **동일한 `.pstat` 체계**: 응답중(responding, 주황 + pulse) / 대기중(ready, 초록) /
  유휴(idle, 회색). 어느 프로젝트가 지금 응답 중인지 탭을 떠나지 않고 콤보에서 바로
  확인. 진입 시점 snapshot + `/ws/projects`(`ProjectBusyChanged`) 구독으로
  responding↔ready 를 새로고침 없이 실시간 patch.
- `ProjectTabsTemplate.page` 에 `projectStatuses: Map<String,String>` 파라미터 추가,
  `/projects/{id}` 핸들러가 `sessionManager.isBusy`/`isAlive` 로 계산해 전달.

## [1.55.0] - 2026-05-31 — General Chat 대화 전용 모드 (파일/실행 도구 차단)

### Changed

- **채팅(`/chat`)은 이제 대화 전용** — General Chat ghost(`__scratch__` /
  `__chat_*`)에서 Claude 가 실제 프로젝트 작업을 하지 못하도록 `claude` spawn 시
  `--disallowedTools` 에 `Bash` / `Write` / `Edit` / `NotebookEdit` / `Task` 를 추가
  차단한다. 파일 생성·수정, 빌드(쉘 실행), 하위 에이전트 디스패치가 모두 막혀
  텍스트 응답만 생성된다. **읽기(`Read`/`Glob`/`Grep`)·웹검색(`WebSearch`/`WebFetch`)
  은 허용**해 대화 품질은 유지. 일반 프로젝트 콘솔은 영향 없음(기존대로 전체 도구).
  판정은 `WorkspacePath.isGhostId(projectId)` (scratch + 모든 chat). 기존 세션은
  다음 prompt 로 프로세스가 재spawn 될 때부터 적용(즉시 반영하려면 "새 세션").

## [1.54.2] - 2026-05-31 — 키스토어 기본 유효기간 25년 → 100년

### Changed

- **키스토어 생성 기본 유효기간을 25년 → 100년으로** (운영자 정책). 설정 →
  Keystores(`/settings/keystores`) 생성 폼의 유효기간 prefill 값이 100년으로 바뀐다.
  `server.yml` 의 `keystore.defaults.validityYears` + `ServerConfig` 기본값 + UI 폼
  prefill. UI 입력 상한(max=100)·서버 `coerceIn(1,100)` 은 기존 그대로. 프로젝트
  등록 경로(`KeystoreRequestDto.validityDays`)는 이미 36500일(100년)이라 변동 없음.
  기존에 생성된 키스토어에는 영향 없음(생성 시점 값 고정).

## [1.54.1] - 2026-05-31 — 채팅 목록 사이드바 접기/펼치기

### Added

- **채팅 목록 사이드바 토글** — `/chat` 좌측 채팅 목록을 접거나 펼칠 수 있다.
  사이드바 상단 ⟨ 버튼으로 접으면 목록이 숨고 콘솔이 전체 폭을 쓰며, 콘솔 영역
  좌상단의 "☰ 채팅 목록" 버튼으로 다시 펼친다. 접힘 상태는 `localStorage`
  (`vibe-chat-side-collapsed`)에 영속되어 새로고침/재진입에도 유지. 데스크톱·모바일
  공통 동작. (`WebProjectTemplates.consolePage` 의 chat-shell 래퍼 + 인라인 토글 JS.)

## [1.54.0] - 2026-05-31 — General Chat 다중 세션 (ChatGPT 스타일)

기존 단일 General Chat(`/chat`, `__scratch__` ghost 1개)을 **여러 개의 독립 채팅
세션**으로 확장. ChatGPT 앱처럼 새 채팅을 만들고, 좌측 사이드바에서 기존 채팅을
골라 그 대화를 이어갈 수 있다.

### Added

- **다중 채팅 세션** — 각 채팅 = 별도 ghost 프로젝트 `__chat_<id>__`. 기존
  `ClaudeSessionManager`(프로세스/세션ID/`--resume`) · `ConversationTurnRepository`
  (turn 영속) · WebSocket 콘솔 토픽 · busy 상태 인프라를 **그대로 재사용** —
  DB 스키마 변경 없음. 채팅마다 독립된 Claude 세션 + 대화 히스토리.
- **좌측 사이드바 UI** — `/chat` 한 페이지 안에 좌측 채팅 목록 패널 + 우측 콘솔.
  "＋ 새 채팅" 버튼, 활성 채팅 강조, 응답중 점(pulse), 항목별 ⋯ 메뉴(제목 변경 /
  삭제). 모바일(≤760px)에선 사이드바가 상단으로 접힘. 최근 활동순 정렬.
- **자동 제목** — 새 채팅은 "New chat"; 첫 사용자 프롬프트의 첫 줄(최대 40자)을
  목록 제목으로 자동 표시. ⋯ 메뉴에서 수동 제목 지정도 가능(빈 값이면 자동 복귀).
- **신규 SSR 라우트** — `POST /chat/new`(생성), `POST /chat/{id}/rename`(제목),
  `POST /chat/{id}/delete`(삭제, 진행 중 세션 종료 + cascade 정리). `GET /chat?c=<id>`
  로 특정 채팅 열기. 프롬프트/취소/새 세션 API(`/api/projects/{id}/claude/console/*`)
  는 활성 채팅의 ghost projectId 를 그대로 받아 재사용(Wire change 없음).
- 신규: `ProjectService.createChat/listChats/ensureChat/renameChat/deleteChat`,
  `ProjectService.ChatSummary`, `ProjectService.isChatGhost/isGhost`,
  `WorkspacePath.isGhostId`/`CHAT_PREFIX`, `ProjectRepository.updateName`,
  `ConversationTurnRepository.firstUserContent/lastTs`,
  `WebProjectTemplates.chatSidebar` + `consolePage(chatSidebar, chatTitle)`.

### Changed

- ghost 프로젝트 제외 로직을 `SCRATCH_ID` 단건 비교에서 `isGhost(id)`
  (scratch + 모든 `__chat_*`)로 통일 — 프로젝트 목록(`list`) / 대시보드
  projectCount(`StatusService`) / Prometheus `vibe_projects_total` 게이지 /
  sub-agent·console redirect 가 채팅 ghost 도 일괄 제외/처리.
- 기존 `__scratch__` 대화는 마이그레이션하지 않음(운영자 결정) — 부팅 시 여전히
  bootstrap 되지만 `/chat` 진입점은 다중 채팅 목록으로 대체. 채팅이 하나도 없으면
  첫 진입 시 자동으로 1개 생성.

### Wire change

- 없음(No). 신규 라우트는 모두 SSR(HTML form) — `shared/ApiPath`·DTO 변경 없음.
  기존 콘솔 prompt/cancel/new JSON API 를 ghost projectId 로 재사용.

## [1.53.1] - 2026-05-31 — 프로젝트 탭 iframe 안 "프로젝트로 돌아가기" 중첩 수정

### Fixed

- **프로젝트 탭 안에 프로젝트 페이지가 또 중첩되던 버그** — 빌드(`/builds`)·
  파일(`/tree`)·Git(`/git`) sub-page 는 `/projects/{id}` 통합 탭 페이지(`ProjectTabsTemplate`)
  의 `<iframe>` 안에 로드된다. 이 세 페이지의 "← 프로젝트로"(`builds.back`) /
  "← 뒤로"(`files.back` / `git.back`) 링크가 `/projects/{id}` (= 탭 페이지 자체)를
  가리켜, iframe 안에서 클릭하면 iframe **안에 전체 탭 페이지가 통째로 다시
  렌더링**되어 "프로젝트 페이지 안에 프로젝트 페이지" 가 생기는 시각적 중첩이
  발생했다. 세 링크에 `target="_top"` 을 추가해 최상위 윈도우에서 이동하도록
  수정 → iframe 중첩 대신 정상적으로 프로젝트 탭 페이지로 전환된다. 직접 URL
  진입(북마크) 시에는 `_top` 이 자기 자신이라 동작이 동일하다.
  (`WebProjectTemplates.kt` 의 builds/files/git 템플릿.)

## [1.53.0] - 2026-05-30 — 프로젝트 목록 상태칩 (응답중/대기중/유휴)

프로젝트 목록(`/projects`)에서 각 프로젝트의 현재 Claude 세션 상태를 한눈에 볼 수
있도록 상태칩을 추가. 콘솔에 들어가지 않아도 어느 프로젝트가 지금 응답 중인지
목록에서 바로 확인 가능.

### Added

- **프로젝트 목록 상태칩** — `/projects` 테이블 **제일 왼쪽**에 상태 컬럼 추가.
  세 가지 상태를 색·점·라벨로 구분:
  - **응답중**(responding, 주황 + pulse 애니메이션) — Claude 가 현재 turn 응답 생성 중
    (`ClaudeSessionManager.isBusy(id)`).
  - **대기중**(ready, 초록) — 세션 프로세스 활성·입력 대기 (`isAlive(id)`).
  - **유휴**(idle, 회색) — 세션 없음(미기동 또는 종료, 다음 prompt 시 `--resume`).
  - 우선순위 responding > ready > idle. 목록 진입 시 SSR snapshot.
- **실시간 갱신** — 목록 페이지가 기존 `/ws/projects` (단방향, v1.3.0 인프라) 를 구독해
  `ProjectBusyChanged` frame 으로 새로고침 없이 responding↔ready 칩을 patch. 인증은
  handshake 의 `vibe_session` 쿠키로 자동 처리(콘솔 WS 와 동일, auth frame 불필요).
  재연결 시 5초 backoff. `prefers-reduced-motion` 존중.

### 파일

- `admin/WebProjectRoutes.kt` — `get("/projects")` 에서 `sessionManager` 로 프로젝트별
  상태 계산 후 `projectsPage(statuses=...)` 전달.
- `admin/WebProjectTemplates.kt` — `projectsPage` 시그니처에 `statuses` 추가, 상태 컬럼
  헤더 + 행 칩(`data-pid`/`data-state`) 렌더, `/ws/projects` 구독 스크립트.
- `static/admin/admin.css` — `.pstat` / `.pstat-responding|ready|idle` + `pstat-pulse`.
- `i18n/MessagesKo.kt`·`MessagesEn.kt` — `projects.list.col.status` + `projects.status.*` 3종.

### Notes

- Wire change 없음(기존 `ProjectBusyChanged` / `/ws/projects` 재사용). Android `shared/`
  동기 불필요.
- 기존 프로젝트/데이터 마이그레이션 영향 없음(읽기 전용 상태 조회).

## [1.52.0] - 2026-05-30 — 보안 하드닝 릴리스 (쿠키 Secure / idle timeout / quota 인증)

직전 25차 보안점검의 "코드 릴리스 필요" 권고를 회수. 외부 노출(vibe.wody.work) 서버 보안 강화.

### Security

- **세션 쿠키 Secure 플래그** — `setSessionCookie`/`clearSessionCookie` 에 `secure =
  (origin.scheme == https)` 추가. openresty(https) 뒤에선 쿠키가 **HTTPS 로만 전송**(http://host:17880
  직접 접근 시 평문 노출 방지). trustForwardedFor=true 라 X-Forwarded-Proto 로 scheme 감지. LAN http
  직접 접근 시엔 secure=false 라 정상 동작. (SameSite=Lax·HttpOnly 는 기존 유지.)
- **`/api/server/quota` 인증 요구** — 미인증 노출(model/plan/usage% 정보 누설)을 `authenticate(AUTH_BEARER)`
  + `requireDevice()` 로 회수. 사이드바 pill 은 `credentials:'same-origin'` 쿠키로 인증되어 정상 동작.
- **세션 idle timeout env override** — `VIBECODER_SECURITY_SESSION_IDLE_TIMEOUT_MINUTES` 추가
  (server.yml 기본 0=무제한 유지, compose env 로 조정). 미사용/탈취 토큰 수명 제한. compose.yml 에
  매핑 추가. **운영은 720분(12h) 적용**(.env).

### Notes

- Android 클라이언트(Bearer)는 응답 body 토큰 사용이라 쿠키 변경 영향 없음. 단 idle timeout 720분은
  12h 무요청 시 재로그인 — 불편 시 .env 값 상향(예: 4320) 또는 0.
- 미적용(운영자 액션): admin 2FA/passkey 활성화(`/2fa`·`/webauthn`) — 외부 노출 단일 계정 최우선 권고.

## [1.51.0] - 2026-05-30 — 25차 전체 코드 정밀점검 회수 (XSS / symlink write-escape / 프로세스)

6개 도메인 병렬 점검. v1.45.0(멀티유저 제거)·v1.46.0(ClaudeStatusService) 델타는 **회귀 0건 PASS**.
DB/repo·동시성 대부분 PASS. 확정 결함 XSS 3 / 경로안전 3 / 프로세스 2 회수.

### Security

- **[Bug] onclick JS 컨텍스트 XSS (escJs 누락)** — `AgentRoutes`(agent 삭제 confirm), `PluginTemplates`
  (plugin/marketplace 삭제 confirm)가 이름을 `esc()`(HTML 엔티티)만 적용 → `onclick` 안 JS 문자열에서
  HTML 디코드 후 `'` 로 JS 탈출 가능. 이름이 **디스크 스캔(agent `*.md` 파일명) / untrusted
  marketplace 메타데이터**라 미검증. `esc(escJs(...))` 로 회수(21차 ScopedManager 패턴과 정렬).
- **[Bug] 중간 디렉토리 symlink write/read escape (assertRealInside 누락)** — v1.43.0 의 symlink
  회수가 read/upload/safeWriteTarget 에만 적용되고 **write()/move·copy 목적지/download-zip** 은
  누락. `<root>/evil -> /외부` 디렉토리 symlink 가 있으면 워크스페이스 밖 write(편집/이동/복사) 또는
  zip 유출 가능. 4개 경로에 `PathSafety.assertRealInside` 추가. (단일 admin+CSRF 뒤지만 신뢰불가
  git clone / Claude 생성 symlink 가 실주입 경로.)

### Fixed

- **[Bug] PluginService.runJson stderr 미배수** — `claude … --json` 을 `redirectErrorStream(false)`
  +stdout-only readText 로 실행 → stderr 64KB 초과 시 파이프 포화로 readText 가 watchdog
  destroyForcibly(30s)까지 블록(매번 30s 지연 + plugin 목록 null). stdout(JSON) 보존 위해 merge 대신
  `redirectError(DISCARD)`. (자매 runStreaming 과 비대칭 해소.)
- **[Minor] shutdown hook 격리** — `ServerMain` 의 `sessionManager.shutdown()`/`subAgentManager.shutdown()`
  만 `runCatching` 미적용 → 예외 시 이후 terminal/queue/hub cleanup 누락(누수). 나머지와 동일하게 격리.

### Notes

- 델타 PASS: WebSession/DevicePrincipal isAdmin/canWrite 상수, WsRoutes 게이팅 제거, ProjectService
  단일 transaction delete, ClaudeStatusService cachedSnapshot/single-flight/runWithHardTimeout 모두 정상.
- SubAgentRoutes:297 의 `isAdmin=false` 하드코딩은 ACL 빈 상태라 항상 통과 — 기능 결함 아님(일관성만).

## [1.50.2] - 2026-05-30 — Overview rail 카드 하단 빈 여백 제거

히스토리 카드의 `flex:1` 이 rail 의 남는 세로 공간을 채워 카드가 길게 늘어나며 하단에 빈 여백이
생기던 문제 → `flex:0 0 auto` 로 카드를 **내용 높이**로. rail 하단 빈 공간은 카드가 아닌 배경.
히스토리 항목이 많으면 list 자체 `max-height:50vh` 로만 스크롤. (`ProjectTabsTemplate.kt` CSS)

## [1.50.1] - 2026-05-30 — Overview 접기 토글을 rail 세로 중앙으로 이동

토글 버튼(⟩/⟨)이 `top:8px` 라 콘솔 헤더 우측의 "새 세션" 버튼과 겹쳐 오클릭 위험이 있었음 →
`top:50% + translateY(-50%)` 로 rail 세로 중앙 배치(높이 48px + 그림자로 식별성 향상).
접힘 상태에서도 중앙 유지. (`ProjectTabsTemplate.kt` CSS)

## [1.50.0] - 2026-05-30 — 프로젝트 우측 고정 Overview rail + 좌측정렬 + 프롬프트 히스토리

프로젝트 페이지를 좌측정렬하고, 우측에 **스크롤 영향 없는 고정 overview rail**을 모든 탭 공통으로
추가. 스크롤바는 디스플레이 최우측 유지(iframe 풀폭 + 콘텐츠 우측 패딩).

### Added

- **우측 Overview rail (모든 탭 공통, 고정)** — 부모 레이어라 좌측 페이지(iframe)만 스크롤되고
  rail 은 고정. 카드:
  - **개요**: 프로젝트명 / 패키지 / 키스토어 준비상태(✓/✗) / 토큰 사용량(K·M, 캐시적중률) /
    프롬프트 송신 횟수.
  - **프롬프트 히스토리**: 최신순 최대 7개, 2줄 축약, 6·7번째는 점점 흐리게. **클릭 → 콘솔 탭
    전환 + 프롬프트 입력창(`#prompt-input`)에 자동 입력.**
- **좌측정렬**: iframe 내부 `.content` 를 `justify-self:center → start` + `max-width:none` 로
  좌측정렬(이전엔 중앙정렬).
- **동적 폭**: rail 폭 `clamp(248px, 22vw, 360px)` (디스플레이 크기 반응형). `project-tabs.js`
  가 rail 실제 폭을 측정해 iframe `.content` 우측 패딩에 주입 + `resize` 시 재계산. 좁은 화면
  (≤760px)은 rail 자동 숨김.
- **숨기기/펼치기 토글** — rail 좌측 손잡이(⟩/⟨). 숨기면 패딩 제거 → 좌측 화면 전체 확장.
  선호는 localStorage(`vibe.projectRail`)에 전역 저장(프로젝트 공통).

(`ProjectTabsTemplate.kt`, `WebProjectRoutes.kt`, `static/admin/project-tabs.js`,
`ConsoleDtos`/i18n `tabs.rail.*`)

## [1.49.0] - 2026-05-30 — 헤더 프로젝트명 클릭 → 프로젝트 전환 콤보박스

프로젝트 페이지 상단의 프로젝트명을 클릭하면 다른 프로젝트로 바로 이동할 수 있는 드롭다운
(콤보박스)을 추가. 매번 목록으로 나갔다 들어올 필요 없이 빠르게 프로젝트 전환.

### Added

- `/projects/{id}` 헤더의 프로젝트명이 `<details>` 콤보박스로 동작 — 클릭 시 전체 프로젝트
  목록이 드롭다운으로 펼쳐지고, 항목 선택 시 해당 프로젝트 탭 페이지로 이동. 현재 프로젝트는
  active 표시. 상단에 **필터 입력**(이름/ID 즉시 검색), **Enter** 로 첫 매치 이동, **Esc**/
  바깥 클릭으로 닫기. 하단에 "전체 목록 / 새 프로젝트" 링크.
- 라우트가 `projects.listForUser(...)` 로 전체 목록을 템플릿에 전달. i18n 키
  `tabs.switch.{title,filter,all}`(en/ko) 추가.

(`ProjectTabsTemplate.kt`, `WebProjectRoutes.kt`, `static/admin/project-tabs.js`)

## [1.48.0] - 2026-05-30 — 콘솔 세션 카드 제거 + 액션을 헤더로 이동

프로젝트 콘솔(`/projects/{id}/console`) 상단의 **세션 카드** 우측 버블 버튼 대부분(빌드/히스토리/
파일/Git/심볼/에이전트)이 v1.11.0 프로젝트 탭 도입으로 **상단 탭과 중복**됐다. 정리.

### Changed

- **세션 카드 제거**: 별도 카드 블록을 없애고, 세션 상태(running/idle/no session + sessionId)와
  남은 액션(■ 중지 / 새 세션)을 **헤더 우측**(탭 바로 밑 헤더)으로 이동 — 화면 상단 공간 절약.
- **중복 nav chip 제거**: 빌드/히스토리/파일/Git/심볼/에이전트 chip 은 모두 상단 프로젝트 탭으로
  대체돼 콘솔에서 제거. (일반 Chat `/chat` 은 탭 바깥 독립 페이지라 history 링크만 유지.)
- 유지: ■ 중지(turn cancel), 새 세션, busy-badge(전송 버튼 라인), 메시지 필터, 프롬프트 폼.
  JS 셀렉터(`#stop-btn`/`#busy-badge`/new-session form)는 그대로라 동작 변화 없음.

(`WebProjectTemplates.consolePage` — `WebProjectTemplates.kt`)

## [1.47.0] - 2026-05-30 — 프로젝트 진입 시 콘솔 우선 로딩 + 나머지 탭 백그라운드 프리로딩

프로젝트 페이지(`/projects/{id}`)는 17개 탭을 iframe 으로 운영하는데, 이전엔 진입 시
**17개 iframe 을 모두 `loading="eager"` 로 동시 fetch** → 콘솔이 나머지 16개와 연결/대역폭/CPU
를 경쟁해 표시가 느렸다(코드 주석도 인정). 콘솔을 가장 빠르게 보이도록 개선.

### Changed

- **콘솔 우선**: 콘솔 iframe 만 HTML 파싱 시점에 즉시 fetch (`src` + `loading="eager"` +
  `fetchpriority="high"`). JS 초기화를 기다리지 않고 가장 먼저 로드.
- **나머지 탭 지연**: 나머지 16개 iframe 은 `src` 대신 `data-src` 로 렌더 → 진입 시 fetch 안 함
  (콘솔과 자원 경쟁 제거).
- **백그라운드 프리로딩**: `project-tabs.js` 가 콘솔 `load` 완료 후(`requestIdleCallback`)
  `data-src` 들을 **한 번에 하나씩 순차**로 로드 → 탭 전환은 여전히 즉시(이미 워밍업)이되 콘솔을
  방해하지 않음.
- **on-demand 폴백**: 프리로딩이 도달하기 전 사용자가 탭을 클릭하면 `activate()` 가 그 iframe 을
  즉시 로드 → 어떤 경우에도 해당 프레임 1개 외 대기 없음.

기존 탭 전환 즉시성 / WebSocket 백그라운드 유지 / inner-nav cleanup / localStorage 기억은
그대로. (`ProjectTabsTemplate.kt`, `static/admin/project-tabs.js`)

## [1.46.0] - 2026-05-30 — quota/usage 캡처 hang 근본 수정 (비차단 + 프로세스트리 하드킬)

운영 점검 중 `/api/server/quota` 가 25~80s 블로킹(HTTP 000 timeout)되고 멈춘 `expect`+`claude`
TUI 프로세스가 누적되는 문제를 발견 → 근본 회수.

### Fixed

- **근본 원인 1 — read-before-timeout 데드락**: `ClaudeStatusService.runUsageCapture` /
  `runInitFrame` 이 `readText()`/`readLine()` 을 `waitFor()` **이전**에 호출 → expect 의 자식
  `claude` TUI 가 stdout 을 닫지 않으면 읽기가 무한 블록(타임아웃 무력화). `claude --print
  /status` 가 최신 CLI 에서 제거("/status isn't available")되어 TUI 캡처로 폴백하면서 노출.
  → `runWithHardTimeout` 헬퍼(별도 pump thread + `waitFor`/`stopWhen` 타임아웃 +
  `ProcessHandle.descendants().destroyForcibly()` **프로세스 트리 전체 종료**)로 재작성.
  expect 손자(claude TUI)까지 죽여 **orphan 누적 0**.
- **근본 원인 2 — quota 가 동기 캡처 호출**: `/api/server/quota` 와
  `/api/projects/{id}/claude/status` 가 캐시 미스 시 느린 TUI 캡처를 **요청 경로에서 동기
  실행** → 요청 통째로 hang. → `ClaudeStatusService.cachedSnapshot()`(비차단, spawn 없음, 마지막
  캐시 + account-global usage 는 scratch 캐시 폴백 + 실시간 busy/alive 오버레이) 추가, 두
  endpoint 가 이를 사용. 실제 캡처는 백그라운드 `ClaudeUsageMonitor`(기본 5분)만 수행.
- **single-flight 가드**: `snapshot()` 에 `capturing` 맵 추가 → 같은 project 의 동시/중첩
  캡처가 프로세스를 누적 spawn 하지 않음.

### Notes

- 사용자 체감: 사이드바 usage pill / Android quota 폴링이 **항상 즉시 응답**(stale-while-revalidate).
  usage 수치는 백그라운드에서 5분 주기로 갱신.
- 운영 점검 시 멈춰 있던 `claude-usage-capture.exp`/TUI 프로세스는 수동 정리함. 재발 방지는
  본 릴리스의 트리 하드킬 + 비차단화로 코드 레벨 해결.

## [1.45.0] - 2026-05-30 — 멀티유저/역할/ACL 제거 (단일 admin 화)

CLAUDE.md §1 "단일 사용자" 원칙과 모순되던 멀티유저 스캐폴딩을 전면 제거. 운영 DB 실측
admin 1명·member/viewer 0명·project_acls 0건으로 **한 번도 사용되지 않았고**, 23·24차
정밀점검에서 viewer 가드 결함이 반복 발생한 진원지였음. 인증·토큰·2FA·passkey 는 유지
(외부 노출 보안 필수).

### Removed

- **유저 관리 UI/API**: `UsersRoutes`(SSR `/users`), `ProjectAclRoutes`(`/users/{id}/projects`),
  JSON 멀티유저 API(`GET/POST /api/users`, `POST .../role`, `DELETE .../{id}`). 설정 탭에서
  `Users` 제거.
- **역할 개념(admin/member/viewer) + Project ACL 게이팅**: SSR/JSON/WS 의 role·viewer·ACL
  검사 로직 제거. WS 핸드셰이크(console/build/sub-agent)의 per-request role 조회·viewer 프롬프트
  차단·ACL 검사 삭제.

### Changed

- `WebSession.isAdmin/canWrite`, `DevicePrincipal.isAdmin/canWrite` → **항상 true**(인증된
  세션은 곧 admin). `ProjectService.canUserAccess` → 항상 true. 기존 가드 함수
  (`requireAdminOrRedirect`/`requireWriteAccessOrRedirect`/`requireApiWrite`/`requireApiAdmin`/
  `requireProjectAcl`/`requireProjectAccessOrThrow`)는 호출부 49곳 그대로 두되 inert
  pass-through 로 동작(회귀 최소화). `installAuth` 의 요청별 role DB 조회 제거.
- DB 스키마(`admin_users.role` 컬럼, `project_acls` 테이블)는 **보존**(마이그레이션 없음,
  하위호환). role 은 항상 "admin", ACL 은 비어 있음.

### Notes

- **Breaking(엄밀히)**: `/api/users*` 공개 endpoint 제거. 단 §1 단일 사용자 도구라 실사용
  영향 0(Android 클라이언트도 운영자 본인). SemVer "애매 시 한 단계 낮춤"에 따라 MINOR 처리.
- 재도입은 §1 성격이 멀티테넌트로 전환될 때만. 그 전까지 새 mutation 라우트는 인증+CSRF 만
  확인하면 충분(역할/ACL 가드 불필요).

## [1.44.0] - 2026-05-30 — 24차 전체 코드 정밀점검 회수 (권한/입력검증/원자성)

v1.43.0(23차) 변경분 회귀 검증(전부 PASS, 회귀 0건) + 새 차원 5개(입력검증·DoS / 에러·정보노출
/ 동시성 심화 / 미점검 SSR 라우트 / wire·DTO·ApiPath SSOT) 병렬 점검. 확정 결함 Bug 1 /
Minor 2 + 일관성 2건 회수.

### Security

- **[Bug] Prompt 템플릿 SSR mutation viewer 가드 누락** — `POST /prompts`, `/prompts/{id}/update`,
  `/prompts/{id}/delete` 가 `requireWriteAccessOrRedirect` 없이 session+CSRF 만 검사 → viewer
  역할이 workspace 전역 프롬프트 템플릿을 생성/수정/삭제 가능. 3개 핸들러에 write 가드 추가.
  (23차 SubAgent Bearer viewer 가드와 동일 계열.) (`PromptRoutes.kt`)

### Fixed

- **[Minor] EnvFilesRoutes body 크기 char/byte 혼동** — `body.length`(UTF-16 char)를 byte
  한도와 비교 → 멀티바이트 본문이 한도의 ~3배까지 통과. `toByteArray(UTF_8).size` 로 정정
  (동종 핸들러 5개와 정렬). (`EnvFilesRoutes.kt`)
- **[Minor] GradleBuilder probe exitValue race** — `destroyForcibly()` 직후 `exitValue()`
  가 IllegalThreadStateException 던질 수 있던 분기를 `waitFor` 성공 시에만 exitValue 호출하도록
  분리(이전엔 catch 로 흡수돼 무해했으나 의도 명확화). (`GradleBuilder.kt`)

### Changed

- **원자적 쓰기 패턴 통일** — `McpService.writeMcpJsonAtomic` / `GitCredentialStore.writeJsonAtomic`
  의 `Files.move` 에 `ATOMIC_MOVE` + `AtomicMoveNotSupportedException` fallback 추가(메서드명과
  동작 일치, ConfigPersistence gold-standard 와 정렬). Linux 배포 타깃에선 기존에도 무손상이라
  기능 변화 없음 — 패턴 일관성 개선.

### Notes

- 회귀 검증 PASS: ProjectService 단일 transaction(Exposed 중첩 transaction 재사용 확인),
  PathSafety.assertRealInside, 프로세스 DISCARD 5곳, SSH 키/SubAgent 인증 래핑 모두 정상.
- 미수정(저위험, 단일 사용자 LAN 도구 맥락): GitCredentialStore read-modify-write 락 부재
  (admin 순차 클릭만 트리거), StatusPages/EnvSetup 의 operator-facing 진단 메시지(e.message),
  ClaudeStatusService 캐시 stampede. ApiPath SSOT / DTO wire / WsFrame 은 drift 0건 PASS.

## [1.43.0] - 2026-05-29 — 23차 전체 코드 정밀점검 회수 (보안/FK/프로세스/경로)

전체 코드(197 파일, ~44K LoC)를 6개 도메인(인증·인가/경로안전/신규코드/프로세스동시성/SSR
XSS/DB·repo) 병렬 정밀점검. 확정 결함 **Critical 2 / Bug 3 / Minor 4** 회수. 핵심 인증
인프라·SSR XSS escape·webhook 암호화 등 대부분 영역은 PASS(회귀 없음).

### Security

- **[Critical] SSH 키 JSON endpoint 무인증 회수** — `GET/POST /api/server/ssh-key[/regenerate]`
  이 `authenticate` 밖에 등록되어 **인증·CSRF 없이** 외부에서 호출 가능했음(POST regenerate 로
  deploy key 무력화 → git remote 연동 DoS). `authenticate(AUTH_BEARER)` + `requireApiAdmin()`
  으로 감쌈. SSR 짝 라우트(cookie+admin+CSRF)와 정렬. (`SshKeyRoutes.kt`)
- **[Bug] sub-agent Bearer 분기 viewer write 차단 누락 (권한 상승)** — `authorizeAgentJson`
  의 Bearer 경로가 `requireWrite` 를 무시해 viewer 토큰으로 console prompt/cancel/new mutation
  통과. cookie 분기와 동일하게 `userRepo.findById(userId).canWrite` 검사 → viewer 403.
  (`SubAgentRoutes.kt`) — multi-user 환경 회귀.
- **[Bug] `GET /api/git-integrations` admin 가드 누락** — 같은 라우터 mutation 들과 달리
  `requireApiAdmin()` 없어 member/viewer 가 git 통합 메타데이터 조회 가능. 추가. (`EnvSetupApiRoutes.kt`)
- **[Bug] 중간 디렉토리 symlink 로 워크스페이스 탈출** — `PathSafety.normalizeAndCheck` 는
  어휘적 검증만 해서 `<root>/evil -> /etc` 같은 디렉토리 symlink 가 있으면 `evil/passwd` 가
  통과(실제 `/etc/passwd` 노출). `PathSafety.assertRealInside()`(real path 컨테인먼트, 미존재
  경로는 존재 조상으로 검사, root 내부 symlink 는 허용) 추가 + `ProjectFileBrowser`
  read/list/raw/write/upload choke point 에 적용. 회귀 테스트 3건 추가. (`PathSafety.kt`,
  `ProjectFileBrowser.kt`)

### Fixed

- **[Critical] 프로젝트 삭제 FK violation + 비원자성** — `ProjectService.delete` 가
  BuildSchedules/BuildWebhookSecrets/ProjectAcls 를 정리하지 않아(해당 row 존재 시) FK
  violation 으로 삭제 자체 실패. 또 자식 정리가 분리 transaction 이라 부분 실패 시 데이터
  유실. 누락 3개 테이블 정리 추가 + 전체를 **단일 transaction** 으로 묶어 원자성 확보.
  (모든 `references(Projects.id)` 가 onDelete CASCADE 미설정임을 확인.) (`ProjectService.kt`,
  `ServerMain.kt`)
- **[Bug×2/Minor×3] 자식 프로세스 stderr 미배수 데드락/누수** — `redirectErrorStream(false)`
  +stdout-only readText 패턴이 자식의 대량 stderr 출력 시 파이프 포화로 데드락(좀비+스레드
  누수). stdout 파싱이 필요한 곳은 **stderr DISCARD**, 출력 불필요한 곳은 stdout/stderr 모두
  DISCARD 로 회수: `GitReader`(status/diff/log), `GitCloneService`(ssh-keyscan),
  `GradleBuilder`(gradle --version probe), `ClaudeTokenRefresher`, `ClaudeStatusService`(usage).
- **[Minor] AgentRegistry body 크기 검증 무효 분기 + char/byte 혼동** — `take(MAX_BODY_BYTES)`
  로 char 기준 자른 뒤 length 검사라 항상 false(검증 불능). UTF-8 byte 기준 검증으로 정정
  (자르지 않고 초과 시 거부). (`AgentRegistry.kt`)

### Notes

- McpService bootstrap 주석을 실제 기본 MCP 5개(fetch/memory/sequential-thinking/context7/
  playwright, [McpCatalog.defaultInstallIds] 단일 출처)와 정합되도록 갱신.
- 보고됐으나 미수정(저위험, 후속): Terminal WS Origin fail-open(이후 cookie+admin 강제로
  실익 낮음), EnvFilesRoutes projectRoot 미존재 시 빈 디렉토리 생성, PushSubscription
  SELECT-then-INSERT race(단일 사용자 환경).

## [1.42.0] - 2026-05-29 — 언어별(EN/KO) 전역 CLAUDE.md + 언어 변경 시 자동 적용

전역 CLAUDE.md 시드를 영문/한글 두 버전으로 분리하고, 각 템플릿에 **응답 언어 지시**를 포함.
설정에서 언어를 바꾸면(영어 기본 선택 시 영어로 설명) 해당 언어 템플릿이 자동 적용된다.

### Added

- 시드 템플릿 2종: `templates/container-global-claude.en.md` (English, "Always respond in
  English") + `container-global-claude.ko.md` (한국어 존댓말). 기존 language-neutral
  `container-global-claude.md` 는 제거.
- `GlobalClaudeMdService.seedDefaultIfAbsent(lang)` — 서버 기본 언어(`i18n.defaultLanguage`)
  템플릿으로 first-run 시드.
- `GlobalClaudeMdService.applyLanguage(lang)` — `/settings/language` 변경 시 호출.
  **현재 파일이 미편집 시드(en/ko 어느 한쪽과 내용 정확히 일치)일 때만** 해당 언어 시드로
  교체(`.bak` 백업). 사용자가 직접 편집한 경우 `SKIPPED_EDITED` 로 **보존**하고 안내 문구
  표시(i18n `settings.general.language.claudemdEdited`).

### Changed

- `AdminRoutesDeps` 에 `globalClaudeMd` 주입. `/settings/language` POST 가 언어 저장 후
  전역 CLAUDE.md 자동 적용을 시도.

### Notes

- **기존 환경 영향 없음**: 이미 CLAUDE.md 가 있고 편집된 볼륨은 절대 덮어쓰지 않음. 미편집
  시드 상태에서만 언어 전환이 반영됨.

## [1.41.0] - 2026-05-29 — 전역 컨테이너 CLAUDE.md 시드 템플릿 (버전-무관 gradle 정책)

신규 도커 설치 시 전역 `~/.claude/CLAUDE.md` 가 비어 있으면, 빌드환경 경로 + **버전을 하드코딩
하지 않는 동적 gradle 정책**을 담은 기본 템플릿을 자동 시드. gradle 이 업그레이드돼도 문서 수정
없이 Claude 가 `gradle --version` / wrapper 캐시를 조회해 대응.

### Added

- 번들 리소스 `server/src/main/resources/templates/container-global-claude.md` — 컨테이너
  빌드환경 도구 경로표(gradle/SDK/JDK/Node/playwright) + **버전 무관 gradle 정책**(설치본·캐시
  조회 후 `distributionUrl` 정렬) + Android SDK/env/금지 규칙.
- `GlobalClaudeMdService.seedDefaultIfAbsent()` — 서버 시작 시 **파일이 없을 때만** 시드.
  ServerMain 에서 호출. 리소스 누락/쓰기 실패해도 startup 비차단.
- **기존 환경 영향 없음**: 이미 CLAUDE.md 가 있는 볼륨(운영자/사용자 편집본)은 **절대 덮어쓰지
  않음**(notExists 가드). `/settings/claude-md` 에서 자유 편집 가능.

운영 노트: 기존 운영 서버의 전역 CLAUDE.md 도 이번에 `gradle 8.7` 하드코딩 → 버전-무관(런타임
`gradle --version` 조회) 정책으로 정정함(실제 설치본은 9.5.1 이었음).

## [1.40.2] - 2026-05-29 — 기본 MCP 에 context7 + playwright 추가

무인증·무설정으로 설치 즉시 쓰는 MCP 중 context7(최신 라이브러리 docs)·playwright(브라우저
자동화)를 기본 설치 대상에 추가. 이제 `defaultInstall` = fetch / memory / sequential-thinking /
**context7 / playwright** 5종.

### Changed

- `McpCatalog` — context7, playwright 에 `defaultInstall = true`. 신규 도커 first-run 시 5종이
  `.mcp.json` 에 자동 등록되고, `/env-setup/mcp` 카탈로그에서 기본 선택됨.
- npm install 없이 등록만 하므로 부팅 비용 없음(`npx -y` 자동 fetch). playwright 의 Chromium 은
  실제 브라우저 사용 시점에 lazy 다운로드.
- **기존 환경 영향 없음**: 이미 부트스트랩 marker 가 있는 볼륨(기존 운영 서버)은 자동 재등록을
  하지 않는다(사용자 선택 보존). 기존 서버에 추가하려면 `/env-setup/mcp` 에서 선택·설치하거나
  `.mcp.json` 에 직접 추가.

## [1.40.1] - 2026-05-29 — 22차 점검 회수 (이번 세션 신규기능 7건: XSS/프로세스 timeout/하드닝)

v1.34.5~v1.40.0 신규/변경분 멀티에이전트 정밀점검(6 도메인). **Critical 0 / Bug 1 / Minor 6,
오탐 0.** 21차에서 발견된 systemic 인가 누락이 신규 라우트엔 0건(전역=admin, 프로젝트=ACL/write
가드 모두 적용 확인). first-run 부트스트랩 marker·logcat WS 인증·SkillRegistry 재귀삭제 traversal
가드·에뮬레이터 제거 완전성 모두 통과. 발견 7건 일괄 회수.

### Fixed

- **B1 (`PluginService.runStreaming` 프로세스/스레드 누수)**: stdout 펌프(useLines)가 timeout
  검사보다 선행해, 자식(`claude plugin`)이 stdout 연 채 멈추면 `waitFor(5분)` 에 도달 못 해
  destroyForcibly 가 안 불렸다 → 좀비 + IO 스레드 누수. read 를 별도 pump 코루틴으로 분리 +
  `withTimeoutOrNull{ runInterruptible{ waitFor() } }` + stdin `/dev/null` 차단으로 교정.
- **M4 (`PluginService.runJson` 동일 패턴)**: readText 가 30s timeout 선행 → GET 렌더 코루틴
  block 가능. watchdog 데몬(timeout 후 destroyForcibly)으로 read block 해제 + stdin 차단.
- **M2 (`ScopedManagerTemplates` onclick JS-문자열 XSS)**: 삭제 confirm 인자에 HTML escape 만
  적용 → `&#39;` 이중디코딩으로 JS 탈출 가능(스킬/에이전트 이름은 디스크 스캔 raw). `escJs()`
  도입 + `esc(escJs(name))` 적용(코드베이스 jsLit/escJs 표준과 정합).
- **M6 (`AdbTemplates` onclick `adbLog('serial')` 동일 XSS)**: `esc(escJs(serial))` 적용 +
  `AdbService.devices()` 가 `serialRe` 미통과 serial 제외(defense-in-depth).
- **M5 (`PluginService` 인자 검증 누락)**: plugin id(`[A-Za-z0-9._-]+(@…)?`)·marketplace name
  정규식 + dash-시작/빈 값 거부 → CLI flag 주입(`--scope` 등) 차단. AdbService 정규식 정책과 통일.
- **M3 (`ScopedManagerTemplates` URL 경로 미인코딩)**: edit/delete 경로 세그먼트의 이름을
  `encPath()`(URLEncoder)로 인코딩 → 특수문자 이름의 깨진 링크/404 방지.
- **M1 (`GlobalClaudeMdService` sidecar 파일명 하드코딩)**: `.bak`/`.tmp` 를 target 파일명에서
  파생(`<name>.bak`/`<name>.tmp`) → `VIBECODER_GLOBAL_CLAUDEMD_PATH` override 시 백업명 일치.

SSR/내부 한정 — `ApiPath`/shared 변경 없음. clean + --no-build-cache --rerun-tasks 컴파일 통과.

## [1.40.0] - 2026-05-29 — 무선 ADB 기기 logcat (브라우저 스트림, admin)

같은 Wi-Fi(공유기)에서 안드로이드 11+ 폰의 **무선 디버깅**에 연결해 **logcat 을 브라우저로
실시간 스트림**. 빌드한 앱을 폰에 설치한 뒤 이상동작 로그를 원격(브라우저)에서 확인.

### Added

- **`/adb`** (도구 탭 "기기 로그", admin 전용) — ① 자동 탐지(HOST 네트워크 mDNS) → 클릭 연결,
  ② 수동 페어링/연결(IP:포트 + 6자리 코드 — HOST 기피자/bridge 환경용 대안), ③ 연결 기기 목록
  (해제/logcat), ④ **logcat 뷰어**(패키지 필터 `--pid`, 텍스트 필터, clear, 자동 스크롤).
- **`AdbService`** — adb 자동 감지(ANDROID_HOME/platform-tools), `adb mdns services`(탐지) /
  `pair` / `connect` / `disconnect` / `devices -l` / `logcat` 프로세스. 모든 인자 strict 검증
  (IP:포트/코드/serial/패키지) + ProcessBuilder list-args(셸 미경유). adb 없으면 안내 페이지.
- **logcat WS 스트림** — `WS /ws/adb/logcat?serial=&pkg=` (쿠키 admin 검증 + Origin), 단방향
  텍스트 라인. 연결 종료 시 logcat 프로세스 destroy. (터미널 PTH→WS 패턴 재사용)
- **사이드바 뱃지** — `/api/adb/status`(저민감, 무인증) 30초 폴링 → 연결 기기 수(📱 N) 표시,
  0대면 hidden. 클릭 시 `/adb`.
- **HOST 네트워크 오버레이** — `docker/compose.host.yml`(Linux): mDNS 자동 탐지를 위해 서버를
  호스트 네트워크에 붙이고 DB host 를 127.0.0.1 로 전환(+postgres 루프백 노출). 적용:
  `docker compose -f compose.yml -f compose.host.yml up -d`. (기본 compose 는 bridge 유지 —
  수동 IP:포트 연결만 가능, 자동 탐지는 HOST 일 때만.)

### Wire change

- `shared/ApiPath.kt` — `ADB_STATUS`, `WS_ADB_LOGCAT` 추가. **server-internal admin 전용
  (Android client 미사용)** → vibe-coder-android shared/ 동기 불필요.

### 제약 (의도된 한계)

- 안드로이드 **11+** 무선 디버깅 필요. 서버 호스트가 폰과 **같은 LAN**(또는 VPN 으로 폰이
  라우팅 가능)이어야 함 — 집 밖/다른 망에선 제한. 공유기 **AP isolation off** 필요.
- 자동 탐지(mDNS)는 **HOST 네트워크 전용**(bridge 에선 빈 목록 → 수동 입력 fallback).

## [1.39.0] - 2026-05-29 — Android 에뮬레이터 기능 전면 제거

에뮬레이터는 컨테이너에서 KVM 패스스루(런타임/호스트 의존)가 필수라 "파일만 받으면 바로
실행"이 구조적으로 불가능하고, 일반 빌드/APK 워크플로엔 불필요해 전면 제거. 실기기(USB/무선
ADB) 또는 호스트 Android Studio 에뮬레이터를 권장.

### Removed

- **서버 코드**: `emulator/` 패키지 전체(`EmulatorService` / `EmulatorRoutes` / `VncProxyRoutes`).
  라우트 `/emulator`, `/emulator/avd/{create-default,launch,stop}`, `/emulator/vnc/*` 제거.
  `ServerMain`/`Module` 배선, `AppContext.emulator` 필드 제거.
- **빌드환경**: `SetupComponent.EMULATOR` + `probeEmulator` 제거 — 빌드환경 페이지의 에뮬레이터
  카드/진단 사라짐. `EnvSetupTemplates` renderAction/priorityRank 정리.
- **네비게이션**: 도구 탭의 "에뮬레이터" 탭/카드 제거(`ToolsTabsTemplate`/`ToolsRoutes`),
  `SettingsNav` `/emulator`→tools 매핑 제거.
- **감사로그**: `emulatorAvdCreate/Launch/Stop` + `EMULATOR_AVD_*` 액션 상수 제거.
- **Docker**: `Dockerfile.full`, `compose.full.yml`, `emulator-entrypoint.sh` 삭제.
  `vibe-doctor`의 `emulator` 커맨드 + `manifest.yml`의 `emulator:` 섹션 +
  `android-sdk.sh`의 `install_emulator_*` 제거. `:full`(noVNC/Xvfb/KVM) 이미지 변형 폐지.
- **i18n**: `env.comp.emulator.*` / `env.action.emulator*` / `probe.emulator.*` /
  `tools.tab.emulator` / `tools.card.emulator.*` / `env.welcome.emulatorNote` 등 Ko/En 키 14개씩 제거.
- **문서**: README.md / HUB_README.md 의 에뮬레이터·noVNC·`:full` 섹션 및 라우트 표 행 정리.

기존 데이터/볼륨 영향 없음(에뮬레이터는 상태를 영속하지 않음). SSR 전용 제거라 `ApiPath`/
shared 변경 없음 → vibe-coder-android 동기 불필요.

## [1.38.0] - 2026-05-29 — 플러그인/마켓플레이스 관리 (전역 + 프로젝트, admin 전용)

Claude Code 플러그인(bkit 등)을 웹 UI 로 설치·관리. 설치된 플러그인의 skill·MCP·sub-agent·
command·hook 은 콘솔의 Claude 세션이 그대로 로드한다(spawn 되는 `claude` 가
`CLAUDE_CONFIG_DIR=/home/vibe/.claude` 를 상속하므로). 영속 볼륨이라 이미지 업데이트에도 유지.

### Added

- **`PluginService`** — 목록은 `claude plugin [marketplace] list --json` 파싱(권위 소스, 안정적
  구조). 변경(마켓 add/remove, 설치/삭제/활성/비활성/업데이트)은 `claude plugin …` 자식
  프로세스를 TaskQueue 로 실행 → env-setup 진행 페이지(WS 라이브 로그)에 표시. scope
  user(전역)/project(cwd=projectRoot) 모두 지원. **터미널/CLI 로 설치한 플러그인도 그대로
  감지·표시**.
- **전역 플러그인 탭** `/settings/plugins` (admin) — 설정 탭바에 "플러그인" 추가(12개). 마켓
  플레이스 추가/삭제 + user-scope 플러그인 설치/활성/비활성/삭제/업데이트 + 번들 MCP 표시.
- **프로젝트 플러그인 탭** `/projects/{id}/plugins` (admin) — 전역(user) 플러그인 읽기 전용
  + project-scope 플러그인 설치/관리(cwd=projectRoot). 마켓플레이스는 전역 공유라 RO.
- **보안**: 플러그인은 MCP/hook 등 코드를 실행하므로 전역·프로젝트 탭 모두 admin 전용 가드.
- 신규 라우터 `PluginRoutes`/`ProjectPluginRoutes` + `PluginTemplates`. i18n `settings.tab.plugins`
  / `tabs.plugins` / `plugins.*` (Ko/En). SSR 전용 — ApiPath/shared 변경 없음.

## [1.37.0] - 2026-05-29 — 기본 MCP(fetch/memory/sequential-thinking) + 도커 first-run 자동 등록

무인증·무설정으로 설치 즉시 쓰는 MCP 를 "기본 설치 대상"으로 지정. 도커 첫 설치 시 자동
등록되고, 카탈로그에서도 기본 선택됨. **이미지 업데이트(재부팅) 시엔 사용자 선택을 절대
변경하지 않음**(영속 볼륨 marker 기반 first-run only).

### Added

- **`McpEntry.defaultInstall`** 플래그 + `McpCatalog.defaultInstallIds`. fetch / memory /
  sequential-thinking 에 지정(sequential-thinking 은 `recommended` 도 추가). 모두 API 키·필수
  config 가 없어 등록 즉시 동작(`npx -y` 자동 fetch).
- **카탈로그 기본 선택** — `/env-setup/mcp` 에서 defaultInstall 항목은 체크박스가 기본 선택
  (미설치 상태에서도) + "기본" 배지. 사용자가 [설치] 한 번이면 바로 적용. i18n `mcp.entry.default`.
- **도커 first-run 자동 등록** — `McpService.bootstrapDefaultsIfFirstRun()` 가 서버 시작 시 1회:
  - 영속 볼륨(`~/.claude`)의 `.vibecoder-mcp-bootstrapped` marker 존재 시 no-op
    → **이미지 업데이트/재부팅에선 사용자 선택 보존**(절대 재등록·변경 안 함).
  - 기존 `.mcp.json` 에 server 가 이미 있으면(구버전 업그레이드) 등록 skip + marker 만 기록.
  - 진짜 fresh(볼륨 비어있음)일 때만 기본 MCP 를 `.mcp.json` 에 등록 + marker.
  - npm install 안 함(`npx -y` 자동 fetch) → 부팅 시 네트워크 의존/실패 위험 없음(파일 쓰기만).
    실패해도 startup 비차단(무시 + 카탈로그 수동 설치 가능).

## [1.36.0] - 2026-05-29 — 에이전트 / 스킬 / MCP 전역·프로젝트별 관리 (디스크 스캔 감지)

CLAUDE.md(v1.35.0)에 이어 에이전트·스킬·MCP 도 전역 + 프로젝트별로 관리. **모든 목록은
파일시스템 스캔 기반**이라 터미널이나 Claude 가 설정 메뉴를 거치지 않고 직접 만든 항목도
자동 감지·표시된다. 프로젝트 탭에선 전역 항목을 함께 보여주되 **읽기 전용**(편집은 전역 탭).

### Added

- **공용 패턴** `ScopedManagerTemplates` (`scope/`) — 전역(RO) + 프로젝트(편집) 2-섹션 목록 +
  편집 페이지 + 전역 단일 관리 페이지. 에이전트 정의·스킬이 공유.
- **에이전트 정의 (프로젝트 탭)** `/projects/{id}/agent-defs` — 전역 `~/.claude/agents`(RO) +
  프로젝트 `<root>/.claude/agents`(편집). `AgentRegistry` 를 프로젝트 디렉토리로 재사용
  (이미 디스크 스캔 기반 → 외부 생성 .md 감지). write+ACL.
- **MCP (프로젝트 탭)** `/projects/{id}/mcp` — 전역 `~/.claude/.mcp.json` server(RO 칩) +
  프로젝트 `<root>/.mcp.json` raw JSON 편집(유효성 검증 + atomic). `McpJsonStore` 신규.
  목록은 디스크 파싱이라 카탈로그/수동 등록 무관하게 표시. **전역 MCP 탭(`/env-setup/mcp`)에도
  "카탈로그 외 등록된 MCP (감지됨)" 섹션 추가** — `McpService.registeredServerNames()` 로
  터미널/Claude 가 직접 추가한 비-카탈로그 server 표시.
- **스킬 (신규, 전역 + 프로젝트)** — `SkillRegistry`(디렉토리 기반, `<dir>/<name>/SKILL.md`).
  전역 설정 탭 `/settings/skills`(편집) + 프로젝트 탭 `/projects/{id}/skills`(전역 RO + 프로젝트
  편집). 디스크 스캔 → 외부 생성 스킬 감지. SKILL.md 없는 디렉토리는 ⚠ 표시.
- 설정 탭바: claude-md 다음에 **skills** 탭 추가(11개). 프로젝트 탭 더보기: agent-defs / mcp /
  skills 추가. 신규 i18n `settings.tab.skills` / `tabs.{agentDefs,mcpProject,skills}` /
  `scope.*` / `agentDefs.*` / `mcpProject.*` / `mcp.detected.*` / `skills.*` (Ko/En).
- SSR 전용 — `ApiPath`/shared 변경 없음.

## [1.35.0] - 2026-05-29 — CLAUDE.md 관리 탭 신설 (전역 + 프로젝트별)

Claude 가 매 세션 읽는 규칙 파일(CLAUDE.md)을 웹 UI 로 직접 편집하는 탭 신설. 전역(모든
프로젝트 공통)과 프로젝트별을 각각 별도 탭으로.

### Added

- **전역 CLAUDE.md 탭** (`/settings/claude-md`, admin 전용) — 설정 탭바에 "CLAUDE.md" 탭
  추가(general/security/notifications/build-env/mcp/**claude-md**/prompts/backup/monitoring/
  users). Claude Code 의 user-memory(`~/.claude/CLAUDE.md`, 컨테이너 = `/home/vibe/.claude/
  CLAUDE.md`)를 read/write — 모든 프로젝트 Claude 세션에 공통 적용되는 전역 규칙.
  `GlobalClaudeMdService`(고정 경로, `/home/vibe/keystores`·git config 와 동일한 워크스페이스
  밖 운영자-config 계열, `VIBECODER_GLOBAL_CLAUDEMD_PATH` env override). atomic move +
  `.bak` 백업, 256KB 한도.
- **프로젝트 CLAUDE.md 탭** (`/projects/{id}/claude-md`) — 프로젝트 탭 더보기에 "CLAUDE.md"
  추가. `<projectRoot>/CLAUDE.md` 를 편집(파일명 고정 → traversal 무관, env-files 와 동일한
  atomic move). write 권한 + Project ACL 가드. 비우면 전역 규칙만 적용.
- 공용 편집기 `ClaudeMdTemplates`(monospace textarea + Ctrl/Cmd+S 저장 + Tab 들여쓰기 +
  경로/크기 표시). 신규 i18n 키 `settings.tab.claudeMd` / `tabs.claudeMd` / `claudeMd.*`
  (Ko/En). 신규 라우터 `GlobalClaudeMdRoutes` / `ProjectClaudeMdRoutes`.
- SSR 전용(JSON API 아님) — `ApiPath` 변경 없음, shared/ 동기 불필요.

## [1.34.6] - 2026-05-29 — MCP 를 빌드환경에서 별도 설정 탭으로 분리

빌드환경 페이지에서 MCP 카탈로그를 독립 탭으로 분리. MCP 는 설치형 빌드 컴포넌트
(SDK/Gradle)와 성격이 달라 별도 카탈로그 관리가 자연스럽다.

### Changed

- **MCP 별도 탭** — 설정 탭바가 8개 → 9개 (general / security / notifications / build-env /
  **mcp** / prompts / backup / monitoring / users). `SettingsTabsTemplate.TABS` (iframe 통합
  탭) + `SettingsNav.tabBar` (sub-page 상단 탭바) 양쪽에 추가. 신규 i18n 키 `settings.tab.mcp`
  (Ko/En "MCP").
- `SettingsNav.tabOf` — `/env-setup/mcp` 를 `/env-setup` prefix 보다 먼저 매칭해 build-env 가
  아닌 mcp 탭으로 라우팅. `McpTemplates` 가 `currentPath="/env-setup/mcp"` 를 넘겨 MCP 탭 active.
- **빌드환경 페이지(/env-setup) 에서 MCP 제거** — 컴포넌트 grid 의 `MCP_DEFAULTS` 카드와
  "관련 설정" quick-link chip 제거(`filterNot`). 진단·install-all 에는 그대로 포함되며 MCP
  선택/설치는 MCP 탭(`/env-setup/mcp`)에서 수행. 라우트 변경 없음(페이지는 기존 `/env-setup/mcp`).

## [1.34.5] - 2026-05-29 — 빌드환경 페이지(/env-setup) 우선순위 재정렬 + 레거시 문장 정리

`/env-setup` 페이지가 중구난방이라 알아보기 어렵다는 피드백 반영. 카드/섹션을 우선순위순으로
재배치하고, 현재 기능과 모순되는 레거시 안내 문장을 정리.

### Changed

- **페이지 우선순위 재정렬** (`EnvSetupTemplates.envSetupPage`): 이전엔 quick-links → 장문
  welcome → preserved → git → 컴포넌트 grid(JDK/Git/Node 가 맨 앞) 순이라 정작 중요한
  Claude 인증·Android SDK 가 한참 아래에 묻혔다. 이제:
  1. **핵심(설치·인증)** — `doctorCmd != null` 컴포넌트를 우선순위순 grid 로 최상단
     (Claude 인증 → Android SDK → Gradle → platform-tools → MCP → 에뮬레이터). `priorityRank()`.
  2. **Git identity** 카드.
  3. **이미지 내장(확인용)** — JDK/Git/Node/Claude CLI 는 손댈 일이 거의 없어 `<details>`
     로 접음(문제 있을 때만 자동 펼침).
  4. **관련 설정 링크**(키스토어/SSH/캐시/Git통합/MCP).
  5. **처음 사용 안내 · 데이터 보존** — 참고용이라 `<details>` 로 접음.
- **레거시 문장 정리** (i18n Ko/En):
  - `env.welcome.step2` — "OAuth 라 터미널에서 한 번만 … (자동화 불가)" → 웹 OAuth 가
    v0.7.0 에 추가됐는데도 터미널만 안내하던 모순 제거. "Claude 인증 카드에서 웹 OAuth ·
    터미널 · API 키 중 하나로" 로 갱신.
  - `env.welcome.emulatorNote` — "에뮬레이터는 기본 제공하지 않습니다" → v1.10.0 에 EMULATOR
    설치 카드가 생겼는데도 "미제공"이라던 모순 제거. "부피 커서 미설치, 아래 카드로 내려받고
    실 부팅은 KVM/:full 필요" 로 갱신.
  - 신규 i18n 키 4개: `env.section.core` / `env.section.builtin` / `env.section.builtinHint`
    / `env.section.help` (Ko/En).
- 소스의 stale HTML 주석(v1.7.13 / v1.27.0 안내) 정리. 기능 동작·라우트·CSS 변경 없음(순수
  SSR 레이아웃 재배치) — 기존 사용자 마이그레이션 불필요.

## [1.34.4] - 2026-05-29 — 21차 점검 후속 (Minor 12건 일괄 회수)

v1.34.3 에서 회수한 Critical 3 + Bug 14 에 이은 후속. 21차 정밀점검의 Minor 13건 중
12건 회수(품질/견고성 개선, 사용자 체감 영향 작음). 1건(`JsonUsageRoutes` /api/usage
ACL 비대칭)은 KDoc 상 viewer 노출이 의도된 설계 + 노출 데이터가 토큰 집계라 별도 결정
사안으로 보류.

### Fixed

- **AuthService Clock 미사용**: `login()` 이 주입된 `clock` 무시하고 `System.currentTimeMillis()`
  직접 호출 → 잠금(10회/15분)·IP차단(30회/24h) window 를 fake clock 으로 결정적 테스트
  불가. `clock.nowInstant().toEpochMilli()` 로 교체.
- **WS Origin(CSWSH) 검증 Host-null 통과**: `host != null && originHost != null` 가드 안에서만
  mismatch 검사 → Origin 존재하나 Host 없음/파싱 실패 시 silent no-op 으로 통과. fail-closed
  (Origin 존재 + Host null|파싱실패|mismatch → 거절)로 전환.
- **터미널 ClaudeLoginService UTF-8 청크 분할**: `drainOutput` 이 4096B 청크 독립 디코딩 →
  멀티바이트 경계 깨짐(비-ASCII 진단 메시지 한정). `InputStreamReader` carry-over.
- **SymbolRoutes escJson 제어문자 미처리**: `\n\r\t` 외 0x20 미만 제어문자(`\b \f` 등)를
  raw 삽입 → 그런 문자가 든 소스 라인 응답 시 RFC 8259 위반 JSON. 전 제어문자 `\u` 이스케이프.
- **VncProxyRoutes WS forward 블로킹**: client→backend forward 루프가 매 프레임 `.get()`
  동기 블로킹으로 WS 코루틴 스레드 점유 → `kotlinx.coroutines.future.await()` suspend 전환.
- **GitWriter push 30s timeout**: `runWithExit` 고정 30s 라 대용량 push 가 중도 504. push 만
  5분(clone 10분과 균형) 부여하는 `timeoutSeconds` 파라미터 추가(add/commit/status 는 30s 유지).
- **FcmSender shutdown scope 누수**: `shutdown()` 이 `scope.cancel()` 미호출 → graceful
  shutdown 시 in-flight launch + SupervisorJob 미정리(Email/WebhookNotifier 와 비대칭). 추가.
- **DeviceRepository token_hash 인덱스 부재**: `findByTokenHash`(인증 hot-path)가 인덱스
  없는 컬럼 full scan. `Devices` 에 non-unique 인덱스 추가(unique 는 기존 데이터 마이그
  실패 위험 회피).
- **ProjectFileBrowser write 한도 char/byte 혼동**: `content.length`(UTF-16 char) 를 byte
  상수 `MAX_VIEW_BYTES` 와 비교 → 멀티바이트 텍스트가 write 허용 후 read 에서 재오픈 불가.
  `toByteArray(UTF_8).size` 로 read/upload 와 대칭화.

### Changed

- **PushRoutes ApiPath SSOT (§8.A)**: vapid-public-key / subscribe endpoint 를 `ApiPath`
  상수 참조로 전환(이전 하드코딩). delete `{id}` 는 `pushSubscription()` 가 pathSeg
  URL-encoding twin 이라 route 패턴엔 부적합 → 리터럴 유지 + 사유 주석.
- **ConversationTurnRepository turnIdx 불변식 명세화**: `nextTurnIdx` SELECT max+INSERT
  비원자(READ COMMITTED)로 동시 적재 시 turnIdx 중복 가능하나, 모든 읽기가 ts ASC 정렬이라
  사용자 체감 없음 — "best-effort, SSOT=ts" 로 KDoc 명확화(엄밀 보장 필요 시 unique+retry).

## [1.34.3] - 2026-05-29 — 21차 점검 회수 (전체 코드베이스 멀티에이전트 정밀점검: 인가 가드 systemic 누락 + race/leak/encoding)

전체 코드베이스(182파일 / ~42K LoC, 30패키지) 멀티에이전트 정밀점검(15 도메인 병렬 탐지
+ 단위별 adversarial 재검증). 확정 31건(Critical 4 / Bug 14 / Minor 13) 중 **Critical 3 +
Bug 14 = 17건 회수**(Minor 13 + JsonUsageRoutes 비대칭은 다음 사이클). 보고된 Critical 1건
(ClaudeStatusService ANSI regex `[^]` throw)은 **오탐으로 확인** — 실제 소스엔 ESC(0x1b)·
BEL(0x07) 제어문자가 박혀 있어 패턴이 `<ESC>\][^<BEL>]*<BEL>?`(닫힌 클래스)이며 JVM 정상
컴파일됨. 점검 도구가 파일을 읽을 때 제어문자를 제거해 `[^]`(미닫힘)으로 오인한 케이스.

**핵심 진단 — 단일 systemic 결함이 인가 누락 10건을 만들어냈다.** `WebProjectRoutes` 가
확립한 `requireProjectAccessOrThrow` / `requireWriteAccessOrRedirect` / `requireAdminOrRedirect`
가드 패턴이, 이후 버전에서 추가된 라우터들에 일관 적용되지 않아 Project ACL(member/viewer
role, v0.49+) 활성 환경에서 cross-project 접근·viewer 권한 우회가 가능했다. (단일 admin
standalone 운영이 기본이라 실 노출은 제한적이나 role 모델 자체의 결함.)

### Security

- **C2 (CRITICAL — `/automation` 6개 핸들러 인가 전무)**: `BuildAutomationRoutes` 의 GET +
  5 POST 가 세션+CSRF 만 검사 → viewer 가 cron schedule·webhook secret(인증 없는 외부 빌드
  트리거 자격증명) 생성/삭제 가능 + `schedules/{scheduleId}`·`secrets/{secretId}` 가 row 의
  projectId 소속 미검증이라 id 만 알면 임의 프로젝트 조작 가능. write+ACL 가드 + row 소속
  검증 추가.
- **C3 (CRITICAL — `/projects/{id}/env-files` ACL 누락)**: ACL 제한 member 가 임의 프로젝트의
  `local.properties`·`.env`(비밀번호/API키) 열람·덮어쓰기 가능. GET/POST 에 ACL(+POST write)
  가드 추가.
- **C4 (CRITICAL — 파일 트리 rename `onsubmit` stored XSS)**: `WebProjectTemplates` 의
  rename 폼이 파일명(`renameJsLit`, backslash·single-quote 만 escape)을 double-quote HTML
  속성에 raw 보간 → 파일명의 `"` 로 속성 breakout·이벤트 핸들러 주입 가능(파일명은 디스크
  listing 무검증). delete 폼처럼 `esc()` 적용으로 속성 컨텍스트 안전화.
- **B3 (`/history` 글로벌 검색 admin 누락)**: SSR cross-project 대화 검색이 로그인만으로
  전 프로젝트 발췌 노출(JSON twin `/api/history/search` 는 admin 전용) → JSON twin 과 동일
  admin-only.
- **B5 (`/deps` ACL+write 누락)**: GET `run=1` 이 Gradle 자식 프로세스 spawn → ACL + run 분기
  write 가드.
- **B7 (`/wrapper`·`/stats` ACL 누락)**: wrapper 변조/통계 열람 → ACL(+wrapper POST write).
- **B8 (Emulator lifecycle 가드 누락)**: viewer 가 AVD spawn/kill·system-image 다운로드 트리거
  → VNC proxy 와 동일 admin-only(GET 포함).
- **B10 (`/env-setup` mutating 전부 가드 누락)**: viewer 가 Claude 자격증명·API키·git identity·
  패키지 설치 조작 → 형제 settings 라우터처럼 모듈 전체 admin-only.
- **B11 (`/env-setup/mcp` 가드 누락)**: viewer 가 MCP 설치(자식 spawn)·`.mcp.json` 변경·시크릿
  파일(Service Account JSON/.p8) 기록 → admin-only.
- **B12 (`WebProjectRoutes` play/testflight-upload·cancel write 누락)**: 나머지 15개 mutating
  POST 와 달리 이 3개만 `requireWriteAccessOrRedirect` 누락 → viewer 가 스토어 업로드 트리거·
  빌드 취소 가능 → write 가드 추가.
- **B13 (artifact API ACL 누락)**: APK list/get/verify/download 가 `requireProjectAcl` 미호출
  (시그니처에 ProjectService 부재) → cross-project APK 다운로드 → `projects` 파라미터 추가
  + 4 endpoint 모두 ACL 강제(`Module.kt` 호출부 동기).

### Fixed

- **B1 (cancelTurn 후 session-id 오삭제 race)**: `ClaudeSessionManager` 가 SIGTERM 의 비정상
  종료코드를 resume-failure 로 오판 — init frame 도착 전 5초 내 cancel 시 보존돼야 할
  session-id 를 삭제하고 `resume_failed_starting_new` 를 emit. `ProjectSession.intentionalKill`
  플래그 추가(terminateSession 이 SIGTERM 전 set) → onProcessExit 이 의도된 종료(cancel/
  startNew/idle reap/shutdown)와 진짜 crash 를 구분.
- **B2 (LogHub seq/ring 비원자 — 동시 emit 시 ring 순서 역전)**: `emitConsole` 의 seq
  증가가 ring addLast 임계영역 밖이라 multi-producer 인 `PROJECTS_TOPIC` 동시 emit 시 ring
  이 seq 내림차순 삽입 → `subscribeConsole` 의 ringFloor·eviction·replay 순서 손상. seq 할당
  ~ ring 삽입을 단일 `synchronized` 로 원자화(publisher.emit 만 락 밖).
- **B4 (터미널 PTY UTF-8 chunk 경계 분할)**: read loop 가 8192B chunk 를 매번 독립 디코딩 →
  멀티바이트(CJK 3B/이모지 4B) 시퀀스가 read 경계에 걸리면 양쪽 영구 `�`. `InputStreamReader`
  로 전환해 미완성 tail 을 다음 read 로 carry-over(v1.34.1 surrogate 가드의 상류 결함).
- **B6 (Wire — `expiresAtIso` epoch ms 를 `ofEpochSecond`)**: Claude credentials 업로드 응답의
  `expiresAtIso` 가 밀리초 값을 초로 변환해 ~1000배 미래 시각. `ofEpochMilli` 로 교정
  (raw `expiresAt:Long` 은 정상이라 SSR/외부 콜러 무영향, **vibe-coder-android 의 `expiresAtIso`
  파싱 클라이언트만 만료 표시/판정이 정상화** — DTO shape 변경 없음 → Android 동기 불필요).
- **B9 (DB startup 재시도 시 HikariDataSource 누수)**: 풀 생성 성공 후 마이그레이션 실패
  (예: `pg_trgm CREATE EXTENSION` 권한 부족)가 반복되면 최대 STARTUP_RETRY 개 orphan 풀
  (connection+housekeeping 스레드)이 startup window 동안 누적. catch 에서 직전 풀 close.
- **B14 (WebAuthn JSON 응답 escape 누락)**: register/verify·assert/verify 응답이 사용자
  제어 passkey name 을 HTML 전용 `esc()` 로만 처리 후 수기 JSON 보간 → backslash/제어문자로
  malformed JSON. JSON 전용 `escJson()`(backslash·따옴표·제어문자 `\u` 변환) 추가·적용.

### 점검 방법론 메모

- **Read 도구의 제어문자 stripping 이 오탐을 유발**할 수 있음(C1 사례 — ESC/BEL 이 보이지
  않아 정규식이 깨져 보임). 제어문자가 포함된 소스(ANSI strip regex, escape 테이블)는 `cat -A`
  / byte-level 로 재확인하고, regex 결함 의심 시 실제 파일 bytes 를 JVM 으로 컴파일해 검증.
- 본 회수 후속(Minor 13): `AuthService` Clock 미사용, WS Origin Host-null 통과,
  `/api/usage` ACL 비대칭, terminal kill `waitFor` 블로킹, `ClaudeLoginService` 4096B UTF-8
  분할, `SymbolRoutes` escJson 제어문자, VncProxy `CompletableFuture.get()` 블로킹, git push
  30s timeout, `FcmSender.shutdown` scope.cancel 누락, `PushRoutes` ApiPath 하드코딩,
  `ConversationTurnRepository` turnIdx race, `DeviceRepository` token_hash 인덱스 부재,
  `ProjectFileBrowser` write char/byte 혼동.

## [1.34.2] - 2026-05-29 — 20차 점검 회수 (LogHub 토픽 누수·console race + .mcp.json 덮어쓰기 + TokenRefresher shutdown)

20차 정밀점검(서버 deep-dive): 안드로이드 wire 호환(터미널 4+3) 동기 완료에 이어
서버 내부 미점검 영역 점검. A 회귀(v1.34.1 surrogate/인덱스) 2/2 PASS, ClaudeStreamParser·
StaticRoutes PASS. CRITICAL 0, BUG 3 + QUALITY 1 회수.

### Fixed

- **BUG-2 (LogHub 레거시 토픽 영구 누수)**: build/task/mcp/env-setup id 별 토픽이
  `publisher()` 의 `computeIfAbsent` 로 영구 등록됐고 `close()` 호출자가 0건이라 서버
  수명 동안 단조 증가(토픽당 replay 64 frame retain). `publisher()` 호출마다 활동 시각
  기록 + idle reaper(5분 주기) 가 **구독자 0 + 10분 무활동** 레거시 토픽만 정리. 진행
  중 빌드(구독자>0)는 보존. `LogHub.shutdown()` + ServerMain hook 으로 reaper 정리.
- **BUG-1 (console 구독 결합 race — frame 유실 완화)**: `subscribeConsole` 의 ring
  snapshot 과 `view.live.collect` 구독 등록 사이 gap 에 emit 된 frame 이 console
  publisher `replay=0` 이라 영구 유실되던 race(활성 스트리밍 중 재연결 시 일부 누락).
  publisher `replay` 를 32 로 키워 직전 frame 을 보유 → collect 시작 시 전달(gap 복구).
  핸들러의 기존 dedup(`sf.seq<=since` + `view.replay.any{}`)이 ring-replay 와의 중복을
  완전 제거하므로 중복 전송 없음.
- **BUG-3 (.mcp.json 파싱 실패 시 덮어쓰기 데이터 손실)**: `readMcpJson` 이 "파일 없음"
  과 "파싱 실패"를 둘 다 null 로 반환 → install/unregister 가 빈 객체로 출발해
  `writeMcpJsonAtomic` 이 손상 파일을 덮어써 기존 mcpServers(타 도구/수동 등록분) 소실.
  파싱 실패 시 `.mcp.json.corrupt-<ts>` 백업 후 null 반환(원본 보존). claude CLI 가
  동시 편집하는 파일이라 현실적 위험.

### Changed

- **QUALITY-2 (ClaudeTokenRefresher shutdown hook 누락)**: `start()` 로 폴링 코루틴을
  launch 하나 다른 모든 매니저와 달리 JVM shutdown hook 에서 정리 누락(graceful-restart
  시 leak). `claudeTokenRefresher.shutdown()` hook 등록.

### Note

- QUALITY-1 (MCP 명령 인젝션): `command`/`args` 가 argv 배열로 기록되고 claude CLI 가
  shell 미경유 spawn → 인젝션 불가. secret 파일도 sanitize+0600+atomic. **결함 아님**(점검만).
- 코드 검토 기반. BUG-1 완화는 race window 축소(완전 제거는 구독 API 재설계 필요하나
  영향 LOW — ring 으로 다음 재연결서 복구되던 것).

## [1.34.1] - 2026-05-29 — 19차 점검 회수 (scrollback surrogate 절단 + builds 계열 인덱스)

19차 정밀점검: v1.34.0 scrollback ring buffer 는 메모리·lock·replay·dispose 가드 모두
PASS. CRITICAL 0, BUG 1 + QUALITY 1 회수. (TOTP replay 는 단일 admin+HTTPS 영향 낮아
보류 유지.)

### Fixed

- **BUG-1 (scrollback cap 절단 시 surrogate 깨짐)**: `appendScrollback` 의
  `scrollback.delete(0, over)` 가 UTF-16 char 단위라 cap(200K) 초과분을 자를 때
  surrogate pair(이모지/CJK 보충문자) 중간을 절단 → 외톨이 low surrogate 가 버퍼
  맨 앞에 남아 재연결 replay 시 깨진 글자(�). 절단 후 맨 앞이 low surrogate 면
  1글자 제거하도록 방어. (저영향 — 재연결 첫 줄 한정, 다음 출력서 자연 복구.)

### Changed

- **Q1 (성장 테이블 projectId 인덱스)**: `Builds`/`Artifacts`/`UploadedFiles` 에
  `index(projectId, createdAt)` 추가. PG 는 FK 에 인덱스를 자동 생성하지 않아 빌드
  목록/통계/비교·아티팩트 prune·업로드 목록의 `WHERE project_id=?` 가 seq scan 이었음.
  `SchemaUtils.createMissingTablesAndColumns` 가 기존 테이블에도 누락 인덱스 추가.
  - `BuildSchedules`/`BuildWebhookSecrets` 는 **의도적으로 제외** — 프로젝트당 소수
    행으로 성장하지 않는 테이블이라 인덱스 이득 없이 쓰기 오버헤드만 발생(seq scan
    of few rows 가 더 빠름). 18차 Q1 의 "5개 테이블 전부" 권고 중 성장 테이블만 선별.

### Note

- Q3 (PTY read `String(buf,0,n,UTF_8)` 8192-byte 청크 경계 multi-byte 절단) 은
  v1.6.0~ 기존 동작(v1.34.0 회귀 아님) + 완전 해결이 InputStreamReader 스트리밍
  디코딩 전환이라 비용 대비 효용 낮아 이번 회수 제외.

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
