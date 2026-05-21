# vibe-coder 프로젝트 규칙

> 전역 룰(`~/.claude/CLAUDE.md`)을 기본으로 따른다. 이 문서는 vibe-coder
> 모노레포에만 적용되는 **예외·보완 사항**만 명시한다.

## 1. 프로젝트 성격

- **단일 사용자, LAN 페어링**: PC(Ktor 서버)와 본인 Android 단말 1대를
  로컬 네트워크에서 페어링해서 사용하는 1인 개발 도구.
- **다중 사용자 / 공개 배포 / 스토어 출시 대상이 아님.**
- 모듈 구성:
  - `:server` — Ktor 백엔드. Claude Code / Gradle / Git 자식 프로세스 관리.
  - `:android-app:app` — Compose 콘솔 클라이언트. 서버 원격조종 UI.
  - `:shared` — JVM-only DTO / API path / WsFrame.

## 2. 광고 정책 (전역 룰 §10 예외)

전역 룰은 "모든 앱은 기본적으로 광고 포함"이지만 vibe-coder는 다음 정책을
따른다.

| 모듈 | 광고 | 사유 |
|---|---|---|
| `:server` | **사용 안 함** | 서버는 UI가 없는 HTTP/WebSocket 서비스. 광고 노출 표면 자체가 존재하지 않음. 본인 PC에서만 실행되는 도구라 외부 노출도 없음. |
| `:android-app:app` | **사용 안 함** (현재) | 본인 1대만 페어링해서 쓰는 개인 도구. AdMob / 결제 / 광고 제거 IAP / 업데이트 알림 모두 적용하지 않는다. 향후 공개 배포 결정 시 재검토. |

따라서 전역 룰의 **광고 / 광고 제거 결제 / 업데이트 알림** 섹션
(§10·§11·§12)은 본 프로젝트에 적용하지 않는다.

## 3. 보안 / 워크스페이스

- 서버는 워크스페이스(`workspace.root`) **외부 경로 접근 절대 금지**.
  모든 디스크 touch는 `WorkspacePath` + `PathSafety`를 경유한다.
- 페어링 토큰은 SHA-256 hash만 DB에 저장. 평문은 발급 직후 1회만 반환.
- raw shell UI · 사용자 입력 shell 실행 절대 금지.
- 업로드 확장자 블랙리스트(`exe/bat/cmd/ps1/sh`)는 `server.yml`에서 관리.

## 4. 버전 / 릴리즈

- `CHANGELOG.md`는 모노레포 루트 1개만 유지 (전역 룰 §5 준수).
- `versionName` / `versionCode`는 `android-app/app/build.gradle.kts`와
  `server/src/main/resources/config/server.yml` 두 곳을 같은 값으로 동기.
- 키스토어는 `../keystores/vibe-coder-{keystore.properties,keystore}` (저장소
  외부, 전역 룰 §3 준수).

## 5. 문서 / PDCA

- 모든 문서는 한국어.
- PDCA 사이클 결과물:
  - 진행 중: `docs/01-plan/`, `docs/02-design/`,
    `docs/03-analysis/`, `docs/04-report/`
  - 완료 후 아카이브: `docs/archive/YYYY-MM/<feature>/`
- bkit 도구 상태(`.bkit/`)는 커밋하지 않는다 (`.gitignore`).

## 6. 실행 환경

- JDK toolchain 17 (전역 매트릭스 21 → 로컬 환경 제약으로 17, AGP 9 +
  Kotlin 2.2 호환).
- 워크스페이스 기본값: `./workspace` (CLI `--workspace <path>`로 override).
- 페어링 코드는 서버 시작 시 콘솔에 출력 (10분 TTL).
