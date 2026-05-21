package com.siamakerlab.vibecoder.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * BCrypt 비밀번호 해시/검증.
 *
 * cost 12 → 현대 CPU 기준 ~250ms / 검증. brute force 비용은 충분히 높이면서
 * 로그인 응답 지연은 사람이 거의 못 느낌.
 */
class PasswordHasher(private val cost: Int = 12) {

    fun hash(plain: CharArray): String {
        require(plain.isNotEmpty()) { "password must not be empty" }
        return BCrypt.withDefaults().hashToString(cost, plain)
    }

    fun hash(plain: String): String = hash(plain.toCharArray())

    fun verify(plain: CharArray, hash: String): Boolean {
        if (plain.isEmpty()) return false
        if (hash.isEmpty()) return false
        return runCatching {
            BCrypt.verifyer().verify(plain, hash.toCharArray()).verified
        }.getOrDefault(false)
    }

    fun verify(plain: String, hash: String): Boolean = verify(plain.toCharArray(), hash)
}

object PasswordPolicy {
    const val MIN_LENGTH = 8
    private val PATTERN = Regex("^(?=.*[A-Za-z])(?=.*\\d).{$MIN_LENGTH,}$")

    /**
     * 정책 검증. 위반 시 한국어 사유 반환, 통과 시 null.
     */
    fun violation(password: String): String? = when {
        password.length < MIN_LENGTH -> "비밀번호는 ${MIN_LENGTH}자 이상이어야 합니다."
        !password.any { it.isLetter() } -> "비밀번호에 영문자가 1개 이상 포함되어야 합니다."
        !password.any { it.isDigit() } -> "비밀번호에 숫자가 1개 이상 포함되어야 합니다."
        !PATTERN.matches(password) -> "비밀번호 형식이 정책에 맞지 않습니다 (영문+숫자 ${MIN_LENGTH}자 이상)."
        else -> null
    }
}

object UsernamePolicy {
    private val PATTERN = Regex("^[a-zA-Z0-9._-]{3,32}$")
    fun violation(username: String): String? = when {
        username.length < 3 -> "사용자명은 3자 이상이어야 합니다."
        username.length > 32 -> "사용자명은 32자 이하여야 합니다."
        !PATTERN.matches(username) -> "사용자명은 영문/숫자/._- 만 사용할 수 있습니다."
        else -> null
    }
}
