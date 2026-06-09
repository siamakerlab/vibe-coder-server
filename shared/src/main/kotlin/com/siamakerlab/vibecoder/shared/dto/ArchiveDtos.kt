package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.119.0 — 프로젝트 아카이브(압축 보관) 레지스트리 항목의 wire 표현.
 *
 * SSR `/archive` 페이지가 보여주는 `ArchivedProjectRow` 의 JSON 노출용(android `/archives`).
 * 내부 경로(`archivePath`)/manifest 원문은 노출하지 않는다 — 클라이언트는 메타만 필요.
 *
 *  - [id]          : 아카이브 id (복원/삭제/다운로드 키).
 *  - [originalId]  : 아카이브 당시 프로젝트 id(폴더명). 복원 시 같은 id 로 되살아남.
 *  - [name]        : 프로젝트 표시명.
 *  - [packageName] : 안드로이드 패키지명.
 *  - [archivedAt]  : ISO-8601 아카이브 시각.
 *  - [sizeBytes]   : .tar.gz 크기(바이트).
 */
@Serializable
data class ArchivedProjectDto(
    val id: String,
    val originalId: String,
    val name: String,
    val packageName: String,
    val archivedAt: String,
    val sizeBytes: Long,
)
