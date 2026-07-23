package com.siamakerlab.vibecoder.server.ios

import com.siamakerlab.vibecoder.server.admin.SshKeyService
import com.siamakerlab.vibecoder.server.config.ConfigHolder
import com.siamakerlab.vibecoder.server.config.ConfigPersistence
import com.siamakerlab.vibecoder.server.config.IosAgentSection
import com.siamakerlab.vibecoder.server.config.ServerConfig
import com.siamakerlab.vibecoder.shared.dto.IosAgentConnectRequestDto
import com.siamakerlab.vibecoder.shared.dto.IosAgentConnectResultDto
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.167.0 — 맥 SSH 에이전트 비밀번호 원클릭 부트스트랩.
 *
 * 흐름(설정/빌드환경의 "연결하기"):
 *  1. `sshpass -e ssh`(비번은 env `SSHPASS` 로만 전달 — argv/ps 노출 없음)로 맥에 접속.
 *  2. 컨테이너 공개키(`~/.ssh/id_ed25519.pub`)를 맥 `~/.ssh/authorized_keys` 에 설치(중복 방지).
 *  3. `~/.vibe-coder-mac/{,signing}` 생성 + 맥 홈 조회 + rsync 존재 확인.
 *  4. 키 인증 검증(`ssh -o BatchMode=yes`, 비번 없이) → 이후 build/simulator 의 키 기반 SSH 동작 확인.
 *  5. agent config 저장(enabled=true, mode=ssh, host/port/user, workspaceRoot=`<home>/.vibe-coder-mac`).
 *  6. preflight(Xcode/simctl/서명) 실행 후 결과 반환.
 *
 * 비밀번호는 부트스트랩 1회에만 쓰이고 **저장/로그되지 않는다** (이후 키 기반 SSH 로 동작).
 * sshpass 미설치(로컬 dev 등)면 `sshpass_missing` 으로 친화 실패.
 */
class IosAgentBootstrapService(
    private val sshKey: SshKeyService = SshKeyService(),
    private val onConfigSaved: ((ServerConfig) -> Unit)? = null,
) {
    fun connect(req: IosAgentConnectRequestDto): IosAgentConnectResultDto {
        val host = req.host.trim()
        val user = req.user.trim()
        val port = req.port
        val password = req.password
        val xcodePath = req.xcodePath.trim().ifBlank { "auto" }

        if (!HOST_RE.matches(host)) return fail("bootstrap_failed", "호스트 형식이 올바르지 않습니다.")
        if (!USER_RE.matches(user)) return fail("bootstrap_failed", "사용자명 형식이 올바르지 않습니다.")
        if (port !in 1..65535) return fail("bootstrap_failed", "포트는 1~65535 범위여야 합니다.")
        if (password.isEmpty()) return fail("wrong_password", "비밀번호가 비어 있습니다.")

        val pub = sshKey.snapshot()?.publicKey?.trim()
        if (pub.isNullOrBlank()) {
            return fail("no_container_key", "컨테이너 SSH 공개키가 없습니다. (entrypoint 미실행 / 로컬 dev)")
        }
        if (pub.contains('\'')) {
            return fail("no_container_key", "컨테이너 공개키에 예기치 않은 문자가 있습니다.")
        }

        // 여러 vibe-coder 서버가 한 맥을 공유해도 충돌 없도록 서버별 고유 id 로 작업공간 분리.
        // id 는 컨테이너 SSH 공개키에서 파생(키는 서버마다 고유·영속) → 안정적·유일.
        val serverId = serverId(pub)

        // 1~3. sshpass 로 접속하며 키 설치 + 서버별 디렉터리 준비 + 홈/rsync 리포트를 한 번에.
        val boot = runWithPassword(sshpassArgv(host, port, user, remoteBootstrapScript(pub, serverId)), password, BOOT_TIMEOUT_SEC)
        when {
            boot.notRun -> return fail("sshpass_missing", "컨테이너에 sshpass 가 설치돼 있지 않습니다.")
            boot.exit == SSHPASS_WRONG_PASSWORD -> return fail("wrong_password", "비밀번호가 올바르지 않습니다.")
            boot.exit == SSH_UNREACHABLE -> return fail(
                "unreachable",
                "맥에 접속할 수 없습니다 — 호스트/포트를 확인하고, 맥에서 '시스템 설정 → 일반 → 공유 → 원격 로그인(Remote Login)'을 켜세요.",
            )
            boot.exit != 0 || !boot.out.contains("VC_OK=1") ->
                return fail("bootstrap_failed", (boot.err.ifBlank { boot.out }).trim().take(300).ifBlank { "부트스트랩 실패 (exit=${boot.exit})" })
        }

        val homeDir = REPORT_HOME.find(boot.out)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val rsyncAvailable = boot.out.contains("VC_RSYNC=yes")
        // 서버별 격리: <home>/.vibe-coder-mac/<serverId> 를 workspaceRoot 로 → 기존 sync/build/simulator
        // 코드(모두 workspaceRoot/<projectId>/ 사용)가 자동으로 서버 단위로 분리된다.
        val workspaceRoot = (homeDir?.trimEnd('/') ?: "").let { if (it.isNotBlank()) "$it/.vibe-coder-mac/$serverId" else "" }
        if (workspaceRoot.isBlank() || !workspaceRoot.startsWith("/")) {
            return fail("bootstrap_failed", "맥 홈 디렉터리를 확인하지 못했습니다.")
        }

        // 4. 키 인증(비번 없이) 검증.
        val verify = ProcessCommandRunner.run(
            listOf(
                "ssh", "-p", port.toString(),
                "-o", "BatchMode=yes",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "ConnectTimeout=10",
                "$user@$host", "echo $KEY_OK_TOKEN",
            ),
            java.time.Duration.ofSeconds(15),
        )
        val keyAuthVerified = verify.ok && verify.stdout.contains(KEY_OK_TOKEN)

        // 5. config 저장 (비밀번호는 저장하지 않음).
        val newAgent = IosAgentSection(
            enabled = true, mode = "ssh", host = host, port = port, user = user,
            workspaceRoot = workspaceRoot, xcodePath = xcodePath,
        )
        runCatching {
            val cur = ConfigHolder.current
            val newConfig = cur.copy(ios = cur.ios.copy(agent = newAgent))
            ConfigPersistence.save(newConfig)
            onConfigSaved?.invoke(newConfig) ?: ConfigHolder.update(newConfig)
        }.onFailure {
            log.warn(it) { "ios agent connect: config 저장 실패" }
            return fail("bootstrap_failed", "설정 저장에 실패했습니다: ${it.message}")
        }

        // 6. preflight — 방금 저장한 config 로 직접 판정(ConfigHolder 반영 타이밍 무관).
        val preflight = runCatching {
            IosPreflightService(agentConfigProvider = { newAgent }).check()
        }.getOrNull()

        log.info { "ios agent connected: $user@$host:$port workspaceRoot=$workspaceRoot keyAuth=$keyAuthVerified" }
        return IosAgentConnectResultDto(
            connected = true,
            keyInstalled = true,
            keyAuthVerified = keyAuthVerified,
            rsyncAvailable = rsyncAvailable,
            homeDir = homeDir,
            workspaceRoot = workspaceRoot,
            failureReason = if (keyAuthVerified) null else "key_auth_unverified",
            message = if (keyAuthVerified) null
            else "연결·키 설치는 됐지만 키 기반 재접속 검증에 실패했습니다. 잠시 후 '연결하기'를 다시 눌러 주세요.",
            preflight = preflight,
        )
    }

    private fun fail(reason: String, message: String) =
        IosAgentConnectResultDto(connected = false, failureReason = reason, message = message)

    /** 맥에서 실행할 부트스트랩 스크립트. pub 은 단일따옴표로 안전 임베드(pub 에 `'` 없음 보장됨). */
    private fun remoteBootstrapScript(pub: String, serverId: String): String = buildString {
        append("set -e; umask 077; ")
        append("mkdir -p \"\$HOME/.ssh\"; touch \"\$HOME/.ssh/authorized_keys\"; ")
        append("chmod 700 \"\$HOME/.ssh\" 2>/dev/null || true; chmod 600 \"\$HOME/.ssh/authorized_keys\" 2>/dev/null || true; ")
        append("PUB='").append(pub).append("'; ")
        append("if ! grep -qF \"\$PUB\" \"\$HOME/.ssh/authorized_keys\" 2>/dev/null; then printf '%s\\n' \"\$PUB\" >> \"\$HOME/.ssh/authorized_keys\"; fi; ")
        // 서버별 격리 작업공간 + 서명 자산 디렉터리.
        append("mkdir -p \"\$HOME/.vibe-coder-mac/").append(serverId).append("/signing\"; ")
        append("printf 'VC_HOME=%s\\n' \"\$HOME\"; ")
        append("if command -v rsync >/dev/null 2>&1; then printf 'VC_RSYNC=yes\\n'; else printf 'VC_RSYNC=no\\n'; fi; ")
        append("printf 'VC_OK=1\\n'")
    }

    /** 컨테이너 SSH 공개키에서 파생한 안정적·유일한 서버 id (파일시스템 안전). */
    private fun serverId(pub: String): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pub.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "srv-" + hex.take(12)
    }

    private fun sshpassArgv(host: String, port: Int, user: String, remoteScript: String): List<String> = listOf(
        "sshpass", "-e", "ssh",
        "-p", port.toString(),
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=10",
        "-o", "PubkeyAuthentication=no",
        "-o", "PreferredAuthentications=password,keyboard-interactive",
        "-o", "NumberOfPasswordPrompts=1",
        "$user@$host", remoteScript,
    )

    /** sshpass 를 env `SSHPASS` 로 실행. 비밀번호는 argv/로그에 남기지 않는다. */
    private fun runWithPassword(argv: List<String>, password: String, timeoutSec: Long): BootResult {
        val pb = ProcessBuilder(argv).redirectErrorStream(false)
        pb.environment()["SSHPASS"] = password
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            log.warn { "sshpass 실행 불가: ${e.message}" }
            return BootResult(notRun = true)
        }
        var out = ""
        var err = ""
        val outReader = Thread { out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val errReader = Thread { err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }
        outReader.isDaemon = true; errReader.isDaemon = true
        outReader.start(); errReader.start()
        val done = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            proc.waitFor(2, TimeUnit.SECONDS)
            outReader.join(1000); errReader.join(1000)
            return BootResult(exit = SSH_UNREACHABLE, out = out, err = err.ifBlank { "timeout" })
        }
        outReader.join(1500); errReader.join(1500)
        return BootResult(exit = proc.exitValue(), out = out, err = err)
    }

    private data class BootResult(
        val exit: Int = -1,
        val out: String = "",
        val err: String = "",
        val notRun: Boolean = false,
    )

    companion object {
        private val HOST_RE = Regex("^[A-Za-z0-9._-]{1,253}$")
        private val USER_RE = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val REPORT_HOME = Regex("""VC_HOME=(.+)""")
        private const val KEY_OK_TOKEN = "vc-key-ok"
        private const val BOOT_TIMEOUT_SEC = 30L
        private const val SSHPASS_WRONG_PASSWORD = 5
        private const val SSH_UNREACHABLE = 255
    }
}
