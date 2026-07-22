package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.build.BuildToolchain

data class PlatformBuildToolchains(
    val gradle: BuildToolchain,
    val flutter: BuildToolchain,
    val ios: BuildToolchain,
)
