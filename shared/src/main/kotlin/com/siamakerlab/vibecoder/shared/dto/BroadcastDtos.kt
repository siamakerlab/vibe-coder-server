package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.136.0 — 프롬프트 일괄 전송 (`ApiPath.CLAUDE_BROADCAST`).
 *
 * 선택한 여러 프로젝트의 메인 콘솔에 같은 프롬프트를 한 번에 전송한다. 서버는 즉시
 * 202 로 응답하고 각 프로젝트 전송은 비동기로 진행 — 동시 turn 게이트
 * (`claude.maxConcurrentTurns`)가 한도 초과분을 순차 처리(큐)한다.
 */
@Serializable
data class BroadcastSendRequestDto(
    val prompt: String,
    val projectIds: List<String>,
)

/** 거부된 프로젝트와 사유 (`not_found` 등). */
@Serializable
data class BroadcastRejectDto(
    val projectId: String,
    val reason: String,
)

@Serializable
data class BroadcastSendResponseDto(
    /** 비동기 전송이 시작된(큐 포함) 프로젝트 id 목록. */
    val accepted: List<String>,
    val rejected: List<BroadcastRejectDto> = emptyList(),
)
