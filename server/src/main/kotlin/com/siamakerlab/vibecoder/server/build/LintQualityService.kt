package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.streams.asSequence

private val log = KotlinLogging.logger {}

/**
 * v1.116.0 — Android Lint 기반 품질/접근성 검사 (프로젝트 "품질" 탭 MVP).
 *
 * Google 공식 정적 분석기 Android Lint 를 헤드리스(에뮬레이터 불필요)로 돌려
 * 접근성(Accessibility) / 정확성(Correctness) / 보안(Security) / 성능(Performance)
 * 이슈를 추출한다. 결과는 "품질" 탭에서 카테고리별로 보고, 선택한 이슈를 콘솔의
 * Claude 세션으로 "수정요청" 으로 바로 전송한다.
 *
 * 흐름:
 *   1. `./gradlew :{module}:lintDebug` 실행 (timeout 300 s).
 *   2. `{module}/build/reports/lint-results-debug.xml` 파싱.
 *      - lint 는 Error 심각도 이슈가 있으면 abortOnError(AGP 기본 true) 로 비정상
 *        종료하지만, **리포트는 그 전에 기록**되므로 exit code 와 무관하게 XML 존재
 *        여부로 성공을 판정한다.
 *   3. XML 이 없으면(= 안드로이드 모듈 아님 / lint 태스크 부재 / 진짜 실패) graceful error.
 *
 * 인스트루먼트 ATF(Accessibility Test Framework, Accessibility Scanner 동급 엔진)는
 * 에뮬레이터 + 테스트 주입이 필요해 후속 단계(phase 2)로 분리한다.
 */
class LintQualityService(
    private val workspace: WorkspacePath,
    private val runGuard: GradleRunGuard,
) {

    data class Issue(
        /** lint issue id (예: ContentDescription, HardcodedText). */
        val id: String,
        /** Error / Warning / Information / Fatal. */
        val severity: String,
        /** Accessibility / Correctness / Security / Performance / Usability / I18N … */
        val category: String,
        /** 우선순위 1(높음)~10(낮음). */
        val priority: Int,
        /** 이 발생 지점의 구체 메시지. */
        val message: String,
        /** issue 일반 요약(발생별 공통). */
        val summary: String,
        /** 프로젝트 루트 기준 상대 경로. 위치 정보 없으면 null. */
        val file: String?,
        val line: Int?,
    )

    data class Result(
        val ok: Boolean,
        val errorMessage: String?,
        val issues: List<Issue>,
        val moduleName: String,
        val durationMs: Long,
        /** 실패 시 gradle 출력 tail (디버그용). */
        val rawTail: String?,
    )

    /** 카테고리 → 검출 수 (UI 필터 칩 카운트). issues 순회로 계산. */
    fun categoryCounts(issues: List<Issue>): Map<String, Int> =
        issues.groupingBy { it.category }.eachCount()

    /**
     * `./gradlew :{module}:lintDebug` 실행 후 리포트 XML 파싱.
     * 같은 프로젝트의 다른 Gradle 작업과 동시 실행되지 않도록 [runGuard] 로 직렬화.
     */
    fun lint(projectId: String, moduleName: String): Result = runGuard.withLock(
        projectId,
        onBusy = {
            Result(false, "이 프로젝트에서 다른 Gradle 작업이 실행 중입니다. 잠시 후 다시 시도하세요.",
                emptyList(), moduleName, 0, null)
        },
    ) {
        lintInternal(projectId, moduleName)
    }

    private fun lintInternal(projectId: String, moduleName: String): Result {
        val root = workspace.projectRoot(projectId)
        if (!Files.isDirectory(root)) {
            return Result(false, "프로젝트 루트를 찾을 수 없습니다.", emptyList(), moduleName, 0, null)
        }
        val wrapper = root.resolve(if (isWindows()) "gradlew.bat" else "gradlew")
        if (!Files.isExecutable(wrapper)) {
            return Result(false, "gradlew 가 없거나 실행 불가입니다 ($wrapper).", emptyList(), moduleName, 0, null)
        }

        val started = System.currentTimeMillis()
        val output = StringBuilder()
        val exit: Int? = try {
            val task = ":${moduleName}:lintDebug"
            val cmd = listOf(wrapper.toString(), task, "--no-daemon", "--stacktrace")
            val pb = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
            pb.environment()["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false"
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (output.length < 200_000) output.appendLine(line)
                }
            }
            if (!proc.waitFor(300, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Result(false, "lint 타임아웃 (300s)", emptyList(), moduleName,
                    System.currentTimeMillis() - started, output.toString().takeLast(4_000))
            }
            proc.exitValue()
        } catch (e: Throwable) {
            log.warn(e) { "lint 실행 실패: $projectId/$moduleName" }
            return Result(false, e.message ?: e.javaClass.simpleName, emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }

        val xml = findReport(root, moduleName)
        if (xml == null) {
            // exit code 와 무관하게 리포트가 없으면 진짜 실패(태스크 부재 등).
            val hint = when {
                output.contains("Task '") && output.contains("not found") ->
                    "이 모듈에 lint 태스크가 없습니다. 안드로이드 모듈명이 맞는지 확인하세요 (현재: $moduleName)."
                else -> "lint 리포트(lint-results-debug.xml)를 찾지 못했습니다 (exit=$exit)."
            }
            return Result(false, hint, emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }

        return try {
            val issues = parseReport(xml, root)
            Result(true, null, issues, moduleName, System.currentTimeMillis() - started, null)
        } catch (e: Throwable) {
            log.warn(e) { "lint 리포트 파싱 실패: $xml" }
            Result(false, "lint 리포트 파싱 실패: ${e.message}", emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }
    }

    /**
     * 리포트 XML 위치 탐색. 1차로 표준 경로, 없으면 build/reports 하위에서 최신
     * `lint-results-debug.xml` 을 탐색(다중 변형/커스텀 위치 대비).
     */
    private fun findReport(root: Path, moduleName: String): Path? {
        val moduleDir = moduleDir(root, moduleName)
        val standard = moduleDir.resolve("build/reports/lint-results-debug.xml")
        if (Files.isRegularFile(standard)) return standard
        val moduleBuild = moduleDir.resolve("build")
        val searchRoot = if (Files.isDirectory(moduleBuild)) moduleBuild else root
        if (!Files.isDirectory(searchRoot)) return null
        return runCatching {
            Files.walk(searchRoot, 6).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString() == "lint-results-debug.xml" }
                    .maxByOrNull { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            }
        }.getOrNull()
    }

    /**
     * lint XML 파싱 (XXE 차단 DOM). 각 `<issue>` 에서 id/severity/category/priority/
     * message/summary + 첫 `<location>` 의 file/line 추출.
     */
    private fun parseReport(xml: Path, root: Path): List<Issue> {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            // XXE 방어: DOCTYPE / 외부 엔티티 전면 차단.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = Files.newInputStream(xml).use { dbf.newDocumentBuilder().parse(it) }
        val nodes = doc.getElementsByTagName("issue")
        val out = ArrayList<Issue>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val id = el.getAttribute("id").ifBlank { "Unknown" }
            val severity = el.getAttribute("severity").ifBlank { "Warning" }
            val category = el.getAttribute("category").ifBlank { "Other" }
            val priority = el.getAttribute("priority").toIntOrNull() ?: 5
            val message = el.getAttribute("message").collapse()
            val summary = el.getAttribute("summary").collapse()

            var file: String? = null
            var line: Int? = null
            val locs = el.getElementsByTagName("location")
            if (locs.length > 0) {
                val loc = locs.item(0) as? org.w3c.dom.Element
                val abs = loc?.getAttribute("file")?.ifBlank { null }
                if (abs != null) {
                    file = runCatching {
                        val p = Path.of(abs)
                        if (p.startsWith(root)) root.relativize(p).toString() else p.fileName.toString()
                    }.getOrDefault(abs)
                }
                line = loc?.getAttribute("line")?.toIntOrNull()
            }
            out += Issue(id, severity, category, priority, message, summary, file, line)
        }
        // 접근성 먼저, 그다음 심각도(Error→Warning), 그다음 우선순위.
        return out.sortedWith(
            compareByDescending<Issue> { it.category.startsWith("Accessibility") }
                .thenBy { severityRank(it.severity) }
                .thenBy { it.priority }
                .thenBy { it.file ?: "" }
        )
    }

    /**
     * Gradle 모듈명을 온디스크 디렉토리로 해석. 중첩 모듈 `:feature:login` 은
     * 표준 레이아웃상 `feature/login` 디렉토리에 대응하므로 ':' → '/' 로 변환한다.
     * (safeModule 로 '/' '\' '..' 가 이미 걸러져 traversal 안전.)
     */
    private fun moduleDir(root: Path, moduleName: String): Path =
        root.resolve(moduleName.trim(':').replace(':', '/'))

    private fun severityRank(s: String): Int = when (s.lowercase()) {
        "fatal", "error" -> 0
        "warning" -> 1
        else -> 2
    }

    private fun String.collapse(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")
}
