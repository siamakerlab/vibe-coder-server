# syntax=docker/dockerfile:1.7
#
# Vibe Coder Server — 슬림 런타임 이미지
#
# 설계 의도
#   - 이미지 크기 최소화를 위해 Android SDK / Gradle 캐시 / Claude 인증은 이미지에 박지 않는다.
#   - 위 무거운 컴포넌트는 사용자가 컨테이너 부팅 후 `vibe-doctor`로 볼륨에 다운로드.
#   - 이미지에는 "vibe-coder 서버 본체 + Claude Code CLI + 빌드환경 셋업 도구"만 포함한다.
#
# 빌드
#   docker build -f docker/Dockerfile -t siamakerlab/vibe-coder:0.3.0 .
#
# 멀티 아키텍처 (선택)
#   docker buildx build --platform linux/amd64,linux/arm64 \
#       -f docker/Dockerfile -t siamakerlab/vibe-coder:0.3.0 --push .

# ------------------------------------------------------------------------------
# Stage 1: 서버 빌드 (Gradle installDist)
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /src

# Gradle wrapper + 빌드 스크립트 복사. Android 모듈은 -PskipAndroidModule=true 로 제외.
COPY gradle ./gradle
COPY gradlew ./gradlew
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY shared/build.gradle.kts ./shared/
COPY server/build.gradle.kts ./server/

# 소스 복사
COPY shared ./shared
COPY server ./server

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/.gradle \
    chmod +x ./gradlew && \
    ./gradlew -PskipAndroidModule=true :server:installDist --no-daemon -x test

# ------------------------------------------------------------------------------
# Stage 2: 런타임 (슬림)
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL org.opencontainers.image.title="Vibe Coder Server"
LABEL org.opencontainers.image.description="Ktor 기반 vibe-coder 서버 (LAN 페어링 1인 개발 도구)"
LABEL org.opencontainers.image.source="https://github.com/siamakerlab/vibe-coder"
LABEL org.opencontainers.image.licenses="UNLICENSED"

# ---- 시스템 패키지 (도구 최소셋) ---------------------------------------------
# Android SDK / Playwright Chromium / 무거운 MCP들은 doctor가 볼륨에 설치한다.
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        gosu \
        gnupg \
        jq \
        openssl \
        sudo \
        tini \
        tzdata \
        unzip \
        wget \
        xz-utils \
        zip \
    && rm -rf /var/lib/apt/lists/*

# ---- Node.js 20 LTS + Claude Code CLI ----------------------------------------
# Node는 Claude CLI와 MCP 실행에 필요하므로 이미지에 포함 (전체 ~150MB).
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm config set fund false \
    && npm config set update-notifier false \
    && npm install -g @anthropic-ai/claude-code \
    && npm cache clean --force

# ---- 사용자 / 디렉토리 -------------------------------------------------------
# entrypoint에서 호스트 UID에 맞춰 재조정한다. 여기는 기본값.
ARG VIBE_UID=1000
ARG VIBE_GID=1000
RUN groupadd --gid ${VIBE_GID} vibe \
    && useradd --uid ${VIBE_UID} --gid ${VIBE_GID} --create-home --shell /bin/bash vibe \
    && mkdir -p \
        /opt/android-sdk \
        /workspace \
        /data \
        /home/vibe/.claude \
        /home/vibe/.gradle \
        /home/vibe/.config \
    && chown -R vibe:vibe /opt/android-sdk /workspace /data /home/vibe

# ---- vibe-coder 서버 본체 ---------------------------------------------------
COPY --from=builder --chown=vibe:vibe /src/server/build/install/server /opt/vibe-coder

# ---- doctor / entrypoint -----------------------------------------------------
COPY --chown=root:root docker/doctor /opt/vibe-doctor
COPY --chown=root:root docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /opt/vibe-doctor/vibe-doctor \
    && chmod +x /opt/vibe-doctor/lib/*.sh \
    && chmod +x /usr/local/bin/entrypoint.sh \
    && ln -s /opt/vibe-doctor/vibe-doctor /usr/local/bin/vibe-doctor \
    && ln -s /opt/vibe-coder/bin/server /usr/local/bin/vibe-coder-server

# ---- 런타임 환경변수 ---------------------------------------------------------
# Android SDK 경로(볼륨)
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

# vibe-coder 서버 설정 경로
ENV VIBECODER_WORKSPACE_ROOT=/workspace
ENV VIBECODER_DATA_DIR=/data

# Claude CLI 인증 경로 (볼륨)
ENV CLAUDE_CONFIG_DIR=/home/vibe/.claude

# JVM 옵션 (사용자가 .env로 덮어쓰기 가능)
ENV JAVA_OPTS="-Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8"

# ---- 포트 / 헬스체크 ---------------------------------------------------------
EXPOSE 17880

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -fsS http://127.0.0.1:17880/health || exit 1

# ---- 실행 -------------------------------------------------------------------
# tini로 PID 1 시그널 처리, entrypoint가 UID 매칭 후 gosu로 vibe 사용자로 전환.
WORKDIR /workspace
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]
CMD ["server"]
