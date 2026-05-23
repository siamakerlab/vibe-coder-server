package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * 신규 프로젝트 등록 시 `git clone` 실행 + SSH 키 자동 생성 관리 — v0.9.0.
 *
 * 사용자 시나리오:
 *  1. **Public HTTPS** — `https://github.com/foo/bar.git` 형태. 인증 불요.
 *  2. **Private HTTPS** — 같은 URL 이지만 토큰 필요. [GitCredentialStore] 에
 *     해당 host 의 토큰이 등록되어 있으면 git CLI 가 ~/.git-credentials 에서
 *     자동 픽업 (별도 인자 불요).
 *  3. **Private SSH** — `git@github.com:foo/bar.git` 형태. [ensureSshKeyExists]
 *     가 vibe 사용자의 `~/.ssh/id_ed25519` 가 없으면 자동 생성하고, 사용자가
 *     공개키를 GitHub/GitLab 등에 등록해 둠. clone 은 ssh-agent 없이 키 파일
 *     직접 사용.
 *
 * URL 자동 검증 + 위험 패턴 (예: `file://`, 로컬 `..` traversal) 거부.
 * 결과 폴더가 비어 있지 않으면 거부 (덮어쓰기 방지).
 */
class GitCloneService(
    private val credentials: GitCredentialStore,
) {

    /**
     * Git 호스팅 공급자별 SSH 호스트키 사전 인증.
     * 첫 clone 에서 host key 프롬프트가 stdin 대기로 hang 되는 것을 방지.
     * `ssh-keyscan` 으로 host key 를 받아 known_hosts 에 사전 추가.
     */
    private val knownHosts = listOf("github.com", "gitlab.com", "bitbucket.org")

    /**
     * vibe 사용자의 SSH 키가 없으면 생성. ed25519 (4096-bit RSA 보다 짧고 안전).
     * @return 공개키 한 줄 (사용자가 GitHub/GitLab 등에 등록).
     */
    fun ensureSshKeyExists(): String {
        val sshDir = userHome().resolve(".ssh")
        try {
            Files.createDirectories(sshDir)
            runCatching {
                Files.setPosixFilePermissions(sshDir,
                    setOf(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE))
            }
        } catch (e: Throwable) {
            throw ApiException(500, "ssh_dir", "~/.ssh 디렉토리 생성 실패: ${e.message}")
        }
        val privKey = sshDir.resolve("id_ed25519")
        val pubKey = sshDir.resolve("id_ed25519.pub")
        if (!privKey.exists()) {
            log.info { "SSH 키 자동 생성: $privKey" }
            val cmd = listOf("ssh-keygen", "-t", "ed25519", "-f", privKey.toString(),
                "-N", "", "-q", "-C", "vibe-coder")
            val proc = try {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            } catch (e: Throwable) {
                throw ApiException(500, "ssh_keygen", "ssh-keygen 실행 실패: ${e.message}")
            }
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                throw ApiException(500, "ssh_keygen_timeout", "ssh-keygen 시간 초과")
            }
            if (proc.exitValue() != 0) {
                val out = proc.inputStream.bufferedReader().readText()
                throw ApiException(500, "ssh_keygen_fail", "ssh-keygen exit ${proc.exitValue()}: $out")
            }
            runCatching {
                Files.setPosixFilePermissions(privKey,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
        }
        return Files.readString(pubKey, Charsets.UTF_8).trim()
    }

    fun getPublicKeyOrNull(): String? {
        val pubKey = userHome().resolve(".ssh").resolve("id_ed25519.pub")
        return if (pubKey.exists()) Files.readString(pubKey, Charsets.UTF_8).trim() else null
    }

    /**
     * known_hosts 에 공식 git 호스팅 host key 추가 (idempotent).
     * 컨테이너 첫 부팅 직후 한 번 호출 — clone 시 host key 프롬프트 hang 방지.
     */
    fun ensureKnownHosts() {
        val sshDir = userHome().resolve(".ssh")
        try { Files.createDirectories(sshDir) } catch (_: Throwable) {}
        val knownHostsFile = sshDir.resolve("known_hosts")
        val existing = if (knownHostsFile.exists())
            Files.readString(knownHostsFile, Charsets.UTF_8) else ""

        val toScan = knownHosts.filter { host -> !existing.contains(host) }
        if (toScan.isEmpty()) return

        toScan.forEach { host ->
            runCatching {
                val cmd = listOf("ssh-keyscan", "-T", "5", host)
                val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
                val out = proc.inputStream.bufferedReader().readText()
                if (proc.waitFor(8, TimeUnit.SECONDS) && proc.exitValue() == 0 && out.isNotBlank()) {
                    Files.writeString(knownHostsFile, out,
                        Charsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND)
                }
            }.onFailure {
                log.warn { "ssh-keyscan $host 실패: ${it.message}" }
            }
        }
    }

    /**
     * `git clone <url> <targetDir>` 실행. 결과 stdout/stderr 라인을 콜백으로 stream.
     *
     * @throws ApiException URL 검증 실패 / clone 실패 / 디렉토리 비어 있지 않음.
     */
    fun clone(
        url: String,
        targetDir: Path,
        branch: String?,
        onLog: (String) -> Unit = {},
    ) {
        val cleanUrl = url.trim()
        validateUrl(cleanUrl)
        if (targetDir.exists() && Files.list(targetDir).use { it.findFirst().isPresent }) {
            throw ApiException(409, "target_not_empty",
                "타겟 디렉토리가 비어 있지 않습니다: $targetDir")
        }
        Files.createDirectories(targetDir.parent)

        // SSH URL 이면 known_hosts 사전 보장.
        if (cleanUrl.startsWith("git@") || cleanUrl.startsWith("ssh://")) {
            ensureKnownHosts()
        }

        val args = buildList {
            add("git")
            add("clone")
            add("--progress")
            if (!branch.isNullOrBlank()) {
                add("--branch"); add(branch)
            }
            add(cleanUrl)
            add(targetDir.toString())
        }
        onLog("$ ${args.joinToString(" ")}")

        val env = mutableMapOf<String, String>()
        // vibe-coder 비인터랙티브 환경 — stdin prompt 시 즉시 실패하도록.
        env["GIT_TERMINAL_PROMPT"] = "0"
        // SSH: 호스트 키 자동 수락 (이미 known_hosts 있으면 안전; 없으면 즉시 추가)
        env["GIT_SSH_COMMAND"] = "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes"

        val pb = ProcessBuilder(args).redirectErrorStream(true)
        env.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            throw ApiException(500, "spawn_fail", "git clone 실행 실패: ${e.message}")
        }
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach(onLog)
        }
        if (!proc.waitFor(10, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            throw ApiException(504, "timeout", "git clone 10분 초과 — 네트워크/저장소 크기 확인.")
        }
        val exit = proc.exitValue()
        if (exit != 0) {
            // 실패한 클론은 부분 파일이 남아있을 수 있음 — 정리.
            runCatching {
                if (targetDir.exists()) {
                    Files.walk(targetDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            }
            throw ApiException(502, "clone_failed",
                "git clone exit $exit — URL/권한/네트워크를 확인하세요. " +
                    "private 레포면 환경설정에서 PAT 등록 (HTTPS) 또는 SSH 공개키 등록 (SSH URL).")
        }
    }

    private fun validateUrl(url: String) {
        if (url.isEmpty()) throw ApiException(400, "empty_url", "clone URL 이 비어 있습니다.")
        when {
            url.startsWith("https://") -> { /* ok */ }
            url.startsWith("http://") -> { /* ok — 신뢰는 사용자 책임 */ }
            url.startsWith("git@") && url.contains(':') -> { /* SSH form */ }
            url.startsWith("ssh://") -> { /* ok */ }
            else -> throw ApiException(400, "bad_url_scheme",
                "지원하지 않는 URL 형식. https:// / http:// / git@host:owner/repo / ssh:// 만 허용.")
        }
        // file:// / 로컬 경로 차단 (워크스페이스 탈출 방지)
        if (url.contains("file://") || url.contains("..")) {
            throw ApiException(400, "unsafe_url", "안전하지 않은 URL 패턴입니다.")
        }
    }

    private fun userHome(): Path {
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: "."
        return Path.of(home)
    }
}
