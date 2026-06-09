package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.119.0 — 품질·접근성 검사(Android Lint) wire DTO. SSR `/projects/{id}/quality` 의
 * Lint 카드와 동일 데이터를 JSON 으로 노출(android `/projects/{id}/quality`).
 *
 * 인스트루먼트 테스트(connectedDebugAndroidTest)는 에뮬레이터(서버측 화면) 의존이라
 * android 클라이언트 대상에서 제외 — Lint(정적 분석, 에뮬레이터 불필요)만 노출한다.
 */
@Serializable
data class LintIssueDto(
    /** Lint 룰 id (예: ContentDescription, HardcodedText). */
    val id: String,
    /** 카테고리(예: Accessibility, Correctness, Security). 상위 카테고리는 `:` 앞부분. */
    val category: String,
    /** 심각도(fatal/error/warning/informational). */
    val severity: String,
    val message: String,
    /** 프로젝트 root 기준 상대 경로. 위치 불명이면 null. */
    val file: String? = null,
    val line: Int? = null,
)

/**
 * Lint 실행 결과.
 *  - [ok]=false 면 lint 자체가 실패(컴파일/툴 오류) — [errorMessage]/[rawTail] 참고.
 *  - [ok]=true + [issues] 비어있으면 깨끗(통과).
 */
@Serializable
data class LintResultDto(
    val ok: Boolean,
    val moduleName: String,
    val durationMs: Long,
    val issues: List<LintIssueDto> = emptyList(),
    val errorMessage: String? = null,
    /** 실패 시 gradle 출력 tail(디버깅용). 정상 시 null. */
    val rawTail: String? = null,
)

/**
 * 선택 항목(lint 이슈)을 콘솔(Claude 세션)로 수정요청 전송하는 요청.
 *  - [kind]: "lint"(기본) | "test". android 는 보통 "lint".
 *  - [selected]: 사람이 읽는 한 줄 요약 리스트(SSR 의 checkbox value 와 동형).
 */
@Serializable
data class QualityFixRequestDto(
    val module: String = "app",
    val kind: String = "lint",
    val selected: List<String>,
)

/** 수정요청 전송 결과 — 콘솔로 보낸 항목 수. 진행 상황은 콘솔 WS 로 확인. */
@Serializable
data class QualityFixResponseDto(
    val sent: Int,
)
