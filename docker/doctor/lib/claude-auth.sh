#!/usr/bin/env bash
# Claude CLI 인증 — 컨테이너 안에서 `claude login` 또는 호스트 ~/.claude 마운트 안내.
# shellcheck shell=bash source=common.sh

setup_claude_auth() {
    local cfg="${CLAUDE_CONFIG_DIR:-/home/vibe/.claude}"

    if [[ -f "$cfg/.credentials.json" ]] || [[ -f "$cfg/config.json" ]]; then
        log_ok "Claude 인증 이미 완료 ($cfg)"
        return 0
    fi

    log_step "Claude CLI 인증"
    cat <<EOF
인증 방법은 두 가지입니다.

  1) 호스트의 ~/.claude 를 컨테이너에 마운트 (권장)
     이미 호스트에서 'claude login' 했다면 compose.yml의 볼륨 마운트만
     확인하면 됩니다:

       volumes:
         - \$HOME/.claude:/home/vibe/.claude

     이후 컨테이너 재시작 → 자동 적용.

  2) 컨테이너 안에서 직접 인증
     아래 명령을 실행하고 표시되는 URL을 브라우저로 열어 로그인:

       claude login

     완료되면 $cfg 에 자격증명이 저장됩니다.

EOF

    if prompt_yn "지금 'claude login'을 실행하시겠어요?" N; then
        log_info "claude login 실행 중... (Ctrl+C로 취소)"
        claude login || log_warn "로그인 실패/취소"
    else
        log_dim "건너뜀. 이후 'docker exec -it vibe-coder claude login' 으로 직접 실행 가능."
    fi
}
