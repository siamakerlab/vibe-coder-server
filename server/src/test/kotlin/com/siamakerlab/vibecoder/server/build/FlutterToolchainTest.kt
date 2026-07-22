package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.config.BuildSection
import com.siamakerlab.vibecoder.server.config.ClaudeSection
import com.siamakerlab.vibecoder.server.config.GitSection
import com.siamakerlab.vibecoder.server.config.SecuritySection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.config.ServerSection
import com.siamakerlab.vibecoder.server.config.WorkspaceSection
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files

class FlutterToolchainTest {
    @Test
    fun `find artifact returns flutter ios ipa`() {
        val root = Files.createTempDirectory("flutter-ios-ipa")
        val oldIpa = root.resolve("build/ios/ipa/old.ipa")
        val newIpa = root.resolve("build/ios/ipa/new.ipa")
        Files.createDirectories(oldIpa.parent)
        Files.writeString(oldIpa, "old")
        Files.writeString(newIpa, "new")
        Files.setLastModifiedTime(oldIpa, java.nio.file.attribute.FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newIpa, java.nio.file.attribute.FileTime.fromMillis(2_000))

        FlutterToolchain(testConfig()).findArtifact(root, "app", BuildVariant.IOS_EXPORT_IPA) shouldBe newIpa
    }

    private fun testConfig(): ServerConfig = ServerConfig(
        server = ServerSection(),
        workspace = WorkspaceSection(),
        security = SecuritySection(),
        claude = ClaudeSection(),
        build = BuildSection(),
        git = GitSection(),
    )
}
