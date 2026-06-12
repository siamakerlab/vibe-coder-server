package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.64.0 (Phase 43) — Conversation history JSON API wire (server v0.16+ 기능을
 * 정식 JSON endpoint 로 노출).
 *
 * 서버 [com.siamakerlab.vibecoder.server.repo.ConversationTurnRow] 한 행을 클라이언트가
 * 받는 모양. SSR-only `/projects/{id}/history` (HTML) 와 동등한 정보지만 Bearer
 * 토큰 인증으로 호출 가능한 `/api/projects/{id}/history` (JSON) + `/api/chat/history`
 * (JSON) 에서 emit.
 *
 * `id` 는 **String** — 서버는 `Ids.taskId()` (ULID-like) 로 발급. Android v0.7.18
 * 까지의 `Long` 정의는 잘못된 추정이었으며 v0.7.19 catch-up 시 String 으로 정렬.
 */
@Serializable
data class HistoryTurnDto(
    val id: String,
    val sessionId: String? = null,
    val turnIdx: Int,
    val ts: String,
    /** [HistoryTurnRole] 의 알려진 값 또는 forward-compat unknown. */
    val role: String,
    val content: String,
    val toolName: String? = null,
    val toolUseId: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
    /** v0.52.0+ — null = main project console; non-null = sub-agent. */
    val agentName: String? = null,
    /** v0.61.0+ — user memo (UI inline editor). null/blank = 없음. */
    val userMemo: String? = null,
    /** v0.61.0+ — ★ 표시 여부. */
    val starred: Boolean = false,
    /**
     * v1.138.0 — user turn 의 첨부 이미지 수 (raw 의 image 블록). 0 = 없음. 바이트는
     * `GET …/claude/console/image?turn=<turnIdx>&idx=<i>` 로 로드. additive default.
     */
    val imageCount: Int = 0,
)

/**
 * GET /api/projects/{id}/history 와 GET /api/chat/history 의 응답.
 *
 * `nextCursor` 가 null 이면 더 가져올 row 없음. 있다면 같은 endpoint 에
 * `?before=<cursor>` 로 다음 페이지. 커서는 가장 오래된 row 의 id (String).
 */
@Serializable
data class HistoryPageDto(
    val turns: List<HistoryTurnDto>,
    val nextCursor: String? = null,
)

/**
 * POST /api/projects/{id}/history/{turnId}/memo 의 body.
 * `memo` 가 null 이거나 blank 면 메모 삭제.
 * 응답: [HistoryMutationAckDto].
 */
@Serializable
data class HistoryMemoUpdateRequestDto(
    val memo: String? = null,
)

/**
 * memo/star toggle 의 공통 ack 응답. `{ "ok": true }`.
 */
@Serializable
data class HistoryMutationAckDto(
    val ok: Boolean = true,
)

/**
 * v0.63.0 (Phase 42) — role="usage" turn 의 [HistoryTurnDto.content] 안에 들어가는
 * JSON 스키마.
 *
 * 모든 값은 토큰 수. 호출자는 `Json.decodeFromString<UsageReportDto>(turn.content)`
 * 로 파싱. `cacheRead` / `cacheCreate` 가 0 이 아니면 Anthropic 캐시 hit/miss
 * 추적 가능.
 */
@Serializable
data class UsageReportDto(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreate: Long = 0,
) {
    val totalTokens: Long get() = input + output + cacheRead + cacheCreate
    /** 캐시 hit 비율 (read / (read + create)). 분모 0 이면 null. */
    val cacheHitRate: Double? get() {
        val total = cacheRead + cacheCreate
        return if (total > 0) cacheRead.toDouble() / total else null
    }
}

/**
 * v0.64.0 — GET /api/usage 응답 (전 기간 집계 + 일별 시계열).
 * 서버는 conversation_turns 의 role="usage" row 들을 합산.
 */
@Serializable
data class UsageSummaryDto(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreate: Long = 0,
    /** 캐시 hit rate (0.0~1.0). 분모 0 이면 null. */
    val hitRate: Double? = null,
    /** ts ASC 정렬, 최근 N 일. */
    val byDay: List<UsageDailyDto> = emptyList(),
)

@Serializable
data class UsageDailyDto(
    /** ISO date `yyyy-MM-dd`. */
    val date: String,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreate: Long = 0,
)

/**
 * [HistoryTurnDto.role] 의 알려진 값. 서버가 새 값을 보내면 클라이언트에서
 * unknown 분기로 fallback (forward-compat).
 */
object HistoryTurnRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL_USE = "tool_use"
    const val TOOL_RESULT = "tool_result"
    const val TOOL_RESULT_ERROR = "tool_result_error"
    const val SYSTEM = "system"
    const val ERROR = "error"
    /** v0.63.0+ — Anthropic usage report. content 는 [UsageReportDto] JSON string. */
    const val USAGE = "usage"
    const val UNKNOWN = "unknown"
}
