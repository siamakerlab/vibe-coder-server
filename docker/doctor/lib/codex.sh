#!/usr/bin/env bash
# Codex CLI 설치 — OpenAI Codex CLI (npm `@openai/codex`).
#
# vibe 사용자의 npm 글로벌 prefix(/home/vibe/.local — npm-global bind mount)에 설치되어
# 이미지 업데이트(재배포) 후에도 보존된다 (gradle.sh / flutter.sh 와 동일한 .local 영속
# 패턴). 로그인/설정은 CODEX_HOME(=/home/vibe/.config/codex, .config bind mount)에 저장
# 되므로 컨테이너 재생성·이미지 교체에도 로그인 상태가 유지된다.
#
# v1.145.0 — Codex CLI 지원 (옵션). "모두 설치"에는 포함하지 않고 개별 설치 카드로만 노출
# (Android 빌드 필수 도구가 아니므로 — Flutter 와 같은 선택 컴포넌트 취급).
#
# shellcheck shell=bash source=common.sh

install_codex() {
    # 인자: $1 = version (없으면 CODEX_VERSION env → latest)
    local ver="${1:-${CODEX_VERSION:-}}"
    local pkg="@openai/codex${ver:+@$ver}"

    log_step "Codex CLI 설치 (npm ${pkg})"
    log_dim "OpenAI Codex CLI — /home/vibe/.local 에 글로벌 설치(영속). 로그인은 CODEX_HOME 에 저장."

    if ! command -v npm >/dev/null 2>&1; then
        log_err "npm 이 없습니다 — Node.js 가 이미지에 포함돼야 합니다."
        return 1
    fi

    # 로그인/설정 영속 디렉토리 보장 (.config bind mount 안). Dockerfile ENV 와 동일 경로.
    : "${CODEX_HOME:=/home/vibe/.config/codex}"
    mkdir -p "$CODEX_HOME" 2>/dev/null || true

    # npm 글로벌 설치(vibe prefix=/home/vibe/.local). 신규 설치/업데이트 모두 같은 명령 — 멱등.
    log_info "npm install -g ${pkg}"
    if ! npm install -g "$pkg"; then
        log_err "npm install -g ${pkg} 실패 (네트워크 또는 패키지명 확인)"
        return 1
    fi

    # 검증
    if command -v codex >/dev/null 2>&1 && codex --version >/dev/null 2>&1; then
        log_ok "Codex 설치 완료 — $(codex --version 2>/dev/null | head -1)"
        log_dim "로그인: 콘솔/터미널에서 'codex login' 실행 (CODEX_HOME=${CODEX_HOME} 에 영속)."
    else
        log_err "설치는 됐으나 codex --version 실행 실패 (/home/vibe/.local/bin PATH 확인)."
        return 1
    fi
}
