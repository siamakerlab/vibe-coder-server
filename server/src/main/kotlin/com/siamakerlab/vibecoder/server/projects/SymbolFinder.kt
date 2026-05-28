package com.siamakerlab.vibecoder.server.projects

import com.siamakerlab.vibecoder.server.core.WorkspacePath
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

private val log = KotlinLogging.logger {}

/**
 * v0.54.0 — Phase 33 best-effort symbol definition lookup.
 *
 * **Why not Kotlin LSP?** A real Kotlin Language Server (e.g. `fwcd/kotlin-language-server`)
 * spawns a second JVM on top of the Kotlin compiler classpath — 200–500 MB RAM, 10–30 s
 * cold start, and only works on Kotlin (not Java / XML / Gradle). For the single-user dev
 * server profile (CLAUDE.md §1) that overhead is not justified yet. A regex-based scan
 * catches 90% of real "jump to definition" cases (top-level declarations) in milliseconds
 * with zero new dependencies.
 *
 * Patterns matched (Kotlin + Java):
 *   - `fun <name>(`
 *   - `(class|object|interface|enum class|annotation class) <name>`
 *   - `val <name>` / `var <name>` (top-level + property)
 *   - Java `(public|protected|private|static)? ... (class|interface|enum) <name>`
 *   - Java method `... <name>(`
 *
 * Heuristic — false positives possible (e.g. nested type with same name). The UI is meant
 * for jumping, not for refactoring; clicking still shows the file + line so the user
 * decides.
 */
class SymbolFinder(private val workspace: WorkspacePath) {

    data class Hit(
        val relPath: String,
        val lineNumber: Int,
        val line: String,
        /** Best-guess declaration kind, e.g. `fun`, `class`, `object`, `interface`, `val`. */
        val kind: String,
    )

    fun find(projectId: String, symbol: String): List<Hit> {
        if (!isValidSymbol(symbol)) return emptyList()
        val projectRoot = workspace.projectRoot(projectId)
        if (!projectRoot.isDirectory()) return emptyList()

        val patterns = buildPatterns(symbol)
        val results = mutableListOf<Hit>()

        runCatching {
            Files.walk(projectRoot).use { stream ->
                stream.forEach { p ->
                    if (results.size >= MAX_HITS) return@forEach
                    if (!p.isRegularFile()) return@forEach
                    val rel = projectRoot.relativize(p).toString().replace('\\', '/')
                    if (shouldExclude(rel)) return@forEach
                    val size = runCatching { Files.size(p) }.getOrDefault(0L)
                    if (size > MAX_FILE_BYTES) return@forEach
                    scanFile(p, rel, patterns, results)
                }
            }
        }.onFailure { log.debug(it) { "symbol walk failed: $projectRoot" } }

        return results.take(MAX_HITS)
    }

    private fun scanFile(
        path: Path,
        rel: String,
        patterns: List<Pair<Regex, String>>,
        out: MutableList<Hit>,
    ) {
        runCatching {
            Files.newBufferedReader(path).use { reader ->
                var lineNo = 0
                while (out.size < MAX_HITS) {
                    val line = reader.readLine() ?: break
                    lineNo++
                    for ((re, kind) in patterns) {
                        if (re.containsMatchIn(line)) {
                            out += Hit(rel, lineNo, line.trim().take(400), kind)
                            break  // 한 줄에 여러 매치면 첫 매치만
                        }
                    }
                }
            }
        }.onFailure { log.debug(it) { "symbol file scan failed: $rel" } }
    }

    /**
     * 패턴은 line 안의 token 경계를 신경 — `\b<symbol>\b` 매치 + 키워드 prefix. Kotlin /
     * Java 가 거의 같은 정의 구문이라 같은 패턴으로 양쪽 잡힘.
     */
    private fun buildPatterns(symbol: String): List<Pair<Regex, String>> {
        val s = Regex.escape(symbol)
        return listOf(
            // fun foo(...) — Kotlin
            Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:\w+\.)?$s\s*[(<]""") to "fun",
            // class / object / interface / enum class / annotation class / data class
            Regex("""\b(?:open\s+|abstract\s+|sealed\s+|final\s+|inner\s+|data\s+|enum\s+|annotation\s+|value\s+)*(?:class|interface|object)\s+$s\b""") to "class",
            // val / var (top-level + property)
            Regex("""\b(?:val|var)\s+(?:<[^>]+>\s+)?$s\s*[:=]""") to "val",
            // Java 메소드 (간이 — return type 무시, 괄호로 식별)
            Regex("""\b(?:public|protected|private|static|final|abstract|synchronized|native)\s+(?:<[^>]+>\s+)?[\w<>\[\],?.\s]+\s+$s\s*\(""") to "fun",
            // Java class / interface / enum
            Regex("""\b(?:public|protected|private|abstract|final|static)?\s*(?:class|interface|enum)\s+$s\b""") to "class",
            // typealias <name> = ...
            Regex("""\btypealias\s+$s\s*=""") to "typealias",
        )
    }

    /** Kotlin / Java identifier 문법 + leading char 제한. 임의 정규식 injection 방지. */
    private fun isValidSymbol(s: String): Boolean {
        if (s.isBlank() || s.length > 80) return false
        // v1.31.2 (Q1) — 연산자 우선순위 버그 fix. `&&` 가 `||` 보다 우선이라
        // 이전엔 `isLetter() || ('_' && all{...})` 로 파싱돼 첫 글자가 letter 면
        // 나머지 글자 검증(`s.all`)을 건너뛰어 `foo bar`/`foo${'$'}x` 등이 통과했음.
        // (Regex.escape 안전망 덕에 인젝션은 불가, 비정상 심볼이 매치 0 으로 새던
        // 기능 결함.) 괄호로 leading-char OR 를 묶음.
        return (s.first().isLetter() || s.first() == '_') &&
            s.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun shouldExclude(rel: String): Boolean {
        val top = rel.substringBefore('/')
        if (top in EXCLUDED_TOP_DIRS) return true
        if (rel.contains("/build/") || rel.contains("/.gradle/") ||
            rel.contains("/node_modules/") || rel.contains("/.idea/")) return true
        val base = rel.substringAfterLast('/')
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext !in TEXT_EXTENSIONS) return true
        return false
    }

    companion object {
        private const val MAX_HITS = 100
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024
        private val EXCLUDED_TOP_DIRS = setOf(".git", "build", ".gradle", "node_modules", ".idea")
        /**
         * Symbol lookup 은 declaration 만 찾으므로 코드 파일에 집중. `.kt`, `.java`,
         * 그리고 Gradle DSL (`.gradle.kts`, `.kts`) 정도. XML 은 별도 grep 으로.
         */
        private val TEXT_EXTENSIONS = setOf("kt", "kts", "java", "groovy")
    }
}
