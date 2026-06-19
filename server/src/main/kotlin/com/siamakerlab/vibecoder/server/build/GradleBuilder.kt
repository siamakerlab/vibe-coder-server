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
         * Gradle CLI 에 inject. AGP 의 IDE-injected signing path 를 활용한다.
         *
         * v1.144.5 — injected signing 은 build.gradle.kts 설정과 무관하게 **빌드되는
         * variant(debug task 포함)에 그대로 서명을 override** 한다. 따라서 어떤 키스토어를
         * 주입할지는 호출자(BuildService.resolveSigning)가 variant 에 맞춰 결정한다:
         * debug 빌드는 `<pkg>-debug.keystore`, release/bundle 은 `<pkg>.keystore`.
         * (이전 주석은 "debug 는 signingConfigs.debug 필요"라 잘못 설명했고, 실제로는
         * 디버그 빌드에도 릴리즈 키가 주입되던 회귀가 있었다.)
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

        // v1.111.3 — gradlew 가 존재해도 실행 비트(+x)가 없으면(clone/zip 해제/파일 직접 생성 시
        // umask·전송 방식에 따라 누락) POSIX 에서 `./gradlew` 가 error=13(EACCES)로 실패한다.
        // bootstrap(신규 wrapper) 경로만 chmod 하던 한계를 보완 — 매 빌드 전, 이미 존재하는
        // gradlew 에도 실행 권한을 보장한다(idempotent, 이미 +x 면 no-op).
        if (os != OsType.WINDOWS) ensureExecutable(gradlew, logger)

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
                    "(빌드되는 variant 에 그대로 적용 — 키스토어 선택은 빌드 타입에 맞춰 결정됨)"
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

    /**
     * v1.111.3 — POSIX gradlew 에 실행 권한(owner/group/other +x)을 보장. 이미 있으면 no-op.
     * 실패해도(예: 비-POSIX FS) 빌드는 계속 — 권한이 이미 맞아 정상일 수 있다.
     */
    private suspend fun ensureExecutable(gradlew: Path, logger: TaskLogger) {
        runCatching {
            if (!java.nio.file.Files.exists(gradlew)) return
            val perms = java.nio.file.Files.getPosixFilePermissions(gradlew).toMutableSet()
            val before = perms.size
            perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
            perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
            if (perms.size != before) {
                java.nio.file.Files.setPosixFilePermissions(gradlew, perms)
                logger.info("gradlew 실행 권한(+x) 부여 — error=13(EACCES) 방지")
            }
        }.onFailure { logger.line("WARN", "gradlew chmod +x 실패(무시하고 진행): ${it.message}") }
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
            // v1.44.0 — waitFor 성공(=종료됨)일 때만 exitValue() 호출. timeout 분기는
            // destroyForcibly 후 false — destroyForcibly() 는 즉시 종료를 보장 안 해
            // 직후 exitValue() 가 IllegalThreadStateException 을 던질 수 있어 분리.
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.exitValue() == 0
            } else {
                p.destroyForcibly()
                false
            }
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
