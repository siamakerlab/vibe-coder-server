package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v0.18.0 — Git write 작업 (add / commit / push).
 *
 * **운영 원칙 (CLAUDE.md §3 보안 정책 준수)**:
 *  - raw shell 노출 없음. ProcessBuilder 의 List<String> 으로만 인자 전달.
 *  - URL / branch / message 등 사용자 입력 검증.
 *  - 같은 PathSafety / WorkspacePath 경유로 외부 경로 작업 차단 (호출자가 보장).
 *  - **push 는 git CLI 가 ~/.git-credentials (HTTPS 토큰) 또는 ~/.ssh/id_ed25519 (SSH)
 *    를 자동 사용. 별도 자격증명 처리 없음**.
 *
 * 새 commit 만 만들고 amend 는 미지원 (사용자가 정말 amend 가 필요하면 콘솔에서
 * Claude 에게 부탁 — 본 API 가 amend 노출은 위험 (force push 우려)).
 */
class GitWriter {

    data class CommitPushResult(
        val committed: Boolean,
        val pushed: Boolean,
        val branch: String,
        val sha: String?,
        val log: String,
    )

    /**
     * source 디렉토리에서 `git add -A` + `git commit -m <message>` + (push 옵션이면)
     * `git push origin <branch>` 를 순차 실행.
     *
     * @param onlyTracked true 면 `git add -A` 대신 `git add -u` (tracked 파일만 stage).
     * @param push        true 면 push 까지. 실패해도 commit 은 유지 (사용자가 재시도 가능).
     */
    fun commitAndPush(
        source: Path,
        message: String,
        push: Boolean = true,
        onlyTracked: Boolean = false,
    ): CommitPushResult {
        require(message.isNotBlank()) { "commit message is required" }
        require(message.length <= MAX_MESSAGE_LEN) { "commit message too long (max $MAX_MESSAGE_LEN chars)" }

        val sb = StringBuilder()
        // 0) 현재 branch 확인
        val branch = run(source, listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), sb)
            .trim().ifBlank {
                throw ApiException.localized(500, "git_not_a_repo", messageKey = "api.gitWriter.notARepo", args = listOf(source.toString()))
            }

        // 1) git add
        val addArgs = if (onlyTracked) listOf("git", "add", "-u") else listOf("git", "add", "-A")
        run(source, addArgs, sb, allowFail = false)

        // 2) status check — 변경 없으면 commit skip
        val statusOut = run(source, listOf("git", "status", "--porcelain"), sb).trim()
        if (statusOut.isEmpty()) {
            sb.appendLine("(no changes to commit)")
            return CommitPushResult(committed = false, pushed = false, branch = branch,
                sha = null, log = sb.toString())
        }

        // 3) git commit
        // 사용자 git config 가 비어 있을 수 있어 명시적 author/email 환경변수 주입.
        val env = mutableMapOf<String, String>()
        env["GIT_AUTHOR_NAME"] = System.getenv("VIBECODER_GIT_AUTHOR_NAME") ?: "vibe-coder"
        env["GIT_AUTHOR_EMAIL"] = System.getenv("VIBECODER_GIT_AUTHOR_EMAIL") ?: "vibe-coder@localhost"
        env["GIT_COMMITTER_NAME"] = env["GIT_AUTHOR_NAME"]!!
        env["GIT_COMMITTER_EMAIL"] = env["GIT_AUTHOR_EMAIL"]!!
        run(source, listOf("git", "commit", "-m", message), sb, env = env, allowFail = false)

        val sha = run(source, listOf("git", "rev-parse", "HEAD"), sb).trim().take(40)

        // 4) push (옵션). 실패해도 commit 은 유지.
        var pushed = false
        if (push) {
            // v1.31.1 (B-Q4) — push 전 origin scheme 검증. Claude/콘솔에서 origin 을
            // file:// 등 로컬/비신뢰 scheme 으로 바꿔치기한 뒤 push 하면 자격증명
            // (~/.git-credentials) 이나 내용이 유출될 수 있어, https/http/ssh/git 만 허용.
            val originUrl = run(source, listOf("git", "remote", "get-url", "origin"), sb).trim()
            if (!isAllowedPushUrl(originUrl)) {
                sb.appendLine("(push skipped — untrusted origin scheme: $originUrl)")
                return CommitPushResult(committed = true, pushed = false, branch = branch, sha = sha, log = sb.toString())
            }
            val pushEnv = mutableMapOf<String, String>()
            pushEnv["GIT_TERMINAL_PROMPT"] = "0"
            pushEnv["GIT_SSH_COMMAND"] = "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes"
            val exit = runWithExit(source, listOf("git", "push", "origin", branch), sb, env = pushEnv)
            pushed = exit == 0
            if (!pushed) {
                sb.appendLine("(push failed — commit kept locally; re-try via UI)")
            }
        }

        return CommitPushResult(committed = true, pushed = pushed, branch = branch, sha = sha, log = sb.toString())
    }

    // v1.31.1 (B-Q4) — push 허용 origin scheme whitelist (GitCloneService.validateUrl 과
    // 동일 정신). file:/ext::/gopher: 등 로컬·비신뢰 scheme 거부.
    private fun isAllowedPushUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("https://") || u.startsWith("http://") ||
            u.startsWith("ssh://") || u.startsWith("git://") || u.startsWith("git@")
    }

    private fun run(
        source: Path,
        cmd: List<String>,
        log: StringBuilder,
        env: Map<String, String>? = null,
        allowFail: Boolean = true,
    ): String {
        val out = StringBuilder()
        val exit = runWithExit(source, cmd, log, env, out)
        if (exit != 0 && !allowFail) {
            throw ApiException.localized(500, "git_failed",
                messageKey = "api.gitWriter.commitFailed",
                args = listOf("git ${cmd.drop(1).joinToString(" ")} exit $exit (cwd=$source)\n${out.toString().take(2000)}"))
        }
        return out.toString()
    }

    private fun runWithExit(
        source: Path,
        cmd: List<String>,
        log: StringBuilder,
        env: Map<String, String>? = null,
        capture: StringBuilder? = null,
    ): Int {
        log.appendLine("$ ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).directory(source.toFile()).redirectErrorStream(true)
        env?.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = try {
            pb.start()
        } catch (e: Throwable) {
            throw ApiException.localized(500, "spawn_fail", messageKey = "api.gitWriter.spawnFail", args = listOf(e.message ?: ""))
        }
        val output = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ApiException.localized(504, "git_timeout",
                messageKey = "api.gitWriter.timeout", args = listOf(cmd.drop(1).joinToString(" ")))
        }
        if (output.isNotEmpty()) log.append(output)
        capture?.append(output)
        return proc.exitValue()
    }

    companion object {
        const val MAX_MESSAGE_LEN = 4000
    }
}
