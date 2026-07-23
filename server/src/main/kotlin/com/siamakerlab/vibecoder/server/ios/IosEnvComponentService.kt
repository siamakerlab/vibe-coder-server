package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 빌드환경(`/env-setup`) 페이지의 iPhone(macOS) 컴포넌트 감지를 한곳에 캡슐화한다.
 *
 * `EnvSetupService` 는 이 서비스에만 위임한다 — `xcrun`/`xcodebuild`/`brew` 등 Apple 도구
 * 명령을 env/admin 계층이 직접 실행하지 않는다(ROADMAP §18.3 캡슐화 규칙).
 *
 * 감지 소스:
 * - [IosPreflightService] — 실행 모드(linux/mac_local/mac_ssh) + Xcode/simctl/codesigning.
 * - [IosAgentCommand] allowlist — Simulator runtime / SwiftLint / SwiftFormat / CocoaPods version.
 *
 * 결과는 30초 TTL 로 캐시한다. 캐시가 없거나 만료된 경우에도 HTTP 요청 스레드에서 원격 Mac
 * probe 를 기다리지 않고 stale/fallback 값을 반환한 뒤 백그라운드에서 갱신한다.
 * Linux 단독(mode=linux)에서는 명령을 전혀 실행하지 않는다.
 */
class IosEnvComponentService(
    private val preflight: IosPreflightService = IosPreflightService(),
    private val agentConfigProvider: () -> IosAgentSection = {
        runCatching { ConfigHolder.current.ios.agent }.getOrDefault(IosAgentSection())
    },
    private val runner: CommandRunner = ProcessCommandRunner,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val cache = AtomicReference<Pair<Long, IosEnvSnapshot>?>(null)
    private val refreshInFlight = AtomicBoolean(false)

    /** 캐시된 스냅샷 반환. 만료/미존재 시 백그라운드 갱신을 예약하고 즉시 반환한다. */
    fun snapshot(): IosEnvSnapshot {
        val now = nowMs()
        cache.get()?.let { (ts, snap) -> if (now - ts < CACHE_TTL_MS) return snap }
        val fallback = cache.get()?.second ?: fastFallbackSnapshot()
        if (fallback.mode == "linux") return fallback
        refreshAsync()
        return fallback
    }

    /** 테스트/명시 진단용 동기 갱신. 일반 SSR 요청 경로에서는 [snapshot]을 사용한다. */
    internal fun snapshotBlocking(): IosEnvSnapshot {
        val fresh = compute()
        cache.set(nowMs() to fresh)
        return fresh
    }

    /**
     * v1.174.0 — 설치/상태 변경 직후 캐시를 강제로 신선하게 다시 계산한다(동기 probe).
     *
     * SwiftLint/SwiftFormat/CocoaPods 등을 설치한 직후, 30초 TTL 캐시가 아직 유효해서 빌드환경
     * 카드가 잠시 "미설치"로 남는 문제를 막는다. 설치 태스크의 onSuccess 에서 호출한다.
     * 실패해도 무해 — 다음 [snapshot] 이 다시 갱신한다.
     */
    fun refreshNow() {
        runCatching { snapshotBlocking() }
    }

    private fun refreshAsync() {
        if (!refreshInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                val fresh = compute()
                cache.set(nowMs() to fresh)
            } finally {
                refreshInFlight.set(false)
            }
        }.apply {
            name = "ios-env-refresh"
            isDaemon = true
            start()
        }
    }

    private fun fastFallbackSnapshot(): IosEnvSnapshot {
        val agent = agentConfigProvider()
        val useSshAgent = agent.enabled && agent.mode.trim().lowercase() in setOf("ssh", "remote")
        if (useSshAgent) {
            return IosEnvSnapshot(
                mode = "mac_ssh",
                macAvailable = true,
                blockedReason = "refreshing",
            )
        }
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return if (osName.contains("mac")) {
            IosEnvSnapshot(mode = "mac_local", macAvailable = true, blockedReason = "refreshing")
        } else {
            IosEnvSnapshot(mode = "linux", macAvailable = false, blockedReason = "mac_required")
        }
    }

    private fun compute(): IosEnvSnapshot {
        val pf = preflight.check()
        if (pf.mode == "linux") {
            return IosEnvSnapshot(
                mode = "linux",
                macAvailable = false,
                blockedReason = pf.blockedReason ?: "mac_required",
            )
        }
        // 원격 Mac agent 가 응답하지 않으면(mac_ssh + agent_unreachable) 추가 probe 4개도 전부
        // 실패(SSH exit 255)한다 — 불필요한 SSH 재시도를 피하고 blocked 상태만 전달한다.
        if (pf.blockedReason == "agent_unreachable") {
            return IosEnvSnapshot(
                mode = pf.mode,
                macAvailable = pf.macAvailable,
                blockedReason = "agent_unreachable",
            )
        }

        val agent = agentConfigProvider()
        val useSsh = pf.mode == "mac_ssh"
        val agentRunner = IosAgentCommandRunner(
            config = if (useSsh) agent.copy(mode = "ssh") else IosAgentSection(mode = "local"),
            processRunner = runner,
        )

        val runtimes = agentRunner.run(IosAgentCommand.SIMULATOR_RUNTIMES, TIMEOUT)
        val swiftLint = agentRunner.run(IosAgentCommand.SWIFTLINT_VERSION, TIMEOUT)
        val swiftFormat = agentRunner.run(IosAgentCommand.SWIFTFORMAT_VERSION, TIMEOUT)
        val cocoapods = agentRunner.run(IosAgentCommand.COCOAPODS_VERSION, TIMEOUT)

        val iosRuntimes = parseIosRuntimes(runtimes.stdout)
        val cltPath = pf.xcodeSelectPath
        val cltIsFullXcode = cltPath?.contains("Xcode", ignoreCase = true) == true

        return IosEnvSnapshot(
            mode = pf.mode,
            macAvailable = pf.macAvailable,
            blockedReason = pf.blockedReason,
            xcodeAvailable = pf.xcodeAvailable,
            xcodeVersion = pf.xcodeVersion,
            xcodeSelectPath = cltPath,
            simctlAvailable = pf.simctlAvailable,
            commandLineToolsAvailable = cltPath != null,
            commandLineToolsFullXcode = cltIsFullXcode,
            iosRuntimes = iosRuntimes,
            simulatorRuntimeAvailable = iosRuntimes.isNotEmpty(),
            swiftLintVersion = firstLineIfOk(swiftLint),
            swiftFormatVersion = firstLineIfOk(swiftFormat),
            cocoapodsVersion = firstLineIfOk(cocoapods),
            codesigningIdentityCount = pf.codesigningIdentities.size,
        )
    }

    /** version probe 성공 시 첫 줄(공백이면 null), 실패면 null. */
    private fun firstLineIfOk(r: CommandResult): String? {
        if (!r.ok) return null
        return r.stdout.trim().lineSequence().firstOrNull()?.trim()?.ifBlank { null }
    }

    private fun parseIosRuntimes(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching emptyList()
            val arr = root["runtimes"] as? JsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val available = (obj["isAvailable"]?.jsonPrimitive?.booleanOrNull) ?: true
                if (!available) return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val identifier = obj["identifier"]?.jsonPrimitive?.content.orEmpty()
                if (name.startsWith("iOS") || identifier.contains("iOS")) name else null
            }.distinct()
        }.getOrDefault(emptyList())
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(6)
        private const val CACHE_TTL_MS = 30_000L
    }
}

/**
 * 빌드환경 iPhone 카드 렌더 + 진단에 필요한 최소 스냅샷.
 *
 * `mode` 가 `linux` 이면 나머지 필드는 모두 미가용(false/empty/null)이고 [blockedReason] 은
 * `mac_required` 다.
 */
data class IosEnvSnapshot(
    val mode: String,                       // linux | mac_local | mac_ssh
    val macAvailable: Boolean,
    val blockedReason: String? = null,      // mac_required | agent_unreachable | xcode_missing | simctl_missing | ...
    val xcodeAvailable: Boolean = false,
    val xcodeVersion: String? = null,
    val xcodeSelectPath: String? = null,
    val simctlAvailable: Boolean = false,
    val commandLineToolsAvailable: Boolean = false,
    val commandLineToolsFullXcode: Boolean = false,
    val iosRuntimes: List<String> = emptyList(),
    val simulatorRuntimeAvailable: Boolean = false,
    val swiftLintVersion: String? = null,
    val swiftFormatVersion: String? = null,
    val cocoapodsVersion: String? = null,
    val codesigningIdentityCount: Int = 0,
) {
    val isMac: Boolean get() = mode == "mac_local" || mode == "mac_ssh"
}
