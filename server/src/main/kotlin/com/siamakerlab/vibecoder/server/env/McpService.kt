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
 * MCP 카탈로그 설치/제거 + Claude `.mcp.json` 등록.
 *
 * v0.8.0 — 빌드환경 페이지의 MCP UI 가 호출.
 *
 * 설치 메커니즘:
 *  - `npm install -g <pkg>` (vibe 사용자, prefix /home/vibe/.local).
 *    v0.7.0 부터 이 디렉토리가 bind mount 라 이미지 업그레이드 시 보존.
 *  - 설치 완료 후 `~/.claude/.mcp.json` 의 `mcpServers` 에 entry 추가.
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
        (readMcpJson()?.get("mcpServers") as? JsonObject)?.keys?.sorted().orEmpty()

    /** v1.35.0 — 전역 `.mcp.json` 경로 (UI 표시용). */
    fun globalMcpJsonPath(): Path = mcpJsonPath()

    /**
     * v1.37.0 — 도커 **첫 설치(first-run)** 시 기본 MCP 를 자동 등록. 영속 볼륨(`~/.claude`)에
     * marker 파일을 두어 **딱 한 번만** 수행한다. 기본 대상은 [McpCatalog.defaultInstallIds]
     * 가 단일 출처 — v1.40.2 기준 fetch/memory/sequential-thinking/context7/playwright 5개
     * (모두 zero-config: 인증/필수 configField 없음).
     *
     * - marker 존재(= 이미지 업데이트 등 재부팅) → no-op. **사용자 선택 절대 변경 안 함.**
     * - 기존 `.mcp.json` 에 server 가 이미 있으면(구버전에서 업그레이드) → 등록 skip + marker 만
     *   기록(사용자 선택 보존).
     * - 진짜 fresh(볼륨 비어있음) → 기본 MCP 를 `.mcp.json` 에 등록 + marker.
     *
     * npm install 은 하지 않는다 — argsTemplate 이 `npx -y <pkg>` 라 Claude 가 첫 사용 시
     * 자동 fetch. 따라서 부팅 시 네트워크 의존/실패 위험 없음(파일 쓰기만).
     */
    fun bootstrapDefaultsIfFirstRun() {
        val marker = claudeConfigDir().resolve(".vibecoder-mcp-bootstrapped")
        if (Files.exists(marker)) return
        try {
            val current = readMcpJson()
            val existing = current?.get("mcpServers") as? JsonObject
            if (existing != null && existing.isNotEmpty()) {
                log.info { "MCP bootstrap: 기존 .mcp.json server ${existing.keys} 존재 → 자동 등록 skip (사용자 선택 보존)" }
            } else {
                var obj: JsonObject = current ?: buildJsonObject { put("mcpServers", buildJsonObject {}) }
                val ids = McpCatalog.defaultInstallIds
                for (id in ids) {
                    val entry = McpCatalog.get(id) ?: continue
                    obj = registerInMcpJson(obj, entry, emptyMap())
                }
                writeMcpJsonAtomic(mcpJsonPath(), obj)
                log.info { "MCP bootstrap (first-run): 기본 MCP 등록 $ids → ${mcpJsonPath()}" }
            }
            Files.createDirectories(marker.parent)
            Files.writeString(marker, clock.nowIso())
        } catch (e: Throwable) {
            log.warn(e) { "MCP 기본 부트스트랩 실패 (무시 — 카탈로그에서 수동 설치 가능)" }
        }
    }

    fun detect(id: String): EntryState {
        val e = McpCatalog.get(id) ?: return EntryState(id, Status.UNKNOWN, "카탈로그에 없음")
        val installed = isPackageInstalled(e.pkg)
        val mcpJson = readMcpJson()
        val registered = mcpJson?.let { it["mcpServers"] as? JsonObject }?.containsKey(id) == true
        val configValues = if (registered) extractConfigValues(mcpJson!!, id, e) else emptyMap()
        val status = when {
            installed && registered -> Status.INSTALLED
            registered -> Status.REGISTERED_ONLY
            else -> Status.NOT_INSTALLED
        }
        val msg = when (status) {
            Status.INSTALLED -> "설치됨 + 등록됨"
            Status.REGISTERED_ONLY -> ".mcp.json 에 등록되어 있으나 npm 패키지 미설치"
            Status.NOT_INSTALLED -> "미설치"
            Status.UNKNOWN -> "확인 불가"
        }
        return EntryState(id, status, msg, configValues)
    }

    /**
     * 선택된 MCP 들을 일괄 설치 — npm install -g + .mcp.json 갱신.
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
                    "✓ MCP 설치 완료 — Claude 콘솔에서 즉시 사용 가능", clock.nowIso()))
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
     * .mcp.json 의 entry 만 제거 (npm 패키지는 남김).
     * 완전 삭제하려면 docker exec 로 `npm uninstall -g <pkg>` 직접 실행 안내.
     */
    fun unregister(ids: List<String>) {
        if (ids.isEmpty()) return
        val mcpPath = mcpJsonPath()
        val current = readMcpJson() ?: buildJsonObject { put("mcpServers", buildJsonObject {}) }
        val servers = (current["mcpServers"] as? JsonObject) ?: buildJsonObject {}
        val newServers = buildJsonObject {
            servers.forEach { (k, v) -> if (k !in ids) put(k, v) }
        }
        val updated = buildJsonObject {
            current.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
            put("mcpServers", newServers)
        }
        writeMcpJsonAtomic(mcpPath, updated)
        log.info { "unregistered MCP entries: $ids" }
    }

    // ─────────────────────────────────────────────────────────────────
    // 내부 — npm + .mcp.json
    // ─────────────────────────────────────────────────────────────────

    private suspend fun runBatch(
        taskId: String,
        selections: Map<String, Map<String, String>>,
    ) {
        val mcpPath = mcpJsonPath()
        // 누적 mcp.json 시작점 (기존 파일 보존)
        val initial = readMcpJson() ?: buildJsonObject { put("mcpServers", buildJsonObject {}) }
        var current: JsonObject = initial

        var idx = 0
        for ((id, cfg) in selections) {
            idx++
            val entry = McpCatalog.get(id)!!
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                "── [$idx/${selections.size}] ${entry.displayName} (${entry.pkg})",
                clock.nowIso()))

            // 1) npm install -g <pkg>
            val installExit = runNpmInstall(taskId, entry.pkg)
            if (installExit != 0) {
                throw RuntimeException("npm install -g ${entry.pkg} → exit $installExit")
            }

            // 2) .mcp.json entry 갱신 (매번 atomic write — 도중 실패해도 직전까지 보존)
            current = registerInMcpJson(current, entry, cfg)
            writeMcpJsonAtomic(mcpPath, current)
            hub.publisher(taskId).emit(WsFrame.Log(taskId, "INFO",
                "  ✓ ${entry.displayName} 설치 + .mcp.json 등록", clock.nowIso()))
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

    private fun registerInMcpJson(
        current: JsonObject,
        entry: McpCatalog.McpEntry,
        cfg: Map<String, String>,
    ): JsonObject {
        // args 치환: @PKG@ → 실제 패키지명, @CONFIG:key@ → cfg[key]
        val args = entry.argsTemplate.map { arg ->
            arg.replace("@PKG@", entry.pkg).let { a ->
                Regex("@CONFIG:([^@]+)@").replace(a) { m ->
                    cfg[m.groupValues[1]].orEmpty()
                }
            }
        }
        // env: 비밀값 + 비 args 사용 config 모두. argsTemplate 에 등장한 키는 env 에서 제외 (중복 방지).
        val argTokens = entry.argsTemplate.joinToString(" ")
        val envEntries = entry.configFields
            .filter { f ->
                val v = cfg[f.key].orEmpty()
                v.isNotEmpty() && !argTokens.contains("@CONFIG:${f.key}@")
            }
            .associate { it.key to cfg[it.key]!! }

        val serverEntry = buildJsonObject {
            put("command", entry.command)
            put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
            if (envEntries.isNotEmpty()) {
                put("env", buildJsonObject {
                    envEntries.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                })
            }
        }
        val mcpServers = (current["mcpServers"] as? JsonObject) ?: buildJsonObject {}
        val newServers = buildJsonObject {
            mcpServers.forEach { (k, v) -> if (k != entry.id) put(k, v) }
            put(entry.id, serverEntry)
        }
        return buildJsonObject {
            current.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
            put("mcpServers", newServers)
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        // `npm ls -g --depth=0 --json` 으로 빠르게 확인. 호출 비용 줄이려면 cache 가능하지만 일단 단순.
        return try {
            val pb = ProcessBuilder(listOf("npm", "ls", "-g", "--depth=0", "--json", pkg))
                .redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return false
            }
            out.contains("\"$pkg\"")
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
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
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
