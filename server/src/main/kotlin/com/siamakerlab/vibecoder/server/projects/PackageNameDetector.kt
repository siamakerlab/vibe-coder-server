package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.shared.dto.ProjectTypes
import java.nio.file.Files
import java.nio.file.Path

/**
 * v1.128.0 — clone 된 Android 프로젝트에서 applicationId / app 모듈을 정확히 감지.
 *
 * ## 배경 (버그 회수)
 * 이전 `ProjectService.detectPackageFromClonedRepo` 는 프로젝트 전체를 `Files.walk` 로 훑어
 * `applicationId|namespace` 중 **첫 매치**를 채택했다. 멀티모듈 프로젝트에서 파일 순회 순서가
 * 비결정적이라, 라이브러리 서브모듈의 `namespace`(예: `:service:session-service` 의
 * `namespace = "com.siashell.service"`)가 app 모듈(`com.siashell.app`)보다 먼저 걸리면 그
 * 서브모듈 namespace 가 packageName 으로 오인됐다. 키스토어/AdMob 가 잘못된 prefix 로 발급되는
 * 운영 사고(siashell / Calculator)로 이어졌다.
 *
 * ## 감지 규칙 (우선순위) — applicationId 가 정답이며 namespace 와 다를 수 있다
 *  1. `com.android.application` 플러그인을 적용한 **app 모듈**의 build.gradle(.kts) 에서
 *     `defaultConfig.applicationId` (release base — `applicationIdSuffix` 는 매칭 제외).
 *  2. app 모듈에 applicationId 가 없으면 그 app 모듈의 `namespace` 로 폴백.
 *  3. app 모듈을 못 찾으면(단일 모듈/settings 파싱 실패) 전체에서 **`applicationId` 만**
 *     검색(namespace 제외). 라이브러리 모듈엔 applicationId 가 없으므로 서브모듈 namespace
 *     오인이 구조적으로 불가능.
 *
 * ## 절대 채택 금지
 * 라이브러리 모듈(`com.android.library`)의 namespace, 서브모듈 package 선언, AndroidManifest 의
 * component `android:name`. → AndroidManifest 는 더 이상 파싱하지 않는다(AGP 7+ 에서 package
 * 속성이 deprecated 되고 namespace 로 이동한 것과도 일관).
 */
internal object PackageNameDetector {

    private val APP_PLUGIN = Regex("""com\.android\.application""")
    // gradle 모듈 path 는 따옴표+콜론 시작 (":app", ":service:session-service"). settings 의
    // include 구문(한 줄 다중 `include(":a", ":b")` / 멀티라인 / 연속 include 모두)에서 콜론-
    // prefixed 문자열을 전부 추출. rootProject.name="x"(콜론 없음)·plugin id 는 매칭 안 됨.
    // v1.127.2 (정밀리뷰 #2) — 이전 `include\s*\(?...` 정규식은 한 줄 다중 include 의 첫 인자만
    // 잡아, app 모듈이 둘째 이후면 detectAppModule 이 null 이 되던 사각지대를 회수.
    private val MODULE_PATH = Regex("""["']:([a-zA-Z0-9_.:\-]+)["']""")

    // `applicationId = "..."`(kts) / `applicationId "..."`(groovy). "applicationIdSuffix" 는
    // "applicationId" 직후가 "Suffix" 라 `\s*=?\s*["']` 패턴에 매칭되지 않아 자동 제외된다
    // (release base 만 추출 — `.debug` 같은 suffix 가 prefix 에 섞이지 않음).
    private val APPLICATION_ID = Regex("""applicationId\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")
    private val NAMESPACE = Regex("""namespace\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_.]+)["']""")

    private val GRADLE_FILES = listOf("build.gradle.kts", "build.gradle")

    /**
     * settings.gradle(.kts) 의 include 모듈 중 `com.android.application` 을 적용한 app 모듈의
     * Gradle path(leading `:` 제거, nested 는 콜론 유지 — 예 `"android-app:app"`). 없으면 null.
     *
     * 여러 application 모듈이 있으면 settings 의 include 순서상 **첫 번째**를 반환한다(단일
     * 사용자 도구 — 보통 app 모듈은 1개). 0개면 null → 호출부가 placeholder/기존값 유지.
     */
    fun detectAppModule(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        for (mod in readIncludes(root)) {
            val dir = root.resolve(mod.replace(':', '/'))
            if (readGradle(dir)?.let { APP_PLUGIN.containsMatchIn(it) } == true) return mod
        }
        return null
    }

    /**
     * 감지 규칙대로 applicationId(없으면 app 모듈 namespace)를 반환. 실패 시 null
     * (호출부는 `com.example.<projectId>` placeholder 를 유지하고 사용자가 후속 수정).
     */
    fun detectApplicationId(root: Path): String? {
        if (!Files.isDirectory(root)) return null

        // (1)(2) app 모듈에서 applicationId 우선 → 없으면 그 모듈의 namespace 폴백.
        detectAppModule(root)?.let { appMod ->
            readGradle(root.resolve(appMod.replace(':', '/')))?.let { text ->
                APPLICATION_ID.find(text)?.groupValues?.get(1)?.let { return it }
                NAMESPACE.find(text)?.groupValues?.get(1)?.let { return it }
            }
        }

        // (3) app 모듈 미식별: 전체에서 applicationId 만 검색(namespace 제외 → 라이브러리
        //     namespace 오인 불가). 단일 모듈(루트/app 에 application plugin 직접) 대비.
        return runCatching {
            Files.walk(root, 5).use { stream ->
                for (path in stream) {
                    if (Files.isDirectory(path)) continue
                    val n = path.fileName?.toString() ?: continue
                    if (n !in GRADLE_FILES) continue
                    val text = runCatching { Files.readString(path) }.getOrNull() ?: continue
                    APPLICATION_ID.find(text)?.groupValues?.get(1)?.let { if (it.contains('.')) return@use it }
                }
                null
            }
        }.getOrNull()
    }

    /**
     * v1.128.0 — clone 된 repo 의 프로젝트 타입 추정. `pubspec.yaml`(루트) → flutter,
     * gradle settings/build(.kts) → kotlin, 둘 다 또는 어느 것도 없으면 null(불명확 →
     * 경고 안 함). clone 시 사용자 선택과 비교해 mismatch 경고에 사용.
     *
     * Flutter 도 android/ 하위에 build.gradle 이 있으므로 **루트 pubspec.yaml** 이 판별 핵심.
     */
    fun detectProjectType(root: Path): String? {
        if (!Files.isDirectory(root)) return null
        if (Files.isRegularFile(root.resolve("pubspec.yaml"))) return ProjectTypes.FLUTTER
        val hasGradle = (GRADLE_FILES + listOf("settings.gradle.kts", "settings.gradle"))
            .any { Files.isRegularFile(root.resolve(it)) }
        return if (hasGradle) ProjectTypes.KOTLIN else null
    }

    private fun readIncludes(root: Path): List<String> {
        for (name in GRADLE_FILES.map { it.replace("build", "settings") }) {
            val f = root.resolve(name)
            if (!Files.isRegularFile(f)) continue
            val text = runCatching { Files.readString(f) }.getOrNull() ?: continue
            // MODULE_PATH 캡처는 leading `:` 이후라 trimStart(':') 불필요.
            return MODULE_PATH.findAll(text)
                .mapNotNull { m -> m.groupValues[1].trim().ifEmpty { null } }
                .toList()
        }
        return emptyList()
    }

    private fun readGradle(moduleDir: Path): String? {
        for (g in GRADLE_FILES) {
            val gf = moduleDir.resolve(g)
            if (!Files.isRegularFile(gf)) continue
            return runCatching { Files.readString(gf) }.getOrNull()
        }
        return null
    }
}
