package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.git.GitCredentialStore

/**
 * 환경설정 > Git 통합 페이지 SSR — v0.9.0.
 *
 * 두 섹션:
 *  1. SSH 키 — 컨테이너의 vibe 사용자 공개키 표시. 사용자가 GitHub/GitLab/Gitea 등에
 *     "Deploy key" 또는 "SSH key" 로 등록 → SSH URL clone 가능.
 *  2. PAT (HTTPS) — provider 별 토큰 등록 폼 + 현재 등록된 토큰 목록 (마스킹).
 */
object GitIntegrationsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        tokens: List<GitCredentialStore.TokenView>,
        sshPubKey: String?,
        flash: String?,
    ): String {
        val sshBlock = if (sshPubKey == null) """
<div style="background:rgba(255,150,80,0.08);padding:10px;border-radius:6px;border:1px solid var(--warn)">
  <p style="margin:0 0 8px">SSH 키가 아직 생성되지 않았습니다.</p>
  <form method="post" action="/settings/git-integrations/ssh-keygen"
        onsubmit="return confirm('vibe 사용자의 ~/.ssh/id_ed25519 키 페어를 자동 생성합니다. 계속할까요?')">
    <button type="submit" class="primary" style="padding:8px 14px">SSH 키 자동 생성</button>
  </form>
  <p class="hint" style="font-size:12px;margin-top:8px">ed25519 (현대 표준, RSA 4096 보다 짧고 안전).
  생성 후 공개키가 표시됩니다.</p>
</div>
""" else """
<p style="margin:0 0 6px"><strong>vibe 사용자 공개키</strong> — 아래 한 줄을 복사해 GitHub/GitLab/Gitea/Bitbucket 의
"SSH keys" 또는 레포의 "Deploy key" 로 등록하세요.</p>
<div style="display:flex;gap:8px;align-items:flex-start;margin-top:6px">
  <pre id="ssh-pub" class="diff-block" style="flex:1;margin:0;font-size:11px;overflow-x:auto;white-space:pre-wrap;word-break:break-all">${esc(sshPubKey)}</pre>
  <button type="button" class="chip" style="flex-shrink:0;padding:6px 12px"
          onclick="navigator.clipboard.writeText(document.getElementById('ssh-pub').textContent.trim()).then(()=>{this.textContent='✓ 복사됨';setTimeout(()=>this.textContent='복사',2000)})">복사</button>
</div>
<p class="hint" style="font-size:12px;margin-top:8px">
  등록 가이드 →
  <a href="https://github.com/settings/keys" target="_blank" rel="noreferrer">GitHub</a> ·
  <a href="https://gitlab.com/-/user_settings/ssh_keys" target="_blank" rel="noreferrer">GitLab</a> ·
  Gitea (User Settings > SSH/GPG Keys) ·
  Bitbucket (Personal settings > SSH keys)
</p>
"""

        val tokenRows = if (tokens.isEmpty())
            """<tr><td colspan="6" class="dim" style="text-align:center;padding:14px">아직 등록된 토큰이 없습니다.</td></tr>"""
        else tokens.joinToString("\n") { t ->
            """<tr>
  <td>${esc(t.provider)}</td>
  <td><code>${esc(t.host)}</code></td>
  <td><code>${esc(t.username)}</code></td>
  <td><code style="font-family:ui-monospace,Menlo,monospace">${esc(t.tokenMasked)}</code></td>
  <td class="dim" style="font-size:11px">${esc(t.createdAt)}</td>
  <td>
    <form method="post" action="/settings/git-integrations/delete" style="display:inline"
          onsubmit="return confirm('${esc(t.host)} 의 토큰을 삭제합니다. 계속할까요?')">
      <input type="hidden" name="host" value="${esc(t.host)}">
      <button type="submit" style="padding:4px 10px;font-size:11px">삭제</button>
    </form>
  </td>
</tr>"""
        }

        return AdminTemplates.shell(
            title = "Git 통합",
            username = username,
            currentPath = "/settings/git-integrations",
            body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">Git 통합 <small class="dim" style="font-size:14px;font-weight:400">v0.9.0</small></h1>
    <a href="/settings" class="chip chip-link">← 설정</a>
  </div>
  <p class="dim" style="margin:6px 0 0;font-size:13px">
    Private 레포 clone 을 위한 토큰/SSH 키 관리. 등록된 토큰은 git CLI 가
    자동으로 사용 (별도 인자 불요).
  </p>
</header>

${flashBlurb(flash)}

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">SSH 키 (private SSH URL clone 용)</h2>
  $sshBlock
</div>

<div class="card">
  <h2 style="margin-top:0">Personal Access Token (private HTTPS URL clone 용)</h2>

  <table style="width:100%;border-collapse:collapse;margin-bottom:14px">
    <thead>
      <tr style="border-bottom:1px solid #333">
        <th style="text-align:left;padding:8px">Provider</th>
        <th style="text-align:left;padding:8px">Host</th>
        <th style="text-align:left;padding:8px">Username</th>
        <th style="text-align:left;padding:8px">Token (masked)</th>
        <th style="text-align:left;padding:8px">등록 시각</th>
        <th style="padding:8px"></th>
      </tr>
    </thead>
    <tbody>
      $tokenRows
    </tbody>
  </table>

  <details ${if (tokens.isEmpty()) "open" else ""}>
    <summary style="cursor:pointer;font-size:13px"><strong>+ 새 토큰 등록</strong></summary>
    <form method="post" action="/settings/git-integrations" style="margin-top:10px;display:grid;grid-template-columns:1fr 1fr;gap:8px">
      <label>Provider
        <select name="provider" required style="width:100%;padding:6px">
          <option value="github">GitHub</option>
          <option value="gitlab">GitLab</option>
          <option value="gitea">Gitea / Forgejo</option>
          <option value="bitbucket">Bitbucket</option>
          <option value="generic">기타 (generic)</option>
        </select>
      </label>
      <label>Host (도메인만)
        <input name="host" required placeholder="github.com 또는 gitea.example.com">
      </label>
      <label style="grid-column:1 / -1">Personal Access Token
        <input type="password" name="token" required minlength="10"
               placeholder="ghp_... / glpat-... / 등" autocomplete="off">
      </label>
      <label>Username (선택, 기본값은 provider 별 표준)
        <input name="username" placeholder="x-access-token / oauth2 / your-username">
      </label>
      <label>Note (선택)
        <input name="note" placeholder="예: production read-only">
      </label>
      <div style="grid-column:1 / -1;display:flex;justify-content:flex-end">
        <button type="submit" class="primary" style="padding:8px 18px">등록</button>
      </div>
    </form>

    <details style="margin-top:14px">
      <summary class="dim" style="cursor:pointer;font-size:12px">토큰 발급 가이드</summary>
      <ul style="margin-top:6px;font-size:12px;line-height:1.7">
        <li><strong>GitHub</strong>: Settings > Developer settings > Personal access tokens — fine-grained 권장. Repository access: Only select repositories. Permissions > Repository > Contents: Read and write.</li>
        <li><strong>GitLab</strong>: User Settings > Access Tokens. Scope: <code>read_repository</code> + <code>write_repository</code> (write 는 push 시만).</li>
        <li><strong>Gitea</strong>: User Settings > Applications > Generate New Token. write:repository.</li>
        <li><strong>Bitbucket</strong>: Personal settings > App passwords. Repository read/write.</li>
      </ul>
    </details>
  </details>
</div>
"""
        )
    }

    private fun flashBlurb(code: String?): String = when (code) {
        "registered" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
          <p style="margin:0;color:var(--ok)">✓ 토큰이 등록되었습니다. 같은 host 에 대해 git clone HTTPS 호출 시 자동 사용됩니다.</p>
        </div>"""
        "deleted" -> """<div class="card" style="margin-bottom:12px;background:rgba(160,160,160,0.06)">
          <p style="margin:0" class="dim">토큰이 삭제되었습니다.</p>
        </div>"""
        "not-found" -> """<div class="card" style="margin-bottom:12px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
          <p style="margin:0;color:var(--warn)">⚠ 해당 host 의 토큰을 찾을 수 없습니다.</p>
        </div>"""
        "ssh-generated" -> """<div class="card" style="margin-bottom:12px;background:rgba(105,219,124,0.06);border-color:var(--ok)">
          <p style="margin:0;color:var(--ok)">✓ SSH 키가 생성되었습니다. 아래 공개키를 GitHub/GitLab 등에 등록하세요.</p>
        </div>"""
        else -> ""
    }
}
