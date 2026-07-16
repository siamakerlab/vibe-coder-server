package com.siamakerlab.vibecoder.server.publish

import com.siamakerlab.vibecoder.server.agent.AgentRouter
import com.siamakerlab.vibecoder.server.env.McpService
import io.github.oshai.kotlinlogging.KotlinLogging

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
    private val agentRouter: AgentRouter,
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

        val mcpStatus = when (state.status) {
            McpService.Status.INSTALLED -> "설치 + 등록 완료"
            McpService.Status.REGISTERED_ONLY -> "등록만 됨 (npm 미설치) — /env-setup/mcp 에서 재설치"
            McpService.Status.NOT_INSTALLED -> "미설치 — /env-setup/mcp 에서 app-store-connect 추가"
            McpService.Status.UNKNOWN -> "확인 불가"
        }

        if (state.status != McpService.Status.INSTALLED) warnings += "app-store-connect MCP 가 설치되지 않았습니다."
        if (keyId == null) warnings += "ASC_KEY_ID 가 비어 있습니다."
        if (issuer == null) warnings += "ASC_ISSUER_ID 가 비어 있습니다."
        if (pk == null) warnings += "ASC_PRIVATE_KEY_FILE (.p8) 경로가 비어 있습니다."

        return Precheck(
            ready = state.status == McpService.Status.INSTALLED && keyId != null && issuer != null && pk != null,
            mcpStatus = mcpStatus,
            hasKey = keyId != null,
            hasIssuer = issuer != null,
            hasPrivateKey = pk != null,
            warnings = warnings,
        )
    }

    /**
     * @param projectId       vibe-coder 프로젝트 id (콘솔 세션 라우팅용).
     * @param ipaRelativePath 프로젝트 root 기준 .ipa 경로.
     * @param distributionGroups TestFlight 외부 테스터 그룹 이름 콤마-구분 (선택).
     *                          비우면 internal 만 활성.
     * @param releaseNotes    optional.
     */
    suspend fun trigger(
        projectId: String,
        ipaRelativePath: String,
        distributionGroups: String? = null,
        releaseNotes: String? = null,
    ) {
        val groupsLine = distributionGroups?.trim().orEmpty().take(200)
            .let { if (it.isBlank()) "외부 테스터 그룹 없이 internal 배포만 진행해 주세요." else "외부 테스터 그룹: $it" }
        val notes = releaseNotes?.trim().orEmpty().take(2000)
        val notesLine = if (notes.isNotBlank()) "Release notes:\n$notes" else "Release notes 가 따로 없으면 최근 커밋 메시지 / CHANGELOG 항목을 인용해 주세요."

        val prompt = """
            이 프로젝트의 .ipa 를 TestFlight 에 업로드해 줘.

            대상 파일 (project root 기준): $ipaRelativePath

            도구는 `app-store-connect` MCP 를 사용. 업로드 후 결과 (build number,
            processing 상태, TestFlight 가용성 ETA) 를 1~2줄 요약해 알려줘.

            $groupsLine
            $notesLine

            업로드 실패 (인증, bundleId 누락, version code 충돌, .ipa 손상 등) 시
            원인과 해결 방법을 명확히 알려줘. compliance / export-compliance 같은
            사용자 결정이 필요한 단계가 있으면 진행하지 말고 알려줘.
        """.trimIndent()

        log.info { "TestFlight upload triggered: project=$projectId ipa=$ipaRelativePath" }
        agentRouter.sendPrompt(projectId, prompt)
    }
}
