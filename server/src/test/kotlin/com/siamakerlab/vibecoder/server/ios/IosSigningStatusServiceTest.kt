package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.build.XcodeBuildSettings
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class IosSigningStatusServiceTest {
    @Test
    fun `linux docker mode blocks signing status without mac commands`() {
        val runner = RecordingRunner(emptyMap())
        val root = Files.createTempDirectory("ios-signing-linux")

        val dto = IosSigningStatusService(
            runner = runner,
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).check("demo", root, "kr.codr.demo")

        dto.mode shouldBe "linux"
        dto.ready shouldBe false
        dto.blockedReason shouldBe "mac_required"
        runner.commands.size shouldBe 0
    }

    @Test
    fun `mac local mode validates matching provisioning profile and identity`() {
        val root = Files.createTempDirectory("ios-signing-local")
        XcodeBuildSettings.save(
            root,
            XcodeBuildSettings(
                bundleIdentifier = "kr.codr.demo",
                teamId = "TEAMID1234",
                signingStyle = "manual",
            ),
        )
        val runner = RecordingRunner(
            mapOf(
                "security find-identity -v -p codesigning" to CommandResult(
                    0,
                    """1) 0123456789ABCDEF0123456789ABCDEF01234567 "Apple Distribution: Wody (TEAMID1234)"""",
                    "",
                ),
                PROFILE_SCRIPT_COMMAND to CommandResult(
                    0,
                    wrapProfiles(profile("PROFILE-1", "Demo AppStore", "TEAMID1234", "kr.codr.demo", "2027-01-01T00:00:00Z")),
                    "",
                ),
            )
        )

        val dto = IosSigningStatusService(
            runner = runner,
            osNameProvider = { "Mac OS X" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).check("demo", root, "kr.codr.demo")

        dto.mode shouldBe "mac_local"
        dto.ready shouldBe true
        dto.blockedReason shouldBe null
        dto.codesigningIdentities.single() shouldBe "0123456789ABCDEF0123456789ABCDEF01234567 \"Apple Distribution: Wody (TEAMID1234)\""
        dto.profiles.single().matchingBundleId shouldBe true
        dto.profiles.single().matchingTeamId shouldBe true
        dto.profiles.single().expired shouldBe false
    }

    @Test
    fun `profile parser reports bundle mismatch and expiration`() {
        val parsed = IosProvisioningProfileParser.parseMany(
            wrapProfiles(
                profile("PROFILE-1", "Old Wrong Bundle", "TEAMID1234", "kr.codr.other", "2026-01-01T00:00:00Z")
            ),
            expectedBundleId = "kr.codr.demo",
            expectedTeamId = "TEAMID1234",
            now = Instant.parse("2026-07-22T00:00:00Z"),
        )

        parsed.single().matchingBundleId shouldBe false
        parsed.single().matchingTeamId shouldBe true
        parsed.single().expired shouldBe true
    }

    @Test
    fun `ssh mode wraps profile inspection command`() {
        val runner = RecordingRunner(
            mapOf(
                ssh("security find-identity -v -p codesigning") to CommandResult(0, "", ""),
                ssh(listOf("bash", "-lc", PROFILE_SCRIPT_KEY)) to CommandResult(0, "", ""),
            )
        )
        val root = Files.createTempDirectory("ios-signing-ssh")

        val dto = IosSigningStatusService(
            runner = runner,
            agentConfigProvider = {
                IosAgentSection(
                    enabled = true,
                    mode = "ssh",
                    host = "mac-mini.local",
                    port = 2222,
                    user = "builder",
                )
            },
            osNameProvider = { "Linux" },
            clock = { Instant.parse("2026-07-22T00:00:00Z") },
        ).check("demo", root, "kr.codr.demo")

        dto.mode shouldBe "mac_ssh"
        runner.commands.size shouldBe 2
    }

    private class RecordingRunner(
        private val responses: Map<String, CommandResult>,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            val key = if (command.size == 3 && command[0] == "bash" && command[1] == "-lc" &&
                command[2].contains("Provisioning Profiles")) {
                PROFILE_SCRIPT_COMMAND
            } else {
                command.joinToString(" ")
            }
            return responses[key] ?: CommandResult(127, "", "not found: ${command.joinToString(" ")}")
        }
    }

    companion object {
        private const val PROFILE_SCRIPT_KEY = "set -e\n" +
            "dir=\"\$HOME/Library/MobileDevice/Provisioning Profiles\"\n" +
            "[ -d \"\$dir\" ] || exit 0\n" +
            "find \"\$dir\" -maxdepth 1 -name '*.mobileprovision' -print0 | while IFS= read -r -d '' f; do\n" +
            "  echo 'VIBECODER_PROFILE_BEGIN'\n" +
            "  security cms -D -i \"\$f\" 2>/dev/null || true\n" +
            "  echo 'VIBECODER_PROFILE_END'\n" +
            "done"
        private const val PROFILE_SCRIPT_COMMAND = "PROFILE_DUMP_SCRIPT"

        private fun ssh(command: String): String =
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new builder@mac-mini.local " +
                command.split(" ").joinToString(" ") { "'$it'" }

        private fun ssh(remoteArgv: List<String>): String =
            "ssh -p 2222 -o BatchMode=yes -o StrictHostKeyChecking=accept-new builder@mac-mini.local " +
                remoteArgv.joinToString(" ") { it.shellSingleQuoted() }

        private fun wrapProfiles(vararg profiles: String): String =
            profiles.joinToString("\n") { "VIBECODER_PROFILE_BEGIN\n$it\nVIBECODER_PROFILE_END" }

        private fun profile(uuid: String, name: String, teamId: String, bundleId: String, expiresAt: String): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
              <key>UUID</key><string>$uuid</string>
              <key>Name</key><string>$name</string>
              <key>TeamIdentifier</key><array><string>$teamId</string></array>
              <key>ExpirationDate</key><date>$expiresAt</date>
              <key>Entitlements</key>
              <dict>
                <key>application-identifier</key><string>$teamId.$bundleId</string>
              </dict>
            </dict>
            </plist>
        """.trimIndent()
    }
}
