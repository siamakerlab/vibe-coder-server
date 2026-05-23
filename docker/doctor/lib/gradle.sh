#!/usr/bin/env bash
# Gradle 설치 — Wrapper bootstrap 용. /home/vibe/.local/gradle 에 풀고
# /home/vibe/.local/bin/gradle 로 symlink. v0.7.0 의 npm-global bind mount
# 덕에 영구 보존.
#
# vibe-coder 의 신규 안드로이드 프로젝트는 build.gradle.kts 가 있어도
# gradle wrapper 파일들 (gradlew, gradle-wrapper.jar 등) 이 없어 첫 빌드
# 실패. 시스템 gradle 한 번 설치 → BuildService 가 `gradle wrapper` 자동
# 호출 → 그 뒤로는 wrapper 사용.
#
# shellcheck shell=bash source=common.sh

GRADLE_HOME="/home/vibe/.local/gradle"

# 최신 stable Gradle 버전 조회 — services.gradle.org/versions/current.
# 실패 시 fallback (현재 시점 안정 버전).
gradle_latest_stable() {
    local resp
    resp=$(curl -fsSL --max-time 8 https://services.gradle.org/versions/current 2>/dev/null)
    if [[ -n "$resp" ]]; then
        # jq 가 있으면 jq, 없으면 grep+sed.
        if command -v jq &>/dev/null; then
            local v
            v=$(printf '%s' "$resp" | jq -r '.version // empty' 2>/dev/null)
            [[ -n "$v" ]] && { echo "$v"; return 0; }
        fi
        # grep fallback — `"version" : "8.10.2",` 형태.
        local v
        v=$(printf '%s' "$resp" | grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/')
        [[ -n "$v" ]] && { echo "$v"; return 0; }
    fi
    echo "8.7"   # fallback
}

install_gradle() {
    # 인자: $1 = version (없으면 GRADLE_VERSION env 또는 최신 stable 조회)
    local ver="${1:-${GRADLE_VERSION:-}}"
    if [[ -z "$ver" ]]; then
        log_info "최신 stable Gradle 조회..."
        ver=$(gradle_latest_stable)
        log_info "→ Gradle ${ver}"
    fi
    log_step "Gradle ${ver} 설치"

    # 이미 설치되어 있고 같은 버전이면 skip.
    if [[ -x "$GRADLE_HOME/bin/gradle" ]]; then
        local existing
        existing=$("$GRADLE_HOME/bin/gradle" --version 2>/dev/null | awk '/^Gradle/ {print $2}' || echo "")
        if [[ "$existing" == "$ver" ]]; then
            log_ok "Gradle ${ver} 이미 설치됨 — $GRADLE_HOME"
            return 0
        fi
        log_info "기존 Gradle ${existing:-unknown} 발견 → ${ver} 로 교체"
        rm -rf "$GRADLE_HOME"
    fi

    local archive="/tmp/gradle-${ver}-bin.zip"
    local url="https://services.gradle.org/distributions/gradle-${ver}-bin.zip"

    log_info "다운로드: $url"
    if ! curl -fsSL --retry 3 -o "$archive" "$url"; then
        log_err "다운로드 실패. 네트워크 또는 버전 확인."
        return 1
    fi

    log_info "압축 풀기: /home/vibe/.local/"
    mkdir -p /home/vibe/.local
    if ! unzip -q "$archive" -d /home/vibe/.local/; then
        log_err "압축 해제 실패"
        rm -f "$archive"
        return 1
    fi
    rm -f "$archive"

    # 디렉토리 표준화: gradle-<ver>/  →  gradle/
    mv "/home/vibe/.local/gradle-${ver}" "$GRADLE_HOME"

    # PATH 의 /home/vibe/.local/bin 에 symlink (이미 PATH 등록되어 있음 — v0.7.0)
    mkdir -p /home/vibe/.local/bin
    ln -sf "$GRADLE_HOME/bin/gradle" /home/vibe/.local/bin/gradle

    # 검증
    if "$GRADLE_HOME/bin/gradle" --version | head -3; then
        log_ok "Gradle ${ver} 설치 완료 — $GRADLE_HOME"
    else
        log_err "설치는 됐으나 실행 실패"
        return 1
    fi
}
