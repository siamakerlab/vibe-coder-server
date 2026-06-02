package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.91.0 — 독립 메모 (전역 / 프로젝트별).
 *
 * 기존 conversation_turns.user_memo (turn-scoped 인라인 메모, v0.61.0 Phase 40) 와는
 * 별개의 free-form 메모. `projectId` 가 null 이면 **전역 메모** (모든 프로젝트 화면에서
 * 노출), non-null 이면 **해당 프로젝트 전용**.
 *
 * 프로젝트 콘솔 화면 우측 rail (프롬프트 히스토리 하단) 과 좌측 사이드바 `/memos`
 * 카드형 목록 양쪽에서 사용. `id` 는 서버가 `Ids.taskId()` (ULID-like) 로 발급.
 */
@Serializable
data class MemoDto(
    val id: String,
    /** null = 전역 메모, non-null = 해당 프로젝트 전용. */
    val projectId: String? = null,
    val content: String,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * POST /api/memos 의 body. `projectId` 가 null 이면 전역 메모로 생성.
 * 응답: [MemoDto].
 */
@Serializable
data class MemoCreateRequestDto(
    val projectId: String? = null,
    val content: String,
)

/**
 * PUT /api/memos/{id} 의 body. 본문 수정 + scope (전역↔프로젝트) 변경 겸용.
 * `projectId` 미지정(field 누락) 시 scope 유지를 위해 `keepScope=true` 사용.
 * 응답: [MemoDto].
 */
@Serializable
data class MemoUpdateRequestDto(
    val content: String,
    /** scope 변경 대상. [keepScope] 가 false 일 때만 적용. */
    val projectId: String? = null,
    /** true 면 기존 scope (projectId) 를 그대로 유지하고 content 만 갱신. */
    val keepScope: Boolean = true,
)

/**
 * GET /api/memos 응답. `?projectId=X` 면 전역 + 프로젝트 X 메모, 없으면 전체.
 */
@Serializable
data class MemoListResponseDto(
    val memos: List<MemoDto> = emptyList(),
)

/**
 * DELETE /api/memos/{id} 의 ack. `{ "ok": true }`.
 */
@Serializable
data class MemoMutationAckDto(
    val ok: Boolean = true,
)
