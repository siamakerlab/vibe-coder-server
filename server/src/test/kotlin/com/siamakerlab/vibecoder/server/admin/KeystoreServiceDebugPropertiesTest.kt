package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * v1.160.0 — 디버그 빌드가 **키스토어 폴더에 보관된 디버그 지문**을 쓰도록 하는 회귀 방지.
 *
 * 핵심: `<pkg>-debug-keystore.properties`(SSOT)가 있으면 loadSigning(debug=true)이 그 값을
 * 그대로 쓴다. 없을 때만 레거시 휴리스틱(release 비번 + debug 키스토어 실제 alias 자동 탐지).
 */
class KeystoreServiceDebugPropertiesTest {

    private val pkg = "com.example.app"
    private fun svc(dir: Path) = KeystoreService(keystoreDir = dir, defaults = KeystoreDefaults())
    private fun tmp() = Files.createTempDirectory("ks-debugprops-test")

    private fun genKeystore(path: Path, alias: String, password: String) {
        val proc = ProcessBuilder(
            "keytool", "-genkeypair", "-keystore", path.toString(),
            "-storetype", "PKCS12", "-alias", alias,
            "-storepass", password, "-keypass", password,
            "-dname", "CN=Test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
        ).redirectErrorStream(true).start()
        proc.inputStream.readAllBytes()
        check(proc.waitFor(30, TimeUnit.SECONDS)) { "keytool timeout" }
        check(proc.exitValue() == 0) { "keytool failed for alias=$alias" }
    }

    /** create() 는 릴리즈 + 디버그 properties 를 모두 만든다. */
    @Test fun `create writes both release and debug properties`() {
        val dir = tmp()
        svc(dir).create(CreateKeystoreRequest(packageName = pkg, password = "testpw123"))
        val debugProps = dir.resolve("$pkg-debug-keystore.properties")
        Files.exists(debugProps).shouldBeTrue()
        val body = Files.readString(debugProps)
        body shouldContain "storeFile=/home/vibe/keystores/$pkg-debug.keystore"
        body shouldContain "keyAlias=key0-debug"
        // loadSigning(debug=true) 가 디버그 properties 를 SSOT 로 참조.
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        s.storeFile shouldBe dir.resolve("$pkg-debug.keystore")
        s.keyAlias shouldBe "key0-debug"
        s.propertiesFile shouldBe debugProps
    }

    /** 디버그 properties 가 있으면 alias/비번이 release 와 완전히 달라도 그대로 사용. */
    @Test fun `debug properties are authoritative over release heuristic`() {
        val dir = tmp()
        // debug 키스토어: 표준 android 비번 + androiddebugkey alias (release 와 무관).
        genKeystore(dir.resolve("$pkg-debug.keystore"), "androiddebugkey", "android")
        genKeystore(dir.resolve("$pkg.keystore"), "key0", "relpw12345")
        Files.writeString(
            dir.resolve("$pkg-keystore.properties"),
            "storeFile=/home/vibe/keystores/$pkg.keystore\nstorePassword=relpw12345\nkeyAlias=key0\nkeyPassword=relpw12345\n",
        )
        Files.writeString(
            dir.resolve("$pkg-debug-keystore.properties"),
            "storeFile=/home/vibe/keystores/$pkg-debug.keystore\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n",
        )
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        // storeFile 의 /home/vibe 경로는 테스트 환경에 없어 debug 키스토어로 fallback.
        s.storeFile shouldBe dir.resolve("$pkg-debug.keystore")
        s.storePassword shouldBe "android"
        s.keyAlias shouldBe "androiddebugkey"
        s.keyPassword shouldBe "android"
    }

    /** 디버그 properties 가 없으면 레거시: debug 키스토어의 단일 key alias 를 자동 탐지. */
    @Test fun `legacy fallback auto-detects sole key alias when heuristic misses`() {
        val dir = tmp()
        // release alias=key0, debug alias=androiddebugkey (key0 도 key0-debug 도 없음).
        genKeystore(dir.resolve("$pkg.keystore"), "key0", "testpw123")
        genKeystore(dir.resolve("$pkg-debug.keystore"), "androiddebugkey", "testpw123")
        Files.writeString(
            dir.resolve("$pkg-keystore.properties"),
            "storeFile=/home/vibe/keystores/$pkg.keystore\nstorePassword=testpw123\nkeyAlias=key0\nkeyPassword=testpw123\n",
        )
        // 디버그 properties 없음 → resolveDebugAlias 가 key0-debug/key0 실패 후 단일 key entry 채택.
        val s = svc(dir).loadSigning(pkg, debug = true)!!
        s.keyAlias shouldBe "androiddebugkey"
    }

    /** delete() 는 디버그 properties 도 함께 지운다. */
    @Test fun `delete removes debug properties too`() {
        val dir = tmp()
        svc(dir).create(CreateKeystoreRequest(packageName = pkg, password = "testpw123"))
        Files.exists(dir.resolve("$pkg-debug-keystore.properties")).shouldBeTrue()
        svc(dir).delete(pkg)
        Files.exists(dir.resolve("$pkg-debug-keystore.properties")) shouldBe false
    }
}
