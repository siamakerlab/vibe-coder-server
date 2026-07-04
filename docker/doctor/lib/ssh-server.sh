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

    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh" 2>/dev/null || true
    if [[ ! -s "$HOME/.ssh/authorized_keys" && -s "$HOME/.ssh/id_ed25519.pub" ]]; then
        cp "$HOME/.ssh/id_ed25519.pub" "$HOME/.ssh/authorized_keys"
        chmod 600 "$HOME/.ssh/authorized_keys" 2>/dev/null || true
        log_info "authorized_keys 가 없어 현재 id_ed25519.pub 를 등록했습니다."
    fi

    sudo /usr/sbin/sshd -t
    if pgrep -x sshd >/dev/null 2>&1; then
        sudo pkill -x sshd || true
    fi
    sudo /usr/sbin/sshd
    log_ok "OpenSSH 서버 실행 중: vibe@<host> -p $port"
    log_dim "compose 기본 매핑: \${VIBE_SSH_PORT:-$port}:\${VIBECODER_SSH_PORT:-$port}"
}
