#!/usr/bin/env bash
# Android SDK 설치 — sdkmanager로 platform-tools / platforms / build-tools 받기.
# shellcheck shell=bash source=common.sh

ANDROID_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION:-11076708}_latest.zip"

# Stage 1: cmdline-tools 다운로드 + 설치
install_cmdline_tools() {
    local sdk="${ANDROID_HOME:-/opt/android-sdk}"
    local target="$sdk/cmdline-tools/latest"

    if [[ -d "$target" ]]; then
        log_ok "cmdline-tools 이미 존재 → 스킵 ($target)"
        return 0
    fi

    log_step "Android cmdline-tools 다운로드"
    log_dim "URL: $ANDROID_CMDLINE_TOOLS_URL"

    local tmp
    tmp="$(mktemp -d)"
    trap "rm -rf '$tmp'" RETURN

    log_info "다운로드 중... (약 130MB)"
    if ! curl -fSL --progress-bar "$ANDROID_CMDLINE_TOOLS_URL" -o "$tmp/cmdline-tools.zip"; then
        log_err "다운로드 실패. 네트워크 확인 필요."
        return 1
    fi

    log_info "압축 해제 중..."
    unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted"

    mkdir -p "$sdk/cmdline-tools"
    mv "$tmp/extracted/cmdline-tools" "$target"
    log_ok "설치 완료: $target"
}

# Stage 2: 라이선스 수락
accept_licenses() {
    local sdk="${ANDROID_HOME:-/opt/android-sdk}"
    local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"

    [[ -x "$sdkmanager" ]] || { log_err "sdkmanager 없음 — cmdline-tools 설치 먼저"; return 1; }

    log_step "Android SDK 라이선스 수락"
    log_dim "Google Android SDK 라이선스 약관에 동의합니다 (자동)"

    # `yes` 파이프로 모든 라이선스 자동 수락. 실패 시에도 계속 진행.
    yes 2>/dev/null | "$sdkmanager" --licenses >/dev/null 2>&1 || true
    log_ok "라이선스 처리 완료"
}

# Stage 3: 매니페스트의 패키지 설치
install_packages() {
    local sdk="${ANDROID_HOME:-/opt/android-sdk}"
    local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"

    [[ -x "$sdkmanager" ]] || { log_err "sdkmanager 없음"; return 1; }

    log_step "Android SDK 컴포넌트 설치"

    local packages=(
        "platform-tools"
        "platforms;android-35"
        "build-tools;35.0.0"
    )

    # 매니페스트에서 더 가져오기 (yaml 파싱은 grep으로 단순 처리)
    local manifest="/opt/vibe-doctor/manifest.yml"
    if [[ -f "$manifest" ]]; then
        # `  - "build-tools;34.0.0"` 같은 라인만 추출
        while IFS= read -r pkg; do
            packages+=("$pkg")
        done < <(sed -n '/^android:/,/^[a-z]/p' "$manifest" \
                 | grep -E '^\s+-\s+"[^"]+"' \
                 | sed -E 's/^\s+-\s+"([^"]+)".*/\1/')
    fi

    # 중복 제거
    local unique=()
    declare -A seen=()
    for p in "${packages[@]}"; do
        if [[ -z "${seen[$p]:-}" ]]; then
            unique+=("$p")
            seen[$p]=1
        fi
    done

    for p in "${unique[@]}"; do
        log_info "설치: $p"
        if ! yes 2>/dev/null | "$sdkmanager" "$p" >/dev/null 2>&1; then
            log_warn "  → 일부 실패하지만 계속 진행"
        else
            log_ok "  → 완료"
        fi
    done
}

install_android_sdk_all() {
    install_cmdline_tools || return 1
    accept_licenses
    install_packages
    log_ok "Android SDK 설치 모두 완료"
}

# v1.10.0 — 안드로이드 에뮬레이터 패키지 (manifest.yml 의 `emulator:` 섹션).
# `emulator` 바이너리 + 1개 default system-image. 부피 1GB+ — 사용자 명시 클릭 시만.
install_emulator_packages() {
    local sdk="${ANDROID_HOME:-/opt/android-sdk}"
    local sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"

    [[ -x "$sdkmanager" ]] || { log_err "sdkmanager 없음 — Android SDK 카드 먼저 설치하세요"; return 1; }

    log_step "안드로이드 에뮬레이터 패키지 설치"

    local manifest="/opt/vibe-doctor/manifest.yml"
    local packages=()
    if [[ -f "$manifest" ]]; then
        # `emulator:` 섹션 안의 `  - "..."` 라인 추출.
        while IFS= read -r pkg; do
            packages+=("$pkg")
        done < <(sed -n '/^emulator:/,/^[a-z]/p' "$manifest" \
                 | grep -E '^\s+-\s+"[^"]+"' \
                 | sed -E 's/^\s+-\s+"([^"]+)".*/\1/')
    fi
    if [[ ${#packages[@]} -eq 0 ]]; then
        # manifest 파싱 실패 시 hardcoded fallback.
        packages=("emulator" "system-images;android-35;google_apis;x86_64")
    fi

    for p in "${packages[@]}"; do
        log_info "설치: $p"
        if ! yes 2>/dev/null | "$sdkmanager" "$p" >/dev/null 2>&1; then
            log_warn "  → $p 설치 실패 (계속 진행)"
        else
            log_ok "  → 완료"
        fi
    done
    log_ok "에뮬레이터 패키지 설치 완료. 부팅은 /emulator 페이지에서 진행하세요."
    log_dim "참고: 컨테이너 안 에뮬레이터 부팅은 --device /dev/kvm + privileged 또는 :full 이미지 필요."
}

install_emulator_all() {
    # cmdline-tools / 라이선스 사전 조건 — Android SDK 카드가 먼저 설치돼야 정상.
    install_cmdline_tools || return 1
    accept_licenses
    install_emulator_packages
    log_ok "안드로이드 에뮬레이터 환경 설치 완료"
}
