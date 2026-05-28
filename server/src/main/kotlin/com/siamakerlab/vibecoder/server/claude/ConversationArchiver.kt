package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.db.ConversationTurns
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.IsNotNullOp
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * v0.33.0 — Claude 세션 자동 archive.
 *
 * - 매 24h tick.
 * - `archiveAfterDays` (기본 30) 이상 inactive 한 (projectId, sessionId) pair
 *   를 찾아 그 session 의 turn 들을 JSON envelope 으로 dump:
 *     `<workspace>/.vibecoder/<projectId>/archive/session-<sid>.json`
 * - dump 성공 시 `conversation_turns` 에서 해당 session 의 row 삭제.
 *
 * 별도 archive 테이블 안 만듦 — 파일 시스템에 두면 backup / 외부 export 양쪽 모두 단순.
 * 복원은 `ConversationExportService.importToProject(json)` 그대로 사용.
 */
class ConversationArchiver(
    private val workspace: WorkspacePath,
    private val archiveAfterDays: Int = 30,
) {

    data class Result(val sessionsExported: Int, val sessionsDeleted: Int, val skipped: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val json = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = false }

    fun start(intervalHours: Long = 24) {
        if (pollJob != null) return
        pollJob = scope.launch {
            log.info { "ConversationArchiver started (archiveAfterDays=$archiveAfterDays, intervalHours=$intervalHours)" }
            delay(60_000)
            while (isActive) {
                runCatching { runOnce(dryRun = false) }.onFailure { log.warn(it) { "archive tick failed" } }
                delay(Duration.ofHours(intervalHours).toMillis())
            }
        }
    }

    fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        scope.cancel()
    }

    /**
     * 단발 archive 실행. 한 사이클에서 발견된 inactive 세션 모두 처리.
     */
    fun runOnce(dryRun: Boolean = true): Result {
        val cutoffIso = Instant.now().minus(Duration.ofDays(archiveAfterDays.toLong())).toString()

        // Step 1: 모든 (projectId, sessionId) 의 max ts 조회.
        val candidates: Map<Pair<String, String>, String> = transaction {
            ConversationTurns
                .select(ConversationTurns.projectId, ConversationTurns.sessionId, ConversationTurns.ts)
                .where { IsNotNullOp(ConversationTurns.sessionId) }
                .orderBy(ConversationTurns.ts to SortOrder.DESC)
                .limit(100_000)
                .map { Triple(it[ConversationTurns.projectId], it[ConversationTurns.sessionId]!!, it[ConversationTurns.ts]) }
                .groupBy { it.first to it.second }
                .mapValues { (_, rows) -> rows.maxOf { it.third } }
                .filterValues { it <= cutoffIso }
        }
        if (candidates.isEmpty()) {
            log.info { "archive: 0 inactive sessions older than $archiveAfterDays days." }
            return Result(0, 0, 0)
        }
        log.info { "archive: ${candidates.size} candidate sessions (cutoff=$cutoffIso, dryRun=$dryRun)" }

        var exported = 0
        var deleted = 0
        var skipped = 0
        for ((key, _) in candidates) {
            val (pid, sid) = key
            val sessionJson = buildSessionEnvelope(pid, sid)
            if (sessionJson == null) {
                skipped++
                continue
            }
            val dir = workspace.root.resolve(".vibecoder").resolve(pid).resolve("archive")
            val ok = runCatching {
                if (!dryRun) {
                    Files.createDirectories(dir)
                    // v1.31.0 (C1 회수) — 파일명에 archive 시각 포함 + 무조건 write.
                    // 이전엔 `session-<sid>.json` 고정 + exists 시 skip 인데 delete 는
                    // 무조건 실행 → 같은 sessionId 가 --resume 으로 재활성돼 새 turn 이
                    // 쌓인 뒤 다시 archive 되면, 옛 파일은 그대로 두고 새 turn 만 DB 에서
                    // 삭제 = 데이터 영구 유실. 매 archive 를 고유 파일로 써서 손실 차단.
                    val target = dir.resolve("session-$sid-${Instant.now().toEpochMilli()}.json")
                    Files.writeString(target, sessionJson)
                }
                true
            }.getOrElse { e ->
                log.warn(e) { "archive write failed: $pid/$sid" }
                false
            }
            if (!ok) {
                skipped++
                continue
            }
            exported++
            if (!dryRun) {
                val n = transaction {
                    ConversationTurns.deleteWhere {
                        (ConversationTurns.projectId eq pid) and (ConversationTurns.sessionId eq sid)
                    }
                }
                if (n > 0) deleted++
            }
        }
        log.info { "archive done: exported=$exported deleted=$deleted skipped=$skipped" }
        return Result(exported, deleted, skipped)
    }

    private fun buildSessionEnvelope(projectId: String, sessionId: String): String? {
        val turns = transaction {
            ConversationTurns.selectAll()
                .where { (ConversationTurns.projectId eq projectId) and (ConversationTurns.sessionId eq sessionId) }
                .orderBy(ConversationTurns.turnIdx to SortOrder.ASC)
                .map {
                    ConversationExportService.TurnRecord(
                        sessionId = it[ConversationTurns.sessionId],
                        turnIdx = it[ConversationTurns.turnIdx],
                        ts = it[ConversationTurns.ts],
                        role = it[ConversationTurns.role],
                        content = it[ConversationTurns.content],
                        toolName = it[ConversationTurns.toolName],
                        toolUseId = it[ConversationTurns.toolUseId],
                        tokensIn = it[ConversationTurns.tokensIn],
                        tokensOut = it[ConversationTurns.tokensOut],
                        raw = it[ConversationTurns.raw],
                    )
                }
        }
        if (turns.isEmpty()) return null
        val env = ConversationExportService.ExportEnvelope(
            projectId = projectId,
            exportedAt = Instant.now().toString(),
            turnCount = turns.size,
            turns = turns,
        )
        return json.encodeToString(ConversationExportService.ExportEnvelope.serializer(), env)
    }
}
