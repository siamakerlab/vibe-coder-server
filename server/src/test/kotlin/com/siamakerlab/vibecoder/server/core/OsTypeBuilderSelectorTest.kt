package com.siamakerlab.vibecoder.server.core

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Paths

class OsTypeBuilderSelectorTest {

    @Test fun `windows uses gradlew_bat`() {
        OsType.WINDOWS.gradleWrapperFileName() shouldBe "gradlew.bat"
    }

    @Test fun `unix uses gradlew`() {
        OsType.LINUX.gradleWrapperFileName() shouldBe "gradlew"
        OsType.MAC.gradleWrapperFileName() shouldBe "gradlew"
    }

    @Test fun `unix command starts with dot-slash`() {
        val cmd = OsType.LINUX.gradleCommand(Paths.get("/tmp/project"), listOf(":app:assembleDebug"))
        cmd.first() shouldBe "./gradlew"
    }

    @Test fun `windows command uses absolute wrapper path`() {
        val cmd = OsType.WINDOWS.gradleCommand(Paths.get("C:/proj"), listOf(":app:assembleDebug"))
        cmd.first().endsWith("gradlew.bat") shouldBe true
    }
}
