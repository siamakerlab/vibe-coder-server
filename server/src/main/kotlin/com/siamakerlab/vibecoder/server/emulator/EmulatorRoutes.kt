package com.siamakerlab.vibecoder.server.emulator

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val log = KotlinLogging.logger {}

/**
 * v0.19.0 — `/emulator` 진단 페이지. v0.24.0 — AVD lifecycle (launch / stop /
 * create-default) POST endpoints 추가.
 *
 * 본 cycle (v0.24.0) 에선 슬림 이미지에서 launch 가 자동으로 software 모드로
 * 떨어져 매우 느릴 수 있음. KVM passthrough + Xvfb 가상 디스플레이는 `:full`
 * 이미지 variant 에서만 실용적이며, 본 페이지의 안내가 그 사용을 가이드.
 */
fun Routing.emulatorRoutes(authDeps: AdminRoutesDeps, svc: EmulatorService) {
    get("/emulator") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val d = svc.diagnose()
        val ok = call.request.queryParameters["ok"]
        val err = call.request.queryParameters["err"]
        call.respondText(EmulatorTemplates.page(sess.username, d, ok, err, sess.csrf, lang = sess.language), ContentType.Text.Html)
    }

    /** v0.24.0 — 디폴트 AVD 자동 생성 (vibe-default). 한 번만 호출하면 됨. */
    post("/emulator/avd/create-default") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val r = svc.createDefaultAvd()
        log.info { "create-default AVD by ${sess.username}: ok=${r.ok} msg=${r.message.take(120)}" }
        authDeps.audit.emulatorAvdCreate(sess.userId, call.request.origin.remoteHost, r.ok, "vibe-default")
        val q = if (r.ok) "ok=${enc("AVD 'vibe-default' 생성됨")}" else "err=${enc(r.message.take(200))}"
        call.respondRedirect("/emulator?$q")
    }

    /** v0.24.0 — AVD 백그라운드 launch. headless (no-window). */
    post("/emulator/avd/launch") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val form = call.receiveParameters()
        val name = form["name"]?.trim().orEmpty().ifBlank { "vibe-default" }
        val r = svc.launchAvd(name)
        log.info { "launch AVD '$name' by ${sess.username}: ok=${r.ok} msg=${r.message.take(120)}" }
        authDeps.audit.emulatorAvdLaunch(sess.userId, call.request.origin.remoteHost, r.ok, name)
        val q = if (r.ok) "ok=${enc(r.message)}" else "err=${enc(r.message.take(200))}"
        call.respondRedirect("/emulator?$q")
    }

    /** v0.24.0 — 실행 중인 emulator 종료. deviceSerial (예: emulator-5554) 인자. */
    post("/emulator/avd/stop") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        requireCsrf()
        val form = call.receiveParameters()
        val serial = form["serial"]?.trim().orEmpty()
        if (serial.isBlank()) {
            call.respondRedirect("/emulator?err=${enc("device serial 누락")}")
            return@post
        }
        val r = svc.stopAvd(serial)
        log.info { "stop AVD '$serial' by ${sess.username}: ok=${r.ok}" }
        authDeps.audit.emulatorAvdStop(sess.userId, call.request.origin.remoteHost, r.ok, serial)
        val q = if (r.ok) "ok=${enc("emulator '$serial' 종료")}" else "err=${enc(r.message.take(200))}"
        call.respondRedirect("/emulator?$q")
    }
}

private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

private object EmulatorTemplates {

    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        d: EmulatorService.Diagnostics,
        flashOk: String? = null,
        flashErr: String? = null,
        csrf: String?,
        /** v1.23.1 — 한국어 설정 상태에서 nav 가 영어로 보이던 회귀. shell 에 명시 전달. */
        lang: String,
    ): String {
        val badge = { ok: Boolean, label: String ->
            if (ok) """<span class="ok">✓ $label</span>"""
            else """<span class="warn">✗ $label</span>"""
        }
        val avdsHtml = if (d.avds.isEmpty())
            """<p class="dim">(설치된 AVD 없음 — <code>avdmanager create avd</code> 로 생성)</p>"""
        else d.avds.joinToString("") { """<li><code>${esc(it)}</code></li>""" }
            .let { """<ul style="margin:6px 0 0 20px">$it</ul>""" }

        val devicesHtml = if (d.runningDevices.isEmpty())
            """<p class="dim">(실행 중인 device/emulator 없음)</p>"""
        else d.runningDevices.joinToString("") { """<li><code>${esc(it)}</code></li>""" }
            .let { """<ul style="margin:6px 0 0 20px">$it</ul>""" }

        return AdminTemplates.shell(
            title = "Android Emulator",
            username = username,
            currentPath = "/emulator",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1>Android Emulator <small class="dim" style="font-size:14px;font-weight:400">v0.19.0 — 진단 + 가이드</small></h1>
  <p class="dim" style="font-size:13px;margin:6px 0 0">
    실 emulator 자동 launch + noVNC 미러는 v0.20+ 에서 별도 이미지 variant
    (<code>siamakerlab/vibe-coder-server:full</code>) 로 제공 예정. 본 cycle 은
    진단 + 수동 setup 가이드 + ADB 통합.
  </p>
</header>

${if (flashOk != null) """<div class="ok-banner">${esc(flashOk)}</div>""" else ""}
${if (flashErr != null) """<div class="error">${esc(flashErr)}</div>""" else ""}

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">컨테이너 환경 진단</h2>
  <dl style="display:grid;grid-template-columns:max-content 1fr;gap:6px 14px;margin:0">
    <dt class="dim">KVM (/dev/kvm)</dt><dd>${badge(d.kvmAvailable, if (d.kvmAvailable) "사용 가능" else "사용 불가")}</dd>
    <dt class="dim">emulator binary</dt><dd>${if (d.emulatorBinary != null) """<code>${esc(d.emulatorBinary)}</code>""" else """<span class="warn">없음 — sdkmanager 'emulator'</span>"""}</dd>
    <dt class="dim">adb binary</dt><dd>${if (d.adbBinary != null) """<code>${esc(d.adbBinary)}</code>""" else """<span class="warn">없음</span>"""}</dd>
    <dt class="dim">설치된 AVD</dt><dd>$avdsHtml</dd>
    <dt class="dim">실행 중인 device</dt><dd>$devicesHtml</dd>
  </dl>
</div>

<div class="card" style="margin-bottom:14px;background:rgba(255,150,80,0.06);border-color:var(--warn)">
  <h2 style="margin-top:0">권장 사항</h2>
  <pre class="diff-block" style="margin:0">${esc(d.recommendation)}</pre>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">AVD lifecycle (v0.24.0)</h2>
  <p class="hint">슬림 이미지에서도 호출 가능하지만 KVM 없으면 software 모드라 매우 느립니다. <code>siamakerlab/vibe-coder-server:full</code> + compose KVM passthrough 권장.</p>

  <div style="display:flex;gap:12px;flex-wrap:wrap;margin-top:12px">
    <form method="post" action="/emulator/avd/create-default" style="display:inline">
      ${com.siamakerlab.vibecoder.server.auth.CsrfTokens.hiddenInput(csrf)}
      <button type="submit" class="chip chip-link" title="vibe-default AVD 자동 생성 (system-image 필요)">+ 디폴트 AVD 생성</button>
    </form>

    <form method="post" action="/emulator/avd/launch" style="display:inline;display:flex;gap:6px;align-items:center">
      ${com.siamakerlab.vibecoder.server.auth.CsrfTokens.hiddenInput(csrf)}
      <input name="name" placeholder="AVD 이름 (비우면 vibe-default)" style="padding:6px 10px">
      <button type="submit" class="primary" style="padding:6px 12px">▶ headless 시작</button>
    </form>
  </div>

  ${if (d.runningDevices.isNotEmpty()) """
  <div style="margin-top:12px">
    <strong style="font-size:13px">실행 중 → 종료:</strong>
    ${d.runningDevices.joinToString("") { serial -> """
      <form method="post" action="/emulator/avd/stop" style="display:inline;margin-left:6px">
        ${com.siamakerlab.vibecoder.server.auth.CsrfTokens.hiddenInput(csrf)}
        <input type="hidden" name="serial" value="${esc(serial)}">
        <button type="submit" class="chip chip-danger" onclick="return confirm('emulator ${esc(serial)} 종료?')">■ ${esc(serial)}</button>
      </form>
    """ }}
  </div>
  """ else ""}
</div>

<div class="card">
  <h2 style="margin-top:0">수동 launch — 컨테이너 안에서</h2>
  <p class="dim" style="font-size:13px">현재는 컨테이너 안 터미널에서 emulator 를 직접 실행하고,
  본 페이지의 "실행 중인 device" 에서 확인. 이후 APK install 은 콘솔에서 Claude 에게 부탁:</p>

  <h3 style="margin-top:14px">1. AVD 생성 (한 번만)</h3>
  <pre class="diff-block">docker exec -it --user vibe vibe-coder-server bash
${'$'} cmdline-tools/latest/bin/sdkmanager 'system-images;android-35;google_apis;x86_64'
${'$'} cmdline-tools/latest/bin/avdmanager create avd -n test -k 'system-images;android-35;google_apis;x86_64'
exit</pre>

  <h3 style="margin-top:14px">2. emulator 시작 (KVM 있으면 빠름, 없으면 software 모드)</h3>
  <pre class="diff-block">docker exec -d --user vibe vibe-coder-server \
  bash -c '${'$'}ANDROID_HOME/emulator/emulator -avd test -no-window -no-audio &amp;'</pre>
  <p class="hint">no-window 는 GUI 없이 (headless). adb 통신만 가능. GUI 미러는 v0.20+ noVNC.</p>

  <h3 style="margin-top:14px">3. 빌드된 APK 설치 (콘솔에서 Claude 에게)</h3>
  <pre class="diff-block">/projects/{id}/builds 에서 APK 다운로드 또는 콘솔:
> 최근 디버그 APK 를 실행 중인 emulator 에 설치하고 앱을 실행해 첫 화면 확인해줘.</pre>
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">🖥 In-browser noVNC (v0.42.0+, :full 이미지 전용)</h2>
  <p>vibe-coder 인증을 거친 admin 사용자만 접근 가능한 reverse proxy.
    별도 호스트 노출 / SSH 터널 불필요. <code>:full</code> 이미지 + KVM passthrough 가
    구성돼 있어야 함.</p>
  <iframe src="/emulator/vnc/vnc.html?path=emulator/vnc/websockify&autoconnect=true&resize=remote"
          style="border:1px solid var(--border);border-radius:6px;width:100%;height:600px;background:#000"
          title="noVNC live emulator screen"></iframe>
  <p class="hint" style="margin-top:8px;font-size:12px">
    blank 화면이면 ① <code>:full</code> 이미지 사용 중인지, ② 위에서 emulator 가 launch 됐는지,
    ③ <code>/dev/kvm</code> passthrough 가 활성화돼 있는지 확인. 직접 URL 열기:
    <a href="/emulator/vnc/vnc.html" target="_blank">/emulator/vnc/vnc.html ↗</a>
  </p>
</div>

<div class="card" style="margin-top:14px;background:rgba(80,150,255,0.05)">
  <h2 style="margin-top:0">:full 변형 — Xvfb + noVNC 사전 설치 (v0.25.0)</h2>
  <p style="font-size:13px;line-height:1.6">
    슬림 이미지엔 Xvfb / x11vnc / websockify / noVNC 가 없습니다. 브라우저에서
    emulator 화면을 보려면 <code>siamakerlab/vibe-coder-server:full</code> (~3-4GB)
    + compose KVM passthrough 가 필요합니다.
  </p>
  <h3 style="margin-top:12px;font-size:13px">1. compose override 파일 추가</h3>
  <pre class="diff-block">curl -fsSL https://raw.githubusercontent.com/siamakerlab/vibe-coder-server/main/docker/compose.full.yml -o compose.full.yml
echo "VIBECODER_IMAGE_FULL=siamakerlab/vibe-coder-server:full" >> .env
echo "KVM_GID=$(getent group kvm | cut -d: -f3)" >> .env
echo "VIBE_NOVNC_PORT=6080" >> .env
docker compose -f compose.yml -f compose.full.yml up -d --force-recreate vibe-coder-server</pre>

  <h3 style="margin-top:12px;font-size:13px">2. AVD 생성 + 시작</h3>
  <p class="hint">위의 lifecycle 카드의 [+ 디폴트 AVD 생성] → [▶ headless 시작]
    그대로 동작. <strong>:full 이미지 안에선</strong> Xvfb 가 entrypoint 단계에서
    이미 떠 있어 emulator GUI 가 가상 디스플레이 <code>:99</code> 에 렌더링됨.</p>

  <h3 style="margin-top:12px;font-size:13px">3. noVNC 로 화면 확인</h3>
  <p class="hint">브라우저에서 <code>http://&lt;host&gt;:6080/vnc.html</code> 열기 →
    "Connect" → emulator 화면. <strong>인증 없는 raw VNC</strong> 이므로 LAN
    격리 또는 SSH 터널 사용:</p>
  <pre class="diff-block">ssh -L 6080:localhost:6080 user@vibe-host
# 그 다음 브라우저에서: http://localhost:6080/vnc.html</pre>

  <p class="hint">인증된 reverse-proxy 통합 (vibe-coder admin 세션 + iframe
    임베드) 은 v0.26+ 예정. 현재는 운영자 본인 책임 하의 별도 인증 레이어.</p>
</div>
"""
        )
    }
}
