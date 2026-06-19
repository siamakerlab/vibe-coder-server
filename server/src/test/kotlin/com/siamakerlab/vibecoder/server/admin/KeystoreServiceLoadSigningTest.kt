package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.config.KeystoreDefaults
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v1.144.5 — `loadSigning` 의 variant 별 키스토어 선택 회귀 방지.
 *
 * 디버그 빌드는 `<pkg>-debug.keystore`, 릴리즈/번들은 `<pkg>.keystore` 로 서명돼야 한다.
 * 이전엔 `debug` 인자가 없어 디버그 빌드에도 properties 의 릴리즈 `storeFile` 이 주입돼
 * 디버그 APK 가 릴리즈 키로 서명되던 결함이 있었다. 키스토어 파일은 내용 검증 없이
 * 존재 여부만 보므로(서명 자체는 Gradle/AGP 가 수행) 더미 파일로 충분하다.
 */
class KeystoreServiceLoadSigningTest {

    private val pkg = "com.example.app"
    private fun svc(dir: Path) = KeystoreService(keystoreDir = dir, defaults = KeystoreDefaults())
    private fun tmp() = Files.createTempDirectory("ks-sign-test")

    /** properties 의 storeFile 은 릴리즈 경로로 하드코딩(create() 와 동일). */
    private fun writeProps(dir: Path) {
        Files.writeString(
            dir.resolve("$pkg-keystore.properties"),
            "storeFile=/home/vibe/keystores/$pkg.keystore\nstorePassword=pw\nkeyAlias=key0\nkeyPassword=pw\n",
        )
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
        // 비밀번호/alias 는 release 와 공유(create() 가 동일 정보로 생성).
        s.keyAlias shouldBe "key0"
        s.storePassword shouldBe "pw"
        s.keyPassword shouldBe "pw"
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
