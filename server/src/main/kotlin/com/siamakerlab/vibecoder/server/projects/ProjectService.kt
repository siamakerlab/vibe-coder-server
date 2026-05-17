package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.repo.BuildRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import com.siamakerlab.vibecoder.server.repo.ProjectRow
import com.siamakerlab.vibecoder.shared.dto.ProjectDto
import com.siamakerlab.vibecoder.shared.dto.RegisterProjectRequestDto
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists
import kotlin.io.path.writeText

class ProjectService(
    private val config: ServerConfig,
    private val workspace: WorkspacePath,
    private val repo: ProjectRepository,
    private val buildRepo: BuildRepository,
) {

    fun register(body: RegisterProjectRequestDto): ProjectDto {
        require(body.projectId.isNotBlank()) { "projectId required" }
        require(!body.projectId.contains('/') && !body.projectId.contains('\\') && !body.projectId.contains("..")) {
            "projectId must not contain path separators"
        }
        if (repo.findById(body.projectId) != null) {
            throw ApiException(409, "project_already_registered", "${body.projectId} already exists")
        }

        val srcRaw = Path.of(body.sourcePath).toAbsolutePath().normalize()
        if (config.security.restrictToWorkspace) {
            workspace.ensureUnderWorkspace(srcRaw)
        } else if (srcRaw.notExists()) {
            throw ApiException(404, "source_not_found", "${body.sourcePath} does not exist")
        }

        val gradlewBat = srcRaw.resolve("gradlew.bat")
        val gradlewSh = srcRaw.resolve("gradlew")
        if (gradlewBat.notExists() && gradlewSh.notExists()) {
            throw ApiException(400, "no_gradle_wrapper", "gradlew (or gradlew.bat) not found in $srcRaw")
        }
        val settingsKts = srcRaw.resolve("settings.gradle.kts")
        val settingsGroovy = srcRaw.resolve("settings.gradle")
        if (settingsKts.notExists() && settingsGroovy.notExists()) {
            throw ApiException(400, "no_settings_gradle", "settings.gradle(.kts) not found in $srcRaw")
        }
        val moduleDir = srcRaw.resolve(body.moduleName)
        if (moduleDir.notExists()) {
            throw ApiException(400, "module_not_found", "${body.moduleName}/ not found in $srcRaw")
        }

        // Mirror the source path into workspace so all `.vibecoder/*` data lives there.
        val mirror = workspace.projectRoot(body.projectId)
        val sourceLink = mirror.resolve("source")
        if (sourceLink.notExists()) {
            // We don't actually copy; we record the absolute source path inside `.vibecoder/project.yml`.
            sourceLink.createDirectories()
        }

        val vibeDir = workspace.vibecoderDir(body.projectId)
        val projectYml = vibeDir.resolve("project.yml")
        if (projectYml.notExists()) {
            projectYml.writeText(buildProjectYml(body, srcRaw))
        }

        val claudeMd = srcRaw.resolve("CLAUDE.md")
        if (claudeMd.notExists()) {
            Files.writeString(claudeMd, ClaudeMdTemplate.CONTENT)
        }

        val row = repo.insert(
            id = body.projectId,
            name = body.name,
            packageName = body.packageName,
            sourcePath = srcRaw.toString(),
            moduleName = body.moduleName,
            debugTask = body.debugTask,
        )
        return row.toDto(hasGitChanges = false, lastBuildStatus = null)
    }

    fun list(): List<ProjectDto> {
        val rows = repo.list()
        return rows.map { row ->
            val last = buildRepo.lastForProject(row.id)
            row.toDto(false, last?.status?.name)
        }
    }

    fun get(id: String): ProjectDto {
        val row = repo.findById(id)
            ?: throw ApiException(404, "project_not_found", "$id not registered")
        val last = buildRepo.lastForProject(id)
        return row.toDto(false, last?.status?.name)
    }

    fun sourcePathOrThrow(id: String): Path {
        val row = repo.findById(id) ?: throw ApiException(404, "project_not_found", id)
        return Path.of(row.sourcePath)
    }

    fun rowOrThrow(id: String): ProjectRow =
        repo.findById(id) ?: throw ApiException(404, "project_not_found", id)

    fun delete(id: String): Boolean = repo.delete(id) > 0

    private fun buildProjectYml(req: RegisterProjectRequestDto, absSource: Path): String = """
        |# Vibe Coder project metadata
        |id: ${req.projectId}
        |name: ${req.name}
        |packageName: ${req.packageName}
        |sourcePath: $absSource
        |moduleName: ${req.moduleName}
        |debugTask: ${req.debugTask}
    """.trimMargin()

    private fun ProjectRow.toDto(hasGitChanges: Boolean, lastBuildStatus: String?): ProjectDto =
        ProjectDto(
            id = id, name = name, packageName = packageName,
            sourcePath = sourcePath, moduleName = moduleName, debugTask = debugTask,
            lastBuildStatus = lastBuildStatus, hasGitChanges = hasGitChanges,
            updatedAt = updatedAt,
        )
}
