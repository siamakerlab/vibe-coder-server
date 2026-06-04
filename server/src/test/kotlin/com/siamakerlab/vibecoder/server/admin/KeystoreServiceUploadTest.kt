package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * v1.101.0 — 키스토어 업로드(saveUploaded) 핵심 로직 회귀 방지: 표준 파일명 저장,
 * PKCS12/JKS 매직 검증, properties storeFile 정규화 + 필수키 검증, 덮어쓰기 전 백업.
 */
class KeystoreServiceUploadTest {

    private val pkg = "com.example.app"
    // PKCS12 = DER SEQUENCE(0x30…), JKS = magic 0xFEEDFEED.
    private val pkcs12 = byteArrayOf(0x30, 0x82.toByte(), 0x04, 0x00)
    private val jks = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())

    private fun svc(dir: Path) = KeystoreService(keystoreDir = dir, defaults = KeystoreDefaults())
    private fun tmp() = Files.createTempDirectory("ks-test")

    @Test fun `saves release+debug+properties to canonical names`() {
        val dir = tmp()
        val props = "storeFile=D:/local/whatever.keystore\nstorePassword=pw123\nkeyAlias=key0\nkeyPassword=pw123\n"
        val r = svc(dir).saveUploaded(pkg, pkcs12, jks, props)

        r.savedRelease shouldBe true
        r.savedDebug shouldBe true
        r.savedProperties shouldBe true
        dir.resolve("$pkg.keystore").exists() shouldBe true
        dir.resolve("$pkg-debug.keystore").exists() shouldBe true

        val written = dir.resolve("$pkg-keystore.properties").readText()
        // storeFile 은 서버 표준 경로로 강제 정규화(업로드된 로컬 경로 무시).
        written.contains("storeFile=/home/vibe/keystores/$pkg.keystore") shouldBe true
        written.contains("D:/local/whatever.keystore") shouldBe false
        written.contains("storePassword=pw123") shouldBe true
        written.contains("keyAlias=key0") shouldBe true
    }

    @Test fun `rejects non-keystore magic bytes`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> {
            svc(dir).saveUploaded(pkg, byteArrayOf(0x00, 0x01, 0x02), null, null)
        }
    }

    @Test fun `rejects properties missing required keys`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> {
            svc(dir).saveUploaded(pkg, null, null, "storeFile=x\n# no password/alias\n")
        }
    }

    @Test fun `keyPassword falls back to storePassword when absent`() {
        val dir = tmp()
        svc(dir).saveUploaded(pkg, null, null, "storePassword=secret\nkeyAlias=key0\n")
        val written = dir.resolve("$pkg-keystore.properties").readText()
        written.contains("keyPassword=secret") shouldBe true
    }

    @Test fun `backs up existing keystore before overwrite`() {
        val dir = tmp()
        val s = svc(dir)
        s.saveUploaded(pkg, pkcs12, null, null) // 최초: 백업 없음
        val r2 = s.saveUploaded(pkg, byteArrayOf(0x30, 0x01), null, null) // 교체: 백업 1개
        r2.backedUp.size shouldBe 1
        dir.listDirectoryEntries().any {
            it.fileName.toString().startsWith("$pkg.keystore.bak.")
        } shouldBe true
    }

    @Test fun `no files throws`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> { svc(dir).saveUploaded(pkg, null, null, null) }
    }

    @Test fun `invalid package name throws`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> { svc(dir).saveUploaded("Bad..Name", pkcs12, null, null) }
    }
}
