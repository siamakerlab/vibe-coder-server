package com.siamakerlab.vibecoder.server.projects

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.notExists

private val log = KotlinLogging.logger {}

/**
 * v0.12.2 — Claude 관련 프로젝트 파일 (CLAUDE.md, .claude/settings.json) 의
 * 멱등 backfill 헬퍼.
 *
 * 신규 프로젝트는 [ProjectService.register] 가 생성 시점에 만들지만, v0.7.0
 * 이전에 만들어졌거나 사용자가 수동으로 만든 프로젝트는 이 파일들이 없을 수
 * 있다. 그러면 Claude Code 가 `permissions.defaultMode: bypassPermissions`
 * 를 적용 못 받아 `ask` 모드로 동작 → vibe-coder 비인터랙티브 환경에서
 * 권한 prompt 가 응답 안 됨 → 모든 write/edit 거부.
 *
 * [ClaudeSessionManager.spawnSession] 이 매 spawn 직전에 호출. 기존 파일은
 * 절대 덮어쓰지 않음 — 사용자 수정 보존.
 */
object ProjectScaffolder {

    /**
     * 프로젝트 루트에 CLAUDE.md / .claude/settings.json 이 없으면 default 생성.
     * 이미 있으면 noop — 사용자가 customize 한 내용 보존.
     *
     * @return 새로 생성된 파일 수 (디버그용).
     */
    fun ensureClaudeFiles(projectRoot: Path): Int {
        var created = 0
        try {
            if (projectRoot.notExists()) return 0   // race — register 가 아직 못 만든 케이스

            val claudeMd = projectRoot.resolve("CLAUDE.md")
            if (claudeMd.notExists()) {
                Files.writeString(claudeMd, ClaudeMdTemplate.CONTENT)
                created++
                log.info { "backfilled CLAUDE.md → $claudeMd" }
            }

            if (ensureAgentsLink(projectRoot)) {
                created++
            }

            val claudeDir = projectRoot.resolve(".claude")
            if (claudeDir.notExists()) {
                claudeDir.createDirectories()
            }
            val settingsJson = claudeDir.resolve("settings.json")
            if (settingsJson.notExists()) {
                Files.writeString(settingsJson, ClaudeSettingsTemplate.CONTENT)
                created++
                log.info { "backfilled .claude/settings.json → $settingsJson" }
            }
        } catch (e: Throwable) {
            // backfill 실패가 prompt 차단 사유는 아니어야 함 — log 만.
            log.warn(e) { "Claude file backfill failed for $projectRoot: ${e.message}" }
        }
        return created
    }

    /**
     * 프로젝트 루트의 `AGENTS.md` 를 같은 폴더의 `CLAUDE.md` 를 가리키는
     * 심볼릭 링크로 유지한다. 사용자가 직접 만든 일반 파일은 덮어쓰지 않는다.
     */
    fun ensureAgentsLink(projectRoot: Path): Boolean {
        return try {
            val claudeMd = projectRoot.resolve("CLAUDE.md")
            if (claudeMd.notExists()) return false

            val agentsMd = projectRoot.resolve("AGENTS.md")
            if (Files.exists(agentsMd)) return false
            if (Files.isSymbolicLink(agentsMd)) {
                Files.deleteIfExists(agentsMd)
            }
            Files.createSymbolicLink(agentsMd, Path.of("CLAUDE.md"))
            log.info { "created AGENTS.md symlink → $agentsMd -> CLAUDE.md" }
            true
        } catch (e: FileAlreadyExistsException) {
            false
        } catch (e: Throwable) {
            log.warn(e) { "AGENTS.md symlink backfill failed for $projectRoot: ${e.message}" }
            false
        }
    }

    fun scaffoldIPhoneStarter(projectRoot: Path, appName: String, bundleId: String): Int {
        if (projectRoot.notExists()) projectRoot.createDirectories()
        var created = 0
        val appDirName = sanitizeSwiftIdentifier(appName).ifBlank { "App" }
        val sourceDir = projectRoot.resolve(appDirName)
        if (sourceDir.notExists()) {
            sourceDir.createDirectories()
        }
        val testsDir = projectRoot.resolve("${appDirName}Tests")
        if (testsDir.notExists()) {
            testsDir.createDirectories()
        }
        val xcodeProjectDir = projectRoot.resolve("$appDirName.xcodeproj")
        if (xcodeProjectDir.notExists()) {
            xcodeProjectDir.createDirectories()
        }
        created += writeIfMissing(
            xcodeProjectDir.resolve("project.pbxproj"),
            minimalXcodeProject(appDirName, bundleId),
        )
        created += writeIfMissing(
            sourceDir.resolve("${appDirName}App.swift"),
            """
            import SwiftUI

            @main
            struct ${appDirName}App: App {
                var body: some Scene {
                    WindowGroup {
                        ContentView()
                    }
                }
            }
            """.trimIndent() + "\n",
        )
        created += writeIfMissing(
            sourceDir.resolve("ContentView.swift"),
            """
            import SwiftUI

            struct ContentView: View {
                var body: some View {
                    NavigationStack {
                        VStack(spacing: 16) {
                            Image(systemName: "iphone")
                                .font(.system(size: 44, weight: .semibold))
                                .accessibilityHidden(true)
                            Text("${escapeSwiftString(appName)}")
                                .font(.title2.weight(.semibold))
                            Text("Ready for iPhone development")
                                .font(.body)
                                .foregroundStyle(.secondary)
                        }
                        .padding()
                        .navigationTitle("${escapeSwiftString(appName)}")
                    }
                }
            }

            #Preview {
                ContentView()
            }
            """.trimIndent() + "\n",
        )
        created += writeIfMissing(
            projectRoot.resolve("Info.plist"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>$bundleId</string>
                <key>CFBundleName</key>
                <string>${escapeXml(appName)}</string>
                <key>UILaunchScreen</key>
                <dict/>
            </dict>
            </plist>
            """.trimIndent() + "\n",
        )
        created += writeIfMissing(
            testsDir.resolve("${appDirName}Tests.swift"),
            """
            import XCTest
            @testable import $appDirName

            final class ${appDirName}Tests: XCTestCase {
                func testStarterConfiguration() {
                    XCTAssertEqual("$bundleId", "$bundleId")
                }
            }
            """.trimIndent() + "\n",
        )
        created += writeIfMissing(
            projectRoot.resolve("README.md"),
            """
            # $appName

            iPhone starter project generated by Vibe Coder.

            - Bundle ID: `$bundleId`
            - UI entry point: `$appDirName/ContentView.swift`
            - App entry point: `$appDirName/${appDirName}App.swift`

            Xcode project/workspace generation and simulator execution require a MacBook local install
            or a configured macOS agent.
            """.trimIndent() + "\n",
        )
        return created
    }

    private fun writeIfMissing(path: Path, content: String): Int {
        if (path.exists()) return 0
        Files.writeString(path, content)
        log.info { "created starter file → $path" }
        return 1
    }

    private fun sanitizeSwiftIdentifier(raw: String): String {
        val cleaned = raw.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        val safe = cleaned.ifBlank { "App" }
        return if (safe.first().isDigit()) "App$safe" else safe
    }

    private fun escapeSwiftString(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun escapeXml(raw: String): String =
        raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")

    private fun minimalXcodeProject(appDirName: String, bundleId: String): String =
        """
        // !$*UTF8*$!
        {
            archiveVersion = 1;
            classes = {};
            objectVersion = 56;
            objects = {
                A00000000000000000000001 /* ${appDirName}App.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ${appDirName}App.swift; sourceTree = "<group>"; };
                A00000000000000000000002 /* ContentView.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ContentView.swift; sourceTree = "<group>"; };
                A00000000000000000000003 /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; };
                A00000000000000000000004 /* ${appDirName}Tests.swift */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = ${appDirName}Tests.swift; sourceTree = "<group>"; };
                A00000000000000000000005 /* ${appDirName}.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = ${appDirName}.app; sourceTree = BUILT_PRODUCTS_DIR; };
                A00000000000000000000006 /* ${appDirName}Tests.xctest */ = {isa = PBXFileReference; explicitFileType = wrapper.cfbundle; includeInIndex = 0; path = ${appDirName}Tests.xctest; sourceTree = BUILT_PRODUCTS_DIR; };
                A00000000000000000000010 /* ${appDirName}App.swift in Sources */ = {isa = PBXBuildFile; fileRef = A00000000000000000000001 /* ${appDirName}App.swift */; };
                A00000000000000000000011 /* ContentView.swift in Sources */ = {isa = PBXBuildFile; fileRef = A00000000000000000000002 /* ContentView.swift */; };
                A00000000000000000000012 /* ${appDirName}Tests.swift in Sources */ = {isa = PBXBuildFile; fileRef = A00000000000000000000004 /* ${appDirName}Tests.swift */; };
                A00000000000000000000020 /* ${appDirName} */ = {
                    isa = PBXGroup;
                    children = (
                        A00000000000000000000001 /* ${appDirName}App.swift */,
                        A00000000000000000000002 /* ContentView.swift */,
                    );
                    path = $appDirName;
                    sourceTree = "<group>";
                };
                A00000000000000000000021 /* ${appDirName}Tests */ = {
                    isa = PBXGroup;
                    children = (
                        A00000000000000000000004 /* ${appDirName}Tests.swift */,
                    );
                    path = ${appDirName}Tests;
                    sourceTree = "<group>";
                };
                A00000000000000000000022 /* Products */ = {
                    isa = PBXGroup;
                    children = (
                        A00000000000000000000005 /* ${appDirName}.app */,
                        A00000000000000000000006 /* ${appDirName}Tests.xctest */,
                    );
                    name = Products;
                    sourceTree = "<group>";
                };
                A00000000000000000000023 = {
                    isa = PBXGroup;
                    children = (
                        A00000000000000000000020 /* ${appDirName} */,
                        A00000000000000000000021 /* ${appDirName}Tests */,
                        A00000000000000000000003 /* Info.plist */,
                        A00000000000000000000022 /* Products */,
                    );
                    sourceTree = "<group>";
                };
                A00000000000000000000030 /* Sources */ = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (A00000000000000000000010, A00000000000000000000011); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000031 /* Frameworks */ = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000032 /* Resources */ = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000033 /* Sources */ = {isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = (A00000000000000000000012); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000034 /* Frameworks */ = {isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000035 /* Resources */ = {isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0; };
                A00000000000000000000040 /* $appDirName */ = {
                    isa = PBXNativeTarget;
                    buildConfigurationList = A00000000000000000000070;
                    buildPhases = (A00000000000000000000030, A00000000000000000000031, A00000000000000000000032);
                    buildRules = ();
                    dependencies = ();
                    name = $appDirName;
                    productName = $appDirName;
                    productReference = A00000000000000000000005;
                    productType = "com.apple.product-type.application";
                };
                A00000000000000000000041 /* ${appDirName}Tests */ = {
                    isa = PBXNativeTarget;
                    buildConfigurationList = A00000000000000000000071;
                    buildPhases = (A00000000000000000000033, A00000000000000000000034, A00000000000000000000035);
                    buildRules = ();
                    dependencies = ();
                    name = ${appDirName}Tests;
                    productName = ${appDirName}Tests;
                    productReference = A00000000000000000000006;
                    productType = "com.apple.product-type.bundle.unit-test";
                };
                A00000000000000000000050 /* Project object */ = {
                    isa = PBXProject;
                    attributes = {
                        BuildIndependentTargetsInParallel = 1;
                        LastSwiftUpdateCheck = 1600;
                        LastUpgradeCheck = 1600;
                        TargetAttributes = {
                            A00000000000000000000040 = {CreatedOnToolsVersion = 16.0;};
                            A00000000000000000000041 = {CreatedOnToolsVersion = 16.0; TestTargetID = A00000000000000000000040;};
                        };
                    };
                    buildConfigurationList = A00000000000000000000072;
                    compatibilityVersion = "Xcode 14.0";
                    developmentRegion = en;
                    hasScannedForEncodings = 0;
                    knownRegions = (en, Base);
                    mainGroup = A00000000000000000000023;
                    productRefGroup = A00000000000000000000022;
                    projectDirPath = "";
                    projectRoot = "";
                    targets = (A00000000000000000000040, A00000000000000000000041);
                };
                A00000000000000000000060 /* Debug */ = {isa = XCBuildConfiguration; buildSettings = {PRODUCT_NAME = "$appDirName"; PRODUCT_BUNDLE_IDENTIFIER = "$bundleId"; INFOPLIST_FILE = Info.plist; SDKROOT = iphoneos; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = "1,2"; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 5.0; CODE_SIGN_STYLE = Automatic; ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;}; name = Debug; };
                A00000000000000000000061 /* Release */ = {isa = XCBuildConfiguration; buildSettings = {PRODUCT_NAME = "$appDirName"; PRODUCT_BUNDLE_IDENTIFIER = "$bundleId"; INFOPLIST_FILE = Info.plist; SDKROOT = iphoneos; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = "1,2"; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 5.0; CODE_SIGN_STYLE = Automatic; ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;}; name = Release; };
                A00000000000000000000062 /* Debug */ = {isa = XCBuildConfiguration; buildSettings = {PRODUCT_NAME = "${appDirName}Tests"; PRODUCT_BUNDLE_IDENTIFIER = "$bundleId.tests"; INFOPLIST_FILE = ""; SDKROOT = iphoneos; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = "1,2"; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 5.0; TEST_HOST = "$(BUILT_PRODUCTS_DIR)/$appDirName.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/$appDirName"; BUNDLE_LOADER = "$(TEST_HOST)";}; name = Debug; };
                A00000000000000000000063 /* Release */ = {isa = XCBuildConfiguration; buildSettings = {PRODUCT_NAME = "${appDirName}Tests"; PRODUCT_BUNDLE_IDENTIFIER = "$bundleId.tests"; INFOPLIST_FILE = ""; SDKROOT = iphoneos; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; TARGETED_DEVICE_FAMILY = "1,2"; IPHONEOS_DEPLOYMENT_TARGET = 17.0; SWIFT_VERSION = 5.0; TEST_HOST = "$(BUILT_PRODUCTS_DIR)/$appDirName.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/$appDirName"; BUNDLE_LOADER = "$(TEST_HOST)";}; name = Release; };
                A00000000000000000000064 /* Debug */ = {isa = XCBuildConfiguration; buildSettings = {SWIFT_VERSION = 5.0;}; name = Debug; };
                A00000000000000000000065 /* Release */ = {isa = XCBuildConfiguration; buildSettings = {SWIFT_VERSION = 5.0;}; name = Release; };
                A00000000000000000000070 = {isa = XCConfigurationList; buildConfigurations = (A00000000000000000000060, A00000000000000000000061); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
                A00000000000000000000071 = {isa = XCConfigurationList; buildConfigurations = (A00000000000000000000062, A00000000000000000000063); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
                A00000000000000000000072 = {isa = XCConfigurationList; buildConfigurations = (A00000000000000000000064, A00000000000000000000065); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; };
            };
            rootObject = A00000000000000000000050 /* Project object */;
        }
        """.trimIndent() + "\n"
}
