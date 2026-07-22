package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class IosWorkspaceSyncServiceTest {
    @Test
    fun `local mode does not require workspace sync`() {
        val root = Files.createTempDirectory("ios-local-sync")
        val plan = IosWorkspaceSyncService(IosAgentSection(mode = "local"))
            .plan("iphone-app", root)

        plan.mode shouldBe "local"
        plan.required shouldBe false
        plan.command shouldContainExactly emptyList()
        plan.remoteProjectRoot shouldBe root.normalize().toString()
    }

    @Test
    fun `ssh mode builds rsync dry run with stable excludes`() {
        val root = Files.createTempDirectory("ios-ssh-sync")
        val plan = IosWorkspaceSyncService(
            IosAgentSection(
                enabled = true,
                mode = "ssh",
                host = "mac-mini.local",
                port = 2222,
                user = "builder",
                workspaceRoot = "/Users/builder/vibe-workspace",
            )
        ).plan("iphone-app", root, dryRun = true)

        plan.mode shouldBe "ssh"
        plan.required shouldBe true
        plan.remoteProjectRoot shouldBe "/Users/builder/vibe-workspace/iphone-app"
        plan.command shouldContain "--dry-run"
        plan.command shouldContainExactly listOf(
            "rsync",
            "-az",
            "--delete",
            "--exclude",
            ".git",
            "--exclude",
            ".gradle",
            "--exclude",
            ".vibecoder",
            "--exclude",
            "build",
            "--exclude",
            "DerivedData",
            "--exclude",
            ".build",
            "--dry-run",
            "-e",
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new",
            root.normalize().toString().trimEnd('/') + "/",
            "builder@mac-mini.local:'/Users/builder/vibe-workspace/iphone-app'/",
        )
    }

    @Test
    fun `ssh mode rejects unsafe project id and relative workspace root`() {
        val root = Files.createTempDirectory("ios-ssh-sync-invalid")
        val service = IosWorkspaceSyncService(
            IosAgentSection(
                enabled = true,
                mode = "ssh",
                host = "mac-mini.local",
                user = "builder",
                workspaceRoot = "relative/path",
            )
        )

        shouldThrow<IllegalArgumentException> {
            service.plan("../bad", root)
        }
        shouldThrow<IllegalArgumentException> {
            service.plan("iphone-app", root)
        }
    }
}
