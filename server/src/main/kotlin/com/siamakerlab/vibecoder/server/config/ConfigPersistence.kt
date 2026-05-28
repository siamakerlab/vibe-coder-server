package com.siamakerlab.vibecoder.server.config

import com.charleskorn.kaml.Yaml
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * server.yml 영구화 + 백업/롤백 — v0.12.4.
 *
 * 운영 환경에서 admin 이 /settings UI 로 값을 수정하면 외부 config 파일에 기록.
 * 일부 값(서버 host/port/name)은 startup 시점에만 적용되므로 사용자에게
 * "재시작 필요" 안내.
 *
 * 쓰기 정책:
 *  1. 외부 config 경로 (`$VIBECODER_CONFIG_DIR/server.yml` 또는 `./config/server.yml`)
 *     를 [resolveTargetPath] 로 결정. 부재 시 생성.
 *  2. 이전 파일이 있으면 `server.yml.bak.<ts>` 로 백업. 최근 [MAX_BACKUPS] 개 유지 (rotation).
 *  3. tmp → atomic move → 본 파일. 실패 시 백업으로 복구.
 *
 * **classpath default 는 절대 덮어쓰지 않음** — 컨테이너 이미지의 내장 파일 보호.
 */
object ConfigPersistence {

    private const val MAX_BACKUPS = 5

    /** YAML 직렬화 시 사용할 인스턴스. encode 는 default 가 안전 (key ordering 유지). */
    private val yaml = Yaml.default

    data class SaveResult(
        val targetPath: Path,
        val backupPath: Path?,
        val wasCreated: Boolean,
    )

    /**
     * [newConfig] 를 외부 config 파일에 기록.
     *
     * 검증: ServerConfig 의 valueOf 가능 여부는 호출자 (라우트) 가 책임. 본 메서드는
     * 직렬화 + 디스크 쓰기만 담당.
     */
    // v1.31.0 (C-B1 회수) — 동시 /settings 저장 직렬화. 이전엔 동기화 없이 고정 tmp
    // 파일명(`server.yml.tmp`)을 공유 → 두 저장이 거의 동시에 오면 tmp 가 서로 덮어써져
    // 깨진 YAML move 가능, backup rotation 도 같은 초에 비결정적. @Synchronized 로
    // save 를 직렬화 + tmp 에 고유 suffix 추가(이중 안전).
    @Synchronized
    fun save(newConfig: ServerConfig): SaveResult {
        val target = resolveTargetPath()
        Files.createDirectories(target.parent)

        val backup: Path? = if (target.exists()) {
            val ts = Instant.now().toString().replace(":", "").replace("-", "")
            val bk = target.resolveSibling("${target.fileName}.bak.$ts")
            try {
                Files.copy(target, bk, StandardCopyOption.REPLACE_EXISTING)
                pruneOldBackups(target)
                bk
            } catch (e: Throwable) {
                log.warn(e) { "config 백업 실패: ${e.message}" }
                null
            }
        } else null

        val yamlText = yaml.encodeToString(ServerConfig.serializer(), newConfig)
        val tmp = target.resolveSibling("${target.fileName}.tmp.${System.nanoTime()}")
        try {
            Files.writeString(
                tmp, yamlText, Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            // 백업이 있으면 그대로 복구 시도
            if (backup != null && backup.exists()) {
                runCatching {
                    Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
                    log.warn { "config 쓰기 실패 → 백업 ($backup) 으로 롤백" }
                }
            }
            throw e
        }
        return SaveResult(target, backup, wasCreated = backup == null)
    }

    /**
     * 외부 config 파일의 경로 결정.
     *  1. $VIBECODER_CONFIG_DIR/server.yml
     *  2. ./config/server.yml (working directory)
     * (classpath default 는 read-only 라 쓰지 않음.)
     */
    fun resolveTargetPath(): Path {
        val explicit = System.getenv("VIBECODER_CONFIG_DIR")?.trim()
        return if (!explicit.isNullOrBlank()) {
            Path.of(explicit, "server.yml")
        } else {
            Path.of("config", "server.yml")
        }
    }

    /** 같은 파일 이름 prefix 의 백업 중 최근 [MAX_BACKUPS] 개만 유지. */
    private fun pruneOldBackups(target: Path) {
        val dir = target.parent ?: return
        val prefix = "${target.fileName}.bak."
        runCatching {
            Files.list(dir).use { stream ->
                val backups = stream
                    .filter { it.fileName.toString().startsWith(prefix) }
                    .toList()
                    .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
                backups.drop(MAX_BACKUPS).forEach { old ->
                    runCatching { Files.deleteIfExists(old) }
                }
            }
        }.onFailure { log.debug { "백업 정리 skip: ${it.message}" } }
    }
}
