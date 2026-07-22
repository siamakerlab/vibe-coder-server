# Vibe Coder Server — Docker 이미지

> **1인 LAN 페어링 도구**용 슬림 Docker 이미지.
> Android SDK / Claude 인증 등 무거운 컴포넌트는 이미지에 박지 않고,
> 컨테이너 부팅 후 `vibe-doctor` 가 사용자 동의 하에 볼륨으로 다운로드합니다.

## 왜 만들었나

클로드 코드로 안드로이드 앱을 끊임없이 만드는 1인 개발자 입장에서, 매번
개발용 노트북을 들고 다닐 수는 없었습니다. 카페에서도, 이동 중에도, 거실
소파에서도 개발을 이어가고 싶은데 기존 도구들은 다 어딘가 불편했습니다.

- **폰에서 SSH** 는 자주 끊겨서 긴 Claude 턴에 부적합.
- **디버그 APK 설치** 는 USB / `adb` 단계가 너무 번거로움.
- **원격 데스크탑 (RDP / VNC)** 은 폰 조작이 정말 불편함.

그래서 클로드 코드 전용으로 작동하는 서버와 안드로이드 클라이언트를 직접
만들었습니다. 서버는 도커로 집의 개발용 PC 에서 일관된 환경으로 돌리고,
안드로이드 앱은 진짜 터치 친화적인 UI 로 프롬프트 전송 / 빌드 로그 감상
/ 디버그 APK 같은 디바이스에 원탭 설치까지 처리합니다. 여러 프로젝트를
병렬로 돌리고, 자주 쓰는 프롬프트를 템플릿화하고, 어디서 연결이 끊겨도
정확히 그 자리에서 이어 작업할 수 있습니다.

**안드로이드 클라이언트는 옵션** — 모든 기능이 브라우저에서도 동작하므로
서버 단독으로도 완결된 도구입니다. 비슷한 워크플로의 1인 안드로이드 개발자
라면 분명 편하게 쓸 수 있을 것입니다.

모든 코드는 클로드 코드와의 vibe coding 으로 작성했고, 본인 워크플로 변화에
따라 수시로 업데이트됩니다. 본인이 잘 안 쓰는 기능은 버그 발견이 늦을 수
있으니, 발견 시 issue 로 신고해 주시면 신속히 수정하겠습니다.

## 빠른 시작 (3분)

`latest` 와 버전 태그는 `linux/amd64` + `linux/arm64` 멀티아키로 게시됩니다.
Intel/AMD PC, Linux 서버, Apple Silicon(M1/M2/M3/M4) Mac에서 같은 compose 파일을
사용하면 Docker가 호스트에 맞는 이미지를 자동으로 받습니다.

```bash
# 1) 이미지 받기 (또는 로컬 빌드)
docker pull siamakerlab/vibe-coder-server:latest
docker pull postgres:17-alpine    # v0.14.0+ 신규 의존

# 2) compose 파일과 .env 복사
mkdir -p ~/vibe-coder && cd ~/vibe-coder
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# 3) .env 편집 — VIBECODER_DB_PASSWORD **반드시** 강력한 값으로 변경 (v0.14.0+).
#    이 값이 비어 있으면 compose 가 부팅을 거절합니다.
${EDITOR:-nano} .env

# 4) 부팅 — postgres 컨테이너 + server 가 같이 뜨고 ./vibe-coder-data/ 자동 생성
docker compose up -d

# 5) admin 웹 셋업 (브라우저)
#    http://<PC IP>:17880/ 또는 /setup → 첫 admin 생성 → 빌드환경 페이지

# 6) 빌드 환경 다운로드 (Android SDK 등)
#    웹 UI 의 "⚡ 모두 설치/업데이트" 버튼이 표준입니다.
#    터미널로 진행하려면:
docker exec -it vibe-coder-server vibe-doctor
```

선택적으로 원격 SSH 접속이 필요하면 웹 UI 의 **빌드환경 → SSH 서버** 카드에서
OpenSSH 서버를 설치하세요. 기본 compose 는 `VIBE_SSH_PORT=2222` 를
컨테이너 `VIBECODER_SSH_PORT=2222` 로 노출하므로 설치 후
`ssh -p 2222 vibe@<host>` 로 접속할 수 있습니다. 보통 외부 포트만 바꾸면 되므로
`.env` 의 `VIBE_SSH_PORT` 만 수정하면 됩니다. 카드에서 컨테이너 포트까지 바꾼
경우에만 `VIBECODER_SSH_PORT` 도 같은 값으로 맞추고 컨테이너를 재생성하세요.

> **v0.13.x → v0.14.0 업그레이드**: SQLite → PostgreSQL 전환으로 fresh start 가
> 필요합니다 (admin / 프로젝트 재등록). 워크스페이스 파일은 보존됩니다. 절차는
> 상위 디렉토리의 [CHANGELOG.md](../CHANGELOG.md) v0.14.0 entry 를 참고하세요.

이후 Android 앱에서 같은 서버 URL + username/password로 로그인.

---

## 이미지 구성

| 레이어 | 내용 | 크기 |
|---|---|---|
| Ubuntu 26.04 LTS (resolute) | base | ~30MB |
| OpenJDK 17 (JRE) | vibe-coder 서버 실행 | ~200MB |
| Node 20 LTS + Claude Code CLI | Claude 자식 프로세스 | ~250MB |
| git, curl, unzip, jq, tini, gosu 등 | 빌드 도구 최소셋 | ~80MB |
| ImageMagick · Pillow(python3) · rsvg · webp · poppler 등 | 이미지 처리 도구 (v1.92.0) | ~150MB |
| vibe-coder 서버 (installDist) | Ktor 본체 | ~50MB |
| **Total** | | **~750MB** |

> **이미지 도구 + 추가 설치** (v1.92.0): Claude Code 가 스크린샷·mockup·아이콘을
> 바로 다룰 수 있도록 `imagemagick` / `python3-pil`(Pillow) / `librsvg2-bin` /
> `webp` / `poppler-utils` / `ghostscript` / `optipng` / `pngquant` / `jpegoptim`
> 를 기본 포함. 그 외 도구는 `vibe` 유저의 NOPASSWD sudo 로
> `sudo apt-get update && sudo apt-get install <pkg>` 로 언제든 추가 설치 가능
> (ffmpeg / inkscape 등).

이미지에 **포함되지 않은 것들** (doctor가 볼륨에 다운로드):

- Android SDK (~3~4GB)
- Flutter SDK (~2GB — 선택, Android APK 빌드 전용으로 precache)
- Gradle 의존성 캐시 (~1~2GB, 첫 빌드 시)
- Claude 인증 자격증명 (호스트 ~/.claude 마운트 권장)
- 선택적 MCP (Playwright Chromium 등)

---

## 환경설정 (`.env`)

`.env.example`을 복사하여 `.env`로 사용합니다. 주요 항목:

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VIBECODER_IMAGE` | `siamakerlab/vibe-coder-server:latest` | pull 할 이미지 태그. 재현 가능성을 우선하면 특정 버전(예: `1.162.5`) 핀. `latest`/버전 태그는 amd64+arm64 멀티아키 |
| `VIBECODER_POSTGRES_IMAGE` | `postgres:17-alpine` | PG 컨테이너 이미지 (v0.14.0+) |
| **`VIBECODER_DB_PASSWORD`** | (필수) | **반드시 강력한 값으로 변경.** 비면 compose 가 부팅 거절 |
| `VIBECODER_DB_HOST` | `postgres` | DB 호스트. 외부 PG 인스턴스면 host:port 로 |
| `VIBECODER_DB_NAME` | `vibecoder` | DB 이름 |
| `VIBECODER_DB_USER` | `vibecoder` | DB 사용자 |
| `VIBECODER_DB_SSLMODE` | `disable` | `prefer`/`require`/`verify-ca`/`verify-full` |
| `PUID` / `PGID` | `1000` / `1000` | 호스트 UID/GID 매칭. `id -u` / `id -g` 로 확인 |
| `VIBE_PORT` | `17880` | 호스트 노출 포트 |
| `VIBE_DATA_ROOT` | `./vibe-coder-data` | **모든 영구 데이터가 들어가는 통합 디렉토리** |
| `VIBE_CLAUDE_DIR` | `${VIBE_DATA_ROOT}/claude` | Claude 인증 디렉토리 — 호스트와 공유하려면 `~/.claude` |
| `VIBECODER_ADMIN_USERNAME` | (미설정) | 첫 부팅 시 admin 자동 생성용 |
| `VIBECODER_ADMIN_PASSWORD` | (미설정) | 위와 한 쌍. 부팅 직후 변경 권장 |
| `JAVA_OPTS` | `-Xmx2g …` | JVM 힙. 호스트 RAM 보고 조정 |
| `VIBECODER_SERVER_MEMORY_LIMIT` | `8g` | 서버 컨테이너 cgroup 메모리 hard limit. AI/빌드 자식 프로세스가 호스트 전체 RAM을 잠식하지 못하게 함 |
| `VIBECODER_POSTGRES_MEMORY_LIMIT` | `1g` | PostgreSQL 컨테이너 메모리 hard limit |
| `VIBECODER_RESOURCE_MEMORY_SOFT_PERCENT` / `_HARD_PERCENT` | `88` / `96` | 서버 내부 resource guard 임계값. soft는 idle TUI 세션 정리 트리거, hard는 새 AI/빌드 작업 시작 차단 기준 |
| `VIBECODER_CONSOLE_TUI_IDLE_TIMEOUT_MINUTES` | `120` | 연결 끊긴 프로젝트 콘솔 TUI 세션 정리 유예. `0` = 무제한 |
| `VIBECODER_IOS_AGENT_ENABLED` | `false` | iPhone/Xcode macOS agent 사용 여부 |
| `VIBECODER_IOS_AGENT_MODE` | `local` | `local` = MacBook 로컬, `ssh` = 원격 macOS agent |
| `VIBECODER_IOS_AGENT_HOST` / `_PORT` / `_USER` | empty / `22` / empty | SSH macOS agent 연결 정보 |
| `VIBECODER_IOS_AGENT_WORKSPACE_ROOT` | empty | 원격 macOS agent 의 작업 루트 |
| `VIBECODER_IOS_AGENT_XCODE_PATH` | `auto` | Xcode developer path override. 기본은 `xcode-select` |

### 볼륨 구조 (v0.7.0 통합)

모든 영구 데이터는 **호스트 한 디렉토리** (`./vibe-coder-data`) 안에 모입니다.
이 디렉토리만 백업하면 워크스페이스 + DB + Android SDK + Gradle 캐시 + MCP
+ Playwright + Claude 인증까지 전부 보존됩니다.

```
${VIBE_DATA_ROOT}/                          컨테이너
─────────────────                           ─────────────
├── workspace/                  →  /workspace                         (소스/APK)
├── postgres/                   →  vibe-coder-postgres 의 /var/lib/postgresql/data  (v0.14.0+)
├── server/                     →  /data                              (로그/빌드 메타)
├── dev-tools/
│   ├── android-sdk/            →  /opt/android-sdk                   (3~4GB)
│   ├── gradle/                 →  /home/vibe/.gradle                 (1~2GB)
│   ├── npm-global/             →  /home/vibe/.local                  (MCP `npm -g`)
│   ├── npm-cache/              →  /home/vibe/.npm                    (npx 캐시)
│   ├── playwright/             →  /home/vibe/.cache/ms-playwright    (선택)
│   ├── config/                 →  /home/vibe/.config                 (도구 설정)
│   ├── codex/                  →  /home/vibe/.codex                  (Codex CODEX_HOME)
│   ├── opencode/               →  /home/vibe/.opencode               (opencode CLI)
│   ├── ssh/                    →  /home/vibe/.ssh                    (v1.2.0+ SSH 키)
│   ├── keystores/              →  /home/vibe/keystores               (v1.5.0+ Android signing keys ⚠️)
│   └── android/                →  /home/vibe/.android                (debug.keystore)
└── claude/                     →  /home/vibe/.claude                 (OAuth/MCP 등록)
```

Codex 전역 지침은 `/home/vibe/.codex/AGENTS.md` 이며, 새 설치에서는
`/home/vibe/.claude/CLAUDE.md` 로 symlink 되어 Claude Code 와 Codex 가 같은
전역 AI 지침을 공유합니다. 사용자가 이미 일반 파일로 `AGENTS.md` 를 만들었다면
entrypoint 는 이를 덮어쓰지 않습니다.

`dev-tools/` 안의 디렉토리는 모두 "한 번 다운로드 → 영구 보존" 도구
캐시입니다. **이미지 업그레이드(`docker compose pull && up -d`) 후에도
절대 사라지지 않습니다.**

**v1.2.0+ SSH 키 (`dev-tools/ssh/`)**: 컨테이너 첫 부팅 시 entrypoint 가
ED25519 키쌍 (`id_ed25519` + `id_ed25519.pub`) 을 자동 생성. 이미 키가 있으면
**절대 덮어쓰지 않음** — 서버 이미지 교체 시 동일 키 유지. 설정 → SSH Key 페이지
에서 공개키 복사하여 Gitea / GitHub 등에 등록.

**v1.5.0+ Android 키스토어 (`dev-tools/keystores/`)** ⚠️: 설정 → Keystores
페이지에서 패키지명별 4개 서명 파일 set (release keystore / debug keystore /
release properties / debug properties)을 자동 생성하고, AdMob IDs 는 선택 파일로
별도 저장. **릴리즈 키 분실 = Play Store 업데이트 영구 차단** 이므로 본 디렉토리는
정기 백업 필수. server.yml 의 `keystore.defaults` 에 본인 DN/password 한 번
입력해두면 form 에서 패키지명만 새로 입력해 즉시 생성 가능.

### 백업 / 이전

PostgreSQL 데이터 일관성을 보장하려면 백업 전 컨테이너를 정지하세요.

```bash
# 백업 (postgres stop 으로 데이터 파일 일관성 보장)
docker compose stop
tar czf vibe-coder-data-$(date +%F).tar.gz vibe-coder-data/
docker compose up -d

# 또는 pg_dump 단독 백업 (server 는 계속 실행)
docker exec vibe-coder-postgres pg_dump -U vibecoder -F c vibecoder > vibe-pg-$(date +%F).pgdump

# 다른 PC로 이전
scp vibe-coder-data-*.tar.gz user@newhost:~/vibe-coder/
ssh user@newhost
cd ~/vibe-coder
tar xzf vibe-coder-data-*.tar.gz
docker compose up -d   # 기존 데이터 그대로 복원됨
```

### v0.7.0 마이그레이션 (이전 사용자)

`v0.6.x` 까지 사용하던 사용자는 **Android SDK 와 Gradle 캐시가 named volume
(`vibe-android-sdk`, `vibe-gradle-cache`) 에 저장**되어 있고, MCP 는 시스템
디렉토리에 있어 이미지 업그레이드 시 사라졌습니다. v0.7.0 부터는 모두 bind
mount 로 통일됩니다.

**옵션 1 — 깔끔하게 새로 시작 (권장, 5~15분):**

```bash
# 1) 기존 컨테이너 중지 (named volume 은 보존됨)
docker compose down

# 2) compose.yml + .env 를 새 버전으로 교체
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/.env.example -o .env

# 3) 부팅 → 빌드환경 페이지에서 "모두 설치/업데이트" 클릭
docker compose up -d
# → 브라우저: http://<IP>:17880/env-setup
```

**옵션 2 — 기존 named volume 데이터 복사 (대역폭 절약):**

```bash
docker compose down

# Android SDK
docker run --rm \
    -v vibe-coder_vibe-android-sdk:/from \
    -v "$(pwd)/vibe-coder-data/dev-tools/android-sdk":/to \
    alpine sh -c 'cp -a /from/. /to/'

# Gradle 캐시
docker run --rm \
    -v vibe-coder_vibe-gradle-cache:/from \
    -v "$(pwd)/vibe-coder-data/dev-tools/gradle":/to \
    alpine sh -c 'cp -a /from/. /to/'

# Claude 인증 (호스트 ~/.claude 마운트였다면 그대로 두면 됨)
# 통합 디렉토리로 옮기려면:
cp -a ~/.claude vibe-coder-data/claude

docker compose up -d
# 확인 후 named volume 삭제 (선택)
docker volume rm vibe-coder_vibe-android-sdk vibe-coder_vibe-gradle-cache
```

> ⚠ **MCP 는 옵션 1, 2 어느 쪽이든 재설치 필요합니다.** 이전엔 시스템
> 디렉토리(`/usr/local/lib/node_modules`)에 깔려서 이미지 layer 안에만
> 존재했기 때문입니다. v0.7.0 부터는 `/home/vibe/.local` (bind mount) 에
> 떨어지므로 한 번 설치 후 영구 보존됩니다.

---

## doctor

```bash
docker exec -it vibe-coder-server vibe-doctor              # 인터랙티브 (권장)
docker exec -it vibe-coder-server vibe-doctor check        # 진단만
docker exec    vibe-coder-server vibe-doctor install       # 비대화형 일괄 설치
docker exec -it vibe-coder-server vibe-doctor android      # Android SDK만
docker exec -it vibe-coder-server vibe-doctor claude       # Claude 인증만
docker exec -it vibe-coder-server vibe-doctor mcp          # 선택적 MCP만
```

처음 실행 시 다음 순서로 진행됩니다.

1. **환경 진단** — JDK / Node / git / Claude CLI / 워크스페이스 권한
2. **Android SDK 설치** — cmdline-tools (130MB) → 라이선스 자동 수락 → platform-tools + platforms;android-35 + build-tools;35.0.0 + emulator + android-35 google_apis x86_64 (하드웨어 렌더링 O — 스크린샷 확인 가능)
3. **Claude 인증** — 호스트 ~/.claude 마운트 권장. 또는 컨테이너 안 `claude login`.
4. **선택적 MCP** — filesystem, sqlite, fetch, playwright 등 (개별 동의)
5. **최종 점검** — 모든 컴포넌트 ✓ 확인

---

## 웹 UI

`http://<PC IP>:17880/`

| 페이지 | 기능 |
|---|---|
| `/setup` | 첫 부팅 시 admin 계정 생성 |
| `/login` | 로그인 |
| `/` | 대시보드 (서버 상태, 환경 진단, 최근 빌드, provider quota) |
| `/projects` | 프로젝트 목록 / 등록 / 정렬 |
| `/projects/{id}` | 프로젝트 탭 (콘솔, 빌드, 파일, Git, 히스토리, 키스토어 등) |
| `/chat` | 프로젝트 없는 일반 채팅 |
| `/terminal` | 컨테이너 workspace PTY 터미널 (`security.allowTerminal=true`) |
| `/env-setup` | Android SDK / Flutter / Codex / OpenCode / SSH 서버 / MCP 설치 |
| `/settings` | server.yml 항목 GUI 편집 |
| `/password` | 비밀번호 변경 |
| `/devices` | 로그인된 디바이스 목록 / revoke |

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

doctor 미실행. `docker exec -it vibe-coder-server vibe-doctor android` 실행.

### Claude가 인증 안 됨

호스트 `~/.claude` 마운트 권장. 또는 `docker exec -it --user vibe vibe-coder-server claude login`.

### 빌드가 느림

`vibe-gradle-cache` 볼륨이 첫 빌드에서 채워집니다. 2번째 빌드부터 빨라짐.
RAM 여유가 있다면 `.env`에서 `JAVA_OPTS=-Xmx8g` 등으로 늘리세요.

### Windows / WSL2에서 사용 시

프로젝트 소스를 **WSL2 안의 리눅스 파일시스템**(`/home/...`)에 두세요.
`/mnt/c/...` 의 윈도우 디스크 경로를 마운트하면 빌드 I/O 가 5~20배 느려집니다.

---

## 빌드 / 푸시 (메인테이너용)

### 일반 commit 푸시 (multi-arch, amd64 + arm64)

```bash
docker buildx create --name vibe-builder --driver docker-container --use  # 1회만
docker buildx build \
    --platform linux/amd64,linux/arm64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<버전> \
    -t siamakerlab/vibe-coder-server:latest \
    --push \
    .
```

Apple Silicon Mac 사용자도 native 속도로 설치할 수 있도록 slim 기본 이미지는
항상 `linux/amd64,linux/arm64` 로 게시합니다. x86_64 Linux builder에서 arm64를
함께 만들려면 QEMU/binfmt가 필요합니다.

```bash
docker run --privileged --rm tonistiigi/binfmt --install arm64
docker buildx inspect --bootstrap
```

### amd64-only 긴급 푸시

```bash
docker buildx build \
    --platform linux/amd64 \
    -f docker/Dockerfile \
    -t siamakerlab/vibe-coder-server:<버전> \
    --push \
    .
```

보안 hotfix 등으로 빌드 시간을 극단적으로 줄여야 할 때만 사용합니다. 이 경우
Apple Silicon Mac 사용자는 해당 태그를 native arm64로 받을 수 없으므로 CHANGELOG에
반드시 amd64-only 임을 남깁니다.

---

## 보안 메모

- 이 이미지는 **LAN 내부 전용**입니다. 공인 IP에 노출하지 마세요.
- Admin 비밀번호 정책: 길이 ≥ 8, 영문+숫자 혼합.
- 페어링 토큰 / 비밀번호는 DB에 **hash만** 저장됩니다 (BCrypt cost 12, SHA-256).
- `.env`에 admin 비밀번호를 plain text로 둘 경우, 부팅 직후 `/password`에서 변경하세요.
