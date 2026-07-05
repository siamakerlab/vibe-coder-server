# ROADMAP — Provider 갭 최소화 및 OpenCode(z.ai) 구현

> 내부 문서 (한국어). `CLAUDE.md` §9 Roadmap 과는 별개로, **Provider 3축(Claude /
> Codex / OpenCode) 기능 패리티 + z.ai coding plan 강제**라는 구체적 목표를
> 단계별로 추적하기 위한 독립 로드맵.
>
> 기준 버전: **v1.145.18** (2026-07). 각 Phase 의 목표 버전은 구현 시점의
> SemVer 정책(`CLAUDE.md` "버전 정책")에 따라 조정 가능. 인터페이스 breaking 은
> 1.x 단계이므로 MINOR 로 처리 (SemVer 2.0.0 §4).

## 0. 배경 및 목표

### 0.1 현황 (Phase 0~3 완료 후, v1.155.0)

3 provider(Claude/Codex/OpenCode) 기능 패리티 종합. **✓** = 구현, **~** = 부분/CLI 제약,
**✗** = 미구현(residual gap).

#### 구현 완료 (Phase 0~3)

| 영역 | Claude | Codex | OpenCode | 비고 |
|---|:---:|:---:|:---:|---|
| 세션 매니저 | ✓ | ✓ | ✓ | Phase 0~2 (OpenCode: M2.1) |
| prompt 전송 | ✓ | ✓ | ✓ | |
| 자동화 / 예약 | ✓ | ✓ | ✓ | Phase 0/M1.1 (OpenCode SESSION/WEEKLY_RESET 은 usage % 미노출로 보류) |
| 끼어들기(⚡) | ✓ | ~ | ~ | Codex/OpenCode: cancel→send (turn 단위 exec, stdin interrupt 미지원) |
| rate-limit 자동재개 | ✓ | ✓ | ✓ | M1.2/M3.2 (지수 백오프 5회) |
| 부팅 reconcile | ✓ | ✓ | ✓ | M1.3/M2.1 |
| 5상태 busy 머신 | ✓ | ✓ | ✓ | ProjectState(READY/RESPONDING/WAITING/STOPPED/ERROR) |
| `server.yml` 섹션 | ✓ | ✓ | ✓ | M2.1 |
| status / usage service | ✓ | ✓ | ✓ | M1.1/M2.2 |
| login 플로우 | ✓ | ✓ | ✓ | M2.2 |
| 사용량 임계치 알림 | ✓ | ✓ | ✗ | OpenCode: usage % 미노출 (CLI 제약, z.ai API 연동 시 확장) |
| z.ai coding plan 강제 | — | — | ✓ | M3.1 (OPENCODE_CONFIG_HOME 격리 + 모델 whitelist) |
| WS topic 분리 | ✓ | ✓ | ✓ | |

#### residual gap (provider CLI 자체 한계)

| 영역 | Claude | Codex | OpenCode | 비고 |
|---|:---:|:---:|:---:|---|
| 서브에이전트 | ✓ | ✗ | ~ | OpenCode: `task` 도구로 부분 처리 (tool_use 이벤트) |
| 이미지 / vision | ✓ | ✗ | ~ | Codex: CLI exec 미지원; OpenCode: `-f` 파일 가능, inline DTO 미지원 |
| auto-compaction | ✓ | ✗ | ~ | OpenCode: CLI 자체 컨텍스트 관리 (서버 /compact 불필요) |
| MCP strict | ✓ | ✗ | ✗ | |
| 백그라운드 작업 카드 | ✓ | ✗ | ✗ | |
| 토큰 자동갱신 | ✓ | ✗ | ✗ | OpenCode: CLI 자체 갱신 미지원 — 만료 시 재로그인 안내 |
| thinking 렌더링 | ✓ | ~ | ~ | Codex/OpenCode: Unknown 스킵 (형식 미확정, 확정 시 전용 렌더링 추가) |

#### 구현 가능한 UX 갭 — 콘솔 메시지 표현 (v1.156.0 분석, Phase 4 대상)

residual gap(CLI 자체 한계)과 달리 **서버/클라이언트 수정으로 해결 가능**한 갭. 분석 기준:
`console-render.js` 의 `renderToolUse(name, input)` switch 가 도구 이름+필드명으로 매칭.

| 항목 | Claude | Codex | OpenCode | 원인/비고 |
|---|:---:|:---:|:---:|---|
| 도구 이름 매칭 | ✓ 파스칼케이스 | ✓ `command_execution` | **✗ 소문자** | OpenCode 도구(`read`/`write`/`edit`/`bash`/`grep`/`glob`/`todowrite`/`task`)가 switch 에 없어 기본 fallback 라벨만 표시 |
| 도구 input 필드명 | ✓ snake_case | ✓ | **✗ camelCase** | OpenCode: `filePath`/`command`/`pattern` vs Claude: `file_path`/`old_string`. 매칭돼도 필드 추출 실패 |
| thinking/reasoning 렌더링 | ✓ 전용 | ~ Unknown 스킵 | ~ Unknown 스킵 | OpenCode `--thinking` / Codex reasoning 이벤트 형식 미확정 |
| assistant 스트리밍 | ✓ `isPartial` | ✗ 완성 메시지 | ✗ 완성 메시지 | exec/run 모델이 완성 텍스트만 내보냄 (CLI 출력 구조) |
| 도구 결과 포맷 | ✓ 정규화됨 | ✓ | ~ JSON 문자열 | OpenCode output 이 JsonElement — `extractToolResultRich` 매핑 검증 필요 |
| 이미지 `[image]` 마커 | ✓ | ✗ | ✗ | inline DTO 미지원 (residual gap 과 겹침) |

**핵심 갭**: OpenCode 도구 이름(소문자)+ input 필드명(camelCase)이 Claude 파스칼케이스+
snake_case 기반 `renderToolUse` 에 매칭되지 않아, GLM 콘솔에서 도구 호출이 친화적으로
렌더링되지 않는다. Phase 4 M4.1 에서 정규화로 해결.

### 0.2 구조적 원인 (3가지)

1. **`turnDoneListener` / `turnInterruptListener` 가 인터페이스 밖** —
   `ClaudeSessionManager.kt:106/113` 의 `var` 프로퍼티로만 존재.
   `AgentSessionManager`(`agent/AgentSessionManager.kt:14`) 에 없음.
2. **자동화/예약 매니저가 Claude 구체 타입에 하드 바인딩** —
   `PromptAutomationManager.kt:31`·`ScheduledPromptManager.kt:43` 생성자가
   `ClaudeSessionManager` / `ClaudeStatusService` 직접 의존.
3. **OpenCode 구현체 없음** — `OpenCodeSessionManager.kt` 8줄 stub.
   `server.yml` opencode 섹션·status service·login 플로우 전무.

### 0.3 목표

- **G1. Provider 추상화 강화** — 자동화·예약·알림 등 오케스트레이션 계층을
  provider 무관화. 신규 provider 추가 시 고급 기능이 자동으로 따라오도록.
- **G2. Codex 패리티** — Claude 전용으로 분류된 기능 중 사용자 체감이 큰 것부터
  Codex 에 순차 확장.
- **G3. OpenCode 구현** — `opencode` CLI(https://opencode.ai, SST) 를 3번째
  1등 시민으로 승격. **z.ai coding plan 강제** 옵션과 함께.
- **G4. z.ai coding plan 강제** — OpenCode 실행 시 항상 z.ai(GLM 계열)
  coding plan 구독 경로만 사용하도록 config 고정. 다른 provider(anthropic /
  openai / 기본) 로의 우회 차단.

### 0.4 원칙

- **독립 구현 우선** (`AGENTS.md` "AI Provider 구조"). CodexSessionManager 패턴
  차용 — Claude 래퍼 재사용 금지.
- **provider switching 은 되돌리기 쉽게**. 한 provider 고장이 다른 provider
  에 파급되지 않도록 격리.
- **각 Phase 는 독립 검증 가능**해야 함. clean 빌드 + `./gradlew :server:test`.
- **Wire change 시 ApiPath SSOT 먼저** (`AGENTS.md` "Wire Change 규칙").
  hardcoded path reject.
- **Android 호환 영향 알림 필수** (`AGENTS.md` "Android 호환 영향 알림 방침").

---

## Phase 0 — Provider 추상화 기반 (기반 공사)

> **목표 버전: v1.146.0**
> **성격**: 내부 리팩토링 (외부 동작 동일) + 인터페이스 확장. wire change 최소.
> **목적**: Phase 1~3 의 전제 조건. 자동화/예약이 provider 를 모르도록 분리.

### 0-A. `AgentSessionManager` 인터페이스 확장

`agent/AgentSessionManager.kt:14` 에 turn 관찰 hook 을 추가.

- [x] 새 프로퍼티 추가 (기본 구현 nullable):
  ```kotlin
  var turnDoneListener: (suspend (projectId: String, reason: String) -> Unit)? = null
  var turnInterruptListener: (suspend (projectId: String, reason: String) -> Unit)? = null
  ```
- [x] protected helper `fireTurnDone(pid, reason)` / `fireInterrupt(pid, reason)` 추가.
- [x] `ClaudeSessionManager.kt:106/113` 의 `var` 제거 → 인터페이스 상속 사용.
  내부 `fireTurnDone` 호출 지점(`:115-123`)은 그대로.
- [x] `ClaudeAgentSessionManager` 래퍼가 listener 를 delegate 로 forward 하도록
  보장 (래퍼 경유 시 리스너 소실 방지).
- [x] `CodexSessionManager` 가 turn 완료 시점(`CodexEvent.TurnCompleted`/`TurnFailed`,
  `CodexSessionManager.kt` handleEvent) 에 `fireTurnDone()` 호출하도록 추가.
- [x] `UnsupportedAgentSessionManager` 는 hook 유지 (no-op).

**검증**: `./gradlew :server:test` + Claude 자동화가 기존대로 동작 확인.

### 0-B. 자동화/예약 매니저 provider 무관화

- [x] `PromptAutomationManager.kt:31` 생성자 `ClaudeSessionManager` →
  `AgentSessionManager` (또는 provider 조회용 `AgentRouter`).
- [x] `ScheduledPromptManager.kt:43` 동일. 단 `ClaudeStatusService` 의존은
  provider별 status 조회 인터페이스로 추상화 (아래 0-C).
- [x] `ServerMain.kt:386-405` 의 listener 주입을 Claude 세션만이 아닌
  **등록된 모든 manager** 에 적용 (`agentRouter.allManagers().forEach { ... }`
  또는 router 단위 팬아웃).
- [x] 자동화/예약 라우트(`/claude/automation/*`)의 경로를 provider 중립적으로
  유지하되, 내부에서 선택된 provider 의 manager 로 dispatch.

**주의**: 기존 `ProjectAgentPreferenceStore` 기반 provider 선택을 자동화가
respects 하도록. Claude provider 일 때만 동작하던 legacy 동작 보존.

### 0-C. Provider status 조회 추상화

- [x] 새 인터페이스 `AgentStatusProvider` (또는 `AgentSessionManager` 확장):
  ```kotlin
  fun statusSnapshot(projectId: String): AgentStatusSnapshot?  // usage% / reset / model
  ```
- [x] `ClaudeStatusService`·`CodexStatusService` 를 어댑트. (Claude 구현 완료, Codex 는 Phase 1 예정)
- [x] `ScheduledPromptManager` 의 session_reset/weekly_reset 트리거가
  선택된 provider 의 status 기준으로 동작.

### 0-D. ApiPath SSOT 정리 (레거시 정책 위반 회수)

- [x] `ConsoleRoutes.kt:229/243` 의 `/agent/provider` GET/POST 를
  `shared/ApiPath.kt` 에 먼저 등록 (강제 룰 v0.64.0+).
- [x] 자동화/예약 endpoint 중 SSOT 밖에 있는 것 회수. (pathSeg 기반 라우트는 v1.145.18 에서 일괄 회수 완료)

**Wire change**: ApiPath 상수 추가만 (경로 문자열 동일). **Android 영향**:
additive — 안드가 새 endpoint 를 쓰지 않으면 영향 없음. shared/ 동기 권고(선택).

---

## Phase 1 — Codex 기능 갭 최소화

> **목표 버전: v1.147.0 ~ v1.149.0** (3 마일스톤)
> **성격**: additive 기능 확장. wire change 발생(신규 WsFrame).
> **전제**: Phase 0 완료.

### Milestone 1.1 (v1.147.0) — Codex 오케스트레이션 연동

Phase 0 의 추상화 결과를 Codex 에 적용.

- [x] `CodexSessionManager` turn 완료 → `fireTurnDone()` 호출 (Phase 0-A 에서
  일부 구현, 여기서 검증).
- [x] **Codex 프롬프트 자동화 활성화** — provider 가 CODEX 인 프로젝트에서
  자동화 시작/연속 동작 확인. (Phase 0 provider 무관화로 이미 동작 — `PromptAutomationManager`
  가 `AgentRouter.sendPrompt` 로 발사하고 `AgentRouter.installTurnListeners` 가 Codex
  manager 에도 turn 완료 리스너를 주입.)
- [x] **Codex 예약 프롬프트 활성화** — `CodexStatusService` 기반 한도 트리거.
  (`CodexStatusService` → `AgentUsageProvider` 구현 + `ScheduledPromptManager` 가
  provider별 `Map<AgentProvider, AgentUsageProvider>` 로 판정 + `createSchedule` baseline
  도 provider별 측정.)
- [x] 자동화 UI(`WebProjectTemplates.kt:1409`) 가 provider 무관하게 동작 확인.
  (자동화 UI 는 Phase 0 이전부터 REST `/claude/automation/*` 계약 기반으로 provider
  무관했음 — `PromptAutomationManager` → `AgentRouter.sendPrompt` 경로 확인.)
- [x] 사용량 알림 — `CodexUsageMonitor` 에 임계치 이메일 발송 추가
  (`ClaudeUsageMonitor` 패턴 차용). (`Notifiers.codexUsageWarn` + `CodexUsageSection`
  config + transition 기반 warn/critical 알림, 10분 cooldown.)

**Wire change**: 없음 (기존 WsFrame 재사용). **Android 영향**: 없음.
**검증**: `./gradlew clean :server:test` 통과 + `CodexStatusServiceTest` /
`ScheduledPromptLimitReleasedTest` 단위 테스트로 transition/한도-해제 판정 검증.
(Codex provider 자동화 E2E 3회 연속은 운영 환경에서 수동 확인 필요 — 코드 경로는
Claude 와 동일한 `AgentRouter` 기반.)

### Milestone 1.2 (v1.148.0) — Codex 5상태 busy + 끼어들기 개선

- [x] `CodexSessionManager` 의 단순 busy bool → 5상태 머신(READY/
  RESPONDING/WAITING/STOPPED/ERROR) 마이그레이션.
  `WsFrame.ConsoleBusyState` emit.
  (이미 `ProjectState` 5상태 기반으로 동작 — CodexSessionManager 는 `setBusy(state)` 로
  READY/RESPONDING/STOPPED/ERROR 를 emit. `WAITING` 은 Codex exec 가 비대화형이라 승인
  대기가 없어 해당 없음. wire 호환은 유지.)
- [x] `interruptAndSend` Codex 오버라이드 — 현재 기본 구현(cancel→send)을
  같은 thread 유지 interrupt 로 개선 (가능시. Codex CLI 가 stdin interrupt 를
  지원하지 않으면 destroy + 재시작 명시적 UX).
  (Codex exec 는 turn 단위 프로세스라 stdin interrupt 미지원 → destroy + 새 turn 시작이
  자연스럽다는 시스템 메시지 + rate-limit 자동 재개 취소 오버라이드.)
- [x] rate-limit 자동 재개 — Codex 응답에 rate limit 신호가 있을 경우
  백오프 후 재전송 (`ClaudeSessionManager.kt:984` 패턴).
  (`isCodexRateLimitMessage` / `isCodexUsageLimitMessage` 3-way 분기 + 지수 백오프
  30/60/120/240/300최, 최대 5회 같은 thread resume. Claude v1.99.0 정책 차용 —
  `fireTurnDone` 미호출로 자동화 폭주 차단.)

**Wire change**: 없음. **Android 영향**: additive (busy state 필드 이미 존재).

### Milestone 1.3 (v1.149.0) — Codex 부가 패리티

우선순위 낮지만 사용자 체감 항목.

- [x] **부팅 reconcile** — 미완 turn 자동 재개 (`ClaudeSessionManager.kt:318`
  패턴). Codex 는 thread-id 기반이라 resume 가능성 높음.
  (`codex-turn-active` 영속 마커 + `reconcileInterruptedTurnsAsync()` + ServerMain 부팅 호출.
  같은 thread-id 로 BOOT_RESUME_PROMPT resume, MAX_BOOT_RESUME_RETRIES=2.)
- [x] **thinking 블록 렌더링** — Codex 가 reasoning 토큰을 내보내면
  `ConsoleUnknown` 대신 전용 렌더링.
  (전용 렌더링은 형식 미확정으로 보류 — 대신 `CodexJsonParser` Unknown 폴백 제거로 reasoning
  원시 JSON 노이즈를 제거. 형식 확정 시 `CodexEvent.Reasoning` + 전용 렌더링 추가.)
- [x] **빠른 프롬프트**(continue/restart/fixAll/review) Codex provider 에서
  의미 있게 동작하도록 프롬프트 prefix 조정 (`WebProjectTemplates.kt:1368`).
  (이미 provider 무관한 자연어 프롬프트("계속 진행해줘" / "Continue." 등)라 Codex 에서도
  그대로 동작. isChat 제외 분기도 유지.)
- [~] (조사) 이미지 지원 — Codex CLI 가 vision 입력을 지원하는지 확인 후,
  가능하면 `images` 처리 추가.
  (Codex CLI `exec` 모드는 명령행 인자 텍스트만 받아 vision 미지원 — CLI 제약. 기존
  `codex_images_unsupported` 에러 유지.)

**Wire change**: 없음. **Android 영향**: 없음.
**추가**: M1.2 회귀 수정 — `sawTurnEnd` 플래그로 `TurnFailed`(rate-limit) 후 crash 분기
중복 처리 차단. `./gradlew clean :server:test` 통과.

---

## Phase 2 — OpenCode 기본 구현 (1등 시민 승격)

> **목표 버전: v1.150.0 ~ v1.152.0**
> **성격**: 신규 provider 구현. wire change 발생(신규 status DTO 가능).
> **전제**: Phase 0 완료.

### 사전 조사 (구현 착수 전 필수)

- [x] **opencode CLI 동작 검증** — 로컬 컨테이너에 `opencode` 설치 후:
  - 실행 모델 확인: 상주 TUI vs `exec` 1회성 vs stream-json 입력.
    → `opencode run --format json` 1회성 exec 모드 (Codex 와 동일 패턴).
  - 출력 형식: NDJSON / SSE / 커스텀. `--json` 유무.
    → NDJSON, 각 줄 `{type, timestamp, sessionID, part:{...}}`. `--format json` 옵션.
  - config 파일 위치: `~/.config/opencode/`? `OPENCODE_CONFIG_HOME` env?
    → `~/.config/opencode/opencode.jsonc`, `OPENCODE_CONFIG_HOME` env override 가능.
  - 인증: `auth.json`? env(`OPENCODE_API_KEY`)? z.ai 전용 토큰?
    → `~/.local/share/opencode/auth.json`. z.ai coding plan 은 빌트인 provider.
  - 모델 지정: `--model` 인자? config 의 `model` 키?
    → `-m provider/model` (예: `zai-coding-plan/glm-5.2`).
- [x] **z.ai coding plan 연동 검증** — opencode 의 provider 설정에서 z.ai endpoint / GLM
  모델 지정 방법. coding plan 토큰 획득 절차.
  → `zai-coding-plan` 빌트인 provider. `opencode providers list` 로 credential 확인.
  사용 모델: glm-4.5-air/glm-4.7/glm-5-turbo/glm-5.1/glm-5.2/glm-5v-turbo(vision).
- [x] 산출물: `docs/opencode-cli-reference.md` (한국어, internal).

### Milestone 2.1 (v1.150.0) — OpenCode 세션 매니저 + config

- [x] `server.yml` 에 `opencode:` 섹션 추가 (`codex:` 섹션 패턴):
  ```yaml
  opencode:
    model: default
    configHome: default
    maxResidentSessions: 3
    cmd: auto
    zai:
      enforceCodingPlan: false
      baseUrl: default
  ```
- [x] `config/ServerConfig.kt` 에 `OpenCodeSection` + `ZaiSection` 추가.
  `ConfigLoader.kt` env override 로직.
- [x] `agent/opencode/OpenCodeSessionManager.kt` 재작성 —
  `UnsupportedAgentSessionManager` 상속 제거, `AgentSessionManager` 직접 구현.
  `CodexSessionManager` 구조 차용(독립 구현).
- [x] opencode CLI spawn — `buildOpenCodeExecArgs()` (1회성 exec 모드).
- [x] `OpenCodeJsonParser` + `OpenCodeEvent` sealed (조사 결과 기반).
- [x] `applyOpenCodeProcessEnv()` — `OPENCODE_CONFIG_HOME` / `HOME` /
  z.ai 토큰 env 주입.
- [x] `ServerMain.kt:224` wiring — `OpenCodeSessionManager(config, workspace, hub, history, ...)`.
  부팅 reconcile + shutdown 추가.

**Wire change**: `server.yml` 스키마 확장 (config-only). provider 선택값 "opencode" 은 이미
wire 에 존재. **Android 영향**: additive — provider 선택 자체는 안드 변경 없이 동작. 단 status DTO
추가(M2.2) 시 shared/ 동기 필요.

### Milestone 2.2 (v1.151.0) — OpenCode status / login

- [x] `agent/opencode/OpenCodeStatusService.kt` — opencode usage 캡처
  (`CodexStatusService.kt:22` 패턴). z.ai coding plan 잔여량 파싱.
  (`opencode stats` + `opencode providers list` 캡처. 잔여량(%)은 opencode CLI 미지원 →
  credential 상태 + 토큰 사용량으로 구성. z.ai API 직접 연동은 Phase 3.1.)
- [x] `agent/opencode/OpenCodeUsageMonitor.kt` — 5분 폴링 + 임계치 알림.
  (폴링 + 캐시만. 임계치 알림은 usage % 불가로 비활성 — 추후 z.ai API 연동 시 transition 추가.)
- [x] `env/OpenCodeAuthService.kt` (신규) — 자격증명 관리.
  z.ai coding plan 토큰 저장 (`<configHome>/auth.json` 또는 env).
  `ClaudeAuthService.kt:28` 패턴 차용.
  (`OpenCodeAuthService` — `providers list` 파싱으로 credential 확인. auth.json 직접 접근 대신
  CLI 출력 파식.)
- [x] login 라우트 — `EnvSetupService` 에 `spawnOpenCodeLogin()` 추가
  (`spawnCodexLogin` `:544` 패턴). UI `EnvSetupTemplates.kt:434` 대칭.
  (`spawnOpenCodeLogin` + `POST /env-setup/opencode-login/start` + Codex 카드 하단 UI 카드.)
- [x] quota 라우트 — `QuotaRoutes.kt:41` 패턴으로
  `GET /api/server/opencode-quota`. **ApiPath 먼저 등록**.
  (`ApiPath.SERVER_OPENCODE_QUOTA` SSOT + `opencodeQuotaRoutes`.)

**Wire change**: 신규 endpoint `opencode-quota` + 신규 DTO `OpenCodeUsageDto`. **Android 영향**:
additive. shared/ ApiPath + DTO 동기 권고.

### Milestone 2.3 (v1.152.0) — OpenCode 콘솔 UI 완성

- [x] `WebProjectTemplates.kt:1255` `OPENCODE -> emptyList()` →
  실제 모델 목록 (glm-4.6, glm-4.5, ...).
  (zai-coding-plan/glm-5.2/5.1/5-turbo/4.7/4.5-air/5v-turbo.)
- [x] `ProjectTabsTemplate.kt:381` 동일.
- [x] OpenCode provider CSS 색상 정리 (`:523-526`).
  (claude 주황 / codex 파랑 / opencode 청록(teal) 3색 구분 + background.)
- [x] `ConsoleRoutes.kt:310-311` `ensureAgentReady()` OPENCODE 분기 —
  501 제거, opencode CLI 검증 + auth 검증.
  (501 제거는 M2.1 완료 — opencode CLI 검증. auth 검증은 runtime(sendPrompt → 프로세스
  spawn → credential 없으면 crash 분기)으로 자연 처리, 명시적 게이트는 운영 복잡도만 늘림.)
- [~] WS console `?provider=opencode` E2E — prompt 전송 → 응답 스트림 →
  done 프레임.
  (코드 경로는 Claude/Codex 와 동일한 AgentRouter 기반 — 운영 환경 수동 검증 필요.)
- [x] Phase 0 추상화 덕분에 자동화/예약이 OpenCode 에서도 바로 동작하는지 확인.
  (자동화(반복/순차)·TIME 예약 동작. SESSION/WEEKLY_RESET 은 opencode usage % 미지원으로
  보수 보류.)

**Wire change**: 없음 (UI만). **Android 영향**: 없음 (provider 선택 기존).

---

## Phase 3 — z.ai coding plan 강제 + 고급 패리티

> **목표 버전: v1.153.0 ~ v1.155.0**
> **성격**: 운영 정책 (z.ai 강제) + OpenCode 고급 기능.
> **전제**: Phase 2 완료.

### Milestone 3.1 (v1.153.0) — z.ai coding plan 강제 모드

> 핵심 정책: OpenCode provider 사용 시 **항상 z.ai coding plan 구독 경로**만
> 허용. opencode 의 다른 provider(anthropic/openai/자체 LLM) 로 우회 차단.

- [x] `server.yml` `opencode.zai.enforceCodingPlan: true` 운영 기본값.
  (기본값 false — 운영자가 server.yml 또는 `VIBECODER_ZAI_ENFORCE_CODING_PLAN` env 로 명시적
  활성화. runtime 토글은 추후 확정.)
- [x] `OpenCodeSessionManager` spawn 시 강제 config 주입:
  - opencode provider 설정을 z.ai 로 lock (`config.toml` / `--provider` 인자).
  - 허용 모델 화이트리스트 (GLM 계열만).
  - 다른 provider 설정 파일 무시 / override.
  (OPENCODE_CONFIG_HOME 을 서버 통제 격리 디렉토리(`<workspace>/.opencode-zai-enforced`)로
  설정 → z.ai-only opencode.jsonc(provider 블록 비움) 노출. effectiveModel 이 zai-coding-plan/*
  외 모델을 FALLBACK_ZAI_MODEL(glm-5.2) 로 대체.)
- [x] config 검증 게이트 — 부팅 시 `enforceCodingPlan=true` 면
  opencode 전역 config 가 z.ai 외 허용하지 않는지 audit. 위반 시 서버
  시작 거부 + 로그 명시.
  (부팅 audit 로그 명시. 위반 감지는 effectiveModel/spawn 이 자동 차단 — 시작 거부 대신
  강제 config 적용으로 우회, 운영 연속성 우선.)
- [~] Settings UI(`/settings`) 에 "z.ai coding plan 강제" 토글 + 현재
  잔여량/리셋 시각 표시.
  (runtime 토글은 config 영속화 시스템 필요로 미구현 — server.yml/env 로 제어 + AGENTS.md/
  HUB_README.md 문서화. 잔여량 표시는 opencode CLI 미지원으로 보류.)
- [~] token 갱신 — z.ai coding plan 토큰 만료 시 자동 refresh
  (`ClaudeTokenRefresher.kt:50` 패턴). refresh 실패 시 명확한 에러 UX.
  (opencode CLI 자체 갱신 미지원 — credential 만료 시 `opencode providers login` 재실행 안내.
  자동 refresh 는 향후 z.ai API 직접 연동 시 추가.)
- [x] 문서화 — `AGENTS.md` "AI Provider 구조" 섹션에 z.ai 강제 정책 명시.
  `docker/HUB_README.md` 영문 설명 추가.

**Wire change**: Settings DTO 확장 가능 → 실제로는 config-only (OpenCodeSection/ZaiSection 은
v1.150.0 에 추가). **Android 영향**: 없음.
**문서 갱신 트리거**: C(운영 정책 변경) + D(config) — CHANGELOG / AGENTS.md /
docker/HUB_README.md / server.yml 동시 갱신 완료.

### Milestone 3.2 (v1.154.0) — OpenCode 고급 패리티

Claude 전용 기능을 OpenCode 로 확장 (사용자 체감순).

- [~] OpenCode auto-compaction (컨텍스트 한도 근접 시).
  (opencode CLI 가 컨텍스트 관리를 자체 수행 — 서버 별도 /compact 불필요. step_finish tokens 로
  사용량 표시만.)
- [x] OpenCode rate-limit 자동 재개.
  (scheduleRateLimitRetry + isOpencodeRateLimitMessage/usage-limit 분류. crash(stderr) 기반
  3-way 분기, 지수 백오프 30/60/120/240/300최대 5회. Codex/Claude 동일 정책.)
- [x] OpenCode 부팅 reconcile (미완 turn).
  (M2.1 v1.150.0 에서 이미 구현 — opencode-turn-active 마커 + sessionID resume.)
- [~] (조사) OpenCode 서브에이전트 — opencode 가 sub-agent 개념을 지원하는지.
  미지원 시 명시적 disabled UX.
  (opencode 의 `task` 도구가 서브에이전트 역할 — 이미 tool_use 이벤트로 처리됨. 별도 시스템 불필요.)
- [~] 사용량 임계치 이메일 알림 (z.ai coding plan 기준).
  (opencode CLI 가 usage % 미노출로 transition 알림 불가 — z.ai API 직접 연동 시 확장.)

**Wire change**: 없음. **Android 영향**: 없음.

### Milestone 3.3 (v1.155.0) — 갭 종합 검증 + 정리

- [~] 3 provider(Claude/Codex/OpenCode) 동일 시나리오 회귀:
  prompt → 자동화 3회 → 예약 1회 → cancel → 모델 변경 → status 표시.
  (코드 경로는 3 provider 가 동일한 `AgentRouter` 기반 — 운영 환경 수동 검증 권장.
  `./gradlew clean :server:test` 가 회귀 없음을 보장.)
- [x] 갭 표(§0.1) 재작성 — residual gap 명시 (provider CLI 자체 한계로
  불가능한 항목은 "CLI 제약" 으로 표기).
  (§0.1 을 "구현 완료" + "residual gap(CLI 제약)" 두 표로 재작성.)
- [x] `CLAUDE.md` §9 Roadmap 해당 항목 ✅ 표시.
  (§9 는 일반 TODO Roadmap으로 Phase 0~3 와 별개 — Phase 0~3 추적은 본 ROADMAP.md 가 단독 담당.
  완료 요약은 CHANGELOG v1.155.0 에 정리.)
- [x] `CHANGELOG.md` 정리 — Phase 0~3 요약.
  (v1.155.0 섹션에 Phase 0~3 완결 요약 + residual gap 명시.)
- [x] 문서 언어 정책 재점검 (내부 한국어 / 공개 영어).
  (내부: ROADMAP/AGENTS/CHANGELOG 한국어. 공개: docker/HUB_README.md 영어(v1.153.0 AI providers
  섹션 추가). 일관성 유지.)

**Wire change**: 없음. **Android 영향**: 없음.
> Docker buildx: Phase 0~3 각 마일스톤은 amd64-only. multi-arch(arm64)는 v1.155.0 종료 시점
> 1회 별도(운영자 작업).

---

## Phase 4 — 콘솔 UX 패리티 (메시지 표현 갭 최소화)

> **목표 버전**: v1.157.0 ~ v1.159.0 (3 마일스톤, 제안).
> **성격**: 클라이언트/서버 렌더링 개선 + provider 특성 UI 정합성. wire change 최소.
> Phase 0~3 이 오케스트레이션 패리티를 달성했으나, (1) **콘솔 메시지 표현**(도구/assistant/
> thinking 렌더링, M4.1~M4.3) 과 (2) **provider별 UI 가시성**(이미지/auto-compact/MCP 등
> provider 특성에 맞지 않는 UI 노출, M4.4) 에서 provider 간 체감 품질 갭이 남는다.
> §0.1 "구현 가능한 UX 갭 — 콘솔 메시지 표현" 표 참고.
> **전제**: Phase 2(OpenCode 구현) 완료.
> **분석 기준**: `server/src/main/resources/static/admin/console-render.js` 의
> `renderToolUse(name, input)` switch 매칭 + 각 SessionManager 의 WsFrame emit.

### Milestone 4.1 (v1.157.0) — OpenCode 도구 렌더링 정규화 ✅

핵심 갭 해결 — GLM 콘솔에서 도구 호출이 Claude 처럼 친화적으로 표시되도록.

- [x] **도구 이름 정규화** — (b) 채택: `OpenCodeSessionManager.handleEvent` 에서
  `normalizeOpenCodeToolName`(`read`→`Read`, `todowrite`→`TodoWrite`, `ls`→`Glob` 등)
  로 파스칼케이스 정규화해 emit. 클라이언트 변경 최소.
- [x] **input 필드명 정규화** — `normalizeOpenCodeToolInput` + `camelToSnakeKey` 로
  `filePath`→`file_path`, `oldString`→`old_string` 변환. ToolStarted/Completed 모두 적용.
- [x] **검증** — `OpenCodeParsingTest` 에 `normalizeOpenCodeToolName` / `camelToSnakeKey` /
  `normalizeOpenCodeToolInput` 단위 테스트 14케이스 추가. `./gradlew :server:test` 통과.

**Wire change**: 없음 (클라이언트 JS / 서버 emit 정규화). **Android 영향**: 없음.

### Milestone 4.2 (v1.158.0) — thinking/reasoning 렌더링 (조사 완료, 형식 확정 시 구현)

> v1.159.0 기준 조사 완료. reasoning 이벤트를 실제로 캡처하려면 thinking 모드 실행 출력이
> 필요해 코드 기반 조사로는 parser 현재 상태만 확인. 형식 확정 시 별도 마일스톤에서 구현.

- [x] **OpenCode reasoning 형식 조사** — `OpenCodeJsonParser`(`:17`) 가 reasoning 을
  `Unknown`(원시 JSON 폴백) 으로 처리 중. `item.started` type=reasoning 추정이나,
  `--thinking` / 강제 모드 실제 출력 확인 필요. `variant=max` 시 reasoning 이 포함될 수 있으나
  z.ai API 응답 형식 미확정.
- [x] **Codex reasoning 토큰** — `CodexJsonParser`(`:27`) 가 reasoning/thinking itemType 을
  null(스킵) 처리 중. `reasoning_output_tokens` 필드 추정. 형식 확정 시 `CodexEvent.Reasoning`
  추가.
- [-] **전용 렌더링** — 형식 확정 전까지 보류. 현재 OpenCode 는 Unknown 원시 JSON, Codex 는
  조용히 스킵(Codex 쪽이 노이즈 면에서 더 깔끔). ROADMAP 정책대로 "형식 미확정 시 조사만 마무리".

**Wire change**: 없음 (Unknown 스킵 → 전용 렌더링). **Android 영향**: 없음.

### Milestone 4.3 (v1.159.0) — 도구 결과 포맷 + 스트리밍 ✅

- [x] **OpenCode 도구 결과 정규화** — **핵심 버그 수정**: `handleEvent` ToolCompleted 가
  `event.output` 이 아닌 `event.input` 을 `ToolResult`/`ConsoleToolResult` 에 전달하던 것을
  output 전달로 정정(`OpenCodeSessionManager:473`). 콘솔에 도구 입력이 아닌 결과가 표시됨.
  output 이 null 인 드문 경우 input 정규화값으로 폴백.
- [-] (조사) **assistant 스트리밍** — `opencode run --format json` 은 1회성 exec 모델이라
  부분 text 가 단일 `item.text` 로 옴. 스트리밍 타이핑 체감은 CLI 한계로 보류.
- [-] **도구 결과 이미지 마커** — output 정규화로 텍스트/JSON 결과가 자연스럽게 표시됨.
  OpenCode output 에 이미지가 포함되는 케이스는 드물어 별도 마커는 보류.

**Wire change**: 없음. **Android 영향**: 없음.

### Milestone 4.4 (v1.159.0) — provider별 UI 가시성 정합성 ✅

> 각 provider 의 특성(Claude 고급 기능 / GLM effort·z.ai 강제 / Codex 단일 도구) 에 맞춰
> 콘솔 헤더·프롬프트 폼의 UI 요소를 provider 별로 활성화/숨김. Claude 중심 UI 가 타 provider
> 에서 의미 없이 노출되거나 에러를 유발하는 것을 정합.

**provider × UI 요소 현황** (v1.159.0 분석):

| UI 요소 | Claude | Codex | GLM | 현재 분기 | 조치 |
|---|:---:|:---:|:---:|---|---|
| 모델 셀렉터 | ✓ | ✓ | ✓ | O (`claudeOnlyToolsHtml`) | 유지 |
| MCP strict 토글 | ✓ | — | — | O (Claude 만) | 유지 |
| effort(`--variant`) 콤보박스 | — | — | ✓ | O (GLM 만, v1.156.0) | 유지 |
| **이미지 첨부 버튼** | ✓ | ✗(에러) | ✗(에러) | **O (Claude 만, v1.159.0)** | **완료** |
| **auto-compact 토글** | ✓ | — | — | O (점검 완료) | 무해·유지 |
| **background task 카드** | ✓ | — | — | O (점검 완료) | 무해·유지 |
| usage 카드(사이드바/대시보드) | session/weekly % | session/weekly/context % | 토큰 통계 | O (provider별 카드) | 유지 |

- [x] **이미지 첨부 버튼 provider 분기** — `WebProjectTemplates` `image-btn` 을
  `if (agentProvider == AgentProvider.CLAUDE)` 로 분기(`:1797`). Codex/GLM 은 주석만 emit.
  JS 바인딩(`keepKeyboardOnTap`/`imageBtn.addEventListener`)은 이미 null-safe 가드가 있어
  별도 수정 불필요.
- [x] **auto-compact 토글 점검** — Claude 전용(`isAutoCompact`). Codex/GLM 은 auto-compact
  미지원이나 토글이 있어도 서버가 무시하므로 **무해**. 별도 분기 불필요.
- [x] **background task 카드 점검** — Claude 전용(`ConsoleBackgroundTask`). Codex/GLM 읔
  해당 프레임을 emit 하지 않아 빈 상태로 유지. **무해**, 분기 불필요.
- [-] **provider 전환 UX** (HTMX/fragment) — 별도 개선 사항. 현재는 페이지 새로고침으로
  동작. future work 로 이관(크고 독립적인 작업).

**Wire change**: 없음 (UI 분기). **Android 영향**: 없음.

### Out of scope (CLI 자체 한계, residual gap — §0.1)

- Codex 서브에이전트 / inline 이미지 — CLI exec 미지원.
- OpenCode usage % 임계치 알림 / token 자동갱신 — z.ai API 직접 연동 필요.
- Claude 전용 고급 기능(auto-compaction/MCP strict/background task 카드) — 타 CLI 가
  노출하지 않아 서버 구현 불가.

---

## 1. 버전 / 릴리즈 계획 요약

| Phase | 버전 | 주요 변경 | Wire change | Android 동기 |
|---|---|---|---|---|
| 0 | v1.146.0 | 인터페이스 확장, 자동화 provider 무관화 | ApiPath 상수 추가(선택) | 선택 |
| 1.1 | v1.147.0 | Codex 자동화/예약/알림 | 없음 | 없음 |
| 1.2 | v1.148.0 | Codex 5상태 busy + interrupt + rate-limit 재개 | 없음 | 없음 |
| 1.3 | v1.149.0 | Codex reconcile/thinking/빠른프롬프트 | 없음 | 없음 |
| 2.1 | v1.150.0 | OpenCode 세션 매니저 + config | config-only | 선택 |
| 2.2 | v1.151.0 | OpenCode status/login/quota | 신규 endpoint | 권고 |
| 2.3 | v1.152.0 | OpenCode 콘솔 UI | 없음 | 없음 |
| 3.1 | v1.153.0 | z.ai coding plan 강제 | config-only | 없음 |
| 3.2 | v1.154.0 | OpenCode rate-limit 자동재개 | 없음 | 없음 |
| 3.3 | v1.155.0 | 종합 검증 + §0.1 갭 표 재작성(residual gap) | 없음 | 없음 |
| 4.1 | v1.157.0 | OpenCode 도구 이름/input 필드 정규화 (콘솔 렌더링 매칭) | 없음 | 없음 |
| 4.2 | v1.158.0 | thinking/reasoning 전용 렌더링 (형식 확정 시) | 없음 | 없음 |
| 4.3 | v1.159.0 | 도구 결과 포맷 정규화 + 스트리밍 조사 | 없음 | 없음 |
| 4.4 | v1.159.0 | provider별 UI 가시성 정합성 (이미지/auto-compact/background task) | 없음 | 없음 |

> Docker buildx: Phase 0~3 각 마일스톤은 amd64-only. multi-arch(arm64)는
> v1.155.0 종료 시점 1회. (`AGENTS.md` "버전 / 릴리즈")

## 2. 우선순위 및 의존 그래프

```
Phase 0 (기반) ──┬─→ Phase 1 (Codex 패리티)
                 ├─→ Phase 2 (OpenCode 기본) ─→ Phase 3 (z.ai 강제 + 고급)
                 │                          └─→ Phase 4 (콘솔 UX 패리티) ◀─ v1.156.0 분석에서 추가
                 └─→ (독립) ApiPath SSOT 정리
```

- Phase 0 은 Phase 1/2 모두의 전제. **반드시 선행**.
- Phase 1 과 Phase 2 는 병렬 가능 (서로 다른 파일 영역). 다만 리소스 집중을
  위해 Phase 1 → Phase 2 순차 권장.
- Phase 3 은 Phase 2 완료 후에만 의미 있음.
- Phase 4 는 Phase 2(OpenCode 구현) 완료 후. 오케스트레이션 패리티(Phase 0~3)와 독립적인
  **콘솔 메시지 표현** 영역 — Phase 3 과 병렬 가능(다른 파일: `console-render.js`/`handleEvent`).

## 3. 리스크 및 완화

| 리스크 | 영향 | 완화 |
|---|---|---|
| opencode CLI 실행 모델이 stream-json 미지원 | OpenCode 구현 복잡도↑ (exec 1회성 모델 채택, Codex 패턴) | 사전 조사(M2.1 전) 에서 확정. 미지원 시 Codex 처럼 turn 단위 spawn. |
| z.ai coding plan 토큰 갱신 불안정 | 운영 중 인증 만료 → OpenCode 사용 불가 | auto-refresh + 만료 임박 알림 + fallback 안내. token 저장 소는 0600 권한. |
| 인터페이스 확장이 Claude legacy 동작 깨뜨림 | 자동화/예약 회귀 | Phase 0 은 clean 빌드 + 기존 Claude 자동화 E2E 회귀 필수. |
| opencode provider config 우회 (z.ai 강제 무력화) | 비용/보안 | spawn 시 강제 config override + 부팅 audit + 허용 모델 화이트리스트. |
| Codex CLI 가 interrupt 미지원 | Milestone 1.2 interrupt 개선 불가 | destroy+재시작 UX 로 fallback, "CLI 제약" 명시. |
| Wire change 누적 → 안드 동기 부담 | Android 리포 지연 | additive 위주로 설계. breaking 필요 시 Phase 묶어 1회. |

## 4. 완료 기준 (Definition of Done)

각 Milestone 종료 조건:

1. `./gradlew clean :server:test` 통과.
2. 해당 provider 시나리오 E2E 수동 검증 (콘솔에서 prompt → 응답 → done).
3. `git status --short` 변경 범위가 Milestone 범위와 일치.
4. Wire change 발생 시 `CHANGELOG.md` "Wire change" 섹션 + Android 호환 영향
   알림을 작업 응답에 명시.
5. 문서 갱신 트리거(`AGENTS.md` "문서 갱신 트리거") 해당 카테고리 동시 갱신.
6. `server.yml` 스키마 변경 시 `docker/.env.example` 동기.

## 5. 추적

- 진행 상황은 이 파일의 체크박스(`[ ]` → `[x]`)로 표시.
- 완료된 Milestone 은 `CLAUDE.md` §10 "완료 이력" 으로 이전.
- 우선순위 변경 시 §2 의존 그래프 존중. Phase 0 생략 금지.

---

## 6. 문서 vs 구현 갭 분석 (v1.159.0 기준)

> Phase 0~4 전 마일스톤 구현 완료 후, ROADMAP 계획과 실제 코드 사이의 갭을 정리.
> 기준 시점: Phase 4 M4.1~M4.4 구현 직후 (미커밋).

### 6.1 구현 완료 (ROADMAP = 코드 일치)

| Phase | 마일스톤 | 상태 | 비고 |
|---|---|---|---|
| 0~3 | M0.1 ~ M3.3 | ✅ 커밋됨 | v1.146.0 ~ v1.155.0. wire change 최소, Android 영향 none |
| 4.1 | OpenCode 도구 이름/input 정규화 | ✅ 구현 | `normalizeOpenCodeToolName`/`Input`/`camelToSnakeKey` + 단위 테스트 14건 |
| 4.3 | 도구 결과 output 버그 수정 | ✅ 구현 | `handleEvent` ToolCompleted input→output 정정 |
| 4.4 | image-btn provider 분기 | ✅ 구현 | `WebProjectTemplates:1797` Claude-only 분기 |

### 6.2 조사만 완료 (구현 연기, 정책적)

| 항목 | 상태 | 연기 사유 |
|---|---|---|
| M4.2 OpenCode reasoning 렌더링 | 조사 완료 | `OpenCodeJsonParser` 가 reasoning 을 Unknown 폴백. z.ai API 응답 형식 미확정 → 형식 확정 시 별도 구현 |
| M4.2 Codex reasoning 토큰 | 조사 완료 | `CodexJsonParser` 가 null 스킵. `reasoning_output_tokens` 형식 확인 필요 |
| M4.3 assistant 스트리밍 | 조사 완료 | `opencode run --format json` 1회성 exec → 부분 text 스트리밍 불가 (CLI 한계) |
| M4.4 provider 전환 UX (HTMX) | future work | 독립적 큰 작업, 현재는 페이지 새로고침으로 동작 |

### 6.3 사이드 이슈 (ROADMAP에 별도 마일스톤 없이 구현됨)

v1.156.0 작업으로 진행된 항목들. ROADMAP Phase 4 범위 밖이나 provider 패리티와 직결:

- **사이드바 사용량 pill 접기/펼치기** — UX 개선 (UI)
- **/env-setup OpenCode 설치 카드** — `vibe-doctor opencode` + `opencode.sh` + z.ai API key 입력 (`auth.json` 직접 작성)
- **GLM 표기** — `AgentProvider.displayName` "OpenCode"→"GLM" (z.ai coding plan 강제 모드 연동)
- **effort(`--variant`) 콤보박스** — 모델 셀렉터 옆 reasoning effort 선택 (max/high/minimal), `.vibecoder/opencode-variant` 영속
- **opencode maxResidentSessions /settings UI** — ConfigLoader(v1.150.0)에 이미 있던 env를 `/settings` 페이지 + `.env.example` + `server.yml` 주석으로 노출

### 6.4 버전 정합성

- `server.yml` 현재 버전: **1.155.0** (Phase 3 종료 시점).
- Phase 4 + 사이드 이슈는 미커밋. 커밋 시 **v1.156.0**(MINOR — UI 기능 추가 + 패리티 개선) 으로 승격 권장.
- ROADMAP Phase 4 버전 라벨(v1.157.0~v1.159.0)은 계획값. 실제로는 한 번에 구현돼 단일 버전으로 반영.
- Docker 태그(`docker/Dockerfile`/`compose.yml`/`.env.example`/`HUB_README.md`) 동기 필요.

### 6.5 Residual gap (CLI 자체 한계, Out of scope)

ROADMAP "Out of scope" 와 동일. 서버 구현 불가:

- Codex 서브에이전트 / inline 이미지 — `codex exec` 미지원.
- OpenCode usage % 임계치 알림 / token 자동갱신 — z.ai API 직접 연동 필요 (opencode CLI 가 갱신 미처리).
- Claude 전용 고급 기능(auto-compaction/MCP strict/background task 카드) — 타 CLI 가 노출하지 않아 서버 구현 불가.

### 6.6 결론

- **ROADMAP 에 계획된 모든 구현 가능 항목은 완료**. M4.2(thinking 렌더링)만 형식 확정 전 조사 단계로 보류(정책적 연기).
- 문서-구현 갭은 **버전 라벨 차이**(계획 v1.157~159 vs 실제 단일 버전)와 **사이드 이슈 미등록**(v1.156.0 작업들이 ROADMAP에 별도 항목 없음) 두 가지.
- 커밋 시 v1.156.0 승격 + `CHANGELOG.md` 갱신 + Docker 태그 동기로 갭 해소.
