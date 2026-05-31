package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.59.0 — 프롬프트 자동화 (서버 백그라운드 autopilot) wire DTOs.
 *
 * 콘솔 turn 이 완료될 때마다 서버가 다음 프롬프트를 자동 전송한다.
 *  - repeat   : 같은 프롬프트(prompts[0])를 [repeatCount] 회 반복.
 *  - sequence : prompts 리스트를 순서대로, 전체를 [loops] 회 반복(기본 1).
 *
 * 프리셋은 workspace 전역 저장(어느 프로젝트에서나 재사용), 실행(run)은 프로젝트별.
 */
object PromptAutomationMode {
    const val REPEAT = "repeat"
    const val SEQUENCE = "sequence"
    val ALL = setOf(REPEAT, SEQUENCE)
}

object PromptAutomationStatus {
    const val RUNNING = "running"
    const val DONE = "done"
    const val STOPPED = "stopped"
    const val FAILED = "failed"
}

/** 저장된 프리셋 (workspace 전역). */
@Serializable
data class PromptAutomationPresetDto(
    val id: String,
    val name: String,
    val mode: String,                 // repeat | sequence
    val prompts: List<String>,
    val repeatCount: Int = 1,         // repeat 모드 — 같은 prompt 반복 횟수
    val loops: Int = 1,               // sequence 모드 — 리스트 전체 반복 횟수
    val stopOnError: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
)

/** 프리셋 생성/수정 요청. */
@Serializable
data class PromptAutomationPresetUpsertDto(
    val name: String,
    val mode: String = PromptAutomationMode.REPEAT,
    val prompts: List<String> = emptyList(),
    val repeatCount: Int = 1,
    val loops: Int = 1,
    val stopOnError: Boolean = true,
)

@Serializable
data class PromptAutomationPresetsResponseDto(
    val presets: List<PromptAutomationPresetDto> = emptyList(),
)

/**
 * 자동화 시작 요청. [presetId] 가 있으면 그 프리셋을 사용(나머지 inline 필드 무시),
 * 없으면 inline(mode/prompts/repeatCount/loops/stopOnError)으로 즉석 시작.
 */
@Serializable
data class PromptAutomationStartRequestDto(
    val presetId: String? = null,
    val mode: String = PromptAutomationMode.REPEAT,
    val prompts: List<String> = emptyList(),
    val repeatCount: Int = 1,
    val loops: Int = 1,
    val stopOnError: Boolean = true,
)

/** 현재(또는 마지막) 자동화 진행 스냅샷. */
@Serializable
data class PromptAutomationStatusDto(
    val active: Boolean = false,
    val runId: String? = null,
    val name: String? = null,
    val mode: String? = null,
    val status: String? = null,       // running | done | stopped | failed
    val sent: Int = 0,
    val total: Int = 0,
    val lastPrompt: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val lastError: String? = null,
)
