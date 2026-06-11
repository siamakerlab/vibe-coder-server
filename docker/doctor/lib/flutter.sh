#!/usr/bin/env bash
# Flutter SDK 설치 (Android 앱 빌드 전용). git stable channel 을
# /home/vibe/.local/flutter 에 clone, /home/vibe/.local/bin/{flutter,dart} 로
# symlink. v0.7.0 의 npm-global bind mount 덕에 영구 보존 (gradle.sh 와 동일 패턴).
#
# ── 운영자 정책: "Android 전용" ──────────────────────────────────────────────
# 이 서버는 Flutter 를 Android APK/AAB 빌드 용도로만 쓴다. 두 단계로 iOS / web /
# desktop 을 차단해 불필요한 artifact(수 GB) 다운로드를 막는다:
#   1) flutter config --no-enable-{ios,web,*-desktop}  → 플랫폼 자체 비활성
#   2) flutter precache --android --no-ios --no-web ... → artifact 선다운로드를
#      Android 로만 한정 (iOS engine artifact 만 1GB+ 이므로 절약 효과가 큼)
# 디스크 절약 검증: 설치 후 `du -sh /home/vibe/.local/flutter` 권장.
#
# shellcheck shell=bash source=common.sh

FLUTTER_HOME="/home/vibe/.local/flutter"
FLUTTER_REPO="https://github.com/flutter/flutter.git"

# manifest.yml 의 `flutter:` 블록에서 channel / version 을 단순 grep 으로 읽는다
# (android-sdk.sh 의 매니페스트 파싱과 같은 방식). 값이 없으면 stable channel HEAD.
flutter_manifest_value() {
    # $1 = key (channel|version)
    local manifest="/opt/vibe-doctor/manifest.yml"
    [[ -f "$manifest" ]] || return 0
    sed -n '/^flutter:/,/^[a-z]/p' "$manifest" \
        | grep -E "^[[:space:]]+$1:" | head -1 \
        | sed -E 's/.*"([^"]*)".*/\1/'
}

install_flutter() {
    # 인자: $1 = channel|tag (없으면 FLUTTER_VERSION env → manifest → stable)
    local ref="${1:-${FLUTTER_VERSION:-}}"
    local channel
    channel="$(flutter_manifest_value channel)"
    [[ -z "$channel" ]] && channel="stable"
    if [[ -z "$ref" ]]; then
        ref="$(flutter_manifest_value version)"   # 비어있으면 channel HEAD
    fi

    log_step "Flutter SDK 설치 (channel=${channel}${ref:+, ref=$ref})"
    log_dim "Android 앱 빌드 전용 — iOS/web/desktop artifact 는 받지 않습니다."

    mkdir -p /home/vibe/.local/bin

    if [[ -x "$FLUTTER_HOME/bin/flutter" ]]; then
        log_info "기존 Flutter 발견 → 채널 갱신 (git fetch/checkout)"
        git config --global --add safe.directory "$FLUTTER_HOME" 2>/dev/null || true
        git -C "$FLUTTER_HOME" fetch --depth 1 origin "$channel" 2>/dev/null || true
        git -C "$FLUTTER_HOME" checkout -q "$channel" 2>/dev/null || true
        git -C "$FLUTTER_HOME" reset --hard "origin/$channel" 2>/dev/null || true
    else
        log_info "git clone (--depth 1, channel=$channel): $FLUTTER_REPO"
        mkdir -p /home/vibe/.local
        if ! git clone --depth 1 -b "$channel" "$FLUTTER_REPO" "$FLUTTER_HOME"; then
            log_err "git clone 실패. 네트워크 또는 channel 이름 확인."
            return 1
        fi
        git config --global --add safe.directory "$FLUTTER_HOME" 2>/dev/null || true
    fi

    # 특정 버전 태그 고정 시 checkout (manifest version 또는 인자).
    if [[ -n "$ref" && "$ref" != "$channel" ]]; then
        log_info "버전 고정 시도: $ref"
        git -C "$FLUTTER_HOME" fetch --depth 1 origin "refs/tags/$ref:refs/tags/$ref" 2>/dev/null || true
        git -C "$FLUTTER_HOME" checkout -q "$ref" 2>/dev/null \
            || log_warn "태그 $ref checkout 실패 → channel '$channel' HEAD 사용"
    fi

    # PATH 의 /home/vibe/.local/bin 에 symlink (이미 PATH 등록됨 — Dockerfile ENV).
    ln -sf "$FLUTTER_HOME/bin/flutter" /home/vibe/.local/bin/flutter
    ln -sf "$FLUTTER_HOME/bin/dart" /home/vibe/.local/bin/dart

    # 비대화형 환경 — analytics/텔레메트리 끔. (한 턴=one-shot, stdin 없음)
    "$FLUTTER_HOME/bin/flutter" config --no-analytics >/dev/null 2>&1 || true
    "$FLUTTER_HOME/bin/flutter" --disable-analytics  >/dev/null 2>&1 || true

    # ── (1) 플랫폼 차단: Android 만 enable, 나머지 disable ────────────────────
    log_info "플랫폼 활성화: Android 만 (iOS/web/desktop 비활성)"
    "$FLUTTER_HOME/bin/flutter" config \
        --enable-android \
        --no-enable-ios \
        --no-enable-web \
        --no-enable-linux-desktop \
        --no-enable-macos-desktop \
        --no-enable-windows-desktop \
        >/dev/null 2>&1 || log_warn "flutter config 일부 플래그 미지원 (버전차) — 무시"

    # ── (2) precache: Android artifact 만 선다운로드 ─────────────────────────
    log_step "Flutter precache (Android only)"
    if ! "$FLUTTER_HOME/bin/flutter" precache \
            --android --no-ios --no-web --no-linux --no-windows --no-macos --no-fuchsia; then
        log_warn "precache 일부 실패 — 첫 빌드 시 자동 재시도됨"
    fi

    # Android SDK 연결 (있을 때만 — Flutter 가 sdkmanager/license 를 찾도록).
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}" ]]; then
        log_info "Android SDK 연결: $ANDROID_HOME"
        "$FLUTTER_HOME/bin/flutter" config --android-sdk "$ANDROID_HOME" >/dev/null 2>&1 || true
    else
        log_warn "ANDROID_HOME 미설정 — 'vibe-doctor android' 로 Android SDK 를 먼저 설치하세요."
    fi

    # 검증
    if "$FLUTTER_HOME/bin/flutter" --version 2>/dev/null | head -3; then
        log_ok "Flutter 설치 완료 — $FLUTTER_HOME"
        log_dim "디스크 사용량 확인: du -sh $FLUTTER_HOME"
    else
        log_err "설치는 됐으나 flutter --version 실행 실패"
        return 1
    fi
}
