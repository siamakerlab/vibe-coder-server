package com.siamakerlab.vibecoder.server.git

import com.siamakerlab.vibecoder.server.error.ApiException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists

private val log = KotlinLogging.logger {}

/**
 * Git provider 별 PAT 보관 — v0.9.0.
 *
 * vibe-coder 운영자가 환경설정 페이지에서 GitHub / GitLab / Gitea /
 * Bitbucket 의 Personal Access Token 을 등록하면, private 레포 clone 시
 * git CLI 가 자동으로 사용한다.
 *
 * 저장 위치:
 *  - `~/.config/vibe-coder/git-tokens.json` — 서버 자체의 정규화된 보관소
 *    (UI 가 list/edit 할 때 읽음). 권한 0600.
 *  - `~/.git-credentials` — git CLI 의 표준 `credential.helper=store` 가
 *    읽는 형식 (`https://x-access-token:TOKEN@host`). 권한 0600.
 *    GitCloneService 가 git clone 실행 시 별도 인증 setup 없이 자동 사용.
 *
 * 두 파일을 모두 atomic 하게 갱신해 일관성 유지.
 *
 * 보안:
 *  - 토큰은 응답 시 마지막 4자리만 표시 (마스킹).
 *  - 파일 권한 0600 — Posix 환경 한정.
 *  - 컨테이너는 1인 LAN 격리 도구라 시스템 레벨 secret store 없이 file-based 로 충분.
 */
class GitCredentialStore {

    @Serializable
    data class Token(
        val provider: String,          // github / gitlab / gitea / bitbucket / generic
        val host: String,              // github.com / gitlab.com / gitea.wody.kr 등
        val username: String,          // 보통 'x-access-token' 또는 'oauth2'
        val token: String,             // 평문 (파일 0600)
        val createdAt: String,         // ISO timestamp
        val note: String? = null,      // 사용자 메모
    )

    @Serializable
    private data class Storage(
        val tokens: MutableList<Token> = mutableListOf(),
    )

    /** UI 가 list 할 때 토큰을 마스킹한 형태. 평문 토큰은 절대 응답에 안 내보냄. */
    data class TokenView(
        val provider: String,
        val host: String,
        val username: String,
        val tokenMasked: String,
        val createdAt: String,
        val note: String?,
    )

    fun list(): List<TokenView> = read().tokens.map { it.toView() }

    /**
     * 등록 — 같은 host 에 이미 토큰이 있으면 덮어쓰기.
     * git-credentials 파일도 같이 갱신해 git CLI 가 즉시 사용 가능.
     */
    fun register(
        provider: String,
        host: String,
        username: String?,
        token: String,
        note: String?,
        nowIso: String,
    ) {
        val cleanProvider = provider.trim().lowercase().ifBlank { "generic" }
        val cleanHost = host.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').ifBlank {
                throw ApiException.localized(400, "empty_host", messageKey = "api.gitCredential.emptyHost")
            }
        val cleanToken = token.trim()
        // v0.12.4 — 실제 PAT 는 보통 30+ 자 (GitHub classic 40, fine-grained 80+,
        // GitLab 26, Gitea 40). 20 자 미만이면 거의 확실히 잘못된 입력 → 사용자가
        // 무효한 토큰 등록 후 clone 실패로 디버깅하는 비용을 줄임.
        if (cleanToken.length < 20) {
            throw ApiException.localized(400, "short_token",
                messageKey = "api.gitCredential.shortToken", args = listOf(20))
        }
        val cleanUsername = (username?.trim()?.ifBlank { null }) ?: defaultUsernameFor(cleanProvider)

        val storage = read()
        storage.tokens.removeAll { it.host == cleanHost }
        storage.tokens.add(Token(
            provider = cleanProvider,
            host = cleanHost,
            username = cleanUsername,
            token = cleanToken,
            createdAt = nowIso,
            note = note?.trim()?.ifBlank { null },
        ))
        writeJsonAtomic(jsonPath(), Json.encodeToString(Storage.serializer(), storage))
        tightenPermissions(jsonPath())

        // git CLI 표준 credential store 동기 갱신
        rewriteGitCredentialsFile(storage.tokens)
        log.info { "git token registered: $cleanProvider @ $cleanHost (user=$cleanUsername)" }
    }

    fun delete(host: String): Boolean {
        val cleanHost = host.trim().lowercase()
        val storage = read()
        val removed = storage.tokens.removeAll { it.host == cleanHost }
        if (!removed) return false
        writeJsonAtomic(jsonPath(), Json.encodeToString(Storage.serializer(), storage))
        rewriteGitCredentialsFile(storage.tokens)
        log.info { "git token deleted: $cleanHost" }
        return true
    }

    /** clone 시 GitCloneService 가 호출 — host 에 매치되는 토큰이 있는지. */
    fun findByHost(host: String): Token? {
        val h = host.lowercase()
        return read().tokens.firstOrNull { it.host == h }
    }

    private fun read(): Storage {
        val p = jsonPath()
        if (!p.exists()) return Storage()
        return try {
            val text = Files.readString(p, Charsets.UTF_8)
            Json.decodeFromString(Storage.serializer(), text)
        } catch (e: Throwable) {
            log.warn(e) { "git-tokens.json 파싱 실패 → 빈 storage 로 시작" }
            Storage()
        }
    }

    private fun rewriteGitCredentialsFile(tokens: List<Token>) {
        // ~/.git-credentials — 한 줄에 하나, `https://user:token@host` 형식.
        val content = tokens.joinToString("\n") { t ->
            val userEnc = urlEncode(t.username)
            val tokEnc = urlEncode(t.token)
            "https://$userEnc:$tokEnc@${t.host}"
        }.let { if (it.isEmpty()) "" else "$it\n" }

        val path = gitCredentialsPath()
        writeJsonAtomic(path, content)
        tightenPermissions(path)

        // git config 의 credential.helper=store 가 setup 안 됐을 수 있어 보장.
        // 컨테이너 단일 vibe 사용자 환경이라 global config 변경이 안전.
        ensureCredentialHelperStore()
    }

    private fun ensureCredentialHelperStore() {
        runCatching {
            val pb = ProcessBuilder(listOf("git", "config", "--global", "credential.helper", "store"))
                .redirectErrorStream(true)
            pb.start().waitFor()
        }.onFailure {
            log.warn { "git config credential.helper 설정 실패: ${it.message}" }
        }
    }

    private fun writeJsonAtomic(path: Path, content: String) {
        try {
            Files.createDirectories(path.parent)
        } catch (_: Throwable) {}
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(
            tmp, content, Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        )
        // v1.44.0 — 메서드명("Atomic")과 동작 일치: ATOMIC_MOVE + 미지원 FS fallback.
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun tightenPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private fun jsonPath(): Path {
        val cfg = userConfigDir().resolve("vibe-coder")
        return cfg.resolve("git-tokens.json")
    }

    private fun gitCredentialsPath(): Path = userHome().resolve(".git-credentials")

    private fun userConfigDir(): Path {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.trim()
        if (!xdg.isNullOrBlank()) return Path.of(xdg)
        return userHome().resolve(".config")
    }

    private fun userHome(): Path {
        val home = System.getProperty("user.home")
            ?: System.getenv("HOME")
            ?: System.getenv("USERPROFILE")
            ?: "."
        return Path.of(home)
    }

    private fun defaultUsernameFor(provider: String): String = when (provider) {
        "github" -> "x-access-token"       // GitHub PAT (classic + fine-grained 모두 동작)
        "gitlab" -> "oauth2"               // GitLab PAT 표준
        "gitea" -> "vibe-coder"            // Gitea 는 username 임의 OK, 토큰만 매칭
        "bitbucket" -> "x-token-auth"      // Bitbucket repository access token
        else -> "vibe-coder"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")

    private fun Token.toView() = TokenView(
        provider = provider,
        host = host,
        username = username,
        tokenMasked = "•".repeat(maxOf(0, token.length - 4)) + token.takeLast(4),
        createdAt = createdAt,
        note = note,
    )
}
