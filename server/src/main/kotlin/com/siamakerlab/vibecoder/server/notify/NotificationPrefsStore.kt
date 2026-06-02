package com.siamakerlab.vibecoder.server.notify

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.shared.dto.NotificationKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * v1.89.0 — 알림 종류(kind)별 수신 on/off 설정 저장소 (workspace 전역).
 *
 * `/settings/notifications` 에서 사용자가 끈 kind 는 [NotificationService.emit] 이 적재 자체를
 * skip 한다. 설정은 `<workspace>/.vibecoder/notification-prefs.json` 에 영속(즉시 반영 — 재시작
 * 불요). 파일에 명시되지 않은 kind 는 **기본 on**(opt-out 방식 — 새 kind 추가 시 자동 수신).
 *
 * 동시성: [ReentrantReadWriteLock] + 메모리 캐시. emit 경로(read)가 빈번하므로 read 우선.
 */
class NotificationPrefsStore(
    private val workspace: WorkspacePath,
) {
    /** 설정 가능한 kind 목록 (UI 렌더 순서이기도 함). */
    val knownKinds: List<String> = listOf(
        NotificationKind.BUILD_SUCCESS,
        NotificationKind.BUILD_FAILED,
        NotificationKind.CLAUDE_TURN_DONE,
        NotificationKind.CLAUDE_STOPPED,
        NotificationKind.CLAUDE_ERROR,
        NotificationKind.USAGE_THRESHOLD,
        NotificationKind.SYSTEM,
    )

    @Serializable
    private data class Storage(val disabled: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }
    private val lock = ReentrantReadWriteLock()

    /** 비활성(disabled) kind 집합 — 캐시. null = 아직 미로딩. */
    @Volatile private var disabledCache: Set<String>? = null

    private fun path(): Path =
        workspace.root.resolve(".vibecoder").resolve("notification-prefs.json")

    private fun loadDisabled(): Set<String> {
        disabledCache?.let { return it }
        return lock.write {
            disabledCache?.let { return@write it }
            val p = path()
            val loaded = if (p.exists()) {
                runCatching { json.decodeFromString(Storage.serializer(), Files.readString(p)).disabled.toSet() }
                    .getOrElse {
                        log.warn(it) { "notification-prefs.json 파싱 실패 — 전체 on 으로 fallback" }
                        emptySet()
                    }
            } else emptySet()
            disabledCache = loaded
            loaded
        }
    }

    /** [kind] 알림을 수신할지. 파일에 명시 없으면 true(기본 on). */
    fun isEnabled(kind: String): Boolean = kind !in loadDisabled()

    /**
     * 현재 enabled 상태 맵 (knownKinds 전부 — UI 렌더용).
     *
     * read lock 으로 감싸지 않는다 — [loadDisabled] 가 cache miss 시 내부에서 write lock 을
     * 잡는데, read 보유 중이면 ReentrantReadWriteLock 의 업그레이드 미지원으로 데드락이 된다.
     * [loadDisabled] 가 자체 동기화하고, knownKinds 는 불변이라 추가 락이 불필요하다.
     */
    fun currentEnabled(): Map<String, Boolean> {
        val disabled = loadDisabled()
        return knownKinds.associateWith { it !in disabled }
    }

    /**
     * [enabledKinds] 에 포함된 kind 만 on, 나머지 knownKinds 는 off 로 저장.
     * (폼 체크박스는 체크된 것만 전송되므로 "체크된 집합" 을 그대로 받는다.)
     */
    fun saveEnabled(enabledKinds: Set<String>) {
        val disabled = knownKinds.filter { it !in enabledKinds }.toSet()
        lock.write {
            val p = path()
            runCatching {
                Files.createDirectories(p.parent)
                val tmp = p.resolveSibling("notification-prefs.json.tmp")
                Files.writeString(tmp, json.encodeToString(Storage.serializer(), Storage(disabled.sorted())))
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                disabledCache = disabled
            }.onFailure { log.warn(it) { "notification-prefs.json 저장 실패" } }
        }
    }
}
