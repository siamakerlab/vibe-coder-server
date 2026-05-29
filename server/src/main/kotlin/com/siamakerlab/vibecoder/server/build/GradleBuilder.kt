package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

class GradleBuilder(private val config: ServerConfig) {

    suspend fun runAssembleDebug(
        source: Path,
        moduleName: String,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        /**
         * v1.8.0 — 매칭되는 키스토어가 있으면 `android.injected.signing.*` 4종을
         * Gradle CLI 에 inject. AGP 의 IDE-injected signing path 를 활용 — release
         * variant 빌드 시 즉시 효과. debug variant 는 build.gradle.kts 의
         * `signingConfigs.debug` 가 명시되어 있어야 적용되므로, Phase 2 (Claude
         * 콘솔의 "Apply signing" 액션) 와 보완 관계.
         */
        signing: SigningCredentials? = null,
    ): Int {
        val os = OsType.detect()

        // v0.12.3 — gradlew 가 없으면 system gradle 로 wrapper bootstrap 1회.
        // 신규 프로젝트가 build.gradle.kts 만 있고 wrapper 가 없는 경우 자동 처리.
        val wrapperName = if (os == OsType.WINDOWS) "gradlew.bat" else "gradlew"
        val gradlew = source.resolve(wrapperName)
        if (!java.nio.file.Files.exists(gradlew)) {
            val bootstrapped = bootstrapWrapper(source, logger)
            if (!bootstrapped) {
                logger.line("ERROR",
                    "gradlew 없음 + system gradle 부재로 wrapper bootstrap 실패. " +
                        "빌드환경 페이지 (/env-setup) 에서 Gradle 카드를 통해 설치하거나 " +
                        "프로젝트에 gradle/wrapper/ 파일을 직접 제공하세요.")
                return 127   // command not found
            }
        }

        // Use `:module:assembleDebug` syntax which works on every OS without quoting.
        val fullTask = ":$moduleName:$debugTask"
        val signingArgs = signing?.toInjectedArgs().orEmpty()
        val tasks = listOf(fullTask, "--no-daemon", "--stacktrace") + signingArgs
        val command = os.gradleCommand(source, tasks)
        // v1.8.0 — store/key password 는 ps 노출은 단일사용자 컨테이너 특성상 감수하지만
        // 빌드 로그에 평문 echo 되지 않도록 redact.
        val printable = command.map { redactSigningArg(it) }
        logger.info("OS=$os, gradle command: ${printable.joinToString(" ")}")
        if (signing != null) {
            logger.info(
                "Signing injected: storeFile=${signing.storeFile} keyAlias=${signing.keyAlias} " +
                    "(release variant 즉시 적용 / debug 는 build.gradle.kts 의 signingConfigs.debug 가 필요)"
            )
        }

        val runner = ProcessRunner(workdir = source)
        val result = runner.run(
            command = command,
            timeout = config.build.timeoutMinutes.minutes,
            cancellation = cancellation,
        ) { level, line -> logger.line(level, line) }

        logger.info("Gradle exited code=${result.exitCode} timedOut=${result.timedOut} duration=${result.durationMs}ms")
        return result.exitCode
    }

    private fun SigningCredentials.toInjectedArgs(): List<String> = listOf(
        "-Pandroid.injected.signing.store.file=$storeFile",
        "-Pandroid.injected.signing.store.password=$storePassword",
        "-Pandroid.injected.signing.key.alias=$keyAlias",
        "-Pandroid.injected.signing.key.password=$keyPassword",
    )

    private fun redactSigningArg(arg: String): String = when {
        arg.startsWith("-Pandroid.injected.signing.store.password=") ->
            "-Pandroid.injected.signing.store.password=***"
        arg.startsWith("-Pandroid.injected.signing.key.password=") ->
            "-Pandroid.injected.signing.key.password=***"
        else -> arg
    }

    /**
     * v0.12.3 — system gradle 로 wrapper bootstrap. POSIX 만 지원 (Windows 운영 대상 아님).
     *
     * `gradle wrapper --gradle-version <X>` 실행 → projectDir 에 gradlew,
     * gradlew.bat, gradle/wrapper/ 아래 jar + properties 생성.
     *
     * system gradle 부재면 false 반환 — 호출자가 사용자에게 안내.
     */
    private suspend fun bootstrapWrapper(source: Path, logger: TaskLogger): Boolean {
        // 1. system gradle 가용 여부
        val probe = try {
            // v1.43.0 — 출력은 불필요(종료코드만). 이전엔 redirectErrorStream(true) 인데
            // 합쳐진 출력을 읽지 않아 `gradle --version` 의 수십 줄이 파이프를 채우면 자식이
            // write 에서 block → 매번 5초 timeout + destroyForcibly 로 gradle 설치본을 "미설치"
            // 오판 가능. stdout/stderr 모두 DISCARD 로 파이프 포화 자체를 제거.
            val p = ProcessBuilder("gradle", "--version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            p.exitValue() == 0
        } catch (_: Throwable) { false }
        if (!probe) {
            logger.info("system gradle 미설치 → wrapper bootstrap 불가")
            return false
        }
        logger.info("system gradle 발견 → 'gradle wrapper' 자동 부트스트랩 시작")

        val pb = ProcessBuilder(
            "gradle", "wrapper",
            "--gradle-version", "8.7",
            "--distribution-type", "bin",
        ).directory(source.toFile()).redirectErrorStream(true)
        val proc = try { pb.start() } catch (e: Throwable) {
            logger.line("ERROR", "gradle wrapper spawn 실패: ${e.message}")
            return false
        }
        proc.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { logger.line("STDOUT", it) }
        }
        val ok = proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
        if (!ok) { proc.destroyForcibly(); logger.line("ERROR", "wrapper bootstrap 시간 초과"); return false }
        val exit = proc.exitValue()
        if (exit != 0) {
            logger.line("ERROR", "wrapper bootstrap exit $exit")
            return false
        }
        // 생성된 gradlew 에 실행 권한 부여 (umask 따라 다를 수 있음)
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                source.resolve("gradlew"),
                java.nio.file.Files.getPosixFilePermissions(source.resolve("gradlew"))
                    + java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                    + java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
                    + java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            )
        }
        logger.info("✓ wrapper bootstrap 완료 — gradlew 사용 가능")
        return true
    }
}
