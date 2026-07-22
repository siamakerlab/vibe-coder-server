package com.siamakerlab.vibecoder.server.terminal

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.error.ApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Test
import java.nio.file.Files

class ConsoleTuiPreferenceStoreTest {
    @Test
    fun `TUI mode stays enabled even when compatibility endpoint receives explicit off`() {
        val root = Files.createTempDirectory("vibe-tui-pref-test")
        val store = ConsoleTuiPreferenceStore(WorkspacePath(root))

        store.isTuiMode("app1").shouldBeTrue()

        store.setTuiMode("app1", false)
        store.isTuiMode("app1").shouldBeTrue()

        store.setTuiMode("app1", true)
        store.isTuiMode("app1").shouldBeTrue()
    }

    @Test
    fun `rejects path traversal project ids`() {
        val root = Files.createTempDirectory("vibe-tui-pref-test")
        val store = ConsoleTuiPreferenceStore(WorkspacePath(root))

        shouldThrow<ApiException> {
            store.setTuiMode("../outside", true)
        }
    }
}
