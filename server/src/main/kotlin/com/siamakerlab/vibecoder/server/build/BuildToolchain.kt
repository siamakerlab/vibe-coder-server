package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * v1.126.0 (P3) — 빌드 variant. toolchain 무관 메타데이터(산출물 확장자 / artifact type /
 * `BuildRow.variant` 에 저장되는 wire 문자열). 값은 v1.107.0 의 기존 문자열과 동일하게
 * 유지(wire/artifact 호환): debug / release / release-bundle.
 */
enum class BuildVariant(val wire: String, val ext: String, val artifactType: String) {
    DEBUG("debug", "apk", "debug-apk"),
    RELEASE("release", "apk", "release-apk"),
    BUNDLE("release-bundle", "aab", "release-aab"),
}

/**
 * v1.126.0 (P3) — 프로젝트 빌드 도구 추상화. Kotlin(Gradle) / Flutter 공통 인터페이스.
 *
 * `BuildService` 가 `ProjectRow.projectType` 으로 구현을 선택([GradleToolchain] /
 * [FlutterToolchain]). 빌드 큐 / 로그 / 서명 후보 조회 / 알림 흐름은 BuildService 가
 * 그대로 소유하고, "실제 빌드 명령 실행 + 산출물 경로 해석"만 toolchain 으로 위임한다.
 *
 * Flutter(Android 앱 빌드 전용) 로드맵: docs/01-plan/flutter-android-support.md §8.
 */
interface BuildToolchain {

    /**
     * [variant] 빌드를 실행하고 종료 코드를 반환한다.
     *
     * @param debugTask Gradle debug task 이름(보통 `assembleDebug`). Gradle 전용 —
     *   Flutter 구현은 무시(Flutter 는 task/모듈 개념 없이 `flutter build apk` 사용).
     * @param signing 매칭 키스토어 서명 후보(없으면 null). Gradle 은 AGP injected
     *   signing 으로 사용, Flutter 는 android/key.properties 표준이라 안내만 한다.
     */
    suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int

    /** [variant] 산출물(APK/AAB) 경로. 없으면 null(호출부가 `artifact_not_found` 처리). */
    fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path?
}
