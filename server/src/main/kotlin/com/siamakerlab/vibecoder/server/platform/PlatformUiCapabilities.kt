package com.siamakerlab.vibecoder.server.platform

data class PlatformUiCapabilities(
    val projectType: String,
    val displayName: String,
    val badgeColor: String,
    val showPlayStoreLink: Boolean = false,
    val showIosBuildSettings: Boolean = false,
    val showIosSimulator: Boolean = false,
    val showIPhoneQuickPrompts: Boolean = false,
    /** v1.164.0 (Phase 9) — 프로젝트 개요에 "iPhone 빌드환경(/env-setup#iphone)" 진입 링크 노출 여부. */
    val showIosBuildEnvLink: Boolean = false,
)
