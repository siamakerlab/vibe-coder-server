package com.siamakerlab.vibecoder.server.admin

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * v1.159.0 — [SshAccessService] 인증키 등록/삭제 + fingerprint 회귀 방지.
 *
 * 핵심: fingerprint 를 ssh-keygen 없이 순수 JVM(SHA256(blob))으로 계산한다. 그 값이
 * OpenSSH `ssh-keygen -lf` 와 정확히 일치해야 카드/삭제 흐름이 성립한다. 아래 KNOWN_* 는
 * 실제 `ssh-keygen -t ed25519` 로 생성한 키 + 그 SHA256 fingerprint 다.
 */
class SshAccessServiceTest {

    private fun tmp(): Path = Files.createTempDirectory("ssh-access-test")
    private fun svc(dir: Path) = SshAccessService(sshDir = dir)

    /** OpenSSH SHA256 fingerprint 가 순수 JVM 계산과 정확히 일치. */
    @Test
    fun `fingerprint matches openssh`() {
        val dir = tmp()
        val info = svc(dir).addAuthorizedKey(KNOWN_PUB)
        info.fingerprint shouldBe KNOWN_FP
        info.algorithm shouldBe "ssh-ed25519"
        info.comment shouldBe "test@vibe"
    }

    /** authorized_keys 파일에 실제로 기록되고 0600 권한. */
    @Test
    fun `add writes authorized_keys`() {
        val dir = tmp()
        svc(dir).addAuthorizedKey(KNOWN_PUB)
        val ak = dir.resolve("authorized_keys")
        Files.exists(ak).shouldBeTrue()
        Files.readString(ak).shouldContain("ssh-ed25519 AAAAC3")
        Files.getPosixFilePermissions(ak).toString() shouldContain "OWNER_READ"
    }

    /** 같은 키(코멘트만 달라도)를 두 번 추가해도 한 항목 — blob 기준 dedupe. */
    @Test
    fun `add is idempotent by blob`() {
        val dir = tmp()
        val s = svc(dir)
        s.addAuthorizedKey(KNOWN_PUB)
        s.addAuthorizedKey("ssh-ed25519 $KNOWN_BLOB different-comment")
        s.listAuthorizedKeys() shouldHaveSize 1
    }

    /** fingerprint 로 삭제. */
    @Test
    fun `remove by fingerprint`() {
        val dir = tmp()
        val s = svc(dir)
        s.addAuthorizedKey(KNOWN_PUB)
        s.listAuthorizedKeys() shouldHaveSize 1
        s.removeAuthorizedKey(KNOWN_FP).shouldBeTrue()
        s.listAuthorizedKeys() shouldHaveSize 0
        // 없는 fingerprint 삭제는 false.
        s.removeAuthorizedKey(KNOWN_FP) shouldBe false
    }

    /** 손상/가짜 키 거부. */
    @Test
    fun `invalid keys rejected`() {
        val s = svc(tmp())
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("") }
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("not-a-key") }
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("ssh-ed25519 @@@not-base64@@@") }
        // 타입은 ed25519 인데 본문은 rsa blob → wire alg 불일치 거부.
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("ssh-ed25519 $RSA_BLOB") }
        // 알 수 없는 타입.
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("ssh-dss $KNOWN_BLOB") }
        // 여러 줄 금지.
        assertFailsWith<IllegalArgumentException> { s.addAuthorizedKey("$KNOWN_PUB\nssh-ed25519 $KNOWN_BLOB") }
    }

    /** 미발급 상태에선 접속용 키 없음. */
    @Test
    fun `no access key initially`() {
        val s = svc(tmp())
        s.accessKey() shouldBe null
        s.accessPrivateKey() shouldBe null
    }

    /** 접속용 키 발급 → 공개키가 authorized_keys 에 등록 + 개인키 다운로드 가능 (ssh-keygen 있을 때만). */
    @Test
    fun `generate access key registers pub and exposes private`() {
        org.junit.Assume.assumeTrue("ssh-keygen 필요", sshKeygenAvailable())
        val dir = tmp()
        val s = svc(dir)
        val info = s.generateAccessKey()
        // 개인키 다운로드 가능 + OpenSSH private key 포맷.
        val priv = s.accessPrivateKey()
        (priv != null).shouldBeTrue()
        String(priv!!).shouldContain("OPENSSH PRIVATE KEY")
        // authorized_keys 에 접속용 공개키가 isAccessKey 로 표시.
        val listed = s.listAuthorizedKeys()
        listed shouldHaveSize 1
        listed[0].isAccessKey.shouldBeTrue()
        listed[0].fingerprint shouldBe info.fingerprint
        // 재발급 시 이전 접속 키는 de-authorize (한 항목만 유지).
        val info2 = s.generateAccessKey()
        val listed2 = s.listAuthorizedKeys()
        listed2 shouldHaveSize 1
        listed2[0].fingerprint shouldBe info2.fingerprint
        (info2.fingerprint != info.fingerprint).shouldBeTrue()
    }

    private fun sshKeygenAvailable(): Boolean = runCatching {
        ProcessBuilder("ssh-keygen", "--help").redirectErrorStream(true).start().waitFor()
        true
    }.getOrDefault(false)

    companion object {
        private const val KNOWN_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAINO0Rl20McmlQoPt7ZBlqQiHM7w+nvbhacpDsIizC5pd"
        private const val KNOWN_PUB = "ssh-ed25519 $KNOWN_BLOB test@vibe"
        private const val KNOWN_FP = "SHA256:FRnZDCHyeJns10ED/yoojJGxJeu7rF/B1uyQJG4eW2A"
        // 유효 base64 지만 wire-format 알고리즘 이름이 "ssh-rsa" → ssh-ed25519 타입과 불일치.
        private const val RSA_BLOB = "AAAAB3NzaC1yc2EAAAADAQAB"
    }
}
