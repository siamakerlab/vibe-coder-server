package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * v1.126.0 (P3) — Flutter(Android-Flutter) 프로젝트용 toolchain. **Android 앱 빌드 전용**:
 * `flutter build apk` / `flutter build appbundle` 만 실행한다(iOS/web/desktop 미지원 —
 * 로드맵 정책 §1, §4). Flutter 가 내부적으로 android/gradlew 를 호출하므로 wrapper
 * bootstrap·moduleName·debugTask(Gradle 개념)는 사용하지 않는다.
 *
 * 서명: Flutter Android 표준은 `android/key.properties` + `signingConfigs.release` 이고
 * `flutter build` 는 AGP injected signing(`-Pandroid.injected.signing.*`)을 직접 전달하지
 * 못한다. 따라서 [signing] 후보가 있어도 release/bundle 에서 안내 로그만 남기고, 실제
 * 서명은 프로젝트의 key.properties 가 담당한다(Claude 콘솔 scaffold — P4/P5).
 */
class FlutterToolchain(private val config: ServerConfig) : BuildToolchain {

    override suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int {
        // v1.127.2 (정밀리뷰 #1) — flutter 미설치 시 ProcessRunner.run 의 pb.start() 가 IOException
        // 을 던져(try/catch 밖) onFailure 로 raw 예외만 노출됐다. GradleBuilder 가 gradlew 를
        // 선체크하듯, flutter 가용성을 먼저 확인해 친절 안내 + 127 반환.
        if (!isFlutterAvailable()) {
            logger.line(
                "ERROR",
                "flutter 명령을 찾을 수 없습니다. 빌드환경 페이지(/env-setup)의 Flutter 카드로 설치하세요 " +
                    "(vibe-doctor flutter).",
            )
            return 127
        }
        // Android 앱 빌드 전용 — apk(debug/release) / appbundle(release).
        val sub = when (variant) {
            BuildVariant.DEBUG -> listOf("build", "apk", "--debug")
            BuildVariant.RELEASE -> listOf("build", "apk", "--release")
            BuildVariant.BUNDLE -> listOf("build", "appbundle", "--release")
        }
        val command = listOf(flutterBin()) + sub
        logger.info("Flutter build command: ${command.joinToString(" ")}")
        if (signing != null && variant != BuildVariant.DEBUG) {
            logger.info(
                "Flutter release 서명은 android/key.properties + signingConfigs.release 로 처리됩니다 " +
                    "(flutter build 는 injected signing 미지원). 등록 키스토어: " +
                    "storeFile=${signing.storeFile} keyAlias=${signing.keyAlias}"
            )
        }

        val runner = ProcessRunner(workdir = source)
        val result = runner.run(
            command = command,
            timeout = config.build.timeoutMinutes.minutes,
            cancellation = cancellation,
        ) { level, line -> logger.line(level, line) }

        logger.info("Flutter exited code=${result.exitCode} timedOut=${result.timedOut} duration=${result.durationMs}ms")
        return result.exitCode
    }

    /**
     * flutter 실행 가능 여부. 미설치면 ProcessRunner 의 `pb.start()` 가 IOException 을 던지므로
     * (try/catch 밖) 빌드 전에 선확인해 친절 안내 + 127 로 끝낸다. 설치돼 있으면 `flutter
     * --version` 은 빠르고, 미설치면 즉시 IOException → false.
     */
    private fun isFlutterAvailable(): Boolean = try {
        val p = ProcessBuilder(flutterBin(), "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() == 0
        else { p.destroyForcibly(); false }
    } catch (_: Throwable) {
        false
    }

    override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? {
        val rel = when (variant) {
            BuildVariant.DEBUG -> "build/app/outputs/flutter-apk/app-debug.apk"
            BuildVariant.RELEASE -> "build/app/outputs/flutter-apk/app-release.apk"
            BuildVariant.BUNDLE -> "build/app/outputs/bundle/release/app-release.aab"
        }
        val p = source.resolve(rel)
        return p.takeIf { Files.exists(it) }
    }

    /**
     * Flutter 실행 파일. 기본은 PATH 의 `flutter`(entrypoint 가 `/home/vibe/.local/bin` 을
     * PATH 에 등록, flutter.sh 가 거기에 symlink). `FLUTTER_CMD` env 로 override 가능.
     */
    private fun flutterBin(): String =
        System.getenv("FLUTTER_CMD")?.ifBlank { null } ?: "flutter"
}
