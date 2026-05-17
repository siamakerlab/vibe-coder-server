package com.siamakerlab.vibecoder.server.auth

import com.siamakerlab.vibecoder.server.core.Sha256
import java.security.SecureRandom

/**
 * Token issuance / verification.
 *
 *   - The opaque bearer token is 256-bit URL-safe Base64 (43 chars).
 *   - Only **SHA-256(token)** is stored in the database.
 *   - Verification recomputes the hash and compares against the row.
 */
class TokenService(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun issue(): TokenIssue {
        val raw = ByteArray(32).also(secureRandom::nextBytes)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return TokenIssue(token, Sha256.hashString(token))
    }

    fun hashOf(token: String): String = Sha256.hashString(token)
}

data class TokenIssue(val token: String, val tokenHash: String)
