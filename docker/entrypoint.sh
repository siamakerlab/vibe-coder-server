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

# ─── 2. 볼륨 소유권 정리 ─────────────────────────────────────────────────────
# 매번 chown -R 하면 느리므로, 디렉토리만 1회 chown + 새 파일은 vibe가 만들도록.
for dir in /workspace /data /opt/android-sdk /home/vibe/.gradle /home/vibe/.claude /home/vibe/.config; do
    if [[ -d "$dir" ]]; then
        chown vibe:vibe "$dir" 2>/dev/null || true
    fi
done

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
        exec gosu vibe:vibe /opt/vibe-coder/bin/server
        ;;
    doctor)
        shift
        exec gosu vibe:vibe /usr/local/bin/vibe-doctor "$@"
        ;;
    shell|bash|sh)
        exec gosu vibe:vibe /bin/bash
        ;;
    *)
        # 그 외 명령은 vibe로 그대로 실행 (`docker run ... claude --version` 등)
        exec gosu vibe:vibe "$@"
        ;;
esac
