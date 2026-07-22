package com.siamakerlab.vibecoder.server.platform

data class PlatformUiCapabilities(
    val projectType: String,
    val displayName: String,
    val badgeColor: String,
    val showPlayStoreLink: Boolean = false,
    val showIosBuildSettings: Boolean = false,
    val showIosSimulator: Boolean = false,
    val showIPhoneQuickPrompts: Boolean = false,
)
