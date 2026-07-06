package com.siamakerlab.vibecoder.server.agent

import com.siamakerlab.vibecoder.server.agent.opencode.applyOpenCodeProcessEnv
import com.siamakerlab.vibecoder.server.agent.opencode.stripOpenCodeAnsi
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.server.core.OsType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val modelCatalogLog = KotlinLogging.logger {}

data class AgentModelOption(
    val value: String,
    val label: String,
    val source: Source = Source.FALLBACK,
) {
    enum class Source { DYNAMIC, FALLBACK, CURRENT }
}

/**
 * Provider별 모델 선택지 catalog.
 *
 * OpenCode/GLM은 CLI가 제공하는 `opencode models` 결과를 짧게 캐시한다. Claude/Codex CLI는
 * 현재 안정적인 모델 목록 명령이 없어, fallback 목록에 현재 저장/적용 모델을 합쳐 UI에서
 * 사용자가 선택한 값이 사라지지 않게 한다.
 */
class ModelCatalogService(
    private val configProvider: () -> ServerConfig,
    private val opencodeCmdProvider: () -> String = { defaultOpenCodeCmd() },
    private val nowProvider: () -> Instant = { Instant.now() },
) {
    private data class CacheEntry(
        val expiresAt: Instant,
        val options: List<AgentModelOption>,
    )

    @Volatile
    private var opencodeCache: CacheEntry? = null

    fun modelsFor(
        provider: AgentProvider,
        currentModel: String? = null,
        effectiveModel: String? = null,
    ): List<AgentModelOption> {
        val base = when (provider) {
            AgentProvider.OPENCODE -> opencodeModels()
            else -> fallbackModels(provider)
        }
        return withCurrentModels(base, currentModel, effectiveModel)
    }

    private fun opencodeModels(): List<AgentModelOption> {
        val now = nowProvider()
        opencodeCache?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.options }
        val queried = runCatching { queryOpenCodeModels() }
            .onFailure { modelCatalogLog.debug(it) { "opencode models 조회 실패: ${it.message}" } }
            .getOrDefault(emptyList())
        val filtered = if (configProvider().opencode.zai.enforceCodingPlan) {
            queried.filter { it.value.startsWith("zai-coding-plan/") }
        } else {
            queried
        }
        val options = filtered.ifEmpty { fallbackModels(AgentProvider.OPENCODE) }
        opencodeCache = CacheEntry(now.plus(CACHE_TTL), options)
        return options
    }

    private fun queryOpenCodeModels(): List<AgentModelOption> {
        val pb = ProcessBuilder(opencodeCmdProvider(), "models", "zai-coding-plan")
            .redirectErrorStream(true)
        applyOpenCodeProcessEnv(pb)
        val raw = runWithHardTimeout(pb, timeoutSeconds = 10)
        val cleaned = stripOpenCodeAnsi(raw)
        val matches = Regex("""zai-coding-plan/[A-Za-z0-9._-]+""")
            .findAll(cleaned)
            .map { it.value.trim() }
            .toList()
        return matches.distinctBy { it.lowercase() }
            .map { AgentModelOption(it, labelFor(it), AgentModelOption.Source.DYNAMIC) }
    }

    private fun runWithHardTimeout(pb: ProcessBuilder, timeoutSeconds: Long): String {
        val proc = pb.start()
        val sb = StringBuilder()
        val done = CountDownLatch(1)
        Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(sb) { sb.append(line).append('\n') }
                    }
                }
            }
            done.countDown()
        }.apply { isDaemon = true; start() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            runCatching { proc.descendants().forEach { it.destroyForcibly() } }
            proc.destroyForcibly()
        }
        done.await(1500, TimeUnit.MILLISECONDS)
        return synchronized(sb) { sb.toString() }
    }

    companion object {
        private val CACHE_TTL: Duration = Duration.ofHours(1)

        fun fallbackModels(provider: AgentProvider): List<AgentModelOption> = when (provider) {
            AgentProvider.CLAUDE -> listOf(
                AgentModelOption("sonnet", "Sonnet (권장·저비용)"),
                AgentModelOption("opus", "Opus (고성능·고비용)"),
                AgentModelOption("fable", "Fable 5"),
                AgentModelOption("haiku", "Haiku"),
            )
            AgentProvider.CODEX -> listOf(
                AgentModelOption("gpt-5", "GPT-5"),
                AgentModelOption("gpt-5-codex", "GPT-5 Codex"),
            )
            AgentProvider.OPENCODE -> listOf(
                AgentModelOption("zai-coding-plan/glm-5.2", "GLM 5.2 (권장)"),
                AgentModelOption("zai-coding-plan/glm-5.1", "GLM 5.1"),
                AgentModelOption("zai-coding-plan/glm-5-turbo", "GLM 5 Turbo"),
                AgentModelOption("zai-coding-plan/glm-4.7", "GLM 4.7"),
                AgentModelOption("zai-coding-plan/glm-4.5-air", "GLM 4.5 Air"),
                AgentModelOption("zai-coding-plan/glm-5v-turbo", "GLM 5V Turbo (vision)"),
            )
        }

        fun labelFor(model: String): String {
            val raw = model.substringAfterLast('/').trim()
            if (raw.isBlank()) return model
            return raw.split('-', '_')
                .filter { it.isNotBlank() }
                .joinToString(" ") { part ->
                    when {
                        part.equals("glm", ignoreCase = true) -> "GLM"
                        part.equals("gpt", ignoreCase = true) -> "GPT"
                        part.all { it.isDigit() || it == '.' } -> part
                        part.length <= 2 && part.all { it.isLetter() } -> part.uppercase()
                        else -> part.replaceFirstChar { it.uppercaseChar() }
                    }
                }
        }

        private fun withCurrentModels(
            base: List<AgentModelOption>,
            currentModel: String?,
            effectiveModel: String?,
        ): List<AgentModelOption> {
            val extras = listOf(currentModel, effectiveModel)
                .mapNotNull { it?.trim()?.takeIf { v -> v.isNotBlank() && !v.equals("default", ignoreCase = true) } }
                .filterNot { candidate -> base.any { it.value.equals(candidate, ignoreCase = true) } }
                .map { AgentModelOption(it, labelFor(it), AgentModelOption.Source.CURRENT) }
            return (extras + base).distinctBy { it.value.lowercase() }
        }

        private fun defaultOpenCodeCmd(): String =
            System.getenv("OPENCODE_CMD")?.takeIf { it.isNotBlank() }
                ?: if (OsType.detect() == OsType.WINDOWS) "opencode.cmd" else "opencode"
    }
}
