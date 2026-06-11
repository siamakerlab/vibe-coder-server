package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * v1.126.0 (P3) — Kotlin(Android-Kotlin) 프로젝트용 toolchain. 기존 [GradleBuilder] +
 * [ApkFinder] 동작을 그대로 위임한다. v1.107.0 까지 BuildService 가 직접 호출하던 흐름과
 * **동작 동일** — 추상화만 추가(회귀 없음).
 */
class GradleToolchain(private val builder: GradleBuilder) : BuildToolchain {

    override suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int {
        val task = when (variant) {
            BuildVariant.DEBUG -> debugTask
            BuildVariant.RELEASE -> releaseTaskFor(debugTask)
            BuildVariant.BUNDLE -> "bundleRelease"
        }
        return builder.runAssembleDebug(
            source = source,
            moduleName = moduleName,
            debugTask = task,
            logger = logger,
            cancellation = cancellation,
            signing = signing,
        )
    }

    override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? =
        when (variant) {
            BuildVariant.DEBUG -> ApkFinder.findLatestDebug(source, moduleName)
            BuildVariant.RELEASE -> ApkFinder.findLatestReleaseApk(source, moduleName)
            BuildVariant.BUNDLE -> ApkFinder.findLatestReleaseBundle(source, moduleName)
        }

    /** debugTask 로부터 release assemble task 추정 (assembleDebug → assembleRelease).
     *  v1.107.0 에서 BuildService.releaseTaskFor 였던 로직을 toolchain 으로 이동. */
    private fun releaseTaskFor(debugTask: String): String = when {
        debugTask.equals("assembleDebug", ignoreCase = true) -> "assembleRelease"
        debugTask.contains("Debug") -> debugTask.replace("Debug", "Release")
        else -> "assembleRelease"
    }
}
