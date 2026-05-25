package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val log = KotlinLogging.logger {}

/**
 * v0.70.0 — Phase 49 #1. SSR [LogSearchRoutes] 의 private search() 를 추출.
 * Bearer JSON endpoint `/api/logs` 도 같은 로직 재사용 → 모바일 admin 의 log
 * search 가 v0.67.0 이래 stub empty 였던 문제 해소.
 *
 * 안전 정책 (그대로 유지):
 *  - workspace 외부 경로 접근 불가 (WorkspacePath 안에서만).
 *  - q 빈 문자열 = 빈 결과 (대량 dump 방지).
 *  - matches 200 hard cap.
 *  - 파일 끝에서 MAX_BYTES_PER_FILE (2 MB) 만 읽어 grep.
 */
class LogSearchService(private val workspace: WorkspacePath) {

    data class Match(
        val projectId: String,
        val buildId: String,
        val lineNumber: Int,
        val line: String,
    )

    fun search(q: String, projectFilter: String? = null): List<Match> {
        if (q.isBlank()) return emptyList()
        val sidecar = workspace.root.resolve(".vibecoder")
        if (!Files.isDirectory(sidecar)) return emptyList()
        val results = mutableListOf<Match>()
        val qLower = q.lowercase()

        Files.list(sidecar).use { topStream ->
            topStream.toList().forEach { projectDir ->
                if (results.size >= MAX_MATCHES) return@forEach
                if (!Files.isDirectory(projectDir)) return@forEach
                val pid = projectDir.name
                if (projectFilter != null && pid != projectFilter) return@forEach
                val logsDir = projectDir.resolve("logs")
                if (!Files.isDirectory(logsDir)) return@forEach
                Files.list(logsDir).use { logStream ->
                    logStream.toList().forEach inner@{ logFile ->
                        if (results.size >= MAX_MATCHES) return@inner
                        if (!logFile.isRegularFile() || !logFile.name.endsWith(".log")) return@inner
                        val buildId = logFile.name.removeSuffix(".log")
                        grepFile(logFile, qLower, pid, buildId, results)
                    }
                }
            }
        }
        return results.take(MAX_MATCHES)
    }

    private fun grepFile(path: Path, qLower: String, pid: String, buildId: String, out: MutableList<Match>) {
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        val skip = (size - MAX_BYTES_PER_FILE).coerceAtLeast(0)
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                if (skip > 0) reader.skip(skip)
                var lineNo = 0
                if (skip > 0) { reader.readLine(); lineNo++ }
                while (true) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    if (out.size >= MAX_MATCHES) return@use
                    if (line.lowercase().contains(qLower)) {
                        out += Match(pid, buildId, lineNo, line.take(400))
                    }
                }
            }
        }.onFailure { log.debug(it) { "grep failed $path" } }
    }

    companion object {
        const val MAX_MATCHES = 200
        const val MAX_BYTES_PER_FILE = 2 * 1024 * 1024
    }
}
