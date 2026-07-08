package com.siamakerlab.vibecoder.server.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

/**
 * v1.159.0 — vibe 사용자 **인바운드 SSH 접속** 인증키 관리.
 *
 * sshd 는 [docker/doctor/lib/ssh-server.sh] 에서 `PasswordAuthentication no` +
 * `PubkeyAuthentication yes` + `AllowUsers vibe` 로 설정된다. 비밀번호 접속이
 * 원천 차단돼 있으므로, 실제 접속을 위해선 `~/.ssh/authorized_keys` 에 공개키를
 * 등록해야 한다. 본 서비스가 그 등록/열람/삭제 + 서버측 전용 접속 키쌍 발급을 담당.
 *
 * 두 가지 provisioning 경로 (빌드환경 SSH 카드):
 *  1. **내 공개키 붙여넣기** — 사용자가 자기 공개키를 등록 ([addAuthorizedKey]).
 *     개인키는 서버에 없음 (가장 안전).
 *  2. **접속용 키 발급** — 서버가 전용 키쌍(`access_ed25519`)을 생성하고 공개키를
 *     authorized_keys 에 넣은 뒤, 개인키를 다운로드 제공 ([generateAccessKey] /
 *     [accessPrivateKey]). 키쌍이 없는 사용자도 즉시 접속.
 *
 * git 클라이언트 키(`id_ed25519`, push 용)와는 **별개** — 그 개인키는 절대 노출하지
 * 않는다. 접속용 전용 키(`access_ed25519`)만 다운로드 대상.
 *
 * fingerprint 는 OpenSSH 와 동일하게 `SHA256:base64(sha256(blob))` (padding 제거).
 * 열람/검증은 순수 JVM 으로 처리해 ssh-keygen 의존을 없앴다 — 발급([generateAccessKey])
 * 만 ssh-keygen 을 쓰며, 없으면 친화 에러(붙여넣기 경로는 계속 동작).
 *
 * 운영자 단일 사용자 도구 — 멀티 사용자 / RBAC 불필요. 서버 프로세스는 gosu 로
 * vibe 로 실행되므로 생성 파일 소유/권한이 자연히 vibe:vibe / 600 이 된다.
 *
 * @param sshDir 기본 `/home/vibe/.ssh` (Docker), 로컬 dev 에선 사용자 home/.ssh.
 */
class SshAccessService(
    private val sshDir: Path = Path.of(System.getProperty("user.home"), ".ssh"),
) {
    private val authorizedKeysFile: Path get() = sshDir.resolve("authorized_keys")
    private val accessPriv: Path get() = sshDir.resolve(ACCESS_KEY_NAME)
    private val accessPub: Path get() = sshDir.resolve("$ACCESS_KEY_NAME.pub")

    // ── 열람 ──────────────────────────────────────────────────────────

    /** authorized_keys 의 등록된 공개키 목록 (파싱 실패 라인은 제외). */
    fun listAuthorizedKeys(): List<AuthorizedKeyInfo> {
        if (!Files.exists(authorizedKeysFile)) return emptyList()
        val accessBlob = currentAccessBlob()
        return readNonBlankLines(authorizedKeysFile).mapNotNull { line ->
            val parsed = runCatching { parseKey(line) }.getOrNull() ?: return@mapNotNull null
            AuthorizedKeyInfo(
                algorithm = parsed.algorithm,
                fingerprint = parsed.fingerprint,
                comment = parsed.comment,
                isAccessKey = accessBlob != null && parsed.blobBase64 == accessBlob,
            )
        }
    }

    /** 발급된 접속용 키가 있으면 그 메타. 없으면 null. */
    fun accessKey(): AccessKeyInfo? {
        if (!Files.exists(accessPub)) return null
        val content = runCatching { Files.readString(accessPub).trim() }.getOrNull()?.ifBlank { null } ?: return null
        val parsed = runCatching { parseKey(content) }.getOrNull() ?: return null
        val createdAt = runCatching { Files.getLastModifiedTime(accessPub).toInstant() }.getOrNull()
        return AccessKeyInfo(
            fingerprint = parsed.fingerprint,
            comment = parsed.comment,
            createdAt = createdAt?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
        )
    }

    /** 접속용 개인키 raw 바이트 (다운로드용). 미발급이면 null. */
    fun accessPrivateKey(): ByteArray? =
        if (Files.exists(accessPriv)) runCatching { Files.readAllBytes(accessPriv) }.getOrNull() else null

    // ── 등록 / 삭제 ───────────────────────────────────────────────────

    /**
     * 붙여넣은 공개키 한 줄을 authorized_keys 에 추가. 이미 있으면 중복 없이 그대로 반환
     * (idempotent). 형식/알고리즘이 유효하지 않으면 [IllegalArgumentException].
     */
    fun addAuthorizedKey(raw: String): AuthorizedKeyInfo {
        val parsed = parseKey(raw)
        ensureDir()
        val existing = if (Files.exists(authorizedKeysFile)) readNonBlankLines(authorizedKeysFile) else emptyList()
        val already = existing.any { runCatching { parseKey(it).blobBase64 }.getOrNull() == parsed.blobBase64 }
        if (!already) {
            val line = buildString {
                append(parsed.algorithm).append(' ').append(parsed.blobBase64)
                parsed.comment?.let { append(' ').append(it) }
            }
            val newContent = (existing + line).joinToString("\n", postfix = "\n")
            Files.writeString(authorizedKeysFile, newContent, StandardCharsets.UTF_8)
            hardenPerms()
            log.info { "authorized_keys 에 공개키 추가: ${parsed.fingerprint}" }
        }
        return AuthorizedKeyInfo(parsed.algorithm, parsed.fingerprint, parsed.comment, isAccessKey = parsed.blobBase64 == currentAccessBlob())
    }

    /** fingerprint 로 authorized_keys 항목 삭제. 삭제됐으면 true. */
    fun removeAuthorizedKey(fingerprint: String): Boolean {
        if (!Files.exists(authorizedKeysFile)) return false
        val fp = fingerprint.trim()
        val lines = readNonBlankLines(authorizedKeysFile)
        val kept = lines.filterNot { runCatching { parseKey(it).fingerprint }.getOrNull() == fp }
        if (kept.size == lines.size) return false
        val newContent = if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
        Files.writeString(authorizedKeysFile, newContent, StandardCharsets.UTF_8)
        hardenPerms()
        log.info { "authorized_keys 에서 공개키 삭제: $fp" }
        return true
    }

    // ── 접속용 키 발급 ────────────────────────────────────────────────

    /**
     * 전용 접속 키쌍(`access_ed25519`)을 발급. 기존 접속 키가 있으면 그 공개키를
     * authorized_keys 에서 제거(de-authorize) + `.bak.<ts>` 백업 후 새로 생성한다.
     * 생성된 공개키를 authorized_keys 에 추가. ssh-keygen 미설치/실패 시
     * [IllegalStateException].
     */
    fun generateAccessKey(): AccessKeyInfo {
        ensureDir()
        val ts = TS_FMT.format(Instant.now())
        // 기존 접속 키 de-authorize + 백업
        currentAccessBlob()?.let { oldBlob ->
            runCatching {
                val remaining = readNonBlankLines(authorizedKeysFile)
                    .filterNot { runCatching { parseKey(it).blobBase64 }.getOrNull() == oldBlob }
                val content = if (remaining.isEmpty()) "" else remaining.joinToString("\n", postfix = "\n")
                Files.writeString(authorizedKeysFile, content, StandardCharsets.UTF_8)
            }
        }
        if (Files.exists(accessPriv)) Files.move(accessPriv, sshDir.resolve("$ACCESS_KEY_NAME.bak.$ts"))
        if (Files.exists(accessPub)) Files.move(accessPub, sshDir.resolve("$ACCESS_KEY_NAME.pub.bak.$ts"))

        val comment = "vibe-access@${hostname()}-$ts"
        val proc = runCatching {
            ProcessBuilder(
                "ssh-keygen", "-t", "ed25519",
                "-f", accessPriv.toString(),
                "-N", "",
                "-C", comment,
            ).redirectErrorStream(true).start()
        }.getOrElse { throw IllegalStateException("ssh-keygen 실행 불가 (openssh-client 미설치?): ${it.message}") }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("ssh-keygen 시간 초과")
        }
        if (proc.exitValue() != 0) {
            val out = proc.inputStream.bufferedReader().readText()
            throw IllegalStateException("ssh-keygen 실패 (exit=${proc.exitValue()}): $out")
        }
        runCatching { setPerm(accessPriv, "rw-------") }
        runCatching { setPerm(accessPub, "rw-r--r--") }

        val pubLine = Files.readString(accessPub).trim()
        addAuthorizedKey(pubLine)
        log.info { "접속용 SSH 키 발급: $comment" }
        return accessKey() ?: throw IllegalStateException("키 생성 후 읽기 실패")
    }

    // ── 내부 ──────────────────────────────────────────────────────────

    private fun currentAccessBlob(): String? =
        if (Files.exists(accessPub)) {
            runCatching { parseKey(Files.readString(accessPub).trim()).blobBase64 }.getOrNull()
        } else null

    private fun ensureDir() {
        if (!Files.exists(sshDir)) {
            Files.createDirectories(sshDir)
            runCatching { setPerm(sshDir, "rwx------") }
        }
    }

    /** .ssh 700, authorized_keys 600 — sshd 가 느슨한 권한을 거부하므로 필수. */
    private fun hardenPerms() {
        runCatching { setPerm(sshDir, "rwx------") }
        if (Files.exists(authorizedKeysFile)) runCatching { setPerm(authorizedKeysFile, "rw-------") }
    }

    private fun setPerm(path: Path, mode: String) {
        val perms: Set<PosixFilePermission> = PosixFilePermissions.fromString(mode)
        runCatching { Files.setPosixFilePermissions(path, perms) }
    }

    private fun readNonBlankLines(file: Path): List<String> =
        Files.readAllLines(file, StandardCharsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun hostname(): String = runCatching {
        ProcessBuilder("hostname").redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText().trim().ifBlank { "vibe-coder" }
        }
    }.getOrDefault("vibe-coder")

    /**
     * 공개키 한 줄 파싱 + 검증. `<alg> <base64-blob> [comment]`.
     * blob 의 wire-format 첫 문자열(알고리즘 이름)이 field[0] 과 일치하는지까지 확인해
     * 손상된/가짜 키를 거른다. 실패 시 [IllegalArgumentException].
     */
    private fun parseKey(raw: String): ParsedKey {
        val line = raw.trim()
        require(line.isNotEmpty()) { "빈 공개키" }
        require(!line.contains('\n') && !line.contains('\r')) { "한 줄 공개키만 허용됩니다" }
        val fields = line.split(Regex("\\s+"), limit = 3)
        require(fields.size >= 2) { "형식 오류 — '<타입> <키>' 형태여야 합니다" }
        val alg = fields[0]
        require(alg in ALLOWED_ALGS) { "지원하지 않는 키 타입: $alg" }
        val blobB64 = fields[1]
        val blob = try {
            Base64.getDecoder().decode(blobB64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("키 본문이 유효한 base64 가 아닙니다")
        }
        val wireAlg = runCatching { readSshString(blob, 0) }.getOrNull()
        require(wireAlg == alg) { "키 본문의 알고리즘($wireAlg)이 타입($alg)과 일치하지 않습니다" }
        val comment = fields.getOrNull(2)?.trim()?.ifBlank { null }
        val fp = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        return ParsedKey(alg, blobB64, comment, fp)
    }

    /** SSH wire-format 문자열 읽기: 4-byte big-endian 길이 + 그 길이만큼의 바이트. */
    private fun readSshString(blob: ByteArray, offset: Int): String {
        require(blob.size >= offset + 4) { "키 본문이 너무 짧습니다" }
        val len = ((blob[offset].toInt() and 0xFF) shl 24) or
            ((blob[offset + 1].toInt() and 0xFF) shl 16) or
            ((blob[offset + 2].toInt() and 0xFF) shl 8) or
            (blob[offset + 3].toInt() and 0xFF)
        require(len in 1..64 && blob.size >= offset + 4 + len) { "키 본문 길이 오류" }
        return String(blob, offset + 4, len, StandardCharsets.US_ASCII)
    }

    private data class ParsedKey(
        val algorithm: String,
        val blobBase64: String,
        val comment: String?,
        val fingerprint: String,
    )

    companion object {
        private const val ACCESS_KEY_NAME = "access_ed25519"
        private val ALLOWED_ALGS = setOf(
            "ssh-ed25519",
            "ssh-rsa",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "sk-ssh-ed25519@openssh.com",
            "sk-ecdsa-sha2-nistp256@openssh.com",
        )
        private val TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}

/** authorized_keys 의 개별 등록 키. */
data class AuthorizedKeyInfo(
    val algorithm: String,
    val fingerprint: String,
    val comment: String?,
    /** 서버가 발급한 접속용 키(access_ed25519)의 공개키면 true. */
    val isAccessKey: Boolean,
)

/** 서버 발급 접속용 키 메타. */
data class AccessKeyInfo(
    val fingerprint: String,
    val comment: String?,
    val createdAt: String?,
)
