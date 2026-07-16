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
import com.siamakerlab.vibecoder.server.admin.buildKeystorePlacementPrompt
import com.siamakerlab.vibecoder.server.admin.isBuildRunning
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.automation.PromptAutomationManager
import com.siamakerlab.vibecoder.server.i18n.Messages
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
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
 *  - 키스토어 생성(미존재 시) / build.gradle.kts 서명 적용(현재 콘솔 provider) / 삭제
 *
 * SSR 라우트 (JSON wire 아님 — ApiPath 대상 아님, symbols/env-files 와 동일 컨벤션):
 *   GET  /projects/{id}/keystore               — 탭 페이지
 *   GET  /projects/{id}/keystore/fingerprints  — SHA 지문 HTML 조각 (lazy)
 *   POST /projects/{id}/keystore/create        — 키스토어 set 생성(패키지=프로젝트 강제)
 *   POST /projects/{id}/keystore/admob         — AdMob ID 저장/삭제
 *   POST /projects/{id}/keystore/apply         — 현재 콘솔 provider 에 서명 적용 prompt 전송
 *   POST /projects/{id}/keystore/delete        — 키스토어 set 삭제
 */
fun Routing.projectKeystoreRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    keystore: KeystoreService,
    agentRouter: AgentRouter,
    buildRepo: BuildRepository,
    promptAutomationManager: PromptAutomationManager,
) {
    // v1.101.0 — 콘솔이 유휴(응답중/빌드중/자동화중 모두 아님)인지. 업로드는 직후 콘솔
    // 프롬프트를 쏘므로 유휴일 때만 허용한다(파괴적/이동 라우트 idle 가드와 동일 체계).
    // v1.161.0 — 선택된 provider 의 busy 상태를 기준으로 idle 판정.
    fun consoleIdle(id: String): Boolean =
        !agentRouter.isBusy(id) &&
            !isBuildRunning(buildRepo, id) &&
            !promptAutomationManager.isActive(id)

    fun selectedProviderName(id: String): String = agentRouter.providerFor(id).displayName

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
                consoleIdle = consoleIdle(id),
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
        // requireCsrf() 가 receiveParameters() 로 본문을 1회 읽고 검증 후 반환한다.
        // Ktor 3.x 는 본문 채널을 1회만 읽을 수 있어 별도 receiveParameters() 재호출 금지.
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
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
            admob = admobFromForm(form).takeIf { !it.isBlank },
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
        // requireCsrf() 반환값(검증된 폼)을 그대로 사용 — 본문 재수신 금지(Ktor 1회 제한).
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        // v1.105.0 — 저장 직후 "앱 적용" 프롬프트를 콘솔에 전송하므로, 업로드와 동일하게
        // 콘솔이 유휴일 때만 허용한다(저장 버튼도 busy 시 비활성). 렌더~제출 사이 race 방어.
        if (!consoleIdle(id)) {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("콘솔이 작업 중입니다 — 유휴 상태에서 다시 시도하세요 (저장 직후 앱 적용 프롬프트를 전송하기 때문).")}",
            )
            return@post
        }
        val ids = admobFromForm(form)
        val result = runCatching { keystore.saveAdmob(p.packageName, ids) }
        if (!result.isSuccess) {
            val reason = result.exceptionOrNull()?.message ?: "save_failed"
            log.warn(result.exceptionOrNull()) { "admob save failed: $id / ${p.packageName}" }
            call.respondRedirect("/projects/$id/keystore?err=${enc("AdMob 저장 실패: $reason")}")
            return@post
        }
        // 모두 비웠으면 삭제만 (적용할 광고 ID 가 없으므로 콘솔 프롬프트 미발사).
        if (ids.isBlank) {
            call.respondRedirect("/projects/$id/keystore?ok=${enc("AdMob ID 모두 비워 삭제됨")}")
            return@post
        }
        // 저장된 광고 ID 를 앱(build.gradle.kts + AndroidManifest + 광고제거 구매)에 적용하도록
        // 콘솔 프롬프트 발사. 광고 ID 원본은 <pkg>-admob.properties 가 단일 진실.
        val admobPropsPath = keystore.keystoreDirPath().resolve("${p.packageName}-admob.properties")
        val prompt = buildApplyAdmobPrompt(p.id, p.moduleName, p.packageName, admobPropsPath, ids)
        val providerName = selectedProviderName(id)
        val sent = runCatching { agentRouter.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "apply-admob prompt failed for $id / ${p.packageName}" } }
            .isSuccess
        if (sent) {
            call.respondRedirect(
                "/projects/$id/keystore?ok=${enc("AdMob ID 저장됨 — 앱 적용(광고 + 광고제거 구매) 요청을 $providerName 콘솔로 보냈습니다. 콘솔 탭에서 진행 상황을 확인하세요.")}",
            )
        } else {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("AdMob ID 는 저장됐으나 콘솔 프롬프트 전송에 실패했습니다 — 콘솔이 유휴 상태인지 확인 후 다시 시도하세요.")}",
            )
        }
    }

    // v1.105.0 — 저장 없이, 이미 저장된 AdMob 광고 ID 로 "앱 적용" 프롬프트만 콘솔에 보낸다.
    // (저장 버튼이 콘솔 busy 였거나, 코드 변경 후 재적용하고 싶을 때 사용.) 콘솔 유휴 + 저장된 ID 필수.
    post("/projects/{id}/keystore/admob/apply") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        if (!consoleIdle(id)) {
            call.respondRedirect("/projects/$id/keystore?err=${enc("콘솔이 작업 중입니다 — 유휴 상태에서 다시 시도하세요.")}")
            return@post
        }
        val ids = runCatching { keystore.readAdmob(p.packageName) }.getOrDefault(AdmobIds())
        if (ids.isBlank) {
            call.respondRedirect("/projects/$id/keystore?err=${enc("저장된 AdMob 광고 ID 가 없습니다 — 먼저 광고 ID 를 저장하세요.")}")
            return@post
        }
        val admobPropsPath = keystore.keystoreDirPath().resolve("${p.packageName}-admob.properties")
        val prompt = buildApplyAdmobPrompt(p.id, p.moduleName, p.packageName, admobPropsPath, ids)
        val providerName = selectedProviderName(id)
        val sent = runCatching { agentRouter.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "apply-admob (manual) prompt failed for $id / ${p.packageName}" } }
            .isSuccess
        if (sent) {
            call.respondRedirect(
                "/projects/$id/keystore?ok=${enc("앱 적용(광고 + 광고제거 구매) 요청을 $providerName 콘솔로 보냈습니다. 콘솔 탭에서 진행 상황을 확인하세요.")}",
            )
        } else {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("콘솔 프롬프트 전송에 실패했습니다 — 콘솔이 유휴 상태인지 확인 후 다시 시도하세요.")}",
            )
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
        // v1.114.1 — 서명 적용도 콘솔 프롬프트를 쏘므로 유휴일 때만(형제 라우트 admob/apply·
        // upload 와 동일 가드). 이전엔 이 라우트만 가드가 빠져, 콘솔이 작업 중일 때 프롬프트가
        // 충돌하던 선재 결함을 해소.
        if (!consoleIdle(id)) {
            call.respondRedirect("/projects/$id/keystore?err=${enc("콘솔이 작업 중입니다 — 유휴 상태에서 다시 시도하세요.")}")
            return@post
        }
        val prompt = buildApplySigningPrompt(p.id, p.moduleName, keystore, entry)
        val providerName = selectedProviderName(id)
        val sent = runCatching { agentRouter.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "apply-signing prompt failed for $id / ${p.packageName}" } }
            .isSuccess
        if (sent) {
            call.respondRedirect("/projects/$id/keystore?ok=${enc("서명 적용 요청을 $providerName 콘솔로 보냈습니다 — 콘솔 탭에서 결과를 확인하세요.")}")
        } else {
            call.respondRedirect("/projects/$id/keystore?err=${enc("$providerName 콘솔 전송 실패")}")
        }
    }

    // v1.101.0 — 외부에서 만든 키스토어 set 업로드. multipart 라 CSRF 는 query(?_csrf=).
    // 콘솔 유휴일 때만(업로드 직후 build.gradle.kts 서명 갱신 프롬프트 발사).
    post("/projects/{id}/keystore/upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        // multipart → receiveParameters 불가. query/header 의 _csrf 검증(실패 시 SSR redirect).
        if (!runCatching { CsrfTokens.verifyCsrfFromQueryOrHeader(call) }.isSuccess) {
            call.respondRedirect("/projects/$id/keystore?err=${enc("보안 토큰(CSRF) 불일치 — 페이지를 새로고침 후 다시 시도하세요.")}")
            return@post
        }
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@post
        }
        // idle 가드 — 업로드 직후 콘솔 프롬프트를 쏘므로 유휴 필수.
        if (!consoleIdle(id)) {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("콘솔이 작업 중입니다 — 유휴 상태에서만 업로드할 수 있습니다 (업로드 직후 정보 갱신 프롬프트를 전송하기 때문).")}",
            )
            return@post
        }
        // multipart 파트 수집 (release / debug / release properties / debug properties).
        var releaseBytes: ByteArray? = null
        var debugBytes: ByteArray? = null
        var propsText: String? = null
        var debugPropsText: String? = null
        val multipart = call.receiveMultipart()
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part is PartData.FileItem) {
                    val bytes = part.provider().toInputStream().use { it.readBytes() }
                    if (bytes.isNotEmpty()) when (part.name) {
                        "release" -> releaseBytes = bytes
                        "debug" -> debugBytes = bytes
                        "properties" -> propsText = bytes.toString(Charsets.UTF_8)
                        "debugProperties" -> debugPropsText = bytes.toString(Charsets.UTF_8)
                    }
                }
            } finally {
                part.dispose()
            }
        }
        // v1.102.0 — 서버는 staging 에만 규약 파일명으로 저장. 최종 이동배치는 콘솔 프롬프트가 수행.
        val stage = runCatching { keystore.stageUploaded(p.packageName, releaseBytes, debugBytes, propsText, debugPropsText) }
            .getOrElse { e ->
                log.warn(e) { "keystore staging failed: $id / ${p.packageName}" }
                call.respondRedirect("/projects/$id/keystore?err=${enc("업로드 실패: ${mapUploadError(e.message)}")}")
                return@post
            }
        if (!stage.stagedAny) {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("업로드된 파일이 없습니다 — release/debug/properties/debug properties 중 최소 하나를 선택하세요.")}",
            )
            return@post
        }
        // 콘솔에 이동배치(staging → keystores, 기존 백업) + build.gradle.kts 서명 적용 프롬프트 발사(한 turn).
        val prompt = buildKeystorePlacementPrompt(p.id, p.moduleName, p.packageName, keystore, stage)
        val providerName = selectedProviderName(id)
        val promptSent = runCatching { agentRouter.sendPrompt(id, prompt) }
            .onFailure { log.warn(it) { "post-upload placement prompt failed: $id" } }
            .isSuccess
        val staged = buildList {
            if (stage.releaseFile != null) add("release")
            if (stage.debugFile != null) add("debug")
            if (stage.propertiesFile != null) add("release properties")
            if (stage.debugPropertiesFile != null) add("debug properties")
        }.joinToString(", ")
        if (promptSent) {
            call.respondRedirect(
                "/projects/$id/keystore?ok=${enc("업로드 완료($staged) — $providerName 콘솔이 키스토어를 최종 위치로 이동배치하고 build.gradle.kts 서명을 적용합니다. 콘솔 탭에서 진행 상황을 확인하세요.")}",
            )
        } else {
            call.respondRedirect(
                "/projects/$id/keystore?err=${enc("파일은 staging 에 저장됐으나 콘솔 프롬프트 전송에 실패했습니다 — 콘솔이 유휴 상태인지 확인 후 다시 시도하세요.")}",
            )
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

/**
 * v1.94.0 / v1.98.2 — 같은 name 의 다중 input(getAll)을 리스트로. 콤마는 직렬화 구분자이므로
 * 입력 안에 콤마가 섞여도 split 으로 흡수(라운드트립 손상 방지) + trim + 빈값 제거 + 중복 제거.
 */
private fun multi(form: io.ktor.http.Parameters, name: String): List<String> =
    form.getAll(name).orEmpty()
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

/**
 * v1.105.0 — 저장된 AdMob 광고 ID 를 앱에 "공식문서 최신 권장 방식"으로 적용하고, 광고가
 * 하나라도 있으면 광고 제거 인앱결제(설정 화면)까지 함께 추가하도록 콘솔 provider 에 보내는 프롬프트.
 *
 * 광고 ID 원본은 `<pkg>-admob.properties` (단일 진실). 빌드 스크립트가 그 파일을 로드해 주입한다.
 * 버전/코드는 의도적으로 고정하지 않고 "공식문서 확인 후 최신 적용"을 지시한다(시간이 지나도 최신 유지).
 */
private fun buildApplyAdmobPrompt(
    projectId: String,
    moduleName: String,
    packageName: String,
    admobPropsPath: java.nio.file.Path,
    ids: AdmobIds,
): String {
    val appIdLine = if (ids.appId.isNotBlank()) ids.appId
        else "(없음 — 미설정/디버그 시 Google 공식 테스트 App ID 사용)"
    return """
[vibe-coder-server / AdMob 광고 + 광고제거 인앱결제 자동 적용]

프로젝트 `$projectId` 의 안드로이드 모듈 `$moduleName` 에 아래 AdMob 광고를 **공식문서 최신 권장
방식**으로 적용하고, 광고가 하나라도 적용되면 **광고 제거 인앱결제 기능도 함께** 추가해 주세요.

대상 패키지: $packageName
광고 ID 원본(단일 진실): `$admobPropsPath`
  (key=value 형식, 다중 unit ID 는 콤마(,) 구분 — 빌드 스크립트에서 split(",") 로 분리)

저장된 값 요약:
- App ID            : $appIdLine
- 배너               : ${ids.bannerUnitIds.size}개
- 앱 오프닝          : ${ids.appOpenUnitIds.size}개
- 네이티브           : ${ids.nativeUnitIds.size}개
- 전면(Interstitial) : ${ids.interstitialUnitIds.size}개
- 보상형             : ${ids.rewardedUnitIds.size}개
- 보상형 전면        : ${ids.rewardedInterstitialUnitIds.size}개

────────────────────────────────────────
■ 공통 원칙 (반드시 준수)
1. 최신화: 작업 전 **공식 문서를 직접 확인**해 라이브러리 최신 stable 버전 + 최신 권장 API/초기화로
   적용. 아래에 적는 코드/버전은 참고용이며, 공식문서가 더 최신이면 공식문서를 따르세요.
   Deprecated/구버전 API 사용 금지.
   - AdMob   : https://developers.google.com/admob/android/quick-start
   - 적응형 배너: https://developers.google.com/admob/android/banner/anchored-adaptive
   - Play Billing: https://developer.android.com/google/play/billing/integrate
2. 광고 ID 하드코딩 금지 — 반드시 위 properties 파일을 build.gradle.kts 에서 로드해 주입:
   ```kotlin
   import java.util.Properties
   import java.io.FileInputStream
   val admobPropsFile = file("$admobPropsPath")
   val admobProps = Properties().apply {
       if (admobPropsFile.exists()) FileInputStream(admobPropsFile).use { load(it) }
   }
   ```
   (groovy DSL 이면 동일 의미의 groovy 문법으로)
3. 디버그 빌드는 **Google 공식 테스트 광고 ID** 사용(실광고 정책 위반 방지). 릴리즈만 실제 ID.
4. GDPR/UMP 동의: 공식 User Messaging Platform(UMP) 최신 방식으로 동의 수집 후 광고 초기화.
5. 광고/결제 로직은 레이어 분리 — Activity/Composable 에 비즈니스 로직 직접 작성 지양(전역 관리).

────────────────────────────────────────
■ App ID
- defaultConfig.manifestPlaceholders 로 properties 의 admobAppId 를 주입(미설정 시 테스트 App ID),
  `AndroidManifest.xml` <application> 안 meta-data 에 연결:
  value 는 placeholder `${'$'}{admobAppId}` 사용, name 은 `com.google.android.gms.ads.APPLICATION_ID`.

────────────────────────────────────────
■ 하단 배너 — 적용 (적응형, 최하단 고정)
- 공식 권장 **적응형 배너(anchored adaptive banner)** 로 적용 — 사이즈는 공식문서의 현재 권장 API 로
  현재 창 너비 기준 계산(고정 320x50 금지).
- **디바이스 최하단에 고정** 배치. 화면 이동 시 재생성하지 말고 전역 1개 인스턴스로 관리.

────────────────────────────────────────
■ 전면(Interstitial) — 적용 (폴더블 주의)
- 최신 InterstitialAd API 로 미리 로드 후 적절한 시점에 표시(과도한 노출 금지).
- **폴더블/멀티윈도우에서 광고 사이즈 오류가 나지 않도록, 광고를 띄우기 직전에 현재 디바이스/창
  사이즈(접힘↔펼침/분할화면)를 확인**하고, 전환 직후 등 부적절하면 표시를 보류/재계산한 뒤 현재
  Activity/Window 컨텍스트로 show.

────────────────────────────────────────
■ 그 외 광고 (앱 오프닝 / 네이티브 / 보상형 / 보상형 전면)
- 배치 위치가 **애매하면 광고 ID 만 기록**(properties 로더 + BuildConfig 필드 주입까지만)하고,
  실제 앱 내 노출 코드는 작성하지 마세요 — 사용자가 직접 배치합니다.
- 배치가 명확한 경우에 한해 공식 권장 방식으로 적용해도 됩니다.

────────────────────────────────────────
■ 광고 제거 인앱결제 (필수 — 설정 화면)
- 광고를 하나라도 적용하면 반드시 함께 추가.
- Google Play Billing 라이브러리를 **공식문서 확인 후 최신 stable 버전**으로 추가하고, 최신 권장
  결제 흐름(BillingClient)으로 구현.
- 상품: **비소모성(one-time) "광고 제거"**. **상품 ID(productId/SKU)는 반드시 `remove_ads` 로 통일**
  (Play Console 인앱 상품 등록 및 코드 조회 모두 동일 문자열 사용). 구매 완료 시 모든 광고(배너/전면/기타) 즉시 제거.
- 앱 시작 시 구매 상태 자동 복원(queryPurchasesAsync). 신뢰 기준은 Play 결제 상태(로컬은 캐시).
- 진입점: **앱 설정(Settings) 화면**에 "광고 제거" 항목 추가. 설정 화면이 이미 있으면 거기에 추가하고,
  없으면 임의로 만들지 말고 가장 적합한 위치를 사용자에게 확인하세요.

────────────────────────────────────────
■ 규칙/제약
- 변경은 안드로이드 모듈 `$moduleName` 범위로 한정. 어떤 파일에 무엇을 추가/변경했는지 diff 로 요약.
- 이미 동일하게 적용돼 있으면 해당 항목은 "변경 없음" 으로 회신.
- 빌드는 자동 실행하지 마세요 — 적용 후 사용자가 직접 assembleDebug/assembleRelease 실행 예정.
- 먼저 변경 계획을 짧게 제시한 뒤 진행하세요.
""".trim()
}

/** v1.94.0 — App ID(단일) + 6종 유형별 다중 unit ID 수집. blank 여부는 호출측에서 판단. */
private fun admobFromForm(form: io.ktor.http.Parameters): AdmobIds =
    AdmobIds(
        appId = form["admobAppId"]?.trim().orEmpty(),
        appOpenUnitIds = multi(form, "admobAppOpenUnitId"),
        bannerUnitIds = multi(form, "admobBannerUnitId"),
        nativeUnitIds = multi(form, "admobNativeUnitId"),
        interstitialUnitIds = multi(form, "admobInterstitialUnitId"),
        rewardedUnitIds = multi(form, "admobRewardedUnitId"),
        rewardedInterstitialUnitIds = multi(form, "admobRewardedInterstitialUnitId"),
    )

/** v1.101.0 — saveUploaded 가 던지는 IllegalArgumentException 메시지를 사용자 안내로. */
private fun mapUploadError(msg: String?): String = when {
    msg == null -> "알 수 없는 오류"
    msg.startsWith("no_files") -> "선택된 파일이 없습니다"
    msg.startsWith("invalid_package_name") -> "패키지명이 올바르지 않습니다"
    msg.startsWith("not_a_keystore") -> "키스토어 형식이 아닙니다 (PKCS12/JKS 필요): ${msg.substringAfter(": ", "")}"
    msg.startsWith("empty_keystore") -> "빈 키스토어 파일입니다"
    msg.startsWith("keystore_too_large") -> "키스토어 파일이 너무 큽니다 (256KB 초과)"
    msg.startsWith("invalid_properties") -> "properties 에 storePassword / keyAlias 가 없습니다"
    else -> msg
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
        consoleIdle: Boolean = true,
        ok: String?,
        err: String?,
        csrf: String?,
        lang: String,
        embed: Boolean = false,
    ): String {
        val t = { k: String -> Messages.t(lang, k) }
        val okHtml = ok?.let { """<div class="ok-banner">✓ ${esc(it)}</div>""" } ?: ""
        val errHtml = err?.let { """<div class="error">⚠ ${esc(it)}</div>""" } ?: ""

        // v1.94.0 — 광고 유형 1개 블록 (라벨 + 다중 unit 행 + "단위 추가" 버튼).
        // 같은 name 의 input 들을 form.getAll(name) 으로 서버에서 수집한다.
        fun admobRow(name: String, value: String): String = """
        <div class="admob-row" style="display:flex;gap:6px;margin-bottom:6px">
          <input name="${esc(name)}" value="${esc(value)}" placeholder="ca-app-pub-XXXX/YYYY"
                 style="flex:1;min-width:0;padding:8px;font-family:ui-monospace,Menlo,monospace">
          <button type="button" class="admob-del" title="${esc(t("ks.admob.remove"))}"
                  style="flex:0 0 auto;padding:0 12px;background:#3a1212;color:#f88;border:0;border-radius:6px;cursor:pointer">✕</button>
        </div>"""
        fun admobType(labelKey: String, name: String, values: List<String>): String {
            val rows = values.ifEmpty { listOf("") }.joinToString("\n") { admobRow(name, it) }
            return """
      <div class="admob-type" style="grid-column:1/3">
        <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t(labelKey))}
          <span class="dim" style="font-size:11px;font-weight:400">${esc(t("ks.admob.unitsLabel"))}</span></div>
        <div class="admob-rows">$rows</div>
        <button type="button" class="admob-add"
                style="margin-top:2px;padding:4px 10px;font-size:12px;background:#1f2937;color:#cbd5e1;border:0;border-radius:6px;cursor:pointer">${esc(t("ks.admob.add"))}</button>
      </div>"""
        }
        // 6종 유형을 사용자 친숙 순서(배너 → 앱오프닝 → 네이티브 → 전면 → 보상형 → 보상형전면)로.
        val admobTypesHtml =
            admobType("ks.admob.banner", "admobBannerUnitId", admob.bannerUnitIds) +
                admobType("ks.admob.appOpen", "admobAppOpenUnitId", admob.appOpenUnitIds) +
                admobType("ks.admob.native", "admobNativeUnitId", admob.nativeUnitIds) +
                admobType("ks.admob.interstitial", "admobInterstitialUnitId", admob.interstitialUnitIds) +
                admobType("ks.admob.rewarded", "admobRewardedUnitId", admob.rewardedUnitIds) +
                admobType("ks.admob.rewardedInterstitial", "admobRewardedInterstitialUnitId", admob.rewardedInterstitialUnitIds)
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
        // v1.105.0 — 저장 시 콘솔에 "앱 적용(광고+광고제거 구매)" 프롬프트를 발사하므로
        // 콘솔이 유휴일 때만 저장 가능. busy 면 저장 버튼 비활성 + 안내.
        val admobOpen = if (hasAdmob(admob)) "open" else ""
        val admobDisabled = if (consoleIdle) "" else "disabled"
        // "적용 프롬프트만 보내기" 버튼: 콘솔 유휴 + 이미 저장된 광고 ID 가 있을 때만 활성.
        val admobApplyDisabled = if (consoleIdle && hasAdmob(admob)) "" else "disabled"
        val admobApplyTitle = when {
            !consoleIdle -> "콘솔이 작업 중입니다"
            !hasAdmob(admob) -> "저장된 광고 ID 가 없습니다 — 먼저 저장하세요"
            else -> "저장된 광고 ID 로 앱 적용 프롬프트를 콘솔에 보냅니다"
        }
        val admobBusyNote = if (consoleIdle) "" else
            """<div class="error" style="margin:0 0 10px">⚠ 콘솔이 작업 중입니다 — 유휴 상태가 되면 저장(앱 적용)할 수 있습니다.</div>"""
        val admobCard = """
<details class="card" $admobOpen style="margin-bottom:14px">
  <summary style="cursor:pointer;font-weight:600">${esc(t("ks.admob.cardTitle"))}</summary>
  <p class="dim" style="font-size:12px;margin:8px 0 10px">
    ${esc(t("ks.admob.cardIntro"))}<br>
    ${esc(t("ks.admob.savedAt"))}: <code>${esc(pkg)}-admob.properties</code><br>
    💡 저장하면 광고 ID 기록과 함께, 앱에 광고(적응형 하단 배너·전면)와 <b>광고 제거 인앱결제</b>까지
    공식문서 최신 방식으로 적용하도록 현재 콘솔 provider 에 요청합니다 (콘솔 유휴 시).
  </p>
  $admobBusyNote
  <form id="admob-apply-form" method="post" action="/projects/${esc(p.id)}/keystore/admob/apply" style="display:none">$csrfHidden</form>
  <form method="post" action="/projects/${esc(p.id)}/keystore/admob" style="display:grid;grid-template-columns:1fr 1fr;gap:10px">
    $csrfHidden
    <label style="grid-column:1/3">
      <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appId"))}</div>
      <input name="admobAppId" value="${esc(admob.appId)}" placeholder="ca-app-pub-XXXX~YYYY"
             style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
    </label>
    $admobTypesHtml
    <p class="dim" style="grid-column:1/3;font-size:11px;margin:2px 0 0">${esc(t("ks.admob.multiHint"))}</p>
    <div style="grid-column:1/3;display:flex;align-items:center;gap:10px;flex-wrap:wrap">
      <button type="submit" form="admob-apply-form" $admobApplyDisabled
              title="${esc(admobApplyTitle)}"
              style="background:#1f2937;color:#cbd5e1;border:0;padding:8px 14px;border-radius:6px;cursor:pointer"
              onclick="return confirm('저장된 AdMob 광고 ID 로 앱 적용(광고 + 광고제거 구매) 프롬프트를 현재 선택된 콘솔 provider 에 보냅니다. 진행할까요?')">↗ 광고 적용 프롬프트 보내기</button>
      <button type="submit" class="primary" $admobDisabled>${esc(t("ks.admob.save"))}</button>
      <span class="dim" style="font-size:11px">저장 시에도 적용 프롬프트가 전송됩니다.</span>
    </div>
  </form>
</details>
<script>
(function () {
  function rowsOf(el) { var t = el.closest('.admob-type'); return t ? t.querySelector('.admob-rows') : null; }
  document.addEventListener('click', function (e) {
    var add = e.target.closest('.admob-add');
    if (add) {
      e.preventDefault();
      var wrap = rowsOf(add); if (!wrap) return;
      var last = wrap.querySelector('.admob-row:last-child'); if (!last) return;
      var clone = last.cloneNode(true);
      var inp = clone.querySelector('input'); if (inp) inp.value = '';
      wrap.appendChild(clone);
      if (inp) inp.focus();
      return;
    }
    var del = e.target.closest('.admob-del');
    if (del) {
      e.preventDefault();
      var row = del.closest('.admob-row'); if (!row) return;
      var wrap = row.parentElement;
      if (wrap.querySelectorAll('.admob-row').length > 1) { row.remove(); }
      else { var inp = row.querySelector('input'); if (inp) inp.value = ''; }
      return;
    }
  });
})();
</script>"""

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
      <summary style="cursor:pointer;font-size:13px;color:#aaa">${esc(t("ks.admob.toggle"))}</summary>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px">
        <label style="grid-column:1/3">
          <div style="font-size:12px;color:#aaa;margin-bottom:4px">${esc(t("ks.admob.appId"))}</div>
          <input name="admobAppId" value="${esc(admob.appId)}" placeholder="ca-app-pub-XXXX~YYYY"
                 style="width:100%;padding:8px;font-family:ui-monospace,Menlo,monospace">
        </label>
        $admobTypesHtml
        <p class="dim" style="grid-column:1/3;font-size:11px;margin:2px 0 0">${esc(t("ks.admob.multiHint"))}</p>
      </div>
    </details>
    <p class="dim" style="font-size:12px;margin:12px 0 8px">⚠ 키스토어는 한 번 잃으면 같은 키로 앱 업데이트가 불가합니다 — 호스트 keystores 볼륨을 백업하세요.</p>
    <button type="submit" class="primary">키스토어 생성</button>
  </form>
</details>"""

        // 서명 적용 + 삭제 (존재 시) ------------------------------------------------
        // v1.114.1 — 서명 적용은 콘솔 프롬프트를 쏘므로 유휴일 때만 활성(admob/apply·upload 와 동일).
        val applySigningDisabled = if (consoleIdle) "" else "disabled"
        val applySigningNote = if (consoleIdle) "" else
            """<small class="dim" style="display:block;margin-top:4px">⚠ 콘솔이 작업 중입니다 — 유휴 상태가 되면 서명 적용할 수 있습니다.</small>"""
        val actionsCard = if (!exists) "" else """
<div class="card" style="margin-bottom:14px">
  <h3 style="margin:0 0 8px;font-size:14px">빌드 적용 / 관리</h3>
  <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
    <form method="post" action="/projects/${esc(p.id)}/keystore/apply" style="margin:0"
          onsubmit="return confirm('이 키스토어로 build.gradle.kts 의 signingConfigs 를 수정하도록 현재 선택된 콘솔 provider 에 요청합니다. 진행할까요?')">
      $csrfHidden
      <button type="submit" class="primary" $applySigningDisabled>build.gradle.kts 서명 적용</button>
    </form>
    <form method="post" action="/projects/${esc(p.id)}/keystore/delete" style="margin:0"
          onsubmit="return confirm('키스토어 set(release/debug/properties/admob) 을 삭제합니다. 복구 불가 — 진행할까요?')">
      $csrfHidden
      <button type="submit" style="background:#7f1d1d;color:#fff;border:0;padding:8px 14px;border-radius:6px;cursor:pointer">키스토어 삭제</button>
    </form>
  </div>
  $applySigningNote
</div>"""

        // 업로드 (외부 키스토어 가져오기) — 항상 표시, 콘솔 유휴일 때만 활성 ----------
        val uploadDisabled = if (consoleIdle) "" else "disabled"
        val uploadBusyNote = if (consoleIdle) "" else
            """<div class="error">⚠ 콘솔이 작업 중입니다 — 유휴 상태가 되면 업로드할 수 있습니다.</div>"""
        val uploadCard = """
<details class="card" style="margin-bottom:14px">
  <summary style="cursor:pointer;font-weight:600">⬆ 키스토어 업로드
    <span class="dim" style="font-weight:400;font-size:12px">(외부에서 만든 키 가져오기 — 콘솔 유휴 시)</span></summary>
  <p class="dim" style="font-size:12px;margin:8px 0 10px">
    이미 가지고 있는 release / debug 키스토어와 release / debug signing properties 파일을 각 칸에 올립니다. 서버는
    파일을 임시(staging) 공간에 규약 파일명(<code>${esc(pkg)}.keystore</code> /
    <code>${esc(pkg)}-debug.keystore</code> / <code>${esc(pkg)}-keystore.properties</code> /
    <code>${esc(pkg)}-debug-keystore.properties</code>)으로 저장한 뒤,
    <b>한 번의 콘솔 프롬프트</b>로 현재 선택된 provider 가 최종 위치(<code>/home/vibe/keystores/</code>)로 이동배치
    (기존 파일은 <code>.bak.&lt;시각&gt;</code> 백업)하고 build.gradle.kts 서명까지 적용합니다.
    properties 의 storeFile 은 서버 경로로 자동 보정됩니다. 콘솔 프롬프트를 전송하므로
    <b>콘솔이 유휴 상태일 때만</b> 가능합니다.
  </p>
  $uploadBusyNote
  <form method="post" action="/projects/${esc(p.id)}/keystore/upload?_csrf=${enc(csrf ?: "")}" enctype="multipart/form-data">
    <div style="display:grid;grid-template-columns:auto 1fr;gap:10px 12px;align-items:center;font-size:13px;max-width:520px">
      <label for="ks-up-release">release 키스토어</label>
      <input id="ks-up-release" type="file" name="release" accept=".keystore,.jks,.p12,.pkcs12" $uploadDisabled>
      <label for="ks-up-debug">debug 키스토어</label>
      <input id="ks-up-debug" type="file" name="debug" accept=".keystore,.jks,.p12,.pkcs12" $uploadDisabled>
      <label for="ks-up-props">release properties</label>
      <input id="ks-up-props" type="file" name="properties" accept=".properties,.txt" $uploadDisabled>
      <label for="ks-up-debug-props">debug properties</label>
      <input id="ks-up-debug-props" type="file" name="debugProperties" accept=".properties,.txt" $uploadDisabled>
    </div>
    <p class="dim" style="font-size:11px;margin:8px 0">최소 1개 파일을 선택하세요. PKCS12/JKS 키스토어만 허용(256KB 이하).</p>
    <button type="submit" class="primary" $uploadDisabled
            onclick="return confirm('업로드 파일을 staging 에 저장하고, 현재 선택된 콘솔 provider 가 최종 위치로 이동배치(기존 백업) + build.gradle.kts 서명 적용하도록 프롬프트를 보냅니다. 진행할까요?')">업로드 → 콘솔 이동배치</button>
  </form>
</details>"""

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
$uploadCard
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

    private fun hasAdmob(a: AdmobIds): Boolean = !a.isBlank
}
