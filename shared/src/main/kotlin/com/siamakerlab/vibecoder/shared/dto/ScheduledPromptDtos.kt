package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.130.0 — 프롬프트 예약 전송 (one-shot scheduled prompt) wire DTOs.
 *
 * 사용자가 지정한 **시점**이 되면 서버가 콘솔에 프롬프트 1회를 자동 전송한다.
 * (프롬프트 자동화 [PromptAutomationStartRequestDto] 가 turn 완료마다 연쇄 전송이라면,
 *  본 기능은 "나중에 1회" 라는 단발 예약이다.)
 *
 * 트리거 종류:
 *  - time          : 지정 시각(epoch millis) 도달 시. UI 의 "N분 뒤 / N시간 뒤 /
 *                    정확한 시각" 입력은 모두 생성 시점에 epoch 로 환산되어 저장된다.
 *  - session_reset : Claude **세션(5시간) 사용 한도 해제**가 감지되면.
 *  - weekly_reset  : Claude **주간(7일) 사용 한도 해제**가 감지되면.
 *
 * 발사는 항상 콘솔이 유휴(turn 미진행)일 때만 수행되어 깔끔한 새 turn 으로 시작된다.
 */
object ScheduledPromptTriggers {
    const val TIME = "time"
    const val SESSION_RESET = "session_reset"
    const val WEEKLY_RESET = "weekly_reset"
    val ALL = setOf(TIME, SESSION_RESET, WEEKLY_RESET)

    fun normalize(value: String?): String =
        value?.trim()?.lowercase()?.takeIf { it in ALL } ?: TIME
}

object ScheduledPromptStatus {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}

/** 예약 1건. */
@Serializable
data class ScheduledPromptDto(
    val id: String,
    val projectId: String,
    val prompt: String,
    val triggerType: String,                 // time | session_reset | weekly_reset
    /** time 트리거의 발사 시각(epoch millis). 리밋 트리거는 null. */
    val fireAtEpochMs: Long? = null,
    /** UI 표시용 사람이 읽는 라벨(서버가 생성 시 계산). 예: "30분 뒤", "세션 한도 해제 후". */
    val triggerLabel: String? = null,
    val status: String,                      // pending | sent | cancelled | failed
    val createdAt: String,
    val sentAt: String? = null,
    val lastError: String? = null,
)

/**
 * 예약 생성 요청. [triggerType] = time 이면 [atEpochMs] 우선, 없으면 [delayMinutes] 로
 * (now + 분) 환산. 리밋 트리거(session_reset/weekly_reset)는 시각 필드를 무시한다.
 */
@Serializable
data class ScheduleSendRequestDto(
    val prompt: String,
    val triggerType: String = ScheduledPromptTriggers.TIME,
    val atEpochMs: Long? = null,
    val delayMinutes: Long? = null,
)

@Serializable
data class ScheduledPromptsResponseDto(
    val schedules: List<ScheduledPromptDto> = emptyList(),
)
