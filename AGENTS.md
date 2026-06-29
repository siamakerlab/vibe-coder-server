# Repository Guidelines

이 문서는 Codex용 프로젝트 지침이다. `CLAUDE.md`의 리포 전용 규칙을 요약한 것이며,
더 자세한 배경이나 로드맵이 필요하면 `CLAUDE.md`를 함께 참고한다.

## 프로젝트 성격

- 이 리포는 브라우저에서 Android 프로젝트 생성, AI 콘솔 프롬프트 전송, 빌드, APK 다운로드까지 처리하는 standalone Docker 개발 서버다.
- 기본 운영 모델은 단일 사용자다. 공개 멀티테넌트 SaaS를 전제로 한 권한 모델이나 복잡한 팀 기능을 추가하지 않는다.
- 웹 UI만으로 핵심 기능이 완결되어야 한다. Android 클라이언트는 부가 클라이언트이며, 웹 없이만 동작하는 기능을 만들지 않는다.
- `:server`는 Ktor 백엔드, child process 관리, PostgreSQL 저장소, WebSocket 로그 허브, SSR admin UI를 담당한다.
- `:shared`는 JVM-only DTO, `ApiPath`, `WsFrame`을 담는다. 같은 wire shape가 `vibe-coder-android` 리포에도 수동 동기화된다.

## 관련 리포

- Android 리포: `vibe-coder-android`
- 원격: `ssh://git@gitea.wody.kr:2929/wody/vibe-coder-android.git`
- `shared/` 변경 시 Android 쪽 동기화 필요 여부를 반드시 판단한다.

## 보안 및 경계

- 워크스페이스 외부 파일 접근은 금지한다. 디스크 접근은 `WorkspacePath`와 `PathSafety` 등 기존 경계 API를 우선 사용한다.
- raw shell UI 또는 사용자 입력을 그대로 shell로 실행하는 기능을 만들지 않는다.
- 인증은 `Authorization: Bearer`와 `vibe_session` 쿠키 양쪽을 지원한다. 새 endpoint를 추가할 때 기존 인증 패턴을 따른다.
- 시크릿은 로그, 문서, 테스트 fixture에 남기지 않는다. MCP/API token은 env 또는 기존 secret store를 사용한다.
- Docker/Claude/Codex child process 변경은 orphan process, timeout, stream drain, shutdown hook까지 고려한다.

## 빌드 및 테스트 명령

- 빠른 컴파일 확인: `./gradlew compileKotlin`
- 서버 테스트: `./gradlew :server:test`
- 전체 테스트: `./gradlew test`
- baseline 결함 확인이 필요하면 clean 빌드를 사용한다: `./gradlew clean :server:test`
- Docker 운영 변경은 필요 시 image build까지 별도 확인한다.

## 코딩 스타일

- Kotlin은 기존 프로젝트 스타일을 따른다. 새 abstraction은 기존 service/route/template 패턴에 맞춘다.
- 수동 파일 편집은 좁은 범위로 한다. unrelated refactor와 metadata churn을 피한다.
- DTO, `ApiPath`, `WsFrame`은 wire compatibility를 먼저 검토한다.
- SSR template은 기존 `AdminTemplates`, `WebProjectTemplates`, i18n `MessagesKo/En` 패턴을 따른다.
- 사용자 가시 문구는 한국어/영어 메시지 키를 함께 추가한다.

## AI Provider 구조

- 기존 Claude Code 기능을 기본값으로 유지한다.
- Claude, Codex, OpenCode 등 provider별 구현은 서로 의존하지 않는 독립 구현을 우선한다.
- provider switching은 명확하고 되돌리기 쉬워야 한다. 효율보다 안정성과 격리를 우선한다.
- Claude 전용 기능을 Codex에 연결할 때는 동일 동작을 가정하지 말고, Codex CLI 공식 동작과 로컬 CLI 출력으로 검증한다.

## Wire Change 규칙

다음 변경은 Android 호환성 영향 검토가 필수다.

- `shared/src/main/kotlin/.../ApiPath.kt`
- `shared/src/main/kotlin/.../dto/*.kt`
- `shared/src/main/kotlin/.../ws/WsFrame.kt`
- REST/WS endpoint 추가, 제거, field 이름/타입 변경, enum/string 변경

작업 완료 응답에는 다음을 명시한다.

- 무엇이 바뀌었는지 endpoint/DTO/frame 단위로 설명
- 하위호환 여부
- Android 리포에서 필요한 동기화 작업

신규 REST/WS endpoint는 먼저 `ApiPath.kt`에 상수로 등록하고 route에서 그 상수를 사용한다.

## 문서 갱신 트리거

코드 변경이 아래에 해당하면 같은 작업에서 문서 갱신도 고려한다.

- 사용자 노출 기능 추가: `CHANGELOG.md`, `README.md`, 관련 Wiki 문서
- Dockerfile, compose, entrypoint, env 변경: Docker 문서와 upgrade 절차
- `shared/` wire 변경: `CHANGELOG.md`의 Wire change, API 문서, Android sync 메모
- 신규 프로젝트 템플릿 변경: 신규 프로젝트 생성 문서와 changelog

내부 문서(`CLAUDE.md`, `docs/`)는 한국어를 유지한다. 공개 문서(`README.md`, Wiki, Docker Hub README)는 영어를 유지한다.

## 운영 환경 참고

- 운영 compose 폴더: `/home/wody/docker/vibe-coder-server/`
- 외부 도메인: `https://vibe.wody.work`
- 운영 갱신 기본 절차:

```bash
cd /home/wody/docker/vibe-coder-server
docker compose pull
docker compose up -d --force-recreate
```

## MCP 및 도구

- MCP는 전역/user-scope 등록을 우선한다. Claude는 `claude mcp add-json -s user` 기반이고, Codex는 `codex mcp add` 기반이다.
- Claude와 Codex의 MCP 설정 저장소는 다르다. 같은 MCP라도 provider별로 독립 등록한다.
- Docker Hub MCP는 stdio MCP이며 token은 `HUB_PAT_TOKEN` env로 전달한다.

## 완료 전 확인

- 변경 범위가 요청과 일치하는지 확인한다.
- `git status --short`로 새 파일과 수정 파일을 확인한다.
- 가능한 경우 `./gradlew compileKotlin`과 관련 테스트를 실행한다.
- 실행하지 못한 검증은 최종 응답에 명시한다.
