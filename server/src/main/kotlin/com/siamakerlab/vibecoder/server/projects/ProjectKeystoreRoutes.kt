package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdmobIds
import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.AdminTemplates
import com.siamakerlab.vibecoder.server.admin.CertInfo
import com.siamakerlab.vibecoder.server.admin.CreateKeystoreRequest
import com.siamakerlab.vibecoder.server.admin.KeystoreEntry
import com.siamakerlab.vibecoder.server.admin.KeystoreFingerprints
import com.siamakerlab.vibecoder.server.admin.KeystoreService
import com.siamakerlab.vibecoder.server.admin.buildApplySigningPrompt
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.call
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
 * v1.93.0 — 프로젝트 페이지 "키스토어" 탭. (사용자 요청 — 기존 `/settings/keystores`
 * 전역 페이지 재활용이 아닌 프로젝트 스코프 신규 페이지.)
 *
 * 해당 프로젝트의 `applicationId`(packageName) 한 건만 다룬다. 패키지 prefix 매칭으로
 * [KeystoreService] 의 같은 파일 set(`<pkg>.keystore` 등)을 공유 — 전역 페이지에서
 * 만든 것이 여기 보이고, 여기서 만든 것이 빌드 서명에 자동 적용된다.
 *
 * 기능:
 *  - 상태(release/debug/properties/admob 존재 + 생성일)
 *  - SHA-1/SHA-256/MD5 지문 열람 (접어두기 — 펼칠 때 lazy fetch, keytool 1회)
 *  - AdMob 광고 ID 관리 (접어두기 — 키스토어 유무와 독립적으로 저장/삭제)
 *  - 키스토어 생성(미존재 시) / build.gradle.kts 서명 적용(Claude) / 삭제
 *
 * SSR 라우트 (JSON wire 아님 — ApiPath 대상 아님, symbols/env-files 와 동일 컨벤션):
 *   GET  /projects/{id}/keystore               — 탭 페이지
 *   GET  /projects/{id}/keystore/fingerprints  — SHA 지문 HTML 조각 (lazy)
 *   POST /projects/{id}/keystore/create        — 키스토어 set 생성(패키지=프로젝트 강제)
 *   POST /projects/{id}/keystore/admob         — AdMob ID 저장/삭제
 *   POST /projects/{id}/keystore/apply         — Claude 콘솔에 서명 적용 prompt 전송
 *   POST /projects/{id}/keystore/delete        — 키스토어 set 삭제
 */
fun Routing.projectKeystoreRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    keystore: KeystoreService,
    sessionManager: ClaudeSessionManager,
) {
    get("/projects/{id}/keystore") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val pkg = p.packageName
        val entry = runCatching { keystore.get(pkg) }.getOrNull()
        val admob = runCatching { keystore.readAdmob(pkg) }.getOrDefault(AdmobIds())
        val defaults = runCatching { keystore.effectiveDefaults() }.getOrNull()
        call.respondText(
            ProjectKeystoreTemplates.page(
                username = sess.username,
                p = p,
                entry = entry,
                admob = admob,
                defaultName = defaults?.name.orEmpty(),
                defaultOrg = defaults?.organization.orEmpty(),
                defaultUnit = defaults?.unit.orEmpty(),
                defaultCountry = defaults?.country.orEmpty(),
                defaultState = defaults?.state.orEmpty(),
                defaultCity = defaults?.city.orEmpty(),
                defaultValidityYears = defaults?.validityYears ?: 100,
                defaultPassword = defaults?.defaultPassword.orEmpty(),
                ok = call.request.queryParameters["ok"],
                err = call.request.queryParameters["err"],
                csrf = sess.csrf,
                lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    get("/projects/{id}/keystore/fingerprints") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondText("<p class=\"dim\">프로젝트를 찾을 수 없습니다.</p>", ContentType.Text.Html)
            return@get
        }
        val fp = runCatching { keystore.fingerprints(p.packageName) }
            .getOrElse { KeystoreFingerprints(false, "read_failed", null, null) }
        call.respondText(ProjectKeystoreTemplates.fingerprintFragment(fp), ContentType.Text.Html)
    }

    post("/projects/{id}/keystore/create") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        val form = call.receiveParameters()
        // packageName 은 프로젝트 메타에서 강제 — 폼 입력 무시(자동 연결 보장).
        val req = CreateKeystoreRequest(
            packageName = p.packageName,
            name = form["name"]?.trim().orEmpty(),
            organization = form["organization"]?.trim().orEmpty(),
            unit = form["unit"]?.trim().orEmpty(),
            country = form["country"]?.trim().orEmpty(),
            state = form["state"]?.trim().orEmpty(),
            city = form["city"]?.trim().orEmpty(),
            password = form["password"]?.trim().orEmpty(),
            validityYears = form["validityYears"]?.trim()?.toIntOrNull(),
            admob = admobFromForm(form),
        )
        val result = runCatching { keystore.create(req) }
        if (result.isSuccess) {
            call.respondRedirect("/projects/$id/keystore?ok=${enc("키스토어 생성됨: ${p.packageName}")}")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "create_failed"
            log.warn(result.exceptionOrNull()) { "project keystore create failed: $id / ${p.packageName}" }
            call.respondRedirect("/projects/$id/keystore?err=${enc("생성 실패: $reason")}")
        }
    }

    post("/projects/{id}/keystore/admob") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        val form = call.receiveParameters()
        val ids = AdmobIds(
            appId = form["admobAppId"]?.trim().orEmpty(),
            appOpenUnitId = form["admobAppOpenUnitId"]?.trim().orEmpty(),
            bannerUnitId = form["admobBannerUnitId"]?.trim().orEmpty(),
            nativeUnitId = form["admobNativeUnitId"]?.trim().orEmpty(),
        )
        val result = runCatching { keystore.saveAdmob(p.packageName, ids) }
        if (result.isSuccess) {
            val allBlank = ids.appId.isBlank() && ids.appOpenUnitId.isBlank() &&
                ids.bannerUnitId.isBlank() && ids.nativeUnitId.isBlank()
            val msg = if (allBlank) "AdMob ID 모두 비워 삭제됨" else "AdMob ID 저장됨"
            call.respondRedirect("/projects/$id/keystore?ok=${enc(msg)}")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "save_failed"
            log.warn(result.exceptionOrNull()) { "admob save failed: $id / ${p.packageName}" }
            call.respondRedirect("/projects/$id/keystore?err=${enc("AdMob 저장 실패: $reason")}")
        }
    }

    post("/projects/{id}/keystore/apply") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        val entry = keystore.get(p.packageName)
        if (entry == null) {
            call.respondRedirect("/projects/$id/keystore?err=${enc("키스토어가 없습니다 — 먼저 생성하세요.")}")
            return@post
        }
        val prompt = buildApplySigningPrompt(p.id, p.moduleName, keystore, entry)
        val sent = runCatching { sessionManager.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "apply-signing prompt failed for $id / ${p.packageName}" } }
            .isSuccess
        if (sent) {
            call.respondRedirect("/projects/$id/keystore?ok=${enc("서명 적용 요청을 Claude 콘솔로 보냈습니다 — 콘솔 탭에서 결과를 확인하세요.")}")
        } else {
            call.respondRedirect("/projects/$id/keystore?err=${enc("Claude 콘솔 전송 실패")}")
        }
    }

    post("/projects/{id}/keystore/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        runCatching { keystore.delete(p.packageName) }
            .onFailure { log.warn(it) { "keystore delete failed: $id / ${p.packageName}" } }
        call.respondRedirect("/projects/$id/keystore?ok=${enc("키스토어 삭제됨: ${p.packageName}")}")
    }
}

private fun admobFromForm(form: io.ktor.http.Parameters): AdmobIds? =
    AdmobIds(
        appId = form["admobAppId"]?.trim().orEmpty(),
        appOpenUnitId = form["admobAppOpenUnitId"]?.trim().orEmpty(),
        bannerUnitId = form["admobBannerUnitId"]?.trim().orEmpty(),
        nativeUnitId = form["admobNativeUnitId"]?.trim().orEmpty(),
    ).takeIf {
        it.appId.isNotBlank() || it.bannerUnitId.isNotBlank() ||
            it.appOpenUnitId.isNotBlank() || it.nativeUnitId.isNotBlank()
    }

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

internal object ProjectKeystoreTemplates {
    private fun esc(s: String?): String =
        s.orEmpty()
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    fun page(
        username: String,
        p: ProjectDto,
        entry: KeystoreEntry?,
        admob: AdmobIds,
        defaultName: String,
        defaultOrg: String,
        defaultUnit: String,
        defaultCountry: String,
        defaultState: String,
        defaultCity: String,
        defaultValidityYears: Int,
        defaultPassword: String,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">⚠ ${esc(it)}</div>""" } ?: ""
        val csrfHidden = CsrfTokens.hiddenInput(csrf)
        val pkg = p.packageName
        val exists = entry != null

        // 상태 카드 ----------------------------------------------------------------
        val files = buildList {
            if (entry?.releaseExists == true) add("release")
            if (entry?.debugExists == true) add("debug")
            if (entry?.propertiesExists == true) add(".properties")
            if (entry?.admobExists == true || hasAdmob(admob)) add("admob")
        }.joinToString(" · ").ifBlank { "—" }
        val statusBadge = if (exists)
            """<span class="ok">✓ 준비됨</span>"""
        else """<span class="dim">✗ 없음 (아래에서 생성)</span>"""
        val statusCard = """
<div class="card" style="margin-bottom:14px">
  <div style="display:grid;grid-template-columns:auto 1fr;gap:6px 14px;font-size:13px">
    <span class="dim">패키지</span><span><code>${esc(pkg)}</code></span>
    <span class="dim">키스토어</span><span>$statusBadge</span>
    <span class="dim">파일</span><span class="dim">${esc(files)}</span>
    <span class="dim">생성일</span><span class="dim">${esc(entry?.createdAt ?: "—")}</span>
  </div>
</div>"""

        // SHA 지문 (접어두기, lazy) -------------------------------------------------
        val shaCard = if (!exists) "" else """
<details class="card" id="pk-sha" data-fp-url="/projects/${esc(p.id)}/keystore/fingerprints" style="margin-bottom:14px">
  <summary style="cursor:pointer;font-weight:600">🔑 SHA 지문 <span class="dim" style="font-weight:400;font-size:12px">(Firebase / Google Sign-In / Maps API 등록용 — 펼치면 불러옵니다)</span></summary>
  <div class="pk-sha-body" style="margin-top:10px">
    <p class="dim" style="font-size:12px">불러오는 중…</p>
  </div>
</details>
<script>
(function () {
  var d = document.getElementById('pk-sha');
  if (!d) return;
  var loaded = false;
  d.addEventListener('toggle', function () {
    if (!d.open || loaded) return;
    loaded = true;
    var body = d.querySelector('.pk-sha-body');
    fetch(d.getAttribute('data-fp-url'), { credentials: 'same-origin' })
      .then(function (r) { return r.text(); })
      .then(function (h) { body.innerHTML = h; })
      .catch(function () { body.textContent = '지문을 불러오지 못했습니다.'; });
  });
})();
</script>"""

        // AdMob 관리 (접어두기 — 키스토어 유무와 독립) ------------------------------
        val admobOpen = if (hasAdmob(admob)) "open" else ""
        val admobCard = """
<details class="card" $admobOpen style="margin-bottom:14px">
  <summary style="cursor:pointer;font-weight:600">📣 AdMob 광고 ID</summary>
  <p class="dim" style="font-size:12px;margin:8px 0 10px">
    릴리스 빌드에 주입할 AdMob ID. 디버그 빌드는 테스트 광고 ID 사용 권장. 모두 비우면 삭제됩니다.
    저장 위치: <code>${esc(pkg)}-admob.properties</code> (호스트 keystores 볼륨).
  </p>
  <form method="post" action="/projects/${esc(p.id)}/keystore/admob" style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
    $csrfHidden
    <label style="grid-column:1/3">
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">App ID</div>
      <input name="admobAppId" value="${esc(admob.appId)}" placeholder="ca-app-pub-XXXX~YYYY"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <label>
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">App Open Unit ID</div>
      <input name="admobAppOpenUnitId" value="${esc(admob.appOpenUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <label>
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">Banner Unit ID</div>
      <input name="admobBannerUnitId" value="${esc(admob.bannerUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <label>
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">Native Unit ID</div>
      <input name="admobNativeUnitId" value="${esc(admob.nativeUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    <div style="grid-column:1/3">
      <button type="submit" class="primary">AdMob 저장</button>
    </div>
  </form>
</details>"""

        // 생성 폼 (미존재 시) ------------------------------------------------------
        val passwordPrefilled = if (defaultPassword.isNotBlank()) """value="${esc(defaultPassword)}"""" else ""
        val createCard = if (exists) "" else """
<details class="card" open style="margin-bottom:14px">
  <summary style="cursor:pointer;font-weight:600">🆕 키스토어 생성</summary>
  <p class="dim" style="font-size:12px;margin:8px 0 10px">
    이 프로젝트의 패키지 <code>${esc(pkg)}</code> 로 release/debug 키스토어 + signing properties 를 생성합니다
    (PKCS12, RSA 4096). 비밀번호 1개로 양쪽 서명. 호스트 영속 볼륨에 저장됩니다.
  </p>
  <form method="post" action="/projects/${esc(p.id)}/keystore/create">
    $csrfHidden
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">패키지 (고정)</div>
        <input value="${esc(pkg)}" readonly
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace;opacity:0.7">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">비밀번호 *</div>
        <input name="password" type="text" required $passwordPrefilled
               style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">유효기간(년)</div>
        <input name="validityYears" type="number" min="1" max="100" value="$defaultValidityYears"
               style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">이름 (CN)</div>
        <input name="name" value="${esc(defaultName)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">조직 (O)</div>
        <input name="organization" value="${esc(defaultOrg)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">부서 (OU)</div>
        <input name="unit" value="${esc(defaultUnit)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">국가 (C)</div>
        <input name="country" value="${esc(defaultCountry)}" maxlength="2"
               style="width:100%;padding:8px;text-transform:uppercase">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">주/도 (ST)</div>
        <input name="state" value="${esc(defaultState)}" style="width:100%;padding:8px">
      </label>
      <label>
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">도시 (L)</div>
        <input name="city" value="${esc(defaultCity)}" style="width:100%;padding:8px">
      </label>
    </div>
    <details style="margin-top:12px">
      <summary style="cursor:pointer;font-size:13px;color:#aaa">AdMob 광고 ID (선택 — 생성 시 함께 저장)</summary>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">
        <label style="grid-column:1/3">
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">App ID</div>
          <input name="admobAppId" value="${esc(admob.appId)}" placeholder="ca-app-pub-XXXX~YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">App Open Unit ID</div>
          <input name="admobAppOpenUnitId" value="${esc(admob.appOpenUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">Banner Unit ID</div>
          <input name="admobBannerUnitId" value="${esc(admob.bannerUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        <label>
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">Native Unit ID</div>
          <input name="admobNativeUnitId" value="${esc(admob.nativeUnitId)}" placeholder="ca-app-pub-XXXX/YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
      </div>
    </details>
    <p class="dim" style="font-size:12px;margin:12px 0 8px">⚠ 키스토어는 한 번 잃으면 같은 키로 앱 업데이트가 불가합니다 — 호스트 keystores 볼륨을 백업하세요.</p>
    <button type="submit" class="primary">키스토어 생성</button>
  </form>
</details>"""

        // 서명 적용 + 삭제 (존재 시) ------------------------------------------------
        val actionsCard = if (!exists) "" else """
<div class="card" style="margin-bottom:14px">
  <h3 style="margin:0 0 8px;font-size:14px">빌드 적용 / 관리</h3>
  <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
    <form method="post" action="/projects/${esc(p.id)}/keystore/apply" style="margin:0"
          onsubmit="return confirm('이 키스토어로 build.gradle.kts 의 signingConfigs 를 수정하도록 Claude 콘솔에 요청합니다. 진행할까요?')">
      $csrfHidden
      <button type="submit" class="primary">build.gradle.kts 서명 적용 (Claude)</button>
    </form>
    <form method="post" action="/projects/${esc(p.id)}/keystore/delete" style="margin:0"
          onsubmit="return confirm('키스토어 set(release/debug/properties/admob) 을 삭제합니다. 복구 불가 — 진행할까요?')">
      $csrfHidden
      <button type="submit" style="background:#7f1d1d;color:#fff;border:0;padding:8px 14px;border-radius:6px;cursor:pointer">키스토어 삭제</button>
    </form>
  </div>
</div>"""

        return AdminTemplates.shell(
            title = "${esc(p.name)} · 키스토어",
            username = username,
            currentPath = "/projects",
            csrf = csrf,
            body = """
<header>
  <h1>키스토어 &amp; 광고 ID
    <small class="dim" style="font-size:14px;font-weight:400">${esc(p.name)} (${esc(p.id)})</small>
  </h1>
</header>

$okHtml
$errHtml

$statusCard
$shaCard
$admobCard
$createCard
$actionsCard
""",
            lang = lang,
            embed = embed,
        )
    }

    /** SHA 지문 lazy fetch 의 HTML 조각 (text/html). */
    fun fingerprintFragment(fp: KeystoreFingerprints): String {
        if (!fp.available) {
            val msg = when (fp.error) {
                "no_keystore" -> "키스토어가 없습니다."
                "password_unknown" -> "signing properties 의 비밀번호를 찾을 수 없어 지문을 읽을 수 없습니다."
                "invalid_package_name" -> "패키지명이 올바르지 않습니다."
                else -> "지문을 읽지 못했습니다 (keytool 오류)."
            }
            return """<p class="dim" style="font-size:12px">$msg</p>"""
        }
        return buildString {
            fp.release?.let { append(certBlock("release", it)) }
            fp.debug?.let { append(certBlock("debug", it)) }
            append("""<p class="dim" style="font-size:11px;margin-top:8px">값을 클릭하면 전체가 선택됩니다 (복사 편의).</p>""")
        }
    }

    private fun certBlock(label: String, c: CertInfo): String {
        fun row(k: String, v: String?): String {
            if (v.isNullOrBlank()) return ""
            return """<div style="display:grid;grid-template-columns:90px 1fr;gap:6px 12px;padding:3px 0;font-size:12px">
                <span class="dim">$k</span>
                <code onclick="var r=document.createRange();r.selectNodeContents(this);var s=getSelection();s.removeAllRanges();s.addRange(r);"
                      style="word-break:break-all;cursor:pointer;font-family:ui-monospace,Menlo,monospace">${esc(v)}</code>
              </div>"""
        }
        return """
<div style="margin-bottom:10px">
  <div style="font-weight:600;font-size:13px;margin-bottom:4px">${esc(label)}</div>
  ${row("SHA-1", c.sha1)}
  ${row("SHA-256", c.sha256)}
  ${row("MD5", c.md5)}
  ${row("만료", c.validUntil)}
</div>"""
    }

    private fun hasAdmob(a: AdmobIds): Boolean =
        a.appId.isNotBlank() || a.appOpenUnitId.isNotBlank() ||
            a.bannerUnitId.isNotBlank() || a.nativeUnitId.isNotBlank()
}
