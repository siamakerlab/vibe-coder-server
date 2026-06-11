package com.siamakerlab.vibecoder.server.projects

import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * v1.128.0 — 멀티모듈에서 서브모듈 namespace 를 applicationId 로 오인하던 버그 회귀 방지.
 * 실제 운영 사고(siashell: app=com.siashell.app 인데 서브모듈 namespace=com.siashell.service
 * 가 채택됨)를 픽스처로 모사.
 */
class PackageNameDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): Path = tmp.root.toPath()

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel)
        Files.createDirectories(f.parent)
        f.writeText(content)
    }

    @Test
    fun `app 모듈 applicationId 가 서브모듈 namespace 보다 우선`() {
        val root = root()
        // siashell 구조 모사 — app 모듈 applicationId=com.siashell.app,
        // 라이브러리 서브모듈 namespace=com.siashell.service.
        write(root, "settings.gradle.kts", """include(":app", ":service:session-service")""")
        write(
            root, "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android {
                namespace = "com.siashell.app"
                defaultConfig { applicationId = "com.siashell.app" }
            }
            """.trimIndent(),
        )
        write(
            root, "service/session-service/build.gradle.kts",
            """
            plugins { id("com.android.library") }
            android { namespace = "com.siashell.service" }
            """.trimIndent(),
        )

        PackageNameDetector.detectApplicationId(root) shouldBe "com.siashell.app"
    }

    @Test
    fun `applicationIdSuffix 는 prefix 에 섞이지 않는다`() {
        val root = root()
        write(root, "settings.gradle.kts", """include(":app")""")
        write(
            root, "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android {
                namespace = "com.x.app"
                defaultConfig { applicationId = "com.x.app" }
                buildTypes { getByName("debug") { applicationIdSuffix = ".debug" } }
            }
            """.trimIndent(),
        )

        PackageNameDetector.detectApplicationId(root) shouldBe "com.x.app"
    }

    @Test
    fun `applicationId 없으면 app 모듈 namespace 로 폴백`() {
        val root = root()
        write(root, "settings.gradle.kts", """include(":app")""")
        write(
            root, "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android { namespace = "com.only.namespace" }
            """.trimIndent(),
        )

        PackageNameDetector.detectApplicationId(root) shouldBe "com.only.namespace"
    }

    @Test
    fun `라이브러리 서브모듈만 있으면 namespace 를 채택하지 않는다`() {
        val root = root()
        // app 모듈(application plugin) 없음 → applicationId 도 없음 → null.
        // 서브모듈 namespace(com.lib.core) 를 절대 채택하지 않는다.
        write(root, "settings.gradle.kts", """include(":core")""")
        write(
            root, "core/build.gradle.kts",
            """
            plugins { id("com.android.library") }
            android { namespace = "com.lib.core" }
            """.trimIndent(),
        )

        PackageNameDetector.detectApplicationId(root) shouldBe null
    }

    @Test
    fun `단일 모듈(settings 없이 root applicationId)도 감지`() {
        val root = root()
        // settings.gradle 없는 단순 구조 — (3) fallback 이 applicationId 만 검색.
        write(
            root, "build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android {
                namespace = "com.solo.app"
                defaultConfig { applicationId = "com.solo.app" }
            }
            """.trimIndent(),
        )

        PackageNameDetector.detectApplicationId(root) shouldBe "com.solo.app"
    }

    @Test
    fun `app 모듈 식별 — nested gradle path`() {
        val root = root()
        write(root, "settings.gradle.kts", """include(":android-app:app", ":core")""")
        write(
            root, "android-app/app/build.gradle.kts",
            """plugins { id("com.android.application") }""",
        )
        write(root, "core/build.gradle.kts", """plugins { id("com.android.library") }""")

        PackageNameDetector.detectAppModule(root) shouldBe "android-app:app"
    }

    @Test
    fun `한 줄 다중 include 에서 app 이 둘째여도 감지`() {
        // 정밀리뷰 #2 회귀 — include(":core", ":app") 한 줄에 app 이 둘째.
        val root = root()
        write(root, "settings.gradle.kts", """include(":core", ":app")""")
        write(root, "core/build.gradle.kts", """plugins { id("com.android.library") }""")
        write(
            root, "app/build.gradle.kts",
            """
            plugins { id("com.android.application") }
            android { defaultConfig { applicationId = "com.multi.app" } }
            """.trimIndent(),
        )

        PackageNameDetector.detectAppModule(root) shouldBe "app"
        PackageNameDetector.detectApplicationId(root) shouldBe "com.multi.app"
    }
}
