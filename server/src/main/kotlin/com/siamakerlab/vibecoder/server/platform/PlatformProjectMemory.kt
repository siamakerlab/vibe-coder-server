package com.siamakerlab.vibecoder.server.platform

data class PlatformProjectMemory(
    val fileName: String = "CLAUDE.md",
    val content: String,
    val overwritePolicy: ProjectMemoryOverwritePolicy = ProjectMemoryOverwritePolicy.CREATE_ONLY,
)

enum class ProjectMemoryOverwritePolicy {
    CREATE_ONLY,
    PREVIEW_DIFF,
}
