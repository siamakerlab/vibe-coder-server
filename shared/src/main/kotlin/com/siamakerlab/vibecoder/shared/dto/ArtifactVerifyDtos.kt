package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.76.0 (server v0.70+) — `GET /api/projects/{id}/artifacts/{aid}/verify` 응답.
 *
 * Android 사본과 wire 호환 (필드 이름/타입/순서 동일). v0.75.x 까지는 server
 * 내부 inner class `ApkVerifier.Result` 만 존재 — wire 호환 보증 어려웠음 (이름 변경
 * 시 client break 위험). 본 cycle 부터 shared SSOT 로 이전.
 *
 * apksigner 가 없으면 `verified=false` + `errors` 에 사유 메시지.
 */
@Serializable
data class ApkVerifyResultDto(
    val verified: Boolean,
    val verifiedV1: Boolean = false,
    val verifiedV2: Boolean = false,
    val verifiedV3: Boolean = false,
    /** apksigner 출력의 Signer DN 목록 (multi-sign 가능). */
    val signers: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    /** 검증 wall-clock 시간 (ms). 운영 진단용. */
    val durationMs: Long = 0,
)
