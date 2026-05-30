package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    // 21차 후속(M5) — 인자 검증. plugin id 는 plugin@marketplace 형태, marketplace name 은
    // 단순 식별자. dash 시작/빈 값 거부로 CLI flag 주입(--scope 등) 방지(셸 미경유라 쉘 인젝션은
    // 원래 없으나 짝 모듈 AdbService 의 정규식 검증과 정책 통일).
    private val pluginIdRe = Regex("^[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?$")
    private val mktNameRe = Regex("^[A-Za-z0-9._-]+$")
    private fun reqPlugin(p: String): String {
        require(pluginIdRe.matches(p)) { "invalid plugin id (plugin@marketplace, [A-Za-z0-9._-])" }
        return p
    }

    // ── 변경 (task queue) ────────────────────────────────────────────────────
    fun addMarketplace(source: String, scope: String = "user"): String {
        // source 는 owner/repo · URL · 경로 — 형태가 다양해 정규식 대신 dash-시작/빈 값만 거부.
        require(source.isNotBlank() && !source.startsWith("-")) { "invalid marketplace source" }
        return spawn("marketplace add $source", listOf("plugin", "marketplace", "add", source, "--scope", scope), null)
    }

    fun removeMarketplace(name: String): String {
        require(mktNameRe.matches(name)) { "invalid marketplace name" }
        return spawn("marketplace remove $name", listOf("plugin", "marketplace", "remove", name), null)
    }

    fun install(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("install ${reqPlugin(plugin)} (scope=$scope)", listOf("plugin", "install", plugin, "--scope", scope), cwd)

    fun uninstall(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("uninstall ${reqPlugin(plugin)}", listOf("plugin", "uninstall", plugin, "--scope", scope), cwd)

    fun enable(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("enable ${reqPlugin(plugin)}", listOf("plugin", "enable", plugin, "--scope", scope), cwd)

    fun disable(plugin: String, scope: String = "user", cwd: Path? = null): String =
        spawn("disable ${reqPlugin(plugin)}", listOf("plugin", "disable", plugin, "--scope", scope), cwd)

    fun update(plugin: String, cwd: Path? = null): String =
        spawn("update ${reqPlugin(plugin)}", listOf("plugin", "update", plugin), cwd)

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

    // 21차 후속(B1) — read 와 timeout 분리. 이전엔 useLines 가 EOF 까지 block 한 뒤에야
    // waitFor(timeout) 를 호출해, 자식이 stdout 을 연 채 멈추면 timeout/destroy 가 영영
    // 도달 못 해 좀비 프로세스 + IO 스레드가 누수됐다. stdin 차단(interactive 대기 방지) +
    // read 를 별도 코루틴 pump 로, waitFor 는 runInterruptible + withTimeoutOrNull 로 감싼다.
    private suspend fun runStreaming(taskId: String, cmd: List<String>, cwd: Path?) = coroutineScope {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        cwd?.let { pb.directory(it.toFile()) }
        // CLAUDE_CONFIG_DIR 등은 부모(vibe) env 상속 — Dockerfile ENV.
        val proc = pb.start()
        val pump = launch(Dispatchers.IO) {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) hub.publisher(taskId).emit(WsFrame.Log(taskId, "STDOUT", line, clock.nowIso()))
                }
            }
        }
        val code = withTimeoutOrNull(5 * 60_000L) { runInterruptible(Dispatchers.IO) { proc.waitFor() } }
        if (code == null) {
            proc.destroyForcibly()
            pump.cancel()
            throw RuntimeException("plugin 명령 timeout (5분)")
        }
        pump.join()
        if (code != 0) throw RuntimeException("claude ${cmd.drop(1).joinToString(" ")} → exit $code")
    }

    /** `claude … --json` 을 동기 실행하고 JsonArray 로 파싱. 실패 시 null. */
    private fun runJson(args: List<String>, cwd: Path?): JsonArray? = runCatching {
        // v1.51.0 — 25차: stderr 를 DISCARD 로 배수. 이전엔 (false)+stdout-only readText 라 claude
        // CLI 가 stderr 로 64KB 초과 출력 시 파이프 포화 → readText 가 30s watchdog destroyForcibly
        // 까지 블록(매번 30s 지연 + null 반환). stdout(JSON)은 보존해야 하므로 merge 대신 DISCARD.
        val pb = ProcessBuilder(listOf(claudeBin) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        cwd?.let { pb.directory(it.toFile()) }
        val proc = pb.start()
        // 21차 후속(M4) — read 가 timeout 보다 선행해 무력화되던 문제. watchdog 데몬이 timeout
        // 후 destroyForcibly 로 readText block 을 강제 해제. (readText 는 프로세스 종료 시 EOF)
        val watchdog = Thread {
            runCatching { if (!proc.waitFor(30, TimeUnit.SECONDS)) proc.destroyForcibly() }
        }.apply { isDaemon = true; start() }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        proc.waitFor()
        watchdog.interrupt()
        if (proc.exitValue() != 0) return null
        Json.parseToJsonElement(out.trim()) as? JsonArray
    }.getOrElse { log.warn(it) { "claude ${args.joinToString(" ")} --json 파싱 실패" }; null }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
