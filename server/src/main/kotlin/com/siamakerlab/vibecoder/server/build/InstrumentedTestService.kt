package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.device.EmulatorService
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

private val log = KotlinLogging.logger {}

/**
 * v1.117.0 — 인스트루먼트(에뮬레이터) 테스트 실행기 — 품질 탭 phase 2.
 *
 * 헤드리스 에뮬레이터(빌드환경 > Emulator) 위에서 `connectedDebugAndroidTest` 를
 * 돌려 Compose UI Test / Espresso / **Accessibility Test Framework(ATF)** 검증을
 * 수행한다. ATF 는 테스트에 `AccessibilityChecks.enable()` 가 있으면 Accessibility
 * Scanner 동급 엔진으로 터치영역/대비/라벨 누락을 동적으로 검출하고, 위반 시 해당
 * 테스트가 실패로 떨어진다 — 즉 본 실행기는 "동적 접근성 검사"의 실행/수집 채널이다.
 *
 * 흐름:
 *   1. 에뮬레이터 booted 확인 (아니면 graceful 안내 — viewer 가 켤 수 없으니 메시지로).
 *   2. `./gradlew :{module}:connectedDebugAndroidTest` 실행 (timeout 900 s).
 *   3. `{module}/build/outputs/androidTest-results/connected/**/TEST-*.xml`(JUnit) 파싱.
 *      AGP 버전에 따라 `connected/` 바로 아래 또는 `connected/debug/` 하위라 walk 로 탐색.
 *   4. 테스트가 0건이면 androidTest 소스 부재로 간주(안내).
 */
class InstrumentedTestService(
    private val workspace: WorkspacePath,
    private val emulator: EmulatorService,
    private val runGuard: GradleRunGuard,
) {

    enum class Outcome { PASSED, FAILED, ERROR, SKIPPED }

    data class TestCase(
        val className: String,
        val name: String,
        val outcome: Outcome,
        val timeSec: Double,
        /** 실패/에러 시 메시지 (없으면 null). */
        val message: String?,
    )

    data class Result(
        val ok: Boolean,
        val errorMessage: String?,
        val cases: List<TestCase>,
        val moduleName: String,
        val durationMs: Long,
        val rawTail: String?,
    ) {
        val total get() = cases.size
        val passed get() = cases.count { it.outcome == Outcome.PASSED }
        val failed get() = cases.count { it.outcome == Outcome.FAILED || it.outcome == Outcome.ERROR }
        val skipped get() = cases.count { it.outcome == Outcome.SKIPPED }
    }

    /** 에뮬레이터가 부팅돼 테스트를 받을 수 있는 상태인지(빠른 사전 체크). */
    fun emulatorReady(): Boolean = runCatching { emulator.booted() }.getOrDefault(false)

    /**
     * v1.117.0 — 인스트루먼트 테스트 "사전 준비" 상태 점검(파일 정적 검사, 빠름).
     * 프로젝트에 androidTest 인프라(러너/의존성/소스/ATF)가 갖춰졌는지 확인해
     * UI 가 체크리스트 + "준비작업" 버튼 노출 여부를 판단한다.
     */
    data class PrepStatus(
        /** src/androidTest 디렉토리 존재. */
        val hasAndroidTestDir: Boolean,
        /** @Test 가 포함된 androidTest 소스 개수. */
        val testSourceCount: Int,
        /** build.gradle 에 testInstrumentationRunner 지정됨. */
        val hasTestRunner: Boolean,
        /** Espresso 또는 Compose UI Test 의존성 존재. */
        val hasUiTestDep: Boolean,
        /** Accessibility Test Framework(ATF) 의존성 또는 AccessibilityChecks 사용. */
        val hasA11yFramework: Boolean,
        /** 점검 자체가 가능했는지(모듈/빌드파일 발견). false 면 모듈명 의심. */
        val inspectable: Boolean,
    ) {
        /** 테스트를 실제로 돌릴 수 있는 최소 조건. */
        val runnable: Boolean get() = inspectable && hasAndroidTestDir && testSourceCount > 0 && hasTestRunner
        /** 동적 접근성(ATF) 검사까지 완비. */
        val fullyReady: Boolean get() = runnable && hasUiTestDep && hasA11yFramework
    }

    fun inspectPrep(projectId: String, moduleName: String): PrepStatus {
        val root = workspace.projectRoot(projectId)
        val moduleDir = moduleDir(root, moduleName)
        val buildFile = sequenceOf("build.gradle.kts", "build.gradle")
            .map { moduleDir.resolve(it) }
            .firstOrNull { Files.isRegularFile(it) }
        if (!Files.isDirectory(moduleDir) || buildFile == null) {
            return PrepStatus(false, 0, false, false, false, inspectable = false)
        }
        val gradle = runCatching { Files.readString(buildFile) }.getOrDefault("")
        val hasRunner = gradle.contains("testInstrumentationRunner")
        val hasUiTest = listOf("espresso", "compose.ui:ui-test", "ui-test-junit4", "androidx.test.ext:junit", "androidx.test:runner")
            .any { gradle.contains(it, ignoreCase = true) }
        val gradleHasA11y = listOf("accessibility-test-framework", "espresso-accessibility")
            .any { gradle.contains(it, ignoreCase = true) }

        val androidTestDir = moduleDir.resolve("src/androidTest")
        val hasDir = Files.isDirectory(androidTestDir)
        var testCount = 0
        var srcHasA11y = false
        if (hasDir) {
            runCatching {
                Files.walk(androidTestDir, 12).use { s ->
                    s.asSequence()
                        .filter { Files.isRegularFile(it) }
                        .filter { it.fileName.toString().let { n -> n.endsWith(".kt") || n.endsWith(".java") } }
                        .forEach { f ->
                            val txt = runCatching { Files.readString(f) }.getOrDefault("")
                            if (txt.contains("@Test")) testCount++
                            if (txt.contains("AccessibilityChecks")) srcHasA11y = true
                        }
                }
            }
        }
        return PrepStatus(
            hasAndroidTestDir = hasDir,
            testSourceCount = testCount,
            hasTestRunner = hasRunner,
            hasUiTestDep = hasUiTest,
            hasA11yFramework = gradleHasA11y || srcHasA11y,
            inspectable = true,
        )
    }

    /**
     * `./gradlew :{module}:connectedDebugAndroidTest` 실행 후 결과 XML 파싱.
     * 같은 프로젝트의 다른 Gradle 작업(lint 포함)과 동시 실행되지 않도록 [runGuard] 로 직렬화.
     */
    fun run(projectId: String, moduleName: String): Result = runGuard.withLock(
        projectId,
        onBusy = {
            Result(false, "이 프로젝트에서 다른 Gradle 작업이 실행 중입니다. 잠시 후 다시 시도하세요.",
                emptyList(), moduleName, 0, null)
        },
    ) {
        runInternal(projectId, moduleName)
    }

    private fun runInternal(projectId: String, moduleName: String): Result {
        val root = workspace.projectRoot(projectId)
        if (!Files.isDirectory(root)) {
            return Result(false, "프로젝트 루트를 찾을 수 없습니다.", emptyList(), moduleName, 0, null)
        }
        if (!emulatorReady()) {
            return Result(false,
                "에뮬레이터가 실행/부팅되어 있지 않습니다. 빌드환경 > Emulator 에서 먼저 기동하세요.",
                emptyList(), moduleName, 0, null)
        }
        val wrapper = root.resolve(if (isWindows()) "gradlew.bat" else "gradlew")
        if (!Files.isExecutable(wrapper)) {
            return Result(false, "gradlew 가 없거나 실행 불가입니다 ($wrapper).", emptyList(), moduleName, 0, null)
        }

        // 이전 결과 XML 을 지워 stale 파싱을 방지(태스크가 UP-TO-DATE 로 스킵돼도 옛 XML 이 남음).
        val resultsDir = moduleDir(root, moduleName).resolve("build/outputs/androidTest-results")
        runCatching { if (Files.isDirectory(resultsDir)) deleteRecursively(resultsDir) }

        val started = System.currentTimeMillis()
        val output = StringBuilder()
        val exit: Int? = try {
            val task = ":${moduleName}:connectedDebugAndroidTest"
            val cmd = listOf(wrapper.toString(), task, "--no-daemon", "--stacktrace")
            val pb = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
            pb.environment()["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false"
            // v1.117.0 — 빌드환경 > Emulator 로 띄운 컨테이너 내 에뮬레이터를 결정적으로 타겟.
            // 같은 컨테이너의 adb 가 보는 디바이스 중 EmulatorService 의 serial(emulator-5554)로
            // 고정해, 외부/추가 디바이스가 잡혀도 connectedAndroidTest 가 그 에뮬레이터를 사용한다.
            pb.environment()["ANDROID_SERIAL"] = emulator.serial
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (output.length < 200_000) output.appendLine(line)
                }
            }
            if (!proc.waitFor(900, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Result(false, "인스트루먼트 테스트 타임아웃 (900s)", emptyList(), moduleName,
                    System.currentTimeMillis() - started, output.toString().takeLast(4_000))
            }
            proc.exitValue()
        } catch (e: Throwable) {
            log.warn(e) { "connectedAndroidTest 실패: $projectId/$moduleName" }
            return Result(false, e.message ?: e.javaClass.simpleName, emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }

        val xmls = findResultXmls(root, moduleName)
        if (xmls.isEmpty()) {
            val hint = when {
                output.contains("No tests found") ->
                    "실행된 테스트가 없습니다. androidTest 소스(예: src/androidTest/...)에 @Test 가 있는지 확인하세요."
                output.contains("Task '") && output.contains("not found") ->
                    "이 모듈에 connectedDebugAndroidTest 태스크가 없습니다 (모듈명: $moduleName)."
                else ->
                    "테스트 결과 XML 을 찾지 못했습니다 (exit=$exit). androidTest 의존성/소스를 확인하세요."
            }
            return Result(false, hint, emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }

        return try {
            val cases = xmls.flatMap { parseJUnit(it) }
                .sortedWith(compareBy({ it.outcome.ordinal == Outcome.PASSED.ordinal }, { it.className }, { it.name }))
            // exit 가 non-zero 여도(테스트 실패) 결과는 유효 — ok=true 로 보여주고 실패는 cases 로 표현.
            Result(true, null, cases, moduleName, System.currentTimeMillis() - started, null)
        } catch (e: Throwable) {
            log.warn(e) { "androidTest 결과 파싱 실패" }
            Result(false, "테스트 결과 파싱 실패: ${e.message}", emptyList(), moduleName,
                System.currentTimeMillis() - started, output.toString().takeLast(4_000))
        }
    }

    private fun findResultXmls(root: Path, moduleName: String): List<Path> {
        val moduleDir = moduleDir(root, moduleName)
        val base = moduleDir.resolve("build/outputs/androidTest-results/connected")
        val searchRoot = if (Files.isDirectory(base)) base else
            moduleDir.resolve("build/outputs/androidTest-results")
        if (!Files.isDirectory(searchRoot)) return emptyList()
        return runCatching {
            Files.walk(searchRoot, 6).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().let { n -> n.startsWith("TEST-") && n.endsWith(".xml") } }
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    /** JUnit XML(testsuite/testcase) 파싱 — XXE 차단 DOM. */
    private fun parseJUnit(xml: Path): List<TestCase> {
        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = Files.newInputStream(xml).use { dbf.newDocumentBuilder().parse(it) }
        val nodes = doc.getElementsByTagName("testcase")
        val out = ArrayList<TestCase>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val className = el.getAttribute("classname").ifBlank { "(unknown)" }
            val name = el.getAttribute("name").ifBlank { "(unnamed)" }
            val timeSec = el.getAttribute("time").toDoubleOrNull() ?: 0.0
            val failure = el.getElementsByTagName("failure").item(0) as? org.w3c.dom.Element
            val error = el.getElementsByTagName("error").item(0) as? org.w3c.dom.Element
            val skipped = el.getElementsByTagName("skipped").length > 0
            val (outcome, msg) = when {
                failure != null -> Outcome.FAILED to (failure.getAttribute("message").ifBlank { failure.textContent }).collapse()
                error != null -> Outcome.ERROR to (error.getAttribute("message").ifBlank { error.textContent }).collapse()
                skipped -> Outcome.SKIPPED to null
                else -> Outcome.PASSED to null
            }
            out += TestCase(className, name, outcome, timeSec, msg?.take(600))
        }
        return out
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { s ->
            s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * Gradle 모듈명을 온디스크 디렉토리로 해석. 중첩 모듈 `:feature:login` 은 표준
     * 레이아웃상 `feature/login` 에 대응하므로 ':' → '/' 로 변환(safeModule 로 traversal 안전).
     */
    private fun moduleDir(root: Path, moduleName: String): Path =
        root.resolve(moduleName.trim(':').replace(':', '/'))

    private fun String.collapse(): String = replace(Regex("\\s+"), " ").trim()
    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")
}
