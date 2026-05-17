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
        val pb = ProcessBuilder(cmd).directory(source.toFile()).redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return ""
        }
        return out
    }
}
