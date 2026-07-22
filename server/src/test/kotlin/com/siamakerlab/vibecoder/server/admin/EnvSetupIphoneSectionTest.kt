package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent
import com.siamakerlab.vibecoder.server.ios.IosEnvSnapshot
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * v1.164.0 (Phase 9) — 빌드환경 iPhone(macOS) 섹션 SSR 렌더 회귀.
 *
 * - mac_local 스냅샷: iPhone 섹션 + preflight 배너 + SwiftLint/SwiftFormat 설치 버튼 노출.
 * - linux 스냅샷: "Mac 필요" 배너 + 설치 버튼 숨김.
 */
class EnvSetupIphoneSectionTest {

    private val iphoneStates = listOf(
        ComponentState(SetupComponent.XCODE, ComponentStatus.INSTALLED, "Xcode 18.0"),
        ComponentState(SetupComponent.SWIFT_TOOLS, ComponentStatus.MISSING, "not installed"),
    )

    @Test
    fun `mac local renders iphone section with swift tools install button`() {
        val html = EnvSetupTemplates.envSetupPage(
            username = "admin",
            states = iphoneStates,
            lang = "en",
            iosEnv = IosEnvSnapshot(
                mode = "mac_local",
                macAvailable = true,
                xcodeAvailable = true,
                xcodeVersion = "Xcode 18.0",
                xcodeSelectPath = "/Applications/Xcode.app/Contents/Developer",
                simctlAvailable = true,
                iosRuntimes = listOf("iOS 18.0"),
                simulatorRuntimeAvailable = true,
            ),
        )

        html shouldContain "iPhone build environment (macOS)"
        html shouldContain "/env-setup/swift-tools/install"
        // preflight 배너에 Xcode 버전이 표시된다.
        html shouldContain "Xcode 18.0"
    }

    @Test
    fun `linux hides install button and shows mac-required banner`() {
        val linuxStates = iphoneStates.map { it.copy(status = ComponentStatus.UNKNOWN) }
        val html = EnvSetupTemplates.envSetupPage(
            username = "admin",
            states = linuxStates,
            lang = "en",
            iosEnv = IosEnvSnapshot(
                mode = "linux",
                macAvailable = false,
                blockedReason = "mac_required",
            ),
        )

        html shouldContain "iPhone build environment (macOS)"
        html shouldContain "This server is not running on macOS."
        // Linux 단독: SwiftLint 설치 버튼(POST form)은 렌더되지 않는다.
        html shouldNotContain "/env-setup/swift-tools/install"
    }
}
