# Repository Guidelines

이 문서는 Codex / OpenCode 등 AI 페어용 프로젝트 지침이다. `CLAUDE.md`의 리포 전용
규칙을 요약한 것이며, 더 자세한 배경·버전 히스토리·Roadmap은 `CLAUDE.md`와
`CHANGELOG.md`를 함께 참고한다.

## 프로젝트 성격

- 이 리포는 브라우저에서 Android 프로젝트 생성, AI 콘솔 프롬프트 전송, 빌드, APK
  다운로드까지 처리하는 standalone Docker 개발 서버다.
- 기본 운영 모델은 **단일 사용자**다. 공개 멀티테넌트 SaaS를 전제로 한 권한 모델이나
  복잡한 팀 기능을 추가하지 않는다 (과거 도입했던 admin/member/viewer 역할과
  ProjectACL은 v1.45.0에서 전면 제거됨).
- **웹 UI만으로 핵심 기능이 완결**되어야 한다. Android 클라이언트는 부가 클라이언트이며,
  웹 없이 단독으로 동작하는 기능을 만들지 않는다. 이 원칙이 깨지면 회귀로 간주.
- 모듈 구성:
  - `:server` — Ktor 백엔드. Claude/Codex/OpenCode + Gradle + Git 자식 프로세스 관리,
    PostgreSQL(Exposed) 저장소 (v0.14.0+, 이전 SQLite), WebSocket 로그 허브,
    SSR admin UI(대시보드/프로젝트/콘솔/빌드/설정/디바이스).
  - `:shared` — JVM-only DTO / `ApiPath` / `WsFrame`. 같은 코드가
    `vibe-coder-android` 리포의 `:shared` 모듈에도 동일 사본으로 수동 동기화된다.

## 관련 리포 (짝 리포)

- Android 리포: `vibe-coder-android`
- 원격: `ssh://git@gitea.wody.kr:2929/wody/vibe-coder-android.git`
- 두 리포는 `:shared` 모듈로 wire-level 호환을 유지한다. `ApiPath` / DTO / `WsFrame`
  변경 시 양쪽 모두 같은 값으로 업데이트해야 한다.

## 🔴 Android 호환 영향 알림 방침 (필수, 누락 금지)

서버 수정 시 **안드로이드 앱(`vibe-coder-android`)이 호환되지 않거나 후속 수정이
필요한 변경**이 생기면, 작업 종료 응답에서 **사용자에게 명시적으로 알린다**.
commit message 한 줄로 넘기지 말 것. 알림에 포함:

1. **무엇이** 바뀌었는지 (endpoint/DTO/WsFrame 단위).
2. **호환성 방향** — 구버전 안드 클라이언트가 그대로 동작하는지(additive/하위호환)
   vs 깨지는지(breaking). nullable 방향·필드 추가/삭제/이름변경·`@SerialName`·
   enum↔string·`ApiPath` 경로 변경 기준으로 판정.
3. **안드 측 해야 할 일** — `shared/` 동기 항목, Retrofit/DTO 수정, UI 반영 등.

트리거 예시: REST/WS 엔드포인트 추가·수정·제거, DTO 필드/구조 변경, `WsFrame` 프레임
추가/변경, `ApiPath` 상수 변경, 인증/토큰 흐름 변경. additive(하위호환)라도 "안드가
새 기능을 쓰려면 동기 필요"하면 알린다. 순수 내부 리팩토링/버그수정이면 알림 불필요.

## 보안 및 워크스페이스 경계

- 서버는 워크스페이스(`workspace.root`) **외부 경로 접근 절대 금지**. 모든 디스크
  touch는 `WorkspacePath` + `PathSafety` 등 기존 경계 API를 경유한다.
- raw shell UI · 사용자 입력 shell 직접 실행 금지.
- 인증: username/password 통합 인증. 비밀번호는 BCrypt cost 12 hash로만 DB 저장,
  10회 실패 시 15분 잠금. 같은 IP 24시간 window 30회 실패 → 24시간 차단(v0.12.4+).
- 토큰은 `Authorization: Bearer …` 헤더와 `vibe_session` 쿠키 양쪽 모두 유효. 같은
  토큰이 어느 경로로 와도 인증된다. CSRF 토큰 시스템(v0.12.4+)이 SSR POST를 보호.
- WS handshake는 Origin 검증으로 CSWSH 추가 방어(v0.12.4+).
- 업로드 확장자 블랙리스트(`exe/bat/cmd/ps1/sh`)는 `server.yml`에서 관리.
- 시크릿은 로그, 문서, 테스트 fixture에 남기지 않는다. MCP/API token은 env 또는
  기존 secret store를 사용한다.
- Docker/Claude/Codex child process 변경은 orphan process, timeout, stream drain,
  shutdown hook까지 고려한다.

## 실행 환경

- JDK toolchain 17 (전역 매트릭스 21 → 로컬 환경 제약으로 17, Ktor 3.x 호환).
- 워크스페이스 기본값: `./workspace` (CLI `--workspace <path>`로 override).
- Docker 실행 시 `VIBECODER_WORKSPACE_ROOT=/workspace` 자동 적용.
- **DB (v0.14.0+)**: PostgreSQL 17 (`postgres:17-alpine` 사이드카). 로컬 dev 실행 시
  별도 PG 인스턴스 필요 (`VIBECODER_DB_HOST` 등 env). compose의 `postgres` 서비스만
  미리 띄우는 게 가장 간단:
  `docker compose -f docker/compose.yml --env-file docker/.env up -d postgres`.
- 한국어 검색은 `pg_trgm` GIN 인덱스 + `mecab-ko` 형태소 분석(`VIBECODER_MECAB_ENABLED`).

## 버전 / 릴리즈

- `versionName` / `versionCode`는 `server/src/main/resources/config/server.yml`의
  `server.version`에만 존재한다 (Android 리포의 `build.gradle.kts`와 **독립 버전**).
- Docker 이미지 태그는 `server.version`과 같은 값을 사용. `docker/Dockerfile`,
  `docker/compose.yml`, `docker/.env.example`, `docker/README.md`,
  `docker/HUB_README.md`의 `siamakerlab/vibe-coder-server:<version>`을 동기.
- **Docker buildx 정책 (v0.6.0+)** — 일반 commit push는 amd64-only(2~3분). multi-arch
  (amd64 + arm64) 빌드는 마일스톤 릴리즈 시점에만. arm64 cross-compile은 emulation으로
  3~5배 느림.
- `:server:installDist` "통과" 메시지가 Gradle daemon 캐시 효과로 위장될 수 있으므로,
  baseline 결함 확인은 반드시 clean 빌드로 한다.

## 빌드 및 테스트 명령

- 빠른 컴파일 확인: `./gradlew compileKotlin`
- 서버 테스트: `./gradlew :server:test`
- 전체 테스트: `./gradlew test`
- baseline 결함 확인이 필요하면 clean 빌드: `./gradlew clean :server:test`
- Docker 운영 변경은 필요 시 image build까지 별도 확인한다.

## 코딩 스타일

- Kotlin은 기존 프로젝트 스타일을 따른다. 새 abstraction은 기존 service/route/template
  패턴에 맞춘다.
- 수동 파일 편집은 좁은 범위로 한다. unrelated refactor와 metadata churn을 피한다.
- DTO, `ApiPath`, `WsFrame`은 wire compatibility를 먼저 검토한다.
- SSR template은 기존 `AdminTemplates`, `WebProjectTemplates`, i18n `MessagesKo/En`
  패턴을 따른다.
- 사용자 가시 문구는 한국어/영어 메시지 키를 함께 추가한다.

## AI Provider 구조

- 기존 Claude Code 기능을 기본값으로 유지한다.
- Claude, Codex, OpenCode 등 provider별 구현은 서로 의존하지 않는 **독립 구현**을 우선.
- provider switching은 명확하고 되돌리기 쉬워야 한다. 효율보다 안정성과 격리를 우선.
- Claude 전용 기능을 다른 provider에 연결할 때는 동일 동작을 가정하지 말고, 해당 CLI
  공식 동작과 로컬 CLI 출력으로 직접 검증한다.

### z.ai coding plan 강제 모드 (v1.153.0+, Phase 3.1)

- `opencode.zai.enforceCodingPlan: true` 시 OpenCode provider 는 **항상 z.ai coding plan
  구독 경로만 허용**. opencode 의 다른 provider(anthropic/openai/자체 LLM) 로의 우회 차단.
- 구현: `OpenCodeSessionManager` 가 spawn 시 `OPENCODE_CONFIG_HOME` 을 서버 통제 격리
  디렉토리(`<workspace>/.opencode-zai-enforced`)로 설정 → z.ai-only `opencode.jsonc`
  (provider 블록 비움) 노출. 사용자 원본 config 의 커스텀 provider 가 무시됨.
- 모델 whitelist: `-m zai-coding-plan/*` 외 모델은 `effectiveModel` 가 `FALLBACK_ZAI_MODEL`
  (`zai-coding-plan/glm-5.2`) 로 대체. 프로젝트별 모델 설정도 zai 외이면 fallback.
- auth.json(`~/.local/share/opencode/auth.json`) credential 은 XDG_DATA_HOME 에 있어
  격리 config 적용 시에도 유지 — z.ai coding plan 토큰이 그대로 동작.
- 부팅 audit: `enforceCodingPlan=true` 면 서버 시작 시 로그로 명시. runtime 토글 UI 는
  추후 확정 — 현재는 `server.yml` 또는 `VIBECODER_ZAI_ENFORCE_CODING_PLAN` env 로 제어.
- token 자동 갱신(refresh): opencode CLI 가 만료 감지/갱신을 자체 처리하지 않아, credential
  만료 시 `opencode providers login` 재실행 안내. 자동 refresh 는 향후 z.ai API 직접 연동 시 추가.

## Wire Change 규칙

다음 변경은 Android 호환성 영향 검토가 필수다.

- `shared/src/main/kotlin/.../ApiPath.kt`
- `shared/src/main/kotlin/.../dto/*.kt`
- `shared/src/main/kotlin/.../ws/WsFrame.kt`
- REST/WS endpoint 추가, 제거, field 이름/타입 변경, enum/string 변경

**강제 룰 (v0.64.0+)**: 신규 REST/WS endpoint는 **반드시 `shared/ApiPath.kt`에 먼저
등록**되고, 라우터는 그 상수를 참조해야 한다. 라우터에 hardcoded path(`/api/projects/...`)
를 쓰면 코드 리뷰에서 reject. 과거 어겨져서 9개 endpoint가 ApiPath SSOT 밖에서 단독
운영됐고 wire shape drift가 5건 발생한 전례가 있다.

작업 완료 응답에는 다음을 명시한다 (위 "Android 호환 영향 알림 방침" 참고):

- 무엇이 바뀌었는지 endpoint/DTO/frame 단위로 설명
- 하위호환 여부
- Android 리포에서 필요한 동기화 작업

## 문서 갱신 트리거 (v0.10.0+ 필수)

코드 변경이 다음 카테고리 중 하나에 해당하면 **같은 commit / 같은 PR 안에서**
아래 문서를 같이 갱신한다.

- **A. Wire change (`shared/` 모듈)**: `CHANGELOG.md` "Wire change" 섹션 + README
  "JSON API" + Wiki REST/WebSocket/Android-Client 페이지 + Android `shared/` 동기 권고 메모.
- **B. 새 사용자 노출 기능 (UI/API 추가)**: `CHANGELOG.md` "Added" + README "Web routes"/
  "JSON API" + 관련 Wiki 페이지 (Build-Environment / Claude-Auth / MCP-Catalog /
  Git-Integration 등).
- **C. 운영 정책 변경** (Dockerfile / compose.yml / .env.example / entrypoint):
  `CHANGELOG.md` "Changed"/"Fixed" + 마이그레이션 절차 + README "Common operations" +
  Wiki Data-Volumes/Security-Model/Upgrade-Guide + `docker/README.md`와 `docker/HUB_README.md`.
- **D. 신규 프로젝트 템플릿** (`projects/Claude*Template.kt`): `CHANGELOG.md` +
  Wiki Architecture.md "신규 프로젝트 생성" 섹션. 기존 프로젝트 영향 없음 명시.

갱신 안 해도 되는 경우: 내부 리팩토링(시그니처·동작 동일), 성능 개선(외부 동작 동일),
테스트 코드 추가, 주석/KDoc 정정.

**문서 언어 정책**: 내부 문서(`CLAUDE.md`, `docs/`)는 한국어. 공개 문서(`README.md`,
Wiki, `docker/HUB_README.md`)는 영어.

## 운영 환경 참고

- 운영 compose 폴더: `/home/wody/docker/vibe-coder-server/`
  - 본 리포의 `docker/compose.yml` 변경사항은 운영 폴더로 수동 sync.
  - 데이터 디렉토리: `${VIBE_DATA_ROOT:-./vibe-coder-data}/`.
- 외부 도메인: <https://vibe.wody.work>
  - openresty/외부 리버스 프록시가 `vibe.wody.work` → `localhost:17880`으로 forward.
  - `https://vibe.wody.work/health`, `/api/server/quota`로 외부 동작 검증 가능.
  - 안드 클라이언트 로그인 host로도 사용.
- 운영 갱신 기본 절차:

```bash
cd /home/wody/docker/vibe-coder-server
docker compose pull
docker compose up -d --force-recreate
```

## MCP 및 도구

- MCP는 전역/user-scope 등록을 우선한다. Claude는 `claude mcp add-json -s user` 기반이고,
  Codex는 `codex mcp add` 기반이다.
- Claude와 Codex의 MCP 설정 저장소는 다르다. 같은 MCP라도 provider별로 독립 등록한다.
- Docker Hub MCP는 stdio MCP이며 token은 `HUB_PAT_TOKEN` env로 전달한다.

## 완료 전 확인

- 변경 범위가 요청과 일치하는지 확인한다.
- `git status --short`로 새 파일과 수정 파일을 확인한다.
- 가능한 경우 `./gradlew compileKotlin`과 관련 테스트를 실행한다.
- 실행하지 못한 검증은 최종 응답에 명시한다.
- Wire change 발생 시 Android 호환 영향 알림을 빠뜨리지 않는다.
