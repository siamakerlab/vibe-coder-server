# syntax=docker/dockerfile:1.7
#
# Vibe Coder Server — 슬림 런타임 이미지
#
# 설계 의도
#   - 이미지 크기 최소화를 위해 Android SDK / Gradle 캐시 / Claude 인증은 이미지에 박지 않는다.
#   - 위 무거운 컴포넌트는 사용자가 컨테이너 부팅 후 `vibe-doctor`로 볼륨에 다운로드.
#   - 이미지에는 "vibe-coder 서버 본체 + Claude Code CLI + 빌드환경 셋업 도구"만 포함한다.
#
# 빌드 (메인테이너용)
#
#   일반 commit push (amd64-only · v0.6.3+ 기본):
#     docker buildx build --platform linux/amd64 \
#         -f docker/Dockerfile -t siamakerlab/vibe-coder-server:1.137.2 --push .
#
#   마일스톤 multi-arch push (v0.7.0 / v1.0.0 등):
#     docker buildx build --platform linux/amd64,linux/arm64 \
#         -f docker/Dockerfile -t siamakerlab/vibe-coder-server:1.137.2 --push .

# ------------------------------------------------------------------------------
# Stage 1: 서버 빌드 (Gradle installDist)
# v0.38.0 — Ubuntu 26.04 LTS (Resolute Raccoon) rebase. eclipse-temurin 의
# resolute 태그가 26.04 LTS 매핑 확정 (`/etc/os-release`: Ubuntu 26.04 LTS).
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-resolute AS builder

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
# Stage 2: 런타임 — v0.38.0 Ubuntu 26.04 LTS rebase, v0.68.1 JDK 로 변경.
#
# v0.68.1 hotfix: Android 디버그 빌드 시 Gradle Java toolchain 이
# `/opt/java/openjdk` 를 잡으면서 JRE-only 라 javac 가 없어 컴파일 실패
# (`Toolchain installation '/opt/java/openjdk' does not provide the required
# capabilities: [JAVA_COMPILER]`). runtime 도 JDK 로 통일 (~130MB 증가) —
# 단일 사용자 LAN 도구라 이미지 크기보다 빌드 안정성 우선.
# 빌더 stage 는 이미 jdk-resolute 라 추가 다운로드 없음.
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-resolute AS runtime

LABEL org.opencontainers.image.title="Vibe Coder Server"
LABEL org.opencontainers.image.description="Ktor 기반 vibe-coder 서버 (LAN 1인 개발 도구) · Ubuntu 26.04 LTS"
LABEL org.opencontainers.image.source="https://github.com/siamakerlab/vibe-coder"
LABEL org.opencontainers.image.licenses="AGPL-3.0-or-later"
LABEL org.opencontainers.image.source="https://github.com/siamakerlab/vibe-coder-server"

# ---- 시스템 패키지 (도구 최소셋) ---------------------------------------------
# Android SDK / Playwright Chromium / 무거운 MCP들은 doctor가 볼륨에 설치한다.
# v1.73.0 — 헤드리스 안드로이드 에뮬레이터(swiftshader_indirect) 런타임 라이브러리도 포함
# (emulator/qemu 가 참조하는 그래픽·오디오·암호 라이브러리). system-image 자체는 볼륨(doctor).
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        gosu \
        expect \
        gnupg \
        jq \
        openssh-client \
        openssl \
        sudo \
        tini \
        tzdata \
        unzip \
        util-linux \
        wget \
        xz-utils \
        zip \
        libgl1 \
        libglx-mesa0 \
        libgl1-mesa-dri \
        libegl1 \
        libpulse0 \
        libnss3 \
        libx11-6 \
        libxcb1 \
        libxcursor1 \
        libxrender1 \
        libxrandr2 \
        libuuid1 \
        libexpat1 \
    && rm -rf /var/lib/apt/lists/*
# util-linux 는 `script` 명령(pty wrapping) 을 제공 — v0.7.0 옵션 A (Claude
# 반자동 웹 OAuth) 가 `claude auth login` TUI 를 spawn 할 때 필수.

# ---- 이미지 처리 도구 (v1.92.0) ----------------------------------------------
# Claude Code 가 스크린샷 / 디자인 mockup / 아이콘 / APK 리소스 등을 다룰 때
# 바로 쓸 수 있도록 기본 image 도구를 박는다. vibe 유저는 NOPASSWD sudo 로
# 추가 패키지(`sudo apt-get install ...`)를 언제든 설치할 수 있으므로, 여기엔
# "거의 항상 쓰는 핵심셋"만 둔다. ffmpeg / inkscape 등 무거운 도구는 필요 시
# 사용자가 apt 로 설치.
#   - imagemagick      : convert / mogrify / identify (래스터 변환·리사이즈·합성)
#   - python3 + pillow : PIL 기반 스크립트 (python3-pil = Pillow, PEP 668 때문에
#                        pip 대신 apt 패키지 사용). numpy 동반 설치.
#   - librsvg2-bin     : rsvg-convert (SVG → PNG, Android 벡터 아이콘 워크플로)
#   - webp             : cwebp / dwebp (WebP 인·디코딩)
#   - poppler-utils    : pdftoppm / pdfinfo (PDF → 이미지)
#   - ghostscript      : ImageMagick 의 PDF/PostScript delegate
#   - optipng/pngquant/jpegoptim : 무손실·유손실 최적화
RUN apt-get update && apt-get install -y --no-install-recommends \
        imagemagick \
        python3 \
        python3-pip \
        python3-pil \
        python3-numpy \
        librsvg2-bin \
        webp \
        poppler-utils \
        ghostscript \
        optipng \
        pngquant \
        jpegoptim \
    && rm -rf /var/lib/apt/lists/*
# 일부 배포판의 ImageMagick policy.xml 은 Ghostscript CVE-2016-3714 완화를 위해
# PDF/PS/EPS coder 를 막아둔다(`convert x.pdf y.png` → "not authorized" 에러).
# 현재 resolute(IM7 7.1.2) 기본 정책엔 해당 차단이 없어 사실상 no-op 이지만,
# 베이스 이미지 교체/IM6 회귀 시에도 PDF/PS 변환이 보장되도록 방어적으로 정책
# 줄을 제거한다. 본 컨테이너는 1인 신뢰 LAN 도구이고 입력은 사용자 본인 자산뿐.
RUN set -eux; \
    for p in /etc/ImageMagick-6/policy.xml /etc/ImageMagick-7/policy.xml; do \
        [ -f "$p" ] && sed -i -E '/<policy[^>]*pattern="(PDF|PS|PS2|PS3|EPS|XPS)"/d' "$p" || true; \
    done

# ---- Node.js 20 LTS + Claude Code CLI ----------------------------------------
# Node는 Claude CLI와 MCP 실행에 필요하므로 이미지에 포함 (전체 ~150MB).
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && npm config set fund false \
    && npm config set update-notifier false \
    && npm install -g @anthropic-ai/claude-code \
    && npm cache clean --force

# ---- 공식 Gitea MCP 서버 바이너리 (Go 정적 링크) -----------------------------
# v1.68.0 — gitea.com/gitea/gitea-mcp. npm 패키지가 아니므로 릴리스 바이너리를
# 이미지에 박는다. /usr/local/bin 은 볼륨 마운트 밖이라 이미지 업그레이드/재시작
# 후에도 유지된다(.local/bin 은 npm prefix 볼륨에 가려질 수 있어 사용 안 함).
# 정적 링크라 런타임 의존성 없음. 실행: `gitea-mcp -t stdio`
# (env GITEA_HOST / GITEA_ACCESS_TOKEN). TARGETARCH 는 buildx 가 주입(amd64/arm64)
# — multi-arch 빌드에서 올바른 자산 선택.
ARG TARGETARCH
ARG GITEA_MCP_VERSION=v1.0.1
RUN set -eux; \
    case "${TARGETARCH:-amd64}" in \
        amd64) GMARCH="x86_64" ;; \
        arm64) GMARCH="arm64" ;; \
        *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/gitea-mcp.tar.gz \
        "https://gitea.com/gitea/gitea-mcp/releases/download/${GITEA_MCP_VERSION}/gitea-mcp_Linux_${GMARCH}.tar.gz"; \
    tar -xzf /tmp/gitea-mcp.tar.gz -C /usr/local/bin gitea-mcp; \
    chmod +x /usr/local/bin/gitea-mcp; \
    rm -f /tmp/gitea-mcp.tar.gz; \
    /usr/local/bin/gitea-mcp -version

# ---- 사용자 / 디렉토리 -------------------------------------------------------
# entrypoint에서 호스트 UID에 맞춰 재조정한다. 여기는 기본값.
ARG VIBE_UID=1000
ARG VIBE_GID=1000
# Ubuntu 24.04 (noble) base 부터 default `ubuntu` 사용자가 UID 1000 으로
# 사전 생성되어 있어 충돌. 먼저 제거 후 vibe 생성. (조용히 실패 허용 — 다른
# base 에선 ubuntu 사용자가 없을 수 있음)
RUN userdel -r ubuntu 2>/dev/null || true \
    && groupdel ubuntu 2>/dev/null || true \
    && groupadd --gid ${VIBE_GID} vibe \
    && useradd --uid ${VIBE_UID} --gid ${VIBE_GID} --create-home --shell /bin/bash vibe \
    && mkdir -p \
        /opt/android-sdk \
        /workspace \
        /data \
        /home/vibe/.claude \
        /home/vibe/.gradle \
        /home/vibe/.config \
        /home/vibe/.npm \
        /home/vibe/.cache \
        /home/vibe/.cache/ms-playwright \
        /home/vibe/.local/bin \
        /home/vibe/.local/lib/node_modules \
    && printf 'prefix=/home/vibe/.local\nfund=false\nupdate-notifier=false\n' > /home/vibe/.npmrc \
    && chown -R vibe:vibe /opt/android-sdk /workspace /data /home/vibe \
    && printf 'vibe ALL=(ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/vibe-nopasswd \
    && chmod 0440 /etc/sudoers.d/vibe-nopasswd \
    && visudo -c -f /etc/sudoers.d/vibe-nopasswd

# v0.7.0 — vibe 사용자 NOPASSWD sudo.
# 근거: 컨테이너는 1인 LAN 격리 도구이며 도커 레벨에서 외부 신뢰 경계가 분리됨.
# Claude Code 가 시스템 패키지 설치 / 권한 정리 등을 위해 sudo 가 필요한 경우
# 비밀번호 prompt 가 vibe-coder 스트림에서 hang 을 일으키므로 NOPASSWD 로 우회.
# CLAUDE.md §3 의 "raw shell UI 금지" 와 별개 — Claude Code 가 자체 정책으로
# 어떤 sudo 명령을 쓸지 결정하며, 사용자가 직접 shell 을 쓰는 경로는 없음.

# ---- vibe-coder 서버 본체 ---------------------------------------------------
COPY --from=builder --chown=vibe:vibe /src/server/build/install/server /opt/vibe-coder

# ---- doctor / entrypoint -----------------------------------------------------
COPY --chown=root:root docker/doctor /opt/vibe-doctor
COPY --chown=root:root docker/entrypoint.sh /usr/local/bin/entrypoint.sh
# v1.4.0 — Claude TUI Usage 탭 PTY capture (expect 스크립트). slash command 가
# --print 에서 차단되어 quota 를 다른 방식으로 가져올 수 없음.
COPY --chown=root:root docker/scripts/claude-usage-capture.exp /usr/local/bin/claude-usage-capture.exp
RUN chmod +x /opt/vibe-doctor/vibe-doctor \
    && chmod +x /opt/vibe-doctor/lib/*.sh \
    && chmod +x /usr/local/bin/entrypoint.sh \
    && chmod +x /usr/local/bin/claude-usage-capture.exp \
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

# v1.9.0 — Git global config 를 영속 위치 (`/home/vibe/.config/git/config`) 로 강제.
# `/home/vibe/.config` 는 이미 `dev-tools/config` 볼륨에 영속화돼 있어 별도 mount
# 추가 없이 컨테이너 재시작 / 이미지 업그레이드 후에도 사용자가 입력한
# `user.name` / `user.email` 이 유지된다. git 2.32+ 의 GIT_CONFIG_GLOBAL 사용.
# (자식 git 프로세스 — clone / commit / log 모두 동일 파일을 global config 로 인식)
ENV GIT_CONFIG_GLOBAL=/home/vibe/.config/git/config

# v0.7.0 — vibe 사용자의 npm 글로벌 prefix 를 /home/vibe/.local 로 분리.
# 이로써 `vibe-doctor mcp` 의 `npm install -g <pkg>` 가 시스템 디렉토리가 아닌
# 영구 볼륨에 저장되어, 이미지 업그레이드 후에도 MCP 가 사라지지 않는다.
# /home/vibe/.local/bin 을 PATH 에 추가 (Claude CLI 가 MCP 명령을 찾도록).
ENV PATH=/home/vibe/.local/bin:${PATH}

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
