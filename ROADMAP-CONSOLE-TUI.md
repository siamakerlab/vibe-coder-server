# Console TUI Roadmap

## 목표

기존 JSON 기반 AI 콘솔을 즉시 제거하지 않고, 프로젝트별 provider CLI 를 xterm.js 기반 TUI 로
구동할 수 있는 병행 경로를 추가한다. 화면은 PTY/TUI 가 담당하고, 프롬프트 전송·히스토리
저장·프롬프트 자동화는 기존 서버 API 와 provider native transcript ingest 가 담당한다.

## 원칙

- 기존 `/projects/{id}/console` JSON 콘솔은 legacy fallback 으로 유지한다.
- TUI 화면 출력은 장기 대화 기록의 source of truth 로 사용하지 않는다.
- 사용자/자동화가 보낸 프롬프트는 기존 prompt history 저장 경로와 동일하게
  기록한다.
- assistant 응답 저장은 provider native transcript reader 로 수집한다.
- AI 콘솔 TUI 는 arbitrary shell 이 아니다. provider 별 allowlist command 만 실행한다.
- project root 밖 cwd, raw shell string 실행, secret 로그 저장을 금지한다.

## Phase 1 — 독립 TUI 세션 배선

- `ApiPath` 에 프로젝트 콘솔 TUI REST/WS endpoint 를 추가한다.
- `ConsoleTuiSessionManager` 를 추가해 project/user/provider 단위 PTY 세션을 유지한다.
- provider command builder 는 `claude`, `codex`, `opencode` 고정 명령만 생성한다.
- 프로젝트 콘솔 화면에 접이식 TUI pane 을 추가한다.
- 기존 prompt textarea, prompt history, JSON WS 로그는 그대로 유지한다.

## Phase 2 — 프롬프트 주입 통합

- 기존 `POST /api/projects/{id}/claude/console/prompt` 진입점을 유지한다.
- TUI 모드에서는 DB user turn 기록 후 동일 prompt 를 TUI stdin 으로 bracketed paste 한다.
- 자동화가 보낸 prompt 도 같은 API 를 지나므로 prompt history 에 동일하게 남긴다.
- 이미지 첨부는 provider 지원 범위를 확인해 Claude 우선으로 유지하고, 미지원 provider 는 명확히
  거절한다.

진행 상태:

- `POST /api/projects/{id}/console/tui/prompt` 추가 완료.
- 기존 JSON 콘솔 `전송` 버튼과 분리된 `TUI 전송` 버튼으로 시작해 중복 전송을 방지한다.
- 프로젝트별 `TUI 기본` 토글을 추가했다. 토글 ON 상태에서는 기존 일반 `전송` 버튼도
  TUI prompt API 를 사용하며, 기존 JSON 전송은 fallback/debug 경로로 남는다.
- `POST /api/projects/{id}/console/tui/interrupt` 를 추가했다. `TUI 기본` ON 상태의
  끼어들기 버튼은 provider TUI 에 `Esc` 를 보낸 뒤 새 prompt 를 bracketed paste 로 주입한다.
- TUI prompt 는 `conversation_turns` 에 `role=user`, `provider`, `sessionId` 를 기록한 뒤
  bracketed paste 로 provider CLI TUI 에 주입한다.
- 이미지 첨부는 아직 미지원으로 명시 거절한다.
- session 조회/생성 응답과 생성 요청은 `shared` DTO(`ConsoleTuiSessionDto`,
  `ConsoleTuiSessionRequestDto`) 로 승격해 Android 와 서버가 같은 wire contract 를 공유한다.

## Phase 3 — Provider Native History Ingest

- `ClaudeTranscriptReader`, `CodexTranscriptReader`, `OpenCodeTranscriptReader` 를 분리 구현한다.
- provider transcript cursor 를 프로젝트별로 저장한다.
- 새 assistant/user/tool message 를 `ConversationTurnRepository` 에 upsert 한다.
- 화면 scrollback 과 DB history 를 분리한다.

진행 상태:

- Claude transcript JSONL append ingest 를 추가했다.
- TUI prompt 전송 직전 기존 transcript 파일 offset 을 baseline 처리해 과거 이력을 대량
  재수입하지 않는다.
- assistant text message 만 `conversation_turns` 에 적재한다. user prompt 는 TUI prompt API 가
  이미 기존 prompt history 경로에 기록한다.
- cursor state 에 file offset 과 최근 imported key 를 함께 저장해 재스캔/offset 꼬임 시 중복
  적재를 줄인다.
- OpenCode/GLM 은 `~/.local/share/opencode/opencode.db` 의 `session`/`message`/`part` 테이블에서
  현재 project directory 와 매칭되는 assistant text part 를 best-effort 로 읽어
  `conversation_turns` 에 적재한다. `sqlite3` CLI 가 없거나 DB 가 없으면 조용히 skip 한다.
- OpenCode/GLM provider 가 quota/rate-limit 등으로 assistant text part 를 만들지 않고
  `opencode.log` 의 `stream error` 만 남기는 경우에도, 현재 project directory 의 OpenCode
  session id 와 매칭되는 오류 로그를 provider error assistant turn 으로 적재한다. 이 오류 turn 도
  `TURN_COMPLETE` 신호를 발생시켜 자동화가 무한 `RUNNING` 대기 상태에 남지 않게 한다.
- Claude/OpenCode/Codex parser 단위 테스트를 추가했다.
- Codex 는 `~/.codex/state_*.sqlite` 의 `threads.rollout_path` 로 현재 project directory 의
  rollout JSONL 을 찾고, `response_item` assistant message 의 `output_text` 를 best-effort 로
  `conversation_turns` 에 적재한다. TUI prompt 전 offset baseline 을 잡아 과거 대화를 대량
  재수입하지 않는다.
- TUI 세션에서 수집한 assistant turn 은 provider native transcript 의 session/thread id 가
  있더라도 DB 저장 `sessionId` 를 TUI PTY session id 로 정규화한다. provider native id 는
  중복 방지 import key 와 raw transcript 에 남겨, 화면 history 복원 기준과 native transcript
  추적 기준이 섞이지 않게 한다.
- Codex TUI 는 새/미신뢰 directory 에서 첫 화면으로 "Do you trust the contents of this
  directory?" 확인창을 띄운다. TUI prompt 전송 직전 scrollback 에 이 화면이 감지되면 Enter 를
  한 번 보내 trust 확인을 통과한 뒤 실제 prompt 를 bracketed paste 한다. trust 확인 처리는
  TUI session 당 한 번만 수행한다. scrollback 은 누적 로그라 과거 trust 화면이 남아도 이후 prompt
  에 추가 Enter 가 반복 주입되지 않는다. 승인 직후에는 고정 sleep 만 쓰지 않고 scrollback 진행을
  최대 2초까지 기다려 Codex 입력 화면이 다시 그려진 뒤 실제 prompt 를 주입한다. 이미 trusted 인
  프로젝트에서는 이 처리를 하지 않아 빈 prompt 가 잘못 제출되지 않게 했다.
- 콘솔 재진입/새로고침 시 `TUI 기본` 이 켜져 있으면 legacy provider session id 대신 active
  TUI session id 를 기준으로 DB history 를 복원한다. 서버 재시작 등으로 PTY session 이 사라진
  경우에는 selected provider 의 최신 main-console turn 을 fallback 으로 보여준다.
- 콘솔 이미지 복원 endpoint 도 TUI 모드에서는 active TUI session id 를 기준으로 조회한다.
  PTY session 이 사라진 경우에는 본문 history 와 같이 selected provider 범위의 fallback 조회를
  허용한다.

## Phase 4 — Turn Detection

- 상태: `IDLE`, `PROMPT_SENT`, `RUNNING`, `ASSISTANT_OUTPUT_DETECTED`, `TURN_COMPLETE`,
  `STALLED`, `EXITED`.
- prompt injection 시점을 turn 시작으로 삼고, transcript 변경 + terminal idle debounce 로 완료를
  판정한다.
- 자동화는 `TURN_COMPLETE` 시점의 최신 응답을 기준으로 다음 prompt 전송 여부를 판단한다.

진행 상태:

- TUI session 상태를 `PROMPT_SENT` 에서 terminal output 감지 시 `RUNNING`, native transcript
  assistant import 시 `ASSISTANT_OUTPUT_DETECTED`, debounce 후 `TURN_COMPLETE` 로 전이하도록
  추가했다.
- `TURN_COMPLETE` 발생 시 기존 provider turn listener 와 같은 자동화 경로로
  `PromptAutomationManager.onTurnDone` 를 호출한다.
- provider CLI PTY 가 active prompt 중 종료되어 native transcript assistant import 가 발생하지
  않는 경우에도 `console_tui_exit` reason 으로 turn listener 를 한 번 호출한다. Codex trust/초기화
  실패, provider quota 로 인한 즉시 종료 등에서 자동화가 무한 대기하지 않게 하기 위한 fallback 이다.
- `PROMPT_SENT` / `RUNNING` 상태에서 일정 시간 native transcript assistant 응답이 감지되지
  않으면 `STALLED` 로 표시한다. 이 상태는 관찰용이며 자동 중단으로 처리하지 않는다.
- 콘솔 TUI 상단 status 가 session GET endpoint 를 주기 polling 하며 `running`,
  `assistant_output_detected`, `turn_complete`, `stalled` 상태를 표시한다.
- TUI session 생성/닫기 경계를 manager 내부에서 직렬화해, 빠른 중복 열기나 자동화 동시 prompt 가
  같은 project/provider 에 provider CLI PTY 를 중복 spawn 하지 않게 했다.
- provider별 더 정교한 완료 신호는 아직 미구현이다.

## Phase 5 — Compact

- `/compact` 는 provider 별 동작 차이를 흡수한다.
- GLM/OpenCode 는 기존 reset/intercept 정책을 TUI 세션에도 이벤트로 연결한다.
- compact 성공 여부는 화면 문자열이 아니라 provider session/history 상태로 판단한다.

진행 상태:

- `POST /api/projects/{id}/console/tui/compact` 를 추가해 TUI 세션 안으로 `/compact` 를
  bracketed paste 방식으로 전송한다.
- TUI compact 도 user turn 으로 prompt history 에 기록하고, native transcript ingest 와 turn
  detection 흐름을 그대로 탄다.
- 프로젝트 우측 rail 의 `/compact` 버튼은 기존처럼 console iframe 에 메시지를 보내되,
  `TUI 기본` 이 켜져 있거나 이미 TUI 세션이 있으면 TUI compact endpoint 를 우선 사용한다.
  TUI 모드가 꺼져 있고 세션도 없으면 기존 JSON 콘솔 전송 흐름을 유지한다.
- GLM/OpenCode TUI compact 성공 판정은 아직 provider native transcript/세션 상태 구현에
  의존한다.

## Phase 6 — Legacy 축소

- JSON mode 는 fallback/debug 용으로 남긴다.
- provider stdout JSON parsing 의존 코드를 줄이고, native history ingest 를 우선한다.
- 운영 전까지 provider 별 TUI, reconnect, 자동 prompt 턴, 히스토리 검색을 회귀 테스트한다.

진행 상태:

- 프로젝트별 `console-tui-mode` workspace flag 를 추가해 DB migration 없이 TUI 기본 모드를
  저장한다.
- JSON settings 응답에 `tuiMode` 를 additive field 로 추가하고,
  `POST /api/projects/{id}/console/tui/mode` 로 모바일/외부 클라이언트도 같은 설정을 바꿀 수
  있게 했다.
- Web console 에서는 `TUI 기본` 체크박스가 SSR route(`/projects/{id}/console/tui-mode`) 로
  즉시 저장되며, 일반 prompt submit 이 TUI prompt API 로 라우팅된다.
- SSR/JSON provider 또는 model 변경 시 기존 TUI PTY session 을 닫아, 다음 TUI session 이
  변경된 provider/model command 로 새로 spawn 되게 했다.
- TUI prompt/interrupt 성공 시 legacy JSON 콘솔과 동일하게 화면 history 에 user/system echo 를
  즉시 추가하고 in-flight 상태를 표시한다.
- TUI WebSocket 이 비정상 종료되면 pane 이 열려 있는 동안 자동 재연결하고, 서버 scrollback
  replay 로 직전 화면을 복원한다. pane 을 숨기면 재연결 타이머만 중단하고 PTY session 은 유지한다.
- TUI pane/session 생성 실패나 xterm.js 로드 실패 시 prompt/interrupt/compact 전송을 중단해,
  사용자가 볼 수 없는 PTY 로 작업만 전송되는 상태를 방지한다.
- 종료된/stale TUI session id 는 서버 manager 와 브라우저 WS close handler 양쪽에서 정리해,
  다음 열기나 전송이 새 provider CLI PTY session 생성으로 자연스럽게 이어지게 했다.

## 검증 항목

- [x] TUI session 생성/조회/종료/WS attach 서버 배선: REST/WS route, owner/project 검증,
  stale session 정리 구현.
- [x] browser refresh 후 scrollback replay 기반: server-side scrollback snapshot 과 WS 재연결
  replay 구현.
- [x] provider/model 변경 후 새 TUI session command 반영: SSR/JSON 변경 시 기존 TUI PTY 종료.
- [x] 기존 JSON 콘솔 prompt/history 정상 동작: legacy route 유지, `./gradlew :server:test` 통과.
- [x] 자동화 prompt history 기록 유지: TUI prompt API 가 `conversation_turns`
  user turn 을 동일하게 기록.
- [x] 배포 패키징 확인: `./gradlew :server:installDist` 통과, `server.jar` 에 xterm assets/TUI
  classes 포함, distribution lib 에 `pty4j-0.13.4.jar` 포함 확인.
- [x] 실제 브라우저 smoke 검증: 로컬 임시 DB/워크스페이스에서 현재 `installDist` 서버를
  `17881` 포트로 기동하고 Playwright 로 로그인 → 프로젝트 생성 → 콘솔 진입 → `TUI 열기`
  → xterm/FitAddon 로딩 → TUI session API alive 확인 → refresh 후 같은 PTY session 유지
  → `새 세션` 후 session 종료/null 확인 → 다시 열기 후 새 session 생성 확인.
- [x] TUI prompt/turn 단위 검증: bracketed paste prompt 정규화, prompt paste envelope,
  interrupt ESC 주입, assistant import 후 `TURN_COMPLETE` 전이를 단위 테스트로 고정.
- [x] provider별 prompt/interrupt/compact 검증 범위 확정: 비용 발생 가능성이 있어 자동 smoke 에서는
  실제 AI prompt 전송을 제외한다. provider별 command 구성, prompt paste, interrupt, compact,
  native transcript ingest, turn complete 판정은 단위 테스트로 고정하고, 운영/로컬 인증 provider
  실계정 prompt 전송은 배포 전 수동 운영 체크로 분리한다.
- [x] Android shared 동기 반영: `ApiPath`, `ConsoleDtos`, `ConsoleSettingsDtos`,
  `WsFrame` 을 `vibe-coder-android` shared 모듈에 동일 사본으로 반영하고
  `:shared:compileKotlin`, `:app:compileDebugKotlin` 통과 확인.
- [x] 운영 서버 배포: 2026-07-20 현재 worktree 로 `siamakerlab/vibe-coder-server:latest`
  amd64 이미지를 로컬 buildx 로 빌드한 뒤, 운영 서버 `wody@192.168.0.68:32324`
  `/home/wody/docker/vibe-codr-kr` compose 에 load/recreate 했다. 현재 운영 latest image 는
  `sha256:9fde0209aee8338f5b2cebd4a98f6810109730c6099bf0fae755cc20bf51a45b` 이다.
  기존 운영 이미지는 `siamakerlab/vibe-coder-server:rollback-before-tui-20260720-185414`,
  prompt 중 PTY exit fallback 적용 전 TUI 이미지는
  `siamakerlab/vibe-coder-server:rollback-before-tui-exit-20260720-190041`, stall listener
  fallback 적용 전 TUI 이미지는
  `siamakerlab/vibe-coder-server:rollback-before-tui-stall-20260720-1915` 로 보존했다.
  운영 `http://127.0.0.1:17880/health` 는 `{"status":"ok","version":"1.162.5"}`,
  Docker health 는 `healthy`, `server.jar` 안에 `ConsoleTui*` 클래스 포함을 확인했다.

## Provider Actual Verification Runbook

실제 provider 호출은 비용 또는 quota 를 사용할 수 있으므로 자동 smoke 에 포함하지 않는다. 운영 전
아래 항목을 provider 별로 한 번씩 수동 검증하고, 결과를 이 문서 또는 릴리즈 노트에 남긴다.

### 2026-07-20 실제 provider smoke 결과

로컬 임시 서버(`installDist`, port 17883), 임시 PostgreSQL DB/user, 임시 workspace 로
`tui-provider-smoke` 프로젝트를 생성해 실제 provider CLI PTY 경로를 확인했다. 검증 후 임시
server/session/DB/user/workspace 는 삭제했다.

확인된 항목:

- CLI availability:
  - Claude Code `2.1.215`
  - Codex CLI `0.144.3`
  - OpenCode `1.17.15`
- TUI session spawn:
  - Claude: `command=claude`, PTY alive.
  - Codex: `command=codex`, PTY alive at creation.
  - OpenCode: `command=opencode`, PTY alive.
- Prompt API:
  - `POST /api/projects/tui-provider-smoke/console/tui/prompt?provider={provider}` 는 세 provider 모두
    `202 Accepted` 를 반환했고, `conversation_turns` 에 provider별 user turn 을 기록했다.
- Claude:
  - native transcript ingest 가 assistant turn 을 DB 에 저장했고, TUI turn state 가
    `TURN_COMPLETE` 로 전이했다.
  - 실제 assistant content 는 `You've hit your weekly limit · resets 6pm (Asia/Seoul)` 였다.
    즉 TUI/ingest/turn-complete 경로는 검증됐지만 정상 답변 검증은 quota 때문에 미완료.
- OpenCode / GLM:
  - OpenCode DB 에 `ses_0811fe5efffeHTA7QwsEkP3oR7` session 과 user text part 가 생성됐다.
  - OpenCode log 에 `zai-coding-plan/glm-5.2` 로 실제 stream 시도 후
    `Weekly/Monthly Limit Exhausted. Your limit will reset at 2026-07-25 21:49:08` 가 기록됐다.
  - assistant text part 가 생성되지 않는 quota/error 케이스를 후속 보강했다. 현재는
    `opencode.log` 의 matching `stream error` 를 provider error assistant turn 으로 import 하도록
    테스트로 고정했다.
- Codex:
  - user turn 기록 후 Codex PTY 가 exit code `0` 으로 종료됐다.
  - `~/.codex/state_5.sqlite` 에 임시 project cwd thread 가 생성되지 않았다. 즉 prompt 가 Codex
    model thread 까지 도달하지 못한 상태로 판단된다.
  - 후속 pexpect 재현에서 새/빈 directory 의 Codex 첫 화면이 trust 확인창임을 확인했다. 기존
    prompt injection 은 이 확인창에 먼저 소비되어 실제 prompt 입력창까지 안정적으로 도달하지
    못할 수 있었다.
  - Codex TUI prompt 전송 직전 trust 확인창 감지/Enter 처리를 추가하고 단위 테스트로 고정했다.
    정상 quota 상태에서 실제 Codex assistant 답변과 native rollout ingest 는 아직 재검증 필요.

이번 smoke 로 체크박스를 완료 처리하지 않는 이유:

- Claude/OpenCode 모두 현재 계정 quota 제한에 걸려 정상 assistant 답변을 받을 수 없었다.
- Codex 는 TUI PTY 생성은 되지만 실제 thread 생성/assistant ingest 가 미검증이다.
- interrupt 와 `/compact` 는 provider별 정상 답변 session 위에서 확인해야 하므로 이번 smoke 에서
  완료 처리하지 않는다.

### 2026-07-20 실제 prompt / interrupt / compact 재검증

로컬 임시 서버(`installDist`, port 17884), 임시 PostgreSQL 컨테이너, 임시 workspace 로
`tui-verify-20260720190514` 프로젝트를 생성해 세 provider 의 TUI API 를 같은 방식으로 호출했다.
검증 후 임시 server/session/DB/workspace 는 삭제했다.

확인된 항목:

- 공통:
  - `POST /console/tui/session` 은 Claude/Codex/OpenCode 모두 PTY session 을 생성했고,
    command 는 각각 `claude`, `codex`, `opencode` 로 확인됐다.
  - 일반 prompt, interrupt prompt, `/compact` 는 세 provider 모두 `202 Accepted` 를 반환했고
    `conversation_turns` 에 provider별 `role=user` turn 으로 기록됐다.
- Claude:
  - 일반 prompt 는 약 2분 관찰 동안 `running` 상태를 유지했고 assistant transcript import 는
    발생하지 않았다.
  - interrupt 전송 직후 기존 Claude PTY 가 exit code `0` 으로 종료됐고, 이후 `/compact` 호출은
    새 Claude TUI session 을 생성해 user turn 으로 기록했다.
  - 현재 계정 quota/CLI 상태에서는 정상 assistant 답변 ingest 까지는 검증되지 않았다.
- Codex:
  - trust prompt 자동 처리 이후에도 약 2분 관찰 동안 `running` 상태를 유지했고 assistant rollout
    import 는 발생하지 않았다.
  - interrupt 와 `/compact` 는 같은 Codex TUI session 에 user turn 으로 기록됐다.
  - 정상 assistant 답변과 rollout ingest 는 아직 quota/CLI 정상 상태에서 재검증 필요.
- OpenCode / GLM:
  - 일반 prompt, interrupt, `/compact` user turn 이 기록됐다.
  - z.ai quota 제한으로 `opencode.log` 에 기록된 `Weekly/Monthly Limit Exhausted` stream error 를
    provider error assistant turn 으로 import 했다. 재검증 시점에는 interrupt 와 `/compact` 에서
    assistant error turn 2건이 적재됐다.
  - 즉 OpenCode quota/error fallback ingest 는 동작하나, 정상 답변 ingest 는 quota 때문에
    아직 미검증이다.

후속 보강:

- provider CLI 가 assistant native transcript 를 만들지 않은 채 장시간 `running` 에 머물면
  자동화가 무한 대기하지 않도록, TUI session 이 `STALLED` 로 전이될 때
  `console_tui_stalled` turn-done listener 를 1회 호출하도록 보강했다.
  단, terminal output 이 최근에 들어오는 동안에는 stale 로 보지 않는다.

공통 준비:

- 테스트 프로젝트 하나를 선택하고 콘솔 우측 설정에서 대상 provider/model 을 지정한다.
- `TUI 기본` 을 ON 으로 저장한 뒤 `TUI 열기` 로 PTY session 을 생성한다.
- Prompt history 에 같은 provider 의 새 user turn 이 기록되는지 확인할 수 있도록 기존 마지막
  turn index 를 기록한다.

Provider 별 최소 검증:

- Claude:
  - 짧은 prompt 1회 전송: `현재 프로젝트 이름만 한 줄로 답하세요.`
  - TUI 화면에 assistant 응답이 표시되는지 확인한다.
  - native transcript ingest 후 prompt history 에 assistant turn 이 같은 TUI session id 로
    복원되는지 확인한다.
  - 긴 작업 prompt 전송 직후 `끼어들기` 를 실행해 `Esc` + 새 prompt 가 실제 Claude TUI 에
    반영되는지 확인한다.
  - `/compact` 버튼을 눌러 TUI session 안에서 compact 명령이 실행되고, compact user turn 이
    prompt history 에 남는지 확인한다.
- Codex:
  - Claude 와 같은 절차로 prompt/history/interrupt/compact 를 확인한다.
  - `~/.codex` rollout 기반 assistant ingest 가 중복 없이 한 번만 들어오는지 확인한다.
- OpenCode / GLM:
  - Claude 와 같은 절차로 prompt/history/interrupt/compact 를 확인한다.
  - `~/.local/share/opencode/opencode.db` 기반 assistant ingest 가 현재 project directory 와
    매칭되는지 확인한다.
  - z.ai coding plan 강제 모드가 켜진 환경에서는 선택 모델이 `zai-coding-plan/*` 로 실행되는지
    TUI command/session 응답에서 확인한다.

합격 기준:

- `새 세션 시작하기` 를 누르기 전까지 refresh/console 재진입 후에도 같은 TUI session id 가
  유지된다.
- 일반 prompt, 자동화 prompt, `/compact` 가 모두 `conversation_turns` user turn 으로
  기록된다.
- assistant 응답은 terminal scrollback 이 아니라 provider native transcript ingest 를 통해
  DB history 로 복원된다.
- 각 prompt turn 이 `PROMPT_SENT` → `RUNNING` 또는 `ASSISTANT_OUTPUT_DETECTED` →
  `TURN_COMPLETE` 로 전이한다. provider transcript 지연으로 `STALLED` 가 표시되면 ingest 로그와
  native transcript 위치를 확인해 원인을 기록한다.
- interrupt 이후 provider TUI 가 이전 작업을 멈추거나 새 prompt 를 우선 처리한다. provider 가
  완전한 cancel 을 지원하지 않는 경우에는 그 한계를 provider별 known issue 로 기록한다.
