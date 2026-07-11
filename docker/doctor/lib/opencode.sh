#!/usr/bin/env bash
# opencode CLI 설치 — opencode (z.ai coding plan provider, https://opencode.ai).
#
# 공식 install 스크립트(curl ... | bash)로 /home/vibe/.opencode/bin/opencode 에 설치.
# 영속: compose.yml 이 dev-tools/opencode:/home/vibe/.opencode 볼륨을 마운트해야 이미지
# 업데이트/컨테이너 재생성 후에도 유지된다 (v1.160.2 에서 볼륨 추가 — 이전엔 임시 레이어라
# 재생성 시 사라졌음). auth.json 은 .local 볼륨의 ~/.local/share/opencode/auth.json 에 저장
# — 빌드환경 "OpenCode (z.ai)" 카드의 API key 입력으로 작성.
#
# v1.156.0 — Phase 2 OpenCode provider 지원 (옵션). "모두 설치"에서는 제외, 개별 카드로만
# (Android 빌드 필수 도구가 아님 — Codex/Flutter 와 같은 선택 컴포넌트 취급).
#
# shellcheck shell=bash source=common.sh

install_opencode() {
    # 인자: $1 = version (미사용 — install 스크립트가 항상 latest). 호환성용.
    : "${1:-}"

    log_step "opencode CLI 설치"
    log_dim "opencode (z.ai coding plan provider) — 공식 install 스크립트로 /home/vibe/.opencode 에 설치."

    # 영속 디렉토리 보장 (auth.json 도 이 영역에 저장).
    mkdir -p /home/vibe/.opencode/bin 2>/dev/null || true
    mkdir -p /home/vibe/.local/share/opencode 2>/dev/null || true

    # 공식 install 스크립트 — /home/vibe/.opencode/bin 에 바이너리 설치 + shell RC 에 PATH 추가.
    log_info "curl -fsSL https://opencode.ai/install | bash"
    if ! curl -fsSL https://opencode.ai/install | bash; then
        log_err "opencode install 스크립트 실패 (네트워크 또는 https://opencode.ai/install 접근 확인)"
        return 1
    fi

    # PATH 에 /home/vibe/.opencode/bin 이 없으면 현재 세션에 추가 (검증용).
    case ":${PATH:-}:" in
        *":/home/vibe/.opencode/bin:"*) ;;
        *) export PATH="/home/vibe/.opencode/bin:${PATH:-}" ;;
    esac

    # 검증 — 직접 경로 우선, PATH 경유 fallback.
    local opencode_bin="/home/vibe/.opencode/bin/opencode"
    if [ -x "$opencode_bin" ] && "$opencode_bin" --version >/dev/null 2>&1; then
        log_ok "opencode 설치 완료 — $("$opencode_bin" --version 2>/dev/null | head -1)"
        log_dim "로그인: 빌드환경 'OpenCode (z.ai)' 카드에서 API key 입력, 또는 터미널에서 'opencode providers login'."
    elif command -v opencode >/dev/null 2>&1 && opencode --version >/dev/null 2>&1; then
        log_ok "opencode 설치 완료 — $(opencode --version 2>/dev/null | head -1)"
        log_dim "로그인: 빌드환경 'OpenCode (z.ai)' 카드에서 API key 입력, 또는 터미널에서 'opencode providers login'."
    else
        log_err "설치는 됐으나 opencode --version 실행 실패 (/home/vibe/.opencode/bin PATH 확인)."
        return 1
    fi
}
