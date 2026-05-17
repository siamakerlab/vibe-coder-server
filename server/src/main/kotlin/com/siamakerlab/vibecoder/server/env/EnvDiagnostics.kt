package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.shared.dto.CheckItemDto
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import com.siamakerlab.vibecoder.shared.dto.EnvironmentCheckDto
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class EnvDiagnostics(private val config: ServerConfig) {

    fun run(): EnvironmentCheckDto = EnvironmentCheckDto(
        java = checkJava(),
        androidSdk = checkAndroidSdk(),
        git = checkGit(),
        claude = checkClaude(),
        workspace = checkWorkspace(),
    )

    private fun checkJava(): CheckItemDto {
        val version = runtimeCommand(listOf("java", "-version"))
        return if (version.exitCode == 0) {
            CheckItemDto(CheckStatus.OK, "JDK", "java is installed", detail = version.combined.take(200))
        } else {
            CheckItemDto(CheckStatus.ERROR, "JDK", "java not found", detail = version.combined.take(200))
        }
    }

    private fun checkAndroidSdk(): CheckItemDto {
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return when {
            sdkRoot.isNullOrBlank() ->
                CheckItemDto(
                    CheckStatus.WARNING, "Android SDK",
                    "ANDROID_HOME and ANDROID_SDK_ROOT are both unset",
                    detail = "Gradle builds will fail unless local.properties sdk.dir is set per project."
                )
            !Path.of(sdkRoot).exists() ->
                CheckItemDto(
                    CheckStatus.ERROR, "Android SDK",
                    "$sdkRoot does not exist", detail = null
                )
            else ->
                CheckItemDto(CheckStatus.OK, "Android SDK", "ANDROID_HOME=$sdkRoot", detail = null)
        }
    }

    private fun checkGit(): CheckItemDto {
        val v = runtimeCommand(listOf("git", "--version"))
        return if (v.exitCode == 0)
            CheckItemDto(CheckStatus.OK, "Git", v.combined.trim().ifBlank { "git installed" }, detail = null)
        else
            CheckItemDto(CheckStatus.ERROR, "Git", "git CLI not found", detail = v.combined.take(200))
    }

    private fun checkClaude(): CheckItemDto {
        if (!config.claude.enabled)
            return CheckItemDto(CheckStatus.WARNING, "Claude Code", "claude.enabled is false")
        val cmd = resolveClaudeCmd()
        val v = runtimeCommand(listOf(cmd, "--version"))
        return if (v.exitCode == 0)
            CheckItemDto(CheckStatus.OK, "Claude Code", v.combined.trim().ifBlank { "claude installed" }, detail = "cmd=$cmd")
        else
            CheckItemDto(CheckStatus.ERROR, "Claude Code", "claude CLI not found", detail = "tried `$cmd --version` (set CLAUDE_CMD env)")
    }

    private fun checkWorkspace(): CheckItemDto {
        val root = Path.of(config.workspace.root)
        return try {
            if (!root.exists()) Files.createDirectories(root)
            val probe = Files.createTempFile(root, "probe-", ".tmp")
            Files.deleteIfExists(probe)
            CheckItemDto(CheckStatus.OK, "Workspace", "read/write OK", detail = root.toAbsolutePath().toString())
        } catch (e: Throwable) {
            CheckItemDto(CheckStatus.ERROR, "Workspace", "cannot write to ${root.toAbsolutePath()}", detail = e.message)
        }
    }

    private fun resolveClaudeCmd(): String {
        val override = System.getenv("CLAUDE_CMD")
        if (!override.isNullOrBlank()) return override
        if (config.claude.path != "auto") return config.claude.path
        return if (OsType.detect() == OsType.WINDOWS) "claude.cmd" else "claude"
    }

    private data class Captured(val exitCode: Int, val combined: String)

    private fun runtimeCommand(cmd: List<String>): Captured =
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val ok = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // give destroyForcibly a moment to land so exitValue() doesn't
                // throw IllegalThreadStateException on a still-alive process.
                p.destroyForcibly().waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            val exit = runCatching { p.exitValue() }.getOrDefault(-1)
            Captured(exit, out)
        } catch (e: Throwable) {
            Captured(-1, e.message ?: e.javaClass.simpleName)
        }
}
