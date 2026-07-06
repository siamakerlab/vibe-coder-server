package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.config.BuildSection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import io.kotest.matchers.shouldBe
import org.junit.Test

class GradleBuilderCleanBuildTest {

    @Test
    fun `gradle build menu runs module clean before requested task and disables build cache`() {
        val args = GradleBuilder(testConfig()).cleanBuildArgs(
            moduleName = "app",
            taskName = "assembleRelease",
        )

        args shouldBe listOf(
            ":app:clean",
            ":app:assembleRelease",
            "--no-build-cache",
            "--no-daemon",
            "--stacktrace",
        )
    }

    @Test
    fun `gradle build menu supports nested module paths`() {
        val args = GradleBuilder(testConfig()).cleanBuildArgs(
            moduleName = "android:app",
            taskName = "bundleRelease",
        )

        args.take(2) shouldBe listOf(
            ":android:app:clean",
            ":android:app:bundleRelease",
        )
    }

    private fun testConfig(): ServerConfig = ServerConfig(
        server = com.siamakerlab.vibecoder.server.config.ServerSection(),
        workspace = com.siamakerlab.vibecoder.server.config.WorkspaceSection(),
        security = com.siamakerlab.vibecoder.server.config.SecuritySection(),
        claude = com.siamakerlab.vibecoder.server.config.ClaudeSection(),
        build = BuildSection(),
        git = com.siamakerlab.vibecoder.server.config.GitSection(),
    )
}
