#!/usr/bin/env bash
# 선택적 MCP 설치 — manifest.yml의 mcp_optional 항목.
# shellcheck shell=bash source=common.sh

install_optional_mcp() {
    log_step "선택적 MCP 설치"
    log_dim "Claude Code에서 사용할 추가 도구 (개별 동의)"

    local manifest="/opt/vibe-doctor/manifest.yml"
    [[ -f "$manifest" ]] || { log_warn "manifest.yml 없음 → 건너뜀"; return 0; }

    # 단순 YAML 파싱: name/package/description 묶음을 추출
    local in_section=0
    local name="" pkg="" desc=""
    while IFS= read -r line; do
        if [[ "$line" =~ ^mcp_optional: ]]; then
            in_section=1; continue
        fi
        if (( in_section )) && [[ "$line" =~ ^[a-z] ]]; then
            in_section=0
        fi
        (( in_section )) || continue

        if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+name:[[:space:]]*\"([^\"]+)\" ]]; then
            # 직전 항목 처리
            [[ -n "$name" ]] && _maybe_install_mcp "$name" "$pkg" "$desc"
            name="${BASH_REMATCH[1]}"; pkg=""; desc=""
        elif [[ "$line" =~ ^[[:space:]]+package:[[:space:]]*\"([^\"]+)\" ]]; then
            pkg="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^[[:space:]]+description:[[:space:]]*\"([^\"]+)\" ]]; then
            desc="${BASH_REMATCH[1]}"
        fi
    done < "$manifest"
    # 마지막 항목
    [[ -n "$name" ]] && _maybe_install_mcp "$name" "$pkg" "$desc"
}

_maybe_install_mcp() {
    local name="$1" pkg="$2" desc="$3"
    [[ -z "$pkg" ]] && return 0

    printf '\n%sMCP%s: %s — %s\n' "${C_BOLD}" "${C_RESET}" "$name" "$desc"
    if prompt_yn "  설치하시겠어요?" N; then
        log_info "  npm install -g $pkg ..."
        if npm install -g "$pkg" >/dev/null 2>&1; then
            log_ok "  → 완료"
        else
            log_err "  → 실패 (네트워크/권한 확인)"
        fi
    else
        log_dim "  → 건너뜀"
    fi
}
