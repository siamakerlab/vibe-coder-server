package com.siamakerlab.vibecoder.server.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Flat workspace layout (v0.2):
 *
 *   <root>/
 *     <projectId>/                ← user's Android project root (gradlew, settings, app/, ...)
 *     <projectId>/CLAUDE.md       ← auto-written if missing
 *     .vibecoder/
 *       <projectId>/
 *         project.yml             ← registration metadata
 *         tasks/                  ← claude task state
 *         builds/
 *         artifacts/
 *         uploads/
 *         logs/<id>.log
 *         patches/<taskId>.patch
 *       keystores/
 *         <projectId>/            ← signing keystores stored OUTSIDE project folder
 *           <appName>.keystore
 *           <appName>-keystore.properties
 *
 * All filesystem-touching services MUST funnel through this class so path
 * traversal defense ([PathSafety]) and directory creation are consistent.
 */
class WorkspacePath(val root: Path) {

    init {
        root.createDirectories()
    }

    companion object {
        // v1.33.0 — General Chat ghost 프로젝트 id. ProjectService.SCRATCH_ID 와 같은 값
        // (모듈 독립성 위해 별도 정의). projectRoot 매핑 + vibecoderDir 에 사용.
        const val SCRATCH_ID = "__scratch__"

        // v1.54.0 — ChatGPT 스타일 다중 채팅. 각 채팅 = 별도 ghost 프로젝트 `__chat_<id>__`.
        // SCRATCH 와 동일하게 워크스페이스 루트가 아닌 .vibecoder 메타 sidecar 에 위치.
        const val CHAT_PREFIX = "__chat_"

        /**
         * v1.54.0 — ghost(비-워크스페이스) 프로젝트 판정. scratch + 모든 chat 세션.
         * 이 id 들은 `<root>/.vibecoder/<id>` 에 살며 일반 프로젝트 목록/파일 탐색에서 제외.
         */
        fun isGhostId(projectId: String): Boolean =
            projectId == SCRATCH_ID || projectId.startsWith(CHAT_PREFIX)
    }

    /**
     * `<root>/<projectId>` — NOT auto-created; the user places their project here.
     *
     * v1.33.0 — scratch(`__scratch__`)만 예외: `<root>/.vibecoder/__scratch__` 반환.
     * v1.54.0 — chat ghost(`__chat_*`)도 동일 — `<root>/.vibecoder/<id>` 반환.
     * General Chat ghost 프로젝트가 워크스페이스 루트의 프로젝트 목록/파일 탐색에 섞이지
     * 않게 메타 sidecar 안으로 이동. ClaudeSessionManager(cwd)·ProjectFileBrowser 등 모든
     * 서비스가 projectRoot 를 쓰므로 여기서 한 번만 매핑하면 전부 일관.
     */
    fun projectRoot(projectId: String): Path =
        if (isGhostId(projectId)) vibecoderDir(projectId)
        else PathSafety.normalizeAndCheck(root, projectId)

    /** `<root>/.vibecoder/<projectId>` — auto-created (server-owned metadata sidecar). */
    fun vibecoderDir(projectId: String): Path {
        val metaRoot = root.resolve(".vibecoder").also { it.createDirectories() }
        val safe = PathSafety.normalizeAndCheck(metaRoot, projectId)
        if (Files.notExists(safe)) safe.createDirectories()
        return safe
    }

    fun tasksDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("tasks").also { it.createDirectories() }

    fun buildsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("builds").also { it.createDirectories() }

    fun artifactsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("artifacts").also { it.createDirectories() }

    fun uploadsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("uploads").also { it.createDirectories() }

    fun logsDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("logs").also { it.createDirectories() }

    fun patchesDir(projectId: String): Path =
        vibecoderDir(projectId).resolve("patches").also { it.createDirectories() }

    fun taskLogFile(projectId: String, taskId: String): Path =
        logsDir(projectId).resolve("$taskId.log")

    fun buildLogFile(projectId: String, buildId: String): Path =
        logsDir(projectId).resolve("$buildId.log")

    fun debugArtifactDir(projectId: String, buildId: String): Path =
        artifactsDir(projectId).resolve("debug").resolve(buildId).also { it.createDirectories() }

    /** `<root>/.vibecoder/keystores/<projectId>` — signing keystores live OUTSIDE the project folder. */
    fun keystoresDir(projectId: String): Path {
        val ksRoot = root.resolve(".vibecoder").resolve("keystores").also { it.createDirectories() }
        val safe = PathSafety.normalizeAndCheck(ksRoot, projectId)
        if (Files.notExists(safe)) safe.createDirectories()
        return safe
    }

    /** v1.98.0 — `<root>/.vibecoder/archives` — 아카이브된 프로젝트 tar.gz 보관소(단일 디렉토리). */
    fun archivesDir(): Path =
        root.resolve(".vibecoder").resolve("archives").also { it.createDirectories() }

    /** Defense-in-depth: reject paths from DB rows / uploads pointing outside the workspace. */
    fun ensureUnderWorkspace(absolute: Path): Path =
        PathSafety.checkAbsoluteIsInsideWorkspace(root, absolute)
}
