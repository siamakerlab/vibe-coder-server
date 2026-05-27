package com.siamakerlab.vibecoder.server.actions

import com.siamakerlab.vibecoder.server.env.EnvDiagnostics
import com.siamakerlab.vibecoder.shared.dto.CapabilityKey
import com.siamakerlab.vibecoder.shared.dto.CheckStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val log = KotlinLogging.logger {}

/**
 * Computes the live capability map for a project — which `requires` keys
 * are currently true.
 *
 * `EnvDiagnostics.run()` shells out to `java`, `git`, and `claude` and is
 * expensive (~hundreds of ms), so the host-wide portion is cached with a short
 * TTL. MCP availability is read from the project's `.mcp.json` via
 * [ProjectActionRegistry.mcpServerNames] and not cached separately (mtime
 * polling inside the registry already handles freshness).
 */
class CapabilityService(
    private val env: EnvDiagnostics,
    private val registry: ProjectActionRegistry,
    /** TTL for the env snapshot (defaults to 30s). */
    private val ttlMs: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private data class HostCaps(val git: Boolean, val claude: Boolean, val computedAt: Long)

    private val hostCache = AtomicReference<HostCaps?>(null)

    /**
     * Capability map for [projectId]. Includes:
     *  - `build` — always true for registered projects (gradle wrapper assumed).
     *  - `git`, `claude_session` — host CLI presence (cached for [ttlMs]).
     *  - `mcp:<name>` — true for every server declared in `.mcp.json`.
     */
    fun forProject(projectId: String): Map<String, Boolean> {
        val host = hostSnapshot()
        val map = LinkedHashMap<String, Boolean>()
        map[CapabilityKey.BUILD] = true
        map[CapabilityKey.GIT] = host.git
        map[CapabilityKey.CLAUDE_SESSION] = host.claude
        for (server in registry.mcpServerNames(projectId)) {
            map[CapabilityKey.mcp(server)] = true
        }
        return map
    }

    private fun hostSnapshot(): HostCaps {
        val nowMs = now()
        val cached = hostCache.get()
        if (cached != null && nowMs - cached.computedAt < ttlMs) return cached
        val fresh = try {
            val snap = env.run()   // v1.25.2 — service-to-service. config.i18n.defaultLanguage 사용.
            HostCaps(
                git = snap.git.status == CheckStatus.OK,
                claude = snap.claude.status == CheckStatus.OK,
                computedAt = nowMs,
            )
        } catch (t: Throwable) {
            log.warn(t) { "EnvDiagnostics.run() failed; treating host capabilities as unavailable" }
            HostCaps(git = false, claude = false, computedAt = nowMs)
        }
        hostCache.set(fresh)
        return fresh
    }
}
