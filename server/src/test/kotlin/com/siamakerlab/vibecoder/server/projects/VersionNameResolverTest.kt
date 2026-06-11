package com.siamakerlab.vibecoder.server.projects

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * v1.128.7 — 운영 프로젝트에서 실측한 versionName 정의 패턴을 픽스처로 회귀 방지.
 * `$` 는 Kotlin raw string 에서 `${'$'}` 로 이스케이프(픽스처의 Gradle 보간 표현).
 */
class VersionNameResolverTest {

    private fun r(gradle: String, props: Map<String, String> = emptyMap()) =
        VersionNameResolver.resolve(gradle, props)

    @Test
    fun `리터럴`() {
        r("""android { defaultConfig { versionName = "1.6.0" } }""") shouldBe "1.6.0"
    }

    @Test
    fun `변수 참조 — 리터럴 (pocketmind)`() {
        r(
            """
            val pocketmindVersionName = "0.17.17"
            android { defaultConfig { versionName = pocketmindVersionName } }
            """.trimIndent(),
        ) shouldBe "0.17.17"
    }

    @Test
    fun `문자열 보간 + val Int (magnifier)`() {
        r(
            """
            val versionMajor = 1
            val versionMinor = 0
            val versionPatch = 8
            android { defaultConfig { versionName = "v${'$'}versionMajor.${'$'}versionMinor.${'$'}versionPatch" } }
            """.trimIndent(),
        ) shouldBe "v1.0.8"
    }

    @Test
    fun `다단계 변수 + versionProps (bubble-level)`() {
        r(
            """
            val vMajor = versionProps["VERSION_MAJOR"].toString().toInt()
            val vMinor = versionProps["VERSION_MINOR"].toString().toInt()
            val vPatch = versionProps["VERSION_PATCH"].toString().toInt()
            val autoVersionName = "${'$'}vMajor.${'$'}vMinor.${'$'}vPatch"
            android { defaultConfig { versionName = autoVersionName } }
            """.trimIndent(),
            mapOf("VERSION_MAJOR" to "1", "VERSION_MINOR" to "4", "VERSION_PATCH" to "4"),
        ) shouldBe "1.4.4"
    }

    @Test
    fun `versionProps 직접 보간 + elvis (sqlite-viewer)`() {
        r(
            """android { defaultConfig { versionName = "${'$'}{versionProps["VERSION_MAJOR"] ?: "1"}.${'$'}{versionProps["VERSION_MINOR"] ?: "0"}.${'$'}{versionProps["VERSION_PATCH"] ?: "0"}" } }""",
            mapOf("VERSION_MAJOR" to "1", "VERSION_MINOR" to "0", "VERSION_PATCH" to "15"),
        ) shouldBe "1.0.15"
    }

    @Test
    fun `변수 보간 + v 접두 보존 (cuevision)`() {
        r(
            """
            val versionMajor = 1
            val versionMinor = 6
            val versionPatch = 1
            val computedVersionName = "v${'$'}versionMajor.${'$'}versionMinor.${'$'}versionPatch"
            android { defaultConfig { versionName = computedVersionName } }
            """.trimIndent(),
        ) shouldBe "v1.6.1"
    }

    @Test
    fun `elvis 기본값 — props 없을 때`() {
        r("""android { defaultConfig { versionName = "${'$'}{versionProps["VERSION_MAJOR"] ?: "9"}" } }""") shouldBe "9"
    }

    @Test
    fun `주석의 versionName 은 무시, 실제 리터럴 채택`() {
        r(
            """
            // versionName = MAJOR.MINOR.PATCH (SemVer) 정책 설명
            android { defaultConfig { versionName = "2.0.1" } }
            """.trimIndent(),
        ) shouldBe "2.0.1"
    }

    @Test
    fun `못 풀면 null — raw 변수명 노출 방지`() {
        r("""android { defaultConfig { versionName = unknownVar } }""") shouldBe null
    }

    @Test
    fun `buildConfigField APP_VERSION_NAME 은 versionName 으로 오인하지 않음`() {
        r(
            """
            android {
                defaultConfig {
                    versionName = "3.2.1"
                    buildConfigField("String", "APP_VERSION_NAME", "\"unused\"")
                }
            }
            """.trimIndent(),
        ) shouldBe "3.2.1"
    }
}
