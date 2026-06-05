package com.siamakerlab.vibecoder.server.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

object ApkFinder {

    /**
     * Returns the most-recently-modified `.apk` file located in
     * the module's debug output directory.
     *
     * v1.7.24 — moduleName 의 Gradle path separator `:` 를 filesystem 의 `/`
     * 로 변환. multi-module 프로젝트 (예: `android-app:app`) 에서
     * `source.resolve("android-app:app")` 가 잘못된 path 를 만들어 APK 미발견
     * → BUILD SUCCESSFUL 인데 "apk not found" FAILED 처리되던 회귀 fix.
     */
    fun findLatestDebug(source: Path, moduleName: String): Path? =
        findLatest(source, moduleName, "build/outputs/apk/debug", ".apk")

    // v1.107.0 — release APK: build/outputs/apk/release 아래 최신 .apk
    fun findLatestReleaseApk(source: Path, moduleName: String): Path? =
        findLatest(source, moduleName, "build/outputs/apk/release", ".apk")

    // v1.107.0 — release AAB 번들: build/outputs/bundle/release 아래 최신 .aab
    fun findLatestReleaseBundle(source: Path, moduleName: String): Path? =
        findLatest(source, moduleName, "build/outputs/bundle/release", ".aab")

    private fun findLatest(source: Path, moduleName: String, subDir: String, ext: String): Path? {
        val modulePath = moduleName.replace(':', '/')
        val dir = source.resolve(modulePath).resolve(subDir)
        if (!dir.exists()) return null
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(ext, ignoreCase = true) }
                .toList()
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
        }
    }
}
