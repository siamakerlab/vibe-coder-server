package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.SystemClock
import com.siamakerlab.vibecoder.shared.dto.IosKeychainImportDto
import com.siamakerlab.vibecoder.shared.dto.IosKeychainImportRequestDto
import java.time.Duration

class IosKeychainImportService(
    private val clock: Clock = SystemClock(),
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val runnerFactory: (IosAgentSection) -> IosAgentCommandRunner = { IosAgentCommandRunner(it) },
) {
    fun importCertificate(req: IosKeychainImportRequestDto): IosKeychainImportDto {
        val normalized = req.normalized()
        val agent = agentConfigProvider()
        val mode = agent.mode.trim().lowercase().let { if (it in setOf("ssh", "remote") && agent.enabled) "ssh" else "local" }
        if (agent.mode.trim().lowercase() in setOf("ssh", "remote") && !agent.enabled) {
            return base(normalized, mode = "ssh", ok = false, blockedReason = "agent_disabled")
        }
        val runner = runnerFactory(if (mode == "ssh") agent.copy(mode = "ssh") else IosAgentSection(mode = "local"))
        val result = runner.run(listOf("bash", "-lc", buildScript(normalized)), COMMAND_TIMEOUT)
        if (!result.ok) {
            val blocked = if (mode == "ssh" && result.exitCode == SSH_UNREACHABLE_EXIT_CODE) "agent_unreachable" else "keychain_import_failed"
            return base(
                normalized,
                mode = mode,
                ok = false,
                blockedReason = blocked,
                message = sanitize(result.stderr.ifBlank { result.stdout }).take(500),
                warnings = listOf(blocked),
            )
        }
        val identities = parseCodesigningIdentities(result.stdout)
        return IosKeychainImportDto(
            checkedAt = clock.nowIso(),
            mode = mode,
            ok = true,
            keychainPath = keychainPath(normalized.keychainName),
            p12Path = normalized.p12Path,
            created = "__VC_KEYCHAIN_CREATED__" in result.stdout,
            unlocked = "__VC_KEYCHAIN_UNLOCKED__" in result.stdout,
            imported = "__VC_CERT_IMPORTED__" in result.stdout,
            partitionListUpdated = "__VC_PARTITION_LIST_UPDATED__" in result.stdout,
            searchListUpdated = "__VC_SEARCH_LIST_UPDATED__" in result.stdout,
            codesigningIdentities = identities,
            warnings = buildList {
                if (identities.isEmpty()) add("codesigning_identity_not_found_after_import")
            },
        )
    }

    private fun base(
        req: IosKeychainImportRequestDto,
        mode: String,
        ok: Boolean,
        blockedReason: String? = null,
        message: String? = null,
        warnings: List<String> = emptyList(),
    ): IosKeychainImportDto =
        IosKeychainImportDto(
            checkedAt = clock.nowIso(),
            mode = mode,
            ok = ok,
            keychainPath = keychainPath(req.keychainName),
            p12Path = req.p12Path,
            blockedReason = blockedReason,
            message = message,
            warnings = warnings,
        )

    private fun IosKeychainImportRequestDto.normalized(): IosKeychainImportRequestDto {
        val name = keychainName.trim().ifBlank { "vibe-coder" }
        require(name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "keychainName must be 1-64 safe filename characters" }
        val p12 = p12Path.trim()
        require(p12.isNotBlank() && '\u0000' !in p12 && '\n' !in p12) { "p12Path is required" }
        require(p12Password.isNotBlank()) { "p12Password is required" }
        require(keychainPassword.length >= 8) { "keychainPassword must be at least 8 characters" }
        require('\u0000' !in p12Password && '\u0000' !in keychainPassword) { "password must not contain NUL" }
        return copy(p12Path = p12, keychainName = name)
    }

    private fun buildScript(req: IosKeychainImportRequestDto): String {
        val keychain = keychainShellPath(req.keychainName)
        val searchList = if (req.setAsDefaultSearchKeychain) """
            current_keychains=${'$'}(security list-keychains -d user | tr -d '"' | sed 's/^[[:space:]]*//')
            security list-keychains -d user -s "${'$'}keychain" ${'$'}current_keychains
            echo __VC_SEARCH_LIST_UPDATED__
        """.trimIndent() else ""
        return """
            set -e
            keychain=${keychain.shellSingleQuoted()}
            p12=${req.p12Path.shellSingleQuoted()}
            if [ ! -f "${'$'}p12" ]; then
              echo "p12_not_found" >&2
              exit 66
            fi
            if [ ! -f "${'$'}keychain" ]; then
              security create-keychain -p ${req.keychainPassword.shellSingleQuoted()} "${'$'}keychain"
              echo __VC_KEYCHAIN_CREATED__
            fi
            security unlock-keychain -p ${req.keychainPassword.shellSingleQuoted()} "${'$'}keychain"
            echo __VC_KEYCHAIN_UNLOCKED__
            security import "${'$'}p12" -k "${'$'}keychain" -P ${req.p12Password.shellSingleQuoted()} -T /usr/bin/codesign -T /usr/bin/security -T /usr/bin/xcodebuild
            echo __VC_CERT_IMPORTED__
            security set-key-partition-list -S apple-tool:,apple: -s -k ${req.keychainPassword.shellSingleQuoted()} "${'$'}keychain"
            echo __VC_PARTITION_LIST_UPDATED__
            $searchList
            security find-identity -v -p codesigning "${'$'}keychain" || true
        """.trimIndent()
    }

    private fun keychainPath(name: String): String =
        "~/Library/Keychains/$name.keychain-db"

    private fun keychainShellPath(name: String): String =
        "\$HOME/Library/Keychains/$name.keychain-db"

    private fun sanitize(raw: String): String =
        raw.replace(Regex("(?i)(password|passphrase)\\s*[:=]\\s*\\S+"), "$1=<redacted>")

    private fun parseCodesigningIdentities(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("""\d+\)\s+[0-9A-Fa-f]{40}\s+".+"""")) }
            .map { it.substringAfter(") ").trim() }
            .distinct()
            .toList()
    }

    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofMinutes(2)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
    }
}
