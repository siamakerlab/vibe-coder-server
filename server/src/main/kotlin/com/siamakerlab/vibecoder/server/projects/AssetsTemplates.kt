package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.i18n.Messages

/**
 * v1.65.0 — 프로젝트 "스토어 자산" 탭 SSR (`/projects/{id}/assets`).
 * 앱 아이콘 / 피처 그래픽 / 스크린샷 업로드 + 미리보기 + (아이콘) Claude 적용 컨펌.
 */
internal object AssetsTemplates {

    private fun esc(s: String?): String =
        s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")

    fun page(
        username: String,
        projectId: String,
        projectName: String,
        hasIcon: Boolean,
        hasGraphic: Boolean,
        screenshots: List<String>,
        flash: String?,
        csrf: String?,
        lang: String,
    ): String {
        val t = { key: String -> Messages.t(lang, key) }
        val csrfHidden = CsrfTokens.hiddenInput(csrf)
        val flashHtml = when {
            flash == null -> ""
            flash == "applied" -> ok(t("assets.flash.applied"))
            flash == "copied" -> ok(t("assets.flash.copied"))
            flash == "graphic" -> ok(t("assets.flash.graphic"))
            flash == "shotDeleted" -> ok(t("assets.flash.shotDeleted"))
            flash.startsWith("shots:") -> ok(Messages.t(lang, "assets.flash.shots", flash.removePrefix("shots:")))
            flash == "err:csrf" -> err(t("assets.flash.err.csrf"))
            flash == "err:nofile" -> err(t("assets.flash.err.nofile"))
            flash.startsWith("err:") -> err(t("assets.flash.err.generic"))
            else -> ""
        }

        val iconSrc = if (hasIcon) "/projects/$projectId/assets/raw/icon.png" else "/static/icon.png"
        val graphicPreview = if (hasGraphic) {
            """<img src="/projects/$projectId/assets/raw/graphic.png" alt="" style="max-width:100%;border:1px solid #222;border-radius:8px;margin-top:8px">"""
        } else """<p class="dim" style="font-size:12px;margin-top:6px">${esc(t("assets.graphic.none"))}</p>"""

        val shotsHtml = if (screenshots.isEmpty()) {
            """<p class="dim" style="font-size:12px">${esc(t("assets.shots.empty"))}</p>"""
        } else """<div style="display:flex;flex-wrap:wrap;gap:10px;margin-top:8px">""" +
            screenshots.joinToString("") { name ->
                """<div style="width:120px">
                  <img src="/projects/$projectId/assets/raw/${esc(name)}" alt="" loading="lazy"
                       style="width:120px;height:auto;border:1px solid #222;border-radius:6px;display:block">
                  <div style="display:flex;justify-content:space-between;align-items:center;gap:4px;margin-top:3px">
                    <small class="dim" style="font-size:10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(name.removePrefix("screenshot-").removeSuffix(".png"))}</small>
                    <form method="post" action="/projects/$projectId/assets/screenshot/${esc(name)}/delete" style="margin:0">
                      $csrfHidden
                      <button type="submit" class="chip chip-danger" style="font-size:10px;padding:2px 6px"
                              onclick="return confirm('${escJs(t("assets.shots.deleteConfirm"))}')">${esc(t("assets.delete"))}</button>
                    </form>
                  </div>
                </div>"""
            } + "</div>"

        return AdminTemplates.shell(
            title = "${esc(projectName)} · ${esc(t("assets.title"))}",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            lang = lang,
            body = """
<header>
  <h1 style="margin:0">${esc(t("assets.title"))}
    <small class="dim" style="font-size:14px;font-weight:400">${esc(projectName)} (${esc(projectId)})</small>
  </h1>
  <p class="dim" style="margin:6px 0 0;font-size:13px">${esc(t("assets.intro"))}</p>
</header>

$flashHtml

<!-- 앱 아이콘 -->
<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("assets.icon.title"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 10px">${esc(t("assets.icon.hint"))}</p>
  <div style="display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap">
    <div style="text-align:center">
      <img id="icon-preview" src="${esc(iconSrc)}" alt="" onerror="this.onerror=null;this.src='/static/icon.png'"
           style="width:96px;height:96px;border-radius:18px;object-fit:cover;border:1px solid #222;background:#11151e">
      <div class="dim" style="font-size:11px;margin-top:4px">${esc(t("assets.icon.current"))}</div>
    </div>
    <form id="icon-form" method="post" action="/projects/$projectId/assets/icon" enctype="multipart/form-data" style="flex:1;min-width:240px">
      $csrfHidden
      <input type="hidden" name="apply" id="icon-apply" value="false">
      <input type="file" id="icon-file" name="file" accept="image/png,image/jpeg,image/webp" required
             style="font-size:13px;display:block;margin-bottom:10px">
      <button type="submit" class="primary" style="padding:8px 16px">${esc(t("assets.icon.upload"))}</button>
      <p class="hint" style="font-size:12px;margin:8px 0 0">${esc(t("assets.icon.applyHint"))}</p>
    </form>
  </div>
</div>

<!-- 피처 그래픽 -->
<div class="card" style="margin-bottom:14px">
  <h2 style="margin-top:0">${esc(t("assets.graphic.title"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 8px">${esc(t("assets.graphic.hint"))}</p>
  <form method="post" action="/projects/$projectId/assets/graphic" enctype="multipart/form-data">
    $csrfHidden
    <input type="file" name="file" accept="image/png,image/jpeg,image/webp" required
           style="font-size:13px;display:block;margin-bottom:10px">
    <button type="submit" class="primary" style="padding:8px 16px">${esc(t("assets.graphic.upload"))}</button>
  </form>
  $graphicPreview
</div>

<!-- 스크린샷 -->
<div class="card">
  <h2 style="margin-top:0">${esc(t("assets.shots.title"))}</h2>
  <p class="dim" style="font-size:13px;margin:0 0 8px">${esc(t("assets.shots.hint"))}</p>
  <form method="post" action="/projects/$projectId/assets/screenshot" enctype="multipart/form-data"
        style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
    $csrfHidden
    <label style="margin:0;font-size:13px">${esc(t("assets.shots.lang"))}
      <input name="lang" value="ko" pattern="[a-z]{2}(-[A-Z]{2})?" required
             style="width:80px;padding:5px 7px;margin-left:6px">
    </label>
    <input type="file" name="file" accept="image/png,image/jpeg,image/webp" multiple required style="font-size:13px">
    <button type="submit" class="primary" style="padding:7px 14px">${esc(t("assets.shots.upload"))}</button>
  </form>
  $shotsHtml
</div>

<script>
(function() {
  // 아이콘: 선택 시 미리보기 + 제출 시 "Claude 적용?" 컨펌(OK=적용, 취소=복사만).
  var form = document.getElementById('icon-form');
  var file = document.getElementById('icon-file');
  var preview = document.getElementById('icon-preview');
  var applyEl = document.getElementById('icon-apply');
  if (file && preview) {
    file.addEventListener('change', function() {
      var f = file.files && file.files[0];
      if (!f) return;
      var url = URL.createObjectURL(f);
      preview.src = url;
    });
  }
  if (form) {
    form.addEventListener('submit', function(e) {
      if (form._go) return;
      e.preventDefault();
      if (!file.files || !file.files.length) return;
      var ap = confirm('${escJs(t("assets.icon.confirm"))}');
      applyEl.value = ap ? 'true' : 'false';
      form._go = true;
      form.submit();
    });
  }
})();
</script>
""",
        )
    }

    private fun ok(msg: String) = """<div class="ok-banner">✓ ${esc(msg)}</div>"""
    private fun err(msg: String) = """<div class="error">⚠ ${esc(msg)}</div>"""
}
