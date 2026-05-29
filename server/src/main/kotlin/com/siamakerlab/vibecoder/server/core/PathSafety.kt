package com.siamakerlab.vibecoder.server.core

import com.siamakerlab.vibecoder.server.error.ApiException
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.notExists

/**
 * Defends against path traversal and absolute-path injection.
 *
 * Every filesystem-touching service MUST funnel through [normalizeAndCheck] before
 * touching disk. Tests in PathSafetyTest verify ../, absolute paths, drive letters,
 * and control bytes are rejected.
 */
object PathSafety {

    /**
     * Resolve [raw] against [root] and return the absolute, normalized Path,
     * ensuring the result is **inside** [root].
     *
     * @throws ApiException with code "path_traversal" otherwise.
     */
    fun normalizeAndCheck(root: Path, raw: String): Path {
        // Reject control bytes (NUL, CR, LF, ESC, DEL, …). These can:
        //   - Smuggle past validators that only look at printable parts
        //   - Break native path APIs on some platforms (e.g., NUL terminator)
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) {
            throw ApiException.localized(400, "invalid_path", messageKey = "api.pathSafety.controlByte")
        }
        // Reject absolute-looking strings to avoid `Path.resolve` swallowing them.
        if (raw.startsWith('/') || raw.startsWith('\\') || hasWindowsDriveLetter(raw)) {
            throw ApiException.localized(403, "path_traversal", messageKey = "api.pathSafety.absoluteNotAllowed")
        }
        val absRoot = root.toAbsolutePath().normalize()
        val candidate = absRoot.resolve(raw).normalize().absolute()
        if (!candidate.startsWith(absRoot)) {
            throw ApiException.localized(403, "path_traversal", messageKey = "api.pathSafety.escapeWorkspace", args = listOf(raw))
        }
        return candidate
    }

    /**
     * v1.43.0 — 22차 정밀점검 회수: symlink escape 방어.
     *
     * [normalizeAndCheck] 는 **어휘적(lexical)** 검증만 한다(`toRealPath` 미수행). 따라서
     * 워크스페이스 안에 워크스페이스 **밖**을 가리키는 디렉토리 symlink(`<root>/evil -> /etc`)
     * 가 있으면 `evil/passwd` 가 lexical 로는 root 안이라 통과하지만 실제로는 `/etc/passwd`
     * 를 읽게 된다. (Claude 자식 프로세스 / 신뢰불가 git clone 이 그런 symlink 를 심을 수 있음.)
     *
     * 이 함수는 [target] 의 **실제 경로**(symlink 해석)가 여전히 [root] 안인지 확인한다.
     * 미존재 경로(신규 파일 생성)는 가장 가까운 존재 조상으로 검사한다. root **내부**를 가리키는
     * symlink(gradle 캐시 등)는 그대로 허용된다(real path 가 root 안이므로).
     *
     * 호출 전 [normalizeAndCheck] 로 lexical 검증을 끝낸 [target] 에 대해 사용한다.
     *
     * @throws ApiException("path_traversal") real path 가 root 밖이면.
     */
    fun assertRealInside(root: Path, target: Path) {
        val realRoot = runCatching { root.toRealPath() }.getOrElse { root.toAbsolutePath().normalize() }
        // 존재하는 가장 가까운 조상까지 거슬러 올라간다(신규 파일 대응).
        var probe: Path? = target.toAbsolutePath().normalize()
        while (probe != null && probe.notExists()) probe = probe.parent
        if (probe == null) return // 존재 조상 없음(비정상) — lexical 검증에 위임
        val real = runCatching { probe.toRealPath() }.getOrElse { probe.toAbsolutePath().normalize() }
        if (!real.startsWith(realRoot)) {
            throw ApiException.localized(403, "path_traversal",
                messageKey = "api.pathSafety.escapeWorkspace", args = listOf(target.fileName?.toString() ?: ""))
        }
    }

    /**
     * Test whether [candidate] sits underneath [root] (both pre-normalized acceptable).
     */
    fun isInside(root: Path, candidate: Path): Boolean {
        val r = root.toAbsolutePath().normalize()
        val c = candidate.toAbsolutePath().normalize()
        return c.startsWith(r)
    }

    /**
     * Defense-in-depth check for paths read from DB rows / uploads:
     * verify [absolute] lies inside [workspaceRoot].
     */
    fun checkAbsoluteIsInsideWorkspace(workspaceRoot: Path, absolute: Path): Path {
        val r = workspaceRoot.toAbsolutePath().normalize()
        val c = absolute.toAbsolutePath().normalize()
        if (!c.startsWith(r)) {
            throw ApiException.localized(403, "path_outside_workspace",
                messageKey = "api.pathSafety.notUnderWorkspace", args = listOf(c.toString()))
        }
        if (c.notExists()) {
            throw ApiException.localized(404, "path_not_found", messageKey = "api.pathSafety.notExist", args = listOf(c.toString()))
        }
        return c
    }

    private fun hasWindowsDriveLetter(raw: String): Boolean {
        if (raw.length < 2) return false
        val c0 = raw[0]
        val c1 = raw[1]
        return c1 == ':' && (c0 in 'a'..'z' || c0 in 'A'..'Z')
    }
}
