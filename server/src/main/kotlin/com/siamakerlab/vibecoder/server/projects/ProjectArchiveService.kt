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
    val version: Int = 2,                       // v1.132.0 — projectType 추가
    val originalId: String,
    val name: String,
    val packageName: String,
    val sourcePath: String,
    val moduleName: String,
    val debugTask: String,
    val createdAt: String,
    val archivedAt: String,
    // v1.132.0 — kotlin | flutter. 구 manifest(version 1)엔 없어 default kotlin (backward compat).
    val projectType: String = "kotlin",
)

/**
 * v1.98.0 — 프로젝트 아카이브/복원.
 *
 * **아카이브**: 소스 폴더(`<root>/<id>`) + 메타(`<root>/.vibecoder/<id>`) + 키스토어
 * (`/home/vibe/keystores/<pkg>.*`) + manifest.json 을 staging 으로 모아 `tar.gz` 로 압축
 * (`<root>/.vibecoder/archives/<id>-<ts>.tar.gz`). 압축 검증 + **DB 정리(delete) 성공을 확인한
 * 뒤에만** 원본 폴더/키스토어를 제거한다(부분 실패 시 원본 보존 + 레지스트리 롤백).
 *
 * **복원**: tar 해제 → 폴더/키스토어 되돌림 + Projects row 재삽입. id/폴더/키스토어 충돌 시 거부.
 *
 * 모든 변경 메서드는 단일 [lock] 으로 직렬화(중복 클릭/탭 race·TOCTOU 방지). tar/cp/rm 은
 * 컨테이너 표준 유틸을 ProcessBuilder 로 호출(해제는 격리 디렉토리 + hardening 플래그로 방어).
 *
 * v1.98.1 (정밀점검 회수): H2 deleteProject 실패 시 폴더 삭제 강행 차단, H3/M1 키스토어 덮어쓰기
 *   방지(중복 archive 거부 + 복원 충돌 거부), M2 직렬화, M3 tar hardening, M4 process drain,
 *   L1 staging finally, L2 archivePath 워크스페이스 검증.
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
    private val lock = Any()

    fun list(): List<ArchivedProjectRow> = archivedRepo.list()
    fun get(id: String): ArchivedProjectRow? = archivedRepo.findById(id)
    fun archiveFile(id: String): Path? =
        archivedRepo.findById(id)?.let { safeArchivePath(it.archivePath) }?.takeIf { it.exists() }

    /** L2 — DB 의 archivePath 가 워크스페이스 내부인지 검증(외부 경로 거부). */
    private fun safeArchivePath(raw: String): Path? =
        runCatching { workspace.ensureUnderWorkspace(Path.of(raw)) }.getOrNull()

    /** 프로젝트를 tar.gz 로 아카이브 후 DB row+폴더+키스토어 제거. @return archiveId. */
    fun archive(projectId: String): String = synchronized(lock) {
        require(projectId != "__scratch__") { "scratch_protected" }
        val p = projectRepo.findById(projectId)
            ?: throw IllegalArgumentException("project_not_found: $projectId")
        // M1 — 같은 프로젝트/패키지의 아카이브가 이미 있으면 거부(키스토어 중복·복원 충돌 예방).
        if (archivedRepo.existsOriginalOrPackage(p.id, p.packageName))
            throw IllegalStateException("already_archived: ${p.packageName}")

        val archivedAt = Instant.now()
        val archiveId = "$projectId-${tsFmt.format(archivedAt)}"
        val manifestJson = json.encodeToString(
            ArchiveManifest(
                originalId = p.id, name = p.name, packageName = p.packageName,
                sourcePath = p.sourcePath, moduleName = p.moduleName, debugTask = p.debugTask,
                createdAt = p.createdAt, archivedAt = archivedAt.toString(), projectType = p.projectType,
            )
        )

        val archivesDir = workspace.archivesDir()
        val staging = archivesDir.resolve(".staging-$archiveId")
        val tar = archivesDir.resolve("$archiveId.tar.gz")
        val projRoot = workspace.projectRoot(projectId)
        val vibeDir = workspace.vibecoderDir(projectId)
        val ksFiles = keystore.keystoreFiles(p.packageName)

        try {
            // 1) staging 구성: manifest + project/ + vibecoder/ + keystores/
            rmrf(staging); Files.createDirectories(staging)
            Files.writeString(staging.resolve("manifest.json"), manifestJson)
            if (projRoot.exists()) copyTree(projRoot, staging.resolve("project"))
            if (vibeDir.exists()) copyTree(vibeDir, staging.resolve("vibecoder"))
            if (ksFiles.isNotEmpty()) {
                val ksStage = staging.resolve("keystores").also { Files.createDirectories(it) }
                ksFiles.forEach { copyFile(it, ksStage.resolve(it.name)) }
            }

            // 2) tar.gz 생성 + 검증
            if (!run(listOf("tar", "czf", tar.toString(), "-C", staging.toString(), "."))) {
                runCatching { Files.deleteIfExists(tar) }
                throw IllegalStateException("tar_failed")
            }
            if (!tar.exists() || tar.fileSize() <= 0L) throw IllegalStateException("archive_empty")
            val size = tar.fileSize()

            // 3) 레지스트리 등록
            archivedRepo.insert(
                ArchivedProjectRow(
                    id = archiveId, originalId = p.id, name = p.name, packageName = p.packageName,
                    archivedAt = archivedAt.toString(), archivePath = tar.toString(),
                    sizeBytes = size, manifestJson = manifestJson,
                )
            )

            // 4) H2 — DB 정리(자식+row) **성공 확인 후에만** 원본 삭제. 실패 시 등록 롤백 +
            //    tar 제거 + 원본 보존(고아 row/폴더 증발 방지).
            val deleted = runCatching { deleteProject(projectId) }.getOrElse { false }
            if (!deleted) {
                runCatching { archivedRepo.delete(archiveId) }
                runCatching { Files.deleteIfExists(tar) }
                throw IllegalStateException("db_cleanup_failed")
            }
            rmrf(projRoot)
            rmrf(vibeDir)
            ksFiles.forEach { runCatching { Files.deleteIfExists(it) } }

            log.info { "project archived: $projectId → $archiveId ($size bytes)" }
            return archiveId
        } finally {
            rmrf(staging) // L1 — 부분 실패 포함 항상 정리
        }
    }

    /** 아카이브를 프로젝트로 복원. tar 해제 + 폴더/키스토어 되돌림 + Projects row 재삽입. */
    fun unarchive(archiveId: String): Unit = synchronized(lock) {
        val row = archivedRepo.findById(archiveId)
            ?: throw IllegalArgumentException("archive_not_found: $archiveId")
        val manifest = json.decodeFromString<ArchiveManifest>(row.manifestJson)
        val targetId = manifest.originalId

        if (projectRepo.findById(targetId) != null) throw IllegalStateException("project_exists: $targetId")
        val projRoot = workspace.projectRoot(targetId)
        if (projRoot.exists()) throw IllegalStateException("folder_exists: $targetId")
        // H3 — 같은 packageName 의 **현역 키스토어**가 있으면 복원이 덮어쓰므로 거부(서명키 보호).
        if (keystore.keystoreFiles(manifest.packageName).isNotEmpty())
            throw IllegalStateException("keystore_conflict: ${manifest.packageName}")
        val tar = safeArchivePath(row.archivePath) ?: throw IllegalStateException("archive_path_invalid")
        if (!tar.exists()) throw IllegalStateException("archive_file_missing")

        val restore = workspace.archivesDir().resolve(".restore-$archiveId")
        try {
            rmrf(restore); Files.createDirectories(restore)
            // M3 — hardening: 절대경로(`/etc/..`)/소유자 무시. 해제는 격리 디렉토리에만.
            if (!run(listOf("tar", "xzf", tar.toString(), "-C", restore.toString(),
                    "--no-absolute-names", "--no-same-owner"))) {
                throw IllegalStateException("untar_failed")
            }

            restore.resolve("project").takeIf { it.exists() }?.let { rp ->
                projRoot.parent?.let { Files.createDirectories(it) }
                Files.move(rp, projRoot)
            }
            restore.resolve("vibecoder").takeIf { it.exists() }?.let { rv ->
                val vd = workspace.vibecoderDir(targetId); rmrf(vd); Files.move(rv, vd)
            }
            // 키스토어 복원 — 위 H3 가드로 충돌이 없음을 보장. 방어적으로 미존재 시에만 기록.
            restore.resolve("keystores").takeIf { it.exists() }?.let { rk ->
                val ksDir = keystore.keystoreDirPath().also { Files.createDirectories(it) }
                Files.list(rk).use { s ->
                    s.forEach { f ->
                        val dst = ksDir.resolve(f.name)
                        if (Files.notExists(dst)) copyFile(f, dst)
                    }
                }
            }

            // DB row 재삽입 — sourcePath 는 현재 환경 경로로 갱신
            projectRepo.insert(
                targetId, manifest.name, manifest.packageName,
                projRoot.toString(), manifest.moduleName, manifest.debugTask, manifest.projectType,
            )
            archivedRepo.delete(archiveId)
            runCatching { Files.deleteIfExists(tar) }
            log.info { "project unarchived: $archiveId → $targetId" }
        } finally {
            rmrf(restore)
        }
    }

    /** 아카이브 영구 삭제 (tar 파일 + 레지스트리). */
    fun deleteArchive(archiveId: String): Boolean = synchronized(lock) {
        val row = archivedRepo.findById(archiveId) ?: return false
        safeArchivePath(row.archivePath)?.let { runCatching { Files.deleteIfExists(it) } }
        archivedRepo.delete(archiveId) > 0
    }

    // ── 백업/복원 (아카이브와 달리 원본 보존 + 다른 서버 이식) ─────────────────────────
    //
    // 같은 tar 레이아웃(manifest.json + project/ + vibecoder/ + keystores/)을 archive 와 공유하므로
    // 다른 서버에서 [restoreFromTar] 로 동일 상태 복원 가능. archive(보관 후 원본 삭제)와 달리
    // backup 은 원본/DB 를 건드리지 않고 다운로드용 tar 만 만든다.

    /**
     * v1.132.0 — 프로젝트를 portable tar.gz 로 백업(원본/DB 불변).
     * @return 생성된 tar 경로. 호출측이 전송 후 [Files.deleteIfExists] 로 정리.
     */
    fun backupToTar(projectId: String): Path = synchronized(lock) {
        require(projectId != "__scratch__") { "scratch_protected" }
        val p = projectRepo.findById(projectId)
            ?: throw IllegalArgumentException("project_not_found: $projectId")
        val now = Instant.now()
        val ts = tsFmt.format(now)
        val manifestJson = json.encodeToString(
            ArchiveManifest(
                originalId = p.id, name = p.name, packageName = p.packageName,
                sourcePath = p.sourcePath, moduleName = p.moduleName, debugTask = p.debugTask,
                createdAt = p.createdAt, archivedAt = now.toString(), projectType = p.projectType,
            )
        )
        val archivesDir = workspace.archivesDir()
        val staging = archivesDir.resolve(".backup-staging-$projectId-$ts")
        val tar = archivesDir.resolve(".backup-$projectId-$ts.tar.gz")
        val projRoot = workspace.projectRoot(projectId)
        val vibeDir = workspace.vibecoderDir(projectId)
        val ksFiles = keystore.keystoreFiles(p.packageName)
        try {
            rmrf(staging); Files.createDirectories(staging)
            Files.writeString(staging.resolve("manifest.json"), manifestJson)
            if (projRoot.exists()) copyTree(projRoot, staging.resolve("project"))
            if (vibeDir.exists()) copyTree(vibeDir, staging.resolve("vibecoder"))
            if (ksFiles.isNotEmpty()) {
                val ksStage = staging.resolve("keystores").also { Files.createDirectories(it) }
                ksFiles.forEach { copyFile(it, ksStage.resolve(it.name)) }
            }
            runCatching { Files.deleteIfExists(tar) }
            if (!run(listOf("tar", "czf", tar.toString(), "-C", staging.toString(), "."))) {
                runCatching { Files.deleteIfExists(tar) }
                throw IllegalStateException("tar_failed")
            }
            if (!tar.exists() || tar.fileSize() <= 0L) throw IllegalStateException("backup_empty")
            log.info { "project backup created: $projectId → ${tar.name} (${tar.fileSize()} bytes)" }
            return tar
        } finally {
            rmrf(staging)
        }
    }

    /**
     * v1.132.0 — 업로드된 백업 tar.gz 로 프로젝트 복원(다른 서버 이전). 폴더/키스토어 충돌 시 거부.
     * archived_projects 레지스트리와 무관(다른 서버에서 만든 백업도 복원). @return 복원된 projectId.
     */
    fun restoreFromTar(tarPath: Path): String = synchronized(lock) {
        if (!tarPath.exists()) throw IllegalStateException("backup_file_missing")
        val restore = workspace.archivesDir().resolve(".restore-upload-${tsFmt.format(Instant.now())}")
        try {
            rmrf(restore); Files.createDirectories(restore)
            // hardening: 절대경로/소유자 무시. 해제는 격리 디렉토리에만.
            if (!run(listOf("tar", "xzf", tarPath.toString(), "-C", restore.toString(),
                    "--no-absolute-names", "--no-same-owner"))) {
                throw IllegalStateException("untar_failed")
            }
            val manifestFile = restore.resolve("manifest.json")
            if (!manifestFile.exists()) throw IllegalStateException("manifest_missing")
            val manifest = json.decodeFromString<ArchiveManifest>(Files.readString(manifestFile))
            val targetId = manifest.originalId
            require(targetId.isNotBlank() && targetId != "__scratch__") { "invalid_target" }
            if (projectRepo.findById(targetId) != null) throw IllegalStateException("project_exists: $targetId")
            val projRoot = workspace.projectRoot(targetId)
            if (projRoot.exists()) throw IllegalStateException("folder_exists: $targetId")
            // 같은 packageName 의 현역 키스토어가 있으면 복원이 덮어쓰므로 거부(서명키 보호).
            if (keystore.keystoreFiles(manifest.packageName).isNotEmpty())
                throw IllegalStateException("keystore_conflict: ${manifest.packageName}")

            restore.resolve("project").takeIf { it.exists() }?.let { rp ->
                projRoot.parent?.let { Files.createDirectories(it) }
                Files.move(rp, projRoot)
            }
            restore.resolve("vibecoder").takeIf { it.exists() }?.let { rv ->
                val vd = workspace.vibecoderDir(targetId); rmrf(vd); Files.move(rv, vd)
            }
            restore.resolve("keystores").takeIf { it.exists() }?.let { rk ->
                val ksDir = keystore.keystoreDirPath().also { Files.createDirectories(it) }
                Files.list(rk).use { s ->
                    s.forEach { f ->
                        val dst = ksDir.resolve(f.name)
                        if (Files.notExists(dst)) copyFile(f, dst)
                    }
                }
            }
            projectRepo.insert(
                targetId, manifest.name, manifest.packageName,
                projRoot.toString(), manifest.moduleName, manifest.debugTask, manifest.projectType,
            )
            log.info { "project restored from upload: $targetId" }
            return targetId
        } finally {
            rmrf(restore)
        }
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

    /**
     * M4 — stdout 을 **별도 데몬 스레드**로 drain 하므로 readText 블로킹이 waitFor 타임아웃을
     * 무력화하지 않는다(거대 출력에도 timeout 실동작). 스트림은 use 로 닫는다.
     */
    private fun run(cmd: List<String>, timeoutSec: Long = 600): Boolean = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val drain = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    r.forEachLine { l -> synchronized(sb) { if (sb.length < 4000) sb.appendLine(l) } }
                }
            }
        }.apply { isDaemon = true; name = "archive-proc-drain"; start() }
        if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly(); drain.join(1000); return false
        }
        drain.join(2000)
        if (proc.exitValue() != 0) {
            log.warn { "cmd ${cmd.take(2)} exit=${proc.exitValue()}: ${synchronized(sb) { sb.toString() }.take(500)}" }
            false
        } else true
    }.getOrElse { log.warn(it) { "cmd error ${cmd.take(2)}" }; false }
}
