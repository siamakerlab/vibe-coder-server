#!/usr/bin/env bash
# vibe-coder 컨테이너 엔트리포인트.
#
# 책임
#   1. 호스트 UID/GID 매칭 (PUID/PGID env로 받음, 미설정 시 1000)
#   2. 볼륨 마운트 디렉토리 소유권 정리
#   3. Admin 부트스트랩 env를 서버 시스템 프로퍼티로 패스스루
#   4. doctor 미실행 안내 (Android SDK 누락 시)
#   5. server / vibe-doctor / shell 중 하나로 분기

set -euo pipefail

# ─── 0. 색상 (TTY일 때만) ────────────────────────────────────────────────────
if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'; C_BLUE=$'\033[0;34m'
    C_YELLOW=$'\033[0;33m'; C_GREEN=$'\033[0;32m'; C_BOLD=$'\033[1m'
else
    C_RESET="" C_BLUE="" C_YELLOW="" C_GREEN="" C_BOLD=""
fi
log()  { printf '%s[entrypoint]%s %s\n' "${C_BLUE}" "${C_RESET}" "$*"; }
warn() { printf '%s[entrypoint]%s %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
ok()   { printf '%s[entrypoint]%s %s\n' "${C_GREEN}" "${C_RESET}" "$*"; }

# ─── 1. UID/GID 매칭 ────────────────────────────────────────────────────────
# 호스트의 PUID/PGID를 받아 vibe 사용자의 UID/GID를 매칭.
# 마운트된 볼륨의 호스트 측 소유권과 일치시켜 권한 충돌 방지.
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

current_uid="$(id -u vibe)"
current_gid="$(id -g vibe)"

if [[ "$current_uid" != "$PUID" ]] || [[ "$current_gid" != "$PGID" ]]; then
    log "UID/GID 매칭: ${current_uid}:${current_gid} → ${PUID}:${PGID}"
    groupmod -o -g "$PGID" vibe 2>/dev/null || true
    usermod  -o -u "$PUID" -g "$PGID" vibe 2>/dev/null || true
fi

# v1.73.0 — 헤드리스 안드로이드 에뮬레이터 KVM 가속(/dev/kvm). compose 에 device 가
# 노출돼 있으면, 그 device 의 실제 GID 로 kvm 그룹을 만들어(또는 GID 조정) vibe 를 추가한다.
# 호스트마다 kvm GID 가 달라(보통 36 또는 108) group_add 하드코딩 대신 동적 매핑.
if [[ -e /dev/kvm ]]; then
    kvm_gid="$(stat -c '%g' /dev/kvm 2>/dev/null || echo '')"
    if [[ -n "$kvm_gid" ]]; then
        if getent group kvm >/dev/null 2>&1; then
            groupmod -o -g "$kvm_gid" kvm 2>/dev/null || true
        else
            groupadd -o -g "$kvm_gid" kvm 2>/dev/null || true
        fi
        usermod -aG kvm vibe 2>/dev/null || true
        # v1.96.2 — 중요: 아래 유저 전환은 반드시 `gosu vibe`(그룹 생략)여야 한다.
        # `gosu vibe:vibe`(그룹 명시)는 supplementary groups 를 버려서(setgroups 1개만)
        # 여기서 추가한 kvm 그룹이 서버 JVM 프로세스에 적용되지 않는다 → /dev/kvm 접근 불가
        # → 에뮬레이터 -accel on 실패. `gosu vibe` 는 initgroups 로 kvm 포함 전체 적용.
        log "KVM 가속 사용 가능 (/dev/kvm gid=${kvm_gid})"
    fi
else
    log "참고: /dev/kvm 없음 — 안드로이드 에뮬레이터는 미가속(매우 느림). compose devices 에 /dev/kvm 추가 권장."
fi

# ─── 2. 볼륨 소유권 정리 ─────────────────────────────────────────────────────
# 매번 chown -R 하면 느리므로, 디렉토리만 1회 chown + 새 파일은 vibe가 만들도록.
# v0.7.0 — 다음을 추가했다 (이미지 업그레이드 시 사라지던 도구들의 영구 위치):
#   /home/vibe/.npm                  npx 캐시 (MCP 자주 사용)
#   /home/vibe/.cache/ms-playwright  Playwright 브라우저
#   /home/vibe/.local                vibe 의 npm 글로벌 prefix (MCP 영구 설치)
for dir in \
    /workspace \
    /data \
    /opt/android-sdk \
    /home/vibe/.gradle \
    /home/vibe/.claude \
    /home/vibe/.config \
    /home/vibe/.npm \
    /home/vibe/.cache \
    /home/vibe/.cache/ms-playwright \
    /home/vibe/.local \
    /home/vibe/.ssh \
    /home/vibe/keystores \
; do
    if [[ -d "$dir" ]]; then
        chown vibe:vibe "$dir" 2>/dev/null || true
    fi
done

# v1.145.1 — Codex CLI 로그인/사용량 캡처는 CODEX_HOME 아래에 auth/log/config 파일을 쓴다.
# 과거에 root docker exec 또는 이전 이미지가 만든 /home/vibe/.config/codex 잔여물이 있으면
# `codex login --device-auth` 가 log/auth 파일 생성에 실패하므로, 해당 하위 트리만 보정한다.
CODEX_CFG_DIR="${CODEX_HOME:-/home/vibe/.config/codex}"
mkdir -p "$CODEX_CFG_DIR" 2>/dev/null || true
if [[ -d "$CODEX_CFG_DIR" ]]; then
    bad_codex_owner="$(find "$CODEX_CFG_DIR" -mindepth 0 ! -user vibe -print -quit 2>/dev/null || true)"
    if [[ -n "$bad_codex_owner" ]]; then
        warn "$CODEX_CFG_DIR 안 root-owned 잔여물 발견 — chown -R vibe:vibe"
        chown -R vibe:vibe "$CODEX_CFG_DIR" 2>/dev/null || true
    else
        chown vibe:vibe "$CODEX_CFG_DIR" 2>/dev/null || true
    fi
    chmod 700 "$CODEX_CFG_DIR" 2>/dev/null || true
fi

# v1.146.x — Codex 전역 지침은 Claude 전역 지침과 같은 내용을 공유한다.
# Codex 는 CODEX_HOME/AGENTS.md 를 전역 instruction 으로 읽고, Claude Code 는
# /home/vibe/.claude/CLAUDE.md 를 읽는다. 새 설치/비어있는 Codex 설정에서는
# AGENTS.md 를 CLAUDE.md 로 symlink 해서 두 provider 의 전역 규칙을 단일 파일로 관리한다.
# 사용자가 이미 일반 파일로 AGENTS.md 를 직접 만들었다면 덮어쓰지 않는다.
CODEX_AGENTS_FILE="$CODEX_CFG_DIR/AGENTS.md"
SHARED_AI_INSTRUCTIONS="../../.claude/CLAUDE.md"
if [[ ! -e "$CODEX_AGENTS_FILE" || -L "$CODEX_AGENTS_FILE" ]]; then
    ln -sfn "$SHARED_AI_INSTRUCTIONS" "$CODEX_AGENTS_FILE" 2>/dev/null || true
    chown -h vibe:vibe "$CODEX_AGENTS_FILE" 2>/dev/null || true
else
    warn "$CODEX_AGENTS_FILE 가 일반 파일로 존재 — Claude 전역 CLAUDE.md symlink 생성을 건너뜁니다."
fi

# v1.7.23 — 워크스페이스 각 프로젝트의 .gradle / .android 캐시 디렉토리 안에
# root 소유 잔여물 자동 정리. 이전에 docker exec (default user root) 또는
# 다른 process 가 그 path 에 쓴 file 이 남으면 vibe 가 Gradle build 시
# "executionHistory.lock (Permission denied)" 에러. find 로 빠르게 idempotent.
for sub in .gradle .android; do
    find /workspace -mindepth 2 -maxdepth 3 -type d -name "$sub" 2>/dev/null | while read -r p; do
        bad=$(find "$p" -mindepth 1 ! -user vibe -print -quit 2>/dev/null)
        if [[ -n "$bad" ]]; then
            warn "$p 안 root-owned 잔여물 발견 — chown -R vibe:vibe"
            chown -R vibe:vibe "$p" 2>/dev/null || true
        fi
    done
done

# ─── 2b. SSH 키 자동 발급 (v1.2.0, v1.2.1 graceful degrade) ────────────────
# 컨테이너 첫 부팅 시 vibe 사용자의 ED25519 SSH 키쌍을 자동 생성.
# 이미 있으면 절대 덮어쓰지 않음 (서버 업데이트 / 이미지 교체 시 동일 키 유지).
# 볼륨 마운트로 영속. 재생성은 운영자가 설정 UI 의 "Regenerate" 버튼으로 명시 트리거.
#
# v1.2.1 — SSH 키 자동 발급은 *보조* 기능. ssh-keygen 누락 / 생성 실패가 서버
# 부팅 자체를 막지 않도록 graceful degrade. set -e 환경에서도 안전하게 통과.
SSH_DIR=/home/vibe/.ssh
SSH_KEY=$SSH_DIR/id_ed25519
if [[ ! -f "$SSH_KEY" ]]; then
    if ! command -v ssh-keygen >/dev/null 2>&1; then
        warn "openssh-client 미설치 — SSH 키 자동 생성을 건너뜁니다."
        warn "git clone/push (SSH) 가 필요하면 이미지에 openssh-client 추가 후 재기동하세요."
    else
        mkdir -p "$SSH_DIR"
        chown vibe:vibe "$SSH_DIR"
        chmod 700 "$SSH_DIR"
        log "SSH 키 (ED25519) 자동 생성 — $SSH_KEY"
        if gosu vibe ssh-keygen -t ed25519 -f "$SSH_KEY" -N "" \
            -C "vibe-coder-server@$(hostname)-$(date +%Y%m%d)" >/dev/null
        then
            chmod 600 "$SSH_KEY"
            chmod 644 "${SSH_KEY}.pub"
            ok "SSH 공개 키 생성 완료. 설정 → SSH Key 에서 복사하여 Gitea/GitHub 등록 가능."
        else
            warn "ssh-keygen 실행 실패 — SSH 키 자동 생성을 건너뜁니다 (서버 부팅은 계속)."
        fi
    fi
fi
# 권한 정리 — 매 부팅마다 idempotent (chmod 가 mounted volume 의 잘못된 mode 정정).
[[ -d "$SSH_DIR" ]] && chmod 700 "$SSH_DIR" 2>/dev/null || true
[[ -f "$SSH_KEY" ]] && chmod 600 "$SSH_KEY" 2>/dev/null || true
[[ -f "${SSH_KEY}.pub" ]] && chmod 644 "${SSH_KEY}.pub" 2>/dev/null || true

# v0.7.0 — 빈 .local 볼륨이 마운트된 케이스 대비: .npmrc 가 home 에 있고 .local
# 이 비어 있으면 prefix 가 무효화될 수 있어, idempotent 재생성.
if [[ ! -f /home/vibe/.npmrc ]]; then
    printf 'prefix=/home/vibe/.local\nfund=false\nupdate-notifier=false\n' \
        > /home/vibe/.npmrc
    chown vibe:vibe /home/vibe/.npmrc
fi

# ─── 2c. Git global config 영속 위치 보장 (v1.9.0) ───────────────────────────
# Dockerfile 의 ENV GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config 와 짝.
# 디렉토리는 dev-tools/config 볼륨이 cover 하지만, 첫 부팅 시 비어 있을 수 있어
# idempotent 생성 + ownership 정리. 파일 자체는 사용자가 /env-setup 의 "Git
# Identity" 카드에서 입력해야 채워짐 (운영자가 직접 만들지 않음 — 자동 ID 추측
# 금지).
GIT_CFG_DIR=/home/vibe/.config/git
GIT_CFG_FILE=$GIT_CFG_DIR/config
if [[ ! -d "$GIT_CFG_DIR" ]]; then
    mkdir -p "$GIT_CFG_DIR"
fi
chown vibe:vibe "$GIT_CFG_DIR" 2>/dev/null || true
chmod 700 "$GIT_CFG_DIR" 2>/dev/null || true
if [[ -f "$GIT_CFG_FILE" ]]; then
    chown vibe:vibe "$GIT_CFG_FILE" 2>/dev/null || true
    chmod 600 "$GIT_CFG_FILE" 2>/dev/null || true
fi

# ─── 2d. Claude TUI 온보딩/trust 부트스트랩 (v1.70.6) ────────────────────────
# Claude Code 2.1.x interactive TUI 는 ~/.claude/.claude.json 의
# hasCompletedOnboarding 플래그가 없으면 매번 "Select login method" 온보딩을
# 띄운다. 서버의 quota 표시는 /usage 화면을 PTY 로 캡처해 얻는데, 온보딩 화면에
# 막히면 사용량이 표시되지 않는다(2.1.158 에서 발생). 이미 로그인(oauthAccount)
# 된 상태면 온보딩은 무의미하므로 플래그를 세우고, 캡처가 도는 cwd
# (/workspace, /workspace/__scratch__) 의 trust 를 사전 기록해 trust 다이얼로그도
# 건너뛰게 한다. config 가 있을 때만, 변경이 있을 때만 기록 (idempotent).
CLAUDE_CFG=/home/vibe/.claude/.claude.json
if [[ -f "$CLAUDE_CFG" ]] && command -v node >/dev/null 2>&1; then
    gosu vibe node -e '
      const fs=require("fs"); const f=process.argv[1];
      try {
        const d=JSON.parse(fs.readFileSync(f,"utf8"));
        let changed=false;
        if(d.hasCompletedOnboarding!==true){d.hasCompletedOnboarding=true;changed=true;}
        if(!(d.numStartups>=1)){d.numStartups=(d.numStartups||0)+1;changed=true;}
        d.projects=d.projects||{};
        for(const p of ["/workspace","/workspace/__scratch__"]){
          d.projects[p]=d.projects[p]||{};
          if(d.projects[p].hasTrustDialogAccepted!==true){d.projects[p].hasTrustDialogAccepted=true;changed=true;}
        }
        if(changed){fs.writeFileSync(f,JSON.stringify(d));console.error("[entrypoint] Claude onboarding/trust bootstrapped");}
      } catch(e){ console.error("[entrypoint] Claude config bootstrap skipped: "+e.message); }
    ' "$CLAUDE_CFG" 2>&1 || true
fi

# ─── 3. Admin 부트스트랩 (있으면 서버 sys-prop으로 전달) ──────────────────────
JAVA_OPTS="${JAVA_OPTS:-}"
if [[ -n "${VIBECODER_ADMIN_USERNAME:-}" ]] && [[ -n "${VIBECODER_ADMIN_PASSWORD:-}" ]]; then
    log "Admin 부트스트랩 자격증명 감지 → 서버에 전달 (첫 실행 시 자동 생성)"
    # 비밀번호가 ps 등에 노출되지 않도록 env로만 전달 (JVM은 env 자체에서 읽음)
    export VIBECODER_ADMIN_USERNAME VIBECODER_ADMIN_PASSWORD
fi

# ─── 4. Android SDK 누락 안내 ────────────────────────────────────────────────
if [[ ! -d "${ANDROID_HOME:-/opt/android-sdk}/platform-tools" ]]; then
    warn ""
    warn "Android SDK가 아직 설치되어 있지 않습니다."
    warn "빌드를 시작하기 전에 다음 명령으로 doctor를 실행하세요:"
    warn ""
    warn "    docker exec -it <container-name> vibe-doctor"
    warn ""
fi

# ─── 5. 디버그 정보 출력 ────────────────────────────────────────────────────
ok "vibe-coder 컨테이너 부팅"
log "PUID:PGID    = ${PUID}:${PGID}"
log "ANDROID_HOME = ${ANDROID_HOME:-(unset)}"
log "WORKSPACE    = ${VIBECODER_WORKSPACE_ROOT:-(unset)}"
log "Admin URL    = http://0.0.0.0:17880/admin"

# ─── 6. 분기 ────────────────────────────────────────────────────────────────
case "${1:-server}" in
    server)
        log "vibe-coder 서버 시작..."
        # gosu로 vibe 사용자로 권한 강등 후 서버 실행
        exec gosu vibe /opt/vibe-coder/bin/server
        ;;
    doctor)
        shift
        exec gosu vibe /usr/local/bin/vibe-doctor "$@"
        ;;
    shell|bash|sh)
        exec gosu vibe /bin/bash
        ;;
    *)
        # 그 외 명령은 vibe로 그대로 실행 (`docker run ... claude --version` 등)
        exec gosu vibe "$@"
        ;;
esac
