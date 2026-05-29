package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.38.0 — Claude Code 플러그인/마켓플레이스 관리.
 *
 * 목록 조회는 `claude plugin [marketplace] list --json` 을 파싱(권위 소스, 안정적 구조).
 * 변경(마켓 추가/삭제, 설치/삭제/활성/비활성/업데이트)은 `claude plugin …` 자식 프로세스를
 * [TaskQueue] 로 실행 — env-setup 진행 페이지(WS 라이브 로그)에 그대로 표시.
 *
 * scope: user(전역, 모든 프로젝트 공통) / project(프로젝트별, cwd=projectRoot 기준) / local.
 * 플러그인은 MCP/hook 등 코드를 실행하므로 라우트 단에서 admin 으로 가드한다.
 */
class PluginService(
    private val clock: Clock,
    private val queue: TaskQueue,
    private val hub: LogHub,
    /** claude 바이너리. 보통 PATH 의 "claude". */
    private val claudeBin: String = "claude",
) {
    data class Marketplace(
        val name: String,
        val source: String,
        val repo: String?,
        val installLocation: String?,
    )

    data class Plugin(
        val id: String,            // "bkit@bkit-marketplace"
        val name: String,          // "bkit"
        val marketplace: String,   // "bkit-marketplace"
        val version: String?,
        val scope: String,         // user | project | local
        val enabled: Boolean,
        val projectPath: String?,
        val mcpServerNames: List<String>,
    )

    fun marketplaces(): List<Marketplace> {
        val arr = runJson(listOf("plugin", "marketplace", "list", "--json"), null) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Marketplace(
                name = o.str("name") ?: return@mapNotNull null,
                source = o.str("source") ?: "",
                repo = o.str("repo"),
                installLocation = o.str("installLocation"),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** 전체 설치 플러그인. cwd 를 주면 그 프로젝트의 project-scope 도 포함된다. */
    fun plugins(cwd: Path? = null): List<Plugin> {
        val arr = runJson(listOf("plugin", "list", "--json"), cwd) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val mcp = (o["mcpServers"] as? JsonObject)?.keys?.sorted().orEmpty()
            Plugin(
                id = id,
                name = id.substringBefore('@'),
                marketplace = id.substringAfter('@', ""),
                version = o.str("version"),
                scope = o.str("scope") ?: "user",
                enabled = (o["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "true"
                    || o["enabled"]?.toString() == "true",
                projectPath = o.str("projectPath"),
                mcpServerNames = mcp,
            )
        }
    }

    // ── 변경 (task queue) ────────────────────────────────────────────────────
    fun addMarketplace(source: String, scope: String = "user"): String =
        spawn("marketplace add $source", listOf("plugin", "marketplace", "add", source, "--scope", scope), null)

    fun removeMarketplace(name: String): String =
        spawn("marketplace remove $name", listOf("plugin", "marketplace", "remove", name), null)

    fun install(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("install $plugin (scope=$scope)", listOf("plugin", "install", plugin, "--scope", scope), cwd)

    fun uninstall(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("uninstall $plugin", listOf("plugin", "uninstall", plugin, "--scope", scope), cwd)

    fun enable(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("enable $plugin", listOf("plugin", "enable", plugin, "--scope", scope), cwd)

    fun disable(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("disable $plugin", listOf("plugin", "disable", plugin, "--scope", scope), cwd)

    fun update(plugin: String, cwd: Path? = null): String =
        spawn("update $plugin", listOf("plugin", "update", plugin), cwd)

    // ── 내부 ────────────────────────────────────────────────────────────────
    private fun spawn(label: String, args: List<String>, cwd: Path?): String {
        val taskId = Ids.taskId()
        queue.submit(
            projectId = "plugins",
            taskId = taskId,
            onStart = { hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "▶ claude $label", clock.nowIso())) },
            executor = { _ -> withContext(Dispatchers.IO) { runStreaming(taskId, listOf(claudeBin) + args, cwd) } },
            onSuccess = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO", "✓ 완료 — 재시작 없이 다음 Claude 세션부터 적용", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "SUCCESS"))
            },
            onFailure = { e ->
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR", "✗ ${e.message}", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "FAILURE", e.message))
            },
        )
        return taskId
    }

    private suspend fun runStreaming(taskId: String, cmd: List<String>, cwd: Path?) {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        cwd?.let { pb.directory(it.toFile()) }
        // CLAUDE_CONFIG_DIR 등은 부모(vibe) env 상속 — Dockerfile ENV.
        val proc = pb.start()
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) hub.publisher(taskId).emit(WsFrame.Log(taskId, "STDOUT", line, clock.nowIso()))
        }
        if (!proc.waitFor(5, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            throw RuntimeException("plugin 명령 timeout (5분)")
        }
        val code = proc.exitValue()
        if (code != 0) throw RuntimeException("claude ${cmd.drop(1).joinToString(" ")} → exit $code")
    }

    /** `claude … --json` 을 동기 실행하고 JsonArray 로 파싱. 실패 시 null. */
    private fun runJson(args: List<String>, cwd: Path?): JsonArray? = runCatching {
        val pb = ProcessBuilder(listOf(claudeBin) + args).redirectErrorStream(false)
        cwd?.let { pb.directory(it.toFile()) }
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) { proc.destroyForcibly(); return null }
        if (proc.exitValue() != 0) return null
        Json.parseToJsonElement(out.trim()) as? JsonArray
    }.getOrElse { log.warn(it) { "claude ${args.joinToString(" ")} --json 파싱 실패" }; null }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
