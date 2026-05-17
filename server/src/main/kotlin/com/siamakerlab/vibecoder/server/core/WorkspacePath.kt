package com.siamakerlab.vibecoder.server.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Concrete workspace layout. All paths derive from [root]; callers must use
 * the helpers below rather than concatenating strings.
 */
class WorkspacePath(val root: Path) {

    init {
        root.createDirectories()
    }

    fun projectsRoot(): Path = root.resolve("projects").also { it.createDirectories() }

    /** Always returns a path **inside** [root]. */
    fun projectRoot(projectId: String): Path {
        val safe = PathSafety.normalizeAndCheck(projectsRoot(), projectId)
        if (Files.notExists(safe)) safe.createDirectories()
        return safe
    }

    fun vibecoderDir(projectId: String): Path =
        projectRoot(projectId).resolve(".vibecoder").also { it.createDirectories() }

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

    fun ensureUnderWorkspace(absolute: Path): Path =
        PathSafety.checkAbsoluteIsInsideWorkspace(root, absolute)
}
