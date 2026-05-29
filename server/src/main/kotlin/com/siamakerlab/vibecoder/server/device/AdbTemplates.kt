package com.siamakerlab.vibecoder.server.device

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages

/** v1.40.0 — 무선 ADB logcat 페이지(admin). */
internal object AdbTemplates {
    private fun esc(s: String?): String =
        s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    // 21차 후속(M6) — onclick JS 문자열 컨텍스트. esc(escJs(x)) 로 HTML 엔티티 이중디코딩 XSS 방지.
    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    fun page(
        username: String,
        available: Boolean,
        devices: List<AdbService.Device>,
        discovered: List<AdbService.Discovered>,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
    ): String {
        val t = { k: String -> Messages.t(lang, k) }
        val okHtml = if (!ok.isNullOrBlank()) """<div class="ok-banner">✓ ${esc(ok)}</div>""" else ""
        val errHtml = if (!err.isNullOrBlank()) """<div class="error">${esc(err)}</div>""" else ""

        if (!available) {
            val body = """
<header><h1 style="margin:0">${esc(t("adb.title"))}</h1></header>
<div class="card"><div class="error">${esc(t("adb.unavailable"))}</div>
  <p class="hint" style="margin-top:8px">${esc(t("adb.unavailable.hint"))}
    <a href="/env-setup">/env-setup</a></p></div>"""
            return AdminTemplates.shell(title = t("adb.title"), username = username, currentPath = "/adb", csrf = csrf, lang = lang, body = body)
        }

        // 자동 탐지 (HOST 네트워크). connect-service / pairing-service 분리.
        val connectSvc = discovered.filter { !it.pairing }
        val pairSvc = discovered.filter { it.pairing }
        val discoveredHtml = if (discovered.isEmpty()) {
            """<p class="hint">${esc(t("adb.discover.empty"))}</p>"""
        } else buildString {
            if (connectSvc.isNotEmpty()) {
                append("""<table class="devices" style="margin-bottom:8px"><thead><tr><th>${esc(t("adb.discover.name"))}</th><th>${esc(t("adb.col.addr"))}</th><th></th></tr></thead><tbody>""")
                connectSvc.forEach { d ->
                    append("""<tr><td><code>${esc(d.name)}</code></td><td><code>${esc(d.hostPort)}</code></td>
                      <td><form method="post" action="/adb/connect" style="display:inline">${CsrfTokens.hiddenInput(csrf)}
                        <input type="hidden" name="hostPort" value="${esc(d.hostPort)}">
                        <button type="submit" class="chip chip-link">${esc(t("adb.connect"))}</button></form></td></tr>""")
                }
                append("</tbody></table>")
            }
            if (pairSvc.isNotEmpty()) {
                append("""<p class="hint" style="font-size:12px">${esc(t("adb.discover.pairHint"))} ${pairSvc.joinToString(", ") { "<code>${esc(it.hostPort)}</code>" }}</p>""")
            }
        }

        // 연결된 기기.
        val deviceRows = if (devices.isEmpty()) {
            """<tr><td colspan="4" class="dim" style="text-align:center;padding:10px">${esc(t("adb.devices.empty"))}</td></tr>"""
        } else devices.joinToString("") { d ->
            val st = if (d.connected) """<span class="ok">${esc(d.state)}</span>""" else """<span class="warn">${esc(d.state)}</span>"""
            val act = if (d.connected) {
                """<button type="button" class="chip chip-link" onclick="adbLog('${esc(escJs(d.serial))}')">${esc(t("adb.viewLog"))}</button>
                   <form method="post" action="/adb/disconnect" style="display:inline">${CsrfTokens.hiddenInput(csrf)}
                     <input type="hidden" name="hostPort" value="${esc(d.serial)}">
                     <button type="submit" class="chip chip-danger">${esc(t("adb.disconnect"))}</button></form>"""
            } else ""
            """<tr><td><code>${esc(d.serial)}</code></td><td class="dim">${esc(d.model ?: "-")}</td><td>$st</td><td style="white-space:nowrap">$act</td></tr>"""
        }

        val body = """
<header>
  <div style="display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap">
    <h1 style="margin:0">${esc(t("adb.title"))}</h1>
    <a href="/adb" class="chip chip-link">${esc(t("adb.refresh"))}</a>
  </div>
</header>
$okHtml
$errHtml
<p class="dim" style="font-size:13px;margin:6px 0 14px;line-height:1.5">${esc(t("adb.intro"))}</p>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin:0 0 8px;font-size:15px">① ${esc(t("adb.discover.title"))}</h2>
  $discoveredHtml
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin:0 0 8px;font-size:15px">② ${esc(t("adb.manual.title"))}</h2>
  <p class="hint" style="font-size:12px;margin:0 0 10px">${esc(t("adb.manual.hint"))}</p>
  <form method="post" action="/adb/pair" style="display:flex;gap:8px;align-items:end;flex-wrap:wrap;margin-bottom:10px">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0">${esc(t("adb.pair.addr"))}<input name="hostPort" required placeholder="192.168.0.x:포트"></label>
    <label style="margin:0">${esc(t("adb.pair.code"))}<input name="code" required pattern="\\d{6}" placeholder="123456"></label>
    <button type="submit" class="primary" style="padding:8px 14px">${esc(t("adb.pair.btn"))}</button>
  </form>
  <form method="post" action="/adb/connect" style="display:flex;gap:8px;align-items:end;flex-wrap:wrap">
    ${CsrfTokens.hiddenInput(csrf)}
    <label style="margin:0;flex:1;min-width:200px">${esc(t("adb.connect.addr"))}<input name="hostPort" required placeholder="192.168.0.x:포트"></label>
    <button type="submit" class="primary" style="padding:8px 14px">${esc(t("adb.connect"))}</button>
  </form>
</div>

<div class="card" style="margin-bottom:14px">
  <h2 style="margin:0 0 8px;font-size:15px">③ ${esc(t("adb.devices.title"))}</h2>
  <table class="devices"><thead><tr><th>serial</th><th>model</th><th>${esc(t("adb.col.state"))}</th><th></th></tr></thead><tbody>$deviceRows</tbody></table>
</div>

<div class="card">
  <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:8px">
    <h2 style="margin:0;font-size:15px">④ logcat</h2>
    <span class="dim" id="adb-log-target" style="font-size:12px">${esc(t("adb.log.none"))}</span>
    <span style="flex:1"></span>
    <input id="adb-pkg" placeholder="${esc(t("adb.log.pkg"))}" style="padding:4px 8px;font-size:12px;width:200px">
    <input id="adb-filter" placeholder="${esc(t("adb.log.filter"))}" style="padding:4px 8px;font-size:12px;width:140px">
    <button type="button" class="chip" onclick="adbLogStop()">${esc(t("adb.log.stop"))}</button>
    <button type="button" class="chip" onclick="document.getElementById('adb-log').textContent=''">${esc(t("adb.log.clear"))}</button>
  </div>
  <pre id="adb-log" style="margin:0;height:380px;overflow:auto;background:#0b0e14;color:#cfd6e4;font-size:11px;line-height:1.45;padding:10px;border-radius:6px;white-space:pre-wrap;word-break:break-word"></pre>
</div>

<script>
(function(){
  var ws=null, logEl=document.getElementById('adb-log'), tgt=document.getElementById('adb-log-target');
  window.adbLogStop=function(){ if(ws){ try{ws.close();}catch(e){} ws=null; } };
  window.adbLog=function(serial){
    adbLogStop();
    var pkg=(document.getElementById('adb-pkg').value||'').trim();
    var proto=location.protocol==='https:'?'wss:':'ws:';
    var url=proto+'//'+location.host+'${com.siamakerlab.vibecoder.shared.ApiPath.WS_ADB_LOGCAT}?serial='+encodeURIComponent(serial)+(pkg?'&pkg='+encodeURIComponent(pkg):'');
    tgt.textContent=serial+(pkg?(' · '+pkg):'');
    ws=new WebSocket(url);
    ws.onmessage=function(ev){
      var filt=(document.getElementById('adb-filter').value||'').trim();
      var atBottom=logEl.scrollTop+logEl.clientHeight>=logEl.scrollHeight-12;
      var text=ev.data;
      if(filt){ text=text.split('\n').filter(function(l){return l.indexOf(filt)>=0;}).join('\n'); if(text) text+='\n'; }
      if(text){ logEl.appendChild(document.createTextNode(text)); if(atBottom) logEl.scrollTop=logEl.scrollHeight; }
    };
    ws.onclose=function(){ tgt.textContent+=' (closed)'; };
    ws.onerror=function(){ };
  };
})();
</script>
"""
        return AdminTemplates.shell(title = t("adb.title"), username = username, currentPath = "/adb", csrf = csrf, lang = lang, body = body)
    }
}
