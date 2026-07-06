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
        pool: EmulatorService.PoolStatus?,
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
            // v1.96.0 — 외부/콘솔에서 띄운(특히 -accel off) 에뮬레이터 경고 + 회수 안내.
            if (status.external) append("""<div class="flash warn">⚠ ${esc(t("emulator.external.warn"))}</div>""")
        }

        val controls = if (!status.available) {
            """<p class="hint">${esc(t("emulator.hint.installFirst"))}
                 <a href="/env-setup" class="chip-link">${esc(t("emulator.link.buildEnv"))}</a></p>"""
        } else {
            """<form method="post" action="/emulator/start" style="display:inline"
                     onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent=${jsLit(t("emulator.action.starting"))}">
                 $csrfIn
                 <button type="submit" class="primary">${esc(t("emulator.action.startDefault"))}</button>
               </form>"""
        }

        val p = pool ?: EmulatorService.PoolStatus(status.available, 5, if (status.running) 1 else 0, if (status.booted) 1 else 0, listOf(status))
        val rows = p.slots.joinToString("\n") { s ->
            val (badgeCls, badgeText) = when {
                !s.available -> "miss" to t("emulator.badge.notInstalled")
                !s.leasedByProjectId.isNullOrBlank() -> "warn" to t("nav.emulator.busy")
                s.booted -> "ok" to t("emulator.badge.booted")
                s.running -> "warn" to t("emulator.badge.booting")
                else -> "dim" to t("emulator.badge.stopped")
            }
            val action = if (!s.available) {
                """<a href="/env-setup" class="chip chip-link">${esc(t("emulator.link.buildEnv"))}</a>"""
            } else if (s.running) {
                """<form method="post" action="/emulator/stop/${esc(s.id)}" style="display:inline">$csrfIn<button type="submit">${esc(t("emulator.action.stop"))}</button></form>"""
            } else {
                """<form method="post" action="/emulator/start/${esc(s.id)}" style="display:inline" onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent=${jsLit(t("emulator.action.starting"))}">$csrfIn<button type="submit" class="primary">${esc(t("emulator.action.start"))}</button></form>"""
            }
            val lease = s.leasedByProjectId?.let { """<span class="badge warn">${esc(it)}</span>""" } ?: """<span class="dim">-</span>"""
            """
<tr data-emulator-id="${esc(s.id)}">
  <td><strong>${esc(s.label)}</strong><br><span class="dim">${esc(s.kind)} · ${esc(s.id)}</span></td>
  <td><span class="badge $badgeCls" data-role="emu-status-badge">${esc(badgeText)}</span><span data-role="emu-external">${if (s.external) " <span class=\"badge warn\">external</span>" else ""}</span></td>
  <td><code>${esc(s.serial)}</code><br><span class="dim">port ${s.consolePort}</span></td>
  <td><code>${esc(s.avdName)}</code><br><span class="dim">${esc(s.systemImage)}</span></td>
  <td data-role="emu-lease">$lease</td>
  <td style="text-align:right">$action</td>
</tr>
"""
        }

        val body = """
<header><h1>${esc(t("emulator.page.title"))}
  <small class="dim" style="font-size:14px;font-weight:400">${esc(t("emulator.page.subtitle"))}</small>
</h1></header>
$flash
<section>
  <div class="row" style="display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:12px">
    <span id="emu-booted-count" class="badge ${if (p.booted > 0) "ok" else "dim"}" aria-live="polite">${p.booted}/${p.max} booted</span>
    <span id="emu-running-count" class="badge ${if (p.running > 0) "warn" else "dim"}" aria-live="polite">${p.running}/${p.max} running</span>
    <span class="dim">${esc(t("emulator.pool.limit"))}</span>
    <span style="margin-left:auto">$controls</span>
  </div>
  <table class="devices">
    <thead><tr><th>${esc(t("emulator.col.profile"))}</th><th>${esc(t("emulator.col.status"))}</th><th>${esc(t("emulator.col.serial"))}</th><th>${esc(t("emulator.col.avd"))}</th><th>${esc(t("emulator.col.lease"))}</th><th></th></tr></thead>
    <tbody>$rows</tbody>
  </table>
  <dl class="kv" style="margin-top:14px">
    <div><dt>AVD</dt><dd><code>${esc(avdName)}</code></dd></div>
    <div><dt>System image</dt><dd><code>${esc(systemImage)}</code></dd></div>
  </dl>
</section>

<section style="margin-top:14px">
  <h2 style="font-size:15px;margin:0 0 8px">${esc(t("emulator.usage.title"))}</h2>
  <p class="hint">${esc(t("emulator.usage.body"))}</p>
  <pre class="diff-block">adb -s emulator-5554 install -r app-debug.apk
adb -s emulator-5554 logcat -v time
adb -s emulator-5554 shell am start -n &lt;pkg&gt;/.MainActivity</pre>
  <p class="hint">${esc(t("emulator.usage.kvm"))}</p>
</section>
<script>
(function(){
  var bootedCount = document.getElementById('emu-booted-count');
  var runningCount = document.getElementById('emu-running-count');
  var labels = {
    notInstalled: ${jsLit(t("emulator.badge.notInstalled"))},
    busy: ${jsLit(t("nav.emulator.busy"))},
    booted: ${jsLit(t("emulator.badge.booted"))},
    booting: ${jsLit(t("emulator.badge.booting"))},
    stopped: ${jsLit(t("emulator.badge.stopped"))}
  };
  function escHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function(c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }
  function badgeFor(slot) {
    if (!slot.available) return ['miss', labels.notInstalled];
    if (slot.leasedByProjectId) return ['warn', labels.busy];
    if (slot.booted) return [slot.leasedByProjectId ? 'warn' : 'ok', labels.booted];
    if (slot.running) return ['warn', labels.booting];
    return ['dim', labels.stopped];
  }
  function updateSlot(slot) {
    var cssEscape = window.CSS && CSS.escape ? CSS.escape : function(s) { return String(s).replace(/["\\]/g, '\\$&'); };
    var row = document.querySelector('tr[data-emulator-id="' + cssEscape(slot.id || '') + '"]');
    if (!row) return;
    var badge = row.querySelector('[data-role="emu-status-badge"]');
    var external = row.querySelector('[data-role="emu-external"]');
    var lease = row.querySelector('[data-role="emu-lease"]');
    var b = badgeFor(slot);
    if (badge) {
      badge.className = 'badge ' + b[0];
      badge.textContent = b[1];
    }
    if (external) external.innerHTML = slot.external ? ' <span class="badge warn">external</span>' : '';
    if (lease) {
      lease.innerHTML = slot.leasedByProjectId
        ? '<span class="badge warn">' + escHtml(slot.leasedByProjectId) + '</span>'
        : '<span class="dim">-</span>';
    }
  }
  function tick() {
    fetch('/api/emulators', { credentials: 'same-origin' })
      .then(function(r){ return r.ok ? r.json() : null; })
      .then(function(p){
        if (!p || !Array.isArray(p.slots)) return;
        if (bootedCount) {
          bootedCount.textContent = (p.booted || 0) + '/' + (p.max || p.slots.length) + ' booted';
          bootedCount.className = 'badge ' + ((p.booted || 0) > 0 ? 'ok' : 'dim');
        }
        if (runningCount) {
          runningCount.textContent = (p.running || 0) + '/' + (p.max || p.slots.length) + ' running';
          runningCount.className = 'badge ' + ((p.running || 0) > 0 ? 'warn' : 'dim');
        }
        p.slots.forEach(updateSlot);
      })
      .catch(function(){});
  }
  tick();
  window.setInterval(tick, 5000);
})();
</script>
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
