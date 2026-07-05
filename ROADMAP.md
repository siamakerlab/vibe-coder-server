# ROADMAP — Provider 갭 최소화 및 OpenCode(z.ai) 구현

> 내부 문서 (한국어). `CLAUDE.md` §9 Roadmap 과는 별개로, **Provider 3축(Claude /
> Codex / OpenCode) 기능 패리티 + z.ai coding plan 강제**라는 구체적 목표를
> 단계별로 추적하기 위한 독립 로드맵.
>
> 기준 버전: **v1.145.18** (2026-07). 각 Phase 의 목표 버전은 구현 시점의
> SemVer 정책(`CLAUDE.md` "버전 정책")에 따라 조정 가능. 인터페이스 breaking 은
> 1.x 단계이므로 MINOR 로 처리 (SemVer 2.0.0 §4).

## 0. 배경 및 목표

### 0.1 현황 (갭 요약)

| 영역 | Claude | Codex | OpenCode |
|---|:---:|:---:|:---:|
| 세션 매니저 | 1,702줄 풀구현 | 494줄 최소구현 | **8줄 stub** (`Unsupported…`) |
| prompt 전송 | O | O | **501 에러** |
| 자동화 / 예약 | O | **X** | **X** |
| 서브에이전트 | O | X | X |
| 끼어들기(⚡, 세션 유지) | O | 기본구현(cancel→send) | X |
| 이미지 / vision | O | X | ? |
| auto-compaction | O | X | ? |
| rate-limit 자동재개 | O | X | ? |
| 부팅 reconcile | O | X | ? |
| MCP strict | O | X | ? |
| 백그라운드 작업 카드 | O | X | ? |
| 토큰 자동갱신 | O | X | ? |
| 사용량 임계치 알림 | O | X | ? |
| 5상태 busy 머신 | O | 단순 busy | X |
| thinking 렌더링 | O | X | ? |
| `server.yml` 섹션 | O | O | **없음** |
| status / usage service | O | O | **없음** |
| login 플로우 | O | env 만 | **없음** |
| WS topic 분리 | O | O | O (이미 완비) |

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

- [ ] `agent/opencode/OpenCodeStatusService.kt` — opencode usage 캡처
  (`CodexStatusService.kt:22` 패턴). z.ai coding plan 잔여량 파싱.
- [ ] `agent/opencode/OpenCodeUsageMonitor.kt` — 5분 폴링 + 임계치 알림.
- [ ] `env/OpenCodeAuthService.kt` (신규) — 자격증명 관리.
  - z.ai coding plan 토큰 저장 (`<configHome>/auth.json` 또는 env).
  - `ClaudeAuthService.kt:28` 패턴 차용.
- [ ] login 라우트 — `EnvSetupService` 에 `spawnOpenCodeLogin()` 추가
  (`spawnCodexLogin` `:544` 패턴). UI `EnvSetupTemplates.kt:434` 대칭.
- [ ] quota 라우트 — `QuotaRoutes.kt:41` 패턴으로
  `GET /api/server/opencode-quota`. **ApiPath 먼저 등록**.

**Wire change**: 신규 endpoint `opencode-quota`. **Android 영향**: additive.
shared/ ApiPath + DTO 동기 권고.

### Milestone 2.3 (v1.152.0) — OpenCode 콘솔 UI 완성

- [ ] `WebProjectTemplates.kt:1255` `OPENCODE -> emptyList()` →
  실제 모델 목록 (glm-4.6, glm-4.5, ...).
- [ ] `ProjectTabsTemplate.kt:381` 동일.
- [ ] OpenCode provider CSS 색상 정리 (`:523-526`).
- [ ] `ConsoleRoutes.kt:310-311` `ensureAgentReady()` OPENCODE 분기 —
  501 제거, opencode CLI 검증 + auth 검증.
- [ ] WS console `?provider=opencode` E2E — prompt 전송 → 응답 스트림 →
  done 프레임.
- [ ] Phase 0 추상화 덕분에 자동화/예약이 OpenCode 에서도 바로 동작하는지 확인.

**Wire change**: 없음 (UI만). **Android 영향**: 없음 (provider 선택 기존).

---

## Phase 3 — z.ai coding plan 강제 + 고급 패리티

> **목표 버전: v1.153.0 ~ v1.155.0**
> **성격**: 운영 정책 (z.ai 강제) + OpenCode 고급 기능.
> **전제**: Phase 2 완료.

### Milestone 3.1 (v1.153.0) — z.ai coding plan 강제 모드

> 핵심 정책: OpenCode provider 사용 시 **항상 z.ai coding plan 구독 경로**만
> 허용. opencode 의 다른 provider(anthropic/openai/자체 LLM) 로 우회 차단.

- [ ] `server.yml` `opencode.zai.enforceCodingPlan: true` 운영 기본값.
- [ ] `OpenCodeSessionManager` spawn 시 강제 config 주입:
  - opencode provider 설정을 z.ai 로 lock (`config.toml` / `--provider` 인자).
  - 허용 모델 화이트리스트 (GLM 계열만).
  - 다른 provider 설정 파일 무시 / override.
- [ ] config 검증 게이트 — 부팅 시 `enforceCodingPlan=true` 면
  opencode 전역 config 가 z.ai 외 허용하지 않는지 audit. 위반 시 서버
  시작 거부 + 로그 명시.
- [ ] Settings UI(`/settings`) 에 "z.ai coding plan 강제" 토글 + 현재
  잔여량/리셋 시각 표시.
- [ ] token 갱신 — z.ai coding plan 토큰 만료 시 자동 refresh
  (`ClaudeTokenRefresher.kt:50` 패턴). refresh 실패 시 명확한 에러 UX.
- [ ] 문서화 — `AGENTS.md` "AI Provider 구조" 섹션에 z.ai 강제 정책 명시.
  `docker/HUB_README.md` 영문 설명 추가.

**Wire change**: Settings DTO 확장 가능. **Android 영향**: additive.
**문서 갱신 트리거**: C(운영 정책 변경) + D(config) — CHANGELOG / README /
Wiki Security-Model / docker README 동시 갱신 필수.

### Milestone 3.2 (v1.154.0) — OpenCode 고급 패리티

Claude 전용 기능을 OpenCode 로 확장 (사용자 체감순).

- [ ] OpenCode auto-compaction (컨텍스트 한도 근접 시).
- [ ] OpenCode rate-limit 자동 재개.
- [ ] OpenCode 부팅 reconcile (미완 turn).
- [ ] (조사) OpenCode 서브에이전트 — opencode 가 sub-agent 개념을 지원하는지.
  미지원 시 명시적 disabled UX.
- [ ] 사용량 임계치 이메일 알림 (z.ai coding plan 기준).

**Wire change**: 없음. **Android 영향**: 없음.

### Milestone 3.3 (v1.155.0) — 갭 종합 검증 + 정리

- [ ] 3 provider(Claude/Codex/OpenCode) 동일 시나리오 회귀:
  prompt → 자동화 3회 → 예약 1회 → cancel → 모델 변경 → status 표시.
- [ ] 갭 표(§0.1) 재작성 — residual gap 명시 (provider CLI 자체 한계로
  불가능한 항목은 "CLI 제약" 으로 표기).
- [ ] `CLAUDE.md` §9 Roadmap 해당 항목 ✅ 표시.
- [ ] `CHANGELOG.md` 정리 — Phase 0~3 요약.
- [ ] 문서 언어 정책 재점검 (내부 한국어 / 공개 영어).

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
| 3.1 | v1.153.0 | z.ai coding plan 강제 | Settings DTO | 권고 |
| 3.2 | v1.154.0 | OpenCode 고급 패리티 | 없음 | 없음 |
| 3.3 | v1.155.0 | 종합 검증 + 문서 정리 | 없음 | 없음 |

> Docker buildx: Phase 0~3 각 마일스톤은 amd64-only. multi-arch(arm64)는
> v1.155.0 종료 시점 1회. (`AGENTS.md` "버전 / 릴리즈")

## 2. 우선순위 및 의존 그래프

```
Phase 0 (기반) ──┬─→ Phase 1 (Codex 패리티)
                 ├─→ Phase 2 (OpenCode 기본) ─→ Phase 3 (z.ai 강제 + 고급)
                 └─→ (독립) ApiPath SSOT 정리
```

- Phase 0 은 Phase 1/2 모두의 전제. **반드시 선행**.
- Phase 1 과 Phase 2 는 병렬 가능 (서로 다른 파일 영역). 다만 리소스 집중을
  위해 Phase 1 → Phase 2 순차 권장.
- Phase 3 은 Phase 2 완료 후에만 의미 있음.

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
