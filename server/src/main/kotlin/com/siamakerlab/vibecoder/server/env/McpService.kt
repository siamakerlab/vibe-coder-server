package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.core.Clock
import com.siamakerlab.vibecoder.server.core.Ids
import com.siamakerlab.vibecoder.server.error.ApiException
import com.siamakerlab.vibecoder.server.tasks.TaskQueue
import com.siamakerlab.vibecoder.server.ws.LogHub
import com.siamakerlab.vibecoder.shared.ws.WsFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * MCP 카탈로그 설치/제거 + Claude/Codex MCP 등록.
 *
 * v0.8.0 — 빌드환경 페이지의 MCP UI 가 호출.
 *
 * 설치 메커니즘:
 *  - `npm install -g <pkg>` (vibe 사용자, prefix /home/vibe/.local).
 *    v0.7.0 부터 이 디렉토리가 bind mount 라 이미지 업그레이드 시 보존.
 *  - 설치 완료 후 Claude user-scope 와 Codex config 에 MCP server entry 추가.
 *  - argsTemplate 의 `@PKG@` / `@CONFIG:key@` placeholder 가 실 값으로 치환.
 *  - 사용자 입력 config (TOKEN 등) 는 같은 entry 의 `env` 에 들어감.
 *
 * 제거:
 *  - .mcp.json 에서 entry 만 삭제 (npm 패키지는 그대로 남김 — 디스크 절약 vs
 *    재설치 속도 trade-off; 사용자가 직접 `npm uninstall -g` 필요시 docker exec).
 */
class McpService(
    private val clock: Clock,
    private val queue: TaskQueue,
    private val hub: LogHub,
) {

    /**
     * 컴포넌트 (카탈로그 항목) 의 현재 설치/등록 상태.
     */
    enum class Status { INSTALLED, REGISTERED_ONLY, NOT_INSTALLED, UNKNOWN }

    data class EntryState(
        val id: String,
        val status: Status,
        val message: String,
        val configValues: Map<String, String> = emptyMap(),
    )

    fun detectAll(): List<EntryState> = McpCatalog.all.map { detect(it.id) }

    /**
     * v1.35.0 — 전역 `.mcp.json` 의 `mcpServers` 에 실제 등록된 **모든** server 이름.
     * 카탈로그 항목 + 터미널/Claude 가 직접 추가한 비-카탈로그 server 도 포함(감지용).
     */
    fun registeredServerNames(): List<String> =
        readUserScopeServers().keys.sorted()

    /** v1.35.0 — 전역 MCP 설정 경로 (UI 표시용). v1.66.5 — 표준 user config(.claude.json). */
    fun globalMcpJsonPath(): Path = userConfigPath()

    /**
     * 기본 MCP user-scope 등록 보장. 첫 설치와 이미지 업그레이드 모두에서
     * [McpCatalog.defaultInstallIds] 누락분을 보강 등록한다. 기존 사용자 MCP 는 보존하고,
     * default 항목은 모두 zero-config / npx 기반이라 부팅 시 네트워크 설치는 하지 않는다.
     */
    fun bootstrapDefaultsIfFirstRun() {
        // 표준 user-scope 등록으로 일원화. 멱등이라 marker 불필요(이미 등록된 건 skip).
        // 레거시 .mcp.json 이관 + defaultInstall 누락분 보강.
        ensureUserScopeRegistration()
    }

    fun detect(id: String): EntryState {
        val e = McpCatalog.get(id) ?: return EntryState(id, Status.UNKNOWN, "카탈로그에 없음")
        // v1.68.0 — binaryInstall 항목은 npm 이 아니라 PATH 의 바이너리 존재로 설치 판정.
        val installed = if (e.binaryInstall) isBinaryOnPath(e.command) else isPackageInstalled(e.pkg)
        // v1.66.5 — 표준 user-scope(`.claude.json` mcpServers) 기준으로 등록 판정.
        val userServers = readUserScopeServers()
        val registered = userServers.containsKey(id)
        val configValues = if (registered) {
            extractConfigValues(buildJsonObject { put("mcpServers", userServers) }, id, e)
        } else emptyMap()
        // v1.66.4 — npx 기반 MCP(`npx -y <pkg>`)는 글로벌 설치 없이 on-demand 실행되므로,
        //  등록만 되어 있어도 "설치됨" 으로 본다(기본 설치 MCP 의 "등록만" 오표시 수정).
        //  글로벌 설치가 필요한(command != npx) 항목만 npm 미설치 시 "등록만" 으로 구분.
        val effectivelyInstalled = installed || (registered && e.command == "npx")
        val status = when {
            effectivelyInstalled && registered -> Status.INSTALLED
            registered -> Status.REGISTERED_ONLY
            else -> Status.NOT_INSTALLED
        }
        val msg = when (status) {
            Status.INSTALLED -> when {
                e.binaryInstall -> "설치됨 (바이너리) + 등록됨"
                installed -> "설치됨 + 등록됨"
                else -> "등록됨 (npx on-demand)"
            }
            Status.REGISTERED_ONLY -> if (e.binaryInstall) "등록되어 있으나 바이너리 없음" else "등록되어 있으나 npm 패키지 미설치"
            Status.NOT_INSTALLED -> "미설치"
            Status.UNKNOWN -> "확인 불가"
        }
        return EntryState(id, status, msg, configValues)
    }

    /**
     * 선택된 MCP 들을 일괄 설치 — npm install -g + Claude/Codex MCP 설정 갱신.
     *
     * @param selections id → configValues 맵. configValues 는 ConfigField.key 별
     *   사용자 입력값. 빈 문자열은 무시 (선택 항목).
     * @return 새 task id (진행 페이지가 polling/WS 로 라이브 로그 보기).
     */
    fun spawnBatch(selections: Map<String, Map<String, String>>): String {
        if (selections.isEmpty()) {
            throw ApiException.localized(400, "no_selection", messageKey = "api.mcp.noSelection")
        }
        // 카탈로그에 없는 id 거부, comingSoon 항목도 거부.
        selections.keys.forEach { id ->
            val entry = McpCatalog.get(id)
                ?: throw ApiException.localized(400, "unknown_mcp", messageKey = "api.mcp.unknownMcp", args = listOf(id))
            if (entry.comingSoon) {
                throw ApiException.localized(400, "coming_soon",
                    messageKey = "api.mcp.comingSoon", args = listOf(entry.displayName))
            }
        }
        // 필수 config 누락 검사
        selections.forEach { (id, cfg) ->
            val entry = McpCatalog.get(id)!!
            entry.configFields.filter { it.required }.forEach { f ->
                val v = cfg[f.key].orEmpty().trim()
                if (v.isEmpty()) {
                    throw ApiException.localized(400, "missing_config",
                        messageKey = "api.mcp.missingConfig", args = listOf("${entry.displayName}: ${f.label}"))
                }
            }
        }

        val taskId = Ids.taskId()
        queue.submit(
            projectId = "mcp",
            taskId = taskId,
            onStart = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                    "▶ MCP 일괄 설치 시작 (${selections.size}개)", clock.nowIso()))
            },
            executor = { _ ->
                withContext(Dispatchers.IO) {
                    runBatch(taskId, selections)
                }
            },
            onSuccess = {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                    "✓ MCP 설치 완료 — Claude/Codex 콘솔에서 즉시 사용 가능", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "SUCCESS"))
            },
            onFailure = { e ->
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR",
                    "✗ 실패: ${e.message}", clock.nowIso()))
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "FAILED", e.message))
            },
            onCancel = {
                hub.publisher(taskId).emit(WsFrame.Done(taskId, "CANCELED"))
            },
        )
        return taskId
    }

    /**
     * v0.11.0 — Secret 파일 업로드. Service Account JSON / OAuth client.json
     * / Apple .p8 같은 isFile=true configField 가 호출.
     *
     * 저장 위치: `${CLAUDE_CONFIG_DIR}/mcp-secrets/<mcpId>-<key><ext>` (0600).
     * 같은 (mcpId, key) 로 재업로드 시 덮어쓰기 (이전 파일 atomic replace).
     *
     * @return 저장된 절대 경로. 호출자는 이 경로를 install request 의
     *   configValues[key] 로 전달해 `.mcp.json` 의 env 에 박힘.
     * @throws ApiException 카탈로그에 없는 mcpId/key, isFile=false 인 key,
     *   비정상 크기, IO 실패.
     */
    fun uploadConfigFile(
        mcpId: String,
        key: String,
        bytes: ByteArray,
        originalFileName: String?,
    ): String {
        val entry = McpCatalog.get(mcpId)
            ?: throw ApiException.localized(404, "unknown_mcp", messageKey = "api.mcp.unknownMcp", args = listOf(mcpId))
        val field = entry.configFields.firstOrNull { it.key == key }
            ?: throw ApiException.localized(404, "unknown_field", messageKey = "api.mcp.unknownField", args = listOf(key, entry.displayName))
        if (!field.isFile) {
            throw ApiException.localized(400, "not_file_field",
                messageKey = "api.mcp.notFileField", args = listOf(field.label))
        }
        if (bytes.isEmpty()) {
            throw ApiException.localized(400, "empty", messageKey = "api.mcp.empty")
        }
        if (bytes.size > MAX_SECRET_FILE_BYTES) {
            throw ApiException.localized(413, "too_large",
                messageKey = "api.mcp.tooLarge", args = listOf(bytes.size, MAX_SECRET_FILE_BYTES))
        }

        val secretsDir = claudeConfigDir().resolve("mcp-secrets")
        try {
            Files.createDirectories(secretsDir)
            // 디렉토리 자체도 0700
            runCatching {
                Files.setPosixFilePermissions(secretsDir, setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ))
            }
        } catch (e: Throwable) {
            throw ApiException.localized(500, "io", messageKey = "api.mcp.secretsDirIo", args = listOf(e.message ?: ""))
        }

        // 안전한 파일명 — id/key 는 catalog 에서 온 값이라 검증된 ASCII 이지만,
        // 그래도 path traversal 방지 차원에서 영숫자/하이픈만 허용.
        val safeId = sanitizeName(mcpId)
        val safeKey = sanitizeName(key)
        val ext = guessExtension(originalFileName, field.acceptMime).ifEmpty { ".bin" }
        val target = secretsDir.resolve("$safeId-$safeKey$ext")

        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            Files.write(tmp, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw ApiException.localized(500, "io", messageKey = "api.mcp.fileWriteIo", args = listOf(e.message ?: ""))
        }
        runCatching {
            Files.setPosixFilePermissions(target, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
        log.info { "mcp secret uploaded: ${entry.displayName}.$key → $target (${bytes.size} bytes)" }
        return target.toString()
    }

    private fun sanitizeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun guessExtension(originalName: String?, acceptMime: String?): String {
        // 1) 원본 파일명 확장자 — 가장 신뢰 가능
        if (!originalName.isNullOrBlank()) {
            val dot = originalName.lastIndexOf('.')
            if (dot in 0..originalName.length - 2) {
                val ext = originalName.substring(dot)
                if (ext.length <= 8 && ext.matches(Regex("\\.[A-Za-z0-9]+"))) return ext.lowercase()
            }
        }
        // 2) acceptMime 첫 항목 — 예: ".json,application/json" → .json
        if (!acceptMime.isNullOrBlank()) {
            val first = acceptMime.split(',').map { it.trim() }.firstOrNull { it.startsWith(".") }
            if (first != null) return first.lowercase()
        }
        return ""
    }

    /**
     * MCP 설정 entry 만 제거 (npm 패키지는 남김).
     * 완전 삭제하려면 docker exec 로 `npm uninstall -g <pkg>` 직접 실행 안내.
     */
    fun unregister(ids: List<String>) {
        if (ids.isEmpty()) return
        // v1.66.5 — 표준 user-scope 에서 제거(`claude mcp remove -s user`).
        ids.forEach { id -> runClaudeMcp("remove", id, "-s", "user") }
        ids.forEach { id -> runCodexMcp("remove", id) }
        // 레거시 .mcp.json 에 남아있던 동일 항목도 정리(있으면).
        runCatching {
            val current = readMcpJson()
            if (current != null) {
                val servers = (current["mcpServers"] as? JsonObject) ?: buildJsonObject {}
                if (servers.keys.any { it in ids }) {
                    val updated = buildJsonObject {
                        current.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
                        put("mcpServers", buildJsonObject { servers.forEach { (k, v) -> if (k !in ids) put(k, v) } })
                    }
                    writeMcpJsonAtomic(mcpJsonPath(), updated)
                }
            }
        }
        log.info { "unregistered MCP entries (user-scope): $ids" }
    }

    // ─────────────────────────────────────────────────────────────────
    // 내부 — npm + .mcp.json
    // ─────────────────────────────────────────────────────────────────

    private suspend fun runBatch(
        taskId: String,
        selections: Map<String, Map<String, String>>,
    ) {
        var idx = 0
        for ((id, cfg) in selections) {
            idx++
            val entry = McpCatalog.get(id)!!
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                "── [$idx/${selections.size}] ${entry.displayName} (${entry.pkg})",
                clock.nowIso()))

            // 1) npm install -g <pkg> — v1.68.0: 바이너리 항목은 이미지에 박혀 있어 설치 skip.
            if (entry.binaryInstall) {
                if (!isBinaryOnPath(entry.command)) {
                    throw RuntimeException("바이너리 `${entry.command}` 를 PATH 에서 찾을 수 없습니다(이미지 업그레이드 필요).")
                }
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                    "  · 바이너리 `${entry.command}` (이미지 번들) — npm 설치 생략", clock.nowIso()))
            } else {
                val installExit = runNpmInstall(taskId, entry.pkg)
                if (installExit != 0) {
                    throw RuntimeException("npm install -g ${entry.pkg} → exit $installExit")
                }
            }

            // 2) 표준 MCP 등록. Claude 는 user-scope, Codex 는 CODEX_HOME/config.toml 에 등록한다.
            //    둘은 설정 파일을 공유하지 않으므로 설치 시 양쪽에 명시 등록해야 한다.
            withContext(Dispatchers.IO) { registerMcpServer(entry.id, buildServerEntryJson(entry, cfg)) }
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                "  ✓ ${entry.displayName} 설치 + Claude/Codex 등록", clock.nowIso()))
        }
    }

    private suspend fun runNpmInstall(taskId: String, pkg: String): Int = withContext(Dispatchers.IO) {
        val cmd = listOf("npm", "install", "-g", pkg)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        // npm 캐시 / prefix 는 vibe 사용자 홈 (.npmrc) 가 결정. 컨테이너 안에선 자동.
        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "ERROR",
                "npm 실행 실패: ${e.message}", clock.nowIso()))
            return@withContext -1
        }
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                hub.publisher(taskId).emit(WsFrame.Log(taskId, "STDOUT", line, clock.nowIso()))
            }
        }
        if (!proc.waitFor(10, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            return@withContext -2
        }
        proc.exitValue()
    }

    /**
     * v1.66.5 — 카탈로그 항목 → Claude MCP server JSON ({command, args, env}).
     * placeholder 치환: `@PKG@` → 패키지명, `@CONFIG:key@` → cfg[key].
     */
    private fun buildServerEntryJson(entry: McpCatalog.McpEntry, cfg: Map<String, String>): JsonObject {
        val args = entry.argsTemplate.map { arg ->
            arg.replace("@PKG@", entry.pkg).let { a ->
                Regex("@CONFIG:([^@]+)@").replace(a) { m -> cfg[m.groupValues[1]].orEmpty() }
            }
        }
        val argTokens = entry.argsTemplate.joinToString(" ")
        val envEntries = entry.configFields
            .filter { f -> cfg[f.key].orEmpty().isNotEmpty() && !argTokens.contains("@CONFIG:${f.key}@") }
            .associate { it.key to cfg[it.key]!! }
        return buildJsonObject {
            put("command", entry.command)
            put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
            if (envEntries.isNotEmpty()) {
                put("env", buildJsonObject { envEntries.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // v1.66.5 — 표준 글로벌(user-scope) 등록. claude 는 CLAUDE_CONFIG_DIR/.mcp.json 을
    //  MCP 설정으로 자동 인식하지 않으므로(과거 등록은 콘솔에서 안 보였음), 표준 위치
    //  (`claude mcp add-json -s user` → .claude.json 의 mcpServers)에 등록한다. 이렇게 하면
    //  콘솔/서브에이전트/터미널/`claude mcp list` 모두에서 일관되게 보인다.
    // ─────────────────────────────────────────────────────────────────

    private fun claudeCmd(): String = System.getenv("CLAUDE_CMD")?.takeIf { it.isNotBlank() } ?: "claude"
    private fun codexCmd(): String = System.getenv("CODEX_CMD")?.takeIf { it.isNotBlank() } ?: "codex"

    /** `claude mcp <args...>` 실행 → (exit, output). [cwd] 지정 시 그 디렉토리에서(프로젝트 scope). */
    private fun runClaudeMcp(vararg args: String, cwd: Path? = null, timeoutSec: Long = 30): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(listOf(claudeCmd(), "mcp", *args)).redirectErrorStream(true)
            if (cwd != null) pb.directory(cwd.toFile())
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -2 to "timeout" }
            proc.exitValue() to out
        } catch (e: Throwable) {
            -1 to (e.message ?: "exec failed")
        }
    }

    private fun runCodexMcp(vararg args: String, timeoutSec: Long = 30): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(listOf(codexCmd(), "mcp", *args)).redirectErrorStream(true)
            val userHome = System.getProperty("user.home")
            val codexHome = codexHomeForProcess()
            runCatching { Files.createDirectories(codexHome) }
            pb.environment().putIfAbsent("HOME", userHome)
            pb.environment().putIfAbsent("XDG_CONFIG_HOME", Path.of(userHome, ".config").toString())
            pb.environment()["CODEX_HOME"] = codexHome.toString()
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -2 to "timeout" }
            proc.exitValue() to out
        } catch (e: Throwable) {
            -1 to (e.message ?: "exec failed")
        }
    }

    private fun codexHomeForProcess(): Path =
        SkillRegistry.resolveCodexGlobalRoot(
            configuredHome = System.getenv("CODEX_HOME")?.ifBlank { null },
            userHome = System.getProperty("user.home"),
        ).parent

    /** v1.67.0 — 현재 인식 MCP + 연결 상태. cwd=프로젝트 root 면 user-scope + 그 프로젝트 scope 포함. */
    data class LiveServer(val name: String, val detail: String, val state: String)

    fun liveStatus(cwd: Path?): List<LiveServer> {
        val (_, out) = runClaudeMcp("list", cwd = cwd, timeoutSec = 60)
        if (out.isBlank()) return emptyList()
        // 형식: "<name>: <command/url> - <상태>"  (상태: ✓ Connected / ✗ Failed to connect /
        //        ! Needs authentication / ⏸ Pending approval)
        val re = Regex("""^(.+?):\s+(.*?)\s+-\s+(.+)$""")
        return out.lineSequence().mapNotNull { line ->
            val tline = line.trim()
            if (tline.isEmpty() || tline.startsWith("Checking")) return@mapNotNull null
            val m = re.find(tline) ?: return@mapNotNull null
            val statusText = m.groupValues[3].trim()
            val state = when {
                statusText.contains("Connected", true) -> "connected"
                statusText.contains("Failed", true) -> "failed"
                statusText.contains("authentication", true) -> "auth"
                statusText.contains("Pending", true) -> "pending"
                else -> "unknown"
            }
            LiveServer(m.groupValues[1].trim(), m.groupValues[2].trim(), state)
        }.toList()
    }

    /** user-scope 에 등록(이미 있으면 교체). add-json 의 JSON 은 server 객체 그대로. */
    private fun registerUserScope(name: String, serverJson: JsonObject) {
        runClaudeMcp("remove", name, "-s", "user")  // 멱등 — 없으면 무시
        val json = serverJson.toString()
        val (exit, out) = runClaudeMcp("add-json", name, json, "-s", "user")
        if (exit != 0) log.warn { "claude mcp add-json $name 실패(exit=$exit): ${out.take(300)}" }
    }

    private fun registerMcpServer(name: String, serverJson: JsonObject) {
        registerUserScope(name, serverJson)
        registerCodexScope(name, serverJson)
    }

    private fun registerCodexScope(name: String, serverJson: JsonObject) {
        if (isCodexScopeSynced(name, serverJson)) {
            log.debug { "codex mcp add $name skip: unchanged" }
            return
        }
        val command = (serverJson["command"] as? JsonPrimitive)?.contentOrNull
        if (command.isNullOrBlank()) {
            log.warn { "codex mcp add $name skip: command 없음" }
            return
        }
        val args = (serverJson["args"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val env = (serverJson["env"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            .orEmpty()

        runCodexMcp("remove", name)  // 멱등 — 없으면 무시
        val addArgs = buildList {
            add("add")
            env.forEach { (k, v) -> add("--env"); add("$k=$v") }
            add(name)
            add("--")
            add(command)
            addAll(args)
        }.toTypedArray()
        val (exit, out) = runCodexMcp(*addArgs)
        if (exit != 0) {
            log.warn { "codex mcp add $name 실패(exit=$exit): ${out.take(300)}" }
        } else {
            markCodexScopeSynced(name, serverJson)
        }
    }

    private fun isCodexScopeSynced(name: String, serverJson: JsonObject): Boolean {
        val marker = codexSyncMarkerPath(name)
        if (!marker.exists()) return false
        val config = codexConfigPath()
        if (!config.exists()) return false
        val markerMtime = runCatching { Files.getLastModifiedTime(marker).toMillis() }.getOrDefault(0L)
        val configMtime = runCatching { Files.getLastModifiedTime(config).toMillis() }.getOrDefault(0L)
        if (configMtime > markerMtime) return false
        return runCatching { Files.readString(marker, Charsets.UTF_8) == serverJson.toString() }
            .getOrDefault(false)
    }

    private fun markCodexScopeSynced(name: String, serverJson: JsonObject) {
        runCatching {
            val marker = codexSyncMarkerPath(name)
            Files.createDirectories(marker.parent)
            Files.writeString(
                marker,
                serverJson.toString(),
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }.onFailure { log.debug(it) { "codex mcp sync marker write failed for $name" } }
    }

    private fun codexSyncMarkerPath(name: String): Path =
        codexHomeForProcess()
            .resolve(".vibecoder-mcp-sync")
            .resolve("${sanitizeName(name)}.json")

    private fun codexConfigPath(): Path =
        codexHomeForProcess().resolve("config.toml")

    private fun userConfigPath(): Path = claudeConfigDir().resolve(".claude.json")

    /** 표준 user-scope mcpServers (`.claude.json` top-level). */
    private fun readUserScopeServers(): JsonObject {
        val p = userConfigPath()
        if (!p.exists()) return buildJsonObject {}
        return try {
            val root = Json.parseToJsonElement(Files.readString(p, Charsets.UTF_8)) as? JsonObject
            (root?.get("mcpServers") as? JsonObject) ?: buildJsonObject {}
        } catch (_: Throwable) { buildJsonObject {} }
    }

    /**
     * v1.66.5 — 표준 user-scope 등록 보장(멱등). 매 startup 호출:
     *  - 레거시 `.mcp.json` 의 server 중 user-scope 에 없는 것을 이관(기존 설치 호환).
     *  - first-run 과 이미지 업그레이드 모두에서 defaultInstall MCP 누락분을 보강 등록.
     */
    fun ensureUserScopeRegistration() {
        runCatching {
            val userServers = readUserScopeServers()
            val legacy = readMcpJson()?.get("mcpServers") as? JsonObject ?: buildJsonObject {}
            val migrate = legacy.filterKeys { it !in userServers.keys }
            migrate.forEach { (name, v) -> (v as? JsonObject)?.let { registerMcpServer(name, it) } }
            if (migrate.isNotEmpty()) log.info { "MCP 레거시 .mcp.json → user-scope 이관: ${migrate.keys}" }

            val afterMigration = readUserScopeServers()
            val missingDefaults = McpCatalog.defaultInstallIds.filterNot { it in afterMigration.keys }
            missingDefaults.forEach { id ->
                val e = McpCatalog.get(id) ?: return@forEach
                registerMcpServer(id, buildServerEntryJson(e, emptyMap()))
            }
            if (missingDefaults.isNotEmpty()) {
                log.info { "MCP user-scope 기본 등록/보강: $missingDefaults" }
            }

            // Claude user-scope 에 직접 추가된 MCP 도 Codex 에 동기화한다. Codex 는 Claude 설정을
            // 읽지 않으므로 이 단계가 없으면 Claude 콘솔에서는 보이고 Codex 콘솔에서는 빠진다.
            val refreshedUserServers = readUserScopeServers()
            refreshedUserServers.forEach { (name, v) ->
                (v as? JsonObject)?.let { registerCodexScope(name, it) }
            }

            // v1.68.0 — 카탈로그가 npm → 바이너리로 바뀐 항목(예: gitea: npx @boringstudio → 공식 gitea-mcp)의
            //  stale user-scope 등록 정리. 등록된 command 가 카탈로그 command 와 다르면 제거해
            //  사용자가 올바른 env(GITEA_HOST/ACCESS_TOKEN)로 재설치하도록 NOT_INSTALLED 로 되돌린다.
            val current = readUserScopeServers()
            current.forEach { (name, v) ->
                val e = McpCatalog.get(name) ?: return@forEach
                if (!e.binaryInstall) return@forEach
                val cmd = ((v as? JsonObject)?.get("command") as? JsonPrimitive)?.content
                if (cmd != null && cmd != e.command) {
                    runClaudeMcp("remove", name, "-s", "user")
                    log.info { "MCP stale 등록 정리(바이너리 전환): $name (command=$cmd → 재설치 필요)" }
                }
            }
        }.onFailure { log.warn(it) { "MCP user-scope 등록 보장 실패(무시)" } }
    }

    /** v1.68.0 — binaryInstall 항목용. `command -v <cmd>` 로 PATH 에 실행파일이 있는지. */
    private fun isBinaryOnPath(cmd: String): Boolean {
        return try {
            val pb = ProcessBuilder(listOf("sh", "-c", "command -v ${cmd.replace("'", "")}"))
                .redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) { proc.destroyForcibly(); return false }
            proc.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * v1.137.1 — npm 글로벌 root (`npm root -g`) 1회 해석 캐시.
     *
     * 종전 [isPackageInstalled] 는 항목마다 `npm ls -g` 자식 프로세스를 spawn 했는데
     * npm 1회 기동이 운영 실측 ~500ms 라 카탈로그 65+ 항목 × 직렬 = `/env-setup/mcp`
     * 페이지가 **33~36초** 걸리는 주범이었다 (설정 통합 탭이 이 페이지를 prerender 해
     * 설정 진입 전체가 끌려감). 같은 판정을 root 하위 디렉토리 존재 확인으로 하면 ~5ms.
     */
    private val npmGlobalRoot = java.util.concurrent.atomic.AtomicReference<Path?>(null)

    private fun npmRoot(): Path? {
        npmGlobalRoot.get()?.let { return it }
        val resolved = runCatching {
            val pb = ProcessBuilder(listOf("npm", "root", "-g")).redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) { proc.destroyForcibly(); null }
            else out.lineSequence().lastOrNull { it.startsWith("/") }?.let { Path.of(it) }
        }.getOrNull()
            // npm 자체가 죽어 있어도 컨테이너 표준 prefix 로 폴백 (entrypoint 가 고정).
            ?: System.getProperty("user.home")?.let { Path.of(it, ".local", "lib", "node_modules") }
        if (resolved != null && Files.isDirectory(resolved)) {
            npmGlobalRoot.set(resolved)
            return resolved
        }
        return resolved  // 디렉토리 미존재(글로벌 설치 0개)여도 경로 판정엔 사용 가능. 캐시는 안 함.
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        // v1.137.1 — npm spawn 없이 글로벌 root 하위 패키지 디렉토리 존재로 판정.
        // scoped 패키지(@scope/name)도 디렉토리 구조가 그대로라 resolve 로 동일 처리.
        val root = npmRoot() ?: return false
        return try {
            Files.isRegularFile(root.resolve(pkg).resolve("package.json"))
        } catch (_: Throwable) {
            false
        }
    }

    private fun readMcpJson(): JsonObject? {
        val p = mcpJsonPath()
        if (!p.exists()) return null
        return try {
            val txt = Files.readString(p, Charsets.UTF_8)
            Json.parseToJsonElement(txt) as? JsonObject
        } catch (e: Throwable) {
            // v1.34.2 (20차 BUG-3) — 파일은 존재하나 파싱 실패(사용자 수동 편집의 주석/
            // trailing comma 등). 이전엔 null 반환 → 호출측이 빈 객체로 출발해 writeMcpJsonAtomic
            // 이 손상 파일을 덮어써 기존 mcpServers(타 도구/수동 등록분) 전체 소실.
            // 원본을 .corrupt-<ts> 로 백업해 데이터 보존 후 null. (claude CLI 가 동시
            // 편집하는 파일이라 현실적 위험.)
            val backup = p.resolveSibling("${p.fileName}.corrupt-${System.currentTimeMillis()}")
            runCatching { Files.copy(p, backup) }
                .onSuccess { log.warn(e) { ".mcp.json 파싱 실패 → 원본 백업: $backup (덮어쓰기 전 보존)" } }
                .onFailure { log.warn(e) { ".mcp.json 파싱 실패 + 백업도 실패: $p" } }
            null
        }
    }

    private fun extractConfigValues(
        mcpJson: JsonObject,
        id: String,
        entry: McpCatalog.McpEntry,
    ): Map<String, String> {
        val server = (mcpJson["mcpServers"] as? JsonObject)?.get(id) as? JsonObject
            ?: return emptyMap()
        val env = (server["env"] as? JsonObject)
        val args = (server["args"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .orEmpty()
        val out = mutableMapOf<String, String>()
        for (field: McpCatalog.ConfigField in entry.configFields) {
            // 1) env 안에 있으면 우선
            val envValue = (env?.get(field.key) as? JsonPrimitive)?.contentOrNull
            if (envValue != null) {
                out[field.key] = envValue
                continue
            }
            // 2) args 에 placeholder 로 들어간 경우 — 위치 인덱스로 추적
            val placeholder = "@CONFIG:" + field.key + "@"
            val tokenIdx = entry.argsTemplate.indexOf(placeholder)
            if (tokenIdx in 0 until args.size) {
                out[field.key] = args[tokenIdx]
            }
        }
        return out
    }

    private fun writeMcpJsonAtomic(path: Path, obj: JsonObject) {
        try {
            Files.createDirectories(path.parent)
        } catch (_: Throwable) {}
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        Files.writeString(
            tmp, pretty.encodeToString(JsonObject.serializer(), obj),
            Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        // v1.44.0 — 메서드명("Atomic")과 동작 일치: ATOMIC_MOVE + 미지원 FS fallback
        // (ConfigPersistence gold-standard 패턴과 정렬).
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun mcpJsonPath(): Path = claudeConfigDir().resolve(".mcp.json")

    private companion object {
        /** Secret 파일 최대 크기 — 일반적인 JSON/.p8 키는 수 KB 이내. */
        private const val MAX_SECRET_FILE_BYTES = 128 * 1024
    }

    private fun claudeConfigDir(): Path {
        val explicit = System.getenv("CLAUDE_CONFIG_DIR")?.trim()
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home).resolve(".claude")
    }
}
