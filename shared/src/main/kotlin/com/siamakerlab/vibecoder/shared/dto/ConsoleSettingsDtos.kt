package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.120.0 — 콘솔(프로젝트 Claude 세션) 토큰/모델 설정. SSR 의
 * `/projects/{id}/console/{model|mcp-strict|auto-compact}` 컨트롤을 JSON 으로 노출
 * (android 콘솔 설정 시트). 토큰 비용 제어(v1.106.0 "토큰 사용량 급소모 개선")를 모바일에서도.
 */
@Serializable
data class ConsoleModelOptionDto(
    /** CLI 인자 값. "" = CLI 기본(미지정). */
    val id: String,
    /** 표시 라벨(중립). 클라이언트가 자체 i18n 으로 대체 가능. */
    val label: String,
)

@Serializable
data class ConsoleSettingsDto(
    /** 사용자가 선택한 모델. null/"" = CLI 기본. */
    val model: String?,
    /** 실제 적용 모델(default 해석 결과). */
    val effectiveModel: String,
    /** 자동 /compact(컨텍스트 임계 초과 시 turn 종료 후 자동 압축). */
    val autoCompact: Boolean,
    /** MCP 최소화(전역 MCP 무시 → 캐시 프리픽스 축소). */
    val mcpStrict: Boolean,
    /** 선택 가능한 모델 목록(첫 항목은 보통 CLI 기본). */
    val availableModels: List<ConsoleModelOptionDto>,
)

/** 모델 변경 요청. model=null/"" 면 CLI 기본으로. 적용 시 세션 재시작(컨텍스트 유지). */
@Serializable
data class ConsoleModelRequestDto(
    val model: String? = null,
)

/** mcp-strict / auto-compact 토글 요청. */
@Serializable
data class ConsoleToggleRequestDto(
    val enabled: Boolean,
)
