package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.32.0 — Gradle 의존성 audit.
 *
 * 두 단계:
 *   1. `gradlew :{module}:dependencies --configuration releaseRuntimeClasspath`
 *      을 실행해 트리를 받아온다. (큰 프로젝트는 수 MB 가능.)
 *   2. `group:name:version` 패턴을 추출해 OSV.dev / GitHub Advisory 같은
 *      외부 DB 와 매칭 ← 이 단계는 v0.32.0 에선 SKIP. 우선 트리만 표시.
 *
 * Full CVE 매칭은 `dependencyCheckAnalyze` plugin 또는 osv-scanner 외부
 * 도구가 필요 (시간이 오래 걸리고 네트워크 필수). v0.32.1+ 에서 별도 minor.
 * 본 cycle 은 "트리 + 버전 추출 + UI" 까지.
 */
class DependencyAudit(
    private val workspace: WorkspacePath,
) {

    data class Coordinate(val group: String, val name: String, val version: String) {
        override fun toString() = "$group:$name:$version"
    }

    data class Result(
        val ok: Boolean,
        val rawOutput: String?,
        val coordinates: List<Coordinate>,
        val errorMessage: String?,
        val moduleName: String,
        val configuration: String,
        val durationMs: Long,
    )

    /**
     * `./gradlew :{module}:dependencies` 호출. timeout 90 s.
     * SDK / Gradle / project 의 wrapper 가 없으면 graceful error.
     */
    fun audit(
        projectId: String,
        moduleName: String,
        configuration: String = "releaseRuntimeClasspath",
    ): Result {
        val root = workspace.projectRoot(projectId)
        if (!Files.isDirectory(root)) {
            return Result(false, null, emptyList(), "project root not found", moduleName, configuration, 0)
        }
        val wrapper = root.resolve(if (isWindows()) "gradlew.bat" else "gradlew")
        if (!Files.isExecutable(wrapper)) {
            return Result(false, null, emptyList(),
                "gradlew not found / not executable at $wrapper", moduleName, configuration, 0)
        }
        val started = System.currentTimeMillis()
        return try {
            val gradleTask = ":${moduleName}:dependencies"
            val cmd = listOf(wrapper.toString(), gradleTask, "--configuration", configuration, "--quiet")
            val pb = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
            pb.environment()["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false"
            val proc = pb.start()
            val output = StringBuilder()
            proc.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (output.length < 200_000) output.appendLine(line)
                }
            }
            if (!proc.waitFor(90, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Result(false, output.toString().take(200_000), emptyList(),
                    "gradle dependencies timeout (90s)", moduleName, configuration,
                    System.currentTimeMillis() - started)
            }
            val ok = proc.exitValue() == 0
            val coords = parseCoordinates(output.toString())
            Result(
                ok = ok,
                rawOutput = output.toString().take(200_000),
                coordinates = coords,
                errorMessage = if (!ok) "gradle exited non-zero (exit=${proc.exitValue()})" else null,
                moduleName = moduleName,
                configuration = configuration,
                durationMs = System.currentTimeMillis() - started,
            )
        } catch (e: Throwable) {
            log.warn(e) { "dep audit failed: $projectId/$moduleName" }
            Result(false, null, emptyList(), e.message ?: e.javaClass.simpleName,
                moduleName, configuration, System.currentTimeMillis() - started)
        }
    }

    /**
     * Gradle `dependencies` 출력에서 `group:name:version` 라인 추출.
     *
     * 출력 예:
     *   +--- androidx.compose.material3:material3:1.3.0
     *   |    +--- androidx.compose.foundation:foundation:1.7.0
     *   \--- com.google.code.gson:gson:2.10.1 -> 2.11.0
     *
     * `-> X` 는 conflict resolution 결과 — 최종 적용 버전을 채택.
     */
    private fun parseCoordinates(raw: String): List<Coordinate> {
        // group, name 은 영문/숫자/_/-/. 만, version 은 숫자/영문/_/-/. 만 (semver 변형 포함)
        val pattern = Regex("([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.\\-+]+)(?:\\s*->\\s*([a-zA-Z0-9_.\\-+]+))?")
        val seen = LinkedHashSet<Coordinate>()
        for (m in pattern.findAll(raw)) {
            val grp = m.groupValues[1]
            val name = m.groupValues[2]
            val ver = if (m.groupValues[4].isNotBlank()) m.groupValues[4] else m.groupValues[3]
            // 너무 짧은 group / name 은 false positive (예: "1:2:3" 단순 숫자열)
            if (grp.length < 3 || name.length < 2) continue
            if (!grp.contains('.')) continue  // group 은 보통 dotted
            seen += Coordinate(grp, name, ver)
        }
        return seen.toList()
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")
}
