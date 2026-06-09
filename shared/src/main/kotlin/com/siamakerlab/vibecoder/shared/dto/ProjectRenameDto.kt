package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.122.0 — 프로젝트 표시 이름 변경. SSR `/projects/{id}/rename-name` 의 JSON.
 * 폴더명/패키지명은 무관(표시 이름만) — 응답은 갱신된 [ProjectDto].
 */
@Serializable
data class ProjectRenameRequestDto(
    val name: String,
)
