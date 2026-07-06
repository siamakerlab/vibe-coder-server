package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * v1.144.5 — `loadSigning` 의 variant 별 키스토어 선택 회귀 방지.
 *
 * 디버그 빌드는 `<pkg>-debug.keystore`, 릴리즈/번들은 `<pkg>.keystore` 로 서명돼야 한다.
 * 이전엔 `debug` 인자가 없어 디버그 빌드에도 properties 의 릴리즈 `storeFile` 이 주입돼
 * 디버그 APK 가 릴리즈 키로 서명되던 결함이 있었다. 키스토어 파일은 내용 검증 없이
 * 존재 여부만 보므로(서명 자체는 Gradle/AGP 가 수행) 더미 파일로 충분하다.
 *
 * v1.154.1 — debug 빌드 시 keyAlias `-debug` 접미사 해석 테스트 추가.
 * 실제 PKCS12 keystore 를 keytool 로 생성하여 resolveDebugAlias 검증.
 */
class KeystoreServiceLoadSigningTest {

    private val pkg = "com.example.app"
    private fun svc(dir: Path) = KeystoreService(keystoreDir = dir, defaults = KeystoreDefaults())
    private fun tmp() = Files.createTempDirectory("ks-sign-test")

    /** properties 의 storeFile 은 릴리즈 경로로 하드코딩(create() 와 동일). */
    private fun writeProps(dir: Path) {
        Files.writeString(
            dir.resolve("$pkg-keystore.properties"),
            "storeFile=/home/vibe/keystores/$pkg.keystore\nstorePassword=testpw\nkeyAlias=key0\nkeyPassword=testpw\n",
        )
    }

    /** keytool 로 실제 PKCS12 keystore 생성 (resolveDebugAlias 테스트용). */
    private fun genKeystore(path: Path, alias: String, password: String = "testpw") {
        val proc = ProcessBuilder(
            "keytool", "-genkeypair",
            "-keystore", path.toString(),
            "-alias", alias,
            "-storepass", password,
            "-keypass", password,
            "-dname", "CN=Test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "365",
        ).redirectErrorStream(true).start()
        proc.inputStream.readAllBytes()
        check(proc.waitFor(30, TimeUnit.SECONDS)) { "keytool timeout" }
        check(proc.exitValue() == 0) { "keytool failed for alias=$alias" }
    }

    @Test fun `release build selects release keystore`() {
        val dir = tmp()
        Files.writeString(dir.resolve("$pkg.keystore"), "x")
        Files.writeString(dir.resolve("$pkg-debug.keystore"), "x")
        writeProps(dir)
        val s = svc(dir).loadSigning(pkg, debug = false)!!
        // properties.storeFile 의 /home/vibe 경로는 테스트 환경에 없어 release 로 fallback.
        s.storeFile shouldBe dir.resolve("$pkg.keystore")
        s.keyAlias shouldBe "key0"
    }

    @Test fun `debug build selects debug keystore not release`() {
        val dir = tmp()
        Files.writeString(dir.resolve("$pkg.keystore"), "x")
        Files.writeString(dir.resolve("$pkg-debug.keystore"), "x")
        writeProps(dir)
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        // 핵심: properties.storeFile 은 릴리즈 경로지만 디버그 빌드는 -debug.keystore 강제.
        s.storeFile shouldBe dir.resolve("$pkg-debug.keystore")
        // 더미 파일이므로 resolveDebugAlias 가 PKCS12 로드 실패 → baseAlias(key0) fallback.
        s.keyAlias shouldBe "key0"
        s.storePassword shouldBe "testpw"
        s.keyPassword shouldBe "testpw"
    }

    @Test fun `debug build resolves -debug suffix alias from real keystore`() {
        val dir = tmp()
        genKeystore(dir.resolve("$pkg.keystore"), "key0")
        genKeystore(dir.resolve("$pkg-debug.keystore"), "key0-debug")
        writeProps(dir)
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        s.storeFile shouldBe dir.resolve("$pkg-debug.keystore")
        // 실제 PKCS12 keystore 에 key0-debug alias 가 있으므로 resolveDebugAlias 가 반환.
        s.keyAlias shouldBe "key0-debug"
    }

    @Test fun `debug build falls back to base alias when -debug suffix absent`() {
        val dir = tmp()
        genKeystore(dir.resolve("$pkg.keystore"), "key0")
        // debug keystore 의 alias 가 key0 (create() v1.5.0–v1.154.0 경로).
        genKeystore(dir.resolve("$pkg-debug.keystore"), "key0")
        writeProps(dir)
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        s.storeFile shouldBe dir.resolve("$pkg-debug.keystore")
        // key0-debug alias 가 없으므로 baseAlias(key0) fallback.
        s.keyAlias shouldBe "key0"
    }

    @Test fun `debug build without debug keystore yields null (no release fallback)`() {
        val dir = tmp()
        Files.writeString(dir.resolve("$pkg.keystore"), "x") // release 만 존재
        writeProps(dir)
        // 디버그 키스토어가 없으면 미주입 → AGP 표준 debug 서명 사용(릴리즈 키 fallback 금지).
        svc(dir).loadSigning(pkg, debug = true) shouldBe null
    }

    @Test fun `release build without release keystore yields null`() {
        val dir = tmp()
        Files.writeString(dir.resolve("$pkg-debug.keystore"), "x") // debug 만 존재
        writeProps(dir)
        svc(dir).loadSigning(pkg, debug = false) shouldBe null
    }

    @Test fun `default debug arg is false (release)`() {
        val dir = tmp()
        Files.writeString(dir.resolve("$pkg.keystore"), "x")
        writeProps(dir)
        // debug 키스토어 없이도 기본 호출은 release 선택 → 기존 호출자(fingerprints 등) 호환.
        val s = svc(dir).loadSigning(pkg)!!
        s.storeFile shouldBe dir.resolve("$pkg.keystore")
    }
}
