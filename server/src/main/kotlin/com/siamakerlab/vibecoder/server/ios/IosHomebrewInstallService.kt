package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.shared.dto.IosHomebrewInstallResultDto
import java.time.Duration

/**
 * v1.171.0 — 맥 에이전트에 **Homebrew 원탭 설치**. `homebrew_missing`(SwiftLint/CocoaPods 등 설치 실패)
 * 를 해소하기 위해 공식 비대화식 설치 스크립트(`NONINTERACTIVE=1 … install.sh`)를 SSH agent(또는 로컬)로 실행.
 *
 * 전제/한계:
 * - Command Line Tools(`xcode-select -p`)가 없으면 설치 스크립트가 GUI 프롬프트로 멈추므로 **먼저 CLT 를 확인**하고
 *   없으면 `clt_required` 로 조기 실패(사용자에게 Xcode/CLT 먼저 설치 안내).
 * - 최초 설치는 `/opt/homebrew`(Apple Silicon)·`/usr/local`(Intel) 생성에 **sudo** 가 필요 —
 *   비대화식은 passwordless sudo 를 요구한다. 안 되면 `brew_install_failed` + sudo 메시지 반환.
 * - 설치 후 brew 는 `IosAgentCommandRunner` 의 PATH prefix(`/opt/homebrew/bin` 등)로 발견된다.
 */
class IosHomebrewInstallService(
    private val clock: Clock = SystemClock(),
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val runnerFactory: (IosAgentSection) -> IosAgentCommandRunner = { IosAgentCommandRunner(it) },
) {
    fun install(): IosHomebrewInstallResultDto {
        val agent = agentConfigProvider()
        val requestedSsh = agent.mode.trim().lowercase() in setOf("ssh", "remote")
        if (requestedSsh && !agent.enabled) {
            return dto(mode = "ssh", ok = false, blockedReason = "agent_disabled", warnings = listOf("agent_disabled"))
        }
        val effectiveMode = if (requestedSsh) "ssh" else "local"
        val runner = runnerFactory(if (effectiveMode == "ssh") agent.copy(mode = "ssh") else IosAgentSection(mode = "local"))
        val result = runner.run(listOf("bash", "-lc", INSTALL_SCRIPT), COMMAND_TIMEOUT)
        if (!result.ok) {
            val blocked = when {
                effectiveMode == "ssh" && result.exitCode == SSH_UNREACHABLE_EXIT_CODE -> "agent_unreachable"
                "clt_required" in result.stderr || "clt_required" in result.stdout -> "clt_required"
                else -> "brew_install_failed"
            }
            return dto(
                mode = effectiveMode,
                ok = false,
                blockedReason = blocked,
                message = (result.stderr.ifBlank { result.stdout }).trim().take(800).ifBlank { null },
                warnings = listOf(blocked),
            )
        }
        return dto(
            mode = effectiveMode,
            ok = true,
            installed = "__VC_BREW_OK__" in result.stdout,
            brewVersion = markerValue(result.stdout, "__VC_BREW_VERSION__"),
        )
    }

    private fun dto(
        mode: String,
        ok: Boolean,
        installed: Boolean = false,
        brewVersion: String? = null,
        blockedReason: String? = null,
        message: String? = null,
        warnings: List<String> = emptyList(),
    ): IosHomebrewInstallResultDto = IosHomebrewInstallResultDto(
        checkedAt = clock.nowIso(),
        mode = mode,
        ok = ok,
        installed = installed,
        brewVersion = brewVersion,
        blockedReason = blockedReason,
        message = message,
        warnings = warnings,
    )

    private fun markerValue(raw: String, marker: String): String? =
        raw.lineSequence().firstOrNull { it.startsWith(marker) }?.removePrefix(marker)?.trim()?.ifBlank { null }

    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(20)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255

        // brew 있으면 즉시 보고, 없으면 CLT 확인 후 공식 비대화식 설치 → 재확인.
        // ($(...) 는 Kotlin 템플릿이 아니라 리터럴 — 원격 bash 가 평가한다.)
        private val INSTALL_SCRIPT = """
            if command -v brew >/dev/null 2>&1; then
              echo __VC_BREW_OK__
              echo "__VC_BREW_VERSION__ $(brew --version 2>/dev/null | head -1)"
              exit 0
            fi
            if ! xcode-select -p >/dev/null 2>&1; then echo clt_required >&2; exit 68; fi
            NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
            if command -v brew >/dev/null 2>&1; then
              echo __VC_BREW_OK__
              echo "__VC_BREW_VERSION__ $(brew --version 2>/dev/null | head -1)"
            else
              echo brew_install_failed >&2
              exit 70
            fi
        """.trimIndent()
    }
}
