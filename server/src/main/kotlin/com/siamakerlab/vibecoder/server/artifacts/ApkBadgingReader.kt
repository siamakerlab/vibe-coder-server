package com.siamakerlab.vibecoder.server.artifacts

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.87.0 — APK 의 `versionName` 추출 (best-effort).
 *
 * Android SDK build-tools 의 `aapt`(없으면 `aapt2`) `dump badging` 출력에서
 * `versionName='...'` 를 파싱한다. 빌드 산출물 APK 파일명을 의미있게 짓기 위한
 * 용도 — [ArtifactService] 가 `<packageName>-<variant>-v<versionName>.apk` 로 저장.
 *
 * 도구 부재(빌드환경 미설치) / 타임아웃 / 파싱 실패 시 **null** 을 반환해, 호출자가
 * 버전 없는 파일명(`<packageName>-<variant>.apk`)으로 graceful fallback 하도록 한다.
 * [ApkSignerInspector] 와 동일한 build-tools 탐지 규약(ANDROID_HOME → 최신 폴더).
 */
object ApkBadgingReader {

    private val versionNameRe = Regex("""versionName='([^']*)'""")

    /** [apk] 의 versionName. 추출 실패 시 null. */
    fun versionName(apk: Path): String? {
        val tool = locateAaptTool() ?: run {
            log.debug { "aapt/aapt2 미발견 — versionName 추출 skip (버전 없는 파일명으로 fallback)" }
            return null
        }
        return runCatching {
            // stderr 를 stdout 에 병합하고 단일 스트림을 끝까지 읽어 파이프 포화 데드락 방지.
            val proc = ProcessBuilder(tool, "dump", "badging", apk.toString())
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching null
            }
            versionNameRe.find(out)?.groupValues?.get(1)?.ifBlank { null }
        }.getOrElse {
            log.warn(it) { "aapt dump badging 실패 — versionName 추출 skip" }
            null
        }
    }

    /** ANDROID_HOME/build-tools/<최신>/aapt (없으면 aapt2) 의 절대경로. 없으면 null. */
    private fun locateAaptTool(): String? {
        val sdk = System.getenv("ANDROID_HOME")?.ifBlank { null }
            ?: System.getenv("ANDROID_SDK_ROOT")?.ifBlank { null }
            ?: return null
        val buildTools = Path.of(sdk, "build-tools")
        if (!Files.isDirectory(buildTools)) return null
        val dirs = Files.list(buildTools).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .toList()
                .sortedByDescending { it.fileName.toString() }  // semver string 비교로 충분
        }
        for (dir in dirs) {
            for (name in listOf("aapt", "aapt2")) {
                val tool = dir.resolve(name)
                if (Files.isExecutable(tool)) return tool.toString()
            }
        }
        return null
    }
}
