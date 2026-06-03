package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.admin.KeystoreService
import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ArchivedProjectRepository
import com.siamakerlab.vibecoder.server.repo.ArchivedProjectRow
import com.siamakerlab.vibecoder.server.repo.ProjectRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/** tar.gz 안 manifest.json — 복원에 필요한 Projects row 필드. */
@Serializable
data class ArchiveManifest(
    val version: Int = 1,
    val originalId: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String,
    val debugTask: String,
    val createdAt: String,
    val archivedAt: String,
)

/**
 * v1.98.0 — 프로젝트 아카이브/복원.
 *
 * **아카이브**: 소스 폴더(`<root>/<id>`) + 메타(`<root>/.vibecoder/<id>`) + 키스토어
 * (`/home/vibe/keystores/<pkg>.*`) + manifest.json 을 staging 으로 모아 `tar.gz` 로 압축
 * (`<root>/.vibecoder/archives/<id>-<ts>.tar.gz`). 압축 검증 성공 후에만 레지스트리 등록 →
 * DB 자식+row 정리([deleteProject] 위임) + 원본 폴더/키스토어 제거. (원자성: tar 완성 전엔
 * 아무것도 안 지움.)
 *
 * **복원**: tar 해제 → 폴더/키스토어 되돌림 + Projects row 재삽입. id/folder 충돌 시 거부.
 *
 * tar/cp/rm 은 컨테이너 표준 유틸을 ProcessBuilder 로 호출(zip-slip 은 격리 디렉토리 해제로 방어).
 */
class ProjectArchiveService(
    private val workspace: WorkspacePath,
    private val keystore: KeystoreService,
    private val projectRepo: ProjectRepository,
    private val archivedRepo: ArchivedProjectRepository,
    private val deleteProject: (String) -> Boolean,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault())

    fun list(): List<ArchivedProjectRow> = archivedRepo.list()
    fun get(id: String): ArchivedProjectRow? = archivedRepo.findById(id)
    fun archiveFile(id: String): Path? =
        archivedRepo.findById(id)?.let { Path.of(it.archivePath) }?.takeIf { it.exists() }

    /** 프로젝트를 tar.gz 로 아카이브 후 DB row+폴더+키스토어 제거. @return archiveId. */
    fun archive(projectId: String): String {
        require(projectId != "__scratch__") { "scratch_protected" }
        val p = projectRepo.findById(projectId)
            ?: throw IllegalArgumentException("project_not_found: $projectId")

        val archivedAt = Instant.now()
        val archiveId = "$projectId-${tsFmt.format(archivedAt)}"
        val manifest = ArchiveManifest(
            originalId = p.id, name = p.name, packageName = p.packageName,
            sourcePath = p.sourcePath, moduleName = p.moduleName, debugTask = p.debugTask,
            createdAt = p.createdAt, archivedAt = archivedAt.toString(),
        )
        val manifestJson = json.encodeToString(manifest)

        val archivesDir = workspace.archivesDir()
        val staging = archivesDir.resolve(".staging-$archiveId")
        rmrf(staging)
        Files.createDirectories(staging)

        // 1) staging 구성: manifest + project/ + vibecoder/ + keystores/
        Files.writeString(staging.resolve("manifest.json"), manifestJson)
        val projRoot = workspace.projectRoot(projectId)
        if (projRoot.exists()) copyTree(projRoot, staging.resolve("project"))
        val vibeDir = workspace.vibecoderDir(projectId)
        if (vibeDir.exists()) copyTree(vibeDir, staging.resolve("vibecoder"))
        val ksFiles = keystore.keystoreFiles(p.packageName)
        if (ksFiles.isNotEmpty()) {
            val ksStage = staging.resolve("keystores").also { Files.createDirectories(it) }
            ksFiles.forEach { copyFile(it, ksStage.resolve(it.name)) }
        }

        // 2) tar.gz 생성 + 검증
        val tar = archivesDir.resolve("$archiveId.tar.gz")
        if (!run(listOf("tar", "czf", tar.toString(), "-C", staging.toString(), "."))) {
            rmrf(staging); runCatching { Files.deleteIfExists(tar) }
            throw IllegalStateException("tar_failed")
        }
        if (!tar.exists() || tar.fileSize() <= 0L) {
            rmrf(staging); throw IllegalStateException("archive_empty")
        }
        val size = tar.fileSize()

        // 3) 레지스트리 등록 (성공 후에만 원본 제거 — 원자성)
        archivedRepo.insert(
            ArchivedProjectRow(
                id = archiveId, originalId = p.id, name = p.name, packageName = p.packageName,
                archivedAt = archivedAt.toString(), archivePath = tar.toString(),
                sizeBytes = size, manifestJson = manifestJson,
            )
        )

        // 4) 원본 정리: DB 자식+row(delete 재사용) + 폴더 + 키스토어 파일
        runCatching { deleteProject(projectId) }
            .onFailure { log.warn(it) { "deleteProject failed during archive: $projectId" } }
        rmrf(projRoot)
        rmrf(vibeDir)
        ksFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        rmrf(staging)

        log.info { "project archived: $projectId → $archiveId ($size bytes)" }
        return archiveId
    }

    /** 아카이브를 프로젝트로 복원. tar 해제 + 폴더/키스토어 되돌림 + Projects row 재삽입. */
    fun unarchive(archiveId: String) {
        val row = archivedRepo.findById(archiveId)
            ?: throw IllegalArgumentException("archive_not_found: $archiveId")
        val manifest = json.decodeFromString<ArchiveManifest>(row.manifestJson)
        val targetId = manifest.originalId

        if (projectRepo.findById(targetId) != null) throw IllegalStateException("project_exists: $targetId")
        val projRoot = workspace.projectRoot(targetId)
        if (projRoot.exists()) throw IllegalStateException("folder_exists: $targetId")
        val tar = Path.of(row.archivePath)
        if (!tar.exists()) throw IllegalStateException("archive_file_missing")

        val restore = workspace.archivesDir().resolve(".restore-$archiveId")
        rmrf(restore)
        Files.createDirectories(restore)
        if (!run(listOf("tar", "xzf", tar.toString(), "-C", restore.toString()))) {
            rmrf(restore); throw IllegalStateException("untar_failed")
        }

        // 폴더 복원
        restore.resolve("project").takeIf { it.exists() }?.let { rp ->
            projRoot.parent?.let { Files.createDirectories(it) }
            Files.move(rp, projRoot)
        }
        restore.resolve("vibecoder").takeIf { it.exists() }?.let { rv ->
            val vd = workspace.vibecoderDir(targetId); rmrf(vd); Files.move(rv, vd)
        }
        // 키스토어 복원 (파일명은 이미 <pkg> prefix)
        restore.resolve("keystores").takeIf { it.exists() }?.let { rk ->
            val ksDir = keystore.keystoreDirPath().also { Files.createDirectories(it) }
            Files.list(rk).use { s -> s.forEach { f -> copyFile(f, ksDir.resolve(f.name)) } }
        }

        // DB row 재삽입 — sourcePath 는 현재 환경 경로로 갱신
        projectRepo.insert(
            targetId, manifest.name, manifest.packageName,
            projRoot.toString(), manifest.moduleName, manifest.debugTask,
        )

        archivedRepo.delete(archiveId)
        runCatching { Files.deleteIfExists(tar) }
        rmrf(restore)
        log.info { "project unarchived: $archiveId → $targetId" }
    }

    /** 아카이브 영구 삭제 (tar 파일 + 레지스트리). */
    fun deleteArchive(archiveId: String): Boolean {
        val row = archivedRepo.findById(archiveId) ?: return false
        runCatching { Files.deleteIfExists(Path.of(row.archivePath)) }
        return archivedRepo.delete(archiveId) > 0
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun copyTree(src: Path, dst: Path) {
        if (!run(listOf("cp", "-a", src.toString(), dst.toString())))
            throw IllegalStateException("copy_failed: $src")
    }

    private fun copyFile(src: Path, dst: Path) {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun rmrf(p: Path) { if (p.exists()) run(listOf("rm", "-rf", p.toString())) }

    private fun run(cmd: List<String>, timeoutSec: Long = 600): Boolean = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { proc.destroyForcibly(); return false }
        if (proc.exitValue() != 0) {
            log.warn { "cmd ${cmd.take(2)} exit=${proc.exitValue()}: ${out.take(500)}" }; false
        } else true
    }.getOrElse { log.warn(it) { "cmd error ${cmd.take(2)}" }; false }
}
