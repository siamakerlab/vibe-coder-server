package com.siamakerlab.vibecoder.console.install

import java.io.File
import java.security.MessageDigest

object Sha256Verifier {
    fun hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expected: String): Boolean =
        hex(file).equals(expected, ignoreCase = true)
}
