package com.siamakerlab.vibecoder.server.files

import com.siamakerlab.vibecoder.server.core.PathSafety
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * 프로젝트 소스 트리의 read-only 디렉토리 listing + 가벼운 텍스트 파일 view/edit.
 * v0.13.0.
 *
 * 보안:
 *  - 모든 path 는 [PathSafety.normalizeAndCheck] 로 traversal 차단.
 *  - 심볼릭 링크는 따라가지 않고 [LinkOption.NOFOLLOW_LINKS] — 외부 escape 방지.
 *  - 텍스트 파일만 view/edit. 이진/큰 파일은 차단.
 *
 * NOTE: 신택스 하이라이트는 후속 사이클. 본 cycle 은 plain `<pre>` + `<textarea>`.
 */
class ProjectFileBrowser(
    private val workspace: WorkspacePath,
) {

    data class Entry(
        val name: String,
        val relPath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedAt: String,
    )

    data class FileView(
        val relPath: String,
        val sizeBytes: Long,
        val content: String,
        val truncated: Boolean,
        val mimeGuess: String,
    )

    /**
     * 프로젝트 폴더 내부의 디렉토리 listing.
     * @param subPath 프로젝트 root 기준 상대 경로 (빈 문자열이면 root).
     */
    fun list(projectId: String, subPath: String): List<Entry> {
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw ApiException.localized(404, "project_root_not_found", messageKey = "api.fileBrowser.projectRootNotFound")
        }
        val target = if (subPath.isBlank()) projectRoot else PathSafety.normalizeAndCheck(projectRoot, subPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(subPath))
        }
        if (!target.isDirectory()) {
            throw ApiException.localized(400, "not_a_directory", messageKey = "api.fileBrowser.notADirectory", args = listOf(subPath))
        }
        return Files.list(target).use { stream ->
            stream
                .filter { skipHidden(it.fileName.toString()).not() }
                .map { p ->
                    val attrs = runCatching {
                        Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    }.getOrNull()
                    Entry(
                        name = p.fileName.toString(),
                        relPath = projectRoot.relativize(p).toString().replace('\\', '/'),
                        isDirectory = attrs?.isDirectory ?: false,
                        sizeBytes = attrs?.size() ?: 0L,
                        modifiedAt = attrs?.lastModifiedTime()?.toString() ?: "-",
                    )
                }
                .toList()
                .sortedWith(compareByDescending<Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    /**
     * 텍스트 파일 read. 너무 큰 파일 / 이진 파일은 차단.
     */
    fun read(projectId: String, relPath: String): FileView {
        val projectRoot = workspace.projectRoot(projectId)
        if (relPath.isBlank()) {
            throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "file_not_found", messageKey = "api.fileBrowser.fileNotFound", args = listOf(relPath))
        }
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedView")
        }
        if (!target.isRegularFile()) {
            throw ApiException.localized(400, "not_a_file", messageKey = "api.fileBrowser.notAFile")
        }
        val size = Files.size(target)
        if (size > MAX_VIEW_BYTES) {
            throw ApiException.localized(413, "file_too_large",
                messageKey = "api.fileBrowser.fileTooLarge", args = listOf(size, MAX_VIEW_BYTES))
        }
        val bytes = Files.readAllBytes(target)
        if (looksBinary(bytes)) {
            throw ApiException.localized(415, "binary_file", messageKey = "api.fileBrowser.binaryFile")
        }
        val content = String(bytes, Charsets.UTF_8)
        val mime = guessMime(relPath)
        return FileView(relPath, size, content, truncated = false, mimeGuess = mime)
    }

    /**
     * 텍스트 파일 write. 상위 디렉토리는 반드시 사전 존재해야 함 (UI 에서 신규 파일 생성은
     * 별도 endpoint 로 분리 — 본 cycle 미구현).
     */
    fun write(projectId: String, relPath: String, content: String) {
        val projectRoot = workspace.projectRoot(projectId)
        if (relPath.isBlank()) {
            throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        }
        if (content.length > MAX_VIEW_BYTES) {
            throw ApiException.localized(413, "content_too_large",
                messageKey = "api.fileBrowser.contentTooLarge", args = listOf(content.length))
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.isRegularFile()) {
            throw ApiException.localized(400, "not_a_file", messageKey = "api.fileBrowser.notAFile")
        }
        // 상위 디렉토리는 존재해야 함 (신규 디렉토리 생성은 본 endpoint 가 안 함)
        val parent = target.parent ?: throw ApiException.localized(400, "bad_path", messageKey = "api.fileBrowser.badPath")
        if (!Files.exists(parent)) {
            throw ApiException.localized(400, "parent_missing", messageKey = "api.fileBrowser.parentMissing", args = listOf(parent.toString()))
        }
        // atomic-ish write: tmp → move
        val tmp = target.resolveSibling("${target.fileName}.editing.tmp")
        Files.writeString(tmp, content, Charsets.UTF_8)
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        log.info { "file edited: $projectId :: $relPath (${content.length} chars)" }
    }

    /**
     * v1.14.0 — 신규 폴더 생성. 부모 디렉토리 존재해야 함. 동명 entry 있으면 거부.
     */
    fun mkdir(projectId: String, relPath: String) {
        val (projectRoot, target) = safeWriteTarget(projectId, relPath)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(relPath))
        }
        val parent = target.parent ?: throw ApiException.localized(400, "bad_path", messageKey = "api.fileBrowser.badPath")
        if (!Files.exists(parent) || !parent.isDirectory()) {
            throw ApiException.localized(400, "parent_missing",
                messageKey = "api.fileBrowser.parentMissing", args = listOf(parent.toString()))
        }
        Files.createDirectories(target)
        log.info { "mkdir: $projectId :: $relPath" }
    }

    /**
     * v1.14.0 — 신규 빈 텍스트 파일 생성. 동명 entry 있으면 거부. 사용자가 그 후
     * /view 에서 편집.
     */
    fun createFile(projectId: String, relPath: String) {
        val (projectRoot, target) = safeWriteTarget(projectId, relPath)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(relPath))
        }
        val parent = target.parent ?: throw ApiException.localized(400, "bad_path", messageKey = "api.fileBrowser.badPath")
        if (!Files.exists(parent) || !parent.isDirectory()) {
            throw ApiException.localized(400, "parent_missing",
                messageKey = "api.fileBrowser.parentMissing", args = listOf(parent.toString()))
        }
        Files.createFile(target)
        log.info { "createFile: $projectId :: $relPath" }
    }

    /**
     * v1.14.0 — 파일 또는 디렉토리 rename. 같은 부모 디렉토리 안에서만 — name 변경.
     * 다른 디렉토리로의 이동은 본 메서드 범위 아님 (사용자가 명시적 move 요청 시 별도
     * endpoint 추가). 동명 entry 있으면 거부.
     */
    fun rename(projectId: String, relPath: String, newName: String) {
        if (newName.isBlank()) throw ApiException.localized(400, "empty_name", messageKey = "api.fileBrowser.emptyName")
        if (newName.contains('/') || newName.contains('\\') || newName == "." || newName == "..") {
            throw ApiException.localized(400, "invalid_name", messageKey = "api.fileBrowser.invalidName")
        }
        val (projectRoot, target) = safeWriteTarget(projectId, relPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(relPath))
        }
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        val dest = target.resolveSibling(newName)
        // dest 도 같은 projectRoot 안에 있음을 PathSafety 로 재확인.
        val destRel = projectRoot.relativize(dest).toString().replace('\\', '/')
        val destChecked = PathSafety.normalizeAndCheck(projectRoot, destRel)
        if (Files.exists(destChecked, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(newName))
        }
        Files.move(target, destChecked)
        log.info { "rename: $projectId :: $relPath → $newName" }
    }

    /**
     * v1.14.0 — 파일 또는 빈 디렉토리 삭제. 비어있지 않은 디렉토리는 재귀 삭제 (사용자가
     * 명시 confirm 후 호출하므로 의도된 행동). 심볼릭 링크는 link 자체만 삭제.
     */
    fun delete(projectId: String, relPath: String) {
        if (relPath.isBlank()) throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        val (_, target) = safeWriteTarget(projectId, relPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(relPath))
        }
        if (Files.isSymbolicLink(target)) {
            // 링크 자체만 unlink — 따라가지 않음 (외부 escape 방지).
            Files.delete(target)
        } else if (target.isDirectory()) {
            Files.walk(target).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    runCatching { Files.delete(p) }
                }
            }
        } else {
            Files.delete(target)
        }
        log.info { "delete: $projectId :: $relPath" }
    }

    /**
     * v1.14.0 — 신규 파일 upload (multipart stream → tree 내 파일). 동명 파일이 있으면
     * `[overwrite]=true` 이면 덮어쓰기, 아니면 [ApiException]. 부모 디렉토리는 사전 존재.
     */
    fun uploadStream(
        projectId: String,
        parentRelPath: String,
        fileName: String,
        input: java.io.InputStream,
        overwrite: Boolean = false,
        maxBytes: Long = MAX_UPLOAD_BYTES,
    ) {
        if (fileName.isBlank()) throw ApiException.localized(400, "empty_name", messageKey = "api.fileBrowser.emptyName")
        if (fileName.contains('/') || fileName.contains('\\') || fileName == "." || fileName == "..") {
            throw ApiException.localized(400, "invalid_name", messageKey = "api.fileBrowser.invalidName")
        }
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw ApiException.localized(404, "project_root_not_found", messageKey = "api.fileBrowser.projectRootNotFound")
        }
        // 부모 디렉토리 검증.
        val parent = if (parentRelPath.isBlank()) projectRoot
            else PathSafety.normalizeAndCheck(projectRoot, parentRelPath)
        if (!Files.exists(parent) || !parent.isDirectory()) {
            throw ApiException.localized(400, "parent_missing",
                messageKey = "api.fileBrowser.parentMissing", args = listOf(parentRelPath))
        }
        // 합쳐서 target.
        val targetRel = (if (parentRelPath.isBlank()) fileName else "$parentRelPath/$fileName")
        val target = PathSafety.normalizeAndCheck(projectRoot, targetRel)
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(fileName))
        }
        // 크기 제한 — 자체 카운터로 stream 소비량 추적.
        val tmp = target.resolveSibling("${target.fileName}.upload.tmp")
        var total = 0L
        try {
            Files.newOutputStream(tmp).use { out ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        runCatching { Files.deleteIfExists(tmp) }
                        throw ApiException.localized(413, "file_too_large",
                            messageKey = "api.fileBrowser.fileTooLarge", args = listOf(total, maxBytes))
                    }
                    out.write(buf, 0, n)
                }
            }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
        log.info { "uploadStream: $projectId :: $targetRel ($total bytes)" }
    }

    /** 공통: 쓰기 작업용 path 검증 + projectRoot/target pair 반환. */
    private fun safeWriteTarget(projectId: String, relPath: String): Pair<Path, Path> {
        if (relPath.isBlank()) throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw ApiException.localized(404, "project_root_not_found", messageKey = "api.fileBrowser.projectRootNotFound")
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        return projectRoot to target
    }

    private fun skipHidden(name: String): Boolean =
        // .vibecoder 등 서버 내부 메타데이터, .git 등 도구 메타데이터는 listing 노출 안 함.
        name == ".vibecoder" || name == ".gradle" || name == "build" || name == "node_modules"

    private fun looksBinary(bytes: ByteArray): Boolean {
        // 처음 4KB 안에 NUL(0x00) 이 있으면 이진으로 간주.
        val sample = if (bytes.size <= 4096) bytes else bytes.copyOf(4096)
        return sample.any { it.toInt() == 0 }
    }

    private fun guessMime(path: String): String = when {
        path.endsWith(".kt", true) -> "text/x-kotlin"
        path.endsWith(".kts", true) -> "text/x-kotlin"
        path.endsWith(".java", true) -> "text/x-java"
        path.endsWith(".xml", true) -> "text/xml"
        path.endsWith(".json", true) -> "application/json"
        path.endsWith(".yml", true) || path.endsWith(".yaml", true) -> "text/yaml"
        path.endsWith(".md", true) -> "text/markdown"
        path.endsWith(".gradle", true) -> "text/x-gradle"
        path.endsWith(".properties", true) -> "text/x-properties"
        path.endsWith(".sh", true) -> "text/x-shellscript"
        else -> "text/plain"
    }

    /**
     * v1.19.0 — 파일 또는 디렉토리를 다른 디렉토리 안으로 이동. rename 의 cross-dir 확장.
     *
     * `srcRel` 는 기존 entry. `dstParentRel` 는 대상 부모 디렉토리 (빈 문자열 = 프로젝트
     * 루트). 결과 path = `<dstParent>/<src name>`. 같은 부모 디렉토리로의 move 는 no-op
     * (src == dst). cycle 방지 — dst 가 src 의 자식이면 거부.
     */
    fun move(projectId: String, srcRel: String, dstParentRel: String) {
        val (projectRoot, src) = safeWriteTarget(projectId, srcRel)
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(srcRel))
        }
        if (Files.isSymbolicLink(src)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        val dstParent = if (dstParentRel.isBlank()) projectRoot
            else PathSafety.normalizeAndCheck(projectRoot, dstParentRel)
        if (!Files.exists(dstParent) || !dstParent.isDirectory()) {
            throw ApiException.localized(400, "parent_missing",
                messageKey = "api.fileBrowser.parentMissing", args = listOf(dstParentRel))
        }
        val dst = dstParent.resolve(src.fileName)
        // dstParent 가 src 자체 또는 src 의 자식이면 cycle.
        if (dstParent.normalize().startsWith(src.normalize())) {
            throw ApiException.localized(400, "cycle",
                messageKey = "api.fileBrowser.cycleMove", args = listOf(srcRel))
        }
        // dst Path 도 PathSafety 재검증.
        val dstRel = projectRoot.relativize(dst).toString().replace('\\', '/')
        val dstChecked = PathSafety.normalizeAndCheck(projectRoot, dstRel)
        if (src.normalize() == dstChecked.normalize()) {
            // 같은 위치 → no-op.
            return
        }
        if (Files.exists(dstChecked, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(src.fileName.toString()))
        }
        Files.move(src, dstChecked)
        log.info { "move: $projectId :: $srcRel → $dstRel" }
    }

    /**
     * v1.19.0 — 파일 또는 디렉토리를 다른 디렉토리 안으로 복사. 디렉토리는 재귀.
     * 동명 entry 있으면 거부 (overwrite 미지원 — 사용자가 명시적 delete 후 paste).
     * cycle 방지 — dst 가 src 자체 또는 자식이면 거부.
     */
    fun copy(projectId: String, srcRel: String, dstParentRel: String) {
        val (projectRoot, src) = safeWriteTarget(projectId, srcRel)
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.fileBrowser.pathNotFound", args = listOf(srcRel))
        }
        if (Files.isSymbolicLink(src)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedEdit")
        }
        val dstParent = if (dstParentRel.isBlank()) projectRoot
            else PathSafety.normalizeAndCheck(projectRoot, dstParentRel)
        if (!Files.exists(dstParent) || !dstParent.isDirectory()) {
            throw ApiException.localized(400, "parent_missing",
                messageKey = "api.fileBrowser.parentMissing", args = listOf(dstParentRel))
        }
        if (dstParent.normalize().startsWith(src.normalize())) {
            throw ApiException.localized(400, "cycle",
                messageKey = "api.fileBrowser.cycleMove", args = listOf(srcRel))
        }
        val dst = dstParent.resolve(src.fileName)
        val dstRel = projectRoot.relativize(dst).toString().replace('\\', '/')
        val dstChecked = PathSafety.normalizeAndCheck(projectRoot, dstRel)
        if (Files.exists(dstChecked, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(409, "already_exists",
                messageKey = "api.fileBrowser.alreadyExists", args = listOf(src.fileName.toString()))
        }
        if (src.isDirectory()) {
            Files.walk(src).use { stream ->
                stream.forEach { p ->
                    val rel = src.relativize(p)
                    val target = dstChecked.resolve(rel.toString())
                    if (p.isDirectory()) {
                        Files.createDirectories(target)
                    } else if (!Files.isSymbolicLink(p)) {
                        Files.createDirectories(target.parent)
                        Files.copy(p, target)
                    } // symlink 는 skip — 외부 escape 방지.
                }
            }
        } else {
            Files.copy(src, dstChecked)
        }
        log.info { "copy: $projectId :: $srcRel → $dstRel" }
    }

    /**
     * v1.17.0 — 이미지 raw stream 읽기. /raw 엔드포인트가 path traversal 검증 후
     * 직접 file 을 stream 으로 응답할 수 있도록 안전 검증된 절대 경로 + 추정 MIME
     * 만 반환. 호출자가 Files.newInputStream(path) 또는 respondFile 으로 응답.
     */
    fun resolveForRawRead(projectId: String, relPath: String): RawFile {
        if (relPath.isBlank()) throw ApiException.localized(400, "empty_path", messageKey = "api.fileBrowser.emptyPath")
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.exists()) {
            throw ApiException.localized(404, "project_root_not_found", messageKey = "api.fileBrowser.projectRootNotFound")
        }
        val target = PathSafety.normalizeAndCheck(projectRoot, relPath)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException.localized(404, "file_not_found", messageKey = "api.fileBrowser.fileNotFound", args = listOf(relPath))
        }
        if (Files.isSymbolicLink(target)) {
            throw ApiException.localized(403, "symlink_blocked", messageKey = "api.fileBrowser.symlinkBlockedView")
        }
        if (!target.isRegularFile()) {
            throw ApiException.localized(400, "not_a_file", messageKey = "api.fileBrowser.notAFile")
        }
        val size = Files.size(target)
        // 이미지 raw stream 한도 — view 보다 크게 (10 MB). 일반 Android asset 충분.
        if (size > MAX_RAW_BYTES) {
            throw ApiException.localized(413, "file_too_large",
                messageKey = "api.fileBrowser.fileTooLarge", args = listOf(size, MAX_RAW_BYTES))
        }
        return RawFile(target, size, guessImageMime(relPath))
    }

    /** v1.17.0 — read-only raw stream + 추정 MIME. /raw endpoint 가 직접 InputStream 응답. */
    data class RawFile(
        val absolutePath: Path,
        val sizeBytes: Long,
        val mime: String,
    )

    companion object {
        /** UI 에서 열거 가능한 최대 텍스트 파일 크기 — 1 MB. */
        const val MAX_VIEW_BYTES = 1024L * 1024
        /** v1.14.0 — 파일 탐색기 업로드 단일 파일 한도 — 50 MB. workspace 안 source asset 가정. */
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
        /** v1.17.0 — /raw 엔드포인트 stream 한도 — 10 MB (이미지 viewer 용). */
        const val MAX_RAW_BYTES = 10L * 1024 * 1024

        /**
         * v1.17.0 — 파일 경로의 확장자가 image 인지 판정. /view 라우트가 image 모드
         * 분기 결정에 사용.
         */
        fun isImagePath(path: String): Boolean {
            val lower = path.lowercase()
            return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
        }

        private val IMAGE_EXTENSIONS = listOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".avif", ".svg",
        )

        /** v1.17.0 — 확장자 기반 MIME 추정. /raw 응답 헤더용. */
        fun guessImageMime(path: String): String {
            val lower = path.lowercase()
            return when {
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".gif") -> "image/gif"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".bmp") -> "image/bmp"
                lower.endsWith(".ico") -> "image/x-icon"
                lower.endsWith(".avif") -> "image/avif"
                lower.endsWith(".svg") -> "image/svg+xml"
                else -> "application/octet-stream"
            }
        }
    }
}
