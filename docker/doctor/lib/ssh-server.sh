#!/usr/bin/env bash
# OpenSSH server installer/configurator for remote container access.
# shellcheck shell=bash source=common.sh

ssh_server_port_file() {
    printf '%s\n' "${VIBECODER_DATA_DIR:-/data}/ssh-server/port"
}

ssh_server_port() {
    local port="${VIBECODER_SSH_PORT:-}"
    if [[ -z "$port" && -f "$(ssh_server_port_file)" ]]; then
        port="$(tr -dc '0-9' <"$(ssh_server_port_file)" | head -c 5)"
    fi
    port="${port:-2222}"
    if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1024 || port > 65535 )); then
        log_warn "잘못된 SSH 포트($port) — 기본값 2222 사용"
        port=2222
    fi
    printf '%s\n' "$port"
}

install_ssh_server() {
    local port
    port="$(ssh_server_port)"

    log_step "OpenSSH 서버 설치/설정"
    if ! command -v sshd >/dev/null 2>&1 && [[ ! -x /usr/sbin/sshd ]]; then
        log_info "openssh-server 설치"
        sudo apt-get update
        sudo DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server
    else
        log_ok "openssh-server 이미 설치됨"
    fi

    mkdir -p "$(dirname "$(ssh_server_port_file)")"
    printf '%s\n' "$port" >"$(ssh_server_port_file)"

    sudo mkdir -p /run/sshd /etc/ssh/sshd_config.d
    sudo tee /etc/ssh/sshd_config.d/vibe-coder.conf >/dev/null <<EOF
Port $port
ListenAddress 0.0.0.0
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
AuthorizedKeysFile .ssh/authorized_keys
AllowUsers vibe
EOF

    # v1.159.0 — authorized_keys 는 빌드환경 SSH 카드가 채운다(내 공개키 등록 / 접속용 키 발급).
    # 예전에 여기서 git 클라이언트 키(id_ed25519.pub)를 자동 복사했지만, 그 개인키는
    # 컨테이너 밖으로 나가지 않아(게다가 push 용) 실제 접속에 못 썼다 → 자동 시드 제거.
    # 여기선 sshd 가 거부하지 않도록 .ssh 700 / authorized_keys 600 만 보장한다.
    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh" 2>/dev/null || true
    [[ -f "$HOME/.ssh/authorized_keys" ]] || : >"$HOME/.ssh/authorized_keys"
    chmod 600 "$HOME/.ssh/authorized_keys" 2>/dev/null || true
    if [[ ! -s "$HOME/.ssh/authorized_keys" ]]; then
        log_warn "authorized_keys 가 비어 있습니다 — 빌드환경 → SSH 서버 카드에서 공개키를 등록하거나 접속용 키를 발급하세요."
    fi

    sudo /usr/sbin/sshd -t
    if pgrep -x sshd >/dev/null 2>&1; then
        sudo pkill -x sshd || true
    fi
    sudo /usr/sbin/sshd
    log_ok "OpenSSH 서버 실행 중 (키 접속 전용): vibe@<host> -p $port"
    log_dim "접속 인증키 등록/발급: 빌드환경 → SSH 서버 카드"
    log_dim "compose 기본 매핑: \${VIBE_SSH_PORT:-$port}:\${VIBECODER_SSH_PORT:-$port}"
}
