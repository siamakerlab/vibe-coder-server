package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.73.0 — 안드로이드 에뮬레이터(헤드리스) 상태/제어 페이지. 화면 미러링 없음 —
 * Claude Code 가 콘솔에서 adb 로 logcat/install 하도록 "띄우기/끄기"만 제공.
 */
internal object EmulatorTemplates {

    fun page(
        username: String,
        status: EmulatorService.Status,
        avdName: String,
        systemImage: String,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val esc = ::escapeHtml
        val csrfIn = CsrfTokens.hiddenInput(csrf)

        val flash = buildString {
            if (!ok.isNullOrBlank()) append("""<div class="flash ok">${esc(ok)}</div>""")
            if (!err.isNullOrBlank()) append("""<div class="flash err">${esc(err)}</div>""")
        }

        // 상태 배지.
        val (badgeCls, badgeText) = when {
            !status.available -> "miss" to t("emulator.badge.notInstalled")
            status.booted -> "ok" to t("emulator.badge.booted")
            status.running -> "warn" to t("emulator.badge.booting")
            else -> "dim" to t("emulator.badge.stopped")
        }

        val controls = if (!status.available) {
            """<p class="hint">${esc(t("emulator.hint.installFirst"))}
                 <a href="/env-setup" class="chip-link">${esc(t("emulator.link.buildEnv"))}</a></p>"""
        } else if (status.running) {
            """<form method="post" action="/emulator/stop" style="display:inline">
                 $csrfIn
                 <button type="submit" class="primary">${esc(t("emulator.action.stop"))}</button>
               </form>"""
        } else {
            """<form method="post" action="/emulator/start" style="display:inline"
                     onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent=${jsLit(t("emulator.action.starting"))}">
                 $csrfIn
                 <button type="submit" class="primary">${esc(t("emulator.action.start"))}</button>
               </form>"""
        }

        val serial = esc(status.serial)
        val body = """
<header><h1>📱 ${esc(t("emulator.page.title"))}
  <small class="dim" style="font-size:14px;font-weight:400">${esc(t("emulator.page.subtitle"))}</small>
</h1></header>
$flash
<div class="card">
  <div class="row" style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
    <span class="badge $badgeCls">${esc(badgeText)}</span>
    <span class="dim">serial: <code>$serial</code></span>
  </div>
  <div style="margin-top:12px">$controls</div>
  <dl class="kv" style="margin-top:14px">
    <div><dt>AVD</dt><dd><code>${esc(avdName)}</code></dd></div>
    <div><dt>System image</dt><dd><code>${esc(systemImage)}</code></dd></div>
  </dl>
</div>

<div class="card" style="margin-top:14px">
  <h2 style="font-size:15px;margin:0 0 8px">${esc(t("emulator.usage.title"))}</h2>
  <p class="hint">${esc(t("emulator.usage.body"))}</p>
  <pre class="diff-block">adb -s $serial install -r app-debug.apk
adb -s $serial logcat -v time
adb -s $serial shell am start -n &lt;pkg&gt;/.MainActivity</pre>
  <p class="hint">${esc(t("emulator.usage.kvm"))}</p>
</div>
"""
        return AdminTemplates.shell(
            title = t("emulator.page.title"),
            username = username,
            currentPath = "/emulator",
            csrf = csrf,
            lang = lang,
            embed = embed,
            body = body,
        )
    }

    private fun jsLit(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
