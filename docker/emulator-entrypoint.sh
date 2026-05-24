#!/usr/bin/env bash
#
# vibe-coder-server :full 변형 entrypoint (v0.24.0+).
#
# 동작:
#   1. Xvfb :99 (1080x1920) 가상 디스플레이 부팅
#   2. fluxbox 최소 WM (선택적, qemu 가 root window 만 써도 됨)
#   3. x11vnc 가 :99 의 화면을 VNC 로 export (5900 internal-only)
#   4. websockify 가 5900 → 6080 (noVNC 정적 자원 same origin proxy)
#   5. 슬림 본 entrypoint (/usr/local/bin/entrypoint.sh) 로 위임 = vibe-coder 서버 시작
#
# 모든 백그라운드 데몬은 -no-X 옵션으로 quiet. 죽으면 tini 가 컨테이너 종료를 결정.
# 컨테이너 재시작 = 모든 데몬 + 서버 재기동 = AVD 도 종료 (사용자가 다시 launch).
#
# 이 entrypoint 는 :full 이미지 전용. 슬림 이미지는 entrypoint.sh 만 사용.

set -euo pipefail

log() { echo "[full-entrypoint] $*"; }

# Xvfb — 가상 X 디스플레이.
if ! pgrep -x Xvfb > /dev/null 2>&1; then
    log "Xvfb on :99 ..."
    Xvfb :99 -screen 0 1080x1920x24 -nolisten tcp &
    sleep 1
fi
export DISPLAY=:99

# minimal WM — qemu 가 root window 한 개만 띄우므로 fluxbox 도 없어도 동작하지만
# 마우스 cursor / focus 처리가 더 자연스러워짐. 가벼움 (~5MB).
if command -v fluxbox > /dev/null 2>&1 && ! pgrep -x fluxbox > /dev/null 2>&1; then
    log "fluxbox WM ..."
    fluxbox > /dev/null 2>&1 &
fi

# x11vnc — :99 의 화면을 VNC 프로토콜로 노출. internal-only (127.0.0.1).
if command -v x11vnc > /dev/null 2>&1 && ! pgrep -x x11vnc > /dev/null 2>&1; then
    log "x11vnc on 127.0.0.1:5900 (no-password — admin-only path)..."
    # -nopw 는 보안 risk 처럼 보이지만 localhost-only 노출이라 ok.
    # 외부 접근은 vibe-coder admin 인증을 거친 reverse-proxy 만.
    x11vnc -display :99 -nopw -listen 127.0.0.1 -forever -shared -xkb -quiet > /dev/null 2>&1 &
fi

# websockify (noVNC 의 HTTP+WS 게이트웨이) — 6080 internal-only.
if command -v websockify > /dev/null 2>&1 && ! pgrep -x websockify > /dev/null 2>&1; then
    NOVNC_ROOT="${NOVNC_ROOT:-/usr/share/novnc}"
    if [ -d "$NOVNC_ROOT" ]; then
        log "websockify on 127.0.0.1:6080 (novnc=$NOVNC_ROOT)..."
        websockify --web="$NOVNC_ROOT" 127.0.0.1:6080 127.0.0.1:5900 > /dev/null 2>&1 &
    else
        log "WARN: NOVNC_ROOT '$NOVNC_ROOT' 없음 — websockify 건너뜀."
    fi
fi

# 슬림 본 entrypoint 가 server start, gosu 전환, env adjust 등을 처리.
exec /usr/local/bin/entrypoint.sh "$@"
