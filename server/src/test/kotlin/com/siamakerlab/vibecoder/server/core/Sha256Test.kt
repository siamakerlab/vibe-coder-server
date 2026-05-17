package com.siamakerlab.vibecoder.server.core

import io.kotest.matchers.shouldBe
import org.junit.Test

class Sha256Test {
    @Test fun `known string`() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        Sha256.hashString("hello") shouldBe "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }

    @Test fun `empty string`() {
        Sha256.hashString("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
