package com.siamakerlab.vibecoder.server.platform

import com.siamakerlab.vibecoder.server.projects.ClaudeMdTemplate
import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.file.Files

class ProjectToolingSeederTest {
    @Test
    fun `seeds only selected platform default skills and agents`() {
        val root = Files.createTempDirectory("vibe-tooling-seed-")
        try {
            val result = ProjectToolingSeeder.seedProjectDefaults(
                root,
                PlatformEngineRegistry.default.forType(ProjectTypes.IPHONE).toolingProfile(),
            )

            result.skillsCreated shouldBe 3
            result.agentsCreated shouldBe 3
            Files.isRegularFile(root.resolve(".claude/skills/swiftui-iphone-expert/SKILL.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/skills/xcode-build-debugger/SKILL.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/skills/vibe-ios-build-flow/SKILL.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/agents/iphone-architect.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/agents/swiftui-implementer.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/agents/xcode-build-fixer.md")) shouldBe true

            Files.exists(root.resolve(".claude/skills/kotlin-expert/SKILL.md")) shouldBe false
            Files.exists(root.resolve(".claude/skills/flutter-dart-expert/SKILL.md")) shouldBe false
            Files.exists(root.resolve(".claude/agents/gradle-build-fixer.md")) shouldBe false
            Files.exists(root.resolve(".claude/agents/flutter-build-fixer.md")) shouldBe false
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `does not overwrite existing project-local tooling files`() {
        val root = Files.createTempDirectory("vibe-tooling-seed-existing-")
        try {
            val skill = root.resolve(".claude/skills/kotlin-expert/SKILL.md")
            val agent = root.resolve(".claude/agents/gradle-build-fixer.md")
            Files.createDirectories(skill.parent)
            Files.createDirectories(agent.parent)
            Files.writeString(skill, "custom skill\n")
            Files.writeString(agent, "custom agent\n")

            val result = ProjectToolingSeeder.seedProjectDefaults(
                root,
                PlatformEngineRegistry.default.forType(ProjectTypes.KOTLIN).toolingProfile(),
            )

            result.skillsCreated shouldBe 3
            result.agentsCreated shouldBe 2
            Files.readString(skill) shouldBe "custom skill\n"
            Files.readString(agent) shouldBe "custom agent\n"
            Files.isRegularFile(root.resolve(".claude/skills/jetpack-compose-expert/SKILL.md")) shouldBe true
            Files.isRegularFile(root.resolve(".claude/agents/kotlin-architect.md")) shouldBe true
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `ensureExistingProjectGuidance seeds skill and appends CLAUDE_md section once`() {
        val root = Files.createTempDirectory("vibe-tooling-ensure-")
        try {
            // 기존 iPhone 프로젝트: CLAUDE.md 는 있으나 빌드 파이프라인 섹션·스킬은 없다.
            val claudeMd = root.resolve("CLAUDE.md")
            Files.writeString(claudeMd, "# CLAUDE.md — Vibe Coder iPhone Project Rules\n\n## Project Rules\n- existing\n")
            val profile = PlatformEngineRegistry.default.forType(ProjectTypes.IPHONE).toolingProfile()

            ProjectToolingSeeder.ensureExistingProjectGuidance(
                root,
                profile,
                ClaudeMdTemplate.IPHONE_PIPELINE_MARKER,
                ClaudeMdTemplate.IPHONE_BUILD_PIPELINE_SECTION,
            )

            // 스킬이 보강되고, CLAUDE.md 끝에 파이프라인 섹션이 append 되며, 기존 내용은 보존된다.
            Files.isRegularFile(root.resolve(".claude/skills/vibe-ios-build-flow/SKILL.md")) shouldBe true
            val afterFirst = Files.readString(claudeMd)
            afterFirst shouldContain "- existing"
            afterFirst shouldContain ClaudeMdTemplate.IPHONE_PIPELINE_MARKER
            afterFirst shouldContain "vibe-ios-build-flow"

            // idempotent: 마커가 이미 있으면 두 번째 호출은 append 하지 않는다.
            ProjectToolingSeeder.ensureExistingProjectGuidance(
                root,
                profile,
                ClaudeMdTemplate.IPHONE_PIPELINE_MARKER,
                ClaudeMdTemplate.IPHONE_BUILD_PIPELINE_SECTION,
            )
            Files.readString(claudeMd) shouldBe afterFirst
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
