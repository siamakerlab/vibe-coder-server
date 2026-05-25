package com.siamakerlab.vibecoder.server.artifacts

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import com.siamakerlab.vibecoder.server.repo.ArtifactRepository
import com.siamakerlab.vibecoder.shared.dto.ApkVerifyResultDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.70.0 — Phase 49 #14. APK 시그너처 검증 (on-demand).
 *
 * `apksigner verify --verbose --print-certs <apk>` 실행 후 응답 파싱.
 * Android SDK 의 build-tools 안에 apksigner 가 있음 — vibe-doctor 가 설치한 SDK
 * 의 latest build-tools 에서 찾는다 (e.g. `/opt/android-sdk/build-tools/35.0.0/apksigner`).
 *
 * 결과는 ArtifactDto / ArtifactRow 에 박지 않고 별도 endpoint 응답 — DB 영속 불요.
 * 사용자가 "verify signature" 클릭 시점에만 실행. APK 크기 별로 1~5초.
 */
class ApkVerifier(
    private val workspace: WorkspacePath,
    private val artifacts: ArtifactRepository,
) {

    // v0.76.0 — inner Result class 제거 → shared `ApkVerifyResultDto` 사용.
    // wire 호환 유지 (필드 동일).

    fun verify(projectId: String, artifactId: String): ApkVerifyResultDto {
        val started = System.currentTimeMillis()
        val row = artifacts.get(projectId, artifactId)
            ?: return ApkVerifyResultDto(false, errors = listOf("artifact $artifactId not found"))
        val apkPath = Path.of(row.filePath)
        if (!Files.exists(apkPath))
            return ApkVerifyResultDto(false, errors = listOf("apk file not found: ${row.filePath}"))

        val apksigner = findApksigner()
            ?: return ApkVerifyResultDto(false, errors = listOf(
                "apksigner not found in /opt/android-sdk/build-tools/*. " +
                "Run vibe-doctor android-sdk first."))

        return try {
            val pb = ProcessBuilder(
                apksigner.toString(), "verify",
                "--verbose", "--print-certs",
                apkPath.toString(),
            ).redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return ApkVerifyResultDto(false, errors = listOf("apksigner timeout (30s)"),
                    durationMs = System.currentTimeMillis() - started)
            }
            parseOutput(output, proc.exitValue()).copy(
                durationMs = System.currentTimeMillis() - started
            )
        } catch (e: Throwable) {
            log.warn(e) { "apksigner verify failed for $artifactId" }
            ApkVerifyResultDto(false, errors = listOf(e.message ?: "verify failed"),
                durationMs = System.currentTimeMillis() - started)
        }
    }

    private fun findApksigner(): Path? {
        // SDK 위치는 environment 가 결정 — workspace.root 외 /opt/android-sdk 표준.
        val sdkRoot = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "/opt/android-sdk"
        val buildTools = Path.of(sdkRoot, "build-tools")
        if (!Files.isDirectory(buildTools)) return null
        return Files.list(buildTools).use { stream ->
            stream.toList()
                .filter { Files.isDirectory(it) }
                .sortedByDescending { it.fileName.toString() }  // 최신 버전 우선
                .mapNotNull {
                    val cand = it.resolve(if (isWindows()) "apksigner.bat" else "apksigner")
                    if (Files.isExecutable(cand)) cand else null
                }
                .firstOrNull()
        }
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")

    private fun parseOutput(output: String, exitCode: Int): ApkVerifyResultDto {
        // apksigner verify 출력 형식:
        //   Verified using v1 scheme (JAR signing): true|false
        //   Verified using v2 scheme (APK Signature Scheme v2): true|false
        //   Verified using v3 scheme (APK Signature Scheme v3): true|false
        //   Signer #1 certificate DN: CN=...
        //   WARNING: ...
        //   ERROR: ...
        val v1 = Regex("v1 scheme.*?:\\s*(true|false)").find(output)?.groupValues?.get(1) == "true"
        val v2 = Regex("v2 scheme.*?:\\s*(true|false)").find(output)?.groupValues?.get(1) == "true"
        val v3 = Regex("v3 scheme.*?:\\s*(true|false)").find(output)?.groupValues?.get(1) == "true"
        val signers = Regex("Signer #\\d+ certificate DN:\\s*(.+)").findAll(output)
            .map { it.groupValues[1].trim() }.toList()
        val warnings = output.lineSequence().filter { it.startsWith("WARNING:") }
            .map { it.removePrefix("WARNING:").trim() }.toList()
        val errors = output.lineSequence().filter { it.startsWith("ERROR:") || it.startsWith("DOES NOT VERIFY") }
            .map { it.trim() }.toList()
        val verified = exitCode == 0 && (v1 || v2 || v3) && errors.isEmpty()
        return ApkVerifyResultDto(
            verified = verified,
            verifiedV1 = v1, verifiedV2 = v2, verifiedV3 = v3,
            signers = signers,
            warnings = warnings,
            errors = errors,
        )
    }
}
