package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.core.WorkspacePath

/**
 * Project-local console TUI compatibility preference.
 *
 * v1.162.5 makes the project console TUI-only. The old flag files are tolerated so older
 * pages/API calls do not fail, but reads always return enabled.
 */
class ConsoleTuiPreferenceStore(
    private val workspace: WorkspacePath,
) {
    fun isTuiMode(projectId: String): Boolean =
        true

    fun setTuiMode(projectId: String, enabled: Boolean) {
        workspace.vibecoderDir(projectId)
        // TUI is the only supported project console mode; legacy preference writes are ignored.
    }
}
