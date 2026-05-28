package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val log = KotlinLogging.logger {}

/**
 * v0.29.0 — 프로젝트 source 를 zip 으로 stream.
 *
 * 사용 시점: 별도 머신으로 source 이전, 백업, 외부 리뷰어에게 zip 전송 등.
 * 큰 트리도 메모리 폭발 없이 stream — 응답 OutputStream 에 직접 write.
 *
 * 제외 패턴 (기본):
 *   - `.git/` — 보통 가장 큰 디렉토리, 사용자가 git clone 으로 복원 가능
 *   - `build/` — Gradle 산출물 (전체 재빌드 가능)
 *   - `.gradle/` — Gradle wrapper / config 캐시
 *   - `node_modules/` — npm 의존성 (재설치 가능)
 *   - `*.apk`, `*.aab` — vibecoder 의 artifact 디렉토리는 별도 경로지만 source
 *     안에 우연히 들어가 있는 산출물도 제외
 *
 * 워크스페이스 경로 안전성: WorkspacePath.projectRoot 가 이미 sanitize. 본 클래스는
 * 그 결과만 walk 하므로 추가 검증 불요.
 */
class ProjectArchiver(private val workspace: WorkspacePath) {

    fun streamZip(projectId: String, out: OutputStream) {
        val root = workspace.projectRoot(projectId)
        if (!Files.isDirectory(root)) {
            throw IllegalArgumentException("project root not a directory: $root")
        }
        ZipOutputStream(out).use { zip ->
            Files.walk(root).use { stream ->
                stream.forEach { p ->
                    if (p == root) return@forEach
                    val rel = root.relativize(p).toString().replace('\\', '/')
                    if (shouldExclude(rel)) return@forEach
                    // v1.31.1 (B-Q2) — 심볼릭 링크 skip. Files.walk 는 링크 디렉토리를
                    // 따라가지 않지만, 링크 노드 자체에 isRegularFile 가 링크 대상을
                    // 따라가 판정 → 워크스페이스 밖 호스트 파일(/etc/passwd 등)을 가리키는
                    // 링크가 zip 에 포함될 수 있었음. zip export 가 sandbox 밖 유출 경로.
                    if (Files.isSymbolicLink(p)) return@forEach
                    try {
                        if (Files.isDirectory(p)) {
                            zip.putNextEntry(ZipEntry("$rel/"))
                            zip.closeEntry()
                        } else if (Files.isRegularFile(p)) {
                            zip.putNextEntry(ZipEntry(rel))
                            Files.copy(p, zip)
                            zip.closeEntry()
                        }
                    } catch (e: Throwable) {
                        log.debug(e) { "skip $rel: ${e.message}" }
                    }
                }
            }
        }
    }

    private fun shouldExclude(rel: String): Boolean {
        val first = rel.substringBefore('/')
        if (first in EXCLUDED_TOP_DIRS) return true
        if (rel.contains("/build/") || rel.contains("/.gradle/") || rel.contains("/node_modules/")) return true
        if (rel.endsWith(".apk") || rel.endsWith(".aab")) return true
        return false
    }

    companion object {
        private val EXCLUDED_TOP_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea")
    }
}
