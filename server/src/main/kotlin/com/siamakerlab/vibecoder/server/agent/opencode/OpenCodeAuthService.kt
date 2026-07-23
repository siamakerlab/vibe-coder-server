package com.siamakerlab.vibecoder.server.agent.opencode

import com.siamakerlab.vibecoder.server.core.OsType
import com.siamakerlab.vibecoder.shared.dto.OpenCodeUsageDto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

private fun defaultOpenCodeCmd(): String =
    System.getenv("OPENCODE_CMD")?.takeIf { it.isNotBlank() }
        ?: if (OsType.detect() == OsType.WINDOWS) "opencode.cmd" else "opencode"

/**
 * v1.151.0 — OpenCode 자격증명 관리. `opencode providers list` 출력을 파싱해 등록된
 * credential(provider + type) 목록을 제공. [OpenCodeStatusService] 가 로그인 상태/사용 가능
 * 여부를 판정하는 데 사용.
 *
 * [com.siamakerlab.vibecoder.server.claude.ClaudeAuthService] 와 대칭이나, opencode 는
 * `providers list` CLI 출력(ANSI 박스)을 파싱한다 (별도 auth 파일 직독 대신). auth.json 직접
 * 접근은 민감정보 노출 risk 로 피한다.
 */
class OpenCodeAuthService(
    private val opencodeCmdProvider: () -> String = { defaultOpenCodeCmd() },
) {
    /** 단일 credential (provider 표시명 + type). */
    data class Credential(val provider: String, val type: String)

    /** 로그인 상태 (credential ≥ 1). [runProvidersList] 실행. */
    suspend fun isLoggedIn(): Boolean = listCredentials().isNotEmpty()

    /** 등록된 credential 목록. 실패 시 빈 리스트. */
    suspend fun listCredentials(): List<Credential> = runCatching {
        val raw = runProvidersList()
        parseOpenCodeProvidersList(raw)
    }.onFailure { log.debug(it) { "opencode providers list 실패: ${it.message}" } }.getOrDefault(emptyList())

    /** `opencode providers list` 실행 (타임아웃 8s). */
    suspend fun runProvidersList(): String = withContext(Dispatchers.IO) {
        val cmd = opencodeCmdProvider()
        if (!isOpenCodeCommandAvailable(cmd)) return@withContext ""
        val pb = ProcessBuilder(cmd, "providers", "list")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        applyOpenCodeProcessEnv(pb)
        runWithHardTimeout(pb, timeoutSeconds = 8)
    }

    private fun runWithHardTimeout(pb: ProcessBuilder, timeoutSeconds: Long): String =
        runCatching {
            val proc = pb.start()
            val sb = StringBuilder()
            val done = CountDownLatch(1)
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            synchronized(sb) { sb.append(line).append('\n') }
                        }
                    }
                }
                done.countDown()
            }.apply { isDaemon = true; start() }
            proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (proc.isAlive) {
                runCatching { proc.descendants().forEach { it.destroyForcibly() } }
                proc.destroyForcibly()
            }
            done.await(1500, TimeUnit.MILLISECONDS)
            synchronized(sb) { sb.toString() }
        }.getOrDefault("")
}

/**
 * v1.151.0 — `opencode providers list` ANSI 출력에서 credential 라인을 추출 (순수 함수).
 * 출력 형식:
 * ```
 * ┌  Credentials ~/.local/share/opencode/auth.json
 * │
 * ●  Z.AI Coding Plan api
 * │
 * └  1 credentials
 * ```
 * `●`(등록됨)/`○`(미등록) 마커 라인에서 마지막 토큰 = type, 나머지 = provider 표시명.
 */
internal fun parseOpenCodeProvidersList(raw: String): List<OpenCodeAuthService.Credential> {
    val cleaned = stripOpenCodeAnsi(raw)
    return cleaned.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("●") || it.startsWith("○") }
        .mapNotNull { line ->
            val body = line.dropWhile { it == '●' || it == '○' }.trim()
            if (body.isEmpty()) return@mapNotNull null
            val idx = body.lastIndexOf(' ')
            if (idx <= 0) {
                OpenCodeAuthService.Credential(body, "credential")
            } else {
                OpenCodeAuthService.Credential(body.substring(0, idx).trim(), body.substring(idx + 1).trim())
            }
        }
        .filter { it.provider.isNotBlank() }
        .toList()
}

internal fun stripOpenCodeAnsi(text: String): String =
    Regex("\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)").replace(text, "")

internal fun isOpenCodeCommandAvailable(cmd: String): Boolean {
    if (cmd.isBlank()) return false
    if (cmd.contains('/') || cmd.contains('\\')) {
        return java.nio.file.Files.isExecutable(java.nio.file.Path.of(cmd))
    }
    val path = System.getenv("PATH").orEmpty()
    if (path.isBlank()) return false
    val extensions = if (OsType.detect() == OsType.WINDOWS) {
        System.getenv("PATHEXT").orEmpty()
            .split(';')
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(".exe", ".cmd", ".bat") }
    } else {
        listOf("")
    }
    return path.split(File.pathSeparatorChar).any { dir ->
        if (dir.isBlank()) return@any false
        extensions.any { ext ->
            java.nio.file.Files.isExecutable(java.nio.file.Path.of(dir, cmd + ext))
        }
    }
}

/** v1.151.0 — env 주입 공통 (OpenCodeAuthService / OpenCodeStatusService 공유).
 * v1.156.1 — putIfAbsent → put 강제 설정 (컨테이너 HOME=/root 회피). */
internal fun applyOpenCodeProcessEnv(pb: ProcessBuilder) {
    pb.environment()["HOME"] = "/home/vibe"
    pb.environment()["XDG_CONFIG_HOME"] = "/home/vibe/.config"
    pb.environment()["XDG_DATA_HOME"] = "/home/vibe/.local/share"
}

/** v1.151.0 — [OpenCodeAuthService.Credential] → [OpenCodeUsageDto] credential 필드 반영 (순수 함수). */
internal fun OpenCodeUsageDto.withCredential(cred: OpenCodeAuthService.Credential?): OpenCodeUsageDto =
    copy(
        loggedIn = cred != null,
        provider = cred?.provider,
        credentialType = cred?.type,
        available = cred != null,
    )
