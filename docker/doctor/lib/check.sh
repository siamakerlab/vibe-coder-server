#!/usr/bin/env bash
# 환경 진단 — 각 컴포넌트 존재 여부 확인 (read-only).
# shellcheck shell=bash source=common.sh

# 반환: 0=ok, 1=missing, 2=partial
check_jdk() {
    if command -v java >/dev/null 2>&1; then
        local v
        v="$(java -version 2>&1 | head -1)"
        log_ok "JDK: $v"
        return 0
    fi
    log_err "JDK 미설치 (이미지 빌드 오류로 추정 — 이미지 재pull 필요)"
    return 1
}

check_node() {
    if command -v node >/dev/null 2>&1; then
        log_ok "Node.js: $(node --version)"
        return 0
    fi
    log_err "Node.js 미설치 (이미지 빌드 오류)"
    return 1
}

check_claude_cli() {
    if command -v claude >/dev/null 2>&1; then
        log_ok "Claude CLI: $(claude --version 2>/dev/null | head -1)"
        return 0
    fi
    log_err "Claude CLI 미설치"
    return 1
}

check_claude_auth() {
    local cfg="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
    if [[ -f "$cfg/.credentials.json" ]] || [[ -f "$cfg/config.json" ]]; then
        log_ok "Claude 인증: $cfg 에 자격증명 발견"
        return 0
    fi
    log_warn "Claude 인증 누락: $cfg (claude login 필요)"
    return 1
}

check_android_sdk() {
    local sdk="${ANDROID_HOME:-/opt/android-sdk}"
    if [[ ! -d "$sdk/cmdline-tools/latest" ]]; then
        log_warn "Android SDK cmdline-tools 미설치 ($sdk)"
        return 1
    fi
    if [[ ! -d "$sdk/platform-tools" ]]; then
        log_warn "Android SDK platform-tools 미설치"
        return 2
    fi
    if [[ ! -d "$sdk/platforms/android-35" ]]; then
        log_warn "Android SDK platforms;android-35 미설치"
        return 2
    fi
    if [[ ! -d "$sdk/build-tools" ]] || [[ -z "$(ls "$sdk/build-tools" 2>/dev/null)" ]]; then
        log_warn "Android SDK build-tools 미설치"
        return 2
    fi
    log_ok "Android SDK: $sdk (cmdline-tools + platform-tools + platforms + build-tools)"
    return 0
}

check_git() {
    if command -v git >/dev/null 2>&1; then
        log_ok "Git: $(git --version)"
        return 0
    fi
    log_err "Git 미설치 (이미지 빌드 오류)"
    return 1
}

check_workspace() {
    local ws="${VIBECODER_WORKSPACE_ROOT:-/workspace}"
    if [[ ! -d "$ws" ]]; then
        log_warn "워크스페이스 디렉토리 없음: $ws"
        return 1
    fi
    if [[ ! -w "$ws" ]]; then
        log_err "워크스페이스 쓰기 불가: $ws (UID/GID 매칭 확인)"
        return 1
    fi
    log_ok "워크스페이스: $ws"
    return 0
}

check_ssh_server() {
    if command -v sshd >/dev/null 2>&1 || [[ -x /usr/sbin/sshd ]]; then
        log_ok "OpenSSH 서버: 설치됨"
        return 0
    fi
    log_warn "OpenSSH 서버 미설치 (원격 접속이 필요하면 빌드환경의 SSH 서버 카드 사용)"
    return 1
}

check_all() {
    local errors=0
    log_step "환경 진단"
    check_jdk        || errors=$((errors+1))
    check_node       || errors=$((errors+1))
    check_git        || errors=$((errors+1))
    check_claude_cli || errors=$((errors+1))
    check_claude_auth || true  # warning only
    check_workspace  || errors=$((errors+1))
    check_android_sdk || true  # warning only — doctor에서 설치
    check_ssh_server || true   # optional — 원격 접속용
    return $errors
}
