package com.siamakerlab.vibecoder.server.publish

import com.siamakerlab.vibecoder.server.env.McpService
import com.siamakerlab.vibecoder.server.ios.AppStoreConnectDiagnosticService
import com.siamakerlab.vibecoder.server.ios.AppStoreConnectKeyStore
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadJobRepository
import com.siamakerlab.vibecoder.server.repo.TestFlightUploadStatus
import com.siamakerlab.vibecoder.server.terminal.ConsolePromptSender
import com.siamakerlab.vibecoder.shared.dto.IosAppStoreConnectDiagnosticDto
import com.siamakerlab.vibecoder.shared.dto.TestFlightUploadJobDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val log = KotlinLogging.logger {}

/**
 * v0.23.0 — TestFlight 자동 업로드.
 *
 * vibe-coder 자체는 iOS 빌드를 수행하지 않는다 (macOS + Xcode 필수, 컨테이너
 * 범위 밖). 대신 사용자가 별도 머신에서 빌드한 `.ipa` 를 워크스페이스 어딘가에
 * 올려 두고, 이 트리거가 MCP `app-store-connect` 를 통해 TestFlight 로
 * 업로드하도록 현재 콘솔 provider 에 지시한다.
 *
 * use case:
 *   - 회사 Mac mini 빌드 농장에서 산출된 .ipa 를 vibe-coder 워크스페이스에
 *     `scp` 또는 `git push` 로 가져와 두고 한 클릭으로 TestFlight 분배.
 *   - 사용자가 Mac 본인 머신을 가지고 있고 SMB / iCloud Drive 로 컨테이너
 *     워크스페이스에 mount 해서 .ipa 를 떨어뜨리는 경우.
 *
 * PlayPublishService 와 동일 패턴 (MCP 위임 → vibe-coder 일관성). Precheck +
 * trigger 두 단계 + track 화이트리스트로 prompt injection 차단.
 */
class TestFlightPublishService(
    private val mcpService: McpService,
    private val promptSender: ConsolePromptSender,
    private val appStoreConnectKeyStore: AppStoreConnectKeyStore? = null,
    private val uploadJobs: TestFlightUploadJobRepository? = null,
    private val appStoreConnectDiagnostics: AppStoreConnectDiagnosticService? =
        appStoreConnectKeyStore?.let { AppStoreConnectDiagnosticService(it) },
) {

    data class Precheck(
        val ready: Boolean,
        val mcpStatus: String,
        val hasKey: Boolean,
        val hasIssuer: Boolean,
        val hasPrivateKey: Boolean,
        val warnings: List<String>,
    )

    fun precheck(): Precheck {
        val state = mcpService.detect("app-store-connect")
        val warnings = mutableListOf<String>()

        val keyId = state.configValues["ASC_KEY_ID"]?.takeIf { it.isNotBlank() }
        val issuer = state.configValues["ASC_ISSUER_ID"]?.takeIf { it.isNotBlank() }
        val pk = state.configValues["ASC_PRIVATE_KEY_FILE"]?.takeIf { it.isNotBlank() }
        val stored = appStoreConnectKeyStore?.get()
        val effectiveKeyId = keyId ?: stored?.keyId?.takeIf { it.isNotBlank() }
        val effectiveIssuer = issuer ?: stored?.issuerId?.takeIf { it.isNotBlank() }
        val effectivePrivateKey = pk ?: stored?.privateKeyPath?.takeIf { stored.privateKeyPresent }

        val mcpStatus = when (state.status) {
            McpService.Status.INSTALLED -> "설치 + 등록 완료"
            McpService.Status.REGISTERED_ONLY -> "등록만 됨 (npm 미설치) — /env-setup/mcp 에서 재설치"
            McpService.Status.NOT_INSTALLED -> "미설치 — /env-setup/mcp 에서 app-store-connect 추가"
            McpService.Status.UNKNOWN -> "확인 불가"
        }

        if (state.status != McpService.Status.INSTALLED) warnings += "app-store-connect MCP 가 설치되지 않았습니다."
        if (effectiveKeyId == null) warnings += "ASC_KEY_ID 가 비어 있습니다."
        if (effectiveIssuer == null) warnings += "ASC_ISSUER_ID 가 비어 있습니다."
        if (effectivePrivateKey == null) warnings += "ASC_PRIVATE_KEY_FILE (.p8) 경로가 비어 있습니다."

        return Precheck(
            ready = state.status == McpService.Status.INSTALLED &&
                effectiveKeyId != null &&
                effectiveIssuer != null &&
                effectivePrivateKey != null,
            mcpStatus = mcpStatus,
            hasKey = effectiveKeyId != null,
            hasIssuer = effectiveIssuer != null,
            hasPrivateKey = effectivePrivateKey != null,
            warnings = warnings,
        )
    }

    fun diagnoseApp(bundleId: String): IosAppStoreConnectDiagnosticDto? =
        appStoreConnectDiagnostics?.diagnose(bundleId)

    fun nextBuildNumber(projectSourcePath: String): String? =
        runCatching { BuildNumberPolicy.next(Path.of(projectSourcePath)) }.getOrNull()

    /**
     * @param projectId       vibe-coder 프로젝트 id (콘솔 세션 라우팅용).
     * @param ipaRelativePath 프로젝트 root 기준 .ipa 경로.
     * @param distributionGroups TestFlight 외부 테스터 그룹 이름 콤마-구분 (선택).
     *                          비우면 internal 만 활성.
     * @param releaseNotes    optional.
     */
    data class UploadRequest(
        val projectId: String,
        val buildId: String? = null,
        val artifactId: String? = null,
        val ipaRelativePath: String,
        val bundleId: String? = null,
        val appId: String? = null,
        val appName: String? = null,
        val buildNumber: String? = null,
        val distributionGroups: String? = null,
        val releaseNotes: String? = null,
    )

    suspend fun trigger(
        projectId: String,
        ipaRelativePath: String,
        distributionGroups: String? = null,
        releaseNotes: String? = null,
    ): TestFlightUploadJobDto? = trigger(
        UploadRequest(
            projectId = projectId,
            ipaRelativePath = ipaRelativePath,
            distributionGroups = distributionGroups,
            releaseNotes = releaseNotes,
        )
    )

    suspend fun trigger(request: UploadRequest): TestFlightUploadJobDto? {
        val groupsLine = request.distributionGroups?.trim().orEmpty().take(200)
            .let { if (it.isBlank()) "외부 테스터 그룹 없이 internal 배포만 진행해 주세요." else "외부 테스터 그룹: $it" }
        val notes = request.releaseNotes?.trim().orEmpty().take(2000)
        val notesLine = if (notes.isNotBlank()) "Release notes:\n$notes" else "Release notes 가 따로 없으면 최근 커밋 메시지 / CHANGELOG 항목을 인용해 주세요."
        val buildNumberLine = request.buildNumber?.takeIf { it.isNotBlank() }?.let {
            "build number: $it (이 값이 프로젝트 설정보다 작거나 같으면 충돌을 피하도록 증가시켜 주세요.)"
        } ?: "build number 는 Info.plist/CURRENT_PROJECT_VERSION/ASC 기존 build 를 확인해 충돌하지 않게 증가시켜 주세요."
        val appLookupLine = listOfNotNull(
            request.bundleId?.takeIf { it.isNotBlank() }?.let { "bundle id: $it" },
            request.appId?.takeIf { it.isNotBlank() }?.let { "App Store Connect app id: $it" },
            request.appName?.takeIf { it.isNotBlank() }?.let { "App Store Connect app name: $it" },
        ).joinToString("\n").ifBlank { "bundle id/app id 는 프로젝트 설정과 ASC 조회 결과를 기준으로 확인해 주세요." }
        val job = uploadJobs?.createQueued(
            projectId = request.projectId,
            buildId = request.buildId,
            artifactId = request.artifactId,
            ipaPath = request.ipaRelativePath,
            bundleId = request.bundleId,
            appId = request.appId,
            appName = request.appName,
            buildNumber = request.buildNumber,
            distributionGroups = request.distributionGroups,
            releaseNotes = request.releaseNotes,
            message = "콘솔 provider 에 TestFlight 업로드 prompt 전송 대기",
        )

        val prompt = """
            이 프로젝트의 .ipa 를 TestFlight 에 업로드해 줘.

            대상 파일 (project root 기준): ${request.ipaRelativePath}
            $appLookupLine
            $buildNumberLine

            도구는 `app-store-connect` MCP 를 사용. 업로드 후 결과 (build number,
            processing 상태, TestFlight 가용성 ETA) 를 1~2줄 요약해 알려줘.
            App Store Connect API key 는 서버 secret store 또는 MCP 환경 설정에 등록된 값을 사용해.

            $groupsLine
            $notesLine

            업로드 실패 (인증, bundleId 누락, version code 충돌, .ipa 손상 등) 시
            원인과 해결 방법을 명확히 알려줘. compliance / export-compliance 같은
            사용자 결정이 필요한 단계가 있으면 진행하지 말고 알려줘.
        """.trimIndent()

        log.info { "TestFlight upload triggered: project=${request.projectId} ipa=${request.ipaRelativePath} job=${job?.id}" }
        return runCatching {
            promptSender.send(request.projectId, prompt, source = "testflight_publish_upload")
            job?.let {
                uploadJobs?.mark(it.id, TestFlightUploadStatus.UPLOADING, "콘솔 provider 에 업로드 prompt 전송 완료")
            } ?: job
        }.getOrElse { e ->
            job?.let {
                uploadJobs?.mark(
                    it.id,
                    TestFlightUploadStatus.FAILED,
                    message = e.message ?: "TestFlight upload prompt send failed",
                    errorCode = "prompt_send_failed",
                )
            }
            throw e
        }
    }
}

object BuildNumberPolicy {
    fun next(projectRoot: Path): String? {
        val current = listOfNotNull(
            readXcodeCurrentProjectVersion(projectRoot),
            readInfoPlistBundleVersion(projectRoot),
            readFlutterPubspecBuild(projectRoot),
        ).maxOrNull() ?: return null
        return (current + 1).toString()
    }

    private fun readXcodeCurrentProjectVersion(root: Path): Long? =
        Files.walk(root, 6).use { stream ->
            stream.iterator().asSequence()
                .filter { it.fileName.toString() == "project.pbxproj" }
                .flatMap { file ->
                    Regex("""CURRENT_PROJECT_VERSION\s*=\s*([0-9]+)""")
                        .findAll(Files.readString(file))
                        .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
                }
                .maxOrNull()
        }

    private fun readInfoPlistBundleVersion(root: Path): Long? =
        Files.walk(root, 6).use { stream ->
            stream.iterator().asSequence()
                .filter { it.fileName.toString() == "Info.plist" }
                .mapNotNull { file ->
                    Regex("""<key>\s*CFBundleVersion\s*</key>\s*<string>\s*([0-9]+)\s*</string>""")
                        .find(Files.readString(file))
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toLongOrNull()
                }
                .maxOrNull()
        }

    private fun readFlutterPubspecBuild(root: Path): Long? {
        val file = root.resolve("pubspec.yaml")
        if (!Files.isRegularFile(file)) return null
        return Regex("""(?m)^\s*version:\s*[^+\s]+(?:\+([0-9]+))?\s*$""")
            .find(Files.readString(file))
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }
}
