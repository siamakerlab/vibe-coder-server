# Vibe Coder Server — Docker 이미지

> **1인 LAN 페어링 도구**용 슬림 Docker 이미지.
> Android SDK / Claude 인증 등 무거운 컴포넌트는 이미지에 박지 않고,
> 컨테이너 부팅 후 `vibe-doctor` 가 사용자 동의 하에 볼륨으로 다운로드합니다.

## 빠른 시작 (3분)

```bash
# 1) 이미지 받기 (또는 로컬 빌드)
docker pull siamakerlab/vibe-coder-server:0.6.0

# 2) compose 파일과 .env 복사
mkdir -p ~/vibe-coder && cd ~/vibe-coder
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder/main/docker/.env.example -o .env
# .env 를 편집기로 열어 PUID/PGID/포트/볼륨 경로 조정

# 3) 부팅
docker compose up -d

# 4) admin 웹 셋업 (브라우저)
#    http://<PC IP>:17880/admin → 첫 비밀번호 설정

# 5) 빌드 환경 다운로드 (Android SDK 등)
docker exec -it vibe-coder vibe-doctor
```

이후 Android 앱에서 같은 서버 URL + username/password로 로그인.

---

## 이미지 구성

| 레이어 | 내용 | 크기 |
|---|---|---|
| Ubuntu 22.04 (slim) | base | ~30MB |
| OpenJDK 17 (JRE) | vibe-coder 서버 실행 | ~200MB |
| Node 20 LTS + Claude Code CLI | Claude 자식 프로세스 | ~250MB |
| git, curl, unzip, jq, tini, gosu 등 | 빌드 도구 최소셋 | ~80MB |
| vibe-coder 서버 (installDist) | Ktor 본체 | ~50MB |
| **Total** | | **~600MB** |

이미지에 **포함되지 않은 것들** (doctor가 볼륨에 다운로드):

- Android SDK (~3~4GB)
- Gradle 의존성 캐시 (~1~2GB, 첫 빌드 시)
- Claude 인증 자격증명 (호스트 ~/.claude 마운트 권장)
- 선택적 MCP (Playwright Chromium 등)

---

## 환경설정 (`.env`)

`.env.example`을 복사하여 `.env`로 사용합니다. 주요 항목:

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:0.6.0` | pull 할 이미지 태그 |
| `PUID` / `PGID` | `1000` / `1000` | 호스트 UID/GID 매칭. `id -u` / `id -g` 로 확인 |
| `VIBE_PORT` | `17880` | 호스트 노출 포트 |
| `VIBE_WORKSPACE` | `./workspace` | 프로젝트 소스/빌드 산출물 디렉토리 |
| `VIBE_DATA` | `./vibe-data` | 서버 메타데이터 (SQLite 등) |
| `VIBE_CLAUDE_DIR` | `~/.claude` | Claude 인증 디렉토리 (호스트와 공유 권장) |
| `VIBECODER_ADMIN_USERNAME` | (미설정) | 첫 부팅 시 admin 자동 생성용 |
| `VIBECODER_ADMIN_PASSWORD` | (미설정) | 위와 한 쌍. 부팅 직후 변경 권장 |
| `JAVA_OPTS` | `-Xmx2g …` | JVM 힙. 호스트 RAM 보고 조정 |

### 볼륨 마운트 구조

```
호스트                            컨테이너
────────                          ─────────────
${VIBE_WORKSPACE}              →  /workspace          (소스/APK)
${VIBE_DATA}                   →  /data               (DB/로그)
${VIBE_CLAUDE_DIR}             →  /home/vibe/.claude  (인증)
named: vibe-android-sdk        →  /opt/android-sdk    (SDK)
named: vibe-gradle-cache       →  /home/vibe/.gradle  (의존성 캐시)
```

호스트 bind mount는 호스트 IDE/에디터에서 직접 접근 가능, named volume은 Docker가 관리(컨테이너 삭제와 분리됨).

---

## doctor

```bash
docker exec -it vibe-coder vibe-doctor              # 인터랙티브 (권장)
docker exec -it vibe-coder vibe-doctor check        # 진단만
docker exec    vibe-coder vibe-doctor install       # 비대화형 일괄 설치
docker exec -it vibe-coder vibe-doctor android      # Android SDK만
docker exec -it vibe-coder vibe-doctor claude       # Claude 인증만
docker exec -it vibe-coder vibe-doctor mcp          # 선택적 MCP만
```

처음 실행 시 다음 순서로 진행됩니다.

1. **환경 진단** — JDK / Node / git / Claude CLI / 워크스페이스 권한
2. **Android SDK 설치** — cmdline-tools (130MB) → 라이선스 자동 수락 → platform-tools + platforms;android-35 + build-tools;35.0.0
3. **Claude 인증** — 호스트 ~/.claude 마운트 권장. 또는 컨테이너 안 `claude login`.
4. **선택적 MCP** — filesystem, sqlite, fetch, playwright 등 (개별 동의)
5. **최종 점검** — 모든 컴포넌트 ✓ 확인

---

## Admin 웹

`http://<PC IP>:17880/admin`

| 페이지 | 기능 |
|---|---|
| `/admin/setup` | 첫 부팅 시 admin 계정 생성 |
| `/admin/login` | 로그인 |
| `/admin` | 대시보드 (서버 상태, 환경 진단, 최근 빌드) |
| `/admin/settings` | server.yml 항목 GUI 편집 |
| `/admin/password` | 비밀번호 변경 |
| `/admin/devices` | 페어링된 디바이스 목록 / revoke |

Android 앱은 같은 username/password로 로그인합니다.

---

## 트러블슈팅

### "Permission denied" — 볼륨 권한 오류

`PUID` / `PGID` 가 호스트 사용자와 일치하지 않을 때 발생.

```bash
id -u; id -g                   # 호스트 UID/GID 확인
# .env의 PUID/PGID를 이 값으로 변경 후
docker compose up -d --force-recreate
```

### "Build failed: SDK location not found"

doctor 미실행. `docker exec -it vibe-coder vibe-doctor android` 실행.

### Claude가 인증 안 됨

호스트 `~/.claude` 마운트 권장. 또는 `docker exec -it vibe-coder claude login`.

### 빌드가 느림

`vibe-gradle-cache` 볼륨이 첫 빌드에서 채워집니다. 2번째 빌드부터 빨라짐.
RAM 여유가 있다면 `.env`에서 `JAVA_OPTS=-Xmx8g` 등으로 늘리세요.

### Windows / WSL2에서 사용 시

프로젝트 소스를 **WSL2 안의 리눅스 파일시스템**(`/home/...`)에 두세요.
`/mnt/c/...` 의 윈도우 디스크 경로를 마운트하면 빌드 I/O 가 5~20배 느려집니다.

---

## 멀티 아키텍처 빌드 (메인테이너용)

```bash
docker buildx create --name vibe-builder --use
docker buildx build \
    --platform linux/amd64,linux/arm64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:0.6.0 \
    -t siamakerlab/vibe-coder-server:latest \
    --push \
    .
```

---

## 보안 메모

- 이 이미지는 **LAN 내부 전용**입니다. 공인 IP에 노출하지 마세요.
- Admin 비밀번호 정책: 길이 ≥ 8, 영문+숫자 혼합.
- 페어링 토큰 / 비밀번호는 DB에 **hash만** 저장됩니다 (BCrypt cost 12, SHA-256).
- `.env`에 admin 비밀번호를 plain text로 둘 경우, 부팅 직후 `/admin/password`에서 변경하세요.
