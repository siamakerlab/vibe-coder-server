# TUI-Only Console Roadmap

## Goal

프로젝트 콘솔의 사용자-facing 실행 방식을 TUI 단일 모드로 확정한다. 레거시 stream-json 콘솔 UI는 제거하고, provider CLI TUI를 PTY/xterm으로 유지하면서 프롬프트, 응답 요약, 상태, 히스토리 기록은 DB에 계속 남긴다.

## Current 1st-Step Implementation

- 콘솔 화면은 항상 TUI pane을 연다.
- `TUI 기본` 토글, `TUI 전송`, `TUI 열기/숨기기` 버튼은 제거했다.
- 레거시 JSON 콘솔 로그 영역은 화면에서 항상 숨긴다.
- 폼 제출, 빠른 프롬프트, 자동화의 `vibe:send-prompt`, `/compact`, 끼어들기는 TUI API만 호출한다.
- TUI mode 저장 API는 호환용 no-op으로 두되 항상 ON으로 저장/응답한다.
- `/api/projects/{id}/claude/console/cancel`은 active TUI 세션이 있으면 PTY에 interrupt(ESC)를 보낸다.

## Regression Risks

| Area | Risk | Mitigation |
| --- | --- | --- |
| Android/shared API | `claude/console/prompt`, `interrupt`, `cancel` 제거 시 기존 Android 클라이언트가 깨진다. | 1차에서는 endpoint 유지. Android 전환 후 deprecation/removal. |
| DB history | TUI output은 provider CLI별 저장 위치와 출력 포맷이 다르다. ingest 실패 시 DB에는 user prompt만 남을 수 있다. | `ConsoleTuiHistoryIngestService` provider별 파서 테스트 확대. |
| Automation | 일부 자동화는 `AgentRouter.sendPrompt()`를 직접 호출한다. 이것은 TUI가 아니라 JSON/exec manager를 탄다. | 다음 단계에서 `ConsolePromptDispatcher`를 만들고 모든 자동 prompt를 TUI 주입으로 라우팅. |
| Context meter | Codex/OpenCode TUI는 JSON usage frame이 항상 나오지 않는다. | TUI history/status capture 기반 context snapshot 보강. |
| Image prompt | TUI prompt injection은 이미지 첨부를 지원하지 않는다. | 이미지 첨부 UI 비활성 또는 provider별 paste/file handoff 설계 필요. |
| Cancel semantics | ESC가 provider별로 현재 turn 중단, 메뉴 닫기, 입력 취소로 다르게 동작할 수 있다. | Claude/Codex/OpenCode별 cancel probe와 fallback key sequence 추가. |
| Session ownership | PTY session은 userId/project/provider 키로 유지된다. 서버 재시작 후 process resume은 CLI session resume과 PTY 재생성이 분리된다. | fresh/resume state 파일을 TUI manager 중심으로 통합. |

## Target Architecture

1. `ConsolePromptDispatcher`
   - 단일 진입점: user prompt, automation prompt, broadcast prompt, quality prompt.
   - 내부 동작: active provider의 `ConsoleTuiSessionManager.ensure()` 후 paste/interrupt/compact.
   - DB 기록: `ConversationTurnRepository.insert()`를 dispatcher가 담당.
   - history ingest: prompt 직후 provider별 ingest schedule.

2. Legacy provider managers
   - Claude/Codex/OpenCode JSON/exec managers는 상태 조회, 모델 설정, 기존 session id 정리처럼 필요한 내부 기능만 남긴다.
   - 사용자 prompt 실행 경로에서는 직접 호출하지 않는다.

3. API compatibility
   - 기존 `/api/projects/{id}/claude/console/prompt|interrupt|cancel`는 일정 기간 TUI dispatcher로 delegate한다.
   - Android shared에 TUI endpoint 사용을 반영한 뒤 legacy endpoint 제거 버전을 별도 wire change로 진행한다.

4. UI
   - 콘솔 본문은 TUI 하나만 렌더링한다.
   - DB 히스토리 조회는 별도 History 탭/오버뷰 기록으로 유지한다.
   - 이미지 첨부는 TUI 지원 전까지 비활성 안내 또는 별도 file handoff로 전환한다.

## Execution Plan

1. Phase A: User-facing TUI-only
   - Done: 콘솔 UI에서 JSON mode 전환 제거.
   - Done: 전송/끼어들기/compact UI 경로 TUI 고정.
   - Done: TUI mode settings 항상 ON.

2. Phase B: Dispatcher Unification
   - `ConsolePromptDispatcher` 추가.
   - `ConsoleTuiRoutes.sendTuiPrompt` 로컬 로직을 dispatcher로 이동.
   - `ConsoleRoutes` legacy prompt/interrupt/cancel endpoint를 dispatcher delegate로 변경.
   - `PromptAutomationManager`, `QualityRoutes`, `KeystoreRoutes`, package rename prompt가 dispatcher를 사용하도록 변경.

3. Phase C: Provider-Specific Reliability
   - Claude/Codex/OpenCode cancel key sequence 검증.
   - Codex trust prompt auto-accept와 OpenCode yolo mode regression test 추가.
   - TUI session resume/fresh start 테스트 확대.

4. Phase D: Android Wire Migration
   - `shared/ApiPath`와 Android client를 TUI endpoint 중심으로 동기화.
   - 기존 `claude/console/*` endpoint deprecation notice 추가.
   - 다음 breaking release에서 legacy DTO/path 제거.

5. Phase E: Cleanup
   - 숨겨진 `.console-log-wrap`와 stream-json renderer 의존 제거.
   - 레거시 `PromptAcceptedDto` 기반 console flow 제거.
   - 문서와 CHANGELOG 정리.

## Verification Checklist

- 새 콘솔 진입 시 TUI가 자동으로 열린다.
- 일반 전송 버튼이 `/api/projects/{id}/console/tui/prompt`만 호출한다.
- 끼어들기가 `/api/projects/{id}/console/tui/interrupt`만 호출한다.
- Overview `/compact`가 TUI compact 경로만 호출한다.
- 새 세션 버튼 후 다음 TUI spawn은 resume하지 않는다.
- 서버 재시작 후 기존 TUI session display와 DB history가 깨지지 않는다.
- Android client 영향은 별도 wire migration 전까지 기존 endpoint 유지로 완화한다.
