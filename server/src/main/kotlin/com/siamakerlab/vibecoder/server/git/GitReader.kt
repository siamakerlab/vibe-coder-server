package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.shared.dto.GitDiffDto
import com.siamakerlab.vibecoder.shared.dto.GitLogDto
import com.siamakerlab.vibecoder.shared.dto.GitLogEntryDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusDto
import com.siamakerlab.vibecoder.shared.dto.GitStatusEntryDto
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Read-only git helper. Only invokes the safe subset (status / diff / log).
 * Push / reset / clean / force are explicitly out of scope.
 */
class GitReader {

    fun status(source: Path): GitStatusDto {
        val branch = run(source, listOf("git", "rev-parse", "--abbrev-ref", "HEAD")).trim()
        val raw = run(source, listOf("git", "status", "--short", "--branch"))
        val lines = raw.lines().filter { it.isNotBlank() }
        val entries = lines.drop(1).mapNotNull { parseShortStatus(it) }
        return GitStatusDto(
            branch = branch.ifBlank { "HEAD" },
            entries = entries,
            ahead = 0, behind = 0,
        )
    }

    fun diff(source: Path): GitDiffDto = GitDiffDto(diff = run(source, listOf("git", "diff")))

    fun log(source: Path, count: Int = 20): GitLogDto {
        val raw = run(source, listOf("git", "log", "--oneline", "-$count"))
        val entries = raw.lines().filter { it.isNotBlank() }.map { line ->
            val sha = line.substringBefore(' ', "")
            val msg = line.substringAfter(' ', line)
            GitLogEntryDto(sha = sha, message = msg)
        }
        return GitLogDto(entries = entries)
    }

    private fun parseShortStatus(line: String): GitStatusEntryDto? {
        if (line.length < 4) return null
        val status = line.substring(0, 2)
        val path = line.substring(3)
        return GitStatusEntryDto(status = status, path = path)
    }

    private fun run(source: Path, cmd: List<String>): String {
        // v1.43.0 — stderr 를 DISCARD 로 버린다. 이전엔 redirectErrorStream(false) 인데
        // stdout 만 readText 로 EOF 까지 읽고 stderr 는 배수하지 않아, git 이 stderr 로
        // 64KB 이상 출력하면 파이프 버퍼 포화 → 상호 데드락(waitFor 도달 불가, 좀비+스레드 누수).
        // stderr 를 merge 하면 status/log/diff 파싱이 오염되므로 merge 대신 DISCARD.
        val pb = ProcessBuilder(cmd).directory(source.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return ""
        }
        return out
    }
}
