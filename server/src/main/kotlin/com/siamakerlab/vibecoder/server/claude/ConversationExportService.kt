package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import com.siamakerlab.vibecoder.server.repo.ConversationTurnRow
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * v0.31.0 — 프로젝트 conversation_turns JSON export / import.
 *
 * 사용 시점:
 *   - 다른 머신 / 다른 vibe-coder 인스턴스로 대화 history 이전.
 *   - 백업 (audit 와 별개로 정리된 turn 단위 record).
 *   - 외부 분석 도구로 전달 (LangChain trace 등).
 *
 * JSON 포맷은 안정적 (v1 schema 표기). 호환성 위해 `schemaVersion` 필드.
 *
 * Import 는 sessionId 를 그대로 보존 — 같은 프로젝트에 다시 import 하면
 * turn idx 가 충돌할 수 있으므로 dryRun 모드로 우선 미리 보기 가능.
 */
class ConversationExportService(
    private val repo: ConversationTurnRepository,
) {

    @Serializable
    data class ExportEnvelope(
        val schemaVersion: Int = 1,
        val projectId: String,
        val exportedAt: String,
        val turnCount: Int,
        val turns: List<TurnRecord>,
    )

    @Serializable
    data class TurnRecord(
        val sessionId: String? = null,
        val turnIdx: Int,
        val ts: String,
        val role: String,
        val content: String,
        val toolName: String? = null,
        val toolUseId: String? = null,
        val tokensIn: Int? = null,
        val tokensOut: Int? = null,
        val raw: String? = null,
        /** v0.70.0 — Phase 49 #10. user memo (v0.61+). null/blank = 없음. */
        val userMemo: String? = null,
        /** v0.70.0 — Phase 49 #10. ★ 표시 (v0.61+). */
        val starred: Boolean = false,
    )

    @Serializable
    data class ImportResult(
        val accepted: Int,
        val skipped: Int,
        val dryRun: Boolean,
        val warnings: List<String>,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    /** Project 전체 turn 을 JSON envelope 로. 큰 프로젝트는 수 MB 가능. */
    fun exportProject(projectId: String): String {
        val rows = repo.list(
            ConversationTurnRepository.Filter(projectId = projectId),
            limit = 1000, offset = 0,
        )
        // limit=1000 으로 한 페이지에 다 안 들어올 수 있음 — pagination loop
        val all = mutableListOf<ConversationTurnRow>()
        all.addAll(rows)
        var offset = rows.size.toLong()
        while (true) {
            val more = repo.list(
                ConversationTurnRepository.Filter(projectId = projectId),
                limit = 1000, offset = offset,
            )
            if (more.isEmpty()) break
            all.addAll(more)
            offset += more.size
            if (all.size > 100_000) break  // safety cap
        }

        val envelope = ExportEnvelope(
            projectId = projectId,
            exportedAt = java.time.Instant.now().toString(),
            turnCount = all.size,
            turns = all.map { it.toRecord() },
        )
        return json.encodeToString(ExportEnvelope.serializer(), envelope)
    }

    /**
     * JSON envelope 를 받아 turn 들을 같은 / 다른 projectId 에 import.
     *
     * dryRun=true 면 검증만 + 카운트 보고 (DB 변경 없음).
     * dryRun=false 면 실제 INSERT. sessionId / turnIdx 충돌 시 skip + warning.
     */
    fun importToProject(
        targetProjectId: String,
        envelopeJson: String,
        dryRun: Boolean,
    ): ImportResult {
        val envelope = try {
            json.decodeFromString(ExportEnvelope.serializer(), envelopeJson)
        } catch (e: Throwable) {
            return ImportResult(0, 0, dryRun, listOf("invalid envelope: ${e.message}"))
        }
        if (envelope.schemaVersion != 1) {
            return ImportResult(0, 0, dryRun, listOf("unsupported schemaVersion ${envelope.schemaVersion}"))
        }
        val warnings = mutableListOf<String>()
        if (envelope.projectId != targetProjectId) {
            warnings += "envelope projectId='${envelope.projectId}' → import target='$targetProjectId' (allowed)"
        }

        // sessionId 기반 중복 방지: 이미 같은 sessionId 의 turn 이 존재하면 그 세션은 통째로 skip.
        // (단일 행 단위 중복 확인 / merge 는 복잡 — sessionId 단위 idempotency 로 단순화.)
        val existingSessionIds = repo.distinctSessions(targetProjectId).toSet()

        var accepted = 0
        var skipped = 0
        for (rec in envelope.turns) {
            if (rec.sessionId != null && rec.sessionId in existingSessionIds) {
                skipped++
                continue
            }
            if (!dryRun) {
                runCatching {
                    val row = repo.insert(
                        projectId = targetProjectId,
                        sessionId = rec.sessionId,
                        role = rec.role,
                        content = rec.content,
                        toolName = rec.toolName,
                        toolUseId = rec.toolUseId,
                        tokensIn = rec.tokensIn,
                        tokensOut = rec.tokensOut,
                        raw = rec.raw,
                    )
                    // v0.70.0 — Phase 49 #10: memo/star 적용 (insert 후 별도 setter).
                    if (!rec.userMemo.isNullOrBlank()) {
                        runCatching { repo.setMemo(row.id, rec.userMemo) }
                    }
                    if (rec.starred) {
                        runCatching { repo.setStarred(row.id, true) }
                    }
                    accepted++
                }.onFailure { e ->
                    skipped++
                    if (warnings.size < 20) warnings += "row session=${rec.sessionId?.take(8)} skipped: ${e.message?.take(80)}"
                }
            } else {
                accepted++
            }
        }
        if (skipped > 0) warnings += "Sessions present in target are skipped wholesale (sessionId-level idempotency)."
        log.info { "import to $targetProjectId: accepted=$accepted skipped=$skipped dryRun=$dryRun" }
        return ImportResult(accepted, skipped, dryRun, warnings)
    }

    private fun ConversationTurnRow.toRecord() = TurnRecord(
        sessionId = sessionId,
        turnIdx = turnIdx,
        ts = ts,
        role = role,
        content = content,
        toolName = toolName,
        toolUseId = toolUseId,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        raw = raw,
        userMemo = userMemo,
        starred = starred,
    )
}
