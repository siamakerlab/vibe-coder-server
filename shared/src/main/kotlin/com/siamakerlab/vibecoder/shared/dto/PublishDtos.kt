package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.Serializable

/**
 * v1.121.0 — Google Play 업로드 트리거. SSR `/projects/{id}/builds/{buildId}/play-upload`
 * 의 JSON. 서버가 Claude 콘솔 세션에 "이 .aab 를 Play <track> 에 업로드" 프롬프트를 보낸다
 * (google-play-publisher MCP 사용). 진행 상황은 콘솔 WS 로 확인.
 *
 *  - [aabPath]: 프로젝트 root 기준 .aab 경로. 기본 = Release AAB 빌드 산출물.
 *  - [track]: internal / alpha / beta / production.
 *  - [releaseNotes]: 비우면 Claude 가 git log 등으로 추론.
 */
@Serializable
data class PlayUploadRequestDto(
    val aabPath: String = "app/build/outputs/bundle/release/app-release.aab",
    val track: String = "internal",
    val releaseNotes: String? = null,
)

/** 업로드 트리거 결과 — 콘솔로 프롬프트 전송됨(202). 진행은 콘솔 WS. */
@Serializable
data class StoreUploadResponseDto(
    val ok: Boolean,
)

@Serializable
data class TestFlightUploadRequestDto(
    val ipaPath: String = "out/app-release.ipa",
    val distributionGroups: String? = null,
    val releaseNotes: String? = null,
)

@Serializable
data class TestFlightUploadJobDto(
    val id: String,
    val projectId: String,
    val buildId: String? = null,
    val artifactId: String? = null,
    val ipaPath: String,
    val bundleId: String? = null,
    val appId: String? = null,
    val appName: String? = null,
    val buildNumber: String? = null,
    val status: String,
    val distributionGroups: String? = null,
    val releaseNotes: String? = null,
    val message: String? = null,
    val errorCode: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val finishedAt: String? = null,
)

@Serializable
data class TestFlightUploadResponseDto(
    val ok: Boolean,
    val job: TestFlightUploadJobDto,
)

@Serializable
data class TestFlightUploadStatusUpdateRequestDto(
    val status: String,
    val message: String? = null,
    val errorCode: String? = null,
)
