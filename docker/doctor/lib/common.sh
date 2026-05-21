#!/usr/bin/env bash
# 공통 유틸 — 로그, 프롬프트, 색상.
# shellcheck shell=bash

set -uo pipefail

# 색상 (TTY일 때만)
if [[ -t 1 ]]; then
    C_RESET=$'\033[0m'
    C_RED=$'\033[0;31m'
    C_GREEN=$'\033[0;32m'
    C_YELLOW=$'\033[0;33m'
    C_BLUE=$'\033[0;34m'
    C_BOLD=$'\033[1m'
    C_DIM=$'\033[2m'
else
    C_RESET="" C_RED="" C_GREEN="" C_YELLOW="" C_BLUE="" C_BOLD="" C_DIM=""
fi

log_info()    { printf '%s[•]%s %s\n' "${C_BLUE}"   "${C_RESET}" "$*"; }
log_ok()      { printf '%s[✓]%s %s\n' "${C_GREEN}"  "${C_RESET}" "$*"; }
log_warn()    { printf '%s[!]%s %s\n' "${C_YELLOW}" "${C_RESET}" "$*"; }
log_err()     { printf '%s[✗]%s %s\n' "${C_RED}"    "${C_RESET}" "$*" >&2; }
log_step()    { printf '\n%s━━ %s%s\n'  "${C_BOLD}" "$*" "${C_RESET}"; }
log_dim()     { printf '%s    %s%s\n'   "${C_DIM}"  "$*" "${C_RESET}"; }

# Y/N 프롬프트. $1: 질문, $2: 기본값 (Y or N, default Y)
prompt_yn() {
    local question="$1"
    local default="${2:-Y}"
    local hint
    [[ "$default" == "Y" ]] && hint="[Y/n]" || hint="[y/N]"

    # 비대화형(TTY 없음)일 땐 기본값 자동 선택
    if [[ ! -t 0 ]]; then
        log_dim "비대화형 모드 → 기본값 '$default' 선택"
        [[ "$default" == "Y" ]]
        return $?
    fi

    while true; do
        printf '%s? %s%s %s ' "${C_BOLD}" "${question}" "${C_RESET}" "${hint}"
        local ans
        read -r ans
        ans="${ans:-$default}"
        case "${ans,,}" in
            y|yes) return 0 ;;
            n|no)  return 1 ;;
            *)     log_warn "y 또는 n으로 답해주세요." ;;
        esac
    done
}

# 진행 표시줄 (대용량 다운로드 후 보여주기)
spinner_pid=""
spinner_start() {
    if [[ ! -t 1 ]]; then return 0; fi
    local msg="${1:-작업 중}"
    (
        local frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
        local i=0
        while :; do
            printf '\r%s %s' "${frames:$((i%10)):1}" "$msg"
            i=$((i+1))
            sleep 0.1
        done
    ) &
    spinner_pid=$!
    disown
}
spinner_stop() {
    [[ -n "$spinner_pid" ]] && kill "$spinner_pid" 2>/dev/null
    spinner_pid=""
    printf '\r\033[K'
}

# 정리: 어떤 이유로든 종료 시 spinner 정리
trap 'spinner_stop' EXIT
