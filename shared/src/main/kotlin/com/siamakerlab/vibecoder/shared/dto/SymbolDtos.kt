package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v0.54.0+ — Symbol definition lookup hit.
 *
 * 서버 스캐너는 정규식 기반 (Kotlin/Java/Groovy/Swift) 으로 다음 종류를 인식:
 *  - `fun` — top-level/member function
 *  - `class` — class / interface / object
 *  - `val` — val / var
 *  - `typealias`
 *  - `struct` / `protocol` / `enum` / `actor` / `func` / `swiftui-view` — Swift
 *
 * `line` 은 매치된 한 줄의 raw 텍스트 (trim 없음). UI 에서는 monospace + 좌우
 * truncation 권장. `relPath` 는 프로젝트 workspace root 기준 경로 (`/` separator).
 *
 * v0.64.0 — Android shared/ 와 동일한 모양으로 server shared/ 에 정식 등록.
 */
@Serializable
data class SymbolHitDto(
    val relPath: String,
    val lineNumber: Int,
    val kind: String,
    val line: String = "",
)

/**
 * GET /api/projects/{id}/symbols?name=... 응답 (v0.54+).
 * 서버 측 hard cap: 100 hits.
 */
@Serializable
data class SymbolsResponseDto(
    val hits: List<SymbolHitDto> = emptyList(),
)
