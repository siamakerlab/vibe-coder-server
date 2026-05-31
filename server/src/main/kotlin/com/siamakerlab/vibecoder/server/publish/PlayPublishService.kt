package com.siamakerlab.vibecoder.server.publish

import com.siamakerlab.vibecoder.server.claude.ClaudeSessionManager
import com.siamakerlab.vibecoder.server.env.McpService
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * v0.22.0 — Play Console (Internal Track) 자동 업로드.
 *
 * 직접 Google Play Publishing API 를 호출하지 않고, **MCP `google-play-publisher`
 * 를 통해 Claude 에게 작업을 위임**한다. vibe-coder 의 일관된 디자인 원칙
 * (Claude + MCP 가 모든 외부 통신 담당) 을 유지하기 위함.
 *
 * 흐름:
 *   1. precheck() — MCP 설치/등록 여부 + Service Account JSON / packageName 일치
 *      여부 검사. 미충족 시 안내 메시지.
 *   2. trigger() — 콘솔 세션에 prompt 전송 ("이 .aab 를 Internal Track 업로드").
 *      Claude 가 MCP 도구를 호출해 실제 업로드 수행. 사용자는 콘솔 페이지에서
 *      진행 상황을 실시간 확인.
 *
 * 사용자 입력 (track 명 등) 은 prompt 안에 포함되어 Claude 가 그대로 MCP 인자로
 * 사용. 잘못된 값 (예: 존재하지 않는 track) 은 Claude 응답에서 즉시 노출.
 */
class PlayPublishService(
    private val mcpService: McpService,
    private val sessionManager: ClaudeSessionManager,
) {

    data class Precheck(
        val ready: Boolean,
        val mcpStatus: String,
        val configuredPackageName: String?,
        val warnings: List<String>,
    )

    /**
     * [projectPackageName] 은 ProjectDto.packageName. MCP 의 `GOOGLE_PLAY_PACKAGE_NAME`
     * 와 다르면 warning. (단일 사용자 환경에서 MCP 는 1회 등록되므로 여러 프로젝트가
     * 동일 packageName 을 공유하지 않는다면 항상 mismatch — 그래도 차단은 아닌
     * 경고 수준으로만 노출.)
     */
    fun precheck(projectPackageName: String): Precheck {
        val state = mcpService.detect("google-play-publisher")
        val warnings = mutableListOf<String>()
        val configuredPkg = state.configValues["GOOGLE_PLAY_PACKAGE_NAME"]?.takeIf { it.isNotBlank() }

        val mcpStatus = when (state.status) {
            McpService.Status.INSTALLED -> "설치 + 등록 완료"
            McpService.Status.REGISTERED_ONLY -> "등록만 됨 (npm 미설치) — /env-setup/mcp 에서 재설치 필요"
            McpService.Status.NOT_INSTALLED -> "미설치 — /env-setup/mcp 에서 google-play-publisher 추가"
            McpService.Status.UNKNOWN -> "확인 불가"
        }

        if (state.status != McpService.Status.INSTALLED) {
            warnings += "google-play-publisher MCP 가 설치되지 않았습니다."
        }
        if (state.configValues["GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"].isNullOrBlank()) {
            warnings += "Service Account JSON 경로가 비어 있습니다."
        }
        if (configuredPkg != null && configuredPkg != projectPackageName) {
            warnings += "MCP 등록 패키지 ($configuredPkg) 와 프로젝트 패키지 ($projectPackageName) 가 다릅니다. " +
                "여러 앱을 운영 중이라면 MCP 는 1개만 등록되므로 의도된 상황일 수 있습니다."
        }

        return Precheck(
            ready = state.status == McpService.Status.INSTALLED &&
                !state.configValues["GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"].isNullOrBlank(),
            mcpStatus = mcpStatus,
            configuredPackageName = configuredPkg,
            warnings = warnings,
        )
    }

    /**
     * 콘솔 세션에 업로드 prompt 발송. session 이 idle 이면 새로 spawn.
     *
     * @param projectId       프로젝트 id (`claude/console/prompt` 와 동일 라우팅).
     * @param aabRelativePath 프로젝트 root 기준 .aab 경로 (예: "app/build/outputs/bundle/release/app-release.aab").
     *                        파일 존재 여부는 검사하지 않음 — Claude 가 진행 중 발견 안 되면 보고.
     * @param track           "internal" / "alpha" / "beta" / "production". 기본 internal.
     * @param releaseNotes    optional. 비우면 Claude 가 git log 등으로 추론.
     */
    suspend fun trigger(
        projectId: String,
        aabRelativePath: String,
        track: String = "internal",
        releaseNotes: String? = null,
    ) {
        val safeTrack = sanitizeTrack(track)
        val notes = releaseNotes?.trim().orEmpty().take(2000)
        val notesLine = if (notes.isNotBlank()) "Release notes:\n$notes" else "Release notes 가 따로 없으면 마지막 커밋 메시지를 사용해 주세요."

        val prompt = """
            이 프로젝트의 release AAB 를 Google Play Console 의 $safeTrack track 에 업로드해 줘.

            대상 파일 (project root 기준): $aabRelativePath
            track: $safeTrack

            도구는 `google-play-publisher` MCP 를 사용. 업로드 후 결과 (version code, edit id,
            업로드 상태) 를 1~2줄로 요약해 알려줘.

            $notesLine

            업로드 실패 (인증 오류, packageName 불일치, version code 충돌 등) 시 원인과
            해결 방법을 명확히 알려줘. publish 단계는 자동으로 commit 하지 말고 review 가
            필요한 상태로 남겨 둘 것.
        """.trimIndent()

        log.info { "Play upload triggered: project=$projectId aab=$aabRelativePath track=$safeTrack" }
        sessionManager.sendPrompt(projectId, prompt)
    }

    /**
     * v1.66.0 — 스토어 자산 탭에서 호출. release AAB 업로드 + (옵션) 스토어 listing
     * 자산(피처 그래픽 / 언어별 스크린샷) 반영을 한 prompt 로 Claude 에 위임.
     *
     * [screenshotsByLang] = lang → ["screenshot-ko-1.png", ...] (project root 기준).
     */
    suspend fun triggerStoreUpload(
        projectId: String,
        track: String = "internal",
        releaseNotes: String? = null,
        includeListing: Boolean = true,
        hasGraphic: Boolean = false,
        screenshotsByLang: Map<String, List<String>> = emptyMap(),
        aabRelativePath: String = "app/build/outputs/bundle/release/app-release.aab",
    ) {
        val safeTrack = sanitizeTrack(track)
        val notes = releaseNotes?.trim().orEmpty().take(2000)
        val notesLine = if (notes.isNotBlank()) notes else "(없으면 마지막 커밋 메시지를 사용)"

        val listingBlock = if (!includeListing) "" else buildString {
            appendLine()
            appendLine("2) 스토어 등록정보(listing) 반영 — 프로젝트 root 의 자산 파일 사용:")
            if (hasGraphic) appendLine("   - 피처 그래픽: graphic.png")
            if (screenshotsByLang.isNotEmpty()) {
                appendLine("   - 스크린샷(언어별):")
                screenshotsByLang.toSortedMap().forEach { (lang, files) ->
                    appendLine("       $lang: ${files.joinToString(", ")}")
                }
            }
            if (!hasGraphic && screenshotsByLang.isEmpty()) {
                appendLine("   - (반영할 그래픽/스크린샷 파일이 없음 — listing 자산은 건너뛰고 바이너리만)")
            }
            appendLine("   - 앱 아이콘은 이미 res/mipmap 에 적용된 것을 사용(별도 listing icon 필요 시 root 의 icon.png).")
        }

        val prompt = """
            이 프로젝트를 Google Play Console 에 업로드해 줘. 도구는 `google-play-publisher` MCP 사용.

            1) 바이너리: release AAB 를 $safeTrack track 에 업로드.
               - 대상(project root 기준): $aabRelativePath
               - 없으면 release AAB 산출물 위치를 탐색(필요 시 빌드 방법 안내만, 자동 빌드는 하지 말 것).
            $listingBlock
            3) Release notes: $notesLine

            업로드/반영 결과(version code, track, 반영 항목)를 1~2줄로 요약해 줘. 실패(인증/packageName
            불일치/version code 충돌 등) 시 원인과 해결 방법을 알려줘. publish 단계는 자동 commit 하지 말고
            review 가 필요한 상태로 남겨 둘 것.
        """.trimIndent()

        log.info { "Play store upload triggered: project=$projectId track=$safeTrack listing=$includeListing" }
        sessionManager.sendPrompt(projectId, prompt)
    }

    private fun sanitizeTrack(raw: String): String {
        val v = raw.trim().lowercase()
        // 화이트리스트로 prompt injection 방지.
        return if (v in setOf("internal", "alpha", "beta", "production")) v else "internal"
    }
}
