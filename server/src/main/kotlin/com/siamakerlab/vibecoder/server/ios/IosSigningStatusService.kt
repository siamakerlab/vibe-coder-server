package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.build.XcodeBuildSettings
import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.shared.dto.IosSigningProfileDto
import com.siamakerlab.vibecoder.shared.dto.IosSigningStatusDto
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class IosSigningStatusService(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val osNameProvider: () -> String = { System.getProperty("os.name").orEmpty() },
    private val clock: () -> Instant = { Instant.now() },
) {
    fun check(projectId: String, projectRoot: Path, packageName: String): IosSigningStatusDto {
        val settings = XcodeBuildSettings.load(projectRoot)
        val bundleId = settings.bundleIdentifier.ifBlank { packageName }.trim()
        val teamId = settings.teamId.trim()
        val signingStyle = settings.signingStyle.trim().ifBlank { "automatic" }
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        val osName = osNameProvider().lowercase()
        if (!useSshAgent && !osName.contains("mac")) {
            return base(projectId, bundleId, teamId, signingStyle, "linux").copy(
                blockedReason = "mac_required",
                warnings = listOf("mac_required"),
            )
        }

        val agentRunner = IosAgentCommandRunner(
            config = if (useSshAgent) agent else IosAgentSection(mode = "local"),
            processRunner = runner,
        )
        val identitiesResult = agentRunner.run(IosAgentCommand.CODESIGNING_IDENTITIES, COMMAND_TIMEOUT)
        val profilesResult = agentRunner.run(listOf("bash", "-lc", PROFILE_DUMP_SCRIPT), COMMAND_TIMEOUT)
        val warnings = mutableListOf<String>()
        if (!identitiesResult.ok) warnings += if (useSshAgent && identitiesResult.exitCode == SSH_UNREACHABLE_EXIT_CODE) "agent_unreachable" else "codesigning_identities_unavailable"
        if (!profilesResult.ok) warnings += if (useSshAgent && profilesResult.exitCode == SSH_UNREACHABLE_EXIT_CODE) "agent_unreachable" else "provisioning_profiles_unavailable"
        if (teamId.isBlank() && signingStyle == "manual") warnings += "team_id_missing"
        if (bundleId.isBlank()) warnings += "bundle_id_missing"

        val identities = parseCodesigningIdentities(identitiesResult.stdout)
        val profiles = if (profilesResult.ok) {
            IosProvisioningProfileParser.parseMany(profilesResult.stdout, bundleId, teamId, clock())
        } else emptyList()
        val matchingProfiles = profiles.filter { it.matchingBundleId && (teamId.isBlank() || it.matchingTeamId) && !it.expired }
        if (profilesResult.ok && profiles.isEmpty()) warnings += "provisioning_profile_missing"
        if (profiles.isNotEmpty() && matchingProfiles.isEmpty()) {
            warnings += when {
                profiles.any { it.matchingBundleId && !it.expired } && teamId.isNotBlank() -> "profile_team_mismatch"
                profiles.any { it.matchingTeamId && !it.expired } -> "profile_bundle_mismatch"
                profiles.all { it.expired } -> "profile_expired"
                else -> "profile_mismatch"
            }
        }
        if (identitiesResult.ok && identities.isEmpty() && signingStyle == "manual") warnings += "certificate_missing"

        val blocked = when {
            warnings.contains("agent_unreachable") -> "agent_unreachable"
            warnings.contains("mac_required") -> "mac_required"
            warnings.contains("bundle_id_missing") -> "bundle_id_missing"
            signingStyle == "manual" && warnings.contains("team_id_missing") -> "team_id_missing"
            signingStyle == "manual" && identities.isEmpty() -> "certificate_missing"
            profilesResult.ok && matchingProfiles.isEmpty() -> warnings.firstOrNull {
                it in setOf("provisioning_profile_missing", "profile_team_mismatch", "profile_bundle_mismatch", "profile_expired", "profile_mismatch")
            }
            else -> null
        }

        return base(projectId, bundleId, teamId, signingStyle, if (useSshAgent) "mac_ssh" else "mac_local").copy(
            codesigningIdentities = identities,
            profiles = profiles,
            ready = blocked == null,
            blockedReason = blocked,
            warnings = warnings.distinct(),
        )
    }

    private fun base(projectId: String, bundleId: String, teamId: String, signingStyle: String, mode: String): IosSigningStatusDto =
        IosSigningStatusDto(
            checkedAt = clock().toString(),
            mode = mode,
            projectId = projectId,
            bundleId = bundleId,
            teamId = teamId,
            signingStyle = signingStyle,
        )

    private fun parseCodesigningIdentities(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("""\d+\)\s+[0-9A-Fa-f]{40}\s+".+"""")) }
            .map { it.substringAfter(") ").trim() }
            .distinct()
            .toList()

    companion object {
        private val COMMAND_TIMEOUT: Duration = Duration.ofSeconds(15)
        private const val SSH_UNREACHABLE_EXIT_CODE = 255
        private val PROFILE_DUMP_SCRIPT = """
            set -e
            dir="${'$'}HOME/Library/MobileDevice/Provisioning Profiles"
            [ -d "${'$'}dir" ] || exit 0
            find "${'$'}dir" -maxdepth 1 -name '*.mobileprovision' -print0 | while IFS= read -r -d '' f; do
              echo 'VIBECODER_PROFILE_BEGIN'
              security cms -D -i "${'$'}f" 2>/dev/null || true
              echo 'VIBECODER_PROFILE_END'
            done
        """.trimIndent()
    }
}

object IosProvisioningProfileParser {
    fun parseMany(raw: String, expectedBundleId: String, expectedTeamId: String, now: Instant): List<IosSigningProfileDto> =
        raw.split("VIBECODER_PROFILE_BEGIN")
            .asSequence()
            .mapNotNull { block -> block.substringBefore("VIBECODER_PROFILE_END").takeIf { it.contains("<plist") } }
            .mapNotNull { parsePlist(it, expectedBundleId, expectedTeamId, now) }
            .sortedWith(compareBy<IosSigningProfileDto>({ it.expired }, { it.name.lowercase() }, { it.uuid }))
            .toList()

    internal fun parsePlist(xml: String, expectedBundleId: String, expectedTeamId: String, now: Instant): IosSigningProfileDto? {
        val dict = parseRootDict(xml) ?: return null
        val uuid = dict.string("UUID") ?: return null
        val name = dict.string("Name") ?: uuid
        val teamIds = dict.stringArray("TeamIdentifier")
        val entitlements = dict.dict("Entitlements")
        val applicationIdentifier = entitlements?.string("application-identifier")
        val bundleId = applicationIdentifier?.substringAfter('.', missingDelimiterValue = "")?.ifBlank { null }
        val expiresAt = dict.date("ExpirationDate")
        val expired = expiresAt?.let { runCatching { Instant.parse(it).isBefore(now) }.getOrDefault(false) } ?: false
        return IosSigningProfileDto(
            uuid = uuid,
            name = name,
            teamIds = teamIds,
            bundleId = bundleId,
            expiresAt = expiresAt,
            expired = expired,
            matchingBundleId = expectedBundleId.isNotBlank() && bundleIdMatches(bundleId, expectedBundleId),
            matchingTeamId = expectedTeamId.isNotBlank() && expectedTeamId in teamIds,
        )
    }

    private fun bundleIdMatches(profileBundle: String?, expected: String): Boolean {
        if (profileBundle.isNullOrBlank()) return false
        if (profileBundle == expected) return true
        return profileBundle.endsWith(".*") && expected.startsWith(profileBundle.removeSuffix("*"))
    }

    private fun parseRootDict(xml: String): PlistDict? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.trim().toByteArray(Charsets.UTF_8)))
        val plist = doc.documentElement ?: return@runCatching null
        plist.childElements().firstOrNull { it.tagName == "dict" }?.let { parseDict(it) }
    }.getOrNull()

    private fun parseDict(element: Element): PlistDict {
        val values = mutableMapOf<String, Any>()
        val children = element.childElements()
        var index = 0
        while (index < children.size) {
            val key = children[index]
            if (key.tagName != "key") {
                index++
                continue
            }
            val name = key.textContent.trim()
            val value = children.getOrNull(index + 1) ?: break
            values[name] = parseValue(value)
            index += 2
        }
        return PlistDict(values)
    }

    private fun parseValue(element: Element): Any = when (element.tagName) {
        "string", "date" -> element.textContent.trim()
        "array" -> element.childElements().filter { it.tagName == "string" }.map { it.textContent.trim() }
        "dict" -> parseDict(element)
        else -> element.textContent.trim()
    }

    private fun Element.childElements(): List<Element> {
        val result = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let { result += it }
        }
        return result
    }

    private class PlistDict(private val values: Map<String, Any>) {
        fun string(key: String): String? = values[key] as? String
        fun date(key: String): String? = values[key] as? String
        @Suppress("UNCHECKED_CAST")
        fun stringArray(key: String): List<String> = values[key] as? List<String> ?: emptyList()
        fun dict(key: String): PlistDict? = values[key] as? PlistDict
    }
}
