package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * v1.102.0 — 키스토어 업로드(stageUploaded) 핵심 로직 회귀 방지: staging 디렉토리에
 * 규약 파일명 저장, PKCS12/JKS 매직 검증, properties storeFile 정규화 + 필수키 검증.
 * 최종 배치(이동/백업)는 Claude 콘솔 프롬프트가 수행하므로 서버 단위는 staging 까지만.
 */
class KeystoreServiceUploadTest {

    private val pkg = "com.example.app"
    // PKCS12 = DER SEQUENCE(0x30…), JKS = magic 0xFEEDFEED.
    private val pkcs12 = byteArrayOf(0x30, 0x82.toByte(), 0x04, 0x00)
    private val jks = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())

    private fun svc(dir: Path) = KeystoreService(keystoreDir = dir, defaults = KeystoreDefaults())
    private fun tmp() = Files.createTempDirectory("ks-test")

    @Test fun `stages release+debug+properties to canonical names under _staging`() {
        val dir = tmp()
        val s = svc(dir)
        val props = "storeFile=D:/local/whatever.keystore\nstorePassword=pw123\nkeyAlias=key0\nkeyPassword=pw123\n"
        val r = s.stageUploaded(pkg, pkcs12, jks, props)

        r.releaseFile shouldBe "$pkg.keystore"
        r.debugFile shouldBe "$pkg-debug.keystore"
        r.propertiesFile shouldBe "$pkg-keystore.properties"
        // staging 에 저장됐고, 최종 디렉토리에는 아직 배치 안 됨(콘솔 프롬프트가 mv).
        val staging = s.stagingDir(pkg)
        staging.resolve("$pkg.keystore").exists() shouldBe true
        staging.resolve("$pkg-debug.keystore").exists() shouldBe true
        dir.resolve("$pkg.keystore").exists() shouldBe false

        val written = staging.resolve("$pkg-keystore.properties").readText()
        // storeFile 은 서버 표준 경로로 강제 정규화(업로드된 로컬 경로 무시).
        written.contains("storeFile=/home/vibe/keystores/$pkg.keystore") shouldBe true
        written.contains("D:/local/whatever.keystore") shouldBe false
        written.contains("storePassword=pw123") shouldBe true
        written.contains("keyAlias=key0") shouldBe true
    }

    @Test fun `rejects non-keystore magic bytes`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> {
            svc(dir).stageUploaded(pkg, byteArrayOf(0x00, 0x01, 0x02), null, null)
        }
    }

    @Test fun `rejects properties missing required keys`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> {
            svc(dir).stageUploaded(pkg, null, null, "storeFile=x\n# no password/alias\n")
        }
    }

    @Test fun `keyPassword falls back to storePassword when absent`() {
        val dir = tmp()
        val s = svc(dir)
        s.stageUploaded(pkg, null, null, "storePassword=secret\nkeyAlias=key0\n")
        val written = s.stagingDir(pkg).resolve("$pkg-keystore.properties").readText()
        written.contains("keyPassword=secret") shouldBe true
    }

    @Test fun `re-staging clears previous staging contents`() {
        val dir = tmp()
        val s = svc(dir)
        s.stageUploaded(pkg, pkcs12, jks, null) // release + debug
        val r2 = s.stageUploaded(pkg, pkcs12, null, null) // release 만 → debug 잔재 제거돼야
        r2.debugFile shouldBe null
        s.stagingDir(pkg).resolve("$pkg-debug.keystore").exists() shouldBe false
    }

    @Test fun `no files throws`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> { svc(dir).stageUploaded(pkg, null, null, null) }
    }

    @Test fun `invalid package name throws`() {
        val dir = tmp()
        shouldThrow<IllegalArgumentException> { svc(dir).stageUploaded("Bad..Name", pkcs12, null, null) }
    }
}
