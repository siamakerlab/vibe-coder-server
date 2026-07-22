package com.siamakerlab.vibecoder.server.build

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.Test
import java.nio.file.Files

class IosBuildToolchainTest {
    @Test
    fun `xcode command uses workspace before project and inferred scheme`() {
        val root = Files.createTempDirectory("ios-command")
        Files.createDirectories(root.resolve("Runner.xcodeproj"))
        Files.createDirectories(root.resolve("App.xcworkspace"))

        IosXcodeCommandBuilder(root).build(BuildVariant.IOS_BUILD_DEBUG) shouldContainExactly listOf(
            "xcodebuild",
            "-workspace",
            "App.xcworkspace",
            "-scheme",
            "App",
            "-configuration",
            "Debug",
            "-destination",
            "generic/platform=iOS Simulator",
            "-derivedDataPath",
            root.resolve(".vibecoder-ios-build/ios-debug/DerivedData").toString(),
            "-resultBundlePath",
            root.resolve(".vibecoder-ios-build/ios-debug/ios-debug.xcresult").toString(),
            "build",
        )
    }

    @Test
    fun `xcode command builds simulator tests`() {
        val root = Files.createTempDirectory("ios-test-command")
        Files.createDirectories(root.resolve("RegexLab.xcodeproj"))

        IosXcodeCommandBuilder(root).build(BuildVariant.IOS_TEST) shouldContainExactly listOf(
            "xcodebuild",
            "-project",
            "RegexLab.xcodeproj",
            "-scheme",
            "RegexLab",
            "-configuration",
            "Debug",
            "-destination",
            "generic/platform=iOS Simulator",
            "-derivedDataPath",
            root.resolve(".vibecoder-ios-build/ios-test/DerivedData").toString(),
            "-resultBundlePath",
            root.resolve(".vibecoder-ios-build/ios-test/ios-test.xcresult").toString(),
            "test",
        )
    }

    @Test
    fun `xcode command uses single shared scheme from project inside workspace`() {
        val root = Files.createTempDirectory("ios-shared-scheme")
        Files.createDirectories(root.resolve("Pods.xcworkspace"))
        val schemeDir = root.resolve("DemoApp.xcodeproj/xcshareddata/xcschemes")
        Files.createDirectories(schemeDir)
        Files.writeString(schemeDir.resolve("DemoApp.xcscheme"), "<Scheme></Scheme>")

        val command = IosXcodeCommandBuilder(root).build(BuildVariant.IOS_BUILD_DEBUG)

        command[command.indexOf("-scheme") + 1] shouldBe "DemoApp"
    }

    @Test
    fun `xcode command uses saved scheme and configurations`() {
        val root = Files.createTempDirectory("ios-saved-settings")
        val schemeDir = root.resolve("App.xcodeproj/xcshareddata/xcschemes")
        Files.createDirectories(schemeDir)
        Files.writeString(schemeDir.resolve("One.xcscheme"), "<Scheme></Scheme>")
        Files.writeString(schemeDir.resolve("Two.xcscheme"), "<Scheme></Scheme>")
        XcodeBuildSettings.save(
            root,
            XcodeBuildSettings(
                scheme = "Two",
                debugConfiguration = "StagingDebug",
                releaseConfiguration = "AppStore",
                bundleIdentifier = "kr.codr.demo",
                teamId = "ABCDE12345",
                exportMethod = "app-store-connect",
                signingStyle = "manual",
                provisioningProfileSpecifier = "Demo AppStore Profile",
            ),
        )

        val debug = IosXcodeCommandBuilder(root).build(BuildVariant.IOS_BUILD_DEBUG)
        val archive = IosXcodeCommandBuilder(root).build(BuildVariant.IOS_ARCHIVE)
        val export = IosXcodeCommandBuilder(root).buildRequest(BuildVariant.IOS_EXPORT_IPA)

        debug[debug.indexOf("-scheme") + 1] shouldBe "Two"
        debug[debug.indexOf("-configuration") + 1] shouldBe "StagingDebug"
        archive[archive.indexOf("-configuration") + 1] shouldBe "AppStore"
        export.exportOptionsPlistContent shouldContain "<string>app-store-connect</string>"
        export.exportOptionsPlistContent shouldContain "<key>teamID</key>"
        export.exportOptionsPlistContent shouldContain "<string>ABCDE12345</string>"
        export.exportOptionsPlistContent shouldContain "<string>manual</string>"
        export.exportOptionsPlistContent shouldContain "<key>kr.codr.demo</key>"
        export.exportOptionsPlistContent shouldContain "<string>Demo AppStore Profile</string>"
    }

    @Test
    fun `xcode command fails clearly when multiple schemes cannot be inferred`() {
        val root = Files.createTempDirectory("ios-multiple-schemes")
        val schemeDir = root.resolve("App.xcodeproj/xcshareddata/xcschemes")
        Files.createDirectories(schemeDir)
        Files.writeString(schemeDir.resolve("One.xcscheme"), "<Scheme></Scheme>")
        Files.writeString(schemeDir.resolve("Two.xcscheme"), "<Scheme></Scheme>")

        val error = shouldThrow<IllegalStateException> {
            IosXcodeCommandBuilder(root).build(BuildVariant.IOS_BUILD_DEBUG)
        }

        error.message shouldContain "Multiple Xcode schemes found"
        error.message shouldContain "One"
        error.message shouldContain "Two"
    }

    @Test
    fun `export ipa command archives before exportArchive with stable artifact paths`() {
        val root = Files.createTempDirectory("ios-export-command")
        Files.createDirectories(root.resolve("Ship.xcodeproj"))

        val request = IosXcodeCommandBuilder(root).buildRequest(BuildVariant.IOS_EXPORT_IPA)

        request.artifactRoot shouldBe root.resolve(".vibecoder-ios-build/ios-export-ipa")
        request.command[0] shouldBe "bash"
        request.command[1] shouldBe "-lc"
        request.command[2] shouldContain "archive"
        request.command[2] shouldContain "-exportArchive"
        request.command[2] shouldContain root.resolve(".vibecoder-ios-build/ios-export-ipa/Ship.xcarchive").toString()
        request.command[2] shouldContain root.resolve(".vibecoder-ios-build/ios-export-ipa/Export").toString()
        request.command[2] shouldContain root.resolve(".vibecoder-ios-build/ios-export-ipa/exportOptions.plist").toString()
    }

    @Test
    fun `artifact finder returns newest exported ipa from isolated build dir`() {
        val root = Files.createTempDirectory("ios-artifact")
        val exportDir = root.resolve(".vibecoder-ios-build/ios-export-ipa/Export")
        Files.createDirectories(exportDir)
        val oldIpa = exportDir.resolve("Old.ipa")
        val newIpa = exportDir.resolve("New.ipa")
        Files.writeString(oldIpa, "old")
        Thread.sleep(5)
        Files.writeString(newIpa, "new")

        XcodeArtifactFinder.find(root, BuildVariant.IOS_EXPORT_IPA) shouldBe newIpa
        XcodeArtifactFinder.find(root, BuildVariant.IOS_ARCHIVE) shouldBe null
    }

    @Test
    fun `artifact finder returns latest simulator app from debug derived data`() {
        val root = Files.createTempDirectory("ios-sim-app")
        val appDir = root.resolve(".vibecoder-ios-build/ios-debug/DerivedData/Build/Products/Debug-iphonesimulator/Demo.app")
        Files.createDirectories(appDir)

        XcodeArtifactFinder.findSimulatorApp(root) shouldBe appDir
    }

    @Test
    fun `artifact finder returns flutter iphonesimulator app`() {
        val root = Files.createTempDirectory("flutter-ios-sim-app")
        val appDir = root.resolve("build/ios/iphonesimulator/Runner.app")
        Files.createDirectories(appDir)

        XcodeArtifactFinder.findSimulatorApp(root) shouldBe appDir
    }

    @Test
    fun `artifact finder returns xcresult xcarchive and dsym as supplementary artifacts`() {
        val root = Files.createTempDirectory("ios-supplementary-artifacts")
        val buildRoot = root.resolve(".vibecoder-ios-build/ios-archive")
        val resultBundle = buildRoot.resolve("ios-archive.xcresult")
        val archiveBundle = buildRoot.resolve("Demo.xcarchive")
        val dsymBundle = archiveBundle.resolve("dSYMs/Demo.app.dSYM")
        Files.createDirectories(resultBundle)
        Files.createDirectories(dsymBundle)
        Files.writeString(resultBundle.resolve("Info.plist"), "result")
        Files.writeString(archiveBundle.resolve("Info.plist"), "archive")
        Files.writeString(dsymBundle.resolve("Contents.plist"), "dsym")

        val artifacts = XcodeArtifactFinder.findSupplementary(root, BuildVariant.IOS_ARCHIVE)

        artifacts.map { it.type } shouldContainExactly listOf("ios-xcresult", "ios-xcarchive", "ios-dsym")
        artifacts.map { it.path } shouldContainExactly listOf(resultBundle, archiveBundle, dsymBundle)
    }

    @Test
    fun `artifact finder returns ios test xcresult and bounded screenshot attachments`() {
        val root = Files.createTempDirectory("ios-test-supplementary-artifacts")
        val resultBundle = root.resolve(".vibecoder-ios-build/ios-test/ios-test.xcresult")
        Files.createDirectories(resultBundle.resolve("Attachments"))
        val older = resultBundle.resolve("Attachments/older.png")
        val newer = resultBundle.resolve("Attachments/newer.jpg")
        Files.writeString(resultBundle.resolve("Info.plist"), "result")
        Files.writeString(older, "old-shot")
        Files.writeString(newer, "new-shot")
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000))

        val artifacts = XcodeArtifactFinder.findSupplementary(root, BuildVariant.IOS_TEST)

        artifacts.map { it.type } shouldContainExactly listOf(
            "ios-xcresult",
            "ios-screenshot-01",
            "ios-screenshot-02",
        )
        artifacts.map { it.path } shouldContainExactly listOf(resultBundle, newer, older)
        artifacts.map { it.ext } shouldContainExactly listOf("xcresult.zip", "jpg", "png")
    }

    @Test
    fun `xcode failure classifier maps actionable log patterns`() {
        XcodeFailureClassifier.classify(
            listOf("xcodebuild: error: Scheme Demo is not currently configured for the test action")
        )?.kind shouldBe "scheme_missing"

        XcodeFailureClassifier.classify(
            listOf("error: No profiles for 'kr.codr.demo' were found")
        )?.kind shouldBe "profile_missing"

        XcodeFailureClassifier.classify(
            listOf("error: Provisioning profile \"Demo\" has expired")
        )?.kind shouldBe "profile_expired"

        XcodeFailureClassifier.classify(
            listOf("Provisioning profile \"Demo\" doesn't support the kr.codr.demo identifier")
        )?.kind shouldBe "profile_bundle_mismatch"

        XcodeFailureClassifier.classify(
            listOf("error: User interaction is not allowed.")
        )?.kind shouldBe "keychain_locked"

        XcodeFailureClassifier.classify(
            listOf("Command CompileSwift failed with a nonzero exit code")
        )?.kind shouldBe "swift_compile_failed"

        XcodeFailureClassifier.classify(
            listOf("xcodebuild: error: Unable to find a destination matching the provided destination specifier")
        )?.kind shouldBe "simulator_unavailable"

        XcodeFailureClassifier.classify(
            listOf("Testing failed:")
        )?.kind shouldBe "test_failed"
    }

    @Test
    fun `classified build failure exception keeps actionable summary and matched line`() {
        val summary = XcodeFailureClassifier.classify(
            listOf("error: No profiles for 'kr.codr.demo' were found")
        )!!

        val error = BuildToolchainFailureException(
            exitCode = 65,
            failureKind = summary.kind,
            failureMessage = summary.message,
            matchedLine = summary.matchedLine,
        )

        error.message shouldContain "Build failed (profile_missing)"
        error.message shouldContain "Provisioning profile is missing"
        error.message shouldContain "matched: error: No profiles for 'kr.codr.demo' were found"
    }

    @Test
    fun `xcresult failure summary extractor reads wrapped issue summaries`() {
        val summary = XcresultFailureSummaryExtractor.parse(
            """
            {
              "issues": {
                "testFailureSummaries": {
                  "_values": [
                    {
                      "testCaseName": {"_value": "DemoTests.testLogin()"},
                      "message": {"_value": "XCTAssertEqual failed: expected true"},
                      "documentLocationInCreatingWorkspace": {"_value": "file:///DemoTests.swift#EndingLineNumber=42"}
                    }
                  ]
                },
                "errorSummaries": {
                  "_values": [
                    {
                      "issueType": {"_value": "Swift Compiler Error"},
                      "message": {"_value": "Cannot find 'foo' in scope"}
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )!!

        summary.toMessage() shouldContain "DemoTests.testLogin()"
        summary.toMessage() shouldContain "XCTAssertEqual failed"
        summary.toMessage() shouldContain "Swift Compiler Error"
        summary.toMessage() shouldContain "Cannot find 'foo' in scope"
    }

    @Test
    fun `ios build variants use stable wire names`() {
        BuildVariant.IOS_BUILD_DEBUG.wire shouldBe "ios-debug"
        BuildVariant.IOS_TEST.wire shouldBe "ios-test"
        BuildVariant.IOS_ARCHIVE.wire shouldBe "ios-archive"
        BuildVariant.IOS_EXPORT_IPA.wire shouldBe "ios-export-ipa"
        BuildVariant.IOS_BUILD_DEBUG.artifactRequired shouldBe false
        BuildVariant.IOS_EXPORT_IPA.artifactRequired shouldBe true
    }
}
