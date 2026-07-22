package com.siamakerlab.vibecoder.server.env

import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SkillRegistryTest {
    private lateinit var root: Path

    @Before
    fun setup() {
        root = Files.createTempDirectory("vibe-skill-registry-test")
    }

    @After
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `codexGlobalRoot uses configured CODEX_HOME when it can be prepared`() {
        val codexHome = root.resolve("codex-home")

        SkillRegistry.resolveCodexGlobalRoot(
            configuredHome = codexHome.toString(),
            userHome = root.resolve("user-home").toString(),
        ) shouldBe codexHome.resolve("skills").toAbsolutePath().normalize()
    }

    @Test
    fun `codexGlobalRoot falls back to current user home when configured CODEX_HOME cannot be prepared`() {
        val missingUnderFile = root.resolve("not-a-dir").also { Files.writeString(it, "x") }.resolve("codex")
        val userHome = root.resolve("user-home")

        SkillRegistry.resolveCodexGlobalRoot(
            configuredHome = missingUnderFile.toString(),
            userHome = userHome.toString(),
        ) shouldBe userHome.resolve(".codex").resolve("skills").toAbsolutePath().normalize()
    }
}
