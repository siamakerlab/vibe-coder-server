package com.siamakerlab.vibecoder.server.build

import com.siamakerlab.vibecoder.server.admin.SigningCredentials
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.core.ProcessRunner
import com.siamakerlab.vibecoder.server.ios.CommandRunner
import com.siamakerlab.vibecoder.server.ios.IosAgentCommandRunner
import com.siamakerlab.vibecoder.server.ios.IosWorkspaceSyncService
import com.siamakerlab.vibecoder.server.ios.ProcessCommandRunner
import com.siamakerlab.vibecoder.server.ios.shellSingleQuoted
import com.siamakerlab.vibecoder.server.tasks.TaskLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque
import java.util.Properties
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds

class IosBuildToolchain(
    private val agentConfigProvider: () -> IosAgentSection,
    private val runner: CommandRunner = ProcessCommandRunner,
    private val timeout: Duration = Duration.ofMinutes(30),
) : BuildToolchain {
    override suspend fun runBuild(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
        debugTask: String,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
        signing: SigningCredentials?,
    ): Int {
        val config = agentConfigProvider()
        val sourceRoot = source.normalize()
        val localRequest = IosXcodeCommandBuilder(sourceRoot).buildRequest(variant)
        prepareLocalBuildFiles(localRequest)
        val workRoot = prepareWorkspace(config, sourceRoot, logger)
        val request = IosXcodeCommandBuilder(workRoot).buildRequest(variant)
        prepareRemoteBuildFilesIfNeeded(config, request, logger)
        logger.info("iOS command: ${request.command.joinToString(" ")}")
        signing?.let { logger.warn("iOS signing currently uses Xcode project settings; Android keystore injection ignored") }
        val result = runCancellable(workRoot, request.command, config, logger, cancellation)
        val artifactSyncError = runCatching {
            pullRemoteArtifactsIfNeeded(config, request.artifactRoot, localRequest.artifactRoot, logger)
        }.exceptionOrNull()
        if (result.exitCode == 0 && artifactSyncError != null) throw artifactSyncError
        result.failureSummary?.let { summary ->
            val xcresultSummary = extractXcresultSummary(workRoot, request.resultBundlePath, config, logger)
            val detail = if (xcresultSummary != null) {
                "${summary.message}\n${xcresultSummary.toMessage()}"
            } else {
                summary.message
            }
            throw BuildToolchainFailureException(
                exitCode = result.exitCode,
                failureKind = summary.kind,
                failureMessage = detail,
                matchedLine = summary.matchedLine,
            )
        }
        return result.exitCode
    }

    override fun findArtifact(source: Path, moduleName: String, variant: BuildVariant): Path? {
        return XcodeArtifactFinder.find(source.normalize(), variant)
    }

    override fun findSupplementaryArtifacts(
        source: Path,
        moduleName: String,
        variant: BuildVariant,
    ): List<BuildSupplementaryArtifact> = XcodeArtifactFinder.findSupplementary(source.normalize(), variant)

    private suspend fun prepareWorkspace(config: IosAgentSection, source: Path, logger: TaskLogger): Path {
        val mode = config.mode.trim().lowercase()
        if (mode !in setOf("ssh", "remote")) return source

        val projectId = source.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "project"
        val plan = IosWorkspaceSyncService(config).plan(projectId, source, dryRun = false)
        logger.info("iOS workspace sync: ${plan.command.joinToString(" ")}")
        val result = withContext(Dispatchers.IO) { runner.run(plan.command, SYNC_TIMEOUT) }
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { logger.info(it) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { logger.warn(it) }
        if (!result.ok) throw IllegalStateException("iOS workspace sync failed: exit ${result.exitCode}")
        return Path.of(plan.remoteProjectRoot)
    }

    private fun prepareLocalBuildFiles(request: XcodeBuildRequest) {
        deleteRecursively(request.artifactRoot)
        Files.createDirectories(request.artifactRoot)
        if (request.variant == BuildVariant.IOS_EXPORT_IPA) {
            Files.createDirectories(request.exportOptionsPlist.parent)
            request.exportOptionsPlist.writeText(request.exportOptionsPlistContent)
        }
    }

    private suspend fun prepareRemoteBuildFilesIfNeeded(config: IosAgentSection, request: XcodeBuildRequest, logger: TaskLogger) {
        val mode = config.mode.trim().lowercase()
        if (mode !in setOf("ssh", "remote")) return

        val command = buildRemoteArtifactPrepareCommand(request)
        logger.info("iOS remote artifact prepare: ${command.joinToString(" ")}")
        val result = withContext(Dispatchers.IO) { runner.run(IosAgentCommandRunner(config).buildCommand(command), SYNC_TIMEOUT) }
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { logger.info(it) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { logger.warn(it) }
        if (!result.ok) throw IllegalStateException("iOS remote artifact prepare failed: exit ${result.exitCode}")
    }

    private fun buildRemoteArtifactPrepareCommand(request: XcodeBuildRequest): List<String> {
        val shell = buildString {
            append("rm -rf ")
            append(request.artifactRoot.toString().shellSingleQuoted())
            append(" && mkdir -p ")
            append(request.artifactRoot.toString().shellSingleQuoted())
            if (request.variant == BuildVariant.IOS_EXPORT_IPA) {
                append(" && cat > ")
                append(request.exportOptionsPlist.toString().shellSingleQuoted())
                append(" <<'VIBECODER_EXPORT_OPTIONS_PLIST'\n")
                append(request.exportOptionsPlistContent)
                if (!request.exportOptionsPlistContent.endsWith("\n")) append('\n')
                append("VIBECODER_EXPORT_OPTIONS_PLIST")
            }
        }
        return listOf("bash", "-lc", shell)
    }

    private suspend fun pullRemoteArtifactsIfNeeded(
        config: IosAgentSection,
        remoteArtifactRoot: Path,
        localArtifactRoot: Path,
        logger: TaskLogger,
    ) {
        val mode = config.mode.trim().lowercase()
        if (mode !in setOf("ssh", "remote")) return

        Files.createDirectories(localArtifactRoot)
        val command = buildArtifactPullCommand(config, remoteArtifactRoot, localArtifactRoot)
        logger.info("iOS artifact sync: ${command.joinToString(" ")}")
        val result = withContext(Dispatchers.IO) { runner.run(command, SYNC_TIMEOUT) }
        result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { logger.info(it) }
        result.stderr.lineSequence().filter { it.isNotBlank() }.forEach { logger.warn(it) }
        if (!result.ok) throw IllegalStateException("iOS artifact sync failed: exit ${result.exitCode}")
    }

    private fun buildArtifactPullCommand(config: IosAgentSection, remoteArtifactRoot: Path, localArtifactRoot: Path): List<String> {
        val host = config.host.trim()
        val user = config.user.trim()
        val port = config.port
        require(host.isNotEmpty()) { "ios agent host is required for artifact sync" }
        require(user.isNotEmpty()) { "ios agent user is required for artifact sync" }
        require(port in 1..65535) { "ios agent port must be between 1 and 65535" }
        return listOf(
            "rsync",
            "-az",
            "-e",
            "ssh -p $port -o BatchMode=yes -o StrictHostKeyChecking=accept-new",
            "$user@$host:${remoteArtifactRoot.toString().shellSingleQuoted()}/",
            localArtifactRoot.toString().trimEnd('/') + "/",
        )
    }

    private suspend fun runCancellable(
        workRoot: Path,
        remoteCommand: List<String>,
        config: IosAgentSection,
        logger: TaskLogger,
        cancellation: Flow<Unit>,
    ): IosBuildRunResult {
        val command = buildExecutableCommand(workRoot, remoteCommand, config)
        val recentLines = ArrayDeque<String>(MAX_FAILURE_SCAN_LINES)
        val result = ProcessRunner(workdir = Path.of("/")).run(
            command = command,
            timeout = timeout.toMillis().milliseconds,
            cancellation = cancellation,
        ) { level, line ->
            if (recentLines.size >= MAX_FAILURE_SCAN_LINES) recentLines.removeFirst()
            recentLines.addLast(line)
            logger.line(level, line)
        }
        when {
            result.cancelled -> logger.warn("iOS build cancelled")
            result.timedOut -> logger.warn("iOS build timed out after ${timeout.toMinutes()} minutes")
            result.exitCode != 0 -> Unit
        }
        val failureSummary = if (result.exitCode != 0) XcodeFailureClassifier.classify(recentLines.toList()) else null
        failureSummary?.let { summary ->
            logger.warn("iOS build failureKind=${summary.kind}: ${summary.message}")
            summary.matchedLine?.let { logger.warn("iOS build matched log: $it") }
        }
        return IosBuildRunResult(result.exitCode, failureSummary)
    }

    private fun buildExecutableCommand(workRoot: Path, remoteCommand: List<String>, config: IosAgentSection): List<String> {
        val shellCommand = "cd ${workRoot.toString().shellSingleQuoted()} && ${remoteCommand.shellJoin()}"
        val wrapped = listOf("bash", "-lc", shellCommand)
        val mode = config.mode.trim().lowercase()
        if (mode == "local") return wrapped
        return IosAgentCommandRunner(config).buildCommand(wrapped)
    }

    private suspend fun extractXcresultSummary(
        workRoot: Path,
        resultBundlePath: Path,
        config: IosAgentSection,
        logger: TaskLogger,
    ): XcresultFailureSummary? {
        val commands = listOf(
            listOf("xcrun", "xcresulttool", "get", "object", "--legacy", "--path", resultBundlePath.toString(), "--format", "json"),
            listOf("xcrun", "xcresulttool", "get", "--path", resultBundlePath.toString(), "--format", "json"),
        )
        for (raw in commands) {
            val result = withContext(Dispatchers.IO) {
                runner.run(buildExecutableCommand(workRoot, raw, config), XCRESULT_TIMEOUT)
            }
            if (!result.ok || result.stdout.isBlank()) {
                result.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                    logger.warn("xcresult summary command failed: $it")
                }
                continue
            }
            val summary = XcresultFailureSummaryExtractor.parse(result.stdout) ?: continue
            logger.warn("iOS xcresult summary: ${summary.lines.joinToString(" | ")}")
            return summary
        }
        return null
    }

    companion object {
        private val SYNC_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val XCRESULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        private const val MAX_FAILURE_SCAN_LINES = 200
    }
}

data class XcodeBuildSettings(
    val scheme: String = "",
    val debugConfiguration: String = "Debug",
    val releaseConfiguration: String = "Release",
    val bundleIdentifier: String = "",
    val teamId: String = "",
    val exportMethod: String = DEFAULT_EXPORT_METHOD,
    val signingStyle: String = DEFAULT_SIGNING_STYLE,
    val provisioningProfileSpecifier: String = "",
) {
    fun normalized(): XcodeBuildSettings = copy(
        scheme = scheme.trim(),
        debugConfiguration = debugConfiguration.trim().ifBlank { "Debug" },
        releaseConfiguration = releaseConfiguration.trim().ifBlank { "Release" },
        bundleIdentifier = bundleIdentifier.trim(),
        teamId = teamId.trim(),
        exportMethod = exportMethod.trim().lowercase().takeIf { it in EXPORT_METHODS } ?: DEFAULT_EXPORT_METHOD,
        signingStyle = signingStyle.trim().lowercase().takeIf { it in SIGNING_STYLES } ?: DEFAULT_SIGNING_STYLE,
        provisioningProfileSpecifier = provisioningProfileSpecifier.singleLineTrim(),
    )

    fun exportOptionsPlistContent(): String {
        val normalized = normalized()
        val lines = mutableListOf(
            "    <key>method</key>",
            "    <string>${plistEsc(normalized.exportMethod)}</string>",
            "    <key>signingStyle</key>",
            "    <string>${plistEsc(normalized.signingStyle)}</string>",
        )
        if (normalized.teamId.isNotBlank()) {
            lines += "    <key>teamID</key>"
            lines += "    <string>${plistEsc(normalized.teamId)}</string>"
        }
        if (normalized.provisioningProfileSpecifier.isNotBlank()) {
            lines += "    <key>provisioningProfiles</key>"
            lines += "    <dict>"
            lines += "        <key>${plistEsc(normalized.bundleIdentifier.ifBlank { normalized.scheme.ifBlank { "app" } })}</key>"
            lines += "        <string>${plistEsc(normalized.provisioningProfileSpecifier)}</string>"
            lines += "    </dict>"
        }
        return """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
${lines.joinToString("\n")}
</dict>
</plist>
""".trimStart()
    }

    companion object {
        private const val FILE_NAME = ".vibecoder-ios-build-settings.properties"
        const val DEFAULT_EXPORT_METHOD = "development"
        const val DEFAULT_SIGNING_STYLE = "automatic"
        val EXPORT_METHODS = setOf("development", "ad-hoc", "app-store-connect", "enterprise")
        val SIGNING_STYLES = setOf("automatic", "manual")

        fun load(source: Path): XcodeBuildSettings {
            val file = source.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return XcodeBuildSettings()
            val props = Properties()
            Files.newInputStream(file).use { props.load(it) }
            return XcodeBuildSettings(
                scheme = props.getProperty("scheme").orEmpty().trim(),
                debugConfiguration = props.getProperty("debugConfiguration").orEmpty().trim().ifBlank { "Debug" },
                releaseConfiguration = props.getProperty("releaseConfiguration").orEmpty().trim().ifBlank { "Release" },
                bundleIdentifier = props.getProperty("bundleIdentifier").orEmpty().trim(),
                teamId = props.getProperty("teamId").orEmpty().trim(),
                exportMethod = props.getProperty("exportMethod").orEmpty().trim().ifBlank { DEFAULT_EXPORT_METHOD },
                signingStyle = props.getProperty("signingStyle").orEmpty().trim().ifBlank { DEFAULT_SIGNING_STYLE },
                provisioningProfileSpecifier = props.getProperty("provisioningProfileSpecifier").orEmpty().trim(),
            ).normalized()
        }

        fun save(source: Path, settings: XcodeBuildSettings) {
            val normalized = settings.normalized()
            val props = Properties()
            props.setProperty("scheme", normalized.scheme)
            props.setProperty("debugConfiguration", normalized.debugConfiguration)
            props.setProperty("releaseConfiguration", normalized.releaseConfiguration)
            props.setProperty("bundleIdentifier", normalized.bundleIdentifier)
            props.setProperty("teamId", normalized.teamId)
            props.setProperty("exportMethod", normalized.exportMethod)
            props.setProperty("signingStyle", normalized.signingStyle)
            props.setProperty("provisioningProfileSpecifier", normalized.provisioningProfileSpecifier)
            Files.newOutputStream(source.resolve(FILE_NAME)).use { props.store(it, "Vibe Coder iOS build settings") }
        }

        private fun plistEsc(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        private fun String.singleLineTrim(): String = replace(Regex("[\\r\\n\\t]+"), " ").trim()
    }
}

data class XcodeProjectInfo(
    val kind: String,
    val containerName: String,
    val inferredScheme: String,
    val sharedSchemes: List<String>,
    val selectedScheme: String,
)

data class IosBuildRunResult(
    val exitCode: Int,
    val failureSummary: XcodeFailureSummary? = null,
)

data class XcodeFailureSummary(
    val kind: String,
    val message: String,
    val matchedLine: String? = null,
)

data class XcresultFailureSummary(
    val lines: List<String>,
) {
    fun toMessage(): String = lines.joinToString(
        separator = "\n",
        prefix = "xcresult summary:\n",
    ) { "- $it" }
}

object XcresultFailureSummaryExtractor {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_LINES = 5
    private const val MAX_LINE_LENGTH = 240

    fun parse(jsonText: String): XcresultFailureSummary? {
        val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return null
        val lines = linkedSetOf<String>()
        collect(root, ancestorKey = "", lines)
        if (lines.isEmpty()) return null
        return XcresultFailureSummary(lines.take(MAX_LINES))
    }

    private fun collect(element: JsonElement, ancestorKey: String, lines: MutableSet<String>) {
        if (lines.size >= MAX_LINES) return
        when (element) {
            is JsonObject -> {
                issueLine(element, ancestorKey)?.let { lines += it }
                for ((key, value) in element) {
                    collect(value, key, lines)
                    if (lines.size >= MAX_LINES) return
                }
            }
            is JsonArray -> {
                for (item in element) {
                    collect(item, ancestorKey, lines)
                    if (lines.size >= MAX_LINES) return
                }
            }
            else -> Unit
        }
    }

    private fun issueLine(obj: JsonObject, ancestorKey: String): String? {
        val message = stringValue(obj["message"]) ?: stringValue(obj["failureMessage"]) ?: return null
        val summaryContext = ancestorKey.contains("Summar", ignoreCase = true) ||
            obj.containsKey("issueType") ||
            obj.containsKey("testCaseName") ||
            obj.containsKey("documentLocationInCreatingWorkspace")
        if (!summaryContext) return null
        val parts = listOfNotNull(
            stringValue(obj["issueType"]),
            stringValue(obj["testCaseName"]) ?: stringValue(obj["testName"]) ?: stringValue(obj["name"]),
            message,
            stringValue(obj["documentLocationInCreatingWorkspace"]),
        ).map { it.squashWhitespace().take(MAX_LINE_LENGTH) }
        return parts.joinToString(" · ").take(MAX_LINE_LENGTH)
    }

    private fun stringValue(element: JsonElement?): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> stringValue(element["_value"])
            else -> null
        }
    }

    private fun String.squashWhitespace(): String = replace(Regex("\\s+"), " ").trim()
}

object XcodeFailureClassifier {
    private data class Rule(
        val kind: String,
        val message: String,
        val pattern: Regex,
    )

    private val rules = listOf(
        Rule(
            kind = "project_missing",
            message = "Xcode project path is missing or does not match the build command.",
            pattern = Regex("""xcodebuild: error: The project named""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "workspace_missing",
            message = "Xcode workspace path is missing or does not match the build command.",
            pattern = Regex("""The workspace named""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "scheme_missing",
            message = "Xcode scheme is missing or not shared. Select or share a valid scheme.",
            pattern = Regex("""Scheme .+ is not currently configured|scheme .+ not found""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "profile_missing",
            message = "Provisioning profile is missing. Check Apple Developer profile installation.",
            pattern = Regex("""No profiles for .+ were found|requires a provisioning profile|provisioning profile .+ couldn't be found""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "profile_expired",
            message = "Provisioning profile is expired. Renew or reinstall the matching profile on the Mac agent.",
            pattern = Regex("""provisioning profile .+ expired|profile .+ has expired|expired provisioning profile""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "profile_bundle_mismatch",
            message = "Provisioning profile does not match the app bundle identifier.",
            pattern = Regex("""provisioning profile .+ doesn't support the .+ identifier|doesn't match the entitlements file|application-identifier entitlement""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "profile_team_mismatch",
            message = "Provisioning profile team does not match the selected development team.",
            pattern = Regex("""does not match team|No profiles for .+ were found: Xcode couldn't find any iOS App Development provisioning profiles matching""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "certificate_missing",
            message = "Signing certificate is missing from the Mac keychain.",
            pattern = Regex("""Signing certificate .+ not found|No signing certificate|unable to build chain to self-signed root|certificate .+ is not trusted""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "keychain_locked",
            message = "The signing keychain is locked or inaccessible on the Mac agent.",
            pattern = Regex("""User interaction is not allowed|The specified item could not be found in the keychain|errSecInteractionNotAllowed""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "swift_compile_failed",
            message = "Swift compilation failed. Review the Swift error above this summary.",
            pattern = Regex("""Command CompileSwift failed|SwiftCompile failed|error: .*\.swift:""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "simulator_unavailable",
            message = "Requested iOS Simulator destination is unavailable. Check simulator runtimes and device types.",
            pattern = Regex("""Unable to find a destination|destination specifier|iOS Simulator.*unavailable""", RegexOption.IGNORE_CASE),
        ),
        Rule(
            kind = "test_failed",
            message = "XCTest failed. Inspect the .xcresult bundle when available.",
            pattern = Regex("""Testing failed|Test Suite .+ failed|Failing tests""", RegexOption.IGNORE_CASE),
        ),
    )

    fun classify(lines: List<String>): XcodeFailureSummary? {
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val rule = rules.firstOrNull { it.pattern.containsMatchIn(trimmed) } ?: continue
            return XcodeFailureSummary(rule.kind, rule.message, trimmed)
        }
        return null
    }
}

data class XcodeBuildRequest(
    val variant: BuildVariant,
    val command: List<String>,
    val artifactRoot: Path,
    val derivedDataPath: Path,
    val resultBundlePath: Path,
    val archivePath: Path,
    val exportPath: Path,
    val exportOptionsPlist: Path,
    val exportOptionsPlistContent: String,
)

class IosXcodeCommandBuilder(
    private val source: Path,
    private val settings: XcodeBuildSettings = XcodeBuildSettings.load(source),
) {
    fun build(variant: BuildVariant): List<String> = buildRequest(variant).command

    fun buildRequest(variant: BuildVariant): XcodeBuildRequest {
        val locator = locateProject(settings)
        val artifactRoot = source.resolve(BUILD_ARTIFACT_ROOT).resolve(variant.wire)
        val request = XcodeBuildRequest(
            variant = variant,
            command = emptyList(),
            artifactRoot = artifactRoot,
            derivedDataPath = artifactRoot.resolve("DerivedData"),
            resultBundlePath = artifactRoot.resolve("${variant.wire}.xcresult"),
            archivePath = artifactRoot.resolve("${locator.scheme}.xcarchive"),
            exportPath = artifactRoot.resolve("Export"),
            exportOptionsPlist = artifactRoot.resolve("exportOptions.plist"),
            exportOptionsPlistContent = settings.copy(scheme = locator.scheme).exportOptionsPlistContent(),
        )
        val base = mutableListOf("xcodebuild")
        when (locator.kind) {
            XcodeProjectKind.WORKSPACE -> {
                base += "-workspace"
                base += locator.path.fileName.toString()
            }
            XcodeProjectKind.PROJECT -> {
                base += "-project"
                base += locator.path.fileName.toString()
            }
        }
        base += "-scheme"
        base += locator.scheme

        val command = when (variant) {
            BuildVariant.IOS_BUILD_DEBUG -> base + listOf(
                "-configuration",
                settings.debugConfiguration.ifBlank { "Debug" },
                "-destination",
                GENERIC_SIMULATOR_DESTINATION,
                "-derivedDataPath",
                request.derivedDataPath.toString(),
                "-resultBundlePath",
                request.resultBundlePath.toString(),
                "build",
            )
            BuildVariant.IOS_TEST -> base + listOf(
                "-configuration",
                settings.debugConfiguration.ifBlank { "Debug" },
                "-destination",
                GENERIC_SIMULATOR_DESTINATION,
                "-derivedDataPath",
                request.derivedDataPath.toString(),
                "-resultBundlePath",
                request.resultBundlePath.toString(),
                "test",
            )
            BuildVariant.IOS_ARCHIVE -> base + listOf(
                "-configuration",
                settings.releaseConfiguration.ifBlank { "Release" },
                "-destination",
                "generic/platform=iOS",
                "-derivedDataPath",
                request.derivedDataPath.toString(),
                "-archivePath",
                request.archivePath.toString(),
                "archive",
            )
            BuildVariant.IOS_EXPORT_IPA -> exportIpaCommand(base, request)
            else -> throw IllegalArgumentException("unsupported iOS build variant: ${variant.wire}")
        }
        return request.copy(command = command)
    }

    private fun exportIpaCommand(base: List<String>, request: XcodeBuildRequest): List<String> {
        val archive = base + listOf(
            "-configuration",
            settings.releaseConfiguration.ifBlank { "Release" },
            "-destination",
            "generic/platform=iOS",
            "-derivedDataPath",
            request.derivedDataPath.toString(),
            "-archivePath",
            request.archivePath.toString(),
            "archive",
        )
        val export = listOf(
            "xcodebuild",
            "-exportArchive",
            "-archivePath",
            request.archivePath.toString(),
            "-exportPath",
            request.exportPath.toString(),
            "-exportOptionsPlist",
            request.exportOptionsPlist.toString(),
        )
        return listOf("bash", "-lc", "${archive.shellJoin()} && ${export.shellJoin()}")
    }

    private fun locateProject(settings: XcodeBuildSettings): XcodeProjectLocator {
        Files.newDirectoryStream(source) { path ->
            Files.isDirectory(path) && path.fileName.toString().endsWith(".xcworkspace")
        }.use { stream ->
            stream.firstOrNull()?.let { return XcodeProjectLocator(XcodeProjectKind.WORKSPACE, it, schemeFrom(it, settings)) }
        }
        Files.newDirectoryStream(source) { path ->
            Files.isDirectory(path) && path.fileName.toString().endsWith(".xcodeproj")
        }.use { stream ->
            stream.firstOrNull()?.let { return XcodeProjectLocator(XcodeProjectKind.PROJECT, it, schemeFrom(it, settings)) }
        }
        throw IllegalStateException("No .xcworkspace or .xcodeproj found in $source")
    }

    private fun schemeFrom(path: Path, settings: XcodeBuildSettings): String {
        val inferred = path.fileName.toString()
            .removeSuffix(".xcworkspace")
            .removeSuffix(".xcodeproj")
            .ifBlank { throw IllegalStateException("Cannot infer Xcode scheme from $path") }
        val schemes = discoverSharedSchemes(path).distinct().sorted()
        val configured = settings.scheme.trim()
        if (configured.isNotBlank()) {
            if (schemes.isNotEmpty() && configured !in schemes) {
                throw IllegalStateException("Configured Xcode scheme '$configured' is not shared in ${path.fileName}. Available schemes: ${schemes.joinToString(", ")}")
            }
            return configured
        }
        if (schemes.isEmpty()) return inferred
        if (schemes.size == 1) return schemes.single()
        schemes.firstOrNull { it == inferred }?.let { return it }
        schemes.singleOrNull { !it.endsWith("Tests") && !it.endsWith("UITests") }?.let { return it }
        throw IllegalStateException("Multiple Xcode schemes found: ${schemes.joinToString(", ")}. Select one in iPhone build settings.")
    }

    private fun discoverSharedSchemes(container: Path): List<String> {
        val dirs = mutableListOf(container.resolve("xcshareddata/xcschemes"))
        if (container.fileName.toString().endsWith(".xcworkspace")) {
            Files.newDirectoryStream(source) { path ->
                Files.isDirectory(path) && path.fileName.toString().endsWith(".xcodeproj")
            }.use { stream ->
                stream.forEach { dirs.add(it.resolve("xcshareddata/xcschemes")) }
            }
        }
        return dirs
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.newDirectoryStream(dir) { path ->
                    Files.isRegularFile(path) && path.fileName.toString().endsWith(".xcscheme")
                }.use { stream ->
                    stream.mapNotNull { it.fileName.toString().removeSuffix(".xcscheme").ifBlank { null } }.toList()
                }
            }
    }

    companion object {
        private const val GENERIC_SIMULATOR_DESTINATION = "generic/platform=iOS Simulator"
        private const val BUILD_ARTIFACT_ROOT = ".vibecoder-ios-build"
    }
}

object XcodeProjectInspector {
    fun inspect(source: Path): XcodeProjectInfo {
        val root = source.normalize()
        val settings = XcodeBuildSettings.load(root)
        val locator = locate(root)
        val schemes = discoverSharedSchemes(root, locator.path).distinct().sorted()
        val inferred = locator.path.fileName.toString()
            .removeSuffix(".xcworkspace")
            .removeSuffix(".xcodeproj")
        val selected = settings.scheme.ifBlank {
            when {
                schemes.size == 1 -> schemes.single()
                schemes.contains(inferred) -> inferred
                else -> inferred
            }
        }
        return XcodeProjectInfo(
            kind = locator.kind.name.lowercase(),
            containerName = locator.path.fileName.toString(),
            inferredScheme = inferred,
            sharedSchemes = schemes,
            selectedScheme = selected,
        )
    }

    private fun locate(source: Path): XcodeProjectLocator {
        Files.newDirectoryStream(source) { path ->
            Files.isDirectory(path) && path.fileName.toString().endsWith(".xcworkspace")
        }.use { stream ->
            stream.firstOrNull()?.let { return XcodeProjectLocator(XcodeProjectKind.WORKSPACE, it, "") }
        }
        Files.newDirectoryStream(source) { path ->
            Files.isDirectory(path) && path.fileName.toString().endsWith(".xcodeproj")
        }.use { stream ->
            stream.firstOrNull()?.let { return XcodeProjectLocator(XcodeProjectKind.PROJECT, it, "") }
        }
        throw IllegalStateException("No .xcworkspace or .xcodeproj found in $source")
    }

    private fun discoverSharedSchemes(source: Path, container: Path): List<String> {
        val dirs = mutableListOf(container.resolve("xcshareddata/xcschemes"))
        if (container.fileName.toString().endsWith(".xcworkspace")) {
            Files.newDirectoryStream(source) { path ->
                Files.isDirectory(path) && path.fileName.toString().endsWith(".xcodeproj")
            }.use { stream ->
                stream.forEach { dirs.add(it.resolve("xcshareddata/xcschemes")) }
            }
        }
        return dirs
            .filter { Files.isDirectory(it) }
            .flatMap { dir ->
                Files.newDirectoryStream(dir) { path ->
                    Files.isRegularFile(path) && path.fileName.toString().endsWith(".xcscheme")
                }.use { stream ->
                    stream.mapNotNull { it.fileName.toString().removeSuffix(".xcscheme").ifBlank { null } }.toList()
                }
            }
    }
}

object XcodeArtifactFinder {
    fun find(source: Path, variant: BuildVariant): Path? {
        val root = source.resolve(".vibecoder-ios-build").resolve(variant.wire)
        if (!Files.exists(root)) return null
        return when (variant) {
            BuildVariant.IOS_EXPORT_IPA -> newestRegularFile(root) { it.fileName.toString().endsWith(".ipa") }
            else -> null
        }
    }

    fun findSimulatorApp(source: Path): Path? {
        val roots = listOf(
            source.resolve(".vibecoder-ios-build").resolve(BuildVariant.IOS_BUILD_DEBUG.wire),
            source.resolve("build/ios"),
        ).filter { Files.exists(it) }
        return roots.asSequence()
            .mapNotNull { root ->
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isDirectory(it) && it.fileName.toString().endsWith(".app") }
                        .filter { it.toString().contains("iphonesimulator", ignoreCase = true) }
                        .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                        .orElse(null)
                }
            }
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }

    fun findSupplementary(source: Path, variant: BuildVariant): List<BuildSupplementaryArtifact> {
        val root = source.resolve(".vibecoder-ios-build").resolve(variant.wire)
        if (!Files.exists(root)) return emptyList()
        val artifacts = mutableListOf<BuildSupplementaryArtifact>()
        newestDirectory(root) { it.fileName.toString().endsWith(".xcresult") }?.let { resultBundle ->
            artifacts += BuildSupplementaryArtifact(resultBundle, type = "ios-xcresult", ext = "xcresult.zip")
            if (variant == BuildVariant.IOS_TEST) {
                screenshotAttachments(resultBundle).forEachIndexed { index, screenshot ->
                    artifacts += BuildSupplementaryArtifact(
                        path = screenshot,
                        type = "ios-screenshot-${(index + 1).toString().padStart(2, '0')}",
                        ext = screenshot.fileName.toString().substringAfterLast('.', "png"),
                    )
                }
            }
        }
        newestDirectory(root) { it.fileName.toString().endsWith(".xcarchive") }?.let {
            artifacts += BuildSupplementaryArtifact(it, type = "ios-xcarchive", ext = "xcarchive.zip")
        }
        newestDirectory(root) { it.fileName.toString().endsWith(".dSYM") }?.let {
            artifacts += BuildSupplementaryArtifact(it, type = "ios-dsym", ext = "dSYM.zip")
        }
        return artifacts
    }

    private fun newestRegularFile(root: Path, predicate: (Path) -> Boolean): Path? =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && predicate(it) }
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }

    private fun newestDirectory(root: Path, predicate: (Path) -> Boolean): Path? =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) && predicate(it) }
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElse(null)
        }

    private fun screenshotAttachments(resultBundle: Path): List<Path> =
        Files.walk(resultBundle).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && isScreenshotAttachment(it) }
                .sorted(Comparator.comparingLong<Path> { Files.getLastModifiedTime(it).toMillis() }.reversed())
                .limit(MAX_XCRESULT_SCREENSHOTS.toLong())
                .toList()
        }

    private fun isScreenshotAttachment(path: Path): Boolean {
        val name = path.fileName.toString()
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SCREENSHOT_EXTENSIONS
    }

    private const val MAX_XCRESULT_SCREENSHOTS = 5
    private val SCREENSHOT_EXTENSIONS = setOf("png", "jpg", "jpeg", "heic")
}

private data class XcodeProjectLocator(
    val kind: XcodeProjectKind,
    val path: Path,
    val scheme: String,
)

private enum class XcodeProjectKind { WORKSPACE, PROJECT }

private fun List<String>.shellJoin(): String = joinToString(" ") { it.shellSingleQuoted() }

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
