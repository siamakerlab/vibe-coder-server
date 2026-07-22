package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.AdminRoutesDeps
import com.siamakerlab.vibecoder.server.admin.isEmbeddedRequest
import com.siamakerlab.vibecoder.server.admin.requireProjectAccessOrThrow
import com.siamakerlab.vibecoder.server.admin.requireSessionOrRedirect
import com.siamakerlab.vibecoder.server.admin.requireWriteAccessOrRedirect
import com.siamakerlab.vibecoder.server.auth.CsrfTokens
import com.siamakerlab.vibecoder.server.auth.CsrfTokens.requireCsrf
import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptSender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val log = KotlinLogging.logger {}

private const val MAX_ICON_BYTES = 8 * 1024 * 1024      // 8MB
private const val MAX_GRAPHIC_BYTES = 8 * 1024 * 1024   // 8MB
private const val MAX_SHOT_BYTES = 12 * 1024 * 1024     // 12MB each
private const val MAX_SHOTS = 50

private val LANG_RE = Regex("""^[a-z]{2}(-[A-Z]{2})?$""")
private val SHOT_RE = Regex("""^screenshot-[a-z]{2}(-[A-Z]{2})?-\d{1,3}\.png$""")
private val ASSET_RAW_RE = Regex("""^(icon\.png|graphic\.png|screenshot-[a-z]{2}(-[A-Z]{2})?-\d{1,3}\.png)$""")

/**
 * v1.65.0 — 프로젝트 "스토어 자산" 탭 (`/projects/{id}/assets`).
 *
 *  - 앱 아이콘: 업로드 → 프로젝트 루트 `icon.png`. 다이얼로그로 컨펌 시 현재 콘솔 provider 에
 *    "런처 아이콘 적용" prompt 전송(자동 변경). 취소 시 icon.png 복사만.
 *  - 피처 그래픽: 업로드 → 루트 `graphic.png` (1024×500 권장).
 *  - 스크린샷: 업로드 → 루트 `screenshot-<lang>-<n>.png` (언어별 자동 증가).
 *
 * 모든 쓰기는 [WorkspacePath.projectRoot] 하위 + 고정/검증된 파일명만. multipart
 * CSRF 는 part 의 `_csrf` 로 검증.
 */
fun Routing.projectAssetsRoutes(
    authDeps: AdminRoutesDeps,
    projects: ProjectService,
    workspace: WorkspacePath,
    agentRouter: AgentRouter,
    promptSender: ConsolePromptSender,
    /** v1.66.0 — Play Console 업로드(MCP google-play-publisher 위임). */
    playPublishService: com.siamakerlab.vibecoder.server.publish.PlayPublishService,
) {
    get("/projects/{id}/assets") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = runCatching { projects.get(id) }.getOrElse {
            call.respondRedirect("/projects?err=${enc("프로젝트 '$id' 를 찾을 수 없습니다.")}")
            return@get
        }
        val root = workspace.projectRoot(id)
        val hasIcon = Files.isRegularFile(root.resolve("icon.png"))
        val hasGraphic = Files.isRegularFile(root.resolve("graphic.png"))
        val shots = listScreenshots(root)
        // v1.66.0 — Play 업로드 precheck (MCP 설치/등록 여부).
        val play = runCatching { playPublishService.precheck(p.packageName) }.getOrNull()
        call.respondText(
            AssetsTemplates.page(
                username = sess.username, projectId = id, projectName = p.name,
                hasIcon = hasIcon, hasGraphic = hasGraphic, screenshots = shots,
                playReady = play?.ready ?: false,
                playStatus = play?.mcpStatus,
                playWarnings = play?.warnings.orEmpty(),
                flash = call.request.queryParameters["flash"],
                csrf = sess.csrf, lang = sess.language,
                embed = call.isEmbeddedRequest(),
            ),
            ContentType.Text.Html,
        )
    }

    // ── Play Console 업로드 (MCP 설치 시) ──────────────────────────────────
    post("/projects/{id}/assets/play-upload") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val form = requireCsrf()
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val p = projects.get(id)
        val pre = runCatching { playPublishService.precheck(p.packageName) }.getOrNull()
        if (pre?.ready != true) {
            call.respondRedirect("/projects/$id/assets?flash=err:playNotReady"); return@post
        }
        val track = form["track"]?.trim().orEmpty().ifBlank { "internal" }
        val notes = form["notes"]?.trim()
        val includeListing = form["includeListing"]?.equals("true", true) == true ||
            form["includeListing"]?.equals("on", true) == true
        val root = workspace.projectRoot(id)
        val sent = runCatching {
            playPublishService.triggerStoreUpload(
                projectId = id, track = track, releaseNotes = notes,
                includeListing = includeListing,
                hasGraphic = Files.isRegularFile(root.resolve("graphic.png")),
                screenshotsByLang = screenshotsByLang(root),
            )
        }.onFailure { log.warn(it) { "play upload trigger failed: $id" } }.isSuccess
        call.respondRedirect("/projects/$id/assets?flash=${if (sent) "play" else "err:agent"}")
    }

    // 미리보기 serve — 루트의 허용된 asset 파일만.
    get("/projects/{id}/assets/raw/{name}") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@get
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val name = call.parameters["name"].orEmpty()
        if (!ASSET_RAW_RE.matches(name)) { call.respond(HttpStatusCode.BadRequest); return@get }
        val f = workspace.projectRoot(id).resolve(name)
        if (!Files.isRegularFile(f)) { call.respond(HttpStatusCode.NotFound); return@get }
        val safe = runCatching { workspace.ensureUnderWorkspace(f.toRealPath()) }.getOrNull()
            ?: run { call.respond(HttpStatusCode.NotFound); return@get }
        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
        call.respondBytes(Files.readAllBytes(safe), ContentType.Image.PNG)
    }

    // ── 앱 아이콘 업로드 (+ 선택적 콘솔 provider 적용) ─────────────────────────
    post("/projects/{id}/assets/icon") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parsed = parseMultipart(call.receiveMultipart(), MAX_ICON_BYTES)
        if (!CsrfTokens.isValidCsrf(call, parsed.csrf)) {
            call.respondRedirect("/projects/$id/assets?flash=err:csrf"); return@post
        }
        val bytes = parsed.firstFile() ?: run {
            call.respondRedirect("/projects/$id/assets?flash=err:nofile"); return@post
        }
        val root = workspace.projectRoot(id)
        runCatching { writeAtomic(root, "icon.png", bytes) }.onFailure {
            log.warn(it) { "icon write failed: $id" }
            call.respondRedirect("/projects/$id/assets?flash=err:write"); return@post
        }
        log.info { "app icon uploaded: $id (${bytes.size}B) by ${sess.username}" }
        if (parsed.apply) {
            val p = projects.get(id)
            val prompt = buildApplyIconPrompt(id, p.moduleName, root.resolve("icon.png"))
            val sent = runCatching { promptSender.send(id, prompt, source = "project_assets_apply_icon", ownerUserId = sess.userId) }
                .onFailure { e -> log.warn(e) { "apply-icon prompt failed: $id" } }.isSuccess
            call.respondRedirect("/projects/$id/assets?flash=${if (sent) "applied" else "err:agent"}")
        } else {
            call.respondRedirect("/projects/$id/assets?flash=copied")
        }
    }

    // ── 피처 그래픽 업로드 ────────────────────────────────────────────────
    post("/projects/{id}/assets/graphic") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parsed = parseMultipart(call.receiveMultipart(), MAX_GRAPHIC_BYTES)
        if (!CsrfTokens.isValidCsrf(call, parsed.csrf)) {
            call.respondRedirect("/projects/$id/assets?flash=err:csrf"); return@post
        }
        val bytes = parsed.firstFile() ?: run {
            call.respondRedirect("/projects/$id/assets?flash=err:nofile"); return@post
        }
        runCatching { writeAtomic(workspace.projectRoot(id), "graphic.png", bytes) }.onFailure {
            log.warn(it) { "graphic write failed: $id" }
            call.respondRedirect("/projects/$id/assets?flash=err:write"); return@post
        }
        log.info { "feature graphic uploaded: $id (${bytes.size}B) by ${sess.username}" }
        call.respondRedirect("/projects/$id/assets?flash=graphic")
    }

    // ── 스크린샷 업로드 (lang 별 자동 증가) ──────────────────────────────────
    post("/projects/{id}/assets/screenshot") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        val parsed = parseMultipart(call.receiveMultipart(), MAX_SHOT_BYTES)
        if (!CsrfTokens.isValidCsrf(call, parsed.csrf)) {
            call.respondRedirect("/projects/$id/assets?flash=err:csrf"); return@post
        }
        val lang = parsed.lang.takeIf { LANG_RE.matches(it) } ?: "en"
        if (parsed.files.isEmpty()) {
            call.respondRedirect("/projects/$id/assets?flash=err:nofile"); return@post
        }
        val root = workspace.projectRoot(id)
        var n = nextScreenshotIndex(root, lang)
        var saved = 0
        for (bytes in parsed.files) {
            if (listScreenshots(root).size + saved >= MAX_SHOTS) break
            runCatching { writeAtomic(root, "screenshot-$lang-$n.png", bytes) }
                .onSuccess { saved++; n++ }
                .onFailure { log.warn(it) { "screenshot write failed: $id" } }
        }
        log.info { "screenshots uploaded: $id lang=$lang count=$saved by ${sess.username}" }
        call.respondRedirect("/projects/$id/assets?flash=shots:$saved")
    }

    post("/projects/{id}/assets/screenshot/{name}/delete") {
        val sess = requireSessionOrRedirect(authDeps) ?: return@post
        if (!requireWriteAccessOrRedirect(sess)) return@post
        val id = call.parameters["id"]!!
        requireProjectAccessOrThrow(sess, projects, id)
        // form 으로 _csrf.
        val form = call.receiveParameters()
        if (!CsrfTokens.isValidCsrf(call, form["_csrf"])) {
            call.respondRedirect("/projects/$id/assets?flash=err:csrf"); return@post
        }
        val name = call.parameters["name"].orEmpty()
        if (!SHOT_RE.matches(name)) { call.respondRedirect("/projects/$id/assets?flash=err:badname"); return@post }
        runCatching { Files.deleteIfExists(workspace.projectRoot(id).resolve(name)) }
        call.respondRedirect("/projects/$id/assets?flash=shotDeleted")
    }
}

private fun listScreenshots(root: Path): List<String> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.list(root).use { s ->
        s.map { it.fileName.toString() }.filter { SHOT_RE.matches(it) }.sorted().toList()
    }
}

private val SHOT_LANG_RE = Regex("""^screenshot-([a-z]{2}(?:-[A-Z]{2})?)-\d{1,3}\.png$""")

/** v1.66.0 — 스크린샷을 언어별로 그룹. Play listing prompt 에 사용. */
private fun screenshotsByLang(root: Path): Map<String, List<String>> {
    return listScreenshots(root)
        .mapNotNull { name -> SHOT_LANG_RE.find(name)?.groupValues?.get(1)?.let { it to name } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.sorted() }
}

private fun nextScreenshotIndex(root: Path, lang: String): Int {
    if (!Files.isDirectory(root)) return 1
    val re = Regex("""^screenshot-${Regex.escape(lang)}-(\d{1,3})\.png$""")
    val max = Files.list(root).use { s ->
        s.map { it.fileName.toString() }
            .map { re.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .filter { it != null }.map { it!! }.toList()
    }.maxOrNull() ?: 0
    return max + 1
}

private fun writeAtomic(root: Path, name: String, bytes: ByteArray) {
    Files.createDirectories(root)
    val target = root.resolve(name)
    val tmp = root.resolve("$name.tmp")
    Files.write(tmp, bytes)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
}

private class Multipart(
    val csrf: String?,
    val apply: Boolean,
    val lang: String,
    val files: List<ByteArray>,
) {
    fun firstFile(): ByteArray? = files.firstOrNull()
}

private suspend fun parseMultipart(multipart: io.ktor.http.content.MultiPartData, maxBytes: Int): Multipart {
    var csrf: String? = null
    var apply = false
    var lang = "en"
    val files = mutableListOf<ByteArray>()
    while (true) {
        val part = multipart.readPart() ?: break
        try {
            when (part) {
                is PartData.FormItem -> when (part.name) {
                    "_csrf" -> csrf = part.value
                    "apply" -> apply = part.value.equals("true", ignoreCase = true)
                    "lang" -> lang = part.value
                    else -> {}
                }
                is PartData.FileItem -> {
                    val bytes = part.provider().toInputStream().use { it.readBytes() }
                    if (bytes.isNotEmpty() && bytes.size <= maxBytes) files.add(bytes)
                }
                else -> {}
            }
        } finally {
            part.dispose()
        }
    }
    return Multipart(csrf, apply, lang, files)
}

/**
 * v1.65.0 — 현재 콘솔 provider 에 보낼 "런처 아이콘 적용" 정형 prompt. 비밀값 없음.
 */
private fun buildApplyIconPrompt(projectId: String, moduleName: String, iconPath: Path): String = """
[vibe-coder-server / 앱 아이콘 적용]

프로젝트 `$projectId` 루트에 새 앱 아이콘 원본을 `icon.png` 로 업로드했습니다.
이 이미지를 안드로이드 앱의 런처 아이콘으로 적용해 주세요.

- 모듈: `$moduleName`
- 아이콘 원본: `$iconPath`

절차:
1. `icon.png` 를 각 density(mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi)로 리사이즈하여
   `$moduleName/src/main/res/mipmap-<density>/ic_launcher.png` (및 `ic_launcher_round.png`)
   로 배치. ImageMagick(`convert`/`magick`) 또는 가능한 도구 활용.
2. 프로젝트가 adaptive icon(`mipmap-anydpi-v26/ic_launcher.xml`)을 쓰면, foreground
   레이어를 새 아이콘으로 교체하거나 PNG 아이콘으로 단순화. 배경/foreground 분리가
   어려우면 일반 PNG 런처 아이콘으로 대체.
3. `AndroidManifest.xml` 의 `android:icon`(및 `android:roundIcon`)이 `@mipmap/ic_launcher`
   를 가리키는지 확인.
4. 변경된 파일을 diff/요약으로 알려 주세요. **빌드는 자동 실행하지 마세요.**

가능한 한 한 번에 적용하고, 도구가 없으면 설치 없이 가능한 방법으로 진행해 주세요.
""".trim()

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
