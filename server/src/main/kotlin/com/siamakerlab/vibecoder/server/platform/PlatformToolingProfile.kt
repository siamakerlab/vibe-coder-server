package com.siamakerlab.vibecoder.server.platform

data class PlatformToolingProfile(
    val projectType: String,
    val defaultMcp: List<String> = emptyList(),
    val conditionalMcp: List<String> = emptyList(),
    val optInMcp: List<String> = emptyList(),
    val defaultSkills: List<String> = emptyList(),
    val conditionalSkills: List<String> = emptyList(),
    val optInSkills: List<String> = emptyList(),
    val defaultAgents: List<String> = emptyList(),
    val conditionalAgents: List<String> = emptyList(),
    val optInAgents: List<String> = emptyList(),
    val forbiddenTools: List<String> = emptyList(),
)
